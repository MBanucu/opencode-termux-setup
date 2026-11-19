package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertFalse;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Predicate;
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
 * opencode" opens a page where the choice between keeping and deleting the
 * user's data is made; both outcomes send their own command on Termux's
 * background runner, so this app stays in front and the command's output arrives
 * on the page that sent it. Whether the command actually
 * changed the system is then answered by "Check setup", which runs
 * {@code opencode --version} in Termux and reports the result back through a
 * {@code PendingIntent}. The suite therefore removes opencode from the device
 * and installs it again, asserting on the version each time.
 *
 * <p>Prerequisites on the device: Termux installed, {@code allow-external-apps=true}
 * in {@code ~/.termux/termux.properties}, network access, and this package
 * installed. Run with {@code test/android-e2e.sh}.
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

    /**
     * What "Check setup" prints, and nothing else. Matching the whole result is
     * deliberate: Termux also delivers the output of the visible install and
     * uninstall commands to this app, and install.sh ends by printing a version
     * line of its own, so a loose {@code contains("opencode v")} could pass on
     * the installer's log instead of on a check run afterwards.
     */
    private static final Pattern CHECK_REMOVED =
            Pattern.compile("(?s)^opencode is not installed\\n+\\nexit code 0$");
    private static final Pattern CHECK_INSTALLED =
            Pattern.compile("(?s)^opencode v\\S+\\n+\\nexit code 0$");

    /** What the box says before the first line of output arrives. */
    private static final String WAITING = "Running in Termux";

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

    /**
     * The uninstall page offers both outcomes and shows the command for each.
     *
     * <p>Nothing runs here: this is the page doing its job, including the fact
     * that the safe choice is the one already selected.
     */
    @Test
    public void cUninstallPageChoosesBetweenKeepingAndDeleting() throws Exception {
        device.findObject(By.text("Uninstall opencode")).click();
        assertTrue("the uninstall page opens",
                device.wait(Until.hasObject(By.text("Remove opencode?")), SHORT));

        UiObject2 keep = device.findObject(By.text("Keep my settings"));
        UiObject2 purge = device.findObject(By.text("Remove everything"));
        assertNotNull("keeping settings is offered", keep);
        assertNotNull("deleting everything is offered", purge);
        assertTrue("keeping settings is the default", keep.isChecked());
        assertFalse("deleting everything is not the default", purge.isChecked());

        UiObject2 preview = device.wait(Until.findObject(By.textContains("uninstall.sh")), SHORT);
        assertNotNull("the page shows the command it would run", preview);
        assertFalse("the default command keeps the data",
                preview.getText().contains("--purge"));
        UiObject2 keepDetail = device.findObject(By.textContains("~/.config/opencode"));
        assertNotNull("the page names the directories the default keeps", keepDetail);
        shot("03-uninstall-page.png");

        purge.click();
        assertTrue("deleting everything changes the command",
                device.wait(Until.hasObject(By.textContains("--purge")), SHORT));
        // Re-read the nodes: a cached UiObject2 would answer for the old state.
        assertTrue("the destructive choice is selected",
                device.findObject(By.text("Remove everything")).isChecked());
        assertFalse("the safe choice is deselected, not left on as well",
                device.findObject(By.text("Keep my settings")).isChecked());
        UiObject2 purgeDetail = device.findObject(By.textContains("connected again"));
        assertNotNull("the destructive choice says what it destroys", purgeDetail);
        shot("04-purge-option.png");

        keep.click();
        assertTrue("going back to the safe choice removes --purge",
                device.wait(Until.gone(By.textContains("--purge")), SHORT));

        device.pressBack();
        assertTrue("back returns to the main screen",
                device.wait(Until.hasObject(By.text("Install opencode")), SHORT));
        assertTrue("reading the page sends nothing", !device.hasObject(By.pkg(TERMUX)));
    }

    /**
     * Removes opencode for real and proves it is gone by asking Termux.
     *
     * <p>The page's default is used, so this is also the promise that a normal
     * uninstall keeps credentials: the command carries no {@code --purge}.
     */
    @Test
    public void dUninstallRemovesOpenCodeForReal() throws Exception {
        // Removing something that is not installed is a one-second repository
        // cleanup, not a removal, and its streamed output would be over before
        // the page could show it. A previous run can leave it gone, so the state
        // is restored first.
        String present = checkOnce();
        if (present == null || !CHECK_INSTALLED.matcher(present.trim()).matches()) {
            installThroughThePage();
        }
        device.findObject(By.text("Uninstall opencode")).click();
        assertTrue("the uninstall page opens",
                device.wait(Until.hasObject(By.text("Remove opencode?")), SHORT));

        UiObject2 remove = device.wait(Until.findObject(By.text("Remove opencode")), SHORT);
        assertNotNull("the page offers the action", remove);
        remove.click();

        // Nothing may be raised over this app: the command runs in Termux's
        // background runner and its output comes back to this page.
        assertTrue("the app stays in front",
                device.wait(Until.hasObject(By.pkg(PACKAGE)), SHORT));
        assertFalse("the Termux window was not brought forward",
                device.hasObject(By.pkg(TERMUX)));
        shot("05-uninstall-started.png");
        assertNotNull("the page shows Termux's output while the removal is still running",
                streamingText());

        String report = checkUntil(CHECK_REMOVED, UNINSTALL_BUDGET);
        assertTrue("the check that reported the removal ran in the app: " + report,
                device.hasObject(By.pkg(PACKAGE)));
        shot("06-uninstalled.png");
    }

    /**
     * Installs opencode for real, after the previous test removed it.
     *
     * <p>This is the whole point of the app: one tap, a genuine install in
     * Termux, and a version string coming back through RUN_COMMAND. It fails if
     * the install did not finish or did not produce a working binary.
     */
    @Test
    public void eInstallPutsItBackForReal() throws Exception {
        installThroughThePage();
    }

    /**
     * Installs from the main page through the consent page, checking that the
     * app is not pushed aside, that output streams while it runs, and that the
     * version comes back from Termux afterwards. Used by the install test and by
     * the uninstall test when it has to restore the state it removes.
     */
    private void installThroughThePage() throws Exception {
        bringToFront();
        device.findObject(By.text("Install opencode")).click();
        assertTrue("the install page opens",
                device.wait(Until.hasObject(By.text("Download about 70 MB?")), SHORT));

        UiObject2 accept = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^start setup$"))), SHORT);
        assertNotNull("the page offers to start the setup", accept);
        accept.click();

        assertTrue("the app stays in front",
                device.wait(Until.hasObject(By.pkg(PACKAGE)), SHORT));
        assertFalse("the Termux window was not brought forward",
                device.hasObject(By.pkg(TERMUX)));
        shot("07-install-started.png");
        assertNotNull("the page shows the install as it happens, not only at the end",
                streamingText());

        String report = checkUntil(CHECK_INSTALLED, INSTALL_BUDGET);
        assertTrue("the check that reported the version ran in the app: " + report,
                device.hasObject(By.pkg(PACKAGE)));
        shot("08-installed.png");
    }

    /**
     * The adb entry point runs the same commands as the UI.
     *
     * <p>{@code AdbCommandActivity} is exported for exactly this, and it answers
     * through {@code last-result.txt} in the app's external files directory,
     * which adb can read without root. Here the test reads the same file the
     * shell would, after starting the activity the way a script would.
     */
    @Test
    public void fAdbEndpointRunsTheSameCommands() throws Exception {
        File result = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "last-result.txt");
        assertTrue("old answer cleared", !result.exists() || result.delete());

        device.executeShellCommand("am start -W -n " + PACKAGE + "/.AdbCommandActivity"
                + " -e command check");
        String report = awaitFile(result,
                text -> CHECK_INSTALLED.matcher(text.trim()).matches(), 30_000);
        assertNotNull("adb check returns Termux's real answer, not an acknowledgement", report);

        device.executeShellCommand("am start -W -n " + PACKAGE + "/.AdbCommandActivity"
                + " -e command bogus");
        String error = awaitFile(result, text -> text.contains("unknown command"), 15_000);
        assertNotNull("an unknown command is refused with a reason", error);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Gets back to the main page the way a user does: back out of whichever page
     * is open. {@code am start} alone is not enough - when the activity is
     * already in this app's stack the system delivers the intent to it
     * (result code 3) instead of bringing the task forward, so the page never
     * changes.
     */
    private void bringToFront() throws Exception {
        for (int attempt = 0; attempt < 3 && !device.hasObject(By.text("Check setup")); attempt++) {
            device.pressBack();
            device.waitForIdle();
        }
        if (!device.hasObject(By.text("Check setup"))) {
            device.executeShellCommand("am start -W -n " + PACKAGE + "/.MainActivity");
        }
        assertTrue("the app is in the foreground",
                device.wait(Until.hasObject(By.text("Check setup")), 30_000));
    }

    /** The last result the app received from Termux, or null before the first. */
    private String resultText() {
        UiObject2 node = device.findObject(By.descContains("exit code"));
        return node == null ? null : node.getContentDescription();
    }

    /**
     * The terminal box once it holds real output that is not the final answer
     * yet. This is what makes the run streamed: the text appears while Termux
     * is still working, not in one block when it exits.
     */
    private String streamingText() {
        long deadline = System.currentTimeMillis() + POLL;
        while (System.currentTimeMillis() < deadline) {
            UiObject2 box = device.findObject(By.res(PACKAGE, "output"));
            if (box != null) {
                // The box is a WebView running xterm.js, so its screen text is
                // published as the node's content description.
                String text = box.getContentDescription();
                if (text != null && !text.trim().isEmpty()
                        && !text.startsWith(WAITING) && !text.contains("exit code")) {
                    return text;
                }
            }
        }
        return null;
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
            device.wait(Until.gone(By.desc(before)), POLL);
        }
        UiObject2 result = device.wait(Until.findObject(By.descContains("exit code")), POLL);
        return result == null ? null : result.getContentDescription();
    }

    /**
     * Asks Termux repeatedly until it reports exactly {@code wanted}. The asking
     * is needed rather than one long wait because a check answers immediately -
     * with the old version while the install is still downloading - so the only
     * honest signal is a check that has already started after the work finished.
     */
    private String checkUntil(Pattern wanted, long budget) throws Exception {
        long deadline = System.currentTimeMillis() + budget;
        String report = null;
        while (System.currentTimeMillis() < deadline) {
            report = checkOnce();
            if (report != null && wanted.matcher(report.trim()).matches()) {
                return report;
            }
        }
        fail("Termux never answered " + wanted + " within " + (budget / 1000)
                + "s; the last answer was: " + report);
        return report;
    }

    /**
     * Waits for a file to say something acceptable. This is the one place the
     * suite polls, because the answer is a file rather than a UI node: the adb
     * path has no accessibility tree to subscribe to.
     */
    private String awaitFile(File file, Predicate<String> acceptable, long budget)
            throws Exception {
        long deadline = System.currentTimeMillis() + budget;
        while (System.currentTimeMillis() < deadline) {
            String text = read(file);
            if (text != null && acceptable.test(text)) {
                return text;
            }
            Thread.sleep(250);
        }
        return null;
    }

    private String read(File file) {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        } catch (IOException unreadable) {
            return null;
        }
    }

    private void shot(String name) throws Exception {
        File dir = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "screenshots");
        assertTrue("screenshot directory", dir.isDirectory() || dir.mkdirs());
        assertTrue("screenshot " + name, device.takeScreenshot(new File(dir, name)));
    }
}
