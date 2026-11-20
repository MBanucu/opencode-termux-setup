package io.github.mbanucu.terminal;

/**
 * Shared bookkeeping for the JVM suites: one line per check, and a count per
 * suite so `terminal/test.sh` can run several small files without a framework.
 */
final class TestSupport {

    private static int passed;
    private static int failed;

    private TestSupport() {
    }

    static String cursor(Screen screen) {
        return screen.cursorRow() + "," + screen.cursorCol();
    }

    static String hex(int colour) {
        return String.format("0x%06x", colour);
    }

    static void check(String what, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }

    /** Prints the suite's result and fails the process when a check did. */
    static void report(String suite) {
        System.out.println(failed == 0
                ? "PASS " + passed + " " + suite
                : "FAIL " + failed + " of " + (passed + failed) + " " + suite);
        if (failed != 0) {
            System.exit(1);
        }
    }
}
