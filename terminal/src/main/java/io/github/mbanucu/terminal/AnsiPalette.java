package io.github.mbanucu.terminal;

/**
 * The colours SGR can name: the 16-entry palette, the 6x6x6 cube, the grey
 * ramp, and the bright entry that bold substitutes for a palette foreground.
 *
 * <p>Separate from {@link Screen} so a table change lands in the file named
 * after it rather than in the parser.
 */
final class AnsiPalette {

    /** The 16 answers to SGR 30-37 and 90-97, the Tango palette. */
    private static final int[] ANSI = {
        0x000000, 0xCC0000, 0x4E9A06, 0xC4A000,
        0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
        0x555753, 0xEF2929, 0x8AE234, 0xFCE94F,
        0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC,
    };

    private AnsiPalette() {
    }

    /** One of the 16 palette entries. */
    static int palette(int index) {
        return ANSI[index];
    }

    /** SGR 38;5;n / 48;5;n: the 16 colours, the 6x6x6 cube, then the greys. */
    static int colour256(int n) {
        if (n < 16) {
            return ANSI[n];
        }
        if (n < 232) {
            int cube = n - 16;
            return (level(cube / 36) << 16) | (level((cube % 36) / 6) << 8) | level(cube % 6);
        }
        int grey = 8 + (n - 232) * 10;
        return (grey << 16) | (grey << 8) | grey;
    }

    /** SGR 1: the bright entry for a palette colour, and nothing for the rest. */
    static int brighten(int colour) {
        for (int i = 0; i < 8; i++) {
            if (ANSI[i] == colour) {
                return ANSI[i + 8];
            }
        }
        return colour;
    }

    /** The 6 levels of the 256-colour cube: 0, 95, 135, 175, 215, 255. */
    private static int level(int step) {
        return step == 0 ? 0 : 55 + step * 40;
    }
}
