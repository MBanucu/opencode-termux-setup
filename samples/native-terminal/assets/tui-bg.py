"""A TUI whose boxes are filled with a background colour.

The grid keeps a foreground for every cell; this one also asks for the
background, in SGR 40-47, 48;5;n or 48;2;r;g;b. A box is a rectangle of spaces in
a colour pair with its text written over them - the fill is the point, so those
spaces must not be dropped as empty cells.
"""

import curses

BOX_WIDTH = 42
BLUE = 1
YELLOW = 2


def fill(screen, top, left, height, width, pair):
    blank = " " * width
    for row in range(height):
        try:
            screen.addstr(top + row, left, blank, pair)
        except curses.error:
            pass


def main(screen):
    curses.curs_set(0)
    curses.start_color()
    curses.init_pair(BLUE, curses.COLOR_WHITE, curses.COLOR_BLUE)
    curses.init_pair(YELLOW, curses.COLOR_BLACK, curses.COLOR_YELLOW)
    while True:
        screen.erase()
        rows, cols = screen.getmaxyx()
        width = min(BOX_WIDTH, max(10, cols - 2))
        left = max(0, (cols - width) // 2)
        top = max(0, (rows - 13) // 2)

        blue = curses.color_pair(BLUE)
        fill(screen, top, left, 7, width, blue)
        # Three lines in seven rows, one blank row above, between and below.
        try:
            screen.addstr(top + 1, left + 2, "BG-TUI", blue | curses.A_BOLD)
            screen.addstr(top + 3, left + 2, "this box is filled with a colour", blue)
            screen.addstr(top + 5, left + 2, "behind the cells, not the glyphs", blue)
        except curses.error:
            pass

        yellow = top + 8
        fill(screen, yellow, left, 3, width, curses.color_pair(YELLOW))
        try:
            screen.addstr(yellow + 1, left + 2, "yellow background (SGR 43)",
                          curses.color_pair(YELLOW))
        except curses.error:
            pass

        screen.refresh()
        if screen.getch() in (ord("q"), ord("Q")):
            break


curses.wrapper(main)
print("bg tui left the alternate screen")
