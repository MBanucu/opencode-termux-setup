package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the cell range a reader selects and the text it copies.
 *
 * <p>Rows are viewport rows, so a selection can start in scrollback and end on
 * the live grid. The range is inclusive of both ends, a backwards drag reads
 * the same, and a row the terminal wrapped is joined to its continuation.
 */
public final class SelectionTest {

    public static void main(String[] args) {
        startsEmpty();
        selectsWithinOneRow();
        aBackwardsDragReadsTheSame();
        spansRowsWithHardBreaks();
        joinsWrappedRows();
        reachesIntoHistory();
        clampsToTheGrid();
        clearDropsIt();

        TestSupport.report("selection checks");
    }

    private static void startsEmpty() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        check("a new selection is empty", "true", String.valueOf(selection.isEmpty()));
        check("an empty selection copies nothing", "", selection.text());
        check("it contains no cell", "false", String.valueOf(selection.contains(0, 0)));
    }

    private static void selectsWithinOneRow() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 1);
        selection.extendTo(0, 4);
        check("a one-row selection copies its cells", "bcde", selection.text());
        check("the start cell is in the selection", "true",
                String.valueOf(selection.contains(0, 1)));
        check("the end cell is in the selection", "true",
                String.valueOf(selection.contains(0, 4)));
        check("the cell after the end is not", "false",
                String.valueOf(selection.contains(0, 5)));
        check("the cell before the start is not", "false",
                String.valueOf(selection.contains(0, 0)));
    }

    private static void aBackwardsDragReadsTheSame() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 4);
        selection.extendTo(0, 1);
        check("a backwards drag copies the same cells", "bcde", selection.text());
    }

    private static void spansRowsWithHardBreaks() {
        Screen screen = new Screen(10, 3);
        screen.feed("one\r\ntwo\r\nthree");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 1);
        selection.extendTo(2, 2);
        check("a multi-row selection keeps the hard line breaks", "ne\ntwo\nthr",
                selection.text());
    }

    private static void joinsWrappedRows() {
        Screen screen = new Screen(5, 3);
        screen.feed("abcdef\r\ngh");
        Viewport viewport = new Viewport(screen);
        Selection selection = new Selection(screen, viewport);
        check("the viewport reports the wrapped row", "true",
                String.valueOf(viewport.wrapped(0)));
        selection.begin(0, 0);
        selection.extendTo(2, 1);
        check("copying joins a wrapped row without a newline", "abcdef\ngh",
                selection.text());
    }

    private static void reachesIntoHistory() {
        Screen screen = new Screen(5, 2);
        screen.feed("abcdef\r\n");
        Viewport viewport = new Viewport(screen);
        viewport.scrollBy(1);
        Selection selection = new Selection(screen, viewport);
        selection.begin(0, 0);
        selection.extendTo(1, 0);
        check("a selection can begin in scrollback", "abcdef", selection.text());
    }

    private static void clampsToTheGrid() {
        Screen screen = new Screen(10, 3);
        screen.feed("one\r\ntwo\r\nthree");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(-4, -2);
        selection.extendTo(99, 99);
        check("a selection clamps to the grid", "one\ntwo\nthree", selection.text());
        check("and contains its first cell", "true",
                String.valueOf(selection.contains(0, 0)));
        check("and its last cell", "true",
                String.valueOf(selection.contains(2, 9)));
    }

    private static void clearDropsIt() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 0);
        selection.extendTo(1, 0);
        selection.clear();
        check("clearing a selection empties it", "true",
                String.valueOf(selection.isEmpty()));
        check("and copies nothing", "", selection.text());
    }
}
