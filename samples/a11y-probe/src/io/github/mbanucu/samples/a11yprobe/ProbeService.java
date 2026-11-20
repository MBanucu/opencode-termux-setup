package io.github.mbanucu.samples.a11yprobe;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The screen reader, for a test: an accessibility service that reads the window
 * in front the way TalkBack does and can perform the actions TalkBack performs.
 *
 * <p>Enabled with adb, which needs no root:
 *
 * <pre>
 * settings put secure enabled_accessibility_services \
 *     io.github.mbanucu.samples.a11yprobe/.ProbeService
 * settings put secure accessibility_enabled 1
 * </pre>
 *
 * <p>Its answers land in {@code files/tree.txt} and {@code files/actions.txt} in
 * this app's external files directory, so what a service can see and do is a
 * file rather than an opinion.
 */
public final class ProbeService extends AccessibilityService {

    private static final String TAG = "A11yProbe";
    /** Events kept for the log; a terminal that repaints constantly makes thousands. */
    private static final int MAX_EVENTS = 200;
    /** Nodes written per dump: a WebView's terminal alone can have thousands. */
    private static final int MAX_NODES = 3000;

    static volatile ProbeService instance;

    /** When set, actions go to this package's window instead of the active one. */
    private static volatile String targetPackage;

    private final Deque<String> events = new ArrayDeque<>();

    static File files() {
        ProbeService service = instance;
        return service == null ? null : service.getExternalFilesDir(null);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        write("status.txt", "connected\nflags=" + getServiceInfo().flags + "\n");
        Log.i(TAG, "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        String packageName = String.valueOf(event.getPackageName());
        // The probe's own screens are noise; a terminal being read is the point.
        if (packageName.startsWith("io.github.mbanucu.samples.a11yprobe")) {
            return;
        }
        events.addLast(packageName + " " + eventType(event.getEventType())
                + " " + event.getClassName() + " text=" + event.getText()
                + " desc=" + event.getContentDescription());
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    @Override
    public void onInterrupt() {
    }

    private static String eventType(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: return "TEXT_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: return "SELECTION";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: return "A11Y_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: return "A11Y_UNFOCUSED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: return "FOCUSED";
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: return "WINDOW_STATE";
            case AccessibilityEvent.TYPE_ANNOUNCEMENT: return "ANNOUNCEMENT";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED: return "SCROLLED";
            default: return "type-" + type;
        }
    }

    /** Writes every window's tree, and the events the service heard, to files. */
    static String dump(String name) {
        ProbeService service = instance;
        if (service == null) {
            return "probe service is not running";
        }
        StringBuilder out = new StringBuilder();
        out.append("package=").append(service.getPackageName()).append('\n');
        out.append("events(").append(service.events.size()).append(") newest last:\n");
        for (String event : service.events) {
            out.append("  ").append(event).append('\n');
        }
        List<AccessibilityWindowInfo> windows = service.getWindows();
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        out.append("windows=").append(windows == null ? 0 : windows.size()).append('\n');
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                out.append("-- window type=").append(window.getType())
                        .append(" active=").append(window.isActive())
                        .append(" focused=").append(window.isFocused()).append('\n');
                if (root != null) {
                    walk(root, 0, out, new int[] { 0, MAX_NODES });
                    root.recycle();
                }
            }
        }
        if (active != null) {
            out.append("-- active root ").append(active.getPackageName()).append('\n');
            walk(active, 0, out, new int[] { 0, MAX_NODES });
            active.recycle();
        }
        write(name + ".txt", out.toString());
        return "dumped " + name + ".txt (" + out.length() + " chars)";
    }

    private static void walk(AccessibilityNodeInfo node, int depth, StringBuilder out, int[] budget) {
        if (node == null || depth > 60 || budget[1]-- <= 0) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        out.append(indent(depth))
                .append(node.getClassName())
                .append(" editable=").append(node.isEditable())
                .append(" focusable=").append(node.isFocusable())
                .append(" focused=").append(node.isFocused())
                .append(" clickable=").append(node.isClickable())
                .append(" actions=").append(actionNames(node))
                .append(" bounds=").append(bounds.left).append(',').append(bounds.top)
                .append(',').append(bounds.right).append(',').append(bounds.bottom)
                .append(" id=").append(node.getViewIdResourceName())
                .append(" text=").append(quote(node.getText()))
                .append(" desc=").append(quote(node.getContentDescription()))
                .append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                walk(child, depth + 1, out, budget);
                child.recycle();
            }
        }
    }

    private static String indent(int depth) {
        StringBuilder pad = new StringBuilder(depth * 2 + 2);
        for (int i = 0; i < depth; i++) {
            pad.append("  ");
        }
        return pad.toString();
    }

    private static String quote(CharSequence text) {
        if (text == null) {
            return "-";
        }
        String flat = text.toString().replace("\n", "\\n");
        return flat.length() > 160 ? flat.substring(0, 160) + "…(" + flat.length() + ")" : flat;
    }

    private static String actionNames(AccessibilityNodeInfo node) {
        StringBuilder names = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (names.length() > 0) {
                names.append('|');
            }
            names.append(actionLabel(action.getId()));
        }
        return names.toString();
    }

    private static String actionLabel(int id) {
        switch (id) {
            case AccessibilityNodeInfo.ACTION_SET_TEXT: return "SET_TEXT";
            case AccessibilityNodeInfo.ACTION_FOCUS: return "FOCUS";
            case AccessibilityNodeInfo.ACTION_CLICK: return "CLICK";
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS: return "A11Y_FOCUS";
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS: return "A11Y_CLEAR";
            case AccessibilityNodeInfo.ACTION_PASTE: return "PASTE";
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: return "SCROLL_FWD";
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: return "SCROLL_BACK";
            case AccessibilityNodeInfo.ACTION_LONG_CLICK: return "LONG_CLICK";
            case AccessibilityNodeInfo.ACTION_SELECT: return "SELECT";
            default: return "action-" + id;
        }
    }

    static void setTarget(String packageName) {
        targetPackage = packageName;
    }

    /**
     * The root of the target package's window, or of the active window when no
     * target was given.
     *
     * <p>A screen reader works on the focused window, which is not always the app
     * under test: with Termux visible behind, Termux's own text field is the first
     * editable in the tree, and a naive SET_TEXT types into Termux's toolbar
     * instead of the app. That is why the probe can be pointed at a package.
     */
    private static AccessibilityNodeInfo rootFor() {
        ProbeService service = instance;
        if (service == null) {
            return null;
        }
        String wanted = targetPackage;
        if (wanted == null) {
            return service.getRootInActiveWindow();
        }
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                if (wanted.contentEquals(root.getPackageName())) {
                    return root;
                }
                root.recycle();
            }
        }
        // The window list lags a freshly started app: the service could see three
        // windows and none of them this app, while `getRootInActiveWindow()` was
        // already this app's. Using it is still safe, because the package check
        // is what keeps an action from landing in Termux's toolbar; "whatever is
        // in front" is not.
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (active != null) {
            if (wanted.contentEquals(active.getPackageName())) {
                return active;
            }
            active.recycle();
        }
        // Deliberately not *any* active window: an action meant for one app and
        // performed on another is worse than none, and it is what made a test
        // flake - the terminal's text went into Termux's toolbar and never ran.
        return null;
    }

    /** Finds a node whose text, description or id contains {@code needle}. */
    static AccessibilityNodeInfo find(CharSequence needle) {
        ProbeService service = instance;
        if (service == null) {
            return null;
        }
        AccessibilityNodeInfo root = rootFor();
        if (root == null) {
            return null;
        }
        String wanted = String.valueOf(needle);
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (contains(node.getText(), wanted) || contains(node.getContentDescription(), wanted)
                    || contains(node.getViewIdResourceName(), wanted)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        root.recycle();
        return null;
    }

    private static boolean contains(CharSequence haystack, String needle) {
        return haystack != null && haystack.toString().contains(needle);
    }

    /** The action a screen reader's "type" and a voice keyboard's dictation use. */
    static String setText(CharSequence find, String text) {
        AccessibilityNodeInfo node = find(find);
        if (node == null) {
            return setTextOnFirstEditable(text);
        }
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        String answer = "SET_TEXT on " + node.getClassName() + " package=" + node.getPackageName()
                + " editable=" + node.isEditable() + " -> " + performed;
        write("actions.txt", answer + "\n");
        node.recycle();
        return answer;
    }

    private static String setTextOnFirstEditable(String text) {
        if (instance == null) {
            return "probe service is not running";
        }
        AccessibilityNodeInfo root = rootFor();
        if (root == null) {
            // Say what the service could see: "no window" alone does not tell a
            // reader whether the app was not on screen or the list was empty.
            ProbeService service = instance;
            String active = "none";
            int windows = 0;
            if (service != null) {
                AccessibilityNodeInfo front = service.getRootInActiveWindow();
                if (front != null) {
                    active = String.valueOf(front.getPackageName());
                    front.recycle();
                }
                List<AccessibilityWindowInfo> list = service.getWindows();
                windows = list == null ? 0 : list.size();
            }
            return "no window for " + (targetPackage == null ? "the active app" : targetPackage)
                    + " (windows=" + windows + " active=" + active + ")";
        }
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (node.isEditable() && node.getActionList().contains(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments);
                String answer = "SET_TEXT on first editable " + node.getClassName()
                        + " package=" + node.getPackageName() + " -> " + performed;
                write("actions.txt", answer + "\n");
                root.recycle();
                return answer;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        root.recycle();
        return "no editable node in the active window";
    }

    /** Moves accessibility focus, which is what TalkBack's swipe does. */
    static String focus(CharSequence find) {
        AccessibilityNodeInfo node = find(find);
        if (node == null) {
            return "no node matching " + find;
        }
        boolean focused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        String answer = "A11Y_FOCUS on " + node.getClassName() + " -> " + focused;
        write("actions.txt", answer + "\n");
        node.recycle();
        return answer;
    }

    static String click(CharSequence find) {
        AccessibilityNodeInfo node = find(find);
        if (node == null) {
            return "no node matching " + find;
        }
        boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        String answer = "CLICK on " + node.getClassName() + " -> " + clicked;
        write("actions.txt", answer + "\n");
        node.recycle();
        return answer;
    }

    static void write(String name, String body) {
        File directory = files();
        if (directory == null) {
            Log.w(TAG, "no files dir for " + name);
            return;
        }
        File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            Log.w(TAG, "could not write " + file, failed);
        }
    }
}
