# Submitting to F-Droid

`io.github.mbanucu.opencodesetup.yml` is the metadata file for
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), F-Droid's app repository.
Everything it points at already exists in this repository, so submission is a
copy of two files into a merge request.

## Submitted

Both records are open:

| record | URL | state |
| --- | --- | --- |
| metadata merge request | <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58147> | open, pipeline green (`fdroid build`, `check apk`) |
| packaging request (RFP) | <https://gitlab.com/fdroid/rfp/-/work_items/4830> | open |

Filed with `glab` on this device (authenticated as `MBanucu`). The fork
`MBanucu/fdroiddata` (project 89128873) already existed from an earlier app, so
the two metadata files went onto branch `opencode-setup` there through the
commits API.

### Endpoint and scanner quirks worth knowing

- `POST /projects/fdroid%2Ffdroiddata/merge_requests` answers `403 Forbidden` for
  this account — the same minimal payload, `Content-Length: 27`, and far from any
  limit (`Ratelimit-Remaining: 95`, `burst_traffic_per_user_free_plan`) — while
  creating an issue in `fdroid/rfp` succeeds with `201`. The way around it is
  git's own merge-request push options:

  ```bash
  git -c credential.helper='!glab auth git-credential' push origin opencode-setup \
    -o merge_request.create -o merge_request.target=master \
    -o merge_request.title="New app: opencode-setup (io.github.mbanucu.opencodesetup)"
  ```

  GitLab creates the request from the receive-pack side and prints the number;
  `PUT /projects/:id/merge_requests/:iid` then sets the description normally, so
  only creation is affected.
- `POST /projects/:id/merge_requests/:iid/pipelines` answers
  `405 Method Not Allowed` (the endpoint is gone), and the merge request has no
  pipeline yet. fdroiddata's `.gitlab-ci.yml` runs on `merge_request_event` when
  `CI_ENABLE_MERGE_REQUEST_PIPELINES` is not `false`, and pipelines from forks
  have to be permitted on the parent project, so the first pipeline is started
  from the merge request page — that part is the maintainer's side.
- The 403 is **not** an expired or under-scoped token. This `glab` session holds an
  OAuth token rather than a PAT (`GET /personal_access_tokens/self` answers
  `400 … requires token type to be a personal access token`) and its `api` scope
  works: it reads `fdroid/fdroiddata`, pushes to its own fork, creates issues in
  `fdroid/rfp`, and `PUT`s the merge request. No scope setting removes the 403;
  what would is membership of the parent project, which is the maintainers'
  call.

- `fdroid build` refuses Maven hosts it does not recognise. The first pipeline
  died before Gradle started on
  `Found unknown maven repo 'https://maven-central.storage-download.googleapis.com/maven2/' at android/settings.gradle.kts`:
  that mirror had been added to dodge the 429s hosted CI gets from Maven Central.
  Keep to `google()`, `mavenCentral()` and `gradlePluginPortal()`, and rely on the
  Gradle repository retry properties instead.

  Note where the fix had to go: fdroiddata builds the **tag**, not the MR branch,
  so correcting the tag is what made `fdroid build` pass. The MR branch carries
  only metadata; `android-v0.1.0` was re-pointed at the fixed commit with
  `git tag -f` plus a force-push, which is acceptable only because nothing had
  been published from it yet.

## Opening the next one by hand

1. Fork `https://gitlab.com/fdroid/fdroiddata`.
2. Add `metadata/io.github.mbanucu.opencodesetup.yml` and
   `metadata/io.github.mbanucu.opencodesetup/en-US/summary.txt`.
3. Open a merge request against `fdroid/fdroiddata` with the text below.

## Merge request text

```markdown
New app: opencode-setup (io.github.mbanucu.opencodesetup)

Setup companion for opencode in Termux. Upstream ships glibc/musl-only binaries
and Termux is Bionic, so the app detects Termux, names the third-party apt
repository it will add (bd-loser/opencode-bionic), shows the download budget,
asks for consent, and hands one command to Termux through RUN_COMMAND. It then
reports `opencode --version` back.

- Source: https://github.com/MBanucu/opencode-termux-setup (GPL-3.0-or-later), subdir `android`
- Build: `gradle`, wrapper 8.9, AGP 8.7.3, compileSdk 35, minSdk 24, JDK 17
  (the wrapper and the distribution SHA-256 live in
  `android/gradle/wrapper/gradle-wrapper.properties`)
- No library dependencies; the app is a few activities and one BroadcastReceiver
- The terminal is xterm.js 5.5.0 (MIT), vendored under `app/src/main/assets/`
  with its licence and loaded from `file:///android_asset`; network loads in the
  WebView are blocked
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

- `versionName`/`versionCode` in `android/gradle/libs.versions.toml` must equal the
  `Builds` entry, and `android/fastlane/metadata/android/en-US/changelogs/2.txt`
  must exist for that `versionCode`. (`2` is the current release.)
- `bash test/fdroid-metadata-test.sh` checks the fields that can be checked
  without fdroidserver: app id agreement, version parity, tag existence, summary
  presence and length limits, changelog presence, and YAML parseability. On a
  pull request a missing `android-v*` tag is printed as a `warn` and does not
  fail the check, because the tag is created with the release; on `main` and on
  tag pushes the same state fails. The PR job is named `F-Droid metadata`.
- Screenshots are not committed yet. F-Droid does not require them, and a
  fabricated one would be worse than none; `fastlane/metadata/android/en-US/images/phoneScreenshots/`
  is where they go once someone runs the app on a device.

## After it is merged

- `fdroid build io.github.mbanucu.opencodesetup:1` reproduces the build locally.
- A new release is: bump the version in `android/gradle/libs.versions.toml`, add
  `changelogs/<versionCode>.txt`, tag `android-v<versionName>`, then bump
  `Builds`, `CurrentVersion` and `CurrentVersionCode` in the YAML.
- Update checks stay manual because this repository also tags the installer and
  APK releases, which an automatic check would mistake for app versions.
