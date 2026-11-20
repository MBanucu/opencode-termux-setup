package io.github.mbanucu.samples.a11yprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * The probe's commands, so a test can act like a screen reader without a UI:
 *
 * <pre>
 * am broadcast -a io.github.mbanucu.samples.a11yprobe.COMMAND \
 *     --es dump tree --es find "$ " --es text "echo hi"
 * </pre>
 *
 * {@code dump} names the file, {@code find} picks the node, {@code text} is what
 * SET_TEXT types. Answer goes to files/actions.txt and to the log.
 */
public final class ProbeCommand extends BroadcastReceiver {

    private static final String TAG = "A11yProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String find = intent.getStringExtra("find");
        String text = intent.getStringExtra("text");
        String dump = intent.getStringExtra("dump");
        // Sent from adb as a literal \n, meaning "and press enter".
        if (text != null) {
            text = text.replace("\\n", "\n");
        }
        ProbeService.setTarget(intent.getStringExtra("pkg"));
        String result;
        if (dump != null) {
            result = ProbeService.dump(dump);
        } else if (text != null) {
            result = ProbeService.setText(find, text);
        } else if (intent.getBooleanExtra("click", false)) {
            result = ProbeService.click(find);
        } else {
            result = ProbeService.focus(find);
        }
        Log.i(TAG, result);
        ProbeService.write("actions.txt", "command find=" + find + " text=" + text + " -> "
                + result + "\n");
        Toast.makeText(context, result, Toast.LENGTH_SHORT).show();
    }
}
