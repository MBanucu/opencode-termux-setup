package io.github.mbanucu.opencodesetup;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * Setup companion for opencode on Termux.
 *
 * <p>It owns no code that installs anything: it explains the download, opens a
 * page for consent, and sends one command to Termux through RUN_COMMAND. Termux
 * stays in the background. This screen deliberately has no terminal: a confirmed
 * command opens its own page and prints there.
 */
public final class MainActivity extends PageActivity {

    private TextView status;
    private TextView permission;
    private Button grant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading(R.string.app_name);
        body(R.string.tagline);
        status = bold(R.string.status_termux_installed);
        permission = bold(R.string.status_permission_granted);
        // Asking here means the request lands on a page that explains it, instead
        // of interrupting the first command halfway through.
        grant = button(R.string.action_grant, v -> askForTermuxPermission());

        // The one thing the app is for gets the filled button; the rest are
        // equal-weight actions next to it.
        primaryButton(R.string.action_install,
                v -> startActivity(new Intent(this, InstallActivity.class)));
        button(R.string.action_uninstall,
                v -> startActivity(new Intent(this, UninstallActivity.class)));
        button(R.string.action_check, v -> startActivity(new Intent(this, CheckActivity.class)));
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

        boolean allowed = installed
                && checkSelfPermission(TermuxCommands.PERMISSION)
                        == PackageManager.PERMISSION_GRANTED;
        permission.setText(allowed
                ? R.string.status_permission_granted
                : R.string.status_permission_missing);
        permission.setTextColor(allowed ? 0xFF2E7D32 : 0xFFB3261E);
        // Without Termux installed there is no permission to hold, and asking
        // would raise a dialog nothing can satisfy.
        grant.setVisibility(installed && !allowed ? View.VISIBLE : View.GONE);
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
