# Source type apt-repo (#2733): one package of a signed Debian repository, pinned in an env file by the
# exact version apt installs, epoch included (ZABBLY_INCUS_VERSION=1:7.6-ubuntu24.04-202611072126).
# The proof is the repository's own chain, the one apt follows: the suite's Release file is signed
# (Release.gpg) by a key of the item's committed key file whose primary fingerprint is pinned, it gives
# the sha256 of the arch's Packages.gz, and that lists the versions. No .deb is downloaded: the pin is
# a version, and the runner's apt checks the package against the same signed chain when it installs.
#   source.repo       https URL of the repository, ending in /
#   source.suite      the suite (dists/<suite>/), a distribution's codename
#   source.component  the component (main)
#   source.arch       the Debian arch pinned (amd64)
#   source.package    the package's name
#   source.with       optional packages installed beside it at the same version (incus-base and
#                     incus-client, which incus depends on at its exact version): a version is a
#                     candidate, and a pin confirmed, only when every one of them is listed at it
#   source.variable   the env file's variable that holds the version
#   source.note       optional text the item's PR body carries
# A version is Zabbly's form of a Debian version without its epoch, <upstream>-<label>-<build stamp>
# (7.6-ubuntu24.04-202611072126): X.Y or X.Y.Z, a lowercase label with digits and dots, and a 12-digit
# UTC build time. Each rebuild is a version of its own, after the one it rebuilds, so a repository that
# keeps only its newest builds proposes a rebuild instead of dropping the pin. The series is X.Y, and
# a version is dated by its build stamp. A version written with another epoch than the repository's is
# a field the check fails. The metadata is read and verified once per run, and every URL is built
# from the manifest, never from the metadata.

APT_FILE="$(item_first "$ITEM" files)"
_src() { yq -r ".items[\"$ITEM\"].source$1 // \"\"" "$MANIFEST"; }
_fprs() { { item "$ITEM" verify.fingerprint; item_list "$ITEM" verify.fingerprints; } | grep . || true; }
_key() { printf '%s/%s' "$REPO_ROOT" "$(item "$ITEM" verify.key)"; }
APT_VERSION_RE='^([0-9]+\.[0-9]+(\.[0-9]+)?)-[a-z]+[0-9.]*-([0-9]{12})$'
_shape() { [[ "$1" =~ $APT_VERSION_RE ]]; }
_index() { printf '%s/binary-%s/Packages.gz' "$(_src .component)" "$(_src .arch)"; }
_dists() { printf '%sdists/%s/' "$(_src .repo)" "$(_src .suite)"; }

# ---- the pin ---------------------------------------------------------------------------------------

# _pinned: "<epoch>\t<version>" of the file's one line for the variable.
_pinned() {
    VAR="$(_src .variable)" perl -ne 'if (/^\Q$ENV{VAR}\E=(.*)$/) { $n++; $v = $1 } END { print "", ($n // 0), "\n", ($v // ""), "\n" }' "$TREE/$APT_FILE" \
        | { read -r n; IFS= read -r v; [[ "$n" == 1 ]] || die "$APT_FILE sets $(_src .variable) $n times, not once";
            [[ "$v" =~ ^([0-9]+):(.+)$ ]] || die "$APT_FILE: $(_src .variable) is not <epoch>:<version>: '$v'"
            local e="${BASH_REMATCH[1]}" ver="${BASH_REMATCH[2]}"
            _shape "$ver" || die "$APT_FILE: $(_src .variable) is not a version of $ITEM: '$v'"
            printf '%s\t%s' "$e" "$ver"; }
}

src_current() { local p; p="$(_pinned)" || exit 1; printf '%s' "$(cut -f2 <<<"$p")"; }
src_series() { [[ "$1" =~ $APT_VERSION_RE ]] || die "not a version of $ITEM: '$1'"; printf '%s' "${BASH_REMATCH[1]}" | cut -d. -f1-2; }
src_verifier() { echo signed-file; }
src_first_party() { return 1; }

src_fields() {
    local p
    p="$(_pinned)" || return 1
    printf 'version=%s\nepoch=%s\n' "$(cut -f2 <<<"$p")" "$(cut -f1 <<<"$p")"
}

# Oldest first: the upstream version by number, then the build stamp.
src_order() {
    perl -ne 'chomp; next unless /^(\d+)\.(\d+)(?:\.(\d+))?-[a-z]+[0-9.]*-(\d{12})$/; push @v, [$_, $1, $2, $3 // -1, $4];
        END { print "$_->[0]\n" for sort { $a->[1] <=> $b->[1] || $a->[2] <=> $b->[2] || $a->[3] <=> $b->[3] || $a->[4] cmp $b->[4] } @v }'
}

# ---- the repository --------------------------------------------------------------------------------

_cached() { printf '%s/dl-%s' "$UPSTREAM_CACHE" "$(printf '%s' "$1" | sha256sum | cut -c1-24)"; }
_get() {
    local f
    f="$(_cached "$1")"
    [[ -s "$f" ]] || fetch "$1" "$f" || return 1
    printf '%s' "$f"
}

_with() { yq -r ".items[\"$ITEM\"].source.with // [] | .[]" "$MANIFEST"; }

# _chain: the arch's Packages.gz, downloaded and checked: the signed Release verified, of the suite
# asked for, and hashing it.
_chain() {
    local base rel sig sum gz fprs
    base="$(_dists)"
    rel="$(_get "${base}Release")" || exit 1
    sig="$(_get "${base}Release.gpg")" || exit 1
    mapfile -t fprs < <(_fprs)
    verify_signed "$(_key)" "$sig" "$rel" "${fprs[@]}"
    sum="$(SUITE="$(_src .suite)" INDEX="$(_index)" perl "$ENGINE_DIR/apt-repo.pl" release "$rel")" || die "${base}Release: cannot read it"
    gz="$(_get "$base$(_index)")" || exit 1
    [[ "$(sha256sum "$gz" | cut -d' ' -f1)" == "$sum" ]] || die "$base$(_index) does not hash to the sha256 the signed Release gives"
    printf '%s' "$gz"
}

# _listing: a file of "<package>\t<epoch>\t<version>" lines, the entries of the package and of those
# it is installed with in the arch's Packages.gz, read only once its chain from the signed Release
# checked. The three files are separate downloads, so a republish between them fails the chain
# although nothing was tampered with: a chain that fails is downloaded once more, and fails the run
# only when it fails again.
_listing() {
    local out="$UPSTREAM_CACHE/apt-$ITEM.tsv" base gz txt first u
    [[ -f "$out" ]] && { printf '%s' "$out"; return; }
    base="$(_dists)"
    first="$(mktemp -p "$UPSTREAM_CACHE")"
    if ! gz="$(_chain 2> "$first")"; then
        log "$ITEM: the repository's chain did not check, so it is downloaded again: $(grep -m1 . "$first" || true)"
        for u in "${base}Release" "${base}Release.gpg" "$base$(_index)"; do rm -f "$(_cached "$u")"; done
        gz="$(_chain)" || { rm -f "$first"; exit 1; }
    fi
    rm -f "$first"
    txt="$(mktemp -p "$UPSTREAM_CACHE")"
    gzip -dc "$gz" > "$txt" || { rm -f "$txt"; die "$base$(_index) is not gzip"; }
    PACKAGES="$(_src .package) $(_with | paste -sd' ' -)" ARCH="$(_src .arch)" perl "$ENGINE_DIR/apt-repo.pl" packages "$txt" > "$out.tmp" \
        || { rm -f "$out.tmp" "$txt"; die "$base$(_index): cannot read it"; }
    rm -f "$txt"
    mv "$out.tmp" "$out"
    printf '%s' "$out"
}

# _epoch <version>: the epoch the repository lists the version with, or nothing when it does not list
# it, or does not list every package of source.with at the same epoch and version.
_epoch() {
    local f e p
    f="$(_listing)" || exit 1
    e="$(awk -F'\t' -v p="$(_src .package)" -v v="$1" '$1 == p && $3 == v { print $2; exit }' "$f")"
    [[ -n "$e" ]] || return 0
    for p in $(_with); do
        awk -F'\t' -v p="$p" -v e="$e" -v v="$1" '$1 == p && $2 == e && $3 == v { found = 1 } END { exit !found }' "$f" || return 0
    done
    printf '%s' "$e"
}

src_releases() {
    local f v
    f="$(_listing)" || exit 1
    awk -F'\t' -v p="$(_src .package)" '$1 == p { print $3 }' "$f" | sort -u | while read -r v; do
        # an if, not &&: a loop whose last test fails would fail the pipeline, and the whole watch
        if _shape "$v" && [[ -n "$(_epoch "$v")" ]]; then echo "$v"; fi
    done | src_order
}

src_released_at() {
    [[ -n "$(_epoch "$1")" ]] || die "the repository of $ITEM does not list $1"
    [[ "$1" =~ $APT_VERSION_RE ]] || die "not a version of $ITEM: '$1'"
    local s="${BASH_REMATCH[3]}"
    date -u -d "${s:0:4}-${s:4:2}-${s:6:2} ${s:8:2}:${s:10:2}" +%s
}

# The pinned version must still be listed: a version the repository dropped is a pin-mismatch issue,
# never a failed run. 2 when the repository could not be read. Every failure is tested by hand: check
# calls this under ||, where errexit is off.
src_confirms() {
    local e
    e="$(_epoch "$1")" || { log "ERROR: $ITEM: cannot read its repository"; return 2; }
    # Zabbly keeps only its newest builds, so a dropped pin is routine there: the issue names the
    # build a person applies, since the watch proposes nothing until the pin matches again.
    if [[ -z "$e" ]]; then
        local newest pkgs
        newest="$(src_releases | tail -1)"
        pkgs="$({ _src .package; _with; } | paste -sd, - | sed 's/,/, /g')"
        if [[ -n "$newest" ]]; then
            log "$ITEM: the repository no longer lists the pinned $1 for every one of $pkgs; its newest build is $newest, which \`upstream/engine/upstream.sh apply $ITEM <version>\` pins"
        else
            log "$ITEM: the repository no longer lists the pinned $1 for every one of $pkgs, and lists no build that has them all"
        fi
        return 1
    fi
    return 0
}

src_apply() {
    local version="$1" e p f="$TREE/$APT_FILE" var
    _shape "$version" || die "not a version of $ITEM: '$version'"
    e="$(_epoch "$version")" || exit 1
    [[ -n "$e" ]] || die "the repository of $ITEM does not list $version"
    p="$(_pinned)" || exit 1
    var="$(_src .variable)"
    replace_once "$f" "$var=$(cut -f1 <<<"$p"):$(cut -f2 <<<"$p")" "$var=$e:$version"
    [[ "$(_pinned)" == "$e"$'\t'"$version" ]] || die "failed to rewrite $ITEM's pin"
}

# ---- manifest rules --------------------------------------------------------------------------------

src_validate() {
    [[ "$(_src .repo)" =~ ^https://[A-Za-z0-9.-]+/[A-Za-z0-9._/~-]*/$ ]] || die "manifest: item $ITEM: source.repo must be an https URL ending in /"
    local k
    for k in suite component arch package; do
        [[ "$(_src ".$k")" =~ ^[a-z0-9][a-z0-9+._-]*$ ]] || die "manifest: item $ITEM: source.$k is not a plain name"
    done
    for k in $(_with); do
        [[ "$k" =~ ^[a-z0-9][a-z0-9+._-]*$ && "$k" != "$(_src .package)" ]] || die "manifest: item $ITEM: source.with names '$k', not another package"
    done
    [[ "$(_src .variable)" =~ ^[A-Z][A-Z0-9_]*$ ]] || die "manifest: item $ITEM: source.variable is not an env variable name"
    [[ "$(item_list "$ITEM" files | wc -l)" == 1 ]] || die "manifest: item $ITEM: an apt-repo item pins one file"
    src_current > /dev/null
}

src_describe() {
    local note
    note="$(_src .note)"
    cat <<MD
Bumps \`$ITEM\` to $1, the newest build of \`$(_src .package)\` that the repository's \`$(_src .suite)\` suite lists for \`$(_src .arch)\`.

The version is listed in the suite's \`$(_index)\`, which hashes to the sha256 the suite's \`Release\` file gives it, whose OpenPGP signature the engine checked against \`$(item "$ITEM" verify.key)\`, accepting a signer only if its primary key is one of the item's pinned fingerprints. That is the chain apt follows when it installs the package; no package is downloaded here:
- $(_dists)Release
${note:+
$note
}
MD
}
