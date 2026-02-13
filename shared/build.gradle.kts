plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
}

kotlin {
    jvm()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }
        
        val jvmMain by getting {
            dependencies {
                // Add JVM-specific dependencies here
            }
        }
    }
}
