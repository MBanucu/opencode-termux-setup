package io.github.mbanucu.opencodesetup;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One timestamped line per event, for logcat and for adb.
 *
 * <p>When a run goes wrong the interesting question is usually "did the command
 * get sent, did output arrive, what came back" - and after the fact the screen
 * is gone. This file keeps that sequence in the app's external files directory,
 * where {@code adb shell cat} can read it without root, and mirrors it to
 * logcat under one tag so a test can quote it in a failure.
 */
final class Diagnostics {

    /** The tag the end-to-end suite reads its evidence from. */
    static final String TAG = "OpencodeSetup";

    /** Older lines are dropped rather than letting the file grow for ever. */
    private static final long LIMIT = 64 * 1024;
    private static final String FILE = "events.log";

    private Diagnostics() {
    }

    static void log(Context context, String message) {
        Log.i(TAG, message);
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            return;
        }
        File file = new File(dir, FILE);
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  " + message + "\n";
        synchronized (Diagnostics.class) {
            try {
                if (file.length() > LIMIT) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write("".getBytes(StandardCharsets.UTF_8));
                    }
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException unwritable) {
                Log.w(TAG, "could not write the event log", unwritable);
            }
        }
    }
}
