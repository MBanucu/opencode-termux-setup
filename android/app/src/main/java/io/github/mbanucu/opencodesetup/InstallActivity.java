package io.github.mbanucu.opencodesetup;

import android.os.Bundle;

/**
 * The install consent, as a page rather than a dialog.
 *
 * <p>It says what will be downloaded and by whom, shows the command, and then
 * prints the install itself as Termux runs it in the background - the window
 * never changes.
 */
public final class InstallActivity extends PageActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading(R.string.consent_title);
        // Once setup starts, the preamble has done its job: collapsing it gives
        // the terminal the screen it needs.
        collapseOnRun(body(R.string.consent_message));
        outputBox();
        primaryButton(R.string.consent_accept, v -> runCommand(Commands.INSTALL, "Install opencode",
                "Add the opencode apt repository and install opencode"));
        button(R.string.consent_cancel, v -> finish());
    }
}
