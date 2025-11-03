allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// REMOVE this entire subprojects block - it's causing conflicts
// subprojects {
//     repositories {
//         maven {
//             name = "cloudstream"
//             url = uri("https://api.github.com/repos/recloudstream/cloudstream-3/packages/maven")
//             credentials {
//                 username = System.getenv("GITHUB_ACTOR")
//                 password = System.getenv("GITHUB_TOKEN")
//             }
//         }
//     }
// }
