package io.github.mbanucu.samples.nativeterminal;

/** The native pseudo terminal, one descriptor, no Termux and no socket. */
final class Pty {

    static {
        System.loadLibrary("native_terminal");
    }

    private Pty() {
    }

    /** Opens a pty of the given size and starts {@code argv} on it; returns the master fd. */
    static native int start(String[] argv, String[] env, String cwd, int cols, int rows, int[] pid);

    static native int read(int fd, byte[] buffer);

    static native int write(int fd, byte[] bytes, int length);

    static native int resize(int fd, int cols, int rows);

    static native void close(int fd);

    static native int waitFor(int pid);
}
