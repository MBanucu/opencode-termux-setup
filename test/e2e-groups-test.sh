#!/usr/bin/env bash
#
# Checks the e2e group manifest without a device.
#
#   bash test/e2e-groups-test.sh
#
# It fails when a @Test class is not in test/e2e-groups.sh, when a class is in
# two groups, or when the flag logic lets --ui-only select an install-dependent
# test. This is the check that keeps "ui-only" from silently skipping the real
# setup flow: the emulator job cannot prove it locally, so the selection itself
# is verified here.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=test/e2e-groups.sh
. "$root/test/e2e-groups.sh"

failures=0
pass() { printf '  ok    %s\n' "$1"; }
fail() {
    printf '  FAIL  %s\n' "$1"
    failures=$((failures + 1))
}

echo "every test class is classified exactly once"
declare -A classified=()
declare -A seen=()
source_dir="$root/android/app/src/androidTest/java/io/github/mbanucu/opencodesetup"
[ -d "$source_dir" ] || { echo "missing $source_dir" >&2; exit 1; }

# shellcheck disable=SC2154  # members is assigned by the eval on the next line
for name in "${E2E_GROUPS[@]}"; do
    eval "members=(\"\${${name}[@]}\")"
    for class in "${members[@]}"; do
        if [ -n "${classified[$class]:-}" ]; then
            fail "$class is in ${classified[$class]} and $name"
        else
            classified[$class]=$name
        fi
    done
done

test_classes=0
for file in "$source_dir"/*.java; do
    grep -q '@Test' "$file" || continue
    test_classes=$((test_classes + 1))
    package=$(sed -n 's/^package \(.*\);/\1/p' "$file" | head -1)
    base=$(basename "$file" .java)
    class="$package.$base"
    seen[$class]=1
    if [ "$(e2e_group_of_class "$class")" = unknown ]; then
        fail "$class has @Test but is not in test/e2e-groups.sh"
    else
        pass "$class -> $(e2e_group_of_class "$class")"
    fi
done

for class in "${!classified[@]}"; do
    [ -n "${seen[$class]:-}" ] || fail "$class is classified but has no @Test in androidTest/"
done
if [ "$test_classes" -eq 0 ]; then
    fail "no test classes found under $source_dir"
fi

echo "flag logic (no device, --list)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if bash "$root/test/android-e2e.sh" --ui-only --list >"$tmp/ui.txt" 2>&1; then
    pass "--ui-only --list succeeds without a device"
else
    fail "--ui-only --list exited non-zero"
    cat "$tmp/ui.txt" >&2
fi

if grep -q 'SetupFlowUiTest' "$tmp/ui.txt"; then
    pass "ui-only selects the setup UI class"
else
    fail "ui-only does not select SetupFlowUiTest"
fi
if grep -q 'PtyRenderingTest' "$tmp/ui.txt"; then
    pass "ui-only selects the pty classes"
else
    fail "ui-only does not select PtyRenderingTest"
fi
if grep -q 'PermissionDeniedTest' "$tmp/ui.txt"; then
    pass "ui-only selects the denial class"
else
    fail "ui-only does not select PermissionDeniedTest"
fi
if grep -qE 'SetupFlowInstallTest|OpencodeTuiTest|group: install|group: opt-in-install' "$tmp/ui.txt"; then
    fail "ui-only listed an install-dependent test"
else
    pass "ui-only lists no install-dependent test"
fi

if bash "$root/test/android-e2e.sh" --list >"$tmp/full.txt" 2>&1; then
    pass "full --list succeeds without a device"
else
    fail "full --list exited non-zero"
fi
if grep -q 'SetupFlowInstallTest' "$tmp/full.txt"; then
    pass "full mode includes the install group"
else
    fail "full mode omits the install group"
fi

expect_refusal() {
    local label=$1 selector=$2 output status
    set +e
    output=$(bash "$root/test/android-e2e.sh" --ui-only --only "$selector" --list 2>&1)
    status=$?
    set -e
    if [ "$status" -eq 2 ] && printf '%s' "$output" | grep -q 'refuses'; then
        pass "--ui-only refuses $label"
    else
        fail "--ui-only did not refuse $label (exit $status): $output"
    fi
}

expect_refusal "the install class" \
    io.github.mbanucu.opencodesetup.SetupFlowInstallTest
expect_refusal "one install method" \
    'io.github.mbanucu.opencodesetup.SetupFlowInstallTest#eInstallPutsItBackForReal'
expect_refusal "the opt-in TUI test" \
    io.github.mbanucu.opencodesetup.OpencodeTuiTest

if bash "$root/test/android-e2e.sh" --ui-only \
    --only io.github.mbanucu.opencodesetup.PtyRenderingTest --list >"$tmp/pty.txt" 2>&1; then
    pass "--ui-only allows a pty test to be selected"
else
    fail "--ui-only rejected a pty test"
    cat "$tmp/pty.txt" >&2
fi

set +e
bash "$root/test/android-e2e.sh" --ui-only \
    --only io.github.mbanucu.opencodesetup.NotATest --list >"$tmp/unknown.txt" 2>&1
status=$?
set -e
if [ "$status" -eq 2 ] && grep -q 'not classified' "$tmp/unknown.txt"; then
    pass "--ui-only rejects an unclassified class"
else
    fail "an unclassified class was not rejected (exit $status)"
fi

echo
if [ "$failures" -eq 0 ]; then
    echo "e2e group checks passed"
else
    echo "$failures e2e group check(s) failed"
fi
exit $((failures > 0))
