# Sourced by upstream.sh and the source types: what every part of the engine knows about the manifest
# and a version (#2036, design/upstream.md). Needs bash 4+, jq, yq (mikefarah), curl, git, sort -V.
#
# A "tree" is a checkout directory the engine reads a pin from or rewrites one in; TREE names it.
# Everything an upstream sends (a version, a tag, a URL, a sum) is data: it is matched against a
# shape before it is used, and never reaches a shell line unquoted or a jq filter unbound.

set -euo pipefail
# A die inside $(...) must end the engine, not leave an empty value for the next line: that needs
# inherit_errexit, bash 4.4. An older bash (macOS ships 3.2 as /bin/bash) fails here rather than run
# without it; the engine runs on Linux CI, and a Mac runs it with Homebrew's bash on PATH.
if (( BASH_VERSINFO[0] < 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4) )); then
    echo "ERROR: the upstream engine needs bash 4.4 or later, not $BASH_VERSION" >&2
    exit 1
fi
shopt -s inherit_errexit

ENGINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The repository whose main this engine runs from (its manifest, its scripts, its keys).
ENGINE_ROOT="$(cd "$ENGINE_DIR/../.." && pwd)"
MANIFEST="${MANIFEST:-$ENGINE_ROOT/upstream/manifest.yaml}"
# The tree whose pins are read and rewritten; the engine's own checkout unless told otherwise.
TREE="${TREE:-$ENGINE_ROOT}"

log() { printf '%s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# Hex and version shapes: a value that does not match one is refused, whoever sent it.
SHA256_RE='^[0-9a-f]{64}$'
VERSION_RE='^[A-Za-z0-9][A-Za-z0-9._-]*$'
ITEM_RE='^[a-z0-9][a-z0-9-]*$'

is_sha256() { [[ "$1" =~ $SHA256_RE ]]; }
is_version() { [[ "$1" =~ $VERSION_RE ]]; }

# manifest <yq path>: a value of the manifest, "" when absent (yq prints null).
manifest() {
    local v
    v="$(yq -r "$1 // \"\"" "$MANIFEST")"
    printf '%s' "$v"
}
manifest_repo() { manifest '.repo'; }
app_login() { manifest '.app.login'; }
items() { yq -r '.items | keys | .[]' "$MANIFEST"; }
# item <item> <key> [<default>]: one scalar of an item.
item() {
    local v
    v="$(manifest ".items[\"$1\"].$2")"
    printf '%s' "${v:-${3:-}}"
}
# item_list <item> <key>: a list of an item, one entry per line.
item_list() { yq -r ".items[\"$1\"].$2 // [] | .[]" "$MANIFEST"; }
item_class() { item "$1" class judgement; }
# item_proposed <item>: the engine writes this item's bump as a PR. A mechanical one the bot may merge
# once it is proven; a review one (#2210) is proven the same way and merged by Sanne alone.
item_proposed() { local c; c="$(item_class "$1")"; [[ "$c" == mechanical || "$c" == review ]]; }
item_source_type() { item "$1" source.type; }

# duration_seconds <24h|7d|30m|0>: a cooling period in seconds; anything else fails.
duration_seconds() {
    case "$1" in
        0) echo 0 ;;
        *h) echo $(( ${1%h} * 3600 )) ;;
        *d) echo $(( ${1%d} * 86400 )) ;;
        *m) echo $(( ${1%m} * 60 )) ;;
        *) die "not a duration: '$1'" ;;
    esac
}

# newest <versions...>: the newest, by the loaded source's src_order when it defines one (oldest
# first, from stdin), else by sort -V.
newest() {
    if declare -F src_order > /dev/null; then
        printf '%s\n' "$@" | src_order | tail -1
    else
        printf '%s\n' "$@" | sort -V | tail -1
    fi
}
# is_newer <a> <b>: a is strictly newer than b.
is_newer() { [[ "$1" != "$2" && "$(newest "$1" "$2")" == "$1" ]]; }

# load_source <item>: sources the item's source type and checks it defines every function the
# engine calls; a type the engine lacks fails here, before anything is fetched.
load_source() {
    local type f
    type="$(item_source_type "$1")"
    [[ "$type" =~ ^[a-z][a-z0-9-]*$ ]] || die "item $1 has no source.type"
    f="$ENGINE_DIR/source-$type.sh"
    [[ -f "$f" ]] || die "item $1: unknown source type '$type'"
    ITEM="$1"
    # The optional hooks of the type loaded before this one must not carry over: plan asks for
    # src_extra_issues by name, and the kernel's would file its EOL issue under any later item;
    # newest asks for src_order, and the base image's would order the next item's versions.
    unset -f src_extra_issues src_stale_issues src_validate src_order
    # shellcheck source=/dev/null
    . "$f"
    for fn in src_current src_series src_releases src_released_at src_apply src_fields src_verifier src_first_party src_describe; do
        declare -F "$fn" > /dev/null || die "source type $type lacks $fn"
    done
}

# fetch <url> <file>: one download, failing loudly. Tests put a curl on PATH that serves fixtures.
fetch() {
    curl -fsSL --connect-timeout 10 --retry 3 --retry-all-errors "$1" -o "$2" \
        || die "failed to download $1"
}

# replace_once <file> <old> <new>: rewrites the one occurrence of <old> in <file>, as a literal, and
# fails when it is there zero or several times, so a rewrite never lands on a line it did not mean.
# Byte-stable: nothing else in the file changes, and two engines on two machines write the same.
replace_once() {
    local file="$1" old="$2" new="$3" count
    [[ "$old" != "$new" ]] && {
        count="$(grep -cF -- "$old" "$file" || true)"
        [[ "$count" == 1 ]] || die "'$old' occurs $count times in $file, not once"
        OLD="$old" NEW="$new" perl -0pi -e 's/\Q$ENV{OLD}\E/$ENV{NEW}/' "$file"
    }
    return 0
}

# ours_filter: the jq test for this repository's own PR, never a fork's of the same branch name.
ours_filter() { printf 'select(.headRepositoryOwner.login == "%s" and .isCrossRepository == false)' "$(manifest_repo | cut -d/ -f1)"; }

# declined_version <item> <version>: a closed, unmerged PR of this repository on upstream/<item>
# carried <version> (the marker pr.sh writes, pending or not): Sanne declined it, and neither plan
# nor verify takes it again. An unanswerable query reads as declined (fail closed).
declined_version() {
    local n
    n="$(gh pr list -R "$(manifest_repo)" --head "upstream/$1" --state closed --limit 200 \
            --json body,mergedAt,headRepositoryOwner,isCrossRepository 2>/dev/null \
        | jq -r --arg m "<!-- upstream-watch: $1 $2 -->" --arg p "<!-- upstream-watch: $1 $2, pending -->" \
            "[.[] | $(ours_filter) | select(.mergedAt == null and ((.body | contains(\$m)) or (.body | contains(\$p))))] | length" 2>/dev/null)" \
        || n=""
    [[ "$n" =~ ^[0-9]+$ ]] || return 0
    [[ "$n" != 0 ]]
}

# cooled <item> <version>: the item's cooling period has passed since the publisher dated the
# version; a version the publisher does not date is not cooled. Needs the item's source loaded.
cooled() {
    local secs rel
    secs="$(duration_seconds "$(item "$1" cooling)")"
    (( secs > 0 )) || return 0
    rel="$(src_released_at "$2")" || return 1
    (( rel + secs <= $(date -u +%s) ))
}
