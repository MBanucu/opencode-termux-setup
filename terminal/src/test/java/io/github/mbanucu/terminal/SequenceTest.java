package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;

/**
 * Checks the alternate screen and sequences that must print nothing.
 */
public final class SequenceTest {

    public static void main(String[] args) {
        alternateScreen();
        titlesAreSwallowed();
        splitSequences();

        TestSupport.report("sequence checks");
    }

    private static void alternateScreen() {
        Screen screen = new Screen(20, 4);
        screen.feed("main");
        screen.feed("\u001b[?1049h");
        check("the alternate screen starts blank", "", screen.text());
        check("the cursor starts at the top", "0,0", cursor(screen));
        check("the view knows a program owns the screen", "true", String.valueOf(screen.isAlternate()));

        screen.feed("alt");
        screen.feed("\u001b[?1049l");
        check("the main screen comes back", "main", screen.line(0));
        check("the cursor comes back with it", "0,4", cursor(screen));
        check("the view knows the program left", "false", String.valueOf(screen.isAlternate()));

        Screen obsolete = new Screen(10, 2);
        obsolete.feed("a\u001b[?47hb\u001b[?47l");
        check("the older alternate-screen mode is understood too", "a", obsolete.line(0));
    }







    private static void titlesAreSwallowed() {
        Screen bel = new Screen(20, 4);
        bel.feed("\u001b]2;a title\u0007x");
        check("OSC ends at BEL and prints nothing", "x", bel.line(0));

        Screen st = new Screen(20, 4);
        st.feed("\u001b]0;a title\u001b\\y");
        check("OSC ends at ST and prints nothing", "y", st.line(0));

        Screen charset = new Screen(20, 4);
        charset.feed("\u001b(Bz");
        check("a charset designation is not text", "z", charset.line(0));

        // opencode asks its termcap question with a DCS payload.
        Screen dcs = new Screen(20, 4);
        dcs.feed("\u001bP+q4d73\u001b\\x");
        check("DCS ends at ST and prints nothing", "x", dcs.line(0));

        // CSI ? u is the kitty keyboard query, not a cursor restore.
        Screen kitty = new Screen(20, 4);
        kitty.feed("\u001b[2;3H\u001b[s\u001b[4;4H\u001b[?uX");
        check("a private u query is not a cursor restore", "   X", kitty.line(3));
    }

    private static void splitSequences() {
        Screen screen = new Screen(20, 6);
        screen.feed("\u001b[3");
        check("an incomplete escape prints nothing yet", "", screen.text());
        screen.feed(";5HZ");
        check("a sequence split across feeds is applied whole", "    Z", screen.line(2));

        Screen osc = new Screen(20, 4);
        osc.feed("\u001b]0;half");
        check("an incomplete OSC prints nothing", "", osc.text());
        osc.feed(" a title\u0007x");
        check("a split OSC is swallowed whole", "x", osc.line(0));

        Screen dcs = new Screen(20, 4);
        dcs.feed("\u001bP+q4d");
        check("an incomplete DCS prints nothing", "", dcs.text());
        dcs.feed("73\u001b\\z");
        check("a split DCS is swallowed whole", "z", dcs.line(0));
    }
}
