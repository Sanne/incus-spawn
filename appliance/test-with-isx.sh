#!/usr/bin/env bash
set -euo pipefail

# Boot the freshly-built appliance with isx and verify the Incus daemon is
# reachable over the vsock socket — i.e. exercise the *real* client path.
#
# Why not vm.sh?  vm.sh boots the appliance standalone for interactive/console
# testing. It deliberately does NOT wire up the vsock forwarder device, the
# virtio-fs host mount, the data/swap disks, or the isx.* kernel parameters
# that isx depends on. So a VM started by vm.sh can never be reached by isx.
# This script instead lets isx's own VM manager boot the build, which is the
# same code path users hit in production.
#
# Usage:  ./appliance/test-with-isx.sh [--keep-data]
#
#   --keep-data   reuse the existing data disk (Incus storage) instead of
#                 wiping it. By default the data disk is recreated, because a
#                 data disk written by a different appliance build can leave
#                 incusd unable to start ("daemon still not running") on the
#                 fresh rootfs. Pass this only when iterating on the same build
#                 and you want your containers to survive.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${APPLIANCE_DIR:-$SCRIPT_DIR/build}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/incus-spawn"
ISX="${ISX:-isx}"
KEEP_DATA=0
[ "${1:-}" = "--keep-data" ] && KEEP_DATA=1

die() { echo "ERROR: $*" >&2; exit 1; }

# This script tests the macOS isx path, where isx boots the appliance VM and
# talks to it over a vsock Unix socket. On Linux, isx connects to a *natively
# installed* Incus over /run/incus/unix.socket and the QEMU boot path exposes no
# vsock socket — so `isx instances` here would hit the host's native daemon, not
# the VM, giving a misleading "success." Test the appliance image standalone
# instead: ./appliance/test-boot.sh (boot checks) or ./appliance/vm.sh shell.
if [ "$(uname -s)" != "Darwin" ]; then
    die "test-with-isx.sh is macOS-only. On Linux use ./appliance/test-boot.sh or ./appliance/vm.sh."
fi

command -v "$ISX" >/dev/null 2>&1 || die "isx not found on PATH (set ISX=/path/to/isx)"
[ -f "$BUILD_DIR/vmlinuz" ] || die "$BUILD_DIR/vmlinuz not found. Run ./appliance/build.sh first."
[ -f "$BUILD_DIR/disk.img.gz" ] || \
    die "$BUILD_DIR/disk.img.gz not found. Run ./appliance/build.sh first."

# Point isx at the locally-built artifacts instead of the downloaded release.
export ISX_APPLIANCE_DIR="$BUILD_DIR"

echo "==> Stopping any running VM..."
"$ISX" vm stop || true

# Force isx to re-extract the new root disk. ensureDisk() skips re-extraction
# when STATE_DIR/disk.img already exists and the version matches, so a rebuild
# with the same version would otherwise boot the stale rootfs. Removing both
# files makes isx extract $BUILD_DIR/disk.img.gz fresh without touching the
# build artifacts (no GitHub re-download is triggered).
echo "==> Clearing previously extracted root disk..."
rm -f "$STATE_DIR/disk.img" "$STATE_DIR/disk.version"

if [ "$KEEP_DATA" -eq 1 ]; then
    echo "==> --keep-data: reusing existing data/swap disks."
else
    echo "==> Wiping persistent data/swap disks (pass --keep-data to keep them)..."
    rm -f "$STATE_DIR/data.img" "$STATE_DIR/swap.img"
fi

echo "==> Starting VM with isx (ISX_APPLIANCE_DIR=$BUILD_DIR)..."
"$ISX" vm start

echo "==> Waiting for Incus daemon (up to 90s)..."
deadline=$(( $(date +%s) + 90 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    if "$ISX" instances >/dev/null 2>&1; then
        echo
        echo "SUCCESS: isx connected to the appliance."
        "$ISX" vm status
        exit 0
    fi
    sleep 2
done

echo >&2
echo "FAILED: Incus daemon did not become reachable." >&2
echo "Inspect boot logs with:  $ISX vm console" >&2
echo "(or: tail -n 80 $STATE_DIR/vm.log )" >&2
exit 1
