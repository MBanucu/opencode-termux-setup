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
 * stays in the background. This screen deliberately has no terminal: each command
 * prints on the page that started it.
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
        button(R.string.action_check,
                v -> startActivity(new Intent(this, CheckActivity.class)));
        button(R.string.action_open, v -> openTermux());

        body(R.string.hint);
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
