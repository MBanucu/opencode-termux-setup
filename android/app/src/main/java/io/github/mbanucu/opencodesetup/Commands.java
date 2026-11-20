package io.github.mbanucu.opencodesetup;

/** The commands this app can hand to Termux. Defined once, used by the UI and by adb. */
final class Commands {

    static final String INSTALL =
            "curl -fsSL https://raw.githubusercontent.com/MBanucu/opencode-termux-setup/main/install.sh"
                    + " | bash";

    private static final String UNINSTALL_BASE =
            "curl -fsSL https://raw.githubusercontent.com/MBanucu/opencode-termux-setup/main/uninstall.sh";

    /** Remove the package and the apt repository, keep credentials and sessions. */
    static final String UNINSTALL = UNINSTALL_BASE + " | bash";

    /** The same, and delete ~/.config/opencode and ~/.local/share/opencode too. */
    static final String UNINSTALL_PURGE = UNINSTALL_BASE + " | bash -s -- --purge";

    static final String CHECK =
            "opencode --version 2>/dev/null || echo 'opencode is not installed'";

    /** The agent itself, started in this app's own terminal. */
    static final String OPENCODE = "opencode";

    private Commands() {
    }
}
