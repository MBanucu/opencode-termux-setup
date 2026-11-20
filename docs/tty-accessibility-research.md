# A TTY on Android, and why accessibility is a different question

*Research note for `android/` — the setup app that shows Termux output through a
WebView running xterm.js. Measured on the device this repository was written on:
XPLORE 2, Android 15 (API 35), aarch64. Every number below comes from the sample
apps under [`samples/`](../samples/README.md), which can be rebuilt and re-run.*

## Short answer

There is no Android framework API for a terminal, and there never was: an app
that wants a TTY makes one itself with `forkpty()` from the NDK (available since
API 23, and this app's `minSdk` is 24), or it borrows a process that already has
one. Termux's own terminal does the former from JNI.

But a TTY is not what makes TalkBack work. The PTY decides whether the program
sees a terminal; **the View decides whether accessibility services see
anything**. Those are independent layers, and today this app's problem is
entirely in the second one:

| Layer | Today | Can be "Android native"? |
|---|---|---|
| PTY | `script(1)` inside Termux, output copied to a loopback socket, resized with `stty` from a second loopback port | Yes — `forkpty()` + `ioctl(TIOCSWINSZ)` on the app's own master fd |
| Screen | WebView + xterm.js, text sent to the page's `contentDescription` only when a command has finished | Yes — a `View` with an `AccessibilityNodeProvider` |
| Input | xterm's hidden textarea, keys relaid as bytes over the socket | Yes — `InputConnection` + `ACTION_SET_TEXT` on a virtual node |

So the honest answer to "is there a more Android-native way to get TTY
functionality so accessibility events work correctly" is: **yes for the
accessibility half, and it does not require changing how the PTY is made.** A
native terminal `View` fed by the same bytes the WebView gets today already fixes
the accessibility tree, and it is a much smaller change than taking over the PTY.
Taking over the PTY is a separate, later decision with its own cost (this app
would stop using Termux as its shell provider, which it cannot fully do anyway
because `opencode` lives inside Termux).

## What was measured, and how

Three sample apps (`samples/native-terminal`, `samples/a11y-probe`,
`samples/xterm-a11y`) were built without Gradle, installed over adb, and driven
by an `AccessibilityService` — the same class of consumer as TalkBack. Nothing
here is inferred from documentation alone.

### 1. An app can own a PTY, and can resize it itself

`native-terminal` calls `forkpty()` from its own JNI library, on a phone with no
Termux involved in that process, and asks the shell what it is talking to:

```
### pty:master-fd:136 pid:30653
/dev/pts/4
stdin-is-tty
stdout-is-tty
size-now=24 80
```

Two and a half seconds later the app changes the window size from Java:

```
### resize:40x12 from app
### resize:result:0 cols:40 rows:12
SIGWINCH-arrived
size-after=12 40
```

`resize:result:0` is `ioctl(TIOCSWINSZ)` on the master returning success;
`SIGWINCH-arrived` and `size-after=12 40` are the kernel telling the foreground
program, exactly as a terminal window does. This is the whole of what
`TermuxCommands.watch()` currently fakes with a second loopback port, a
background `read` loop and `stty -F <tty>`: one `ioctl` on a descriptor the app
owns.

Availability, from bionic's own header: `openpty`/`forkpty` are
`__INTRODUCED_IN(23)` in `<pty.h>`, and on Android both live in **libc**, not in
`libutil` (`-lutil` does not exist). The library built here has exactly two
`NEEDED` entries, `libc.so` and `libdl.so`.

### 2. A native View can expose a terminal to a screen reader

`native-terminal` also draws its screen itself and publishes it as a virtual view
hierarchy: one node per visible line, plus an editable node for the prompt. Dumped
through the accessibility service:

```
io.github.mbanucu.samples.nativeterminal.TerminalView ...
  android.widget.TextView ... bounds=32,91,1408,143 text= ...
  ...
  android.widget.EditText editable=true focused=true actions=FOCUS|CLICK|PASTE|SET_TEXT
```

- **26** line nodes were in the tree in that dump.
- The prompt node reports `editable=true` and offers `SET_TEXT`.
- The service performed `ACTION_SET_TEXT` with `echo a11y-set-text`; the view's
  `performAction` accepted it (`-> true`), and the app then wrote it to the PTY:
  `### submitted:echo a11y-set-text` in the app's evidence file.
- `ACTION_ACCESSIBILITY_FOCUS`, `TYPE_VIEW_TEXT_CHANGED`, `TYPE_ANNOUNCEMENT`
  (8 in that dump) and `TYPE_WINDOW_CONTENT_CHANGED` all reached the service.

That is the shape TalkBack, Voice Access, switch access and braille keyboards
consume. A `View` that draws glyphs into a bitmap and reports one
`contentDescription` gives them none of it — which is why Termux itself is only
semi-accessible, and why its own tracker says "the TerminalView is not really a
text field (EditText) used by android accessibility service and so there is no
concept of 'cursor position' for it" (termux-app#2543).

### 3. The WebView does not expose the terminal's text — until `screenReaderMode`

The same known screen of text, rendered by the same xterm.js bundle the app
ships, with the page reporting its own buffer first (so "nothing in the tree"
cannot be confused with "nothing on the screen"):

```
poll: mode=plain rows=80 cols=63 buffer=80 first=terminal a11y sample
PROMPT-MARKER $ run --help
MARKER-ONE first output line
MARKER-TWO second output line
```

|  | `MARKER` text in the tree | nodes with text | editable nodes | tree lines |
|---|---|---|---|---|
| `screenReaderMode: false` (today's page) | **0** | 10, all SystemUI | 4 | 542 |
| `screenReaderMode: true` | **yes** | 29, 22 of them terminal rows | 2 | 439 |

With `screenReaderMode` off, the entire terminal is invisible to the service: the
only text nodes in the tree are SystemUI's clock and the app's own title. Every
`MARKER` string in the buffer is absent. This is the current opencode page's
situation, and nothing in `PageActivity` changes it — the section that writes the
screen to `contentDescription` deliberately skips a running command, and a TUI
never finishes.

With `screenReaderMode` on, the rows become real, focusable nodes with text and
bounds, and the page also grows one `TextView` holding the whole screen
concatenated (`….(262)` characters in that dump) — xterm's `aria-live` region.
That is the mechanism behind xterm.js#5382 ("screen readers read content twice"):
the live region duplicates what the row nodes already say.

Two things were *not* observed, and are therefore not claimed: Chrome did not
surface the live region as a `TYPE_ANNOUNCEMENT` event to this service (0 in the
dump), so live output may or may not be spoken as it arrives depending on the
reader; and TalkBack itself is not installed on this device, so what a real
screen reader chooses to speak was not verified — only what it is offered.

### 4. Two traps that cost runs

- `ViewGroup.requestSendAccessibilityEvent()` throws
  `IllegalStateException: Accessibility off. Did you forget to check that?` when
  no service is enabled. A terminal that emits a text-changed event per line
  crashes on its first screen of output on a normal phone until it checks
  `AccessibilityManager.isEnabled()`.
- `termios` with the speed bits left at zero is `B0`, "hang up": the pty exists,
  the shell runs, and nothing is ever echoed or delivered. `cfsetispeed`/
  `cfsetospeed(B38400)` is required, and the first version of the sample produced
  exactly that silent failure.

## The options

### A. Keep the WebView, turn on `screenReaderMode`

`new Terminal({ screenReaderMode: true })` in `terminal.html`, ideally only when
`AccessibilityManager.isEnabled()` — it costs a parallel DOM tree and an
`aria-live` region, which is also a second thing repainting at 4 Hz on a page that
already avoids per-chunk accessibility events.

- **Wins:** a two-line change; measured to put the rows in the tree.
- **Costs:** output arrives as a wall of text in one node plus per-row nodes
  (duplication); no editable semantics for the *screen*, only xterm's textarea;
  the WebView's accessibility bridge remains an extra layer between the page and
  the service, and its behaviour is Chrome's, not this app's.
- **Effort:** hours. **Risk:** low. **Does not** change the PTY story.

### B. Use Termux's terminal emulator as a library

`com.termux:terminal-emulator` is Apache-2.0 Java plus one JNI file
(`terminal-emulator/src/main/jni/termux.c`). `TerminalView` + `TerminalSession` +
`JNI.createSubprocess` give a real VT parser, scrollback, key handling and a
PTY — the same code Termux runs.

- **Wins:** a genuine terminal, maintained upstream, with the PTY included;
  far less code than writing an emulator.
- **Costs:** it is a `View` that draws into a bitmap: `TerminalView` itself is
  exactly the "one opaque node" object described above, so **using it does not by
  itself fix accessibility** — the virtual-node layer still has to be written (or
  contributed upstream). It also drags in Termux's session/bootstrap assumptions,
  and the app currently has *no* dependencies on purpose ("No AndroidX, no
  networking, no analytics").
- **Effort:** days. **Risk:** medium (new dependency, new lifecycle, upstream
  direction). **Also** replaces the PTY, so it supersedes the socket/`script`/
  `stty` machinery.

### C. A native `View` fed by the bytes the app already receives

Replace only the WebView with a `View` that consumes the same UTF-8 stream from
`LiveStream`, parses it (or, minimally, renders lines), and publishes virtual
nodes as the sample does. The PTY side stays exactly as it is today.

- **Wins:** the accessibility tree is this app's own, not Chrome's; measured
  working (`SET_TEXT` → PTY, row nodes, announcements); no new dependency; the
  IME gets a real `InputConnection` instead of a hidden textarea; the 250 ms
  `tail`/`publish` cycle stops being an accessibility event quota and becomes a
  plain repaint (the comment in `terminal.html` about UI Automator needing a
  stable snapshot goes away).
- **Costs:** a VT parser has to come from somewhere. A line-oriented renderer is
  enough for `apt`, `pkg` and the check output, but **not** for opencode's TUI
  (cursor addressing, alternate screen, box drawing). Either vendor a parser
  (from B) or keep the WebView for the TUI page and use the native view for
  everything else.
- **Effort:** days for the `View` + accessibility layer (the sample is ~450
  lines, most of it the node provider); plus a parser decision. **Risk:** medium.

### D. Own the PTY as well (`forkpty` instead of `script`)

Feed C (or B) from an in-app PTY: `forkpty` + `read`/`write` on the master, resize
with `ioctl(TIOCSWINSZ)`.

- **Wins:** deletes `script`, `tee`, `stdbuf`, the input socket, and the second
  control port plus its `stty` watcher. One descriptor, one reader thread, and a
  resize that is an `ioctl` rather than a shell loop reacting to a loopback
  protocol. It also makes the terminal work with Termux closed.
- **Costs:** the app has to provide the shell environment itself. Today the shell
  and `opencode` are Termux's (`/data/data/com.termux/files/usr/bin/bash`), with
  Termux's `$PREFIX`, `$HOME` and packages. An app-owned PTY runs Termux's `bash`
  fine — that is what the sample does with `/system/bin/sh` — but a run that must
  reach `opencode` still needs Termux installed, its `PREFIX` on `PATH`, and
  `allow-external-apps` no longer matters while `RUN_COMMAND` disappears.
- **Effort:** days, mostly in environment plumbing. **Risk:** medium, and it is
  the option most likely to break the install flow, which is the app's whole job.

## Recommendation

1. **Do A now.** Turn on `screenReaderMode` when accessibility is enabled, and
   keep the app's existing "publish the screen" path for everything else. It is
   measured to be the difference between zero and 22 readable row nodes, and it is
   reversible.
2. **Then do C for the non-TUI pages.** Install and check output is a log, not a
   screen: a native line view with the node provider from `samples/native-terminal`
   reads better, types better, and stops the app from paying Chrome's bridge for
   text it already has.
3. **Do D only if dropping Termux becomes a goal.** It is a real simplification
   (`ioctl` instead of a control socket, `SIGWINCH` instead of `stty`), but it
   does not help accessibility, and `opencode` still lives in Termux.
4. **Do not do B *for accessibility*.** Termux's emulator is the right answer to
   "how do I render a TUI", and the wrong answer to "why can't TalkBack read my
   terminal" — its `View` has the same problem this app has, which is why its own
   accessibility issues are still open (termux-app#245, #2543, #4175, #4742).

A TUI is the hard case no option solves completely: a full-screen program's
screen is not a document, there is no cursor in the accessibility sense, and
opencode redraws its composer on every keystroke. Realistically the terminal page
can be made *navigable* (rows as nodes, text announcements) and *typeable*
(`SET_TEXT` + IME), which is what the samples demonstrate; making a TUI
*comfortable* with a screen reader is a design problem on top of that, and out of
scope for a TTY change.

## Reproduce

```bash
bash samples/build.sh native-terminal
bash samples/build.sh a11y-probe
bash samples/build.sh xterm-a11y
adb install -r samples/out/native-terminal.apk
adb install -r samples/out/a11y-probe.apk
adb install -r samples/out/xterm-a11y.apk

# the screen reader stand-in; no root, restore it to null afterwards
adb shell settings put secure enabled_accessibility_services \
    io.github.mbanucu.samples.a11yprobe/.ProbeService
adb shell settings put secure accessibility_enabled 1

adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity
adb shell am broadcast -p io.github.mbanucu.samples.a11yprobe \
    -a io.github.mbanucu.samples.a11yprobe.COMMAND --es dump native-tree
adb shell cat /sdcard/Android/data/io.github.mbanucu.samples.a11yprobe/files/native-tree.txt

adb shell am start -n io.github.mbanucu.samples.xterna11y/.MainActivity --ez sr true
adb shell am broadcast -p io.github.mbanucu.samples.a11yprobe \
    -a io.github.mbanucu.samples.a11yprobe.COMMAND --es dump xterm-sr
```

`samples/README.md` has the per-app detail, including the extra that types
through the service and the evidence files each app writes.

## References

- bionic `<pty.h>`: `openpty`/`forkpty`, `__INTRODUCED_IN(23)`, in libc —
  <https://android.googlesource.com/platform/bionic/+/main/libc/include/pty.h>
- bionic status, "New libc functions in M (API level 23): `<pty.h>` `forkpty`,
  `openpty`" —
  <https://android.googlesource.com/platform/bionic/+/main/docs/status.md>
- Termux's native PTY (`/dev/ptmx`, `grantpt`, `unlockpt`, `ptsname_r`,
  `TIOCSWINSZ`) — <https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/jni/termux.c>
- Termux accessibility, the "not really a text field" explanation —
  <https://github.com/termux/termux-app/issues/2543>, and the still-open
  #245, #4175, #4742.
- `AccessibilityNodeProvider` (virtual view hierarchies) —
  <https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeProvider>
- `ACTION_SET_TEXT`, and the requirement to send `TYPE_VIEW_TEXT_CHANGED` and
  report `isEditable()` —
  <https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction>
- xterm.js screen reader mode design (accessibility tree + assertive live
  region) —
  <https://github.com/xtermjs/xterm.js/wiki/Design-Document:-Screen-Reader-Mode>,
  and the duplication complaint, <https://github.com/xtermjs/xterm.js/issues/5382>
- ConnectBot's terminal view accessibility work (same class of problem, same
  conclusion: the emulator, not the transport, is what a reader cannot see) —
  <https://github.com/connectbot/connectbot/issues/1552>
