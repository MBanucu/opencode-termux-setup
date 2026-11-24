#!/usr/bin/env bash
# Builds one sample app into samples/out/<app>.apk without Gradle.
#
#   bash samples/build.sh xterm-a11y
#   bash test/local-build.sh --samples   # builds it
#
# The point of these samples is to be readable and runnable on the device this
# repository was written on, where Gradle's aapt2 has no aarch64 build. The steps
# are the ones Gradle would take: aapt2 link, javac, d8, zip in the dex and the
# native libraries, zipalign, apksigner.
#
# Conventions, so each app stays a directory of files and no build config:
#   AndroidManifest.xml   the manifest
#   res/                  optional resources
#   src/                  Java sources
#   assets/               optional assets (xterm-a11y copies the app's copy in)
#   jni/*.c               optional native sources, built into one library named
#                         after the app directory (native-terminal ->
#                         libnative_terminal.so, so System.loadLibrary("native_terminal"))
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
# The same candidates test/local-build.sh looks at, so a machine that can build
# the app can build a sample and neither script has its own idea of where the SDK
# lives. The main script exports ANDROID_HOME before calling this one.
sdk=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" /usr/lib/android-sdk; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        sdk=$candidate
        break
    fi
done
if [ -z "$sdk" ]; then
    echo "no Android SDK found (looked at ANDROID_HOME, ANDROID_SDK_ROOT," >&2
    echo "$HOME/android-sdk and /usr/lib/android-sdk)." >&2
    exit 1
fi
api=35
android_jar=$sdk/platforms/android-$api/android.jar
build_tools=$sdk/build-tools/35.0.0
[ -f "$android_jar" ] || { echo "no $android_jar" >&2; exit 1; }
for tool in aapt2 d8 zipalign apksigner javac clang python3; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

app=${1:-}
case "$app" in
    all)
        shift
        exec bash "$here/build.sh" xterm-a11y "$@"
        ;;
    test|--test)
        shift
        # One entry for every suite. A second copy of the build order here is how
        # this script and the repository's build loop drifted apart.
        exec bash "$root/test/local-build.sh" --samples "$@"
        ;;
esac
app=${1:?usage: build.sh <app directory>}
dir=$here/$app
[ -f "$dir/AndroidManifest.xml" ] || { echo "no $dir/AndroidManifest.xml" >&2; exit 1; }
[ -d "$dir/assets/xterm" ] || {
    # The WebView sample renders with the same bundle the app ships; copying it
    # at build time keeps one copy of xterm.js in the repository.
    if [ -d "$root/android/app/src/main/assets/xterm" ] && [ -f "$dir/assets/terminal.html" ]; then
        cp -r "$root/android/app/src/main/assets/xterm" "$dir/assets/xterm"
    fi
}
[ -f "$dir/assets/terminal-mono.ttf" ] || {
    # TerminalView loads this from its host's assets. The app ships the one copy
    # the WebView page already uses; a sample gets it at build time the same way
    # it gets xterm.js.
    if [ -d "$dir/assets" ] && [ -f "$root/android/app/src/main/assets/terminal-mono.ttf" ]; then
        cp "$root/android/app/src/main/assets/terminal-mono.ttf" "$dir/assets/terminal-mono.ttf"
    fi
}

work=$here/out/$app
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$work/gen"

# --- resources -----------------------------------------------------------------
link_args=()
if [ -d "$dir/res" ]; then
    mkdir -p "$work/res"
    aapt2 compile --dir "$dir/res" -o "$work/res/"
    for flat in "$work"/res/*.flat; do link_args+=("$flat"); done
fi
if [ -d "$dir/assets" ]; then
    link_args+=(-A "$dir/assets")
fi

echo "== $app: aapt2 link =="
aapt2 link -o "$work/base.apk" \
    -I "$android_jar" \
    --manifest "$dir/AndroidManifest.xml" \
    --java "$work/gen" \
    --min-sdk-version 24 --target-sdk-version "$api" \
    --version-code 1 --version-name 1.0 \
    "${link_args[@]}"

# --- java ----------------------------------------------------------------------
echo "== $app: javac =="
mapfile -t sources < <(find "$dir/src" "$work/gen" -name '*.java' 2>/dev/null)
javac -nowarn --release 17 -classpath "$android_jar" -d "$work/classes" "${sources[@]}"

python3 - "$work/classes" "$work/classes.jar" <<'EOF'
import os, sys, zipfile
classes, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(classes):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, classes))
EOF

echo "== $app: d8 =="
d8 --lib "$android_jar" --min-api 24 --output "$work/dex" "$work/classes.jar"

# --- native --------------------------------------------------------------------
libs=()
if [ -d "$dir/jni" ]; then
    echo "== $app: clang =="
    libname=$(echo "$app" | tr '-' '_')
    mkdir -p "$work/lib/arm64-v8a"
    # Termux's clang adds a runpath into the Termux prefix; it does not exist in
    # an app's mount namespace, so the loader skips it. Nothing here links
    # against a library an app cannot load: bionic's libc carries openpty.
    clang --target=aarch64-linux-android24 -fPIC -shared -O2 \
        -I "$PREFIX/include" \
        -o "$work/lib/arm64-v8a/lib$libname.so" "$dir"/jni/*.c
    command -v patchelf >/dev/null && patchelf --remove-rpath "$work/lib/arm64-v8a/lib$libname.so" || true
    libs+=("lib/arm64-v8a/lib$libname.so")
fi

# --- package -------------------------------------------------------------------
echo "== $app: package =="
python3 - "$work/base.apk" "$work/dex" "$work/unaligned.apk" "$work/lib" "$app" <<'EOF'
import os, sys, zipfile
base, dexdir, out, libroot, app = sys.argv[1:6]
with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, 'w') as dst:
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    dst.write(os.path.join(dexdir, 'classes.dex'), 'classes.dex')
    if os.path.isdir(libroot):
        for base_dir, _, files in os.walk(libroot):
            for name in files:
                path = os.path.join(base_dir, name)
                # lib/<abi>/<name>.so: the prefix is what makes the loader find it.
                dst.write(path, 'lib/' + os.path.relpath(path, libroot))
EOF

zipalign -f 4 "$work/unaligned.apk" "$work/aligned.apk"
keystore=$HOME/.android/debug.keystore
[ -f "$keystore" ] || keytool -genkeypair -keystore "$keystore" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
mkdir -p "$here/out"
apksigner sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$here/out/$app.apk" "$work/aligned.apk"
echo "built $here/out/$app.apk ($(wc -c < "$here/out/$app.apk") bytes)"

# --- adb ----------------------------------------------------------------------
# A build is usually followed by an install, and the wireless-debugging port
# changes between sessions. adb-harness (test/requirements.txt) has the lookup
# and the watcher; this never fails the build, and ADB_CONNECT=0 skips it for an
# offline build.
harness_bin() {
    if [ -x "$root/test/.venv/bin/$1" ]; then
        printf '%s\n' "$root/test/.venv/bin/$1"
    elif command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
    else
        return 1
    fi
}

if [ "${ADB_CONNECT:-1}" != 0 ] && command -v adb >/dev/null; then
    adb_watch=$(harness_bin adb-watch || true)
    adb_connect=$(harness_bin adb-connect || true)
    # Keep the pid and log with the rest of the repository's e2e state instead
    # of an out/ directory relative to wherever the build was started.
    export ADB_WATCH_OUT=${ADB_WATCH_OUT:-$root/test/out}
    if [ -n "$adb_watch" ] && ! "$adb_watch" --status >/dev/null 2>&1; then
        echo "== $app: starting the adb port watcher"
        setsid nohup "$adb_watch" >/dev/null 2>&1 < /dev/null &
    fi
    if adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'; then
        echo "== $app: adb device already connected"
    elif [ -n "$adb_connect" ]; then
        echo "== $app: looking up the wireless-debugging port"
        "$adb_connect" --timeout 4 2>&1 | sed 's/^/   /' || true
    else
        echo "== $app: adb-harness is not installed; skipping discovery"
        echo "   python3 -m venv test/.venv"
        echo "   test/.venv/bin/pip install -r test/requirements.txt"
    fi
fi
