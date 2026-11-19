package io.github.mbanucu.opencodesetup;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The one place that builds and sends a Termux RUN_COMMAND.
 *
 * <p>Both activities use it: the main screen for install and check, the
 * uninstall page for either of its two commands.
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

    static void send(Context context, String command, String label, String description,
                     boolean background) {
        PendingIntent result = PendingIntent.getBroadcast(context, 0,
                new Intent(RunCommandReceiver.ACTION_RESULT).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, SERVICE);
        intent.setAction(ACTION);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] {"-lc", command});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        // A background command reports its output through the PendingIntent; a
        // visible one runs in the Termux window, and this Termux also delivers
        // its output when it finishes, so the app shows whatever arrives.
        // Current Termux picks the runner; the boolean is the older spelling of
        // the same choice and is still honoured as a fallback.
        intent.putExtra("com.termux.RUN_COMMAND_RUNNER",
                background ? "app-shell" : "terminal-session");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", description);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);

        // RuntimeExceptions are surfaced to the caller, which explains the
        // allow-external-apps setting instead of failing silently.
        context.startService(intent);
        Log.i(TAG, "sent " + (background ? "app-shell" : "terminal-session") + ": " + label);
    }
}
