# opencode-setup — one-tap installer app

A setup companion for [OpenCode](https://opencode.ai) in Termux, built in the same
shape as [`freebuff-native`](https://github.com/MBanucu/freebuff-native-termux)'s
setup app: a single Android app, one button, no bundled binaries.

It installs nothing by itself. It explains the download, asks for consent, and
hands exactly one command to Termux via `RUN_COMMAND`:

```
curl -fsSL https://raw.githubusercontent.com/MBanucu/opencode-termux-setup/main/install.sh | bash
```

The visible `terminal-session` runner runs it in the Termux window, so the
download progress is yours to watch and Ctrl+C works. "Check setup" uses the
background `app-shell` runner and prints `opencode --version` back in the app.

"Uninstall opencode" is the inverse, and it is deliberately conservative: it
removes the package and the APT repository that `install.sh` added, and leaves
`~/.config/opencode` and `~/.local/share/opencode` untouched, so provider
authentication and sessions survive a reinstall. Deleting those is a separate,
manual `uninstall.sh --purge`.

## Build

No local Android SDK is needed: CI is the compiler.

```bash
gh workflow run android --repo MBanucu/opencode-termux-setup
gh run watch --repo MBanucu/opencode-termux-setup
```

The APK is uploaded as the `opencode-setup-debug-apk` artifact, and attached to
the latest release.

## Constraints kept from the reference app

- **No dependencies.** No AndroidX, no networking, no analytics — the app is one
  `Activity` plus one `BroadcastReceiver`, so `assembleDebug` needs only the SDK.
- **One permission**, `com.termux.permission.RUN_COMMAND`, owned by Termux. It is
  requested at the moment of use, and a denial is explained instead of retried.
- **`allow-external-apps=true`** must be set in `~/.termux/termux.properties`; if
  Termux refuses the intent, the app copies that one line to the clipboard and
  shows it.
- **Android 11+ package visibility** is handled by a `<queries>` entry for
  `com.termux`, otherwise "Termux is installed" is always false.
- **No privileged work.** Adding an apt source and installing a package is the
  installer's job, run with the user's own Termux shell.

## Tests

`app/src/androidTest/` drives the real app through **UI Automator**, which is the
accessibility event stream: waits are `Until.hasObject` / `Until.findObject` /
`Until.gone`, so a step resolves the moment the UI changes instead of after a
sleep, and the nodes a screen reader would see are what the tests match on. No
assertion comes from a screenshot.

- `SetupFlowTest` — the main screen offers four actions; each consent dialog
  names its command and what it changes (sizes and repository for install, kept
  credentials for uninstall), and cancel closes it without a side effect;
  approving install hands the command to Termux and waits for Termux's window to
  take the foreground; "Check setup" round-trips a real `RUN_COMMAND` through
  Termux and back into the output view (`exit code 0`).
- `PermissionDeniedTest` — a second run on purpose, because revoking a dangerous
  permission kills the process the runner lives in: Android asks for
  `com.termux.permission.RUN_COMMAND`, and a denial produces the explanation with
  a link to the app settings.

```bash
cd android && ./gradlew assembleDebug assembleDebugAndroidTest
bash ../test/android-e2e.sh      # adb install + am instrument, both runs
```

CI compiles both APKs on every push (`assembleDebugAndroidTest`) and uploads the
test APK, so a test that stops compiling cannot land. Running them needs a device
or emulator.

Install the app and the test APK **from the same artifact set**. CI mints a fresh
debug keystore on every run, so a test APK from one run does not match an app APK
from another, and `am instrument` refuses with `does not have a signature
matching the target`.

## Layout

```
app/src/main/java/io/github/mbanucu/opencodesetup/
  MainActivity.java       # programmatic UI, consent dialog, RUN_COMMAND handoff
  RunCommandReceiver.java # parses Termux's result bundle
app/src/main/res/values/  # strings (all user-facing text), theme
```
