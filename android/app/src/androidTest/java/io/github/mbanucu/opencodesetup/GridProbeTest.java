package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Draws a known grid and photographs it, for measuring cell alignment.
 *
 * <p>Diagnostic, not a regression test: every line is a run of one character
 * whose cells can be read off the pixels - digits for pitch, 'l' for stroke
 * positions, full blocks for exact cell edges, and the ambiguous-width
 * characters a TUI decorates with. Comparing those edges at several cell sizes
 * is how a half-cell drift is told from a rounding artefact.
 */
@RunWith(AndroidJUnit4.class)
public final class GridProbeTest {

    private static String repeat(String unit, int times) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < times; i++) text.append(unit);
        return text.toString();
    }

    private static final String PROBE = String.join("\n",
            repeat("0123456789", 7),
            repeat("l", 70),
            repeat("\u2503", 70),
            repeat("\u2580", 70),
            repeat("\u00b7", 70),
            repeat("\u2026", 70),
            repeat("\u2584", 70),
            "END-MARKER") + "\n";

    /** The terminal font sizes to photograph; 8 is the opencode page's own. */
    private static final int[] FONTS = { 8 };

    @Test
    public void capturesTheGridAtSeveralSizes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File dir = context.getExternalFilesDir(null);
        for (int font : FONTS) {
            String name = "probe-" + font + ".txt";
            File screen = new File(dir, name);
            screen.delete();
            // CLEAR_TASK, because a page already on top keeps the font it was
            // built with: a reused activity would photograph size 8 four times.
            context.startActivity(TerminalPage.opening(context, name)
                    .putExtra("pty_probe_b64", Base64.encodeToString(
                            PROBE.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    .putExtra("terminal_font", font)
                    .putExtra("terminal_title", "Grid probe " + font)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
            boolean drawn = FileWatcher.await(screen, () -> {
                try {
                    return read(screen).contains("END-MARKER");
                } catch (IOException unreadable) {
                    return false;
                }
            }, 20_000);
            assertTrue("the probe never drew at " + font, drawn);
            device.waitForIdle(800);
            File shot = new File(dir, "probe-" + font + ".png");
            shot.delete();
            device.takeScreenshot(shot);
            assertTrue("the screenshot was written at " + font, shot.isFile());
        }
    }

    private static String read(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] buffer = new byte[(int) input.length()];
            input.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }
}
