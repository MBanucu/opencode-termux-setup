// The Android Gradle Plugin is the only build-time dependency; the app itself
// has none, so an F-Droid build needs nothing but the SDK.  Versions live in
// gradle/libs.versions.toml.
import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application) apply false
}

/**
 * Fails before a compile with one message instead of an AGP stack trace.
 *
 * AGP reports a missing SDK during configuration, so settings.gradle.kts checks
 * that first.  This task covers the rest - the JDK, the compile platform, the
 * build-tools directory AGP will use, and the bionic aapt2 that Termux needs -
 * and is also what `tools/termux-build.sh` runs before any build task.
 */
@DisableCachingByDefault(because = "Reads the machine's build environment")
abstract class CheckBuildEnv : DefaultTask() {
    @get:Input
    abstract val compileSdk: Property<Int>

    @get:Input
    abstract val buildTools: Property<String>

    @get:Input
    abstract val javaSpecificationVersion: Property<String>

    @get:Input
    abstract val osArch: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    @get:Optional
    abstract val termuxPrefix: Property<String>

    @get:Input
    @get:Optional
    abstract val sdkFromEnvironment: Property<String>

    @get:Input
    @get:Optional
    abstract val sdkFromProperty: Property<String>

    @get:Input
    @get:Optional
    abstract val aapt2Override: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localProperties: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val jdk = javaSpecificationVersion.get()
            .removePrefix("1.")
            .substringBefore('.')
            .toIntOrNull() ?: 0
        if (jdk < 17) {
            problems += "JDK $jdk is too old; use JDK 17 or newer " +
                "(Termux: pkg install openjdk-21)"
        }

        val localPropertiesFile = localProperties.files.firstOrNull()?.takeIf { it.isFile }
        val localSdk = if (localPropertiesFile != null) {
            Properties().apply {
                localPropertiesFile.inputStream().use { load(it) }
            }.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val sdk = localSdk
            ?: sdkFromProperty.orNull?.takeIf { it.isNotBlank() }
            ?: sdkFromEnvironment.orNull?.takeIf { it.isNotBlank() }
        val platform = compileSdk.get()
        if (sdk == null) {
            problems += "no Android SDK: export ANDROID_HOME=/path/to/android-sdk, " +
                "or copy local.properties.example to local.properties and set sdk.dir"
        } else if (!File(sdk).isDirectory) {
            problems += "the Android SDK at $sdk does not exist; set ANDROID_HOME or " +
                "local.properties sdk.dir to a real SDK directory"
        } else {
            val androidJar = File(sdk, "platforms/android-$platform/android.jar")
            if (!androidJar.isFile) {
                problems += "Android platform $platform is not installed in $sdk; run " +
                    "sdkmanager \"platforms;android-$platform\""
            }
            val tools = File(sdk, "build-tools/${buildTools.get()}")
            if (!tools.isDirectory) {
                problems += "Android build-tools ${buildTools.get()} are not installed in " +
                    "$sdk; run sdkmanager \"build-tools;${buildTools.get()}\""
            }
        }

        val aapt2 = aapt2Override.orNull?.takeIf { it.isNotBlank() }
        if (aapt2 == null) {
            // Termux's aapt2 is a bionic binary; AGP's arm64/x86_64 downloads
            // are glibc builds and do not run there.  Other arm64 hosts (macOS,
            // glibc Linux) can use AGP's own aapt2.
            val onTermux = termuxPrefix.orNull?.contains("com.termux") == true ||
                javaHome.get().contains("com.termux")
            val arm64 = osArch.get().lowercase().let { it == "aarch64" || it == "arm64" }
            if (onTermux && arm64) {
                problems += "no aapt2 override on Termux; add this to " +
                    "~/.gradle/gradle.properties (Termux's aapt2 is bionic, AGP's is glibc): " +
                    "android.aapt2FromMavenOverride=\$PREFIX/bin/aapt2"
            }
        } else {
            val binary = File(aapt2)
            if (!binary.isFile) {
                problems += "android.aapt2FromMavenOverride points at $aapt2, which is not a " +
                    "file; use \$PREFIX/bin/aapt2 on Termux"
            } else if (!binary.canExecute()) {
                problems += "the aapt2 override $aapt2 is not executable; run chmod +x $aapt2"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The Android build environment is not ready:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nSee docs/termux-build.md for a working Termux setup."
            )
        }
        logger.lifecycle(
            "build environment: JDK $jdk, SDK $sdk, platform $platform, " +
                "aapt2 ${aapt2 ?: "AGP's own"}"
        )
    }
}

tasks.register<CheckBuildEnv>("checkBuildEnv") {
    group = "verification"
    description = "Fails early, with one message, when the JDK, SDK, platform or aapt2 is missing."
    compileSdk.set(libs.versions.compileSdk.map(String::toInt))
    buildTools.set(libs.versions.buildTools)
    javaSpecificationVersion.set(providers.systemProperty("java.specification.version"))
    osArch.set(providers.systemProperty("os.arch"))
    javaHome.set(providers.systemProperty("java.home"))
    termuxPrefix.set(providers.environmentVariable("PREFIX"))
    sdkFromEnvironment.set(
        providers.environmentVariable("ANDROID_HOME").filter { it.isNotBlank() }
            .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    )
    sdkFromProperty.set(providers.systemProperty("android.home"))
    aapt2Override.set(providers.gradleProperty("android.aapt2FromMavenOverride"))
    localProperties.from(layout.projectDirectory.file("local.properties"))
}
