#!/bin/bash
set -euo pipefail

# Collect diagnostics from the appliance VM.
# Boots the VM in diagnostic mode, prints results, and exits.
# The disk image is not modified (QEMU uses snapshot=on, vfkit uses a temp copy).
#
# Usage: sudo ./appliance/diag.sh

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/incus-spawn"
APPLIANCE_DIR="${APPLIANCE_DIR:-$(dirname "$0")/build}"
DISK_IMG="$STATE_DIR/disk.img"

[ -f "$APPLIANCE_DIR/vmlinuz" ] || { echo "ERROR: $APPLIANCE_DIR/vmlinuz not found. Run build.sh first." >&2; exit 1; }
[ -f "$DISK_IMG" ] || { echo "ERROR: $DISK_IMG not found. Run vm.sh start first." >&2; exit 1; }

if [ "$(uname -s)" = "Darwin" ] && command -v vfkit >/dev/null 2>&1; then
    LOGFILE=$(mktemp)
    dummy_initrd=$(mktemp)
    echo | cpio -o -H newc 2>/dev/null | gzip > "$dummy_initrd"
    vfkit \
        --cpus 2 --memory 2048 \
        --kernel "$APPLIANCE_DIR/vmlinuz" \
        --initrd "$dummy_initrd" \
        --kernel-cmdline "root=/dev/vda rw console=hvc0 isx.diag" \
        --device virtio-blk,path="$DISK_IMG" \
        --device virtio-net,nat \
        --device virtio-serial,logFilePath="$LOGFILE" \
        --restful-uri "tcp://localhost:0" \
        > /dev/null 2>&1 &
    pid=$!
    elapsed=0
    while [ "$elapsed" -lt 600 ]; do
        if grep -q '=== end diagnostics ===' "$LOGFILE" 2>/dev/null; then
            break
        fi
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    sed -n '/=== incus-spawn diagnostics ===/,/=== end diagnostics ===/p' "$LOGFILE"
    rm -f "$LOGFILE" "$dummy_initrd"
else
    arch=$(uname -m)
    qemu_bin="qemu-system-$arch"
    console="ttyS0"
    machine_args="-machine pc -cpu qemu64"

    case "$arch" in
        x86_64)
            [ -e /dev/kvm ] && machine_args="-machine pc -cpu host -enable-kvm"
            ;;
        aarch64)
            machine_args="-machine virt -cpu cortex-a57"
            [ -e /dev/kvm ] && machine_args="-machine virt -cpu host -enable-kvm"
            console="ttyAMA0"
            ;;
        *) echo "ERROR: unsupported architecture: $arch" >&2; exit 1 ;;
    esac

    timeout 60 $qemu_bin \
        $machine_args \
        -m 2048 -nographic -no-reboot -nodefaults -serial stdio \
        -kernel "$APPLIANCE_DIR/vmlinuz" \
        -drive file="$DISK_IMG",format=raw,if=virtio,snapshot=on \
        -netdev user,id=net0 -device virtio-net-pci,netdev=net0 \
        -append "root=/dev/vda rw console=$console isx.diag" \
        2>/dev/null | sed -n '/=== incus-spawn diagnostics ===/,/=== end diagnostics ===/p'
fi
