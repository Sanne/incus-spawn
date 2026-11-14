# Source type kernel-org (#2036): the appliance and VM kernel pinned in appliance/kernel/build-kernel.sh.
# Every version and sum comes out of kernel.org's signed sha256sums.asc, verified by main's
# appliance/kernel/update-kernel-pin.sh against the committed autosigner key and pinned fingerprint:
# the engine adds nothing to that trust path, it only chooses the version. A series is "7.3"; a
# release of another series is a judgement issue, never a PR (the fragments need a person).

PIN_SCRIPT="$ENGINE_ROOT/appliance/kernel/update-kernel-pin.sh"
KERNEL_FILE="appliance/kernel/build-kernel.sh"

_kernel_pin() {
    local v
    v="$(sed -n "s/^$1=\"\\([^\"]*\\)\"\$/\\1/p" "$TREE/$KERNEL_FILE")"
    [[ "$(printf '%s\n' "$v" | grep -c .)" == 1 ]] || die "$TREE/$KERNEL_FILE does not set $1 exactly once"
    printf '%s' "$v"
}

# The signer is the one update-kernel-pin.sh pins: validate checks the key file and fingerprint are
# well formed, and this that the fingerprint is that script's.
src_validate() {
    [[ -n "$(item "$ITEM" verify.fingerprint)" ]] || return 0
    grep -q "^AUTOSIGNER_FPR=\"$(item "$ITEM" verify.fingerprint)\"\$" "$PIN_SCRIPT" \
        || die "manifest: item $ITEM: verify.fingerprint is not the pinned one"
}

src_current() { _kernel_pin KERNEL_VERSION; }
src_series() { printf '%s' "$1" | cut -d. -f1-2; }
# The pinned major's signed file, and the next major's once kernel.org publishes it: a missing
# file is no release yet, a file that does not verify is a failure like any other.
src_releases() {
    local major err="$UPSTREAM_CACHE/next-major.err" out
    major="$(src_current | cut -d. -f1)"
    "$PIN_SCRIPT" releases "$major"
    if out="$("$PIN_SCRIPT" releases "$((major + 1))" 2> "$err")"; then
        printf '%s\n' "$out"
    elif ! grep -q 'failed to download' "$err"; then
        cat "$err" >&2
        return 1
    fi
}
src_verifier() { echo signed-sums; }
src_first_party() { return 1; }

# kernel.org's releases.json gives the date of each series' newest release only; an older one, or
# one the file does not list yet, has no date, and a candidate without a date waits.
src_released_at() {
    local f ts
    f="$(mktemp)"
    fetch https://www.kernel.org/releases.json "$f"
    ts="$(jq -r --arg v "$1" '[.releases[] | select(.version == $v) | .released.timestamp] | first // empty' "$f")"
    rm -f "$f"
    [[ "$ts" =~ ^[0-9]+$ ]] || die "kernel.org's releases.json does not date $1"
    printf '%s' "$ts"
}

# Rewrites both pin lines to <version> and the sum the signed file gives it.
src_apply() {
    local version="$1" sum cur_v cur_s
    [[ "$version" =~ ^[0-9]+(\.[0-9]+)+$ ]] || die "not a kernel version: '$version'"
    sum="$("$PIN_SCRIPT" sum "$version")"
    is_sha256 "$sum" || die "no signed sum for $version"
    cur_v="$(_kernel_pin KERNEL_VERSION)"
    cur_s="$(_kernel_pin KERNEL_SHA256)"
    replace_once "$TREE/$KERNEL_FILE" "KERNEL_VERSION=\"$cur_v\"" "KERNEL_VERSION=\"$version\""
    replace_once "$TREE/$KERNEL_FILE" "KERNEL_SHA256=\"$cur_s\"" "KERNEL_SHA256=\"$sum\""
    [[ "$(_kernel_pin KERNEL_VERSION)" == "$version" && "$(_kernel_pin KERNEL_SHA256)" == "$sum" ]] \
        || die "failed to rewrite the kernel pin"
}

src_fields() {
    printf 'KERNEL_VERSION=%s\nKERNEL_SHA256=%s\n' "$(_kernel_pin KERNEL_VERSION)" "$(_kernel_pin KERNEL_SHA256)"
}

# The PR body's item-specific part.
src_describe() {
    local version="$1" major="${1%%.*}"
    cat <<MD
Bumps the kernel the VM base image and the macOS appliance build, \`$KERNEL_FILE\`, to $version, the newest release of its series on kernel.org.

\`KERNEL_SHA256\` is taken from kernel.org's [sha256sums.asc](https://cdn.kernel.org/pub/linux/kernel/v$major.x/sha256sums.asc), verified against the committed autosigner key by \`update-kernel-pin.sh\`; \`kernel-pin-signed.yml\` checks it again with main's script and key. Changes: https://cdn.kernel.org/pub/linux/kernel/v$major.x/ChangeLog-$version

Before merging: a Mac boot of this appliance kernel under vfkit, recorded by \`appliance/test-kernel-on-mac.sh <this PR>\` on the bare-metal Mac; \`kernel-mac-verified\` fails until then (ci.md, #791). A newer release replaces this PR's commit, and the boot has to be repeated.

Once merged, the images repo's \`ISX_REF\` has to follow for VMs to get it, and an isx release for the appliance (#1355).
MD
}

# The kernel's own judgement signal besides a new series: kernel.org ending the pinned series.
_eol_title() { printf '[upstream] kernel: series %s is EOL' "$1"; }
src_extra_issues() {
    local series
    series="$("$PIN_SCRIPT" eol)"
    [[ -n "$series" ]] || return 0
    printf '%s\t%s\n' "$(_eol_title "$series")" \
        "kernel.org's releases.json ends the $series series that \`$KERNEL_FILE\` pins, or no longer lists it, so its security fixes stop. The upstream watch never moves to another series: that needs the fragments checked on it, both boot paths and a Mac boot (#1355, #2036)."
}

# An EOL issue of a series no longer pinned, or no longer ended, is done; the retired job's
# "[kernel] <series> is EOL" title counts too. Prints <title regex>\t<title to keep>\t<comment>.
src_stale_issues() {
    local series
    series="$("$PIN_SCRIPT" eol)"
    printf '%s\t%s\t%s\n' '^(\[upstream\] kernel: series|\[kernel\]) [0-9.]+ is EOL$' \
        "${series:+$(_eol_title "$series")}" "The pin has moved to a live series."
}
