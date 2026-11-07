#!/bin/bash
set -euo pipefail

# Boot a PR's appliance on the Mac and record it for the kernel-mac-verified
# check (#1355, .claude/rules/ci.md). Runs on a bare-metal Apple silicon Mac,
# the only place vfkit runs (#791), from a clean checkout of origin/main: the
# test scripts are this checkout's, and only the PR's build is under test.
#
#   0. refuses before anything else unless this checkout is origin/main with no
#      local change, gh is logged in as one of the check's VERIFIERS, and the
#      PR's author and head repository are both Sanne or the bot (#1385): an
#      external PR's image is built by that PR's own workflow, and the boot
#      shares this user's home, gh token included, with the guest. There is
#      no override; Sanne re-pushes an external kernel change as her own branch
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
#      build, the fingerprint, the artifact's sums, the machine, each result,
#      and for a failed step the checks it failed and its log's tail) and sets
#      the status isx/mac-kernel-boot on the head, success only if every step
#      passed, with the fingerprint as its description. A failed step's full
#      logs are kept on the Mac (#2033): in its work directory, which is left
#      without the disk images, and in ~/.cache/incus-spawn/kernel-mac/pr-<pr>/
#      (replaced by the next failed run of that PR). The record names neither
#      this machine, its user nor its home: they are scrubbed from the logs
#   5. re-runs the PR's kernel-mac-verified check, which reads that status
#
# Usage:  ./test-kernel-on-mac.sh <pr-number>
#
# Needs gh logged in as an account kernel-mac-verified.yml lists in VERIFIERS,
# strings (the Xcode command line tools), and what test-boot.sh and
# test-with-isx.sh need (vfkit, isx on PATH or ISX=...). Stops any appliance VM
# isx runs.

REPO=Sanne/incus-spawn
CONTEXT=isx/mac-kernel-boot
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# Whose PRs the Mac boots: their images are built from branches only they push (#1385). Apart
# from VERIFIERS, which says whose boots the check takes and may come to name a Mac-only login.
TRUSTED="Sanne Sanne-ai-bot"
PR="${1:?Usage: $0 <pr-number>}"
# A number: it names the record's PR and the directory a failed run's logs are kept in.
[[ "$PR" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: $PR is not a PR number" >&2; exit 1; }
ISX="${ISX:-isx}"

die() { echo "ERROR: $*" >&2; exit 1; }
listed() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }

[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] \
    || die "this records a vfkit boot, so it runs only on an Apple silicon Mac"

# Everything below runs with the verifier's credentials, so it must be main's code: a PR branch
# checked out here would run its own scripts and fingerprint with its own input lists.
git -C "$ROOT" fetch -q --no-tags origin +refs/heads/main:refs/remotes/origin/main \
    || die "cannot fetch origin's main"
[ "$(git -C "$ROOT" rev-parse HEAD)" = "$(git -C "$ROOT" rev-parse refs/remotes/origin/main)" ] \
    || die "$ROOT is not at origin/main; run this from a checkout of origin/main (git checkout --detach origin/main)"
[ -z "$(git -C "$ROOT" status --porcelain --untracked-files=all)" ] \
    || die "$ROOT has local changes; run this from a clean checkout of origin/main"

# A missing tool or the wrong login would only be found after the boot, and post for nothing or
# post a failure that withdraws an earlier success.
command -v strings > /dev/null || die "strings is missing; install the Xcode command line tools (xcode-select --install)"
verifiers="$(sed -n 's/^ *VERIFIERS: *//p' "$ROOT/.github/workflows/kernel-mac-verified.yml")"
[ -n "$verifiers" ] || die "kernel-mac-verified.yml lists no VERIFIERS"
login="$(gh api user --jq .login)"
listed "$login" "$verifiers" \
    || die "gh is logged in as $login, and kernel-mac-verified.yml takes a boot only from $verifiers"

read -r head author owner <<< "$(gh pr view "$PR" -R "$REPO" --json headRefOid,author,headRepositoryOwner \
    --jq '"\(.headRefOid) \(.author.login) \(.headRepositoryOwner.login // "-")"')"
# The PR's own build-appliance.yml built its image, so an external PR's image is its author's,
# and the boot shares this user's home, gh token included, with the guest.
if ! listed "$author" "$TRUSTED" || ! listed "$owner" "$TRUSTED"; then
    die "PR #$PR is by $author from $owner's repository: the Mac boots only PRs by and from $TRUSTED. Sanne re-pushes an external kernel change as her own branch to have it booted."
fi
echo "==> PR #$PR at $head"

WORK="$(mktemp -d)"
# A failed boot keeps its logs (#2033); everything else, a pass included, leaves nothing.
keep=0
trap '[ "$keep" = 1 ] || rm -rf "$WORK"' EXIT

# --event pull_request: a push or dispatch run of the same head builds the head, not the merge.
run="$(gh run list -R "$REPO" --workflow build-appliance.yml --commit "$head" --event pull_request --status success \
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

# The PR's merge as GitHub has it now, read as data: nothing of the PR runs here. Not --depth:
# that would turn this full clone shallow.
git -C "$SCRIPT_DIR" fetch -q --no-tags origin "+refs/pull/$PR/merge:refs/remotes/pr/merge" \
    || die "PR #$PR has no merge; it may conflict"
merge="$(git -C "$SCRIPT_DIR" rev-parse refs/remotes/pr/merge)"
[ "$(git -C "$SCRIPT_DIR" rev-parse refs/remotes/pr/merge^2)" = "$head" ] || die "GitHub has not merged $head yet; try again shortly"
[ "$built" = "$merge" ] || die "run $run built $built, but the PR now merges as $merge (its base moved); push to the PR to build that"
fingerprint="$(cd "$ROOT" && appliance/kernel/kernel-fingerprint.sh "$merge")"
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
        # The lines the boot scripts print for a failed check, which carry its counts, and their
        # tally. A tail alone does not name them (#2033).
        # Not from the guest's console that test-boot.sh quotes after its summary, whose lines are
        # the guest's own. Lines are cut short so the record stays under GitHub's comment limit.
        checks="$(perl -ne 'last if /^Last 40 log lines:$/; s/\e\[[0-9;]*m//g; print' "$WORK/$name.log" \
            | grep -E '^[[:space:]]*(FAIL|FAILED|ERROR):|checks failed\.$' | head -n 40 | cut -c 1-300 || true)"
        logs+=$'\n'"$name, failing checks:"$'\n\n```\n'"${checks:-none named; see the log}"$'\n```\n'
        logs+=$'\n'"<details><summary>$name, last 40 lines</summary>"$'\n\n```\n'"$(tail -n 40 "$WORK/$name.log" | cut -c 1-300)"$'\n```\n</details>\n'
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
# Marks the boots' start, so only a vm.log they wrote is kept with their logs.
: > "$WORK/started"
step test-boot.sh env SERIAL_LOG="$WORK/serial.log" "$SCRIPT_DIR/test-boot.sh" "$WORK/boot"
step test-with-isx.sh env APPLIANCE_DIR="$WORK/build" ISX="$ISX" "$SCRIPT_DIR/test-with-isx.sh"
"$ISX" vm stop > /dev/null 2>&1 || true

# The record is public: what it takes from the logs must not name this machine, its user or its
# home. Longest first, so a home inside the work directory's path is not split.
scrub() {
    local host; host="$(uname -n)"
    W="$WORK" WP="$(cd "$WORK" && pwd -P)" H="$HOME" N="$host" S="${host%%.*}" U="$(id -un)" perl -pe '
        for my $k (qw(WP W H)) { my $v = $ENV{$k}; s/\Q$v\E(?![\w.-])/$k eq "H" ? "~" : "<work>"/ge if length $v > 1 }
        for my $k (qw(N S)) { my $v = $ENV{$k}; s/\b\Q$v\E\b/<host>/gi if length $v }
        my $u = $ENV{U}; s/\b\Q$u\E\b/<user>/g if length $u;'
}

# What decides a failure is in the full logs, so they outlive this run. Of the artifact only
# kernel-inputs: its images and kernel are tens of MB, and an unattended Mac would fill up with
# them. Saving is best effort, since the record must still be posted. The paths are isx's own
# (Environment.cacheDir, vmLogFile), which do not follow XDG_CACHE_HOME or XDG_STATE_HOME.
if [ "$failed" = 1 ]; then
    keep=1
    rm -rf "${WORK:?}/boot" || true
    find "${WORK:?}/build" -mindepth 1 -maxdepth 1 ! -name kernel-inputs -exec rm -rf {} + || true
    echo "==> Kept the logs of this run in $WORK"
    saved="$HOME/.cache/incus-spawn/kernel-mac/pr-$PR"
    if rm -rf "$saved" && mkdir -p "$saved" && cp "$WORK"/*.log "$saved/"; then
        state_log="$HOME/.local/state/incus-spawn/vm.log"
        [ ! "$state_log" -nt "$WORK/started" ] || cp "$state_log" "$saved/vm.log" || true
        echo "==> Saved them in $saved"
    else
        saved=""
    fi
    logs="$(printf '%s' "$logs" | scrub)"
    # Only the path is scrubbed: a short host name such as "mac" would take this line's own words.
    [ -z "$saved" ] || logs=$'\n'"Logs: kept on the Mac in $(printf '%s' "$saved" | scrub)"$'\n'"$logs"
fi

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
    echo "    A re-run repeats the workflow as it was when that run started. If main has changed"
    echo "    kernel-mac-verified.yml or the kernel's input lists since, it stays red until a push to the PR."
else
    echo "==> No kernel-mac-verified run of $now to re-run; push or re-open the PR to start one."
fi
[ "$failed" = 0 ]
