// Only repositories fdroidserver recognises may appear here.  Its source scanner
// rejects unknown Maven hosts and aborts before Gradle runs, so a mirror of Maven
// Central cannot be declared even though it would avoid the 429s that hosted CI
// egress IPs hit.  The retry settings in gradle.properties are what cover that
// case instead.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "opencode-setup"
include(":app")
