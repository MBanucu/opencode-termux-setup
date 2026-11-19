package io.github.mbanucu.opencodesetup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * A command and its output, and nothing else.
 *
 * <p>Once a page has the user's consent - Start setup, Remove opencode, Run check
 * - the command is handed to this page, which runs it on arrival. The header keeps
 * the way back and the name of the action, the footer keeps copy and cancel, and
 * everything between belongs to the terminal.
 *
 * <p>Cancel only leaves the page: Termux's background runner is already working,
 * and the app has no way to stop a command it did not fork.
 */
public final class TerminalActivity extends PageActivity {

    private static final String EXTRA_COMMAND = "terminal_command";
    private static final String EXTRA_LABEL = "terminal_label";
    private static final String EXTRA_DESCRIPTION = "terminal_description";
    private static final String EXTRA_TITLE = "terminal_title";

    /** Raw PTY bytes for the rendering tests; when set, nothing is run. */
    static final String EXTRA_PROBE = "pty_probe_b64";

    /** Opens a page that runs {@code command} as soon as it appears. */
    static void start(Context from, String command, String label, String description,
            CharSequence title) {
        Intent page = new Intent(from, TerminalActivity.class);
        page.putExtra(EXTRA_COMMAND, command);
        page.putExtra(EXTRA_LABEL, label);
        page.putExtra(EXTRA_DESCRIPTION, description);
        page.putExtra(EXTRA_TITLE, title);
        if (from instanceof PageActivity) {
            // Test hooks travel with the intent, so the terminal page is the one
            // that receives the height and filler asked for on the start page.
            Intent opener = ((PageActivity) from).getIntent();
            if (opener != null) {
                page.putExtras(opener);
            }
        }
        from.startActivity(page);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent opener = getIntent();
        String command = opener == null ? null : opener.getStringExtra(EXTRA_COMMAND);
        String probe = opener == null ? null : opener.getStringExtra(EXTRA_PROBE);
        if (command == null && probe == null) {
            finish();
            return;
        }
        CharSequence title = opener.getCharSequenceExtra(EXTRA_TITLE);
        terminalPage(title == null ? getString(R.string.app_name) : title);

        if (probe != null) {
            // A rendering test hand-writes the bytes; the terminal is what has to
            // interpret them, so there is nothing here to run.
            writeProbeToTerminal(probe);
            return;
        }

        int filler = opener.getIntExtra("filler_lines", 0);
        if (filler > 0) {
            // A test asked for a full terminal: a two line command would clear
            // what it wrote, so nothing is run here.
            fillTerminal(filler);
            return;
        }
        runCommand(command,
                String.valueOf(opener.getStringExtra(EXTRA_LABEL)),
                String.valueOf(opener.getStringExtra(EXTRA_DESCRIPTION)));
    }
}
