package io.github.mbanucu.opencodesetup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/**
 * Receives the result bundle Termux sends back for a RUN_COMMAND.
 *
 * <p>Declared in the manifest as not exported: only a PendingIntent created by
 * this app (and filled in by Termux) can deliver here.
 */
public final class RunCommandReceiver extends BroadcastReceiver {

    static final String ACTION_RESULT = "io.github.mbanucu.opencodesetup.RUN_COMMAND_RESULT";

    /** Where the last answer is kept so adb can read it without root. */
    static final String RESULT_FILE = "last-result.txt";

    private static final String TAG = "OpencodeSetup";

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
        store(context, text);
        Listener target = listener == null ? null : listener.get();
        if (target == null) {
            // The activity is not visible; hand it over on the next resume.
            pending = text;
        } else {
            pending = null;
            target.onCommandResult(text);
        }
    }

    /** Keeps the answer on disk: the adb entry point and the app both use it. */
    private static void store(Context context, String text) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            return;
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(dir, RESULT_FILE)), StandardCharsets.UTF_8)) {
            writer.write(text);
        } catch (IOException failed) {
            Log.w(TAG, "could not store " + RESULT_FILE, failed);
        }
    }

    private static String describe(Bundle result) {
        if (result == null) {
            return "Termux returned no result for this command. Everything runs in its background runner; the Termux notification shows the log.";
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
