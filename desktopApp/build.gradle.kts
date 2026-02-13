plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
    }
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":shared"))
                
                // Material3 for Compose
                implementation(compose.material3)
                
                // JSON parsing
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.supermetroid.editor.MainKt"
        
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "Super Metroid Editor"
            packageVersion = "1.0.0"
            
            macOS {
                bundleID = "com.supermetroid.editor"
            }
        }
    }
}
