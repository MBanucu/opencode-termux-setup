package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the answers a screen owes a program that asks a question: device
 * attributes, status and cursor position, the version, kitty keyboard support
 * and the OSC colour queries. Nothing is sent unless it was asked for, and a
 * query split across feeds still gets one answer.
 */
public final class QueryReplyTest {

    private static final String ESC = "\033";

    public static void main(String[] args) {
        deviceAttributes();
        deviceStatus();
        version();
        kittyKeyboard();
        colours();
        unrelatedQueries();
        splitSequences();
        noSink();

        TestSupport.report("reply checks");
    }

    private static void deviceAttributes() {
        StringBuilder out = new StringBuilder();
        Screen screen = replyingTo(out);
        screen.feed(ESC + "[c");
        check("DA1 answers with a VT100 identity", ESC + "[?1;2c", out.toString());

        out.setLength(0);
        screen.feed(ESC + "[>c");
        check("DA2 answers with a secondary identity", ESC + "[>84;0;0c", out.toString());
    }

    private static void deviceStatus() {
        StringBuilder out = new StringBuilder();
        Screen screen = replyingTo(out);
        screen.feed(ESC + "[5n");
        check("DSR 5 answers OK", ESC + "[0n", out.toString());

        out.setLength(0);
        screen.feed(ESC + "[6n");
        check("CPR at home is row 1, column 1", ESC + "[1;1R", out.toString());

        out.setLength(0);
        screen.feed(ESC + "[3;5H" + ESC + "[6n");
        check("CPR reports the cursor's cell", ESC + "[3;5R", out.toString());
    }

    private static void version() {
        StringBuilder out = new StringBuilder();
        Screen screen = replyingTo(out);
        screen.feed(ESC + "[>0q");
        check("XTVERSION answers a DCS string", ESC + "P>|terminal(1)" + ESC + "\\",
                out.toString());
    }

    private static void kittyKeyboard() {
        StringBuilder out = new StringBuilder();
        Screen screen = replyingTo(out);
        screen.feed(ESC + "[?u");
        check("the kitty query answers with no flags", ESC + "[?0u", out.toString());
    }

    private static void colours() {
        StringBuilder out = new StringBuilder();
        Screen screen = replyingTo(out);
        screen.feed(ESC + "]10;?" + ESC + "\\");
        check("OSC 10 answers the default foreground",
                ESC + "]10;rgb:dcdc/dcdc/dcdc" + ESC + "\\", out.toString());

        out.setLength(0);
        screen.feed(ESC + "]11;?\007");
        check("OSC 11 answers the default background",
                ESC + "]11;rgb:1010/1414/1818" + ESC + "\\", out.toString());
    }

    private static void unrelatedQueries() {
        StringBuilder out = new StringBuilder();
        Screen screen = replyingTo(out);
        screen.feed(ESC + "[7n" + ESC + "]12;?\007" + ESC + "[?25h");
        check("a query this screen does not know gets no reply", "", out.toString());
        check("and prints nothing", "", screen.text());
    }

    private static void splitSequences() {
        StringBuilder out = new StringBuilder();
        Screen screen = replyingTo(out);
        screen.feed(ESC + "[6");
        check("an incomplete CPR query waits", "", out.toString());
        screen.feed("n");
        check("a split CPR query is answered once", ESC + "[1;1R", out.toString());

        out.setLength(0);
        screen.feed(ESC + "]10;");
        check("an incomplete OSC query waits", "", out.toString());
        screen.feed("?\007");
        check("a split OSC query is answered once",
                ESC + "]10;rgb:dcdc/dcdc/dcdc" + ESC + "\\", out.toString());
    }

    private static void noSink() {
        Screen quiet = new Screen(20, 5);
        quiet.feed(ESC + "[6n" + ESC + "[c" + ESC + "]10;?\007");
        check("queries with no sink still print nothing", "", quiet.text());
    }

    private static Screen replyingTo(StringBuilder out) {
        Screen screen = new Screen(20, 5);
        screen.setReplySink(out::append);
        return screen;
    }
}
