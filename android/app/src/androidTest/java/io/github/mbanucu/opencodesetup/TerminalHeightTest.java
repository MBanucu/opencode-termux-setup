package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last line of the terminal has to be readable at every height it can take.
 *
 * <p>A screenshot cannot answer that question: it shows one height, and the
 * clipping that matters happens at the bottom edge of the terminal's own area. The
 * terminal page reports the geometry of its newest line
 * ({@code tail:{"fits":true,"terminal":900,...}}), and this test reads it at three
 * heights the page is asked to use through an intent extra.
 *
 * <p>The page is opened the same way the rendering cases open it, through
 * {@link TerminalPage}: these tests measure the terminal, and the taps through
 * the main and check pages cost seconds without adding evidence. The height and
 * a filler - so the buffer overflows - travel directly to the page that uses
 * them.
 */
@RunWith(AndroidJUnit4.class)
public final class TerminalHeightTest {

    /** Short, the default full-screen page, and a pinned tall one. */
    private static final float[] FRACTIONS = {0.2f, 0.45f, 0.62f};

    private static final long SHORT = 5_000;
    /** How long the log has to stay unchanged for the page to count as settled. */
    private static final long QUIET = 400;

    @Rule
    public final TestTimings timings = new TestTimings();

    private Context context;
    private File events;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        events = new File(context.getExternalFilesDir(null), "events.log");
    }

    @Test
    public void lastLineFitsAtEveryHeight() throws Exception {
        int previous = -1;
        for (float fraction : FRACTIONS) {
            long mark = eventsMark();
            TestMilestones.mark("height-" + fraction);
            String tail;
            try (TestMilestones.Step ignored =
                         TestMilestones.step("filled terminal at " + fraction)) {
                // The full buffer is what matters: an overflowing terminal keeps
                // its newest row against the bottom edge, a two line one does not,
                // so the page is told to fill itself instead of running a command
                // that would clear it.
                String name = "height-" + fraction + ".txt";
                new File(context.getExternalFilesDir(null), name).delete();
                context.startActivity(TerminalPage.opening(context, name)
                        .putExtra("terminal_fraction", fraction)
                        .putExtra("filler_lines", 300)
                        .putExtra("terminal_title", "Height probe"));
                tail = settledTail(mark);
            }
            assertNotNull("the filled terminal reports its last line at " + fraction, tail);
            assertTrue("the last line has text at " + fraction + ": " + tail,
                    !tail.contains("\"chars\":0"));
            assertTrue("the last line ends inside the terminal at " + fraction + ": " + tail,
                    tail.contains("\"fits\":true"));

            int height = heightOf(tail);
            assertTrue("the terminal height was reported at " + fraction + ": " + tail, height > 0);
            assertTrue("a pinned height differs from the previous one at " + fraction
                            + " (" + previous + " then " + height + ")",
                    previous < 0 || height != previous);
            previous = height;
        }
    }

    /**
     * The last geometry line that measured something, once the page has settled.
     *
     * <p>Clearing the terminal publishes an empty screen, and every repaint while
     * a chunk lands publishes another line; the one that matters is the last.
     */
    private String settledTail(long mark) throws Exception {
        // The first look can find nothing: the page is started by the call that
        // returns just before this, and on a cold process that is longer than
        // the quiet window below. Silence only means something once the page has
        // reported at least once.
        FileWatcher.await(events, () -> lastTextTail(eventsSince(mark)) != null, SHORT);
        // Silence is the signal: the page republishes whenever its screen or its
        // size changes, so the measurements have stopped coming when the log has
        // gone this long without a new line. A fixed sleep was either wasted time
        // or a halfway drawn buffer read on a slow device.
        String log = eventsSince(mark);
        long deadline = System.currentTimeMillis() + SHORT;
        while (System.currentTimeMillis() < deadline) {
            String latest = eventsSince(mark);
            if (!latest.equals(log)) {
                log = latest;
                continue;
            }
            // Woken by the next write; when none comes for QUIET, it has settled.
            long left = Math.min(QUIET, deadline - System.currentTimeMillis());
            if (left <= 0 || !FileWatcher.awaitChange(events, left)) {
                break;
            }
        }
        return lastTextTail(log);
    }

    /** The last tail line that measured a line with text in it. */
    private String lastTextTail(String log) {
        String tail = null;
        Matcher found = Pattern.compile("tail:\\{[^}]*\\}").matcher(log);
        Pattern chars = Pattern.compile("\"chars\":(\\d+)");
        while (found.find()) {
            Matcher count = chars.matcher(found.group());
            if (count.find() && Integer.parseInt(count.group(1)) > 0) {
                tail = found.group();
            }
        }
        return tail;
    }

    /**
     * What the app logged after {@code mark}, read from the file it writes.
     *
     * <p>The page republishes a line per change, so this is the same evidence
     * {@code logcat -d} gave, without a shell process on every 50 ms poll.
     */
    private String eventsSince(long mark) {
        if (!events.isFile()) {
            return "";
        }
        try (RandomAccessFile in = new RandomAccessFile(events, "r")) {
            long start = Math.min(mark, in.length());
            in.seek(start);
            byte[] buffer = new byte[(int) (in.length() - start)];
            in.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** The event log's length now, so the next reads only see what follows. */
    private long eventsMark() {
        return events.length();
    }

    private int heightOf(String tail) {
        Matcher field = Pattern.compile("\"terminal\":(\\d+)").matcher(tail);
        return field.find() ? Integer.parseInt(field.group(1)) : -1;
    }
}
