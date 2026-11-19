#!/usr/bin/env bash
#
# Runs the instrumented end-to-end tests over adb against a connected device.
#
#   bash test/android-e2e.sh [app-debug.apk] [app-debug-androidTest.apk]
#   bash test/android-e2e.sh --only <class>[#method]   # one test, nothing else
#   bash test/android-e2e.sh --no-record                # skip the screen recording
#
# Needs: adb in PATH, the device authorised, Termux installed with
# allow-external-apps=true. python3 is optional: it only adds the timing history
# in test/.timings.db and the estimate printed while the tests run.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)

# The run is recorded to test/recordings/<run>.mp4 unless --no-record is given.
record=true
if [ "${1:-}" = "--no-record" ]; then
    record=false
    shift
fi

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
# Installing over the previous build is the normal case and costs well under a
# second for both APKs. CI signs every run with a fresh debug keystore, so an app
# installed from an earlier artifact cannot be upgraded in place and adb refuses
# with INSTALL_FAILED_UPDATE_INCOMPATIBLE: that is the one case worth starting
# from a clean package.
install_apk() {
    local path=$1 package=$2 output
    if output=$(adb install -r "$path" 2>&1); then
        echo "  $package installed over the previous build"
        return 0
    fi
    case "$output" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
            echo "  $package was signed differently; reinstalling it from scratch"
            adb uninstall "$package" >/dev/null 2>&1 || true
            adb install "$path"
            ;;
        *)
            printf '%s\n' "$output" >&2
            return 1
            ;;
    esac
}

install_apk "$apk" "$pkg"
install_apk "$test_apk" "$pkg.test"

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
    # Also on a failure: the recording is worth more then, not less.
    stop_recording
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
    local class=$1 log=$2 label=${3:-$1} started
    start_estimates "$class"
    started=$(date +%s)
    # Window animations are pure wall clock for a test that taps and waits:
    # the flag covers the activities this run starts, and nothing else.
    adb shell am instrument -w --no-window-animation \
        -e class "$class" -e runId "$run_id" "$runner" \
        | tee "$log" || true
    stop_estimates
    # What the phase cost, so the quiet stretches in the recording have a number
    # next to them instead of only a span.
    echo "  $label in $(( $(date +%s) - started ))s"
    if $timing; then
        python3 "$timings" record --log "$timing_log" --run "$run_id" --db "$timings_db" \
            || true
    fi
}

# --- recording ---------------------------------------------------------------
# One MP4 for the run, in a format a player can stream (H.264 in a fragmented
# MP4, written as it records). Tests mark moments with TestMilestones, which logs the
# device's monotonic uptime; the recording starts at a known uptime too, so a
# milestone's second in the video is a subtraction, not a guess. Nothing is
# extracted from the video during the run - test/frames.py does that on demand.
recording=""
recording_saved=""
recording_start_uptime=""
milestone_log=""
milestone_pid=""
recorder_pid=""

start_recording() {
    $record || return 0
    if ! command -v ffmpeg >/dev/null 2>&1; then
        echo "recording skipped: ffmpeg is not installed" >&2
        return 0
    fi
    recording_start_uptime=$(adb shell cat /proc/uptime | awk '{print $1}')
    recording=$root/test/recordings/$(date +%s).mp4
    mkdir -p "$(dirname "$recording")"
    milestone_log=$(mktemp)
    # A cleared buffer: a milestone from an earlier run would otherwise be read
    # back as if it belonged to this one.
    adb logcat -c >/dev/null 2>&1 || true
    adb logcat -s OpencodeSetup:I > "$milestone_log" 2>/dev/null &
    milestone_pid=$!
    # screenrecord hands out raw H.264 and ffmpeg wraps it in a fragmented MP4
    # as it arrives, so the file on disk is already playable while the run is
    # still going. There is no file on the device, nothing to pull when the run
    # ends and no remux step: 720x1560 at 2M is small because a test screen is
    # mostly still, which is what H.264 spends the least on.
    adb exec-out screenrecord --size 720x1560 --bit-rate 2M --time-limit 900 \
        --output-format=h264 - 2>/dev/null \
      | ffmpeg -hide_banner -loglevel error -y -f h264 -i - -c copy \
            -movflags frag_keyframe+empty_moov+default_base_moof \
            "$recording" >/dev/null 2>&1 &
    recorder_pid=$!
    echo "recording to $recording"
}

stop_recording() {
    [ -n "$recording" ] || return 0
    local saved=$recording
    recording=""
    recording_saved=$saved
    # SIGINT lets screenrecord finish the frame it is on and close the stream;
    # ffmpeg then writes its last fragment and exits, which is when the file is
    # complete.
    adb shell "pkill -INT screenrecord" >/dev/null 2>&1 || true
    if [ -n "$recorder_pid" ]; then
        for _ in $(seq 1 50); do
            kill -0 "$recorder_pid" >/dev/null 2>&1 || break
            sleep 0.2
        done
        kill -INT "$recorder_pid" >/dev/null 2>&1 || true
        wait "$recorder_pid" >/dev/null 2>&1 || true
        recorder_pid=""
    fi
    [ -n "$milestone_pid" ] && kill "$milestone_pid" >/dev/null 2>&1 || true
    milestone_pid=""
    python3 - "$saved" "$recording_start_uptime" "$milestone_log" <<'PY'
import json, os, re, sys

recording, start, log = sys.argv[1], float(sys.argv[2]), sys.argv[3]
milestones, busies, opened = [], [], {}
if os.path.isfile(log):
    for line in open(log, errors="replace"):
        found = re.search(r"(milestone|busy-start|busy-end):(\S+) uptime:(\d+)", line)
        if not found:
            continue
        kind, name = found.group(1), found.group(2)
        at = (int(found.group(3)) - start * 1000) / 1000
        if kind == "milestone":
            milestones.append({"name": name, "seconds": round(at, 2)})
        elif kind == "busy-start":
            opened[name] = at
        elif name in opened:
            busies.append({"name": name, "start": round(opened.pop(name), 2),
                           "end": round(at, 2)})
sidecar = os.path.splitext(recording)[0] + ".json"
json.dump({"recording": os.path.basename(recording),
           "startUptime": start,
           "milestones": milestones,
           "busy": busies}, open(sidecar, "w"), indent=2)
print("  %d milestone(s), %d declared busy span(s) -> %s"
      % (len(milestones), len(busies), sidecar))
PY
    echo "  recording: $saved"
}

start_recording

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
    stop_recording
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

echo "== terminal behaviour (heights, PTY size, rendering) =="
# Three classes in one instrumentation run: each start costs a couple of seconds
# and none of them needs the app in a different state.
#   heights - the last line fits at each of three heights.
#   size - a progress line that redraws itself with a carriage return is
#     formatted to the PTY's width. At script's 80x24 default it wraps on a
#     phone terminal and leaves the previous fragment behind, so the app makes
#     the PTY the emulator's own columns and reports both numbers in events.log.
#   rendering - raw bytes are written into the real terminal page and the screen
#     it publishes is asserted: a carriage return must overwrite, SGR must not
#     print, ESC[2K must erase, a backspace must step back, and a line longer
#     than the terminal must wrap - including cursor-up rewriting, clearing the
#     whole screen, scrolling past the screen, and a carriage return after a
#     wrap. One case per class method, ten in all.
terminal_log=$(mktemp)
run_phase "$pkg.TerminalHeightTest,$pkg.PtySizeTest,$pkg.PtyRenderingTest" \
    "$terminal_log" "terminal behaviour"
grep -q 'OK (12 tests)' "$terminal_log" || { echo "terminal behaviour tests FAILED" >&2; exit 1; }

echo "== denial path (permission revoked, separate run) =="
# Revoking a dangerous permission kills the target process, hence a second run.
adb shell "pm revoke $pkg com.termux.permission.RUN_COMMAND" || true
run_phase "$pkg.PermissionDeniedTest" "$denied_log"
grep -q 'OK (1 test)' "$denied_log" || { echo "denial path FAILED" >&2; exit 1; }

# Put the device back the way the run found it: the denial phase revoked the
# permission, and leaving it revoked makes the next manual tap ask for it again.
adb shell "pm grant $pkg com.termux.permission.RUN_COMMAND" >/dev/null 2>&1 || true

stop_recording

echo
echo "both runs passed"

if $timing; then
    echo
    echo "== timings (test/.timings.db) =="
    python3 "$timings" history --db "$timings_db" || true
    echo
    echo "== where the time went (test/.timings.db) =="
    python3 "$timings" steps --db "$timings_db" --run "$run_id" || true
fi

if [ -n "$recording_saved" ]; then
    echo
    echo "frames come out of the recording on demand:"
    echo "  python3 test/frames.py list --milestones ${recording_saved%.mp4}.json"
fi
