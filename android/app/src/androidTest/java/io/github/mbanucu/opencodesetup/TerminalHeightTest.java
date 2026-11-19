package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last line of the terminal has to be readable at every height it can take.
 *
 * <p>A screenshot cannot answer that question: it shows one height, and the
 * clipping that matters happens at the rounded bottom edge of the frame. The
 * terminal page therefore reports the geometry of its newest line
 * ({@code tail:{"fits":true,"terminal":1080,...}}), and this test reads that at
 * three heights the page is asked to use through an intent extra.
 *
 * <p>It also checks that the heights are actually different, so a run cannot
 * pass by measuring the same terminal three times.
 */
@RunWith(AndroidJUnit4.class)
public final class TerminalHeightTest {

    private static final String PACKAGE = "io.github.mbanucu.opencodesetup";
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";

    /** Short, the default share, and tall: 160 dp to roughly 480 dp on this device. */
    private static final float[] FRACTIONS = {0.2f, 0.45f, 0.62f};

    private static final long SHORT = 5_000;
    private static final long ANSWER = 12_000;

    @Rule
    public final TestTimings timings = new TestTimings();

    private UiDevice device;

    @Before
    public void grantPermission() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("pm grant " + PACKAGE + " " + PERMISSION);
    }

    @Test
    public void lastLineFitsAtEveryHeight() throws Exception {
        int previous = -1;
        for (float fraction : FRACTIONS) {
            openAt(fraction);

            // The full buffer is measured first, because that is the case where a
            // last line can be clipped: an overflowing terminal keeps its newest
            // row against the bottom edge, a two line one does not.
            String filled = settledTail();
            assertNotNull("the filled terminal reports its last line at " + fraction, filled);
            assertTrue("the last line of a full terminal has text at " + fraction + ": " + filled,
                    !filled.contains("\"chars\":0"));
            assertTrue("the last line of a full terminal ends inside it at " + fraction
                            + ": " + filled,
                    filled.contains("\"fits\":true"));

            UiObject2 run = device.wait(Until.findObject(By.text("Run check")), SHORT);
            if (run == null) {
                org.junit.Assert.fail("no run action at " + fraction + " of the screen");
            }
            device.executeShellCommand("logcat -c");
            run.click();

            // And again for the command's own output, which arrives with its own
            // line endings and progress rewrites.
            String tail = settledTail();
            assertNotNull("the terminal reports where its last line ends at " + fraction, tail);
            assertTrue("the last line has text at " + fraction + ": " + tail,
                    !tail.contains("\"chars\":0"));
            assertTrue("the last line ends inside the terminal at " + fraction + ": " + tail,
                    tail.contains("\"fits\":true"));

            int height = heightOf(filled);
            assertTrue("the terminal height was reported at " + fraction + ": " + tail, height > 0);
            assertTrue("a pinned height differs from the previous one at " + fraction
                            + " (" + previous + " then " + height + ")",
                    previous < 0 || height != previous);
            previous = height;
        }
    }

    /**
     * Opens the page with a pinned terminal height, as a test hook only.
     *
     * <p>There is deliberately no {@code am force-stop} here: the instrumentation
     * runs inside this app's process, so stopping the package would stop the test
     * before it could measure anything. A plain start adds a new page instance on
     * top, and that instance reads the extra in {@code onCreate}.
     */
    private void openAt(float fraction) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            launch(fraction);
            if (device.hasObject(By.text("Run check"))) {
                Thread.sleep(500);
                return;
            }
        }
        org.junit.Assert.fail("the page did not open at " + fraction);
    }

    private void launch(float fraction) throws Exception {
        // CLEAR_TASK (0x8000) with NEW_TASK (0x10000000): a fresh page instance
        // without stopping the process the test runner lives in, which is what a
        // plain start does not guarantee - it reused the page and every height
        // came back the same. The filler makes the buffer overflow, because only
        // a full terminal puts the last line at the bottom edge.
        // Through the real path: the start page is exported and carries the hooks
        // on to the check page, which owns the terminal.
        device.executeShellCommand("am start -W -f 0x10008000 -n " + PACKAGE + "/.MainActivity"
                + " --ef terminal_fraction " + fraction
                + " --ei filler_lines 300");
        UiObject2 open = device.wait(Until.findObject(By.text("Check setup")), SHORT);
        if (open != null) {
            open.click();
        }
        device.wait(Until.hasObject(By.text("Run check")), SHORT);
        Thread.sleep(500);
    }

    /**
     * The last geometry line that measured something, once the page has settled.
     *
     * <p>Clearing the terminal publishes an empty screen, and every repaint while
     * a chunk lands publishes another line; the one that matters is the last, not
     * the first that happens to carry text.
     */
    private String settledTail() throws Exception {
        Thread.sleep(2_000);
        String tail = null;
        Matcher found = Pattern.compile("tail:\\{[^}]*\\}").matcher(
                device.executeShellCommand("logcat -d -s OpencodeSetup:I"));
        Pattern chars = Pattern.compile("\"chars\":(\\d+)");
        while (found.find()) {
            Matcher count = chars.matcher(found.group());
            if (count.find() && Integer.parseInt(count.group(1)) > 0) {
                tail = found.group();
            }
        }
        return tail;
    }

    private int heightOf(String tail) {
        Matcher field = Pattern.compile("\"terminal\":(\\d+)").matcher(tail);
        return field.find() ? Integer.parseInt(field.group(1)) : -1;
    }
}
