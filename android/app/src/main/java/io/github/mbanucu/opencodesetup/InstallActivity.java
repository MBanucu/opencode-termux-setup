package io.github.mbanucu.opencodesetup;

import android.os.Bundle;

/**
 * The install consent, as a page rather than a dialog.
 *
 * <p>It says what will be downloaded and by whom, shows the command, and then
 * prints the install itself while Termux runs it.
 */
public final class InstallActivity extends PageActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        heading(R.string.consent_title);
        body(R.string.consent_message);
        outputBox();
        button(R.string.consent_accept, v -> runCommand(Commands.INSTALL, "Install opencode",
                "Add the opencode apt repository and install opencode", false));
        button(R.string.consent_cancel, v -> finish());
    }
}
