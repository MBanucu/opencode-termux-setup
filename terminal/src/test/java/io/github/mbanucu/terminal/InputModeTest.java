package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the input-reporting modes a program can turn on: bracketed paste,
 * mouse tracking and its coordinate encoding, and focus in/out reports.
 */
public final class InputModeTest {

    public static void main(String[] args) {
        bracketedPaste();
        mouseModes();
        focusReporting();
        TestSupport.report("input mode checks");
    }

    private static void bracketedPaste() {
        Screen screen = new Screen(20, 4);
        check("bracketed paste starts off", "false",
                String.valueOf(screen.bracketedPaste()));
        screen.feed("\u001b[?2004h");
        check("mode 2004 turns bracketed paste on", "true",
                String.valueOf(screen.bracketedPaste()));
        check("the mode prints nothing", "", screen.text());
        screen.feed("text");
        check("text after the mode is still text", "text", screen.line(0));
        screen.feed("\u001b[?2004l");
        check("mode 2004 turns it off", "false",
                String.valueOf(screen.bracketedPaste()));

        Screen alternate = new Screen(10, 2);
        alternate.feed("\u001b[?2004h\u001b[?1049h");
        check("bracketed paste survives the alternate screen", "true",
                String.valueOf(alternate.bracketedPaste()));
    }

    private static void mouseModes() {
        Screen screen = new Screen(20, 4);
        check("mouse reporting starts off", "0", String.valueOf(screen.mouseMode()));
        screen.feed("\u001b[?1000h");
        check("mode 1000 reports presses", "1000", String.valueOf(screen.mouseMode()));
        screen.feed("\u001b[?1002h");
        check("mode 1002 replaces 1000", "1002", String.valueOf(screen.mouseMode()));
        screen.feed("\u001b[?1002l");
        check("turning 1002 off stops reporting", "0",
                String.valueOf(screen.mouseMode()));
        screen.feed("\u001b[?1003h\u001b[?1003l");
        check("mode 1003 turns off the same way", "0",
                String.valueOf(screen.mouseMode()));

        check("mouse coordinates use the default encoding", "0",
                String.valueOf(screen.mouseEncoding()));
        screen.feed("\u001b[?1006h");
        check("mode 1006 selects SGR coordinates", "1006",
                String.valueOf(screen.mouseEncoding()));
        screen.feed("\u001b[?1015h");
        check("mode 1015 replaces SGR", "1015",
                String.valueOf(screen.mouseEncoding()));
        screen.feed("\u001b[?1015l");
        check("turning 1015 off restores the default", "0",
                String.valueOf(screen.mouseEncoding()));
        check("mouse modes print nothing", "", screen.text());
    }

    private static void focusReporting() {
        Screen screen = new Screen(20, 4);
        check("focus reporting starts off", "false",
                String.valueOf(screen.focusReporting()));
        screen.feed("\u001b[?1004h");
        check("mode 1004 turns focus reporting on", "true",
                String.valueOf(screen.focusReporting()));
        screen.feed("\u001b[?1004l");
        check("mode 1004 turns it off", "false",
                String.valueOf(screen.focusReporting()));
        screen.feed("\u001b[?2031h");
        check("mode 2031 is focus reporting too", "true",
                String.valueOf(screen.focusReporting()));
        check("the focus mode prints nothing", "", screen.text());
    }
}
