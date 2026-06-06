#!/bin/bash
set -euo pipefail

# Build the incus-spawn VM appliance image.
#
# Produces a rootfs tarball + custom kernel (no initrd). The tarball is
# unpacked into a btrfs disk image on first use by vm.sh.
#
# Strategy:
#   1. Pull Alpine Linux edge container image via podman
#   2. Copy overlay files, install packages via chroot
#   3. Run config.sh to configure the appliance
#   4. Pack rootfs into a compressed tarball
#   5. Build custom minimal kernel from kernel.org source
#
# Requirements: podman, build-essential, flex, bison, bc, libelf-dev, libssl-dev
# Usage:       sudo ./build.sh [target-dir]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$(mkdir -p "${1:-$SCRIPT_DIR/build}" && cd "${1:-$SCRIPT_DIR/build}" && pwd)"
CONTAINER_IMAGE="docker.io/alpine:edge"

echo "Building incus-spawn appliance..."
echo "  Target: $TARGET_DIR"
echo

ROOTFS_DIR=$(mktemp -d)
cleanup() {
    echo "Cleaning up..."
    umount "$ROOTFS_DIR/dev" 2>/dev/null || true
    umount "$ROOTFS_DIR/proc" 2>/dev/null || true
    umount "$ROOTFS_DIR/sys" 2>/dev/null || true
    rm -rf "$ROOTFS_DIR"
}
trap cleanup EXIT

echo "==> Extracting container rootfs..."
podman pull "$CONTAINER_IMAGE"
CONTAINER_ID=$(podman create "$CONTAINER_IMAGE")
podman export "$CONTAINER_ID" | tar -xC "$ROOTFS_DIR"
podman rm "$CONTAINER_ID"
echo "    Base size: $(du -sh "$ROOTFS_DIR" | cut -f1)"

echo "==> Setting up chroot..."
mount --bind /dev "$ROOTFS_DIR/dev"
mount --bind /proc "$ROOTFS_DIR/proc"
mount --bind /sys "$ROOTFS_DIR/sys"
cp /etc/resolv.conf "$ROOTFS_DIR/etc/resolv.conf"

if [ -d "$SCRIPT_DIR/root" ]; then
    echo "==> Copying overlay files..."
    cp -a "$SCRIPT_DIR/root"/* "$ROOTFS_DIR/"
fi

echo "==> Installing packages..."
chroot "$ROOTFS_DIR" /bin/sh -c "
set -eu
apk add --no-cache \
    incus \
    incus-client \
    lxcfs \
    dbus \
    btrfs-progs \
    iptables \
    nftables \
    iproute2 \
    ca-certificates \
    util-linux \
    qemu-guest-agent \
    shadow-subids
"
echo "    Size after packages: $(du -sh "$ROOTFS_DIR" | cut -f1)"

# shadow-subids creates empty subuid/subgid; write entries after install.
# Two ranges: UID 1000 for raw.idmap identity mapping (podman user),
# and 1000000+ for shifted namespace UIDs. Range must NOT start at 0
# because Incus rejects subuid ranges that include the host daemon's UID.
printf 'root:1000:1\nroot:1000000:1000000000\n' > "$ROOTFS_DIR/etc/subuid"
printf 'root:1000:1\nroot:1000000:1000000000\n' > "$ROOTFS_DIR/etc/subgid"
# Alpine's newuidmap is not SUID by default; LXC needs it
chmod u+s "$ROOTFS_DIR/usr/bin/newuidmap" "$ROOTFS_DIR/usr/bin/newgidmap" 2>/dev/null || true

if [ -f "$SCRIPT_DIR/config.sh" ]; then
    echo "==> Running config.sh..."
    cp "$SCRIPT_DIR/config.sh" "$ROOTFS_DIR/config.sh"
    chmod +x "$ROOTFS_DIR/config.sh"
    chroot "$ROOTFS_DIR" /config.sh
    rm "$ROOTFS_DIR/config.sh"
fi

echo "==> Final cleanup..."
rm -rf "$ROOTFS_DIR/usr/share/man" "$ROOTFS_DIR/usr/share/doc" "$ROOTFS_DIR/usr/share/info"
rm -rf "$ROOTFS_DIR/tmp/"* "$ROOTFS_DIR/var/tmp/"*
rm -rf "$ROOTFS_DIR/run/"*
rm -rf "$ROOTFS_DIR/var/lib/incus/"*

umount "$ROOTFS_DIR/dev" 2>/dev/null || true
umount "$ROOTFS_DIR/proc" 2>/dev/null || true
umount "$ROOTFS_DIR/sys" 2>/dev/null || true

echo "    Rootfs: $(du -sh "$ROOTFS_DIR" | cut -f1)"

chmod 755 "$ROOTFS_DIR"

echo "==> Creating rootfs tarball..."
tar cf - -C "$ROOTFS_DIR" . | zstd -T0 -f -o "$TARGET_DIR/rootfs.tar.zst"
chmod 644 "$TARGET_DIR/rootfs.tar.zst"

echo "==> Creating pre-built disk image..."
truncate -s 2G "$TARGET_DIR/disk.img"
mkfs.btrfs -q -L isxroot "$TARGET_DIR/disk.img"
mkdir -p /mnt/isx-disk
mount -o loop "$TARGET_DIR/disk.img" /mnt/isx-disk
zstd -d "$TARGET_DIR/rootfs.tar.zst" --stdout | tar xf - -C /mnt/isx-disk
chmod 755 /mnt/isx-disk
umount /mnt/isx-disk
gzip -9 "$TARGET_DIR/disk.img"
chmod 644 "$TARGET_DIR/disk.img.gz"
echo "    Disk image: $(du -sh "$TARGET_DIR/disk.img.gz" | cut -f1) (2G virtual, extends to ${ISX_VM_DISK:-60G} on first use)"

if [ -f "$TARGET_DIR/vmlinuz" ]; then
    echo "==> Kernel already built, skipping (delete vmlinuz to force rebuild)"
else
    echo "==> Building custom kernel..."
    "$SCRIPT_DIR/kernel/build-kernel.sh" "$TARGET_DIR"
fi

echo
echo "Build complete!"
ls -lh "$TARGET_DIR/rootfs.tar.zst" "$TARGET_DIR/disk.img.gz" "$TARGET_DIR/vmlinuz"
