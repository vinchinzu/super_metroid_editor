plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
}

// ─── Native libspc build ────────────────────────────────────────────────
// Compiles blargg's snes_spc (tools/snes_spc submodule) into a shared
// library and places it in the JNA resource path for the current platform.

fun detectJnaPlatformDir(): String {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "darwin"
        osName.contains("win") -> "win32"
        osName.contains("linux") -> "linux"
        else -> error("Unsupported OS: $osName")
    }
    val cpu = when {
        arch == "aarch64" || arch == "arm64" -> "aarch64"
        arch == "amd64" || arch == "x86_64" -> "x86-64"
        else -> error("Unsupported arch: $arch")
    }
    return "$os-$cpu"
}

fun detectLibName(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("mac") || osName.contains("darwin") -> "libspc.dylib"
        osName.contains("win") -> "spc.dll"
        else -> "libspc.so"
    }
}

val spcSourceDir = rootProject.file("tools/snes_spc")
val jnaPlatform = detectJnaPlatformDir()
val libName = detectLibName()
val nativeOutputDir = file("src/jvmMain/resources/$jnaPlatform")

val buildNativeSpc = tasks.register("buildNativeSpc") {
    description = "Compile libspc shared library for the current platform"
    group = "build"

    inputs.dir(spcSourceDir.resolve("snes_spc"))
    inputs.file(spcSourceDir.resolve("Makefile"))
    outputs.file(nativeOutputDir.resolve(libName))

    doLast {
        if (!spcSourceDir.resolve("Makefile").exists()) {
            logger.warn("snes_spc submodule not checked out — skipping native build")
            return@doLast
        }

        val osName = System.getProperty("os.name").lowercase()
        val dynlibExt = when {
            osName.contains("mac") || osName.contains("darwin") -> ".dylib"
            osName.contains("win") -> ".dll"
            else -> ".so"
        }

        exec {
            workingDir = spcSourceDir
            commandLine("make", "clean")
            isIgnoreExitValue = true
        }

        exec {
            workingDir = spcSourceDir
            val args = mutableListOf("make", "DYNLIB_EXT=$dynlibExt")
            if (osName.contains("win")) {
                args.add("DYNLIB_PREFIX=")
            }
            commandLine(args)
        }

        nativeOutputDir.mkdirs()
        val builtLib = spcSourceDir.resolve("${if (osName.contains("win")) "" else "lib"}spc$dynlibExt")
        builtLib.copyTo(nativeOutputDir.resolve(libName), overwrite = true)
        logger.lifecycle("Built $libName -> $nativeOutputDir/$libName")
    }
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }
        
        val jvmMain by getting {
            dependencies {
                implementation("net.java.dev.jna:jna:5.14.0")
            }
        }
        
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.0")
            }
        }
    }
}

tasks.named("jvmProcessResources") {
    dependsOn(buildNativeSpc)
}
