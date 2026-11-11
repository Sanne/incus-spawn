#!/bin/bash
# Run as root inside an Ubuntu guest of scripts/distro-vm.sh, by its boot (#2148).
#
# The guest resolves names through the runner (QEMU's user-mode network), and the hosted
# runners' resolver has failed every ubuntu.com name for hours at a time (#2146). Then every
# index apt reads fails after a long wait, and isx init finds no incus package.
# So each ubuntu.com archive host that does not resolve is replaced, in apt's sources, by a
# mirror outside ubuntu.com; the archive's signatures are checked whichever mirror serves it.
# A host that resolves is left alone, as a user's host has it.
# Only the guest harness does this: isx init's own apt is not changed (Sanne, on #2148).
#
# Environment: APT_FALLBACK_MIRROR (empty for none; default https://mirrors.edge.kernel.org/ubuntu, which
# carries amd64's archive only, so ubuntu-ports is never moved), APT_DIR (default /etc/apt),
# APT_RESOLVE_TIMEOUT (seconds, default 15).
set -euo pipefail

# Empty for none, as in .github/scripts/apt-install.sh
FALLBACK=${APT_FALLBACK_MIRROR-https://mirrors.edge.kernel.org/ubuntu}
FALLBACK=${FALLBACK%/}
[ -n "$FALLBACK" ] || exit 0
APT_DIR=${APT_DIR:-/etc/apt}
RESOLVE_TIMEOUT=${APT_RESOLVE_TIMEOUT:-15}
# An Ubuntu archive URI: its scheme and host, then /ubuntu as a whole path segment
ARCHIVE='https?://([a-z0-9-]+\.)*ubuntu\.com/ubuntu(/|[[:space:]]|$)'

resolves() {
    timeout "$RESOLVE_TIMEOUT" getent ahosts "$1" > /dev/null 2>&1
}

files=()
for f in "$APT_DIR/sources.list" "$APT_DIR"/sources.list.d/*.list "$APT_DIR"/sources.list.d/*.sources; do
    [ -f "$f" ] && files+=("$f")
done
[ ${#files[@]} -gt 0 ] || exit 0

# Commented lines are not read by apt, so they never make a host count
hosts=$(grep -hv '^[[:space:]]*#' "${files[@]}" | grep -oE "$ARCHIVE" | sed -E 's#^https?://##; s#/.*##; s#[[:space:]]*$##' | sort -u || true)
# In parallel: a host that does not resolve costs the resolver's whole timeout
probe=$(mktemp -d)
for host in $hosts; do
    { resolves "$host" && touch "$probe/$host"; } &
done
wait
dead=()
for host in $hosts; do
    [ -f "$probe/$host" ] || dead+=("$host")
done
rm -rf "$probe"
[ ${#dead[@]} -gt 0 ] || exit 0

fallback_host=${FALLBACK#*://}
fallback_host=${fallback_host%%[/:]*}
if ! resolves "$fallback_host"; then
    echo "distro-vm: no address for ${dead[*]}, nor for $fallback_host; apt's sources are unchanged" >&2
    exit 0
fi

alternation=$(printf '%s\n' "${dead[@]}" | sed 's/\./\\./g' | paste -sd '|' -)
# The mirror goes into sed's replacement, where '#', '&' and '\' are special
replacement=$(printf '%s' "$FALLBACK" | sed 's/[#&\\]/\\&/g')
for f in "${files[@]}"; do
    # Uncommented lines only; written in place so the file keeps its owner and mode
    sed -E "/^[[:space:]]*#/!s#https?://($alternation)/ubuntu(/|[[:space:]]|\$)#$replacement\\2#g" "$f" > "$f.isx-tmp"
    cat "$f.isx-tmp" > "$f"
    rm -f "$f.isx-tmp"
done
echo "distro-vm: no address for ${dead[*]}, so apt in the guest reads $FALLBACK instead" >&2
