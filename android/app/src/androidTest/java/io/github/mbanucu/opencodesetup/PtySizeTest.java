package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

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
 * The command must run under a PTY that is as wide as the terminal showing it.
 *
 * <p>Termux runs the command under a pseudo terminal, and a program that redraws
 * a progress line with a carriage return formats that line to the PTY's width.
 * {@code script} leaves the PTY at 80x24, while this terminal fits about 44
 * columns on a phone: the progress line then wraps, the carriage return goes to
 * the start of the wrapped row, and the fragment above it stays on screen. That
 * is what made an install look like a wall of "0%", "50%", "50%" lines.
 *
 * <p>The test is event driven: the app writes both numbers into its own event
 * log, one when it sends the command ({@code sent ... pty=NNxNN}) and one from
 * the terminal's own report ({@code "cols":NN,"rows":NN}), so it waits for those
 * lines to appear rather than sleeping a guessed amount of time.
 */
@RunWith(AndroidJUnit4.class)
public final class PtySizeTest {

    private static final String PACKAGE = "io.github.mbanucu.opencodesetup";
    private static final long SHORT = 10_000;
    private static final long AWAIT = 25_000;

    /** What the app asked Termux to run: the PTY size it set. */
    private static final Pattern SENT = Pattern.compile("sent Check .* pty=(\\d+)x(\\d+)");

    /** What the terminal says it is showing. */
    private static final Pattern REPORTED = Pattern.compile("\"cols\":(\\d+),\"rows\":(\\d+)");

    @Rule
    public final TestTimings timings = new TestTimings();

    private UiDevice device;
    private File events;

    @Before
    public void openApp() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        events = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null), "events.log");
        InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(
                new Intent(Intent.ACTION_MAIN)
                        .setClassName(PACKAGE, PACKAGE + ".MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("the app starts",
                device.wait(Until.hasObject(By.text("Check setup")), 20_000));
    }

    @Test
    public void theCommandRunsUnderAptyAsWideAsTheTerminal() throws Exception {
        long mark = events.length();

        device.findObject(By.text("Check setup")).click();
        UiObject2 run = device.wait(Until.findObject(By.text("Run check")), SHORT);
        assertNotNull("the check page offers to run the check", run);
        run.click();

        Matcher sent = SENT.matcher(await(mark, SENT));
        assertTrue("the app reports the PTY it asked Termux for", sent.find());
        int ptyCols = Integer.parseInt(sent.group(1));
        int ptyRows = Integer.parseInt(sent.group(2));

        Matcher reported = REPORTED.matcher(await(mark, REPORTED));
        assertTrue("the terminal reports the size it is showing", reported.find());

        assertTrue("a real width reached Termux, not the 80x24 default", ptyCols > 20);
        assertEquals("the PTY is as wide as the terminal",
                Integer.parseInt(reported.group(1)), ptyCols);
        assertEquals("the PTY has as many rows as the terminal",
                Integer.parseInt(reported.group(2)), ptyRows);
    }

    /** Waits for a line the app itself publishes, instead of sleeping. */
    private String await(long mark, Pattern pattern) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT;
        String seen = "";
        while (System.currentTimeMillis() < deadline) {
            seen = readFrom(mark);
            if (pattern.matcher(seen).find()) {
                return seen;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no line matching " + pattern + " appeared in events.log");
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
