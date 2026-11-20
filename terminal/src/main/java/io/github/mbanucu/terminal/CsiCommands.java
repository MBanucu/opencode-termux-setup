package io.github.mbanucu.terminal;

/**
 * The non-private CSI sequences: where text goes, what is erased, where the
 * scroll region is and what is saved.
 *
 * <p>Parses the parameters and calls one {@link Screen} method per command, so
 * the state and the operations stay in the screen and this file is only the
 * table of sequences. Private modes, SGR and the caret stay with the screen.
 */
final class CsiCommands {

    private final Screen screen;

    CsiCommands(Screen screen) {
        this.screen = screen;
    }

    void dispatch(String[] parts, char last) {
        switch (last) {
            case 'H':
            case 'f':
                screen.moveTo(number(parts, 0, 1), number(parts, 1, 1));
                break;
            case 'A':
                screen.moveUp(number(parts, 0, 1));
                break;
            case 'B':
                screen.moveDown(number(parts, 0, 1));
                break;
            case 'C':
                screen.moveRight(number(parts, 0, 1));
                break;
            case 'D':
                screen.moveLeft(number(parts, 0, 1));
                break;
            case 'E':
                screen.nextLine(number(parts, 0, 1));
                break;
            case 'F':
                screen.previousLine(number(parts, 0, 1));
                break;
            case 'G':
                screen.setColumn(number(parts, 0, 1));
                break;
            case 'd':
                screen.setRow(number(parts, 0, 1));
                break;
            case 'J':
                screen.eraseScreen(number(parts, 0, 0));
                break;
            case 'K':
                screen.eraseLine(number(parts, 0, 0));
                break;
            case 'L':
                screen.insertLines(number(parts, 0, 1));
                break;
            case 'M':
                screen.deleteLines(number(parts, 0, 1));
                break;
            case 'P':
                screen.deleteChars(number(parts, 0, 1));
                break;
            case '@':
                screen.insertChars(number(parts, 0, 1));
                break;
            case 'X':
                screen.eraseChars(number(parts, 0, 1));
                break;
            case 'b':
                screen.repeat(number(parts, 0, 1));
                break;
            case 'S':
                screen.scrollUp(number(parts, 0, 1));
                break;
            case 'T':
                screen.scrollDown(number(parts, 0, 1));
                break;
            case 'r':
                screen.setScrollRegion(number(parts, 0, 1), number(parts, 1, screen.rows()));
                break;
            case 's':
                screen.saveCursor();
                break;
            case 'u':
                screen.restoreCursor();
                break;
            case 'm':
                screen.applySgr(parts);
                break;
            case 'q':
                screen.applyCursorStyle(parts);
                break;
            case 'n':
                screen.deviceStatus(parts);
                break;
            default:
                // h/l (modes other than the alternate screen): nothing to place.
                break;
        }
    }

    private static int number(String[] parts, int index, int fallback) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
