package io.github.mbanucu.opencodesetup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.ref.WeakReference;

/**
 * Receives the result bundle Termux sends back for a RUN_COMMAND.
 *
 * <p>Declared in the manifest as not exported: only a PendingIntent created by
 * this app (and filled in by Termux) can deliver here.
 */
public final class RunCommandReceiver extends BroadcastReceiver {

    static final String ACTION_RESULT = "io.github.mbanucu.opencodesetup.RUN_COMMAND_RESULT";

    /** Bundle key used by Termux for the result (PLUGIN_RESULT_BUNDLE). */
    private static final String EXTRA_RESULT_BUNDLE = "result";

    interface Listener {
        void onCommandResult(String text);
    }

    private static WeakReference<Listener> listener;
    private static String pending;

    static void setListener(Listener next) {
        listener = next == null ? null : new WeakReference<>(next);
        if (next == null || pending == null) {
            return;
        }
        String text = pending;
        pending = null;
        next.onCommandResult(text);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String text = describe(intent == null ? null : intent.getBundleExtra(EXTRA_RESULT_BUNDLE));
        Listener target = listener == null ? null : listener.get();
        if (target == null) {
            // The activity is not visible; hand it over on the next resume.
            pending = text;
        } else {
            pending = null;
            target.onCommandResult(text);
        }
    }

    private static String describe(Bundle result) {
        if (result == null) {
            return "Termux returned no result for this command. Visible commands run in the Termux window; watch them there.";
        }

        StringBuilder text = new StringBuilder();
        String message = result.getString("errmsg", "");
        if (message != null && !message.isEmpty()) {
            text.append(message.trim()).append("\n\n");
        }
        String stdout = result.getString("stdout", "");
        if (stdout != null && !stdout.isEmpty()) {
            text.append(stdout.trim()).append('\n');
        }
        String stderr = result.getString("stderr", "");
        if (stderr != null && !stderr.isEmpty()) {
            text.append(stderr.trim()).append('\n');
        }
        if (text.length() == 0) {
            text.append("(no output)");
        }
        text.append("\nexit code ").append(result.getInt("exitCode", -1));
        return text.toString();
    }
}
