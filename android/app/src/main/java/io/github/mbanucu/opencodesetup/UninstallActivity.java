package io.github.mbanucu.opencodesetup;

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
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The uninstall page: one decision, then one command.
 *
 * <p>Uninstalling has two honest outcomes and they differ a lot, so the choice
 * gets a screen of its own instead of being buried in a dialog. The safe one is
 * preselected and the exact command is always on screen; the destructive one
 * says which directories it deletes.
 */
public final class UninstallActivity extends Activity {

    private static final int REQUEST_RUN_COMMAND = 2;

    private RadioButton keep;
    private TextView preview;
    private Runnable pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        showCommand();
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
            showDeniedHelp();
        }
    }

    // --- ui ------------------------------------------------------------------

    private ScrollView buildUi() {
        int pad = dp(20);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.uninstall_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        TextView intro = new TextView(this);
        intro.setText(R.string.uninstall_intro);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        intro.setPadding(0, dp(6), 0, dp(16));
        column.addView(intro);

        RadioGroup group = new RadioGroup(this);

        keep = option(group, R.string.uninstall_keep_title, true);
        detail(group, R.string.uninstall_keep_detail);
        option(group, R.string.uninstall_purge_title, false);
        detail(group, R.string.uninstall_purge_detail);

        group.setOnCheckedChangeListener((ignored, id) -> showCommand());
        column.addView(group);

        preview = new TextView(this);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        preview.setBackgroundColor(0xFFF0F0F0);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        previewParams.topMargin = dp(16);
        column.addView(preview, previewParams);

        column.addView(button(R.string.uninstall_confirm, v -> remove()));
        column.addView(button(R.string.consent_cancel, v -> finish()));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private RadioButton option(RadioGroup group, int label, boolean checked) {
        RadioButton button = new RadioButton(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setPadding(0, dp(8), 0, dp(2));
        group.addView(button);
        // Checked only after it joins the group: a button checked before being
        // added never becomes the group's checked id, so selecting the other
        // option left both of them on and the command never changed.
        button.setChecked(checked);
        return button;
    }

    private void detail(RadioGroup group, int text) {
        TextView detail = new TextView(this);
        detail.setText(text);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        detail.setPadding(dp(32), 0, 0, dp(12));
        group.addView(detail);
    }

    private Button button(int label, android.view.View.OnClickListener onClick) {
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

    private boolean purging() {
        return !keep.isChecked();
    }

    private void showCommand() {
        preview.setText(getString(R.string.uninstall_preview) + "\n\n"
                + (purging() ? Commands.UNINSTALL_PURGE : Commands.UNINSTALL));
    }

    // --- action --------------------------------------------------------------

    private void remove() {
        String command = purging() ? Commands.UNINSTALL_PURGE : Commands.UNINSTALL;
        if (checkSelfPermission(TermuxCommands.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            pending = () -> send(command);
            requestPermissions(new String[] {TermuxCommands.PERMISSION}, REQUEST_RUN_COMMAND);
            return;
        }
        send(command);
    }

    private void send(String command) {
        try {
            TermuxCommands.send(this, command, "Uninstall opencode",
                    purging()
                            ? "Remove the opencode package, its apt repository and all its data"
                            : "Remove the opencode package and its apt repository, keeping its data",
                    false);
            finish();
        } catch (RuntimeException refused) {
            showPermissionHelp();
        }
    }

    // --- help ----------------------------------------------------------------

    private void showPermissionHelp() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("opencode-setup", TermuxCommands.ALLOW_EXTERNAL_APPS));
        }
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
}
