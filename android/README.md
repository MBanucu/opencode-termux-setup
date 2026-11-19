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

## Layout

```
app/src/main/java/io/github/mbanucu/opencodesetup/
  MainActivity.java       # programmatic UI, consent dialog, RUN_COMMAND handoff
  RunCommandReceiver.java # parses Termux's result bundle
app/src/main/res/values/  # strings (all user-facing text), theme
```
