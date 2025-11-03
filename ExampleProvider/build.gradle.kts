// Provider Module build.gradle.kts - FINAL WORKING CONFIGURATION

plugins {
    // Apply the plugins configured in the root build.gradle.kts
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.provider")
}

android {
    namespace = "com.example.provider"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    // The core CloudStream app library (Version 4.0.0 is latest stable on Maven Central)
    implementation("com.lagradost.cloudstream3:app:4.0.0") 

    // Standard Android libraries
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// This metadata section must be outside the 'android' block.
version = 1

cloudstream {
    description = "A multi-source video provider"
    authors = listOf("FlummoxGamer")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    requiresResources = false
    language = "en"
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Korduene_Logo.png"
}
