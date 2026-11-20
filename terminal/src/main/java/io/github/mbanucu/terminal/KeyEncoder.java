package io.github.mbanucu.terminal;

/**
 * The bytes a hardware key sends to the program.
 *
 * <p>Named keys are sequences, not text: arrows and Home/End switch between
 * CSI and SS3 with DECCKM, the editing keys end in {@code ~}, and F1-F4 use SS3
 * while F5-F12 use {@code CSI n ~}. A modified arrow, Home or End is always
 * {@code CSI 1 ; m final}, where {@code m} is 1 plus Shift, 2 for Alt and 4 for
 * Ctrl, because that is how xterm reports the modifiers. Typed characters keep
 * their IME/unicode path, with Ctrl folded to a control byte and Alt prefixed by
 * ESC. No key is encoded here for the shell prompt: the view owns that line
 * until it is submitted.
 */
final class KeyEncoder {

    static final int ENTER = 1;
    static final int TAB = 2;
    static final int BACKSPACE = 3;
    static final int ESCAPE = 4;
    static final int UP = 5;
    static final int DOWN = 6;
    static final int RIGHT = 7;
    static final int LEFT = 8;
    static final int HOME = 9;
    static final int END = 10;
    static final int PAGE_UP = 11;
    static final int PAGE_DOWN = 12;
    static final int INSERT = 13;
    static final int DELETE = 14;
    static final int F1 = 15;
    static final int F2 = 16;
    static final int F3 = 17;
    static final int F4 = 18;
    static final int F5 = 19;
    static final int F6 = 20;
    static final int F7 = 21;
    static final int F8 = 22;
    static final int F9 = 23;
    static final int F10 = 24;
    static final int F11 = 25;
    static final int F12 = 26;

    private KeyEncoder() {
    }

    /** The sequence for a named key, or "" when the key has none. */
    static String encode(int key, boolean ctrl, boolean alt, boolean shift,
            boolean applicationCursor) {
        char cursor = cursorFinal(key);
        if (cursor != 0 && modifier(ctrl, alt, shift) > 1) {
            return "\u001b[1;" + modifier(ctrl, alt, shift) + cursor;
        }
        String sequence = sequence(key, shift, applicationCursor);
        if (sequence.isEmpty()) {
            return "";
        }
        return alt ? "\u001b" + sequence : sequence;
    }

    /** xterm's modifier parameter: 1 plus Shift, 2 for Alt and 4 for Ctrl. */
    private static int modifier(boolean ctrl, boolean alt, boolean shift) {
        return 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
    }

    /** The final byte of an arrow, Home or End, or 0 for any other key. */
    private static char cursorFinal(int key) {
        switch (key) {
            case UP:
                return 'A';
            case DOWN:
                return 'B';
            case RIGHT:
                return 'C';
            case LEFT:
                return 'D';
            case HOME:
                return 'H';
            case END:
                return 'F';
            default:
                return 0;
        }
    }

    private static String sequence(int key, boolean shift, boolean applicationCursor) {
        char last = cursorFinal(key);
        if (last != 0) {
            return applicationCursor ? "\u001bO" + last : "\u001b[" + last;
        }
        switch (key) {
            case ENTER:
                return "\r";
            case TAB:
                return shift ? "\u001b[Z" : "\t";
            case BACKSPACE:
                return "\u007f";
            case ESCAPE:
                return "\u001b";
            case PAGE_UP:
                return "\u001b[5~";
            case PAGE_DOWN:
                return "\u001b[6~";
            case INSERT:
                return "\u001b[2~";
            case DELETE:
                return "\u001b[3~";
            case F1:
                return "\u001bOP";
            case F2:
                return "\u001bOQ";
            case F3:
                return "\u001bOR";
            case F4:
                return "\u001bOS";
            case F5:
                return "\u001b[15~";
            case F6:
                return "\u001b[17~";
            case F7:
                return "\u001b[18~";
            case F8:
                return "\u001b[19~";
            case F9:
                return "\u001b[20~";
            case F10:
                return "\u001b[21~";
            case F11:
                return "\u001b[23~";
            case F12:
                return "\u001b[24~";
            default:
                return "";
        }
    }

    /** A typed character with Ctrl folded and Alt prefixed, as a terminal sends it. */
    static String encodeCharacter(char c, boolean ctrl, boolean alt) {
        StringBuilder bytes = new StringBuilder();
        if (alt) {
            bytes.append('\u001b');
        }
        int control = ctrl ? controlCode(c) : -1;
        bytes.append(control >= 0 ? (char) control : c);
        return bytes.toString();
    }

    /** The control byte for a character, 0-31 or 127, or -1 when it has none. */
    private static int controlCode(char c) {
        char lower = Character.toLowerCase(c);
        if (lower >= 'a' && lower <= 'z') {
            return lower - 'a' + 1;
        }
        if (c == ' ' || c == '@' || c == '2') {
            return 0;
        }
        if (c == '[') {
            return 0x1b;
        }
        if (c == '\\') {
            return 0x1c;
        }
        if (c == ']') {
            return 0x1d;
        }
        if (c == '^') {
            return 0x1e;
        }
        if (c == '_') {
            return 0x1f;
        }
        if (c == '?') {
            return 0x7f;
        }
        return -1;
    }
}
