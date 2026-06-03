#!/bin/bash
set -euo pipefail

# Enable passwordless root auto-login on serial consoles.
# Patches a disk image for debugging via QEMU (-serial stdio) or vfkit console.
# The appliance already starts gettys on available serial devices via rcS.
# This script removes the root password so login doesn't require one.
#
# Usage: sudo ./enable-console.sh <disk.img>

DISK_IMG="${1:-}"
[ -n "$DISK_IMG" ] || { echo "Usage: $(basename "$0") <disk.img>" >&2; exit 1; }
[ -f "$DISK_IMG" ] || { echo "ERROR: $DISK_IMG not found" >&2; exit 1; }

MOUNT_POINT=$(mktemp -d)
LOOP_DEV=""

cleanup() {
    umount "$MOUNT_POINT" 2>/dev/null || true
    [ -n "$LOOP_DEV" ] && losetup -d "$LOOP_DEV" 2>/dev/null || true
    rmdir "$MOUNT_POINT" 2>/dev/null || true
}
trap cleanup EXIT

LOOP_DEV=$(losetup --find --show "$DISK_IMG")
mount "$LOOP_DEV" "$MOUNT_POINT"

# Remove root password
sed -i 's/^root:[^:]*:/root::/' "$MOUNT_POINT/etc/shadow"

umount "$MOUNT_POINT"
losetup -d "$LOOP_DEV"
LOOP_DEV=""
trap - EXIT
rmdir "$MOUNT_POINT"

echo "Console access enabled on $DISK_IMG (passwordless root login)"
