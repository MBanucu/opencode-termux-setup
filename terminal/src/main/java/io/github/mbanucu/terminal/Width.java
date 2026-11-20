package io.github.mbanucu.terminal;

/**
 * The display width of one character in cells.
 *
 * <p>Enough of `wcwidth` for the scripts a TUI draws: the East Asian wide and
 * fullwidth ranges take two cells, and everything else takes one. Combining
 * marks and astral characters are not handled yet - a cell is still one
 * {@code char}, so a surrogate pair or a mark occupies its own cell.
 */
final class Width {

    private Width() {
    }

    /** 2 for a wide or fullwidth glyph, 1 for everything else. */
    static int of(char c) {
        return isWide(c) ? 2 : 1;
    }

    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }
}
