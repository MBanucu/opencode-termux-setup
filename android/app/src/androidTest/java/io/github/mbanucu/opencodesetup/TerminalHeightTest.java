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
 * terminal page reports the geometry of its newest line
 * ({@code tail:{"fits":true,"terminal":900,...}}), and this test reads it at three
 * heights the page is asked to use through an intent extra.
 *
 * <p>The path is the real one: start page, check page, then the terminal page that
 * runs the command. The start page carries the height and a filler so the buffer
 * overflows, and the terminal page receives both.
 */
@RunWith(AndroidJUnit4.class)
public final class TerminalHeightTest {

    private static final String PACKAGE = "io.github.mbanucu.opencodesetup";
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";

    /** Short, the default full-screen page, and a pinned tall one. */
    private static final float[] FRACTIONS = {0.2f, 0.45f, 0.62f};

    private static final long SHORT = 5_000;

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
            openFilledTerminalAt(fraction);

            // The full buffer is what matters: an overflowing terminal keeps its
            // newest row against the bottom edge, a two line one does not.
            String tail = settledTail();
            assertNotNull("the filled terminal reports its last line at " + fraction, tail);
            assertTrue("the last line has text at " + fraction + ": " + tail,
                    !tail.contains("\"chars\":0"));
            assertTrue("the last line ends inside the terminal at " + fraction + ": " + tail,
                    tail.contains("\"fits\":true"));

            int height = heightOf(tail);
            assertTrue("the terminal height was reported at " + fraction + ": " + tail, height > 0);
            assertTrue("a pinned height differs from the previous one at " + fraction
                            + " (" + previous + " then " + height + ")",
                    previous < 0 || height != previous);
            previous = height;
        }
    }

    /**
     * Drives the app the way a user does, with the test hooks on the start page.
     *
     * <p>{@code CLEAR_TASK} gives a fresh page without stopping the process the
     * test runner lives in, and the filler makes the terminal page write 300 lines
     * instead of running a two line command that would clear them.
     */
    private void openFilledTerminalAt(float fraction) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            device.executeShellCommand("am start -W -f 0x10008000 -n " + PACKAGE + "/.MainActivity"
                    + " --ef terminal_fraction " + fraction
                    + " --ei filler_lines 300");

            UiObject2 open = device.wait(Until.findObject(By.text("Check setup")), SHORT);
            if (open == null) {
                continue;
            }
            open.click();

            device.executeShellCommand("logcat -c");
            UiObject2 run = device.wait(Until.findObject(By.text("Run check")), SHORT);
            if (run == null) {
                continue;
            }
            run.click();
            device.wait(Until.hasObject(By.text("Copy output")), SHORT);
            return;
        }
        org.junit.Assert.fail("the terminal page did not open at " + fraction);
    }

    /**
     * The last geometry line that measured something, once the page has settled.
     *
     * <p>Clearing the terminal publishes an empty screen, and every repaint while
     * a chunk lands publishes another line; the one that matters is the last.
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
