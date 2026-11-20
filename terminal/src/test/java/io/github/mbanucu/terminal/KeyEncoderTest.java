package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the bytes a hardware key sends, in normal and application-cursor
 * mode, including the control and escape forms of modified characters.
 */
public final class KeyEncoderTest {

    public static void main(String[] args) {
        arrows();
        homeAndEnd();
        editingKeys();
        functionKeys();
        controls();
        modifiers();
        unknownKey();

        TestSupport.report("key checks");
    }

    private static void arrows() {
        check("Up is CSI A", "\033[A", encode(KeyEncoder.UP, false, false, false, false));
        check("Down is CSI B", "\033[B", encode(KeyEncoder.DOWN, false, false, false, false));
        check("Right is CSI C", "\033[C", encode(KeyEncoder.RIGHT, false, false, false, false));
        check("Left is CSI D", "\033[D", encode(KeyEncoder.LEFT, false, false, false, false));
        check("application Up is SS3 A", "\033OA",
                encode(KeyEncoder.UP, false, false, false, true));
        check("application Left is SS3 D", "\033OD",
                encode(KeyEncoder.LEFT, false, false, false, true));
    }

    private static void homeAndEnd() {
        check("Home is CSI H", "\033[H", encode(KeyEncoder.HOME, false, false, false, false));
        check("End is CSI F", "\033[F", encode(KeyEncoder.END, false, false, false, false));
        check("application Home is SS3 H", "\033OH",
                encode(KeyEncoder.HOME, false, false, false, true));
        check("application End is SS3 F", "\033OF",
                encode(KeyEncoder.END, false, false, false, true));
    }

    private static void editingKeys() {
        check("Page up is CSI 5 ~", "\033[5~",
                encode(KeyEncoder.PAGE_UP, false, false, false, false));
        check("Page down is CSI 6 ~", "\033[6~",
                encode(KeyEncoder.PAGE_DOWN, false, false, false, false));
        check("Insert is CSI 2 ~", "\033[2~",
                encode(KeyEncoder.INSERT, false, false, false, false));
        check("Delete is CSI 3 ~", "\033[3~",
                encode(KeyEncoder.DELETE, false, false, false, false));
    }

    private static void functionKeys() {
        check("F1 is SS3 P", "\033OP", encode(KeyEncoder.F1, false, false, false, false));
        check("F2 is SS3 Q", "\033OQ", encode(KeyEncoder.F2, false, false, false, false));
        check("F3 is SS3 R", "\033OR", encode(KeyEncoder.F3, false, false, false, false));
        check("F4 is SS3 S", "\033OS", encode(KeyEncoder.F4, false, false, false, false));
        check("F5 is CSI 15 ~", "\033[15~", encode(KeyEncoder.F5, false, false, false, false));
        check("F6 is CSI 17 ~", "\033[17~", encode(KeyEncoder.F6, false, false, false, false));
        check("F7 is CSI 18 ~", "\033[18~", encode(KeyEncoder.F7, false, false, false, false));
        check("F8 is CSI 19 ~", "\033[19~", encode(KeyEncoder.F8, false, false, false, false));
        check("F9 is CSI 20 ~", "\033[20~", encode(KeyEncoder.F9, false, false, false, false));
        check("F10 is CSI 21 ~", "\033[21~", encode(KeyEncoder.F10, false, false, false, false));
        check("F11 is CSI 23 ~", "\033[23~", encode(KeyEncoder.F11, false, false, false, false));
        check("F12 is CSI 24 ~", "\033[24~", encode(KeyEncoder.F12, false, false, false, false));
    }

    private static void controls() {
        check("Enter is CR", "\r", encode(KeyEncoder.ENTER, false, false, false, false));
        check("Tab is HT", "\t", encode(KeyEncoder.TAB, false, false, false, false));
        check("Shift+Tab is CSI Z", "\033[Z",
                encode(KeyEncoder.TAB, false, false, true, false));
        check("Backspace is DEL", "\177",
                encode(KeyEncoder.BACKSPACE, false, false, false, false));
        check("Escape is ESC", "\033",
                encode(KeyEncoder.ESCAPE, false, false, false, false));
    }

    private static void modifiers() {
        check("Alt+Left prefixes ESC", "\033\033[D",
                encode(KeyEncoder.LEFT, false, true, false, false));
        check("Ctrl+C is the control byte", "\003",
                KeyEncoder.encodeCharacter('c', true, false));
        check("Ctrl+Space is NUL", "\000",
                KeyEncoder.encodeCharacter(' ', true, false));
        check("Ctrl+[ is ESC", "\033",
                KeyEncoder.encodeCharacter('[', true, false));
        check("Alt+x prefixes ESC", "\033x",
                KeyEncoder.encodeCharacter('x', false, true));
        check("Ctrl+Alt+C keeps both", "\033\003",
                KeyEncoder.encodeCharacter('c', true, true));
    }

    private static void unknownKey() {
        check("an unknown key sends nothing", "",
                encode(999, false, false, false, false));
    }

    private static String encode(int key, boolean ctrl, boolean alt, boolean shift,
            boolean applicationCursor) {
        return KeyEncoder.encode(key, ctrl, alt, shift, applicationCursor);
    }
}
