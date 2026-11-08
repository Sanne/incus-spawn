# Source type jetbrains (#2036): a JetBrains IDE pinned in a tool YAML, idea-backend today. JetBrains
# signs neither tarball nor sums, so the proof is the publisher's own .sha256 beside each tarball
# (same origin: consistency and transfer, not provenance) plus a re-hash of the download; a link
# that leaves source.url-prefix is refused, whatever the feed says. Every release is mechanical
# (within: any): the tool installs whatever /opt/idea-IU-* unpacks to, which the re-hash checks.

TOOL_FILE="$(item_list "$ITEM" files | head -1)"
_api() { printf 'https://data.services.jetbrains.com/products/releases?code=%s&type=release' "$(item "$ITEM" source.code)"; }
_prefix() { item "$ITEM" source.url-prefix; }

# The URL and sum of one arch's download as the YAML has them.
_url() { yq -r ".downloads[] | select(.arch == \"$1\") | .url" "$TREE/$TOOL_FILE"; }
_sum() { yq -r ".downloads[] | select(.arch == \"$1\") | .sha256" "$TREE/$TOOL_FILE"; }
_desc() { yq -r '.description' "$TREE/$TOOL_FILE"; }

# The manifest keys this type needs, checked by validate rather than by the first fetch.
src_validate() {
    [[ "$(item "$ITEM" source.code)" =~ ^[A-Z]+$ ]] || die "manifest: item $ITEM: source.code must be a JetBrains product code"
    [[ "$(_prefix)" =~ ^https://download\.jetbrains\.com/[a-z]+/$ ]] || die "manifest: item $ITEM: source.url-prefix must be a directory under https://download.jetbrains.com/"
    [[ -n "$(item "$ITEM" source.unpacks-to)" ]] || die "manifest: item $ITEM: source.unpacks-to is required"
}

src_current() {
    local v
    v="$(_url x86_64 | sed -n 's|^.*/idea-\([0-9][0-9.]*\)\.tar\.gz$|\1|p')"
    [[ -n "$v" ]] || die "cannot read the IDEA version from $TOOL_FILE's x86_64 URL"
    printf '%s' "$v"
}
src_series() { printf '%s' "$1" | cut -d. -f1; }
src_verifier() { echo publisher-sums; }
src_first_party() { return 1; }

_releases_json() {
    local f="$UPSTREAM_CACHE/jetbrains-$(item "$ITEM" source.code).json"
    [[ -s "$f" ]] || fetch "$(_api)" "$f"
    printf '%s' "$f"
}

# Every release with both Linux downloads, oldest first.
src_releases() {
    jq -r --arg c "$(item "$ITEM" source.code)" \
        '.[$c][] | select(.downloads.linux.link and .downloads.linuxARM64.link) | .version' "$(_releases_json)" \
        | grep -E '^[0-9]+(\.[0-9]+)+$' | sort -V
}

src_released_at() {
    local d
    d="$(jq -r --arg c "$(item "$ITEM" source.code)" --arg v "$1" '[.[$c][] | select(.version == $v) | .date] | first // empty' "$(_releases_json)")"
    [[ "$d" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "no release date for $1"
    date -u -d "$d" +%s
}

# _publisher_sum <link> <checksumLink>: the sum the .sha256 file beside the tarball gives it, and,
# unless UPSTREAM_REHASH=0, the tarball's own hash, which must agree; the archive must unpack to
# source.unpacks-to.
_publisher_sum() {
    local link="$1" sums="$2" f sum have top
    [[ "$link" == "$(_prefix)"* && "$sums" == "$(_prefix)"* ]] || die "a JetBrains link leaves $(_prefix): $link $sums"
    [[ "$sums" == "$link.sha256" ]] || die "the checksum link is not the tarball's .sha256: $sums"
    f="$(mktemp)"
    fetch "$sums" "$f"
    sum="$(awk 'NR == 1 { print $1 }' "$f")"
    is_sha256 "$sum" || die "$sums holds no sha256"
    if [[ "${UPSTREAM_REHASH:-1}" != 0 ]]; then
        fetch "$link" "$f"
        have="$(sha256sum "$f" | cut -d' ' -f1)"
        [[ "$have" == "$sum" ]] || die "$link hashes to $have, but $sums says $sum"
        # head stops after the first entry, so tar dies of SIGPIPE on a real tarball: not a failure.
        top="$( (set +o pipefail; tar tzf "$f" | head -1) )"
        [[ "$top" == "$(item "$ITEM" source.unpacks-to)"* ]] || die "$link unpacks to '$top', not $(item "$ITEM" source.unpacks-to)*"
    fi
    rm -f "$f"
    printf '%s' "$sum"
}

src_apply() {
    local version="$1" entry link_x link_a sums_x sums_a sum_x sum_a f="$TREE/$TOOL_FILE" desc
    [[ "$version" =~ ^[0-9]+(\.[0-9]+)+$ ]] || die "not a JetBrains version: '$version'"
    entry="$(jq -c --arg c "$(item "$ITEM" source.code)" --arg v "$version" '[.[$c][] | select(.version == $v)] | first // empty' "$(_releases_json)")"
    [[ -n "$entry" ]] || die "JetBrains lists no release $version"
    link_x="$(jq -r '.downloads.linux.link // empty' <<<"$entry")"
    sums_x="$(jq -r '.downloads.linux.checksumLink // empty' <<<"$entry")"
    link_a="$(jq -r '.downloads.linuxARM64.link // empty' <<<"$entry")"
    sums_a="$(jq -r '.downloads.linuxARM64.checksumLink // empty' <<<"$entry")"
    [[ -n "$link_x" && -n "$sums_x" && -n "$link_a" && -n "$sums_a" ]] || die "release $version lacks a Linux download"
    [[ "$link_x" == "$(_prefix)idea-$version.tar.gz" && "$link_a" == "$(_prefix)idea-$version-aarch64.tar.gz" ]] \
        || die "release $version's links are not the expected names: $link_x $link_a"
    sum_x="$(_publisher_sum "$link_x" "$sums_x")"
    sum_a="$(_publisher_sum "$link_a" "$sums_a")"
    is_sha256 "$sum_x" && is_sha256 "$sum_a" || die "release $version: no publisher sum for a download"
    desc="$(_desc)"
    replace_once "$f" "$(_url x86_64)" "$link_x"
    replace_once "$f" "$(_sum x86_64)" "$sum_x"
    replace_once "$f" "$(_url aarch64)" "$link_a"
    replace_once "$f" "$(_sum aarch64)" "$sum_a"
    replace_once "$f" "description: $desc" "description: ${desc% *} $version"
    [[ "$(src_current)" == "$version" && "$(_sum aarch64)" == "$sum_a" ]] || die "failed to rewrite $TOOL_FILE"
}

src_fields() {
    printf 'description=%s\nurl.x86_64=%s\nsha256.x86_64=%s\nurl.aarch64=%s\nsha256.aarch64=%s\n' \
        "$(_desc)" "$(_url x86_64)" "$(_sum x86_64)" "$(_url aarch64)" "$(_sum aarch64)"
}

src_describe() {
    cat <<MD
Bumps \`$ITEM\` to IntelliJ IDEA $1, the newest release JetBrains lists for \`$(item "$ITEM" source.code)\`.

Both tarball links and their \`.sha256\` files come from JetBrains' releases feed, under \`$(_prefix)\` only; the engine re-hashed both downloads and checked that they unpack to \`$(item "$ITEM" source.unpacks-to)*\`. Release notes: https://www.jetbrains.com/idea/whatsnew/

Users who want another version override the tool with a same-name \`~/.config/incus-spawn/tools/$ITEM.yaml\`.
MD
}
