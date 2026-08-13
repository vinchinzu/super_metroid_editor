plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":shared"))
                
                // Material3 for Compose
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                
                // JSON parsing
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
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

compose.desktop {
    application {
        mainClass = "com.supermetroid.editor.MainKt"
        // macOS trackpad pinch-to-zoom (magnification gesture)
        jvmArgs(
            "--add-exports", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
            "--add-opens", "java.desktop/com.apple.eawt.event=ALL-UNNAMED"
        )
        nativeDistributions {
            includeAllModules = true

            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "Super Metroid Editor"
            packageVersion = "1.0.0"
            description = "Super Metroid ROM editor — tile, PLM, enemy, and patch editing"
            copyright = "© 2025 Super Metroid Editor"

            macOS {
                bundleID = "com.supermetroid.editor"
                iconFile.set(project.file("src/jvmMain/resources/macos/app_icon.icns"))
            }

            windows {
                iconFile.set(project.file("src/jvmMain/resources/windows/app_icon.ico"))
                menuGroup = "Super Metroid Editor"
                upgradeUuid = "b3a7f2c1-8d4e-4a9f-b6c5-1e3d7f8a2b9c"
            }

            linux {
                packageName = "supermetroideditor"
                iconFile.set(project.file("src/jvmMain/resources/linux/app_icon.png"))
                shortcut = true
                menuGroup = "Games"
            }
        }
    }
}
