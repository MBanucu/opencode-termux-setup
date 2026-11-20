package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;

/**
 * Checks cursor addressing, relative moves, the caret modes and DECCKM.
 */
public final class CursorTest {

    public static void main(String[] args) {
        cursorAddressing();
        relativeMoves();
        cursorVisibility();
        cursorKeys();

        TestSupport.report("cursor checks");
    }

    private static void cursorAddressing() {
        Screen screen = new Screen(20, 6);
        screen.feed("\u001b[3;5HX");
        check("CUP places text at row 3, column 5", "    X", screen.line(2));
        check("CUP leaves the cursor after it", "2,5", cursor(screen));

        Screen missing = new Screen(20, 6);
        missing.feed("\u001b[3;HX");
        check("CUP with an empty column means column 1", "X", missing.line(2));

        Screen f = new Screen(20, 6);
        f.feed("\u001b[2;4fX");
        check("HVP is the same as CUP", "   X", f.line(1));
    }

    private static void relativeMoves() {
        Screen screen = new Screen(20, 6);
        screen.feed("\u001b[4;10H\u001b[2A\u001b[3CQ");
        check("CUU/CUF move from where the cursor is", "1,13", cursor(screen));
        check("the moved text lands on the moved row", "            Q", screen.line(1));

        screen.feed("\u001b[10B\u001b[99C\u001b[H");
        check("moves clamp to the grid instead of leaving it", "0,0", cursor(screen));

        screen.feed("\u001b[3;7H\u001b[EX");
        check("CNL goes to the row below, column 1", "X", screen.line(3));
        screen.feed("\u001b[1FY");
        check("CPL goes to the row above, column 1", "Y", screen.line(2));

        screen.feed("\u001b[1;19H\u001b[0GZ");
        check("CHA with 0 means column 1", "Z", screen.line(0));
        screen.feed("\u001b[4;1H\u001b[0dZ");
        check("VPA with 0 means row 1", "Z", screen.line(0));
    }

    private static void cursorVisibility() {
        Screen screen = new Screen(20, 4);
        check("the caret is on until a program says otherwise", "true",
                String.valueOf(screen.cursorVisible()));
        screen.feed("\u001b[?25l");
        check("DEC mode 25 off hides the caret", "false",
                String.valueOf(screen.cursorVisible()));
        screen.feed("\u001b[?25h");
        check("DEC mode 25 on shows it again", "true",
                String.valueOf(screen.cursorVisible()));

        Screen alternate = new Screen(20, 4);
        alternate.feed("\u001b[?1049h");
        check("a full-screen program keeps the caret on until it hides it", "true",
                String.valueOf(alternate.cursorVisible()));

        Screen blinking = new Screen(20, 4);
        blinking.feed("\u001b[1 q");
        check("DECSCUSR 1 asks for a blinking caret", "true",
                String.valueOf(blinking.cursorBlink()));
        blinking.feed("\u001b[2 q");
        check("DECSCUSR 2 asks for a steady one", "false",
                String.valueOf(blinking.cursorBlink()));
        blinking.feed("\u001b[?12h");
        check("DEC mode 12 on blinks the caret", "true",
                String.valueOf(blinking.cursorBlink()));
        blinking.feed("\u001b[?12l");
        check("DEC mode 12 off steadies it", "false",
                String.valueOf(blinking.cursorBlink()));

        // A program that brackets its repaints with ?2026h/l hides the caret
        // inside the frame and shows it at the end. Applied as they arrive, the
        // caret would be hidden almost all the time.
        Screen synced = new Screen(20, 4);
        synced.feed("\u001b[?2026h\u001b[?25l");
        check("a hide inside a synchronized update waits for its end", "true",
                String.valueOf(synced.cursorVisible()));
        synced.feed("\u001b[?25h\u001b[?2026l");
        check("the update's last word decides", "true",
                String.valueOf(synced.cursorVisible()));

        Screen hidden = new Screen(20, 4);
        hidden.feed("\u001b[?2026h\u001b[?25h\u001b[?25l\u001b[?2026l");
        check("a hide that survives the update is applied", "false",
                String.valueOf(hidden.cursorVisible()));
    }

    private static void cursorKeys() {
        Screen screen = new Screen(10, 3);
        check("cursor keys start in normal mode", "false",
                String.valueOf(screen.applicationCursorKeys()));
        screen.feed("\033[?1h");
        check("DECCKM asks for application cursor keys", "true",
                String.valueOf(screen.applicationCursorKeys()));
        screen.feed("\033[?1l");
        check("and normal ones again", "false",
                String.valueOf(screen.applicationCursorKeys()));
    }
}
