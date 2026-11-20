package io.github.mbanucu.terminal;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.InputMethodManager;

/**
 * Publishes the view's rows and prompt to accessibility services.
 *
 * <p>One virtual child per non-blank visible row, with an id fixed by row
 * position so focus survives a repaint, and one editable prompt node that
 * accepts {@code ACTION_SET_TEXT} and the IME's {@code InputConnection}. The
 * view keeps drawing and input handling; this class owns the tree, the events
 * and the announcement timer.
 */
final class TerminalAccessibility {

    /** AccessibilityNodeProvider.HOST_VIEW_ID is API 26; the view's own id is fine. */
    private static final int HOST = View.NO_ID;
    /** The prompt, as an editable node: one id no row ever uses. */
    private static final int INPUT_NODE = 1_000_000;
    /** Row ids are fixed by position, so a reader's focus survives a repaint. */
    private static final int FIRST_ROW_ID = 100;
    /** A TUI repaints constantly; speak only once it has been quiet this long. */
    private static final long ANNOUNCE_QUIET_MS = 400;

    private final TerminalView view;
    private final Screen screen;
    private final Viewport viewport;
    private final SpannableStringBuilder input;
    private String[] lastRows;
    private int accessibilityFocusedRow = View.NO_ID;
    /** Announces finished rows; a screen reader hears them as they settle. */
    private boolean announceOutput = true;
    private String pendingAnnouncement;

    private final Runnable announcer = () -> {
        String line = pendingAnnouncement;
        pendingAnnouncement = null;
        if (line != null) {
            announce(line);
        }
    };

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
            if (row >= 0 && row < screen.rows() && !viewport.line(row).isEmpty()) {
                return rowNode(row);
            }
            return null;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId == INPUT_NODE) {
                return promptAction(action, arguments);
            }
            int row = virtualViewId - FIRST_ROW_ID;
            if (row < 0 || row >= screen.rows()) {
                return false;
            }
            return rowAction(virtualViewId, row, action);
        }

        @Override
        public AccessibilityNodeInfo findFocus(int focus) {
            if (focus == AccessibilityNodeInfo.FOCUS_INPUT && view.hasFocus()) {
                return inputNode();
            }
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
                    && accessibilityFocusedRow != View.NO_ID) {
                return createAccessibilityNodeInfo(accessibilityFocusedRow);
            }
            return null;
        }
    };

    TerminalAccessibility(TerminalView view, Screen screen, Viewport viewport,
            SpannableStringBuilder input) {
        this.view = view;
        this.screen = screen;
        this.viewport = viewport;
        this.input = input;
        resetRows();
    }

    AccessibilityNodeProvider provider() {
        return provider;
    }

    void setAnnounceOutput(boolean announceOutput) {
        this.announceOutput = announceOutput;
    }

    /** The grid changed size, so the rows the announcer compares are new. */
    void resetRows() {
        lastRows = new String[screen.rows()];
        for (int row = 0; row < screen.rows(); row++) {
            lastRows[row] = screen.line(row);
        }
        accessibilityFocusedRow = View.NO_ID;
    }

    /** A feed changed the grid: the subtree is stale. */
    void contentChanged() {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName(view.getContext().getPackageName());
        event.setClassName(TerminalView.class.getName());
        event.setSource(view);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        send(event);
    }

    /** The prompt's text changed, so the editable node's value changed with it. */
    void textChanged() {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
        event.setPackageName(view.getContext().getPackageName());
        event.setClassName("android.widget.EditText");
        event.setSource(view, INPUT_NODE);
        event.getText().add(input.toString());
        event.setFromIndex(0);
        event.setAddedCount(input.length());
        send(event);
    }

    /** Announces a submitted line; a screen reader hears what was run. */
    void submitted(String line) {
        if (!announceOutput) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setPackageName(view.getContext().getPackageName());
        event.setClassName("android.widget.EditText");
        event.setSource(view, INPUT_NODE);
        event.getText().add(line);
        send(event);
    }

    /**
     * Announces at most one dump of text per quiet period.
     *
     * <p>A TUI repaints several times a second, and an event per changed row
     * would be a stream of speech nobody can follow; waiting for the screen to
     * settle and then saying what appeared is the closest thing to usable
     * without a full "read from here" model.
     */
    void announceSettledRows() {
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
        view.removeCallbacks(announcer);
        view.postDelayed(announcer, ANNOUNCE_QUIET_MS);
    }

    /** Adds the row children to the host node the view built. */
    void addRowsTo(AccessibilityNodeInfo info) {
        for (int row = 0; row < screen.rows(); row++) {
            if (!viewport.line(row).isEmpty()) {
                info.addChild(view, FIRST_ROW_ID + row);
            }
        }
        info.addChild(view, INPUT_NODE);
    }

    boolean focusForTyping() {
        view.requestFocus();
        InputMethodManager imm = view.getContext().getSystemService(
                InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
        return true;
    }

    // --- the two kinds of node -------------------------------------------------

    /** The prompt node: editable, and the only way a service can run a line. */
    private boolean promptAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
            String value = setTextValue(arguments);
            // A screen reader that sets text ending in a newline means "run
            // this line": there is no other way for a service to press Enter
            // on a terminal that is not an EditText.
            boolean run = value.endsWith("\n") || value.endsWith("\r");
            if (run) {
                value = value.substring(0, value.length() - 1);
            }
            if (view.forwardsRawInput()) {
                if (!value.isEmpty()) {
                    view.programInput(value);
                }
                if (run) {
                    view.programInput("\r");
                }
                return true;
            }
            input.replace(0, input.length(), value);
            view.inputChanged();
            if (run) {
                view.submit();
            }
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_CLICK
                || action == AccessibilityNodeInfo.ACTION_FOCUS) {
            return focusForTyping();
        }
        return false;
    }

    private static String setTextValue(Bundle arguments) {
        CharSequence text = arguments == null ? null
                : arguments.getCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
        return text == null ? "" : text.toString();
    }

    /** A row node: focus is what a reader moves between rows with. */
    private boolean rowAction(int virtualViewId, int row, int action) {
        if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
            if (accessibilityFocusedRow != View.NO_ID) {
                sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                        accessibilityFocusedRow, null);
            }
            accessibilityFocusedRow = virtualViewId;
            sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                    virtualViewId, viewport.line(row));
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                && accessibilityFocusedRow == virtualViewId) {
            accessibilityFocusedRow = View.NO_ID;
            sendVirtualEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                    virtualViewId, null);
            return true;
        }
        return action == AccessibilityNodeInfo.ACTION_CLICK;
    }

    // --- node construction -----------------------------------------------------

    /**
     * The host of the virtual subtree.
     *
     * <p>Built with {@code obtain()} rather than {@code obtain(this)}: the
     * latter hydrates the node from the real View hierarchy, and the first
     * attempt at this sample returned a host whose only child was the prompt -
     * the rows were added and then dropped.
     */
    private AccessibilityNodeInfo hostNode() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(view);
        node.setPackageName(view.getContext().getPackageName());
        node.setClassName(TerminalView.class.getName());
        node.setEnabled(true);
        node.setFocusable(true);
        node.setVisibleToUser(true);
        node.setBoundsInScreen(viewBounds());
        addRowsTo(node);
        return node;
    }

    private AccessibilityNodeInfo rowNode(int row) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(view, FIRST_ROW_ID + row);
        node.setParent(view);
        node.setPackageName(view.getContext().getPackageName());
        node.setClassName("android.widget.TextView");
        node.setText(viewport.line(row));
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
        node.setSource(view, INPUT_NODE);
        node.setParent(view);
        node.setPackageName(view.getContext().getPackageName());
        node.setClassName("android.widget.EditText");
        node.setText(input.toString());
        node.setHintText("terminal input");
        node.setEditable(true);
        node.setEnabled(true);
        node.setFocusable(true);
        node.setFocused(view.hasFocus());
        node.setInputType(InputType.TYPE_CLASS_TEXT);
        node.setVisibleToUser(true);
        node.setBoundsInScreen(rowBounds(screen.rows()));
        node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT);
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        node.addAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.addAction(AccessibilityNodeInfo.ACTION_PASTE);
        return node;
    }

    private Rect viewBounds() {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private Rect rowBounds(int row) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float cellHeight = view.cellHeight();
        return new Rect(location[0], location[1] + Math.round(row * cellHeight),
                location[0] + view.getWidth(),
                location[1] + Math.round((row + 1) * cellHeight));
    }

    // --- events ----------------------------------------------------------------

    private void sendVirtualEvent(int type, int virtualViewId, String text) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(view.getContext().getPackageName());
        event.setClassName(type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                ? "android.widget.TextView" : TerminalView.class.getName());
        event.setSource(view, virtualViewId);
        if (text != null) {
            event.getText().add(text);
        }
        send(event);
    }

    private void announce(String line) {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setPackageName(view.getContext().getPackageName());
        event.setClassName("android.widget.TextView");
        event.setSource(view, HOST);
        event.getText().add(line);
        send(event);
    }

    /**
     * Posts an event from the view's window; before attachment there is nobody
     * to tell.
     *
     * <p>The {@link AccessibilityManager} check is not decoration:
     * {@code requestSendAccessibilityEvent} throws {@code IllegalStateException:
     * Accessibility off} when no service is enabled, which is a phone's normal
     * state. The first version of this view crashed on its first screen of
     * output for exactly that reason.
     */
    private void send(AccessibilityEvent event) {
        ViewParent parent = view.getParent();
        AccessibilityManager manager = view.getContext().getSystemService(
                AccessibilityManager.class);
        if (parent == null || manager == null || !manager.isEnabled()) {
            return;
        }
        parent.requestSendAccessibilityEvent(view, event);
    }
}
