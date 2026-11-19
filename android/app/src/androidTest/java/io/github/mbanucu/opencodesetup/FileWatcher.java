package io.github.mbanucu.opencodesetup;

import android.os.FileObserver;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Waits for a file the app writes, without polling it.
 *
 * <p>{@link FileObserver} reports writes on its own thread, so a wait ends when
 * the app writes rather than on the next tick of a timer. The directory is what
 * gets watched, because the file being waited for may not exist yet and the app
 * writes several of them side by side; an event that is not the one wanted costs
 * one re-read and nothing more.
 *
 * <p>Every wait is capped at {@link #MAX_WAIT}, so on a device whose storage
 * reports no events the condition is still re-checked instead of the whole
 * budget passing unseen.
 */
final class FileWatcher {

    private static final int MASK = FileObserver.MODIFY
            | FileObserver.CREATE
            | FileObserver.CLOSE_WRITE
            | FileObserver.MOVED_TO
            | FileObserver.ATTRIB;

    /** The longest a single wait blocks before it looks at the condition again. */
    private static final long MAX_WAIT = 500;

    private FileWatcher() {
    }

    /** True as soon as {@code ready} holds; false once {@code budget} has passed. */
    static boolean await(File file, BooleanSupplier ready, long budget)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + budget;
        while (true) {
            if (ready.getAsBoolean()) {
                return true;
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return ready.getAsBoolean();
            }
            awaitChange(file, Math.min(left, MAX_WAIT));
        }
    }

    /** True if the file's directory changed within {@code budget}. */
    static boolean awaitChange(File file, long budget) throws InterruptedException {
        File dir = file.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            // Nothing to watch (the directory itself is not there yet): fall back
            // to waiting the way a timer would.
            Thread.sleep(Math.min(budget, MAX_WAIT));
            return false;
        }
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));
        FileObserver observer = new FileObserver(dir.getAbsolutePath(), MASK) {
            @Override
            public void onEvent(int event, String path) {
                gate.get().countDown();
            }
        };
        observer.startWatching();
        try {
            return gate.get().await(Math.max(1, budget), TimeUnit.MILLISECONDS);
        } finally {
            observer.stopWatching();
        }
    }
}
