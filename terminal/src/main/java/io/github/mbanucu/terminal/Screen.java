package io.github.mbanucu.terminal;

import java.util.Arrays;

/**
 * A character grid: the part of a terminal that makes a full-screen program
 * visible.
 *
 * <p>opencode draws its interface by moving the cursor around the alternate
 * screen and erasing regions, so a view that only splits output on newlines ends
 * up showing the leftovers between escape sequences. This keeps cells, applies
 * the escapes that place, erase and scroll text, and keeps the foreground and
 * background each cell was drawn with — a row of cells is a row of text, and a
 * TUI's box is usually the background behind spaces.
 *
 * <p>Enough of ECMA-48 for a TUI, not an emulator: no scrolling regions across
 * buffers, no fonts, no mouse reporting, no replies to device queries.
 */
final class Screen implements EscapeParser.Handler {

    /** The xterm page's scrollback depth, so the native view starts at parity. */
    static final int DEFAULT_HISTORY_LINES = 3000;

    private final EscapeParser parser = new EscapeParser(this);
    /** Where answers to a program's queries go; null means stay quiet. */
    private ReplySink replySink;
    private final CsiCommands commands = new CsiCommands(this);
    private final Modes modes = new Modes();
    private char[][] main;
    private char[][] alt;
    /** Whether each row of the primary buffer continues on the next row. */
    private boolean[] mainWrapped;
    private boolean[] altWrapped;
    /** Whether each row of the buffer being drawn into continues below. */
    private boolean[] wrapped;
    /** Foreground per cell as 0xRRGGBB, or 0 for the view's default. */
    private int[][] mainFg;
    private int[][] altFg;
    /** Background per cell as 0xRRGGBB, or 0 for the view's default. */
    private int[][] mainBg;
    private int[][] altBg;
    /** SGR attributes per cell, as {@link CellAttributes} bits. */
    private int[][] mainAttr;
    private int[][] altAttr;
    /** The buffer being drawn into: {@link #main} or {@link #alt}. */
    private char[][] cells;
    /** The foregrounds of the buffer being drawn into. */
    private int[][] colours;
    /** The backgrounds of the buffer being drawn into. */
    private int[][] backgrounds;
    /** The SGR attributes of the buffer being drawn into. */
    private int[][] attributes;
    private final Sgr sgr = new Sgr();
    /** The last character written, for REP: a run of spaces is one REP. */
    private char lastChar = ' ';
    private int cols;
    private int rows;
    private int cursorRow;
    private int cursorCol;
    private boolean wrapPending;
    private boolean alternate;
    private int savedRow;
    private int savedCol;
    private int scrollTop;
    private int scrollBottom;
    /** Rows scrolled off the top of the primary screen, oldest first. */
    private final Scrollback scrollback;

    Screen(int cols, int rows) {
        this(cols, rows, DEFAULT_HISTORY_LINES);
    }

    Screen(int cols, int rows, int historyLines) {
        this.cols = cols;
        this.rows = rows;
        this.scrollback = new Scrollback(historyLines);
        main = blank();
        alt = blank();
        mainFg = blankColours();
        altFg = blankColours();
        mainBg = blankColours();
        altBg = blankColours();
        mainAttr = blankColours();
        altAttr = blankColours();
        mainWrapped = new boolean[rows];
        altWrapped = new boolean[rows];
        cells = main;
        colours = mainFg;
        backgrounds = mainBg;
        attributes = mainAttr;
        wrapped = mainWrapped;
        scrollBottom = rows - 1;
    }

    int cols() {
        return cols;
    }

    /**
     * Changes the grid when the view does, keeping what still fits.
     *
     * <p>A program is told about this separately - `ioctl(TIOCSWINSZ)` on a pty
     * this app owns, `stty` for one Termux owns - and redraws to the new size;
     * this only makes room for that redraw.
     */
    void resize(int newCols, int newRows) {
        if (newCols < 2 || newRows < 1 || (newCols == cols && newRows == rows)) {
            return;
        }
        scrollback.resize(newCols);
        main = copy(main, cols, rows, newCols, newRows);
        alt = copy(alt, cols, rows, newCols, newRows);
        mainFg = copyColours(mainFg, cols, rows, newCols, newRows);
        altFg = copyColours(altFg, cols, rows, newCols, newRows);
        mainBg = copyColours(mainBg, cols, rows, newCols, newRows);
        altBg = copyColours(altBg, cols, rows, newCols, newRows);
        mainAttr = copyColours(mainAttr, cols, rows, newCols, newRows);
        altAttr = copyColours(altAttr, cols, rows, newCols, newRows);
        mainWrapped = copyFlags(mainWrapped, rows, newRows);
        altWrapped = copyFlags(altWrapped, rows, newRows);
        cells = alternate ? alt : main;
        colours = alternate ? altFg : mainFg;
        backgrounds = alternate ? altBg : mainBg;
        attributes = alternate ? altAttr : mainAttr;
        wrapped = alternate ? altWrapped : mainWrapped;
        cols = newCols;
        rows = newRows;
        scrollTop = 0;
        scrollBottom = rows - 1;
        cursorRow = Math.min(cursorRow, rows - 1);
        cursorCol = Math.min(cursorCol, cols - 1);
        wrapPending = false;
    }

    private static char[][] copy(char[][] from, int fromCols, int fromRows, int toCols, int toRows) {
        char[][] to = new char[toRows][toCols];
        for (char[] row : to) {
            Arrays.fill(row, ' ');
        }
        for (int row = 0; row < Math.min(fromRows, toRows); row++) {
            System.arraycopy(from[row], 0, to[row], 0, Math.min(fromCols, toCols));
        }
        return to;
    }

    private static int[][] copyColours(int[][] from, int fromCols, int fromRows,
            int toCols, int toRows) {
        int[][] to = new int[toRows][toCols];
        for (int row = 0; row < Math.min(fromRows, toRows); row++) {
            System.arraycopy(from[row], 0, to[row], 0, Math.min(fromCols, toCols));
        }
        return to;
    }

    private static boolean[] copyFlags(boolean[] from, int fromRows, int toRows) {
        boolean[] to = new boolean[toRows];
        System.arraycopy(from, 0, to, 0, Math.min(fromRows, toRows));
        return to;
    }

    int rows() {
        return rows;
    }

    int cursorRow() {
        return Math.max(0, Math.min(rows - 1, cursorRow));
    }

    int cursorCol() {
        return Math.max(0, Math.min(cols - 1, cursorCol));
    }

    /** One row, without the blank cells on its right. */
    String line(int row) {
        if (row < 0 || row >= rows) {
            return "";
        }
        char[] chars = cells[row];
        int end = cols;
        while (end > 0 && (chars[end - 1] == ' ' || chars[end - 1] == 0)) {
            end--;
        }
        StringBuilder text = new StringBuilder(end);
        for (int col = 0; col < end; col++) {
            // A continuation cell is half of a wide glyph, not a character.
            if (chars[col] != 0) {
                text.append(chars[col]);
            }
        }
        return text.toString();
    }

    /** One cell's character: 0 for the continuation of a wide glyph. */
    char cell(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return ' ';
        }
        return cells[row][col];
    }

    /** The background of one cell: 0xRRGGBB, or 0 for the view's default. */
    int background(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return 0;
        }
        return backgrounds[row][col];
    }

    /** The foreground of one cell: 0xRRGGBB, or 0 for the view's default. */
    int foreground(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return 0;
        }
        return colours[row][col];
    }

    /** One history cell's character: 0 for the continuation of a wide glyph. */
    char historyCell(int row, int col) {
        return scrollback.cell(row, col);
    }

    /** The SGR attributes one cell was drawn with, as {@link CellAttributes} bits. */
    int attribute(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return 0;
        }
        return attributes[row][col];
    }

    /** The SGR attributes one history cell was drawn with. */
    int historyAttribute(int row, int col) {
        return scrollback.attribute(row, col);
    }

    /** The whole screen, blank rows dropped from the end. */
    String text() {
        StringBuilder all = new StringBuilder();
        int last = rows - 1;
        while (last >= 0 && line(last).isEmpty()) {
            last--;
        }
        for (int row = 0; row <= last; row++) {
            all.append(line(row));
            // A wrapped row is one logical line cut by the width, so it gets no
            // newline the program never wrote.
            if (!wrapped(row)) {
                all.append('\n');
            }
        }
        return all.toString();
    }

    /** The whole screen with the primary screen's history above it. */
    String textWithHistory() {
        // The alternate screen replaced the program only: its snapshot is the
        // TUI, not the shell history the program covered.
        if (alternate) {
            return text();
        }
        StringBuilder all = new StringBuilder();
        for (int row = 0; row < scrollback.size(); row++) {
            all.append(scrollback.line(row));
            if (!scrollback.wrapped(row)) {
                all.append('\n');
            }
        }
        all.append(text());
        return all.toString();
    }

    /** How many rows have scrolled off the top of the primary screen. */
    int historyRows() {
        return scrollback.size();
    }

    /** One history row, oldest first, without its trailing blanks. */
    String historyLine(int row) {
        return scrollback.line(row);
    }

    /** The foreground of one history cell: 0xRRGGBB, or 0 for the default. */
    int historyForeground(int row, int col) {
        return scrollback.foreground(row, col);
    }

    /** The background of one history cell: 0xRRGGBB, or 0 for the default. */
    int historyBackground(int row, int col) {
        return scrollback.background(row, col);
    }

    /** True when the row continues on the next one because it filled the width. */
    boolean wrapped(int row) {
        return row >= 0 && row < rows && wrapped[row];
    }

    /** True when a history row continued on the next one. */
    boolean historyWrapped(int row) {
        return scrollback.wrapped(row);
    }

    /** True while the program is on the alternate screen, i.e. a full-screen app. */
    boolean isAlternate() {
        return alternate;
    }

    /** True while the program has left the caret on, as DEC mode 25 says. */
    boolean cursorVisible() {
        return modes.cursorVisible();
    }

    /** True while the program asked for a blinking caret (mode 12 or DECSCUSR). */
    boolean cursorBlink() {
        return modes.cursorBlink();
    }

    /** True while DECCKM is on and cursor keys should use the SS3 forms. */
    boolean applicationCursorKeys() {
        return modes.applicationCursors();
    }

    /** True between ?2004h and ?2004l, when a paste should be bracketed. */
    boolean bracketedPaste() {
        return modes.bracketedPaste();
    }

    /** The mouse-reporting mode in force (1000, 1002, 1003), or 0. */
    int mouseMode() {
        return modes.mouseMode();
    }

    /** The mouse coordinate encoding in force (1006 or 1015), or 0. */
    int mouseEncoding() {
        return modes.mouseEncoding();
    }

    /** True while the program asked for focus in/out reports (1004/2031). */
    boolean focusReporting() {
        return modes.focusReporting();
    }

    // --- input -----------------------------------------------------------------

    void feed(String chunk) {
        parser.feed(chunk);
    }

    @Override
    public void text(char c) {
        put(c);
    }

    @Override
    public void carriageReturn() {
        cursorCol = 0;
        wrapPending = false;
    }

    @Override
    public void lineFeed() {
        newline();
    }

    @Override
    public void backspace() {
        if (cursorCol > 0) {
            cursorCol--;
        }
        wrapPending = false;
    }

    @Override
    public void tab() {
        cursorCol = Math.min(cols - 1, (cursorCol / 8 + 1) * 8);
        wrapPending = false;
    }

    @Override
    public void controlSequence(String raw, char last) {
        csi(raw, last);
    }

    /** Sets the pty's write path, or null to answer nothing. */
    void setReplySink(ReplySink sink) {
        replySink = sink;
    }

    private void reply(String bytes) {
        if (replySink != null) {
            replySink.send(bytes);
        }
    }

    @Override
    public void operatingSystemCommand(String payload) {
        if (payload.equals("10;?")) {
            reply(Replies.FOREGROUND_COLOUR);
        } else if (payload.equals("11;?")) {
            reply(Replies.BACKGROUND_COLOUR);
        }
    }

    private void csi(String raw, char last) {
        boolean privateMode = raw.startsWith("?");
        String body = privateMode ? raw.substring(1) : raw;
        String[] parts = body.isEmpty() ? new String[0] : body.split(";", -1);
        if (privateMode) {
            if (last == 'h' || last == 'l') {
                applyModes(parts, last == 'h');
            } else if (last == 'u' && body.isEmpty()) {
                // The kitty keyboard query: no enhanced flags are supported.
                reply(Replies.KEYBOARD_FLAGS);
            }
            return;
        }
        if (reportIdentity(body, last)) {
            return;
        }
        commands.dispatch(parts, last);
        wrapPending = false;
    }

    /** DA1, DA2 and XTVERSION: the queries whose answer never changes. */
    private boolean reportIdentity(String body, char last) {
        if (last == 'c') {
            if (body.isEmpty()) {
                reply(Replies.PRIMARY_DEVICE_ATTRIBUTES);
            } else if (body.equals(">")) {
                reply(Replies.SECONDARY_DEVICE_ATTRIBUTES);
            }
            return true;
        }
        if (last == 'q' && body.startsWith(">")) {
            reply(Replies.VERSION);
            return true;
        }
        return false;
    }

    /**
     * The private modes this app understands. The caret, DECCKM and the
     * synchronized update are state that outlives one sequence, so they live in
     * {@link Modes}; the alternate screen swaps buffers, so the screen keeps it.
     */
    private void applyModes(String[] parts, boolean set) {
        for (String part : parts) {
            int mode = number(part, 0);
            if (modes.apply(mode, set)) {
                continue;
            }
            if (mode == 47 || mode == 1047 || mode == 1049) {
                setAlternate(set);
            }
        }
    }



    /** DSR 5 is "are you OK"; DSR 6 is the cursor position report. */
    void deviceStatus(String[] parts) {
        int request = number(parts, 0, 0);
        if (request == 5) {
            reply(Replies.STATUS_OK);
        } else if (request == 6) {
            reply(Replies.cursorPosition(cursorRow(), cursorCol()));
        }
    }

    /**
     * DECSCUSR ({@code CSI Ps SP q}): 1/3/5 blink, 2/4/6 are steady, and 0 goes
     * back to the view's steady default.
     */
    private void cursorStyle(String[] parts) {
        int style = 0;
        if (parts.length > 0) {
            try {
                style = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException notANumber) {
                return;
            }
        }
        modes.setCursorStyle(style);
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

    private static int number(String part, int fallback) {
        if (part.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    // --- drawing ---------------------------------------------------------------

    private char[][] blank() {
        char[][] fresh = new char[rows][cols];
        for (char[] row : fresh) {
            Arrays.fill(row, ' ');
        }
        return fresh;
    }

    private int[][] blankColours() {
        return new int[rows][cols];
    }

    void moveTo(int row, int col) {
        cursorRow = Math.max(0, Math.min(rows - 1, row - 1));
        cursorCol = Math.max(0, Math.min(cols - 1, col - 1));
        wrapPending = false;
    }

    void moveUp(int lines) {
        cursorRow -= Math.max(1, lines);
        clamp();
    }

    void moveDown(int lines) {
        cursorRow += Math.max(1, lines);
        clamp();
    }

    void moveRight(int columns) {
        cursorCol += Math.max(1, columns);
        clamp();
    }

    void moveLeft(int columns) {
        cursorCol -= Math.max(1, columns);
        clamp();
    }

    void nextLine(int lines) {
        cursorRow += Math.max(1, lines);
        cursorCol = 0;
        clamp();
    }

    void previousLine(int lines) {
        cursorRow -= Math.max(1, lines);
        cursorCol = 0;
        clamp();
    }

    void setColumn(int column) {
        cursorCol = Math.max(1, column) - 1;
        clamp();
    }

    void setRow(int row) {
        cursorRow = Math.max(1, row) - 1;
        clamp();
    }

    void setScrollRegion(int top, int bottom) {
        scrollTop = Math.max(0, top - 1);
        scrollBottom = Math.min(rows - 1, bottom - 1);
        moveTo(1, 1);
    }

    void saveCursor() {
        savedRow = cursorRow;
        savedCol = cursorCol;
    }

    void restoreCursor() {
        cursorRow = savedRow;
        cursorCol = savedCol;
        clamp();
    }

    void applySgr(String[] parts) {
        sgr.apply(parts);
    }

    void applyCursorStyle(String[] parts) {
        cursorStyle(parts);
    }

    private void clamp() {
        cursorRow = Math.max(0, Math.min(rows - 1, cursorRow));
        cursorCol = Math.max(0, Math.min(cols - 1, cursorCol));
        wrapPending = false;
    }

    private void put(char c) {
        if (wrapPending) {
            // The row is full and the next glyph continues it: remember that so
            // copy joins the two halves instead of inventing a line break.
            wrapped[cursorRow] = true;
            cursorCol = 0;
            newline();
            wrapPending = false;
        }
        int width = Width.of(c);
        if (width == 2 && cursorCol == cols - 1) {
            // A wide glyph cannot straddle the edge: it moves to the next row
            // whole, and the row it leaves is a wrapped one.
            wrapped[cursorRow] = true;
            cursorCol = 0;
            newline();
        }
        cells[cursorRow][cursorCol] = c;
        // Bold is applied here, not when the code is read: SGR 22 turns it off
        // for what follows and leaves what is already on screen bright.
        colours[cursorRow][cursorCol] = sgr.drawnForeground();
        backgrounds[cursorRow][cursorCol] = sgr.background();
        attributes[cursorRow][cursorCol] = sgr.attributes();
        if (width == 2) {
            // The second cell is a continuation marker, not text; it keeps the
            // same look so a background fills both cells.
            cells[cursorRow][cursorCol + 1] = 0;
            colours[cursorRow][cursorCol + 1] = sgr.drawnForeground();
            backgrounds[cursorRow][cursorCol + 1] = sgr.background();
            attributes[cursorRow][cursorCol + 1] = sgr.attributes();
        }
        lastChar = c;
        if (cursorCol + width >= cols) {
            wrapPending = true;
        } else {
            cursorCol += width;
        }
    }

    /**
     * REP ({@code CSI n b}): the preceding character, n times.
     *
     * <p>Not an optimisation to skip: a terminal is told to fill a cell with a
     * background by writing a space in that background, and xterm-256color
     * advertises REP, so ncurses writes a box's fill as one space and a REP.
     */
    void repeat(int count) {
        for (int i = 0; i < count; i++) {
            put(lastChar);
        }
    }

    private void newline() {
        wrapPending = false;
        if (cursorRow == scrollBottom) {
            scrollUp(1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    void scrollUp(int count) {
        for (int i = 0; i < count; i++) {
            // Only a full-screen scroll of the primary buffer is scrollback: a
            // program scrolling a region owns those rows, and the alternate
            // screen is a full-screen program's own grid, not a log.
            if (!alternate && scrollTop == 0 && scrollBottom == rows - 1) {
                rememberTopRow();
            }
            for (int row = scrollTop; row < scrollBottom; row++) {
                System.arraycopy(cells[row + 1], 0, cells[row], 0, cols);
                System.arraycopy(colours[row + 1], 0, colours[row], 0, cols);
                System.arraycopy(backgrounds[row + 1], 0, backgrounds[row], 0, cols);
                System.arraycopy(attributes[row + 1], 0, attributes[row], 0, cols);
                wrapped[row] = wrapped[row + 1];
            }
            Arrays.fill(cells[scrollBottom], ' ');
            Arrays.fill(colours[scrollBottom], 0);
            Arrays.fill(backgrounds[scrollBottom], sgr.background());
            Arrays.fill(attributes[scrollBottom], 0);
            wrapped[scrollBottom] = false;
        }
    }

    /** Copies the top row into history before a full-screen scroll consumes it. */
    private void rememberTopRow() {
        scrollback.add(new HistoryRow(cells[scrollTop].clone(), colours[scrollTop].clone(),
                backgrounds[scrollTop].clone(), attributes[scrollTop].clone(), wrapped[scrollTop]));
    }

    void scrollDown(int count) {
        for (int i = 0; i < count; i++) {
            for (int row = scrollBottom; row > scrollTop; row--) {
                System.arraycopy(cells[row - 1], 0, cells[row], 0, cols);
                System.arraycopy(colours[row - 1], 0, colours[row], 0, cols);
                System.arraycopy(backgrounds[row - 1], 0, backgrounds[row], 0, cols);
                System.arraycopy(attributes[row - 1], 0, attributes[row], 0, cols);
                wrapped[row] = wrapped[row - 1];
            }
            Arrays.fill(cells[scrollTop], ' ');
            Arrays.fill(colours[scrollTop], 0);
            Arrays.fill(backgrounds[scrollTop], sgr.background());
            Arrays.fill(attributes[scrollTop], 0);
            wrapped[scrollTop] = false;
        }
    }

    /**
     * Erased cells take the background in force (xterm's "background color
     * erase"), which is what ncurses relies on: a box's fill is written as one
     * space and an ECH to the end of the line, not as a run of spaces.
     */
    void eraseScreen(int mode) {
        if (mode == 3) {
            // ED 3 is xterm's "clear scrollback", not "clear screen": the
            // visible grid stays, which is what a program that clears its
            // scrollback without redrawing relies on.
            scrollback.clear();
            return;
        }
        if (mode == 2) {
            for (char[] row : cells) {
                Arrays.fill(row, ' ');
            }
            for (int[] row : colours) {
                Arrays.fill(row, 0);
            }
            for (int[] row : backgrounds) {
                Arrays.fill(row, sgr.background());
            }
            for (int[] row : attributes) {
                Arrays.fill(row, 0);
            }
            Arrays.fill(wrapped, false);
            return;
        }
        if (mode == 1) {
            for (int row = 0; row < cursorRow; row++) {
                Arrays.fill(cells[row], ' ');
                Arrays.fill(colours[row], 0);
                Arrays.fill(backgrounds[row], sgr.background());
                Arrays.fill(attributes[row], 0);
                wrapped[row] = false;
            }
            Arrays.fill(cells[cursorRow], 0, Math.min(cols, cursorCol + 1), ' ');
            Arrays.fill(colours[cursorRow], 0, Math.min(cols, cursorCol + 1), 0);
            Arrays.fill(backgrounds[cursorRow], 0, Math.min(cols, cursorCol + 1), sgr.background());
            Arrays.fill(attributes[cursorRow], 0, Math.min(cols, cursorCol + 1), 0);
            return;
        }
        Arrays.fill(cells[cursorRow], cursorCol, cols, ' ');
        Arrays.fill(colours[cursorRow], cursorCol, cols, 0);
        Arrays.fill(backgrounds[cursorRow], cursorCol, cols, sgr.background());
        Arrays.fill(attributes[cursorRow], cursorCol, cols, 0);
        for (int row = cursorRow + 1; row < rows; row++) {
            Arrays.fill(cells[row], ' ');
            Arrays.fill(colours[row], 0);
            Arrays.fill(backgrounds[row], sgr.background());
            Arrays.fill(attributes[row], 0);
            wrapped[row] = false;
        }
    }

    void eraseLine(int mode) {
        if (mode == 2) {
            Arrays.fill(cells[cursorRow], ' ');
            Arrays.fill(colours[cursorRow], 0);
            Arrays.fill(backgrounds[cursorRow], sgr.background());
            Arrays.fill(attributes[cursorRow], 0);
            wrapped[cursorRow] = false;
        } else if (mode == 1) {
            Arrays.fill(cells[cursorRow], 0, Math.min(cols, cursorCol + 1), ' ');
            Arrays.fill(colours[cursorRow], 0, Math.min(cols, cursorCol + 1), 0);
            Arrays.fill(backgrounds[cursorRow], 0, Math.min(cols, cursorCol + 1), sgr.background());
            Arrays.fill(attributes[cursorRow], 0, Math.min(cols, cursorCol + 1), 0);
        } else {
            Arrays.fill(cells[cursorRow], cursorCol, cols, ' ');
            Arrays.fill(colours[cursorRow], cursorCol, cols, 0);
            Arrays.fill(backgrounds[cursorRow], cursorCol, cols, sgr.background());
            Arrays.fill(attributes[cursorRow], cursorCol, cols, 0);
        }
    }

    void eraseChars(int count) {
        int end = Math.min(cols, cursorCol + count);
        Arrays.fill(cells[cursorRow], cursorCol, end, ' ');
        Arrays.fill(colours[cursorRow], cursorCol, end, 0);
        Arrays.fill(backgrounds[cursorRow], cursorCol, end, sgr.background());
        Arrays.fill(attributes[cursorRow], cursorCol, end, 0);
    }

    void deleteChars(int count) {
        char[] row = cells[cursorRow];
        int end = Math.min(cols, cursorCol + count);
        System.arraycopy(row, end, row, cursorCol, cols - end);
        System.arraycopy(colours[cursorRow], end, colours[cursorRow], cursorCol, cols - end);
        System.arraycopy(backgrounds[cursorRow], end, backgrounds[cursorRow], cursorCol, cols - end);
        System.arraycopy(attributes[cursorRow], end, attributes[cursorRow], cursorCol, cols - end);
        Arrays.fill(row, cols - Math.min(count, cols - cursorCol), cols, ' ');
        Arrays.fill(colours[cursorRow], cols - Math.min(count, cols - cursorCol), cols, 0);
        Arrays.fill(backgrounds[cursorRow], cols - Math.min(count, cols - cursorCol), cols,
                sgr.background());
        Arrays.fill(attributes[cursorRow], cols - Math.min(count, cols - cursorCol), cols,
                0);
    }

    void insertChars(int count) {
        char[] row = cells[cursorRow];
        int shift = Math.min(count, cols - cursorCol);
        System.arraycopy(row, cursorCol, row, cursorCol + shift, cols - cursorCol - shift);
        System.arraycopy(colours[cursorRow], cursorCol, colours[cursorRow],
                cursorCol + shift, cols - cursorCol - shift);
        System.arraycopy(backgrounds[cursorRow], cursorCol, backgrounds[cursorRow],
                cursorCol + shift, cols - cursorCol - shift);
        System.arraycopy(attributes[cursorRow], cursorCol, attributes[cursorRow],
                cursorCol + shift, cols - cursorCol - shift);
        Arrays.fill(row, cursorCol, cursorCol + shift, ' ');
        Arrays.fill(colours[cursorRow], cursorCol, cursorCol + shift, 0);
        Arrays.fill(backgrounds[cursorRow], cursorCol, cursorCol + shift, sgr.background());
        Arrays.fill(attributes[cursorRow], cursorCol, cursorCol + shift, 0);
    }

    void insertLines(int count) {
        for (int i = 0; i < count; i++) {
            for (int row = scrollBottom; row > cursorRow; row--) {
                System.arraycopy(cells[row - 1], 0, cells[row], 0, cols);
                System.arraycopy(colours[row - 1], 0, colours[row], 0, cols);
                System.arraycopy(backgrounds[row - 1], 0, backgrounds[row], 0, cols);
                System.arraycopy(attributes[row - 1], 0, attributes[row], 0, cols);
                wrapped[row] = wrapped[row - 1];
            }
            Arrays.fill(cells[cursorRow], ' ');
            Arrays.fill(colours[cursorRow], 0);
            Arrays.fill(backgrounds[cursorRow], sgr.background());
            Arrays.fill(attributes[cursorRow], 0);
            wrapped[cursorRow] = false;
        }
    }

    void deleteLines(int count) {
        for (int i = 0; i < count; i++) {
            for (int row = cursorRow; row < scrollBottom; row++) {
                System.arraycopy(cells[row + 1], 0, cells[row], 0, cols);
                System.arraycopy(colours[row + 1], 0, colours[row], 0, cols);
                System.arraycopy(backgrounds[row + 1], 0, backgrounds[row], 0, cols);
                System.arraycopy(attributes[row + 1], 0, attributes[row], 0, cols);
                wrapped[row] = wrapped[row + 1];
            }
            Arrays.fill(cells[scrollBottom], ' ');
            Arrays.fill(colours[scrollBottom], 0);
            Arrays.fill(backgrounds[scrollBottom], sgr.background());
            Arrays.fill(attributes[scrollBottom], 0);
            wrapped[scrollBottom] = false;
        }
    }

    private void setAlternate(boolean on) {
        if (on == alternate) {
            return;
        }
        // A program that leaves mid-update must not leave the caret deferred.
        modes.endUpdate();
        if (on) {
            savedRow = cursorRow;
            savedCol = cursorCol;
            alternate = true;
            cells = alt;
            colours = altFg;
            backgrounds = altBg;
            attributes = altAttr;
            wrapped = altWrapped;
            for (char[] row : alt) {
                Arrays.fill(row, ' ');
            }
            for (int[] row : altFg) {
                Arrays.fill(row, 0);
            }
            for (int[] row : altBg) {
                Arrays.fill(row, 0);
            }
            for (int[] row : altAttr) {
                Arrays.fill(row, 0);
            }
            Arrays.fill(altWrapped, false);
            cursorRow = 0;
            cursorCol = 0;
        } else {
            alternate = false;
            cells = main;
            colours = mainFg;
            backgrounds = mainBg;
            attributes = mainAttr;
            wrapped = mainWrapped;
            cursorRow = savedRow;
            cursorCol = savedCol;
            clamp();
        }
    }
}
