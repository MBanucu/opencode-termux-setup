# terminal — an accessible terminal view

A `View` that draws a character grid and publishes it to accessibility services as
rows, plus an editable prompt node. This is what lets a screen reader read and type
into a terminal that is not an `EditText`.

Extracted from [`samples/native-terminal`](../samples/native-terminal), where it was
measured on a device: hundreds of row nodes in one dump, `ACTION_SET_TEXT` reaching
a Termux shell, `TYPE_ANNOUNCEMENT` as output arrives. The write-up, including the
comparison with xterm.js in a WebView, is
[`docs/tty-accessibility-research.md`](../docs/tty-accessibility-research.md).

## What is here

| type | role |
|---|---|
| `TerminalView` | the view: grid, rendering, `AccessibilityNodeProvider`, `InputConnection`, announcements |
| `Screen` | the grid: the ECMA-48 subset a TUI uses — alternate screen, cursor addressing, erases, insert/delete, scroll regions, SGR foreground and background. Package-private; not part of the API |

`TerminalView` loads `terminal-mono.ttf` from the host app's assets when present —
the same DejaVu Sans Mono the WebView page uses — and falls back to the system
monospace when it is not. The sample build copies the font into a sample's assets.

It is transport-agnostic: the caller feeds text and is told what to do.

```java
TerminalView terminal = new TerminalView(context);
terminal.setListener(line -> sendToTheProgram(line + "\r"));   // a submitted line
terminal.setSizeListener((cols, rows) -> resizeThePty(cols, rows));
terminal.feed(chunkFromThePty);        // often; a partial escape is buffered
```

`feed` takes whole chunks and does not care where they split: an escape sequence cut
in half is held until it is complete. `screenText()` returns the primary screen's
history above the visible grid; on the alternate screen it returns only that
screen, because a full-screen program owns the display.

On the alternate screen the caret is the view's to draw as well: a block at the
grid's cursor, hidden while the program says DEC mode 25 is off, and blinking when
it asks for a blinking style (`DECSCUSR 1` or DEC mode 12) — the blink is the
terminal's, so a program that wants one sends nothing per blink. A full-screen
program positions the caret and leaves drawing it to the terminal, so without that
a TUI — opencode's composer, the sample's box and echo TUIs — has no visible caret
at all.

## The accessibility contract

- one virtual child per non-blank row, id fixed by row position, class
  `android.widget.TextView`, with text, bounds and accessibility-focus actions;
- one editable node (class `android.widget.EditText`, `isEditable`, `ACTION_SET_TEXT`,
  `ACTION_PASTE`, `ACTION_FOCUS`) that also accepts the IME's `InputConnection`;
- `ACTION_SET_TEXT` text ending in a newline submits the line, because a service has
  no other way to press Enter on a view that is not an `EditText`;
- `TYPE_WINDOW_CONTENT_CHANGED` per feed, and `TYPE_ANNOUNCEMENT` coalesced to at most
  one per 400 ms of quiet, so a repainting TUI does not speak a syllable stream;
- every event is guarded by `AccessibilityManager.isEnabled()`. Without that check,
  `requestSendAccessibilityEvent` throws `IllegalStateException: Accessibility off`,
  which is a normal phone's state.

## Tests

`bash terminal/test.sh` compiles `Screen` with `src/test` and runs it on the JVM:
cursor addressing, erases, insert/delete, scroll regions, primary-screen
scrollback (limits, colours, resize, alternate-screen isolation and `ED 3`),
the alternate screen, wrapping, SGR foreground and background colour (16, 256
and truecolor, and `1`
brightening a foreground), the
caret (DEC mode 25, synchronized updates, DECSCUSR style and DEC mode 12 blinking),
and the sequences that must print nothing (OSC titles and DCS payloads, cursor
visibility),
including escapes split across two `feed` calls. It needs no device,
no Gradle and no test framework, because the grid is plain Java. The view needs a
device and is covered by `samples/e2e.sh`.

## What it is not

- **Not a VT emulator.** `SGR` foreground and background colour are drawn — 16,
  256 and truecolor, with `1` brightening a palette foreground — but underline,
  reverse and the rest are dropped. It keeps a primary-screen scrollback (the
  alternate screen is the program's own), but has no selection, copy, mouse or
  bracketed-paste state. Enough to draw a TUI and read it; not enough to be
  someone's daily terminal without more work.
- **Not a transport.** An app-owned `forkpty` pty and Termux's `RUN_COMMAND` session
  are both in the sample, and both are the caller's business. The size callback is the
  whole contract between the view and the program.
- **Not verified against TalkBack**, only against an `AccessibilityService` probe, on
  one device (Android 15, API 35).

## Roadmap

The staged plan for replacing the app's xterm.js page with this view — including
the scrollback, selection, keymap and query-reply work still needed — is in
[`docs/terminal-module-roadmap.md`](../docs/terminal-module-roadmap.md).

## Using it

In this repository the samples compile `terminal/src/main/java` directly —
`samples/build.sh` adds it to each sample's sources, which is exactly the source a
library module would hold:

```gradle
android {
    sourceSets.main.java.srcDirs += "$rootDir/terminal/src/main/java"
}
```

Or give it its own `com.android.library` module and point that module's source set at
the same directory. `AndroidManifest.xml` here is the empty library manifest, so there
is nothing to merge.

Licensed like the rest of this repository.
