#!/bin/bash
set -euo pipefail

# Collect diagnostics from the appliance VM.
# Boots the VM in diagnostic mode, prints results, and exits.
# The disk image is not modified (QEMU uses snapshot=on, isx-vm boots an APFS clone).
#
# Usage: sudo ./appliance/diag.sh

# isx's own VM state directory (Environment.vmStateDir), which does not follow XDG_STATE_HOME.
STATE_DIR="$HOME/.local/state/incus-spawn"
APPLIANCE_DIR="${APPLIANCE_DIR:-$(dirname "$0")/build}"
DISK_IMG="$STATE_DIR/disk.img"

[ -f "$APPLIANCE_DIR/vmlinuz" ] || { echo "ERROR: $APPLIANCE_DIR/vmlinuz not found. Run build.sh first." >&2; exit 1; }
[ -f "$DISK_IMG" ] || { echo "ERROR: $DISK_IMG not found. Run vm.sh start first." >&2; exit 1; }

if [ "$(uname -s)" = "Darwin" ]; then
    # isx-vm, isx's launcher: ISX_VM=<program> names it, else it is built from this
    # checkout, as vm.sh and test-boot.sh do.
    # Under sudo the build runs as the user who called it, so launcher/.build stays theirs
    # and vm.sh and test-boot.sh can build there afterwards.
    launcher="${ISX_VM:-}"
    build=("$(dirname "$0")/../launcher/build-isx-vm.sh")
    [ "$(id -u)" -ne 0 ] || [ -z "${SUDO_USER:-}" ] || build=(sudo -u "$SUDO_USER" "${build[@]}")
    [ -n "$launcher" ] || launcher="$("${build[@]}")" \
        || { echo "ERROR: isx-vm could not be built" >&2; exit 1; }
    LOGFILE=$(mktemp)
    WORK=$(mktemp -d)
    trap 'rm -rf "$LOGFILE" "$WORK"' EXIT
    # An APFS clone of the disk, so the boot leaves the VM's own disk as it was.
    cp -c "$DISK_IMG" "$WORK/disk.img"
    "$launcher" \
        --cpus 2 --memory 2048 \
        --kernel "$APPLIANCE_DIR/vmlinuz" \
        --cmdline "root=/dev/vda rootfstype=btrfs rw console=hvc0 isx.diag" \
        --disk "$WORK/disk.img" \
        --nat "$(printf '4a:53:58:%02x:%02x:%02x' $((RANDOM % 256)) $((RANDOM % 256)) $((RANDOM % 256)))" \
        --console "$LOGFILE" \
        > /dev/null 2>&1 &
    pid=$!
    elapsed=0
    while [ "$elapsed" -lt 600 ]; do
        if grep -q '=== end diagnostics ===' "$LOGFILE" 2>/dev/null; then
            break
        fi
        # A launcher that refused its arguments or could not start the machine has exited.
        kill -0 "$pid" 2>/dev/null || { echo "ERROR: isx-vm exited before the diagnostics ended" >&2; break; }
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    sed -n '/=== incus-spawn diagnostics ===/,/=== end diagnostics ===/p' "$LOGFILE"
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
