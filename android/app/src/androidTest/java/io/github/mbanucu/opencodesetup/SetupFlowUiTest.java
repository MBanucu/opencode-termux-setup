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

import java.util.regex.Pattern;

/**
 * The UI group of the setup flow: pages that need neither opencode nor the
 * installer.
 *
 * <p>These three cases read the main screen, open the install consent dialog and
 * cancel it, and read the uninstall page without running anything. They need
 * Termux installed (the package-visibility query and the "no Termux window was
 * opened" assertions) but never download or run opencode, so a hosted API 35
 * x86_64 emulator can run them. That is the subset
 * {@code .github/workflows/emulator-ui.yml} runs as the {@code ui-only} check.
 *
 * <p>{@code test/android-e2e.sh --ui-only} runs this class, the pty group and
 * {@link PermissionDeniedTest}; the real install group is
 * {@link SetupFlowInstallTest} and is refused in that mode.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class SetupFlowUiTest extends SetupFlowTestBase {

    @Test
    public void aMainScreenOffersTheFourActions() throws Exception {
        assertNotNull("install", device.findObject(By.text("Install opencode")));
        assertNotNull("uninstall", device.findObject(By.text("Uninstall opencode")));
        assertNotNull("check", device.findObject(By.text("Check setup")));
        assertNotNull("open Termux", device.findObject(By.text("Open Termux")));
        milestone("main");
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
        milestone("consent");

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
        milestone("uninstall-page");

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
        milestone("purge-option");

        keep.click();
        assertTrue("going back to the safe choice removes --purge",
                device.wait(Until.gone(By.textContains("--purge")), SHORT));

        device.pressBack();
        assertTrue("back returns to the main screen",
                device.wait(Until.hasObject(By.text("Install opencode")), SHORT));
        assertTrue("reading the page sends nothing", !device.hasObject(By.pkg(TERMUX)));
    }
}
