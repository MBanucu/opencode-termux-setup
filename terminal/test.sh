#!/usr/bin/env bash
# Runs the grid's tests on the JVM. Screen, Scrollback, HistoryRow, AnsiPalette,
# Viewport and Selection have no Android dependency, so this needs no device,
# no Gradle and no test framework - only a javac.
#
#   bash terminal/test.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$here/out/test
rm -rf "$work"
mkdir -p "$work"

javac -nowarn --release 17 -d "$work" \
    "$here/src/main/java/io/github/mbanucu/terminal/Screen.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Scrollback.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/HistoryRow.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/AnsiPalette.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Viewport.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Selection.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/TestSupport.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/ScreenTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/ScrollbackTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/ViewportTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/SelectionTest.java"
java -cp "$work" io.github.mbanucu.terminal.ScreenTest
java -cp "$work" io.github.mbanucu.terminal.ScrollbackTest
java -cp "$work" io.github.mbanucu.terminal.ViewportTest
java -cp "$work" io.github.mbanucu.terminal.SelectionTest
