package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.hex;

/**
 * Checks the primary screen's scrollback: what leaves the top, what the limit
 * drops, what a resize keeps, and how the alternate screen isolates it.
 */
public final class ScrollbackTest {

    public static void main(String[] args) {
        scrollback();
        scrollbackLimit();
        scrollbackOnResize();
        scrollbackAndAlternateScreen();
        scrollbackErase();

        TestSupport.report("scrollback checks");
    }

    // --- scrollback --------------------------------------------------------------

    private static void scrollback() {
        Screen screen = new Screen(20, 3);
        screen.feed("one\r\ntwo\r\nthree");
        screen.feed("\r\nfour");
        check("a full-screen scroll keeps the line that left", "one", screen.historyLine(0));
        check("the history reports one row", "1", String.valueOf(screen.historyRows()));
        check("the visible grid still starts at two", "two", screen.line(0));
        check("the visible grid still ends at four", "four", screen.line(2));
        check("textWithHistory puts the history above the screen",
                "one\ntwo\nthree\nfour\n", screen.textWithHistory());
        check("text() stays the visible screen only", "two\nthree\nfour\n", screen.text());

        Screen colour = new Screen(10, 2);
        colour.feed("\u001b[31;44mred\u001b[0m\r\nsecond\r\nthird");
        check("history keeps the foreground", "0xcc0000",
                hex(colour.historyForeground(0, 0)));
        check("history keeps the background", "0x3465a4",
                hex(colour.historyBackground(0, 0)));
        check("history text trims its trailing blanks", "red", colour.historyLine(0));
    }

    private static void scrollbackLimit() {
        Screen limited = new Screen(10, 2, 2);
        for (String line : new String[] {"a", "b", "c", "d"}) {
            limited.feed(line + "\r\n");
        }
        check("history stops at its limit", "2", String.valueOf(limited.historyRows()));
        check("the oldest line is dropped", "b", limited.historyLine(0));
        check("the newest history line is kept", "c", limited.historyLine(1));
        check("the visible screen is still the newest line", "d", limited.line(0));

        Screen none = new Screen(10, 2, 0);
        none.feed("a\r\nb\r\nc\r\n");
        check("a zero limit keeps no history", "0", String.valueOf(none.historyRows()));
    }

    private static void scrollbackOnResize() {
        Screen screen = new Screen(10, 2);
        screen.feed("abcdefghij\r\nsecond\r\nthird");
        screen.resize(5, 2);
        check("narrowing keeps the history's left part", "abcde", screen.historyLine(0));
        check("narrowing keeps the history row", "1", String.valueOf(screen.historyRows()));
        screen.resize(12, 2);
        check("widening keeps the history row", "abcde", screen.historyLine(0));
    }

    private static void scrollbackAndAlternateScreen() {
        Screen screen = new Screen(10, 2);
        screen.feed("main\r\nsecond\r\nthird");
        int before = screen.historyRows();
        screen.feed("\u001b[?1049h");
        screen.feed("alt\r\nx\r\ny");
        check("the alternate screen does not add history", String.valueOf(before),
                String.valueOf(screen.historyRows()));
        check("the alternate screen does not show main history", "x\ny\n",
                screen.textWithHistory());
        screen.feed("\u001b[?1049l");
        check("the main history survives the alternate screen", "main", screen.historyLine(0));
        check("the main screen comes back", "second", screen.line(0));
    }

    private static void scrollbackErase() {
        Screen screen = new Screen(10, 2);
        screen.feed("one\r\ntwo\r\nthree");
        screen.feed("\u001b[2J");
        check("clearing the screen leaves history", "1",
                String.valueOf(screen.historyRows()));
        screen.feed("\u001b[3J");
        check("ED 3 clears the history", "0", String.valueOf(screen.historyRows()));

        Screen kept = new Screen(10, 2);
        kept.feed("one\r\ntwo\r\nthree");
        kept.feed("\u001b[3J");
        check("ED 3 keeps the visible rows", "two", kept.line(0));
        check("ED 3 still clears history without ED 2", "0",
                String.valueOf(kept.historyRows()));
    }

}
