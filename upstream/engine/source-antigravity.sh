# Source type antigravity (#2366): Antigravity CLI (agy), pinned in AntigravitySetup.java. Google
# publishes no signature and no sha256, only a sha512 per download, in two places this reads:
#   - the release manifests its installer and agy's self-updater read, one per platform
#     (<updater>/manifests/linux_amd64.json), which name the one stable version, its URL and its sha512;
#   - a manifest.json beside each build's tarball in the bucket, of the same shape, which stays when
#     the stable version moves on.
# The candidate is always the stable version, and only when both Linux manifests name the same build;
# a build listed nowhere else counts for nothing. A version the stable manifests no longer name is
# re-read only at the build the tree itself pins, from its manifest.json. The sha256 isx pins is the
# engine's own hash of the download, after the download matched the sha512, so every new sum costs a
# download (two tarballs, about 100 MB each); with UPSTREAM_REHASH=0 the tree's own pin keeps its sum.
# The release date is the bucket's timeCreated for the later of the two tarballs. The series is the
# major version.

JAVA_FILE="$(item_list "$ITEM" files | head -1)"
_UPDATER=https://antigravity-cli-auto-updater-974169037036.us-central1.run.app
_BUCKET=https://storage.googleapis.com/antigravity-public/antigravity-cli/
_OBJECTS=https://storage.googleapis.com/storage/v1/b/antigravity-public/o/antigravity-cli%2F
_ARCHES="x86_64 aarch64"
# _platform <arch>: the installer's name for it; _path <arch>: the tarball under a build's directory;
# _const <arch>: the Java constant that holds its Download.
_platform() { case "$1" in x86_64) echo linux_amd64 ;; aarch64) echo linux_arm64 ;; esac; }
_path() { case "$1" in x86_64) echo linux-x64/cli_linux_x64.tar.gz ;; aarch64) echo linux-arm/cli_linux_arm64.tar.gz ;; esac; }
_const() { case "$1" in x86_64) echo X86_64 ;; aarch64) echo AARCH64 ;; esac; }
_numeric() { [[ "$1" =~ ^[0-9]+(\.[0-9]+)+$ ]]; }
_build_ok() { [[ "$2" =~ ^[0-9]+(\.[0-9]+)+-[0-9]+$ && "${2%-*}" == "$1" ]]; }

# _java <perl regex with one group>: the group's one match in the Java file, which must match once.
_java() {
    RE="$1" perl -0ne 'my @m = /$ENV{RE}/mg; print scalar(@m), "\n", (@m ? $m[0] : ""), "\n"' "$TREE/$JAVA_FILE" \
        | { read -r n; read -r v; [[ "$n" == 1 ]] || die "$JAVA_FILE matches '$1' $n times, not once"; printf '%s' "$v"; }
}
_version() { _java '^    static final String VERSION = "([^"\n]*)";$'; }
_build() { _java '^    private static final String BUILD = "([^"\n]*)";$'; }
_sum() {
    local p; p="$(_path "$1")"
    _java "^    static final Download $(_const "$1") = new Download\\(DOWNLOAD_BASE \\+ \"/${p//./\\.}\",\\n {12}\"([0-9a-f]{64})\"\\);$"
}

# The Java file must build each URL as the bucket, the build and the arch's path, which this reads.
src_validate() {
    _java '^    private static final String DOWNLOAD_BASE = ("https://storage\.googleapis\.com/antigravity-public/antigravity-cli/") \+ BUILD;$' > /dev/null
    _build_ok "$(src_current)" "$(_build)" || die "$JAVA_FILE's BUILD is not VERSION-<digits>"
    local a; for a in $_ARCHES; do _sum "$a" > /dev/null; done
}

src_current() {
    local v; v="$(_version)"
    _numeric "$v" || die "cannot read agy's version from $JAVA_FILE"
    printf '%s' "$v"
}
src_series() { printf '%s' "${1%%.*}"; }
src_verifier() { echo publisher-sums; }
src_first_party() { return 1; }

# _json <url>: a downloaded JSON file, once per run.
_json() {
    local f="$UPSTREAM_CACHE/agy-$(printf '%s' "$1" | sha256sum | cut -c1-16).json"
    [[ -s "$f" ]] || fetch "$1" "$f"
    jq -e 'type == "object"' "$f" > /dev/null 2>&1 || die "$1 is not a JSON object"
    printf '%s' "$f"
}

# _stable: "<version> <build>" of the stable release, which both Linux manifests must name alike.
# Nothing while they name two builds, as during a rollout that reached one platform first: the item
# then waits, and the rest of the watch goes on.
_stable() {
    local a f v u b want="" have
    for a in $_ARCHES; do
        # Checked here, not left to errexit: check reads this under ||, where errexit is off.
        f="$(_json "$_UPDATER/manifests/$(_platform "$a").json")" || exit 1
        v="$(jq -r '.version // empty' "$f")"; u="$(jq -r '.url // empty' "$f")"
        _numeric "$v" || die "the $(_platform "$a") manifest names no version: '$v'"
        b="${u#"$_BUCKET"}"; b="${b%%/*}"
        [[ "$u" == "$_BUCKET$b/$(_path "$a")" ]] && _build_ok "$v" "$b" \
            || die "the $(_platform "$a") manifest's URL is not a build of $v under $_BUCKET: $u"
        have="$v $b"
        if [[ -n "$want" && "$want" != "$have" ]]; then
            log "antigravity: the Linux manifests name different releases, $want and $have, so none is a candidate yet"
            return 0
        fi
        want="$have"
    done
    printf '%s' "$want"
}

# The stable version, or while the manifests disagree the pin itself, which proposes nothing.
src_releases() {
    local s; s="$(_stable)" || exit 1
    if [[ -n "$s" ]]; then printf '%s\n' "${s%% *}"; else src_current; echo; fi
}

# The pin against the stable release: 1 when both Linux manifests name a version older than the pin,
# a release Google rolled back, which check turns into a pin-mismatch issue (the pinned build's
# manifest.json stays in the bucket, so the rest of check would pass); 0 when either names the pin or
# a newer version; 2 when one could not be read, which fails the run. Each manifest is read on its own,
# so a rollback is caught during a split rollout too. Every read is checked here, not left to errexit:
# check calls this under ||, where errexit is off.
src_confirms() {
    local a f v older=0
    for a in $_ARCHES; do
        f="$(_json "$_UPDATER/manifests/$(_platform "$a").json")" \
            || { log "ERROR: $ITEM: cannot read Google's stable manifests"; return 2; }
        v="$(jq -r '.version // empty' "$f")" || return 2
        _numeric "$v" || { log "ERROR: $ITEM: the $(_platform "$a") manifest names no version: '$v'"; return 2; }
        ! is_newer "$1" "$v" || older=$((older + 1))
    done
    if (( older == 2 )); then
        log "$ITEM: Google's stable manifests name a version older than the pinned $1: a release rolled back"
        return 1
    fi
    return 0
}

# _build_of <version>: its build, from the stable manifests or else from the tree's own pin.
_build_of() {
    local s; s="$(_stable)" || exit 1
    if [[ -n "$s" && "$1" == "${s%% *}" ]]; then printf '%s' "${s#* }"; return; fi
    if [[ "$1" != "$(src_current)" ]]; then
        [[ -n "$s" ]] || die "Google's Linux manifests name two builds, so agy $1 is not the stable release, and $JAVA_FILE does not pin it"
        die "Google's manifests name agy ${s%% *}, not $1, and $JAVA_FILE does not pin it"
    fi
    _build_ok "$1" "$(_build)" || die "$JAVA_FILE's BUILD is not $1-<digits>"
    _build
}

src_released_at() {
    local b a t s j latest=0
    b="$(_build_of "$1")" || exit 1
    for a in $_ARCHES; do
        j="$(_json "$_OBJECTS$b%2F$(_path "$a" | sed 's|/|%2F|g')")" || exit 1
        t="$(jq -r '.timeCreated // empty' "$j")"
        [[ "$t" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z$ ]] || die "no creation time for agy $b's $a tarball"
        s="$(date -u -d "$t" +%s)"
        (( s > latest )) && latest="$s"
    done
    printf '%s' "$latest"
}

# _sha512 <version> <build> <arch>: Google's sum of the arch's tarball, from the build's own
# manifest.json, which must name this version and URL, and equal the stable manifest's for the stable build.
_sha512() {
    local v="$1" b="$2" a="$3" f url sum stable s m
    url="$_BUCKET$b/$(_path "$a")"
    f="$(_json "$_BUCKET$b/$(dirname "$(_path "$a")")/manifest.json")" || exit 1
    [[ "$(jq -r '.version // empty' "$f")" == "$v" && "$(jq -r '.url // empty' "$f")" == "$url" ]] \
        || die "agy $b's $a manifest.json does not name $v at $url"
    sum="$(jq -r '.sha512 // empty' "$f")"
    is_sha512 "$sum" || die "agy $b's $a manifest.json holds no sha512"
    # Read before the comparison: a failed read inside [[ ]] would pass as "not the stable build" and
    # skip the cross-check below.
    s="$(_stable)" || exit 1
    if [[ "$s" == "$v $b" ]]; then
        m="$(_json "$_UPDATER/manifests/$(_platform "$a").json")" || exit 1
        stable="$(jq -r '.sha512 // empty' "$m")"
        [[ "$stable" == "$sum" ]] || die "agy $b's $a manifest.json and the $(_platform "$a") manifest give different sha512s"
    fi
    printf '%s' "$sum"
}

# _sha256 <version> <build> <arch>: the sha256 of the download, which must hash to Google's sha512.
# Under UPSTREAM_REHASH=0 the tree's own pin keeps its sum without a download.
_sha256() {
    local v="$1" b="$2" a="$3" sha512 f have
    # Checked here: check reads this under ||, where errexit is off, and the pin's own sum below would
    # otherwise stand in for a manifest.json that is gone or disagrees.
    sha512="$(_sha512 "$v" "$b" "$a")" || exit 1
    if [[ "${UPSTREAM_REHASH:-1}" == 0 && "$v" == "$(src_current)" && "$b" == "$(_build)" ]]; then
        _sum "$a"; return
    fi
    # In the run's cache, which the engine removes on exit, so a failed download leaves no tarball behind.
    f="$(mktemp -p "$UPSTREAM_CACHE")"
    fetch "$_BUCKET$b/$(_path "$a")" "$f"
    have="$(sha512sum "$f" | cut -d' ' -f1)"
    [[ "$have" == "$sha512" ]] || { rm -f "$f"; die "agy $b's $a tarball hashes to $have, but Google's manifest gives $sha512"; }
    have="$(sha256sum "$f" | cut -d' ' -f1)"
    rm -f "$f"
    printf '%s' "$have"
}

src_apply() {
    local version="$1" b a f="$TREE/$JAVA_FILE" cur
    local -A sums=()
    _numeric "$version" || die "not an agy version: '$version'"
    b="$(_build_of "$version")" || exit 1
    for a in $_ARCHES; do
        sums[$a]="$(_sha256 "$version" "$b" "$a")"
        is_sha256 "${sums[$a]}" || die "agy $version: no sum for $a"
    done
    cur="$(src_current)"
    replace_once "$f" "    static final String VERSION = \"$cur\";" "    static final String VERSION = \"$version\";"
    replace_once "$f" "    private static final String BUILD = \"$(_build)\";" "    private static final String BUILD = \"$b\";"
    for a in $_ARCHES; do replace_once "$f" "\"$(_sum "$a")\");" "\"${sums[$a]}\");"; done
    [[ "$(src_current)" == "$version" && "$(_build)" == "$b" ]] || die "failed to rewrite $JAVA_FILE"
    for a in $_ARCHES; do [[ "$(_sum "$a")" == "${sums[$a]}" ]] || die "failed to rewrite $JAVA_FILE"; done
}

# Each value is read before anything is printed, and checked, so a tree whose lines cannot be read
# fails here even under _narrow's if, where errexit is off (_narrow counts such a tree as changed);
# printf would ignore a failed $(...).
src_fields() {
    local v b x a
    v="$(src_current)" || return 1; b="$(_build)" || return 1
    x="$(_sum x86_64)" || return 1; a="$(_sum aarch64)" || return 1
    printf 'version=%s\nbuild=%s\nsha256.x86_64=%s\nsha256.aarch64=%s\n' "$v" "$b" "$x" "$a"
}

src_describe() {
    cat <<MD
Bumps \`$ITEM\` to agy $1, the stable release Google's installer reads from its Linux manifests (\`$_UPDATER/manifests/linux_amd64.json\` and \`linux_arm64.json\`).

Google publishes a sha512 for each tarball and no signature. The engine checked that both manifests name the same build, that the build's own \`manifest.json\` in the bucket gives the same sha512, downloaded both tarballs and checked them against it, then pinned their sha256. The sum comes from Google over HTTPS, so it proves consistency and transfer, not provenance. Release notes: https://antigravity.google/docs/changelog/

A person merges this PR: isx's delegation parses what agy prints and the conversations it writes, and no CI job runs agy against a model.
MD
}
