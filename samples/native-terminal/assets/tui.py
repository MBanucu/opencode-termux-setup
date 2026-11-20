import curses
import os

ITEMS = ["one", "two", "three"]
WIDTH = 38


def row(screen, y, text):
    screen.addstr(y, 1, "|" + text.ljust(WIDTH)[:WIDTH] + "|")


def draw(screen, selected, flags, status):
    screen.erase()
    screen.addstr(1, 1, "+" + "-" * WIDTH + "+")
    row(screen, 2, "  BOX-TUI")
    row(screen, 3, "")
    row(screen, 4, "  [File] [Edit] [View] [Help]")
    row(screen, 5, "")
    for index, name in enumerate(ITEMS):
        marker = ">" if index == selected else " "
        checked = "x" if flags[index] else " "
        row(screen, 6 + index, "  %s [%s] %s" % (marker, checked, name))
    row(screen, 9, "")
    row(screen, 10, "  status: %s" % status)
    row(screen, 11, "")
    screen.addstr(12, 1, "+" + "-" * WIDTH + "+")
    screen.addstr(14, 1, "> ")
    screen.refresh()


def main(screen):
    # A blinking block: the caret is the terminal's to draw, so the program only
    # asks for the style. curses has no call for DECSCUSR, hence the raw write.
    curses.curs_set(1)
    os.write(1, b"\x1b[1 q")
    selected = 0
    flags = [False, False, False]
    status = "start"
    while True:
        draw(screen, selected, flags, status)
        key = screen.getch()
        if key in (ord("n"), ord("j"), curses.KEY_DOWN):
            selected = (selected + 1) % len(ITEMS)
            status = "next"
        elif key in (ord("p"), ord("k"), curses.KEY_UP):
            selected = (selected - 1) % len(ITEMS)
            status = "prev"
        elif key in (ord("t"), ord(" ")):
            flags[selected] = not flags[selected]
            status = "toggle"
        elif key == ord("q"):
            break
        # Every submitted line ends with Enter; curses sees it as a key, and it
        # means nothing here.


curses.wrapper(main)
print("tui left the alternate screen")
