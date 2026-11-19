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
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(20);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
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

    /** The monospace box this page prints Termux output into. */
    protected TextView outputBox() {
        if (output == null) {
            output = new TextView(this);
            output.setTypeface(Typeface.MONOSPACE);
            output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            output.setTextIsSelectable(true);
            output.setPadding(dp(12), dp(12), dp(12), dp(12));
            output.setBackgroundColor(0xFFF0F0F0);
            output.setVisibility(View.GONE);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(12);
            column.addView(output, params);
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
        try {
            TermuxCommands.send(this, command, label, description);
            clearNotice();
        } catch (RuntimeException refused) {
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
        TextView box = outputBox();
        box.setText(text);
        box.setVisibility(View.VISIBLE);
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

    protected int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
