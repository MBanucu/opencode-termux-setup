package io.github.mbanucu.opencodesetup;

import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * The uninstall page: one decision, then one command, and its output.
 *
 * <p>Uninstalling has two honest outcomes and they differ a lot, so the choice
 * gets a screen of its own. The safe one is preselected and the exact command is
 * always on screen; the destructive one says which directories it deletes. The
 * removal itself is printed here, where it was started.
 */
public final class UninstallActivity extends PageActivity {

    private RadioButton keep;
    private TextView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading(R.string.uninstall_title);
        body(R.string.uninstall_intro);

        RadioGroup group = new RadioGroup(this);
        keep = option(group, R.string.uninstall_keep_title, true);
        detail(group, R.string.uninstall_keep_detail);
        option(group, R.string.uninstall_purge_title, false);
        detail(group, R.string.uninstall_purge_detail);
        group.setOnCheckedChangeListener((ignored, id) -> showCommand());
        column.addView(group);

        // Shown the way the terminal will print it, not as grey body text.
        preview = mono(getString(R.string.uninstall_preview));
        outputBox();
        primaryButton(R.string.uninstall_confirm, v -> remove());
        button(R.string.consent_cancel, v -> finish());
        showCommand();
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

    private boolean purging() {
        return !keep.isChecked();
    }

    private void showCommand() {
        preview.setText(getString(R.string.uninstall_preview) + "\n\n"
                + (purging() ? Commands.UNINSTALL_PURGE : Commands.UNINSTALL));
    }

    private void remove() {
        runCommand(purging() ? Commands.UNINSTALL_PURGE : Commands.UNINSTALL,
                "Uninstall opencode",
                purging()
                        ? "Remove the opencode package, its apt repository and all its data"
                        : "Remove the opencode package and its apt repository, keeping its data");
    }
}
