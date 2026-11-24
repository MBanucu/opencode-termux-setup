# Building the Android app on Termux

This is the on-device counterpart to `.github/workflows/android.yml`: what a
fresh checkout needs on Termux, how the wrapper and the test-only dependencies
are pinned, and which toolchain this repository is on.

The app in `android/` builds on this phone with the JDK and the Android SDK that
are already installed. The two traps are that Gradle cannot see the SDK and that
the Maven `aapt2` is a glibc binary which cannot run on bionic.

## One command

```bash
# From the repository root:
tools/termux-build.sh                       # checkBuildEnv + assembleDebug
tools/termux-build.sh --offline assembleDebug assembleDebugAndroidTest
tools/termux-build.sh lintDebug             # any Gradle task, same environment
tools/termux-build.sh --check               # print the resolved SDK/aapt2, no build
```

The script:

1. reads `compileSdk` and `buildTools` from `android/gradle/libs.versions.toml`;
2. uses `sdk.dir` from `android/local.properties` when present, then
   `$ANDROID_HOME`, then `$ANDROID_SDK_ROOT`, then the Termux default
   `$HOME/android-sdk`;
3. validates that `platforms/android-<compileSdk>/android.jar` and
   `build-tools/<buildTools>` exist;
4. passes `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2` when Termux's
   bionic aapt2 is available and no override is configured in
   `~/.gradle/gradle.properties` or `AAPT2`;
5. passes `-Dandroid.home` (AGP's documented system property) and runs
   `android/gradlew` with `checkBuildEnv` plus the arguments you gave, defaulting
   to `assembleDebug`.

The explicit `-Dandroid.home` also survives a Gradle daemon that was started
earlier without `ANDROID_HOME`; Gradle does not refresh a reused daemon's
environment. The script never writes `local.properties`, so a clean checkout
stays clean.

## Building without the script

```bash
export ANDROID_HOME="$HOME/android-sdk"
cd android
./gradlew --offline assembleDebug
```

`-Dandroid.home="$HOME/android-sdk"` is an equivalent AGP system property and is
what `tools/termux-build.sh` passes, so a reused daemon cannot hide it.

If neither `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `-Dandroid.home` nor `sdk.dir` is
set, Gradle stops while settings are evaluated with one message:

```
Android SDK not found: set ANDROID_HOME, or sdk.dir in android/local.properties.

On Termux, use the documented wrapper (it finds $HOME/android-sdk and
the bionic aapt2 for you):

    tools/termux-build.sh assembleDebug
...
```

For an IDE or a local file, copy `android/local.properties.example` to
`android/local.properties` and set `sdk.dir`. `local.properties` is gitignored
and must never be committed.

## What was verified here

Verified on 2026-11-24 on Termux aarch64 / Android 15, unrooted:

| item | value |
| --- | --- |
| Gradle | 8.9 (wrapper; `distributionSha256Sum` checked against the downloaded archive) |
| AGP | 8.7.3 (from `gradle/libs.versions.toml`) |
| JDK | OpenJDK 21.0.13 |
| SDK | `$HOME/android-sdk`, platform 35, build-tools 35.0.0 |
| aapt2 | Termux 16.0.0.4, passed as `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2` |
| clean checkout | `env -u ANDROID_HOME -u ANDROID_SDK_ROOT tools/termux-build.sh --offline assembleDebug assembleDebugAndroidTest` passed with no `local.properties` |
| missing SDK | `tools/termux-build.sh --check` and Gradle settings each exit 1 with one actionable block |
| missing aapt2 | the script exits 1 with `pkg install aapt2` |
| `assembleDebug assembleDebugAndroidTest` | `BUILD SUCCESSFUL` |
| `lintDebug` | `BUILD SUCCESSFUL` (required in CI) |
| `bash test/fdroid-metadata-test.sh` | all metadata checks passed |

## Requirements

- JDK 17 or newer. Termux's `openjdk-21` is what this project is tested with;
  the app compiles to Java 17 bytecode.
- Android SDK with `platforms/android-35` and `build-tools/35.0.0`, the levels
  the catalog and CI use.
- Termux's bionic `aapt2`: `pkg install aapt2` (Google ships aapt2 for x86_64
  only).
- Network on the first run, or an already populated `~/.gradle` cache and
  `--offline`.

## Environment failures

`checkBuildEnv` prints one block instead of an AGP stack trace:

```
The Android build environment is not ready:
  - no Android SDK: export ANDROID_HOME=/path/to/android-sdk, or copy local.properties.example to local.properties and set sdk.dir
  - Android platform 35 is not installed in ...; run: sdkmanager "platforms;android-35"
  - Android build-tools 35.0.0 are not installed in ...; run: sdkmanager "build-tools;35.0.0"
  - no aapt2 override on Termux; add this to ~/.gradle/gradle.properties ...
  - the aapt2 override ... is not executable; run chmod +x ...
See docs/termux-build.md for a working Termux setup.
```

`tools/termux-build.sh` performs the same SDK/platform/build-tools checks before
it starts Gradle, and refuses to run on Termux when the bionic aapt2 is missing:

```
error: Termux's bionic aapt2 is missing. Install it with: pkg install aapt2
```

## Toolchain decision: stay on AGP 8.7.3 / Gradle 8.9

The sibling Android projects use AGP 9.5.2 / Gradle 9.9.2. That pair was tried on
this phone on 2026-11-24, in a scratch copy, with the installed Gradle 9.9.2 and
Build Tools 36.0.0:

| task | result |
| --- | --- |
| `assembleDebug` | passed |
| `assembleDebugAndroidTest` | passed |
| `lintDebug` | passed |
| `compileSdk` | 35 |
| build time | `BUILD SUCCESSFUL in 28s` (warm dependency cache) |

It is **not** adopted yet, deliberately:

- AGP 9.5 requires SDK Build Tools 36.0.0. F-Droid's buildserver availability
  for that version has not been verified, and fdroiddata builds a tagged commit,
  so a failed build there affects the published listing. This repository must
  not change the toolchain on an unverified release path.
- AGP 9.5.2 on Gradle 9.9.2 emits `Configuration.setVisible` deprecation
  warnings. The 8.7.3 / 8.9 pair has no project-owned Gradle 10 deprecations.
- The app is dependency-free Java, so the upgrade buys nothing here.
- The androidTest lockfile is generated by the active AGP line: AGP 9 adds its
  built-in Kotlin stdlib to the androidTest classpath, so its lockfile differs
  from the 8.7.3 one. An upgrade must regenerate the lockfile as part of the
  same change.

The 8.9 wrapper distribution is cached on this phone, so offline builds work
today; its checksum is pinned below. The alternative remains one follow-up: bump
`agp` in the catalog, the wrapper URL/checksum, the CI build-tools version and
the fdroiddata notes together, regenerate the lockfile, then re-run the
reproducible release job and `fdroid build` with Build Tools 36.0.0.

## Wrapper integrity

`android/gradle/wrapper/gradle-wrapper.properties` pins:

```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
distributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
```

That is the checksum published at
<https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256>. The
wrapper itself refuses a distribution that does not match.

CI runs both halves:

- `gradle/actions/wrapper-validation@v4` checks the committed wrapper JAR
  against Gradle's published checksums;
- `bash tools/check-wrapper.sh --online` checks the URL/hash shape, the JAR
  contents, and compares `distributionSha256Sum` with Gradle's published
  `.sha256`.

The script works offline too (`tools/check-wrapper.sh`), which is what a phone
without network can use.

## Dependency boundary

Every version the Gradle build uses has one home, `android/gradle/libs.versions.toml`:
AGP, `compileSdk`/`minSdk`/`targetSdk`, `buildTools`, the Java bytecode level,
`versionCode`/`versionName`, and the four `androidx.test` artifacts. The plugin
alias is the only plugin entry; no version literals remain in the build files.

The app APK has no dependencies. The four `androidx.test` libraries are
`androidTestImplementation` only. They are locked with Gradle dependency locking:

- `android/app/gradle.lockfile` pins the `debugAndroidTestCompileClasspath` and
  `debugAndroidTestRuntimeClasspath` module versions, including transitives;
- `lockMode = STRICT`, so a version bump or a new transitive fails the build
  until the lockfile is regenerated with
  `./gradlew --write-locks assembleDebugAndroidTest`;
- the release APK is untouched, so F-Droid's `assembleRelease` does not need the
  lockfile.

Dependency verification metadata is **not** committed. `--write-verification-metadata`
records the AGP plugin and its platform-specific build tools, and a file
generated on Termux records a different platform set from F-Droid's x86_64
buildserver; a global metadata file would then fail an otherwise-valid F-Droid
build with checksum errors. The lockfile pins versions portably; checksums of
the four test artifacts can still be audited locally:

```bash
cd android
./gradlew --write-verification-metadata sha256 assembleDebugAndroidTest
```

Do not commit the generated file without checking it against the F-Droid
buildserver first.

## Configuration cache and deprecations

`org.gradle.configuration-cache=true` and `org.gradle.caching=true` are enabled
in `android/gradle.properties`. CI keeps `--no-daemon`.

Measured on this phone (Termux aarch64, Android 15, JDK 21), with the wrapper and
`--offline`:

| run | command | result |
| --- | --- | --- |
| cold, no config cache, no `build/` | `./gradlew --offline assembleDebug assembleDebugAndroidTest` | `BUILD SUCCESSFUL in 54s`, cache entry stored, 55s wall |
| warm, cache reused | same command | `BUILD SUCCESSFUL in 2s`, cache entry reused, 3s wall |
| lint | `./gradlew --offline lintDebug` | `BUILD SUCCESSFUL in 13s` |

`--warning-mode all` reports no project-owned Gradle 10 deprecations on the
8.7.3 / 8.9 line. The remaining line is AGP's
`android.aapt2FromMavenOverride is experimental` notice, which describes the
documented Termux workaround rather than this project's build logic.

## Lint is required

`lintDebug` is part of the CI build job, not advisory. Two errors that the
advisory job had been hiding were fixed:

- `android:windowLightNavigationBar` moved to `values-v27/themes.xml`, because it
  exists only from API 27 while `minSdk` is 24;
- the resize receiver's pre-API-33 `registerReceiver` call is annotated, because
  the exported-state flag does not exist before API 33.

Warnings remain warnings (`warningsAsErrors = false`); a new lint error fails the
build.

## Termux smoke gate

`tools/termux-build.sh` is the documented smoke build and the gate a contributor
runs before pushing. CI also has a `Termux smoke build` job in
`.github/workflows/android.yml`, but it is opt-in until a runner is registered:

- it runs on `[self-hosted, termux]`;
- it is enabled by setting the repository variable
  `TERMUX_RUNNER_ENABLED=true`;
- without a runner the job is skipped, so it cannot claim a Termux build passed.

The Ubuntu job cannot exercise the bionic aapt2 or the on-phone daemon, so it
does not replace this gate. Registering a Termux runner and making the job
required is the remaining work.

## What this does not do

- It does not publish an APK or trigger F-Droid.
- It does not upgrade to AGP 9.5.2; see the toolchain decision above.
- It does not commit dependency verification metadata; see the dependency
  boundary above.
