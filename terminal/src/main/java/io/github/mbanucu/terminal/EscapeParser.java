package io.github.mbanucu.terminal;

/**
 * Splits terminal output into text, controls and escape sequences.
 *
 * <p>Output arrives in chunks that can cut a sequence in half, so the parser
 * keeps the incomplete tail between feeds. What a sequence changes belongs to
 * the {@link Handler}; this class only knows where one ends. OSC/DCS/SOS/PM/APC
 * payloads are swallowed as strings, not printed as text.
 */
final class EscapeParser {

    /** What a parsed chunk means to the screen. */
    interface Handler {
        void text(char c);

        void carriageReturn();

        void lineFeed();

        void backspace();

        void tab();

        void controlSequence(String raw, char last);
    }

    private final StringBuilder pending = new StringBuilder();
    private final Handler handler;

    EscapeParser(Handler handler) {
        this.handler = handler;
    }

    void feed(String chunk) {
        pending.append(chunk);
        int index = 0;
        while (index < pending.length()) {
            char c = pending.charAt(index);
            if (c == 0x1b) {
                int consumed = escapeAt(index);
                if (consumed == 0) {
                    break;
                }
                index += consumed;
                continue;
            }
            switch (c) {
                case '\r':
                    handler.carriageReturn();
                    break;
                case '\n':
                    handler.lineFeed();
                    break;
                case '\b':
                    handler.backspace();
                    break;
                case '\t':
                    handler.tab();
                    break;
                default:
                    if (c >= 0x20 && c != 0x7f) {
                        handler.text(c);
                    }
                    break;
            }
            index++;
        }
        pending.delete(0, index);
        // A sequence longer than any terminal could need is a program that is
        // not a terminal's output; drop it rather than growing forever.
        if (pending.length() > 8192) {
            pending.setLength(0);
        }
    }

    /** Number of characters this escape takes, or 0 when it is not complete yet. */
    private int escapeAt(int index) {
        int length = pending.length();
        if (index + 1 >= length) {
            return 0;
        }
        char kind = pending.charAt(index + 1);
        if (kind == '[') {
            for (int j = index + 2; j < length; j++) {
                char c = pending.charAt(j);
                if (c >= 0x40 && c <= 0x7e) {
                    handler.controlSequence(pending.substring(index + 2, j), c);
                    return j - index + 1;
                }
            }
            return 0;
        }
        if (kind == ']' || kind == 'P' || kind == 'X' || kind == '^' || kind == '_') {
            // OSC, DCS, SOS, PM and APC are strings, not text. opencode asks its
            // termcap question with DCS (+q4d73); the payload is not a row.
            for (int j = index + 2; j < length; j++) {
                char c = pending.charAt(j);
                if (c == 0x07) {
                    return j - index + 1;
                }
                if (c == 0x1b) {
                    if (j + 1 >= length) {
                        return 0;
                    }
                    if (pending.charAt(j + 1) == '\\') {
                        return j - index + 2;
                    }
                }
            }
            return 0;
        }
        if (kind == '(' || kind == ')' || kind == '*' || kind == '+') {
            return index + 2 < length ? 3 : 0;
        }
        return 2;
    }
}
