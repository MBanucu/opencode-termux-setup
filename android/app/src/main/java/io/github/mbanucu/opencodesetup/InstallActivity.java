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
        body(R.string.consent_message);
        // Consent is given here; the install itself gets a page of its own, where
        // the terminal can take the whole screen.
        primaryButton(R.string.consent_accept, v -> TerminalActivity.start(this,
                Commands.INSTALL, "Install opencode",
                "Add the opencode apt repository and install opencode",
                getString(R.string.action_install)));
        button(R.string.consent_cancel, v -> finish());
    }
}
