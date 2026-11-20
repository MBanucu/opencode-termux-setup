package io.github.mbanucu.samples.nativeterminal;

import io.github.mbanucu.terminal.TerminalView;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
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
    private LiveStream live;
    private volatile boolean termuxMode;
    /** Whether the session is asked for its size after the first answer. */
    private boolean sizeProbes = true;
    private volatile String evidence = "no evidence file";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                getResources().getDisplayMetrics()));
        root.setPadding(pad, pad, pad, pad);

        // Android 15 draws every app edge to edge, so without this the status bar
        // sits on top of the first terminal row and the status line collides with
        // the clock. Padding by the insets is what the window used to do for us.
        //
        // The IME is the other half of that: edge-to-edge means the window is not
        // resized for the keyboard any more, so without its inset the terminal
        // runs on behind the keyboard, the row the user is typing into is drawn
        // underneath it, and the only cursor in sight is the shell's own - which
        // reads as a cursor that goes up instead of down.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = pad;
            int top = pad;
            int right = pad;
            int bottom = pad;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // The larger of the two: the keyboard covers the navigation bar.
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.ime());
                left += bars.left;
                top += bars.top;
                right += bars.right;
                bottom += bars.bottom;
            } else {
                left += insets.getSystemWindowInsetLeft();
                top += insets.getSystemWindowInsetTop();
                right += insets.getSystemWindowInsetRight();
                bottom += insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });

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
        buttons.addView(button("tty", v -> type("tty; stty size")));
        buttons.addView(button("dump", v -> writeEvidence("dump cursor=" + terminal.cursor())));
        buttons.addView(button("probe", v -> probeForeignPrograms()));
        root.addView(buttons);
        setContentView(root);

        evidence = new File(getExternalFilesDir(null), "evidence.txt").getAbsolutePath();
        terminal.setListener(line -> {
            writeEvidence("submitted:" + line);
            submit(line);
        });
        // A full-screen program draws its own input, so keys go to it as they are
        // pressed instead of into a prompt this view cannot show there.
        terminal.setRawInput(this::typeRaw);
        // The view's grid changes when the window does, which is what a keyboard
        // opening looks like from here; the pty has to be told so the program
        // redraws to the rows that are actually visible.
        terminal.setSizeListener(this::onGridResized);

        // A terminal is the thing with the cursor: focused, so the IME and
        // `adb shell input text` land in its InputConnection without a tap.
        terminal.requestFocus();

        probeForeignPrograms();

        boolean fake = getIntent() != null && getIntent().getBooleanExtra("fake", false);
        boolean termux = getIntent() != null && getIntent().getBooleanExtra("termux", false);
        boolean opencode = getIntent() != null && getIntent().getBooleanExtra("opencode", false);
        boolean cursor = getIntent() != null && getIntent().getBooleanExtra("cursor", false);
        boolean colours = getIntent() != null && getIntent().getBooleanExtra("colors", false);
        boolean tui = getIntent() != null && getIntent().getBooleanExtra("tui", false);
        boolean echo = getIntent() != null && getIntent().getBooleanExtra("echo", false);
        boolean background = getIntent() != null
                && getIntent().getBooleanExtra("background", false);
        sizeProbes = getIntent() == null || getIntent().getBooleanExtra("sizeProbes", true);
        if (echo) {
            startTui("tui-echo.py", "echo-tui");
        } else if (background) {
            startTui("tui-bg.py", "bg-tui");
        } else if (tui) {
            startTui("tui.py", "tui");
        } else if (colours) {
            startColourDemo();
        } else if (cursor) {
            startCursorDemo();
        } else if (fake) {
            startFake();
        } else if (opencode) {
            startTermuxSession("opencode", "opencode in a sample view", true);
        } else if (termux) {
            startTermuxSession(null, null, false);
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

    /**
     * Starts a program in a pty this app owns and pumps it into the view.
     *
     * <p>False when {@code forkpty} failed, so a caller can say why rather than
     * showing an empty screen.
     */
    private boolean startPty(String[] argv, String[] environment, String home,
            int cols, int rows) {
        int[] pid = new int[1];
        int fd = Pty.start(argv, environment, home, cols, rows, pid);
        if (fd < 0) {
            status.setText("forkpty failed: " + fd);
            writeEvidence("forkpty:failed:" + fd);
            return false;
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
        return true;
    }

    /**
     * Runs an interactive curses TUI in the Termux session.
     *
     * <p>Python and curses are in Termux, not in /system/bin, so the program runs
     * in the session rather than in an app-owned pty - and the view is fed the
     * same bytes opencode produces. Its source arrives as base64 on the session's
     * own input, because an app cannot write into Termux's home. Keys are the
     * one-character lines a service submits: `n`, `p`, `t`, `q` for the box TUI,
     * a word and Enter for the echo TUI.
     *
     * @param asset the program's source in this app's assets
     * @param tag   the evidence prefix, so a test can wait for the TUI it started
     */
    private void startTui(String asset, String tag) {
        String script = readAsset(asset);
        if (script == null) {
            startFake();
            return;
        }
        String launch = "python3 -c \"import base64;exec(base64.b64decode('"
                + Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)
                + "'))\"";
        termuxMode = true;
        live = new LiveStream();
        int port = live.open(chunk -> ui.post(() -> {
            transcript.append(chunk);
            terminal.feed(chunk);
            status.setText("curses tui, " + transcript.length() + " chars");
        }));
        int control = live.openControl();
        writeEvidence("termux:port:" + port + " control:" + control);
        if (port == 0) {
            status.setText("no loopback port");
            return;
        }
        writeEvidence("termux:command:" + TermuxSession.wrap(port, control, "bash -i"));
        try {
            TermuxSession.start(this, port, control, "bash -i", "curses TUI");
        } catch (RuntimeException refused) {
            writeEvidence("termux:start-failed:" + refused);
            status.setText("Termux refused: " + refused.getMessage());
        }
        Thread driver = new Thread(() -> {
            for (int i = 0; i < 80 && !live.connected(); i++) {
                sleep(100);
            }
            writeEvidence("termux:connected:" + live.connected());
            if (!live.connected()) {
                return;
            }
            sleep(1500);
            submit(launch);
            writeEvidence(tag + ":started");
            for (int i = 0; i < 30; i++) {
                sleep(1500);
                writeEvidence("dump cursor=" + terminal.cursor());
            }
        }, "tui-driver");
        driver.setDaemon(true);
        driver.start();
    }

    /** Reads an asset as text; null when it is not in the APK. */
    private String readAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException failed) {
            writeEvidence("asset:failed:" + name + ":" + failed);
            return null;
        }
    }

    private void startShell() {
        String home = getFilesDir().getAbsolutePath();
        // `/system/bin/sh` (mksh) sources /system/etc/mkshrc, which sets a prompt
        // that carries the working directory, so the PS1 put in the environment is
        // overwritten and the `$` ends up at the end of a long path instead of at
        // the start of the line. ENV replaces that rc for an interactive shell.
        String rc = writeShellRc(home);
        String[] environment = {
                "TERM=xterm-256color",
                "HOME=" + home,
                "PATH=/system/bin:/system/xbin:/vendor/bin",
                "ENV=" + rc,
                "PS1=$ ",
        };
        if (!startPty(new String[] { "/system/bin/sh" }, environment, home, 80, 24)) {
            startFake();
            return;
        }

        // Each line fits the 80 columns the pty is told it has. mksh's line
        // editor does not wrap a long input: it repaints the row scrolled, with a
        // `<` marker at the right margin, which leaves the command half missing
        // and a stray `<` in the grid. The probes used to be one 160-character
        // line, and every reader of that screen saw exactly that.
        ui.postDelayed(() -> type("tty"), 700);
        ui.postDelayed(() -> type("[ -t 0 ] && echo stdin-is-tty"), 900);
        ui.postDelayed(() -> type("[ -t 1 ] && echo stdout-is-tty"), 1100);
        ui.postDelayed(() -> type("echo size-now=$(stty size)"), 1300);
        ui.postDelayed(() -> type("trap 'echo SIGWINCH-arrived; "
                + "echo size-after=$(stty size)' WINCH"), 1500);
        ui.postDelayed(() -> type("echo still-alive; stty size"), 3500);
        // `--ez sizeProbes false` leaves the size questions to the caller: a
        // service typing its own at the same moment interleaves on the pty, and
        // a fixed number of timed probes runs out while a test is still resizing.
        if (sizeProbes) {
            for (int i = 0; i < 6; i++) {
                ui.postDelayed(() -> type("echo size-now=$(stty size)"), 5000 + i * 3000L);
            }
        }
        // Snapshots, not only at events: the shell's answers to the probes above
        // arrive between them, and a log that only records events is a log the
        // tests cannot read the pty's state from.
        for (int i = 0; i < 8; i++) {
            ui.postDelayed(() -> writeEvidence("dump cursor=" + terminal.cursor()),
                    1500 + i * 3000L);
        }
    }

    /**
     * Records whether this app can run programs it does not own: Termux's shell,
     * a system binary, and a copy of a binary inside this app's own data dir.
     *
     * <p>The last one is the interesting one for "inline pty": Android 10 stopped
     * apps targeting 29 or later from executing files in their writable home
     * directory, which is why Termux still ships with targetSdk 28.
     */
    private void probeForeignPrograms() {
        record("/data/data/com.termux/files/usr/bin/bash");
        record("/system/bin/sh");
        File copy = new File(getFilesDir(), "sh-copy");
        try (java.io.InputStream in = new java.io.FileInputStream("/system/bin/sh")) {
            try (FileOutputStream out = new FileOutputStream(copy)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
            }
            copy.setExecutable(true, true);
        } catch (IOException e) {
            writeEvidence("copy:failed:" + e.getMessage());
        }
        record(copy.getAbsolutePath());
    }

    private void record(String path) {
        int[] result = Pty.probeExec(path);
        writeEvidence("exec:" + path + " access=" + errnoName(result[0])
                + " stat=" + errnoName(result[1]) + " exec=" + errnoName(result[2]));
    }

    private static String errnoName(int code) {
        switch (code) {
            case 0:
                return "ok(0)";
            case 1:
                return "EPERM(1)";
            case 2:
                return "ENOENT(2)";
            case 13:
                return "EACCES(13)";
            default:
                return "errno-" + code;
        }
    }

    /**
     * A real Termux session, driven through RUN_COMMAND and a loopback socket.
     *
     * <p>Termux owns the pty - this app cannot have it - so the terminal here is
     * fed by the same bytes the WebView page receives today, and this view is
     * what turns them into accessibility nodes and announcements.
     */
    private void startTermuxSession(String command, String label, boolean tui) {
        termuxMode = true;
        live = new LiveStream();
        int port = live.open(chunk -> ui.post(() -> {
            transcript.append(chunk);
            terminal.feed(chunk);
            status.setText("termux session, " + transcript.length() + " chars");
        }));
        int control = live.openControl();
        writeEvidence("termux:port:" + port + " control:" + control);
        if (port == 0) {
            status.setText("no loopback port");
            return;
        }
        // Recorded before it is sent: when a session never connects back, the
        // wrapper is the only thing worth looking at, and it has to be checked
        // outside the app (`bash -n` on this line).
        writeEvidence("termux:command:" + TermuxSession.wrap(port, control,
                command == null ? "bash -i" : command));
        try {
            if (command == null) {
                TermuxSession.start(this, port, control, "bash -i", label == null
                        ? "inline terminal sample" : label);
            } else {
                TermuxSession.start(this, port, control, command, label);
            }
        } catch (RuntimeException refused) {
            // Termux's RunCommandService is guarded by its own permission, so a
            // missing grant throws here rather than in Termux.
            writeEvidence("termux:start-failed:" + refused);
            status.setText("Termux refused: " + refused.getMessage());
        }

        Thread driver = new Thread(() -> {
            for (int i = 0; i < 80 && !live.connected(); i++) {
                sleep(100);
            }
            writeEvidence("termux:connected:" + live.connected());
            if (!live.connected()) {
                return;
            }
            if (tui) {
                // A TUI draws when it is ready and keeps redrawing; give it time,
                // then record the screen as the view has it. Nothing is typed:
                // opencode's composer is not a shell prompt.
                sleep(14_000);
                writeEvidence("opencode:drawn");
                sleep(6_000);
                writeEvidence("opencode:still-drawn");
                return;
            }
            sleep(1200);
            // What the session thinks it is, answered by the session itself.
            submit("tty; [ -t 0 ] && echo session-stdin-is-tty; "
                    + "[ -t 1 ] && echo session-stdout-is-tty; echo size=$(stty size)");
            sleep(1500);
            submit("echo TERMUX-SESSION-OK");
            sleep(1200);
            // Output arriving with nobody typing: the case a screen reader has
            // to hear about while the user sits still.
            submit("for i in 1 2 3; do echo TICK-$i; sleep 1; done");
            sleep(4500);
            writeEvidence("termux:after-ticks");
            // Keeps asking the session what size it has, so a resize from the
            // app side is visible in the evidence as a changed answer - and keeps
            // taking snapshots either way, because output the service types in
            // only reaches the evidence through one.
            //
            // `--ez sizeProbes false` stops the questions, not the snapshots: a
            // submit from the app at the same moment as a submit from the service
            // interleaves on the socket, and the shell runs one mangled line
            // instead of two.
            for (int i = 0; i < 8; i++) {
                sleep(3000);
                if (sizeProbes) {
                    submit("echo size-now=$(stty size)");
                    sleep(600);
                }
                writeEvidence("termux:snapshot");
            }
        }, "termux-driver");
        driver.setDaemon(true);
        driver.start();
    }

    /**
     * The view has a new grid: give the same size to whatever owns the pty.
     *
     * <p>Two mechanisms, one cause. An app-owned pty takes `ioctl(TIOCSWINSZ)` on
     * the master; a Termux-owned one takes `stty` from inside Termux, over the
     * control port. Both end with the program getting SIGWINCH and redrawing.
     */
    private void onGridResized(int cols, int rows) {
        writeEvidence("grid:" + cols + "x" + rows);
        if (termuxMode) {
            LiveStream stream = live;
            if (stream != null) {
                stream.sendControl(cols + " " + rows);
            }
            return;
        }
        int fd = master;
        if (fd >= 0) {
            int result = Pty.resize(fd, cols, rows);
            writeEvidence("pty:grid-resize:" + result + " " + cols + "x" + rows);
        }
    }

    /** Keys for a full-screen program: the same terminal, without the newline. */
    private void typeRaw(String text) {
        if (text.isEmpty()) {
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (termuxMode) {
            LiveStream stream = live;
            if (stream != null) {
                stream.send(bytes);
            }
            return;
        }
        int fd = master;
        if (fd >= 0) {
            Pty.write(fd, bytes, bytes.length);
        }
    }

    /** One submitted line, to whichever terminal this activity is running. */
    private void submit(String line) {
        if (termuxMode) {
            LiveStream stream = live;
            if (stream != null) {
                stream.send((line + "\r").getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        type(line);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException done) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The rc an interactive {@code /system/bin/sh} sources in place of its own.
     *
     * <p>Without it the prompt is the shell's default, which begins with the
     * current directory: `/data/data/.../files $ `. A one-character prompt is
     * shorter to read, and it is what the synthetic prompt row in the view already
     * looks like.
     */
    private String writeShellRc(String home) {
        File rc = new File(home, ".shrc");
        try (FileOutputStream out = new FileOutputStream(rc)) {
            out.write("PS1='$ '\nPS2='> '\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // An unwritable home only means the shell's default prompt.
        }
        return rc.getAbsolutePath();
    }

    /**
     * Feeds SGR sequences with their text, at addresses a test can find again.
     *
     * <p>The picture is the evidence here: the rows say the escapes were
     * consumed, and a screenshot says which colours reached the pixels.
     */
    private void startColourDemo() {
        status.setText("colour demo");
        ui.postDelayed(() -> {
            terminal.feed("\u001b[2J");
            terminal.feed("\u001b[3;3H\u001b[31mRED-TEXT\u001b[0m");
            terminal.feed("\u001b[3;20H\u001b[32mGREEN-TEXT\u001b[0m");
            terminal.feed("\u001b[4;3H\u001b[1;34mBRIGHT-BLUE\u001b[0m");
            terminal.feed("\u001b[5;3H\u001b[38;5;208mORANGE-256\u001b[0m");
            terminal.feed("\u001b[6;3Hplain-after-reset");
            writeEvidence("cursor:colors cursor=" + terminal.cursor());
        }, 700);
    }

    /**
     * Feeds the escape sequences a program uses to move the cursor, one step at a
     * time, and records the grid and the cursor after each.
     *
     * <p>Same path as the pty: bytes into {@link TerminalView#feed}. The order is
     * known here, so a test can assert where every jump landed without a program's
     * timing in the way.
     */
    private void startCursorDemo() {
        status.setText("cursor demo");
        ui.postDelayed(() -> step("absolute",
                "\u001b[2J\u001b[3;3HAA\u001b[5;7HBB\u001b[1;1HCC"), 500);
        ui.postDelayed(() -> step("relative",
                "\u001b[3;3H\u001b[2A\u001b[4CDD"), 1300);
        ui.postDelayed(() -> step("column-row",
                "\u001b[4d\u001b[10GEE"), 2100);
        ui.postDelayed(() -> step("save-restore",
                "\u001b[s\u001b[6;2HX\u001b[uFF"), 2900);
        ui.postDelayed(() -> step("judge", ""), 3500);
        ui.postDelayed(() -> step("alternate",
                "\u001b[?1049h\u001b[2;5HTUI"), 4300);
        ui.postDelayed(() -> step("main-again", "\u001b[?1049l"), 5100);
    }

    private void step(String name, String escapes) {
        if (!escapes.isEmpty()) {
            terminal.feed(escapes);
        }
        writeEvidence("cursor:" + name + " cursor=" + terminal.cursor());
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
        LiveStream stream = live;
        live = null;
        if (stream != null) {
            stream.close();
        }
        super.onDestroy();
    }
}
