#!/usr/bin/env bash
#
# Runs the instrumented end-to-end tests over adb against a connected device.
#
#   bash test/android-e2e.sh [app-debug.apk] [app-debug-androidTest.apk]
#
# Needs: adb in PATH, the device authorised, Termux installed with
# allow-external-apps=true. Build the two APKs with
#   cd android && ./gradlew assembleDebug assembleDebugAndroidTest
#
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
apk=${1:-$root/android/app/build/outputs/apk/debug/app-debug.apk}
test_apk=${2:-$root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}
pkg=io.github.mbanucu.opencodesetup
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner

for f in "$apk" "$test_apk"; do
    [ -f "$f" ] || { echo "missing $f (build it first)" >&2; exit 1; }
done

echo "== device =="
adb wait-for-device
adb shell getprop ro.build.version.release

echo "== install =="
adb install -r "$apk"
adb install -r "$test_apk"

# mktemp honours TMPDIR: Termux has no writable /tmp, so a hard-coded path made
# tee fail and took the whole run down before the second class.
setup_log=$(mktemp)

 echo "== setup flow (permission granted) =="
adb shell "pm grant $pkg com.termux.permission.RUN_COMMAND" || true
adb shell am instrument -w -e class "$pkg.SetupFlowTest" "$runner" | tee "$setup_log"
grep -q 'OK (5 tests)' "$setup_log" || { echo "setup flow FAILED" >&2; exit 1; }

echo "== denial path (permission revoked, separate run) =="
# Revoking a dangerous permission kills the target process, hence a second run.
adb shell "pm revoke $pkg com.termux.permission.RUN_COMMAND" || true
denied_log=$(mktemp)
adb shell am instrument -w -e class "$pkg.PermissionDeniedTest" "$runner" | tee "$denied_log"
grep -q 'OK (1 test)' "$denied_log" || { echo "denial path FAILED" >&2; exit 1; }

echo
echo "both runs passed"
echo "screenshots are in the app's external files dir:"
adb shell "ls /sdcard/Android/data/$pkg/files/screenshots" 2>/dev/null || true
