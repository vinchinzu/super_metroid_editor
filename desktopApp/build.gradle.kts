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
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "Super Metroid Editor"
            packageVersion = "1.0.0"
            
            macOS {
                bundleID = "com.supermetroid.editor"
                iconFile.set(project.file("src/jvmMain/resources/macos/app_icon.icns"))
            }
        }
    }
}
