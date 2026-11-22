# Sample app

One small app that answers with measurements rather than opinions: *is there a
more Android-native way to give this app a TTY, so accessibility events work?*
The write-up is in
[`../docs/tty-accessibility-research.md`](../docs/tty-accessibility-research.md).

The other two samples — `native-terminal` (an app-owned PTY drawn by the native
terminal view) and `a11y-probe` (the `AccessibilityService` they are driven with)
— moved with the view to
[`MBanucu/android-accessible-terminal`](https://github.com/MBanucu/android-accessible-terminal),
which is now the home of the terminal module and of the end-to-end suite that
exercises it on a device.

The sample is deliberately not a Gradle module: on the device this repository
was written on, Gradle's `aapt2` has no aarch64 build, and a sample should be a
directory of files you can read. `build.sh` does what Gradle would do — `aapt2
link`, `javac`, `d8`, zip in the dex, `zipalign`, `apksigner` — and needs only
the SDK that `test/local-build.sh` already uses.

```bash
bash samples/build.sh xterm-a11y   # -> samples/out/xterm-a11y.apk
bash samples/build.sh all          # the same, for now the only sample
```

Conventions the script relies on, so no build file is needed:

| path | meaning |
|---|---|
| `AndroidManifest.xml` | the manifest |
| `res/` | resources, if any |
| `src/` | Java sources |
| `assets/` | assets; `assets/xterm` is copied from the app at build time |
| `jni/*.c` | native sources, built into `lib<app dir with _>.so` |

## xterm-a11y — the WebView page, measured

The app's terminal page is xterm.js in a `WebView`. This sample renders the same
bundle with accessibility hooks so the page's nodes can be dumped and compared
with the native view's. It is the baseline the
[roadmap](../docs/terminal-module-roadmap.md) measures the native page against:
xterm.js in a `WebView` is one opaque node unless `screenReaderMode` is on, and
the sample is where that was measured.

It copies `assets/xterm` and `assets/terminal-mono.ttf` from
`../android/app/src/main/assets/` at build time, so the repository keeps one copy
of each.

Build and install it like any sample:

```bash
bash test/local-build.sh --samples
adb install -r samples/out/xterm-a11y.apk
adb shell am start -n io.github.mbanucu.samples.xterm/.MainActivity
```
