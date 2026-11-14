#!/bin/bash
set -euo pipefail

# Boot the appliance image and verify it reaches ISX READY state.
#
# On macOS: uses vfkit (Apple Virtualization.framework)
# On Linux: uses QEMU (with KVM if available)
#
# Usage:  ./test-boot.sh [build-dir] [timeout-seconds]
#
# SERIAL_LOG=<file> keeps the guest's serial console in <file>, which is
# otherwise a temporary file deleted on exit (test-kernel-on-mac.sh, #2033).
#
# Under vfkit two of test-tunnel.sh's checks fail on vfkit's own leak (#785) and
# are reported as KNOWN, not FAIL (its TUNNEL_KNOWN_LEAK=vfkit). That exception
# is tracked in #2031 and goes when the isx launcher replaces vfkit.
# TUNNEL_KNOWN_LEAK= (empty) keeps both checks strict.
#
# VFKIT=<program> boots with that program where vfkit would run, on macOS. It is
# how the launcher's prototype, isx-vm, is put through the same boot and the same
# tunnel checks as vfkit: it reads the arguments this script gives vfkit
# (launcher-on-mac.sh, #2031). HYPERVISOR_LOG=<file> keeps what that program
# itself printed, which is otherwise discarded. The exception above is vfkit's
# alone: under another program both checks are strict unless the caller says
# otherwise.
#
# WHILE_UP=<program> runs "<program> <incus-socket> <agent-socket> <silent-socket>"
# on macOS once the guest is ready and before the tunnel checks, with the
# hypervisor's pid in HYPERVISOR_PID. It is how a machine is measured between
# its boot and its checks (measure-tunnel.sh). The program's exit status does
# not change this script's result: the checks that follow say what it left.

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

VFKIT="${VFKIT:-vfkit}"
# What ran, for the messages: vfkit, or the program given in its place.
HYPERVISOR="$(basename "$VFKIT")"
LOGFILE="${SERIAL_LOG:-$(mktemp)}"
: > "$LOGFILE"
VSOCK_RESULT=$(mktemp)
VSOCK_DIR=""
BACKEND=""
TUNNEL_RC=0
# An `if`, not `[ ] && rm`: the trap's last status becomes the script's exit
# status, and VSOCK_DIR is always empty on the QEMU path.
cleanup() { [ -n "${SERIAL_LOG:-}" ] || rm -f "$LOGFILE"; rm -f "$VSOCK_RESULT"; if [ -n "$VSOCK_DIR" ]; then rm -rf "$VSOCK_DIR"; fi; }
trap cleanup EXIT

boot_vfkit() {
    BACKEND="vfkit"
    echo "  backend: $HYPERVISOR (Apple Virtualization.framework)"
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
    # The test-only silent endpoint (isx.test_silent_vsock) that TUNNEL_BACKSTOP=1 times.
    local silent_sock="$VSOCK_DIR/silent.sock"
    "$VFKIT" \
        --cpus 2 --memory 2048 \
        --kernel "$BUILD_DIR/vmlinuz" \
        --initrd "$dummy_initrd" \
        --kernel-cmdline "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=hvc0 isx.vsock_incus=8443 isx.agent_vsock=1025 isx.test_silent_vsock=8444" \
        --device virtio-blk,path="$BUILD_DIR/disk.img" \
        --device virtio-net,nat \
        --device virtio-serial,logFilePath="$LOGFILE" \
        --device "virtio-vsock,port=8443,socketURL=$vsock_sock,connect" \
        --device "virtio-vsock,port=1025,socketURL=$agent_sock,connect" \
        --device "virtio-vsock,port=8444,socketURL=$silent_sock,connect" \
        --restful-uri "tcp://localhost:0" \
        > "${HYPERVISOR_LOG:-/dev/null}" 2>&1 &
    local pid=$!
    local elapsed=0 ready=0
    while [ "$elapsed" -lt "$((TIMEOUT * 10))" ]; do
        if grep -q 'ISX READY' "$LOGFILE" 2>/dev/null; then
            ready=1
            local ms=$((elapsed * 100))
            echo "  ISX READY in ~${ms}ms"
            break
        fi
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    if [ -n "${WHILE_UP:-}" ] && [ "$ready" = 0 ]; then
        echo "  not running $(basename "$WHILE_UP"): the guest was not ready within ${TIMEOUT}s"
    elif [ -n "${WHILE_UP:-}" ]; then
        echo "  running $(basename "$WHILE_UP") while the guest is up"
        # The status first: the $(basename) below would replace it.
        HYPERVISOR_PID=$pid "$WHILE_UP" "$vsock_sock" "$agent_sock" "$silent_sock" \
            || { local while_up_rc=$?; echo "  $(basename "$WHILE_UP") exited $while_up_rc"; }
    fi
    # Probe the forwarded sockets regardless of ISX READY (the daemon is up well
    # before readiness; the probes poll on their own). Results go to VSOCK_RESULT,
    # NOT the serial LOGFILE: vfkit streams the boot log into LOGFILE concurrently,
    # and interleaved appends from this shell were being lost.
    # TUNNEL_SHUTDOWN: its last check asks the agent to shut the guest down (#881). The
    # hypervisor must then exit by itself, after rcK ran; only a guest that did not is killed.
    # TUNNEL_KNOWN_LEAK: vfkit's own leak is reported, not failed, until the isx launcher
    # replaces vfkit (#2031); a caller's empty value keeps the two checks strict. The leak
    # is the relay's in vfkit, so no other program gets the exception by default.
    local known_leak=""
    [ "$HYPERVISOR" = vfkit ] && known_leak=vfkit
    TUNNEL_SHUTDOWN=1 TUNNEL_KNOWN_LEAK="${TUNNEL_KNOWN_LEAK-$known_leak}" "$(dirname "$0")/test-tunnel.sh" "$vsock_sock" "$agent_sock" "$silent_sock" > "$VSOCK_RESULT" 2>&1 \
        || TUNNEL_RC=$?
    local waited=0
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 300 ]; do sleep 0.1; waited=$((waited + 1)); done
    if kill -0 "$pid" 2>/dev/null; then
        echo "  FAIL: $HYPERVISOR still running 30s after the agent's shutdown" >> "$VSOCK_RESULT"
        TUNNEL_RC=1
        kill "$pid" 2>/dev/null || true
    elif grep -q 'incus-spawn: shutting down' "$LOGFILE"; then
        echo "  PASS: the guest ran rcK and powered off, and $HYPERVISOR exited by itself" >> "$VSOCK_RESULT"
    else
        echo "  FAIL: $HYPERVISOR exited without the guest running rcK" >> "$VSOCK_RESULT"
        TUNNEL_RC=1
    fi
    wait "$pid" 2>/dev/null || true
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
        -netdev user,id=net0 -device virtio-net-pci,netdev=net0 \
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

if [ "$VFKIT" != "vfkit" ] && ! command -v "$VFKIT" >/dev/null 2>&1; then
    # Never QEMU instead: the caller asked for one program's result.
    echo "ERROR: VFKIT=$VFKIT is not a program that can be run" >&2
    exit 1
fi
if [ "$VFKIT" != "vfkit" ] && [ "$(uname -s)" != "Darwin" ]; then
    echo "ERROR: VFKIT=$VFKIT is for macOS; this host boots with QEMU" >&2
    exit 1
fi
if [ "$(uname -s)" = "Darwin" ] && command -v "$VFKIT" >/dev/null 2>&1; then
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
    echo "-- vsock tunnel (isx.vsock_incus=8443, isx.agent_vsock=1025) --"
    check "vsock forwarder on port 8443"       "in-guest vsock forwarder started"
    check "control agent on vsock port 1025"   "in-guest control agent started"
    echo
    sed 's/^/  /' "$VSOCK_RESULT"
    [ "$TUNNEL_RC" -eq 0 ] || FAIL=$((FAIL + 1))
else
    echo
    echo "-- Smoke test (isx.smoke_test=1) --"
    check "SMOKE TEST START"                    "smoke test ran"
    check "incus daemon responsive"             "incus API responsive"
    check "storage pool 'cow' exists"           "smoke test: storage pool"
    check "bridge 'incusbr0' exists"            "smoke test: bridge"
    check "inotify instance limit raised"       "smoke test: per-UID inotify budget"
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
# A failure allowed as known is still listed, and counted here, so no run hides it (#2031).
KNOWN=$(grep -c '^[[:space:]]*KNOWN:' "$VSOCK_RESULT" || true)
if [ "$FAIL" -eq 0 ] && [ "$KNOWN" -gt 0 ]; then
    echo "All $PASS checks passed, with $KNOWN known failures of vfkit allowed (KNOWN above, #2031)."
elif [ "$FAIL" -eq 0 ]; then
    echo "All $PASS checks passed."
else
    echo "$FAIL of $((PASS + FAIL)) checks failed."
    [ "$KNOWN" -eq 0 ] || echo "$KNOWN known failures of vfkit were allowed besides (KNOWN above, #2031)."
    echo
    echo "Last 40 log lines:"
    tail -40 "$LOGFILE" | sed 's/\x1b\[[0-9;]*m//g'
    exit 1
fi
