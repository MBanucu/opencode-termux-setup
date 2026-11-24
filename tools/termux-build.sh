#!/usr/bin/env bash
#
# First-run build entry point for Termux.
#
# Resolves the Android SDK ($ANDROID_HOME, $ANDROID_SDK_ROOT, or the Termux
# default $HOME/android-sdk), validates the compile platform and build-tools,
# points AGP at Termux's bionic aapt2 when the Maven one cannot run, and then
# runs `checkBuildEnv` plus the tasks you asked for.
#
# Usage:
#   tools/termux-build.sh                              # assembleDebug
#   tools/termux-build.sh assembleDebugAndroidTest
#   tools/termux-build.sh --offline assembleDebug assembleDebugAndroidTest
#   tools/termux-build.sh lintDebug
#   tools/termux-build.sh --check                      # print the resolved env only
#
# The script never writes local.properties: the SDK is passed as AGP's
# android.home system property for this process, so a clean checkout stays
# clean and a reused Gradle daemon cannot hide the value.

set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/.." && pwd)
android=$root/android
catalog=$android/gradle/libs.versions.toml

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

catalog_value() {
    sed -n "s/^$1 = \"\\(.*\\)\"/\\1/p" "$catalog" | head -1
}

[ -f "$catalog" ] || die "missing $catalog; restore android/gradle/libs.versions.toml"
compile_sdk=$(catalog_value compileSdk)
build_tools=$(catalog_value buildTools)
[ -n "$compile_sdk" ] || die "compileSdk is missing from $catalog"
[ -n "$build_tools" ] || die "buildTools is missing from $catalog"

if [ -f "$android/local.properties" ]; then
    local_sdk=$(sed -n 's/^sdk\.dir=//p' "$android/local.properties" | head -1)
else
    local_sdk=""
fi

if [ -n "$local_sdk" ]; then
    sdk=$local_sdk
elif [ -n "${ANDROID_HOME:-}" ]; then
    sdk=$ANDROID_HOME
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    sdk=$ANDROID_SDK_ROOT
elif [ -d "${HOME:-}/android-sdk" ]; then
    sdk=$HOME/android-sdk
else
    die "no Android SDK found. Install one and export ANDROID_HOME (Termux usually keeps it at \$HOME/android-sdk), or run: sdkmanager \"platforms;android-$compile_sdk\" \"build-tools;$build_tools\""
fi

[ -d "$sdk" ] || die "ANDROID_HOME points at '$sdk', which is not a directory"
[ -f "$sdk/platforms/android-$compile_sdk/android.jar" ] || die "'$sdk' has no platform $compile_sdk; run: sdkmanager \"platforms;android-$compile_sdk\""
[ -d "$sdk/build-tools/$build_tools" ] || die "'$sdk' has no build-tools $build_tools; run: sdkmanager \"build-tools;$build_tools\""

# AGP downloads a glibc aapt2 that cannot execute on bionic.  Termux ships a
# compatible one in $PREFIX/bin; prefer it unless the user already configured an
# override in ~/.gradle/gradle.properties or on the command line.
aapt2=${AAPT2:-${PREFIX:-}/bin/aapt2}
if [ -n "${AAPT2:-}" ] && [ ! -x "$aapt2" ]; then
    die "AAPT2=$AAPT2 is not executable"
fi
user_override=0
if grep -qs '^android\.aapt2FromMavenOverride=' "${HOME:-}/.gradle/gradle.properties" 2>/dev/null; then
    user_override=1
fi

args=("$@")
if [ "$user_override" = 1 ]; then
    aapt2_from_script=""
elif [ -n "${PREFIX:-}" ] && [ -x "$aapt2" ]; then
    aapt2_from_script=$aapt2
elif [ -n "${PREFIX:-}" ]; then
    die "Termux's bionic aapt2 is missing. Install it with: pkg install aapt2"
else
    aapt2_from_script=""
fi

if [ "${args[0]:-}" = "--check" ]; then
    if [ -n "$aapt2_from_script" ]; then
        aapt2_label=$aapt2_from_script
    elif [ "$user_override" = 1 ]; then
        aapt2_label="configured in ~/.gradle/gradle.properties"
    else
        aapt2_label="AGP's own"
    fi
    printf 'ANDROID_HOME=%s\n' "$sdk"
    printf 'compileSdk=%s\n' "$compile_sdk"
    printf 'buildTools=%s\n' "$build_tools"
    printf 'aapt2=%s\n' "$aapt2_label"
    exit 0
fi
if [ ${#args[@]} -eq 0 ]; then
    args=(assembleDebug)
fi

export ANDROID_HOME=$sdk
export ANDROID_SDK_ROOT=$sdk

if [ -n "$aapt2_from_script" ]; then
    printf 'Using Android SDK %s and bionic aapt2 %s\n' "$sdk" "$aapt2_from_script" >&2
else
    printf 'Using Android SDK %s\n' "$sdk" >&2
fi

gradle=${GRADLE:-$android/gradlew}
[ -x "$gradle" ] || die "no Gradle at '$gradle'; set GRADLE=gradle or restore the wrapper"

cd "$android"
# -Dandroid.home is AGP's documented system property.  Pass it explicitly so a
# reused Gradle daemon cannot hide the SDK this script just resolved.  The
# aapt2 override goes on the command line for the same reason.
gradle_args=(--console=plain --warning-mode all "-Dandroid.home=$sdk" checkBuildEnv)
if [ -n "$aapt2_from_script" ]; then
    gradle_args+=("-Pandroid.aapt2FromMavenOverride=$aapt2_from_script")
fi
exec "$gradle" "${gradle_args[@]}" "${args[@]}"
