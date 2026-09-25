#!/bin/bash
# Generate config.yaml fixtures with a released isx's own serializer.
#
# PreviousReleaseConfigCompatTest and the CI step test-previous-release-config.sh load
# these files to pin that what a released isx wrote keeps working. They must be what that
# release actually wrote -- empty strings, stray keys and all -- so they are produced by
# the release's own SpawnConfig.save(), never written by hand.
#
# Usage:  scripts/config-compat/generate-fixtures.sh v0.3.8
# Output: common/src/test/resources/config-compat/<tag>-*.yaml
#
# Add a fixture set for each release whose config format differs from the last one, then
# list its files in PreviousReleaseConfigCompatTest.

set -euo pipefail

TAG="${1:?usage: $0 <release-tag>}"
REPO="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
OUT="$REPO/common/src/test/resources/config-compat"
WORK="$(mktemp -d)"
trap 'git -C "$REPO" worktree remove --force "$WORK/src" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

git -C "$REPO" worktree add --detach "$WORK/src" "$TAG" >/dev/null
(cd "$WORK/src" && mvn -q -pl common -am package -DskipTests \
    && mvn -q -pl common dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt")

CP="$(ls "$WORK"/src/common/target/incus-spawn-common-*.jar | grep -v sources | head -1):$(cat "$WORK/cp.txt")"
mkdir -p "$WORK/classes" "$OUT"
javac -d "$WORK/classes" -cp "$CP" "$REPO/scripts/config-compat/GenerateConfigFixtures.java"
java -cp "$WORK/classes:$CP" GenerateConfigFixtures "$OUT" "$TAG"

ls -1 "$OUT"/"$TAG"-*.yaml
