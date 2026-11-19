package io.github.mbanucu.opencodesetup;

import android.os.FileObserver;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Waits for a file the app writes, woken by the write rather than by a timer.
 *
 * <p>{@link FileObserver} reports changes on its own thread. The directory is
 * watched, not the file: the file may not exist yet, and the app writes several
 * of them side by side, so an event that is not the one wanted costs one re-read.
 *
 * <p>Each wait blocks for at most {@link #TICK}, so a device whose storage
 * reports no events still re-checks the condition as often as the polling this
 * replaced - the observer is a shortcut, not a requirement.
 */
final class FileWatcher {

    private static final int MASK = FileObserver.MODIFY
            | FileObserver.CREATE
            | FileObserver.CLOSE_WRITE
            | FileObserver.MOVED_TO
            | FileObserver.ATTRIB;

    /** The longest a wait blocks before looking at the condition again. */
    private static final long TICK = 50;

    private FileWatcher() {
    }

    /** True as soon as {@code ready} holds; false once {@code budget} has passed. */
    static boolean await(File file, BooleanSupplier ready, long budget)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + budget;
        if (ready.getAsBoolean()) {
            return true;
        }
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));
        FileObserver observer = watch(file.getParentFile(), gate);
        if (observer != null) {
            observer.startWatching();
        }
        try {
            while (true) {
                if (ready.getAsBoolean()) {
                    return true;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return ready.getAsBoolean();
                }
                upTo(gate, Math.min(left, TICK));
            }
        } finally {
            if (observer != null) {
                observer.stopWatching();
            }
        }
    }

    /** True if the file's directory changed within {@code budget}. */
    static boolean awaitChange(File file, long budget) throws InterruptedException {
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));
        FileObserver observer = watch(file.getParentFile(), gate);
        if (observer == null) {
            Thread.sleep(budget);
            return false;
        }
        observer.startWatching();
        try {
            return upTo(gate, budget);
        } finally {
            observer.stopWatching();
        }
    }

    private static FileObserver watch(File dir, AtomicReference<CountDownLatch> gate) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        return new FileObserver(dir.getAbsolutePath(), MASK) {
            @Override
            public void onEvent(int event, String path) {
                gate.get().countDown();
            }
        };
    }

    private static boolean upTo(AtomicReference<CountDownLatch> gate, long budget)
            throws InterruptedException {
        boolean event = gate.get().await(Math.max(1, budget), TimeUnit.MILLISECONDS);
        gate.set(new CountDownLatch(1));
        return event;
    }
}
