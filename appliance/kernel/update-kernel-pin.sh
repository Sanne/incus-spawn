#!/bin/bash
set -euo pipefail

# Keep build-kernel.sh's kernel pin honest and current (#1355).
#
#   check  fail unless KERNEL_SHA256 is the sum kernel.org's signed
#          sha256sums.asc gives for linux-${KERNEL_VERSION}.tar.xz; given a
#          file, the pin in that file, still verified with this script's key:
#          kernel-pin-signed.yml checks a PR's pin with main's script and key
#   bump   rewrite KERNEL_VERSION and KERNEL_SHA256 to the newest point release
#          of the pinned series, if one is newer; print "<version> <sha256>"
#          when it did, nothing when the pin is current
#   pinned print KERNEL_VERSION as this script reads it
#   eol    print the pinned series when kernel.org's releases.json marks it
#          end of life or no longer lists it, nothing otherwise
#
# Usage:  ./update-kernel-pin.sh check [<build-kernel.sh>] | bump | pinned | eol
#
# Every sum comes out of the signed file, and only out of the part gpgv reports
# as signed: never from a downloaded tarball, a build log or a PR. The file is
# verified against the one key committed beside this script, whose fingerprint
# is pinned below as well; the key is never fetched at run time. A new series
# (7.2 -> 7.3) is never proposed: its fragments need a person.
#
# Changing the key is a two-place edit, kernel.org-autosigner.asc and
# AUTOSIGNER_FPR, made only after the new fingerprint has been checked against
# kernel.org's WKD (gpg --locate-keys autosigner@kernel.org), keys.openpgp.org
# and the signed pgpkeys.git on git.kernel.org; KernelPinUpdateTest fails when
# the two places disagree.
#
# Requirements: curl, gpg and gpgv (GnuPG 2.1+), sort -V; jq for eol.

AUTOSIGNER_FPR="B8868C80BA62A1FFFAF5FDA9632D3A06589DA6B1"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_SCRIPT="$SCRIPT_DIR/build-kernel.sh"
# Only the pin to check can come from elsewhere; the key and its fingerprint never do.
if [ "${1:-}" = check ] && [ -n "${2:-}" ]; then
    BUILD_SCRIPT="$2"
fi
KEY="$SCRIPT_DIR/kernel.org-autosigner.asc"

pinned() {
    sed -n "s/^$1=\"\\([^\"]*\\)\"\$/\\1/p" "$BUILD_SCRIPT"
}

# A pin checked for someone else is the two lines pinned() reads, and nothing else may set
# either name: a second assignment would be the one build-kernel.sh uses. This catches the
# plain forms; the rest of a changed build-kernel.sh still needs a person to read it.
if [ "${1:-}" = check ]; then
    assignments="$(grep -vE '^[[:space:]]*#' "$BUILD_SCRIPT" | grep -cE '(^|[^A-Za-z0-9_])KERNEL_(VERSION|SHA256)\+?=' || true)"
    canonical="$(grep -cE '^KERNEL_(VERSION|SHA256)="[^"]*"$' "$BUILD_SCRIPT" || true)"
    if [ "$assignments" != 2 ] || [ "$canonical" != 2 ]; then
        echo "ERROR: $BUILD_SCRIPT must set KERNEL_VERSION and KERNEL_SHA256 once each, as NAME=\"value\" lines, and nowhere else" >&2
        exit 1
    fi
fi
KERNEL_VERSION="$(pinned KERNEL_VERSION)"
KERNEL_SHA256="$(pinned KERNEL_SHA256)"
[ -n "$KERNEL_VERSION" ] && [ -n "$KERNEL_SHA256" ] || {
    echo "ERROR: KERNEL_VERSION or KERNEL_SHA256 not found in $BUILD_SCRIPT" >&2
    exit 1
}
MAJOR="${KERNEL_VERSION%%.*}"
SERIES="$(echo "$KERNEL_VERSION" | cut -d. -f1-2)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() {
    if ! curl -fsSL --connect-timeout 10 --retry 3 --retry-all-errors "$1" -o "$2"; then
        echo "ERROR: failed to download $1" >&2
        return 1
    fi
}

# Prints the signed body of the series' sha256sums.asc, one "<sha256>  <file>"
# per line, or fails. gpgv must exit 0 and report exactly one valid signature,
# made by AUTOSIGNER_FPR's primary key; text outside the signed block never
# reaches its --output.
signed_sums() {
    fetch "https://cdn.kernel.org/pub/linux/kernel/v${MAJOR}.x/sha256sums.asc" "$WORK/sums.asc"
    mkdir -m 700 "$WORK/gnupg"
    gpg --homedir "$WORK/gnupg" --batch --quiet --dearmor < "$KEY" > "$WORK/keyring.gpg"
    if ! gpgv --homedir "$WORK/gnupg" --keyring "$WORK/keyring.gpg" --status-fd 3 \
            --output "$WORK/sums.txt" "$WORK/sums.asc" 3> "$WORK/status" 2> "$WORK/gpgv.log"; then
        echo "ERROR: sha256sums.asc failed signature verification:" >&2
        cat "$WORK/gpgv.log" >&2
        return 1
    fi
    # VALIDSIG's last field is the primary key's fingerprint.
    local sigs
    sigs="$(awk '$2 == "VALIDSIG" { print $NF }' "$WORK/status")"
    if [ "$sigs" != "$AUTOSIGNER_FPR" ]; then
        echo "ERROR: sha256sums.asc is not signed by $AUTOSIGNER_FPR alone (got: ${sigs:-no valid signature})" >&2
        return 1
    fi
    cat "$WORK/sums.txt"
}

# The sum the signed body gives for one version's tarball, or nothing; two
# different sums for one tarball fail rather than pick one.
sum_for() {
    local sums
    sums="$(awk -v f="linux-$2.tar.xz" '$2 == f && $1 ~ /^[0-9a-f]{64}$/ { print $1 }' "$1" | sort -u)"
    if [ "$(printf '%s' "$sums" | grep -c .)" -gt 1 ]; then
        echo "ERROR: kernel.org's signed sha256sums.asc gives linux-$2.tar.xz more than one sum" >&2
        return 1
    fi
    printf '%s' "$sums"
}

case "${1:-}" in
    pinned)
        echo "$KERNEL_VERSION"
        ;;
    check)
        signed_sums > "$WORK/body"
        expected="$(sum_for "$WORK/body" "$KERNEL_VERSION")"
        if [ -z "$expected" ]; then
            echo "ERROR: linux-$KERNEL_VERSION.tar.xz is not in kernel.org's signed sha256sums.asc" >&2
            exit 1
        fi
        if [ "$expected" != "$KERNEL_SHA256" ]; then
            echo "ERROR: KERNEL_SHA256 for $KERNEL_VERSION is $KERNEL_SHA256, but kernel.org's signed sum is $expected" >&2
            exit 1
        fi
        echo "KERNEL_SHA256 for $KERNEL_VERSION matches kernel.org's signed sha256sums.asc"
        ;;
    bump)
        signed_sums > "$WORK/body"
        # Releases of the pinned series only: "7.2" and "7.2.<n>", never "7.20".
        newest="$(awk -v s="$SERIES" '$1 ~ /^[0-9a-f]{64}$/ && $2 ~ /^linux-[0-9.]+\.tar\.xz$/ {
                    v = $2; sub(/^linux-/, "", v); sub(/\.tar\.xz$/, "", v)
                    if (v == s || index(v, s ".") == 1) print v
                  }' "$WORK/body" | sort -V | tail -1)"
        [ -n "$newest" ] || { echo "ERROR: no $SERIES release in kernel.org's signed sha256sums.asc" >&2; exit 1; }
        # Strictly newer only: a sums file that lost entries never moves the pin back.
        [ "$(printf '%s\n%s\n' "$newest" "$KERNEL_VERSION" | sort -V | tail -1)" != "$KERNEL_VERSION" ] || exit 0
        sum="$(sum_for "$WORK/body" "$newest")"
        sed -i.bak -e "s/^KERNEL_VERSION=\"[^\"]*\"\$/KERNEL_VERSION=\"$newest\"/" \
                   -e "s/^KERNEL_SHA256=\"[^\"]*\"\$/KERNEL_SHA256=\"$sum\"/" "$BUILD_SCRIPT"
        rm -f "$BUILD_SCRIPT.bak"
        [ "$(pinned KERNEL_VERSION)" = "$newest" ] && [ "$(pinned KERNEL_SHA256)" = "$sum" ] || {
            echo "ERROR: failed to rewrite the pin in $BUILD_SCRIPT" >&2
            exit 1
        }
        echo "$newest $sum"
        ;;
    eol)
        fetch https://www.kernel.org/releases.json "$WORK/releases.json"
        # A series is listed by its newest release, "7.2" or "7.2.<n>".
        state="$(jq -r --arg s "$SERIES" '[.releases[]
                    | select(.version == $s or (.version | startswith($s + ".")))
                    | if .iseol then "eol" else "live" end] | first // "gone"' "$WORK/releases.json")"
        [ "$state" = live ] || echo "$SERIES"
        ;;
    *)
        echo "Usage: $0 check [<build-kernel.sh>] | bump | pinned | eol" >&2
        exit 2
        ;;
esac
