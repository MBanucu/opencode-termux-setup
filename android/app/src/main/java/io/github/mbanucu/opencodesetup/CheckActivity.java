package io.github.mbanucu.opencodesetup;

import android.os.Bundle;

/**
 * The choice before the terminal: check the setup, or start opencode itself.
 *
 * <p>Both actions open the same terminal page, one for the version check and one
 * for the agent. This screen used to own the terminal itself; now that a command
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
        // The check only says whether opencode is there; this opens it, in the
        // same inline terminal the check's output is printed in. The smaller
        // font is for the TUI: more rows and columns, and the PTY gets them.
        // Below about sixty columns opencode drops the footer it prints its
        // version in, so the grid has to be wider than a check needs.
        button(R.string.check_opencode_action, v -> TerminalActivity.start(this,
                Commands.OPENCODE, "opencode",
                "Start the opencode coding agent",
                getString(R.string.check_opencode_title), 8));
    }
}
