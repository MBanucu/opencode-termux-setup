#!/usr/bin/env bash
# Runs the grid's tests on the JVM. The screen model, its parser, history,
# viewport, selection, key encoder and reply sink have no Android dependency,
# so this needs no device, no Gradle and no test framework - only a javac.
#
# The files are found, not listed: a new model, and a new `*Test.java`, runs here
# without editing this script - the same way the instrumented tests are found by
# their runner rather than named one by one. `TerminalView` and
# `TerminalAccessibility` import android.* and need android.jar; the view is
# covered on a device by `samples/e2e.sh`.
#
# Two things make this fast, both borrowed from how the app's tests are run:
# javac runs again only when a source or the set of files changed (Gradle gives
# the app that for free, and compiling was the larger half of the clock), and
# every suite runs in one JVM through SuiteRunner (one start cost more than the
# checks it ran, the way one instrumentation run per class did on the device).
#
#   bash terminal/test.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$here/out/test
stamp=$here/out/test.stamp
listing=$here/out/test.sources
mkdir -p "$here/out"

# Every model in the module except the two that draw and speak.
mapfile -t sources < <(find "$here/src/main/java" -name '*.java' \
    ! -name TerminalView.java ! -name TerminalAccessibility.java | sort)
# TestSupport is compiled but not run: it is the suites' shared assertion helper.
mapfile -t test_sources < <(find "$here/src/test/java" -name '*.java' | sort)
mapfile -t tests < <(find "$here/src/test/java" -name '*Test.java' | sort)

current=$listing.new
printf '%s\n' "${sources[@]}" "${test_sources[@]}" > "$current"

# A deleted suite must not leave its class behind, so the file list is compared
# as well as the timestamps. A missing output directory also forces a build.
if [ ! -f "$stamp" ] || [ ! -f "$listing" ] || [ ! -d "$work" ] \
    || ! cmp -s "$current" "$listing" \
    || [ -n "$(find "$here/src" -name '*.java' -newer "$stamp" -print -quit)" ]; then
    rm -rf "$work"
    mkdir -p "$work"
    javac -nowarn --release 17 -d "$work" \
        "${sources[@]}" "${test_sources[@]}"
    mv "$current" "$listing"
    touch "$stamp"
else
    rm -f "$current"
fi

classes=()
for file in "${tests[@]}"; do
    class=${file#"$here/src/test/java/"}
    class=${class%.java}
    classes+=("${class//\//.}")
done

java -cp "$work" io.github.mbanucu.terminal.SuiteRunner "${classes[@]}"
