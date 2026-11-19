package io.github.mbanucu.opencodesetup;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * A small terminal for the output of a command run under a pseudo terminal.
 *
 * <p>Under a PTY a command behaves as it does in the Termux window: apt draws
 * its progress percentage by returning the carriage to the start of the line,
 * errors arrive in colour, and every line ends in {@code \r\n}. Writing those
 * bytes straight into a TextView would show the escape codes and one line per
 * progress update, so they are interpreted here: {@code \r} rewrites the line,
 * {@code \b} deletes, tabs expand, SGR sets colour and bold, and every other
 * escape sequence is dropped instead of printed.
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

    /** A long install prints a lot; the oldest lines go first. */
    private static final int MAX_LINES = 3000;

    private static final int[] CUBE = {0, 95, 135, 175, 215, 255};

    private final List<SpannableStringBuilder> lines = new ArrayList<>();
    private final StringBuilder parameters = new StringBuilder();

    private int current;
    private int colour;
    private boolean bold;

    // The stretch of the current line that has not been given its colour yet.
    private int runStart;
    private int runColour;
    private boolean runBold;

    private State state = State.TEXT;

    private enum State { TEXT, ESCAPE, SEQUENCE, OSC, OSC_ESCAPE }

    Terminal() {
        reset();
    }

    /** Forgets everything, like a terminal that was just opened. */
    void reset() {
        lines.clear();
        lines.add(new SpannableStringBuilder());
        current = 0;
        colour = 0;
        bold = false;
        runStart = 0;
        runColour = 0;
        runBold = false;
        state = State.TEXT;
        parameters.setLength(0);
    }

    /** Feeds exactly what the PTY wrote; escape sequences are interpreted. */
    void append(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            feed(chunk.charAt(i));
        }
    }

    /** The screen as it would look: colours kept as spans, cursor position applied. */
    CharSequence render() {
        closeRun();
        SpannableStringBuilder all = new SpannableStringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                all.append('\n');
            }
            all.append(lines.get(i));
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
                    state = State.TEXT;          // a two-byte escape: nothing to show
                }
                return;
            case SEQUENCE:
                if (c >= 0x40 && c <= 0x7e) {    // final byte of a CSI sequence
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
                    state = State.TEXT;          // window title and the like
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
                // Carriage return: the line is redrawn from column 0. That is
                // how apt moves "45%" to "46%" without printing a new line.
                closeRun();
                line().clear();
                runStart = 0;
                runColour = colour;
                runBold = bold;
                return;
            case '\n':
                closeRun();
                newLine();
                return;
            case '\b':
                closeRun();
                if (line().length() > 0) {
                    line().delete(line().length() - 1, line().length());
                }
                runStart = line().length();
                return;
            case '\t':
                closeRun();
                for (int i = 8 - line().length() % 8; i > 0; i--) {
                    line().append(' ');
                }
                runStart = line().length();
                return;
            default:
                if (c < 0x20 || c == 0x7f) {
                    return;
                }
                if (colour != runColour || bold != runBold) {
                    closeRun();
                }
                line().append(c);
        }
    }

    private void newLine() {
        current++;
        if (current == lines.size()) {
            lines.add(new SpannableStringBuilder());
            if (lines.size() > MAX_LINES) {
                lines.remove(0);
                current--;
            }
        }
        runStart = 0;
        runColour = colour;
        runBold = bold;
    }

    private SpannableStringBuilder line() {
        return lines.get(current);
    }

    /** Gives the stretch written so far its colour, so spans stay per word. */
    private void closeRun() {
        SpannableStringBuilder line = line();
        int end = line.length();
        if (end > runStart) {
            if (runColour != 0) {
                line.setSpan(new ForegroundColorSpan(runColour), runStart, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (runBold) {
                line.setSpan(new StyleSpan(Typeface.BOLD), runStart, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        runStart = end;
        runColour = colour;
        runBold = bold;
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
                int value = 0;
                int next = number(parts[i + 1]);
                if (next == 5 && i + 2 < parts.length) {
                    value = colour256(number(parts[i + 2]));
                    i += 2;
                } else if (next == 2 && i + 4 < parts.length) {
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
