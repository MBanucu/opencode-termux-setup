// repo.maven.apache.org answers 429 Too Many Requests to the shared IPs of
// hosted CI runners, which fails the build on artifacts that plainly exist.
// Google's mirror of Maven Central is the same content from a different edge;
// mavenCentral() stays as the fallback.
//
// pluginManagement must stay the first block in this file, so the mirror URL is
// written out rather than hoisted into a val above it.
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
        }
        mavenCentral()
    }
}

rootProject.name = "opencode-setup"
include(":app")
