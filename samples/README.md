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

The terminal view these samples are about is no longer inside this app: it is the
[`terminal/`](../terminal/README.md) module, and `build.sh` compiles that module's
sources into any sample that imports `io.github.mbanucu.terminal`. Nothing else
changed — the pty, the socket and the Termux session are still the sample's own
code, because those are app decisions rather than view code.

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

Buttons: `80x24`, `40x12`, `tty` (prints `tty; stty size`), `dump`, and `probe`.

`probe` records whether this app can run programs it does not own — the question
behind "start Termux's shell in my own pty":

```
exec:/data/data/com.termux/files/usr/bin/bash access=ENOENT(2) stat=ENOENT(2) exec=ENOENT(2)
exec:/system/bin/sh                     access=ok(0) stat=ok(0) exec=ok(0)
exec:/data/user/0/.../files/sh-copy     access=ok(0) stat=ok(0) exec=EACCES(13)
```

Termux's directory is 0700 and belongs to another uid, so the path does not even
resolve; and a binary copied into this app's own `files/` cannot be executed on a
target SDK of 29 or later, which is why Termux still ships with 28. An app-owned
pty can run system binaries, not Termux.

### Termux mode: a real session, drawn by the same view

The app cannot start Termux's shell, so it asks Termux for a session and hands it
a loopback socket for stdin and stdout (the same transport the setup app uses):

```bash
adb shell pm grant io.github.mbanucu.samples.nativeterminal \
    com.termux.permission.RUN_COMMAND      # after install or pm clear: clearing revokes it
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez termux true
```

The session answers what it is — `/dev/pts/4`, `session-stdin-is-tty`,
`session-stdout-is-tty`, `size=24 80` — then prints `TERMUX-SESSION-OK` and
`TICK-1..3` on a timer with nobody typing, and all of it appears as rows the
probe can read: 28 row nodes, 92 `CONTENT_CHANGED`, 11 `ANNOUNCEMENT` in one
dump. Typing back through the service (`--es text 'echo A11Y-TO-TERMUX\n'`) makes
Termux's shell run the line and the output come back as a node.

Two things bite: `script`'s pty starts at `0x0`, so a size has to be set from
inside it, and a service acts on the *focused* window — Termux's own toolbar field
is the first editable in a naive tree, so the probe needs `--es pkg`.

### Resizing

The view's grid is the source of truth. A window change — the keyboard opening, a
rotation, `wm size` — resizes the grid, and the app then tells whoever owns the
pty: `ioctl(TIOCSWINSZ)` for its own `forkpty` pty, a `cols rows` line on the
control port (and `stty -F` inside Termux) for a session. Measured: a grid going
62x68 → 33x22 makes the program answer `size-now=22 33` in both modes.

The activity sets `configChanges` so a window change reaches `onSizeChanged`
instead of recreating the activity and dropping the session, and `adjustResize` so
the keyboard shrinks the window rather than panning it.

### opencode mode: the TUI, drawn by the same view

opencode paints with cursor addressing on the alternate screen, so the view draws a
`Screen` grid (the ECMA-48 subset a TUI needs — CUP, ED/EL, insert/delete, scroll
region, alternate buffer; colours ignored) instead of splitting output on
newlines. `--ez opencode true` starts opencode in the Termux session:

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez opencode true
```

At 80x24 — above the 60-column floor opencode drops its footer below — the view
shows the logo, `Ask anything… "Fix broken tests"`, `Build auto · Big Pickle Default
/ OpenCode`, the composer border, `tab agents  ctrl+p commands` and `2.3.2`,and the probe reads the same lines as row nodes: 26 rows, 60 `CONTENT_CHANGED`, and 2
coalesced `ANNOUNCEMENT`s in one dump. The caret in the composer is drawn where
opencode puts it, because the view follows DEC mode 25. Text and `SGR` foreground and background are carried
(bold brightens a palette colour); underline and the rest are dropped, and the
block-glyph logo is punctuation when spoken.

Nothing is typed in this mode by the suite — opencode's composer is not a shell
prompt. Manually, keys do reach it: once a program owns the alternate screen the
view hands committed text and key events to the pty as they arrive, so opencode
draws them in its composer instead of the view holding an invisible line until
Enter. The session it opens outlives the app: force-stopping closes the socket
and the pty, but opencode keeps a session record (`opencode session list`).

### Cursor mode: the escapes a program uses to jump

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez cursor true
```

The view is fed, one step at a time, the sequences a program addresses the screen
with — `CUP`, `CUU`/`CUF`, `VPA`/`CHA`, `CSI s`/`CSI u`, the alternate screen — and
`files/evidence.txt` records the grid and `cursor=row,col` after each step. The
end-to-end suite asserts both: the rows the probe reads, and where the view put the
cursor.

### Colour mode: SGR, and what a screenshot proves

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez colors true
```

Feeds `\e[31m`, `\e[32m`, `\e[1;34m` and `\e[38;5;208m` text at known cells and
records the grid. The end-to-end suite asserts the rows (each text at the column its
`CUP` asked for, and no escape sequence in the text) and then counts red, green and
blue pixels in a screenshot of those rows: rows alone would pass even if every glyph
were drawn in the default grey.

### TUI mode: a curses program, driven one line at a time

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez tui true
```

Python and curses live in Termux, not in `/system/bin`, so this TUI runs in the
Termux session: the app sends its source as base64 on the session's input and runs
`python3 -c "..."`. It draws a box, a menu, a selection and a status line, and
reads keys — `n`, `p`, `t`, `q` — which the suite submits as one-character lines
through `ACTION_SET_TEXT` plus Enter. The suite checks the row nodes and the
40-column box, then that `n` moves the selection, `t` marks it, and `q` leaves the
alternate screen. It also asks for a blinking block caret below the box
(`DECSCUSR 1`), which only the terminal can draw: the suite samples that cell and
requires both a lit and a dark frame.

### Echo mode: an input box, and the line echoed above it

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez echo true
```

A second curses program (`assets/tui-echo.py`), kept small on purpose: an input
box centred in the screen, and an output box directly above it holding the last
three lines submitted. What a service types lands in the input box one character
at a time, Enter submits the line, and it appears in the box above while the
input box is cleared. A line containing `/quit` leaves the alternate screen. The
end-to-end suite asserts that the input box is centred, that the submitted line
is in the box above it, and that the input box is empty afterwards.

### Background mode: boxes filled with a colour

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez background true
```

The third curses program: a blue box (SGR 44) with a yellow one (SGR 43) below it,
both filled by writing spaces in a colour pair. The fill is why the grid keeps a
background per cell: a row of spaces reads as empty text, but on screen it is a
coloured rectangle. The end-to-end suite screenshots it and counts the pixels of
both colours, in the areas the row nodes say the boxes occupy.

### Entry-page symbols and modes: what opencode actually writes

`opencode`'s entry page was captured from its own pty at 100x30, and again at
80x24 in Termux's own GUI. It draws with seven non-ASCII glyphs — `▀` (U+2580),
`█` (U+2588), `▄` (U+2584), `┃` (U+2503), `╹` (U+2579), `…` (U+2026) and `·`
(U+00B7) — and with truecolor `SGR` only (a grey-to-white #808080→#FFFFFF logo
gradient, `fg` #EEEEEE, #808080, a blue #5C9CF5 composer gutter; `bg` #0A0A0A,
#1E1E1E), plus bold. Around the paint it
switches to the alternate screen, brackets the repaint with `?2026`, hides and
shows the caret, enables bracketed paste, mouse reporting (`?1000/1002/1003/1006`)
and focus events (`?2031`), and asks the terminal questions: `CSI c` (DA),
`CSI 6n` (cursor position), `CSI ? u` (kitty keyboard), a `DCS +q` termcap pair,
and `OSC 10`/`11` colour queries.

Two sample TUIs put that list on the device, so the view can be checked without
waiting for opencode:

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez symbols true     # the seven glyphs and the truecolor fills
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez modes true       # the modes and queries, then one sentinel row
```

`tui-symbols.py` is the page in miniature: the block logo, the composer's `┃`
gutter, `╹▀▀▀▀`, `Ask anything…` and the palette above. Two more extras run the
miniature and opencode in a *foreground* Termux session, so both are drawn by
Termux's own renderer and the screenshots can be compared directly:
`--ez symbolsForeground true` and `--ez opencodeForeground true`. The suite reads the rows
for every glyph and counts #0A0A0A and #1E1E1E in a screenshot.
`tui-modes.py` sends the mode set and every query, then draws `MODES-OK`; the
suite requires that sentinel, requires that no payload (`1049`, `2026`,
`P+q4d73`, `1337`, …) appears as text, and quits with `q`.

## a11y-probe — an accessibility service as a measuring instrument

*Proves: what a service actually sees and can do, for any app's window.*

```bash
adb install -r samples/out/a11y-probe.apk
# Keep what was there: a phone that already has a reader on has a list of
# services, and the probe has to be appended to it, not put in place of it.
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services \
    io.github.mbanucu.samples.a11yprobe/.ProbeService
adb shell settings put secure accessibility_enabled 1
# ... and when done, put the saved value back (`settings delete` when it was
# `null`). Writing `null` over the list is what turns the phone's reader off.
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

# a trailing \n means "and press enter"; --es pkg acts on that app's window
# instead of the focused one, which is not always the app under test
adb shell "am broadcast -p io.github.mbanucu.samples.a11yprobe \
  -a io.github.mbanucu.samples.a11yprobe.COMMAND \
  --es pkg io.github.mbanucu.samples.nativeterminal --es text 'echo hi\n'"

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
- `script`'s pty starts at `0x0`: a session that never sets a size is a one-cell
  terminal to anything that asks, so `stty cols 80 rows 24` has to run inside it.
- `pm clear` revokes a runtime permission granted a moment earlier, and starting
  Termux's guarded service without it throws instead of returning an error.
- The evidence file is appended to, not rewritten. The first version wrote a
  fresh file per line and the one line that proved a pty existed was gone by the
  time it was read.
- A wrapper that is one `bash -lc` string has to be quoted for that one level,
  not for a string nested inside it: `\"$p\"` becomes literal quotes, the wrapper a
  syntax error, and a session that "never connected" is really a session whose
  socket was never opened.

## End-to-end suite

`samples/e2e.sh` drives the apps the way a screen reader does: the probe service
performs the actions, and the assertions are the rows and events it is given. No
coordinate taps, no `input text`.

```bash
bash test/local-build.sh --samples  # build both APKs, then the fast checks
bash samples/e2e.sh --opencode      # and the TUI, against installed APKs
bash samples/e2e.sh --only tui-echo # one check
```

It installs both APKs, grants the Termux permission, enables the probe, and puts
the accessibility settings back as it found them on exit, including when a check
fails or the run is interrupted. The display is never resized - the window checks
shrink the app's own view - so there is no display state to restore. The saved
accessibility values live in `samples/out/device-state.txt`, so a run that is
killed is repaired by the next run; to do it without running the checks:

```bash
bash samples/e2e.sh --restore
```

| check | what has to be true |
|---|---|
| pty | the app owns the master, the shell is on `/dev/pts`, stdin and stdout are ttys |
| resize | shrinking the window changes the grid, and the program's `stty size` matches it |
| rows/events | a service reads at least five row nodes and gets `CONTENT_CHANGED` and `ANNOUNCEMENT` |
| session | Termux connects, and `ACTION_SET_TEXT` reaches it and is executed |
| session resize | Termux's pty reports the new grid after a window change |
| cursor jumps | `CUP`, relative moves, `VPA`/`CHA`, `CSI s/u` and the alternate screen put text and the cursor where the escape said |
| cursor through the pty | a program's own `printf` escapes land at row 10 column 3 and row 3 column 40 |
| colour rows | SGR text lands at its columns, and no escape sequence is in the row text |
| colour pixels | a screenshot of those rows has red, green and blue from the SGR codes |
| tui rows | the curses box is 8+ row nodes, every row 40 columns, and no escape leaked |
| tui keys | `n`/`t`/`q` submitted as one-character lines move the selection, toggle it, and quit |
| tui echo | a service types a word into a centred input box; it appears in the box above and the input is cleared |
| tui caret | the view draws a block at the cursor in that input box, checked on the pixels |
| tui blink | the box TUI's caret blinks: its cell is lit in some frames and dark in others |
| tui bg | a TUI's boxes are filled with SGR 44 and 43, counted in the screenshot |
| tui symbols | the entry page's seven glyphs are row text, and its truecolor fills are in the screenshot |
| tui modes | the entry page's modes and queries leave no text behind, and `q` still quits |
| `--opencode` | the TUI's own text is in the tree, and it announces |
