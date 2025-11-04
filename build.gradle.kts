@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.konan.properties.Properties

version = 1

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        android.buildFeatures.buildConfig=true
    }
}

cloudstream {
    language = "en"
    description = "Multi-source provider with AllMovieLand and more"
    authors = listOf("FlummoxGamer")

    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Cartoon"
    )

    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
