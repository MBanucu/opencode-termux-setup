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

    static Screen numberedRows(int rows) {
        Screen screen = new Screen(20, rows);
        for (int row = 0; row < rows; row++) {
            screen.feed(String.valueOf(row));
            if (row < rows - 1) {
                screen.feed("\r\n");
            }
        }
        return screen;
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

    /**
     * Prints the suite's result and fails the process when a check did.
     *
     * <p>The counters start again afterwards: SuiteRunner runs every suite in one
     * JVM and they share this class, so a cumulative count would make each suite
     * report the checks before it as its own.
     */
    static void report(String suite) {
        boolean failedAny = failed != 0;
        System.out.println(failedAny
                ? "FAIL " + failed + " of " + (passed + failed) + " " + suite
                : "PASS " + passed + " " + suite);
        passed = 0;
        failed = 0;
        if (failedAny) {
            System.exit(1);
        }
    }
}
