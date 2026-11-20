package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;

/**
 * Checks writing into the grid: auto-wrap versus hard breaks, the remembered
 * wrap flag, backspace/tab and cursor save/restore.
 */
public final class WritingTest {

    public static void main(String[] args) {
        wrapping();
        wrappingIsRemembered();
        backspaceAndTab();
        saveAndRestore();

        TestSupport.report("writing checks");
    }

    // --- writing -----------------------------------------------------------------

    private static void wrapping() {
        Screen screen = new Screen(5, 3);
        screen.feed("abcde");
        check("the last column holds its character", "abcde", screen.line(0));
        check("the cursor waits on the last column", "0,4", cursor(screen));
        screen.feed("f");
        check("the next character starts a new row", "f", screen.line(1));
        check("the cursor is past that character", "1,1", cursor(screen));

        Screen carriage = new Screen(5, 3);
        carriage.feed("abcde\rX");
        check("a carriage return cancels the pending wrap", "Xbcde", carriage.line(0));

        Screen newline = new Screen(5, 3);
        newline.feed("ab\r\ncd");
        check("CRLF returns to column 1", "cd", newline.line(1));
    }

    /**
     * A row the terminal wrapped is continued below it, and copy or a reader
     * should not see a newline the program never wrote.
     */
    private static void wrappingIsRemembered() {
        Screen screen = new Screen(5, 3);
        screen.feed("abcde");
        check("a full row is not wrapped yet", "false",
                String.valueOf(screen.wrapped(0)));
        screen.feed("f");
        check("the row that wrapped says so", "true",
                String.valueOf(screen.wrapped(0)));
        check("the continuation row does not", "false",
                String.valueOf(screen.wrapped(1)));
        check("text joins a wrapped row to its continuation", "abcdef\n", screen.text());

        Screen carriage = new Screen(5, 3);
        carriage.feed("abcde\rX");
        check("a carriage return does not leave a wrapped row", "false",
                String.valueOf(carriage.wrapped(0)));

        Screen hard = new Screen(5, 3);
        hard.feed("abc\r\ndef");
        check("a hard CRLF separates rows", "abc\ndef\n", hard.text());

        Screen scrolled = new Screen(5, 2);
        scrolled.feed("abcdef\r\n");
        check("history keeps the wrap flag", "true",
                String.valueOf(scrolled.historyWrapped(0)));
        check("textWithHistory joins the history row to the screen", "abcdef\n",
                scrolled.textWithHistory());

        Screen resized = new Screen(5, 3);
        resized.feed("abcdef");
        resized.resize(8, 4);
        check("a wider grid keeps the wrap flag", "true",
                String.valueOf(resized.wrapped(0)));
        check("a new row is not wrapped", "false",
                String.valueOf(resized.wrapped(2)));

        Screen narrowing = new Screen(5, 3);
        narrowing.feed("abcdef");
        narrowing.resize(3, 3);
        check("a narrower grid keeps the wrap flag", "true",
                String.valueOf(narrowing.wrapped(0)));

        Screen historyResized = new Screen(5, 2);
        historyResized.feed("abcdef\r\n");
        historyResized.resize(8, 2);
        check("history keeps the wrap flag across resize", "true",
                String.valueOf(historyResized.historyWrapped(0)));

        Screen inserted = new Screen(5, 4);
        inserted.feed("\r\nabcdef");
        inserted.feed("\u001b[1;1H\u001b[L");
        check("inserting a line moves the wrap flag with its row", "true",
                String.valueOf(inserted.wrapped(2)));
        check("the line insert opened is not wrapped", "false",
                String.valueOf(inserted.wrapped(1)));

        Screen deleted = new Screen(5, 4);
        deleted.feed("\r\nabcdef");
        deleted.feed("\u001b[1;1H\u001b[M");
        check("deleting a line moves the wrap flag up with its row", "true",
                String.valueOf(deleted.wrapped(0)));

        Screen cleared = new Screen(5, 3);
        cleared.feed("abcdef");
        cleared.feed("\u001b[2J");
        check("clearing the screen clears the wrap flags", "false",
                String.valueOf(cleared.wrapped(0)));
    }

    private static void backspaceAndTab() {
        Screen back = new Screen(20, 4);
        back.feed("abc\u0008X");
        check("a backspace moves the cursor, not the text", "abX", back.line(0));

        Screen tab = new Screen(20, 4);
        tab.feed("\tX");
        check("a tab reaches the next multiple of 8", "        X", tab.line(0));
    }

    private static void saveAndRestore() {
        Screen screen = new Screen(20, 4);
        screen.feed("ab\u001b[s cd\u001b[uX");
        check("the cursor is restored by CSI u", "abXcd", screen.line(0));
    }

}
