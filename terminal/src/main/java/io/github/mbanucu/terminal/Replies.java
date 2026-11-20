package io.github.mbanucu.terminal;

/**
 * The answers the screen sends to a program's fixed queries.
 *
 * <p>Kept together so the bytes are written once and their shape is visible in
 * one place: a device-attributes reply, a status report, a cursor position, the
 * version string, the kitty keyboard flags and the two default colours. The
 * screen decides when to send one; this file only knows what one is.
 */
final class Replies {

    /** DA1: a VT100 with the advanced video option, as xterm reports. */
    static final String PRIMARY_DEVICE_ATTRIBUTES = "\u001b[?1;2c";
    /** DA2: the terminal's own identification. */
    static final String SECONDARY_DEVICE_ATTRIBUTES = "\u001b[>84;0;0c";
    /** DSR 5: the terminal is ready. */
    static final String STATUS_OK = "\u001b[0n";
    /** XTVERSION: the program name and version. */
    static final String VERSION = "\u001bP>|terminal(1)\u001b\\";
    /** The kitty keyboard query: no enhanced protocol, so flags stay 0. */
    static final String KEYBOARD_FLAGS = "\u001b[?0u";
    /** OSC 10: the default foreground the view draws text in. */
    static final String FOREGROUND_COLOUR = "\u001b]10;rgb:dcdc/dcdc/dcdc\u001b\\";
    /** OSC 11: the default background. */
    static final String BACKGROUND_COLOUR = "\u001b]11;rgb:1010/1414/1818\u001b\\";

    private Replies() {
    }

    /** DSR 6: the 1-based cursor position. */
    static String cursorPosition(int row, int col) {
        return "\u001b[" + (row + 1) + ";" + (col + 1) + "R";
    }
}
