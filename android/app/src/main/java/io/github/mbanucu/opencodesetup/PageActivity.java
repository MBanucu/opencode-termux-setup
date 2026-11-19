package io.github.mbanucu.opencodesetup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A page of this app: a column of text, a terminal for Termux output and a
 * place for problems.
 *
 * <p>Every screen extends it, so the output of the command a page starts is
 * printed on that page - the install page shows the install, the uninstall page
 * shows the removal, the main page shows the check, and no command raises the
 * Termux window. Results arrive from Termux through {@link RunCommandReceiver}
 * while the page is resumed, and a result that
 * arrives while another page is on top is handed over on the next resume.
 *
 * <p>The terminal is xterm.js, bundled in {@code assets/terminal.html}. It is
 * given the PTY's bytes unchanged, so wrapping, colours, cursor movement and
 * scrollback are the work of the emulator rather than of this file.
 *
 * <p>Nothing here is a dialog: consent, failures and the refusal notice are all
 * part of the page, so there is no overlay to dismiss before reading what
 * happened.
 */
abstract class PageActivity extends Activity implements RunCommandReceiver.Listener {

    private static final int REQUEST_RUN_COMMAND = 7;
    private static final String TAG = "OpencodeSetup";

    protected LinearLayout column;

    private TextView notice;
    private Button noticeAction;
    private ScrollView page;
    private WebView output;
    private volatile boolean outputLoaded;
    private final Deque<String> waitingScripts = new ArrayDeque<>();
    private Runnable pending;
    private volatile Live live;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(20);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        page = scroll;
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RunCommandReceiver.setListener(this);
    }

    @Override
    protected void onPause() {
        RunCommandReceiver.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopListening();
        if (output != null) {
            column.removeView(output);
            output.destroy();
            output = null;
        }
        super.onDestroy();
    }

    // --- pieces of a page ----------------------------------------------------

    protected TextView heading(int label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(view);
        return view;
    }

    protected TextView body(int label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setPadding(0, dp(6), 0, dp(16));
        column.addView(view);
        return view;
    }

    protected TextView bold(int label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, 0, 0, dp(16));
        column.addView(view);
        return view;
    }

    protected Button button(int label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(onClick);
        column.addView(button, buttonParams());
        return button;
    }

    protected LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }

    /**
     * The terminal this page prints Termux output into: the xterm.js emulator
     * loaded from the app's own assets, dark, and a fixed height so a long
     * install scrolls inside it instead of pushing the page around. The page
     * never navigates anywhere and the contents are never fetched from a
     * network, so the WebView is only a canvas for that asset.
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected WebView outputBox() {
        if (output == null) {
            WebView web = new WebView(this);
            web.setId(R.id.output);
            web.setBackgroundColor(0xFF101418);

            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setBlockNetworkLoads(true);
            settings.setAllowContentAccess(false);

            // The only bridge into the page: the emulator's screen text, so a
            // screen reader and the end-to-end test can read what it shows.
            web.addJavascriptInterface(new Screen(), "AndroidBridge");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    outputLoaded = true;
                    while (true) {
                        String next;
                        synchronized (waitingScripts) {
                            next = waitingScripts.pollFirst();
                        }
                        if (next == null) {
                            break;
                        }
                        view.evaluateJavascript(next, null);
                    }
                    view.evaluateJavascript("fitNow()", null);
                }
            });

            // A page ScrollView would otherwise steal the terminal's own scroll.
            web.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });

            web.setVisibility(View.GONE);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(280));
            params.topMargin = dp(12);
            column.addView(web, params);

            output = web;
            web.loadUrl("file:///android_asset/terminal.html");
        }
        return output;
    }

    /** Shows a problem on the page, with an optional action button. */
    protected void notice(CharSequence text, int actionLabel, View.OnClickListener action) {
        ensureNotice();
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        if (actionLabel == 0 || action == null) {
            noticeAction.setVisibility(View.GONE);
            return;
        }
        noticeAction.setText(actionLabel);
        noticeAction.setOnClickListener(action);
        noticeAction.setVisibility(View.VISIBLE);
    }

    protected void notice(int title, int text, int actionLabel, View.OnClickListener action) {
        notice(getString(title) + "\n\n" + getString(text), actionLabel, action);
    }

    protected void clearNotice() {
        ensureNotice();
        notice.setVisibility(View.GONE);
        noticeAction.setVisibility(View.GONE);
    }

    private void ensureNotice() {
        if (notice != null) {
            return;
        }
        notice = new TextView(this);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        notice.setTypeface(Typeface.DEFAULT_BOLD);
        notice.setPadding(0, dp(16), 0, dp(8));
        notice.setTextIsSelectable(true);
        notice.setVisibility(View.GONE);
        column.addView(notice);

        noticeAction = new Button(this);
        noticeAction.setText(R.string.permission_settings);
        noticeAction.setAllCaps(false);
        noticeAction.setVisibility(View.GONE);
        column.addView(noticeAction, buttonParams());
    }

    // --- running a command ---------------------------------------------------

    /**
     * Sends one command to Termux, asking for its permission first if needed.
     * Whatever comes back is printed in this page's terminal.
     */
    protected void runCommand(String command, String label, String description) {
        if (checkSelfPermission(TermuxCommands.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "asking for " + TermuxCommands.PERMISSION);
            // RUN_COMMAND is a dangerous permission owned by Termux; without it
            // startService throws and the reason looks like a settings problem.
            pending = () -> send(command, label, description);
            requestPermissions(new String[] {TermuxCommands.PERMISSION}, REQUEST_RUN_COMMAND);
            return;
        }
        send(command, label, description);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQUEST_RUN_COMMAND) {
            return;
        }
        Runnable next = pending;
        pending = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && next != null) {
            next.run();
        } else {
            notice(R.string.permission_denied_title, R.string.permission_denied_message,
                    R.string.permission_settings, v -> openSettings());
        }
    }

    private void send(String command, String label, String description) {
        Live opened = listen();
        try {
            TermuxCommands.send(this, command, label, description,
                    opened == null ? 0 : opened.port());
            clearNotice();
            if (opened != null) {
                startOutput();
            }
        } catch (RuntimeException refused) {
            stopListening();
            Log.w(TAG, "Termux refused the command", refused);
            copy(TermuxCommands.ALLOW_EXTERNAL_APPS);
            notice(getString(R.string.permission_title) + "\n\n"
                            + getString(R.string.permission_message) + "\n\n"
                            + TermuxCommands.ALLOW_EXTERNAL_APPS,
                    R.string.permission_settings, v -> openSettings());
        }
    }

    @Override
    public void onCommandResult(String text) {
        Log.i(TAG, "result: " + text.replace("\n", " | "));
        stopListening();
        WebView box = outputBox();
        script("resetTerm()");
        write(text);
        reveal(box);
    }

    // --- helpers -------------------------------------------------------------

    protected void openSettings() {
        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("opencode-setup", text));
        }
    }

    // --- live output --------------------------------------------------------

    /** The loopback port Termux writes a running command's output to. */
    private static final class Live {
        final ServerSocket socket;

        Live(ServerSocket socket) {
            this.socket = socket;
        }

        int port() {
            return socket.getLocalPort();
        }
    }

    /**
     * Opens a loopback port before the command is sent. Termux copies the PTY's
     * output into it as the command produces it, which is what makes the
     * terminal fill while apt is still working instead of only when it exits.
     */
    private Live listen() {
        stopListening();
        try {
            // 127.0.0.1 by name: getLoopbackAddress() may hand back ::1, and
            // Termux's bash connects to the IPv4 literal below.
            Live opened = new Live(
                    new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")));
            live = opened;
            Thread pump = new Thread(() -> pump(opened), "termux-output");
            pump.setDaemon(true);
            pump.start();
            return opened;
        } catch (IOException noPort) {
            // No port means no live output; the result bundle still arrives.
            Log.w(TAG, "could not open a local port for live output", noPort);
            return null;
        }
    }

    private void pump(Live opened) {
        try (Socket connection = opened.socket.accept()) {
            Reader reader = new InputStreamReader(connection.getInputStream(),
                    StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int read;
            while (live == opened && (read = reader.read(buffer)) >= 0) {
                write(new String(buffer, 0, read));
            }
        } catch (IOException done) {
            // The command exited or the page went away; the result follows.
        }
    }

    /** Hands one chunk of the PTY's output to the emulator, unchanged. */
    private void write(String chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        String encoded = Base64.encodeToString(
                chunk.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        script("writeB64('" + encoded + "')");
    }

    /**
     * Runs a script in the terminal page. Before the asset has loaded the calls
     * are queued, so output that arrives early is not lost.
     */
    private void script(String javascript) {
        if (!outputLoaded) {
            synchronized (waitingScripts) {
                waitingScripts.add(javascript);
            }
            return;
        }
        WebView box = output;
        if (box == null) {
            return;
        }
        box.post(() -> box.evaluateJavascript(javascript, null));
    }

    private void reveal(WebView box) {
        box.setVisibility(View.VISIBLE);
        box.post(() -> {
            script("fitNow()");
            // A long page can put the terminal below the fold; bring it into
            // view so the output is seen as it arrives.
            page.smoothScrollTo(0, box.getTop());
        });
    }

    private void startOutput() {
        WebView box = outputBox();
        script("resetTerm()");
        String waiting = getString(R.string.output_waiting);
        File dir = getExternalFilesDir(null);
        if (dir != null) {
            try (FileOutputStream out = new FileOutputStream(new File(dir, "live-output.txt"))) {
                out.write(waiting.getBytes(StandardCharsets.UTF_8));
            } catch (IOException unwritable) {
                Log.w(TAG, "could not reset the live output file", unwritable);
            }
        }
        script("waitingB64('" + Base64.encodeToString(
                waiting.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "')");
        reveal(box);
    }

    private void stopListening() {
        Live opened = live;
        live = null;
        if (opened == null) {
            return;
        }
        try {
            opened.socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    /**
     * Publishes the text the terminal is showing: to the accessibility tree for
     * a screen reader, and to {@code live-output.txt} for adb. The file is also
     * what the end-to-end test reads, because a terminal that repaints while a
     * command runs does not always give UI Automator a node to read.
     */
    private final class Screen {
        @JavascriptInterface
        public void onScreen(String text) {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                File file = new File(dir, "live-output.txt");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                } catch (IOException unwritable) {
                    Log.w(TAG, "could not write live output", unwritable);
                }
            }
            ui.post(() -> {
                if (output != null) {
                    output.setContentDescription(text);
                }
            });
        }
    }

    protected int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
