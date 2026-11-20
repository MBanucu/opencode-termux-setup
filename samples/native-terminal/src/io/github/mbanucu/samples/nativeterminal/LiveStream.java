package io.github.mbanucu.samples.nativeterminal;

import android.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The loopback port a Termux session copies its output to, and the way back in.
 *
 * <p>This app cannot reach the pty: it belongs to {@code script}, inside Termux's
 * process and Termux's uid. What it can do is give Termux a socket to write to
 * and read from, which is exactly what the setup app does today. The same socket
 * carries both directions, so the session's stdin is the app's typed bytes.
 */
final class LiveStream {

    private static final String TAG = "PtySample";

    private ServerSocket server;
    private volatile Socket connection;
    /** The second port, for grid changes: the pty is Termux's, so `stty` runs there. */
    private ServerSocket controlServer;
    private volatile Socket controlConnection;
    /** The newest size, replayed when Termux connects after the first layout. */
    private volatile String pendingControl;

    /**
     * Opens a port on the loopback address and streams every chunk Termux writes
     * to {@code sink}. Returns the port, or {@code 0} if none could be opened, in
     * which case the session still runs but its output goes only to Termux.
     */
    int open(Consumer<String> sink) {
        close();
        try {
            // 127.0.0.1 by name: getLoopbackAddress() may hand back ::1, and
            // Termux's bash connects to the IPv4 literal.
            ServerSocket opened = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server = opened;
            Thread pump = new Thread(() -> pump(opened, sink), "termux-output");
            pump.setDaemon(true);
            pump.start();
            return opened.getLocalPort();
        } catch (IOException noPort) {
            Log.w(TAG, "could not open a local port", noPort);
            return 0;
        }
    }

    /** True once the session has connected back, i.e. a write will arrive. */
    boolean connected() {
        return connection != null;
    }

    /**
     * Opens the port the wrapper reads {@code cols rows} lines from.
     *
     * <p>The pty belongs to `script` inside Termux, so this app cannot ioctl it;
     * the wrapper runs `stty -F <tty>` from inside Termux instead, which raises
     * SIGWINCH in the program exactly as a terminal's own resize would. Returns
     * the port, or {@code 0} when none could be opened.
     */
    int openControl() {
        closeControl();
        try {
            ServerSocket opened = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            controlServer = opened;
            Thread pump = new Thread(() -> pumpControl(opened), "termux-control");
            pump.setDaemon(true);
            pump.start();
            return opened.getLocalPort();
        } catch (IOException noPort) {
            Log.w(TAG, "could not open a control port", noPort);
            return 0;
        }
    }

    /** Sends one {@code cols rows} line, or remembers it until Termux connects. */
    void sendControl(String line) {
        pendingControl = line;
        Socket target = controlConnection;
        if (target == null) {
            return;
        }
        Thread writer = new Thread(() -> writeControl(target, line), "termux-control-write");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeControl(Socket socket, String line) {
        try {
            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (IOException gone) {
            Log.w(TAG, "could not send a size", gone);
        }
    }

    /** Sends bytes to the session's stdin, off the UI thread. */
    void send(byte[] bytes) {
        Socket live = connection;
        if (live == null || bytes == null || bytes.length == 0) {
            return;
        }
        byte[] copy = bytes.clone();
        Thread writer = new Thread(() -> {
            try {
                live.getOutputStream().write(copy);
                live.getOutputStream().flush();
            } catch (IOException gone) {
                Log.w(TAG, "could not write to the session", gone);
            }
        }, "termux-input");
        writer.setDaemon(true);
        writer.start();
    }

    void close() {
        closeControl();
        ServerSocket open = server;
        server = null;
        Socket live = connection;
        connection = null;
        if (live != null) {
            try {
                live.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private void closeControl() {
        ServerSocket open = controlServer;
        controlServer = null;
        Socket live = controlConnection;
        controlConnection = null;
        if (live != null) {
            try {
                live.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    /** Accepts the wrapper's control connection and keeps it open for later sizes. */
    private void pumpControl(ServerSocket listening) {
        try (Socket accepted = listening.accept()) {
            controlConnection = accepted;
            String pending = pendingControl;
            if (pending != null) {
                writeControl(accepted, pending);
            }
            byte[] buffer = new byte[256];
            java.io.InputStream in = accepted.getInputStream();
            while (in.read(buffer) >= 0) {
                // Nothing to read: the wrapper only listens.
            }
        } catch (IOException done) {
            // The session exited or the activity went away.
        } finally {
            controlConnection = null;
        }
    }

    private void pump(ServerSocket listening, Consumer<String> sink) {
        try (Socket accepted = listening.accept()) {
            connection = accepted;
            Reader reader = new InputStreamReader(accepted.getInputStream(),
                    StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int read;
            while (!listening.isClosed() && (read = reader.read(buffer)) >= 0) {
                sink.accept(new String(buffer, 0, read));
            }
        } catch (IOException done) {
            // The session exited or the activity went away.
        } finally {
            connection = null;
        }
    }
}
