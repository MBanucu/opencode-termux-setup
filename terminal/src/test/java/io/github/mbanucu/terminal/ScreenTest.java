package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;

/**
 * Checks the grid against the escape sequences a program actually sends.
 *
 * <p>No JUnit and no device: {@link Screen} is plain Java, so `terminal/test.sh`
 * compiles it with this file and runs `main`. A failure here is an interpreter
 * bug the device tests would only see as a row that reads wrong.
 */
public final class ScreenTest {

    public static void main(String[] args) {
        cursorAddressing();
        relativeMoves();
        eraseInLine();
        eraseInDisplay();
        insertAndDeleteLines();
        insertAndDeleteChars();
        scrollRegion();
        scrollCommands();
        alternateScreen();
        cursorVisibility();
        cursorKeys();
        titlesAreSwallowed();
        splitSequences();
        resize();

        TestSupport.report("screen checks");
    }

    // --- cursor ------------------------------------------------------------------

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

    // --- erases ------------------------------------------------------------------

    private static void eraseInLine() {
        Screen end = new Screen(20, 4);
        end.feed("abcdef\r\u001b[3C\u001b[0K");
        check("EL 0 erases from the cursor to the margin", "abc", end.line(0));

        Screen start = new Screen(20, 4);
        start.feed("abcdef\r\u001b[3C\u001b[1K");
        check("EL 1 erases from the margin to the cursor", "    ef", start.line(0));

        Screen all = new Screen(20, 4);
        all.feed("abcdef\u001b[2K");
        check("EL 2 erases the whole line", "", all.line(0));
    }

    private static void eraseInDisplay() {
        Screen below = new Screen(20, 4);
        below.feed("abc\r\ndef\r\nghi\u001b[2;1H\u001b[J");
        check("ED 0 keeps what is above the cursor", "abc", below.line(0));
        check("ED 0 drops the rest of the cursor's row", "", below.line(1));
        check("ED 0 drops the rows below", "", below.line(2));

        Screen above = new Screen(20, 4);
        above.feed("abc\r\ndef\r\nghi\u001b[3;1H\u001b[1J");
        check("ED 1 drops the rows above", "", above.line(0));
        check("ED 1 keeps the text after the cursor on its row", " hi", above.line(2));

        Screen all = new Screen(20, 4);
        all.feed("abc\r\ndef\u001b[2J");
        check("ED 2 clears the screen", "", all.text());
    }

    // --- insert/delete -----------------------------------------------------------

    private static void insertAndDeleteLines() {
        Screen screen = new Screen(20, 6);
        screen.feed("row0\r\nrow1\r\nrow2\u001b[2;2H\u001b[L");
        check("IL leaves a blank line at the cursor", "", screen.line(1));
        check("IL pushes the old line down", "row1", screen.line(2));
        check("IL pushes the line after that down too", "row2", screen.line(3));

        screen.feed("\u001b[2;2H\u001b[M");
        check("DL pulls the lines below up", "row1", screen.line(1));
        check("DL pulls the rest up too", "row2", screen.line(2));
    }

    private static void insertAndDeleteChars() {
        Screen insert = new Screen(20, 4);
        insert.feed("abcdef\u001b[1;1H\u001b[2@");
        check("ICH opens a gap at the cursor", "  abcdef", insert.line(0));

        Screen delete = new Screen(20, 4);
        delete.feed("abcdef\u001b[1;1H\u001b[2P");
        check("DCH closes a gap at the cursor", "cdef", delete.line(0));

        Screen erase = new Screen(20, 4);
        erase.feed("abcdef\u001b[1;3H\u001b[2X");
        check("ECH blanks cells without moving the rest", "ab  ef", erase.line(0));
    }

    // --- scrolling ---------------------------------------------------------------

    private static void scrollRegion() {
        Screen screen = numberedRows(6);
        screen.feed("\u001b[2;4r");
        check("DECSTBM moves the cursor to the region's top", "0,0", cursor(screen));
        screen.feed("\u001b[4;1H\r\n");
        check("a newline at the region's bottom scrolls only the region", "0", screen.line(0));
        check("the region's top line went away", "2", screen.line(1));
        check("the region's next line followed", "3", screen.line(2));
        check("the region's bottom is blank", "", screen.line(3));
        check("a line below the region is untouched", "4", screen.line(4));
        check("the last line is untouched", "5", screen.line(5));
        check("a partial scroll region does not add history", "0",
                String.valueOf(screen.historyRows()));

        Screen reset = numberedRows(6);
        reset.feed("\u001b[2;4r\u001b[r\u001b[6;1H\r\n");
        check("DECSTBM with no arguments resets the region", "1", reset.line(0));
    }

    private static void scrollCommands() {
        Screen up = numberedRows(4);
        up.feed("\u001b[1;1H\u001b[S");
        check("SU scrolls the region up", "1", up.line(0));
        check("SU blanks the last row", "", up.line(3));
        check("SU keeps the row it scrolls off", "0", up.historyLine(0));

        Screen down = numberedRows(4);
        down.feed("\u001b[1;1H\u001b[T");
        check("SD opens a blank line at the top", "", down.line(0));
        check("SD pushes the rest down", "0", down.line(1));
    }

    private static void alternateScreen() {
        Screen screen = new Screen(20, 4);
        screen.feed("main");
        screen.feed("\u001b[?1049h");
        check("the alternate screen starts blank", "", screen.text());
        check("the cursor starts at the top", "0,0", cursor(screen));
        check("the view knows a program owns the screen", "true", String.valueOf(screen.isAlternate()));

        screen.feed("alt");
        screen.feed("\u001b[?1049l");
        check("the main screen comes back", "main", screen.line(0));
        check("the cursor comes back with it", "0,4", cursor(screen));
        check("the view knows the program left", "false", String.valueOf(screen.isAlternate()));

        Screen obsolete = new Screen(10, 2);
        obsolete.feed("a\u001b[?47hb\u001b[?47l");
        check("the older alternate-screen mode is understood too", "a", obsolete.line(0));
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

    private static void titlesAreSwallowed() {
        Screen bel = new Screen(20, 4);
        bel.feed("\u001b]2;a title\u0007x");
        check("OSC ends at BEL and prints nothing", "x", bel.line(0));

        Screen st = new Screen(20, 4);
        st.feed("\u001b]0;a title\u001b\\y");
        check("OSC ends at ST and prints nothing", "y", st.line(0));

        Screen charset = new Screen(20, 4);
        charset.feed("\u001b(Bz");
        check("a charset designation is not text", "z", charset.line(0));

        // opencode asks its termcap question with a DCS payload.
        Screen dcs = new Screen(20, 4);
        dcs.feed("\u001bP+q4d73\u001b\\x");
        check("DCS ends at ST and prints nothing", "x", dcs.line(0));

        // CSI ? u is the kitty keyboard query, not a cursor restore.
        Screen kitty = new Screen(20, 4);
        kitty.feed("\u001b[2;3H\u001b[s\u001b[4;4H\u001b[?uX");
        check("a private u query is not a cursor restore", "   X", kitty.line(3));
    }

    private static void splitSequences() {
        Screen screen = new Screen(20, 6);
        screen.feed("\u001b[3");
        check("an incomplete escape prints nothing yet", "", screen.text());
        screen.feed(";5HZ");
        check("a sequence split across feeds is applied whole", "    Z", screen.line(2));

        Screen osc = new Screen(20, 4);
        osc.feed("\u001b]0;half");
        check("an incomplete OSC prints nothing", "", osc.text());
        osc.feed(" a title\u0007x");
        check("a split OSC is swallowed whole", "x", osc.line(0));

        Screen dcs = new Screen(20, 4);
        dcs.feed("\u001bP+q4d");
        check("an incomplete DCS prints nothing", "", dcs.text());
        dcs.feed("73\u001b\\z");
        check("a split DCS is swallowed whole", "z", dcs.line(0));
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

    // --- resize ------------------------------------------------------------------

    private static void resize() {
        Screen screen = new Screen(5, 3);
        screen.feed("abcde\r\nfg");
        screen.resize(8, 5);
        check("a wider grid keeps the rows", "abcde", screen.line(0));
        check("a taller grid keeps the other rows", "fg", screen.line(1));
        check("the grid reports its new size", "8x5", screen.cols() + "x" + screen.rows());

        screen.resize(2, 1);
        check("a smaller grid keeps what fits", "ab", screen.line(0));
        check("the cursor is clamped into it", "0,1", cursor(screen));
    }

    // --- helpers -----------------------------------------------------------------

    private static Screen numberedRows(int rows) {
        Screen screen = new Screen(20, rows);
        for (int row = 0; row < rows; row++) {
            screen.feed(String.valueOf(row));
            if (row < rows - 1) {
                screen.feed("\r\n");
            }
        }
        return screen;
    }

}
