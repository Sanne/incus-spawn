#!/bin/bash
set -euo pipefail

# Print the fingerprint of the macOS appliance kernel's inputs at a commit: a
# hash of the git blob ids of the files that decide what kernel the appliance
# build produces (#1355). build-appliance.yml records it beside the image it
# builds, a Mac boot of that image is recorded against it, and the
# kernel-mac-verified check accepts the boot for any PR whose merge result has
# the same fingerprint: a rebase that leaves these files alone keeps the boot,
# and any change to them needs another.
#
# Usage:  ./kernel-fingerprint.sh [<commit>]     (default HEAD; run from the repository)
#
# The inputs are build.sh, which builds the kernel in its container, and in
# appliance/kernel/ build-kernel.sh and the appliance fragments. Every other
# file in appliance/kernel/ is listed in NOT_INPUTS; one that is in neither
# list fails, so a new file is classified before it can change the kernel
# unseen. The VM fragments are not inputs: the appliance never reads them.
# Not covered: the compiler, which build.sh takes from the fedora:44 image when
# it runs; the manual pre-release boot on a Mac still sees a change there.

INPUTS='build-kernel.sh isx.config isx-*.config'
NOT_INPUTS='vm.config vm-*.config update-kernel-pin.sh kernel.org-autosigner.asc kernel-fingerprint.sh'

REV="${1:-HEAD}"
DIR=appliance/kernel

matches() {
    local name="$1" pattern
    for pattern in $2; do
        # shellcheck disable=SC2254 # the patterns are globs on purpose
        case "$name" in $pattern) return 0 ;; esac
    done
    return 1
}

build="$(git ls-tree "$REV" -- appliance/build.sh)"
tree="$(git ls-tree "$REV" -- "$DIR/")"
[ -n "$build" ] && [ -n "$tree" ] || { echo "ERROR: no appliance/build.sh or $DIR/ at $REV" >&2; exit 1; }

inputs="$build"$'\n'
while IFS= read -r line; do
    path="${line#*$'\t'}"
    name="${path#"$DIR"/}"
    if matches "$name" "$INPUTS"; then
        inputs+="$line"$'\n'
    elif ! matches "$name" "$NOT_INPUTS"; then
        echo "ERROR: $path is neither a kernel input nor listed as not one in $DIR/kernel-fingerprint.sh" >&2
        exit 1
    fi
done <<< "$tree"

printf '%s' "$inputs" | git hash-object --stdin
