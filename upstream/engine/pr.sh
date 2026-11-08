# Sourced by upstream.sh publish: one PR per item on upstream/<item>, opened or refreshed as the app,
# ported from update-kernel.yml (#1355) for every item (#2036). Needs GH_TOKEN (the app's installation
# token), REPO, APP_LOGIN and APP_EMAIL, and a checkout whose origin is REPO; the prepared commit
# comes from plan's <item>.json. Pushes go through gh's credential helper, so the token is never
# on a URL or in .git/config.
#
# The rules, each a line below: only this repository's own branch counts, never a fork's of the same
# name; a commit on the branch the app did not make stops any push or close; a declined version
# (its PR closed unmerged, recognised by the marker in the body) is not proposed again; a PR main
# has caught up with is closed; a refresh marks the PR pending before the push and names the version
# only after it, so a failure anywhere leaves a PR that claims nothing its branch lacks.

# publish_item <item.json>
publish_item() {
    local spec="$1" item version current title body marker pending branch commit files
    item="$(jq -r .item "$spec")"; version="$(jq -r .version "$spec")"; current="$(jq -r .current "$spec")"
    title="$(jq -r .title "$spec")"; body="$(jq -r .body "$spec")"; marker="$(jq -r .marker "$spec")"
    commit="$(jq -r .commit "$spec")"; files="$(jq -r .files "$spec")"
    [[ "$item" =~ $ITEM_RE && "$commit" =~ ^[0-9a-f]{40}$ ]] || die "malformed $spec"
    branch="upstream/$item"
    pending="<!-- upstream-watch: $item $version, pending -->"
    local owner="${REPO%%/*}"
    local ours="select(.headRepositoryOwner.login == \"$owner\" and .isCrossRepository == false)"

    gh auth setup-git > /dev/null
    local open body_now="" branch_sha="" foreign="" merge_base="" others
    open="$(gh pr list -R "$REPO" --head "$branch" --state open --json number,headRepositoryOwner,isCrossRepository \
        --jq "[.[] | $ours][0].number // empty")"
    # Fetched before the body is edited: an edit that fails stops the run with the PR as it was.
    [[ -z "$open" ]] || body_now="$(gh pr view "$open" -R "$REPO" --json body --jq .body)"

    if branch_exists "$branch"; then
        git fetch -q origin "$branch"
        branch_sha="$(git rev-parse FETCH_HEAD)"
        read -r merge_base foreign < <(branch_foreign "$branch")
    fi
    keep_out() { die "$branch has a commit this job did not make, so it is left alone: $1. Rebase or delete the branch by hand."; }

    # The branch already carries this version on a base whose pin is main's: its commit stands.
    local carries=""
    if [[ -n "$open" && -n "$branch_sha" ]]; then
        # shellcheck disable=SC2086
        if git diff --quiet "$branch_sha" "$commit" -- $files; then
            git fetch -q origin "$merge_base"
            # shellcheck disable=SC2086
            git diff --quiet "$merge_base" HEAD -- $files && carries=1
        fi
    fi
    finish_pr() {
        gh pr comment "$open" -R "$REPO" --body "Rebuilt as $version on the current main: the previous commit, and any verification of it, no longer applies."
        gh pr edit "$open" -R "$REPO" --title "$title" --body "$body"
    }
    if [[ -n "$carries" ]]; then
        if [[ "$body_now" == *"$pending"* ]]; then
            finish_pr; echo "#$open carries $version; finished its refresh."
        else
            echo "#$open already proposes $version."
        fi
        return 0
    fi
    # An open PR of a newer version than the publisher lists now stays: feeds lag.
    local branch_version=""
    if [[ -n "$open" && -n "$branch_sha" ]]; then
        branch_version="$(sed -n 's/^<!-- upstream-watch: [a-z0-9-]* \([^ ,>]*\).*-->$/\1/p' <<<"$body_now" | head -1)"
        if [[ -n "$branch_version" && "$branch_version" != "$version" ]] && is_newer "$branch_version" "$version"; then
            echo "::warning::#$open proposes $branch_version, newer than $version; left open."
            return 0
        fi
    fi
    # A declined version is recognised by the marker this job put in its PR's body, pending or not.
    local declined
    declined="$(gh pr list -R "$REPO" --head "$branch" --state closed --limit 200 --json body,mergedAt,headRepositoryOwner,isCrossRepository \
        --jq "[.[] | $ours | select(.mergedAt == null and ((.body | contains(\"$marker\")) or (.body | contains(\"$pending\"))))] | length")"
    if [[ "$declined" != 0 ]]; then
        echo "A PR for $item $version was closed unmerged; waiting for the next release."
        return 0
    fi
    [[ -z "$foreign" ]] || keep_out "it is not rebuilt as $version"
    if [[ -n "$open" ]]; then
        gh pr edit "$open" -R "$REPO" --title "$title" --body "Being rebuilt as $version: its commit does not carry it yet, so do not verify or review it until this says otherwise.

$pending"
    fi
    git push -q --force-with-lease="refs/heads/$branch:$branch_sha" origin "$commit:refs/heads/$branch"
    if [[ -n "$open" ]]; then
        finish_pr
    else
        gh pr create -R "$REPO" --base main --head "$branch" --title "$title" --body "$body"
    fi
}

# branch_exists <branch>: whether origin has it; an error asking is a failure, not "no".
branch_exists() {
    local rc=0
    git ls-remote --exit-code --heads origin "$1" > /dev/null || rc=$?
    [[ "$rc" == 0 || "$rc" == 2 ]] || exit "$rc"
    [[ "$rc" == 0 ]]
}

# branch_foreign <branch>: prints "<merge base with main> <1 if the branch carries a commit the app
# did not make, else empty>". Judged by its own commits, not its tip: a merge from main ("Update
# branch") is nobody's to keep. Such a commit stops any push or close of the branch.
branch_foreign() {
    local merge_base others
    read -r merge_base others < <(gh api "repos/$REPO/compare/main...$1" \
        --jq "\"\\(.merge_base_commit.sha) \\([.commits[] | select((.parents | length) == 1 and .commit.author.email != \"$APP_EMAIL\")] | length)\"")
    [[ -n "$merge_base" && -n "$others" ]] || die "could not compare $1 with main"
    printf '%s %s\n' "$merge_base" "$([[ "$others" == 0 ]] || echo 1)"
}

# close_stale <item> <current>: closes this item's open PR when main already pins its version or
# newer (called by publish for items whose plan says none). Without its marker, so it never reads as
# declined. A branch carrying a commit the app did not make is left alone, PR and branch, and the
# run fails so a person sees it: the branch holds someone's work.
close_stale() {
    local item="$1" current="$2" branch="upstream/$1" owner="${REPO%%/*}" open body v merge_base foreign=""
    local ours="select(.headRepositoryOwner.login == \"$owner\" and .isCrossRepository == false)"
    open="$(gh pr list -R "$REPO" --head "$branch" --state open --json number,headRepositoryOwner,isCrossRepository --jq "[.[] | $ours][0].number // empty")"
    [[ -n "$open" ]] || return 0
    body="$(gh pr view "$open" -R "$REPO" --json body --jq .body)"
    v="$(sed -n 's/^<!-- upstream-watch: [a-z0-9-]* \([^ ,>]*\).*-->$/\1/p' <<<"$body" | head -1)"
    [[ -n "$v" ]] || return 0
    is_newer "$v" "$current" && return 0
    if branch_exists "$branch"; then
        read -r merge_base foreign < <(branch_foreign "$branch")
    fi
    [[ -z "$foreign" ]] || die "$branch has a commit this job did not make, so #$open is not closed as stale and the branch stays. Rebase or delete it by hand."
    gh pr edit "$open" -R "$REPO" --body "$(grep -v '^<!-- upstream-watch: ' <<<"$body" || true)"
    gh pr close "$open" -R "$REPO" --delete-branch --comment "main already pins $current, so this bump of $v is no longer needed."
}
