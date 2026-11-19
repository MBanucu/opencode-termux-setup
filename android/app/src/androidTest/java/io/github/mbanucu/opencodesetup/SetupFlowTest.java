package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;
import java.util.regex.Matcher;
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
    /** A screen change: a page opens, a button appears. */
    private static final long SHORT = 5_000;
    private static final long LONG = 120_000;
    /** One Termux round trip; a check answers in about a second. */
    private static final long POLL = 12_000;
    /** The first chunk of a command that is still running. */
    private static final long STREAM = 8_000;
    /** Between two checks while waiting for a long command to finish. */
    private static final long RETRY = 1_000;
    /**
     * How long a running command may produce nothing at all.
     *
     * <p>A ~70 MB download takes as long as the network takes, so there is no
     * honest duration to assert on: the test watches the download and fails on
     * silence, not on slowness.
     */
    private static final long STALL = 60_000;

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

    private UiDevice device;

    /** Logs this test's duration; the harness keeps those in its database. */
    @Rule
    public final TestTimings timings = new TestTimings();

    /**
     * Once for the class, not once per test: a shell round trip per method was
     * several seconds over the run, and the grant does not change in between.
     */
    @BeforeClass
    public static void grantThePermission() throws Exception {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("pm grant " + PACKAGE + " " + PERMISSION);
    }

    @Before
    public void openApp() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (TestMilestones.Step ignored = TestMilestones.step("open the app")) {
            bringToFront();
        }
    }

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
     * Installs from the main page through the consent page, checking that the
     * app is not pushed aside, that output streams while it runs, and that the
     * version comes back from Termux afterwards. Used by the install test and by
     * the uninstall test when it has to restore the state it removes.
     */
    private void installThroughThePage() throws Exception {
        bringToFront();
        UiObject2 install = device.findObject(By.text("Install opencode"));
        if (install == null) {
            fail("the install action is not on screen: " + diagnosis());
        }
        try (TestMilestones.Step ignored = TestMilestones.step("open the install page")) {
            install.click();
            assertTrue("the install page opens",
                    device.wait(Until.hasObject(By.text("Download about 70 MB?")), SHORT));
        }

        UiObject2 accept = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^start setup$"))), SHORT);
        assertNotNull("the page offers to start the setup", accept);
        long mark = eventsMark();
        accept.click();

        assertTrue("the app stays in front",
                device.wait(Until.hasObject(By.pkg(PACKAGE)), SHORT));
        assertFalse("the Termux window was not brought forward",
                device.hasObject(By.pkg(TERMUX)));
        milestone("install-started");
        try (TestMilestones.Busy busy = TestMilestones.busy("install");
             TestMilestones.Step ignored = TestMilestones.step("install command")) {
            assertStreamed(mark,
                    "the page shows the install as it happens, not only at the end");
            waitForResult(mark, STALL);
        }

        String report;
        try (TestMilestones.Step ignored = TestMilestones.step("check that it is installed")) {
            report = checkUntil(CHECK_INSTALLED, 60_000);
        }
        assertTrue("the check that reported the version ran in the app: " + report,
                device.hasObject(By.pkg(PACKAGE)));
        milestone("installed");
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

    // --- helpers -------------------------------------------------------------

    /**
     * Gets back to the main page by closing the app and opening it again.
     *
     * <p>Pressing back until the stack emptied was slow and ended somewhere
     * different every time - a page deeper in the app, or the launcher with the
     * app gone, which is not a state the next action can start from.
     * {@code CLEAR_TASK} finishes every page of the task and starts the main one
     * fresh. Plain {@code am start} is not enough: when the activity is already
     * in the stack the system delivers the intent to it instead of bringing the
     * task forward, so the page never changes.
     */
    private void bringToFront() throws Exception {
        // Already there: the actions are what the next step needs, so a lookup
        // that finds them is cheaper than reopening the page and waiting for it
        // to be laid out. A lookup that finds nothing also says the app is not
        // the page in front, which is exactly when reopening it is right - the
        // app's own log cannot see a window that something else has covered.
        if (!device.hasObject(By.text("Check setup"))) {
            long mark = eventsMark();
            // Reopened, not restarted: CLEAR_TOP finishes whatever pages sit
            // above the main one, and SINGLE_TOP hands the intent to the
            // instance already there instead of rebuilding it.
            InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(PACKAGE, PACKAGE + ".MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            // The app says when the page has been laid out; the wait after it is
            // for accessibility to see the actions it has just laid out.
            awaitPage(mark, "MainActivity", SHORT);
        }
        assertTrue("the app is in the foreground, with its actions in view: " + diagnosis(),
                actionsVisible());
    }

    /** Waits for the app to say a page has been laid out, read from its own log. */
    private void awaitPage(long mark, String name, long budget) throws Exception {
        String wanted = "page:" + name + " ready";
        if (!FileWatcher.await(eventsFile(), () -> eventsSince(mark).contains(wanted), budget)) {
            fail("the " + name + " page never said it was laid out: " + diagnosis());
        }
    }

    /**
     * True when both actions are on screen, which is what the next step needs.
     *
     * <p>Nothing here swipes any more. The swipes existed because a terminal on
     * the same page can scroll the actions out of the hierarchy, but the main
     * page has no terminal: its actions sit at the top of the page. A swipe at a
     * page the accessibility tree had not reached yet moved the screen for
     * nobody, and the recording showed it as motion with no effect.
     */
    private boolean actionsVisible() {
        // Two lookups, no idle wait: the page has already said it is laid out,
        // and waiting for the accessibility stream to settle on top of that cost
        // two seconds a test.
        return device.hasObject(By.text("Install opencode"))
                && device.hasObject(By.text("Check setup"));
    }

    /** Fails at the moment output should have started, quoting what happened. */
    private void assertStreamed(long mark, String message) throws Exception {
        if (streamingText(mark) == null) {
            fail(message + " - no live chunk in " + (STREAM / 1000) + "s. " + diagnosis());
        }
    }

    /**
     * What the screen and the app's own event log say right now. A failure
     * should not need a re-run to be understood.
     */
    private String diagnosis() throws Exception {
        device.executeShellCommand("uiautomator dump /sdcard/diag.xml >/dev/null 2>&1");
        String xml = device.executeShellCommand("cat /sdcard/diag.xml 2>/dev/null");
        StringBuilder visible = new StringBuilder();
        Matcher text = Pattern.compile("text=\"([^\"]+)\"").matcher(xml);
        while (text.find() && visible.length() < 300) {
            visible.append(visible.length() == 0 ? "" : " | ").append(text.group(1));
        }
        return "screen [" + visible + "] answer [" + read(resultFile())
                + "] shown [" + read(liveOutputFile()) + "] events [" + logTail() + "]";
    }

    /** The last events the app recorded, on one line whose wording is greppable. */
    private String logTail() {
        String[] lines = eventsSince(0).split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 12); i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                tail.append(tail.length() == 0 ? "" : " ; ").append(lines[i].trim());
            }
        }
        return tail.toString();
    }

    /**
     * Proof that output arrived while the command was still running.
     *
     * <p>The app logs one {@code stream:<bytes>} line per chunk it hands to the
     * terminal. That is the only signal that survives the terminal repainting:
     * UI Automator does not reliably return a WebView node while it repaints.
     * The log is read from the file the app writes itself, from the position it
     * had before the command started, so a line here cannot come from an earlier
     * run or from the final result - and no shell round trip is spent asking.
     */
    private String streamingText(long mark) throws Exception {
        boolean chunk = FileWatcher.await(eventsFile(),
                () -> eventsSince(mark).contains("stream:"), STREAM);
        if (!chunk) {
            return null;
        }
        Matcher stream = Pattern.compile("stream:(\\d+)").matcher(eventsSince(mark));
        return stream.find() ? "streamed " + stream.group(1) + " bytes while running" : null;
    }

    /**
     * Runs "Check setup" once and returns Termux's answer.
     *
     * <p>The new answer is observed through {@code last-result.txt}, which the
     * receiver rewrites for every result, after the file is removed. Watching the
     * terminal node instead is what used to stall the loop: a check run twice
     * prints exactly the same text, so the old description never went away, the
     * wait spent its whole timeout looking for an event that cannot happen, and
     * the caller asked again - for ever.
     */
    private String checkOnce() throws Exception {
        bringToFront();
        File answer = resultFile();
        if (!answer.delete() && answer.exists()) {
            fail("the last answer cannot be cleared, so a new one is unobservable: " + answer);
        }

        UiObject2 check = device.wait(Until.findObject(By.text("Check setup")), SHORT);
        if (check == null) {
            fail("the check button is not on screen: " + diagnosis());
        }
        long opened = eventsMark();
        check.click();

        // The check has its own page now; the command is started from there. It
        // announces its layout, so one lookup is enough once it has.
        awaitPage(opened, "CheckActivity", SHORT);
        UiObject2 run = device.findObject(By.text("Run check"));
        if (run == null) {
            fail("the check page offers no run action: " + diagnosis());
        }
        run.click();

        String text = awaitFile(answer, value -> value.contains("exit code"), POLL);
        if (text == null) {
            fail("the check did not answer within " + (POLL / 1000) + "s. " + diagnosis());
        }

        // The answer has to end up on the page as well, and the terminal node is
        // not a reliable thing to poll while it repaints. live-output.txt is
        // written from the text the emulator is showing, so it is the proof that
        // the page has it.
        String firstLine = text.trim().split("\n")[0].trim();
        if (!firstLine.isEmpty()
                && awaitFile(liveOutputFile(), value -> value.contains(firstLine), SHORT) == null) {
            fail("Termux answered but the page does not show it: " + diagnosis());
        }
        // The check has a page of its own, so leaving it is part of asking: the
        // caller's next action is on the main screen.
        bringToFront();
        return text;
    }

    /** The app's own event log: the same lines logcat has, without a shell call. */
    private File eventsFile() {
        return new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "events.log");
    }

    /** The log's length now: everything after it belongs to what happens next. */
    private long eventsMark() {
        return eventsFile().length();
    }

    /**
     * What the app logged after {@code mark}.
     *
     * <p>Read straight from the file the app writes: one {@code logcat -d} per
     * poll was a shell process per 250 ms, and the same lines were already here.
     */
    private String eventsSince(long mark) {
        File file = eventsFile();
        if (!file.isFile()) {
            return "";
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = Math.min(mark, in.length());
            in.seek(start);
            byte[] buffer = new byte[(int) (in.length() - start)];
            in.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** Where the result bundle of the last command is stored. */
    private File resultFile() {
        return new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "last-result.txt");
    }

    /** What the terminal is showing, written by the page itself. */
    private File liveOutputFile() {
        return new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "live-output.txt");
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
            // A check answers in about a second; without this the button would
            // be pressed as fast as it answers for the whole budget.
            Thread.sleep(RETRY);
        }
        fail("Termux never answered " + wanted + " within " + (budget / 1000)
                + "s; the last answer was: " + report + ". " + diagnosis());
        return report;
    }

    /**
     * Follows the running command until it finishes.
     *
     * <p>Nothing here assumes how fast a download is. The app logs the size of
     * every chunk Termux streams, and rewrites {@code live-output.txt} with what
     * the terminal shows; either changing means the command is still working, so
     * the clock only runs out when it is silent. That way a slow mirror is a slow
     * test rather than a failure, and a hang is reported in a minute.
     *
     * <p>Waiting also keeps the flow honest: a check sent while an install is
     * still running answers with the state before it, and the installer's summary
     * and the check's answer overwrite each other in {@code last-result.txt}.
     */
    private void waitForResult(long mark, long stall) throws Exception {
        long silence = System.currentTimeMillis() + stall;
        long bytes = 0;
        while (true) {
            String log = eventsSince(mark);
            if (log.contains("result: ")) {
                return;
            }
            Matcher counted = Pattern.compile("stream:(\\d+)").matcher(log);
            bytes = 0;
            while (counted.find()) {
                bytes += Long.parseLong(counted.group(1));
            }
            long left = silence - System.currentTimeMillis();
            if (left <= 0) {
                break;
            }
            // Woken by the write itself rather than by a timer, and every write
            // is progress: the silence clock starts again from it.
            if (FileWatcher.awaitChange(eventsFile(), left)) {
                silence = System.currentTimeMillis() + stall;
            }
        }
        fail("the command streamed " + bytes + " bytes and then produced nothing for "
                + (stall / 1000) + "s. " + diagnosis());
    }

    /**
     * Waits for a file to say something acceptable. This is the one place the
     * suite polls, because the answer is a file rather than a UI node: the adb
     * path has no accessibility tree to subscribe to.
     */
    private String awaitFile(File file, Predicate<String> acceptable, long budget)
            throws Exception {
        boolean found = FileWatcher.await(file, () -> {
            String text = read(file);
            return text != null && acceptable.test(text);
        }, budget);
        return found ? read(file) : null;
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

    /**
     * A moment worth finding later. It used to take a PNG; the suite records the
     * screen now, and this only names the second to look at in that recording.
     */
    private void milestone(String name) {
        TestMilestones.mark(name);
    }
}
