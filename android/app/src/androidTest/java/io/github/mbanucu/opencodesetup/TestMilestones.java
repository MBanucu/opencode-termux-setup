package io.github.mbanucu.opencodesetup;

import android.os.SystemClock;
import android.util.Log;

/**
 * Marks a moment inside a test that the screen recording is meant to show.
 *
 * <p>The suite records the device's screen while it runs and reads
 * {@code /proc/uptime} at the instant it starts. A milestone is logged with
 * {@link SystemClock#elapsedRealtime()}, the same monotonic clock, so the second
 * a milestone falls on in the video is just
 * {@code (milestone - recordingStart) / 1000} - no clock offset to guess at and
 * nothing to keep in sync by hand.
 *
 * <p>Written to logcat under the app's tag, which is where the harness reads it
 * from, so it also lands in the per-run event log.
 */
public final class TestMilestones {

    private static final String TAG = "OpencodeSetup";

    private TestMilestones() {
    }

    /** Records {@code name} at the current monotonic time. */
    public static void mark(String name) {
        Log.i(TAG, "milestone:" + name + " uptime:" + SystemClock.elapsedRealtime());
    }
}
