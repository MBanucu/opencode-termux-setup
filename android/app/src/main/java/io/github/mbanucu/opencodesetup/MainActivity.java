package io.github.mbanucu.opencodesetup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
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

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_RUN_COMMAND = 1;
    private static final String TAG = "OpencodeSetup";

    private static final String CHECK_COMMAND =
            "opencode --version 2>/dev/null || echo 'opencode is not installed'";

    private static final String INSTALL_COMMAND =
            "curl -fsSL https://raw.githubusercontent.com/MBanucu/opencode-termux-setup/main/install.sh"
                    + " | bash";

    private static final String UNINSTALL_COMMAND =
            "curl -fsSL https://raw.githubusercontent.com/MBanucu/opencode-termux-setup/main/uninstall.sh"
                    + " | bash";

    private static final String ALLOW_EXTERNAL_APPS =
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties"
                    + " && termux-reload-settings";

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
        column.addView(button(R.string.action_uninstall, v -> confirmUninstall()));
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
                        (dialog, which) -> start(INSTALL_COMMAND,
                                "Install opencode",
                                "Add the opencode apt repository and install opencode",
                                false))
                .show();
    }

    private void confirmUninstall() {
        if (!requireTermux()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.uninstall_title)
                .setMessage(R.string.uninstall_message)
                .setNegativeButton(R.string.consent_cancel, null)
                .setPositiveButton(R.string.uninstall_accept,
                        (dialog, which) -> start(UNINSTALL_COMMAND,
                                "Uninstall opencode",
                                "Remove the opencode package and its apt repository",
                                false))
                .show();
    }

    private void runCheck() {
        if (!requireTermux()) {
            return;
        }
        start(CHECK_COMMAND, "Check opencode", "Report the installed opencode version", true);
    }

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        } else {
            showMissingDialog();
        }
    }

    private void start(String command, String label, String description, boolean background) {
        if (checkSelfPermission(PERMISSION_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "asking for " + PERMISSION_RUN_COMMAND);
            // RUN_COMMAND is a dangerous permission owned by Termux; without it
            // startService throws and the reason looks like a settings problem.
            pending = () -> send(command, label, description, background);
            requestPermissions(new String[] {PERMISSION_RUN_COMMAND}, REQUEST_RUN_COMMAND);
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
        PendingIntent result = PendingIntent.getBroadcast(this, 0,
                new Intent(RunCommandReceiver.ACTION_RESULT).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(RUN_COMMAND_ACTION);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] {"-lc", command});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        // A background command reports its output through the PendingIntent; a
        // visible one runs in the Termux window, and this Termux also delivers
        // its output when it finishes, so the app shows whatever arrives.
        // Current Termux picks the runner; the boolean is the older spelling of
        // the same choice and is still honoured as a fallback.
        intent.putExtra("com.termux.RUN_COMMAND_RUNNER",
                background ? "app-shell" : "terminal-session");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", description);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);

        try {
            startService(intent);
            Log.i(TAG, "sent " + (background ? "app-shell" : "terminal-session") + ": " + label);
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
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
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
        copy(ALLOW_EXTERNAL_APPS);
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_title)
                .setMessage(getString(R.string.permission_message)
                        + "\n\n" + ALLOW_EXTERNAL_APPS)
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
