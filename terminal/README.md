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
| `Screen` | the grid: the ECMA-48 subset a TUI uses — alternate screen, cursor addressing, erases, insert/delete, scroll regions, SGR colour and attributes. Package-private; not part of the API |
| `HistoryRow` | one row of primary-screen scrollback, with the colours and SGR attributes of its cells. Package-private |
| `AnsiPalette` | the 16-colour palette, the 256-colour cube and bold brightening. Package-private |
| `CellAttributes` | the per-cell SGR bits the view draws differently: underline, reverse, dim, italic, strikethrough, hidden. Package-private |
| `Width` | the display width of a character in cells: 2 for CJK/fullwidth, 1 otherwise. Package-private |
| `Viewport` | the window over the history and the live grid: scroll offset, clamping, follow-at-bottom. Package-private |
| `Selection` | an inclusive cell range over the viewport, and the text it copies with hard breaks kept and wrapped rows joined. Package-private |
| `KeyEncoder` | the bytes a hardware key sends, normal or application-cursor mode. Package-private |
| `ReplySink` | the caller's write path for the answers a screen owes a querying program |
| `TerminalAccessibility` | the row and prompt nodes, their events, and the announcement timer. Package-private |

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

A row the terminal wrapped itself is marked as continuing on the next row, and
`text()`/`screenText()` join the two instead of inserting a newline the program
never wrote; the flag follows the row into history and survives a resize.

A program on the alternate screen receives hardware keys as terminal bytes:
arrows, Home/End, Page up/down, Insert/Delete, Esc, Tab, Backspace and
F1-F12, with DECCKM switching arrows and Home/End to the `ESC O` forms.
Ctrl, Alt and Shift modify a typed character or a cursor key: a modified arrow,
Home or End is `ESC [ 1 ; m` plus its final byte, where `m` is 1 plus Shift,
2 for Alt and 4 for Ctrl, so Alt+Left sends `ESC[1;3D`.
Bracketed-paste mode (2004) is tracked too, so a paste can be wrapped when the
program asks for it.
Focus reporting (1004/2031) is tracked, and the view sends `ESC[I`/`ESC[O` when
the program asks; mouse modes 1000/1002/1003 with 1006/1015 encodings are
tracked, though touch still scrolls.

Queries are answered through a `ReplySink`: DA1/DA2, DSR and CPR, XTVERSION,
the kitty keyboard query and OSC 10/11 colours. Point it at the pty's
write path; with no sink the screen stays quiet.

Dragging the view scrolls back through the primary screen's history, and a fling
carries on at finger speed. While scrolled back, output that pushes rows into
history keeps the row being read in place rather than sliding it away; a view at
the bottom follows new output, and typing returns it to the bottom. The caret is
not drawn while scrolled back, because its row is below the view.

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

`bash terminal/test.sh` compiles the grid, its history rows and colour tables,
and the viewport with `src/test`, then runs them on the JVM. The screen checks
cover cursor addressing, erases,
insert/delete, scroll regions, primary-screen scrollback (limits, colours,
resize, alternate-screen isolation and `ED 3`),
the alternate screen, wrapping, SGR colour (16, 256 and truecolor, `1`
brightening a foreground) and the other attributes (underline, reverse, dim,
italic, strikethrough, hidden), the
caret (DEC mode 25, synchronized updates, DECSCUSR style and DEC mode 12 blinking),
and the sequences that must print nothing (OSC titles and DCS payloads, cursor
visibility),
including escapes split across two `feed` calls. The viewport checks cover
scrolling into history, clamping at both ends, output while scrolled back not
moving the row being read, the alternate screen resetting the view, `ED 3`, and
a 3000-line history. The selection checks cover inclusive ranges, backwards
drags, hard breaks versus wrapped rows, scrollback and clamping. The attribute
checks cover every attribute code, its reset, `SGR 0`, and attributes surviving
history and a resize. The key checks cover each named
sequence, application-cursor mode, and the control/escape forms. The reply checks cover every
answer and queries split across feeds. They need no
device, no Gradle and no test framework, because the models are plain Java. The view itself needs a device
and is covered by `samples/e2e.sh`.

## What it is not

- **Not a VT emulator.** `SGR` colour is drawn — 16, 256 and truecolor, with
  `1` brightening a palette foreground — and so are underline, reverse, dim,
  italic, strikethrough and hidden. It keeps a primary-screen scrollback (the
  alternate screen is the program's own) and a cell-range selection model that
  copies hard breaks and wrapped rows correctly, but it has no long-press
  handles, clipboard wiring or mouse events; bracketed paste and focus modes
  are tracked, though a paste is not wrapped yet. Width-2 CJK glyphs take two
  cells and wrap whole; combining marks and emoji do not. Enough to draw a TUI
  and read it; not enough to be someone's daily terminal without more work.
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
