package io.github.mbanucu.opencodesetup;

import android.util.Log;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.util.Locale;

/**
 * Logs how long each test took, in one line that is easy to parse.
 *
 * <p>The end-to-end script captures the app's logcat while the instrumentation
 * runs and stores these lines in {@code test/.timings.db}, so a run leaves its
 * timings behind, the history can be queried with SQL, and the next run can be
 * given an estimate before it starts:
 *
 * <pre>timing: io.github.mbanucu.opencodesetup.SetupFlowTest#eInstall… 45123ms passed</pre>
 *
 * <p>Timing through a rule rather than the runner also counts the {@code @Before}
 * work, because the rule starts before the fixture and finishes with the test.
 */
public final class TestTimings extends TestWatcher {

    private static final String TAG = "OpencodeSetup";

    private long started;
    private boolean passed = true;

    @Override
    protected void starting(Description test) {
        started = System.currentTimeMillis();
        passed = true;
    }

    @Override
    protected void failed(Throwable failure, Description test) {
        passed = false;
    }

    @Override
    protected void finished(Description test) {
        String method = test.getMethodName();
        if (method == null) {
            // A class-level rule has no duration of its own to report.
            return;
        }
        Log.i(TAG, String.format(Locale.US, "timing: %s#%s %dms %s",
                test.getClassName(), method, System.currentTimeMillis() - started,
                passed ? "passed" : "failed"));
    }
}
