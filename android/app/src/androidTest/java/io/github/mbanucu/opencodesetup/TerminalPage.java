package io.github.mbanucu.opencodesetup;

import android.content.Context;
import android.content.Intent;

/**
 * How a test opens the terminal page: directly, in-process, in a fresh task.
 *
 * <p>The height and rendering tests measure the terminal itself - its last line
 * at a pinned height, the screen it draws from raw bytes - so they start the
 * page that owns it instead of tapping through the main page and the check page
 * first. Those taps cost seconds and prove nothing either test asserts; the
 * navigation and the command the check page sends are what {@link SetupFlowTest}
 * drives through the user interface.
 *
 * <p>Every page publishes its screen to {@code live-output.txt} unless told
 * otherwise, so the caller names its own file: two pages that overlapped would
 * otherwise be read as one.
 *
 * <p>The page is reused when it is already on top. A case hands the next probe
 * or height to the page that is already there, so the task is never torn down -
 * which is what used to flash the home screen or Termux between cases.
 */
final class TerminalPage {

    private TerminalPage() {
    }

    /** An intent to open the terminal page, publishing to {@code screenFile}. */
    static Intent opening(Context context, String screenFile) {
        return new Intent(context, TerminalActivity.class)
                .putExtra("screen_file", screenFile)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
