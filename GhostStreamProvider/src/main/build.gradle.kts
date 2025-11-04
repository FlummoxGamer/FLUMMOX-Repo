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
    
    // Add these dependencies for HTTP requests and HTML parsing
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jsoup:jsoup:1.16.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// Create a fat JAR with all dependencies
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("GhostStreamProvider")
    archiveVersion.set("1.0.0")
    
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
