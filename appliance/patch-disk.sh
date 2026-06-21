#!/bin/bash
set -euo pipefail

# Mount the VM root disk image and run a command on the mounted filesystem.
# Works on macOS (via podman machine) and Linux (direct loop mount).
# The VM must be stopped first — the disk cannot be mounted while in use.
#
# Usage: ./patch-disk.sh [disk.img] -- <command> [args...]
#
# The disk is mounted at /mnt/disk inside the mount context.
# Reference files as /mnt/disk/path/to/file in your command.
#
# Examples:
#   ./patch-disk.sh -- cat /mnt/disk/etc/isx-version
#   ./patch-disk.sh -- grep unix.socket /mnt/disk/usr/local/sbin/incus-spawn-vm-init
#   ./patch-disk.sh -- sed -i 's|old|new|' /mnt/disk/usr/local/sbin/incus-spawn-vm-init

STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/incus-spawn"
PID_FILE="$STATE_DIR/vm.pid"
MOUNT_POINT="/mnt/disk"

# Parse arguments: [disk.img] -- command [args...]
DISK_IMG=""
CMD_ARGS=()
found_separator=false
for arg in "$@"; do
    if [ "$arg" = "--" ]; then
        found_separator=true
        continue
    fi
    if $found_separator; then
        CMD_ARGS+=("$arg")
    else
        DISK_IMG="$arg"
    fi
done

[ -z "$DISK_IMG" ] && DISK_IMG="$STATE_DIR/disk.img"
[ ${#CMD_ARGS[@]} -eq 0 ] && { echo "Usage: $(basename "$0") [disk.img] -- <command> [args...]" >&2; exit 1; }
[ -f "$DISK_IMG" ] || { echo "ERROR: $DISK_IMG not found" >&2; exit 1; }

# Refuse to run if VM is using the disk
if [ -f "$PID_FILE" ]; then
    pid=$(cat "$PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
        echo "ERROR: VM is running (pid=$pid). Stop it first: isx vm stop" >&2
        exit 1
    fi
fi

ABS_DISK="$(cd "$(dirname "$DISK_IMG")" && pwd)/$(basename "$DISK_IMG")"

if [ "$(uname -s)" = "Darwin" ]; then
    command -v podman >/dev/null 2>&1 || { echo "ERROR: podman required on macOS" >&2; exit 1; }

    # Build the command string for remote execution, escaping each argument
    escaped_cmd=""
    for arg in "${CMD_ARGS[@]}"; do
        escaped_cmd+="'${arg//\'/\'\\\'\'}' "
    done

    podman machine ssh << REMOTE
        set -euo pipefail
        sudo mkdir -p $MOUNT_POINT
        sudo mount -o loop '$ABS_DISK' $MOUNT_POINT
        trap 'sudo umount $MOUNT_POINT 2>/dev/null || true' EXIT
        sudo $escaped_cmd
REMOTE
else
    LOOP_DEV=""
    cleanup() {
        umount "$MOUNT_POINT" 2>/dev/null || true
        [ -n "$LOOP_DEV" ] && losetup -d "$LOOP_DEV" 2>/dev/null || true
    }
    trap cleanup EXIT

    mkdir -p "$MOUNT_POINT"
    LOOP_DEV=$(losetup --find --show "$ABS_DISK")
    mount "$LOOP_DEV" "$MOUNT_POINT"

    "${CMD_ARGS[@]}"

    umount "$MOUNT_POINT"
    losetup -d "$LOOP_DEV"
    LOOP_DEV=""
    trap - EXIT
fi
