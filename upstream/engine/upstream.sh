#!/usr/bin/env bash
# The upstream watch (#2036, design/upstream.md): one manifest of watched artifacts, one engine
# that proposes a bump PR for a mechanical item, an issue for a judgement item, and verifies both a
# person's bump and its own against the publisher. Runs from main's checkout only: upstream-watch.yml
# calls validate, plan, issues, publish and track; upstream-verify.yml calls verify-pr.
#
#   validate                 the manifest keeps every rule (UpstreamManifestTest runs it too)
#   current <item> [<tree>]  the version <tree> pins
#   releases <item>          every version the publisher lists, oldest first
#   check <item> [<tree>]    re-derive the pinned fields from the publisher and compare (no re-hash)
#   apply <item> <version> [<tree>]
#                            rewrite the item's fields to <version>, from the publisher, verified
#   plan <out>               decide every item: a prepared commit, an issue, or nothing (no app token)
#   issues <out>             open the issues plan wrote, with the run's own token
#   publish <out>            push the prepared commits and open or refresh their PRs, as the app
#   verify-pr <number>       the upstream-verify check for one PR of this repository
#   verify-trees <base> <head> <author> <same-repo: yes|no>
#                            the same on two git revisions of the engine's repository
#   track <success|failure> <run url>
#                            the one "[upstream] watch failing" issue
set -euo pipefail
. "$(dirname "$0")/lib.sh"

UPSTREAM_CACHE="$(mktemp -d)"
trap 'rm -rf "$UPSTREAM_CACHE"' EXIT
export UPSTREAM_CACHE

usage() { sed -n '2,20p' "$0" >&2; exit 2; }

# ---- validate -------------------------------------------------------------------------------------

validate() {
    local repo login i class within cooling secs type verify fingerprint files f gates judgement keys k
    repo="$(manifest_repo)"
    [[ "$repo" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$ ]] || die "manifest: repo is not owner/name: '$repo'"
    login="$(app_login)"
    [[ "$login" =~ ^[A-Za-z0-9-]+\[bot\]$ ]] || die "manifest: app.login is not a GitHub App login: '$login'"
    for k in $(yq -r 'keys | .[]' "$MANIFEST"); do
        case "$k" in repo|app|items) ;; *) die "manifest: unknown key '$k'" ;; esac
    done
    [[ -n "$(items)" ]] || die "manifest: no items"
    for i in $(items); do
        [[ "$i" =~ $ITEM_RE ]] || die "manifest: item name '$i' is not [a-z0-9-]"
        for k in $(yq -r ".items[\"$i\"] | keys | .[]" "$MANIFEST"); do
            case "$k" in class|within|cooling|judgement|source|verify|files|fields|gates) ;;
                *) die "manifest: item $i has unknown key '$k' (there is no key that skips a check)" ;; esac
        done
        class="$(item_class "$i")"
        [[ "$class" == mechanical || "$class" == judgement ]] || die "manifest: item $i: class must be mechanical or judgement, not '$class'"
        judgement="$(item "$i" judgement design)"
        [[ "$judgement" == design || "$judgement" == build ]] || die "manifest: item $i: judgement must be design or build"
        files="$(item_list "$i" files)"
        [[ -n "$files" ]] || die "manifest: item $i lists no files"
        while read -r f; do
            [[ -f "$ENGINE_ROOT/$f" ]] || die "manifest: item $i: no such file $f"
        done <<<"$files"
        # Every item has a source: plan asks it for the pinned version and the releases, a judgement
        # item's included, so one without fails here rather than the whole watch.
        load_source "$i"
        type="$(item_source_type "$i")"
        ! declare -F src_validate > /dev/null || src_validate
        [[ "$class" == mechanical ]] || continue
        within="$(item "$i" within)"
        [[ "$within" == series || "$within" == any ]] || die "manifest: mechanical item $i: within must be series or any"
        verify="$(item "$i" verify.type)"
        [[ -n "$verify" ]] || die "manifest: mechanical item $i has no verify.type"
        [[ "$verify" == "$(src_verifier)" ]] || die "manifest: item $i: verify.type $verify is not what source type $type provides ($(src_verifier))"
        if [[ "$verify" == signed-sums ]]; then
            fingerprint="$(item "$i" verify.fingerprint)"
            [[ -f "$ENGINE_ROOT/$(item "$i" verify.key)" ]] || die "manifest: item $i: verify.key is not a committed file"
            grep -q "^AUTOSIGNER_FPR=\"$fingerprint\"\$" "$ENGINE_ROOT/appliance/kernel/update-kernel-pin.sh" \
                || die "manifest: item $i: verify.fingerprint is not the pinned one"
        fi
        cooling="$(item "$i" cooling)"
        [[ -n "$cooling" ]] || die "manifest: mechanical item $i has no cooling"
        secs="$(duration_seconds "$cooling")"
        if (( secs < 86400 )) && ! src_first_party; then
            die "manifest: item $i: cooling $cooling is under the 24h floor for a third-party source"
        fi
        # The fields the item declares are the ones its source rewrites: a drift fails here.
        [[ "$(item_list "$i" fields | sort)" == "$(src_fields | cut -d= -f1 | sort)" ]] \
            || die "manifest: item $i: fields differ from what source type $type reads: $(src_fields | cut -d= -f1 | tr '\n' ' ')"
        for k in $(item_list "$i" gates); do
            [[ "$k" =~ ^[A-Za-z0-9._-]+$ ]] || die "manifest: item $i: gate '$k' is not a check name"
        done
    done
    echo "manifest ok: $(items | wc -l) items"
}

# ---- reading a tree -------------------------------------------------------------------------------

with_tree() { [[ -z "${1:-}" ]] || { [[ -d "$1" ]] || die "no such tree: $1"; TREE="$(cd "$1" && pwd)"; }; }

current() { load_source "$1"; with_tree "${2:-}"; src_current; echo; }
releases() { load_source "$1"; src_releases; }

# apply <item> <version> [<tree>]
apply() {
    load_source "$1"; with_tree "${3:-}"
    is_version "$2" || die "not a version: '$2'"
    src_apply "$2"
    echo "$1 now pins $2 in $TREE"
}

# regenerate_fields <item> <version>: src_fields as the publisher would have them for <version>, from
# a copy of TREE, without downloading tarballs. Prints key=value lines.
regenerate_fields() {
    local copy f
    copy="$(mktemp -d)"
    for f in $(item_list "$1" files); do
        mkdir -p "$copy/$(dirname "$f")"; cp "$TREE/$f" "$copy/$f"
    done
    ( TREE="$copy"; UPSTREAM_REHASH="${UPSTREAM_REHASH:-0}"; src_apply "$2" >&2; src_fields )
    rm -rf "$copy"
}

# check <item> [<tree>]: the pinned fields are what the publisher gives the pinned version.
check() {
    local have want
    load_source "$1"; with_tree "${2:-}"
    have="$(src_fields)"
    want="$(regenerate_fields "$1" "$(src_current)")" || die "$1: cannot derive $(src_current) from its publisher"
    if [[ "$have" != "$want" ]]; then
        log "$1 pins $(src_current), but the publisher gives:"
        diff <(echo "$have") <(echo "$want") >&2 || true
        return 1
    fi
    echo "$1: $(src_current) matches its publisher"
}

# ---- plan -----------------------------------------------------------------------------------------

# Writes <out>/plan.tsv (item, action, version, reason), a prepared commit per PR in <out>/<item>/
# (a worktree of HEAD with the bump committed as the app) and the issues to open in <out>/issues/.
# Needs APP_LOGIN and APP_EMAIL for the commits; fetches with no app credential.
plan() {
    local out="$1" i class within cur cands cand all top series judgement reason
    [[ -n "${APP_LOGIN:-}" && -n "${APP_EMAIL:-}" ]] || die "plan needs APP_LOGIN and APP_EMAIL"
    mkdir -p "$out/issues"
    : > "$out/plan.tsv"
    for i in $(items); do
        [[ -z "${UPSTREAM_ONLY:-}" || "$UPSTREAM_ONLY" == "$i" ]] || continue
        class="$(item_class "$i")"
        load_source "$i"
        TREE="$ENGINE_ROOT"
        cur="$(src_current)"
        all="$(src_releases)"
        [[ -n "$all" ]] || die "$i: the publisher lists no release"
        top="$(newest $all)"
        # The pin itself first: a pinned version whose sums the publisher no longer confirms is a
        # republished or tampered release, an issue, never a silent re-pin.
        if ! UPSTREAM_REHASH=0 check "$i" > /dev/null 2> "$out/$i.check"; then
            issue_file "$out" "$i" pin-mismatch "[upstream] $i: pinned $cur no longer matches its publisher" \
                "\`$i\` pins \`$cur\`, but the publisher now gives other values for it:

\`\`\`
$(cat "$out/$i.check")
\`\`\`

A release published again under its name, or a sums source that changed: a person decides. The watch proposes nothing for \`$i\` until the pin matches again (#2036)." design
            printf '%s\tissue\t%s\tpin mismatch\n' "$i" "$cur" >> "$out/plan.tsv"
            continue
        fi
        if [[ "$class" != mechanical ]]; then
            if is_newer "$top" "$cur"; then
                issue_file "$out" "$i" "$top" "[upstream] $i: $top available" \
                    "\`$i\` pins \`$cur\`; the publisher lists \`$top\`. The item is \`class: judgement\`: a box implements the bump and Sanne merges it (#2036).

$(src_describe "$top" 2>/dev/null || true)" "$(item "$i" judgement design)"
                printf '%s\tissue\t%s\tjudgement item\n' "$i" "$top" >> "$out/plan.tsv"
            else
                printf '%s\tnone\t%s\tcurrent\n' "$i" "$cur" >> "$out/plan.tsv"
            fi
            continue
        fi
        within="$(item "$i" within)"
        cands="$all"
        if [[ "$within" == series ]]; then
            series="$(src_series "$cur")"
            cands="$(for v in $all; do [[ "$(src_series "$v")" == "$series" ]] && echo "$v"; done || true)"
            if is_newer "$top" "$cur" && [[ "$(src_series "$top")" != "$series" ]]; then
                issue_file "$out" "$i" "$(src_series "$top")" "[upstream] $i: series $(src_series "$top") available" \
                    "\`$i\` pins \`$cur\` and follows series \`$series\` mechanically; the publisher now lists \`$top\`. Moving to another series needs a person (#2036)." \
                    "$(item "$i" judgement design)"
            fi
        fi
        if declare -F src_extra_issues > /dev/null; then
            while IFS=$'\t' read -r title body; do
                [[ -n "$title" ]] && issue_file "$out" "$i" extra "$title" "$body" "$(item "$i" judgement design)"
            done < <(src_extra_issues)
        fi
        if declare -F src_stale_issues > /dev/null; then
            while IFS=$'\t' read -r pattern keep comment; do
                [[ -n "$pattern" ]] && jq -n --arg p "$pattern" --arg k "$keep" --arg c "$comment" \
                    '{pattern: $p, keep: $k, comment: $c}' > "$out/issues/$i.close"
            done < <(src_stale_issues)
        fi
        cand="$(newest ${cands:-$cur})"
        if ! is_newer "$cand" "$cur"; then
            printf '%s\tnone\t%s\tcurrent\n' "$i" "$cur" >> "$out/plan.tsv"
            continue
        fi
        if ! cooled "$i" "$cand"; then
            printf '%s\twait\t%s\tcooling, or no release date yet\n' "$i" "$cand" >> "$out/plan.tsv"; continue
        fi
        # A none row's version is what main pins: publish hands it to close_stale as that, so the
        # declined candidate goes in the reason, never in the version column.
        if declined_version "$i" "$cand"; then
            printf '%s\tnone\t%s\tdeclined %s\n' "$i" "$cur" "$cand" >> "$out/plan.tsv"; continue
        fi
        # A re-hash of the downloads once per version: when this repository's open PR already
        # carries it, publish will find the branch carries the commit and push nothing, and
        # upstream-verify re-hashed it on the PR; the sums are still taken from the publisher.
        if open_pr_carries "$i" "$cand"; then
            UPSTREAM_REHASH=0 prepare_commit "$out" "$i" "$cur" "$cand"
        else
            prepare_commit "$out" "$i" "$cur" "$cand"
        fi
        printf '%s\tpr\t%s\tprepared\n' "$i" "$cand" >> "$out/plan.tsv"
    done
    cat "$out/plan.tsv"
}

# open_pr_carries <item> <version>: this repository's open PR on upstream/<item> names <version>
# (its marker, not pending). A read with whatever token the step has; an error reads as "no".
open_pr_carries() {
    local owner body
    owner="$(manifest_repo | cut -d/ -f1)"
    body="$(gh pr list -R "$(manifest_repo)" --head "upstream/$1" --state open --json body,headRepositoryOwner,isCrossRepository 2>/dev/null \
        | jq -r --arg o "$owner" '[.[] | select(.headRepositoryOwner.login == $o and .isCrossRepository == false) | .body] | first // ""' 2>/dev/null || true)"
    [[ "$body" == *"<!-- upstream-watch: $1 $2 -->"* ]]
}

# issue_file <out> <item> <key> <title> <body> <go: design|build>
issue_file() {
    local f="$1/issues/$2-$3.json"
    jq -n --arg t "$4" --arg b "$5" --arg l "go:$6" '{title: $t, body: $b, labels: [$l]}' > "$f"
}

# prepare_commit <out> <item> <current> <version>: a worktree of the engine's HEAD at <out>/<item>
# with the bump applied and committed as the app, and <out>/<item>.json describing the PR.
prepare_commit() {
    local out="$1" i="$2" cur="$3" version="$4" wt="$1/$2" title body marker f
    git -C "$ENGINE_ROOT" worktree add -q --detach "$wt" HEAD
    TREE="$wt" src_apply "$version"
    title="$(pr_title "$i" "$version")"
    marker="<!-- upstream-watch: $i $version -->"
    body="$(TREE="$wt" src_describe "$version")

Written by \`upstream/engine\` on \`main\` (#2036) for item \`$i\` of \`upstream/manifest.yaml\`: every value above comes from the publisher, nothing from a build log or a PR. The \`upstream-verify\` check regenerates this change from the same publisher data and fails unless this PR is exactly that; only then may the bot approve and merge it.

$marker"
    (
        cd "$wt"
        git config user.name "$APP_LOGIN"
        git config user.email "$APP_EMAIL"
        for f in $(item_list "$i" files); do git add -- "$f"; done
        git commit -q -m "$title" -m "By the upstream watch (#2036), from $cur, with every value from the publisher."
    )
    # The commit travels to the publish job as a bundle: a ref of its own, fetched there by name.
    git -C "$wt" update-ref "refs/upstream-prepared/$i" HEAD
    git -C "$wt" bundle create -q "$out/$i.bundle" "refs/upstream-prepared/$i"
    jq -n --arg i "$i" --arg v "$version" --arg c "$cur" --arg t "$title" --arg b "$body" --arg m "$marker" \
        --arg sha "$(git -C "$wt" rev-parse HEAD)" --arg files "$(item_list "$i" files | tr '\n' ' ')" \
        '{item: $i, version: $v, current: $c, title: $t, body: $b, marker: $m, commit: $sha, files: $files}' > "$out/$i.json"
    git -C "$ENGINE_ROOT" worktree remove --force "$wt"
}

pr_title() {
    case "$1" in
        kernel) echo "Update the kernel to $2" ;;
        base-image) echo "Update base image to $2" ;;
        *) echo "Update $1 to $2" ;;
    esac
}

# ---- issues and tracking (the run's own token) -----------------------------------------------------

# open_issue_number <title>: the number of the open issue with exactly that title, or nothing.
open_issue_number() {
    gh issue list --state open --search "in:title \"$1\"" --json number,title \
        | jq -r --arg t "$1" '[.[] | select(.title == $t) | .number][0] // empty'
}

# ensure_issue <title> <body> <label...>: one open issue of that exact title.
ensure_issue() {
    local title="$1" body="$2" n l; shift 2
    n="$(open_issue_number "$title")"
    if [[ -n "$n" ]]; then
        echo "#$n is open: $title"
        return 0
    fi
    local args=(--title "$title" --body "$body")
    for l in "$@"; do args+=(--label "$l"); done
    gh issue create "${args[@]}"
}

issues() {
    local out="$1" f pattern keep comment n title
    for f in "$out"/issues/*.json; do
        [[ -f "$f" ]] || continue
        ensure_issue "$(jq -r .title "$f")" "$(jq -r .body "$f")" $(jq -r '.labels[]' "$f")
    done
    # Issues a source says are done: every open one whose title matches, but the one to keep.
    for f in "$out"/issues/*.close; do
        [[ -f "$f" ]] || continue
        pattern="$(jq -r .pattern "$f")"; keep="$(jq -r .keep "$f")"; comment="$(jq -r .comment "$f")"
        gh issue list --state open --limit 200 --json number,title \
            | jq -r --arg p "$pattern" --arg k "$keep" '.[] | select((.title | test($p)) and .title != $k) | "\(.number)\t\(.title)"' \
            | while IFS=$'\t' read -r n title; do
                [[ -n "$n" ]] && gh issue close "$n" --comment "$comment" && echo "closed #$n: $title"
            done
    done
}

track() {
    local status="$1" run="$2" title="[upstream] watch failing" n
    n="$(open_issue_number "$title")"
    if [[ "$status" == success ]]; then
        [[ -z "$n" ]] || gh issue close "$n" --comment "Passing again: $run"
        # The retired update-kernel.yml's issue, once.
        n="$(open_issue_number "[kernel] update check failing")"
        [[ -z "$n" ]] || gh issue close "$n" --comment "update-kernel.yml retired into the upstream watch, which passes: $run"
    elif [[ -n "$n" ]]; then
        gh issue comment "$n" --body "Failed again: $run"
    else
        gh issue create --title "$title" --body "upstream-watch.yml failed: $run

A signature that does not verify, a sum the publisher does not confirm, a link off its allowlist or a manifest that breaks a rule fails closed: nothing is proposed until a person looks (#2036). The next good run closes this issue."
    fi
}

# ---- publish (the app's token) ---------------------------------------------------------------------

# publish <out>: plan's prepared commits become PRs; an item plan found current loses its open PR,
# which main has caught up with.
publish() {
    local out="$1" i action version reason
    [[ -n "${REPO:-}" && -n "${APP_EMAIL:-}" ]] || die "publish needs REPO and APP_EMAIL"
    . "$ENGINE_DIR/pr.sh"
    while IFS=$'\t' read -r i action version reason; do
        # pr.sh compares versions by the item's own order (src_order)
        load_source "$i"
        case "$action" in
            pr) git fetch -q "$out/$i.bundle" "refs/upstream-prepared/$i:refs/upstream-prepared/$i"
                [[ "$(git rev-parse "refs/upstream-prepared/$i")" == "$(jq -r .commit "$out/$i.json")" ]] || die "$i: the bundle does not carry the prepared commit"
                publish_item "$out/$i.json" ;;
            none) close_stale "$i" "$version" ;;
            *) echo "$i: $action ($reason)" ;;
        esac
    done < "$out/plan.tsv"
}

# ---- verify ---------------------------------------------------------------------------------------

# verify-pr <number>: the PR's trees are read from this checkout (the base repository's), never run.
verify_pr() {
    local pr="$1" repo json head base author same
    [[ "$pr" =~ ^[0-9]+$ ]] || die "not a PR number: '$pr'"
    repo="$(manifest_repo)"
    json="$(gh api "repos/$repo/pulls/$pr")"
    head="$(jq -r '.head.sha' <<<"$json")"
    base="$(jq -r '.base.ref' <<<"$json")"
    author="$(jq -r '.user.login' <<<"$json")"
    same="$(jq -r 'if .head.repo.full_name == .base.repo.full_name then "yes" else "no" end' <<<"$json")"
    [[ "$head" =~ ^[0-9a-f]{40}$ && "$base" =~ ^[A-Za-z0-9._/-]+$ ]] || die "cannot read PR #$pr"
    git -C "$ENGINE_ROOT" fetch -q origin "+refs/pull/$pr/head:refs/remotes/pr/$pr" "+refs/heads/$base:refs/remotes/origin/$base"
    [[ "$(git -C "$ENGINE_ROOT" rev-parse "refs/remotes/pr/$pr")" == "$head" ]] || die "PR #$pr moved while it was read; the next run checks it"
    verify_trees "$(git -C "$ENGINE_ROOT" merge-base "refs/remotes/origin/$base" "$head")" "$head" "$author" "$same"
}

# verify_trees <base rev> <head rev> <author login> <same-repo yes|no>
verify_trees() {
    local rc=0
    VERIFY_WT_HEAD=""; VERIFY_WT_BASE=""
    git -C "$ENGINE_ROOT" worktree prune
    # In a subshell with errexit on: a function on the left of || runs with it off, and a die inside
    # $(...) would then leave an empty sum and carry on to a diff that passes. The worktree paths
    # come back through files, since the subshell's variables do not.
    local state; state="$(mktemp)"
    set +e
    ( set -e; VERIFY_STATE="$state"; _verify_trees "$@" ); rc=$?
    set -e
    VERIFY_WT_HEAD="$(sed -n 's/^head=//p' "$state")"; VERIFY_WT_BASE="$(sed -n 's/^base=//p' "$state")"
    rm -f "$state"
    [[ -z "$VERIFY_WT_HEAD" ]] || git -C "$ENGINE_ROOT" worktree remove --force "$VERIFY_WT_HEAD" 2>/dev/null || true
    [[ -z "$VERIFY_WT_BASE" ]] || git -C "$ENGINE_ROOT" worktree remove --force "$VERIFY_WT_BASE" 2>/dev/null || true
    return "$rc"
}

_verify_trees() {
    local base="$1" head="$2" author="$3" same="$4" changed touched=() i f wt_head wt_base v_head v_base tree rc=0
    changed="$(git -C "$ENGINE_ROOT" diff --name-only "$base" "$head")"
    for i in $(items); do
        for f in $(item_list "$i" files); do
            grep -qxF -- "$f" <<<"$changed" && { touched+=("$i"); break; }
        done
    done
    if [[ ${#touched[@]} == 0 ]]; then
        echo "upstream-verify: no watched item changed"
        return 0
    fi
    wt_head="$(mktemp -d)"; rmdir "$wt_head"
    git -C "$ENGINE_ROOT" worktree add -q --detach "$wt_head" "$head"
    echo "head=$wt_head" >> "$VERIFY_STATE"
    if [[ "$author" == "$(app_login)" && "$same" == yes ]]; then
        # The app's PR: the whole tree must be what main's engine writes for that version.
        [[ ${#touched[@]} == 1 ]] || die "an upstream PR changes one item, this one changes: ${touched[*]}"
        i="${touched[0]}"
        [[ "$(item_class "$i")" == mechanical ]] || die "item $i is not mechanical, so no PR of the app's may bump it"
        while read -r f; do
            grep -qxF -- "$f" <(item_list "$i" files) || die "an upstream PR for $i changes $f, which is not one of its files"
        done <<<"$changed"
        load_source "$i"
        v_head="$(TREE="$wt_head" src_current)"
        v_base="$(git_show_current "$base")"
        is_version "$v_head" && is_version "$v_base" || die "$i: cannot read the version of the head or the base"
        is_newer "$v_head" "$v_base" || die "$i: $v_head is not newer than the base's $v_base"
        if [[ "$(item "$i" within)" == series ]]; then
            [[ "$(src_series "$v_head")" == "$(src_series "$v_base")" ]] || die "$i: $v_head is not of series $(src_series "$v_base")"
        fi
        # What plan would not propose, verify does not pass: a key thief gains nothing the engine
        # would not do (design/upstream.md).
        cooled "$i" "$v_head" || die "$i: $v_head is still within its cooling period, or the publisher does not date it yet"
        ! declined_version "$i" "$v_head" || die "$i: $v_head was declined (a closed, unmerged upstream/$i PR carried it)"
        wt_base="$(mktemp -d)"; rmdir "$wt_base"
        echo "base=$wt_base" >> "$VERIFY_STATE"
        git -C "$ENGINE_ROOT" worktree add -q --detach "$wt_base" "$base"
        TREE="$wt_base" src_apply "$v_head"
        # Every regenerated sum has a sum's shape, whatever path wrote it. Read into a variable
        # first: errexit does not see a process substitution fail, and an empty loop would pass.
        local fields
        fields="$(TREE="$wt_base" src_fields)"
        [[ -n "$fields" ]] || die "$i: no fields after regeneration"
        while IFS== read -r k v; do
            [[ "${k,,}" != *sha256* ]] || is_sha256 "$v" || die "$i: regenerated $k is not a sha256: '$v'"
        done <<<"$fields"
        tree="$(cd "$wt_base" && git add -A && git write-tree)"
        if ! git -C "$ENGINE_ROOT" diff --quiet "$tree" "$head"; then
            log "upstream-verify: PR tree is not main's engine's regeneration of $i at $v_head:"
            git -C "$ENGINE_ROOT" diff --stat "$tree" "$head" >&2
            return 1
        fi
        echo "upstream-verify: $i $v_head is exactly the engine's regeneration from $(git -C "$ENGINE_ROOT" rev-parse --short "$base")"
        return 0
    fi
    # Anyone else's bump: each touched item's fields must be the publisher's for the version pinned.
    for i in "${touched[@]}"; do
        UPSTREAM_REHASH=1 check "$i" "$wt_head"
    done
}

# git_show_current <rev>: the version a revision pins, for the loaded item, from a throwaway worktree.
git_show_current() {
    local wt v rc=0
    wt="$(mktemp -d)"; rmdir "$wt"
    git -C "$ENGINE_ROOT" worktree add -q --detach "$wt" "$1"
    set +e; v="$(set -e; TREE="$wt" src_current)"; rc=$?; set -e
    # Removed whether or not the pin could be read, so a revision without one leaves no worktree.
    git -C "$ENGINE_ROOT" worktree remove --force "$wt"
    (( rc == 0 )) || exit "$rc"
    printf '%s' "$v"
}

case "${1:-}" in
    validate) validate ;;
    current) [[ $# -ge 2 ]] || usage; current "$2" "${3:-}" ;;
    releases) [[ $# -ge 2 ]] || usage; releases "$2" ;;
    check) [[ $# -ge 2 ]] || usage; check "$2" "${3:-}" ;;
    apply) [[ $# -ge 3 ]] || usage; apply "$2" "$3" "${4:-}" ;;
    plan) [[ $# -ge 2 ]] || usage; plan "$2" ;;
    issues) [[ $# -ge 2 ]] || usage; issues "$2" ;;
    publish) [[ $# -ge 2 ]] || usage; publish "$2" ;;
    verify-pr) [[ $# -ge 2 ]] || usage; verify_pr "$2" ;;
    verify-trees) [[ $# -ge 5 ]] || usage; verify_trees "$2" "$3" "$4" "$5" ;;
    track) [[ $# -ge 3 ]] || usage; track "$2" "$3" ;;
    *) usage ;;
esac
