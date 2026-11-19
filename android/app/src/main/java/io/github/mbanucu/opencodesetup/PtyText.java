package io.github.mbanucu.opencodesetup;

import java.util.regex.Pattern;

/**
 * Turns the bytes of a PTY back into the plain lines a reader expects.
 *
 * <p>A PTY writes escape sequences for colour and for redrawing a line in place,
 * and Termux hands them back verbatim. What a terminal showed is: escapes gone,
 * and for each line only what was left after the last carriage return. Lifted
 * out of {@link RunCommandReceiver}, which was receiving, storing and cleaning
 * the answer in one class.
 */
final class PtyText {

    /** CSI and OSC escapes, which a PTY writes for colour and progress. */
    private static final Pattern ANSI =
            Pattern.compile("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007]*\u0007");

    private PtyText() {
    }

    static String plain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = ANSI.matcher(text).replaceAll("").split("\n", -1);
        StringBuilder cleaned = new StringBuilder();
        for (String line : lines) {
            int carriage = line.lastIndexOf('\r');
            String shown = carriage < 0 ? line : line.substring(carriage + 1);
            if (cleaned.length() > 0 || !shown.trim().isEmpty()) {
                cleaned.append(shown).append('\n');
            }
        }
        return cleaned.toString().trim();
    }
}
