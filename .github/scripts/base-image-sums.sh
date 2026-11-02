# Sourced, not run: what update-base-image.yml and verify-base-image-sums.sh know about a base-image
# release's checksums (#1678). The release's own SHA256SUMS and SHA256SUMS.v2 are the only source of a
# sum, and an asset's name always comes from the URL template it is downloaded through, so the names
# checked are the names downloaded. Every function fails, naming what it lacked, rather than print an
# empty or malformed value. Needs gh.

# images_repo <url template>: the owner/name of the repository whose release the template downloads
# from. Only a template under that release's tag is accepted: one that names another tag, or another
# host, downloads a file the release's SHA256SUMS does not speak for.
images_repo() {
    if [[ "$1" =~ ^https://github\.com/([^/]+/[^/]+)/releases/download/\{tag\}/[^/]+$ ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
    else
        echo "Not a release download URL under {tag}: '$1'" >&2
        return 1
    fi
}

# release_repo <image_url> <vm_image_url>: images_repo of both, which must be the same repository.
release_repo() {
    local repo
    repo="$(images_repo "$1")" && [[ "$(images_repo "$2")" == "$repo" ]] \
        || { echo "image_url and vm_image_url do not name one release repository" >&2; return 1; }
    printf '%s\n' "$repo"
}

# asset_name <url template> <tag> <arch>: the file name the template downloads for that release and arch.
asset_name() {
    local f="${1##*/}"
    f="${f//\{tag\}/$2}"
    printf '%s\n' "${f//\{arch\}/$3}"
}

# fetch_release_sums <repo> <tag> <dir>: downloads the release's SHA256SUMS and SHA256SUMS.v2, whichever
# it has, into <dir>. From Fedora 45 on, releases have only SHA256SUMS.v2 (#1862).
fetch_release_sums() {
    local repo="$1" tag="$2" dir="$3"
    if [[ ! "$tag" =~ ^[A-Za-z0-9._-]+$ ]]; then
        echo "Not a release tag: '$tag'" >&2
        return 1
    fi
    # Fails only when neither file is there, or the release cannot be read.
    gh release download "$tag" -R "$repo" -p SHA256SUMS -p SHA256SUMS.v2 -D "$dir" \
        || { echo "Cannot download the sums files of release $tag of $repo" >&2; return 1; }
    if [[ ! -f "$dir/SHA256SUMS" && ! -f "$dir/SHA256SUMS.v2" ]]; then
        echo "Release $tag of $repo has neither a SHA256SUMS nor a SHA256SUMS.v2 asset" >&2
        return 1
    fi
}

# repeat_command <repo> <tag> <dir>: the command that lists the sums fetch_release_sums read. One file
# only, since -O - writes one: SHA256SUMS.v2 when the release has it, as it repeats SHA256SUMS.
repeat_command() {
    local file=SHA256SUMS
    [[ -f "$3/SHA256SUMS.v2" ]] && file=SHA256SUMS.v2
    printf 'gh release download %s -R %s -p %s -O -\n' "$2" "$1" "$file"
}

# release_lists <dir> <asset>: whether the release's sums files name the asset at all.
release_lists() {
    [[ -n "$(_listed_sums "$1" "$2")" ]]
}

# release_sum <dir> <asset>: the asset's SHA-256 as the release lists it, from SHA256SUMS or
# SHA256SUMS.v2 (which repeats SHA256SUMS and adds -vm-direct). Fails when neither names the asset,
# when the two disagree, or when the value is not 64 lowercase hex digits.
release_sum() {
    local sums
    sums="$(_listed_sums "$1" "$2")"
    if [[ -z "$sums" ]]; then
        echo "The release's SHA256SUMS lists no $2" >&2
        return 1
    fi
    if [[ "$(wc -l <<<"$sums")" -ne 1 ]]; then
        echo "The release's sums files disagree on $2: $(tr '\n' ' ' <<<"$sums")" >&2
        return 1
    fi
    if [[ ! "$sums" =~ ^[0-9a-f]{64}$ ]]; then
        echo "The release's SHA256SUMS lists a malformed sum for $2: '$sums'" >&2
        return 1
    fi
    printf '%s\n' "$sums"
}

# Every distinct sum the sums files give the asset, one per line; "*name" is sha256sum's binary mode,
# and a CRLF line ending is read as LF.
_listed_sums() {
    local f
    for f in "$1/SHA256SUMS" "$1/SHA256SUMS.v2"; do
        [[ -f "$f" ]] && awk -v f="$2" '{ sub(/\r$/, ""); n = $2; sub(/^\*/, "", n) } n == f { print $1 }' "$f"
    done | sort -u
}

# release_line <tag>: the distribution and release a tag is of, as fedora-44 for fedora-44-20261101.
release_line() {
    if [[ "$1" =~ ^([a-z]+-[0-9]+)-[A-Za-z0-9._-]+$ ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
    else
        echo "Not a release tag: '$1'" >&2
        return 1
    fi
}

# move_line <name> <from line> <to line>: the name with its one "<from line>-" made "<to line>-". Fails
# when the name does not hold it exactly once, rather than leave a name of the old line behind.
move_line() {
    local count
    count="$(grep -o -- "$2-" <<<"$1" | wc -l)"
    if [[ "$count" -ne 1 ]]; then
        echo "'$1' does not name release line $2 exactly once" >&2
        return 1
    fi
    printf '%s\n' "${1/"$2-"/"$3-"}"
}
