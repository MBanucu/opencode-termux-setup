package io.github.mbanucu.terminal;

/**
 * The per-cell SGR attributes other than colour: the ones the view draws
 * differently from plain text.
 */
final class CellAttributes {

    static final int UNDERLINE = 1;
    static final int REVERSE = 2;
    static final int DIM = 4;
    static final int ITALIC = 8;
    static final int STRIKETHROUGH = 16;
    static final int HIDDEN = 32;

    private CellAttributes() {
    }

    static boolean has(int attributes, int flag) {
        return (attributes & flag) != 0;
    }
}
