#!/data/data/com.termux/files/usr/bin/bash
#
# Checks the F-Droid metadata that can be verified without fdroidserver:
# app-id agreement, version parity, tag existence, summary/changelog presence,
# fastlane field limits, and YAML parseability.
#
#   bash test/fdroid-metadata-test.sh
#   ALLOW_MISSING_TAG=1 bash test/fdroid-metadata-test.sh
#   bash test/fdroid-metadata-test.sh --allow-missing-tag
#
# `Builds.commit` may name an `android-v*` tag that is created only when the
# release is tagged.  A version-bump pull request is therefore correct while
# the tag is still absent.  `ALLOW_MISSING_TAG=1` (or `--allow-missing-tag`)
# turns a missing tag into a printed warning; the default is the strict
# post-merge behaviour, where a missing tag fails the run.  Other missing
# commits and every other check stay failures in both modes.
#
set -uo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
id_expected=$(basename "$root/fdroiddata/io.github.mbanucu.opencodesetup.yml" .yml)
yml="$root/fdroiddata/$id_expected.yml"
gradle="$root/android/app/build.gradle.kts"
catalog="$root/android/gradle/libs.versions.toml"
fastlane="$root/android/fastlane/metadata/android/en-US"
failures=0
warnings=0
allow_missing_tag=${ALLOW_MISSING_TAG:-0}

for arg in "$@"; do
    case "$arg" in
        --allow-missing-tag) allow_missing_tag=1 ;;
        -h | --help)
            sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "unknown option: $arg (try --help)" >&2
            exit 2
            ;;
    esac
done

pass() { printf '  ok    %s\n' "$1"; }
warn() {
    printf '  warn  %s\n' "$1"
    warnings=$((warnings + 1))
}
fail() {
    printf '  FAIL  %s\n' "$1"
    failures=$((failures + 1))
}
skip() { printf '  skip  %s\n' "$1"; }

[ -f "$yml" ] || {
    echo "missing $yml"
    exit 1
}
[ -f "$gradle" ] || {
    echo "missing $gradle"
    exit 1
}
[ -f "$catalog" ] || {
    echo "missing $catalog"
    exit 1
}

echo "app id"
namespace=$(sed -n 's/^ *namespace = "\(.*\)"/\1/p' "$gradle" | head -1)
application_id=$(sed -n 's/^ *applicationId = "\(.*\)"/\1/p' "$gradle" | head -1)
[ "$application_id" = "$id_expected" ] &&
    pass "applicationId matches the recipe name ($application_id)" ||
    fail "applicationId '$application_id' != recipe '$id_expected'"
[ "$namespace" = "$application_id" ] &&
    pass "namespace matches applicationId" ||
    fail "namespace '$namespace' != applicationId '$application_id'"

echo "version parity (libs.versions.toml vs recipe)"
name=$(sed -n 's/^appVersionName = "\(.*\)"/\1/p' "$catalog" | head -1)
code=$(sed -n 's/^appVersionCode = "\(.*\)"/\1/p' "$catalog" | head -1)
grep -q 'libs.versions.appVersionName' "$gradle" &&
    pass "the app reads versionName from the catalog" ||
    fail "app/build.gradle.kts does not use libs.versions.appVersionName"
grep -q 'libs.versions.appVersionCode' "$gradle" &&
    pass "the app reads versionCode from the catalog" ||
    fail "app/build.gradle.kts does not use libs.versions.appVersionCode"
# The first key of a Builds entry carries the YAML list dash: "- versionName:".
build_name=$(sed -n '/^Builds:/,/^[A-Z]/s/^ *-* *versionName: \(.*\)/\1/p' "$yml" | head -1)
build_code=$(sed -n '/^Builds:/,/^[A-Z]/s/^ *-* *versionCode: \(.*\)/\1/p' "$yml" | head -1)
current_name=$(sed -n 's/^CurrentVersion: \(.*\)/\1/p' "$yml" | head -1)
current_code=$(sed -n 's/^CurrentVersionCode: \(.*\)/\1/p' "$yml" | head -1)
[ -n "$name" ] && [ "$build_name" = "$name" ] &&
    pass "Builds.versionName == $name" ||
    fail "Builds.versionName '$build_name' != catalog '$name'"
[ -n "$code" ] && [ "$build_code" = "$code" ] &&
    pass "Builds.versionCode == $code" ||
    fail "Builds.versionCode '$build_code' != catalog '$code'"
[ "$current_name" = "$name" ] &&
    pass "CurrentVersion == $name" ||
    fail "CurrentVersion '$current_name' != '$name'"
[ "$current_code" = "$code" ] &&
    pass "CurrentVersionCode == $code" ||
    fail "CurrentVersionCode '$current_code' != '$code'"

echo "release commit"
# fdroiddata wants the full hash here, not a tag, so accept either and check the
# one that is present: a hash must resolve in this repository, a tag must exist.
commit_ref=$(sed -n '/^Builds:/,/^[A-Z]/s/^ *commit: \(.*\)/\1/p' "$yml" | head -1)
if git -C "$root" rev-parse --git-dir >/dev/null 2>&1; then
    case "$commit_ref" in
        android-v*)
            if [ -n "$(git -C "$root" tag -l "$commit_ref")" ]; then
                pass "tag $commit_ref exists"
            elif [ "$allow_missing_tag" = 1 ] || [ "$allow_missing_tag" = true ]; then
                warn "tag $commit_ref does not exist yet; allowed on pull requests, strict on main (git tag $commit_ref)"
            else
                fail "tag $commit_ref does not exist (git tag $commit_ref)"
            fi ;;
        *)
            if printf '%s' "$commit_ref" | grep -qE '^[0-9a-f]{40}$' &&
                git -C "$root" cat-file -e "$commit_ref^{commit}" 2>/dev/null; then
                pass "commit ${commit_ref:0:8} exists (full hash, as fdroiddata asks)"
            else
                fail "Builds.commit '$commit_ref' is neither a full commit hash in this repo nor an android-v* tag"
            fi ;;
    esac
else
    skip "not a git checkout; cannot verify commit $commit_ref"
fi

echo "recipe fields fdroiddata's CI requires"
for key in AutoUpdateMode UpdateCheckMode License RepoType Repo Builds output subdir; do
    grep -qE "^ *$key:" "$yml" && pass "$key present" || fail "$key missing"
done
grep -qE '^ *Summary:' "$yml" &&
    fail "summary must live in metadata/<appid>/en-US/summary.txt, not in the YAML" ||
    pass "no Summary key in the YAML"

echo "summary and changelog"
summary_file="$root/fdroiddata/$id_expected/en-US/summary.txt"
if [ -s "$summary_file" ]; then
    length=$(wc -m <"$summary_file" | tr -d ' ')
    [ "$length" -le 80 ] && pass "summary is $length chars (limit 80)" ||
        fail "summary is $length chars, limit is 80"
else
    fail "missing $summary_file"
fi
changelog="$fastlane/changelogs/$code.txt"
[ -s "$changelog" ] && pass "changelog/changelogs/$code.txt exists" ||
    fail "missing $changelog for versionCode $code"

echo "fastlane limits"
check_len() {
    local path="$1" limit="$2" label="$3"
    if [ ! -s "$path" ]; then
        fail "missing $path"
        return
    fi
    local length
    length=$(wc -m <"$path" | tr -d ' ')
    [ "$length" -le "$limit" ] && pass "$label is $length chars (limit $limit)" ||
        fail "$label is $length chars, limit is $limit"
}
check_len "$fastlane/title.txt" 30 "title"
check_len "$fastlane/short_description.txt" 80 "short_description"
check_len "$fastlane/full_description.txt" 4000 "full_description"

echo "yaml parses"
if python3 -c 'import yaml' >/dev/null 2>&1; then
    python3 - "$yml" <<'PY'
import sys, yaml
with open(sys.argv[1]) as handle:
    data = yaml.safe_load(handle)
assert isinstance(data, dict), "top level is not a mapping"
missing = [k for k in ("Categories", "License", "Repo", "RepoType", "Builds",
                       "AutoUpdateMode", "UpdateCheckMode", "CurrentVersion",
                       "CurrentVersionCode") if k not in data]
assert not missing, "missing keys: %s" % missing
assert isinstance(data["Builds"], list) and data["Builds"], "Builds is empty"
print("  ok    parsed with pyyaml; %d build entry" % len(data["Builds"]))
PY
    [ $? -eq 0 ] || failures=$((failures + 1))
elif [ -d "${NODE_PATH:-/nonexistent}/yaml" ]; then
    NODE_PATH="$NODE_PATH" node -e '
      const yaml = require("yaml");
      const text = require("fs").readFileSync(process.argv[1], "utf8");
      const data = yaml.parse(text);
      if (!data || typeof data !== "object") throw new Error("top level is not a mapping");
      if (!Array.isArray(data.Builds) || data.Builds.length === 0) throw new Error("Builds is empty");
      console.log("  ok    parsed with the yaml npm package");
    ' "$yml" || failures=$((failures + 1))
else
    skip "no YAML parser (pip install pyyaml, or npm i yaml and set NODE_PATH)"
fi

echo
if [ "$failures" -eq 0 ]; then
    if [ "$warnings" -gt 0 ]; then
        echo "all metadata checks passed with $warnings warning(s)"
    else
        echo "all metadata checks passed"
    fi
else
    echo "$failures check(s) failed"
fi
exit $((failures > 0))
