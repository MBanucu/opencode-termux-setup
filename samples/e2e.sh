#!/usr/bin/env bash
# End-to-end checks for the sample apps, driven the way a screen reader drives
# an app: an AccessibilityService performs the actions (ACTION_SET_TEXT, and the
# tree it dumps), and the assertions are the events and rows it is given. Nothing
# here taps a coordinate or types with `input text`.
#
#   bash test/local-build.sh --samples    # builds both APKs, then runs this suite
#   bash samples/e2e.sh                   # the fast checks (APKs already installed)
#   bash samples/e2e.sh --opencode        # also the TUI (slow, needs opencode)
#   bash samples/e2e.sh --only tui-echo   # just one check
#   bash samples/e2e.sh --only pty,tui-bg # several checks, one install
#   bash samples/e2e.sh --list            # the check names
#   bash samples/e2e.sh --quiet           # failures and the summary only
#   bash samples/e2e.sh --restore         # put back what a dead run left changed
#
# The probe service is enabled for the run, and the accessibility settings are
# put back the way they were found - even when a check fails or the run is
# interrupted. The display's size is never changed: a window is resized through
# the app itself.
set -u

ADB=${ADB:-adb}
APP=io.github.mbanucu.samples.nativeterminal
PROBE=io.github.mbanucu.samples.a11yprobe
APK=samples/out/native-terminal.apk
PROBE_APK=samples/out/a11y-probe.apk
FILES=/sdcard/Android/data/$APP/files
PROBE_FILES=/sdcard/Android/data/$PROBE/files
OPENCODE=0
RESTORE_ONLY=0
LIST_ONLY=0
QUIET=0
ONLY=""
case "${1:-}" in
    --opencode) OPENCODE=1 ;;
    --restore) RESTORE_ONLY=1 ;;
    --list) LIST_ONLY=1 ;;
    --quiet) QUIET=1 ;;
    --only) ONLY=${2:-} ;;
esac
# A comma is how a shell user usually writes a list; spaces are how the loop reads it.
ONLY=${ONLY//,/ }
START=$SECONDS
CHECKS_RUN=0
CHECKS="pty cursor cursor-escapes colour tui tui-echo tui-caret tui-blink tui-bg tui-symbols tui-modes resize rows session session-resize opencode"
FAIL=0
FAIL_COUNT=0

say() { printf '%s\n' "$*"; }
ok() { [ "$QUIET" = 1 ] || say "ok   $*"; }
bad() { say "FAIL $*"; FAIL=1; FAIL_COUNT=$((FAIL_COUNT + 1)); }

device() { $ADB "$@"; }

# A dropped wireless session is not a failing check: ask mDNS for the current
# port before deciding the suite cannot run. The watcher (test/adb-watch.sh)
# normally keeps this from being needed, but a suite started by hand should not
# require it.
ensure_device() {
    device get-state >/dev/null 2>&1 && return 0
    local connector
    connector=$(cd "$here/.." && pwd)/test/adb-connect.py
    [ -f "$connector" ] || return 1
    say "no device; asking mDNS for the wireless-debugging port"
    python3 "$connector" 2>&1 | sed 's/^/  /'
    device get-state >/dev/null 2>&1
}

# --- device state -------------------------------------------------------------
# The suite changes one setting that belongs to the phone's owner: the
# accessibility service it drives, and switching it off turns a screen reader off
# for good when a run dies before its cleanup. Saving the value first is what
# makes "put it back" true.
#
# The saved values go to disk, not only into this shell, because a run killed
# outright never reaches its trap. The file is what lets the next run - or
# `--restore` - put it back, and it is only deleted once the phone is seen to be
# back. The display size is saved only so a state file written by an older run
# (which resized the display) is still repaired; this suite never changes it.

here=$(cd "$(dirname "$0")" && pwd)
STATE_FILE=$here/out/device-state.txt
RUN_STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TIMING_LOG=$here/out/e2e-timings.log
: > "$TIMING_LOG"
SAVED_SIZE=""
SAVED_SERVICES=""
SAVED_ACCESSIBILITY=""
STATE_SAVED=0

# The size in `wm size`'s "Override size:" line; empty when the display has none.
read_size_override() {
    device shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Override size: *//p'
}

read_secure_setting() {
    device shell settings get secure "$1" 2>/dev/null | tr -d '\r'
}

# The three saved values, one per line; empty is a value too, so the order counts.
load_device_state() {
    [ -f "$STATE_FILE" ] || return 1
    SAVED_SIZE=$(sed -n '1p' "$STATE_FILE")
    SAVED_SERVICES=$(sed -n '2p' "$STATE_FILE")
    SAVED_ACCESSIBILITY=$(sed -n '3p' "$STATE_FILE")
    STATE_SAVED=1
}

persist_device_state() {
    mkdir -p "$(dirname "$STATE_FILE")" || return 1
    printf '%s\n%s\n%s\n' "$SAVED_SIZE" "$SAVED_SERVICES" "$SAVED_ACCESSIBILITY" \
        > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

save_device_state() {
    [ "$STATE_SAVED" = 1 ] && return 0
    # Nothing to save on an unreachable device, and nothing to change on it either.
    device get-state >/dev/null 2>&1 || return 0
    SAVED_SIZE=$(read_size_override)
    SAVED_SERVICES=$(read_secure_setting enabled_accessibility_services)
    SAVED_ACCESSIBILITY=$(read_secure_setting accessibility_enabled)
    STATE_SAVED=1
    persist_device_state || say "WARNING: could not write $STATE_FILE;" \
        "a run that is killed will not be able to put the device back"
}

# A secure setting back to what it was; "null" and empty are Android's "unset".
put_secure_setting() {
    case "${2:-}" in
        ''|null) device shell settings delete secure "$1" >/dev/null 2>&1 ;;
        *) device shell settings put secure "$1" "$2" >/dev/null 2>&1 ;;
    esac
}

# The display as the run found it: its override if it had one, else no override.
restore_display() {
    if [ -n "$SAVED_SIZE" ]; then
        device shell wm size "$SAVED_SIZE" >/dev/null 2>&1
    else
        device shell wm size reset >/dev/null 2>&1
    fi
}

setting_matches() { # key saved-value
    local current
    current=$(read_secure_setting "$1")
    [ "${current:-null}" = "${2:-null}" ]
}

state_is_restored() {
    [ "$(read_size_override)" = "$SAVED_SIZE" ] || return 1
    setting_matches enabled_accessibility_services "$SAVED_SERVICES" || return 1
    setting_matches accessibility_enabled "$SAVED_ACCESSIBILITY"
}

# Puts the saved values back, and only forgets them once the phone agrees they
# are back. Retried: a connection that dropped during a resize comes back.
put_device_back() {
    local attempt
    for attempt in 1 2 3 4 5; do
        restore_display
        put_secure_setting enabled_accessibility_services "$SAVED_SERVICES"
        put_secure_setting accessibility_enabled "$SAVED_ACCESSIBILITY"
        if state_is_restored; then
            STATE_SAVED=0
            rm -f "$STATE_FILE"
            return 0
        fi
        sleep 1
    done
    return 1
}

# What an earlier run saved and did not get to put back, done before anything
# else, so a rerun repairs the phone instead of photographing the damage.
recover_previous_run() {
    load_device_state || return 0
    say "a previous run left the device changed; putting it back first"
    if put_device_back; then
        say "restored"
        return 0
    fi
    say "could not restore it; the saved state is in $STATE_FILE"
    return 1
}

# `--restore`: put back what a dead run saved, or, with nothing saved, undo the
# change this suite makes to a phone it knows nothing about.
restore_only() {
    if ! device get-state >/dev/null 2>&1; then
        say "no device reachable (adb get-state failed)"
        return 1
    fi
    if load_device_state; then
        if put_device_back; then
            say "display and accessibility settings restored"
            return 0
        fi
        say "could not restore the saved state; it is still in $STATE_FILE"
        return 1
    fi
    say "no saved state; removing this suite's accessibility probe"
    local services remaining
    services=$(read_secure_setting enabled_accessibility_services)
    case "$services" in
        ''|null) ;;
        *"$PROBE/.ProbeService"*)
            remaining=$(printf '%s' "$services" | tr ':' '\n' \
                | grep -vxF "$PROBE/.ProbeService" | tr '\n' ' ')
            remaining=${remaining% }
            remaining=${remaining// /:}
            if [ -n "$remaining" ]; then
                device shell settings put secure enabled_accessibility_services \
                    "$remaining" >/dev/null 2>&1
            else
                device shell settings delete secure enabled_accessibility_services \
                    >/dev/null 2>&1
                device shell settings put secure accessibility_enabled 0 >/dev/null 2>&1
            fi
            ;;
    esac
    return 0
}

# Enables the probe without switching off a reader the owner already had on.
enable_probe() {
    local services=$SAVED_SERVICES
    case "$services" in
        ''|null) services="$PROBE/.ProbeService" ;;
        *"$PROBE/.ProbeService"*) ;;
        *) services="$services:$PROBE/.ProbeService" ;;
    esac
    device shell settings put secure enabled_accessibility_services "$services" >/dev/null
    device shell settings put secure accessibility_enabled 1 >/dev/null
}

# One command in the app's own external files, or the probe's.
evidence() { device shell "cat $FILES/evidence.txt" 2>/dev/null; }
probe_file() { device shell "cat $PROBE_FILES/$1.txt" 2>/dev/null; }

# The probe walks the target window and writes the summary itself; the suite only
# reads values such as rows= and prompt.actions=.
inspect() {
    device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
        --es inspect '$1' >/dev/null 2>&1; cat $PROBE_FILES/$1.txt" 2>/dev/null
}

# Waits for the probe to see at least `minimum` row nodes. The view publishes its
# rows as the program draws them, so a dump taken the moment the launch evidence
# line appears can arrive before the first screen does; the row nodes are what
# this check is about, so they are what it waits for.
await_rows() {
    local minimum=${1:-5} seconds=${2:-10} i=0 summary rows
    while [ "$i" -lt $((seconds * 5)) ]; do
        summary=$(inspect e2e-rows)
        rows=$(printf '%s\n' "$summary" | sed -n 's/^rows=//p')
        [ "${rows:-0}" -ge "$minimum" ] && return 0
        sleep 0.2
        i=$((i + 1))
    done
    return 1
}

# Waits for an accessibility event without polling: the probe blocks on a new
# event off its main thread, and this streams the answer file until the match.
# $1 needle, $2 seconds, $3 optional event type (WINDOW_STATE, ANNOUNCEMENT...).
wait_event() {
    local needle=$1 seconds=${2:-15} type=${3:-}
    timeout "$seconds" "$ADB" shell ": > '$PROBE_FILES/wait.txt'; \
        am broadcast -p $PROBE -a $PROBE.COMMAND --es wait '$needle' \
            --el waitMs $((seconds * 1000))${type:+ --es event $type} >/dev/null 2>&1; \
        tail -n +1 -f '$PROBE_FILES/wait.txt' | grep -q -m1 '^matched'"
}

# Sets text and waits for the accessibility event the action causes, all in one
# adb shell: the wait is armed first, then the action runs, then the answer file
# is streamed until it matches. The probe does the matching, off its main thread.
type_wait() { # text needle seconds
    local text=$1 needle=$2 seconds=${3:-12}
    timeout "$seconds" "$ADB" shell ": > '$PROBE_FILES/wait.txt'; \
        am broadcast -p $PROBE -a $PROBE.COMMAND --es wait '$needle' \
            --el waitMs $((seconds * 1000)) >/dev/null 2>&1; \
        am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
            --es text '$text\\n' >/dev/null 2>&1; \
        tail -n +1 -f '$PROBE_FILES/wait.txt' | grep -q -m1 '^matched'"
}

# The service writes status.txt when it binds; nothing it performs works before
# that, and a reinstall or a settings change both unbind it.
await_probe() {
    local tries=${1:-45}
    # grep runs on the device: when it matches, the shell pipeline ends and the
    # adb call returns, instead of staying open until the host timeout.
    timeout "$tries" "$ADB" shell "touch '$PROBE_FILES/status.txt'; \
        tail -n +1 -F '$PROBE_FILES/status.txt' | grep -q -m1 connected"
}

# Types one line into the app's own window the way a service does, and waits for
# the answer. Retried: a window can take a moment to appear in the service's
# window list, and the probe refuses to type anywhere else.
#
# `type_in_app` wants the answer as the *start* of an evidence line, which is
# right for a shell prompt and wrong for a box drawn in the middle of the
# screen; `type_until` takes any pattern in the evidence.
type_until() {
    local text=$1 pattern=$2 attempt=0
    while [ "$attempt" -lt 5 ]; do
        attempt=$((attempt + 1))
        device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
            --es text '$text\\n'" >/dev/null 2>&1
        if await "$pattern" 6; then
            return 0
        fi
    done
    return 1
}

type_in_app() {
    local text=$1 answer=$2 attempt=0
    while [ "$attempt" -lt 5 ]; do
        attempt=$((attempt + 1))
        # One backslash before the n: two make the shell see `echo x\` and
        # treat the line as a continuation instead of running it.
        device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
            --es text '$text\\n'" >/dev/null 2>&1
        if await_line "$answer" 6; then
            return 0
        fi
    done
    return 1
}

# Bound is not able to see: right after the accessibility settings are written
# the connection can be up with no windows in it yet, and every action then has
# nothing to act on. The service's own dump is the witness.
await_windows() {
    local tries=${1:-12} i=0 tree
    while [ "$i" -lt "$tries" ]; do
        tree=$(dump e2e-ready)
        if printf '%s\n' "$tree" | grep -aqE '^windows=[1-9]'; then
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    return 1
}

# Waits for a line in the evidence file; most of these checks are "the app will
# say this within N seconds, or it will not".
#
# The device polls its own file every 100 ms and exits on the match, so a hit
# costs one adb round trip instead of a `cat` per second - the same trade the
# instrumented tests make by reading the file the app writes rather than running
# `logcat -d` on a timer. The host `timeout` is a backstop for a shell that never
# runs the loop at all.
await() {
    local pattern=$1 seconds=${2:-15}
    timeout "$seconds" "$ADB" shell "i=0
        while [ \$i -lt $((seconds * 10)) ]; do
            grep -aqF -- '$pattern' '$FILES/evidence.txt' 2>/dev/null && exit 0
            sleep 0.1
            i=\$((i + 1))
        done
        exit 1" >/dev/null 2>&1
}

expect() {
    if evidence | grep -aqF -- "$1"; then ok "$2"; else bad "$2 (no '$1' in evidence)"; fi
}

# Shortens the app's own terminal view, the way the keyboard does. The display's
# size belongs to the phone's owner: `wm size` made the same window change, but a
# run that died before its trap left the screen at that size.
resize_window() {
    local fraction=${1:-0.3}
    device shell "am broadcast -p $APP -a $APP.action.RESIZE \
        --ef resize_fraction $fraction" >/dev/null 2>&1
}

# The newest grid the view reported, as CxR.
last_grid() { evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://'; }

# How many bytes the evidence has now: a wait that must be answered only by what
# is written next passes this to grid_changed.
evidence_bytes() {
    device shell "wc -c < '$FILES/evidence.txt' 2>/dev/null" | tr -d ' \r'
}

# Waits until a grid line written after `mark` is something other than `before`:
# a window change is a layout, so this is also the wait for the app to have
# noticed it. The mark is taken before the action, because a broadcast is fast
# enough that the new grid can already be in the file by the time a wait starts.
grid_changed() {
    local before=$1 mark=${2:-0} seconds=${3:-12}
    case $mark in ''|*[!0-9]*) mark=0 ;; esac
    timeout "$seconds" "$ADB" shell "i=0
        while [ \$i -lt $((seconds * 10)) ]; do
            tail -c +$((mark + 1)) '$FILES/evidence.txt' 2>/dev/null \
                | grep -a -o '### grid:[0-9]*x[0-9]*' \
                | grep -a -q -m1 -v -F -- '### grid:$before' && exit 0
            sleep 0.1
            i=\$((i + 1))
        done
        exit 1" >/dev/null 2>&1
}

# The grid once two reads two seconds apart agree. A window resize with the
# keyboard opening gives the view more than one size before it stops, and the
# pty follows the last of them: reading the first is how a check comes to expect
# a size the program never ends up with.
settled_grid() {
    local first second tries=${1:-10} i=0
    first=$(last_grid)
    while [ "$i" -lt "$tries" ]; do
        sleep 2
        second=$(last_grid)
        if [ "$second" = "$first" ]; then
            printf '%s' "$second"
            return 0
        fi
        first=$second
        i=$((i + 1))
    done
    printf '%s' "$first"
    return 1
}

# A line the evidence has that *starts* with the pattern: output, rather than the
# shell echoing the command back (which contains the same text). Polled on the
# device for the same reason `await` is.
await_line() {
    local pattern=$1 seconds=${2:-15}
    timeout "$seconds" "$ADB" shell "i=0
        while [ \$i -lt $((seconds * 10)) ]; do
            grep -aqE -- '^$pattern' '$FILES/evidence.txt' 2>/dev/null && exit 0
            sleep 0.1
            i=\$((i + 1))
        done
        exit 1" >/dev/null 2>&1
}

# Every screen section in the evidence, without the raw pty transcript - that
# one is full of the escape bytes the view consumed, on purpose.
all_screens() {
    evidence | awk '/^--- screen ---/{f=1;next} /^--- transcript ---/{f=0} f'
}

# The newest screen section, for checks about one drawn frame rather than every
# frame so far. The transcript sections are skipped, not collected.
last_screen() {
    evidence | awk '
        /^--- screen ---/ { inside = 1; block = ""; next }
        /^--- transcript ---/ { if (inside) { last = block } ; inside = 0; next }
        inside { block = block $0 "\n" }
        END { printf "%s", last }'
}

# The screen the view had at one step of the cursor demo.
screen_at() {
    evidence | sed -n "/^### cursor:$1 /,/^--- transcript ---/p" \
        | sed -n '/^--- screen ---/,/^--- transcript ---/p' | sed '1d;$d'
}

has_line() {
    printf '%s\n' "$1" | grep -qxF -- "$2"
}

# Installs only when the on-device APK differs, so an unchanged probe keeps its
# accessibility binding instead of paying the rebind time on every run.
install_if_changed() { # apk package label
    local apk=$1 package=$2 label=$3 remote hash local_hash
    remote=$(device shell pm path "$package" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p')
    if [ -n "$remote" ]; then
        hash=$(device shell md5sum "$remote" 2>/dev/null | awk '{print $1}' | tr -d '\r')
    fi
    local_hash=$(md5sum "$apk" 2>/dev/null | awk '{print $1}')
    if [ -z "$hash" ] || [ "$hash" != "$local_hash" ]; then
        device install -r "$apk" >/dev/null 2>&1 || {
            bad "$label did not install; build it first"
            return 1
        }
    fi
}

install_everything() {
    save_device_state
    install_if_changed "$APK" "$APP" "the native-terminal APK" || exit 1
    install_if_changed "$PROBE_APK" "$PROBE" "the a11y-probe APK" || exit 1
    # Reinstalling the probe kills its service, and the settings below only ask
    # for it back: the actions go nowhere until it has bound. Old logs would also
    # let a later check match a line from a previous run.
    device shell rm -f "$PROBE_FILES/actions.txt" "$PROBE_FILES/wait.txt" >/dev/null 2>&1
    # Reinstalling the APK already replaces the process; force-stopping an
    # accessibility service here makes the manager take tens of seconds to bind
    # it again. A fresh install gets the fresh window connection instead.
    device logcat -c >/dev/null 2>&1
    # Appended, not substituted: a reader the owner had on keeps working while
    # the probe drives the app, and the saved value puts the list back untouched.
    enable_probe
    if ! await_windows 30; then
        bad "the probe service never exposed a window"
    fi
}

# A clean app and a granted Termux permission. pm clear revokes runtime
# permissions, so the grant has to come after it, not before.
launch() {
    local attempt
    for attempt in 1 2; do
        device shell am force-stop "$APP" >/dev/null 2>&1
        device shell pm clear "$APP" >/dev/null 2>&1
        device shell pm grant "$APP" com.termux.permission.RUN_COMMAND >/dev/null 2>&1
        device shell am start -n "$APP/.MainActivity" "$@" >/dev/null 2>&1
        if await "activity:created" 15; then
            return 0
        fi
    done
    # A launch that quietly did nothing turns every later check into its own
    # mystery; say it once, here, with the log lines that explain it.
    bad "launch: the app did not start after two attempts (adb: $(device get-state 2>/dev/null))"
    device logcat -d -t 400 2>/dev/null \
        | grep -a 'FATAL\|nativeterminal' | tail -6 | sed 's/^/     /'
}

dump() {
    # am broadcast waits for the receiver, so the file is complete when cat runs;
    # one adb round trip instead of broadcast-then-read.
    device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
        --es dump '$1' >/dev/null 2>&1; cat $PROBE_FILES/$1.txt" 2>/dev/null
}

restore() {
    if [ "$STATE_SAVED" = 1 ]; then
        put_device_back || say "WARNING: the device is still changed;" \
            "run 'bash samples/e2e.sh --restore' when it is reachable"
    fi
    device shell am force-stop "$APP" >/dev/null 2>&1
}
trap restore EXIT
# An interrupted run goes through the same cleanup: bash runs EXIT after these.
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# --- the app-owned pty --------------------------------------------------------

test_pty_is_a_terminal() {
    launch
    if await "pty:master-fd:" 10; then
        ok "pty: the app owns the master descriptor"
    else
        bad "pty: no master descriptor in the evidence"
        return
    fi
    if ! await "stdin-is-tty"; then
        bad "pty: the shell never reported a terminal"
        return
    fi
    expect "/dev/pts/" "pty: the shell is on a pty device"
    expect "stdout-is-tty" "pty: stdout is a terminal"
}

# --- cursor jumps -------------------------------------------------------------

# Every kind of jump a program uses, asserted twice: on the rows the service can
# read, and on the cursor the view drew.
test_cursor_jumps() {
    launch --ez cursor true
    if ! await "cursor:main-again" 15; then
        # A fresh install can leave the first demo without its session.
        launch --ez cursor true
        if ! await "cursor:main-again" 15; then
            bad "cursor: the demo never finished"
            return
        fi
    fi

    local screen
    screen=$(screen_at absolute)
    if has_line "$screen" "CC" && has_line "$screen" "  AA" \
            && has_line "$screen" "      BB"; then
        ok "cursor: CUP placed three markers at their rows and columns"
    else
        bad "cursor: CUP did not place the markers (got: $screen)"
    fi
    expect "cursor:absolute cursor=0,2" "cursor: the cursor followed CUP to row 1, column 3"

    screen=$(screen_at relative)
    has_line "$screen" "CC    DD" \
        && ok "cursor: CUU/CUF moved up and right from the addressed cell" \
        || bad "cursor: the relative move landed elsewhere (got: $screen)"
    expect "cursor:relative cursor=0,8" "cursor: two up and four right left the cursor at column 9"

    screen=$(screen_at column-row)
    has_line "$screen" "         EE" \
        && ok "cursor: VPA/CHA jumped to a row and a column directly" \
        || bad "cursor: the row/column jump landed elsewhere (got: $screen)"
    expect "cursor:column-row cursor=3,11" "cursor: the vertical and column jumps put the cursor at row 4, column 12"

    screen=$(screen_at save-restore)
    if has_line "$screen" "         EEFF" && has_line "$screen" " X"; then
        ok "cursor: CSI s/u restored the cursor and the text went there"
    else
        bad "cursor: the saved cursor was not restored (got: $screen)"
    fi
    expect "cursor:save-restore cursor=3,13" "cursor: after the restore the cursor is where it was saved"

    screen=$(screen_at alternate)
    if has_line "$screen" "    TUI" && ! printf '%s\n' "$screen" | grep -qF "CC"; then
        ok "cursor: the alternate screen hid the main one and the jump applied there"
    else
        bad "cursor: the alternate screen did not hide the main grid (got: $screen)"
    fi
    expect "cursor:alternate cursor=1,7" "cursor: the jump into the alternate screen put the cursor there"

    screen=$(screen_at main-again)
    if has_line "$screen" "CC    DD" && ! printf '%s\n' "$screen" | grep -qF "TUI"; then
        ok "cursor: leaving the alternate screen brought the main grid and cursor back"
    else
        bad "cursor: the main grid did not come back (got: $screen)"
    fi
    expect "cursor:main-again cursor=3,13" "cursor: the cursor came back with the main screen"
}

# --- colours ------------------------------------------------------------------

# The rows prove the SGR was consumed; the screenshot proves it was drawn.
test_colour_escapes_render() {
    launch --ez colors true
    if ! await "cursor:colors" 12; then
        bad "colour: the demo never drew"
        return
    fi
    # The evidence is written as the grid changes; the frame carrying the colours
    # is the next one, and RED-TEXT arriving in the file is that frame's write.
    await "RED-TEXT" 5 || true

    local screen
    screen=$(screen_at colors)
    # CUP 3;3 red, CUP 3;20 green: the row has both, nine columns apart, and no
    # escape sequence of its own.
    if printf '%s\n' "$screen" | grep -qE '^  RED-TEXT {9}GREEN-TEXT$'; then
        ok "colour: SGR text landed at its columns and printed no escape"
    else
        bad "colour: the coloured row is not what the SGR asked for (got: $screen)"
    fi
    if printf '%s\n' "$screen" | grep -qE '\[[0-9;]*m'; then
        bad "colour: an escape sequence leaked into the row text"
    else
        ok "colour: no escape sequence leaked into the row text"
    fi
    local marker
    for marker in BRIGHT-BLUE ORANGE-256 plain-after-reset; do
        has_line "$screen" "  $marker" \
            && ok "colour: $marker is on its row" \
            || bad "colour: $marker is missing from the grid (got: $screen)"
    done

    if ! command -v ffmpeg >/dev/null; then
        say "skip colour pixels (no ffmpeg to read the screenshot)"
        return
    fi
    local shot=samples/out/colour.png counts red green blue
    device exec-out screencap -p > "$shot" 2>/dev/null
    counts=$(ffmpeg -loglevel error -i "$shot" -vf "crop=1440:900:0:150" \
        -f rawvideo -pix_fmt rgb24 - 2>/dev/null | python3 -c '
import sys
d = sys.stdin.buffer.read()
red = green = blue = 0
for i in range(0, len(d) - 2, 3):
    r, g, b = d[i], d[i + 1], d[i + 2]
    if r > 150 and g < 110 and b < 110:
        red += 1
    elif g > 120 and r < 110 and b < 110:
        green += 1
    elif b > 150 and r < 130:
        blue += 1
print(red, green, blue)
')
    read -r red green blue <<< "$counts"
    [ "${red:-0}" -gt 200 ] \
        && ok "colour: the red SGR reached the pixels ($red red pixels)" \
        || bad "colour: no red pixels from SGR 31 (got '${red:-0}')"
    [ "${green:-0}" -gt 200 ] \
        && ok "colour: the green SGR reached the pixels ($green green pixels)" \
        || bad "colour: no green pixels from SGR 32 (got '${green:-0}')"
    [ "${blue:-0}" -gt 200 ] \
        && ok "colour: the bright-blue SGR reached the pixels ($blue blue pixels)" \
        || bad "colour: no blue pixels from SGR 1;34 (got '${blue:-0}')"
}

# --- a curses TUI, driven through the accessibility prompt ---------------------

test_tui_renders_and_responds() {
    launch --ez tui true
    if ! await "BOX-TUI" 30; then
        bad "tui: the program never drew its box"
        return
    fi
    local tree rows widths
    tree=$(dump e2e-tui)
    rows=$(printf '%s\n' "$tree" | grep -acE 'android.widget.TextView editable=false')
    [ "$rows" -ge 8 ] \
        && ok "tui: the box is $rows row nodes, not one blob of text" \
        || bad "tui: only $rows row nodes in the tree"
    widths=$(all_screens | grep -E '^ [|+]' | awk '{print length($0)}' | sort -u | tr '\n' ' ')
    [ "$widths" = "41 " ] \
        && ok "tui: every box row is 40 columns wide" \
        || bad "tui: the box rows are not aligned (widths: $widths)"
    if printf '%s\n' "$tree" | grep -aqF '[File] [Edit] [View] [Help]'; then
        ok "tui: the menu row is in the tree"
    else
        bad "tui: the menu row is missing from the tree"
    fi
    if all_screens | grep -aqF '[?1049h'; then
        bad "tui: an escape sequence leaked into the grid"
    else
        ok "tui: the alternate-screen sequence was consumed, not printed"
    fi

    # One character per line: the service types it, Enter submits it, curses reads
    # it as a key. This is the interactive path, not a screenshot of a static box.
    if type_in_app "n" ' \|  status: next'; then
        ok "tui: 'n' reached the program through the service, which redrew"
    else
        bad "tui: 'n' was not processed"
    fi
    evidence | grep -aqF '> [ ] two' \
        && ok "tui: 'n' moved the selection to the second row" \
        || bad "tui: the selection did not move"

    if type_in_app "t" ' \|  status: toggle'; then
        ok "tui: 't' reached the program"
    else
        bad "tui: 't' was not processed"
    fi
    await '[x] two' 2 \
        && ok "tui: 't' marked the selected row" \
        || bad "tui: the toggle did not show"

    if type_in_app "q" 'tui left the alternate screen'; then
        ok "tui: 'q' left the alternate screen"
    else
        bad "tui: 'q' did not leave the alternate screen"
    fi
}

# --- a second TUI: an input box, and the line echoed above it ------------------

# The program is `assets/tui-echo.py`: an input box centred in the screen and an
# output box directly above it. A service types a word and Enter; the word has to
# appear in the box above, and the input box has to be empty again.
test_tui_echoes_the_line_above() {
    launch --ez echo true
    if ! await "echo-tui:started" 30; then
        bad "echo tui: the program was never sent to the session"
        return
    fi
    if ! await '|  > ' 25; then
        bad "echo tui: the input box never appeared"
        return
    fi
    ok "echo tui: the input box is on the screen"

    # Centred: the box starts where an equal margin on both sides puts it. The
    # grid is the view's own report, so this is the screen the program was given,
    # not an assumption about the device.
    local screen cols width left indent
    cols=$(evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://; s/x.*//')
    if [ -z "$cols" ]; then
        bad "echo tui: the view never reported its grid"
        return
    fi
    width=$((cols - 2))
    [ "$width" -gt 40 ] && width=40
    left=$(( (cols - width) / 2 ))
    screen=$(last_screen)
    indent=$(printf '%s\n' "$screen" | awk '/\|  > /{ match($0, /^ */); print RLENGTH; exit }')
    if [ "${indent:-x}" = "$left" ]; then
        ok "echo tui: the input box is centred in $cols columns (starts at column $((left + 1)))"
    else
        bad "echo tui: the input box is not centred (indent '${indent:-x}', wanted $left in $cols columns)"
    fi

    if ! type_until "hello" '|  hello'; then
        bad "echo tui: the submitted word was not echoed"
        return
    fi
    ok "echo tui: the word a service typed reached the program"

    # The dump cadence is 1.5 s; one more snapshot is the frame after Enter.
    sleep 2
    screen=$(last_screen)
    local echo_row input_row
    echo_row=$(printf '%s\n' "$screen" | grep -nF '|  hello' | head -1 | cut -d: -f1)
    input_row=$(printf '%s\n' "$screen" | grep -nE '\|  > ' | head -1 | cut -d: -f1)
    if [ -n "$echo_row" ] && [ -n "$input_row" ] && [ "$echo_row" -lt "$input_row" ]; then
        ok "echo tui: the line is echoed in the box above the input"
    else
        bad "echo tui: no echo above the input box (echo row '${echo_row:-none}', input row '${input_row:-none}')"
    fi
    if printf '%s\n' "$screen" | grep -qE '\|  > +\|$'; then
        ok "echo tui: the input box is empty again after Enter"
    else
        bad "echo tui: the input box still holds the line"
    fi

    if type_until "/quit" "echo tui left the alternate screen"; then
        ok "echo tui: '/quit' left the alternate screen"
    else
        bad "echo tui: '/quit' did not leave the alternate screen"
    fi
}

# Mean brightness of cells in one band of a screenshot: <shot> <left> <top>
# <right> <bottom> <cols> <col>... The cell width is the row's own width over the
# grid's columns, so this works at whatever size the view happens to be.
cell_means() {
    local shot=$1
    shift
    python3 - "$shot" "$@" <<'EOF'
import math, subprocess, sys
shot = sys.argv[1]
left, top, right, bottom, cols = map(int, sys.argv[2:7])
cells = [int(v) for v in sys.argv[7:]]
cell = (right - left) / cols

def mean(col):
    data = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', shot, '-vf',
        'crop=%d:%d:%d:%d' % (math.ceil(cell), bottom - top, round(left + col * cell), top),
        '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], capture_output=True).stdout
    return sum(data) / len(data) if data else -1

print(' '.join('%.0f' % mean(col) for col in cells))
EOF
}

# Pixels near a colour in a screenshot: <shot> <r> <g> <b> <tol>.
color_pixels() {
    python3 - "$@" <<'EOF'
import subprocess, sys
shot, r, g, b, tol = sys.argv[1:6]
r, g, b, tol = map(int, (r, g, b, tol))
data = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', shot, '-f', 'rawvideo',
    '-pix_fmt', 'rgb24', '-'], capture_output=True).stdout
count = 0
for i in range(0, len(data) - 2, 3):
    if abs(data[i] - r) <= tol and abs(data[i + 1] - g) <= tol and abs(data[i + 2] - b) <= tol:
        count += 1
print(count)
EOF
}

# --- the caret the view draws (not the program) --------------------------------

# A TUI positions the cursor and leaves drawing it to the terminal. The echo TUI
# asks for a visible caret (curses leaves DEC mode 25 on) and puts it in its empty
# input box, so the pixels here are the view's block, not a glyph: bright cell,
# dark cell four columns along.
test_tui_caret_is_visible() {
    launch --ez echo true
    if ! await "echo-tui:started" 30 || ! await '|  > ' 25; then
        bad "caret: the echo TUI never drew its input box"
        return
    fi
    if ! command -v ffmpeg >/dev/null; then
        say "skip caret pixels (no ffmpeg to read the screenshot)"
        return
    fi
    # The row's own bounds are the cell geometry: a column is the view's width
    # over the grid's. Waiting for the grid to settle first keeps a keyboard that
    # is still opening from moving the row between the dump and the screenshot.
    settled_grid 8 >/dev/null || true
    local tree row_line row_text bounds left top right bottom cols caret_col
    tree=$(dump e2e-caret)
    row_line=$(printf '%s\n' "$tree" | grep -aF '|  > ' | head -1)
    bounds=$(printf '%s\n' "$row_line" | sed -n \
        's/.*bounds=\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\).*/\1 \2 \3 \4/p')
    if [ -z "$row_line" ] || [ -z "$bounds" ]; then
        bad "caret: the input box is not a row node with bounds"
        return
    fi
    read -r left top right bottom <<< "$bounds"
    # The probe writes the text unquoted: ` text=<rows> desc=<desc>`.
    row_text=$(printf '%s\n' "$row_line" | sed -n 's/.* text=\(.*\) desc=.*/\1/p')
    cols=$(evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://; s/x.*//')
    if [ -z "$cols" ] || [ "$cols" -le 0 ]; then
        bad "caret: the view never reported its grid"
        return
    fi
    # The caret sits after "  > " inside the box, i.e. five columns in from the
    # border; nothing has been typed, so it is over a blank cell.
    indent=$(printf '%s\n' "$row_text" | awk '{ match($0, /^ */); print RLENGTH }')
    caret_col=$((indent + 5))

    local shot=samples/out/caret.png means caret_mean=0 blank_mean=999 lit dark
    local attempt
    for attempt in 1 2 3 4 5; do
        device exec-out screencap -p > "$shot" 2>/dev/null
        means=$(cell_means "$shot" "$left" "$top" "$right" "$bottom" "$cols" \
            "$caret_col" "$((caret_col + 4))")
        read -r lit dark <<< "$means"
        # A frame can land mid-repaint; the most lit sample is the caret.
        if [ "${lit:-0}" -gt "$caret_mean" ]; then
            caret_mean=${lit:-0}
            blank_mean=${dark:-999}
        fi
    done
    # The block covers the glyph cell, not the leading between rows, so a lit
    # cell averages well below the block colour (about 150 of 255): measured
    # here at 96 against a blank 25. The threshold keeps those apart without
    # demanding a full cell of ink from a block that does not fill one.
    if [ "${caret_mean:-0}" -gt 80 ] && [ "${blank_mean:-999}" -lt 60 ]; then
        ok "caret: the view draws a block at the input box's cursor ($caret_mean against $blank_mean)"
    else
        bad "caret: no block at the cursor (cell '${caret_mean:-none}', background '${blank_mean:-none}'; row '$row_text', indent '${indent:-?}', cols '$cols', cell '$caret_col', bounds '$bounds')"
    fi
}

# The box TUI asks for a blinking block (DECSCUSR 1), which only the terminal can
# draw. Both phases have to reach the pixels: always-on is a steady caret, not a
# blinking one, and always-off is no caret at all.
test_tui_blinks_the_caret() {
    launch --ez tui true
    if ! await "BOX-TUI" 30; then
        bad "blink: the box TUI never drew"
        return
    fi
    if ! command -v ffmpeg >/dev/null; then
        say "skip blink pixels (no ffmpeg to read the screenshot)"
        return
    fi
    settled_grid 8 >/dev/null || true
    local tree row_line row_text bounds left top right bottom cols indent caret_col
    tree=$(dump e2e-blink)
    # The prompt row is the one whose text is " >" - the '>' sits in column 1,
    # so the row starts with a space. The box's selection markers have two
    # spaces and more text after them.
    row_line=$(printf '%s\n' "$tree" | grep -aE ' text= > desc=' | head -1)
    bounds=$(printf '%s\n' "$row_line" | sed -n \
        's/.*bounds=\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\).*/\1 \2 \3 \4/p')
    if [ -z "$row_line" ] || [ -z "$bounds" ]; then
        bad "blink: the prompt is not a row node with bounds"
        return
    fi
    read -r left top right bottom <<< "$bounds"
    row_text=$(printf '%s\n' "$row_line" | sed -n 's/.* text=\(.*\) desc=.*/\1/p')
    indent=$(printf '%s\n' "$row_text" | awk '{ match($0, /^ */); print RLENGTH }')
    # The prompt is "> " at that indent, so the caret is two columns further.
    caret_col=$((indent + 2))
    cols=$(evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://; s/x.*//')
    if [ -z "$cols" ] || [ "$cols" -le 0 ]; then
        bad "blink: the view never reported its grid"
        return
    fi

    local lit=0 dark=0 i mean
    for i in 1 2 3 4 5 6 7 8 9 10; do
        device exec-out screencap -p > samples/out/blink.png 2>/dev/null
        mean=$(cell_means samples/out/blink.png "$left" "$top" "$right" "$bottom" \
            "$cols" "$caret_col")
        if [ "${mean:-0}" -gt 120 ]; then lit=$((lit + 1)); fi
        if [ "${mean:-999}" -lt 60 ]; then dark=$((dark + 1)); fi
        sleep 0.2
    done
    if [ "$lit" -gt 0 ] && [ "$dark" -gt 0 ]; then
        ok "blink: the box TUI's caret blinks ($lit on, $dark off in 10 samples)"
    else
        bad "blink: the caret did not blink ($lit on, $dark off in 10 samples)"
    fi
}

# --- background colours --------------------------------------------------------

# A TUI's box is spaces with a colour behind them, so the grid has to keep the
# background as well as the glyph. The proof is the pixels: both fills the
# program asked for (SGR 44 and 43) have to be on screen. Counting the whole
# frame keeps this independent of where the IME leaves the view between the
# dump and the screenshot.
test_tui_background_box() {
    launch --ez background true
    if ! await "BG-TUI" 30; then
        bad "background: the TUI never drew"
        return
    fi
    if ! command -v ffmpeg >/dev/null; then
        say "skip background pixels (no ffmpeg to read the screenshot)"
        return
    fi
    settled_grid 8 >/dev/null || true
    local tree
    tree=$(dump e2e-background)
    if ! printf '%s\n' "$tree" | grep -aqF 'yellow background'; then
        bad "background: the yellow box is not in the tree"
        return
    fi
    local shot=samples/out/background.png blue yellow
    device exec-out screencap -p > "$shot" 2>/dev/null
    blue=$(color_pixels "$shot" 52 101 164 10)
    yellow=$(color_pixels "$shot" 196 160 0 10)
    # Each fill is tens of thousands of pixels; a missing one counts as zero.
    if [ "${blue:-0}" -gt 20000 ] && [ "${yellow:-0}" -gt 20000 ]; then
        ok "background: the blue and yellow fills reached the pixels ($blue, $yellow)"
    else
        bad "background: a box fill is missing (blue '${blue:-none}', yellow '${yellow:-none}' pixels)"
    fi
}

# The same escapes as a program writes them: pty, reader, grid, row nodes.
test_cursor_escapes_from_a_program() {
    launch --ez sizeProbes false
    if ! await "stdout-is-tty" 15; then
        bad "cursor: no shell to print the escapes"
        return
    fi
    sleep 4
    # `printf` is the program; the CUP sequences are its output. Clear first, so
    # the rows the jumps land on hold nothing but what the program put there.
    if type_in_app "printf \"\\033[2J\\033[10;3HPTY-JUMP\\033[3;40HROW-JUMP\"" \
            "  PTY-JUMP"; then
        ok "cursor: a program's CUP reached the pty, the grid and the row nodes"
    else
        bad "cursor: the program's first jump did not reach a row"
        return
    fi
    if await_line " {39}ROW-JUMP" 10; then
        ok "cursor: the second jump landed at row 3, column 40"
    else
        bad "cursor: the second jump did not land at row 3, column 40"
    fi
}

test_grid_resize_reaches_the_pty() {
    # The size question is the service's own: a timed probe can run out while a
    # window is being resized, and then this check waits for an answer that is
    # never asked for.
    launch --ez sizeProbes false
    if ! type_in_app "echo size-now=\$(stty size)" "size-now="; then
        bad "resize: the shell never answered stty size"
        return
    fi
    local before mark
    before=$(last_grid)
    mark=$(evidence_bytes)
    resize_window 0.3

    if ! grid_changed "$before" "$mark" 12; then
        bad "resize: the view's grid did not follow the window (was $before)"
        return
    fi
    local after cols rows
    after=$(settled_grid)
    cols=${after%%x*}
    rows=${after##*x}
    ok "resize: the view's grid is ${before} -> ${after}"

    # ioctl(TIOCSWINSZ) is the app's own path here, and the shell has to see it.
    if type_in_app "echo size-now=\$(stty size)" "size-now=$rows $cols"; then
        ok "resize: the pty reports $rows $cols, the grid the view has"
    else
        bad "resize: the pty did not follow the view (wanted 'size-now=$rows $cols')"
    fi
}

# The other transport: the pty is Termux's, so the size has to travel over the
# control port and `stty -F`; the program's own `stty size` is the witness.
test_session_resize_follows_the_view() {
    launch --ez termux true --ez sizeProbes false
    if ! await "termux:connected:true" 20; then
        bad "session resize: Termux never connected back"
        return
    fi
    # Asked through the service, not by a timed probe: a fixed number of probes
    # runs out, and this check then waits for an answer nobody is going to ask
    # for. With the timer off, the service's own write cannot interleave either.
    if ! type_in_app "echo size-now=\$(stty size)" "size-now="; then
        bad "session resize: the session never answered stty size"
        return
    fi
    local before mark after cols rows
    before=$(last_grid)
    mark=$(evidence_bytes)
    resize_window 0.3
    if ! grid_changed "$before" "$mark" 12; then
        bad "session resize: the view's grid did not follow the window (was $before)"
        return
    fi
    after=$(settled_grid)
    cols=${after%%x*}
    rows=${after##*x}
    if type_in_app "echo size-now=\$(stty size)" "size-now=$rows $cols"; then
        ok "session resize: Termux's pty reports $rows $cols, the grid the view has"
    else
        bad "session resize: Termux's pty did not follow (wanted 'size-now=$rows $cols')"
    fi
}

# --- what a service can read --------------------------------------------------

test_rows_and_events() {
    launch
    # The old poll gave the view a second to draw; the wait for launch evidence
    # does not, so the rows this check reads are waited for explicitly.
    await_rows 5 10
    local summary rows changed announced actions needle
    summary=$(inspect e2e-rows)
    rows=$(printf '%s\n' "$summary" | sed -n 's/^rows=//p')
    changed=$(printf '%s\n' "$summary" | sed -n 's/^events\.CONTENT_CHANGED=//p')
    announced=$(printf '%s\n' "$summary" | sed -n 's/^events\.ANNOUNCEMENT=//p')
    actions=$(printf '%s\n' "$summary" | sed -n 's/^prompt\.actions=//p')

    [ "${rows:-0}" -ge 5 ] && ok "rows: the probe's own summary counts $rows row nodes" \
        || bad "rows: only ${rows:-0} row nodes in the summary"
    [ "${changed:-0}" -gt 0 ] && ok "events: $changed content-changed events" \
        || bad "events: no TYPE_WINDOW_CONTENT_CHANGED reached the service"
    [ "${announced:-0}" -gt 0 ] && ok "events: $announced announcements" \
        || bad "events: no TYPE_ANNOUNCEMENT reached the service"
    case "$actions" in
        *SET_TEXT*) ok "a11y: the prompt exposes ACTION_SET_TEXT ($actions)" ;;
        *) bad "a11y: the prompt does not expose ACTION_SET_TEXT (got '$actions')" ;;
    esac

    # TalkBack's swipe, performed by the service; the probe chooses the row.
    device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
        --ez focusRow true" >/dev/null 2>&1
    if probe_file actions | grep -aq 'A11Y_FOCUS_FIRST .* -> true'; then
        ok "a11y: ACTION_ACCESSIBILITY_FOCUS focused a terminal row"
    else
        bad "a11y: the focus action did not succeed (got: $(probe_file actions | grep A11Y_FOCUS_FIRST | tail -1))"
        return
    fi
    summary=$(inspect e2e-focus)
    focused=$(printf '%s\n' "$summary" | sed -n 's/^focusedA11y=//p')
    changed=$(printf '%s\n' "$summary" | sed -n 's/^events\.A11Y_FOCUSED=//p')
    if [ -n "$focused" ] && [ "$focused" != "-" ] && [ "${changed:-0}" -gt 0 ]; then
        ok "a11y: the service reads the focused row back ('$focused')"
    else
        bad "a11y: accessibility focus was not visible to the service (focused '$focused', events '${changed:-0}')"
    fi
}

# --- a Termux session, driven through accessibility ---------------------------

test_service_types_into_a_termux_session() {
    # No size questions while the service types: both writes go to the same
    # socket, and two submits landing together become one mangled line.
    launch --ez termux true --ez sizeProbes false
    if ! await "termux:connected:true" 20; then
        bad "session: Termux never connected back"
        return
    fi
    ok "session: Termux connected and the view is receiving its output"

    local token="E2E-$$-$RANDOM"
    # The action a screen reader or Voice Access performs; the trailing newline
    # is "and press enter", because a service cannot press Enter any other way.
    # Asserted on a line that starts with the answer, not on the token anywhere:
    # the terminal echoes the command back, so the token alone proves nothing.
    if type_in_app "echo RESULT-$token" "RESULT-$token"; then
        ok "session: ACTION_SET_TEXT reached Termux, which ran the line"
    else
        bad "session: the line the service typed never came back"
        device shell "tail -c 240 $PROBE_FILES/actions.txt" 2>/dev/null | sed 's/^/     /'
        dump e2e-session-fail 2>/dev/null | grep -a 'windows=\|-- window' | sed 's/^/     /'
    fi
    if probe_file actions | grep -aq "package=$APP"; then
        ok "session: the service performed the action on this app's window"
    else
        bad "session: the action did not report this app's package"
    fi
}

# --- the TUI ------------------------------------------------------------------

test_opencode_tui() {
    launch --ez opencode true
    sleep 20
    local tree announcements
    tree=$(dump e2e-opencode)
    if printf '%s\n' "$tree" | grep -aq "Ask anything"; then
        ok "opencode: the TUI's own text is in the tree"
    else
        bad "opencode: no TUI text reached the service"
    fi
    announcements=$(printf '%s\n' "$tree" | grep -ac 'ANNOUNCEMENT')
    [ "$announcements" -gt 0 ] && ok "opencode: $announcements announcements" \
        || bad "opencode: no announcements from a repainting TUI"
}

# --- the opencode entry page's glyphs and modes -------------------------------

# The page was captured from its own pty at 100x30: six non-ASCII glyphs and
# truecolor SGR only. `assets/tui-symbols.py` is the page in miniature. The rows
# prove the parser carried every glyph; the screenshot proves a truecolor
# background reached the cells, which the row text cannot show.
test_entry_page_symbols() {
    launch --ez symbols true
    if ! await "symbols-tui:started" 30 || ! await "SYMBOLS" 25; then
        bad "symbols: the entry-page TUI never drew"
        return
    fi
    local tree
    tree=$(dump e2e-symbols)
    if printf '%s\n' "$tree" | grep -aqF 'SYMBOLS ▀ █ ▄ ┃ ╹ … ·'; then
        ok "symbols: all seven entry-page glyphs reached the row nodes"
    else
        bad "symbols: a glyph is missing from the row nodes"
    fi
    if printf '%s\n' "$tree" | grep -aqF '█▀▀█ █▀▀█ █▀▀█'; then
        ok "symbols: the block logo is in the tree"
    else
        bad "symbols: the block logo is missing"
    fi
    if printf '%s\n' "$tree" | grep -aqF 'Ask anything…'; then
        ok "symbols: the composer's ellipsis reached the tree"
    else
        bad "symbols: the composer text is missing"
    fi
    if ! command -v ffmpeg >/dev/null; then
        say "skip symbols pixels (no ffmpeg to read the screenshot)"
        return
    fi
    settled_grid 8 >/dev/null || true
    local shot=samples/out/symbols.png canvas panel
    device exec-out screencap -p > "$shot" 2>/dev/null
    canvas=$(color_pixels "$shot" 10 10 10 6)   # #0A0A0A, the page's canvas
    panel=$(color_pixels "$shot" 30 30 30 6)    # #1E1E1E, the composer
    if [ "${canvas:-0}" -gt 20000 ] && [ "${panel:-0}" -gt 10000 ]; then
        ok "symbols: the page's truecolor fills reached the pixels ($canvas, $panel)"
    else
        bad "symbols: a truecolor fill is missing (canvas '${canvas:-none}', panel '${panel:-none}')"
    fi
    if type_until "q" "symbols tui left the alternate screen"; then
        ok "symbols: 'q' left the alternate screen"
    else
        bad "symbols: 'q' did not leave the alternate screen"
    fi
}

# The page also enables modes that draw nothing and asks the terminal questions.
# `assets/tui-modes.py` sends the whole set and draws one sentinel: a payload
# that leaked is text in the tree, and a mode that swallowed input never quits.
test_entry_page_modes() {
    launch --ez modes true
    if ! await "modes-tui:started" 30 || ! await "MODES-OK" 25; then
        bad "modes: the entry-page TUI never drew"
        return
    fi
    local tree text
    tree=$(dump e2e-modes)
    text=$(printf '%s\n' "$tree" | sed -n 's/.* text=\(.*\) desc=.*/\1/p')
    if printf '%s\n' "$text" | grep -aqF 'MODES-OK'; then
        ok "modes: the sentinel row is in the tree"
    else
        bad "modes: the sentinel row is missing"
    fi
    if printf '%s\n' "$text" | grep -aqE '1049|2026|2004|2031|P\+q4d73|1337|\[\?u'; then
        bad "modes: an escape payload leaked into the row text"
    else
        ok "modes: every mode and query was consumed, not printed"
    fi
    if type_until "q" "modes tui left the alternate screen"; then
        ok "modes: 'q' left the alternate screen"
    else
        bad "modes: 'q' did not leave the alternate screen"
    fi
}

# --- run ----------------------------------------------------------------------

# One check by name, or all of them: the install and the device state are the
# same either way, because a check that runs alone starts from the same phone.
wanted() {
    case " $ONLY " in
        *" $1 "*) return 0 ;;
    esac
    return 1
}

check() {
    local name=$1
    shift
    if [ -n "$ONLY" ] && ! wanted "$name"; then
        return 0
    fi
    local started=$SECONDS before=$FAIL_COUNT seconds
    CHECKS_RUN=$((CHECKS_RUN + 1))
    "$@"
    seconds=$((SECONDS - started))
    say "  ($name: ${seconds}s)"
    # The log line test/timings.py already knows: class#test duration outcome.
    printf 'timing: samples.e2e#%s %dms %s\n' "$name" "$((seconds * 1000))" \
        "$([ "$FAIL_COUNT" = "$before" ] && echo passed || echo failed)" >> "$TIMING_LOG"
}

# Records this run in the suite's existing timing database.
record_run() {
    [ -s "$TIMING_LOG" ] || return 0
    python3 "$here/../test/timings.py" record --log "$TIMING_LOG" \
        --run "$RUN_STARTED" --db "$here/../test/.timings.db" >/dev/null 2>&1 \
        || say "WARNING: could not record this run in test/.timings.db"
}

if [ "$ONLY" = opencode ]; then
    OPENCODE=1
fi

if [ "$RESTORE_ONLY" = 1 ]; then
    restore_only
    exit $?
fi

if [ "$LIST_ONLY" = 1 ]; then
    printf '%s\n' "$CHECKS"
    exit 0
fi

for name in $ONLY; do
    case " $CHECKS " in
        *" $name "*) ;;
        *)
            printf 'no such check: %s\nchecks: %s\n' "$name" "$CHECKS" >&2
            exit 2
            ;;
    esac
done

if ! ensure_device; then
    say "FAIL no device reachable, and mDNS did not name a working port"
    FAIL=1
    record_run
    exit 1
fi

# A dead run never reached its trap; repair what it left before changing more.
if ! recover_previous_run; then
    say "FAIL the previous run's device state could not be restored; not starting"
    FAIL=1
    record_run
    exit 1
fi

install_everything
check cursor test_cursor_jumps
check cursor-escapes test_cursor_escapes_from_a_program
check colour test_colour_escapes_render
check tui test_tui_renders_and_responds
check tui-echo test_tui_echoes_the_line_above
check tui-caret test_tui_caret_is_visible
check tui-blink test_tui_blinks_the_caret
check tui-bg test_tui_background_box
check tui-symbols test_entry_page_symbols
check tui-modes test_entry_page_modes
check pty test_pty_is_a_terminal
check resize test_grid_resize_reaches_the_pty
check rows test_rows_and_events
check session test_service_types_into_a_termux_session
check session-resize test_session_resize_follows_the_view
if [ "$OPENCODE" = 1 ]; then
    check opencode test_opencode_tui
elif [ -z "$ONLY" ]; then
    say "skip opencode (pass --opencode)"
fi

if [ "$FAIL" = 0 ]; then
    say "PASS $CHECKS_RUN checks in $((SECONDS - START))s"
    record_run
else
    say "FAILURES after $((SECONDS - START))s"
    record_run
    exit 1
fi
