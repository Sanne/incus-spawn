#!/usr/bin/env bash
# Writes the SHA256SUMS a release publishes for its assets (#2080), which get-isx.sh checks every
# download against.
#
#   release-sha256sums.sh <out> <asset>...
#
# A binary's sum is the one the job that built it recorded: an asset whose directory holds a
# SHA256SUMS (release.yml's build jobs write one into each artifact they upload) must be listed there
# and still match it, so an artifact that changed between the build and the release stops the run
# instead of being published under a sum of its own. An asset in a directory with no SHA256SUMS
# (made by the release job itself, or by a job that records none) is summed here.
# Every asset is published under its file name, so two with the same name stop the run too.
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }

[ $# -ge 2 ] || die "usage: $0 <out> <asset>..."
out=$1
shift

# Indexed arrays only, and none expanded while empty: GetIsxScriptTest runs this on macOS's bash 3.2
names=()
paths=()
lines=()
for asset in "$@"; do
  [ -f "$asset" ] || die "$asset: no such file"
  name=$(basename -- "$asset")
  for ((i = 0; i < ${#names[@]}; i++)); do
    [ "${names[i]}" != "$name" ] || die "$name: named twice (${paths[i]} and $asset)"
  done
  names+=("$name")
  paths+=("$asset")
  actual=$(sha256sum -- "$asset" | cut -d' ' -f1)
  built="$(dirname -- "$asset")/SHA256SUMS"
  if [ -f "$built" ]; then
    expected=$(awk -v n="$name" '$2 == n || $2 == "*" n { print $1 }' "$built")
    [ -n "$expected" ] || die "$asset: its build's $built does not list it"
    [ "$(printf '%s\n' "$expected" | wc -l)" -eq 1 ] || die "$asset: its build's $built lists it more than once"
    [ "$actual" = "$expected" ] \
      || die "$asset: sha256 $actual, but the job that built it recorded $expected in $built"
  fi
  lines+=("$actual  $name")
done

printf '%s\n' "${lines[@]}" > "$out"
