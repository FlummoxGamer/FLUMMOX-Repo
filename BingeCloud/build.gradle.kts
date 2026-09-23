plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.flummox.bingecloud"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        val tmdbKey = (System.getenv("TMDB_API_KEY") ?: "").trim()
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        val tvdbKey = (System.getenv("TVDB_API_KEY") ?: "").trim()
        buildConfigField("String", "TVDB_API_KEY", "\"$tvdbKey\"")
        val pluginVer = (project.findProperty("bingecloud_version") as? String)?.toIntOrNull() ?: 1
        buildConfigField("int", "PLUGIN_VERSION", "$pluginVer")
    }
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
    description = "TV Series, Movies, Anime"
    authors = listOf("FLUMMOX")
    status = 1
    tvTypes = listOf("Movies","TV Series","Anime")
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/FlummoxGamer/FLUMMOX-Repo/main/BingeCloud/icon.png"
    // Read from gradle.properties → single source of truth.
    // Bump `bingecloud_version` there; CI reads it and passes to patch_plugins.py.
    version = (project.findProperty("bingecloud_version") as? String)?.toIntOrNull() ?: 1
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
