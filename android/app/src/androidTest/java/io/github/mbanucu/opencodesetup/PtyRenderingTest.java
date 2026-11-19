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

    /** Starts the terminal page with these bytes and nothing else to run. */
    private void render(String ptyBytes) {
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
        long deadline = System.currentTimeMillis() + AWAIT;
        String shown = "";
        while (System.currentTimeMillis() < deadline) {
            shown = currentScreen();
            if (wanted.test(shown)) {
                return shown;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the screen never showed what was expected; last screen: "
                + shown.replace("\n", " | "));
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
