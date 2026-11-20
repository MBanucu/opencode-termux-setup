#!/usr/bin/env bash
# Runs the grid's tests on the JVM. The screen model, its parser, history,
# viewport, selection, key encoder and reply sink have no Android dependency,
# so this needs no device, no Gradle and no test framework - only a javac.
#
#   bash terminal/test.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$here/out/test
rm -rf "$work"
mkdir -p "$work"

javac -nowarn --release 17 -d "$work" \
    "$here/src/main/java/io/github/mbanucu/terminal/Screen.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Sgr.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/CsiCommands.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/EscapeParser.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Scrollback.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/HistoryRow.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/AnsiPalette.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Width.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/CellAttributes.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Viewport.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Selection.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/KeyEncoder.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/ReplySink.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Modes.java" \
    "$here/src/main/java/io/github/mbanucu/terminal/Replies.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/TestSupport.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/CursorTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/EraseTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/ScrollTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/SequenceTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/InputModeTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/AttributeTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/WideTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/ColourTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/WritingTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/ScrollbackTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/ViewportTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/SelectionTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/KeyEncoderTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/CharacterTest.java" \
    "$here/src/test/java/io/github/mbanucu/terminal/QueryReplyTest.java"
java -cp "$work" io.github.mbanucu.terminal.CursorTest
java -cp "$work" io.github.mbanucu.terminal.EraseTest
java -cp "$work" io.github.mbanucu.terminal.ScrollTest
java -cp "$work" io.github.mbanucu.terminal.SequenceTest
java -cp "$work" io.github.mbanucu.terminal.InputModeTest
java -cp "$work" io.github.mbanucu.terminal.AttributeTest
java -cp "$work" io.github.mbanucu.terminal.WideTest
java -cp "$work" io.github.mbanucu.terminal.ColourTest
java -cp "$work" io.github.mbanucu.terminal.WritingTest
java -cp "$work" io.github.mbanucu.terminal.ScrollbackTest
java -cp "$work" io.github.mbanucu.terminal.ViewportTest
java -cp "$work" io.github.mbanucu.terminal.SelectionTest
java -cp "$work" io.github.mbanucu.terminal.KeyEncoderTest
java -cp "$work" io.github.mbanucu.terminal.CharacterTest
java -cp "$work" io.github.mbanucu.terminal.QueryReplyTest
