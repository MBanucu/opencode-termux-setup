#!/data/data/com.termux/files/usr/bin/bash
#
# Checks the F-Droid metadata that can be verified without fdroidserver:
# app-id agreement, version parity, tag existence, summary/changelog presence,
# fastlane field limits, and YAML parseability.
#
#   bash test/fdroid-metadata-test.sh
#
set -uo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
id_expected=$(basename "$root/fdroiddata/io.github.mbanucu.opencodesetup.yml" .yml)
yml="$root/fdroiddata/$id_expected.yml"
gradle="$root/android/app/build.gradle.kts"
fastlane="$root/android/fastlane/metadata/android/en-US"
failures=0

pass() { printf '  ok    %s\n' "$1"; }
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

echo "app id"
namespace=$(sed -n 's/^ *namespace = "\(.*\)"/\1/p' "$gradle" | head -1)
application_id=$(sed -n 's/^ *applicationId = "\(.*\)"/\1/p' "$gradle" | head -1)
[ "$application_id" = "$id_expected" ] &&
    pass "applicationId matches the recipe name ($application_id)" ||
    fail "applicationId '$application_id' != recipe '$id_expected'"
[ "$namespace" = "$application_id" ] &&
    pass "namespace matches applicationId" ||
    fail "namespace '$namespace' != applicationId '$application_id'"

echo "version parity (build.gradle.kts vs recipe)"
name=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$gradle" | head -1)
code=$(sed -n 's/^ *versionCode = \(.*\)/\1/p' "$gradle" | head -1)
build_name=$(sed -n '/^Builds:/,/^[A-Z]/s/^ *versionName: \(.*\)/\1/p' "$yml" | head -1)
build_code=$(sed -n '/^Builds:/,/^[A-Z]/s/^ *versionCode: \(.*\)/\1/p' "$yml" | head -1)
current_name=$(sed -n 's/^CurrentVersion: \(.*\)/\1/p' "$yml" | head -1)
current_code=$(sed -n 's/^CurrentVersionCode: \(.*\)/\1/p' "$yml" | head -1)
[ -n "$name" ] && [ "$build_name" = "$name" ] &&
    pass "Builds.versionName == $name" ||
    fail "Builds.versionName '$build_name' != build.gradle.kts '$name'"
[ -n "$code" ] && [ "$build_code" = "$code" ] &&
    pass "Builds.versionCode == $code" ||
    fail "Builds.versionCode '$build_code' != build.gradle.kts '$code'"
[ "$current_name" = "$name" ] &&
    pass "CurrentVersion == $name" ||
    fail "CurrentVersion '$current_name' != '$name'"
[ "$current_code" = "$code" ] &&
    pass "CurrentVersionCode == $code" ||
    fail "CurrentVersionCode '$current_code' != '$code'"

echo "release tag"
commit_ref=$(sed -n '/^Builds:/,/^[A-Z]/s/^ *commit: \(.*\)/\1/p' "$yml" | head -1)
case "$commit_ref" in
    android-v*) ;;
    *) fail "Builds.commit '$commit_ref' is not an android-v* tag" ;;
esac
if git -C "$root" rev-parse --git-dir >/dev/null 2>&1; then
    if [ -n "$(git -C "$root" tag -l "$commit_ref")" ]; then
        pass "tag $commit_ref exists"
    else
        fail "tag $commit_ref does not exist (git tag $commit_ref)"
    fi
else
    skip "not a git checkout; cannot verify tag $commit_ref"
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
    echo "all metadata checks passed"
else
    echo "$failures check(s) failed"
fi
exit $((failures > 0))
