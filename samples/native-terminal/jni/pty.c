// A pseudo terminal owned by the app, opened with bionic's forkpty().
//
// This is the whole of what Termux's terminal-emulator does natively, minus the
// screen: open a pty, fork, exec, and hand the master descriptor back to Java.
// Because the app holds the master, it can also resize the pty itself with
// ioctl(TIOCSWINSZ) - no second process, no `stty`, no loopback control port.
//
// Available since API 23: bionic's <pty.h> declares openpty/forkpty, so the
// project's minSdk 24 is enough. Nothing here links libutil, because on Android
// those symbols live in libc.

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

// JNI must not be touched in the child of a fork of a VM that may have other
// threads: convert everything to plain C first, then fork.
static char **to_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) {
        return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t) length + 1, sizeof(char *));
    if (out == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < length; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        out[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, item, chars);
        (*env)->DeleteLocalRef(env, item);
    }
    out[length] = NULL;
    return out;
}

static void free_array(char **array) {
    if (array == NULL) {
        return;
    }
    for (char **item = array; *item != NULL; item++) {
        free(*item);
    }
    free(array);
}

// The termios ssh/tmux expect from a terminal: canonical input, echo, signals.
static void terminal_attributes(struct termios *tios) {
    memset(tios, 0, sizeof *tios);
    tios->c_iflag = ICRNL | IXON | IUTF8;
    tios->c_oflag = OPOST | ONLCR;
    // The speed bits matter: leaving them zero is B0, which says "hang up".
    // With B0 the pty exists, the shell runs, and nothing is ever echoed - the
    // first version of this sample did exactly that.
    tios->c_cflag = CS8 | CREAD | HUPCL;
    cfsetispeed(tios, B38400);
    cfsetospeed(tios, B38400);
    tios->c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
    tios->c_cc[VINTR] = 3;
    tios->c_cc[VEOF] = 4;
    tios->c_cc[VERASE] = 127;
    tios->c_cc[VKILL] = 21;
}

JNIEXPORT jint JNICALL
Java_io_github_mbanucu_samples_nativeterminal_Pty_start(
        JNIEnv *env, jclass cls, jobjectArray argv, jobjectArray envp,
        jstring cwd, jint cols, jint rows, jintArray pid_out) {
    char **args = to_array(env, argv);
    if (args == NULL || args[0] == NULL) {
        free_array(args);
        return -EINVAL;
    }
    char **environment = to_array(env, envp);
    char *directory = NULL;
    if (cwd != NULL) {
        const char *chars = (*env)->GetStringUTFChars(env, cwd, NULL);
        directory = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, cwd, chars);
    }

    struct termios tios;
    terminal_attributes(&tios);
    struct winsize size = {
            .ws_row = (unsigned short) rows,
            .ws_col = (unsigned short) cols,
            .ws_xpixel = (unsigned short) (cols * 8),
            .ws_ypixel = (unsigned short) (rows * 16),
    };

    int master = -1;
    pid_t pid = forkpty(&master, NULL, &tios, &size);
    if (pid < 0) {
        int failure = -errno;
        free_array(args);
        free_array(environment);
        free(directory);
        return failure;
    }
    if (pid == 0) {
        // forkpty already made this a session leader with the slave as fds 0-2,
        // which is what makes the child see a terminal instead of a pipe.
        if (directory != NULL) {
            if (chdir(directory) != 0) {
                _exit(126);
            }
        }
        for (char **item = environment; item != NULL && *item != NULL; item++) {
            char *equals = strchr(*item, '=');
            if (equals != NULL) {
                *equals = '\0';
                setenv(*item, equals + 1, 1);
            }
        }
        execvp(args[0], args);
        _exit(127);
    }

    if (pid_out != NULL) {
        jint process = (jint) pid;
        (*env)->SetIntArrayRegion(env, pid_out, 0, 1, &process);
    }
    free_array(args);
    free_array(environment);
    free(directory);
    return master;
}

// Blocks until bytes are available. Returns the count, or 0 at end of file.
JNIEXPORT jint JNICALL
Java_io_github_mbanucu_samples_nativeterminal_Pty_read(
        JNIEnv *env, jclass cls, jint fd, jbyteArray buffer) {
    jsize length = (*env)->GetArrayLength(env, buffer);
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    ssize_t read_count = read(fd, bytes, (size_t) length);
    // A pty master reports EIO when the last slave closes; that is end of file.
    jint result = read_count < 0 ? (errno == EIO ? 0 : -errno) : (jint) read_count;
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_mbanucu_samples_nativeterminal_Pty_write(
        JNIEnv *env, jclass cls, jint fd, jbyteArray buffer, jint length) {
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    ssize_t written = write(fd, bytes, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return written < 0 ? -errno : (jint) written;
}

// The whole point: the app owns the master, so it can change the size itself.
// ioctl(TIOCSWINSZ) on the master is what a terminal does when its window
// shrinks, and the kernel answers with SIGWINCH to the foreground program.
JNIEXPORT jint JNICALL
Java_io_github_mbanucu_samples_nativeterminal_Pty_resize(
        JNIEnv *env, jclass cls, jint fd, jint cols, jint rows) {
    struct winsize size = {
            .ws_row = (unsigned short) rows,
            .ws_col = (unsigned short) cols,
            .ws_xpixel = (unsigned short) (cols * 8),
            .ws_ypixel = (unsigned short) (rows * 16),
    };
    if (ioctl(fd, TIOCSWINSZ, &size) != 0) {
        return -errno;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_io_github_mbanucu_samples_nativeterminal_Pty_close(JNIEnv *env, jclass cls, jint fd) {
    close(fd);
}

JNIEXPORT jint JNICALL
Java_io_github_mbanucu_samples_nativeterminal_Pty_waitFor(JNIEnv *env, jclass cls, jint pid) {
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) {
        return -errno;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : -WTERMSIG(status);
}
