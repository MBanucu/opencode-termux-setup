package io.github.mbanucu.opencodesetup;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

/**
 * A command and its output, and nothing else.
 *
 * <p>Once a page has the user's consent - Start setup, Remove opencode, Run check
 * - the command is handed to this page, which runs it on arrival. The header keeps
 * the way back and the name of the action, the footer keeps the keys a terminal
 * needs - Ctrl and the keyboard - and a small copy, and everything between
 * belongs to the terminal.
 */
public final class TerminalActivity extends PageActivity {

    private static final String EXTRA_COMMAND = "terminal_command";
    private static final String EXTRA_LABEL = "terminal_label";
    private static final String EXTRA_DESCRIPTION = "terminal_description";
    private static final String EXTRA_TITLE = "terminal_title";

    /** Raw PTY bytes for the rendering tests; when set, nothing is run. */
    static final String EXTRA_PROBE = "pty_probe_b64";

    /**
     * Shortens the window under a running command, carrying a
     * {@code resize_fraction} extra.
     *
     * <p>A broadcast rather than another intent: a page that is already on top
     * does not reliably get a second intent on this device - the launch makes no
     * second activity and no {@code onNewIntent} either - and what a resize test
     * needs is the page that is running the command, not a page that replaces it.
     */
    public static final String ACTION_RESIZE = "io.github.mbanucu.opencodesetup.action.RESIZE";

    /** Whether {@link #resizeReceiver} is still registered, so closing cannot throw. */
    private boolean listeningForResize;

    /**
     * Moves the window under the running command and leaves the command alone.
     *
     * <p>Nothing is abandoned and nothing is thawed: what is being watched is what
     * the command does when the terminal it draws to changes size. The frame
     * shrinks, the page re-fits the grid it froze, and the app writes the new size
     * to the PTY.
     */
    private final BroadcastReceiver resizeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context from, Intent intent) {
            float fraction = intent == null ? 0f : intent.getFloatExtra("resize_fraction", 0f);
            if (fraction <= 0f) {
                return;
            }
            Diagnostics.log(TerminalActivity.this, "reshape:" + fraction);
            getIntent().putExtra("terminal_fraction", fraction);
            pinTerminalHeight();
        }
    };

    /** Opens a page that runs {@code command} as soon as it appears. */
    static void start(Context from, String command, String label, String description,
            CharSequence title) {
        start(from, command, label, description, title, 0);
    }

    /**
     * The same, with a terminal font size in pixels.
     *
     * <p>A TUI uses more of the screen than a two line check does. The PTY is
     * resized to the cells that fit, so a smaller font is also a larger terminal
     * for the program it runs.
     */
    static void start(Context from, String command, String label, String description,
            CharSequence title, int fontPx) {
        start(from, command, label, description, title, fontPx, false);
    }

    /**
     * The same, with this page's keyboard wired into the command's stdin.
     *
     * <p>Only an agent's TUI has anything to type into: a check's output is two
     * lines, and a package manager must not be interrupted by a stray key.
     */
    static void start(Context from, String command, String label, String description,
            CharSequence title, int fontPx, boolean input) {
        Intent page = new Intent(from, TerminalActivity.class);
        page.putExtra(EXTRA_COMMAND, command);
        page.putExtra(EXTRA_LABEL, label);
        page.putExtra(EXTRA_DESCRIPTION, description);
        page.putExtra(EXTRA_TITLE, title);
        page.putExtra("terminal_font", fontPx);
        page.putExtra("terminal_input", input);
        from.startActivity(page);
    }

    /**
     * A probe or a filled page runs nothing, so it has no result of its own to
     * receive - and taking one would print another page's output into a
     * terminal that a test is reading.
     */
    @Override
    protected boolean listensForResults() {
        Intent opener = getIntent();
        return opener != null
                && opener.getStringExtra(EXTRA_PROBE) == null
                && opener.getIntExtra("filler_lines", 0) <= 0;
    }

    /**
     * Takes over the page for a test's next case.
     *
     * <p>The tests start each case on top of this page instead of clearing the
     * task, so the terminal, its window and the task all survive: the new probe
     * or height arrives here. Anything the page was showing before - a command
     * still streaming, its result listener, the previous screen - is dropped
     * first, because the new case is about to read what it writes.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        abandonCommand();
        // A reused page froze the grid its last command was given; the new
        // intent may pin another height, so it measures itself again.
        thawTerminal();
        CharSequence title = intent.getCharSequenceExtra(EXTRA_TITLE);
        retitle(title == null ? getString(R.string.app_name) : title);
        pinTerminalHeight();

        String probe = intent.getStringExtra(EXTRA_PROBE);
        if (probe != null) {
            clearTerminal();
            writeProbeToTerminal(probe);
            return;
        }
        int filler = intent.getIntExtra("filler_lines", 0);
        if (filler > 0) {
            clearTerminal();
            fillTerminal(filler);
            return;
        }
        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (command != null) {
            runCommand(command,
                    String.valueOf(intent.getStringExtra(EXTRA_LABEL)),
                    String.valueOf(intent.getStringExtra(EXTRA_DESCRIPTION)));
        }
    }

    // The flag exists only from API 33; the else branch must call the legacy
    // overload on API 24-32, which is what lint misses through the guard.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IntentFilter resizes = new IntentFilter(ACTION_RESIZE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resizeReceiver, resizes, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resizeReceiver, resizes);
        }
        listeningForResize = true;
        Intent opener = getIntent();
        String command = opener == null ? null : opener.getStringExtra(EXTRA_COMMAND);
        String probe = opener == null ? null : opener.getStringExtra(EXTRA_PROBE);
        // A filler is a page of its own: a test asked for a full terminal and
        // nothing to run, which is how the height test gets a buffer that
        // overflows at every size.
        int filler = opener == null ? 0 : opener.getIntExtra("filler_lines", 0);
        if (command == null && probe == null && filler <= 0) {
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

    @Override
    protected void onDestroy() {
        if (listeningForResize) {
            unregisterReceiver(resizeReceiver);
            listeningForResize = false;
        }
        super.onDestroy();
    }
}
