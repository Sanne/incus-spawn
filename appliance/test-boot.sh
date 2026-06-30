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
VSOCK_RESULT=$(mktemp)
VSOCK_DIR=""
BACKEND=""
cleanup() { rm -f "$LOGFILE" "$VSOCK_RESULT"; [ -n "$VSOCK_DIR" ] && rm -rf "$VSOCK_DIR"; }
trap cleanup EXIT

# Verify the Incus API is reachable over the forwarded vsock socket — the exact
# path isx uses on macOS: host Unix socket -> vfkit vsock -> in-guest socat
# forwarder -> /var/lib/incus/unix.socket. Also confirm the daemon reports the
# expected storage pool and bridge through that socket. Polls until incusd
# answers (it comes up during boot) or a short deadline elapses. Markers go to
# VSOCK_RESULT, NOT the serial LOGFILE: vfkit streams the boot log into LOGFILE
# concurrently, and interleaved appends from this shell were being lost.
probe_vsock() {
    local sock="$1" deadline body=""
    deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ -S "$sock" ]; then
            body=$(curl -s --max-time 5 --unix-socket "$sock" http://localhost/1.0 2>/dev/null) || body=""
            echo "$body" | grep -q '"metadata"' && break
        fi
        sleep 1
    done
    if echo "$body" | grep -q '"metadata"'; then
        echo "ISX VSOCK API OK" >> "$VSOCK_RESULT"
    else
        echo "ISX VSOCK API FAIL: no Incus response over forwarded socket" >> "$VSOCK_RESULT"
        return
    fi
    curl -s --max-time 5 --unix-socket "$sock" http://localhost/1.0/storage-pools 2>/dev/null \
        | grep -q 'storage-pools/cow' && echo "ISX VSOCK STORAGE OK" >> "$VSOCK_RESULT"
    curl -s --max-time 5 --unix-socket "$sock" http://localhost/1.0/networks 2>/dev/null \
        | grep -q 'incusbr0' && echo "ISX VSOCK BRIDGE OK" >> "$VSOCK_RESULT"
}

# Verify the in-guest control agent answers over its dedicated vsock port (the channel
# isx doctor uses for introspection/recovery). One verb per connection: send "ping",
# expect "ok"; then record the socat-count it reports.
probe_agent() {
    local sock="$1" deadline resp=""
    deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ -S "$sock" ]; then
            resp=$(printf 'ping\n' | nc -U -w 5 "$sock" 2>/dev/null | tr -d '\r\n') || resp=""
            [ "$resp" = "ok" ] && break
        fi
        sleep 1
    done
    if [ "$resp" = "ok" ]; then
        echo "ISX AGENT OK" >> "$VSOCK_RESULT"
        local count
        count=$(printf 'socat-count\n' | nc -U -w 5 "$sock" 2>/dev/null | tr -d '\r\n') || count=""
        echo "ISX AGENT SOCAT COUNT: $count" >> "$VSOCK_RESULT"
    else
        echo "ISX AGENT FAIL: no response from control agent" >> "$VSOCK_RESULT"
    fi
}

boot_vfkit() {
    BACKEND="vfkit"
    echo "  backend: vfkit (Apple Virtualization.framework)"
    # vfkit requires --initrd even though our kernel ignores it (CONFIG_BLK_DEV_INITRD=n)
    local dummy_initrd
    dummy_initrd=$(mktemp)
    echo | cpio -o -H newc 2>/dev/null | gzip > "$dummy_initrd"
    # Forward the in-guest Incus socket to a host Unix socket over vsock, the
    # same way isx does (VmManager). isx.vsock_incus tells vm-init to start the
    # socat forwarder on that vsock port. We do NOT set isx.smoke_test here: the
    # smoke test redirects its output to a serial console that does not exist
    # under vfkit (which uses hvc0), so it blocks boot before ISX READY. The
    # vsock probe below verifies the daemon directly instead, which is the path
    # that actually matters on macOS.
    VSOCK_DIR=$(mktemp -d)
    local vsock_sock="$VSOCK_DIR/incus.sock"
    local agent_sock="$VSOCK_DIR/agent.sock"
    vfkit \
        --cpus 2 --memory 2048 \
        --kernel "$BUILD_DIR/vmlinuz" \
        --initrd "$dummy_initrd" \
        --kernel-cmdline "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=hvc0 isx.vsock_incus=8443 isx.agent_vsock=1025" \
        --device virtio-blk,path="$BUILD_DIR/disk.img" \
        --device virtio-net,nat \
        --device virtio-serial,logFilePath="$LOGFILE" \
        --device "virtio-vsock,port=8443,socketURL=$vsock_sock,connect" \
        --device "virtio-vsock,port=1025,socketURL=$agent_sock,connect" \
        --restful-uri "tcp://localhost:0" \
        > /dev/null 2>&1 &
    local pid=$!
    local elapsed=0
    while [ "$elapsed" -lt "$((TIMEOUT * 10))" ]; do
        if grep -q 'ISX READY' "$LOGFILE" 2>/dev/null; then
            local ms=$((elapsed * 100))
            echo "  ISX READY in ~${ms}ms"
            break
        fi
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    # Probe the forwarded socket regardless of ISX READY (the daemon is up well
    # before readiness; the probe polls on its own).
    probe_vsock "$vsock_sock"
    probe_agent "$agent_sock"
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    rm -f "$dummy_initrd"
}

boot_qemu() {
    BACKEND="qemu"
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
        -append "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=$console isx.smoke_test=1" \
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

# Assert a pattern is present in the boot log.
check() {
    if grep -q "$1" "$LOGFILE"; then
        echo "  PASS: $2"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $2"
        FAIL=$((FAIL + 1))
    fi
}

# Assert a marker written by probe_vsock (kept in a separate file from the
# concurrently-written serial log).
check_result() {
    if grep -q "$1" "$VSOCK_RESULT"; then
        echo "  PASS: $2"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $2"
        FAIL=$((FAIL + 1))
    fi
}

# Assert a pattern is ABSENT (regression / failure markers must not appear).
check_absent() {
    if grep -q "$1" "$LOGFILE"; then
        echo "  FAIL: $2"
        FAIL=$((FAIL + 1))
    else
        echo "  PASS: $2"
        PASS=$((PASS + 1))
    fi
}

# Boot-stage markers logged by rcS/vm-init on every boot (independent of
# whether the bridge/storage pool already existed on a reused disk; those are
# verified every boot by the smoke test section below instead).
echo "-- Boot stages --"
check "BTRFS\|btrfs"                          "btrfs root mounted"
check "network up on"                          "network came up"
check "chronyd started"                        "chrony NTP service started"
check "incusd started"                         "incus daemon launched"
check "incus-spawn-vm-init: ready"             "vm-init completed"
check "ISX READY"                              "appliance reached ISX READY"

# On vfkit the daemon is verified through the forwarded vsock socket (the macOS
# isx path). On qemu (Linux) that forwarding is not wired up, so use the
# in-guest smoke test, which CI runs the same way.
if [ "$BACKEND" = "vfkit" ]; then
    echo
    echo "-- vsock Incus socket forwarding (isx.vsock_incus=8443) --"
    check "vsock forwarder on port 8443"       "in-guest vsock forwarder started"
    check_result "ISX VSOCK API OK"            "Incus API reachable over forwarded host socket"
    check_result "ISX VSOCK STORAGE OK"        "cow storage pool visible via API"
    check_result "ISX VSOCK BRIDGE OK"         "incusbr0 bridge visible via API"
    echo
    echo "-- Control agent (isx.agent_vsock=1025) --"
    check "control agent on vsock port 1025"   "in-guest control agent started"
    check_result "ISX AGENT OK"                "control agent answered ping over vsock"
else
    echo
    echo "-- Smoke test (isx.smoke_test=1) --"
    check "SMOKE TEST START"                    "smoke test ran"
    check "incus daemon responsive"             "incus API responsive"
    check "storage pool 'cow' exists"           "smoke test: storage pool"
    check "bridge 'incusbr0' exists"            "smoke test: bridge"
    check "SMOKE TEST PASSED"                    "smoke test passed"
fi

echo
echo "-- Regression markers (must be absent) --"
check_absent "SMOKE TEST FAILED"               "no smoke test failure"
check_absent "incus-spawn-vm-init: ERROR"      "no vm-init error"
check_absent "Daemon still not running"        "incusd did not time out"
check_absent "Kernel panic"                    "no kernel panic"
check_absent "Call Trace:\|kernel BUG"         "no kernel oops/BUG"

echo
if [ "$FAIL" -eq 0 ]; then
    echo "All $PASS checks passed."
else
    echo "$FAIL of $((PASS + FAIL)) checks failed."
    echo
    echo "Last 40 log lines:"
    tail -40 "$LOGFILE" | sed 's/\x1b\[[0-9;]*m//g'
    exit 1
fi
