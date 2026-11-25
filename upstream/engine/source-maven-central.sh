# Source type maven-central (#2210): versions of artifacts on Maven Central that the poms pin, by a
# property (<aesh.version>3.18.5</aesh.version>) or beside an artifactId. An item has one or more
# source.members, each of them a set of artifacts released together under one version:
#   source.members.<key>.artifacts  groupId:artifactId of every artifact the version drives; each must
#                                   be on Central at a version before it counts (aesh and its processor)
#   source.members.<key>.pins       file, text and optional count (default 1): a literal text of the
#                                   file with {version} where the version is (pins.pl), every
#                                   occurrence read, agreeing and rewritten
# One member's version is the version (3.19.1). A family's names each member's version in the members'
# order, as <key>.<version> joined by _ (compiler.3.16.0_surefire.3.6.1), and moves them as one PR.
# The proof is central-exists: Central's listing of every artifact names the version, and the pom it
# serves there hashes to the .sha1 beside it. That is Central's record of a release, not a signature,
# and not what the code does, so validate refuses it on a mechanical item: a person merges every bump.
# A version is a release only in the shape of a dotted number (3.6.0-M1, 4.0.0.CR1 never count), and
# the date of its directory in the listing dates it, the latest of its artifacts'. A family proposes,
# for each member, its newest release past cooling within its major, and opens the new-series issue
# for one beyond it, so a member's new major never holds the others back.
# Every ${property} a pin defines must version only the member's artifacts in the item's files, and
# appear in no other pom, so a dependency added on the same property is either declared and checked,
# or fails validate.

MC_BASE=https://repo.maven.apache.org/maven2
MC_KEY_RE='^[a-z0-9]+(-[a-z0-9]+)*$'
MC_GA_RE='^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$'
MC_VERSION_RE='^[0-9]+(\.[0-9]+)*$'

_mc() { yq -r ".items[\"$ITEM\"].source$1 // \"\"" "$MANIFEST"; }
# The members in the manifest's order, which is the order of a family's version.
_mkeys() { yq -r ".items[\"$ITEM\"].source.members // {} | to_entries | .[] | .key" "$MANIFEST"; }
_family() { [[ "$(_mkeys | wc -l)" -gt 1 ]]; }
_artifacts() { yq -r ".items[\"$ITEM\"].source.members[\"$1\"].artifacts // [] | .[]" "$MANIFEST"; }
_npins() { yq -r ".items[\"$ITEM\"].source.members[\"$1\"].pins // [] | length" "$MANIFEST"; }
_pin() { local v; v="$(_mc ".members[\"$1\"].pins[$2][\"$3\"]")"; printf '%s' "${v:-${4:-}}"; }
_release() { [[ "$1" =~ $MC_VERSION_RE ]]; }
_major() { printf '%s' "${1%%.*}"; }
# _dir <groupId:artifactId>: the artifact's directory on Central.
_dir() { local g="${1%%:*}"; printf '%s/%s/%s' "$MC_BASE" "${g//.//}" "${1#*:}"; }

# _get <url>: the download, once per run.
_get() {
    local f="$UPSTREAM_CACHE/mc-$(printf '%s' "$1" | sha256sum | cut -c1-24)"
    [[ -s "$f" ]] || fetch "$1" "$f"
    printf '%s' "$f"
}

# ---- reading the pins ------------------------------------------------------------------------------

# _member_current <key>: the one version every occurrence of the member's pins agrees on.
_member_current() {
    local key="$1" i n out count v="" all=""
    n="$(_npins "$key")"
    (( n > 0 )) || die "$ITEM: member $key has no pins"
    for ((i = 0; i < n; i++)); do
        out="$(TEXT="$(_pin "$key" "$i" text)" perl "$ENGINE_DIR/pins.pl" read "$TREE/$(_pin "$key" "$i" file)")" \
            || die "$ITEM: cannot read pin $i of $key in $(_pin "$key" "$i" file)"
        count="$(_pin "$key" "$i" count 1)"
        [[ "$(head -1 <<<"$out")" == "count=$count" ]] \
            || die "$(_pin "$key" "$i" file) holds pin $i of $ITEM's $key $(head -1 <<<"$out" | cut -d= -f2) times, not $count"
        all+="$(tail -n +2 <<<"$out" | sed -n 's/^version=//p')"$'\n'
    done
    v="$(grep . <<<"$all" | sort -u)"
    [[ -n "$v" && "$(wc -l <<<"$v")" == 1 ]] || die "$ITEM: the pins of $key do not agree on one version: $(tr '\n' ' ' <<<"$v")"
    _release "$v" || die "$ITEM: $key pins '$v', not a release version"
    printf '%s' "$v"
}

# _parse <version>: "key<TAB>version" per member, in the members' order; anything else fails. Callers
# read it into a variable with || exit, never loop over < <(_parse ...): errexit does not see a
# process substitution fail, so a refused version would read as no members (and date 0, cooled).
_parse() {
    local part key v out="" parts
    is_version "$1" || die "not a version: '$1'"
    if ! _family; then
        _release "$1" || die "$ITEM: '$1' is not a release version"
        printf '%s\t%s\n' "$(_mkeys)" "$1"
        return 0
    fi
    IFS=_ read -ra parts <<<"$1"
    for part in "${parts[@]}"; do
        key="${part%%.*}"; v="${part#*.}"
        [[ "$part" == *.* && "$key" =~ $MC_KEY_RE ]] && _release "$v" || die "$ITEM: '$part' of '$1' is not <key>.<version>"
        out+="$key"$'\t'"$v"$'\n'
    done
    [[ "$(cut -f1 <<<"${out%$'\n'}")" == "$(_mkeys)" ]] || die "$ITEM: '$1' does not name each member once, in order: $(_mkeys | tr '\n' ' ')"
    printf '%s' "$out"
}

# _join: "key<TAB>version" lines on stdin, as the item's version.
_join() {
    if _family; then
        awk -F'\t' '{ printf "%s%s.%s", (NR > 1 ? "_" : ""), $1, $2 }'
    else
        cut -f2 | tr -d '\n'
    fi
}

src_current() {
    local key out=""
    for key in $(_mkeys); do out+="$key"$'\t'"$(_member_current "$key")"$'\n'; done
    printf '%s' "$out" | _join
}

src_fields() {
    local key v
    for key in $(_mkeys); do
        v="$(_member_current "$key")"
        printf '%s.version=%s\n' "$key" "$v"
    done
}

src_series() {
    local key v out="" parsed
    parsed="$(_parse "$1")" || exit 1
    while IFS=$'\t' read -r key v; do out+="$key"$'\t'"$(_major "$v")"$'\n'; done <<<"$parsed"
    printf '%s' "$out" | _join
}

# Oldest first. A family's versions all name the same members, so they compare member by member.
src_order() {
    if ! _family; then sort -V; return; fi
    local v key m n parsed
    while read -r v; do
        [[ -n "$v" ]] || continue
        n=""
        parsed="$(_parse "$v")" || exit 1
        while IFS=$'\t' read -r key m; do
            n+="$(awk -F. '{ for (i = 1; i <= 8; i++) printf "%012d", (i <= NF ? $i : 0) }' <<<"$m")"
        done <<<"$parsed"
        printf '%s\t%s\n' "$n" "$v"
    done | sort -t$'\t' -k1,1 | cut -f2
}

src_verifier() { echo central-exists; }
src_first_party() { return 1; }

# ---- Central ---------------------------------------------------------------------------------------

# _listing <groupId:artifactId>: "<version>\t<epoch>" of every release Central's listing dates. The
# download is read into a variable first: a die inside an argument's $(...) is lost, and the run
# would go on to fail on an empty path rather than on the download.
_listing() {
    local v d f
    f="$(_get "$(_dir "$1")/")" || exit 1
    REGEX='([0-9]+(?:\.[0-9]+)*)/' perl "$ENGINE_DIR/index.pl" "$f" \
        | while IFS=$'\t' read -r v d; do
            _release "$v" && [[ -n "$d" ]] || continue
            printf '%s\t%s\n' "$v" "$(date -u -d "$d UTC" +%s)"
        done
}

# _member_releases <key>: "<version>\t<epoch>" of every version all the member's artifacts are released
# at, dated by the latest of them, oldest first. Read into a variable first, so a failed read of
# Central fails the run rather than reading as no release.
_member_releases() {
    local a l all="" n=0
    for a in $(_artifacts "$1"); do
        l="$(_listing "$a")" || exit 1
        all+="$l"$'\n'
        n=$((n + 1))
    done
    awk -F'\t' -v n="$n" 'NF == 2 { c[$1]++; if ($2 > d[$1]) d[$1] = $2 } END { for (v in c) if (c[v] == n) print v "\t" d[v] }' <<<"$all" | sort -V
}

# _member_cooled <key>: its releases past the item's cooling, oldest first.
_member_cooled() {
    local secs rel
    secs="$(duration_seconds "$(item "$ITEM" cooling)")"
    rel="$(_member_releases "$1")" || exit 1
    awk -F'\t' -v lim="$(( $(date -u +%s) - secs ))" '$2 <= lim { print $1 }' <<<"$rel"
}

# One member: every release. A family: each member at its newest cooled release within its major, and
# each at its newest cooled release outright, never older than its pin; the second differs from the
# first only when a member has a new major, which plan makes the new-series issue.
src_releases() {
    if ! _family; then
        _member_releases "$(_mkeys)" | cut -f1
        return 0
    fi
    local key cur cooled in out="" top="" pick parsed
    parsed="$(_parse "$(src_current)")" || exit 1
    while IFS=$'\t' read -r key cur; do
        cooled="$(_member_cooled "$key")"
        pick="$(awk -v m="$(_major "$cur")." 'index($0, m) == 1' <<<"$cooled" | tail -1)"
        [[ -n "$pick" ]] && is_newer_v "$pick" "$cur" || pick="$cur"
        in+="$key"$'\t'"$pick"$'\n'
        pick="$(tail -1 <<<"$cooled")"
        [[ -n "$pick" ]] && is_newer_v "$pick" "$cur" || pick="$cur"
        top+="$key"$'\t'"$pick"$'\n'
    done <<<"$parsed"
    { printf '%s' "$in" | _join; echo; printf '%s' "$top" | _join; echo; } | sort -u | src_order
}

# is_newer_v <a> <b>: dotted version a is strictly newer than b.
is_newer_v() { [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" == "$1" ]]; }

# The latest date of the member versions it names; one Central does not date is not cooled.
src_released_at() {
    local key v at rel max=0 parsed
    parsed="$(_parse "$1")" || exit 1
    while IFS=$'\t' read -r key v; do
        rel="$(_member_releases "$key")" || exit 1
        at="$(awk -F'\t' -v v="$v" '$1 == v { print $2 }' <<<"$rel")"
        [[ -n "$at" ]] || die "$ITEM: Central does not list $key $v for every one of its artifacts"
        (( at > max )) && max="$at"
    done <<<"$parsed"
    printf '%s' "$max"
}

# _exists <groupId:artifactId> <version>: Central's listing names the version, and (unless
# UPSTREAM_REHASH=0) the pom there hashes to the .sha1 Central lists beside it.
_exists() {
    local a="$1" v="$2" url pom sha rel f
    rel="$(_listing "$a")" || exit 1
    awk -F'\t' -v v="$v" '$1 == v { found = 1 } END { exit !found }' <<<"$rel" \
        || die "$ITEM: Central lists no release $v of $a"
    [[ "${UPSTREAM_REHASH:-1}" != 0 ]] || return 0
    url="$(_dir "$a")/$v/${a#*:}-$v.pom"
    pom="$(_get "$url")" || exit 1
    f="$(_get "$url.sha1")" || exit 1
    sha="$(head -c 40 "$f")"
    [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die "$ITEM: $url.sha1 is not a sha1"
    [[ "$(sha1sum "$pom" | cut -d' ' -f1)" == "$sha" ]] || die "$ITEM: $url does not hash to the sha1 Central lists for it"
}

src_apply() {
    local key v cur a i n parsed
    local -A want=()
    parsed="$(_parse "$1")" || exit 1
    while IFS=$'\t' read -r key v; do want[$key]="$v"; done <<<"$parsed"
    for key in $(_mkeys); do
        cur="$(_member_current "$key")"
        v="${want[$key]}"
        # What plan proposes never moves a member back, so neither does a bump.
        [[ "$v" == "$cur" ]] || is_newer_v "$v" "$cur" || die "$ITEM: $key would move back from $cur to $v"
        for a in $(_artifacts "$key"); do _exists "$a" "$v"; done
        [[ "$v" != "$cur" ]] || continue
        n="$(_npins "$key")"
        for ((i = 0; i < n; i++)); do
            TEXT="$(_pin "$key" "$i" text)" COUNT="$(_pin "$key" "$i" count 1)" VERSION="$v" \
                perl "$ENGINE_DIR/pins.pl" write "$TREE/$(_pin "$key" "$i" file)" || die "$ITEM: cannot rewrite pin $i of $key"
        done
    done
    [[ "$(src_current)" == "$1" ]] || die "failed to rewrite $ITEM to $1"
}

# ---- validate ----------------------------------------------------------------------------------------

# _entries <file>: "groupId:artifactId<TAB>version" of every plugin, dependency and processor path of
# the pom, as yq reads its XML (a plugin with no groupId is Maven's own). Only read, never written:
# yq would not write the file back byte for byte.
_entries() {
    yq -p xml -o json '.' "$1" | jq -r '
        [paths(objects and has("artifactId"))] as $ps
        | $ps[] as $p | getpath($p) as $e
        | ([$p[] | strings] | last) as $kind
        | select($kind != "exclusion" and ($e.version | type) == "string")
        | "\($e.groupId // (if $kind == "plugin" then "org.apache.maven.plugins" else "" end)):\($e.artifactId)\t\($e.version)"'
}

src_validate() {
    local key a i n f text prop uses users all_entries others
    [[ -n "$(_mkeys)" ]] || die "manifest: item $ITEM has no source.members"
    for key in $(_mkeys); do
        [[ "$key" =~ $MC_KEY_RE ]] || die "manifest: item $ITEM: member key '$key' is not [a-z0-9-]"
        [[ -n "$(_artifacts "$key")" ]] || die "manifest: item $ITEM: member $key lists no artifacts"
        for a in $(_artifacts "$key"); do
            [[ "$a" =~ $MC_GA_RE ]] || die "manifest: item $ITEM: member $key: '$a' is not groupId:artifactId"
        done
        n="$(_npins "$key")"
        (( n > 0 )) || die "manifest: item $ITEM: member $key has no pins"
        for ((i = 0; i < n; i++)); do
            f="$(_pin "$key" "$i" file)"
            grep -qxF -- "$f" <<<"$(item_list "$ITEM" files)" || die "manifest: item $ITEM: member $key pins $f, which is not one of the item's files"
            text="$(_pin "$key" "$i" text)"
            [[ "$text" == *"{version}"* ]] || die "manifest: item $ITEM: pin $i of $key has no {version}"
            [[ "${text//\{version\}/}" != *[\{\}]* ]] || die "manifest: item $ITEM: pin $i of $key has a placeholder other than {version}"
        done
    done
    [[ -z "$(_mc '.members | to_entries | .[] | .value.artifacts[]' | sort | uniq -d)" ]] \
        || die "manifest: item $ITEM: an artifact belongs to two members"
    duration_seconds "$(item "$ITEM" cooling)" > /dev/null
    # A property a pin defines versions only the member's artifacts, in every file of the item.
    all_entries="$(for f in $(item_list "$ITEM" files); do _entries "$TREE/$f"; done)"
    # Any module may use a property the parent pom defines, so the poms outside the item's files are
    # read too: a use there would move with the pin, unchecked on Central.
    others="$(cd "$TREE" && find . -name pom.xml -not -path '*/target/*' -not -path './.git/*' | sed 's|^\./||' \
        | grep -vxF -f <(item_list "$ITEM" files) || true)"
    for key in $(_mkeys); do
        n="$(_npins "$key")"
        for ((i = 0; i < n; i++)); do
            text="$(_pin "$key" "$i" text)"
            [[ "$text" =~ ^\<([A-Za-z0-9._-]+)\>\{version\}\</([A-Za-z0-9._-]+)\>$ && "${BASH_REMATCH[1]}" == "${BASH_REMATCH[2]}" ]] || continue
            prop="${BASH_REMATCH[1]}"
            uses="$(awk -F'\t' -v p="\${$prop}" 'index($2, p) { print $1 }' <<<"$all_entries" | sort -u)"
            for f in $others; do
                ! grep -qF -- "\${$prop}" "$TREE/$f" || die "manifest: item $ITEM: \${$prop} is used in $f, which is not one of the item's files (add it)"
            done
            users="$(for f in $(item_list "$ITEM" files); do grep -cF -- "\${$prop}" "$TREE/$f" || true; done | awk '{ s += $1 } END { print s + 0 }')"
            [[ "$users" == "$(awk -F'\t' -v p="\${$prop}" 'index($2, p)' <<<"$all_entries" | wc -l)" ]] \
                || die "manifest: item $ITEM: \${$prop} is used where it does not version a plugin or dependency"
            while read -r a; do
                [[ -z "$a" ]] || grep -qxF -- "$a" <<<"$(_artifacts "$key")" \
                    || die "manifest: item $ITEM: \${$prop} also versions $a, which is not an artifact of member $key (add it)"
            done <<<"$uses"
        done
    done
    src_current > /dev/null
}

src_describe() {
    local key v a rows="" parsed
    parsed="$(_parse "$1")" || exit 1
    while IFS=$'\t' read -r key v; do
        for a in $(_artifacts "$key"); do
            rows+="| \`$a\` | $v | $(_dir "$a")/$v/ |"$'\n'
        done
    done <<<"$parsed"
    cat <<MD
Bumps \`$ITEM\` to $1: each member's newest release on Maven Central within its major, past the item's $(item "$ITEM" cooling) cooling.

| Artifact | Version | On Central |
|---|---|---|
${rows%$'\n'}

The proof is \`central-exists\`: Central lists every artifact at its version, and the pom there hashes to the sha1 Central lists beside it. That is Central's record of a release, not the publisher's signature, and it says nothing of what the code does, so the item is \`class: review\` and a person reads the release notes before merging (#2210).
MD
}
