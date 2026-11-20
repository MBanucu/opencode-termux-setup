"""The terminal modes and queries the opencode entry page uses, with a sentinel.

Captured at 100x30, opencode enters the alternate screen, brackets its repaint
with ?2026, hides and shows the caret, enables bracketed paste, mouse reporting
(1000/1002/1003/1006) and focus events (?2031), then asks the terminal
questions: DA (CSI c), cursor position (CSI 6n), kitty keyboard (CSI ? u), a
termcap pair (DCS +q), and the foreground and background colours (OSC 10/11).

None of those draws anything. All of them must be consumed: a payload that
leaked would land in the grid as text. The sentinel row is what a test reads,
and `q` is what proves the modes did not swallow the keyboard.
"""

import os
import sys
import termios
import tty

MODES = [
    "\u001b[?1049h",  # alternate screen
    "\u001b[?2026h",  # synchronized update
    "\u001b[?2004h",  # bracketed paste
    "\u001b[?1000h",  # mouse clicks
    "\u001b[?1002h",  # mouse drag
    "\u001b[?1003h",  # any motion
    "\u001b[?1006h",  # SGR mouse
    "\u001b[?2031h",  # focus events
    "\u001b[?25l",    # caret off while painting
]
QUERIES = [
    "\u001b[6n",              # cursor position
    "\u001b[c",               # device attributes
    "\u001b[?u",              # kitty keyboard
    "\u001b[>0q",             # version-ish
    "\u001bP+q4d73\u001b\\",  # termcap pair
    "\u001b]10;?\u0007",      # foreground colour
    "\u001b]11;?\u0007",      # background colour
    "\u001b]1337;Capabilities\u0007",
    "\u001b]7501;?\u0007",
]


def at(row, col):
    return "\u001b[%d;%dH" % (row, col)


def main():
    saved = termios.tcgetattr(0)
    try:
        tty.setcbreak(0)
        sys.stdout.write(
            "\u001b[48;2;10;10;10m\u001b[2J"
            + "".join(MODES) + "".join(QUERIES)
            + at(3, 3) + "\u001b[38;2;255;255;255mMODES-OK"
            + at(5, 3) + "\u001b[38;2;128;128;128malt sync paste mouse cursor queries"
            + "\u001b[?2026l\u001b[?25h")
        sys.stdout.flush()
        while os.read(0, 1) not in (b"q", b"Q"):
            pass
    finally:
        termios.tcsetattr(0, termios.TCSADRAIN, saved)
        sys.stdout.write("\u001b[?1000l\u001b[?1002l\u001b[?1003l\u001b[?1006l"
                         "\u001b[?2004l\u001b[?2031l\u001b[?25h\u001b[?1049l")
        sys.stdout.flush()
    print("modes tui left the alternate screen")


main()
