"""A form in the terminal: an input box centred in the screen, and the lines it
is given echoed in a box above it.

Deliberately small. It is the second TUI the end-to-end suite drives through an
accessibility service, and it exists to check that text a service types lands in
a box the view can draw, not only in a shell prompt. Keys arrive one at a time as
characters, Enter submits the line and clears the box, and `/quit` submitted as a
line leaves the alternate screen.
"""

import curses

WIDTH = 40
ECHOES = 3
QUIT = "/quit"


def write(screen, y, x, text):
    """One row, clipped: the last cell of the last row raises instead of drawing."""
    try:
        screen.addstr(y, x, text)
    except curses.error:
        pass


def box(screen, top, left, width, height, lines):
    border = "+" + "-" * (width - 2) + "+"
    write(screen, top, left, border)
    for offset in range(height - 2):
        content = lines[offset] if offset < len(lines) else ""
        write(screen, top + 1 + offset, left,
              "|" + content[:width - 2].ljust(width - 2) + "|")
    write(screen, top + height - 1, left, border)


def layout(rows, cols):
    """Both boxes: the input centred in the screen, the output directly above it."""
    width = min(WIDTH, max(6, cols - 2))
    left = max(0, (cols - width) // 2)
    input_top = max(0, (rows - 3) // 2)
    output_top = input_top - (ECHOES + 2) - 1
    if output_top < 0:
        output_top = 0
        input_top = ECHOES + 3
    return width, left, output_top, input_top


def draw(screen, echoes, typed):
    screen.erase()
    rows, cols = screen.getmaxyx()
    width, left, output_top, input_top = layout(rows, cols)
    box(screen, output_top, left, width, ECHOES + 2, echoes[-ECHOES:])
    box(screen, input_top, left, width, 3, ["  > " + typed])
    # The cursor is at the end of what has been typed: without it an input box
    # says nothing about where the next character will land.
    try:
        screen.move(input_top + 1, min(left + 5 + len(typed), left + width - 2))
    except curses.error:
        pass
    screen.refresh()


def main(screen):
    curses.curs_set(1)
    echoes = []
    typed = ""
    while True:
        draw(screen, echoes, typed)
        key = screen.getch()
        if key in (10, 13, curses.KEY_ENTER):
            line = typed.strip()
            typed = ""
            if line == QUIT:
                break
            if line:
                echoes.append("  " + line)
        elif key in (8, 127, curses.KEY_BACKSPACE):
            typed = typed[:-1]
        elif 32 <= key <= 126:
            typed += chr(key)
        # Anything else - an arrow, a resize - only costs a redraw.


curses.wrapper(main)
print("echo tui left the alternate screen")
