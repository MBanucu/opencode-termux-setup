#!/usr/bin/env bash
# Hosted x86_64 ui-only emulator script.
#
# reactivecircus/android-emulator-runner splits its `script` input on newlines
# and runs every line as a separate `sh -c`, so variables and multi-line
# constructs cannot live in the workflow. Keep the whole script here and call
# it from `script:` as one command.

set -euo pipefail

# The action only runs this script once the emulator has booted, but say which
# device was chosen and refuse to test anything else.
serial=$(adb get-serialno | tr -d '\r')
case "$serial" in
  emulator-*) ;;
  *)
    echo "::error::no emulator device: adb get-serialno returned '$serial'"
    exit 1
    ;;
esac
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
if [ "$abi" != x86_64 ]; then
  echo "::error::expected an x86_64 AVD, got '$abi'"
  exit 1
fi
echo "emulator $serial, abi $abi"

adb install -r -g "$RUNNER_TEMP/termux.apk"

# allow-external-apps has to exist before Termux first reads it. run-as is why
# the workflow fetches the debuggable build.
adb shell "run-as com.termux sh -c '
  mkdir -p files/home/.termux
  if ! grep -qx allow-external-apps=true files/home/.termux/termux.properties 2>/dev/null; then
    echo allow-external-apps=true >> files/home/.termux/termux.properties
  fi
'"

# First launch extracts the bundled bootstrap; wait for its bash.
adb shell am start -W -n com.termux/.app.TermuxActivity
for _ in $(seq 1 90); do
  adb shell run-as com.termux test -x files/usr/bin/bash 2>/dev/null && break
  sleep 2
done
if ! adb shell run-as com.termux test -x files/usr/bin/bash; then
  echo "::error::Termux bootstrap never produced files/usr/bin/bash"
  exit 1
fi

# The app wraps commands in `script` and resizes with `stty`; the base bootstrap
# does not guarantee them. No opencode, and no package from the opencode
# repository.
adb shell "run-as com.termux env \
  HOME=/data/data/com.termux/files/home \
  PREFIX=/data/data/com.termux/files/usr \
  PATH=/data/data/com.termux/files/usr/bin:/system/bin \
  bash -lc 'pkg update -y && pkg install -y util-linux coreutils'"

if adb shell run-as com.termux test -e files/usr/bin/opencode; then
  echo "::error::opencode is installed; the ui-only job must not depend on it"
  exit 1
fi

# The script uses the emulator adb already lists and refuses to run the install
# group. Exact test counts are checked by the script.
bash test/android-e2e.sh --ui-only --no-record
