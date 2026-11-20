package io.github.mbanucu.terminal;

import java.util.Arrays;

/**
 * One primary-screen row that has scrolled off the top, with the colours of
 * each of its cells.
 *
 * <p>Whole cells, not just text: a coloured row in a TUI's output has to keep
 * its foreground and background when it moves into history, and a resize has to
 * keep what still fits together with those colours.
 */
final class HistoryRow {

    private final char[] chars;
    private final int[] fg;
    private final int[] bg;
    private final boolean wrapped;

    HistoryRow(char[] chars, int[] fg, int[] bg, boolean wrapped) {
        this.chars = chars;
        this.fg = fg;
        this.bg = bg;
        this.wrapped = wrapped;
    }

    String text() {
        int end = chars.length;
        while (end > 0 && (chars[end - 1] == ' ' || chars[end - 1] == 0)) {
            end--;
        }
        return new String(chars, 0, end);
    }

    int foreground(int col) {
        return col >= 0 && col < fg.length ? fg[col] : 0;
    }

    int background(int col) {
        return col >= 0 && col < bg.length ? bg[col] : 0;
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
        int keep = Math.min(newCols, chars.length);
        System.arraycopy(chars, 0, nextChars, 0, keep);
        System.arraycopy(fg, 0, nextFg, 0, keep);
        System.arraycopy(bg, 0, nextBg, 0, keep);
        return new HistoryRow(nextChars, nextFg, nextBg, wrapped);
    }
}
