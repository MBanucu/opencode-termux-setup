package io.github.mbanucu.opencodesetup;

import android.os.Bundle;

/**
 * The terminal for "Check setup", on a page of its own.
 *
 * <p>A single decision - run the check or go back - and then the terminal page
 * that runs it. This screen used to own the terminal itself; now that the command
 * gets a whole page, this one is only the choice and a sentence about it.
 */
public final class CheckActivity extends PageActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading(R.string.check_title);
        body(R.string.check_body);
        primaryButton(R.string.check_action, v -> TerminalActivity.start(this,
                Commands.CHECK, "Check opencode",
                "Report the installed opencode version",
                getString(R.string.check_title)));
    }
}
