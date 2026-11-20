package io.github.mbanucu.opencodesetup;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

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
import java.util.regex.Pattern;

/**
 * Checks that the footer's slash key puts a slash into the running command.
 *
 * <p>The footer exists for the keys a phone keyboard cannot type, and a button
 * that draws the right label proves nothing about the byte reaching the pseudo
 * terminal. So the command here turns echo off and prints the decimal code of
 * the first byte it is handed: {@code 47} on the screen can only be {@code od}
 * having read a slash. A key that sent nothing, sent the wrong byte or only
 * echoed locally leaves that line absent.
 *
 * <p>The page is opened with the font and the keyboard the "Open opencode"
 * action uses, because that is the page this key lives on; nothing here depends
 * on its width, so the two taps of that flow would only cost seconds.
 */
@RunWith(AndroidJUnit4.class)
public final class SlashKeyTest {

    /** Echo off, so nothing but od's answer can put 47 on the screen. */
    private static final String COMMAND = "stty raw -echo; echo READY; od -An -tu1 -N1";

    /** A line that is nothing but the value, indentation and all. */
    private static final Pattern READ_SLASH = Pattern.compile("(?m)^\\s*47\\s*$");

    private static final long AWAIT = 25_000;

    @Rule
    public final TestTimings timings = new TestTimings();

    /** Names the screen file after the case, so nothing else writes there. */
    @Rule
    public final TestName testName = new TestName();

    private Context context;
    private File screen;
    private UiDevice device;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test
    public void theSlashKeySendsASlashToTheCommand() throws Exception {
        String name = "slash-" + testName.getMethodName() + ".txt";
        screen = new File(context.getExternalFilesDir(null), name);
        screen.delete();
        TestMilestones.mark("slash-key");
        context.startActivity(TerminalPage.opening(context, name)
                .putExtra("terminal_command", COMMAND)
                .putExtra("terminal_label", "slash")
                .putExtra("terminal_description", "reads one byte and prints its code")
                .putExtra("terminal_title", "Slash key")
                .putExtra("terminal_font", 8)
                .putExtra("terminal_input", true));

        // READY is printed by od's shell just before od reads, so the key is
        // pressed only once there is something on the other end of the PTY.
        await("the command to start", text -> text.contains("READY"));

        UiObject2 slash = device.wait(Until.findObject(By.text("/")), AWAIT);
        assertNotNull("the footer has a slash key", slash);
        slash.click();

        String shown = await("the command to read a slash",
                text -> READ_SLASH.matcher(text).find());
        assertTrue("the screen shows od's answer: " + shown.replace("\n", " | "),
                READ_SLASH.matcher(shown).find());
    }

    /** Waits for the page to publish a screen that satisfies {@code wanted}. */
    private String await(String what, Predicate<String> wanted) throws Exception {
        java.util.function.BooleanSupplier ready = () -> {
            try {
                return wanted.test(currentScreen());
            } catch (IOException unreadable) {
                return false;
            }
        };
        if (!FileWatcher.await(screen, ready, AWAIT)) {
            throw new AssertionError(what + " never happened; last screen: "
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
