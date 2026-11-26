pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal {
            // Maven Central serves the marker covered by Gradle verification.
            // The Plugin Portal publishes a different POM for this marker.
            content {
                excludeModule("org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Kotlin component of rustls-platform-verifier (Android platform TLS
        // trust for Rust networking). Content-filtered to that group only.
        maven {
            url = uri("https://raw.githubusercontent.com/rustls/rustls-platform-verifier/maven-archive/android-release-support/maven/")
            content { includeGroup("org.rustls") }
        }
    }
}
rootProject.name = "TilecastPlayer"
include(":app")
include(":rtmp-client")
include(":platform-verifier")
