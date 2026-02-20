plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
}

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }
    }
}

tasks.register<JavaExec>("runCli") {
    val cliArgs: String = project.findProperty("args") as? String ?: ""
    mainClass.set("com.supermetroid.editor.cli.CliMainKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    // Parse args respecting quoted strings
    this.args = parseCliArgs(cliArgs)
}

fun parseCliArgs(input: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuote = false
    var quoteChar = ' '
    for (c in input) {
        when {
            !inQuote && (c == '"' || c == '\'') -> { inQuote = true; quoteChar = c }
            inQuote && c == quoteChar -> inQuote = false
            !inQuote && c == ' ' -> {
                if (current.isNotEmpty()) { result.add(current.toString()); current.clear() }
            }
            else -> current.append(c)
        }
    }
    if (current.isNotEmpty()) result.add(current.toString())
    return result
}
