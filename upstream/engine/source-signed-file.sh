# Source type signed-file (#2210): a download whose publisher signs it with OpenPGP, pinned by its
# hash. The manifest says where the versions are listed (a directory listing or a feed page), which
# files an item pins, and where each pin sits in the tree; a signature by a key of the item's
# committed key file whose primary fingerprint is one of verify.fingerprint(s) is the proof, so a
# key the publisher added to the same file (Apache's KEYS holds dozens) proves nothing.
#   source.index          url and regex (one capture group: the version) of a listing; the
#                         date on an entry's line dates the version, so cooling counts from it
#   source.successors     optional second page and regex whose versions only show that another
#                         series exists (an issue), never a bump: Alpine's cross-branch feed
#   source.note           optional text the item's PR body carries (what a reviewer must know)
#   source.series-parts   how many dotted parts of a version name its series (default 2)
#   source.files.<label>  url ({version}), algo (sha256 or sha512, the hash the pin holds) and proof:
#                           artifact  the signature (url + sig-suffix, default .asc) is of the file
#                                     itself: the engine downloads both, verifies, hashes the file
#                           sums      the signature (sig-url) is of the publisher's sums file
#                                     (sums-url), whose line for the file's name gives the hash: the
#                                     file is never downloaded (a cloud image is gigabytes)
#   source.pins           file, text, optional count (default 1) and label: a literal text of the
#                         file with {version} and {sum} or {sum:<label>} where the values are
#                         (pins.pl). Every occurrence is read, and must agree, and every one is rewritten.
# A signature is always checked, so UPSTREAM_REHASH has no effect here. The URLs are built from the
# manifest and a version the listing gave in the shape of a dotted number, never taken from the feed.

_src() { yq -r ".items[\"$ITEM\"].source$1 // \"\"" "$MANIFEST"; }
_labels() { yq -r ".items[\"$ITEM\"].source.files | keys | .[]" "$MANIFEST"; }
# _f <label> <key> [<default>]
_f() { local v; v="$(_src ".files[\"$1\"][\"$2\"]")"; printf '%s' "${v:-${3:-}}"; }
_npins() { yq -r ".items[\"$ITEM\"].source.pins | length" "$MANIFEST"; }
_pin() { local v; v="$(_src ".pins[$1][\"$2\"]")"; printf '%s' "${v:-${3:-}}"; }
_series_parts() { local v; v="$(_src '["series-parts"]')"; printf '%s' "${v:-2}"; }
_numeric() { [[ "$1" =~ ^[0-9]+(\.[0-9]+)*$ ]]; }
_fprs() { { item "$ITEM" verify.fingerprint; item_list "$ITEM" verify.fingerprints; } | grep . || true; }
_key() { printf '%s/%s' "$ENGINE_ROOT" "$(item "$ITEM" verify.key)"; }
_sole_label() { local l; l="$(_labels)"; [[ "$(wc -l <<<"$l")" == 1 ]] && printf '%s' "$l" || true; }
_expand() { _numeric "$2" || die "not a release version: '$2'"; printf '%s' "${1//\{version\}/$2}"; }
_https() { [[ "$1" =~ ^https://[A-Za-z0-9.-]+/[A-Za-z0-9._/{}~-]*$ && "${1//\{version\}/}" != *[\{\}]* ]]; }

# _get <url>: the download, once per run.
_get() {
    local f="$UPSTREAM_CACHE/dl-$(printf '%s' "$1" | sha256sum | cut -c1-24)"
    [[ -s "$f" ]] || fetch "$1" "$f"
    printf '%s' "$f"
}

# ---- reading the pins ------------------------------------------------------------------------------

# Every occurrence of every pin, as version=<v> and sum:<label>=<hash> lines.
_read_all() {
    local i n count out
    n="$(_npins)"
    for ((i = 0; i < n; i++)); do
        out="$(TEXT="$(_pin "$i" text)" LABEL="$(_pin "$i" label "$(_sole_label)")" perl "$ENGINE_DIR/pins.pl" read "$TREE/$(_pin "$i" file)")" \
            || die "$ITEM: cannot read pin $i in $(_pin "$i" file)"
        count="$(_pin "$i" count 1)"
        [[ "$(head -1 <<<"$out")" == "count=$count" ]] \
            || die "$(_pin "$i" file) holds pin $i of $ITEM $(head -1 <<<"$out" | cut -d= -f2) times, not $count"
        tail -n +2 <<<"$out"
    done
}
_one() { # <prefix>: the one value of the lines that start with it
    local v; v="$(_read_all | sed -n "s/^$1=//p" | sort -u)"
    [[ -n "$v" && "$(wc -l <<<"$v")" == 1 ]] || die "$ITEM's pins in the tree do not agree on one value for $1: $(tr '\n' ' ' <<<"$v")"
    printf '%s' "$v"
}
_pinned_sum() { _one "sum:$1"; }

src_current() {
    local v; v="$(_one version)"
    _numeric "$v" || die "cannot read $ITEM's version from its pins"
    printf '%s' "$v"
}
src_series() { printf '%s' "$1" | cut -d. -f1-"$(_series_parts)"; }
src_verifier() { echo signed-file; }
src_first_party() { return 1; }

# Each value is read into a variable first: a die inside the arguments of a printf would only end
# that substitution, and the line would print empty.
src_fields() {
    local l v s
    v="$(src_current)"
    printf 'version=%s\n' "$v"
    for l in $(_labels); do
        s="$(_pinned_sum "$l")"
        printf '%s.%s=%s\n' "$(_f "$l" algo sha256)" "$l" "$s"
    done
}

# ---- the listings ----------------------------------------------------------------------------------

# _entries <index|successors>: "<version>\t<date>" lines of the page.
_entries() {
    local url regex
    url="$(_src ".$1.url")"; regex="$(_src ".$1.regex")"
    [[ -n "$url" && -n "$regex" ]] || return 0
    REGEX="$regex" perl "$ENGINE_DIR/index.pl" "$(_get "$url")"
}

src_releases() {
    # A successors version of the pinned series is no release yet (the index does not list it), so only
    # those of another series count: they are there to open the new-series issue.
    local cur series
    cur="$(src_current)"; series="$(src_series "$cur")"
    { _entries index; _entries successors | while IFS=$'\t' read -r v d; do [[ "$(src_series "$v")" != "$series" ]] && printf '%s\t%s\n' "$v" "$d"; done || true; } \
        | cut -f1 | grep -E '^[0-9]+(\.[0-9]+)*$' | sort -uV
}

src_released_at() {
    local d
    _numeric "$1" || die "not a release version: '$1'"
    d="$(awk -F'\t' -v v="$1" '$1 == v { print $2; exit }' <<<"$(_entries index)")"
    [[ -n "$d" ]] || die "the listing of $ITEM gives no date for $1"
    date -u -d "$d UTC" +%s
}

# ---- the proof -------------------------------------------------------------------------------------

# _publisher_sum <label> <version>: the hash of the label's file, proven by the signature.
_publisher_sum() {
    local label="$1" version="$2" url algo f sig sums name hash
    url="$(_expand "$(_f "$label" url)" "$version")"
    algo="$(_f "$label" algo sha256)"
    mapfile -t fprs < <(_fprs)
    case "$(_f "$label" proof)" in
        artifact)
            f="$(_get "$url")"
            sig="$(_get "$url$(_f "$label" sig-suffix .asc)")"
            verify_signed "$(_key)" "$sig" "$f" "${fprs[@]}"
            hash="$("${algo}sum" "$f" | cut -d' ' -f1)" ;;
        sums)
            sums="$(_get "$(_expand "$(_f "$label" sums-url)" "$version")")"
            sig="$(_get "$(_expand "$(_f "$label" sig-url)" "$version")")"
            verify_signed "$(_key)" "$sig" "$sums" "${fprs[@]}"
            name="${url##*/}"
            hash="$(awk -v n="$name" '{ f = $2; sub(/^\*/, "", f); if (f == n) print $1 }' "$sums")"
            [[ -n "$hash" && "$(wc -l <<<"$hash")" == 1 ]] || die "the signed sums of $label give $name no single hash" ;;
        *) die "$ITEM: file $label has no proof" ;;
    esac
    if [[ "$algo" == sha256 ]]; then is_sha256 "$hash"; else is_sha512 "$hash"; fi || die "$label $version: the proven hash is not a $algo"
    printf '%s' "$hash"
}

src_apply() {
    local version="$1" l i n listed
    local -A sums=()
    local -a envs=()
    _numeric "$version" || die "not a release version: '$version'"
    listed="$(_entries index | cut -f1)"
    grep -qxF -- "$version" <<<"$listed" || die "the listing of $ITEM does not give $version"
    for l in $(_labels); do
        sums[$l]="$(_publisher_sum "$l" "$version")"
        envs+=("SUM_$l=${sums[$l]}")
    done
    n="$(_npins)"
    for ((i = 0; i < n; i++)); do
        env TEXT="$(_pin "$i" text)" LABEL="$(_pin "$i" label "$(_sole_label)")" COUNT="$(_pin "$i" count 1)" VERSION="$version" "${envs[@]}" \
            perl "$ENGINE_DIR/pins.pl" write "$TREE/$(_pin "$i" file)" || die "$ITEM: cannot rewrite pin $i"
    done
    [[ "$(src_current)" == "$version" ]] || die "failed to rewrite $ITEM's pins"
    for l in $(_labels); do [[ "$(_pinned_sum "$l")" == "${sums[$l]}" ]] || die "failed to rewrite $ITEM's pins"; done
}

# ---- manifest rules --------------------------------------------------------------------------------

src_validate() {
    local l i n p have fp proof used
    for p in index.url successors.url; do
        [[ -z "$(_src ".$p")" ]] || _https "$(_src ".$p")" || die "manifest: item $ITEM: source.$p must be an https URL"
    done
    [[ -n "$(_src .index.url)" && -n "$(_src .index.regex)" ]] || die "manifest: item $ITEM: source.index needs url and regex"
    [[ -z "$(_src .successors.url)" || -n "$(_src .successors.regex)" ]] || die "manifest: item $ITEM: source.successors needs a regex"
    [[ "$(_series_parts)" =~ ^[1-9]$ ]] || die "manifest: item $ITEM: source.series-parts must be a digit"
    [[ -n "$(_labels)" ]] || die "manifest: item $ITEM: source.files is empty"
    for l in $(_labels); do
        [[ "$l" =~ ^[a-z0-9_]+$ ]] || die "manifest: item $ITEM: file label '$l' is not [a-z0-9_]"
        _https "$(_f "$l" url)" || die "manifest: item $ITEM: file $l: url must be https with {version} the only placeholder"
        [[ "$(_f "$l" algo sha256)" == sha256 || "$(_f "$l" algo sha256)" == sha512 ]] || die "manifest: item $ITEM: file $l: algo must be sha256 or sha512"
        proof="$(_f "$l" proof)"
        case "$proof" in
            artifact) [[ "$(_f "$l" sig-suffix .asc)" =~ ^\.[a-z]+$ ]] || die "manifest: item $ITEM: file $l: sig-suffix must be like .asc" ;;
            sums) _https "$(_f "$l" sums-url)" && _https "$(_f "$l" sig-url)" || die "manifest: item $ITEM: file $l: sums-url and sig-url must be https" ;;
            *) die "manifest: item $ITEM: file $l: proof must be artifact or sums" ;;
        esac
    done
    n="$(_npins)"
    [[ "$n" =~ ^[1-9][0-9]*$ ]] || die "manifest: item $ITEM: source.pins is empty"
    used=""
    for ((i = 0; i < n; i++)); do
        [[ -n "$(_pin "$i" text)" ]] || die "manifest: item $ITEM: pin $i has no text"
        item_list "$ITEM" files | grep -qxF -- "$(_pin "$i" file)" || die "manifest: item $ITEM: pin $i: $(_pin "$i" file) is not one of the item's files"
        [[ "$(_pin "$i" count 1)" =~ ^[1-9][0-9]*$ ]] || die "manifest: item $ITEM: pin $i: count must be a number"
        have="$(_pin "$i" text | grep -o '{sum[^}]*}' || true)"
        while IFS= read -r fp; do
            [[ -n "$fp" ]] || continue
            l="${fp#\{sum}"; l="${l#:}"; l="${l%\}}"
            [[ -n "$l" ]] || l="$(_pin "$i" label "$(_sole_label)")"
            _labels | grep -qxF -- "$l" || die "manifest: item $ITEM: pin $i: {sum} names no file label ('$l')"
            used="$used $l"
        done <<<"$have"
    done
    for l in $(_labels); do [[ " $used " == *" $l "* ]] || die "manifest: item $ITEM: no pin holds the hash of file $l"; done
    grep -q '{version}' <<<"$(yq -r ".items[\"$ITEM\"].source.pins[].text" "$MANIFEST")" || die "manifest: item $ITEM: no pin holds the version"
}

src_describe() {
    local l gates note
    note="$(_src .note)"
    gates="$(item_list "$ITEM" gates | paste -sd, -)"
    cat <<MD
Bumps \`$ITEM\` to $1, the newest release its publisher lists in $(_src .index.url) within the pinned series.

Each pinned hash is the hash of a file whose OpenPGP signature the engine checked against \`$(item "$ITEM" verify.key)\`, accepting a signer only if its primary key is one of the item's pinned fingerprints:
$(for l in $(_labels); do
    if [[ "$(_f "$l" proof)" == artifact ]]; then printf -- '- `%s`: the signature of the file itself (%s), which the engine downloaded and hashed.\n' "$l" "$(_expand "$(_f "$l" url)" "$1")"
    else printf -- '- `%s`: the signature of the publisher'"'"'s sums file (%s), whose line for the file gives the hash; the file itself is not downloaded.\n' "$l" "$(_expand "$(_f "$l" sums-url)" "$1")"; fi
done)
${note:+
$note
}${gates:+
This PR also needs: $gates (ci.md). The check stays red until it is done.}
MD
}
