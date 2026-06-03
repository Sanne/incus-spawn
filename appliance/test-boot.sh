#!/bin/bash
set -euo pipefail

# Boot the appliance image and verify it reaches ISX READY state.
#
# On macOS: uses vfkit (Apple Virtualization.framework)
# On Linux: uses QEMU (with KVM if available)
#
# Usage:  ./test-boot.sh [build-dir] [timeout-seconds]

BUILD_DIR="${1:-$(dirname "$0")/build}"
TIMEOUT="${2:-300}"

if [ ! -f "$BUILD_DIR/vmlinuz" ]; then
    echo "ERROR: $BUILD_DIR/vmlinuz not found" >&2
    echo "Run build.sh first, or pass the build directory as argument." >&2
    exit 1
fi

# Create disk.img from rootfs tarball if needed
if [ ! -f "$BUILD_DIR/disk.img" ]; then
    if [ ! -f "$BUILD_DIR/rootfs.tar.zst" ]; then
        echo "ERROR: neither disk.img nor rootfs.tar.zst found in $BUILD_DIR" >&2
        exit 1
    fi
    echo "Creating disk image from rootfs tarball..."
    ABS_BUILD_DIR="$(cd "$BUILD_DIR" && pwd)"
    if [ "$(uname -s)" = "Darwin" ]; then
        podman machine ssh << REMOTE
            set -euo pipefail
            truncate -s 4G '$ABS_BUILD_DIR/disk.img'
            mkfs.btrfs -q -L isxroot '$ABS_BUILD_DIR/disk.img'
            sudo mkdir -p /mnt/isx-test
            sudo mount -o loop '$ABS_BUILD_DIR/disk.img' /mnt/isx-test
            zstd -d '$ABS_BUILD_DIR/rootfs.tar.zst' --stdout | sudo tar xf - -C /mnt/isx-test
            sudo chmod 755 /mnt/isx-test
            sudo umount /mnt/isx-test
REMOTE
    else
        truncate -s 4G "$BUILD_DIR/disk.img"
        LOOP_DEV=$(losetup --find --show "$BUILD_DIR/disk.img")
        mkfs.btrfs -q -L isxroot "$LOOP_DEV"
        MOUNT_POINT=$(mktemp -d)
        mount "$LOOP_DEV" "$MOUNT_POINT"
        zstd -d "$BUILD_DIR/rootfs.tar.zst" --stdout | tar xf - -C "$MOUNT_POINT"
        chmod 755 "$MOUNT_POINT"
        umount "$MOUNT_POINT"
        losetup -d "$LOOP_DEV"
        rmdir "$MOUNT_POINT"
    fi
fi

LOGFILE=$(mktemp)
trap 'rm -f "$LOGFILE"' EXIT

boot_vfkit() {
    echo "  backend: vfkit (Apple Virtualization.framework)"
    # vfkit requires --initrd even though our kernel ignores it (CONFIG_BLK_DEV_INITRD=n)
    local dummy_initrd
    dummy_initrd=$(mktemp)
    echo | cpio -o -H newc 2>/dev/null | gzip > "$dummy_initrd"
    vfkit \
        --cpus 2 --memory 2048 \
        --kernel "$BUILD_DIR/vmlinuz" \
        --initrd "$dummy_initrd" \
        --kernel-cmdline "root=/dev/vda rw rootflags=commit=300 console=hvc0 mitigations=off" \
        --device virtio-blk,path="$BUILD_DIR/disk.img" \
        --device virtio-net,nat \
        --device virtio-serial,logFilePath="$LOGFILE" \
        --restful-uri "tcp://localhost:0" \
        > /dev/null 2>&1 &
    local pid=$!
    local elapsed=0
    while [ "$elapsed" -lt "$((TIMEOUT * 10))" ]; do
        if grep -q 'ISX READY' "$LOGFILE" 2>/dev/null; then
            local ms=$((elapsed * 100))
            echo "  ISX READY in ~${ms}ms"
            sleep 5
            break
        fi
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    rm -f "$dummy_initrd"
}

boot_qemu() {
    local arch qemu_bin machine_args console
    arch=$(uname -m)
    qemu_bin="qemu-system-$arch"
    console="ttyS0"

    case "$arch" in
        x86_64)
            machine_args="-machine pc -cpu qemu64"
            [ -e /dev/kvm ] && machine_args="-machine pc -cpu host -enable-kvm"
            ;;
        aarch64)
            machine_args="-machine virt -cpu cortex-a57"
            [ -e /dev/kvm ] && machine_args="-machine virt -cpu host -enable-kvm"
            console="ttyAMA0"
            ;;
        *) echo "ERROR: unsupported architecture: $arch" >&2; exit 1 ;;
    esac

    echo "  backend: QEMU ($qemu_bin)"
    timeout "$TIMEOUT" $qemu_bin \
        $machine_args \
        -m 2048 \
        -nographic \
        -no-reboot \
        -nodefaults \
        -serial stdio \
        -kernel "$BUILD_DIR/vmlinuz" \
        -drive file="$BUILD_DIR/disk.img",format=raw,if=virtio \
        -append "root=/dev/vda rw rootflags=commit=300 console=$console mitigations=off" \
        > "$LOGFILE" 2>&1 &
    local qemu_pid=$!
    local elapsed=0
    while [ "$elapsed" -lt "$((TIMEOUT * 10))" ]; do
        if ! kill -0 "$qemu_pid" 2>/dev/null; then
            echo "  QEMU exited unexpectedly"
            break
        fi
        if grep -q 'ISX READY' "$LOGFILE" 2>/dev/null; then
            local ms=$((elapsed * 100))
            echo "  ISX READY in ~${ms}ms"
            sleep 5
            break
        fi
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    kill "$qemu_pid" 2>/dev/null || true; wait "$qemu_pid" 2>/dev/null || true
}

echo "Booting appliance (timeout: ${TIMEOUT}s)..."
echo "  disk:   $BUILD_DIR/disk.img ($(du -sh "$BUILD_DIR/disk.img" | cut -f1))"
echo "  kernel: $BUILD_DIR/vmlinuz"

if [ "$(uname -s)" = "Darwin" ] && command -v vfkit >/dev/null 2>&1; then
    boot_vfkit
else
    boot_qemu
fi

echo
echo "=== Boot Summary ==="

PASS=0
FAIL=0

check() {
    if grep -q "$1" "$LOGFILE"; then
        echo "  PASS: $2"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $2"
        FAIL=$((FAIL + 1))
    fi
}

check "BTRFS\|btrfs"                  "btrfs root mounted"
check "Incus.*Hypervisor\|incusd"     "Incus activated"
check "ISX READY"                     "appliance ready (implies network, bridge, storage)"

echo
if [ "$FAIL" -eq 0 ]; then
    echo "All $PASS checks passed."
else
    echo "$FAIL of $((PASS + FAIL)) checks failed."
    exit 1
fi
