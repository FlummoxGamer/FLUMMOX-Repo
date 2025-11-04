plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    compileSdk = 34
    namespace = "com.flummox.ghoststream"

    defaultConfig {
        applicationId = "com.flummox.ghoststream"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

repositories {
    mavenCentral()
    maven {
        name = "cloudstream"
        url = uri("https://maven.pkg.github.com/recloudstream/cloudstream")
        credentials {
            username = System.getenv("CS_USERNAME")
            password = System.getenv("CS_TOKEN")
        }
    }
}

dependencies {
    implementation("com.lagacy:ext-api:master-SNAPSHOT")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jsoup:jsoup:1.16.1")
}
