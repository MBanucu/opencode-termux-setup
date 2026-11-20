"""A deliberately small TUI for measuring glyph widths on the cell grid.

The markers are ASCII and the margin is a red glyph in one cell, so both follow
the cell grid whatever font is in use. The bars are sixty block glyphs, columns
11-70, and the margin is column 71: if a block's ink is not the cell width it
spills into the margin, which a screenshot shows as white next to the red
marker. The box ends on the same column, so its right side has the same
reference.

It also puts the entry page's glyphs in one column, so their ink starts can be
compared on one screen without opencode.
"""

import os
import sys
import termios
import tty

RESET = "\x1b[0m"
BG = "\x1b[48;2;10;10;10m"
WHITE = "\x1b[38;2;255;255;255m"
GREY = "\x1b[38;2;128;128;128m"
BLUE = "\x1b[38;2;92;156;245m"
MARGIN = "\x1b[38;2;255;80;80m|"  # one cell, col 71: the overhang witness


def at(row, col):
    return "\x1b[%d;%dH" % (row, col)


def draw(width, height):
    out = [RESET, BG, "\x1b[2J"]

    def put(row, col, text):
        if 1 <= row <= height and 1 <= col <= width:
            out.append(at(row, col) + text)

    # The margin exists before the bars: a bar that overhangs its cell paints
    # white into this marker's cell.
    for row in range(3, 12):
        put(row, 71, MARGIN)
    # The margin's SGR is stateful: put the page colours back, or every cell
    # written after this point inherits the red foreground.
    out.append(RESET + BG)

    # A cell ruler: one digit per column, so column 71 is the margin's cell.
    put(2, 1, GREY + "".join(str((c + 1) % 10) for c in range(width)))

    # Sixty cells, columns 11-70, with one cell of margin before column 72.
    for i, glyph in enumerate(("█", "▀", "▄")):
        put(3 + i, 11, WHITE + glyph * 60)
    put(7, 11, GREY + "─" * 60)

    # A box whose right side ends on column 70, like the bars.
    put(9, 11, BLUE + "┌" + "─" * 58 + "┐")
    put(10, 11, BLUE + "│" + " " * 58 + "│")
    put(11, 11, BLUE + "└" + "─" * 58 + "┘")

    # The entry page's glyphs, each at column 11, with a label to its right.
    glyphs = ("█", "▀", "▄", "┃", "╹", "…", "·", "A", "/")
    for i, glyph in enumerate(glyphs):
        put(13 + i, 11, WHITE + glyph + GREY + "  glyph %d" % (i + 1))

    put(13 + len(glyphs) + 1, 11, GREY + "WIDTH-TUI %dx%d  q quits" % (width, height))
    return "".join(out) + at(2, 1)


def main():
    try:
        size = os.get_terminal_size(1)
        width, height = size.columns, size.lines
    except OSError:
        width, height = 80, 24
    saved = termios.tcgetattr(0)
    try:
        tty.setcbreak(0)
        sys.stdout.write("\x1b[?1049h\x1b[?25l" + draw(width, height))
        sys.stdout.flush()
        while os.read(0, 1) not in (b"q", b"Q"):
            pass
    finally:
        termios.tcsetattr(0, termios.TCSADRAIN, saved)
        sys.stdout.write("\x1b[?25h\x1b[?1049l")
        sys.stdout.flush()
    print("width tui left the alternate screen")


main()
