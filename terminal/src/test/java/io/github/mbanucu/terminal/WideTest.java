package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;
import static io.github.mbanucu.terminal.TestSupport.hex;

/**
 * Checks width-2 glyphs: a CJK character occupies two cells, its continuation
 * carries the same colours, it wraps as a whole at the right edge, and it
 * survives history, a resize and an erase. Emoji and combining marks are not
 * covered yet.
 */
public final class WideTest {

    public static void main(String[] args) {
        twoCells();
        continuationKeepsItsColours();
        wrapsWhole();
        rightEdge();
        historyAndResize();
        eraseAndText();
        TestSupport.report("wide checks");
    }

    private static void twoCells() {
        Screen screen = new Screen(10, 3);
        screen.feed("日");
        check("a wide glyph takes two cells", "0,2", cursor(screen));
        check("the continuation cell is not text", "日", screen.line(0));
        check("text keeps one glyph per character", "日\n", screen.text());
    }

    private static void continuationKeepsItsColours() {
        Screen screen = new Screen(10, 3);
        screen.feed("\u001b[44m日");
        check("the glyph cell carries the background", "0x3465a4",
                hex(screen.background(0, 0)));
        check("the continuation carries it too", "0x3465a4",
                hex(screen.background(0, 1)));
    }

    private static void wrapsWhole() {
        Screen screen = new Screen(4, 3);
        screen.feed("日日日");
        check("two wide glyphs fill a four-column row", "日日", screen.line(0));
        check("the third starts the next row", "日", screen.line(1));
        check("the cursor follows it", "1,2", cursor(screen));
        check("the filled row is marked wrapped", "true",
                String.valueOf(screen.wrapped(0)));
    }

    private static void rightEdge() {
        Screen screen = new Screen(4, 3);
        screen.feed("abc");
        check("three narrow glyphs fill the row", "abc", screen.line(0));
        screen.feed("日");
        check("a wide glyph cannot straddle the edge", "日", screen.line(1));
        check("it starts in the first column there", "1,2", cursor(screen));

        Screen moved = new Screen(4, 3);
        moved.feed("\u001b[1;4H日");
        check("a wide glyph at the last column wraps", "日", moved.line(1));
    }

    private static void historyAndResize() {
        Screen screen = new Screen(4, 2);
        screen.feed("日日日\r\n");
        check("a scrolled wide row keeps its glyphs", "日日", screen.historyLine(0));

        Screen resized = new Screen(4, 2);
        resized.feed("日日");
        resized.resize(8, 3);
        check("a resize keeps wide glyphs", "日日", resized.line(0));
    }

    private static void eraseAndText() {
        Screen screen = new Screen(6, 3);
        screen.feed("日\u001b[1;1H\u001b[2J");
        check("erasing clears the glyph and its continuation", "", screen.line(0));
        screen.feed("ab日");
        check("wide glyphs join the surrounding text", "ab日\n", screen.text());
    }
}
