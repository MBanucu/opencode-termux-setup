# Notes for agents

What this repository taught the hard way, mostly while building the instrumented
tests under `android/app/src/androidTest/`. Read this before changing the
terminal page or those tests: each item is a mistake that cost a run, and most
of them look like harmless simplifications from the outside.

Commands referenced here are the ones in the README: `bash test/local-build.sh`
builds and runs everything, `--only <class>[#method]` runs one test class, and
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
- The app's evidence, all under
  `/sdcard/Android/data/io.github.mbanucu.opencodesetup/files/`:
  `live-output.txt` (screen text as the page rendered it), `events.log`
  (`tail:{"cols":N}` and progress lines), `tui-screen.txt` (what a test wrote
  down), `last-result.txt` (the `check` answer, deleted before asking).
