#!/bin/bash
set -euo pipefail

# Manage the incus-spawn appliance VM.
#
# On macOS: uses vfkit (Apple Virtualization.framework)
# On Linux: uses QEMU with KVM
#
# Usage:  vm.sh start|stop|status|console

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/incus-spawn"
APPLIANCE_DIR="${APPLIANCE_DIR:-$SCRIPT_DIR/build}"
PID_FILE="$STATE_DIR/vm.pid"
LOG_FILE="$STATE_DIR/vm.log"
REST_URI_FILE="$STATE_DIR/vm.rest-uri"

DISK_IMG="$STATE_DIR/disk.img"
DISK_SIZE="${ISX_VM_DISK:-60G}"

detect_cpus() {
    local available
    if [ "$(uname -s)" = "Darwin" ]; then
        available=$(sysctl -n hw.ncpu)
    else
        available=$(nproc)
    fi
    local limit=$((available - 2))
    [ "$limit" -lt 1 ] && limit=1
    echo "$limit"
}

detect_memory() {
    local total_mb
    if [ "$(uname -s)" = "Darwin" ]; then
        total_mb=$(( $(sysctl -n hw.memsize) / 1024 / 1024 ))
    else
        total_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
    fi
    local limit_mb=$(( total_mb * 60 / 100 ))
    [ "$limit_mb" -lt 2048 ] && limit_mb=2048
    echo "$limit_mb"
}

CPUS="${ISX_VM_CPUS:-$(detect_cpus)}"
MEMORY="${ISX_VM_MEMORY:-$(detect_memory)}"

mkdir -p "$STATE_DIR"

die() { echo "ERROR: $*" >&2; exit 1; }

check_artifacts() {
    [ -f "$APPLIANCE_DIR/vmlinuz" ] || die "$APPLIANCE_DIR/vmlinuz not found. Run build.sh first."
    [ -f "$DISK_IMG" ] || [ -f "$APPLIANCE_DIR/rootfs.tar.zst" ] || \
        die "No disk image or rootfs tarball found. Run build.sh first."
}

ensure_disk() {
    [ -f "$DISK_IMG" ] && return 0
    [ -f "$APPLIANCE_DIR/rootfs.tar.zst" ] || die "rootfs.tar.zst not found"
    echo "Creating disk image from rootfs tarball (first run)..."

    if [ "$(uname -s)" = "Darwin" ]; then
        # On macOS, format and populate via the podman machine VM.
        # mkfs.btrfs works directly on files; mount -o loop handles loop setup.
        podman machine ssh << REMOTE
            set -euo pipefail
            truncate -s $DISK_SIZE '$DISK_IMG'
            mkfs.btrfs -q -L isxroot '$DISK_IMG'
            sudo mkdir -p /mnt/isx-disk
            sudo mount -o loop '$DISK_IMG' /mnt/isx-disk
            zstd -d '$APPLIANCE_DIR/rootfs.tar.zst' --stdout | sudo tar xf - -C /mnt/isx-disk
            sudo chmod 755 /mnt/isx-disk
            sudo umount /mnt/isx-disk
REMOTE
    else
        truncate -s "$DISK_SIZE" "$DISK_IMG"
        LOOP_DEV=$(losetup --find --show "$DISK_IMG")
        disk_cleanup() {
            umount /mnt/isx-disk 2>/dev/null || true
            losetup -d "$LOOP_DEV" 2>/dev/null || true
        }
        trap disk_cleanup EXIT
        mkfs.btrfs -q -L isxroot "$LOOP_DEV"
        mkdir -p /mnt/isx-disk
        mount "$LOOP_DEV" /mnt/isx-disk
        zstd -d "$APPLIANCE_DIR/rootfs.tar.zst" --stdout | tar xf - -C /mnt/isx-disk
        umount /mnt/isx-disk
        losetup -d "$LOOP_DEV"
        trap - EXIT
    fi
    echo "Disk image created: $(du -sh "$DISK_IMG" | cut -f1) (${DISK_SIZE} virtual)"
}

is_running() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid=$(cat "$PID_FILE")
    kill -0 "$pid" 2>/dev/null || return 1
    ps -p "$pid" -o comm= 2>/dev/null | grep -qE 'vfkit|qemu' || return 1
}

detect_backend() {
    case "$(uname -s)" in
        Darwin)
            command -v vfkit >/dev/null 2>&1 || die "vfkit not found. Install with: brew install vfkit"
            echo "vfkit"
            ;;
        Linux)
            command -v qemu-system-"$(uname -m)" >/dev/null 2>&1 || die "qemu-system-$(uname -m) not found"
            echo "qemu"
            ;;
        *) die "Unsupported OS: $(uname -s)" ;;
    esac
}

start_vfkit() {
    local rest_port
    rest_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("",0)); print(s.getsockname()[1]); s.close()')

    # vfkit requires --initrd even though our kernel ignores it (CONFIG_BLK_DEV_INITRD=n)
    local dummy_initrd="$STATE_DIR/empty-initrd"
    [ -f "$dummy_initrd" ] || (echo | cpio -o -H newc 2>/dev/null | gzip > "$dummy_initrd")

    vfkit \
        --cpus "$CPUS" --memory "$MEMORY" \
        --kernel "$APPLIANCE_DIR/vmlinuz" \
        --initrd "$dummy_initrd" \
        --kernel-cmdline "root=/dev/vda rw rootflags=commit=300 console=hvc0 quiet mitigations=off isx.gateway=${ISX_GATEWAY:-10.166.11.1} isx.mitm_port=${ISX_MITM_PORT:-18443}" \
        --device virtio-blk,path="$DISK_IMG" \
        --device virtio-net,nat \
        --device virtio-serial,logFilePath="$LOG_FILE" \
        --restful-uri "tcp://localhost:$rest_port" \
        &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    echo "http://localhost:$rest_port" > "$REST_URI_FILE"
    echo "VM started (pid=$pid, rest=localhost:$rest_port)"
}

start_qemu() {
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
        *) die "Unsupported architecture: $arch" ;;
    esac

    $qemu_bin \
        $machine_args \
        -m "$MEMORY" \
        -smp "$CPUS" \
        -nographic \
        -nodefaults \
        -serial stdio \
        -kernel "$APPLIANCE_DIR/vmlinuz" \
        -drive id=root,file="$DISK_IMG",format=raw,if=virtio \
        -netdev user,id=net0 -device virtio-net-pci,netdev=net0 \
        -append "root=/dev/vda rw rootflags=commit=300 console=$console quiet mitigations=off isx.gateway=${ISX_GATEWAY:-10.166.11.1} isx.mitm_port=${ISX_MITM_PORT:-18443}" \
        > "$LOG_FILE" 2>&1 &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    echo "VM started (pid=$pid)"
}

cmd_start() {
    if is_running; then
        echo "VM already running (pid=$(cat "$PID_FILE"))"
        return 0
    fi
    check_artifacts
    ensure_disk
    local backend
    backend=$(detect_backend)
    echo "Starting VM with $backend (cpus=$CPUS, memory=${MEMORY}M)..."
    case "$backend" in
        vfkit) start_vfkit ;;
        qemu)  start_qemu ;;
    esac
}

cmd_stop() {
    if ! is_running; then
        echo "VM not running"
        rm -f "$PID_FILE" "$REST_URI_FILE"
        return 0
    fi
    local pid
    pid=$(cat "$PID_FILE")

    if [ -f "$REST_URI_FILE" ]; then
        local uri
        uri=$(cat "$REST_URI_FILE")
        curl -s -X POST "$uri/vm/state" -d '{"state":"Stop"}' >/dev/null 2>&1 || true
        for _ in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.5
        done
    fi

    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        sleep 1
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE" "$REST_URI_FILE"
    echo "VM stopped"
}

cmd_status() {
    if is_running; then
        echo "VM running (pid=$(cat "$PID_FILE"))"
        [ -f "$REST_URI_FILE" ] && echo "  REST API: $(cat "$REST_URI_FILE")"
        echo "  Log: $LOG_FILE"
    else
        echo "VM not running"
        rm -f "$PID_FILE" "$REST_URI_FILE"
    fi
}

cmd_console() {
    [ -f "$LOG_FILE" ] || die "No log file found"
    tail -f "$LOG_FILE"
}

cmd_shell() {
    is_running && die "VM is running in background. Stop it first: $(basename "$0") stop"
    check_artifacts
    ensure_disk
    local backend
    backend=$(detect_backend)
    echo "Starting interactive VM (exit with Ctrl-A X)..."

    case "$backend" in
        vfkit) die "Interactive shell not supported with vfkit. Use QEMU on Linux." ;;
        qemu)
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
                *) die "Unsupported architecture: $arch" ;;
            esac
            $qemu_bin \
                $machine_args \
                -m "$MEMORY" \
                -smp "$CPUS" \
                -nographic \
                -no-reboot \
                -nodefaults \
                -serial stdio \
                -kernel "$APPLIANCE_DIR/vmlinuz" \
                -drive file="$DISK_IMG",format=raw,if=virtio \
                -netdev user,id=net0 -device virtio-net-pci,netdev=net0 \
                -append "root=/dev/vda rw rootflags=commit=300 console=$console mitigations=off isx.gateway=${ISX_GATEWAY:-10.166.11.1} isx.mitm_port=${ISX_MITM_PORT:-18443}"
            ;;
    esac
}

case "${1:-}" in
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    status)  cmd_status ;;
    console) cmd_console ;;
    shell)   cmd_shell ;;
    *)
        echo "Usage: $(basename "$0") {start|stop|status|console|shell}"
        exit 1
        ;;
esac
