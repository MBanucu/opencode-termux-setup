#!/usr/bin/env bash
# Builds one sample app into samples/out/<app>.apk without Gradle.
#
#   bash samples/build.sh native-terminal
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
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
api=35
android_jar=$sdk/platforms/android-$api/android.jar
build_tools=$sdk/build-tools/35.0.0
[ -f "$android_jar" ] || { echo "no $android_jar" >&2; exit 1; }
for tool in aapt2 d8 zipalign apksigner javac clang python3; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

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
# The terminal view is a module, not part of any one app: terminal/src is compiled
# into every sample, the way a library module's source set would be. Only the
# classes a sample references end up in its dex, so compiling it everywhere costs
# a second and keeps the module honest about being self-contained.
if grep -rq 'io\.github\.mbanucu\.terminal' "$dir/src" 2>/dev/null; then
    mapfile -t sources < <(find "$dir/src" "$work/gen" "$root/terminal/src/main/java" -name '*.java')
else
    mapfile -t sources < <(find "$dir/src" "$work/gen" -name '*.java' 2>/dev/null)
fi
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
