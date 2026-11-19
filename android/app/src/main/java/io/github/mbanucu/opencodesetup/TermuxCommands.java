package io.github.mbanucu.opencodesetup;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The one place that builds and sends a Termux RUN_COMMAND.
 *
 * <p>Everything goes out on the background {@code app-shell} runner on purpose:
 * a {@code terminal-session} command raises the Termux window, which takes the
 * user out of this app mid-flow. Running in the background keeps this screen in
 * front, and the command's output comes back through {@link RunCommandReceiver}
 * to be printed on the page that sent it.
 */
final class TermuxCommands {

    static final String TERMUX_PACKAGE = "com.termux";
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";

    static final String ALLOW_EXTERNAL_APPS =
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties"
                    + " && termux-reload-settings";

    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String TAG = "OpencodeSetup";

    private TermuxCommands() {
    }

    static void send(Context context, String command, String label, String description) {
        send(context, command, label, description, 0, 0, 0);
    }

    /**
     * Sends the command, telling Termux to copy its output to a loopback
     * {@code port} while it runs. {@code 0} means no live stream: only the
     * result bundle comes back.
     */
    static void send(Context context, String command, String label, String description,
            int port, int columns, int rows) {
        PendingIntent result = PendingIntent.getBroadcast(context, 0,
                new Intent(RunCommandReceiver.ACTION_RESULT).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, SERVICE);
        intent.setAction(ACTION);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                new String[] {"-lc", wrap(command, port, columns, rows)});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        intent.putExtra("com.termux.RUN_COMMAND_RUNNER", "app-shell");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", description);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);

        // RuntimeExceptions are surfaced to the caller, which explains the
        // allow-external-apps setting instead of failing silently.
        context.startService(intent);
        Log.i(TAG, "sent app-shell: " + label);
    }

    /**
     * Runs the command under a pseudo terminal, the way the Termux window does,
     * so progress bars and colours exist at all, and copies its output to the
     * caller's port as it is written.
     *
     * <p>{@code script} gives the command a PTY; {@code tee} sends the same bytes
     * to the app and to Termux, so the result bundle is unchanged. If Termux has
     * no {@code script}, the command still runs and streams, just without a PTY.
     *
     * <p>The PTY is resized to the app's terminal before the command starts. Left
     * at {@code script}'s 80x24 default, a progress line that redraws itself with
     * a carriage return wraps on a phone-width terminal, so each redraw lands on
     * a row of its own and the fragment above it is left behind.
     */
    private static String wrap(String command, int port, int columns, int rows) {
        String quoted = "'" + command.replace("'", "'\\''") + "'";
        String sized = columns > 0 && rows > 0
                ? "stty cols " + columns + " rows " + rows + " 2>/dev/null; " : "";
        String run = "if command -v script >/dev/null 2>&1; then "
                + "TERM=xterm-256color script -q -f -e -c \"" + sized
                + "bash -lc " + quoted + "\" /dev/null; "
                + "else bash -lc " + quoted + "; fi";
        if (port <= 0) {
            return run;
        }
        // fd 3 is the app's listener; when that cannot be opened the command
        // still runs and Termux still gets the output.
        return "exec 3<>/dev/tcp/127.0.0.1/" + port + " 2>/dev/null || exec 3>/dev/null; "
                + "{ " + run + " ; } 2>&1 | stdbuf -oL tee >(cat >&3); "
                + "exit ${PIPESTATUS[0]}";
    }
}
