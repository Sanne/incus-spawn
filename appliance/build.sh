#!/bin/bash
set -euo pipefail

# Build the incus-spawn VM appliance image.
#
# Produces a rootfs tarball + custom kernel (no initrd). The tarball is
# unpacked into a btrfs disk image on first use by vm.sh.
#
# Strategy:
#   1. Download Alpine Linux minirootfs
#   2. Copy overlay files, install packages via chroot
#   3. Run config.sh to configure the appliance
#   4. Pack rootfs into a compressed tarball
#   5. Build custom minimal kernel from kernel.org source
#
# The build re-executes inside a privileged Fedora container via podman.
# The only host requirement is podman.
#
# Requirements: podman
# Usage:        ./build.sh [target-dir]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$(mkdir -p "${1:-$SCRIPT_DIR/build}" && cd "${1:-$SCRIPT_DIR/build}" && pwd)"
ISX_ARCH="${ISX_ARCH:-$(uname -m)}"
[ "$ISX_ARCH" = "arm64" ] && ISX_ARCH="aarch64"
ISX_VERSION="${ISX_VERSION:-$(cd "$SCRIPT_DIR/.." && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo dev)}"

# On macOS the privileged build container cannot set up loop devices, so the
# in-container step produces only rootfs.tar.zst + vmlinuz. Assemble the btrfs
# disk.img.gz here on the host via the podman machine VM (where loop-mount
# works), matching what vm.sh does. isx requires disk.img.gz, so this is what
# makes the macOS build usable by `isx vm start`.
assemble_disk_image_macos() {
    local rootfs="$TARGET_DIR/rootfs.tar.zst"
    local img="$TARGET_DIR/disk.img"
    [ -f "$rootfs" ] || { echo "ERROR: $rootfs not found; cannot assemble disk image" >&2; exit 1; }
    echo "==> Assembling btrfs disk image via podman machine..."
    rm -f "$img" "$img.gz"
    podman machine ssh <<REMOTE
set -euo pipefail
truncate -s 2G '$img'
mkfs.btrfs -q -L isxroot '$img'
sudo mkdir -p /mnt/isx-disk
sudo mount -o loop '$img' /mnt/isx-disk
zstd -d '$rootfs' --stdout | sudo tar xf - -C /mnt/isx-disk
sudo chmod 755 /mnt/isx-disk
sudo umount /mnt/isx-disk
REMOTE
    gzip -9 "$img"
    chmod 644 "$img.gz"
    echo "    Disk image: $(du -sh "$img.gz" | cut -f1) (2G virtual, extends to ${ISX_VM_DISK:-60G} on first use)"
}

if [ -z "${ISX_INSIDE_CONTAINER:-}" ]; then
    command -v podman >/dev/null 2>&1 || { echo "ERROR: podman is required. Install with: brew install podman (macOS) or dnf install podman (Fedora)" >&2; exit 1; }
    echo "Re-executing build inside a Linux container..."
    CACHE_DIR="${KERNEL_CACHE_DIR:-$HOME/.cache/isx-build}"
    mkdir -p "$CACHE_DIR"
    podman run --rm --privileged \
        -v "$SCRIPT_DIR:/appliance:ro" \
        -v "$TARGET_DIR:/output" \
        -v "$CACHE_DIR:/cache" \
        -e ISX_ARCH="$ISX_ARCH" \
        -e ISX_VERSION="$ISX_VERSION" \
        -e KERNEL_CACHE_DIR=/cache \
        -e ISX_INSIDE_CONTAINER=1 \
        fedora:44 bash -c '
            dnf install -y -q btrfs-progs zstd make gcc flex bison bc \
                elfutils-libelf-devel openssl-devel findutils tar xz curl gzip \
                perl diffutils
            /appliance/build.sh /output
        ' || exit $?

    # The container cannot create loop devices on macOS; finish the disk image
    # on the host. On Linux the container already produced disk.img.gz; if it
    # didn't, the build is broken (CI needs disk.img.gz to publish a release).
    if [ ! -f "$TARGET_DIR/disk.img.gz" ]; then
        if [ "$(uname -s)" = "Darwin" ]; then
            assemble_disk_image_macos
        else
            echo "ERROR: disk.img.gz was not produced (loop device unavailable in build container)." >&2
            exit 1
        fi
    fi

    echo
    echo "Build complete!"
    ls -lh "$TARGET_DIR/rootfs.tar.zst" "$TARGET_DIR/disk.img.gz" "$TARGET_DIR/vmlinuz" 2>/dev/null || \
        ls -lh "$TARGET_DIR/rootfs.tar.zst" "$TARGET_DIR/vmlinuz"
    exit 0
fi

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

ALPINE_VERSION="3.23.0"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/releases/${ISX_ARCH}/alpine-minirootfs-${ALPINE_VERSION}-${ISX_ARCH}.tar.gz"

echo "==> Downloading Alpine minirootfs (arch=$ISX_ARCH)..."
curl -fsSL "$ALPINE_URL" | tar xz -C "$ROOTFS_DIR"
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
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
set -eu
apk add --no-cache \
    incus-feature \
    incus-feature-client \
    lxcfs \
    dbus \
    btrfs-progs \
    iptables \
    nftables \
    iproute2 \
    ca-certificates \
    util-linux \
    qemu-guest-agent \
    shadow-subids \
    socat
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

echo "==> Embedding version: $ISX_VERSION"
echo "$ISX_VERSION" > "$ROOTFS_DIR/etc/isx-version"

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
if LOOP_DEV=$(losetup --find --show "$TARGET_DIR/disk.img" 2>/dev/null); then
    mkfs.btrfs -q -L isxroot "$LOOP_DEV"
    mkdir -p /mnt/isx-disk
    mount "$LOOP_DEV" /mnt/isx-disk
    zstd -d "$TARGET_DIR/rootfs.tar.zst" --stdout | tar xf - -C /mnt/isx-disk
    chmod 755 /mnt/isx-disk
    umount /mnt/isx-disk
    losetup -d "$LOOP_DEV"
    gzip -9 "$TARGET_DIR/disk.img"
    chmod 644 "$TARGET_DIR/disk.img.gz"
    echo "    Disk image: $(du -sh "$TARGET_DIR/disk.img.gz" | cut -f1) (2G virtual, extends to ${ISX_VM_DISK:-60G} on first use)"
else
    rm -f "$TARGET_DIR/disk.img"
    echo "    Skipped (no loop device support; vm.sh will create it on first use)"
fi

if [ -f "$TARGET_DIR/vmlinuz" ]; then
    echo "==> Kernel already built, skipping (delete vmlinuz to force rebuild)"
else
    echo "==> Building custom kernel..."
    "$SCRIPT_DIR/kernel/build-kernel.sh" "$TARGET_DIR"
fi

echo "==> Container build step done (rootfs + kernel ready)."
# When loop devices were unavailable (e.g. macOS), the host wrapper assembles
# disk.img.gz after this container exits; see assemble_disk_image_macos above.
