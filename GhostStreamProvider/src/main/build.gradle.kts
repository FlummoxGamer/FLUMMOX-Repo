plugins {
    `java-library`
    kotlin("jvm") version "1.9.0"
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
    implementation(kotlin("stdlib-jdk8"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
