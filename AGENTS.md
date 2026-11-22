# Notes for agents

What this repository taught the hard way, mostly while building the instrumented
tests under `android/app/src/androidTest/`. Read this before changing the
terminal page or those tests: each item is a mistake that cost a run, and most
of them look like harmless simplifications from the outside.

Commands referenced here are the ones in the README: `bash test/local-build.sh`
builds and runs everything, `--only <class>[#method]` runs one test class,
`--samples` builds the xterm-a11y sample only, and
`test/android-e2e.sh --no-record --only <class>` skips the recording.

## Test the page a user opens, not a shortcut into it

`OpencodeTuiTest` started by firing a `terminal_command` intent straight at
`TerminalActivity`. That drew opencode at the default font — 13 px, about 44
columns — and opencode drops its own footer below roughly 60 columns, so the
test asserted against a screen no user ever sees. The page a user reaches
("Check setup" → "Open opencode") passes `terminal_font=8` and
`terminal_input=true` and renders 71 columns.

The test now navigates by accessibility, like a person, and then asserts the
size: `events.log` records `tail:{"cols":N}`, and the test requires `N >= 60`.
Assert the grid, not only the text — a wrong font still produces the right text.

## Typing into a TUI is not pasting

opencode enters shell mode only when `!` is a real shifted keypress. Text
committed by the IME is pasted into the prompt: it appears on screen and never
opens shell mode, so the command looks typed and nothing runs. The working
sequence is

```bash
adb shell input keycombination 59 8                 # '!'
adb shell input keyevent --delay 120 112 119 100 66 # pwd + Enter
```

Both are separate `adb shell` calls. Merging them into one
`adb shell 'input ...; input ...'` delivered **zero** keystrokes — tested twice,
screen stayed on "Ask anything…" — and the comment in the test records that so
nobody folds them together again. A `keyevent` list can only express unshifted
characters, which is why the test runs `!pwd` rather than something prettier.

## The screen is not always in the accessibility tree

`PageActivity` sets the terminal's `contentDescription` only when the command
has finished (`!commandRunning`). A TUI never finishes, so on the opencode page
that node stays empty forever. The screen text has to come from the file the page
writes, `/sdcard/Android/data/io.github.mbanucu.opencodesetup/files/live-output.txt`.

The reverse is also true, and this is what a check has to account for: a page
that is not in front does not write `live-output.txt`, but it still publishes its
finished screen to the node. So neither source is authoritative alone. Read the
node, wait on the file when the node says nothing, then read the node once more
for the page that was still repainting — that is `SetupFlowTest.pageShows`.

## Publish all of the screen, not the last 40 lines

`terminal.html` published `lines.slice(-40)` for `live-output.txt`. On a 71-row
screen opencode's shell output sits near the top, so the last 40 lines never
changed, `onScreen` never fired, and the file was never written — the page looked
correct while the evidence file stayed empty. It now publishes
`lines.slice(-Math.max(40, term.rows))`.

When a page renders but its output file does not, suspect the slice before the
wiring.

## A hairline border is not the renderer's fault

opencode's composer border looked like hairlines, so the DOM renderer was blamed
and `@xterm/addon-canvas` (0.7.0, 94,607 B) was vendored into `assets/xterm/` to
take over. It attaches, and it changes nothing: the probe screenshot with and
without it is **99.96 % identical**.

Two things had to be right before it would even attach, and both look like "no
effect". It must be loaded after `term.open()`: before that, `screenElement` is
undefined, `new CanvasRenderer(...)` throws, and a `try/catch` around the load
hides it. Attached, `events.log` says `canvas:"attached"` and four `<canvas>`
layers exist - and `.xterm-rows` is gone, which is the real cost: `keepTailVisible`,
`tailInfo` (`fits`, `row`, `bottom`) and `alignFrozenGrid` all read those divs, so
switching renderers means rewriting the page's own measurements for a
pixel-identical result.

The glyph was never the problem. `GridProbeTest` rows of `▀`, `▄` and `┃` paint
full-width 18 px bars at font 8 (cell 36.4 device px), with palette ink and with
`38;2;…`/`48;2;…` true colour, in the DOM renderer and in canvas alike. Read back
from the buffer, opencode's border cells are `▀` with a 24-bit `fg #1E1E1E` on
`bg #0A0A0A`: the border is dark because opencode draws it dark, and no renderer
can brighten a colour a program chose.

So when a TUI's border looks wrong, read the cell before replacing the renderer.
`term.buffer.active.getLine(row).getCell(col)` gives `getChars()`, `getFgColor()`,
`getFgColorMode()` (50331648 is RGB) and the bold/dim/inverse flags, and a
`window.AndroidBridge.onTail(JSON.stringify({...}))` carrying a few of them is how
they reach `events.log` - the page has no console forwarder.

The addon and those diagnostics were reverted; the page draws with the DOM
renderer.

## A resize is an event, not a state to poll

The terminal's size used to reach the app only inside the 250 ms `tail` report,
so a page whose screen was still published nothing: the keyboard opening stayed
invisible until the next tick, and a frozen grid - one a running command owns -
never said anything at all. `terminal.html` now calls `AndroidBridge.onResize`
the moment the grid or the element around it moves, reporting
`{"cols","rows","width","height","frozen"}` into `events.log`; `PtySizeTest`
asserts the announced grid is the one the PTY was given.

`frozen` is the part worth knowing: a running command owns its grid, and the
window around it can move under it - the keyboard opening is enough. The page
re-fits the grid when asked and the app hands the same size to the PTY, so the
program redraws to fit instead of drawing into rows nobody can see. Do not pin
the terminal's height from the Android side instead: the element then never
changes size, so no event is sent and the app is back to guessing. That pin lived
in `PageActivity` for one commit and is what this replaced.

## Resizing a PTY this app does not own

A terminal resizes a PTY with `ioctl(TIOCSWINSZ)` on the master, and the kernel
sends SIGWINCH to the foreground program; that is what Termux does when the
keyboard opens and its view gets shorter. This app cannot: `script` made the PTY,
so the master descriptor is Termux's. So the app opens a second loopback port
(`LiveStream control`) and the wrapper `TermuxCommands.wrap` starts a background
`read` loop that sets the size on the slave device with
`stty -F <tty> cols/rows`, falling back to `stty ... < <tty>`. The device name is
written by the command itself to `$HOME/.opencode-setup/pty`, because only the
process inside the PTY can ask `tty`. The loop is killed from a `trap ... EXIT`,
so it cannot outlive the command and hold the app's socket open.

`PtyResizeTest` is the only honest witness: the command prints `stty size` once a
second, the test shortens the page under it, and the rows the program prints must
be the rows the app wrote to its control port. A page that re-fits its own grid
passes a log-only check and fails this one.

Three things that cost runs here:

- A test's second `startActivity` for a page already on top does not reach it on
  this device: no new activity, no `onNewIntent`, nothing in the ATMS log. The
  resize hook is a package-scoped broadcast (`TerminalActivity.ACTION_RESIZE`)
  for that reason, since the test code runs in the page's own process.
- `pinTerminalHeight` has to clear the frame's layout weight before setting a
  height. A weighted frame is given whatever is left, so the height was measured
  and then ignored, the window never moved, and no resize was ever reported.
- `test/android-e2e.sh` installs the APK under `android/app/build/`; it does not
  build it. Run `bash test/local-build.sh --skip-tests` first, or you are testing
  the previous build while the source you just changed looks correct.

## A failing suite is usually leftover device state

`SetupFlowTest` failed in `@Before` on `aMainScreenOffersTheFourActions`:
`the MainActivity page never said it was laid out`, with the screen still showing
the opencode TUI. Nothing in the diff under review caused it — the previous TUI
run left the app on that page. `adb shell am force-stop io.github.mbanucu.opencodesetup`
and a back press later, the class returned `OK (6 tests)`.

Reset the app before believing a first-run failure, and treat this as a known
fragility: `bringToFront` gives the main page only a few seconds to announce
itself.

## Look at the recording before re-running blind

The recording is the cheapest way to tell "the code is wrong" from "the input
never arrived". The first typing attempt failed with the prompt unchanged;
`python3 test/frames.py extract --recording test/recordings/<run>.mp4 --at N,M --out $TMPDIR/frames`
showed the line sitting in shell mode, typed and never submitted — which is what
pointed at the merged `input` invocations rather than at the keystrokes. Delete
the frames directory afterwards; `test/recordings/` is git-ignored and local.

`python3 test/frames.py idle --recording <run>.mp4 --milestones <run>.json`
separates a slow test from a slow screen, which is what the busy spans in
`TestMilestones.busy(...)` are for.

## Keep a commit to the change it describes

`SetupFlowTest.java` was already modified when `terminal.html` was fixed, so it
was left out of that commit (`d559217`) and committed on its own once reviewed.
Check `git status` before committing: a working tree that is dirty with someone
else's change is the normal state of this repo, not permission to bundle.

## The symbol in a screenshot is a guess; the accessibility tree is the answer

Matching Termux's TAB key took two tries. A 720 px screenrecord frame of Termux's strip
read as `⇤` (U+21E4) at 15 px, and that is what went into `key_tab_glyph`. A
`uiautomator dump` of the same screen says the key's text is `↹` (U+21B9); the same dump
also names the dash `―` (U+2015) and PASTE `⎘` (U+2398). Symbol fonts draw different
codepoints with almost the same shape at that size. Copy a glyph from the tree, not the
pixels: dump it, then print each `text=` with its codepoints (`[hex(ord(c)) for c in t]`).
Termux only has the extra keys in the tree while the keyboard is up, so tap the terminal
first.

## Extra keys are a grid: count the eight cells before moving one

The footer is two rows of eight equal cells. Every "X should sit over Y" request is
settled by the top row's order, and there are only eight slots: with Left, Down and
Right in cells 4, 5 and 6, Home and End have to be 4 and 6 above them, Page up and Page
down 7, and the two symbols 1 and 8 - which leaves PASTE in cell 3, over ALT. That is
where it went when Home/Left and End/Right were aligned. Before moving a key, write the
eight columns out; the free cell is rarely the one that looks free.

## A run that says "no devices" is not a failing test

A test run reported `OpencodeTuiTest ... FAILED` after 37 s with `adb: no devices/
emulators found` from the harness: the wireless transport had dropped. The port is per
session - 44619, then 45249, then 38915 in one afternoon - so `python3 test/adb-connect.py`
reconnects and the same APK passes. The harness log under `android/app/build/e2e-*.log`
holds NUL bytes; read it with `tr -d '\000' < log | grep -a`, or the grep itself looks
like it found nothing.

## `ppwd` is the typing flake, not a rendering bug

Two runs failed with `awaitScreen` saying "the TUI never drew a screen" while its own
quoted screen showed the TUI drawn and `$ ppwd` answered `command not found`: the
`input keyevent` batch had delivered a doubled `p`. Read the quoted last screen before
believing the message; a drawn TUI with a mangled command means the typing sequence, and
a rerun is the answer.

## Ask which window is in front before believing a node is missing

A keyboard-toggle check dumped the UI right after `OpencodeTuiTest` and found no
`Keyboard` key. `dumpsys window | grep mCurrentFocus` showed
`com.termux/.app.TermuxActivity` - the test had left Termux in front, so the dump was
Termux's window. Print the focus line next to the dump, and drive the page again
(`am force-stop`, then the flow) instead of trusting a "not found".

## A keyboard key that remembers its state is a one-way switch

`toggleKeyboard()` used to decide from `keyboardShown`, a flag derived from the window
shrinking, and to hide with `hideSoftInputFromWindow` alone. On the opencode page the key
now shows and hides on alternate taps - `dumpsys input_method` reports `mInputShown=true`
then `false`, and `events.log` gets `keyboard:show` then `keyboard:hide`. Two things were
needed: ask the window rather than the flag
(`getRootWindowInsets().isVisible(WindowInsets.Type.ime())`, with the flag as the fallback
below API 30), and blur xterm's textarea (`blurTerm()`) before hiding - the IME is
anchored to that element, so a hide aimed at the window alone leaves the keyboard up.

## Environment facts this device has

- adb connects over TCP at `127.0.0.1:<port>`, and the port changes between
  sessions. `python3 test/adb-connect.py` finds it over mDNS - the phone
  advertises `_adb-tls-connect._tcp` - and connects; `--pair` prints the
  `adb pair` line. Termux's adb has no mDNS of its own (`adb mdns services`
  answers "not supported"), and `ss`, `/proc/net/tcp6` and
  `service.adb.tcp.port` are closed to an app, which is why the lookup uses
  python-zeroconf instead.
- That advertisement outlives the setting: with Wireless debugging off, mDNS
  still names the last port (38135 on one run here) and every address answers
  "connection refused". A refusal, not silence, is what "off" looks like.
- Termux is `com.termux` with prefix `/data/data/com.termux/files/usr`; opencode
  is 2.3.2 and its TUI is the only thing in this repo that never exits.
- `adb pull` cannot write to `/tmp` here (`Permission denied`); pull into the
  project and delete the file afterwards.
- The app's evidence, all under
  `/sdcard/Android/data/io.github.mbanucu.opencodesetup/files/`:
  `live-output.txt` (screen text as the page rendered it), `events.log`
  (`tail:{"cols":N}` and progress lines), `tui-screen.txt` (what a test wrote
  down), `last-result.txt` (the `check` answer, deleted before asking).

## A TUI run leaves a session; close its tab, delete it by id

Opening the TUI creates an opencode session. `ctrl+x w` is the leader `w`
(`session.tab.close`): it closes the tab but keeps the session record, which is
what `opencode session list` shows later. `OpencodeTuiTest` closes the tab it
opened and exits with `ctrl+x q`, so a run cannot add a tab to the next run's
title bar. It does not delete the session: the TUI never shows the id, and
`ctrl+d` is `session_delete` inside the session list but `app_exit` in the
composer, so pressing it blind can quit the app or hit a session that was
already there.

There is no ephemeral mode yet (upstream issue #53921); `opencode run
--archived` keeps scripted `run` sessions out of the lists, but not the TUI.

To remove the records by hand, on the device:

```bash
opencode session list --format json    # ids, newest first
opencode session delete ses_...        # one id; its child sessions go too
```

The leader is two real presses, so send it as two separate `adb shell input`
calls - `input keycombination 113 52` then `input keyevent 51` for `w`. A merged
call delivered no keystrokes at all on this device, the same trap the `!` in
`OpencodeTuiTest` records.
