package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Feeds a PTY's bytes to the terminal and checks what ends up on its screen.
 *
 * <p>A terminal is not a text view: the bytes it is given are commands as much as
 * text. A carriage return goes back to column zero and overwrites, {@code ESC[2K}
 * erases a line, SGR sequences are colour rather than characters, a backspace
 * steps back, and a line longer than the terminal wraps. These tests write those
 * bytes into the real terminal page and read back the screen it publishes, so a
 * regression in the emulator wiring shows up as a wrong screen rather than as a
 * screenshot somebody has to look at.
 *
 * <p>Every case is event driven: the page republishes its screen text whenever it
 * changes, and the test waits for that publication (with a deadline) instead of
 * sleeping a guessed amount of time.
 */
@RunWith(AndroidJUnit4.class)
public final class PtyRenderingTest {

    private static final String EXTRA_PROBE = "pty_probe_b64";
    private static final long AWAIT = 12_000;

    @Rule
    public final TestTimings timings = new TestTimings();

    /** Names the milestone after the case that drew the screen. */
    @Rule
    public final TestName testName = new TestName();

    private Context context;
    private File screen;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        screen = new File(context.getExternalFilesDir(null), "live-output.txt");
    }

    @Test
    public void aCarriageReturnOverwritesTheLineInsteadOfAddingOne() throws Exception {
        render("download 0%\rdownload 100%\n");
        String shown = awaitScreen(text -> text.contains("download 100%"));
        assertFalse("the redrawn line replaced the first one, it did not add a row",
                shown.contains("download 0%"));
    }

    @Test
    public void colourEscapesAreNotPrintedAsCharacters() throws Exception {
        render("\u001b[31mred\u001b[0m and plain\n");
        String shown = awaitScreen(text -> text.contains("red and plain"));
        assertFalse("no escape sequence reached the screen text", shown.contains("\u001b["));
    }

    @Test
    public void erasingALineRemovesWhatWasThere() throws Exception {
        render("obsolete\u001b[2K\rdone\n");
        String shown = awaitScreen(text -> text.contains("done"));
        assertFalse("the erased text is gone", shown.contains("obsolete"));
    }

    @Test
    public void aBackspaceStepsBackAndTheNextCharacterReplacesIt() throws Exception {
        render("abX\bY\n");
        String shown = awaitScreen(text -> text.contains("abY"));
        assertFalse("the overwritten character is not left behind", shown.contains("abXY"));
    }

    @Test
    public void aLineLongerThanTheTerminalWrapsInsteadOfBeingClipped() throws Exception {
        render("x".repeat(200) + "\n");
        String shown = awaitScreen(text -> text.replaceAll("[^x]", "").length() >= 200);
        int longest = 0;
        for (String line : shown.split("\n")) {
            longest = Math.max(longest, line.length());
        }
        assertTrue("the long line was wrapped into several rows, longest row was " + longest,
                longest < 100);
    }

    @Test
    public void cursorUpRewritesTheLineAboveInsteadOfAddingOne() throws Exception {
        // Two lines, then the cursor is moved back up to the second one and that
        // whole row is erased and rewritten: the state to get wrong is the row the
        // cursor is on, not the one it started from.
        render("first line\nsecond line\n\u001b[1A\u001b[2K\rrewritten\n");
        String shown = awaitScreen(text -> text.contains("rewritten"));
        assertFalse("the line the cursor moved up to was replaced",
                shown.contains("second line"));
        assertTrue("the untouched line above it is still there", shown.contains("first line"));
    }

    @Test
    public void clearingTheScreenRemovesEveryLineItHad() throws Exception {
        // ESC[2J erases the display, ESC[H puts the cursor back at the top; both
        // are multi-line operations, unlike the single-row erase above.
        render("alpha\nbeta\ngamma\n\u001b[2J\u001b[Hcleared\n");
        String shown = awaitScreen(text -> text.contains("cleared"));
        assertFalse("no earlier line survived the clear", shown.contains("alpha")
                || shown.contains("beta") || shown.contains("gamma"));
    }

    @Test
    public void moreLinesThanTheScreenKeepsTheTailAndDropsTheOldest() throws Exception {
        StringBuilder pty = new StringBuilder();
        for (int row = 1; row <= 100; row++) {
            pty.append("row ").append(row).append(" of 100\n");
        }
        render(pty.toString());
        String shown = awaitScreen(text -> text.contains("row 100 of 100"));
        assertFalse("the first row scrolled out of the window that is published",
                shown.contains("row 1 of 100"));
        assertTrue("what remains is still in order",
                shown.indexOf("row 90 of 100") < shown.indexOf("row 100 of 100"));
    }

    @Test
    public void aCarriageReturnAfterAWrapRewritesOnlyTheWrappedRow() throws Exception {
        // This is the phone-width case that used to break: text wraps into a
        // second row, and the carriage return belongs to that row, not to where
        // the line began.
        render("z".repeat(200) + "\rEND\n");
        String shown = awaitScreen(text -> text.contains("END"));
        int wrappedRows = 0;
        boolean endsOnWrappedRow = false;
        for (String line : shown.split("\n")) {
            if (line.contains("z")) {
                wrappedRows++;
            }
            if (line.startsWith("END")) {
                endsOnWrappedRow = true;
            }
        }
        assertTrue("the long line still occupies more than one row, got " + wrappedRows,
                wrappedRows >= 2);
        assertTrue("the carriage return landed on the row the text wrapped into",
                endsOnWrappedRow);
    }

    @Test
    public void aLineFeedStartsTheNextLineAtColumnZero() throws Exception {
        // A raw PTY sends \r\n; a bundle that lost the \r would indent the next
        // line by the length of the one before it.
        render("abc\ndef\n");
        String shown = awaitScreen(text -> text.contains("def"));
        boolean atColumnZero = false;
        for (String line : shown.split("\n")) {
            atColumnZero |= line.equals("def");
        }
        assertTrue("the second line starts at column zero", atColumnZero);
    }

    /** Starts the terminal page with these bytes and nothing else to run. */
    private void render(String ptyBytes) {
        TestMilestones.mark("pty-" + testName.getMethodName());
        Intent probe = new Intent(context, TerminalActivity.class)
                .putExtra(EXTRA_PROBE,
                        Base64.encodeToString(ptyBytes.getBytes(StandardCharsets.UTF_8),
                                Base64.NO_WRAP))
                .putExtra("terminal_title", "PTY probe")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(probe);
    }

    /** Waits for the terminal to publish a screen that satisfies {@code wanted}. */
    private String awaitScreen(Predicate<String> wanted) throws IOException, InterruptedException {
        java.util.function.BooleanSupplier ready = () -> {
            try {
                return wanted.test(currentScreen());
            } catch (IOException unreadable) {
                return false;
            }
        };
        if (!FileWatcher.await(screen, ready, AWAIT)) {
            throw new AssertionError("the screen never showed what was expected; last screen: "
                    + currentScreen().replace("\n", " | "));
        }
        return currentScreen();
    }

    private String currentScreen() throws IOException {
        if (!screen.isFile()) {
            return "";
        }
        try (RandomAccessFile file = new RandomAccessFile(screen, "r")) {
            byte[] buffer = new byte[(int) file.length()];
            file.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }
}
