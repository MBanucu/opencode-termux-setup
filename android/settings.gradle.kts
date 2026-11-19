// repo.maven.apache.org answers 429 Too Many Requests to the shared IPs of
// hosted CI runners, which fails the build on artifacts that plainly exist.
// Google's mirror of Maven Central is the same content from a different edge;
// mavenCentral() stays as the fallback.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        google()
        maven(centralMirror) { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        maven(centralMirror) { name = "MavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "opencode-setup"
include(":app")
