package io.github.mbanucu.samples.nativeterminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A terminal drawn by this app, with the shape a screen reader needs.
 *
 * <p>A terminal emulator normally draws glyphs into a bitmap and exposes one
 * opaque view to accessibility: nothing to move between, nothing to type into,
 * no text to read. This one publishes each visible line as a virtual child of
 * the view ({@link AccessibilityNodeProvider}) and the prompt as an editable
 * node that accepts {@code ACTION_SET_TEXT} and an {@link InputConnection}, which
 * is what TalkBack, Voice Access, switch access and braille keyboards use.
 *
 * <p>The screen itself is a list of lines; nothing here depends on a pty, so the
 * same view can be driven by the native pty or by a test.
 */
public final class TerminalView extends View {

    /** What a submitted line is handed to: in this sample, the pty. */
    interface Listener {
        void onLine(String line);
    }

    /** The host view's virtual id: AccessibilityNodeProvider.HOST_VIEW_ID is API 26. */
    private static final int HOST = NO_ID;
    /** The prompt, as an editable node: one id no line ever uses. */
    private static final int INPUT_NODE = 1_000_000;
    /** The first id a line gets; ids are never reused while the line lives. */
    private static final int FIRST_ROW_ID = 1;

    private static final class Row {
        final int id;
        final StringBuilder text = new StringBuilder();
        boolean complete;

        Row(int id) {
            this.id = id;
        }
    }

    private final ArrayDeque<Row> rows = new ArrayDeque<>();
    private final SpannableStringBuilder input = new SpannableStringBuilder();
    private final List<Row> visible = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int nextRowId = FIRST_ROW_ID;
    private int cellHeight = 1;
    private int focusedRow = NO_ID;
    private int accessibilityFocusedRow = NO_ID;
    private Listener listener;
    /** Announces finished output lines; a screen reader hears them as they arrive. */
    private boolean announceOutput = true;

    public TerminalView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 11f);
        paint.setColor(Color.rgb(0xDC, 0xDC, 0xDC));
        setBackgroundColor(Color.rgb(0x10, 0x14, 0x18));
        Row first = new Row(nextRowId++);
        first.complete = true;
        rows.add(first);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setAnnounceOutput(boolean announceOutput) {
        this.announceOutput = announceOutput;
    }

    // --- screen ----------------------------------------------------------------

    /** Appends output from the pty: CR/LF, backspace and ANSI colour are handled. */
    void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String text = chunk.replaceAll("\u001b\\][^\u0007]*\u0007", "")
                .replaceAll("\u001b\\[[0-9;?]*[ -/]*[@-~]", "")
                .replaceAll("\u001b[()][0-9A-Za-z]", "");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                completeLine();
                continue;
            }
            if (c == '\b') {
                Row last = rows.peekLast();
                if (last != null && last.text.length() > 0) {
                    last.text.deleteCharAt(last.text.length() - 1);
                }
                continue;
            }
            if (c < 0x20 && c != '\t') {
                continue;
            }
            Row last = rows.peekLast();
            if (last == null || last.complete) {
                last = new Row(nextRowId++);
                rows.add(last);
            }
            last.text.append(c);
        }
        while (rows.size() > 1000) {
            rows.removeFirst();
        }
        rebuild();
    }

    private void completeLine() {
        Row last = rows.peekLast();
        if (last == null || last.complete) {
            last = new Row(nextRowId++);
            rows.add(last);
        }
        last.complete = true;
        if (announceOutput && last.text.length() > 0) {
            announce(last.text.toString());
        }
        Row fresh = new Row(nextRowId++);
        rows.add(fresh);
    }

    /** The screen as text, newest line last: what a service reads out of the tree. */
    String screenText() {
        StringBuilder all = new StringBuilder();
        for (Row row : rows) {
            all.append(row.text).append('\n');
        }
        if (input.length() > 0) {
            all.append("$ ").append(input).append('\n');
        }
        return all.toString();
    }

    private void rebuild() {
        visible.clear();
        int rowsThatFit = Math.max(4, getHeight() / Math.max(1, cellHeight) - 1);
        List<Row> all = new ArrayList<>(rows);
        int from = Math.max(0, all.size() - rowsThatFit);
        for (int i = from; i < all.size(); i++) {
            visible.add(all.get(i));
        }
        invalidate();
        notifyContentChanged();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cellHeight = Math.max(1, Math.round(paint.getFontSpacing()));
        rebuild();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float y = -paint.ascent();
        for (Row row : visible) {
            canvas.drawText(row.text.toString(), 0, y, paint);
            y += cellHeight;
        }
        Paint prompt = new Paint(paint);
        prompt.setColor(Color.rgb(0x98, 0xC3, 0x79));
        canvas.drawText("$ " + input, 0, y, prompt);
        int cursorX = (int) prompt.measureText("$ ") + (int) prompt.measureText(input.toString());
        canvas.drawRect(cursorX, y + prompt.ascent(), cursorX + (int) prompt.measureText("M"),
                y + prompt.descent(), prompt);
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
                // The same path as a physical key, so a screen reader, the IME
                // and a hardware keyboard all end up writing to one buffer.
                return handleKey(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
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
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                submit();
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (input.length() > 0) {
                    input.delete(input.length() - 1, input.length());
                    inputChanged();
                }
                return true;
            default:
                int unicode = event.getUnicodeChar();
                if (unicode >= 0x20) {
                    input.append((char) unicode);
                    inputChanged();
                    return true;
                }
                return false;
        }
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
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
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

    /**
     * What an accessibility service gets instead of one pixmap: a list of rows,
     * each with its own id and text, and a prompt that is editable.
     */
    private final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == HOST) {
                return hostNode();
            }
            if (virtualViewId == INPUT_NODE) {
                return inputNode();
            }
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).id == virtualViewId) {
                    return rowNode(i, visible.get(i));
                }
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
                    input.replace(0, input.length(), text == null ? "" : text);
                    inputChanged();
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_CLICK
                        || action == AccessibilityNodeInfo.ACTION_FOCUS) {
                    return focusForTyping();
                }
                return false;
            }
            for (int i = 0; i < visible.size(); i++) {
                Row row = visible.get(i);
                if (row.id != virtualViewId) {
                    continue;
                }
                if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                    if (accessibilityFocusedRow != NO_ID) {
                        sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                                accessibilityFocusedRow, null);
                    }
                    accessibilityFocusedRow = row.id;
                    sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, row.id,
                            row.text.toString());
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                        && accessibilityFocusedRow == row.id) {
                    accessibilityFocusedRow = NO_ID;
                    sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                            row.id, null);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    focusedRow = row.id;
                    return true;
                }
                return false;
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
        for (Row row : visible) {
            node.addChild(this, row.id);
        }
        node.addChild(this, INPUT_NODE);
        return node;
    }

    /** The same subtree, for a framework that asks the view rather than the provider. */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(TerminalView.class.getName());
        for (Row row : visible) {
            info.addChild(this, row.id);
        }
        info.addChild(this, INPUT_NODE);
    }

    private Rect viewBounds() {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + getWidth(),
                location[1] + getHeight());
    }

    private AccessibilityNodeInfo rowNode(int index, Row row) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(this, row.id);
        node.setParent(this);
        node.setPackageName(getContext().getPackageName());
        node.setClassName("android.widget.TextView");
        node.setText(row.text.toString());
        node.setEnabled(true);
        node.setFocusable(true);
        node.setVisibleToUser(true);
        node.setBoundsInScreen(rowBounds(index));
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
        node.setBoundsInScreen(rowBounds(visible.size()));
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT);
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        node.addAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE);
        return node;
    }

    private Rect rowBounds(int index) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(location[0], location[1] + index * cellHeight,
                location[0] + getWidth(), location[1] + (index + 1) * cellHeight);
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

    static boolean supportsSetText() {
        return Build.VERSION.SDK_INT >= 21;
    }
}
