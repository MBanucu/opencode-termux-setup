#!/usr/bin/env bash
# Checks the adb watcher reacts to a dropped transport.
#
# The watcher is adb-harness's `adb-watch` console script, installed from
# test/requirements.txt; this test only proves the installed watcher reconnects
# on this device.
#
# The test waits on events rather than sleeping: it streams the watcher's log
# with `tail -F` and stops on the first matching line, and it uses
# `adb wait-for-device` instead of polling the device list on a timer.
#
#   bash test/adb-watch-test.sh
set -u

here=$(cd "$(dirname "$0")" && pwd)
export ADB_WATCH_OUT=${ADB_WATCH_OUT:-$here/out}
log=${ADB_WATCH_LOG:-$ADB_WATCH_OUT/adb-watch.log}
pidfile=$ADB_WATCH_OUT/adb-watch.pid
mkdir -p "$ADB_WATCH_OUT"

say() { printf '%s\n' "$*"; }
fail() { say "FAIL $*"; exit 1; }

harness_bin() {
    if [ -x "$here/.venv/bin/$1" ]; then
        printf '%s\n' "$here/.venv/bin/$1"
    elif command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
    else
        return 1
    fi
}

if ! watcher=$(harness_bin adb-watch); then
    say "adb-watch is missing; install the e2e requirements first:"
    say "  python3 -m venv test/.venv"
    say "  test/.venv/bin/pip install -r test/requirements.txt"
    exit 3
fi
if ! command -v adb >/dev/null 2>&1; then
    say "adb is missing; install Android platform-tools (pkg install android-tools)"
    exit 3
fi

line_count() {
    [ -f "$log" ] && wc -l < "$log" | tr -d ' ' || echo 0
}

watcher_running() {
    local pid
    pid=$(cat "$pidfile" 2>/dev/null || true)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

device_serial() {
    adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

# Blocks until a line added after $1 matches $2, or $3 seconds pass.
await_log() {
    timeout "$3" tail -n +$(( $1 + 1 )) -F "$log" 2>/dev/null | grep -q -m1 -- "$2"
}

if ! watcher_running; then
    say "starting the watcher"
    before=$(line_count)
    setsid nohup "$watcher" >/dev/null 2>&1 < /dev/null &
    await_log "$before" 'connected:' 30 \
        || fail "the watcher never reported a connected device (log: $log)"
fi

serial=$(device_serial)
[ -n "$serial" ] || fail "the watcher is running but adb has no device"
say "ok   watcher is running and connected to $serial"

before=$(line_count)
say "dropping $serial"
adb disconnect "$serial" >/dev/null 2>&1 || true

await_log "$before" 'reconnected:' 45 \
    || fail "the watcher did not log a reconnect after the transport dropped"
timeout 10 adb wait-for-device || fail "adb has no device after the reconnect"
say "ok   watcher reconnected after the transport dropped: $(device_serial)"
say "PASS adb watcher checks"
