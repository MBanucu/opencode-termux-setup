package io.github.mbanucu.terminal;

/**
 * Where a screen sends the bytes it owes a program that asked a question.
 *
 * <p>Set it to the pty's write path. With no sink the screen stays quiet, which
 * is also what keeps a test or a preview from needing a transport.
 */
public interface ReplySink {
    void send(String bytes);
}
