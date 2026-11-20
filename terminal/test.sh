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
#   bash terminal/test.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$here/out/test
rm -rf "$work"
mkdir -p "$work"

# Every model in the module except the two that draw and speak.
mapfile -t sources < <(find "$here/src/main/java" -name '*.java' \
    ! -name TerminalView.java ! -name TerminalAccessibility.java | sort)
# TestSupport is compiled but not run: it is the suites' shared assertion helper.
mapfile -t test_sources < <(find "$here/src/test/java" -name '*.java' | sort)
mapfile -t tests < <(find "$here/src/test/java" -name '*Test.java' | sort)

javac -nowarn --release 17 -d "$work" \
    "${sources[@]}" "${test_sources[@]}"

for file in "${tests[@]}"; do
    class=${file#"$here/src/test/java/"}
    class=${class%.java}
    java -cp "$work" "${class//\//.}"
done
