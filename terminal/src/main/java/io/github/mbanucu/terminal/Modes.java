package io.github.mbanucu.terminal;

/**
 * The private modes the screen keeps that are not buffers: the caret the view
 * draws, DECCKM and a program's bracket around one repaint.
 *
 * <p>These outlive one sequence, so they live together rather than as loose
 * booleans on the screen: a program sets a mode and expects it to hold, and the
 * synchronized-update bracket defers the caret exactly as long as it is open.
 * The alternate screen is not here - switching it swaps buffers, which is the
 * screen's work.
 */
final class Modes {

    /** DEC mode 25: whether the program has left the caret on. */
    private boolean cursorVisible = true;
    /** DEC mode 12 / DECSCUSR: whether the caret should blink. */
    private boolean cursorBlink;
    /** DECCKM: arrows and Home/End use SS3 (ESC O) instead of CSI. */
    private boolean applicationCursor;
    /** Between ?2026h and ?2026l, mode changes wait for the frame to end. */
    private boolean syncUpdate;
    private boolean pendingCursorVisible;
    private boolean hasPendingCursorVisible;

    boolean cursorVisible() {
        return cursorVisible;
    }

    boolean cursorBlink() {
        return cursorBlink;
    }

    boolean applicationCursors() {
        return applicationCursor;
    }

    /** DECSCUSR: 1/3/5 blink, 2/4/6 are steady, 0 goes back to the default. */
    void setCursorStyle(int style) {
        cursorBlink = style != 0 && style % 2 == 1;
    }

    /**
     * Applies one private mode, or returns false when it is not kept here.
     *
     * <p>Synchronized update (2026) is a bracket: the caret change inside it is
     * held until the program closes the bracket, because opencode hides the
     * caret at the start of every frame and shows it at the end.
     */
    boolean apply(int mode, boolean set) {
        if (mode == 2026) {
            if (set) {
                syncUpdate = true;
            } else {
                syncUpdate = false;
                if (hasPendingCursorVisible) {
                    cursorVisible = pendingCursorVisible;
                    hasPendingCursorVisible = false;
                }
            }
            return true;
        }
        if (mode == 25) {
            if (syncUpdate) {
                pendingCursorVisible = set;
                hasPendingCursorVisible = true;
            } else {
                cursorVisible = set;
            }
            return true;
        }
        if (mode == 1) {
            applicationCursor = set;
            return true;
        }
        if (mode == 12) {
            cursorBlink = set;
            return true;
        }
        return false;
    }

    /** A program that leaves mid-update must not leave the caret deferred. */
    void endUpdate() {
        syncUpdate = false;
        hasPendingCursorVisible = false;
    }
}
