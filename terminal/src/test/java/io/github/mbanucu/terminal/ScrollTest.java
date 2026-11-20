package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;
import static io.github.mbanucu.terminal.TestSupport.numberedRows;

/**
 * Checks scroll regions, scroll commands and resize.
 */
public final class ScrollTest {

    public static void main(String[] args) {
        scrollRegion();
        scrollCommands();
        resize();

        TestSupport.report("scroll checks");
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
