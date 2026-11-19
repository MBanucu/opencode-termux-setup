# Submitting to F-Droid

`io.github.mbanucu.opencodesetup.yml` is the metadata file for
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), F-Droid's app repository.
Everything it points at already exists in this repository, so submission is a
copy of two files into a merge request.

## How to submit

F-Droid's data lives on GitLab (the GitHub repository is a mirror), and nothing
here has a GitLab token, so the merge request is opened by hand:

1. Register on GitLab and fork `https://gitlab.com/fdroid/fdroiddata`.
2. In the fork, add
   - `metadata/io.github.mbanucu.opencodesetup.yml` (this directory's YAML),
   - `metadata/io.github.mbanucu.opencodesetup/en-US/summary.txt`.
3. Open a merge request against `fdroid/fdroiddata` with the text below.

First-time contributors are also asked to open a request at
<https://gitlab.com/fdroid/rfp/-/issues>.

## Merge request text

```markdown
New app: opencode-setup (io.github.mbanucu.opencodesetup)

Setup companion for opencode in Termux. Upstream ships glibc/musl-only binaries
and Termux is Bionic, so the app detects Termux, names the third-party apt
repository it will add (bd-loser/opencode-bionic), shows the download budget,
asks for consent, and hands one command to Termux through RUN_COMMAND. It then
reports `opencode --version` back.

- Source: https://github.com/MBanucu/opencode-termux-setup (MIT), subdir `android`
- Build: `gradle`, wrapper 8.9, AGP 8.7.3, compileSdk 35, minSdk 24, JDK 17
- No dependencies in the APK; the app is one Activity and one BroadcastReceiver
- No INTERNET permission: Termux performs the download the app describes
- Reproducible release build: dependency metadata and BuildConfig are disabled,
  and CI builds `assembleRelease` twice and compares the APKs byte for byte
- `fastlane/` metadata lives in `android/fastlane/`
```

## What fdroiddata's CI insists on

These are the rules that break naive submissions, learned from the sibling app
in [freebuff-native](https://github.com/MBanucu/freebuff-native-termux):

- `AutoUpdateMode` must be present even when it is `None`.
- The summary is not allowed inside the YAML: it belongs in
  `metadata/<appid>/en-US/summary.txt`.
- Gradle output discovery looks under `<subdir>/build/outputs/apk`, which a
  multi-module AGP project never writes to, so `output:` names `app/build/...`.
- `commit:` must be a tag that exists and builds. Ours is `android-v0.1.0`; check
  with `git ls-remote --tags` before submitting.

## Before clicking submit

- `versionName`/`versionCode` in `android/app/build.gradle.kts` must equal the
  `Builds` entry, and `android/fastlane/metadata/android/en-US/changelogs/1.txt`
  must exist for that `versionCode`. (`1` is the first release.)
- `bash test/fdroid-metadata-test.sh` checks the fields that can be checked
  without fdroidserver: app id agreement, version parity, tag existence, summary
  presence and length limits, changelog presence, and YAML parseability.
- Screenshots are not committed yet. F-Droid does not require them, and a
  fabricated one would be worse than none; `fastlane/metadata/android/en-US/images/phoneScreenshots/`
  is where they go once someone runs the app on a device.

## After it is merged

- `fdroid build io.github.mbanucu.opencodesetup:1` reproduces the build locally.
- A new release is: bump version in `build.gradle.kts`, add
  `changelogs/<versionCode>.txt`, tag `android-v<versionName>`, then bump
  `Builds`, `CurrentVersion` and `CurrentVersionCode` in the YAML.
- Update checks stay manual because this repository also tags the installer and
  APK releases, which an automatic check would mistake for app versions.
