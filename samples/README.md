# Sample apps

Three small apps that were built to answer one question with measurements rather
than opinions: *is there a more Android-native way to give this app a TTY, so
accessibility events work?* The write-up is in
[`../docs/tty-accessibility-research.md`](../docs/tty-accessibility-research.md).

They are deliberately not Gradle modules: on the device this repository was
written on, Gradle's `aapt2` has no aarch64 build, and a sample should be a
directory of files you can read. `build.sh` does what Gradle would do — `aapt2
link`, `javac`, `d8`, zip in the dex and the native library, `zipalign`,
`apksigner` — and needs only the SDK that `test/local-build.sh` already uses.

```bash
bash samples/build.sh native-terminal   # -> samples/out/native-terminal.apk
bash samples/build.sh a11y-probe
bash samples/build.sh xterm-a11y
```

Conventions the script relies on, so no build file is needed:

| path | meaning |
|---|---|
| `AndroidManifest.xml` | the manifest |
| `res/` | resources, if any |
| `src/` | Java sources |
| `assets/` | assets; `assets/xterm` is copied from the app at build time |
| `jni/*.c` | native sources, built into `lib<app dir with _>.so` (so `native-terminal` produces `libnative_terminal.so`) |

## native-terminal — an app-owned PTY, and a screen reader can use it

*Proves: `forkpty()` works from an app (no Termux, no root), the app can resize
its own pty with `ioctl(TIOCSWINSZ)`, and a `View` with an
`AccessibilityNodeProvider` gives a screen reader rows to read and text to type.*

`jni/pty.c` is the whole PTY: `forkpty`, `execvp`, `read`, `write`,
`ioctl(TIOCSWINSZ)`, `waitpid` — about 180 lines, linked only against libc.
`TerminalView` draws lines and publishes them as virtual nodes (one per visible
line, plus an editable prompt node) and accepts `ACTION_SET_TEXT` as well as IME
text and raw key events.

On launch it asks the shell what it is connected to, then resizes the pty from
Java two seconds later. `files/evidence.txt` in the app's external directory
holds the transcript:

```
### pty:master-fd:136 pid:30653
/dev/pts/4
stdin-is-tty
stdout-is-tty
size-now=24 80
### resize:40x12 from app
### resize:result:0 cols:40 rows:12
SIGWINCH-arrived
size-after=12 40
```

`resize:result:0` is the `ioctl` returning success; `SIGWINCH-arrived` and
`size-after=12 40` are the kernel telling the foreground program, which is what
`TermuxCommands.watch()` and its `stty -F <tty>` loop exist to fake today.

Buttons: `80x24`, `40x12`, and `tty` (prints `tty; stty size`).

## a11y-probe — an accessibility service as a measuring instrument

*Proves: what a service actually sees and can do, for any app's window.*

```bash
adb install -r samples/out/a11y-probe.apk
adb shell settings put secure enabled_accessibility_services \
    io.github.mbanucu.samples.a11yprobe/.ProbeService
adb shell settings put secure accessibility_enabled 1
# ... and when done, so the device is left as it was found:
adb shell settings put secure enabled_accessibility_services null
adb shell settings put secure accessibility_enabled 0
```

Commands go in through a package-scoped broadcast (an implicit one is queued for
later delivery, which reads as "the receiver never ran"):

```bash
# every window's tree, plus the events the service heard, to files/<name>.txt
adb shell am broadcast -p io.github.mbanucu.samples.a11yprobe \
  -a io.github.mbanucu.samples.a11yprobe.COMMAND --es dump tree

# ACTION_SET_TEXT, the way a screen reader or Voice Access types
adb shell "am broadcast -p io.github.mbanucu.samples.a11yprobe \
  -a io.github.mbanucu.samples.a11yprobe.COMMAND --es text 'echo hi'"

# ACTION_ACCESSIBILITY_FOCUS on a node whose text contains the needle
adb shell am broadcast -p io.github.mbanucu.samples.a11yprobe \
  -a io.github.mbanucu.samples.a11yprobe.COMMAND --es find MARKET
```

Answers land in `files/<name>.txt` and `files/actions.txt` in the probe's external
directory. Each node line carries `editable`, `actions`, `bounds` and `text`,
which is what makes "the WebView exposes nothing" a number instead of a guess.

Note the quoting: the local shell eats `"echo a11y-set-text"` before adb sees it,
and the command arrives as `--es text echo a11y-set-text` — `text=echo`. Wrap the
whole remote command in quotes, as above, or the probe types one word.

## xterm-a11y — what the current page exposes, with and without screen-reader mode

*Proves: xterm.js in a WebView publishes the rows only when
`screenReaderMode` is on.*

```bash
adb shell am start -n io.github.mbanucu.samples.xterna11y/.MainActivity
adb shell am start -n io.github.mbanucu.samples.xterna11y/.MainActivity --ez sr true
```

The page prints a known screen (`PROMPT-MARKER`, `MARKER-ONE`, `MARKER-TWO`),
then one `LIVE-n` line a second, and reports its own buffer back to the app
(`files/screen.txt`, `files/events.log`) so "absent from the tree" cannot be
confused with "never rendered". Measured on this device:

| page | `MARKER` text in the tree | nodes with text | editable nodes | tree lines |
|---|---|---|---|---|
| `screenReaderMode: false` | 0 | 10, all SystemUI | 4 | 542 |
| `screenReaderMode: true` | yes, 22 row nodes | 29 | 2 | 439 |

With the mode on, each row is an `android.view.View` with `text=` and bounds, and
the page also adds one `TextView` holding the whole screen concatenated — xterm's
`aria-live` region, and the duplicate reading in xterm.js#5382. Chrome did not
surface that region as a `TYPE_ANNOUNCEMENT` to this service, so live output was
not confirmed to be spoken; only the rows were confirmed to be navigable.

## Things these samples learned the hard way

- `termios` with the speed bits at zero is `B0` — "hang up". The pty exists, the
  shell runs, and nothing is ever echoed. `cfsetispeed`/`cfsetospeed(B38400)`.
- `requestSendAccessibilityEvent()` throws `IllegalStateException: Accessibility
  off` when no service is enabled. A view that emits an event per line crashes on
  its first screen of output until it checks `AccessibilityManager.isEnabled()`.
- A library in an APK must be at `lib/<abi>/<name>.so`; the first packaging
  attempt wrote `arm64-v8a/<name>.so` and the app died with
  `dlopen failed: library "libnative_terminal.so" not found`.
- `AccessibilityNodeInfo.obtain(view)` hydrates the node from the real view
  hierarchy, and the host of a virtual subtree then has no children. `obtain()`
  plus explicit `setSource`/`addChild` is what works.
- A platform `Activity` without a NoActionBar theme lays out
  `ActionBarOverlayLayout`, which is noise for a terminal; the manifest sets
  `@android:style/Theme.DeviceDefault.NoActionBar`.
- The evidence file is appended to, not rewritten. The first version wrote a
  fresh file per line and the one line that proved a pty existed was gone by the
  time it was read.
