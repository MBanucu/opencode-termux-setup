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
    /** Mode 2004: the program wants a paste wrapped in ESC [ 200 ~ / 201 ~. */
    private boolean bracketedPaste;
    /** 1000/1002/1003: press/release, button-motion or any-motion reports. */
    private int mouseMode;
    /** 1006 (SGR) or 1015 (urxvt): how a mouse report names its coordinates. */
    private int mouseEncoding;
    /** 1004/2031: report focus in and out. */
    private boolean focusReporting;
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

    boolean bracketedPaste() {
        return bracketedPaste;
    }

    int mouseMode() {
        return mouseMode;
    }

    int mouseEncoding() {
        return mouseEncoding;
    }

    boolean focusReporting() {
        return focusReporting;
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
        switch (mode) {
            case 2026:
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
            case 25:
                if (syncUpdate) {
                    pendingCursorVisible = set;
                    hasPendingCursorVisible = true;
                } else {
                    cursorVisible = set;
                }
                return true;
            case 1:
                applicationCursor = set;
                return true;
            case 12:
                cursorBlink = set;
                return true;
            case 2004:
                bracketedPaste = set;
                return true;
            case 1000:
            case 1002:
            case 1003:
                // The three tracking modes replace each other; each turns itself off.
                if (set) {
                    mouseMode = mode;
                } else if (mouseMode == mode) {
                    mouseMode = 0;
                }
                return true;
            case 1006:
            case 1015:
                if (set) {
                    mouseEncoding = mode;
                } else if (mouseEncoding == mode) {
                    mouseEncoding = 0;
                }
                return true;
            case 1004:
            case 2031:
                focusReporting = set;
                return true;
            default:
                return false;
        }
    }

    /** A program that leaves mid-update must not leave the caret deferred. */
    void endUpdate() {
        syncUpdate = false;
        hasPendingCursorVisible = false;
    }
}
