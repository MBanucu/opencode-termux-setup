package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the bytes a typed character sends: Ctrl folded to a control byte and
 * Alt prefixed by ESC, and the characters Ctrl leaves alone.
 */
public final class CharacterTest {

    public static void main(String[] args) {
        controls();
        alt();
        unmodifiedAreUntouched();
        TestSupport.report("character checks");
    }

    private static void controls() {
        check("Ctrl+C is the control byte", "\003",
                KeyEncoder.encodeCharacter('c', true, false));
        check("Ctrl+A is the first control byte", "\001",
                KeyEncoder.encodeCharacter('A', true, false));
        check("Ctrl+Space is NUL", "\000",
                KeyEncoder.encodeCharacter(' ', true, false));
        check("Ctrl+[ is ESC", "\033",
                KeyEncoder.encodeCharacter('[', true, false));
        check("Ctrl+\\ is FS", "\034",
                KeyEncoder.encodeCharacter('\\', true, false));
        check("Ctrl+] is GS", "\035",
                KeyEncoder.encodeCharacter(']', true, false));
        check("Ctrl+^ is RS", "\036",
                KeyEncoder.encodeCharacter('^', true, false));
        check("Ctrl+_ is US", "\037",
                KeyEncoder.encodeCharacter('_', true, false));
        check("Ctrl+? is DEL", "\177",
                KeyEncoder.encodeCharacter('?', true, false));
    }

    private static void alt() {
        check("Alt+x prefixes ESC", "\033x",
                KeyEncoder.encodeCharacter('x', false, true));
        check("Ctrl+Alt+C keeps both", "\033\003",
                KeyEncoder.encodeCharacter('c', true, true));
    }

    private static void unmodifiedAreUntouched() {
        check("a plain character is itself", "x",
                KeyEncoder.encodeCharacter('x', false, false));
        check("Ctrl on a character with no code leaves it alone", "\u00e9",
                KeyEncoder.encodeCharacter('\u00e9', true, false));
    }
}
