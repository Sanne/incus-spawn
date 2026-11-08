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
#   3. Replace incusd with one built from the same release plus incus/*.patch
#   4. Run config.sh to configure the appliance
#   5. Pack rootfs into a compressed tarball
#   6. Build custom minimal kernel from kernel.org source
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
        fedora:45 bash -c '
            dnf install -y -q btrfs-progs zstd make gcc flex bison bc \
                elfutils-libelf-devel openssl-devel findutils tar xz curl gzip \
                perl diffutils
            /appliance/build.sh /output
        ' || exit $?

    # The container cannot create loop devices on macOS; finish the disk image
    # on the host. Always (re)assemble so a rebuild reflects the new rootfs
    # rather than leaving a stale disk.img.gz from a previous build. On Linux
    # the container already produced disk.img.gz; if it didn't, the build is
    # broken (CI needs disk.img.gz to publish a release).
    if [ "$(uname -s)" = "Darwin" ]; then
        assemble_disk_image_macos
    elif [ ! -f "$TARGET_DIR/disk.img.gz" ]; then
        echo "ERROR: disk.img.gz was not produced (loop device unavailable in build container)." >&2
        exit 1
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
INCUS_BUILD_DIR=$(mktemp -d)
unmount_chroot() {
    umount "$1/dev" 2>/dev/null || true
    umount "$1/proc" 2>/dev/null || true
    umount "$1/sys" 2>/dev/null || true
}
cleanup() {
    echo "Cleaning up..."
    unmount_chroot "$ROOTFS_DIR"
    unmount_chroot "$INCUS_BUILD_DIR"
    rm -rf --one-file-system "$ROOTFS_DIR" "$INCUS_BUILD_DIR" "$INCUS_BUILD_DIR".minirootfs.tar.gz*
}
trap cleanup EXIT

# The latest Alpine stable release (#2045). The sha256 of each arch's minirootfs is the one the
# release publishes (its .sha256 file and latest-releases.yaml), checked against the release's
# .asc signature by Natanael Copa's key (0482 D840 22F5 2DF1 C4E7 CD43 293A CD09 07D9 495A).
# Take a new one from there, never from a build log.
ALPINE_VERSION="3.24.4"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/releases/${ISX_ARCH}/alpine-minirootfs-${ALPINE_VERSION}-${ISX_ARCH}.tar.gz"
case "$ISX_ARCH" in
    x86_64)  ALPINE_SHA256="cdaa38cb8de3537967ee5b6c9b6cd00cc962da2b018e6eea48696bb274923012" ;;
    aarch64) ALPINE_SHA256="4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de" ;;
    *) echo "ERROR: no Alpine $ALPINE_VERSION minirootfs checksum pinned for $ISX_ARCH" >&2; exit 1 ;;
esac

# The Incus release Alpine's incus-feature is built from, and the sha512 its APKBUILD pins for
# the source tarball. incusd is rebuilt from it with incus/*.patch applied (#1313), so the
# package must be this release too: apk's ~ takes any revision of it and nothing else, and a
# new Alpine release fails the build here instead of mixing versions.
INCUS_VERSION="7.0.0"
INCUS_SHA512="a0b24732b6d5f35dde0081d50ab0ce710f41a10396d2ce940912b3dee1c44b0c6d9215a1a54d4517606f806a611f70e288401659a6992d73441f926c7fed6f5e"

# Unpack the minirootfs into $1 (downloaded once, for both chroots, and unpacked only once it
# matches ALPINE_SHA256), then make it a chroot: /dev, /proc and /sys bound, and DNS.
fetch_rootfs() {
    local tarball="$INCUS_BUILD_DIR.minirootfs.tar.gz"
    if [ ! -f "$tarball" ]; then
        curl -fsSL -o "$tarball.part" "$ALPINE_URL"
        if ! echo "$ALPINE_SHA256  $tarball.part" | sha256sum -c --status; then
            rm -f "$tarball.part"
            echo "ERROR: Alpine minirootfs checksum mismatch: $ALPINE_URL is not the pinned $ALPINE_SHA256" >&2
            exit 1
        fi
        mv "$tarball.part" "$tarball"
    fi
    tar xzf "$tarball" -C "$1"
}
mount_chroot() {
    mount --bind /dev "$1/dev"
    mount --bind /proc "$1/proc"
    mount --bind /sys "$1/sys"
    cp /etc/resolv.conf "$1/etc/resolv.conf"
}

echo "==> Downloading Alpine minirootfs (arch=$ISX_ARCH)..."
fetch_rootfs "$ROOTFS_DIR"
echo "    Base size: $(du -sh "$ROOTFS_DIR" | cut -f1)"

echo "==> Setting up chroot..."
mount_chroot "$ROOTFS_DIR"

# apk upgrade first: the minirootfs carries the point release's packages, and the branch's
# security fixes since then reach the image only through it. Before the overlay, so a package
# owning an overlaid file never leaves an .apk-new beside it.
echo "==> Upgrading the minirootfs's packages..."
chroot "$ROOTFS_DIR" /bin/sh -c "export PATH=/usr/sbin:/usr/bin:/sbin:/bin; apk upgrade --no-cache"

if [ -d "$SCRIPT_DIR/root" ]; then
    echo "==> Copying overlay files..."
    cp -a "$SCRIPT_DIR/root"/* "$ROOTFS_DIR/"
fi

echo "==> Installing packages..."
chroot "$ROOTFS_DIR" /bin/sh -c "
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
set -eu
apk add --no-cache \
    incus-feature~$INCUS_VERSION \
    incus-feature-client~$INCUS_VERSION \
    lxcfs \
    dbus \
    btrfs-progs \
    chrony \
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

# No Incus release Alpine ships yet has lxc/incus@2cb2549, without which one local client that
# sends fewer than 8 bytes stalls every API request behind it (#1313). Build incusd with it in a
# separate chroot, so the toolchain stays out of the image, and record what was built.
echo "==> Building incusd $INCUS_VERSION with $(find "$SCRIPT_DIR/incus" -name '*.patch' | wc -l) backport(s)..."
fetch_rootfs "$INCUS_BUILD_DIR"
mount_chroot "$INCUS_BUILD_DIR"
cp -r "$SCRIPT_DIR/incus" "$INCUS_BUILD_DIR/incus"
mkdir "$INCUS_BUILD_DIR/out"
chroot "$INCUS_BUILD_DIR" /incus/build-incusd.sh "$INCUS_VERSION" "$INCUS_SHA512" /out
install -m 755 "$INCUS_BUILD_DIR/out/incusd" "$ROOTFS_DIR/usr/sbin/incusd"
{
    echo "package=$(chroot "$ROOTFS_DIR" /sbin/apk info -e -v incus-feature) (Alpine $ALPINE_VERSION)"
    cat "$INCUS_BUILD_DIR/out/isx-incus"
} > "$ROOTFS_DIR/etc/isx-incus"
sed 's/^/    /' "$ROOTFS_DIR/etc/isx-incus"
unmount_chroot "$INCUS_BUILD_DIR"

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
rm -f "$TARGET_DIR/disk.img" "$TARGET_DIR/disk.img.gz"
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
