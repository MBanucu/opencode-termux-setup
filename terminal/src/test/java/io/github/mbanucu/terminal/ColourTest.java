package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.hex;

/**
 * Checks SGR colours: what is consumed without printing, the 16/256/truecolor
 * forms, backgrounds, bold brightening and the resets.
 */
public final class ColourTest {

    public static void main(String[] args) {
        coloursAreConsumed();
        colours();
        backgrounds();

        TestSupport.report("colour checks");
    }

    // --- sequences that must not print -------------------------------------------

    private static void coloursAreConsumed() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[1;31;44mred\u001b[0m plain");
        check("SGR does not print anything", "red plain", screen.line(0));

        Screen cursor = new Screen(20, 4);
        cursor.feed("\u001b[?25l\u001b[?25hx");
        check("cursor-visibility modes are not text", "x", cursor.line(0));
    }

    private static void colours() {
        Screen palette = new Screen(30, 4);
        palette.feed("\u001b[31mred\u001b[0m plain");
        check("SGR 31 colours its text", "0xcc0000", hex(palette.foreground(0, 0)));
        check("SGR 0 returns the rest to the default colour", "0x000000",
                hex(palette.foreground(0, 3)));

        Screen bright = new Screen(20, 4);
        bright.feed("\u001b[1;32mbold");
        check("bold brightens a palette colour", "0x8ae234", hex(bright.foreground(0, 0)));
        check("bold is cleared by SGR 22", "0x4e9a06", boldAfterReset());

        Screen grey = new Screen(20, 4);
        grey.feed("\u001b[90mgrey");
        check("SGR 90 is bright black", "0x555753", hex(grey.foreground(0, 0)));

        Screen cube = new Screen(20, 4);
        cube.feed("\u001b[38;5;208morange");
        check("SGR 38;5;208 is the 256-colour cube entry", "0xff8700",
                hex(cube.foreground(0, 0)));

        Screen truecolor = new Screen(20, 4);
        truecolor.feed("\u001b[38;2;10;20;30mrgb");
        check("SGR 38;2;r;g;b is truecolor", "0x0a141e", hex(truecolor.foreground(0, 0)));

        Screen cleared = new Screen(20, 4);
        cleared.feed("\u001b[31mred\u001b[2J");
        check("clearing the screen clears the colours", "0x000000",
                hex(cleared.foreground(0, 0)));

        Screen background = new Screen(20, 4);
        background.feed("\u001b[44mblue background");
        check("a background code leaves the foreground alone", "0x000000",
                hex(background.foreground(0, 0)));
    }

    private static void backgrounds() {
        Screen fill = new Screen(20, 4);
        fill.feed("\u001b[44mblue");
        check("SGR 44 fills the cells behind the text", "0x3465a4",
                hex(fill.background(0, 0)));
        check("a background leaves the foreground alone", "0x000000",
                hex(fill.foreground(0, 0)));
        fill.feed("\u001b[49m plain");
        check("SGR 49 leaves what was already drawn", "0x3465a4",
                hex(fill.background(0, 0)));
        check("SGR 49 restores the default for what follows", "0x000000",
                hex(fill.background(0, 5)));

        Screen cube = new Screen(20, 4);
        cube.feed("\u001b[48;5;208morange");
        check("SGR 48;5;n is the 256-colour cube", "0xff8700",
                hex(cube.background(0, 0)));

        Screen truecolor = new Screen(20, 4);
        truecolor.feed("\u001b[48;2;10;20;30mrgb");
        check("SGR 48;2;r;g;b is truecolor", "0x0a141e",
                hex(truecolor.background(0, 0)));

        Screen reset = new Screen(20, 4);
        reset.feed("\u001b[44m\u001b[0mplain");
        check("SGR 0 clears the background too", "0x000000", hex(reset.background(0, 0)));

        Screen cleared = new Screen(20, 4);
        cleared.feed("\u001b[44m\u001b[2J");
        check("clearing with a background in force fills it", "0x3465a4",
                hex(cleared.background(0, 0)));

        Screen erased = new Screen(20, 4);
        erased.feed("\u001b[44mxx\u001b[1;1H\u001b[0K");
        check("erasing a line fills with the background in force", "0x3465a4",
                hex(erased.background(0, 1)));

        // What ncurses actually does for a box's fill: one space, then ECH.
        Screen eol = new Screen(20, 4);
        eol.feed("\u001b[44m \u001b[3X");
        check("ECH fills with the background in force", "0x3465a4",
                hex(eol.background(0, 2)));

        Screen rep = new Screen(20, 4);
        rep.feed("\u001b[44m \u001b[3b");
        check("REP repeats the last character with its background", "0x3465a4",
                hex(rep.background(0, 3)));
        check("REP leaves the text as spaces", "", rep.line(0));
    }

    /** Bold set, then 22: the colour stays, the brightness goes. */
    private static String boldAfterReset() {
        Screen screen = new Screen(20, 4);
        screen.feed("\u001b[1;32mbold\u001b[22mplain");
        return hex(screen.foreground(0, 4));
    }

}
