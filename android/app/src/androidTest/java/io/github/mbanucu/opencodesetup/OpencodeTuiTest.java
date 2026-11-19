package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Runs the real opencode TUI in this app's terminal and looks at what it draws.
 *
 * <p>Everything else in the suite uses output a command printed. A TUI is the
 * harder case: it switches to the alternate screen, moves the cursor by escape
 * sequence, and redraws panels in place, so a terminal that mishandles any of
 * that shows a mess of half-drawn boxes. This is a visual check with a mechanical
 * floor under it - the screen must be substantial and must not contain raw escape
 * sequences - plus a screenshot for a person to look at.
 *
 * <p>Opt in, because it needs opencode installed on the device:
 * {@code bash test/local-build.sh --only io.github.mbanucu.opencodesetup.OpencodeTuiTest}
 */
@RunWith(AndroidJUnit4.class)
public final class OpencodeTuiTest {

    private static final long AWAIT = 30_000;

    @Rule
    public final TestTimings timings = new TestTimings();

    @Test
    public void theTuiDrawsItsInterfaceInsteadOfRawEscapeSequences() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File screen = new File(context.getExternalFilesDir(null), "live-output.txt");

        Intent tui = new Intent(context, TerminalActivity.class)
                .putExtra("terminal_command", "opencode")
                .putExtra("terminal_label", "opencode")
                .putExtra("terminal_description", "TUI rendering")
                .putExtra("terminal_title", "opencode")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(tui);

        String shown = awaitScreen(screen, text -> visible(text) > 20);

        // Leave both behind whether it passed or not: the screenshot is for a
        // person, and the text is what a failure can be read from later.
        File dir = new File(context.getExternalFilesDir(null), "screenshots");
        dir.mkdirs();
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(new File(dir, "opencode-tui.png"));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                new File(context.getExternalFilesDir(null), "tui-screen.txt"))) {
            out.write(shown.getBytes(StandardCharsets.UTF_8));
        }

        assertFalse("the terminal interpreted the escapes instead of printing them",
                shown.contains("\u001b["));
        assertTrue("the TUI drew text, not just blank cells: it had " + visible(shown)
                + " visible characters", visible(shown) > 20);
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

    private String awaitScreen(File screen, java.util.function.Predicate<String> wanted)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT;
        String shown = "";
        while (System.currentTimeMillis() < deadline) {
            shown = read(screen);
            if (wanted.test(shown)) {
                return shown;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the TUI never drew a screen; last screen was: "
                + shown.replace("\n", " | "));
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
