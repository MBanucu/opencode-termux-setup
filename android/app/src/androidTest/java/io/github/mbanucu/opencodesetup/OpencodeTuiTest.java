package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the real opencode TUI in this app's terminal, through the page a user
 * reaches, and looks at what it draws.
 *
 * <p>Everything else in the suite uses output a command printed. A TUI is the
 * harder case: it switches to the alternate screen, moves the cursor by escape
 * sequence, and redraws panels in place, so a terminal that mishandles any of
 * that shows a mess of half-drawn boxes. This is a visual check with a
 * mechanical floor under it - the screen must be substantial and must not
 * contain raw escape sequences - plus a screenshot for a person to look at.
 *
 * <p>The terminal is opened the way a user opens it: "Check setup", then "Open
 * opencode", both awaited as accessibility nodes. That path is not only
 * navigation - it is what makes the page fit for a TUI, since "Open opencode"
 * passes the smaller font and the keyboard wiring, while handing the terminal an
 * intent of our own drew the agent at a check's font, in about 44 columns, on a
 * screen no user would ever see. The grid the page reports is asserted for the
 * same reason: opencode drops the footer it prints its version in below about
 * sixty columns, so a TUI that drew at the wrong size has to fail here.
 *
 * <p>Typing is the other half of that page: its keyboard is wired to the PTY's
 * stdin, and nothing else in the suite sends a byte to a running program. A line
 * starting with {@code !} is the TUI's shell mode, so a command typed there runs
 * in Termux and its output comes back over the same stream.
 *
 * <p>The frame to look at is not taken here: the run is recorded, this marks the
 * moment, and {@code test/frames.py extract --names opencode-tui} produces the
 * PNG from that recording.
 *
 * <p>It also tidies up after itself: the tab this run opens is closed with the
 * leader key ({@code ctrl+x w}) and the TUI is exited ({@code ctrl+x q}), so a
 * run does not add a tab to the next one's title bar. The session record
 * outlives the tab; deleting it is a manual step because the TUI never reveals
 * its id, and {@code ctrl+d} deletes only from inside the session list.
 *
 * <p>Opt in, because it needs opencode installed on the device:
 * {@code bash test/local-build.sh --only io.github.mbanucu.opencodesetup.OpencodeTuiTest}
 */
@RunWith(AndroidJUnit4.class)
public final class OpencodeTuiTest {

    private static final String PACKAGE = "io.github.mbanucu.opencodesetup";
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    /** The TUI keeps drawing, so the screen has to grow before the wait is over. */
    private static final long AWAIT = 30_000;
    /** A screen change: a page opens, a button appears. */
    private static final long SHORT = 5_000;

    /**
     * What "wide enough for the agent" means, from the page that chooses the
     * font: below about sixty columns opencode drops the footer it prints its
     * version in. A check fits about 44 columns at the default font, so this is
     * the number that separates a TUI page from a check page.
     */
    private static final int TUI_COLUMNS = 60;

    /** The size the page hands the PTY, as it logs it from the emulator. */
    private static final Pattern COLUMNS = Pattern.compile("\"cols\":(\\d+)");

    /**
     * A shell command typed at the TUI's prompt, and the text only its output
     * can have: the directory the command runs in. A screen that merely echoed
     * the line back cannot contain it.
     *
     * <p>Everything after the leading {@code !} is unshifted, and that is what
     * lets the whole line go to the device as one list of key codes: {@code !}
     * is shift+1, which needs a combination of its own, and "pwd" is three
     * letters plus the Enter that submits them.
     */
    private static final String SHELL_INPUT = "!pwd";
    private static final String SHELL_OUTPUT = "/data/data/com.termux/files/home";

    /** Logs this test's duration; the harness keeps those in its database. */
    @Rule
    public final TestTimings timings = new TestTimings();

    /**
     * Once for the class: the command is sent on arrival, and a page asking for
     * the permission would put a dialog in front of the terminal this reads.
     */
    @BeforeClass
    public static void grantThePermission() throws Exception {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("pm grant " + PACKAGE + " " + PERMISSION);
    }

    @Test
    public void theTuiDrawsItsInterfaceInsteadOfRawEscapeSequences() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File screen = new File(context.getExternalFilesDir(null), "live-output.txt");
        File events = new File(context.getExternalFilesDir(null), "events.log");

        bringTheMainPageToFront(device, events);
        tap(device, "Check setup", "the way to the check page");
        long onCheckPage = events.length();
        awaitPage(events, onCheckPage, "CheckActivity");

        // A screen left by an earlier run would satisfy the wait below before
        // this page had drawn anything.
        if (!screen.delete() && screen.exists()) {
            fail("the previous screen cannot be cleared, so the TUI's is unobservable: "
                    + screen);
        }

        long started = events.length();
        boolean tuiOpen = false;
        try {
            tap(device, "Open opencode", "the action that starts the TUI");
            awaitPage(events, started, "TerminalActivity");
            tuiOpen = true;
            String shown = awaitScreen(screen, text -> visible(text) > 20);

            assertFalse("opencode is not installed, so no TUI could be drawn: install it first",
                    shown.contains("command not found"));
            int columns = awaitColumns(events, started);
            assertTrue("the page gave the PTY " + columns + " columns, and opencode drops its"
                            + " footer below " + TUI_COLUMNS + ": the TUI is being drawn at a"
                            + " check's font instead of the one \"Open opencode\" asks for",
                    columns >= TUI_COLUMNS);

            // The keyboard is wired to the PTY on this page only, so this also says
            // the page a user reaches is the one that can be typed into.
            typeIntoTheTui(device, SHELL_INPUT);
            String ran = awaitScreen(screen, text -> text.contains(SHELL_OUTPUT));

            // Leave the text behind whether it passed or not, and mark the second in
            // the recording where the TUI was on screen: a frame can be extracted
            // from it with test/frames.py when somebody wants to look.
            TestMilestones.mark("opencode-tui");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(context.getExternalFilesDir(null), "tui-screen.txt"))) {
                out.write(ran.getBytes(StandardCharsets.UTF_8));
            }

            assertFalse("the terminal interpreted the escapes instead of printing them",
                    shown.contains("\u001b["));
            assertTrue("the TUI drew text, not just blank cells: it had " + visible(shown)
                    + " visible characters", visible(shown) > 20);
        } finally {
            if (tuiOpen) {
                leaveTheTui(device, screen, context);
            }
        }
    }

    /** Characters that are not whitespace: a screen of blank cells is not a TUI. */
    private static int visible(String screen) {
        int count = 0;
        for (int i = 0; i < screen.length(); i++) {
            if (!Character.isWhitespace(screen.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** Taps a button, failing with the label it could not find rather than NPE. */
    private static void tap(UiDevice device, String label, String what) {
        UiObject2 target = device.wait(Until.findObject(By.text(label)), SHORT);
        if (target == null) {
            fail("no " + what + " on screen: \"" + label + "\" was never published");
        }
        target.click();
    }

    /**
     * Types a line at the TUI's prompt and presses Enter.
     *
     * <p>Nothing is written to a socket by the test: the terminal's keyboard
     * sends each keystroke to the page, and the page on the socket Termux gave
     * the PTY as stdin. A page without that wiring has stdin on {@code /dev/null},
     * which is why this cannot be checked by opening the terminal around it.
     */
    private static void typeIntoTheTui(UiDevice device, String line) throws Exception {
        focusTerminal(device);
        // "!" puts the TUI in shell mode, and it has to be a press of its own:
        // shift+1 is a combination only keycombination can hold, and sent as
        // committed text it is pasted into the prompt without the mode change.
        // Both in one shell invocation delivered no keystrokes at all.
        device.executeShellCommand("input keycombination "
                + KeyEvent.KEYCODE_SHIFT_LEFT + " " + KeyEvent.KEYCODE_1);
        // The rest is letters, so the line and the Enter that submits it travel
        // as one list of key codes, at the speed a person types.
        StringBuilder keys = new StringBuilder("input keyevent --delay 120");
        for (char key : line.substring(1).toCharArray()) {
            keys.append(' ').append(KeyEvent.KEYCODE_A + (key - 'a'));
        }
        device.executeShellCommand(keys.append(' ').append(KeyEvent.KEYCODE_ENTER).toString());
    }

    /** Puts the cursor in the terminal, so a keystroke has somewhere to land. */
    private static void focusTerminal(UiDevice device) {
        UiObject2 terminal = device.wait(Until.findObject(By.clazz("android.webkit.WebView")), SHORT);
        assertNotNull("the terminal is not on screen, so there is nothing to type into", terminal);
        terminal.click();
    }

    /**
     * Closes this run's session tab and exits the TUI.
     *
     * <p>opencode creates a session when the TUI opens and keeps it after the
     * tab is closed. The tab is the part that accumulates in the title bar of
     * every later run, so leader {@code w} closes it here; leader {@code q} then
     * exits, which also ends the command page instead of leaving a TUI behind.
     * The session record stays: the TUI never shows its id, and {@code ctrl+d}
     * is {@code session_delete} only inside the session list while it is
     * {@code app_exit} in the composer.
     */
    private static void leaveTheTui(UiDevice device, File screen, Context context) {
        try {
            UiObject2 terminal = device.findObject(By.clazz("android.webkit.WebView"));
            if (terminal == null) {
                Diagnostics.log(context, "tui: nothing to close");
                return;
            }
            terminal.click();
            leader(device, KeyEvent.KEYCODE_W);
            Diagnostics.log(context, "tui: close tab");
            device.waitForIdle(500);
            leader(device, KeyEvent.KEYCODE_Q);
            Diagnostics.log(context, "tui: exit");
            boolean gone = awaitExit(screen, 10_000);
            Diagnostics.log(context, gone ? "tui: exit seen" : "tui: exit not seen");
        } catch (Exception failed) {
            // Cleanup is a courtesy: a failure here must not replace the result
            // of what the test actually checked.
            Diagnostics.log(context, "tui: cleanup failed: " + failed);
        }
    }

    /**
     * The leader, from its own presses.
     *
     * <p>Two separate shell calls: a merged {@code input} delivered no
     * keystrokes at all on this device, and opencode has to see ctrl+x and then
     * the key inside its leader timeout.
     */
    private static void leader(UiDevice device, int key) throws IOException {
        device.executeShellCommand("input keycombination "
                + KeyEvent.KEYCODE_CTRL_LEFT + " " + KeyEvent.KEYCODE_X);
        device.executeShellCommand("input keyevent " + key);
    }

    /** True once the page has printed the command's exit line. */
    private static boolean awaitExit(File screen, long timeout) {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (read(screen).contains("exit code")) {
                    return true;
                }
            } catch (IOException unreadable) {
                // The page may be rewriting it; look again.
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Gets the main page in front, the way the setup flow does: the app is
     * reopened only when its actions are not already on screen, because the
     * intent is delivered to an activity already in the task instead of
     * bringing its page forward.
     */
    private static void bringTheMainPageToFront(UiDevice device, File events) throws Exception {
        if (device.hasObject(By.text("Check setup"))) {
            return;
        }
        long mark = events.length();
        InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(
                new Intent(Intent.ACTION_MAIN)
                        .setClassName(PACKAGE, PACKAGE + ".MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        awaitPage(events, mark, "MainActivity");
        assertNotNull("the main page offers no way to the check page",
                device.findObject(By.text("Check setup")));
    }

    /** Waits for the app to say a page has been laid out, read from its own log. */
    private static void awaitPage(File events, long mark, String page) throws Exception {
        String wanted = "page:" + page + " ready";
        if (!FileWatcher.await(events, () -> since(events, mark).contains(wanted), SHORT)) {
            fail("the " + page + " page never said it was laid out");
        }
    }

    /**
     * The grid the page gave the PTY, as the emulator reported it.
     *
     * <p>Read from the app's own event log rather than from a node: the terminal
     * publishes its finished screen to accessibility, and a TUI never finishes.
     */
    private static int awaitColumns(File events, long mark) throws Exception {
        if (!FileWatcher.await(events, () -> columns(since(events, mark)) >= 0, AWAIT)) {
            fail("the page never reported the grid it gave the PTY");
        }
        return columns(since(events, mark));
    }

    /** The last grid the log reports, or -1 while it reports none. */
    private static int columns(String log) {
        Matcher found = COLUMNS.matcher(log);
        int last = -1;
        while (found.find()) {
            last = Integer.parseInt(found.group(1));
        }
        return last;
    }

    private String awaitScreen(File screen, Predicate<String> wanted)
            throws IOException, InterruptedException {
        java.util.function.BooleanSupplier ready = () -> {
            try {
                return wanted.test(read(screen));
            } catch (IOException unreadable) {
                return false;
            }
        };
        if (!FileWatcher.await(screen, ready, AWAIT)) {
            throw new AssertionError("the TUI never drew a screen; last screen was: "
                    + read(screen).replace("\n", " | "));
        }
        return read(screen);
    }

    /** Everything the log says after {@code mark}. */
    private static String since(File file, long mark) {
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

    private static String read(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            byte[] buffer = new byte[(int) handle.length()];
            handle.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }
}
