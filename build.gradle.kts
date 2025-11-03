allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// CRITICAL: This subprojects block is for DEPENDENCIES (like ext-api:master-SNAPSHOT)
subprojects {
    repositories {
        maven {
            name = "cloudstream"
            url = uri("https://api.github.com/repos/recloudstream/cloudstream-3/packages/maven")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
