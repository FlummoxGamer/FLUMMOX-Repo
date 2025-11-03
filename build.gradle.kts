// Root level build.gradle.kts - FINAL FIX: Using buildscript to force plugin resolution

// 1. Define where to find the *plugins*
buildscript {
    repositories {
        google()
        mavenCentral()
        // CRITICAL FIX: The plugin must be found here via buildscript
        maven("https://jitpack.io")
    }
    dependencies {
        // Apply the CloudStream provider plugin as a dependency in the buildscript classpath
        classpath("com.lagradost.cloudstream3:provider:1.4.2")
        // Also required: the Kotlin Gradle plugin
        classpath(kotlin("gradle-plugin", version = "1.9.24"))
    }
}

// 2. Define the repositories for *dependencies*
allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
