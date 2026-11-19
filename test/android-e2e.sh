#!/usr/bin/env bash
#
# Runs the instrumented end-to-end tests over adb against a connected device.
#
#   bash test/android-e2e.sh [app-debug.apk] [app-debug-androidTest.apk]
#   bash test/android-e2e.sh --only <class>[#method]   # one test, nothing else
#
# Needs: adb in PATH, the device authorised, Termux installed with
# allow-external-apps=true. python3 is optional: it only adds the timing history
# in test/.timings.db and the estimate printed while the tests run.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)

# A single class or one method of it, for the loop where waiting for the whole
# suite is the slow part. Everything else - install, timings, the grant dance -
# is the same.
only=""
if [ "${1:-}" = "--only" ]; then
    only=${2:-}
    [ -n "$only" ] || { echo "--only needs a class or class#method" >&2; exit 2; }
    shift 2
fi

apk=${1:-$root/android/app/build/outputs/apk/debug/app-debug.apk}
test_apk=${2:-$root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}
pkg=io.github.mbanucu.opencodesetup
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
timings=$root/test/timings.py
timings_db=$root/test/.timings.db

for f in "$apk" "$test_apk"; do
    [ -f "$f" ] || { echo "missing $f (build it first)" >&2; exit 1; }
done

# Timings are a convenience: without python3 the tests run and pass as before.
timing=false
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sqlite3' >/dev/null 2>&1; then
    timing=true
fi

echo "== device =="
adb wait-for-device
adb shell getprop ro.build.version.release

echo "== install =="
# CI signs every run with a fresh debug keystore, so an app installed from an
# earlier artifact cannot be upgraded in place: adb refuses with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE. Start from a clean package instead.
adb uninstall "$pkg" >/dev/null 2>&1 || true
adb uninstall "$pkg.test" >/dev/null 2>&1 || true
adb install "$apk"
adb install "$test_apk"

# mktemp honours TMPDIR: Termux has no writable /tmp, so a hard-coded path made
# tee fail and took the whole run down before the second class.
setup_log=$(mktemp)
denied_log=$(mktemp)
timing_log=$(mktemp)
run_id=$(date +%s)
logcat_pid=""
watcher_pid=""

cleanup() {
    [ -n "$watcher_pid" ] && kill "$watcher_pid" >/dev/null 2>&1 || true
    [ -n "$logcat_pid" ] && kill "$logcat_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if $timing; then
    # One stream for the whole session; each test logs its own duration.
    adb shell logcat -c >/dev/null 2>&1 || true
    adb logcat -s OpencodeSetup:I > "$timing_log" 2>/dev/null &
    logcat_pid=$!
fi

estimate() {
    python3 "$timings" eta --class "$1" --run "$run_id" --log "$timing_log" \
        --db "$timings_db" 2>/dev/null || true
}

predict() {
    # A run id that never executes, so every test counts as still to come.
    python3 "$timings" eta --class "$1" --run "plan-$run_id" --db "$timings_db" \
        2>/dev/null || true
}

start_estimates() {
    $timing || return 0
    ( while :; do estimate "$1"; sleep 5; done ) &
    watcher_pid=$!
}

stop_estimates() {
    [ -n "$watcher_pid" ] && kill "$watcher_pid" >/dev/null 2>&1 || true
    watcher_pid=""
}

# Runs one instrumentation class, printing the estimate from history while it
# runs and recording afterwards what each test actually took. The result is
# judged by the caller from the runner's own output.
run_phase() {
    local class=$1 log=$2
    start_estimates "$class"
    adb shell am instrument -w -e class "$class" -e runId "$run_id" "$runner" \
        | tee "$log" || true
    stop_estimates
    if $timing; then
        python3 "$timings" record --log "$timing_log" --run "$run_id" --db "$timings_db" \
            || true
    fi
}

if [ -n "$only" ]; then
    echo "== $only (one test) =="
    # The grant the phases below rely on, so a single test starts the same way.
    adb shell "pm grant $pkg com.termux.permission.RUN_COMMAND" >/dev/null 2>&1 || true
    single_log=$(mktemp)
    run_phase "$only" "$single_log"
    grep -qE '^OK \([0-9]+ tests?\)' "$single_log" || {
        echo "$only FAILED" >&2
        exit 1
    }
    echo
    echo "one test passed"
    exit 0
fi

echo "== setup flow (permission granted) =="
adb shell "pm grant $pkg com.termux.permission.RUN_COMMAND" || true
if $timing; then
    predict "$pkg.SetupFlowTest"
else
    echo "(python3 not found: no timing history this run)"
fi
run_phase "$pkg.SetupFlowTest" "$setup_log"
grep -q 'OK (6 tests)' "$setup_log" || { echo "setup flow FAILED" >&2; exit 1; }

echo "== terminal heights (last line fits at each) =="
height_log=$(mktemp)
run_phase "$pkg.TerminalHeightTest" "$height_log"
grep -q 'OK (1 test)' "$height_log" || { echo "terminal height test FAILED" >&2; exit 1; }

echo "== PTY size (the terminal's width reaches Termux) =="
# A progress line that redraws itself with a carriage return is formatted to the
# PTY's width. At script's 80x24 default it wraps on a phone terminal and leaves
# the previous fragment behind, so the app resizes the PTY to the emulator's own
# columns and reports both numbers in events.log, which this test waits for.
pty_log=$(mktemp)
run_phase "$pkg.PtySizeTest" "$pty_log"
grep -q 'OK (1 test)' "$pty_log" || { echo "PTY size test FAILED" >&2; exit 1; }

echo "== PTY rendering (carriage returns, escapes, wrapping) =="
# Raw bytes are written into the real terminal page and the screen it publishes
# is asserted: a carriage return must overwrite, SGR must not print, ESC[2K must
# erase, a backspace must step back, and a line longer than the terminal must
# wrap - including the multi-line cases: cursor-up rewriting, clearing the whole
# screen, scrolling past the screen, and a carriage return after a wrap. One case
# per class method, ten in all.
pty_render_log=$(mktemp)
run_phase "$pkg.PtyRenderingTest" "$pty_render_log"
grep -q 'OK (10 tests)' "$pty_render_log" || { echo "PTY rendering tests FAILED" >&2; exit 1; }

echo "== denial path (permission revoked, separate run) =="
# Revoking a dangerous permission kills the target process, hence a second run.
adb shell "pm revoke $pkg com.termux.permission.RUN_COMMAND" || true
run_phase "$pkg.PermissionDeniedTest" "$denied_log"
grep -q 'OK (1 test)' "$denied_log" || { echo "denial path FAILED" >&2; exit 1; }

# Put the device back the way the run found it: the denial phase revoked the
# permission, and leaving it revoked makes the next manual tap ask for it again.
adb shell "pm grant $pkg com.termux.permission.RUN_COMMAND" >/dev/null 2>&1 || true

echo
echo "both runs passed"

if $timing; then
    echo
    echo "== timings (test/.timings.db) =="
    python3 "$timings" history --db "$timings_db" || true
fi

echo "screenshots are in the app's external files dir:"
adb shell "ls /sdcard/Android/data/$pkg/files/screenshots" 2>/dev/null || true
