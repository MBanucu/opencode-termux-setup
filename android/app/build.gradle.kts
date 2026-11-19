plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.mbanucu.opencodesetup"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mbanucu.opencodesetup"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Reproducible builds.  AGP otherwise embeds a dependency metadata blob that
    // carries the absolute build path, and generates BuildConfig.java from the
    // build environment; neither adds anything the app uses.  With both off, two
    // clean builds of the same commit produce byte-identical APKs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = false
    }

    // No AndroidX, no networking, no analytics: the whole app is the framework
    // Activity and a BroadcastReceiver.
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}
