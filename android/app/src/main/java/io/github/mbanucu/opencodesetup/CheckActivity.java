package io.github.mbanucu.opencodesetup;

import android.os.Bundle;

/**
 * The terminal for "Check setup", on a page of its own.
 *
 * <p>The check used to print into a terminal at the bottom of the start screen,
 * which left the main page carrying a mostly empty black box before anything had
 * run. Now the main page only opens this one, and this one owns the terminal: it
 * asks Termux for {@code opencode --version} when told to, shows the answer where
 * the command wrote it, and can run again.
 */
public final class CheckActivity extends PageActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading(R.string.check_title);
        body(R.string.check_body);
        primaryButton(R.string.check_action, v -> run());
        outputBox();
    }

    private void run() {
        runCommand(Commands.CHECK, "Check opencode", "Report the installed opencode version");
    }
}
