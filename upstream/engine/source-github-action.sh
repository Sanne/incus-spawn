# Source type github-action (#2210): the GitHub Actions the workflows use, each pinned to a commit and
# named by its release tag (uses: owner/repo[/path]@<40 hex> # vX.Y.Z). One item is a family: its
# source.members map a short key to each action's repository, and the item moves all of them as one
# version, written <key>.<tag>_<key>.<tag>... in the members' order (checkout.v6.1.0_cache.v5.1.0).
# The proof is tag-commit: the commit is what the tag points at on GitHub when it is read, an
# annotated tag followed to its commit. It does not prove what the action's code does.
# Every external uses: line of the item's files is read, and a tag instead of a commit, a missing or
# malformed tag comment, an action that is not a member and one member at two commits all fail, so a
# PR that adds any of them fails upstream-verify. The family is class judgement: the upstream app has
# no workflows permission, so it could never push the change (Sanne on #2210: "go with option 2").
# A release counts once its cooling has passed since GitHub published it; drafts, prereleases and tags
# that are not v<numeric dotted> never count.

GA_TAG_RE='^v[0-9]+(\.[0-9]+)+$'
GA_SHA_RE='^[0-9a-f]{40}$'
GA_KEY_RE='^[a-z0-9]+(-[a-z0-9]+)*$'
GA_REPO_RE='^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'

# "key<TAB>repo" per member, in the manifest's order.
_members() { yq -r ".items[\"$ITEM\"].source.members | to_entries | .[] | \"\(.key)\t\(.value)\"" "$MANIFEST"; }
_member_repo() { yq -r ".items[\"$ITEM\"].source.members[\"$1\"] // \"\"" "$MANIFEST"; }

# _uses: every external uses: of the item's files, "repo<TAB>sha<TAB>tag<TAB>file:line"; anything
# that is not a member pinned to a commit with its tag fails. A local (./) or docker:// use is not an
# action of a repository and is skipped.
_uses() {
    local f n line ref rest repo sha tag out="" members
    members="$(_members | cut -f2)"
    for f in $(item_list "$ITEM" files); do
        [[ -f "$TREE/$f" ]] || die "$ITEM: no such file $f"
        while IFS=: read -r n line; do
            [[ "$line" =~ ^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]*([^[:space:]#]+)(.*)$ ]] || continue
            ref="${BASH_REMATCH[2]}"; rest="${BASH_REMATCH[3]}"
            [[ "$ref" == ./* || "$ref" == docker://* ]] && continue
            [[ "$ref" =~ ^([A-Za-z0-9-]+/[A-Za-z0-9._-]+)(/[A-Za-z0-9._/-]+)?@([^@]+)$ ]] || die "$f:$n: uses '$ref' is not owner/repo[/path]@<ref>"
            repo="${BASH_REMATCH[1]}"; sha="${BASH_REMATCH[3]}"
            [[ "$sha" =~ $GA_SHA_RE ]] || die "$f:$n: $repo is used at '$sha', not at a commit (pin it: $repo@<sha> # <tag>)"
            [[ "$rest" =~ ^[[:space:]]+#[[:space:]]+(v[0-9.]+)[[:space:]]*$ ]] || die "$f:$n: $repo@$sha has no '# <tag>' comment"
            tag="${BASH_REMATCH[1]}"
            [[ "$tag" =~ $GA_TAG_RE ]] || die "$f:$n: '$tag' is not a release tag"
            grep -qxF -- "$repo" <<<"$members" || die "$f:$n: $repo is not a member of $ITEM (add it to the manifest)"
            out+="$repo"$'\t'"$sha"$'\t'"$tag"$'\t'"$f:$n"$'\n'
        done < <(grep -nE '^[[:space:]]*(-[[:space:]]+)?uses:' "$TREE/$f" || true)
    done
    printf '%s' "$out"
}

# _pinned <uses> <repo>: the member's one "sha<TAB>tag"; none or two fail.
_pinned() {
    local p
    p="$(awk -F'\t' -v r="$2" '$1 == r { print $2 "\t" $3 }' <<<"$1" | sort -u)"
    [[ -n "$p" ]] || die "$ITEM: member $2 is used in none of the item's files (remove it from the manifest)"
    [[ "$(wc -l <<<"$p")" == 1 ]] || die "$ITEM: $2 is pinned at more than one commit or tag: $(tr '\t\n' ' ,' <<<"$p")"
    printf '%s' "$p"
}

# _parse <version>: "key<TAB>tag" per member, in the members' order; a version that names another set
# of members, or a tag of another shape, fails.
_parse() {
    local part key tag out="" keys parts
    is_version "$1" || die "not a version: '$1'"
    IFS=_ read -ra parts <<<"$1"
    for part in "${parts[@]}"; do
        key="${part%%.v*}"; tag="v${part#*.v}"
        [[ "$part" == *.v* && "$key" =~ $GA_KEY_RE && "$tag" =~ $GA_TAG_RE ]] || die "$ITEM: '$part' of '$1' is not <key>.<tag>"
        out+="$key"$'\t'"$tag"$'\n'
    done
    keys="$(_members | cut -f1)"
    [[ "$(cut -f1 <<<"${out%$'\n'}")" == "$keys" ]] || die "$ITEM: '$1' does not name each member once, in order: $(tr '\n' ' ' <<<"$keys")"
    printf '%s' "$out"
}

# _commit <repo> <tag>: the commit the tag points at now, an annotated tag followed to it.
_commit() {
    local repo="$1" tag="$2" type sha ref n
    ref="$(gh api "repos/$repo/git/ref/tags/$tag" --jq '[.ref, .object.type, .object.sha] | @tsv')" || die "$repo has no tag $tag"
    IFS=$'\t' read -r n type sha <<<"$ref"
    [[ "$n" == "refs/tags/$tag" ]] || die "$repo: GitHub answered $n for tag $tag"
    for n in 1 2 3; do
        [[ "$type" == tag ]] || break
        [[ "$sha" =~ $GA_SHA_RE ]] || die "$repo $tag: not a tag object: '$sha'"
        IFS=$'\t' read -r type sha < <(gh api "repos/$repo/git/tags/$sha" --jq '[.object.type, .object.sha] | @tsv')
    done
    [[ "$type" == commit && "$sha" =~ $GA_SHA_RE ]] || die "$repo $tag does not point at a commit"
    printf '%s' "$sha"
}

# _releases <repo>: "tag<TAB>epoch" of every published release of a release tag, oldest first.
_releases() {
    gh api --paginate "repos/$1/releases" \
        --jq '.[] | select(.draft == false and .prerelease == false) | [.tag_name, .published_at] | @tsv' \
        | while IFS=$'\t' read -r tag at; do
            [[ "$tag" =~ $GA_TAG_RE && -n "$at" ]] || continue
            printf '%s\t%s\n' "$tag" "$(date -u -d "$at" +%s)"
        done | sort -V
}

# _cooled <repo>: the release tags past the item's cooling, oldest first. The listing is read into a
# variable first, so a failed GitHub read fails the run rather than reading as no release.
_cooled() {
    local secs rel
    secs="$(duration_seconds "$(item "$ITEM" cooling 72h)")"
    rel="$(_releases "$1")"
    awk -F'\t' -v lim="$(( $(date -u +%s) - secs ))" '$2 <= lim { print $1 }' <<<"$rel"
}

# _newer <a> <b>: tag a is strictly newer than tag b.
_newer() { [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" == "$1" ]]; }

_major() { printf '%s' "${1%%.*}"; }

src_validate() {
    local key repo
    [[ "$(item_class "$ITEM")" == judgement ]] \
        || die "manifest: item $ITEM: a github-action item is class judgement: the upstream app has no workflows permission, so it cannot push the bump"
    [[ -n "$(_members)" ]] || die "manifest: item $ITEM has no source.members"
    while IFS=$'\t' read -r key repo; do
        [[ "$key" =~ $GA_KEY_RE ]] || die "manifest: item $ITEM: member key '$key' is not [a-z0-9-]"
        [[ "$repo" =~ $GA_REPO_RE ]] || die "manifest: item $ITEM: member $key is not owner/repo: '$repo'"
    done < <(_members)
    [[ "$(_members | cut -f2 | sort | uniq -d)" == "" ]] || die "manifest: item $ITEM: a repository is a member twice"
    duration_seconds "$(item "$ITEM" cooling 72h)" > /dev/null
    src_current > /dev/null
}

src_current() {
    local uses key repo p out=""
    uses="$(_uses)"
    while IFS=$'\t' read -r key repo; do
        p="$(_pinned "$uses" "$repo")"
        out+="${out:+_}$key.${p#*$'\t'}"
    done < <(_members)
    printf '%s' "$out"
}

src_series() {
    local key tag out=""
    while IFS=$'\t' read -r key tag; do out+="${out:+_}$key.$(_major "$tag")"; done < <(_parse "$1")
    printf '%s' "$out"
}

# One version: each member at its newest release past cooling, never older than the one pinned.
src_releases() {
    local key tag pick out="" cur parsed
    cur="$(src_current)"; parsed="$(_parse "$cur")"
    while IFS=$'\t' read -r key tag; do
        pick="$(_cooled "$(_member_repo "$key")" | tail -1)"
        [[ -n "$pick" ]] && _newer "$pick" "$tag" || pick="$tag"
        out+="${out:+_}$key.$pick"
    done <<<"$parsed"
    printf '%s\n' "$out"
}

# Oldest first, by the members' versions in order: every version compared is of the same members.
src_order() {
    local v key tag n
    while read -r v; do
        [[ -n "$v" ]] || continue
        n=""
        while IFS=$'\t' read -r key tag; do
            n+="$(awk -F. '{ for (i = 1; i <= 4; i++) printf "%06d", (i <= NF ? $i : 0) }' <<<"${tag#v}")"
        done < <(_parse "$v")
        printf '%s\t%s\n' "$n" "$v"
    done | sort -t$'\t' -k1,1 | cut -f2
}

# The newest publication date of the members' releases; a tag with no release of its own counts as
# none. Only cooled() asks, which a judgement item never reaches: src_releases holds the cooling.
src_released_at() {
    local key tag at rel max=0
    while IFS=$'\t' read -r key tag; do
        rel="$(_releases "$(_member_repo "$key")")"
        at="$(awk -F'\t' -v t="$tag" '$1 == t { print $2 }' <<<"$rel")"
        [[ -n "$at" ]] && (( at > max )) && max="$at"
    done < <(_parse "$1")
    (( max > 0 )) || die "$ITEM: GitHub dates none of the releases of $1"
    printf '%s' "$max"
}

src_apply() {
    local version="$1" key tag repo sha f
    local -A shas=()
    while IFS=$'\t' read -r key tag; do
        repo="$(_member_repo "$key")"
        # The tag alone: actions/github-script published v8.0.0's release under v8, so a release
        # of the exact tag is not required. Plan proposes only tags of published releases.
        shas[$key]="$(_commit "$repo" "$tag")"
    done < <(_parse "$version")
    _uses > /dev/null
    while IFS=$'\t' read -r key tag; do
        repo="$(_member_repo "$key")"
        for f in $(item_list "$ITEM" files); do
            REPO="$repo" SHA="${shas[$key]}" TAG="$tag" perl -pi -e \
                's{^(\s*(?:-\s+)?uses:\s*\Q$ENV{REPO}\E(?:/[A-Za-z0-9._/-]+)?@)[0-9a-f]{40}(\s+#\s+)v[0-9.]+(\s*)$}{$1$ENV{SHA}$2$ENV{TAG}$3}' \
                "$TREE/$f"
        done
    done < <(_parse "$version")
    [[ "$(src_current)" == "$version" ]] || die "failed to rewrite $ITEM to $version"
    while IFS=$'\t' read -r key repo; do
        [[ "$(_pinned "$(_uses)" "$repo" | cut -f1)" == "${shas[$key]}" ]] || die "failed to rewrite $repo"
    done < <(_members)
}

src_fields() {
    local uses key repo p
    uses="$(_uses)"
    while IFS=$'\t' read -r key repo; do
        p="$(_pinned "$uses" "$repo")"
        printf '%s.tag=%s\n%s.commit=%s\n' "$key" "${p#*$'\t'}" "$key" "${p%%$'\t'*}"
    done < <(_members)
}

src_verifier() { echo tag-commit; }
src_first_party() { return 1; }

src_issue_title() { echo "[upstream] $ITEM: newer releases available"; }

# The issue's text: each member that moves, and the apply commands for the newest release within each
# pinned major and for the newest release outright (a new major needs its release notes read).
src_describe() {
    local key tag want repo rows="" within="" major=0 pick cur
    declare -A target=()
    cur="$(src_current)"
    while IFS=$'\t' read -r key tag; do target[$key]="$tag"; done < <(_parse "$1")
    while IFS=$'\t' read -r key tag; do
        want="${target[$key]}"; repo="$(_member_repo "$key")"
        if [[ "$(_major "$want")" == "$(_major "$tag")" ]]; then
            pick="$want"
        else
            major=1
            pick="$(_cooled "$repo" | awk -v m="$(_major "$tag")." 'index($0, m) == 1' | tail -1)"
            [[ -n "$pick" ]] && _newer "$pick" "$tag" || pick="$tag"
        fi
        within+="${within:+_}$key.$pick"
        [[ "$want" == "$tag" ]] || rows+="| \`$repo\` | $tag | $want$([[ "$(_major "$want")" == "$(_major "$tag")" ]] || echo ' (new major)') | https://github.com/$repo/releases/tag/$want |"$'\n'
    done < <(_parse "$cur")
    cat <<MD
These GitHub Actions have a newer release, each published at least $(item "$ITEM" cooling 72h) ago:

| Action | Pinned | Newest | Release notes |
|---|---|---|---|
${rows%$'\n'}

Write the bump with \`upstream/engine/upstream.sh apply $ITEM <version>\`, which sets each action to the commit its tag points at now.
MD
    if [[ "$within" == "$cur" ]]; then
        echo
        echo "No action has a newer release within its pinned major."
    else
        cat <<MD
Within each pinned major:

\`\`\`
upstream/engine/upstream.sh apply $ITEM $within
\`\`\`
MD
    fi
    if (( major )); then
        cat <<MD

A new major changes inputs and behaviour, so read its release notes first. Every action at its newest release:

\`\`\`
upstream/engine/upstream.sh apply $ITEM $1
\`\`\`
MD
    fi
    cat <<MD

The PR's \`upstream-verify\` check reads each tag on GitHub again and fails unless every commit is the one its tag names. That proves where the tag pointed, not what the action's code does, which is why a person merges it. The family is \`class: judgement\` because the upstream app has no \`workflows\` permission (#2210).
MD
}
