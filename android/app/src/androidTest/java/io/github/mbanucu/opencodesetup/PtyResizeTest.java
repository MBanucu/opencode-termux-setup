package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pseudo terminal has to follow the window while a command is running.
 *
 * <p>Termux resizes the PTY it made when the terminal view changes size, and the
 * program inside gets SIGWINCH and redraws. This app does not own the PTY -
 * {@code script} does - so it asks a watcher on Termux's side to set the size.
 * The only honest witness is the program: the command here prints what the
 * kernel says its terminal is ({@code stty size}), once a second, and the test
 * shortens the page under it.
 *
 * <p>Two numbers are compared: what the app wrote to its control port
 * ({@code pty-resize:NNxNN} in {@code events.log}) and what the program then
 * printed for itself. A page that only re-fits its own grid passes the first
 * check and fails the second, which is the bug this test exists for.
 */
@RunWith(AndroidJUnit4.class)
public final class PtyResizeTest {

    private static final String FILE = "pty-resize.txt";

    /**
     * The size the kernel gives the command, once a second, for long enough to
     * cover the page opening and one resize - and bounded, so a leftover loop
     * does not outlive the run.
     */
    private static final String SIZES = "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18"
            + " 19 20 21 22 23 24 25; do stty size; sleep 1; done";

    private static final long SHORT = 10_000;
    private static final long AWAIT = 30_000;

    /** A line the command printed: {@code rows cols}, as {@code stty size} writes it. */
    private static final Pattern SIZE = Pattern.compile("^\\s*(\\d+) (\\d+)\\s*$", Pattern.MULTILINE);

    /** What the app told Termux to set on the PTY. */
    private static final Pattern PTY_RESIZE = Pattern.compile("pty-resize:(\\d+)x(\\d+)");

    @Rule
    public final TestTimings timings = new TestTimings();

    private Context context;
    private File events;
    private File screen;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        events = new File(context.getExternalFilesDir(null), "events.log");
        screen = new File(context.getExternalFilesDir(null), FILE);
    }

    @Test
    public void thePtyFollowsTheWindowWhileTheCommandRuns() throws Exception {
        long mark = events.length();
        screen.delete();
        TestMilestones.mark("pty-resize");

        int[] before;
        try (TestMilestones.Step ignored = TestMilestones.step("command prints its terminal size")) {
            context.startActivity(TerminalPage.opening(context, FILE)
                    .putExtra("terminal_command", SIZES)
                    .putExtra("terminal_label", "Pty resize")
                    .putExtra("terminal_description", "")
                    .putExtra("terminal_title", "Resize probe"));
            before = awaitSize(-1);
        }
        assertNotNull("the command reports the size of its own terminal", before);

        int[] expected;
        try (TestMilestones.Step ignored = TestMilestones.step("window shortened under the command")) {
            // The frame shrinks the way the keyboard shrinks it; the command is
            // untouched, so only a real PTY resize can change what it prints.
            context.sendBroadcast(new Intent(TerminalActivity.ACTION_RESIZE)
                    .setPackage(context.getPackageName())
                    .putExtra("resize_fraction", 0.3f));
            expected = awaitResize(mark);
        }

        int[] after;
        try (TestMilestones.Step ignored = TestMilestones.step("program sees the new size")) {
            after = awaitSize(expected[1]);
        }

        assertTrue("the terminal was told a shorter PTY: " + expected[1] + " rows, then "
                + before[0], expected[1] < before[0]);
        assertNotNull("the program prints the new size: expected rows " + expected[1], after);
        assertEquals("the program sees the columns the app set",
                expected[0], after[1]);
    }

    /** The size the app asked Termux to set, as {@code {cols, rows}}. */
    private int[] awaitResize(long mark) throws Exception {
        boolean found = FileWatcher.await(events, () -> {
            try {
                return PTY_RESIZE.matcher(readFrom(mark)).find();
            } catch (IOException unreadable) {
                return false;
            }
        }, AWAIT);
        if (!found) {
            throw new AssertionError("the app never changed the PTY size: no pty-resize line");
        }
        Matcher resized = PTY_RESIZE.matcher(readFrom(mark));
        int[] last = null;
        while (resized.find()) {
            last = new int[] {Integer.parseInt(resized.group(1)),
                    Integer.parseInt(resized.group(2))};
        }
        assertNotNull("a PTY resize was written down", last);
        return last;
    }

    /**
     * The last size line the command printed, waiting for the one with
     * {@code rows} rows - or for any line when {@code rows} is negative.
     */
    private int[] awaitSize(int rows) throws Exception {
        FileWatcher.await(screen, () -> {
            int[] size = lastSize();
            return size != null && (rows < 0 || size[0] == rows);
        }, AWAIT);
        int[] size = lastSize();
        if (rows < 0 || (size != null && size[0] == rows)) {
            return size;
        }
        return null;
    }

    /** The newest {@code rows cols} the command printed, as {@code {rows, cols}}. */
    private int[] lastSize() {
        String text;
        try {
            text = readAll(screen);
        } catch (IOException unreadable) {
            return null;
        }
        Matcher size = SIZE.matcher(text);
        int[] last = null;
        while (size.find()) {
            last = new int[] {Integer.parseInt(size.group(1)),
                    Integer.parseInt(size.group(2))};
        }
        return last;
    }

    private String readAll(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] buffer = new byte[(int) in.length()];
            in.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    /** The part of the log written after this test started. */
    private String readFrom(long mark) throws IOException {
        if (!events.isFile()) {
            return "";
        }
        try (RandomAccessFile file = new RandomAccessFile(events, "r")) {
            long start = Math.min(mark, file.length());
            byte[] buffer = new byte[(int) (file.length() - start)];
            file.seek(start);
            file.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }
}
