#!/bin/bash
# Runs update-kernel.yml's "Open or refresh the bump PR" step against a local remote, with gh
# faked: the fake applies each --jq filter to fixture JSON, so the filters are tested too (#1355).
# Checks that a new release opens the one bump PR; that a newer one marks the PR pending, pushes,
# then names the release, so a failure at any point leaves a PR claiming nothing and the next run
# finishes it; that a PR whose branch already carries the release is left alone; that a declined
# release is skipped; that a stale PR is closed, and one ahead of main is not; that a commit
# someone else pushed is never pushed over; and that a fork's branch of the same name is ignored.
# Needs bash, git, jq and mikefarah yq.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
WORKFLOW="$REPO/.github/workflows/update-kernel.yml"
BOT=github-actions[bot]@users.noreply.github.com

STEP="Open or refresh the bump PR" yq -r '.jobs.bump.steps[] | select(.name == strenv(STEP)) | .run' \
    "$WORKFLOW" > "$WORK/step.sh"
[ -s "$WORK/step.sh" ] || { echo "FAIL: no bump PR step in $WORKFLOW" >&2; exit 1; }

mkdir -p "$WORK/bin"
# Records every call. The compare API answers from the remote's history ($REMOTE); pr list reads $FIXTURES/<state>.json through the caller's --jq; pr view
# prints $FIXTURES/body; $FIXTURES/fail-<verb> makes `pr <verb>` fail.
cat > "$WORK/bin/gh" <<'EOF'
#!/bin/bash
echo "gh $(printf "%s" "$*" | tr "\n" " ")" >> "$CALLS"
[ "$1" = pr ] && [ -f "$FIXTURES/fail-$2" ] && { echo "gh: pr $2 failed (HTTP 502)" >&2; exit 1; }
# $FIXTURES/race runs while the PR is marked pending, between the step's read of the branch and its push.
[ "$1 $2" = "pr edit" ] && [ -x "$FIXTURES/race" ] && [[ "$*" == *pending* ]] && "$FIXTURES/race"
case "$1 $2" in
    "pr view") cat "$FIXTURES/body" ;;
    "api repos/"*/compare/*)
        filter=""; while [ $# -gt 0 ]; do [ "$1" = --jq ] && filter=$2; shift; done
        printf '{"merge_base_commit": {"sha": "%s"}}' "$(git --git-dir="$REMOTE" merge-base main bot/kernel-bump)" | jq -r "$filter" ;;
    "pr list")
        state=""; filter=""
        while [ $# -gt 0 ]; do case "$1" in --state) state=$2; shift ;; --jq) filter=$2; shift ;; esac; shift; done
        jq -r "$filter" "$FIXTURES/$state.json" ;;
esac
EOF
chmod +x "$WORK/bin/gh"

fail() { echo "FAIL: $*" >&2; exit 1; }
OURS='"headRepositoryOwner": {"login": "Sanne"}, "isCrossRepository": false'
FORK='"headRepositoryOwner": {"login": "someone"}, "isCrossRepository": true'
OPEN7="[{\"number\": 7, $OURS}]"
marker() { printf 'Bumps the kernel.\n\n<!-- update-kernel.yml: %s -->\n' "$1"; }
declined() { printf '[{%s, "body": "<!-- update-kernel.yml: %s -->", "mergedAt": %s}]' "$OURS" "$1" "$2"; }

# setup <name> <current> <new> <open.json> <closed.json> [<version on bot/kernel-bump> [<main's
# version when that branch was made> [<that commit's author email>]]]: a clone of main, which pins
# <current> and whose build-kernel.sh already carries <new>, as update-kernel-pin.sh bump leaves
# it, and its remote. Runs with errexit, so a fixture that cannot be made fails the test.
# Env: BODY, the open PR's body (default: one naming the branch's release); FAIL, a `gh pr` verb
# that fails; REJECT_PUSH, set to make the remote refuse pushes to bot/kernel-bump.
setup() {
    local dir="$WORK/$1"
    mkdir -p "$dir/fixtures" "$dir/src/appliance/kernel"
    printf '%s' "$4" > "$dir/fixtures/open.json"
    printf '%s' "$5" > "$dir/fixtures/closed.json"
    if [ -n "${BODY:-}" ]; then printf '%s\n' "$BODY" > "$dir/fixtures/body"; else marker "${6:-x}" > "$dir/fixtures/body"; fi
    if [ -n "${FAIL:-}" ]; then touch "$dir/fixtures/fail-$FAIL"; fi
    printf 'KERNEL_VERSION="%s"\n' "${7:-$2}" > "$dir/src/appliance/kernel/build-kernel.sh"
    cd "$dir/src"
    git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm init
    git init -q --bare "$dir/remote.git" && git remote add origin "$dir/remote.git" && git push -q origin main
    # As GitHub does: a commit can be fetched by its id.
    git --git-dir="$dir/remote.git" config uploadpack.allowAnySHA1InWant true
    if [ -n "${6:-}" ]; then
        git switch -q -c bot/kernel-bump
        printf 'KERNEL_VERSION="%s"\n' "$6" > appliance/kernel/build-kernel.sh
        git -c user.name=t -c user.email="${8:-$BOT}" commit -q --allow-empty -am "Update the kernel to $6"
        git push -q origin bot/kernel-bump
        git switch -q main
    fi
    if [ -n "${7:-}" ]; then
        printf 'KERNEL_VERSION="%s"\n' "$2" > appliance/kernel/build-kernel.sh
        git -c user.name=t -c user.email=t@t commit -qam "Bump by hand to $2"
        git push -q origin main
    fi
    if [ -n "$3" ]; then printf 'KERNEL_VERSION="%s"\n' "$3" > appliance/kernel/build-kernel.sh; fi
    if [ -n "${REJECT_PUSH:-}" ]; then
        printf '#!/bin/sh\necho "rejected" >&2\nexit 1\n' > "$dir/remote.git/hooks/pre-receive"
        chmod +x "$dir/remote.git/hooks/pre-receive"
    fi
    cd "$WORK"
}

# step <name> <current> <new>: runs the step in <name>'s clone; its exit status goes to <name>/exit
step() {
    local dir="$WORK/$1" rc=0
    (cd "$dir/src" && PATH="$WORK/bin:$PATH" CALLS="$dir/calls" FIXTURES="$dir/fixtures" REMOTE="$dir/remote.git" GH_TOKEN=x REPO=Sanne/incus-spawn \
        CURRENT="$2" NEW="$3" bash -eo pipefail "$WORK/step.sh") > "$dir/out" 2>&1 || rc=$?
    echo "$rc" > "$dir/exit"
    touch "$dir/calls"
}

# run <setup's arguments>: setup, then the step, which must succeed
run() { setup "$@"; step "$1" "$2" "$3"; ok "$1"; }
# run_failing <setup's arguments>: setup, then the step, which must fail; the branch must be as it was
run_failing() {
    local before
    setup "$@"; before=$(branch_sha "$1"); step "$1" "$2" "$3"
    [ "$(cat "$WORK/$1/exit")" != 0 ] || { cat "$WORK/$1/out" >&2; fail "$1: the step succeeded"; }
    [ "$(branch_sha "$1")" = "$before" ] || fail "$1: the branch was pushed by a run that failed"
}
ok() { [ "$(cat "$WORK/$1/exit")" = 0 ] || { cat "$WORK/$1/out" >&2; fail "$1: the step failed"; }; }
calls() { grep -c -- "$2" "$WORK/$1/calls" || true; }
on_branch() { git -C "$WORK/$1/remote.git" show bot/kernel-bump:appliance/kernel/build-kernel.sh 2>/dev/null | sed -n 's/^KERNEL_VERSION="\(.*\)"$/\1/p'; }
branch_sha() { git -C "$WORK/$1/remote.git" rev-parse -q --verify refs/heads/bot/kernel-bump || true; }

# A new release, nothing open: one commit on main, pushed, and one PR asking for the Mac.
run new 7.2.9 7.2.10 '[]' '[]'
[ "$(on_branch new)" = 7.2.10 ] || fail "new: the pushed branch does not carry 7.2.10"
[ "$(git -C "$WORK/new/remote.git" rev-list --count main..bot/kernel-bump)" = 1 ] || fail "new: not one commit on main"
[ "$(calls new 'pr create .*--title Update the kernel to 7.2.10 .*<!-- update-kernel.yml: 7.2.10 --> --label env:macos')" = 1 ] \
    || fail "new: no PR for 7.2.10 with env:macos: $(cat "$WORK/new/calls")"
[ "$(calls new 'auth setup-git')" = 1 ] || fail "new: pushes do not go through gh's credentials"

# A newer release while 7.2.10's PR is open: the same PR, marked pending, the new commit pushed, and
# only then named for it, labelled env:macos again and told its Mac boot no longer applies.
run newer 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10
[ "$(on_branch newer)" = 7.2.11 ] || fail "newer: bot/kernel-bump does not carry 7.2.11"
edits=$(grep -n 'pr edit 7 ' "$WORK/newer/calls" | cut -d: -f1 | tr '\n' ' ')
[ "$(calls newer 'pr edit 7 --title Update the kernel to 7.2.11 --remove-label env:macos --body Being rebuilt as 7.2.11.*<!-- update-kernel.yml: 7.2.11, pending -->')" = 1 ] \
    && [ "$(calls newer 'pr edit 7 --title Update the kernel to 7.2.11 .*<!-- update-kernel.yml: 7.2.11 --> --add-label env:macos')" = 1 ] \
    && [ "$(echo "$edits" | wc -w)" = 2 ] && grep -n 'pr edit 7 ' "$WORK/newer/calls" | head -1 | grep -q pending \
    || fail "newer: #7 was not marked pending, then named 7.2.11 and labelled env:macos: $(cat "$WORK/newer/calls")"
[ "$(calls newer 'pr comment 7 ')" = 1 ] || fail "newer: #7 was not told its Mac boot no longer applies"
[ "$(calls newer 'pr edit 7 .*--remove-label env:macos .*pending')" = 1 ] || fail "newer: the pending #7 kept its request for a Mac boot"
# The comment before the final edit: the PR stays pending until both have gone through.
[ "$(grep -n 'pr comment 7\|pr edit 7 .*--add-label' "$WORK/newer/calls" | head -1 | grep -c 'pr comment')" = 1 ] \
    || fail "newer: #7 was named for 7.2.11 before it was told its Mac boot no longer applies"
[ "$(calls newer 'pr create')" = 0 ] || fail "newer: a second bump PR was opened"

# The pending mark fails: nothing else happens, and the branch is as it was.
FAIL=edit run_failing edit-fails 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10
[ "$(calls edit-fails 'pr comment')" = 0 ] || fail "edit-fails: the PR was told it was rebuilt"

# The push fails after the pending mark: the PR claims nothing (no marker naming a release its branch
# lacks, no new request for a boot), and the branch is as it was.
REJECT_PUSH=1 run_failing push-fails 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10
[ "$(calls push-fails 'pr edit 7 .*<!-- update-kernel.yml: 7.2.11 -->')" = 0 ] && [ "$(calls push-fails 'add-label\|pr comment')" = 0 ] \
    || fail "push-fails: the PR names 7.2.11 over 7.2.10's commit"

# Someone pushes to the branch while the run is between reading it and pushing: the push is
# refused rather than going over their commit.
setup race 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10
cat > "$WORK/race/fixtures/race" <<EOF
#!/bin/bash
set -e
git clone -q "$WORK/race/remote.git" "$WORK/race/other" && cd "$WORK/race/other" && git switch -q bot/kernel-bump
echo fix > fix && git add fix && git -c user.name=s -c user.email=sanne@example.invalid commit -qm fix && git push -q origin bot/kernel-bump
EOF
chmod +x "$WORK/race/fixtures/race"
step race 7.2.9 7.2.11
[ "$(cat "$WORK/race/exit")" != 0 ] && [ "$(git -C "$WORK/race/remote.git" log -1 --format=%ae bot/kernel-bump)" = sanne@example.invalid ] \
    || fail "race: a commit pushed during the run was pushed over"

# The branch carries the release on the current main, and the PR says so: nothing is touched.
setup same 7.2.9 7.2.10 "$OPEN7" '[]' 7.2.10
before=$(branch_sha same)
step same 7.2.9 7.2.10; ok same
[ "$(calls same 'pr \(create\|edit\|comment\|close\)')" = 0 ] && [ "$(branch_sha same)" = "$before" ] \
    || fail "same: the PR or its branch was changed"

# Sanne updated the branch from main (GitHub's "Update branch": a merge commit of hers on top).
# The branch still carries the release on a base with main's pin, so it stands, and her commit is
# no reason to fail.
setup updated 7.2.9 7.2.10 "$OPEN7" '[]' 7.2.10
(
    cd "$WORK/updated/src"
    git stash -q
    echo "unrelated" > elsewhere && git add elsewhere && git -c user.name=t -c user.email=t@t commit -qm "elsewhere"
    git push -q origin main
    git fetch -q origin bot/kernel-bump && git switch -q -c merged FETCH_HEAD
    git -c user.name=s -c user.email=sanne@example.invalid merge -q --no-edit main
    git push -q origin HEAD:bot/kernel-bump && git switch -q main && git stash pop -q
)
before=$(branch_sha updated)
step updated 7.2.9 7.2.10; ok updated
[ "$(branch_sha updated)" = "$before" ] && [ "$(calls updated 'pr \(edit\|comment\)')" = 0 ] \
    || fail "updated: a branch Sanne updated from main was rebuilt"

# A refresh this job left pending after its push (the last edit failed): it is finished, and the
# commit, with any Mac boot of it, stays.
BODY="Being rebuilt as 7.2.11. <!-- update-kernel.yml: 7.2.11, pending -->" setup half-done 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.11
before=$(branch_sha half-done)
step half-done 7.2.9 7.2.11; ok half-done
[ "$(calls half-done 'pr edit 7 --title Update the kernel to 7.2.11 .*<!-- update-kernel.yml: 7.2.11 --> --add-label env:macos')" = 1 ] \
    || fail "half-done: #7's refresh was not finished"
[ "$(branch_sha half-done)" = "$before" ] || fail "half-done: the commit was replaced"

# A body Sanne edited by hand, marker gone: left as it is, and not labelled again.
BODY="Rewritten by hand" run hand-edited 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.11
[ "$(calls hand-edited 'pr \(edit\|comment\)')" = 0 ] || fail "hand-edited: the hand-edited PR was overwritten"

# Sanne closed the PR for this release unmerged, whatever she titled it; a merged one of another
# release does not count.
run declined 7.2.9 7.2.10 '[]' \
    "[{$OURS, \"body\": \"Retitled by hand\\n\\n<!-- update-kernel.yml: 7.2.10 -->\", \"mergedAt\": null}, {$OURS, \"body\": \"<!-- update-kernel.yml: 7.2.9 -->\", \"mergedAt\": \"2026-10-06T00:00:00Z\"}]"
[ -z "$(branch_sha declined)" ] && [ "$(calls declined 'pr create')" = 0 ] || fail "declined: a declined release was proposed again"

# A PR closed while pending counts as declined too.
run declined-pending 7.2.9 7.2.10 '[]' "[{$OURS, \"body\": \"<!-- update-kernel.yml: 7.2.10, pending -->\", \"mergedAt\": null}]"
[ -z "$(branch_sha declined-pending)" ] || fail "declined-pending: a release declined while pending was proposed again"

# Merged once and since reverted on main: a merged PR is not a declined one.
run reverted 7.2.9 7.2.10 '[]' "$(declined 7.2.10 '"2026-10-13T00:00:00Z"')"
[ "$(calls reverted 'pr create')" = 1 ] || fail "reverted: 7.2.10 was not proposed again"

# An earlier release was declined: this one is still proposed.
run declined-earlier 7.2.9 7.2.11 '[]' "$(declined 7.2.10 null)"
[ "$(calls declined-earlier 'pr create')" = 1 ] || fail "declined-earlier: 7.2.11 was not proposed"

# main moved its pin under the open PR (7.2.9 -> 7.2.10 by hand): the same release, rebuilt on
# main, or the PR would conflict.
run stale 7.2.10 7.2.11 "$OPEN7" '[]' 7.2.11 7.2.9
[ "$(git -C "$WORK/stale/remote.git" rev-list --count main..bot/kernel-bump)" = 1 ] \
    && git -C "$WORK/stale/remote.git" merge-base --is-ancestor main bot/kernel-bump \
    || fail "stale: bot/kernel-bump was not rebuilt on main"
[ "$(calls stale 'pr comment 7 ')" = 1 ] || fail "stale: #7 was not told its Mac boot no longer applies"

# main caught up (bumped by hand): the open bump is closed, after losing its marker so it never
# reads as declined.
run caught-up 7.2.10 '' "$OPEN7" '[]' 7.2.10
[ "$(calls caught-up 'pr close 7 --delete-branch')" = 1 ] || fail "caught-up: the stale #7 was not closed"
grep 'pr edit 7 --body' "$WORK/caught-up/calls" | grep -q 'Bumps the kernel' \
    && ! grep 'pr edit 7 --body' "$WORK/caught-up/calls" | grep -q 'update-kernel.yml:' \
    || fail "caught-up: the stale #7 lost its body, or kept its marker"

# The signed sums list nothing newer than main, but the open PR proposes a newer release (a sums
# file can lag): it stays open, with its Mac boot.
run ahead 7.2.10 '' "$OPEN7" '[]' 7.2.11
[ "$(calls ahead 'pr \(edit\|close\)')" = 0 ] || fail "ahead: a PR for a newer release was closed"

# A branch whose pin cannot be read fails the run, rather than read as no release at all.
setup unreadable 7.2.10 '' "$OPEN7" '[]' 7.2.11
(cd "$WORK/unreadable/src" && git fetch -q origin bot/kernel-bump && git switch -q -c b FETCH_HEAD \
    && sed -i 's/^KERNEL_VERSION=/VERSION=/' appliance/kernel/build-kernel.sh \
    && git -c user.name=t -c user.email="$BOT" commit -qam broken && git push -q origin HEAD:bot/kernel-bump && git switch -q main)
step unreadable 7.2.10 ''
[ "$(cat "$WORK/unreadable/exit")" != 0 ] && [ "$(calls unreadable 'pr \(edit\|close\)')" = 0 ] || fail "unreadable: an unreadable pin did not fail the run"

# The body cannot be read: the run fails before anything is edited, instead of wiping it.
FAIL=view run_failing view-fails 7.2.10 '' "$OPEN7" '[]' 7.2.10
[ "$(calls view-fails 'pr \(edit\|close\)')" = 0 ] || fail "view-fails: the PR was edited or closed blind"

# Someone pushed to the branch: the run stops, visibly, rather than push over their commit, whether
# or not a PR is open on it.
run_failing hand-pushed 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10 '' sanne@example.invalid
grep -q "did not make" "$WORK/hand-pushed/out" || fail "hand-pushed: the reason is not given"
[ "$(git -C "$WORK/hand-pushed/remote.git" log -1 --format=%ae bot/kernel-bump)" = sanne@example.invalid ] \
    && [ "$(calls hand-pushed 'pr \(edit\|create\)')" = 0 ] || fail "hand-pushed: the commit was pushed over"
run_failing hand-pushed-closed 7.2.9 7.2.11 '[]' '[]' 7.2.10 '' sanne@example.invalid
[ "$(git -C "$WORK/hand-pushed-closed/remote.git" log -1 --format=%ae bot/kernel-bump)" = sanne@example.invalid ] \
    || fail "hand-pushed-closed: a branch with no PR open was pushed over"
# Nothing to do: such a branch is no reason to fail.
run hand-pushed-idle 7.2.10 '' '[]' '[]' 7.2.10 '' sanne@example.invalid

# A fork's branch of the same name is neither the open bump nor a declined one.
run fork 7.2.9 7.2.10 "[{\"number\": 8, $FORK}]" "[{$FORK, \"body\": \"<!-- update-kernel.yml: 7.2.10 -->\", \"mergedAt\": null}]"
[ "$(calls fork 'pr create')" = 1 ] && [ "$(calls fork 'pr \(edit\|close\|view\) 8')" = 0 ] || fail "fork: a fork's PR was taken for the bump"

# Current, nothing open: nothing at all.
run current 7.2.10 '' '[]' '[]'
[ "$(calls current 'pr \(create\|edit\|close\)')" = 0 ] || fail "current: the PRs were touched"

echo "update-kernel.yml's bump PR step: all cases pass"
