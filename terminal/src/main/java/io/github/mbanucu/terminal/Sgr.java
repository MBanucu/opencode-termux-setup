package io.github.mbanucu.terminal;

/**
 * The SGR state: the foreground and background in force, and whether bold is
 * brightening a palette colour.
 *
 * <p>Separate from {@link Screen} so the colour codes live with their state and
 * the grid only asks what to paint a cell with: 16 colours, 256 and truecolor,
 * bold, dim, underline, reverse, italic, strikethrough and hidden.
 */
final class Sgr {

    private int foreground;
    private int background;
    private boolean bold;
    private boolean dim;
    private boolean underline;
    private boolean reverse;
    private boolean italic;
    private boolean strikethrough;
    private boolean hidden;

    /** Applies one SGR sequence; no parameters means a full reset. */
    void apply(String[] parts) {
        if (parts.length == 0) {
            reset();
            return;
        }
        for (int i = 0; i < parts.length; i++) {
            int code = number(parts, i, 0);
            if (code >= 30 && code <= 37) {
                foreground = AnsiPalette.palette(code - 30);
            } else if (code >= 90 && code <= 97) {
                foreground = AnsiPalette.palette(code - 90 + 8);
            } else if (code == 38) {
                i = extended(parts, i, false);
            } else if (code == 48) {
                i = extended(parts, i, true);
            } else if (code >= 40 && code <= 47) {
                background = AnsiPalette.palette(code - 40);
            } else if (code >= 100 && code <= 107) {
                background = AnsiPalette.palette(code - 100 + 8);
            } else if (code == 0) {
                reset();
            } else if (code == 1) {
                bold = true;
            } else if (code == 2) {
                dim = true;
            } else if (code == 22) {
                // SGR 22 is "normal intensity": it ends bold and dim together.
                bold = false;
                dim = false;
            } else if (code == 3) {
                italic = true;
            } else if (code == 23) {
                italic = false;
            } else if (code == 4) {
                underline = true;
            } else if (code == 24) {
                underline = false;
            } else if (code == 7) {
                reverse = true;
            } else if (code == 27) {
                reverse = false;
            } else if (code == 8) {
                hidden = true;
            } else if (code == 28) {
                hidden = false;
            } else if (code == 9) {
                strikethrough = true;
            } else if (code == 29) {
                strikethrough = false;
            } else if (code == 39) {
                foreground = 0;
            } else if (code == 49) {
                background = 0;
            }
        }
    }

    int foreground() {
        return foreground;
    }

    int background() {
        return background;
    }

    /**
     * The foreground a cell is drawn with. Bold is applied here, when the cell
     * is written, so SGR 22 leaves what is already on screen bright.
     */
    int drawnForeground() {
        return bold ? AnsiPalette.brighten(foreground) : foreground;
    }

    /** The attributes in force, as {@link CellAttributes} bits. */
    int attributes() {
        int bits = 0;
        if (underline) {
            bits |= CellAttributes.UNDERLINE;
        }
        if (reverse) {
            bits |= CellAttributes.REVERSE;
        }
        if (dim) {
            bits |= CellAttributes.DIM;
        }
        if (italic) {
            bits |= CellAttributes.ITALIC;
        }
        if (strikethrough) {
            bits |= CellAttributes.STRIKETHROUGH;
        }
        if (hidden) {
            bits |= CellAttributes.HIDDEN;
        }
        return bits;
    }

    private void reset() {
        foreground = 0;
        background = 0;
        bold = false;
        dim = false;
        underline = false;
        reverse = false;
        italic = false;
        strikethrough = false;
        hidden = false;
    }

    /**
     * Reads {@code 38/48;5;n} or {@code 38/48;2;r;g;b}, and returns the last
     * index used. The same two forms name a foreground and a background.
     */
    private int extended(String[] parts, int index, boolean background) {
        int kind = number(parts, index + 1, -1);
        if (kind == 5) {
            int n = number(parts, index + 2, -1);
            if (n >= 0 && n < 256) {
                setColour(background, AnsiPalette.colour256(n));
            }
            return index + 2;
        }
        if (kind == 2) {
            int red = number(parts, index + 2, -1);
            int green = number(parts, index + 3, -1);
            int blue = number(parts, index + 4, -1);
            if (red >= 0 && green >= 0 && blue >= 0) {
                setColour(background, (red << 16) | (green << 8) | blue);
            }
            return index + 4;
        }
        return index;
    }

    private void setColour(boolean background, int colour) {
        if (background) {
            this.background = colour;
        } else {
            foreground = colour;
        }
    }

    private static int number(String[] parts, int index, int fallback) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return fallback;
        }
        // SGR 4:3 and friends carry a subparameter after a colon; the
        // attribute itself is the part before it.
        String value = parts[index];
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
