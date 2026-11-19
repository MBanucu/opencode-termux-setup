package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
 * {@code Until.gone} fires when it is removed.
 *
 * <p>The Termux side is real, and it is not simulated anywhere. "Uninstall
 * opencode" and "Install opencode" go out on the visible {@code terminal-session}
 * runner, so the event that proves the handoff is Termux's own window taking the
 * foreground; whether the command actually changed the system is then answered
 * by "Check setup", which runs {@code opencode --version} in Termux through the
 * background {@code app-shell} runner and reports the result back through a
 * {@code PendingIntent}. The suite therefore removes opencode from the device
 * and installs it again, asserting on the version each time.
 *
 * <p>Prerequisites on the device: Termux installed, {@code allow-external-apps=true}
 * in {@code ~/.termux/termux.properties}, network access, and this package
 * installed. Run with {@code test/android-e2e.sh}; the run takes as long as a
 * real 70 MB install does.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class SetupFlowTest {

    private static final String PACKAGE = "io.github.mbanucu.opencodesetup";
    private static final String TERMUX = "com.termux";
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final long SHORT = 10_000;
    private static final long LONG = 120_000;
    /** Time to wait for one check to come back from Termux. */
    private static final long POLL = 30_000;
    /** A removal is one apt transaction, but it waits for the apt lock. */
    private static final long UNINSTALL_BUDGET = 300_000;
    /** A reinstall downloads ~70 MB, so it gets a download-sized budget. */
    private static final long INSTALL_BUDGET = 900_000;

    private UiDevice device;

    @Before
    public void openApp() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("pm grant " + PACKAGE + " " + PERMISSION);
        bringToFront();
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

    /**
     * Removes opencode for real and proves it is gone by asking Termux.
     *
     * <p>Nothing here is faked: the button sends {@code uninstall.sh}, Termux
     * runs it in its own window, and the assertion is that a later
     * {@code opencode --version} in Termux no longer finds a binary.
     */
    @Test
    public void dUninstallRemovesOpenCodeForReal() throws Exception {
        device.findObject(By.text("Uninstall opencode")).click();
        assertTrue("the uninstall dialog appears",
                device.wait(Until.hasObject(By.text("Remove opencode?")), SHORT));

        UiObject2 remove = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^remove$"))), SHORT);
        assertNotNull("the dialog offers the destructive action", remove);
        remove.click();

        assertTrue("the removal is handed to the Termux window",
                device.wait(Until.hasObject(By.pkg(TERMUX)), LONG));
        shot("04-uninstall-running.png");

        String report = checkUntil("opencode is not installed", UNINSTALL_BUDGET);
        assertTrue("the check that reported the removal ran in the app",
                device.hasObject(By.pkg(PACKAGE)));
        shot("05-uninstalled.png");
        assertTrue("nothing was left running: " + report, report.contains("exit code"));
    }

    /**
     * Installs opencode for real, after the previous test removed it.
     *
     * <p>This is the whole point of the app: one tap, a genuine 70 MB install in
     * Termux, and a version string coming back through RUN_COMMAND. It fails if
     * the install did not finish or did not produce a working binary.
     */
    @Test
    public void eInstallPutsItBackForReal() throws Exception {
        device.findObject(By.text("Install opencode")).click();
        assertTrue("the consent dialog appears",
                device.wait(Until.hasObject(By.text("Download about 70 MB?")), SHORT));

        UiObject2 accept = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^start setup$"))), SHORT);
        assertNotNull("the dialog offers to start the setup", accept);
        accept.click();

        assertTrue("the setup is handed to the Termux window",
                device.wait(Until.hasObject(By.pkg(TERMUX)), LONG));
        shot("06-install-running.png");

        String report = checkUntil("opencode v", INSTALL_BUDGET);
        assertTrue("the install left a runnable binary: " + report,
                report.contains("exit code 0"));
        shot("07-installed.png");
    }

    // --- helpers -------------------------------------------------------------

    private void bringToFront() throws Exception {
        device.executeShellCommand("am start -W -n " + PACKAGE + "/.MainActivity");
        assertTrue("the app is in the foreground",
                device.wait(Until.hasObject(By.text("Check setup")), 30_000));
    }

    /** The last result the app received from Termux, or null before the first. */
    private String resultText() {
        UiObject2 node = device.findObject(By.textContains("exit code"));
        return node == null ? null : node.getText();
    }

    /**
     * Runs "Check setup" once and waits for a result newer than the one on
     * screen. The old node going away is the event; no sleep guesses how long a
     * Termux command takes.
     */
    private String checkOnce() throws Exception {
        bringToFront();
        String before = resultText();

        UiObject2 check = device.wait(Until.findObject(By.text("Check setup")), SHORT);
        assertNotNull("the check button is reachable", check);
        check.click();

        if (before != null) {
            device.wait(Until.gone(By.text(before)), POLL);
        }
        UiObject2 result = device.wait(Until.findObject(By.textContains("exit code")), POLL);
        return result == null ? null : result.getText();
    }

    /**
     * Asks Termux repeatedly until it reports {@code needle}. The asking is
     * needed rather than one long wait because a check answers immediately -
     * with the old version while the install is still downloading - so the only
     * honest signal is a check that has already started after the work finished.
     */
    private String checkUntil(String needle, long budget) throws Exception {
        long deadline = System.currentTimeMillis() + budget;
        String report = null;
        while (System.currentTimeMillis() < deadline) {
            report = checkOnce();
            if (report != null && report.contains(needle)) {
                return report;
            }
        }
        fail("Termux never reported '" + needle + "' within " + (budget / 1000)
                + "s; the last answer was: " + report);
        return report;
    }

    private void shot(String name) throws Exception {
        File dir = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "screenshots");
        assertTrue("screenshot directory", dir.isDirectory() || dir.mkdirs());
        assertTrue("screenshot " + name, device.takeScreenshot(new File(dir, name)));
    }
}
