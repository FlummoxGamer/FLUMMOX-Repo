// Root level build.gradle.kts - FINAL WORKING VERSION

// 1. Define where to find the *plugins* using the reliable buildscript method
buildscript {
    repositories {
        google()
        mavenCentral()
        // CRITICAL: JitPack is where the CloudStream plugin lives
        maven("https://jitpack.io")
    }
    dependencies {
        // These are the actual plugin JARs placed on the classpath
        classpath("com.lagradost.cloudstream3:provider:1.4.2")
        classpath(kotlin("gradle-plugin", version = "1.9.24"))
        classpath("com.android.tools.build:gradle:8.5.1")
    }
}

// 2. Define the repositories for *dependencies* (libraries like material, okhttp)
allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
