package io.github.mbanucu.opencodesetup;

import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;

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

    /**
     * Declares a stretch in which the app is meant to show little because
     * something real is happening outside it - a download, a removal.
     *
     * <p>A still screen inside one of these is work, not a wait the app could
     * shorten, and {@code frames.py idle} reports the two apart. Closing the
     * handle marks the end, including when the test fails inside the block.
     */
    public static Busy busy(String name) {
        return new Busy(name);
    }

    /**
     * Times one part of a test and logs it as {@code step:<name> <ms>ms}.
     *
     * <p>The harness turns these lines into rows in {@code test/.timings.db}, so
     * a test that takes twelve seconds can be read as the sum of its parts
     * instead of one number that says nothing about where the time went.
     */
    public static Step step(String name) {
        return new Step(name);
    }

    /** The timed stretch, closed to log how long it took. */
    public static final class Step implements Closeable {

        private final String name;
        private final long started = SystemClock.elapsedRealtime();
        private boolean closed;

        private Step(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Log.i(TAG, "step:" + name + " "
                        + (SystemClock.elapsedRealtime() - started) + "ms");
            }
        }
    }

    /** The declared window, closed to mark its end. */
    public static final class Busy implements Closeable {

        private final String name;
        private boolean closed;

        private Busy(String name) {
            this.name = name;
            log("busy-start");
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                log("busy-end");
            }
        }

        private void log(String kind) {
            Log.i(TAG, kind + ":" + name + " uptime:" + SystemClock.elapsedRealtime());
        }
    }
}
