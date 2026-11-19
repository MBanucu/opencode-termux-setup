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

            UiObject2 check = device.wait(Until.findObject(By.text("Check setup")), SHORT);
            if (check == null) {
                org.junit.Assert.fail("no check button at " + fraction + " of the screen");
            }
            device.executeShellCommand("logcat -c");
            check.click();

            String tail = awaitTail(ANSWER);
            assertNotNull("the terminal reports where its last line ends at " + fraction, tail);
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

    /** Restarts the page with a pinned terminal height, as a test hook only. */
    private void openAt(float fraction) throws Exception {
        device.executeShellCommand("am force-stop " + PACKAGE);
        device.executeShellCommand("am start -W -n " + PACKAGE + "/.MainActivity"
                + " --ef terminal_fraction " + fraction);
    }

    /** The geometry line the page logs once its text changes. */
    private String awaitTail(long budget) throws Exception {
        long deadline = System.currentTimeMillis() + budget;
        while (System.currentTimeMillis() < deadline) {
            String log = device.executeShellCommand("logcat -d -s OpencodeSetup:I");
            Matcher tail = Pattern.compile("tail:\\{[^}]*\\}").matcher(log);
            if (tail.find()) {
                return tail.group();
            }
            Thread.sleep(250);
        }
        return null;
    }

    private int heightOf(String tail) {
        Matcher field = Pattern.compile("\"terminal\":(\\d+)").matcher(tail);
        return field.find() ? Integer.parseInt(field.group(1)) : -1;
    }
}
