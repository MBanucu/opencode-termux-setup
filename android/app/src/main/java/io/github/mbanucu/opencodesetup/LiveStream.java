package io.github.mbanucu.opencodesetup;

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
 * The loopback port Termux copies a running command's output to.
 *
 * <p>Lifted out of {@link PageActivity}, which had grown to hold the page's
 * furniture, its terminal and this transport at once. Termux connects back to
 * this port as the command writes, which is what fills the terminal while apt is
 * still working instead of only when it exits.
 *
 * <p>One port is open at a time: {@link #open} closes whatever was there before,
 * so a second command can never inherit the previous one's stream.
 */
final class LiveStream {

    private static final String TAG = "OpencodeSetup";

    private ServerSocket socket;

    /**
     * Opens a port on the loopback address and streams what arrives to
     * {@code sink}, one chunk per read. Returns the port, or {@code 0} when no
     * port could be opened - the command still runs and its result still comes
     * back through the receiver, just without live output.
     */
    int open(Consumer<String> sink) {
        close();
        try {
            // 127.0.0.1 by name: getLoopbackAddress() may hand back ::1, and
            // Termux's bash connects to the IPv4 literal.
            ServerSocket opened = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            socket = opened;
            Thread pump = new Thread(() -> pump(opened, sink), "termux-output");
            pump.setDaemon(true);
            pump.start();
            return opened.getLocalPort();
        } catch (IOException noPort) {
            Log.w(TAG, "could not open a local port for live output", noPort);
            return 0;
        }
    }

    /** Closes the port; a pump blocked in {@code accept} or {@code read} ends. */
    void close() {
        ServerSocket open = socket;
        socket = null;
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static void pump(ServerSocket server, Consumer<String> sink) {
        try (Socket connection = server.accept()) {
            Reader reader = new InputStreamReader(connection.getInputStream(),
                    StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int read;
            while (!server.isClosed() && (read = reader.read(buffer)) >= 0) {
                sink.accept(new String(buffer, 0, read));
            }
        } catch (IOException done) {
            // The command exited or the page went away; the result follows.
        }
    }
}
