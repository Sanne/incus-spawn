#!/bin/bash
set -euo pipefail

# Enable passwordless root auto-login on serial consoles.
# Patches a disk image for debugging via QEMU (-serial stdio) or vfkit console.
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

SYSDIR="$MOUNT_POINT/etc/systemd/system"

# Remove any serial-getty masks
rm -f "$SYSDIR/serial-getty@ttyS0.service"
rm -f "$SYSDIR/serial-getty@ttyAMA0.service"

# Create auto-login serial-getty units
for tty in ttyS0 ttyAMA0; do
    cat > "$SYSDIR/serial-getty@${tty}.service" << EOF
[Unit]
Description=Serial Console (${tty})
After=systemd-user-sessions.service

[Service]
ExecStart=-/sbin/agetty -a root 115200 ${tty} linux
Restart=always

[Install]
WantedBy=getty.target
EOF
    mkdir -p "$SYSDIR/getty.target.wants"
    ln -sf "/etc/systemd/system/serial-getty@${tty}.service" \
        "$SYSDIR/getty.target.wants/serial-getty@${tty}.service"
done

# Remove root password
sed -i 's/^root:[^:]*:/root::/' "$MOUNT_POINT/etc/shadow"

# Allow empty password login via PAM
if [ -f "$MOUNT_POINT/etc/pam.d/login" ]; then
    grep -q 'nullok' "$MOUNT_POINT/etc/pam.d/login" || \
        sed -i 's/\(pam_unix.so.*\)/\1 nullok/' "$MOUNT_POINT/etc/pam.d/login"
fi

umount "$MOUNT_POINT"
losetup -d "$LOOP_DEV"
LOOP_DEV=""
trap - EXIT
rmdir "$MOUNT_POINT"

echo "Console access enabled on $DISK_IMG (root auto-login on ttyS0, ttyAMA0)"
