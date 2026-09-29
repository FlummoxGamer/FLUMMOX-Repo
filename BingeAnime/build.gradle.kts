plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.flummox.bingeanime"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        val pluginVer = (project.findProperty("bingeanime_version") as? String)?.toIntOrNull() ?: 1
        buildConfigField("int", "PLUGIN_VERSION", "$pluginVer")
        val isDev = (System.getenv("IS_DEV_BUILD") ?: "false").equals("true", ignoreCase = true)
        buildConfigField("boolean", "IS_DEV_BUILD", "$isDev")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

cloudstream {
    description = "Anime streaming — multi-source, AniList catalog"
    authors = listOf("FLUMMOX")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/FlummoxGamer/FLUMMOX-Repo/main/BingeAnime/icon.png"
    version = (project.findProperty("bingeanime_version") as? String)?.toIntOrNull() ?: 1
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
