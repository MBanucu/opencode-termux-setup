package io.github.mbanucu.opencodesetup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
 * Setup companion for opencode on Termux.
 *
 * <p>It owns no code that installs anything: it explains the download, asks for
 * consent, and sends one command to Termux through RUN_COMMAND.  Every state it
 * shows comes from running {@code opencode --version} in Termux.
 */
@SuppressLint("SdCardPath") // the Termux paths are fixed by Termux, not by us
public final class MainActivity extends Activity implements RunCommandReceiver.Listener {

    private static final int REQUEST_RUN_COMMAND = 1;
    private static final String TAG = "OpencodeSetup";

    private TextView status;
    private TextView output;
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        RunCommandReceiver.setListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        RunCommandReceiver.setListener(null);
        super.onPause();
    }

    // --- ui ------------------------------------------------------------------

    private View buildUi() {
        int pad = dp(20);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        TextView tagline = new TextView(this);
        tagline.setText(R.string.tagline);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tagline.setPadding(0, dp(6), 0, dp(16));
        column.addView(tagline);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(0, 0, 0, dp(16));
        column.addView(status);

        column.addView(button(R.string.action_install, v -> confirmSetup()));
        // Uninstalling is a decision with two very different outcomes, so it gets
        // a page instead of a dialog.
        column.addView(button(R.string.action_uninstall,
                v -> startActivity(new Intent(this, UninstallActivity.class))));
        column.addView(button(R.string.action_check, v -> runCheck()));
        column.addView(button(R.string.action_open, v -> openTermux()));

        TextView hint = new TextView(this);
        hint.setText(R.string.hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(16), 0, dp(8));
        hint.setTextIsSelectable(true);
        column.addView(hint);

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        output.setTextIsSelectable(true);
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        output.setBackgroundColor(0xFFF0F0F0);
        output.setVisibility(View.GONE);
        column.addView(output);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Button button(int label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void refresh() {
        status.setText(isTermuxInstalled()
                ? R.string.status_termux_installed
                : R.string.status_termux_missing);
    }

    // --- actions -------------------------------------------------------------

    private void confirmSetup() {
        if (!requireTermux()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.consent_title)
                .setMessage(R.string.consent_message)
                .setNegativeButton(R.string.consent_cancel, null)
                .setPositiveButton(R.string.consent_accept,
                        (dialog, which) -> start(Commands.INSTALL,
                                "Install opencode",
                                "Add the opencode apt repository and install opencode",
                                false))
                .show();
    }

    private void runCheck() {
        if (!requireTermux()) {
            return;
        }
        start(Commands.CHECK, "Check opencode", "Report the installed opencode version", true);
    }

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TermuxCommands.TERMUX_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        } else {
            showMissingDialog();
        }
    }

    private void start(String command, String label, String description, boolean background) {
        if (checkSelfPermission(TermuxCommands.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "asking for " + TermuxCommands.PERMISSION);
            // RUN_COMMAND is a dangerous permission owned by Termux; without it
            // startService throws and the reason looks like a settings problem.
            pending = () -> send(command, label, description, background);
            requestPermissions(new String[] {TermuxCommands.PERMISSION}, REQUEST_RUN_COMMAND);
            return;
        }
        send(command, label, description, background);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQUEST_RUN_COMMAND) {
            return;
        }
        Runnable next = pending;
        pending = null;
        Log.i(TAG, "permission " + (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                ? "granted" : "denied"));
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && next != null) {
            next.run();
        } else {
            showDeniedHelp();
        }
    }

    private void send(String command, String label, String description, boolean background) {
        try {
            TermuxCommands.send(this, command, label, description, background);
        } catch (RuntimeException refused) {
            Log.w(TAG, "Termux refused the command", refused);
            showPermissionHelp();
        }
    }

    @Override
    public void onCommandResult(String text) {
        Log.i(TAG, "result: " + text.replace("\n", " | "));
        output.setText(text);
        output.setVisibility(View.VISIBLE);
        refresh();
    }

    // --- helpers -------------------------------------------------------------

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TermuxCommands.TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    private boolean requireTermux() {
        if (isTermuxInstalled()) {
            return true;
        }
        showMissingDialog();
        return false;
    }

    private void showMissingDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.missing_title)
                .setMessage(R.string.missing_message)
                .setNegativeButton(R.string.missing_negative, null)
                .setPositiveButton(R.string.missing_positive, (dialog, which) -> openFdroid())
                .show();
    }

    private void openFdroid() {
        Intent browser = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://f-droid.org/packages/com.termux/"));
        startActivity(browser);
    }

    private void showPermissionHelp() {
        copy(TermuxCommands.ALLOW_EXTERNAL_APPS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_title)
                .setMessage(getString(R.string.permission_message)
                        + "\n\n" + TermuxCommands.ALLOW_EXTERNAL_APPS)
                .setPositiveButton(R.string.dialog_close, null)
                .show();
    }

    private void showDeniedHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_denied_title)
                .setMessage(R.string.permission_denied_message)
                .setNegativeButton(R.string.dialog_close, null)
                .setPositiveButton(R.string.permission_settings,
                        (dialog, which) -> startActivity(new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()))))
                .show();
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("opencode-setup", text));
        }
    }
}
