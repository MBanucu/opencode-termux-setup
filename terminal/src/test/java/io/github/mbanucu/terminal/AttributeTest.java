package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.hex;

/**
 * Checks the SGR attributes that change how a cell is drawn: underline,
 * reverse, dim, italic, strikethrough and hidden, their resets, and that they
 * travel with a row into history and through a resize.
 */
public final class AttributeTest {

    public static void main(String[] args) {
        underline();
        reverse();
        dim();
        italic();
        strikethrough();
        hidden();
        resetClearsEverything();
        historyAndResize();
        TestSupport.report("attribute checks");
    }

    private static void underline() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[4munder\u001b[24mplain");
        check("SGR 4 underlines its text", "true", has(screen, 0, 0, CellAttributes.UNDERLINE));
        check("SGR 24 stops underlining", "false", has(screen, 0, 5, CellAttributes.UNDERLINE));
    }

    private static void reverse() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[7mreversed\u001b[27mplain");
        check("SGR 7 reverses its text", "true", has(screen, 0, 0, CellAttributes.REVERSE));
        check("SGR 27 stops reversing", "false", has(screen, 0, 8, CellAttributes.REVERSE));
    }

    private static void dim() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[1;2;32mdim\u001b[22mplain");
        check("SGR 2 dims its text", "true", has(screen, 0, 0, CellAttributes.DIM));
        check("SGR 22 clears dim", "false", has(screen, 0, 3, CellAttributes.DIM));
        check("SGR 22 clears bold too", "0x4e9a06", hex(screen.foreground(0, 3)));
        check("bold stayed bright before 22", "0x8ae234", hex(screen.foreground(0, 0)));
    }

    private static void italic() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[3mitalic\u001b[23mplain");
        check("SGR 3 italicises its text", "true", has(screen, 0, 0, CellAttributes.ITALIC));
        check("SGR 23 stops italic", "false", has(screen, 0, 6, CellAttributes.ITALIC));
    }

    private static void strikethrough() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[9mstruck\u001b[29mplain");
        check("SGR 9 strikes through its text", "true",
                has(screen, 0, 0, CellAttributes.STRIKETHROUGH));
        check("SGR 29 stops the strike", "false",
                has(screen, 0, 6, CellAttributes.STRIKETHROUGH));
    }

    private static void hidden() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[8mhidden\u001b[28mplain");
        check("SGR 8 hides its text", "true", has(screen, 0, 0, CellAttributes.HIDDEN));
        check("SGR 28 shows it again", "false", has(screen, 0, 6, CellAttributes.HIDDEN));
        check("hidden text is still in the grid", "hiddenplain", screen.line(0));
    }

    private static void resetClearsEverything() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[1;2;3;4;7;8;9mmarked\u001b[0mplain");
        check("SGR 0 clears every attribute", "0",
                String.valueOf(screen.attribute(0, 6)));
        check("the marked cell carried them", "63",
                String.valueOf(screen.attribute(0, 0)));
    }

    private static void historyAndResize() {
        Screen scrolled = new Screen(10, 2);
        scrolled.feed("\u001b[4mfirst\r\nsecond\r\nthird");
        check("a scrolled row keeps its attributes", "true",
                String.valueOf(CellAttributes.has(scrolled.historyAttribute(0, 0),
                        CellAttributes.UNDERLINE)));

        Screen resized = new Screen(10, 2);
        resized.feed("\u001b[7mwide enough");
        resized.resize(20, 3);
        check("a resize keeps attributes", "true",
                has(resized, 0, 0, CellAttributes.REVERSE));
    }

    private static String has(Screen screen, int row, int col, int flag) {
        return String.valueOf(CellAttributes.has(screen.attribute(row, col), flag));
    }
}
