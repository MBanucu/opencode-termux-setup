# Roadmap: replace xterm.js with the terminal module

Status: draft, 2026-11-20. Last reviewed after the SGR-attribute,
modifier-key, query-reply and bracketed-paste slices.

## Where this stands

Phase 1 is most of the way there: the module parses the escapes a TUI uses,
keeps a 3000-row primary-screen scrollback with colours and attributes, has a
cell-range selection model that joins wrapped rows, answers the common device
queries, and encodes hardware keys including modified cursor keys. Thirteen JVM
suites (298 checks) pass without a device, and `samples/native-terminal` builds.

What remains is concentrated in four places: the selection/copy UI (1.2),
mouse encoding and paste wrapping (1.5), wide and combining characters (1.6),
and any measurement at all (1.8). The app is still on the xterm page;
`PageActivity` has only been read, not changed.

The app's terminal page currently uses a WebView and xterm.js. The
[`terminal/`](../terminal/README.md) module can draw the same bytes in a native
`View`, with row nodes and an editable prompt that a screen reader can use. This
is the plan for moving the app onto it without losing the terminal features the
page has today.

## Why

- `PageActivity` publishes the WebView's `contentDescription` only after a
  command finishes. A TUI never finishes, so the opencode page is invisible to
  accessibility while it runs.
- The native view was measured with a real Termux session: rows as `TextView`
  nodes, `ACTION_SET_TEXT` reaching the shell, output announced, and the grid
  resize reaching the PTY.
- The module is not an xterm replacement yet. It has the scrollback and
  selection *models*, SGR colour and attributes, query replies, key encoding
  and the input-mode state, but no selection/copy UI, no mouse encoding, no
  wide-character handling and no performance numbers. Replacing the page before
  those are covered would regress daily use.

## Guardrails

- Keep Termux as the PTY owner. An app-owned `forkpty` cannot run
  `$PREFIX/bin/opencode` (Termux's home is `0700`, and an app-targeted binary in
  the app's own files is `EACCES`), so the existing `LiveStream` /
  `TermuxCommands` transport stays.
- Do not adopt Termux's emulator just for accessibility. Its `View` is the same
  one-opaque-node problem this module exists to solve.
- Keep the WebView as a fallback until each migration gate passes. Add a flag
  rather than switching the page in one commit.
- Migrate the read-only command/output pages first and the interactive opencode
  TUI last.
- The research note's recommendation still applies: xterm's
  `screenReaderMode` is a stopgap, this module is the native destination, and
  owning the PTY or using Termux's emulator is not part of this migration.

## Current state

- [x] `TerminalView` and `Screen`: alternate screen, cursor addressing, erases,
  insert/delete, scroll regions, `SGR` colour (16, 256 and truecolor), bold,
  dim, underline, reverse, italic, strikethrough and hidden, caret blink.
- [x] Key encoding: named hardware keys, DECCKM, the Ctrl/Alt character forms
  and xterm's modifier parameter for arrows, Home and End.
- [x] Query replies: DA1/DA2, DSR/CPR, XTVERSION, OSC 10/11 and the kitty
  query, sent through a `ReplySink`.
- [x] Bracketed-paste mode (2004) tracked and exposed to the view.
- [x] Accessibility: one node per non-blank row, an editable prompt node,
  `ACTION_SET_TEXT`, `InputConnection`, coalesced announcements.
- [x] Resize callback from the view to the app (`setSizeListener`).
- [x] The bundled `terminal-mono.ttf` is loaded by `TerminalView`, and the
  80-column fit uses the face's real advance.
- [x] `tui-width.py` reproduces the block-glyph overhang with system monospace
  and shows it gone with the bundled font.
- [x] `samples/native-terminal` proves the view against a real Termux session,
  typing and resize included.
- [x] Primary-screen scrollback model: a 3000-row default, per-cell colours,
  resize, alternate-screen isolation, partial-region exclusion, `ED 3`, and
  `textWithHistory()`; `TerminalView.screenText()` includes it.
- [x] Cell-range selection model across the grid and history, joining wrapped
  rows; no long-press UI, handles or clipboard yet.
- [x] The research note documents the measured WebView/xterm and native-view
  accessibility difference.
- [ ] The app uses the module. `PageActivity` still loads
  `assets/terminal.html` in a `WebView`; xterm.js still renders the page.

## Structure

The module is kept in small files with one job each, so a change lands in the
file named for it:

- [x] `Viewport` — the window over history and the live grid.
- [x] `HistoryRow` — one stored history row, its colours and its attributes.
- [x] `Scrollback` — the bounded list of rows that left the top.
- [x] `AnsiPalette` — the 16-colour palette, the 256 cube and bold brightening.
- [x] `CellAttributes` — the per-cell SGR bits.
- [x] `Sgr` — the colour and attribute state, and the foreground to paint with.
- [x] `EscapeParser` — chunk buffering, split sequences and OSC/DCS payloads.
- [x] `Modes` — caret visibility/blink, DECCKM, synchronized update and
  bracketed paste.
- [x] `Replies` — the fixed bytes for the queries the screen answers.
- [x] `Selection` — an inclusive viewport cell range and the text it copies,
  joining wrapped rows.
- [x] `TerminalAccessibility` — row nodes, the prompt node, events and the
  announcement timer.
- [x] `CsiCommands` — the non-private CSI table; `Screen` keeps the state and
  one operation per command.
- [x] `KeyEncoder` — named hardware keys, modified cursor keys and characters.
- [x] `ReplySink` — the caller's write path for query answers.

`Screen` is still the largest file (grid, both buffers, resize and mode
handling). If it grows again, the next extraction is the two-screen buffer
state: chars, foreground, background, attributes and wrap flags per row.

## Phase 0 — decide the shape

- [ ] Choose the integration surface. Prefer a new native terminal page (or a
  `TerminalSurface` abstraction) over editing `PageActivity` in place; it is
  1,405 lines of WebView-specific code.
- [ ] Add a `terminal_native` flag to `TerminalActivity` / `PageActivity`,
  default off, so the old page and the native page can run side by side.
- [ ] Record baseline evidence from the xterm page: 80x29 / 80x44 screenshots,
  typing, resize, scrolling, selection/copy, accessibility dump.
- [ ] Define parity for this app, not for every terminal:
  - scrollback depth (the xterm page uses 3000) and touch scrolling;
  - selection and copy, including the existing copy-all key;
  - hardware/IME key encoding (the footer keys can keep sending bytes directly);
  - query replies (DA, DSR, OSC 10/11, kitty, DCS);
  - mouse, bracketed paste and focus events if the opencode TUI uses them;
  - wide/combining characters and the `SGR` attributes the pages use;
  - 80 columns, the `terminal_font` extra, and `terminal_fraction` heights.
- [ ] Set a frame-time / jank budget for an 80x24 and an 80x44 TUI.
- [ ] Decide how long the xterm fallback remains after the native page ships.
- [ ] Document the chosen scrollback, selection and fallback decisions in this
  file or the module README.

Gate: the flag exists, the baseline is recorded, and the parity checklist is
agreed.

## Phase 1 — terminal behavior

Do these in `terminal/src/main/java` (and the JVM suites under
`terminal/src/test`) before touching the app beyond the flag.

### 1.1 Scrollback and touch scrolling

- [x] Add a history buffer for the primary screen; keep the alternate screen
  separate, as a full-screen program owns its grid.
- [x] Preserve cells and foreground/background across history and resize.
- [x] Preserve wrap information across history and resize: a wrapped row stays
  marked through scrolling, line inserts/deletes and a resize, and extracted
  text joins it to its continuation.
- [x] Add a scroll offset to `TerminalView`; follow output only while the view
  is at the bottom; return to the bottom on input.
- [x] Touch drag/fling scrolling; the caret is not drawn while scrolled back,
  because its row is below the view (selection arrives in 1.2).
- [x] Make `screenText()` include history, so copy, `live-output.txt` and the
  accessibility snapshot do not lose earlier output.
- [x] Model tests: overflow, limits, resize, alternate-screen isolation, partial
  regions, and `ED 3`.
- [ ] View tests still needed: an instrumented drag/fling on a device, and
  resize while scrolled.

Model state: screen and viewport checks pass. The view keeps a scroll offset,
follows output only at the bottom, and drags or flings the history; the
3000-line stress case is covered by the model test. Wrap flags travel with a
row into history and through resizes and line edits.

### 1.2 Selection and copy

Model state: inclusive viewport ranges, backwards drags, wrapped joins,
scrollback mapping and clamping are covered by `SelectionTest`; the long-press
UI, clipboard and resize test remain.

- [x] A cell-range selection model across the visible grid and history.
- [ ] Long-press word/line selection, drag handles, and a copy action.
- [ ] Keep the existing whole-screen copy key (`copy all`) as a separate action.
- [ ] Clipboard integration and a clear visual selection; no link handling
  unless the xterm page already offers it.
- [ ] A test for selection across a resize, and an instrumented long-press
  test on a device.

### 1.3 Key encoding

- [x] Encode hardware keys: arrows, Home/End, PgUp/PgDn, Insert/Delete, Esc,
  Tab, Backspace and F1-F12.
- [x] DECCKM/application-cursor mode for arrows and Home/End.
- [x] Ctrl/Alt modifiers for typed characters and IME action; the IME
  text/Enter path is unchanged.
- [x] Modifier-encoded cursor keys: a modified arrow, Home or End is
  `CSI 1 ; m A|B|C|D|H|F` with `m` = 1 + Shift + 2*Alt + 4*Ctrl.
- [ ] Keep the app's footer keys able to send their bytes directly; the module
  must also be complete for a hardware keyboard.
- [ ] Tests with a scripted pty that echoes the received byte sequences.

Model state: named keys, DECCKM, the Ctrl/Alt character forms, the
modifier-encoded cursor keys and the remaining control bytes are covered by
`KeyEncoderTest` and `CharacterTest`. `TerminalView.encodedKey` is the only
Android-key mapping. The footer is still separate: `PageActivity.type(byte[])`
and `control(byte[])` spell their own cursor sequences and control codes, so
the module is not yet what the footer sends through.

### 1.4 Query replies

- [x] Add a reply sink from `Screen`/`TerminalView` to the caller (the PTY).
- [x] Reply to DA1/DA2, DSR/CPR, XTVERSION, OSC 10/11 colours and the kitty
  keyboard query.
- [ ] Answer the DCS termcap question once the wrapper's terminfo name is known.
- [x] Do not reply when the program did not ask; avoid reply loops.
- [x] Tests for each reply, including escapes split across feeds.

Model state: the sink and every answer above are covered by `QueryReplyTest`,
and the bytes live in `Replies`. The DCS termcap answer waits on the wrapper's
terminfo name, and nothing in the app points a sink at `LiveStream` yet.

### 1.5 Mouse, bracketed paste and focus

- [x] Track DEC mouse modes 1000/1002/1003 and coordinate encodings 1006/1015,
  and expose them to the view.
- [ ] Encode mouse events and honour the tracking modes on touch; the
  touch-to-mouse decision below comes first.
- [x] Track bracketed-paste mode (2004) and expose it to the view.
- [ ] Wrap clipboard text in `ESC[200~` / `ESC[201~` when the mode is on.
- [x] Focus events (1004/2031): the view sends `ESC[I` / `ESC[O` through its
  raw-input path when the program asked for them.
- [ ] Decide touch-to-mouse behaviour for the TUI; at minimum do not regress
  scrolling and selection.
- [ ] Tests for mouse encoding and for paste wrapping.

Model state: bracketed-paste mode, the mouse-mode/encoding state and focus
reporting are covered by `InputModeTest`; the alternate screen leaves bracketed
paste on. Mouse events are not encoded and touch still scrolls, because the
touch-to-mouse behaviour is an open decision. `PageActivity.paste()` still sends
clipboard bytes raw, so the wrapping half of 2004 has no caller yet.

### 1.6 Wide and combining characters

- [ ] Use a width table (`wcwidth`-style) for width-2 cells and combining marks.
- [ ] Store enough per cell for a wide glyph and its continuation.
- [ ] Tests for CJK, emoji, combining accents and cursor movement over them.

Model state: a cell is one `char`, so a surrogate pair or a combining mark
occupies two cells today; `line()` trims spaces and NULs from the right. A width
table and a continuation marker have to land in `Screen`, `HistoryRow` and
`TerminalView` together, because history and the viewport copy cells unchanged.

### 1.7 Remaining `SGR`

- [x] Underline, reverse, italic, dim, strikethrough and hidden, as far as the
  app's pages use them.
- [x] Keep 16/256/truecolor and bold-brightening behaviour; SGR 22 ends bold
  and dim together.
- [x] Tests for each attribute and for reset, plus attributes surviving
  scrollback and a resize.

Model state: every attribute above is parsed into `CellAttributes` bits, stored
per cell (and per history cell), and drawn by `TerminalView` — reverse swaps the
cell's colours, dim mixes the glyph with its background, italic selects an
italic face, and underline and strikethrough are drawn as lines. `AttributeTest`
covers each code, its reset, `SGR 0`, history and resize. A hidden cell is still
in the grid, and so still reaches `screenText()`, copy and the accessibility
rows; xterm hides only the glyph. That is a visible-behaviour decision left
open, not an implementation detail.

### 1.8 Performance

- [ ] Measure feed-to-frame time for opencode at 80x29 and 80x44, plus a
  fast-scrolling 3000-line buffer.
- [ ] Optimise only if the budget fails (row runs, glyph cache, fewer invalidations).
- [ ] Record the numbers in this roadmap or the module README.

Model state: nothing has been measured. The first task is instrumentation, not
an optimisation: feed-to-frame time at 80x29 and 80x44 plus a fast 3000-line
scroll, with the numbers written down here.

Gate: the P0/P1 features pass their tests, and an xterm feature the app actually
uses is either present or explicitly accepted as dropped.

## Phase 2 — wire the app

The transport stays. Replace the WebView-specific half of `PageActivity`.

Known starting points from reading the page: `outputBox()` builds the `WebView`
and loads `terminal.html`; output arrives through `LiveStream` and is pushed
with `writeB64`/`script`; the footer sends through `PageActivity.type(byte[])`,
which has its own cursor and control table rather than `KeyEncoder`; `Replies`
has no sink in the app yet, so a native page must point `setReplySink` at
`LiveStream.send`. The three responsibilities to keep on the page are the
footer rows, the progress strip and `live-output.txt`.

- [ ] Add the native page/surface and the `terminal_native` flag; keep the xterm
  page behind the other value.
- [ ] Replace `outputBox()`'s `WebView` with `TerminalView` (or the new surface),
  keeping the `FrameLayout`, progress strip and footer key rows.
- [ ] Feed: open `LiveStream` and call `terminal.feed(chunk)` instead of
  `writeB64`/`script`; remove `waitingScripts` and `outputLoaded`.
- [ ] Input: `setListener` / `setRawInput` write to `LiveStream`; keep the footer
  `type(bytes)` and paste paths working.
- [ ] Resize: `setSizeListener` calls the existing `resizePty` / control-port
  path. Remove `fitNow`, `lockFit`, `thawFit`, `tailInfo` and the WebView
  `onResize` bridge where the view now supplies the same information.
- [ ] Accessibility: rely on the view's row nodes and editable node; stop setting
  the terminal's `contentDescription` only when a command ends. Keep the
  quiet-window announcements.
- [ ] Copy: keep copy-all via `screenText()`; add selection copy in Phase 1.
- [ ] `live-output.txt`: write the view's screen text on the same cadence as
  today, or at quiet boundaries; preserve the file the tests and `adb` read.
- [ ] Font and layout: honour `terminal_font` (add a view font-size API or map
  it to the fitted size) and `terminal_fraction`; keep the 80-column floor and
  the resize broadcast path.
- [ ] Keyboard: focus the view, use `WindowInsets` for the IME state, and drop
  the xterm `textarea` focus/blur calls.
- [ ] Update the app's tests that read `tail:` / `resize:` events, the
  accessibility node, or `live-output.txt`; keep the assertions meaningful
  rather than deleting them.

### 2.1 Read-only pages first

- [ ] Migrate the command/output pages that do not take typing (install, check,
  logs) behind the flag.
- [ ] Compare screenshots, the output file, resize and accessibility against the
  xterm baseline.
- [ ] Look for long-output behaviour: scrollback, copy-all, and the last line
  staying visible.

Gate: the read-only pages pass the app suite with the flag on, with xterm still
available as the fallback.

## Phase 3 — migrate the TUI page

- [ ] Enable the native surface for the interactive opencode page.
- [ ] Verify the entry page, typing, shell mode, selection/copy, scrollback,
  resize and keyboard toggling at 80x29 and 80x44.
- [ ] Run the accessibility probe/TalkBack checks: rows, editable prompt,
  announcements, no duplicate speech.
- [ ] Compare the opencode entry page with the recorded/Termux screenshots.
- [ ] Run a full app e2e pass and the terminal module tests.
- [ ] Roll out the default only after the fallback has been exercised on the
  target device.

Gate: the interactive page passes without the WebView, and the fallback can be
retired.

## Phase 4 — remove xterm

- [ ] Delete `assets/terminal.html` and `assets/xterm/` once the native page is
  the default and the fallback is no longer needed.
- [ ] Remove the WebView imports, the JS bridge, `writeB64`/`script`, and the
  xterm-specific layout code from `PageActivity`.
- [ ] Update README credits and licensing references (xterm.js is MIT) and the
  `samples/README.md` comparison.
- [ ] `git grep -n xterm` should leave only history/docs, not runtime code.
- [ ] Empty `assets/` of the font only if the app no longer needs the WebView
  copy; the module still loads `terminal-mono.ttf`.

## Definition of done

- [ ] Scrollback, touch scrolling, selection and copy work on the native page.
- [ ] A hardware keyboard, the IME, the footer keys and paste all reach the PTY.
- [ ] Query replies, mouse, bracketed paste and focus events are either
  implemented or explicitly not needed by the app's programs.
- [ ] Wide/combining characters and the `SGR` attributes in use render correctly.
- [ ] The native page passes the app's e2e suite, the terminal module's tests,
  and the accessibility probe.
- [ ] 80x29 and 80x44 opencode pages match the recorded screen at cell level.
- [ ] The xterm WebView is no longer on the terminal path; `terminal.html` and
  `assets/xterm/` are removed.
- [ ] The frame-time budget is met, and the numbers are recorded.

## Open questions

- ~~How much scrollback, and should the alternate screen ever have any?~~
  Answered by the model: 3000 primary-screen rows (`Screen.DEFAULT_HISTORY_LINES`)
  and none on the alternate screen.
- Is cell selection enough, or does the app need word/line/block selection?
- Is a subset VT parser acceptable indefinitely, or should a full parser be
  adopted once scrollback/selection are in place?
- Which query replies do the app's programs actually wait for? The opencode page
  draws without them, but a general shell may not.
- Do the opencode page and the app's checks need mouse input, or is touch only
  for scrolling/selection?
- How should a screen reader navigate a TUI: rows, a live region, or a
  "read from here" model? The current announcements are coalesced but still
  screen-shaped, not document-shaped.
- How long should the xterm fallback stay after the native page ships?

## References

- [TTY accessibility research](../docs/tty-accessibility-research.md)
- [Terminal module README](../terminal/README.md)
- [Sample apps](../samples/README.md)
- [xterm page](../android/app/src/main/assets/terminal.html)
- [PageActivity](../android/app/src/main/java/io/github/mbanucu/opencodesetup/PageActivity.java)
- [TerminalActivity](../android/app/src/main/java/io/github/mbanucu/opencodesetup/TerminalActivity.java)
- [LiveStream](../android/app/src/main/java/io/github/mbanucu/opencodesetup/LiveStream.java)
- [TermuxCommands](../android/app/src/main/java/io/github/mbanucu/opencodesetup/TermuxCommands.java)
- [Native terminal sample](../samples/native-terminal)
- [Width test TUI](../samples/native-terminal/assets/tui-width.py)
