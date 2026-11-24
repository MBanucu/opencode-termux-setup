package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;

/**
 * The install group: removes and reinstalls the real opencode package.
 *
 * <p>This is the group a hosted x86_64 emulator cannot run. opencode-bionic is
 * an aarch64 Bionic build and {@code install.sh} refuses any other
 * {@code uname -m}, so the download, the install and the {@code --version}
 * check need a real arm64 Termux. Run it with {@code test/android-e2e.sh}; a
 * UI-only run refuses to select this class.
 *
 * <p>The three cases keep the order the old single class used:
 * uninstall, install, adb parity. The uninstall restores a missing package
 * before it removes anything, so the group is safe to start from either state.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class SetupFlowInstallTest extends SetupFlowTestBase {

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
        String present;
        try (TestMilestones.Step ignored = TestMilestones.step("ask whether it is there")) {
            present = checkOnce();
        }
        if (present == null || !CHECK_INSTALLED.matcher(present.trim()).matches()) {
            installThroughThePage();
        }
        try (TestMilestones.Step ignored = TestMilestones.step("open the uninstall page")) {
            device.findObject(By.text("Uninstall opencode")).click();
            assertTrue("the uninstall page opens",
                    device.wait(Until.hasObject(By.text("Remove opencode?")), SHORT));
        }

        UiObject2 remove = device.wait(Until.findObject(By.text("Remove opencode")), SHORT);
        assertNotNull("the page offers the action", remove);
        long mark = eventsMark();
        remove.click();

        // Nothing may be raised over this app: the command runs in Termux's
        // background runner and its output comes back to this page.
        assertTrue("the app stays in front",
                device.wait(Until.hasObject(By.pkg(PACKAGE)), SHORT));
        assertFalse("the Termux window was not brought forward",
                device.hasObject(By.pkg(TERMUX)));
        milestone("uninstall-started");
        // A still screen from here to the end of the block is the filesystem
        // working, not the app hesitating: the harness reports it as work.
        try (TestMilestones.Busy busy = TestMilestones.busy("uninstall");
             TestMilestones.Step ignored = TestMilestones.step("uninstall command")) {
            assertStreamed(mark,
                    "the page shows Termux's output while the removal is still running");
            // Ask only once the removal is over: a check sent while it runs answers
            // with the old state, and the two results race in last-result.txt.
            waitForResult(mark, STALL);
        }

        String report;
        try (TestMilestones.Step ignored = TestMilestones.step("check that it is gone")) {
            report = checkUntil(CHECK_REMOVED, 60_000);
        }
        assertTrue("the check that reported the removal ran in the app: " + report,
                device.hasObject(By.pkg(PACKAGE)));
        milestone("uninstalled");
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
     * The adb entry point runs the same commands as the UI.
     *
     * <p>{@code AdbCommandActivity} is exported for exactly this, and it answers
     * through {@code last-result.txt} in the app's external files directory,
     * which adb can read without root. Here the test reads the same file the
     * shell would, after starting the activity the way a script would.
     */
    @Test
    public void fAdbEndpointRunsTheSameCommands() throws Exception {
        File result = resultFile();
        assertTrue("old answer cleared", !result.exists() || result.delete());

        device.executeShellCommand("am start -W -n " + PACKAGE + "/.AdbCommandActivity"
                + " -e command check");
        String report = awaitFile(result,
                text -> CHECK_INSTALLED.matcher(text.trim()).matches(), 15_000);
        assertNotNull("adb check returns Termux's real answer, not an acknowledgement", report);

        device.executeShellCommand("am start -W -n " + PACKAGE + "/.AdbCommandActivity"
                + " -e command bogus");
        String error = awaitFile(result, text -> text.contains("unknown command"), 8_000);
        assertNotNull("an unknown command is refused with a reason", error);
    }
}
