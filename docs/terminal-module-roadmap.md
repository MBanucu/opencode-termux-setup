# Roadmap: replace xterm.js with the terminal module

Status: draft, 2026-11-20.

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
- The module is not an xterm replacement yet. It has no scrollback, selection,
  copy, mouse or bracketed-paste state, and it only carries part of `SGR`.
  Replacing the page before those are covered would regress daily use.

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
  insert/delete, scroll regions, `SGR` foreground/background (16, 256 and
  truecolor), bold brightness, caret blink.
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
- [x] The research note documents the measured WebView/xterm and native-view
  accessibility difference.
- [ ] The app uses the module. `PageActivity` still loads
  `assets/terminal.html` in a `WebView`; xterm.js still renders the page.

## Structure

The module is kept in small files with one job each, so a change lands in the
file named for it:

- [x] `Viewport` — the window over history and the live grid.
- [x] `HistoryRow` — one stored history row and its colours.
- [x] `AnsiPalette` — the 16-colour palette, the 256 cube and bold brightening.
- [x] `Selection` — an inclusive viewport cell range and the text it copies,
  joining wrapped rows.
- [x] `TerminalAccessibility` — row nodes, the prompt node, events and the
  announcement timer.
- [x] `Screen.csi` splits into private-mode handling (`applyModes`) and the
  non-private dispatch (`dispatch`).
- [x] `KeyEncoder` — named hardware keys and modified characters.
- [x] `ReplySink` — the caller's write path for query answers.

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

Do these in `terminal/src/main/java` (and `ScreenTest`) before touching the app
beyond the flag.

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
- [ ] Keep the app's footer keys able to send their bytes directly; the module
  must also be complete for a hardware keyboard.
- [ ] Tests with a scripted pty that echoes the received byte sequences.

Model state: named keys, DECCKM and the Ctrl/Alt character forms are covered by
`KeyEncoderTest`; modifier-encoded arrow sequences, the footer key path and the
scripted-pty test remain.

### 1.4 Query replies

- [x] Add a reply sink from `Screen`/`TerminalView` to the caller (the PTY).
- [x] Reply to DA1/DA2, DSR/CPR, XTVERSION, OSC 10/11 colours and the kitty
  keyboard query.
- [ ] Answer the DCS termcap question once the wrapper's terminfo name is known.
- [x] Do not reply when the program did not ask; avoid reply loops.
- [x] Tests for each reply, including escapes split across feeds.

Model state: the sink and every answer above are covered by `QueryReplyTest`;
the DCS termcap answer and wiring the app's `LiveStream` write path remain.

### 1.5 Mouse, bracketed paste and focus

- [ ] Parse and honour DEC mouse modes 1000/1002/1003/1006/1015; encode SGR
  mouse events.
- [ ] Bracketed paste (mode 2004) around clipboard text.
- [ ] Focus events (1004/2031) when the view gains/loses focus.
- [ ] Decide touch-to-mouse behaviour for the TUI; at minimum do not regress
  scrolling and selection.
- [ ] Tests for each mode and for paste wrapping.

### 1.6 Wide and combining characters

- [ ] Use a width table (`wcwidth`-style) for width-2 cells and combining marks.
- [ ] Store enough per cell for a wide glyph and its continuation.
- [ ] Tests for CJK, emoji, combining accents and cursor movement over them.

### 1.7 Remaining `SGR`

- [ ] Underline, reverse, italic, dim, strikethrough and hidden, as far as the
  app's pages use them.
- [ ] Keep 16/256/truecolor and bold-brightening behaviour.
- [ ] Tests for each attribute and for reset.

### 1.8 Performance

- [ ] Measure feed-to-frame time for opencode at 80x29 and 80x44, plus a
  fast-scrolling 3000-line buffer.
- [ ] Optimise only if the budget fails (row runs, glyph cache, fewer invalidations).
- [ ] Record the numbers in this roadmap or the module README.

Gate: the P0/P1 features pass their tests, and an xterm feature the app actually
uses is either present or explicitly accepted as dropped.

## Phase 2 — wire the app

The transport stays. Replace the WebView-specific half of `PageActivity`.

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

- How much scrollback, and should the alternate screen ever have any?
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
