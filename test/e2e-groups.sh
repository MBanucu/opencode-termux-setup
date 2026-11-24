#!/usr/bin/env bash
# shellcheck shell=bash
#
# Group definitions for the instrumented end-to-end suite.
#
# This file is the single source of truth for which tests a hosted x86_64
# emulator may run and which need a real aarch64 device with opencode. Both
# `test/android-e2e.sh` and `test/e2e-groups-test.sh` source it; the latter
# checks that every class in androidTest/ with a @Test is classified here, so a
# new test cannot silently escape a group.
#
# Groups:
#   ui      - app shell, consent, uninstall page, permission denial. Needs
#             Termux installed, but never opencode or the installer.
#   pty     - terminal/PTY behaviour. Runs commands in Termux but never installs
#             opencode.
#   install - removes and reinstalls the real opencode package and checks the
#             version. Needs aarch64/Bionic Termux and network.
#
# Two further groups are opt-in with `--only`: they are not part of any default
# phase (they are slower or diagnostic), but they are still classified so a
# UI-only run can refuse the ones that need opencode.

# Runs in --ui-only and in the full suite. The two halves are separate phases
# because revoking a permission kills the runner's process.
E2E_SETUP_UI_CLASSES=(
    io.github.mbanucu.opencodesetup.SetupFlowUiTest
)
E2E_DENIAL_CLASSES=(
    io.github.mbanucu.opencodesetup.PermissionDeniedTest
)
E2E_UI_CLASSES=(
    "${E2E_SETUP_UI_CLASSES[@]}"
    "${E2E_DENIAL_CLASSES[@]}"
)

# Runs in --ui-only and in the full suite.
E2E_PTY_CLASSES=(
    io.github.mbanucu.opencodesetup.TerminalHeightTest
    io.github.mbanucu.opencodesetup.PtySizeTest
    io.github.mbanucu.opencodesetup.PtyRenderingTest
    io.github.mbanucu.opencodesetup.PtyResizeTest
)

# Full suite only: this is the group a hosted x86_64 emulator cannot run.
E2E_INSTALL_CLASSES=(
    io.github.mbanucu.opencodesetup.SetupFlowInstallTest
)

# Opt-in diagnostics and input coverage, selected with --only.
E2E_OPT_IN_PTY_CLASSES=(
    io.github.mbanucu.opencodesetup.GridProbeTest
    io.github.mbanucu.opencodesetup.SlashKeyTest
)

# Opt-in, and install-dependent: refused by --ui-only.
E2E_OPT_IN_INSTALL_CLASSES=(
    io.github.mbanucu.opencodesetup.OpencodeTuiTest
)

# Every classification array, for the consistency test.
# shellcheck disable=SC2034  # read by test/e2e-groups-test.sh
E2E_GROUPS=(
    E2E_UI_CLASSES
    E2E_PTY_CLASSES
    E2E_INSTALL_CLASSES
    E2E_OPT_IN_PTY_CLASSES
    E2E_OPT_IN_INSTALL_CLASSES
)

# `Class#method` becomes `Class`; selectors may be comma separated.
e2e_class_of() {
    local selector=$1
    selector=${selector%%,*}
    selector=${selector%%#*}
    printf '%s' "$selector"
}

# ui | pty | install | opt-in-pty | opt-in-install | unknown
e2e_group_of_class() {
    local wanted=$1 item
    for item in "${E2E_UI_CLASSES[@]}"; do
        [ "$item" = "$wanted" ] && { printf 'ui'; return 0; }
    done
    for item in "${E2E_PTY_CLASSES[@]}"; do
        [ "$item" = "$wanted" ] && { printf 'pty'; return 0; }
    done
    for item in "${E2E_INSTALL_CLASSES[@]}"; do
        [ "$item" = "$wanted" ] && { printf 'install'; return 0; }
    done
    for item in "${E2E_OPT_IN_PTY_CLASSES[@]}"; do
        [ "$item" = "$wanted" ] && { printf 'opt-in-pty'; return 0; }
    done
    for item in "${E2E_OPT_IN_INSTALL_CLASSES[@]}"; do
        [ "$item" = "$wanted" ] && { printf 'opt-in-install'; return 0; }
    done
    printf 'unknown'
}

# True when the group needs opencode or the installer, and so cannot run on the
# hosted x86_64 job.
e2e_is_install_group() {
    case $1 in
        install|opt-in-install) return 0 ;;
        *) return 1 ;;
    esac
}

# Comma-separated `-e class` argument for a list of class names.
e2e_class_list() {
    local IFS=,
    printf '%s' "$*"
}
