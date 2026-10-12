#!/bin/bash
set -euo pipefail

# Boot a PR's appliance on the Mac and record it for the kernel-mac-verified
# check (#1355, .claude/rules/ci.md). Runs on a bare-metal Apple silicon Mac,
# the only place vfkit runs (#791), from a checkout of main: the test scripts
# are this checkout's, and only the PR's build is under test.
#
#   1. takes the newest successful build-appliance.yml run of the PR's head and
#      its appliance-aarch64 artifact, whose kernel-inputs names the commit it
#      built (on a PR, the merge), and refuses it unless that is the PR's
#      merge now: an older merge's build, from before its base moved, is not
#      what would be merged
#   2. fingerprints that merge with this checkout's kernel-fingerprint.sh, as
#      the check does, and checks the kernel's Linux version banner against
#      the merge's KERNEL_VERSION
#   3. boots it with test-boot.sh (vfkit, ISX READY and the tunnel checks), then
#      with test-with-isx.sh (isx's own VM manager, over vsock)
#   4. posts a "### Mac kernel verification" record on the PR (the head, the
#      build, the fingerprint, the artifact's sums, the machine, each result)
#      and sets the status isx/mac-kernel-boot on the head, success only if
#      every step passed, with the fingerprint as its description
#   5. re-runs the PR's kernel-mac-verified check, which reads that status
#
# Usage:  ./test-kernel-on-mac.sh <pr-number>
#
# Needs gh logged in as an account kernel-mac-verified.yml lists in VERIFIERS,
# and what test-boot.sh and test-with-isx.sh need (vfkit, isx on PATH or
# ISX=...). Stops any appliance VM isx runs.

REPO=Sanne/incus-spawn
CONTEXT=isx/mac-kernel-boot
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PR="${1:?Usage: $0 <pr-number>}"
ISX="${ISX:-isx}"

die() { echo "ERROR: $*" >&2; exit 1; }

[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] \
    || die "this records a vfkit boot, so it runs only on an Apple silicon Mac"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

head="$(gh pr view "$PR" -R "$REPO" --json headRefOid --jq .headRefOid)"
echo "==> PR #$PR at $head"
run="$(gh run list -R "$REPO" --workflow build-appliance.yml --commit "$head" --status success \
    --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
[ -n "$run" ] || die "no successful build-appliance.yml run of $head yet"
run_url="https://github.com/$REPO/actions/runs/$run"
mkdir "$WORK/build"
gh run download "$run" -R "$REPO" -n appliance-aarch64 -D "$WORK/build"
for f in vmlinuz disk.img.gz kernel-inputs; do
    [ -f "$WORK/build/$f" ] || die "the appliance-aarch64 artifact of run $run has no $f"
done
built="$(sed -n 's/^commit \([0-9a-f]\{40\}\)$/\1/p' "$WORK/build/kernel-inputs")"
[ -n "$built" ] || die "kernel-inputs of run $run names no commit"

# The PR's merge as GitHub has it now, read as data: nothing of the PR runs here.
git -C "$SCRIPT_DIR" fetch -q --no-tags --depth 2 origin "+refs/pull/$PR/merge:refs/remotes/pr/merge" \
    || die "PR #$PR has no merge; it may conflict"
merge="$(git -C "$SCRIPT_DIR" rev-parse refs/remotes/pr/merge)"
[ "$(git -C "$SCRIPT_DIR" rev-parse refs/remotes/pr/merge^2)" = "$head" ] || die "GitHub has not merged $head yet; try again shortly"
[ "$built" = "$merge" ] || die "run $run built $built, but the PR now merges as $merge (its base moved); push to the PR to build that"
fingerprint="$(cd "$SCRIPT_DIR/.." && appliance/kernel/kernel-fingerprint.sh "$merge")"
pinned="$(git -C "$SCRIPT_DIR" show "$merge:appliance/kernel/build-kernel.sh" | sed -n 's/^KERNEL_VERSION="\(.*\)"$/\1/p')"
# A series' first release is pinned as X.Y and reports X.Y.0.
expected="$pinned"
[ "${pinned//[^.]/}" = . ] && expected="$pinned.0"

# arm64's Image is not compressed, so the banner uname -r reports is readable in it.
banner="$(LC_ALL=C strings "$WORK/build/vmlinuz" | grep -m1 '^Linux version ' || true)"
release="$(echo "$banner" | cut -d' ' -f3)"

results=()
failed=0
logs=""
step() {
    local name="$1"; shift
    echo "==> $name"
    if "$@" > "$WORK/$name.log" 2>&1; then
        results+=("$name: pass")
    else
        results+=("$name: FAIL (exit $?)")
        failed=1
        logs+=$'\n'"<details><summary>$name, last 40 lines</summary>"$'\n\n```\n'"$(tail -n 40 "$WORK/$name.log")"$'\n```\n</details>\n'
    fi
}

if [ -n "$release" ] && [ "$release" = "$expected" ]; then
    results+=("kernel release: $release, the KERNEL_VERSION it was built from")
else
    results+=("kernel release: FAIL (${release:-no banner found}, but it was built from KERNEL_VERSION $pinned)")
    failed=1
fi

sum() { shasum -a 256 "$WORK/build/$1" | cut -d' ' -f1; }
vmlinuz_sum="$(sum vmlinuz)"
disk_sum="$(sum disk.img.gz)"

# test-boot.sh boots disk.img and writes to it; test-with-isx.sh extracts disk.img.gz itself.
mkdir "$WORK/boot"
cp "$WORK/build/vmlinuz" "$WORK/boot/"
gunzip -c "$WORK/build/disk.img.gz" > "$WORK/boot/disk.img"
step test-boot.sh "$SCRIPT_DIR/test-boot.sh" "$WORK/boot"
step test-with-isx.sh env APPLIANCE_DIR="$WORK/build" ISX="$ISX" "$SCRIPT_DIR/test-with-isx.sh"
"$ISX" vm stop > /dev/null 2>&1 || true

state=success
[ "$failed" = 0 ] || state=failure
body="### Mac kernel verification
Result: $state
Commit: $head
Built: $built (the PR's merge)
Fingerprint: $fingerprint
Kernel: ${banner:-no Linux version banner in vmlinuz}
Artifact: appliance-aarch64 of $run_url
vmlinuz sha256: $vmlinuz_sum
disk.img.gz sha256: $disk_sum
Machine: macOS $(sw_vers -productVersion), $(sysctl -n hw.model), $("$ISX" --version 2>/dev/null | head -1 || echo "isx unknown")
Scripts: $(git -C "$SCRIPT_DIR" rev-parse HEAD) (this checkout)

$(printf -- '- %s\n' "${results[@]}")
$logs"
comment_url="$(gh pr comment "$PR" -R "$REPO" --body "$body")"
gh api -X POST "repos/$REPO/statuses/$head" -f state="$state" -f context="$CONTEXT" \
    -f description="fingerprint $fingerprint" -f target_url="$comment_url" > /dev/null
echo "==> Recorded $state on $head: $comment_url"

# The check of the head the PR has now, which a push during the boot may have moved; the record
# names $head, so the check finds this boot there. A pull_request_target run carries the head as
# its commit.
now="$(gh pr view "$PR" -R "$REPO" --json headRefOid --jq .headRefOid)"
check_run="$(gh run list -R "$REPO" --workflow kernel-mac-verified.yml --commit "$now" --limit 1 \
    --json databaseId --jq '.[0].databaseId // empty')"
if [ -n "$check_run" ]; then
    gh run rerun "$check_run" -R "$REPO" > /dev/null && echo "==> Re-running kernel-mac-verified"
else
    echo "==> No kernel-mac-verified run of $now to re-run; push or re-open the PR to start one."
fi
[ "$failed" = 0 ]
