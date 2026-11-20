package io.github.mbanucu.terminal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runs every suite named on the command line in one JVM.
 *
 * <p>A {@code java} start costs more than the checks themselves: fifteen files,
 * fifteen starts, about two seconds of the suite's four. The instrumented tests
 * got the same time back by running three classes in one instrumentation run, so
 * this does the same for the JVM suites. The names come from
 * {@code terminal/test.sh}, which finds the files; nothing is listed here.
 *
 * <p>Each suite is timed into the same {@code timing: <class>#<test> <ms>
 * <passed|failed>} line the app's {@code TestTimings} writes, so a slow suite is
 * on the record instead of only being felt.
 */
final class SuiteRunner {

    private SuiteRunner() {
    }

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            long started = System.currentTimeMillis();
            run(name);
            System.out.println("timing: " + name + "#main "
                    + (System.currentTimeMillis() - started) + "ms passed");
        }
    }

    /** Calls the suite's {@code main}; its own report decides the exit code. */
    private static void run(String name) throws Exception {
        Method main = Class.forName(name).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw failed;
        }
    }
}
