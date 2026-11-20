#!/usr/bin/env bash
# Checks the adb watcher reacts to a dropped transport.
#
# The test waits on events rather than sleeping: it streams the watcher's log
# with `tail -F` and stops on the first matching line, and it uses
# `adb wait-for-device` instead of polling the device list on a timer.
#
#   bash test/adb-watch-test.sh
set -u

here=$(cd "$(dirname "$0")" && pwd)
log=${ADB_WATCH_LOG:-$here/out/adb-watch.log}
pidfile=$here/out/adb-watch.pid
mkdir -p "$here/out"

say() { printf '%s\n' "$*"; }
fail() { say "FAIL $*"; exit 1; }

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
    setsid nohup bash "$here/adb-watch.sh" >/dev/null 2>&1 < /dev/null &
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
