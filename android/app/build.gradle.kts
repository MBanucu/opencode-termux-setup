plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.mbanucu.opencodesetup"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.mbanucu.opencodesetup"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
        // The instrumented end-to-end tests drive the app through UI Automator,
        // which is the accessibility event stream, not screenshots.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
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

// The instrumented dependencies are the only ones in the build, so they are the
// only configuration worth locking.  The lockfile pins every transitive module
// that androidTest resolves, which is what makes an offline build use the same
// set as CI.  It does not touch the release APK, which has no dependencies.
dependencyLocking {
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}
configurations.configureEach {
    if (name.endsWith("AndroidTestCompileClasspath") ||
        name.endsWith("AndroidTestRuntimeClasspath")
    ) {
        resolutionStrategy.activateDependencyLocking()
    }
}

// Test-only, and never packaged: UI Automator and the runner live in the
// separate androidTest APK.
dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
