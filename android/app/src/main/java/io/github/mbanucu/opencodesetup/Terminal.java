package io.github.mbanucu.opencodesetup;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small terminal for the output of a command run under a pseudo terminal.
 *
 * <p>Under a PTY a command behaves as it does in the Termux window: apt rewrites
 * a progress line by returning the carriage to column 0, errors arrive in
 * colour, and lines end in {@code \r\n}. Those bytes cannot be printed as they
 * are, so they are interpreted the way a terminal would: {@code \r} moves the
 * cursor back to column 0 and the next characters overwrite the line, {@code \b}
 * deletes, tabs advance, SGR sets colour and bold, and other escapes are dropped.
 */
final class Terminal {

    /** The colours Termux uses in its own dark scheme, so output looks familiar. */
    private static final int[] NORMAL = {
            0xFF5C5C5C, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
            0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFDCDCDC,
    };
    private static final int[] BRIGHT = {
            0xFF7F7F7F, 0xFFE88B8B, 0xFFB5E890, 0xFFF0D197,
            0xFF82C7FF, 0xFFD79AE8, 0xFF7FD6DF, 0xFFFFFFFF,
    };
    private static final int[] CUBE = {0, 95, 135, 175, 215, 255};

    /** A long install prints a lot; the oldest lines go first. */
    private static final int MAX_LINES = 3000;

    /** One row: the characters written to it and the style each was written in. */
    private static final class Row {
        char[] text = new char[120];
        int[] colour = new int[120];
        boolean[] bold = new boolean[120];
        int length;

        void put(int column, char c, int colour, boolean bold) {
            if (column >= text.length) {
                int size = Math.max(column + 1, text.length * 2);
                text = Arrays.copyOf(text, size);
                this.colour = Arrays.copyOf(this.colour, size);
                this.bold = Arrays.copyOf(this.bold, size);
            }
            text[column] = c;
            this.colour[column] = colour;
            this.bold[column] = bold;
            if (column + 1 > length) {
                length = column + 1;
            }
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final StringBuilder parameters = new StringBuilder();

    private int current;
    private int cursor;
    private int colour;
    private boolean bold;

    private State state = State.TEXT;

    private enum State { TEXT, ESCAPE, SEQUENCE, OSC, OSC_ESCAPE }

    Terminal() {
        reset();
    }

    /** Forgets everything, like a terminal that was just opened. */
    void reset() {
        rows.clear();
        rows.add(new Row());
        current = 0;
        cursor = 0;
        colour = 0;
        bold = false;
        state = State.TEXT;
        parameters.setLength(0);
    }

    /** Feeds exactly what the PTY wrote; the escapes are interpreted. */
    void append(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            feed(chunk.charAt(i));
        }
    }

    /** The screen as it would look: colours kept as spans, cursor position applied. */
    CharSequence render() {
        SpannableStringBuilder all = new SpannableStringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                all.append('\n');
            }
            Row row = rows.get(i);
            int start = all.length();
            all.append(new String(row.text, 0, row.length));
            int run = 0;
            for (int column = 1; column <= row.length; column++) {
                boolean ends = column == row.length
                        || row.colour[column] != row.colour[run]
                        || row.bold[column] != row.bold[run];
                if (!ends) {
                    continue;
                }
                if (row.colour[run] != 0) {
                    all.setSpan(new ForegroundColorSpan(row.colour[run]), start + run,
                            start + column, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (row.bold[run]) {
                    all.setSpan(new StyleSpan(Typeface.BOLD), start + run, start + column,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                run = column;
            }
        }
        return all;
    }

    private void feed(char c) {
        switch (state) {
            case ESCAPE:
                if (c == '[') {
                    parameters.setLength(0);
                    state = State.SEQUENCE;
                } else if (c == ']') {
                    state = State.OSC;
                } else {
                    state = State.TEXT;              // a two-byte escape: nothing to show
                }
                return;
            case SEQUENCE:
                if (c >= 0x40 && c <= 0x7e) {        // final byte of a CSI sequence
                    if (c == 'm') {
                        sgr(parameters);
                    }
                    parameters.setLength(0);
                    state = State.TEXT;
                } else {
                    parameters.append(c);
                }
                return;
            case OSC:
                if (c == '\u0007') {
                    state = State.TEXT;              // window title and the like
                } else if (c == '\u001b') {
                    state = State.OSC_ESCAPE;
                }
                return;
            case OSC_ESCAPE:
                state = c == '\\' ? State.TEXT : State.OSC;
                return;
            default:
                write(c);
        }
    }

    private void write(char c) {
        switch (c) {
            case '\u001b':
                state = State.ESCAPE;
                return;
            case '\r':
                // Column 0, as a terminal does. The line is not erased here: the
                // characters that follow overwrite it, which is how apt turns
                // "45%" into "46%" and why the "\r" of "\r\n" costs nothing.
                cursor = 0;
                return;
            case '\n':
                current++;
                cursor = 0;
                if (current == rows.size()) {
                    rows.add(new Row());
                    if (rows.size() > MAX_LINES) {
                        rows.remove(0);
                        current--;
                    }
                }
                return;
            case '\b':
                if (cursor > 0) {
                    cursor--;
                }
                return;
            case '\t':
                int target = cursor + 8 - cursor % 8;
                while (cursor < target) {
                    row().put(cursor++, ' ', colour, bold);
                }
                return;
            default:
                if (c < 0x20 || c == 0x7f) {
                    return;
                }
                row().put(cursor++, c, colour, bold);
        }
    }

    private Row row() {
        return rows.get(current);
    }

    /** SGR: colour and bold, in the forms apt, curl and opencode actually send. */
    private void sgr(CharSequence parameters) {
        if (parameters.length() == 0) {
            colour = 0;
            bold = false;
            return;
        }
        String[] parts = parameters.toString().split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            int code = number(parts[i]);
            if (code == 0) {
                colour = 0;
                bold = false;
            } else if (code == 1) {
                bold = true;
            } else if (code == 22) {
                bold = false;
            } else if (code >= 30 && code <= 37) {
                colour = NORMAL[code - 30];
            } else if (code == 39) {
                colour = 0;
            } else if (code >= 90 && code <= 97) {
                colour = BRIGHT[code - 90];
            } else if ((code == 38 || code == 48) && i + 1 < parts.length) {
                boolean foreground = code == 38;
                int value;
                int kind = number(parts[i + 1]);
                if (kind == 5 && i + 2 < parts.length) {
                    value = colour256(number(parts[i + 2]));
                    i += 2;
                } else if (kind == 2 && i + 4 < parts.length) {
                    value = Color.rgb(number(parts[i + 2]), number(parts[i + 3]),
                            number(parts[i + 4]));
                    i += 4;
                } else {
                    break;
                }
                if (foreground) {
                    colour = value;
                }
            }
        }
    }

    private static int colour256(int index) {
        if (index < 0) {
            return 0;
        }
        if (index < 8) {
            return NORMAL[index];
        }
        if (index < 16) {
            return BRIGHT[index - 8];
        }
        if (index >= 232) {
            int level = 8 + Math.min(23, index - 232) * 10;
            return Color.rgb(level, level, level);
        }
        int n = index - 16;
        return Color.rgb(CUBE[n / 36], CUBE[n / 6 % 6], CUBE[n % 6]);
    }

    private static int number(String part) {
        try {
            return part.isEmpty() ? 0 : Integer.parseInt(part);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
