package io.github.mbanucu.terminal;

/**
 * A cell range over the visible rows, for copying what a reader selected.
 *
 * <p>Rows and columns are viewport coordinates, so a range can start in
 * scrollback and end on the live grid. Both ends are inclusive. Text is taken
 * cell by cell: a hard line break becomes a newline, while a row the terminal
 * wrapped is joined to its continuation. Drawing the highlight and the handles
 * stays the view's job.
 */
final class Selection {

    private final Screen screen;
    private final Viewport viewport;
    private boolean active;
    private int anchorRow;
    private int anchorCol;
    private int focusRow;
    private int focusCol;

    Selection(Screen screen, Viewport viewport) {
        this.screen = screen;
        this.viewport = viewport;
    }

    /** Starts a range at one cell; a second call replaces it. */
    void begin(int row, int col) {
        active = true;
        anchorRow = clampRow(row);
        anchorCol = clampCol(col);
        focusRow = anchorRow;
        focusCol = anchorCol;
    }

    /** Moves the other end of the range; starts one if there is none yet. */
    void extendTo(int row, int col) {
        if (!active) {
            begin(row, col);
        }
        focusRow = clampRow(row);
        focusCol = clampCol(col);
    }

    void clear() {
        active = false;
    }

    boolean isEmpty() {
        return !active;
    }

    /** True when the cell is inside the range; blank cells count too. */
    boolean contains(int row, int col) {
        if (!active) {
            return false;
        }
        if (row < firstRow() || row > lastRow()) {
            return false;
        }
        if (row == firstRow() && col < firstCol()) {
            return false;
        }
        return row != lastRow() || col <= lastCol();
    }

    /** The selected text, with hard breaks kept and wrapped rows joined. */
    String text() {
        if (!active) {
            return "";
        }
        int firstRow = firstRow();
        int lastRow = lastRow();
        int firstCol = firstCol();
        int lastCol = lastCol();
        StringBuilder copied = new StringBuilder();
        for (int row = firstRow; row <= lastRow; row++) {
            String line = viewport.line(row);
            int from = row == firstRow ? Math.min(firstCol, line.length()) : 0;
            int to = row == lastRow ? Math.min(lastCol + 1, line.length()) : line.length();
            if (to > from) {
                copied.append(line, from, to);
            }
            if (row < lastRow && !viewport.wrapped(row)) {
                copied.append('\n');
            }
        }
        return copied.toString();
    }

    private int firstRow() {
        return Math.min(anchorRow, focusRow);
    }

    private int lastRow() {
        return Math.max(anchorRow, focusRow);
    }

    private int firstCol() {
        return forward() ? anchorCol : focusCol;
    }

    private int lastCol() {
        return forward() ? focusCol : anchorCol;
    }

    /** Reading order: earlier row, then earlier column on the same row. */
    private boolean forward() {
        return anchorRow < focusRow
                || (anchorRow == focusRow && anchorCol <= focusCol);
    }

    private int clampRow(int row) {
        return Math.max(0, Math.min(screen.rows() - 1, row));
    }

    private int clampCol(int col) {
        return Math.max(0, Math.min(screen.cols() - 1, col));
    }
}
