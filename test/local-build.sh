#!/usr/bin/env bash
#
# Builds the app and the instrumented test APKs on this machine, then runs the
# end-to-end suite against a connected device.  This is the loop for checking a
# change without pushing to GitHub, where .github/workflows/android.yml is the
# compiler of record.
#
#   bash test/local-build.sh                 # build, then the app and sample suites
#   bash test/local-build.sh --offline       # same, without Gradle hitting the network
#   bash test/local-build.sh --skip-tests    # build only, print the APK paths
#   bash test/local-build.sh --skip-samples  # the app and its suite, not the samples
#   bash test/local-build.sh --samples       # only the sample apps' checks
#   bash test/local-build.sh --only <class>[#method]   # one test instead of the suite
#
# Needs: JDK 17+, an Android SDK (ANDROID_HOME, ANDROID_SDK_ROOT, ~/android-sdk
# or /usr/lib/android-sdk), and for the suite adb plus Termux with
# allow-external-apps=true.
#
# Two things this does that a bare ./gradlew does not:
#
#   * The SDK path goes into android/local.properties (git-ignored) as well as
#     the environment, because a running Gradle daemon keeps the environment it
#     was started with and otherwise reports "SDK location not found".
#
#   * Termux's aarch64 aapt2 is used when it is installed.  Google ships aapt2
#     for x86_64 only, so on an aarch64 host AGP's copy dies with "AAPT2 daemon
#     startup failed"; `pkg install aapt2` provides a native one, passed here as
#     a command-line property so nothing machine-specific lands in gradle.properties.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
android=$root/android
offline=false
build=true
run_tests=true
run_samples=true
samples_only=false
only=""

while [ $# -gt 0 ]; do
    case $1 in
        --offline)      offline=true; shift ;;
        --skip-tests)   run_tests=false; run_samples=false; shift ;;
        --skip-samples) run_samples=false; shift ;;
        --samples)
            samples_only=true
            build=false
            run_tests=false
            shift ;;
        --only)
            [ -n "${2:-}" ] || { echo "--only needs a class or class#method" >&2; exit 2; }
            only=$2
            shift 2 ;;
        --help|-h)
            echo "usage: bash test/local-build.sh [--offline] [--skip-tests] [--skip-samples]"
            echo "       bash test/local-build.sh --samples [--only <check>]"
            echo "       bash test/local-build.sh --only <class>[#method]"
            exit 0 ;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
done

# `--only` names one instrumented test; that is not the samples suite, which
# would otherwise run its whole set around a request for a single test.
if [ -n "$only" ] && ! $samples_only; then
    run_samples=false
fi

# --- SDK -----------------------------------------------------------------------
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
    echo "install the packages the app compiles against, then retry:" >&2
    echo "  sdkmanager \"platforms;android-35\" \"build-tools;35.0.0\" \"platform-tools\"" >&2
    exit 1
fi
export ANDROID_HOME=$sdk ANDROID_SDK_ROOT=$sdk
echo "sdk: $sdk"

if ! grep -qx "sdk.dir=$sdk" "$android/local.properties" 2>/dev/null; then
    printf 'sdk.dir=%s\n' "$sdk" > "$android/local.properties"
fi

args=()
if [ -x "${PREFIX:-/nonexistent}/bin/aapt2" ]; then
    if ! grep -qs 'aapt2FromMavenOverride' \
        "$android/gradle.properties" "$HOME/.gradle/gradle.properties"; then
        args+=("-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2")
        echo "aapt2: $PREFIX/bin/aapt2"
    fi
fi
if $offline; then
    args+=(--offline)
fi

cd "$android"

apk=$android/app/build/outputs/apk/debug/app-debug.apk
test_apk=$android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
start=$SECONDS
built=0

# --- build ---------------------------------------------------------------------
if $build; then
    # One Gradle invocation for both APKs: the test APK shares everything the app
    # APK already produced.  Both must be built here rather than mixed with a CI
    # artifact, because CI signs each run with its own debug keystore and adb then
    # refuses the instrumentation with a Permission Denial.
    echo "== build =="
    build_start=$SECONDS
    ./gradlew ${args[@]+"${args[@]}"} assembleDebug assembleDebugAndroidTest
    built=$((SECONDS - build_start))

    for f in "$apk" "$test_apk"; do
        [ -f "$f" ] || { echo "gradle reported success but $f is missing" >&2; exit 1; }
    done
    echo "   built in ${built}s"
    echo "   app:  $(wc -c < "$apk") bytes"
    echo "   test: $(wc -c < "$test_apk") bytes"
fi

# --- terminal JVM suite --------------------------------------------------------
# The grid and its models are plain Java: no device, no Gradle, and no part of the
# samples, which only happen to compile the same module. It runs next to the app
# suite, so one build check covers both halves of the repository's tests.
if $run_tests; then
    echo
    echo "== terminal JVM suite =="
    terminal_start=$SECONDS
    bash "$root/terminal/test.sh"
    echo "   terminal suite in $((SECONDS - terminal_start))s"
fi

# --- end-to-end suite ----------------------------------------------------------
if $run_tests; then
    echo
    echo "== end-to-end suite =="
    if [ -n "$only" ]; then
        echo "(one test: $only)"
    fi
    suite_start=$SECONDS
    if ! bash "$root/test/android-e2e.sh" ${only:+--only "$only"} "$apk" "$test_apk"; then
        echo "suite FAILED after $((SECONDS - suite_start))s" >&2
        exit 1
    fi
    echo "   suite in $((SECONDS - suite_start))s"
elif $build; then
    echo
    echo "tests skipped; run them with:"
    echo "  bash test/android-e2e.sh \"$apk\" \"$test_apk\""
fi

# --- sample apps ---------------------------------------------------------------
# The samples are not Gradle modules and have their own build and checks; running
# them from here is what keeps one command - and one SDK lookup - for the whole
# repository. The checks install the APKs and drive the probe themselves.
if $run_samples; then
    cd "$root"
    echo
    echo "== sample apps =="
    samples_start=$SECONDS
    bash "$root/samples/build.sh" all
    if ! bash "$root/samples/e2e.sh" ${only:+--only "$only"}; then
        echo "samples FAILED after $((SECONDS - samples_start))s" >&2
        exit 1
    fi
    echo "   samples in $((SECONDS - samples_start))s"
fi

echo
echo "build ${built}s, total $((SECONDS - start))s"
