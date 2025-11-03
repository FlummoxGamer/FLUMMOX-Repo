pluginManagement {
    repositories {
        google()
        mavenCentral()
        // FIX: Use the correct GitHub Packages URL
        maven {
            name = "cloudstream"
            url = uri("https://maven.pkg.github.com/recloudstream/cloudstream")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // FIX: Add the dependency repository here too
        maven {
            name = "cloudstream"
            url = uri("https://maven.pkg.github.com/recloudstream/cloudstream")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "FLUMMOX-Repo"
include("ExampleProvider")
