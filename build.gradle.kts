// Root level build.gradle.kts - defines global versions.

plugins {
    // Standard Android and Kotlin plugins
    id("com.android.application") version "8.5.1" apply false
    id("com.android.library") version "8.5.1" apply false
    kotlin("android") version "1.9.24" apply false
    
    // CloudStream provider plugin
    id("com.lagradost.cloudstream3.provider") version "4.0.0" apply false
}
// This section defines repositories for dependencies (libraries used by the code).
// Must be placed below the plugins block.
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
