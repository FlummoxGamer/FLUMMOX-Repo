plugins {
    id("com.android.application") version "8.5.1" apply false
    id("com.android.library") version "8.5.1" apply false
    kotlin("android") version "1.9.24" apply false
    id("com.lagradost.cloudstream3.provider") version "4.0.0" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
