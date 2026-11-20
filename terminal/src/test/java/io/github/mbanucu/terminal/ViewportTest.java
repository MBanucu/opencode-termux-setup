package io.github.mbanucu.terminal;

/**
 * Checks the window the view shows over the grid and its history.
 *
 * <p>Scrollback is the terminal's view of the program's output, not something
 * the program knows about: while a reader is looking at an older row, new
 * output must not drag that row out from under them. These checks pin down the
 * offset arithmetic before {@link TerminalView} draws or drags anything.
 */
public final class ViewportTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        startsAtTheBottom();
        scrollsIntoHistory();
        clampsToWhatIsStored();
        outputWhileScrolledBackKeepsTheViewStill();
        outputAtTheBottomFollows();
        inputReturnsToTheBottom();
        alternateScreenHasNoScrollback();
        clearingHistoryClampsTheOffset();
        coloursFollowTheHistory();
        threeThousandLines();

        System.out.println(failed == 0
                ? "PASS " + passed + " viewport checks"
                : "FAIL " + failed + " of " + (passed + failed) + " viewport checks");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void startsAtTheBottom() {
        Viewport viewport = new Viewport(scrolledScreen());
        check("a new viewport is at the live screen", "true",
                String.valueOf(viewport.atBottom()));
        check("the live screen starts at the first row", "five", viewport.line(0));
        check("the live screen ends at the last row", "six", viewport.line(1));
        check("there is history to go back to", "4",
                String.valueOf(viewport.maxOffset()));
    }

    private static void scrollsIntoHistory() {
        Viewport viewport = new Viewport(scrolledScreen());
        viewport.scrollBy(1);
        check("one line back leaves the bottom", "false",
                String.valueOf(viewport.atBottom()));
        check("one line back puts the newest history row on top", "four",
                viewport.line(0));
        check("the live rows follow it", "five", viewport.line(1));
        check("the last live row is still the newest", "six", viewport.line(2));

        viewport.scrollBy(3);
        check("four lines back reaches the oldest row", "one", viewport.line(0));
        check("four lines back fills the grid with history", "three",
                viewport.line(2));
    }

    private static void clampsToWhatIsStored() {
        Viewport viewport = new Viewport(scrolledScreen());
        viewport.scrollBy(100);
        check("scrolling past the oldest row stops there", "4",
                String.valueOf(viewport.offset()));
        viewport.scrollBy(-100);
        check("scrolling past the bottom stops at zero", "0",
                String.valueOf(viewport.offset()));
        check("and counts as the bottom again", "true",
                String.valueOf(viewport.atBottom()));
    }

    private static void outputWhileScrolledBackKeepsTheViewStill() {
        Screen screen = scrolledScreen();
        Viewport viewport = new Viewport(screen);
        viewport.scrollBy(2);
        String viewed = viewport.line(0);
        int before = screen.historyRows();

        screen.feed("seven\r\n");
        viewport.followOutput(screen.historyRows() - before);

        check("new output does not move the row being read", viewed,
                viewport.line(0));
        check("the offset follows the row into a deeper history", "3",
                String.valueOf(viewport.offset()));
        check("the view is still not at the bottom", "false",
                String.valueOf(viewport.atBottom()));
    }

    private static void outputAtTheBottomFollows() {
        Screen screen = scrolledScreen();
        Viewport viewport = new Viewport(screen);
        int before = screen.historyRows();

        screen.feed("seven\r\n");
        viewport.followOutput(screen.historyRows() - before);

        check("output while at the bottom stays at the bottom", "0",
                String.valueOf(viewport.offset()));
        check("the newest row is on the grid, not in history", "seven",
                viewport.line(1));
    }

    private static void inputReturnsToTheBottom() {
        Viewport viewport = new Viewport(scrolledScreen());
        viewport.scrollBy(3);
        viewport.scrollToBottom();
        check("typing returns the view to the live screen", "true",
                String.valueOf(viewport.atBottom()));
        check("the live rows are back", "six", viewport.line(1));
    }

    private static void alternateScreenHasNoScrollback() {
        Screen screen = scrolledScreen();
        Viewport viewport = new Viewport(screen);
        viewport.scrollBy(2);

        screen.feed("\u001b[?1049h");
        viewport.followOutput(0);
        viewport.clamp();

        check("a full-screen program returns the view to the live grid", "0",
                String.valueOf(viewport.offset()));
        check("the alternate screen has nothing to scroll to", "0",
                String.valueOf(viewport.maxOffset()));
        check("its first row is the program's row", "", viewport.line(0));
        viewport.scrollBy(5);
        check("dragging on the alternate screen does not leave it", "0",
                String.valueOf(viewport.offset()));

        screen.feed("\u001b[?1049l");
        viewport.clamp();
        check("the main screen keeps its history", "4",
                String.valueOf(viewport.maxOffset()));
    }

    private static void clearingHistoryClampsTheOffset() {
        Screen screen = scrolledScreen();
        Viewport viewport = new Viewport(screen);
        viewport.scrollBy(4);

        screen.feed("\u001b[3J");
        viewport.clamp();

        check("ED 3 leaves nowhere to scroll back to", "0",
                String.valueOf(viewport.maxOffset()));
        check("the offset does not outlive the history", "0",
                String.valueOf(viewport.offset()));
        check("and the view is back on the live rows", "five", viewport.line(0));
    }

    private static void coloursFollowTheHistory() {
        Screen screen = new Screen(10, 2);
        screen.feed("\u001b[31mred\u001b[0m\r\nnext\r\nlast");
        Viewport viewport = new Viewport(screen);
        viewport.scrollBy(1);

        check("a history row keeps its foreground", "0xcc0000",
                hex(viewport.foreground(0, 0)));
        check("the row after it keeps the default", "0x000000",
                hex(viewport.foreground(1, 0)));
    }

    private static void threeThousandLines() {
        Screen screen = new Screen(80, 24);
        for (int i = 0; i < 5000; i++) {
            if (i > 0) {
                screen.feed("\r\n");
            }
            screen.feed("row" + i);
        }
        Viewport viewport = new Viewport(screen);

        check("the history stops at the default depth", "3000",
                String.valueOf(viewport.maxOffset()));
        viewport.scrollBy(3000);
        check("the oldest retained row is the top of the history", "row1976",
                viewport.line(0));
        check("the 3000-line window ends at the newest history row", "row1999",
                viewport.line(23));
        check("the live rows are still below it", "row4999", screen.line(23));
    }

    // --- helpers -----------------------------------------------------------------

    /** Three visible rows and four history rows: one..four above five, six. */
    private static Screen scrolledScreen() {
        Screen screen = new Screen(10, 3);
        screen.feed("one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\n");
        return screen;
    }

    private static String hex(int colour) {
        return String.format("0x%06x", colour);
    }

    private static void check(String what, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
