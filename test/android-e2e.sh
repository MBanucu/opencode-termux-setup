#!/usr/bin/env bash
#
# Runs the instrumented end-to-end tests over adb against a connected device.
#
#   bash test/android-e2e.sh [app-debug.apk] [app-debug-androidTest.apk]
#   bash test/android-e2e.sh --ui-only                  # ui + pty, no installer
#   bash test/android-e2e.sh --only <class>[#method]    # one test, nothing else
#   bash test/android-e2e.sh --ui-only --only <class>   # one test, if it is ui/pty
#   bash test/android-e2e.sh --no-record                # skip the screen recording
#   bash test/android-e2e.sh --ui-only --list           # print the selection, no device
#
# The suite is split by dependency in test/e2e-groups.sh:
#
#   ui      app shell, consent, uninstall page, permission denial
#   pty     terminal/PTY behaviour; runs commands but installs nothing
#   install removes and reinstalls the real opencode package
#
# --ui-only runs ui and pty and refuses an install-dependent selection instead
# of silently skipping it. The install group needs aarch64 Termux and the real
# aarch64 package, so it cannot run on a hosted x86_64 emulator; see
# .github/workflows/emulator-ui.yml and .github/workflows/e2e-arm64.yml.
#
# Needs: adb in PATH and the device authorised. A device adb already lists is
# used directly (an emulator); otherwise adb-harness discovers the wireless
# phone, which needs the e2e requirements installed once:
#
#   python3 -m venv test/.venv
#   test/.venv/bin/pip install -r test/requirements.txt
#
# Termux must be installed with allow-external-apps=true. python3 is otherwise
# optional: it only adds the timing history in test/.timings.db and the estimate
# printed while the tests run.
#
# Set E2E_LOG_DIR to keep each phase's instrumentation output in that directory
# (CI uploads it). The recording goes to test/recordings/<run>.mp4.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=test/e2e-groups.sh
. "$root/test/e2e-groups.sh"

# The run is recorded to test/recordings/<run>.mp4 unless --no-record is given.
record=true
ui_only=false
list_only=false
only=""
apk=""
test_apk=""
positionals=()

usage() {
    sed -n '3,35p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case $1 in
        --no-record) record=false; shift ;;
        --ui-only) ui_only=true; shift ;;
        --list) list_only=true; shift ;;
        --only)
            [ -n "${2:-}" ] || { echo "--only needs a class or class#method" >&2; exit 2; }
            only=$2
            shift 2 ;;
        --help|-h) usage; exit 0 ;;
        --*)
            echo "unknown option: $1 (try --help)" >&2
            exit 2 ;;
        *)
            positionals+=("$1")
            shift ;;
    esac
done

if [ "${#positionals[@]}" -gt 2 ]; then
    echo "expected at most two APKs, got ${#positionals[@]} (try --help)" >&2
    exit 2
fi
apk=${positionals[0]:-$root/android/app/build/outputs/apk/debug/app-debug.apk}
test_apk=${positionals[1]:-$root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}
pkg=io.github.mbanucu.opencodesetup
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
timings=$root/test/timings.py
timings_db=$root/test/.timings.db

# --- selection ---------------------------------------------------------------
# `--only` may name a class or one method of it. Every class has to be in
# test/e2e-groups.sh: an unclassified name is a typo or a new test that nobody
# placed in a group, and either would otherwise run nothing and look green.
if [ -n "$only" ]; then
    IFS=, read -r -a selectors <<<"$only"
    for selector in "${selectors[@]}"; do
        class=$(e2e_class_of "$selector")
        group=$(e2e_group_of_class "$class")
        if [ "$group" = unknown ]; then
            echo "--only $selector is not classified in test/e2e-groups.sh" >&2
            echo "add the class to a group before selecting it directly" >&2
            exit 2
        fi
        if $ui_only && e2e_is_install_group "$group"; then
            echo "--ui-only refuses $selector: it is in the '$group' group and" >&2
            echo "needs opencode or the installer, which a hosted x86_64 emulator" >&2
            echo "cannot run. Use the self-hosted android-arm64 full suite instead." >&2
            exit 2
        fi
    done
fi

# The phases in the order they run. Each entry is a class list for `-e class`,
# its log, the permission action around it, and the exact runner summary a
# success must show. Empty expect means "one or more tests, whatever --only
# selected".
phase_label=()
phase_group=()
phase_classes=()
phase_log=()
phase_perm=()
phase_expect=()
add_phase() {
    phase_label+=("$1")
    phase_group+=("$2")
    phase_classes+=("$3")
    phase_log+=("$4")
    phase_perm+=("$5")
    phase_expect+=("$6")
}

if [ -n "$only" ]; then
    add_phase "one test ($only)" "$(e2e_group_of_class "$(e2e_class_of "$only")")" \
        "$only" "single.log" grant ""
elif $ui_only; then
    add_phase "setup pages (no installer)" ui \
        "$(e2e_class_list "${E2E_SETUP_UI_CLASSES[@]}")" "setup-ui.log" grant "OK (3 tests)"
    add_phase "terminal behaviour (heights, PTY size, rendering)" pty \
        "$(e2e_class_list "${E2E_PTY_CLASSES[@]}")" "terminal.log" none "OK (13 tests)"
    add_phase "denial path (permission revoked, separate run)" ui \
        "$(e2e_class_list "${E2E_DENIAL_CLASSES[@]}")" "denial.log" revoke "OK (1 test)"
else
    add_phase "setup pages (no installer)" ui \
        "$(e2e_class_list "${E2E_SETUP_UI_CLASSES[@]}")" "setup-ui.log" grant "OK (3 tests)"
    add_phase "install flow (real opencode package)" install \
        "$(e2e_class_list "${E2E_INSTALL_CLASSES[@]}")" "install.log" grant "OK (3 tests)"
    add_phase "terminal behaviour (heights, PTY size, rendering)" pty \
        "$(e2e_class_list "${E2E_PTY_CLASSES[@]}")" "terminal.log" none "OK (13 tests)"
    add_phase "denial path (permission revoked, separate run)" ui \
        "$(e2e_class_list "${E2E_DENIAL_CLASSES[@]}")" "denial.log" revoke "OK (1 test)"
fi

# The invariant that makes --ui-only honest: no install-dependent phase can be
# in the plan, even after an edit to the block above.
if $ui_only; then
    for group in "${phase_group[@]}"; do
        if e2e_is_install_group "$group"; then
            echo "internal error: --ui-only selected an install-dependent phase" >&2
            exit 1
        fi
    done
fi

if $list_only; then
    if $ui_only; then echo "mode: ui-only"; else echo "mode: full"; fi
    for i in "${!phase_label[@]}"; do
        echo "phase: ${phase_label[$i]}"
        echo "  group: ${phase_group[$i]}"
        echo "  log: ${phase_log[$i]}"
        echo "  expect: ${phase_expect[$i]:-one-or-more}"
        echo "  classes: ${phase_classes[$i]}"
    done
    exit 0
fi

for f in "$apk" "$test_apk"; do
    [ -f "$f" ] || { echo "missing $f (build it first)" >&2; exit 1; }
done

# Timings are a convenience: without python3 the tests run and pass as before.
timing=false
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sqlite3' >/dev/null 2>&1; then
    timing=true
fi

# --- adb connection -----------------------------------------------------------
# Discovery and reconnection belong to adb-harness, installed from
# test/requirements.txt. Use this repository's venv when it exists, otherwise a
# console script already on PATH (pipx, a system install).
harness_bin() {
    if [ -x "$root/test/.venv/bin/$1" ]; then
        printf '%s\n' "$root/test/.venv/bin/$1"
    elif command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
    else
        return 1
    fi
}

if ! command -v adb >/dev/null 2>&1; then
    echo "adb is missing: install Android platform-tools (pkg install android-tools)" >&2
    exit 3
fi

# True when adb already has an authorised device. A hosted emulator is in this
# state before the script starts; the wireless phone usually is not, so it still
# goes through discovery below.
adb_has_device() {
    adb devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'
}

echo "== connect =="
if adb_has_device; then
    echo "  using the device adb already lists: $(adb get-serialno 2>/dev/null || echo unknown)"
else
    if ! adb_connect=$(harness_bin adb-connect); then
        echo "adb-connect is missing. Install the e2e requirements once:" >&2
        echo "  python3 -m venv test/.venv" >&2
        echo "  test/.venv/bin/pip install -r test/requirements.txt" >&2
        exit 3
    fi

    # adb-connect needs zeroconf for every discovery run. Check it here so a
    # missing python package gets its own message: adb-harness 0.2.3 still
    # reports it as "no service" (exit 1), while exit 3 is reserved for exactly
    # this case.
    harness_python=$(head -n 1 "$adb_connect" | sed 's/^#!//; s/[[:space:]].*//')
    [ -x "$harness_python" ] || harness_python=python3
    if ! "$harness_python" -c 'import zeroconf' >/dev/null 2>&1; then
        echo "python-zeroconf is missing from $harness_python." >&2
        echo "Install the e2e requirements once:" >&2
        echo "  test/.venv/bin/pip install -r test/requirements.txt" >&2
        exit 3
    fi

    # Exit codes: 0 connected, 1 no service, 2 usage, 3 missing prerequisite.
    # Branch on them so "Wireless debugging is off" and "zeroconf is not
    # installed" cannot be mistaken for each other.
    if "$adb_connect" --timeout 8; then
        :
    else
        status=$?
        case $status in
            2) echo "adb-connect rejected its arguments (exit 2)" >&2 ;;
            3) echo "adb-connect is missing a prerequisite (exit 3)" >&2 ;;
            *) echo "no adb device: turn Wireless debugging on and run this again" >&2 ;;
        esac
        exit "$status"
    fi
fi

adb wait-for-device

# Keep what each phase printed when CI or a debugging run asks for it.
log_dir=${E2E_LOG_DIR:-}
if [ -n "$log_dir" ]; then
    mkdir -p "$log_dir"
    # A log from a previous full run would make a ui-only run look as if it had
    # executed the install phase; the workflow checks for exactly that file.
    rm -f "$log_dir/setup-ui.log" "$log_dir/install.log" "$log_dir/terminal.log" \
        "$log_dir/denial.log" "$log_dir/single.log"
fi
new_log() {
    if [ -n "$log_dir" ]; then
        printf '%s/%s\n' "$log_dir" "$1"
    else
        mktemp
    fi
}

echo "== device =="
echo "  serial: $(adb get-serialno 2>/dev/null || echo unknown)"
echo "  release: $(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "  abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
if [ -n "$log_dir" ]; then
    adb devices -l >"$log_dir/adb-devices.txt" 2>&1 || true
    adb shell getprop >"$log_dir/getprop.txt" 2>&1 || true
fi

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
timing_log=$(new_log timing.log)
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
# One MP4 for the run, in a format a player can stream (H.264 in MP4, remuxed
# with faststart). Tests mark moments with TestMilestones, which logs the
# device's monotonic uptime; the recording starts at a known uptime too, so a
# milestone's second in the video is a subtraction, not a guess. Nothing is
# extracted from the video during the run - test/frames.py does that on demand.
recording=""
recording_saved=""
recording_start_uptime=""
milestone_log=""
milestone_pid=""

start_recording() {
    $record || return 0
    adb shell "rm -f /data/local/tmp/suite.mp4" >/dev/null 2>&1 || true
    recording_start_uptime=$(adb shell cat /proc/uptime | awk '{print $1}')
    recording=$root/test/recordings/$(date +%s).mp4
    mkdir -p "$(dirname "$recording")"
    milestone_log=$(mktemp)
    # A cleared buffer: a milestone from an earlier run would otherwise be read
    # back as if it belonged to this one.
    adb logcat -c >/dev/null 2>&1 || true
    adb logcat -s OpencodeSetup:I > "$milestone_log" 2>/dev/null &
    milestone_pid=$!
    # 720x1560 at 2M keeps the file small enough to pull and share; the suite is
    # mostly still UI, which is what H.264 spends the least on.
    adb shell screenrecord --size 720x1560 --bit-rate 2M --time-limit 900 \
        /data/local/tmp/suite.mp4 >/dev/null 2>&1 &
    echo "recording to $recording"
}

stop_recording() {
    [ -n "$recording" ] || return 0
    local saved=$recording
    recording=""
    recording_saved=$saved
    adb shell "pkill -INT screenrecord" >/dev/null 2>&1 || true
    # screenrecord finalises the file when it gets SIGINT; pull as soon as the
    # process is gone instead of after a guessed pause.
    for _ in $(seq 1 25); do
        adb shell "pidof screenrecord" >/dev/null 2>&1 || break
        sleep 0.2
    done
    adb pull /data/local/tmp/suite.mp4 "$saved" >/dev/null 2>&1 || true
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
    # A faststart MP4 plays while it is still being downloaded, which is the
    # difference between a streamable file and one that has to arrive first.
    if command -v ffmpeg >/dev/null 2>&1 && [ -f "$saved" ]; then
        if ffmpeg -hide_banner -loglevel error -y -i "$saved" -c copy \
            -movflags +faststart "$saved.stream.mp4" 2>/dev/null; then
            mv "$saved.stream.mp4" "$saved"
        fi
    fi
    # Written as it records, the file plays but cannot be seeked: a fragmented
    # MP4 carries no index until something closes it. Rewriting it with a real
    # one takes a fraction of a second and is the only pass after the run.
    if [ -s "$saved" ] && command -v ffmpeg >/dev/null 2>&1; then
        if ffmpeg -hide_banner -loglevel error -y -i "$saved" -c copy \
            -movflags +faststart "$saved.index.mp4" 2>/dev/null; then
            mv "$saved.index.mp4" "$saved"
        fi
    fi
    echo "  recording: $saved"
}

start_recording

$timing || echo "(python3 not found: no timing history this run)"

# The phases are the same code path for a full run, --ui-only and --only. What
# differs is only which classes the plan above put in them.
for i in "${!phase_label[@]}"; do
    label=${phase_label[$i]}
    classes=${phase_classes[$i]}
    log=$(new_log "${phase_log[$i]}")
    expect=${phase_expect[$i]}

    case ${phase_perm[$i]} in
        grant)
            # A single test starts the way the phases do; the grant does not
            # change in between, so repeating it is harmless.
            adb shell "pm grant $pkg com.termux.permission.RUN_COMMAND" >/dev/null 2>&1 || true
            ;;
        revoke)
            # Revoking a dangerous permission kills the target process, hence a
            # run of its own for the denial path.
            adb shell "pm revoke $pkg com.termux.permission.RUN_COMMAND" >/dev/null 2>&1 || true
            ;;
    esac

    echo "== $label =="
    if $timing && [ -n "$expect" ]; then
        predict "$classes"
    fi
    run_phase "$classes" "$log" "$label"

    if [ -n "$expect" ]; then
        # The exact summary is the point: a class that matched nothing reports
        # "OK (0 tests)" or a different count, and that must be a failure.
        if ! grep -qF "$expect" "$log"; then
            echo "$label FAILED: expected '$expect'" >&2
            tail -20 "$log" >&2 || true
            exit 1
        fi
    elif ! grep -qE '^OK \([1-9][0-9]* tests?\)' "$log"; then
        echo "$label FAILED: no test ran" >&2
        tail -20 "$log" >&2 || true
        exit 1
    fi
done

# Put the device back the way the run found it: the denial phase revoked the
# permission, and leaving it revoked makes the next manual tap ask for it again.
adb shell "pm grant $pkg com.termux.permission.RUN_COMMAND" >/dev/null 2>&1 || true

stop_recording

echo
if [ -n "$only" ]; then
    echo "one test passed"
elif $ui_only; then
    echo "ui/pty suite passed (install group not run)"
else
    echo "full suite passed (ui, pty and the real install flow)"
fi

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
