pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.library" -> useModule("com.android.tools.build:gradle:8.13.0")
                "org.jetbrains.kotlin.android" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
                "com.lagradost.cloudstream3.gradle" -> useModule("com.github.recloudstream:gradle:-SNAPSHOT")
            }
        }
    }
}

rootProject.name = "FLUMMOX-Repo"
include(":BingeCloud")
