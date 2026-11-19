package io.github.mbanucu.opencodesetup;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * The same four actions, without the UI, for adb (and any script that can run
 * one).
 *
 * <pre>
 * adb shell am start -n io.github.mbanucu.opencodesetup/.AdbCommandActivity -e command check
 * adb shell cat /sdcard/Android/data/io.github.mbanucu.opencodesetup/files/last-result.txt
 * </pre>
 *
 * <p>{@code command} is {@code install}, {@code uninstall} or {@code check};
 * {@code --ez purge true} turns an uninstall into {@code uninstall.sh --purge}.
 *
 * <p>It is an activity and not a broadcast receiver for a boring Android reason:
 * starting a service in another app from a background receiver is refused, so
 * the dispatch has to happen while this app is in the foreground.
 *
 * <p>The result lands in {@code last-result.txt} in the app's external files
 * directory, which adb can read without root, and in logcat as
 * {@code ADB_RESULT}. For {@code check} the file is deleted first and then filled
 * by {@link RunCommandReceiver} with Termux's real answer; for install and
 * uninstall it says the command was sent, because those run in the Termux
 * window, which also reports its output there when it finishes.
 */
public final class AdbCommandActivity extends Activity {

    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_PURGE = "purge";

    private static final String TAG = "OpencodeSetup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String command = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_COMMAND);
        boolean purge = getIntent() != null && getIntent().getBooleanExtra(EXTRA_PURGE, false);
        report(dispatch(command, purge));
        finish();
    }

    private String dispatch(String command, boolean purge) {
        if (command == null) {
            return "error: no command; use -e command install|uninstall|check";
        }
        try {
            switch (command) {
                case "install":
                    TermuxCommands.send(this, Commands.INSTALL, "Install opencode",
                            "Add the opencode apt repository and install opencode", false);
                    return "sent install\n" + Commands.INSTALL;

                case "uninstall":
                    TermuxCommands.send(this, purge ? Commands.UNINSTALL_PURGE : Commands.UNINSTALL,
                            "Uninstall opencode",
                            purge
                                    ? "Remove the opencode package, its apt repository and all its data"
                                    : "Remove the opencode package and its apt repository, keeping its data",
                            false);
                    return "sent " + (purge ? "uninstall --purge" : "uninstall keeping settings")
                            + "\n" + (purge ? Commands.UNINSTALL_PURGE : Commands.UNINSTALL);

                case "check":
                    // The answer comes back through RunCommandReceiver, so the
                    // stale file is cleared rather than overwritten.
                    clearResult();
                    TermuxCommands.send(this, Commands.CHECK, "Check opencode",
                            "Report the installed opencode version", true);
                    return "sent check; the answer replaces last-result.txt";

                default:
                    return "error: unknown command '" + command
                            + "'; use install, uninstall or check";
            }
        } catch (RuntimeException refused) {
            Log.w(TAG, "Termux refused the adb command", refused);
            return "error: Termux refused the command; set allow-external-apps=true in "
                    + "~/.termux/termux.properties, and make sure "
                    + TermuxCommands.PERMISSION + " is granted";
        }
    }

    private void report(String text) {
        Log.i(TAG, "ADB_RESULT " + text.replace("\n", " | "));
        File dir = getExternalFilesDir(null);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            return;
        }
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(dir, RunCommandReceiver.RESULT_FILE)),
                StandardCharsets.UTF_8)) {
            writer.write(text);
        } catch (IOException failed) {
            Log.w(TAG, "could not write the adb result", failed);
        }
    }

    private void clearResult() {
        File dir = getExternalFilesDir(null);
        if (dir != null) {
            new File(dir, RunCommandReceiver.RESULT_FILE).delete();
        }
    }
}
