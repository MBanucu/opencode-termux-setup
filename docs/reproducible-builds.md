# Reproducible builds

F-Droid publishes the APK it builds itself, and only tags a release as
reproducible when a build from the same source yields the same bytes. This
document records what the app does about that, and how it is checked.

## What is turned off, and why

Two AGP defaults make a build depend on the machine rather than on the source:

| default | what it injects | setting now |
|---|---|---|
| `dependenciesInfo.includeInApk` | a metadata blob containing the **absolute build path** and the dependency list | `includeInApk = false`, `includeInBundle = false` |
| `buildFeatures.buildConfig` | generated `BuildConfig.java` derived from the build environment | `buildConfig = false` |

Both live in `android/app/build.gradle.kts`. Neither adds anything the app uses:
there is no dependency to report and no `BuildConfig` field in the source.

Everything else is already deterministic in this project:

- **No dependencies in the APK**, so no resolution order or version drift can
  enter it. (The androidTest APK has dependencies, pinned by
  `android/app/gradle.lockfile`; it never enters the release APK.)
- **One source set**, no flavours, no splits, no native code.
- **Pinned toolchain**: Gradle wrapper 8.9, AGP 8.7.3, `compileSdk 35`,
  `targetSdk 35`, `minSdk 24`, Java 17. The AGP and SDK levels live in
  `android/gradle/libs.versions.toml`.
- **Fixed zip timestamps**: AGP writes `1981-01-01` entries, which is why the
  committed APK lists that date for every file.
- **No build-time version stamping**: `versionCode`/`versionName` come from
  `android/gradle/libs.versions.toml` at configuration time, and there is no
  `git describe` or clock read anywhere in the build.

## How it is checked

CI job `reproducible` in `.github/workflows/android.yml` builds `assembleRelease`
twice, with the build cache disabled and `--rerun-tasks`, then compares the two
APKs byte for byte:

```bash
sha256sum "$first" "$second"
cmp "$first" "$second"
```

The job fails on any difference, so a setting that reintroduces variation cannot
land unnoticed. The unsigned release APK is the comparison target because the
debug APK carries a per-machine debug signature.

Locally, with an Android SDK and JDK 17 available:

```bash
cd android
./gradlew --no-daemon --no-build-cache --rerun-tasks clean assembleRelease
cp app/build/outputs/apk/release/*.apk /tmp/first.apk
./gradlew --no-daemon --no-build-cache --rerun-tasks clean assembleRelease
cmp /tmp/first.apk app/build/outputs/apk/release/*.apk && echo reproducible
```

## What this does and does not prove

Two clean builds in the same CI image are **the same toolchain** — that proves
the build has no hidden dependency on time, path, cache state or task order. It
does not by itself prove byte equality with F-Droid's Debian container, which
uses its own JDK and Android SDK build-tools. That cross-check is F-Droid's:
their `reproducible` verdict compares their build against a build of the same tag,
and `AllowedAPKSigningKeys` in the metadata is where their signing certificate is
pinned once that verdict exists.

If the two ever disagree, the likely culprits in order are the Android SDK
build-tools patch level (`aapt2`) and the JDK, both of which the metadata fixes by
building with `gradle: - yes` and the pinned wrapper.
