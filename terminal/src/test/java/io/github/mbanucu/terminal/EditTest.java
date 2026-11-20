package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;
import static io.github.mbanucu.terminal.TestSupport.numberedRows;

/**
 * Checks erases, insert/delete, scroll regions and resize.
 */
public final class EditTest {

    public static void main(String[] args) {
        eraseInLine();
        eraseInDisplay();
        insertAndDeleteLines();
        insertAndDeleteChars();
        scrollRegion();
        scrollCommands();
        resize();

        TestSupport.report("edit checks");
    }

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
}
