package io.github.mbanucu.opencodesetup;

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
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A page of this app: a column of text, a monospace box for Termux output and a
 * place for problems.
 *
 * <p>Every screen extends it, so the output of the command a page starts is
 * printed on that page - the install page shows the install, the uninstall page
 * shows the removal, the main page shows the check, and no command raises the
 * Termux window. Results arrive from Termux through {@link RunCommandReceiver}
 * while the page is resumed, and a result that
 * arrives while another page is on top is handed over on the next resume.
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
    private TextView output;
    private Runnable pending;
    private ScrollView page;
    private ScrollView outputScroll;
    private Terminal terminal;
    private volatile Live live;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean renderQueued;

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
     * The terminal this page prints Termux output into: dark, monospace, and a
     * fixed height, so a long install scrolls inside it instead of pushing the
     * page around while it fills up.
     */
    protected TextView outputBox() {
        if (output == null) {
            terminal = new Terminal();

            output = new TextView(this);
            output.setId(R.id.output);
            output.setTypeface(Typeface.MONOSPACE);
            output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            output.setTextColor(0xFFDCDCDC);
            output.setTextIsSelectable(true);
            output.setPadding(dp(10), dp(10), dp(10), dp(10));

            outputScroll = new ScrollView(this);
            outputScroll.setBackgroundColor(0xFF101418);
            outputScroll.addView(output);
            outputScroll.setVisibility(View.GONE);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(280));
            params.topMargin = dp(12);
            column.addView(outputScroll, params);
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
     * Whatever comes back is printed in this page's output box.
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
        TextView box = outputBox();
        synchronized (terminal) {
            terminal.reset();
            terminal.append(text);
            box.setText(terminal.render());
        }
        outputScroll.setVisibility(View.VISIBLE);
        outputScroll.fullScroll(View.FOCUS_DOWN);
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
     * output into it as the command produces it, which is what makes the box
     * fill while apt is still working instead of only when it exits.
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
                synchronized (terminal) {
                    terminal.append(new String(buffer, 0, read));
                }
                scheduleRender();
            }
        } catch (IOException done) {
            // The command exited or the page went away; the result follows.
        }
    }

    /** Redraws at most every 120 ms: a PTY can emit progress faster than a screen. */
    private void scheduleRender() {
        if (renderQueued) {
            return;
        }
        renderQueued = true;
        ui.postDelayed(() -> {
            renderQueued = false;
            if (output == null) {
                return;
            }
            synchronized (terminal) {
                output.setText(terminal.render());
            }
            outputScroll.fullScroll(View.FOCUS_DOWN);
        }, 120);
    }

    private void startOutput() {
        TextView box = outputBox();
        synchronized (terminal) {
            terminal.reset();
            box.setText(R.string.output_waiting);
        }
        outputScroll.setVisibility(View.VISIBLE);
        outputScroll.fullScroll(View.FOCUS_DOWN);
        // A long page can put the terminal below the fold; bring it into view so
        // the output is seen as it arrives instead of after scrolling.
        outputScroll.post(() -> page.smoothScrollTo(0, outputScroll.getTop()));
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

    protected int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
