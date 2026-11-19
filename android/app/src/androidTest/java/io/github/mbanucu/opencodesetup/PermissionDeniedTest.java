package io.github.mbanucu.opencodesetup;

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

import java.util.regex.Pattern;

/**
 * The denial path, in a run of its own.
 *
 * <p>Revoking a dangerous permission kills the app process, and the test runner
 * lives inside that process, so this class cannot share a run with the others.
 * The runner script revokes the permission before starting it and grants it
 * again afterwards.
 *
 * <p>Everything is event driven: the permission dialog is awaited as an
 * accessibility node, and so is the explanation the app shows after a denial.
 */
@RunWith(AndroidJUnit4.class)
public final class PermissionDeniedTest {

    private static final String PACKAGE = "io.github.mbanucu.opencodesetup";
    private static final long SHORT = 10_000;

    private UiDevice device;

    /** Logs this test's duration; the harness keeps those in its database. */
    @Rule
    public final TestTimings timings = new TestTimings();

    @Before
    public void openApp() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // No grant on purpose: this run tests what happens without it.
        InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(
                new Intent(Intent.ACTION_MAIN)
                        .setClassName(PACKAGE, PACKAGE + ".MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("the app starts",
                device.wait(Until.hasObject(By.text("Check setup")), 20_000));
    }

    @Test
    public void asksForThePermissionAndExplainsADenial() throws Exception {
        device.findObject(By.text("Check setup")).click();
        // The command runs from its own page now, so that is where Android asks.
        UiObject2 run = device.wait(Until.findObject(By.text("Run check")), SHORT);
        assertNotNull("the check page offers to run the check", run);
        run.click();

        UiObject2 allow = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)allow"))), 15_000);
        assertNotNull("Android asks for Termux's permission before anything is sent", allow);

        device.pressBack();
        // The explanation is printed on the page, not in a dialog.
        assertTrue("the app explains the denial instead of failing silently",
                device.wait(Until.hasObject(By.textContains("Permission needed")), SHORT));
        assertTrue("the explanation points at the app settings",
                device.hasObject(By.text(Pattern.compile("(?i)^open settings$"))));
    }
}
