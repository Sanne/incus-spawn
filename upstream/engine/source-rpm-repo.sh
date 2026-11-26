# Source type rpm-repo (#2653): a package of a signed rpm repository, pinned in a tool YAML by the URL
# and sha256 of its rpm for each arch. The proof is the repository's own chain, the one dnf follows:
# repomd.xml is signed (repomd.xml.asc) by a key of the item's committed key file whose primary
# fingerprint is pinned, it gives the sha256 of the primary metadata, and that gives the sha256 of
# each rpm. So no rpm is downloaded, and UPSTREAM_REHASH has no effect here, as for signed-file.
#   source.repo      https URL of one arch's repository, with {arch} the only placeholder
#   source.package   the package's name
#   source.arches    the arches pinned, each a download of the file
#   source.title     the tool's description starts "<title> <version>", which is rewritten too
#   source.note      optional text the item's PR body carries
# A version is the package's rpm version, with a pre-release's "~" written "-" (0.1.0~preview.25 is
# 0.1.0-preview.25), the form a description shows and a branch name or commit title can hold. Only
# X.Y.Z and X.Y.Z~<word>.N are read; a release sorts after its pre-releases, as rpm sorts them. The
# release of a version is the highest the repository lists for it, per arch. A version is a candidate
# only when every arch lists it, and is dated by the latest of their build times. The series is X.Y.
# Each repository's metadata is read and verified once per run, and a URL is built from the manifest
# and values of the shapes above, never taken from the metadata.

RPM_FILE="$(item_first "$ITEM" files)"
_src() { yq -r ".items[\"$ITEM\"].source$1 // \"\"" "$MANIFEST"; }
_arches() { yq -r ".items[\"$ITEM\"].source.arches // [] | .[]" "$MANIFEST"; }
_repo() { local r; r="$(_src .repo)"; printf '%s' "${r//\{arch\}/$1}"; }
_fprs() { { item "$ITEM" verify.fingerprint; item_list "$ITEM" verify.fingerprints; } | grep . || true; }
_key() { printf '%s/%s' "$REPO_ROOT" "$(item "$ITEM" verify.key)"; }
# The engine's form of a version and the rpm's.
_display() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z]+\.[0-9]+)?$ ]]; }
_rpm_shape() { [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(~[a-z]+\.[0-9]+)?$ ]]; }
_to_rpm() { _display "$1" || die "not a version of $ITEM: '$1'"; printf '%s' "${1/-/\~}"; }
_to_display() { printf '%s' "${1/\~/-}"; }

# ---- the pins --------------------------------------------------------------------------------------

# _match <perl regex with groups>: the groups of its one match in the file, tab separated.
_match() {
    RE="$1" perl -0ne 'my @all; while (/$ENV{RE}/mg) { push @all, join("\t", map { $_ // "" } @{^CAPTURE}) } print scalar(@all), "\n", (@all ? $all[0] : ""), "\n"' "$TREE/$RPM_FILE" \
        | { read -r n; IFS= read -r v; [[ "$n" == 1 ]] || die "$RPM_FILE holds $ITEM's pin '$1' $n times, not once"; printf '%s' "$v"; }
}
_q() { printf '%s' "$1" | sed 's/[][\\.^$*+?(){}|\/-]/\\&/g'; }
_described() { _match "^description: $(_q "$(_src .title)") (\\S+)(?= |\$)"; }
# _pinned <arch>: "<rpm version>\t<release>\t<sha256>" of the arch's download.
_pinned() {
    local a="$1"
    _match "^  - url: $(_q "$(_repo "$a")$(_src .package)")-([0-9][0-9A-Za-z.~]*)-([0-9]+)\\.$(_q "$a")\\.rpm\\n    sha256: ([0-9a-f]{64})\$"
}

src_current() {
    local d p a rv
    d="$(_described)" || exit 1
    _display "$d" || die "$RPM_FILE's description does not name a version of $ITEM: '$d'"
    for a in $(_arches); do
        p="$(_pinned "$a")" || exit 1
        rv="$(cut -f1 <<<"$p")"
        [[ "$(_to_display "$rv")" == "$d" ]] || die "$RPM_FILE pins $ITEM $rv for $a but describes $d"
    done
    printf '%s' "$d"
}
src_series() { printf '%s' "$1" | cut -d. -f1-2; }
src_verifier() { echo signed-file; }
src_first_party() { return 1; }

# Each value is read and checked before anything is printed: printf would ignore a failed $(...).
src_fields() {
    local v a p out
    v="$(src_current)" || return 1
    out="version=$v"
    for a in $(_arches); do
        p="$(_pinned "$a")" || return 1
        out+=$'\n'"sha256.$a=$(cut -f3 <<<"$p")"
    done
    printf '%s\n' "$out"
}

# Oldest first: X.Y.Z by number, a pre-release before its release, pre-releases by word then number.
src_order() {
    perl -ne 'chomp; next unless /^(\d+)\.(\d+)\.(\d+)(?:-([a-z]+)\.(\d+))?$/; push @v, [$_, $1, $2, $3, defined $4 ? 0 : 1, $4 // "", $5 // 0];
        END { print "$_->[0]\n" for sort { $a->[1] <=> $b->[1] || $a->[2] <=> $b->[2] || $a->[3] <=> $b->[3] || $a->[4] <=> $b->[4] || $a->[5] cmp $b->[5] || $a->[6] <=> $b->[6] } @v }'
}

# ---- the repository --------------------------------------------------------------------------------

# _get <url>: the download, once per run.
_get() {
    local f="$UPSTREAM_CACHE/dl-$(printf '%s' "$1" | sha256sum | cut -c1-24)"
    [[ -s "$f" ]] || fetch "$1" "$f"
    printf '%s' "$f"
}

# _listing <arch>: a file of "<rpm version>\t<release>\t<sha256>\t<build time>" lines, the package's
# entries in the arch's primary metadata, read only once its chain from the signed repomd.xml checked.
_listing() {
    local a="$1" out="$UPSTREAM_CACHE/rpm-$ITEM-$1.tsv" base md sig ref sum href gz xml fprs
    [[ -f "$out" ]] && { printf '%s' "$out"; return; }
    base="$(_repo "$a")"
    md="$(_get "${base}repodata/repomd.xml")" || exit 1
    sig="$(_get "${base}repodata/repomd.xml.asc")" || exit 1
    mapfile -t fprs < <(_fprs)
    verify_signed "$(_key)" "$sig" "$md" "${fprs[@]}"
    ref="$(perl "$ENGINE_DIR/rpm-repo.pl" repomd "$md")" || die "$base: cannot read repomd.xml"
    sum="${ref%% *}"; href="${ref#* }"
    gz="$(_get "$base$href")" || exit 1
    [[ "$(sha256sum "$gz" | cut -d' ' -f1)" == "$sum" ]] || die "$base$href does not hash to the sha256 the signed repomd.xml gives"
    xml="$(mktemp -p "$UPSTREAM_CACHE")"
    gzip -dc "$gz" > "$xml" || die "$base$href is not gzip"
    PACKAGE="$(_src .package)" ARCH="$a" perl "$ENGINE_DIR/rpm-repo.pl" primary "$xml" > "$out.tmp" \
        || { rm -f "$out.tmp"; die "$base: cannot read the primary metadata"; }
    mv "$out.tmp" "$out"
    printf '%s' "$out"
}

# _entry <arch> <version>: "<release>\t<sha256>\t<build time>" of the highest release the arch lists
# for it, or nothing when it lists none.
_entry() {
    local f rv
    f="$(_listing "$1")" || exit 1
    rv="$(_to_rpm "$2")" || exit 1
    awk -F'\t' -v v="$rv" '$1 == v' "$f" | sort -t$'\t' -k2,2n | tail -1 | cut -f2-
}

src_releases() {
    local a f n=0 all=""
    for a in $(_arches); do
        f="$(_listing "$a")" || exit 1
        all+="$(cut -f1 "$f" | sort -u | while read -r v; do _rpm_shape "$v" && _to_display "$v" && echo; done || true)"$'\n'
        n=$((n + 1))
    done
    # Listed by every arch.
    { grep . <<<"$all" || true; } | sort | uniq -c | awk -v n="$n" '$1 == n { print $2 }' | src_order
}

src_released_at() {
    local a e t latest=0
    for a in $(_arches); do
        e="$(_entry "$a" "$1")" || exit 1
        [[ -n "$e" ]] || die "the $a repository of $ITEM does not list $1"
        t="$(cut -f3 <<<"$e")"
        (( t > latest )) && latest="$t"
    done
    printf '%s' "$latest"
}

# The pinned version must still be listed by every arch: a version the repository dropped is a
# pin-mismatch issue, never a failed run. 2 when a repository could not be read. Every failure is
# tested by hand: check calls this under ||, where errexit is off, and a set -e inside a subshell
# would not turn it back on.
src_confirms() {
    local a e
    for a in $(_arches); do
        e="$(_entry "$a" "$1")" || { log "ERROR: $ITEM: cannot read its repositories"; return 2; }
        [[ -n "$e" ]] || { log "$ITEM: the $a repository no longer lists the pinned $1"; return 1; }
    done
    return 0
}

src_apply() {
    local version="$1" rv a e old p d f="$TREE/$RPM_FILE" title
    local -A rel=() sum=()
    rv="$(_to_rpm "$version")" || exit 1
    for a in $(_arches); do
        e="$(_entry "$a" "$version")" || exit 1
        [[ -n "$e" ]] || die "the $a repository of $ITEM does not list $version"
        rel[$a]="$(cut -f1 <<<"$e")"; sum[$a]="$(cut -f2 <<<"$e")"
        is_sha256 "${sum[$a]}" || die "$ITEM $version: no sha256 for $a"
    done
    title="$(_src .title)"
    d="$(_described)" || exit 1
    replace_once "$f" "description: $title $d" "description: $title $version"
    for a in $(_arches); do
        p="$(_pinned "$a")" || exit 1
        old="$(_repo "$a")$(_src .package)-$(cut -f1 <<<"$p")-$(cut -f2 <<<"$p").$a.rpm"
        replace_once "$f" "  - url: $old" "  - url: $(_repo "$a")$(_src .package)-$rv-${rel[$a]}.$a.rpm"
        replace_once "$f" "    sha256: $(cut -f3 <<<"$p")" "    sha256: ${sum[$a]}"
    done
    [[ "$(src_current)" == "$version" ]] || die "failed to rewrite $ITEM's pins"
    for a in $(_arches); do [[ "$(_pinned "$a" | cut -f3)" == "${sum[$a]}" ]] || die "failed to rewrite $ITEM's pins"; done
}

# ---- manifest rules --------------------------------------------------------------------------------

src_validate() {
    local r a
    r="$(_src .repo)"
    [[ "$r" =~ ^https://[A-Za-z0-9.-]+/[A-Za-z0-9._/{}~-]*/$ && "$r" == *'{arch}'* && "${r//\{arch\}/}" != *[\{\}]* ]] \
        || die "manifest: item $ITEM: source.repo must be an https URL ending in /, with {arch} its only placeholder"
    [[ "$(_src .package)" =~ ^[a-z0-9][a-z0-9+._-]*$ ]] || die "manifest: item $ITEM: source.package is not a package name"
    [[ "$(_src .title)" =~ ^[A-Za-z0-9][A-Za-z0-9\ ._-]*$ ]] || die "manifest: item $ITEM: source.title must be plain words"
    [[ -n "$(_arches)" ]] || die "manifest: item $ITEM: source.arches is empty"
    for a in $(_arches); do [[ "$a" =~ ^[a-z0-9_]+$ ]] || die "manifest: item $ITEM: arch '$a' is not [a-z0-9_]"; done
    [[ "$(item_list "$ITEM" files | wc -l)" == 1 ]] || die "manifest: item $ITEM: an rpm-repo item pins one file"
    src_current > /dev/null
}

src_describe() {
    local a note
    note="$(_src .note)"
    cat <<MD
Bumps \`$ITEM\` to $1, the newest version its publisher's rpm repository lists for $(_arches | paste -sd, - | sed 's/,/ and /g').

Each pinned sha256 is the one the repository's primary metadata gives the rpm, and that metadata hashes to the sha256 its \`repomd.xml\` gives, whose OpenPGP signature the engine checked against \`$(item "$ITEM" verify.key)\`, accepting a signer only if its primary key is one of the item's pinned fingerprints. That is the chain dnf follows; no rpm is downloaded here:
$(for a in $(_arches); do printf -- '- `%s`: %srepodata/repomd.xml\n' "$a" "$(_repo "$a")"; done)
${note:+
$note
}
MD
}
