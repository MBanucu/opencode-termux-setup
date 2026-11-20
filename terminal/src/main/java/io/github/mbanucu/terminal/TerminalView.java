package io.github.mbanucu.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Scroller;

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
    private final Viewport viewport = new Viewport(screen);
    private final SpannableStringBuilder input = new SpannableStringBuilder();
    private final TerminalAccessibility accessibility =
            new TerminalAccessibility(this, screen, viewport, input);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The bundled monospace face, or the system one when the host has no asset. */
    private final Typeface typeface;
    private float cellHeight = 1;
    private float cellWidth = 1;
    private Listener listener;
    private RawInput rawInput;
    private SizeListener sizeListener;
    /** A caret blinks about twice a second, as terminals have always done. */
    private static final long BLINK_MS = 530;
    /** The caret's on/off phase while the program asked for a blinking one. */
    private boolean blinkOn = true;
    /** True while the blink timer is posted. */
    private boolean blinking;
    /** The finger's last row and the pixels not yet worth a whole row. */
    private float touchLastY;
    private float touchRemainder;
    private boolean touchDragging;
    private VelocityTracker touchVelocity;
    /** Carries a drag on after the finger lifts. */
    private final Scroller flinger = new Scroller(getContext());

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
        accessibility.setAnnounceOutput(announceOutput);
    }

    // --- screen ----------------------------------------------------------------

    /** Appends output: escapes that place text are applied, text is drawn into the grid. */
    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        int before = screen.historyRows();
        screen.feed(chunk);
        // A view scrolled back keeps its place while output grows the log; at
        // the bottom it follows, and a full-screen program returns it.
        viewport.followOutput(screen.historyRows() - before);
        viewport.clamp();
        invalidate();
        startBlinking();
        accessibility.contentChanged();
        accessibility.announceSettledRows();
    }

    /** The grid's cursor as {@code row,col}: what the sample logs, and what a test can assert. */
    public String cursor() {
        return screen.cursorRow() + "," + screen.cursorCol();
    }

    /** The screen as text, newest line last: what a service reads out of the tree. */
    public String screenText() {
        // History first: copy, the live-output snapshot and a reader should keep
        // what scrolled off the top, not only the rows currently on screen.
        StringBuilder all = new StringBuilder(screen.textWithHistory());
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
            viewport.clamp();
            accessibility.resetRows();
            if (sizeListener != null) {
                sizeListener.onGridSize(cols, rows);
            }
            accessibility.contentChanged();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int row = 0; row < screen.rows(); row++) {
            String line = viewport.line(row);
            float baseline = row * cellHeight - paint.ascent();
            // A background is a filled cell whether or not there is a glyph in
            // it: a TUI's box is spaces with a colour behind them, and `line()`
            // has already trimmed those spaces away.
            for (int col = 0; col < screen.cols(); col++) {
                int bg = viewport.background(row, col);
                if (bg != 0) {
                    paint.setColor(0xFF000000 | bg);
                    canvas.drawRect(col * cellWidth, row * cellHeight,
                            (col + 1) * cellWidth, (row + 1) * cellHeight, paint);
                }
            }
            for (int col = 0; col < line.length(); col++) {
                // SGR carries the foreground per cell; 0 means the default.
                int fg = viewport.foreground(row, col);
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
        // Scrolled back, the cursor's row is below the view: drawing it would
        // put a block on a history row.
        if (!viewport.atBottom()) {
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
        String under = viewport.line(row);
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
                int named = encodedKey(event.getKeyCode());
                if (named != 0) {
                    if (raw) {
                        rawInput.onInput(KeyEncoder.encode(named, event.isCtrlPressed(),
                                event.isAltPressed(), event.isShiftPressed(),
                                screen.applicationCursorKeys()));
                    }
                    return true;
                }
                int unicode = event.getUnicodeChar();
                if (unicode >= 0x20) {
                    if (raw) {
                        rawInput.onInput(KeyEncoder.encodeCharacter((char) unicode,
                                event.isCtrlPressed(), event.isAltPressed()));
                    } else {
                        input.append((char) unicode);
                        inputChanged();
                    }
                    return true;
                }
                return false;
        }
    }

    /** The module's key id for an Android key code, or 0 for a text key. */
    private static int encodedKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return KeyEncoder.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return KeyEncoder.DOWN;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return KeyEncoder.RIGHT;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return KeyEncoder.LEFT;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return KeyEncoder.HOME;
            case KeyEvent.KEYCODE_MOVE_END:
                return KeyEncoder.END;
            case KeyEvent.KEYCODE_PAGE_UP:
                return KeyEncoder.PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return KeyEncoder.PAGE_DOWN;
            case KeyEvent.KEYCODE_INSERT:
                return KeyEncoder.INSERT;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return KeyEncoder.DELETE;
            case KeyEvent.KEYCODE_ESCAPE:
                return KeyEncoder.ESCAPE;
            case KeyEvent.KEYCODE_TAB:
                return KeyEncoder.TAB;
            case KeyEvent.KEYCODE_F1:
                return KeyEncoder.F1;
            case KeyEvent.KEYCODE_F2:
                return KeyEncoder.F2;
            case KeyEvent.KEYCODE_F3:
                return KeyEncoder.F3;
            case KeyEvent.KEYCODE_F4:
                return KeyEncoder.F4;
            case KeyEvent.KEYCODE_F5:
                return KeyEncoder.F5;
            case KeyEvent.KEYCODE_F6:
                return KeyEncoder.F6;
            case KeyEvent.KEYCODE_F7:
                return KeyEncoder.F7;
            case KeyEvent.KEYCODE_F8:
                return KeyEncoder.F8;
            case KeyEvent.KEYCODE_F9:
                return KeyEncoder.F9;
            case KeyEvent.KEYCODE_F10:
                return KeyEncoder.F10;
            case KeyEvent.KEYCODE_F11:
                return KeyEncoder.F11;
            case KeyEvent.KEYCODE_F12:
                return KeyEncoder.F12;
            default:
                return 0;
        }
    }

    /**
     * True while a program owns the screen: it draws the text, so keys go to it
     * now rather than into a prompt this view does not draw on the alternate
     * screen.
     */
    boolean forwardsRawInput() {
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

    /**
     * Dragging scrolls the log; a tap still asks for the keyboard.
     *
     * <p>Dragging down pulls older rows into view, the direction the content
     * moves under the finger. The remainder is kept in pixels between events so
     * a slow drag accumulates to a row instead of being rounded away each time.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                flinger.forceFinished(true);
                touchLastY = event.getY();
                touchRemainder = 0;
                touchDragging = false;
                if (touchVelocity == null) {
                    touchVelocity = VelocityTracker.obtain();
                } else {
                    touchVelocity.clear();
                }
                touchVelocity.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                touchVelocity.addMovement(event);
                touchRemainder += event.getY() - touchLastY;
                touchLastY = event.getY();
                if (Math.abs(touchRemainder) >= cellHeight / 2) {
                    touchDragging = true;
                }
                int lines = (int) (touchRemainder / cellHeight);
                if (lines != 0) {
                    viewport.scrollBy(lines);
                    touchRemainder -= lines * cellHeight;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                touchVelocity.addMovement(event);
                touchVelocity.computeCurrentVelocity(1000);
                if (touchDragging) {
                    fling((int) touchVelocity.getYVelocity());
                } else {
                    accessibility.focusForTyping();
                }
                recycleVelocity();
                return true;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                return true;
            default:
                return true;
        }
    }

    private void recycleVelocity() {
        if (touchVelocity != null) {
            touchVelocity.recycle();
            touchVelocity = null;
        }
    }

    /** Carries the drag on at finger speed, bounded by the stored history. */
    private void fling(int velocityY) {
        int max = viewport.maxOffset();
        if (max == 0 || Math.abs(velocityY) < 200) {
            return;
        }
        int row = Math.max(1, Math.round(cellHeight));
        flinger.fling(0, viewport.offset() * row, 0, velocityY, 0, 0,
                0, max * row);
        postInvalidateOnAnimation();
    }

    /** A fling's frames: the offset follows the scroller until it settles. */
    @Override
    public void computeScroll() {
        if (!flinger.computeScrollOffset()) {
            return;
        }
        if (screen.isAlternate()) {
            flinger.forceFinished(true);
            return;
        }
        viewport.scrollTo(Math.round((float) flinger.getCurrY() / cellHeight));
        postInvalidateOnAnimation();
    }

    void submit() {
        String line = input.toString();
        input.clear();
        inputChanged();
        accessibility.submitted(line);
        if (listener != null) {
            listener.onLine(line);
        }
    }

    void inputChanged() {
        // Typing is for the live prompt, so it brings a scrolled-back view home.
        viewport.scrollToBottom();
        invalidate();
        accessibility.textChanged();
    }

    // --- accessibility ---------------------------------------------------------

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return accessibility.provider();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return TerminalView.class.getName();
    }

    /** The same subtree, for a framework that asks the view rather than the provider. */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(TerminalView.class.getName());
        accessibility.addRowsTo(info);
    }

    /** The row height the accessibility bounds are measured with. */
    float cellHeight() {
        return cellHeight;
    }

    /** Hands text to the running program, if one owns the screen. */
    void programInput(String text) {
        if (rawInput != null) {
            rawInput.onInput(text);
        }
    }
}
