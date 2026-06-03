#!/bin/bash
set -euo pipefail

# Build the incus-spawn VM appliance image.
#
# Produces a rootfs tarball + custom kernel (no initrd). The tarball is
# unpacked into a btrfs disk image on first use by vm.sh.
#
# Strategy:
#   1. Pull openSUSE Tumbleweed container image via podman
#   2. Copy overlay files, install packages via chroot
#   3. Run config.sh to strip bloat and configure services
#   4. Pack rootfs into a compressed tarball
#   5. Build custom minimal kernel from kernel.org source
#
# Requirements: podman, build-essential, flex, bison, bc, libelf-dev, libssl-dev
# Usage:       sudo ./build.sh [target-dir]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$(mkdir -p "${1:-$SCRIPT_DIR/build}" && cd "${1:-$SCRIPT_DIR/build}" && pwd)"
CONTAINER_IMAGE="registry.opensuse.org/opensuse/tumbleweed:latest"

echo "Building incus-spawn appliance..."
echo "  Target: $TARGET_DIR"
echo

ROOTFS_DIR=$(mktemp -d)
cleanup() {
    echo "Cleaning up..."
    umount "$ROOTFS_DIR/var/cache/zypp" 2>/dev/null || true
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

ZYPP_CACHE="${ZYPP_CACHE_DIR:-/var/cache/incus-spawn-zypp}"
mkdir -p "$ZYPP_CACHE"
mkdir -p "$ROOTFS_DIR/var/cache/zypp"
mount --bind "$ZYPP_CACHE" "$ROOTFS_DIR/var/cache/zypp"

echo "==> Installing packages..."
chroot "$ROOTFS_DIR" /bin/bash -c "
set -euo pipefail
# Skip appdata/appstream metadata download — only needed for GUI package managers.
# The large appdata.xml.gz frequently times out on CDN edge nodes in CI.
if command -v sed >/dev/null 2>&1; then
    sed -i 's/^type=yast2/type=rpm-md/' /etc/zypp/repos.d/*.repo 2>/dev/null || true
fi
zypper --non-interactive refresh
zypper --non-interactive install --no-recommends \
    systemd \
    systemd-network \
    systemd-resolved \
    iproute2 \
    iptables \
    incus \
    btrfs-progs \
    ca-certificates \
    ca-certificates-mozilla
"
echo "    Size after packages: $(du -sh "$ROOTFS_DIR" | cut -f1)"

if [ -f "$SCRIPT_DIR/config.sh" ]; then
    echo "==> Running config.sh..."
    cp "$SCRIPT_DIR/config.sh" "$ROOTFS_DIR/config.sh"
    chmod +x "$ROOTFS_DIR/config.sh"
    chroot "$ROOTFS_DIR" /config.sh
    rm "$ROOTFS_DIR/config.sh"
fi

echo "==> Final cleanup..."
umount "$ROOTFS_DIR/var/cache/zypp" 2>/dev/null || true
rm -rf "$ROOTFS_DIR/var/cache/zypp" "$ROOTFS_DIR/var/log/zypp"
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

if [ -f "$TARGET_DIR/vmlinuz" ]; then
    echo "==> Kernel already built, skipping (delete vmlinuz to force rebuild)"
else
    echo "==> Building custom kernel..."
    "$SCRIPT_DIR/kernel/build-kernel.sh" "$TARGET_DIR"
fi

echo
echo "Build complete!"
ls -lh "$TARGET_DIR/rootfs.tar.zst" "$TARGET_DIR/vmlinuz"
