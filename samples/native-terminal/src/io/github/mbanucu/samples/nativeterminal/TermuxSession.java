package io.github.mbanucu.samples.nativeterminal;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts an interactive shell in a Termux session, reachable from this app.
 *
 * <p>An app cannot fork Termux's shell itself: `$PREFIX/bin/bash` is another
 * uid's private file and does not even resolve (see the `probe` button). What it
 * can do is ask Termux to run a command, which is what RUN_COMMAND is for, and
 * hand that command a loopback socket for its stdin and stdout. The command runs
 * under `script`, so it has a real pty - `[ -t 1 ]` holds, `stty size` works, and
 * a full-screen program renders - while this app talks to it through the socket.
 *
 * <p>Background runner for the inline sample: a foreground session raises the
 * Termux window and takes the user out of this app, while the whole point there
 * is to keep the inline terminal in front. {@link #startForeground} is the other
 * way round, for when the program is to be seen in Termux's own renderer.
 */
final class TermuxSession {

    private static final String TAG = "PtySample";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String DIRECTORY = HOME + "/.native-terminal";
    /** Where the command writes the pty it was given, for the resize loop. */
    private static final String TTY_FILE = DIRECTORY + "/pty";

    private TermuxSession() {
    }

    static void start(Context context, int port) {
        start(context, port, 0, "bash -i", "inline terminal sample");
    }

    /** The same, running {@code command} in the session instead of a shell. */
    static void start(Context context, int port, String command, String label) {
        start(context, port, 0, command, label);
    }

    /** The same, with a control port the wrapper reads grid sizes from. */
    static void start(Context context, int port, int controlPort, String command, String label) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, SERVICE);
        intent.setAction(ACTION);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                new String[] { "-lc", wrap(port, controlPort, command) });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        intent.putExtra("com.termux.RUN_COMMAND_RUNNER", "app-shell");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "for the accessibility sample");
        // RuntimeExceptions reach the caller: a missing allow-external-apps is
        // explained there instead of failing silently.
        context.startService(intent);
        Log.i(TAG, "asked Termux for a session on port " + port);
    }

    /**
     * Runs a command in a Termux session the GUI shows and switches to.
     *
     * <p>The opposite of the sessions above: they are background so the inline
     * view stays in front, this one is foreground precisely so Termux's own
     * emulator draws the program, with no socket and no wrapper in between.
     *
     * @param command the line {@code bash -lc} runs in the new session
     */
    static void startForeground(Context context, String command, String label) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, SERVICE);
        intent.setAction(ACTION);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] { "-lc", command });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        // 0 asks Termux to switch to the new session, which is the point.
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 0);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        context.startService(intent);
        Log.i(TAG, "asked Termux for a foreground session: " + label);
    }

    /**
     * The command Termux runs: a program under a pty, with fd 3 as this app's
     * socket and fd 4 as its control port.
     *
     * <p>Quoting matters more than it looks. This string is what {@code bash -lc}
     * runs, so `"$p"` here must reach bash as plain double quotes; a backslash
     * before a quote - the natural thing to write when the string is also
     * *inside* a quoted string somewhere - makes the wrapper a syntax error, and a
     * syntax error means the app's socket is never opened and the session looks
     * like it never started. The shell is stopped by the {@code || exec ...} on
     * each `exec`: a failed `exec` with a redirection error ends a
     * non-interactive shell.
     */
    static String wrap(int port, int controlPort, String command) {
        String open = "exec 3<>/dev/tcp/127.0.0.1/" + port
                + " 2>/dev/null || exec 3<>/dev/null; ";
        // The pty is script's, inside Termux: this app cannot ioctl it, so a
        // process on Termux's side sets the size with stty -F, which raises
        // SIGWINCH in the program just like a terminal window's resize. The
        // device name is written by the command itself, because only the process
        // inside the pty can ask which /dev/pts it got.
        String watch = "";
        if (controlPort > 0) {
            watch = "mkdir -p " + DIRECTORY + " 2>/dev/null; "
                    + "exec 4<>/dev/tcp/127.0.0.1/" + controlPort
                    + " 2>/dev/null || exec 4<>/dev/null; "
                    + "( while IFS=' ' read -r wc wr <&4; do "
                    + "p=$(cat " + TTY_FILE + " 2>/dev/null); "
                    + "{ [ -n \"$p\" ] && [ -n \"$wr\" ]; } || continue; "
                    + "stty -F \"$p\" cols \"$wc\" rows \"$wr\" 2>/dev/null "
                    + "|| stty cols \"$wc\" rows \"$wr\" < \"$p\" 2>/dev/null; "
                    + "done ) 2>/dev/null & watch=$!; trap 'kill $watch 2>/dev/null' EXIT; ";
        }
        // `tty` writes the device name to the file itself, so no $(...) and no
        // escaping to get wrong; the size is set from inside the pty because only
        // the process in it can.
        String sized = "tty > " + TTY_FILE + " 2>/dev/null; "
                + "stty cols 80 rows 24 2>/dev/null; ";
        String run = "if command -v script >/dev/null 2>&1; then "
                + "TERM=xterm-256color script -q -f -e -c \"" + sized + command + "\" /dev/null; "
                + "else " + command + "; fi";
        return open + watch + "{ " + run + " ; } <&3 2>&1 | stdbuf -oL tee >(cat >&3)";
    }
}
