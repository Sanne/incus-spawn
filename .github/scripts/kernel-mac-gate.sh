#!/bin/bash
# The kernel-mac-verified check (#1355): passes a PR whose merge leaves the macOS appliance
# kernel's inputs as they are on its base, and otherwise only one with a recorded Mac boot of a
# build with exactly those inputs. A boot is a commit status `isx/mac-kernel-boot`, state success,
# set by a login in VERIFIERS, whose description is `fingerprint <fp>`, with <fp> what this
# checkout's appliance/kernel/kernel-fingerprint.sh gives for the PR merged into its base. It is
# looked for on the PR's head and on the commits the verifiers' "### Mac kernel verification"
# records on the PR name, so a rebase that leaves the kernel alone keeps the boot; the newest
# status a verifier set on a commit is the one that counts.
#
# Runs from kernel-mac-verified.yml under pull_request_target, i.e. the base branch's copy of this
# script and of kernel-fingerprint.sh: it reads the PR's merge as git trees and its files,
# statuses and comments through the API, and never runs anything from the PR. So a PR that
# changes what kernel-fingerprint.sh counts as an input is fingerprinted by the old lists; such a
# change goes in a PR of its own first.
#
# Env: REPO, PR, VERIFIERS (space-separated logins), GH_TOKEN; run in a checkout whose origin
# serves refs/pull/<PR>/merge. Needs bash, git and gh. The PR's head comes from the API, not the
# event, so the workflow never names it: a pull_request_target workflow that does reads, to a
# scanner, like one that checks the head out.
set -euo pipefail
FINGERPRINT="$(cd "$(dirname "$0")/../.." && pwd)/appliance/kernel/kernel-fingerprint.sh"
CONTEXT=isx/mac-kernel-boot
RECORD="### Mac kernel verification"
MERGE_REF=refs/remotes/pr/merge
ERR="$(mktemp)"
trap 'rm -f "$ERR"' EXIT

# A PR touching none of the inputs leaves its merge's inputs as its base's, whatever the base did
# meanwhile; most PRs end here, without waiting for GitHub's merge.
# The API lists at most 3000 files, so a longer list proves nothing.
files="$(gh api --paginate "repos/$REPO/pulls/$PR/files" --jq '.[] | .filename, (.previous_filename // empty)')"
if [ "$(printf '%s\n' "$files" | grep -c .)" -lt 3000 ] && ! printf '%s\n' "$files" | grep -qE '^appliance/(build\.sh|kernel/)'; then
    echo "The PR changes neither appliance/build.sh nor appliance/kernel/: no Mac boot needed."
    exit 0
fi

# GitHub recomputes the merge after a push, and may not have yet: asking for the PR's mergeability
# makes it start, and a merge of an older head would be checked in place of this one.
HEAD_SHA="$(gh api "repos/$REPO/pulls/$PR" --jq .head.sha)"
merged_head=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    gh api "repos/$REPO/pulls/$PR" --jq .mergeable > /dev/null
    git fetch -q --no-tags --depth 2 origin "+refs/pull/$PR/merge:$MERGE_REF" 2> /dev/null || true
    merged_head="$(git rev-parse -q --verify "$MERGE_REF^2" || true)"
    [ "$merged_head" = "$HEAD_SHA" ] && break
    sleep "${MERGE_WAIT:-10}"
done
if [ "$merged_head" != "$HEAD_SHA" ]; then
    echo "::error::$MERGE_REF merges ${merged_head:-nothing}, not the PR's head $HEAD_SHA: GitHub has not merged it yet, or it conflicts. Re-run this check once the PR is mergeable." >&2
    exit 1
fi

base_fp="$("$FINGERPRINT" "$MERGE_REF^1")"
fp="$("$FINGERPRINT" "$MERGE_REF")"
if [ "$fp" = "$base_fp" ]; then
    echo "The appliance kernel's inputs are unchanged (fingerprint $fp): no Mac boot needed."
    exit 0
fi

verifiers="$(printf '"%s",' $VERIFIERS)"
verifiers="${verifiers%,}"

candidates="$(
    echo "$HEAD_SHA"
    gh api --paginate "repos/$REPO/issues/$PR/comments" \
        --jq ".[] | select(.user.login | IN($verifiers)) | select(.body | startswith(\"$RECORD\")) | .body" \
        | sed -n 's/^Commit: \([0-9a-f]\{40\}\)[[:space:]]*$/\1/p'
)"

for sha in $(printf '%s\n' "$candidates" | awk 'NF && !seen[$0]++'); do
    # A record can name a commit a force-push has since removed; anything else is an error.
    if ! latest="$(gh api "repos/$REPO/commits/$sha/statuses?per_page=100" \
            --jq "[.[] | select(.context == \"$CONTEXT\" and (.creator.login | IN($verifiers)))][0] // empty | \"\(.state) \(.description) \(.target_url)\"" \
            2> "$ERR")"; then
        grep -qE 'HTTP 4(04|22)' "$ERR" && continue
        cat "$ERR" >&2
        exit 1
    fi
    read -r state word recorded url <<< "$latest" || true
    if [ "${state:-}" = success ] && [ "${word:-}" = fingerprint ] && [ "${recorded:-}" = "$fp" ]; then
        echo "Booted on a Mac with these kernel inputs (fingerprint $fp), recorded on $sha: ${url:-no link}"
        exit 0
    fi
done

cat >&2 <<EOF
::error::This PR's merge changes the macOS appliance kernel's inputs (fingerprint $fp), and no Mac boot of them is recorded.
Boot its appliance-aarch64 build on the bare-metal Mac with appliance/test-kernel-on-mac.sh $PR
(ci.md, #791); that records it and re-runs this check.
EOF
exit 1
