package io.github.mbanucu.terminal;

/**
 * The window of lines a view shows: history above the live grid when the reader
 * has scrolled back, and the live grid alone at the bottom.
 *
 * <p>The offset is in rows of history, oldest at the top. While the reader is
 * scrolled back, output that pushes rows into history must not slide the rows
 * under them, so {@link #followOutput} moves the offset with the history; only
 * at the bottom does new output follow the bottom. Coming back is the caller's
 * cue from input, not the program's.
 */
final class Viewport {

    private final Screen screen;
    private int offset;

    Viewport(Screen screen) {
        this.screen = screen;
    }

    /** True when the live grid fills the view and new output is followed. */
    boolean atBottom() {
        return offset == 0;
    }

    /** How many history rows are above the live grid's top row. */
    int offset() {
        return offset;
    }

    /** How far back the view can go: the primary screen's history depth. */
    int maxOffset() {
        return screen.isAlternate() ? 0 : screen.historyRows();
    }

    void scrollBy(int lines) {
        scrollTo(offset + lines);
    }

    void scrollTo(int lines) {
        offset = Math.max(0, Math.min(maxOffset(), lines));
    }

    /** The cursor is on the live screen, so typing always comes back to it. */
    void scrollToBottom() {
        offset = 0;
    }

    /**
     * Keeps the row being read in place as output pushes rows into history.
     *
     * <p>A row added above the view moves the whole log down by one, so an
     * offset that stayed put would show the reader a different row. Only at the
     * bottom (offset zero) does the new output get followed instead.
     */
    void followOutput(int historyAdded) {
        if (screen.isAlternate()) {
            // A full-screen program owns the whole screen; there is no log to
            // stay in, and its own scrolling is not the view's.
            offset = 0;
            return;
        }
        if (historyAdded <= 0 || offset == 0) {
            return;
        }
        offset = Math.min(maxOffset(), offset + historyAdded);
    }

    /** After a resize or ED 3: the offset cannot name rows that no longer exist. */
    void clamp() {
        offset = Math.max(0, Math.min(maxOffset(), offset));
    }

    /** One visible row: history first, then the live grid. */
    String line(int row) {
        if (screen.isAlternate()) {
            return screen.line(row);
        }
        int history = historyRow(row);
        if (history >= 0 && history < screen.historyRows()) {
            return screen.historyLine(history);
        }
        return screen.line(history - screen.historyRows());
    }

    /** The foreground of one visible cell: 0xRRGGBB, or 0 for the default. */
    int foreground(int row, int col) {
        if (screen.isAlternate()) {
            return screen.foreground(row, col);
        }
        int history = historyRow(row);
        if (history >= 0 && history < screen.historyRows()) {
            return screen.historyForeground(history, col);
        }
        return screen.foreground(history - screen.historyRows(), col);
    }

    /** The background of one visible cell: 0xRRGGBB, or 0 for the default. */
    int background(int row, int col) {
        if (screen.isAlternate()) {
            return screen.background(row, col);
        }
        int history = historyRow(row);
        if (history >= 0 && history < screen.historyRows()) {
            return screen.historyBackground(history, col);
        }
        return screen.background(history - screen.historyRows(), col);
    }

    /** The combined row index at the top of the view. */
    private int historyRow(int row) {
        return screen.historyRows() - offset + row;
    }
}
