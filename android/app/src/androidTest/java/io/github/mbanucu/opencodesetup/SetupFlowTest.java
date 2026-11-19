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
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Drives the real app on a device through accessibility, not through
 * screenshots and not through sleeps.
 *
 * <p>UI Automator subscribes to the accessibility event stream, so every wait
 * here resolves the moment the UI changes: {@code Until.hasObject} and
 * {@code Until.findObject} return as soon as a matching node is published, and
 * {@code Until.gone} fires when it is removed. There is no polling loop.
 *
 * <p>The Termux side is real. "Check setup" leaves the app as a RUN_COMMAND and
 * the {@code RunCommandReceiver} broadcast has to arrive before the output
 * assertion can pass. "Install opencode" and "Uninstall opencode" go out on the
 * visible {@code terminal-session} runner, so for them the event that proves the
 * handoff is Termux's own window taking the foreground.
 *
 * <p>Prerequisites on the device: Termux installed, {@code allow-external-apps=true}
 * in {@code ~/.termux/termux.properties}, and this package installed. Run with
 * {@code test/android-e2e.sh}.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class SetupFlowTest {

    private static final String PACKAGE = "io.github.mbanucu.opencodesetup";
    private static final String TERMUX = "com.termux";
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final long SHORT = 10_000;
    private static final long LONG = 120_000;

    private UiDevice device;

    @Before
    public void openApp() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("pm grant " + PACKAGE + " " + PERMISSION);
        device.executeShellCommand("am start -W -n " + PACKAGE + "/.MainActivity");
        assertTrue("the app starts and sees Termux",
                device.wait(Until.hasObject(By.text("Termux is installed.")), 20_000));
    }

    @Test
    public void aMainScreenOffersTheFourActions() throws Exception {
        assertNotNull("install", device.findObject(By.text("Install opencode")));
        assertNotNull("uninstall", device.findObject(By.text("Uninstall opencode")));
        assertNotNull("check", device.findObject(By.text("Check setup")));
        assertNotNull("open Termux", device.findObject(By.text("Open Termux")));
        shot("01-main.png");
    }

    @Test
    public void bInstallConsentDialogStatesTheDownload() throws Exception {
        device.findObject(By.text("Install opencode")).click();
        assertTrue("the consent dialog appears",
                device.wait(Until.hasObject(By.text("Download about 70 MB?")), SHORT));

        // The dialog is read through accessibility: the text node is what a
        // screen reader would announce, and it is what the test asserts on.
        UiObject2 message = device.wait(
                Until.findObject(By.textContains("bd-loser/opencode-bionic")), SHORT);
        assertNotNull("the dialog names the repository it will add", message);
        String text = message.getText();
        assertTrue("the download and installed sizes are stated",
                text.contains("70 MB") && text.contains("209 MB"));
        assertTrue("the command it will run is shown", text.contains("install.sh"));
        assertTrue("the third-party build is named so consent is informed",
                text.contains("third-party"));
        shot("02-consent.png");

        // Platform dialog buttons are rendered uppercase, so match the label
        // the accessibility tree reports rather than the string resource.
        UiObject2 cancel = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^cancel$"))), SHORT);
        assertNotNull("the dialog offers a cancel button", cancel);
        cancel.click();
        assertTrue("cancel closes the dialog and runs nothing",
                device.wait(Until.gone(By.text("Download about 70 MB?")), SHORT));
        assertTrue("no Termux window was opened by cancelling",
                !device.hasObject(By.pkg(TERMUX)));
    }

    @Test
    public void cUninstallConsentDialogStatesWhatIsRemoved() throws Exception {
        device.findObject(By.text("Uninstall opencode")).click();
        assertTrue("the uninstall dialog appears",
                device.wait(Until.hasObject(By.text("Remove opencode?")), SHORT));

        UiObject2 message = device.wait(
                Until.findObject(By.textContains("uninstall.sh")), SHORT);
        assertNotNull("the dialog shows the command it will run", message);
        String text = message.getText();
        assertTrue("it says the package and the repository go",
                text.contains("opencode package") && text.contains("APT repository"));
        assertTrue("it says credentials and sessions are kept",
                text.contains("kept") && text.contains("~/.config/opencode"));
        shot("03-uninstall-consent.png");

        UiObject2 cancel = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^cancel$"))), SHORT);
        assertNotNull("the dialog offers a cancel button", cancel);
        cancel.click();
        assertTrue("cancel closes the dialog and removes nothing",
                device.wait(Until.gone(By.text("Remove opencode?")), SHORT));
        assertTrue("no Termux window was opened by cancelling",
                !device.hasObject(By.pkg(TERMUX)));
    }

    @Test
    public void dInstallHandsTheCommandToTermux() throws Exception {
        device.findObject(By.text("Install opencode")).click();
        assertTrue("the consent dialog appears",
                device.wait(Until.hasObject(By.text("Download about 70 MB?")), SHORT));

        UiObject2 accept = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^start setup$"))), SHORT);
        assertNotNull("the dialog offers to start the setup", accept);
        accept.click();

        // Approving has to leave this app and reach Termux: the install runs on
        // the visible runner, so Termux's window coming to the front is the
        // accessibility event that proves the RUN_COMMAND was accepted. The
        // reinstall over the installed package is idempotent and downloads
        // nothing.
        assertTrue("the setup is handed to the Termux window",
                device.wait(Until.hasObject(By.pkg(TERMUX)), LONG));
        shot("04-install-handoff.png");
    }

    @Test
    public void eCheckReportsThroughTermux() throws Exception {
        device.findObject(By.text("Check setup")).click();

        // Event-driven: the output view only exists after the PendingIntent the
        // receiver answers comes back from Termux, so this wait is the assertion
        // that the whole round trip happened.
        UiObject2 output = device.wait(Until.findObject(By.textContains("exit code")), LONG);
        assertNotNull("Termux returns the check result to the app", output);

        String report = output.getText();
        assertTrue("the check exited 0", report.contains("exit code 0"));
        assertTrue("the report carries one of the two expected outcomes",
                report.contains("opencode v") || report.contains("opencode is not installed"));
        assertTrue("a background command stays in the app window",
                device.hasObject(By.pkg(PACKAGE)));
        shot("05-check.png");
    }

    private void shot(String name) throws Exception {
        File dir = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "screenshots");
        assertTrue("screenshot directory", dir.isDirectory() || dir.mkdirs());
        assertTrue("screenshot " + name, device.takeScreenshot(new File(dir, name)));
    }
}
