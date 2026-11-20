package io.github.mbanucu.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * A terminal drawn by this app, with the shape a screen reader needs.
 *
 * <p>A terminal emulator normally draws glyphs and exposes one opaque view to
 * accessibility: nothing to move between, nothing to type into, no text to read.
 * This one publishes each non-blank row of its grid as a virtual child of the
 * view ({@link AccessibilityNodeProvider}) and the prompt as an editable node
 * that accepts {@code ACTION_SET_TEXT} and an {@link InputConnection}, which is
 * what TalkBack, Voice Access, switch access and braille keyboards use.
 *
 * <p>The screen is a {@link Screen} grid, so a full-screen program's cursor
 * addressing and erases are applied rather than printed, and what a reader gets
 * is the rows as they are on screen rather than a transcript of what was written.
 */
public final class TerminalView extends View {

    /** What a submitted line is handed to: the pty, or a Termux session. */
    public interface Listener {
        void onLine(String line);
    }

    /**
     * Text for a running full-screen program, as it is typed.
     *
     * <p>On the alternate screen the program draws its own input, so a key has to
     * reach it when it is pressed: the view's prompt row is not drawn there, and a
     * line held back until Enter is a line the user never sees.
     */
    public interface RawInput {
        void onInput(String text);
    }

    /** The grid the view can show now, so the program can be told to redraw into it. */
    public interface SizeListener {
        void onGridSize(int cols, int rows);
    }

    /** The host view's virtual id: AccessibilityNodeProvider.HOST_VIEW_ID is API 26. */
    private static final int HOST = NO_ID;
    /** The prompt, as an editable node: one id no row ever uses. */
    private static final int INPUT_NODE = 1_000_000;
    /** Row ids are fixed by position, so a reader's focus survives a repaint. */
    private static final int FIRST_ROW_ID = 100;
    /** A TUI repaints constantly; speak only once it has been quiet this long. */
    private static final long ANNOUNCE_QUIET_MS = 400;
    /**
     * The narrowest grid worth giving a program.
     *
     * <p>The font adapts to the width so this many columns always fit; without a
     * floor, a large phone font produced 62 columns, and opencode wraps its footer
     * mid-word below about 70 - "tab agents c" on one row and "commands" on the
     * next, its version split across two rows. Rows as accessibility nodes looked
     * right; the picture did not.
     */
    private static final int MIN_COLUMNS = 80;
    /** What uncoloured text is drawn in. */
    private static final int TEXT_COLOR = 0xFFDCDCDC;

    private final Screen screen = new Screen(80, 24);
    private final SpannableStringBuilder input = new SpannableStringBuilder();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The bundled monospace face, or the system one when the host has no asset. */
    private final Typeface typeface;
    private String[] lastRows = new String[screen.rows()];
    private float cellHeight = 1;
    private float cellWidth = 1;
    private int accessibilityFocusedRow = NO_ID;
    private Listener listener;
    private RawInput rawInput;
    private SizeListener sizeListener;
    /** Announces finished rows; a screen reader hears them as they settle. */
    private boolean announceOutput = true;
    private String pendingAnnouncement;
    /** A caret blinks about twice a second, as terminals have always done. */
    private static final long BLINK_MS = 530;
    /** The caret's on/off phase while the program asked for a blinking one. */
    private boolean blinkOn = true;
    /** True while the blink timer is posted. */
    private boolean blinking;

    private final Runnable announcer = () -> {
        String line = pendingAnnouncement;
        pendingAnnouncement = null;
        if (line != null) {
            announce(line);
        }
    };

    /**
     * Flips the caret while the program wants it blinking.
     *
     * <p>Terminals blink the caret themselves; a program that asks for a
     * blinking block (DECSCUSR 1) sends nothing per blink. The timer stops as
     * soon as the program wants a steady caret or leaves the alternate screen,
     * and leaves the caret on for whoever asks next.
     */
    private final Runnable blinker = new Runnable() {
        @Override
        public void run() {
            if (!blinkWanted()) {
                blinking = false;
                blinkOn = true;
                return;
            }
            blinkOn = !blinkOn;
            invalidate();
            postDelayed(this, BLINK_MS);
        }
    };

    public TerminalView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        typeface = loadTypeface();
        paint.setTypeface(typeface);
        paint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 9f);
        cellWidth = Math.max(1f, paint.measureText("M"));
        paint.setColor(TEXT_COLOR);
        setBackgroundColor(Color.rgb(0x10, 0x14, 0x18));
    }

    /**
     * The font the WebView page and this view share.
     *
     * <p>The page has always rendered with the bundled DejaVu Sans Mono, while
     * this view asked for the system "monospace": block and box glyphs came from
     * a fallback whose ink was not the cell width, so bars and borders drifted by
     * a few pixels. Loading the same face gives every glyph the same advance as
     * the cell; a host that does not ship the asset keeps the system fallback.
     */
    private Typeface loadTypeface() {
        try {
            return Typeface.createFromAsset(getContext().getAssets(), "terminal-mono.ttf");
        } catch (RuntimeException missing) {
            return Typeface.MONOSPACE;
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRawInput(RawInput rawInput) {
        this.rawInput = rawInput;
    }

    public void setSizeListener(SizeListener listener) {
        this.sizeListener = listener;
    }

    public void setAnnounceOutput(boolean announceOutput) {
        this.announceOutput = announceOutput;
    }

    // --- screen ----------------------------------------------------------------

    /** Appends output: escapes that place text are applied, text is drawn into the grid. */
    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        screen.feed(chunk);
        invalidate();
        startBlinking();
        notifyContentChanged();
        announceSettledRows();
    }

    /**
     * Announces at most one dump of text per quiet period.
     *
     * <p>A TUI repaints several times a second, and an event per changed row would
     * be a stream of speech nobody can follow; waiting for the screen to settle
     * and then saying what appeared is the closest thing to usable without a full
     * "read from here" model.
     */
    private void announceSettledRows() {
        if (!announceOutput) {
            return;
        }
        String changed = null;
        for (int row = 0; row < screen.rows(); row++) {
            String now = screen.line(row);
            if (!now.equals(lastRows[row])) {
                lastRows[row] = now;
                if (!now.isEmpty()) {
                    changed = now;
                }
            }
        }
        if (changed == null) {
            return;
        }
        pendingAnnouncement = changed;
        removeCallbacks(announcer);
        postDelayed(announcer, ANNOUNCE_QUIET_MS);
    }

    /** The grid's cursor as {@code row,col}: what the sample logs, and what a test can assert. */
    public String cursor() {
        return screen.cursorRow() + "," + screen.cursorCol();
    }

    /** The screen as text, newest line last: what a service reads out of the tree. */
    public String screenText() {
        StringBuilder all = new StringBuilder(screen.text());
        if (input.length() > 0) {
            all.append("$ ").append(input).append('\n');
        }
        return all.toString();
    }

    /**
     * Turns the view's pixels into a grid, and says so when the grid changes.
     *
     * <p>This is the half of a resize that belongs to the view: the keyboard
     * opening, a rotation or a smaller window all arrive here as a new height, and
     * the program can only redraw to fit once it has been told the new size. The
     * other half is the caller's - `ioctl(TIOCSWINSZ)` for a pty this app owns,
     * `stty` for the one Termux owns.
     */
    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Fit MIN_COLUMNS across the view, shrinking the type rather than the
        // grid: a program's layout assumes its columns, and a reader wants the
        // rows it drew, not the ones that happen to be legible.
        paint.setTypeface(typeface);
        float base = getResources().getDisplayMetrics().scaledDensity * 9f;
        // Fit MIN_COLUMNS using this face's own advance, not a nominal 0.6 em:
        // DejaVu Sans Mono is 0.602 em wide, and the old constant would let an
        // 80th column hang past the view.
        paint.setTextSize(base);
        float cellAtBase = Math.max(1f, paint.measureText("M"));
        float forColumns = width / (MIN_COLUMNS * (cellAtBase / base));
        paint.setTextSize(Math.max(1f, Math.min(base, forColumns)));
        cellWidth = Math.max(1f, paint.measureText("M"));
        cellHeight = Math.max(1f, paint.getFontSpacing());
        int cols = Math.max(MIN_COLUMNS, (int) (width / cellWidth));
        // One row is kept back for the line being typed: the grid used to fill the
        // view exactly, so that line was drawn below the last visible row and a
        // reader saw only its cursor - a green block in the bottom-left corner.
        int rows = Math.max(4, (int) (height / cellHeight) - 1);
        if (cols != screen.cols() || rows != screen.rows()) {
            screen.resize(cols, rows);
            lastRows = new String[screen.rows()];
            for (int row = 0; row < screen.rows(); row++) {
                lastRows[row] = screen.line(row);
            }
            accessibilityFocusedRow = NO_ID;
            if (sizeListener != null) {
                sizeListener.onGridSize(cols, rows);
            }
            notifyContentChanged();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int row = 0; row < screen.rows(); row++) {
            String line = screen.line(row);
            float baseline = row * cellHeight - paint.ascent();
            // A background is a filled cell whether or not there is a glyph in
            // it: a TUI's box is spaces with a colour behind them, and `line()`
            // has already trimmed those spaces away.
            for (int col = 0; col < screen.cols(); col++) {
                int bg = screen.background(row, col);
                if (bg != 0) {
                    paint.setColor(0xFF000000 | bg);
                    canvas.drawRect(col * cellWidth, row * cellHeight,
                            (col + 1) * cellWidth, (row + 1) * cellHeight, paint);
                }
            }
            for (int col = 0; col < line.length(); col++) {
                // SGR carries the foreground per cell; 0 means the default.
                int fg = screen.foreground(row, col);
                paint.setColor(fg == 0 ? TEXT_COLOR : 0xFF000000 | fg);
                canvas.drawText(line, col, col + 1, col * cellWidth, baseline, paint);
            }
        }
        paint.setColor(TEXT_COLOR);
        // A full-screen program leaves the caret to the terminal: it says whether
        // to show one with DEC mode 25, and the view draws it where the program
        // put the cursor. Nothing else is added on that screen - the program owns
        // the prompt there.
        if (screen.isAlternate()) {
            if (screen.cursorVisible() && (!screen.cursorBlink() || blinkOn)) {
                drawCursor(canvas, TEXT_COLOR);
            }
            return;
        }
        // The shell's own cursor, at the position the program put it, inverted.
        drawCursor(canvas, Color.rgb(0x98, 0xC3, 0x79));
        // The line being typed lives on the row kept back above, and only exists
        // once there is something in it: an empty prompt with a cursor on it was
        // the other half of the stray-block bug.
        if (input.length() == 0) {
            return;
        }
        Paint typing = new Paint(paint);
        typing.setColor(Color.rgb(0x98, 0xC3, 0x79));
        float baseline = screen.rows() * cellHeight - typing.ascent();
        canvas.drawText("$ " + input, 0, baseline, typing);
        int cursorX = (int) typing.measureText("$ " + input);
        canvas.drawRect(cursorX, baseline + typing.ascent(), cursorX + cellWidth,
                baseline + typing.descent(), typing);
    }

    /**
     * A block at the grid's cursor, with the cell's glyph redrawn inside it.
     *
     * <p>The cell is measured from the row's baseline, not from the row's top:
     * ascent is negative and already accounted for in that baseline, so adding it
     * to {@code row * cellHeight} drew every cursor one ascent above its own line
     * - which reads as a block that climbs instead of a line that moves down.
     */
    private void drawCursor(Canvas canvas, int colour) {
        int row = screen.cursorRow();
        int col = screen.cursorCol();
        float rowBaseline = row * cellHeight - paint.ascent();
        Paint block = new Paint(paint);
        block.setColor(colour);
        canvas.drawRect(col * cellWidth, rowBaseline + paint.ascent(),
                (col + 1) * cellWidth, rowBaseline + paint.descent(), block);
        String under = screen.line(row);
        if (col < under.length()) {
            Paint glyph = new Paint(paint);
            glyph.setColor(Color.rgb(0x10, 0x14, 0x18));
            canvas.drawText(under, col, col + 1, col * cellWidth, rowBaseline, glyph);
        }
    }

    /** True while a blinking caret is what the program asked for. */
    private boolean blinkWanted() {
        return screen.isAlternate() && screen.cursorVisible() && screen.cursorBlink();
    }

    /** Starts the blink timer; a no-op while it is already running. */
    private void startBlinking() {
        if (blinking || !blinkWanted()) {
            return;
        }
        blinking = true;
        postDelayed(blinker, BLINK_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(blinker);
        blinking = false;
        super.onDetachedFromWindow();
    }

    // --- typing ----------------------------------------------------------------

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    /**
     * The IME's way in: committed text and key events arrive here, which is how a
     * soft keyboard, a braille keyboard or a voice keyboard types into a terminal
     * that is not an EditText.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        out.initialSelStart = input.length();
        out.initialSelEnd = input.length();
        return new BaseInputConnection(this, false) {
            @Override
            public Editable getEditable() {
                return input;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (forwardsRawInput()) {
                    rawInput.onInput(text.toString());
                    return true;
                }
                input.append(text);
                inputChanged();
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Composing text stays out of the pty until it is committed;
                // showing it in the prompt would make it look sent.
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (forwardsRawInput()) {
                    for (int i = 0; i < beforeLength; i++) {
                        rawInput.onInput("\u007f");
                    }
                    return true;
                }
                int from = Math.max(0, input.length() - beforeLength);
                input.delete(from, input.length());
                inputChanged();
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return true;
                }
                return handleKey(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                if (forwardsRawInput()) {
                    rawInput.onInput("\r");
                    return true;
                }
                submit();
                return true;
            }
        };
    }

    /**
     * A physical keyboard, and {@code adb shell input text}, arrive as key
     * events rather than through the {@link InputConnection}.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyDown(keyCode, event);
    }

    private boolean handleKey(KeyEvent event) {
        boolean raw = forwardsRawInput();
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (raw) {
                    rawInput.onInput("\r");
                } else {
                    submit();
                }
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (raw) {
                    rawInput.onInput("\u007f");
                } else if (input.length() > 0) {
                    input.delete(input.length() - 1, input.length());
                    inputChanged();
                }
                return true;
            default:
                int unicode = event.getUnicodeChar();
                if (unicode >= 0x20) {
                    if (raw) {
                        rawInput.onInput(String.valueOf((char) unicode));
                    } else {
                        input.append((char) unicode);
                        inputChanged();
                    }
                    return true;
                }
                return false;
        }
    }

    /**
     * True while a program owns the screen: it draws the text, so keys go to it
     * now rather than into a prompt this view does not draw on the alternate
     * screen.
     */
    private boolean forwardsRawInput() {
        return rawInput != null && screen.isAlternate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocused) {
        super.onFocusChanged(gainFocus, direction, previouslyFocused);
        if (!gainFocus) {
            return;
        }
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.restartInput(this);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            requestFocus();
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        return true;
    }

    private void submit() {
        String line = input.toString();
        input.clear();
        inputChanged();
        notifyTextChanged(line);
        if (listener != null) {
            listener.onLine(line);
        }
    }

    private void inputChanged() {
        invalidate();
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.EditText");
        event.setSource(this, INPUT_NODE);
        event.getText().add(input.toString());
        event.setFromIndex(0);
        event.setAddedCount(input.length());
        send(event);
    }

    // --- accessibility ---------------------------------------------------------

    /** The rows of the grid that have text, each with a stable id, and the prompt. */
    private final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == HOST) {
                return hostNode();
            }
            if (virtualViewId == INPUT_NODE) {
                return inputNode();
            }
            int row = virtualViewId - FIRST_ROW_ID;
            if (row >= 0 && row < screen.rows() && !screen.line(row).isEmpty()) {
                return rowNode(row);
            }
            return null;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId == INPUT_NODE) {
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    CharSequence text = arguments == null ? null
                            : arguments.getCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
                    String value = text == null ? "" : text.toString();
                    // A screen reader that sets text ending in a newline means
                    // "run this line": there is no other way for a service to
                    // press Enter on a terminal that is not an EditText.
                    boolean run = value.endsWith("\n") || value.endsWith("\r");
                    if (run) {
                        value = value.substring(0, value.length() - 1);
                    }
                    if (forwardsRawInput()) {
                        if (!value.isEmpty()) {
                            rawInput.onInput(value);
                        }
                        if (run) {
                            rawInput.onInput("\r");
                        }
                        return true;
                    }
                    input.replace(0, input.length(), value);
                    inputChanged();
                    if (run) {
                        submit();
                    }
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_CLICK
                        || action == AccessibilityNodeInfo.ACTION_FOCUS) {
                    return focusForTyping();
                }
                return false;
            }
            int row = virtualViewId - FIRST_ROW_ID;
            if (row < 0 || row >= screen.rows()) {
                return false;
            }
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocusedRow != NO_ID) {
                    sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                            accessibilityFocusedRow, null);
                }
                accessibilityFocusedRow = virtualViewId;
                sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                        virtualViewId, screen.line(row));
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    && accessibilityFocusedRow == virtualViewId) {
                accessibilityFocusedRow = NO_ID;
                sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                        virtualViewId, null);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                return true;
            }
            return false;
        }

        @Override
        public AccessibilityNodeInfo findFocus(int focus) {
            if (focus == AccessibilityNodeInfo.FOCUS_INPUT && hasFocus()) {
                return inputNode();
            }
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
                    && accessibilityFocusedRow != NO_ID) {
                return createAccessibilityNodeInfo(accessibilityFocusedRow);
            }
            return null;
        }
    };

    private boolean focusForTyping() {
        requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
        return true;
    }

    /**
     * The host of the virtual subtree.
     *
     * <p>Built with {@code obtain()} rather than {@code obtain(this)}: the latter
     * hydrates the node from the real View hierarchy, and the first attempt at
     * this sample returned a host whose only child was the prompt - the rows were
     * added and then dropped.
     */
    private AccessibilityNodeInfo hostNode() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(this);
        node.setPackageName(getContext().getPackageName());
        node.setClassName(TerminalView.class.getName());
        node.setEnabled(true);
        node.setFocusable(true);
        node.setVisibleToUser(true);
        node.setBoundsInScreen(viewBounds());
        for (int row = 0; row < screen.rows(); row++) {
            if (!screen.line(row).isEmpty()) {
                node.addChild(this, FIRST_ROW_ID + row);
            }
        }
        node.addChild(this, INPUT_NODE);
        return node;
    }

    /** The same subtree, for a framework that asks the view rather than the provider. */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(TerminalView.class.getName());
        for (int row = 0; row < screen.rows(); row++) {
            if (!screen.line(row).isEmpty()) {
                info.addChild(this, FIRST_ROW_ID + row);
            }
        }
        info.addChild(this, INPUT_NODE);
    }

    private Rect viewBounds() {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + getWidth(),
                location[1] + getHeight());
    }

    private AccessibilityNodeInfo rowNode(int row) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(this, FIRST_ROW_ID + row);
        node.setParent(this);
        node.setPackageName(getContext().getPackageName());
        node.setClassName("android.widget.TextView");
        node.setText(screen.line(row));
        node.setEnabled(true);
        node.setFocusable(true);
        node.setVisibleToUser(true);
        node.setBoundsInScreen(rowBounds(row));
        node.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        node.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        return node;
    }

    private AccessibilityNodeInfo inputNode() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(this, INPUT_NODE);
        node.setParent(this);
        node.setPackageName(getContext().getPackageName());
        node.setClassName("android.widget.EditText");
        node.setText(input.toString());
        node.setHintText("terminal input");
        node.setEditable(true);
        node.setEnabled(true);
        node.setFocusable(true);
        node.setFocused(hasFocus());
        node.setInputType(InputType.TYPE_CLASS_TEXT);
        node.setVisibleToUser(true);
        node.setBoundsInScreen(rowBounds(screen.rows()));
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT);
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        node.addAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE);
        return node;
    }

    private Rect rowBounds(int row) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(location[0], location[1] + Math.round(row * cellHeight),
                location[0] + getWidth(), location[1] + Math.round((row + 1) * cellHeight));
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return provider;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return TerminalView.class.getName();
    }

    private void sendVirtualEvent(int type, int virtualViewId, String text) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(getContext().getPackageName());
        event.setClassName(type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                ? "android.widget.TextView" : TerminalView.class.getName());
        event.setSource(this, virtualViewId);
        if (text != null) {
            event.getText().add(text);
        }
        send(event);
    }

    private void notifyContentChanged() {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName(getContext().getPackageName());
        event.setClassName(TerminalView.class.getName());
        event.setSource(this);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        send(event);
    }

    private void notifyTextChanged(String line) {
        if (!announceOutput) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.EditText");
        event.setSource(this, INPUT_NODE);
        event.getText().add(line);
        send(event);
    }

    private void announce(String line) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.TextView");
        event.setSource(this, HOST);
        event.getText().add(line);
        send(event);
    }

    /**
     * Posts an event from this view's window; before attachment there is nobody
     * to tell.
     *
     * <p>The {@link AccessibilityManager} check is not decoration:
     * {@code requestSendAccessibilityEvent} throws {@code IllegalStateException:
     * Accessibility off} when no service is enabled, which is a phone's normal
     * state. The first version of this view crashed on its first screen of
     * output for exactly that reason.
     */
    private void send(AccessibilityEvent event) {
        ViewParent parent = getParent();
        AccessibilityManager manager = getContext().getSystemService(AccessibilityManager.class);
        if (parent == null || manager == null || !manager.isEnabled()) {
            return;
        }
        parent.requestSendAccessibilityEvent(this, event);
    }
}
