#!/usr/bin/env bash
# The engine's PR mechanics (upstream/engine/pr.sh) against a local remote with gh faked (#2036; the
# rules are update-kernel.yml's, #1355): a prepared bump opens the one PR on upstream/<item>; a newer
# one marks the PR pending, pushes, then names the version, so a failure at any point leaves a PR
# claiming nothing; a branch that already carries the version is left alone, or finished if a refresh
# left it pending; a declined version is skipped; a commit someone else pushed is never pushed over;
# a fork's branch of the same name is ignored; an open PR of a newer version stays; and an item main
# has caught up with loses its PR. The fake gh applies each --jq filter to fixture JSON, so the
# filters are tested too. Needs bash, git, jq.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"; trap '[ -n "${KEEP:-}" ] || rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
export REPO=Sanne/incus-spawn APP_LOGIN="isx-upstream[bot]" APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com" GH_TOKEN=x
BRANCH=upstream/kernel

mkdir -p "$WORK/bin"
cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
echo "gh $(printf "%s" "$*" | tr "\n" " ")" >> "$CALLS"
[ "$1" = pr ] && [ -f "$FIXTURES/fail-$2" ] && { echo "gh: pr $2 failed (HTTP 502)" >&2; exit 1; }
case "$1 $2" in
    "auth setup-git") ;;
    "pr view") cat "$FIXTURES/body" ;;
    "api repos/"*/compare/*)
        filter=""; while [ $# -gt 0 ]; do [ "$1" = --jq ] && filter=$2; shift; done
        git --git-dir="$REMOTE" log --format='%ae %P' main..upstream/kernel \
            | jq -R 'split(" ") | {commit: {author: {email: .[0]}}, parents: (.[1:] | map({sha: .}))}' \
            | jq -s --arg base "$(git --git-dir="$REMOTE" merge-base main upstream/kernel)" '{merge_base_commit: {sha: $base}, commits: .}' \
            | jq -r "$filter" ;;
    "pr list")
        state=""; filter=""
        while [ $# -gt 0 ]; do case "$1" in --state) state=$2; shift ;; --jq) filter=$2; shift ;; esac; shift; done
        jq -r "$filter" "$FIXTURES/$state.json" ;;
    "pr edit"|"pr create"|"pr comment"|"pr close") ;;
    *) echo "gh: unexpected $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/gh"
OURS='"headRepositoryOwner": {"login": "Sanne"}, "isCrossRepository": false'
FORK='"headRepositoryOwner": {"login": "someone"}, "isCrossRepository": true'
OPEN7="[{\"number\": 7, $OURS}]"
marker() { printf 'Bumps the kernel.\n\n<!-- upstream-watch: kernel %s -->\n' "$1"; }
declined() { printf '[{%s, "body": "<!-- upstream-watch: kernel %s -->", "mergedAt": %s}]' "$OURS" "$1" "$2"; }

# setup <name> <current> <new> <open.json> <closed.json> [<version on the branch> [<its author email>]]:
# a clone of main pinning <current> with a prepared commit for <new>, its remote, and the plan dir.
# Env: BODY (the open PR's body; default: the marker of the branch's version), FAIL (a `gh pr`
# verb that fails), ACTION (plan.tsv's action, default pr).
setup() {
    local dir="$WORK/$1" sha
    mkdir -p "$dir/fixtures" "$dir/src/upstream" "$dir/plan"
    printf '%s' "$4" > "$dir/fixtures/open.json"; printf '%s' "$5" > "$dir/fixtures/closed.json"
    if [ -n "${BODY:-}" ]; then printf '%s\n' "$BODY" > "$dir/fixtures/body"; else marker "${6:-x}" > "$dir/fixtures/body"; fi
    [ -z "${FAIL:-}" ] || touch "$dir/fixtures/fail-$FAIL"
    cp -r "$SRC/upstream/engine" "$dir/src/upstream/engine"
    # publish loads each item's source type, for the order its versions compare in (SOURCE: the
    # item's source type, default kernel-org; github-release orders base-image tags, #1994)
    printf 'items:\n  kernel:\n    source:\n      type: %s\n' "${SOURCE:-kernel-org}" > "$dir/src/upstream/manifest.yaml"
    mkdir -p "$dir/src/.github/scripts"; cp "$SRC/.github/scripts/base-image-sums.sh" "$dir/src/.github/scripts/"
    echo "$2" > "$dir/src/pin.txt"
    cd "$dir/src"
    git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm init
    git init -q --bare "$dir/remote.git" && git remote add origin "$dir/remote.git" && git push -q origin main
    git --git-dir="$dir/remote.git" config uploadpack.allowAnySHA1InWant true
    if [ -n "${6:-}" ]; then
        git switch -q -c "$BRANCH"; echo "$6" > pin.txt
        git -c user.name=t -c user.email="${7:-$APP_EMAIL}" commit -q --allow-empty -am "Update the kernel to $6"
        git push -q origin "$BRANCH"; git switch -q main
    fi
    # The prepared commit, as plan leaves it: a detached commit on main's tip.
    git switch -q --detach main; echo "$3" > pin.txt
    git -c user.name="$APP_LOGIN" -c user.email="$APP_EMAIL" commit -q --allow-empty -am "Update the kernel to $3"
    sha="$(git rev-parse HEAD)"; git switch -q main
    # As plan hands it to the publish job: a bundle of the prepared ref.
    git update-ref refs/upstream-prepared/kernel "$sha"
    git bundle create -q "$dir/plan/kernel.bundle" refs/upstream-prepared/kernel
    git update-ref -d refs/upstream-prepared/kernel
    printf 'kernel\t%s\t%s\tprepared\n' "${ACTION:-pr}" "$3" > "$dir/plan/plan.tsv"
    jq -n --arg v "$3" --arg c "$2" --arg sha "$sha" --arg body "$(marker "$3")" --arg m "<!-- upstream-watch: kernel $3 -->" \
        '{item: "kernel", version: $v, current: $c, title: ("Update the kernel to " + $v), body: $body, marker: $m, commit: $sha, files: "pin.txt"}' > "$dir/plan/kernel.json"
    cd "$WORK"
}
publish() { # <name>: runs publish in <name>'s clone; exit status to <name>/exit
    local dir="$WORK/$1" rc=0
    (cd "$dir/src" && PATH="$WORK/bin:$PATH" CALLS="$dir/calls" FIXTURES="$dir/fixtures" REMOTE="$dir/remote.git" \
        upstream/engine/upstream.sh publish "$dir/plan") > "$dir/out" 2>&1 || rc=$?
    echo "$rc" > "$dir/exit"; touch "$dir/calls"
}
run() { setup "$@"; publish "$1"; [ "$(cat "$WORK/$1/exit")" = 0 ] || { cat "$WORK/$1/out" >&2; fail "$1: publish failed"; }; }
run_failing() {
    local before; setup "$@"; before=$(branch_sha "$1"); publish "$1"
    [ "$(cat "$WORK/$1/exit")" != 0 ] || { cat "$WORK/$1/out" >&2; fail "$1: publish succeeded"; }
    [ "$(branch_sha "$1")" = "$before" ] || fail "$1: the branch was pushed by a run that failed"
}
calls() { grep -c -- "$2" "$WORK/$1/calls" || true; }
on_branch() { git -C "$WORK/$1/remote.git" show "$BRANCH:pin.txt" 2>/dev/null || true; }
branch_sha() { git -C "$WORK/$1/remote.git" rev-parse -q --verify "refs/heads/$BRANCH" || true; }

# A new release, no branch: one PR is opened on the pushed branch.
run new 7.2.9 7.2.10 '[]' '[]'
[ "$(on_branch new)" = 7.2.10 ] || fail "new: the branch does not carry 7.2.10"
[ "$(calls new 'gh pr create')" = 1 ] || fail "new: expected one pr create"
grep -q -- "--head $BRANCH" "$WORK/new/calls" || fail "new: the PR is not on $BRANCH"
[ "$(calls new 'gh pr edit')" = 0 ] || fail "new: nothing to edit"

# A newer release than the open PR's: pending, push, then the comment and the new title and body.
run refresh 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10
[ "$(on_branch refresh)" = 7.2.11 ] || fail "refresh: the branch does not carry 7.2.11"
grep -n 'pending\|pr comment\|^gh pr edit 7 -R Sanne/incus-spawn --title' "$WORK/refresh/calls" | cut -d: -f1 | tr '\n' ' ' > "$WORK/order"
[ "$(calls refresh 'gh pr edit 7 .*pending -->')" = 1 ] || fail "refresh: no pending edit"
[ "$(calls refresh 'gh pr comment 7')" = 1 ] || fail "refresh: no rebuilt comment"
[ "$(calls refresh 'gh pr create')" = 0 ] || fail "refresh: a second PR was opened"
first_pending="$(grep -n 'gh pr edit 7 .*pending -->' "$WORK/refresh/calls" | head -1 | cut -d: -f1)"
comment="$(grep -n 'gh pr comment 7' "$WORK/refresh/calls" | cut -d: -f1)"
[ "$first_pending" -lt "$comment" ] || fail "refresh: the comment came before the pending edit"
grep -q 'upstream-watch: kernel 7.2.11 -->' "$WORK/refresh/calls" || fail "refresh: the final body has no marker"

# The branch already carries the release: nothing is pushed or edited.
run carries 7.2.9 7.2.10 "$OPEN7" '[]' 7.2.10
before="$(branch_sha carries)"
[ "$(calls carries 'gh pr edit')" = 0 ] && [ "$(calls carries 'gh pr comment')" = 0 ] || fail "carries: the PR was touched"
grep -q 'already proposes' "$WORK/carries/out" || fail "carries: not reported as already proposing"

# A refresh left pending is finished without a push.
BODY="Being rebuilt as 7.2.10.

<!-- upstream-watch: kernel 7.2.10, pending -->" run pending 7.2.9 7.2.10 "$OPEN7" '[]' 7.2.10
[ "$(calls pending 'gh pr comment 7')" = 1 ] && [ "$(calls pending 'gh pr edit 7')" = 1 ] || fail "pending: the refresh was not finished"
grep -q 'finished its refresh' "$WORK/pending/out" || fail "pending: not reported as finished"

# A declined release (its PR closed unmerged, pending or not) is not proposed again; a merged one is no reason to stop.
run declined 7.2.9 7.2.10 '[]' "$(declined 7.2.10 null)"
[ -z "$(branch_sha declined)" ] && [ "$(calls declined 'gh pr create')" = 0 ] || fail "declined: a declined release was proposed"
run declined-pending 7.2.9 7.2.10 '[]' "$(declined '7.2.10, pending' null)"
[ -z "$(branch_sha declined-pending)" ] || fail "declined-pending: a declined release was proposed"
run merged 7.2.9 7.2.10 '[]' "$(declined 7.2.10 '"2026-01-01T00:00:00Z"')"
[ "$(on_branch merged)" = 7.2.10 ] || fail "merged: a merged PR of another run stopped a new bump"

# A commit someone else pushed to the branch is never pushed over or closed.
run_failing foreign 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10 someone@example.org
[ "$(on_branch foreign)" = 7.2.10 ] || fail "foreign: the branch was pushed over"
grep -q 'left alone' "$WORK/foreign/out" || fail "foreign: no keep-out message"
[ "$(calls foreign 'gh pr edit')" = 0 ] || fail "foreign: the PR was marked pending before the refusal"

# A fork's branch of the same name is someone else's PR: ours is opened anew.
run fork 7.2.9 7.2.10 "[{\"number\": 9, $FORK}]" '[]'
[ "$(calls fork 'gh pr create')" = 1 ] || fail "fork: no PR opened beside the fork's"
[ "$(calls fork 'gh pr edit')" = 0 ] || fail "fork: the fork's PR was edited"

# An open PR of a newer release than the publisher lists now is left as it is.
run newer 7.2.9 7.2.10 "$OPEN7" '[]' 7.2.11
[ "$(on_branch newer)" = 7.2.11 ] || fail "newer: the branch was pushed back"
[ "$(calls newer 'gh pr edit')" = 0 ] || fail "newer: the PR was edited"

# A failing edit stops the run before the push: the branch is as it was.
FAIL=edit run_failing edit-fails 7.2.9 7.2.11 "$OPEN7" '[]' 7.2.10
[ "$(on_branch edit-fails)" = 7.2.10 ] || fail "edit-fails: pushed although the pending edit failed"

# main caught up: the open PR of a release no newer than main's pin is closed, its marker stripped first.
ACTION=none run stale 7.2.10 7.2.10 "$OPEN7" '[]' 7.2.10
[ "$(calls stale 'gh pr close 7')" = 1 ] || fail "stale: the PR was not closed"
grep -q -- '--delete-branch' "$WORK/stale/calls" || fail "stale: the branch was not deleted"
edit_line="$(grep 'gh pr edit 7' "$WORK/stale/calls")"
[[ "$edit_line" != *"upstream-watch: kernel"* ]] || fail "stale: the marker was not stripped before closing"
ACTION=none run ahead 7.2.10 7.2.10 "$OPEN7" '[]' 7.2.11
[ "$(calls ahead 'gh pr close')" = 0 ] || fail "ahead: a PR ahead of main was closed"
# By the item's own order (#1994): a PR for fedora-45-20261111 is ahead of a fedora-45-v1 pin, which
# sort -V denies, so it stays open.
SOURCE=github-release ACTION=none run ahead-dated fedora-45-v1 fedora-45-v1 "$OPEN7" '[]' fedora-45-20261111
[ "$(calls ahead-dated 'gh pr close')" = 0 ] || fail "ahead-dated: a dated PR ahead of an explicit-tag pin was closed"
# A stale branch carrying someone's commit is neither closed nor deleted: the run fails instead.
ACTION=none run_failing stale-foreign 7.2.10 7.2.10 "$OPEN7" '[]' 7.2.10 someone@example.org
[ "$(calls stale-foreign 'gh pr close')" = 0 ] && [ "$(calls stale-foreign 'gh pr edit')" = 0 ] || fail "stale-foreign: the PR was touched"
[ "$(on_branch stale-foreign)" = 7.2.10 ] || fail "stale-foreign: the branch is gone"
grep -q 'not closed as stale' "$WORK/stale-foreign/out" || fail "stale-foreign: no keep-out message"
echo "test-pr: ok"
