package io.github.mbanucu.opencodesetup;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Setup companion for opencode on Termux.
 *
 * <p>It owns no code that installs anything: it explains the download, opens a
 * page for consent, and sends one command to Termux through RUN_COMMAND. Termux
 * stays in the background; every state it shows comes from running
 * {@code opencode --version} in Termux and printing the answer on this page.
 */
public final class MainActivity extends PageActivity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading(R.string.app_name);
        body(R.string.tagline);
        status = bold(R.string.status_termux_installed);

        // The one thing the app is for gets the filled button; the rest are
        // equal-weight actions next to it.
        primaryButton(R.string.action_install,
                v -> startActivity(new Intent(this, InstallActivity.class)));
        button(R.string.action_uninstall,
                v -> startActivity(new Intent(this, UninstallActivity.class)));
        button(R.string.action_check, v -> runCheck());
        button(R.string.action_open, v -> openTermux());

        body(R.string.hint);
        outputBox();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean installed = isTermuxInstalled();
        status.setText(installed
                ? R.string.status_termux_installed
                : R.string.status_termux_missing);
        status.setTextColor(installed ? 0xFF2E7D32 : 0xFFB3261E);
    }

    private void runCheck() {
        if (!requireTermux()) {
            return;
        }
        runCommand(Commands.CHECK, "Check opencode", "Report the installed opencode version");
    }

    private void openTermux() {
        if (!requireTermux()) {
            return;
        }
        Intent launch =
                getPackageManager().getLaunchIntentForPackage(TermuxCommands.TERMUX_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        }
    }

    @Override
    public void onCommandResult(String text) {
        super.onCommandResult(text);
        refresh();
    }

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
        notice(getString(R.string.missing_title) + "\n\n" + getString(R.string.missing_message),
                R.string.missing_positive,
                v -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://f-droid.org/packages/com.termux/"))));
        return false;
    }
}
