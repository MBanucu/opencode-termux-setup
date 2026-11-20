package io.github.mbanucu.samples.nativeterminal;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A real shell in an app-owned pty, shown in a view an accessibility service can
 * read and type into - no Termux, no script(1), no socket, no WebView.
 *
 * <p>On start it asks the shell to say what it is connected to ({@code tty},
 * {@code stty size}, {@code [ -t 1 ]}) and to announce SIGWINCH. Two seconds
 * later it resizes the pty from the app with ioctl(TIOCSWINSZ); the traps'
 * answers, and everything the shell prints afterwards, are written to
 * {@code evidence.txt} in this app's external files directory so the run can be
 * read back from adb instead of from a screenshot.
 */
public final class MainActivity extends Activity {

    private static final String TAG = "PtySample";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder transcript = new StringBuilder();
    private TerminalView terminal;
    private TextView status;
    private volatile int master = -1;
    private volatile String evidence = "no evidence file";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                getResources().getDisplayMetrics()));
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("starting");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setTextIsSelectable(true);
        root.addView(status);

        terminal = new TerminalView(this);
        root.addView(terminal, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("80x24", v -> resize(80, 24)));
        buttons.addView(button("40x12", v -> resize(40, 12)));
        buttons.addView(button("tty", v -> type("tty; stty size")));
        buttons.addView(button("dump", v -> writeEvidence("dump")));
        root.addView(buttons);
        setContentView(root);

        evidence = new File(getExternalFilesDir(null), "evidence.txt").getAbsolutePath();
        terminal.setListener(line -> {
            writeEvidence("submitted:" + line);
            type(line);
        });

        // A terminal is the thing with the cursor: focused, so the IME and
        // `adb shell input text` land in its InputConnection without a tap.
        terminal.requestFocus();

        boolean fake = getIntent() != null && getIntent().getBooleanExtra("fake", false);
        if (fake) {
            startFake();
        } else {
            startShell();
        }
        writeEvidence("activity:created");
    }

    private Button button(String label, android.view.View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        button.setOnClickListener(click);
        return button;
    }

    private void startShell() {
        String home = getFilesDir().getAbsolutePath();
        String[] environment = {
                "TERM=xterm-256color",
                "HOME=" + home,
                "PATH=/system/bin:/system/xbin:/vendor/bin",
                "PS1=$ ",
        };
        int[] pid = new int[1];
        int fd = Pty.start(new String[] { "/system/bin/sh" }, environment, home, 80, 24, pid);
        if (fd < 0) {
            status.setText("forkpty failed: " + fd);
            writeEvidence("forkpty:failed:" + fd);
            startFake();
            return;
        }
        master = fd;
        writeEvidence("pty:master-fd:" + fd + " pid:" + pid[0]);
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[8192];
            int count;
            while (master >= 0 && (count = Pty.read(master, buffer)) > 0) {
                Log.i(TAG, "read " + count + " bytes");
                String chunk = new String(buffer, 0, count, StandardCharsets.UTF_8);
                if (chunk.isEmpty()) {
                    break;
                }
                ui.post(() -> {
                    transcript.append(chunk);
                    terminal.feed(chunk);
                    status.setText("pty open, " + transcript.length() + " chars");
                });
            }
            ui.post(() -> status.setText("pty closed"));
            writeEvidence("pty:closed");
        }, "pty-reader");
        reader.setDaemon(true);
        reader.start();

        // What the shell thinks it is talking to, asked once the prompt exists.
        ui.postDelayed(() -> type("tty; "
                + "[ -t 0 ] && echo stdin-is-tty; "
                + "[ -t 1 ] && echo stdout-is-tty; "
                + "echo size-now=$(stty size); "
                + "trap 'echo SIGWINCH-arrived; echo size-after=$(stty size)' WINCH"), 700);
        // The app changes the size itself: the kernel tells the shell.
        ui.postDelayed(() -> {
            writeEvidence("resize:40x12 from app");
            resize(40, 12);
        }, 2500);
        ui.postDelayed(() -> type("echo still-alive; stty size"), 3500);
    }

    private void startFake() {
        terminal.feed("fake mode: no pty, only the view\r\n");
        status.setText("fake mode");
    }

    private void type(String line) {
        byte[] bytes = (line + "\r").getBytes(StandardCharsets.UTF_8);
        int fd = master;
        if (fd < 0) {
            Log.w(TAG, "no pty for " + line);
            return;
        }
        int written = Pty.write(fd, bytes, bytes.length);
        Log.i(TAG, "wrote " + written + " of " + bytes.length + " to " + fd);
    }

    private void resize(int cols, int rows) {
        int fd = master;
        if (fd >= 0) {
            int result = Pty.resize(fd, cols, rows);
            writeEvidence("resize:result:" + result + " cols:" + cols + " rows:" + rows);
        }
        ui.post(() -> terminal.invalidate());
    }

    /**
     * Appends one line of evidence with the screen as the view has it.
     *
     * <p>Appended, not overwritten: the first attempts at this sample wrote a
     * fresh file each time and the pty's start line - the one that proves a pty
     * exists at all - was gone by the time the file was read.
     */
    private void writeEvidence(String what) {
        String screen = terminal == null ? "" : terminal.screenText();
        String body = "### " + what + "\n--- screen ---\n" + screen + "--- transcript ---\n"
                + transcript + "\n=== end ===\n";
        try (FileOutputStream out = new FileOutputStream(evidence, true)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nothing to do in a sample
        }
    }

    @Override
    protected void onDestroy() {
        int fd = master;
        master = -1;
        if (fd >= 0) {
            Pty.close(fd);
        }
        super.onDestroy();
    }
}
