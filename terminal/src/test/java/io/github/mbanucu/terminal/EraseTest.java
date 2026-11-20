package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;

/**
 * Checks the erase, insert and delete sequences.
 */
public final class EraseTest {

    public static void main(String[] args) {
        eraseInLine();
        eraseInDisplay();
        insertAndDeleteLines();
        insertAndDeleteChars();

        TestSupport.report("erase checks");
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
}
