"""The opencode entry page's glyphs and colours, without opencode.

Captured from `opencode` at 80x24 in Termux's own GUI, the entry page draws with
seven non-ASCII glyphs - U+2580 upper half block, U+2588 full block, U+2584 lower
half block, U+2503 heavy vertical, U+2579 heavy up, U+2026 ellipsis and U+00B7
middle dot - and with truecolor SGR only: a grey-to-white logo gradient, fg
#EEEEEE/#808080, bg #0A0A0A/#1E1E1E, a blue #5C9CF5 composer gutter and bold.

This is the page in miniature: the block logo, the composer's blue gutter and
`╹▀▀▀▀` rule, the model row, the footer and the version, on the same rows the
page uses (logo 11-13, composer 16-19, rule 20, footer 21, version 24). It writes
the escapes itself because curses cannot ask for an arbitrary RGB, and the point
is the parser: a dropped glyph or a background that never reaches the cell has to
show up here.
"""

import os
import sys
import termios
import tty

BG = "\u001b[48;2;10;10;10m"      # #0A0A0A, the page's canvas
PANEL = "\u001b[48;2;30;30;30m"   # #1E1E1E, the composer's panel
ACCENT = "\u001b[38;2;92;156;245m"  # #5C9CF5, the composer's blue gutter
WHITE = "\u001b[38;2;255;255;255m"
LIGHT = "\u001b[38;2;238;238;238m"
GREY = "\u001b[38;2;128;128;128m"
BORDER = "\u001b[38;2;67;67;67m"  # #434343, the rule under the panel
RESET = "\u001b[0m"

LOGO = [
    "█▀▀█ █▀▀█ █▀▀█ █▀▀▄ █▀▀▀ █▀▀█ █▀▀█ █▀▀█",
    "█  █ █  █ █▀▀▀ █  █ █    █  █ █  █ █▀▀▀",
    "▀▀▀▀ █▀▀▀ ▀▀▀▀ ▀▀▀▀ ▀▀▀▀ ▀▀▀▀ ▀▀▀▀ ▀▀▀▀",
]

SYMBOLS = "SYMBOLS ▀ █ ▄ ┃ ╹ … ·"


def at(row, col):
    return "\u001b[%d;%dH" % (row, col)


def shade(cell, cells):
    """The logo's gradient: #808080 on the left, #FFFFFF on the right."""
    level = 128 + round(127 * cell / max(1, cells - 1))
    return "\u001b[38;2;%d;%d;%dm" % (level, level, level)


def draw(width, height):
    # Reset first: the erase takes the background in force, so setting BG after
    # the reset is what makes the canvas #0A0A0A instead of the view's default.
    out = [RESET, BG, "\u001b[2J"]

    def put(row, col, colour, text):
        if row > height or col > width:
            return
        out.append(at(row, col))
        out.append(colour)
        out.append(text[:max(0, width - col + 1)])

    logo_left = max(1, (width - len(LOGO[0])) // 2 + 2)
    for i, line in enumerate(LOGO):
        for j, glyph in enumerate(line):
            put(11 + i, logo_left + j, shade(j, len(line)), glyph)
    # The row naming every glyph the page uses; the page itself leaves row 9
    # blank, but this program exists to carry each of them through the parser.
    put(9, logo_left, LIGHT, SYMBOLS)

    # The composer: four rows of panel, the prompt and the model row, and the
    # heavy gutter beside all of it - blue in the page, unlike the rest of it.
    for row in (16, 17, 18, 19):
        put(row, 1, PANEL, " " * width)
    put(17, 7, PANEL + WHITE, 'Ask anything… "Fix broken tests"')
    put(19, 7, PANEL + GREY, "Build auto · Big Pickle Default / OpenCode")
    for row in (16, 17, 18, 19):
        put(row, 4, PANEL + ACCENT, "┃")
    put(20, 4, BORDER, "╹")
    put(20, 5, BORDER, "▀" * max(0, width - 6))

    # The footer is right-aligned under the rule, and the version sits on the
    # page's last row - not beside the footer.
    put(21, 4, GREY, "~")
    footer = "tab agents  ctrl+p commands"
    put(21, max(1, width - 1 - len(footer)), GREY, footer)
    put(24, max(1, width - 6), GREY, "2.3.2")
    # The caret is in the composer, at the start of the empty input.
    out.append(at(17, 7))
    return "".join(out)


def main():
    try:
        size = os.get_terminal_size(1)
    except OSError:
        size = os.terminal_size((80, 24))
    saved = termios.tcgetattr(0)
    try:
        tty.setcbreak(0)
        sys.stdout.write("\u001b[?1049h\u001b[?25l\u001b[?2026h"
                         + draw(size.columns, size.lines)
                         + "\u001b[?2026l\u001b[?25h")
        sys.stdout.flush()
        while os.read(0, 1) not in (b"q", b"Q"):
            pass
    finally:
        termios.tcsetattr(0, termios.TCSADRAIN, saved)
        sys.stdout.write("\u001b[?25h\u001b[?1049l")
        sys.stdout.flush()
    print("symbols tui left the alternate screen")


main()
