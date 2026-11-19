#!/data/data/com.termux/files/usr/bin/bash
#
# Remove opencode from Termux, together with the apt repository install.sh added.
#
#   bash uninstall.sh            # remove the program and the repository
#   bash uninstall.sh --purge    # also delete credentials, sessions and settings
#
# The --purge form is the only one that touches files under $HOME.
set -euo pipefail

PURGE=0
case "${1:-}" in
    --purge) PURGE=1 ;;
    "") ;;
    *)
        echo "usage: bash uninstall.sh [--purge]" >&2
        exit 1
        ;;
esac

die() {
    echo "FAILED: $*" >&2
    exit 1
}

# ── preflight ──────────────────────────────────────────────────────────
[ -n "${PREFIX:-}" ] || die "run this inside Termux (PREFIX is unset)"
command -v pkg >/dev/null || die "pkg not found - is this Termux?"

if pgrep -f 'apt |dpkg ' >/dev/null 2>&1; then
    echo "waiting for another apt/dpkg run to finish (it holds the dpkg lock)..."
    for _ in $(seq 1 60); do
        pgrep -f 'apt |dpkg ' >/dev/null 2>&1 || break
        sleep 5
    done
fi

# ── the package ────────────────────────────────────────────────────────
if dpkg -s opencode >/dev/null 2>&1; then
    echo "removing the opencode package..."
    pkg uninstall -y opencode
else
    echo "opencode is not installed; cleaning up the repository only."
fi

# ── the repository install.sh added ────────────────────────────────────
dropped=0
for f in "$PREFIX"/etc/apt/sources.list.d/*opencode* "$PREFIX"/etc/apt/trusted.gpg.d/*opencode*; do
    [ -e "$f" ] || continue
    echo "removing $f"
    rm -f "$f"
    dropped=1
done

if [ "$dropped" = 1 ]; then
    echo "refreshing the package lists..."
    pkg update -y >/dev/null 2>&1 ||
        echo "warning: pkg update failed - run it yourself before the next install"
fi

# ── what stays, unless asked otherwise ─────────────────────────────────
CONFIG="$HOME/.config/opencode"
DATA="$HOME/.local/share/opencode"

if [ "$PURGE" = 1 ]; then
    for d in "$CONFIG" "$DATA" "$HOME/.local/state/opencode" "$HOME/.cache/opencode"; do
        if [ -e "$d" ]; then
            echo "removing $d"
            rm -rf "$d"
        fi
    done
    echo "credentials, sessions and settings deleted."
else
    echo
    echo "Your settings, credentials and sessions are still in place:"
    for d in "$CONFIG" "$DATA"; do
        if [ -e "$d" ]; then
            echo "  $d"
        fi
    done
    echo "Delete them too with: bash uninstall.sh --purge"
fi

# A glibc install from the official script would still shadow the package.
if command -v opencode >/dev/null 2>&1; then
    echo
    echo "note: another opencode is still on PATH at $(command -v opencode)"
    echo "      it comes from an earlier glibc install; remove that directory and"
    echo "      the line pointing at it in ~/.bashrc, then run hash -r"
fi

echo
echo "Done."
