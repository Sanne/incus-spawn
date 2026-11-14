# Source type github-asset (#2210): a tool's downloads pinned to the assets of one GitHub release, starship
# and the IntelliJ index plugin today. The manifest names the repository, the tag's prefix, one asset
# name per label (x86_64, aarch64, or any for an entry with no arch; {version} stands for the version)
# and where the sum comes from:
#   sums: sha256-asset   the publisher's own <asset>.sha256 beside it (verify.type publisher-sums).
#   sums: github-digest  GitHub's record of the asset's hash at upload, for a release that ships no
#                        sums (verify.type asset-digest). It is not the publisher's sum, so only a
#                        review item may rely on it (a person merges it).
# Either way the asset is downloaded again and hashed, and a link is never taken from the feed: the URL
# is built from the repository, tag and asset name, and must be the one GitHub lists for the asset.
# The version is read from the URL in the tool YAML (https://github.com/<repo>/releases/download/
# <tag-prefix><version>/<asset>), so the YAML holds each pin once. A draft, a prerelease and a tag that
# is not numeric dotted never count, and a release must list every asset (and its .sha256) to count.
# The series is the major version: a new major opens an issue.

TOOL_FILE="$(item_list "$ITEM" files | head -1)"
_repo() { item "$ITEM" source.repo; }
_prefix() { item "$ITEM" source.tag-prefix; }
_sums() { item "$ITEM" source.sums; }
_base() { printf 'https://github.com/%s/releases/download/%s' "$(_repo)" "$(_prefix)"; }
_labels() { yq -r '.items["'"$ITEM"'"].source.assets | keys | .[]' "$MANIFEST"; }
_template() { yq -r ".items[\"$ITEM\"].source.assets[\"$1\"]" "$MANIFEST"; }
# _asset <label> <version>: the asset's name for the version.
_asset() { local t; t="$(_template "$1")"; printf '%s' "${t//\{version\}/$2}"; }
_numeric() { [[ "$1" =~ ^[0-9]+(\.[0-9]+)+$ ]]; }

# _url <label>: the tool YAML's download URL of the label's asset, whatever version it names. The
# entry is the one whose URL is _base<version>/<the label's asset for that version>, exactly one.
_url() {
    local label="$1" u rest ver name found="" n=0
    while IFS= read -r u; do
        [[ "$u" == "$(_base)"* ]] || continue
        rest="${u#"$(_base)"}"
        ver="${rest%%/*}"
        name="${rest#*/}"
        [[ "$rest" == */* ]] && _numeric "$ver" && [[ "$name" == "$(_asset "$label" "$ver")" ]] || continue
        found="$u"; n=$((n + 1))
    done < <(yq -r '.downloads[].url' "$TREE/$TOOL_FILE")
    [[ "$n" == 1 ]] || die "$TOOL_FILE has $n downloads for $ITEM's asset '$label', not one"
    printf '%s' "$found"
}
_sum() { URL="$(_url "$1")" yq -r '.downloads[] | select(.url == strenv(URL)) | .sha256' "$TREE/$TOOL_FILE"; }
_desc() { yq -r '.description' "$TREE/$TOOL_FILE"; }
_versioned_description() { [[ "$(item "$ITEM" source.versioned-description false)" == true ]]; }

src_validate() {
    local l t
    [[ "$(_repo)" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$ ]] || die "manifest: item $ITEM: source.repo must be owner/name"
    [[ "$(_prefix)" =~ ^[A-Za-z]*$ ]] || die "manifest: item $ITEM: source.tag-prefix must be letters only"
    case "$(_sums)" in
        sha256-asset) ;;
        github-digest)
            [[ "$(item_class "$ITEM")" != mechanical ]] \
                || die "manifest: item $ITEM: sums github-digest is GitHub's record, not the publisher's sum, so only a review item may rely on it" ;;
        *) die "manifest: item $ITEM: source.sums must be sha256-asset or github-digest" ;;
    esac
    [[ -n "$(_labels)" ]] || die "manifest: item $ITEM: source.assets is empty"
    for l in $(_labels); do
        [[ "$l" =~ ^[a-z0-9_]+$ ]] || die "manifest: item $ITEM: asset label '$l' is not [a-z0-9_]"
        t="$(_template "$l")"
        [[ "$t" =~ ^[A-Za-z0-9._{}-]+$ && "${t//\{version\}/}" != *[\{\}]* ]] \
            || die "manifest: item $ITEM: asset '$t' is not a file name, with {version} the only placeholder"
        # Reading the pin checks that the YAML holds exactly one download for it.
        _url "$l" > /dev/null
    done
}

src_current() {
    local l v="" w u
    for l in $(_labels); do
        u="$(_url "$l")"
        w="${u#"$(_base)"}"; w="${w%%/*}"
        [[ -z "$v" || "$v" == "$w" ]] || die "$TOOL_FILE pins $ITEM at $v and at $w"
        v="$w"
    done
    _numeric "$v" || die "cannot read $ITEM's version from $TOOL_FILE"
    printf '%s' "$v"
}
src_series() { printf '%s' "${1%%.*}"; }
src_verifier() { if [[ "$(_sums)" == github-digest ]]; then echo asset-digest; else echo publisher-sums; fi; }
src_first_party() { return 1; }

# Every published release of numeric tag that lists each asset (and its .sha256 when the sums are
# those), oldest first.
src_releases() {
    local want="[]" l n
    for l in $(_labels); do
        n="$(_asset "$l" '{v}')"
        want="$(jq -c --arg n "$n" '. + [$n]' <<<"$want")"
        [[ "$(_sums)" != sha256-asset ]] || want="$(jq -c --arg n "$n.sha256" '. + [$n]' <<<"$want")"
    done
    gh api --paginate "repos/$(_repo)/releases" \
        --jq '.[] | select(.draft == false and .prerelease == false) | {tag: .tag_name, assets: [.assets[].name]}' \
        | jq -r --argjson want "$want" --arg p "$(_prefix)" '
            . as $r | select($r.tag | startswith($p)) | ($r.tag[($p | length):]) as $v
            | select($v | test("^[0-9]+(\\.[0-9]+)+$"))
            | select(all($want[] | gsub("\\{v\\}"; $v); . as $n | $r.assets | index($n) != null))
            | $v' \
        | sort -V
}

src_released_at() {
    local at
    _numeric "$1" || die "not a release version: '$1'"
    at="$(gh api "repos/$(_repo)/releases/tags/$(_prefix)$1" --jq '.published_at // empty')"
    [[ -n "$at" ]] || die "release $(_prefix)$1 has no published_at"
    date -u -d "$at" +%s
}

# _publisher_sum <release json> <label> <version>: the sum the publisher gives the label's asset in the
# release, checked against the asset's own hash unless UPSTREAM_REHASH=0.
_publisher_sum() {
    local rel="$1" label="$2" version="$3" name url sum have f listed
    name="$(_asset "$label" "$version")"
    url="$(_base)$version/$name"
    listed="$(jq -r --arg n "$name" '[.assets[] | select(.name == $n)] | first | .url // empty' <<<"$rel")"
    [[ "$listed" == "$url" ]] || die "release $(_prefix)$version lists $name at '$listed', not at $url"
    f="$(mktemp)"
    case "$(_sums)" in
        sha256-asset)
            listed="$(jq -r --arg n "$name.sha256" '[.assets[] | select(.name == $n)] | first | .url // empty' <<<"$rel")"
            [[ "$listed" == "$url.sha256" ]] || die "release $(_prefix)$version lists no $name.sha256 at $url.sha256"
            fetch "$url.sha256" "$f"
            sum="$(awk 'NR == 1 { print $1 }' "$f")"
            is_sha256 "$sum" || die "$url.sha256 holds no sha256" ;;
        github-digest)
            sum="$(jq -r --arg n "$name" '[.assets[] | select(.name == $n)] | first | .digest // empty' <<<"$rel")"
            [[ "$sum" == sha256:* ]] || die "GitHub gives $name no sha256 digest"
            sum="${sum#sha256:}"
            is_sha256 "$sum" || die "GitHub's digest of $name is not a sha256" ;;
    esac
    if [[ "${UPSTREAM_REHASH:-1}" != 0 ]]; then
        fetch "$url" "$f"
        have="$(sha256sum "$f" | cut -d' ' -f1)"
        [[ "$have" == "$sum" ]] || die "$url hashes to $have, but the release gives $sum"
    fi
    rm -f "$f"
    printf '%s' "$sum"
}

src_apply() {
    local version="$1" rel l cur desc new f="$TREE/$TOOL_FILE" url sum
    local -A urls=() sums=()
    _numeric "$version" || die "not a release version: '$version'"
    rel="$(gh api "repos/$(_repo)/releases/tags/$(_prefix)$version" \
        --jq '{draft, prerelease, assets: [.assets[] | {name, digest, url: .browser_download_url}]}')"
    [[ "$(jq -r '.draft or .prerelease' <<<"$rel")" == false ]] || die "release $(_prefix)$version is a draft or a prerelease"
    for l in $(_labels); do
        sums[$l]="$(_publisher_sum "$rel" "$l" "$version")"
        urls[$l]="$(_base)$version/$(_asset "$l" "$version")"
        is_sha256 "${sums[$l]}" || die "release $version: no sum for '$l'"
    done
    cur="$(src_current)"
    desc="$(_desc)"
    for l in $(_labels); do
        url="$(_url "$l")"; sum="$(_sum "$l")"
        replace_once "$f" "$url" "${urls[$l]}"
        replace_once "$f" "$sum" "${sums[$l]}"
    done
    if _versioned_description; then
        [[ "$desc" == *"$cur"* ]] || die "the description of $TOOL_FILE does not name $cur"
        new="${desc/"$cur"/"$version"}"
        replace_once "$f" "description: $desc" "description: $new"
    fi
    [[ "$(src_current)" == "$version" ]] || die "failed to rewrite $TOOL_FILE"
    for l in $(_labels); do [[ "$(_sum "$l")" == "${sums[$l]}" ]] || die "failed to rewrite $TOOL_FILE"; done
}

src_fields() {
    local l
    ! _versioned_description || printf 'description=%s\n' "$(_desc)"
    for l in $(_labels); do printf 'url.%s=%s\nsha256.%s=%s\n' "$l" "$(_url "$l")" "$l" "$(_sum "$l")"; done
}

src_describe() {
    local how
    if [[ "$(_sums)" == github-digest ]]; then
        how="The release ships no sums, so each sum is the hash GitHub recorded for the asset when it was uploaded; the engine downloaded the asset again and checked that it hashes to it. That is GitHub's record, not a sum the publisher signed, which is why a person merges this PR."
    else
        how="Each sum is the publisher's own \`.sha256\` beside the asset, taken from the release; the engine downloaded the asset again and checked that it hashes to it. The sum comes from the same origin as the asset, so it proves consistency and transfer, not provenance."
    fi
    cat <<MD
Bumps \`$ITEM\` to $(_prefix)$1, the newest release of $(_repo) that lists every asset.

Every URL is built from the repository, the tag and the asset name, and is the one GitHub lists for that asset. $how Release notes: https://github.com/$(_repo)/releases/tag/$(_prefix)$1

Users who want another version override the tool with a same-name \`~/.config/incus-spawn/tools/\` file.
MD
}
