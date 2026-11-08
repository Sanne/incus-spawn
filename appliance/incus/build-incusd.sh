#!/bin/sh
# Build incusd with the backports in this directory (#1313), and describe what was built.
#
# Runs as root in a throwaway Alpine chroot of the appliance's own release (build.sh), so it
# links against the same musl, lxc and cowsql as the rest of incus-feature, and the compiler
# and -dev packages never reach the appliance. Mirrors how Alpine's APKBUILD builds it: the
# same source tarball (checked against the sha512 the APKBUILD pins), the libsqlite3 tag, the
# same cgo flags and abuild's GOFLAGS; only incusd is built, since the backports touch nothing
# else.
#
# Usage: build-incusd.sh <incus-version> <source-sha512> <out-dir>
#   Writes <out-dir>/incusd and <out-dir>/isx-incus (the provenance lines for /etc/isx-incus).

export PATH=/usr/sbin:/usr/bin:/sbin:/bin
set -eu

[ $# -eq 3 ] || { echo "usage: build-incusd.sh <incus-version> <source-sha512> <out-dir>" >&2; exit 2; }
VERSION="$1" SHA512="$2" OUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"

apk upgrade --no-cache -q
apk add --no-cache -q curl patch go gcc musl-dev \
    acl-dev cowsql-dev raft-cowsql-dev eudev-dev gettext-dev libcap-dev libuv-dev \
    linux-headers lxc-dev lz4-dev sqlite-dev

WORK=$(mktemp -d)
curl -fsSL -o "$WORK/incus.tar.gz" "https://github.com/lxc/incus/archive/refs/tags/v$VERSION.tar.gz"
echo "$SHA512  $WORK/incus.tar.gz" | sha512sum -c -s \
    || { echo "ERROR: incus v$VERSION source does not match its pinned sha512" >&2; exit 1; }
tar xzf "$WORK/incus.tar.gz" -C "$WORK"
cd "$WORK/incus-$VERSION"

patches=""
for p in "$HERE"/*.patch; do
    [ -e "$p" ] || continue   # none left once Alpine ships the fixes
    patch -p1 --no-backup-if-mismatch < "$p"
    patches="$patches
patch=$(basename "$p") sha256:$(sha256sum "$p" | cut -d' ' -f1)"
done

export CGO_CFLAGS="-I/usr/include/raft -I/usr/include/cowsql"
# abuild's default LDFLAGS (/etc/abuild.conf), which the APKBUILD passes through.
export CGO_LDFLAGS="-Wl,--as-needed,-O1,--sort-common -Wl,-z,pack-relative-relocs -lintl"
export CGO_LDFLAGS_ALLOW="(-Wl,-wrap,pthread_create)|(-Wl,-z,now)"
# abuild's GOFLAGS, and stripped as abuild strips a package (config.sh's strip finds no strip
# in the image, so it would otherwise ship with its debug info: 20MB more).
export GOTOOLCHAIN=local GOFLAGS="-buildmode=pie -trimpath -buildvcs=false" GOPATH="$WORK/go" GOCACHE="$WORK/go-cache"
go build -tags libsqlite3 -ldflags "-s -w" -o "$OUT/incusd" ./cmd/incusd

cat > "$OUT/isx-incus" << EOF
incusd=lxc/incus v$VERSION source sha512:$SHA512$patches
go=$(go version | cut -d' ' -f3-)
EOF
chmod -R u+w "$WORK"
rm -rf "$WORK"
