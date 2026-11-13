# Source type github-release (#2036): the base image pinned in minimal.yaml, released by
# Sanne/incus-spawn-images. The release's own SHA256SUMS and SHA256SUMS.v2 are the only source of a
# sum (.github/scripts/base-image-sums.sh, #1678), and an asset's name comes from the URL template it
# is downloaded through. A tag of another release line (fedora-46 under a fedora-45 built-in) is a
# judgement issue, never a PR: every template rebuilds on it. A channel's line is never followed.

. "$ENGINE_ROOT/.github/scripts/base-image-sums.sh"
IMAGE_FILE="common/src/main/resources/images/minimal.yaml"

_yaml() { printf '%s' "$(yq -r "$1" "$TREE/$IMAGE_FILE")"; }
_repo() { release_repo "$(_yaml .image_url)" "$(_yaml .vm_image_url)"; }

src_current() { _yaml .image_tag; }
# The series is the tag's whole line, its channel included (#1994): fedora-45, fedora-45-next.
src_series() { _tag_fields "$1" > /dev/null && printf '%s\n' "$_LINE"; }
src_verifier() { echo publisher-sums; }
src_first_party() { [[ "$(_repo)" == "${MANIFEST_OWNER:-$(manifest_repo | cut -d/ -f1)}/"* ]]; }

# Every published, non-draft release tag of the stable channel, oldest first. Only the stable line
# is followed by itself (#1994): a channel's releases (fedora-45-next-20261120) are pinned by hand,
# so the watch neither proposes nor files an issue for one.
src_releases() {
    gh api --paginate "repos/$(_repo)/releases" \
        --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name' \
        | grep -E "^([a-z]+-[0-9]+-[0-9]{8}(-[0-9]{1,9})?|$LEGACY_TAG)\$" | src_order
}

# The one release published under a typed tag before every tag was generated: the stable Fedora 45
# line, built on 2026-11-04, ordered before that day's first dated release. isx names the same
# exception (BaseImageAge.LEGACY_TAG); no other such tag is published again.
LEGACY_TAG=fedora-45-v1

# _tag_fields <tag>: sets _LINE, _DATE and _SERIAL as isx's BaseImageAge.ReleaseTag reads the tag
# (<distribution>-<release>[-<channel>]-<yyyymmdd>[-<n>], #1994); fails for a tag of no such shape.
# Inline regexes, no subshell: src_order runs it for every release listed.
_tag_fields() {
    if [[ "$1" == "$LEGACY_TAG" ]]; then
        _LINE=fedora-45; _DATE=20261104; _SERIAL=0
    elif [[ "$1" =~ ^([a-z]+-[0-9]{1,9}(-[a-z]+)?)-([0-9]{8})(-([0-9]{1,9}))?$ ]]; then
        _LINE="${BASH_REMATCH[1]}"; _DATE="${BASH_REMATCH[3]}"; _SERIAL="${BASH_REMATCH[5]:-1}"
    else
        return 1
    fi
}

# Release tags, oldest first: by line (sort -V, so fedora-46 comes after fedora-45, for the issue a
# new Fedora opens), then within a line by date and that day's number, as isx orders them. A tag of
# no known shape comes first. sort -V alone would put fedora-45-v1 after every dated tag of its line.
src_order() {
    local t
    while IFS= read -r t; do
        [[ -n "$t" ]] || continue
        if _tag_fields "$t"; then
            printf '%s\t%s\t%s\t%s\n' "$_LINE" "$_DATE" "$_SERIAL" "$t"
        else
            printf '%s\t0\t0\t%s\n' "" "$t"
        fi
    done | sort -t$'\t' -k1,1V -k2,2n -k3,3n -k4,4V | cut -f4
}

src_released_at() {
    local at
    is_version "$1" || die "not a release tag: '$1'"
    at="$(gh api "repos/$(_repo)/releases/tags/$1" --jq '.published_at // empty')"
    [[ -n "$at" ]] || die "release $1 has no published_at"
    date -u -d "$at" +%s
}

# Rewrites the tag and all four sums to release <tag> of the same line; the VM image stays or becomes
# -vm-direct (both arches, or not at all) and never goes back to stock (design/templates.md).
src_apply() {
    local tag="$1" repo sums cur_tag image_url vm_url vm_file vm_base direct stock want
    local c_x86 c_arm v_x86 v_arm
    is_version "$tag" || die "not a release tag: '$tag'"
    cur_tag="$(_yaml .image_tag)"
    # The whole line, its channel included (#1994): release_line alone reads fedora-45-next as fedora-45
    [[ -n "$(src_series "$tag")" && "$(src_series "$tag")" == "$(src_series "$cur_tag")" ]] \
        || die "release $tag is not of the built-in's line $(src_series "$cur_tag"): a line move needs a person"
    repo="$(_repo)"
    sums="$(mktemp -d)"
    fetch_release_sums "$repo" "$tag" "$sums"
    image_url="$(_yaml .image_url)"
    vm_url="$(_yaml .vm_image_url)"
    vm_file="${vm_url##*/}"
    case "$vm_file" in
        *-vm.tar.xz | *-vm-direct.tar.xz) vm_base="${vm_file%-vm*.tar.xz}" ;;
        *) die "vm_image_url names neither a -vm.tar.xz nor a -vm-direct.tar.xz: $vm_url" ;;
    esac
    stock="$vm_base-vm.tar.xz"; direct="$vm_base-vm-direct.tar.xz"
    want="$stock"
    if [[ "$tag" == "$cur_tag" ]]; then
        # The pinned release again (check): its sums, with the VM kind the tree chose.
        want="$vm_file"
    elif release_lists "$sums" "$(asset_name "$direct" "$tag" x86_64)" \
        && release_lists "$sums" "$(asset_name "$direct" "$tag" aarch64)"; then
        want="$direct"
    elif [[ "$vm_file" == "$direct" ]]; then
        die "release $tag lacks -vm-direct for both arches, and the built-in already uses it"
    fi
    c_x86="$(release_sum "$sums" "$(asset_name "$image_url" "$tag" x86_64)")"
    c_arm="$(release_sum "$sums" "$(asset_name "$image_url" "$tag" aarch64)")"
    v_x86="$(release_sum "$sums" "$(asset_name "$want" "$tag" x86_64)")"
    v_arm="$(release_sum "$sums" "$(asset_name "$want" "$tag" aarch64)")"
    rm -rf "$sums"
    is_sha256 "$c_x86" && is_sha256 "$c_arm" && is_sha256 "$v_x86" && is_sha256 "$v_arm" || die "release $tag: a sum is missing"
    local f="$TREE/$IMAGE_FILE"
    replace_once "$f" "image_tag: $cur_tag" "image_tag: $tag"
    replace_once "$f" "$vm_url" "${vm_url%/*}/$want"
    replace_once "$f" "$(_yaml .image_sha256.x86_64)" "$c_x86"
    replace_once "$f" "$(_yaml .image_sha256.aarch64)" "$c_arm"
    replace_once "$f" "$(_yaml .vm_image_sha256.x86_64)" "$v_x86"
    replace_once "$f" "$(_yaml .vm_image_sha256.aarch64)" "$v_arm"
    [[ "$(_yaml .image_tag)" == "$tag" && "$(_yaml .vm_image_sha256.aarch64)" == "$v_arm" ]] \
        || die "failed to rewrite $IMAGE_FILE"
}

src_fields() {
    local k
    for k in image image_url vm_image_url image_tag image_sha256.x86_64 image_sha256.aarch64 vm_image_sha256.x86_64 vm_image_sha256.aarch64; do
        printf '%s=%s\n' "$k" "$(_yaml ".$k")"
    done
}

src_describe() {
    local tag="$1" kind=stock
    [[ "$(_yaml .vm_image_url)" == *-vm-direct.tar.xz ]] && kind=direct
    cat <<MD
Bumps the built-in base-image fallback to \`$tag\`, the newest release of $(_repo), refreshing the container and VM checksums.

VM image: \`$kind\` (\`direct\` is the image built from scratch, \`-vm-direct.tar.xz\`, listed in SHA256SUMS.v2; \`stock\` is \`-vm.tar.xz\`).

Each checksum was taken from the release's own sums files, for the asset its URL downloads:

$(bash "$ENGINE_ROOT/.github/scripts/verify-base-image-sums.sh" "$TREE/$IMAGE_FILE")

Builds already track the latest release at build time when unpinned; this keeps the offline fallback current.
MD
}
