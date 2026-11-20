package io.github.mbanucu.terminal;

import java.util.Arrays;

/**
 * One primary-screen row that has scrolled off the top, with the colours and
 * attributes of each of its cells.
 *
 * <p>Whole cells, not just text: a coloured or underlined row in a TUI's output
 * has to keep its look when it moves into history, and a resize has to keep what
 * still fits together with those cells.
 */
final class HistoryRow {

    private final char[] chars;
    private final int[] fg;
    private final int[] bg;
    private final int[] attributes;
    private final boolean wrapped;

    HistoryRow(char[] chars, int[] fg, int[] bg, int[] attributes, boolean wrapped) {
        this.chars = chars;
        this.fg = fg;
        this.bg = bg;
        this.attributes = attributes;
        this.wrapped = wrapped;
    }

    String text() {
        int end = chars.length;
        while (end > 0 && (chars[end - 1] == ' ' || chars[end - 1] == 0)) {
            end--;
        }
        StringBuilder text = new StringBuilder(end);
        for (int col = 0; col < end; col++) {
            if (chars[col] != 0) {
                text.append(chars[col]);
            }
        }
        return text.toString();
    }

    char cell(int col) {
        return col >= 0 && col < chars.length ? chars[col] : ' ';
    }

    int foreground(int col) {
        return col >= 0 && col < fg.length ? fg[col] : 0;
    }

    int background(int col) {
        return col >= 0 && col < bg.length ? bg[col] : 0;
    }

    /** The SGR attributes of one cell, as {@link CellAttributes} bits. */
    int attribute(int col) {
        return col >= 0 && col < attributes.length ? attributes[col] : 0;
    }

    /** True when this row continued on the next one. */
    boolean wrapped() {
        return wrapped;
    }

    HistoryRow resize(int newCols) {
        if (newCols == chars.length) {
            return this;
        }
        char[] nextChars = new char[newCols];
        Arrays.fill(nextChars, ' ');
        int[] nextFg = new int[newCols];
        int[] nextBg = new int[newCols];
        int[] nextAttr = new int[newCols];
        int keep = Math.min(newCols, chars.length);
        System.arraycopy(chars, 0, nextChars, 0, keep);
        System.arraycopy(fg, 0, nextFg, 0, keep);
        System.arraycopy(bg, 0, nextBg, 0, keep);
        System.arraycopy(attributes, 0, nextAttr, 0, keep);
        return new HistoryRow(nextChars, nextFg, nextBg, nextAttr, wrapped);
    }
}
