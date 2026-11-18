#!/data/data/com.termux/files/usr/bin/bash
#
# Install opencode on Termux (Android / aarch64).
#
#   bash install.sh          # stable channel (recommended)
#   bash install.sh v1       # legacy 1.x line
#   bash install.sh dev      # bleeding edge
#
# Native Bionic build: no proot, no glibc-runner, no patchelf.
# Installs from the signed apt repo of bd-loser/opencode-bionic, so later
# updates come through `pkg upgrade opencode` like any other Termux package.
#
set -euo pipefail

CHANNEL="${1:-${OPENCODE_CHANNEL:-stable}}"
SETUP_URL="https://raw.githubusercontent.com/bd-loser/opencode-bionic/main/repo-setup.sh"

case "$CHANNEL" in
    stable | v1 | dev) ;;
    *)
        echo "channel must be stable, v1 or dev (got: $CHANNEL)" >&2
        exit 1
        ;;
esac

die() {
    echo "FAILED: $*" >&2
    exit 1
}

# ── preflight ──────────────────────────────────────────────────────────
[ -n "${PREFIX:-}" ] || die "run this inside Termux (PREFIX is unset)"
[ "$(uname -m)" = "aarch64" ] || die "needs aarch64, this is $(uname -m)"
command -v pkg >/dev/null || die "pkg not found - is this Termux?"
command -v curl >/dev/null || pkg install -y curl

if pgrep -f 'apt |dpkg ' >/dev/null 2>&1; then
    echo "waiting for another apt/dpkg run to finish (it holds the dpkg lock)..."
    for _ in $(seq 1 60); do
        pgrep -f 'apt |dpkg ' >/dev/null 2>&1 || break
        sleep 5
    done
fi

# ── install ────────────────────────────────────────────────────────────
echo "installing opencode (channel: $CHANNEL)..."

TMP="$(mktemp "${TMPDIR:-/tmp}/opencode-bionic.XXXXXX")"
trap 'rm -f "$TMP"' EXIT
curl -fsSL --connect-timeout 10 --max-time 60 "$SETUP_URL" -o "$TMP" ||
    die "could not download the setup script - check your connection"

TERMUX_PACKAGE=opencode OPENCODE_CHANNEL="$CHANNEL" sh "$TMP"

# ── verify ─────────────────────────────────────────────────────────────
echo
"$PREFIX/bin/opencode" --version || die "opencode installed but does not run"

cat <<'EOF'

Done. Next steps:
  opencode auth     # connect a model provider
  cd <your-project>
  opencode          # start the TUI

Update:    pkg upgrade opencode
Uninstall: pkg uninstall opencode
EOF
