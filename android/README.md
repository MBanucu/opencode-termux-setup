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

"Uninstall opencode" opens a page instead of a dialog, because removing the
package and deleting the user's data are very different outcomes. The page
preselects **Keep my settings** (`~/.config/opencode` and
`~/.local/share/opencode` survive, so provider authentication and sessions need
no re-setup on reinstall) and offers **Remove everything**, which sends
`uninstall.sh --purge` and says which directories it deletes. The exact command
for the selected choice is always on screen.

## Build

No local Android SDK is needed: CI is the compiler.

```bash
gh workflow run android --repo MBanucu/opencode-termux-setup
gh run watch --repo MBanucu/opencode-termux-setup
```

The APK is uploaded as the `opencode-setup-debug-apk` artifact, and attached to
the latest release.

## Constraints kept from the reference app

- **No dependencies.** No AndroidX, no networking, no analytics — two small
  activities plus one `BroadcastReceiver`, so `assembleDebug` needs only the SDK.
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
  names its command and what it changes (sizes and repository for install), and
  cancel closes it without a side effect; **the uninstall page** offers keeping
  settings or deleting them, has keeping preselected, swaps the command to
  `--purge` when deleting is chosen, and sends nothing while being read;
  **Remove really removes**: it presses the page's action, waits for Termux's
  window, then asks "Check setup" until Termux reports `opencode is not
  installed`; **Start setup really installs**: after that removal it downloads
  and installs opencode again and passes only once Termux reports a version with
  `exit code 0`. So the suite runs with no opencode on the device and leaves one
  behind.

Both real operations are asynchronous in Termux, so their budget is measured in
what they actually do: up to 5 minutes for a removal (apt lock and package swap)
and up to 15 minutes for a reinstall (a 70 MB download over mobile data). A check
that answers while the work is still running is ignored rather than trusted -
the test asks again, and only a check started after the work finished counts.
- `PermissionDeniedTest` — a second run on purpose, because revoking a dangerous
  permission kills the process the runner lives in: Android asks for
  `com.termux.permission.RUN_COMMAND`, and a denial produces the explanation with
  a link to the app settings.

```bash
cd android && ./gradlew assembleDebug assembleDebugAndroidTest
bash ../test/android-e2e.sh      # adb install + am instrument, both runs
```

Expect several minutes: the suite uninstalls and reinstalls the real package.

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
  UninstallActivity.java  # keep-or-delete page, one command per choice
  TermuxCommands.java     # the single place that builds a RUN_COMMAND intent
  RunCommandReceiver.java # parses Termux's result bundle
app/src/main/res/values/  # strings (all user-facing text), theme
```
