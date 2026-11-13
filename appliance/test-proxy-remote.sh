#!/usr/bin/env bash
set -uo pipefail

# Boot the appliance in remote proxy mode (isx.proxy=remote, as isx boots it on macOS) and check
# the data path between a container and a proxy outside the VM: the guest's DNAT of the bridge
# gateway's port 443 to the host, and the masquerade on the way out.
#
# On macOS vfkit NATs the VM onto a host bridge, and isx-proxy listens on the host side of it.
# Here a tap device plays that bridge: the host holds its first address and serves the VM a DHCP
# lease on it, so the guest's default route is the host, as under vfkit. A socat listener on the
# host's MITM port stands in for isx-proxy. It answers every connection with the address it came
# from and the first line it read, which is what the proxy has to identify a caller by.
#
# This proves the guest half of the macOS path. The real isx-proxy, in its macOS role, is
# test-macos-role.sh's (#1800).
#
# Needs root (tap device, DHCP server), qemu-system-<arch>, socat, dnsmasq, the incus client,
# a statically linked busybox (busybox-static on Ubuntu, busybox on Fedora) and /dev/vhost-vsock.
# The containers' image is built here from that busybox, not fetched from
# images.linuxcontainers.org, so an outage of that server cannot fail this test (#1558).
#
# Usage:  sudo ./test-proxy-remote.sh [build-dir]
#
#   build-dir          holds vmlinuz and disk.img (default: ./build beside this script). The disk
#                      is booted with snapshot=on, so the test leaves it as it found it.
#   BOOT_DEADLINE      seconds to wait for ISX READY (default 120; CI raises it under TCG).
#   ACCEL              QEMU acceleration arguments (default: KVM when /dev/kvm is usable).
#   CONSOLE_LOG        where to keep the VM's console (default: a temporary file).
#   VSOCK_BRIDGE       path of isx-vsock-bridge (launcher/, #2031), to bridge the Incus vsock port
#                      with the relay isx is to ship in place of vfkit's. Default: socat.
#
# Prints one PASS/FAIL line per check and exits non-zero if any check failed.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${1:-$SCRIPT_DIR/build}"
BOOT_DEADLINE="${BOOT_DEADLINE:-120}"

# The VM's network, standing in for vfkit's NAT (192.168.64.0/24 there). The host is .1.
TAP=isxtap0
NET_PREFIX=192.168.177
HOST_IP=$NET_PREFIX.1
# What isx boots the appliance with on macOS (VmManager): the bridge gateway, the MITM port.
GATEWAY=10.166.11.1
MITM_PORT=18443
# Two containers at isx-style static addresses, filtered as isx filters them.
C1_IP=10.166.11.201
C2_IP=10.166.11.202

die() { echo "ERROR: $*" >&2; exit 2; }
[ "$(id -u)" -eq 0 ] || die "needs root for the tap device and its DHCP server (run with sudo)"
[ -f "$BUILD_DIR/vmlinuz" ] || die "$BUILD_DIR/vmlinuz not found"
[ -f "$BUILD_DIR/disk.img" ] || die "$BUILD_DIR/disk.img not found (test-boot.sh creates it from rootfs.tar.zst)"
for tool in socat dnsmasq incus ip busybox; do
    command -v "$tool" >/dev/null || die "$tool not found"
done
# It is copied into a rootfs that has no libc.
if ldd "$(command -v busybox)" >/dev/null 2>&1; then
    die "busybox is dynamically linked; install a static one (busybox-static on Ubuntu)"
fi

ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  QEMU=qemu-system-x86_64;  MACHINE=pc;   CONSOLE=ttyS0 ;;
    aarch64) QEMU=qemu-system-aarch64; MACHINE=virt; CONSOLE=ttyAMA0 ;;
    *) die "unsupported architecture: $ARCH" ;;
esac
command -v "$QEMU" >/dev/null || die "$QEMU not found"
if [ -z "${ACCEL:-}" ]; then
    if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then ACCEL="-enable-kvm -cpu host"; else ACCEL="-cpu max"; fi
fi

WORK=$(mktemp -d)
CONSOLE_LOG="${CONSOLE_LOG:-$WORK/console.log}"
# A guest CID is unique per host kernel, so pick one no other test VM is likely to hold.
CID=$(( 1000 + $$ % 50000 ))
QEMU_PID=""
PIDS=()

cleanup() {
    for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
    if [ -n "$QEMU_PID" ]; then kill "$QEMU_PID" 2>/dev/null; wait "$QEMU_PID" 2>/dev/null; fi
    ip link del "$TAP" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

PASS=0
FAIL=0
pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
summary() {
    echo
    if [ "$FAIL" -eq 0 ]; then
        echo "All $PASS remote proxy checks passed."
        exit 0
    fi
    echo "$FAIL of $((PASS + FAIL)) remote proxy checks failed."
    echo "Last 30 console lines:"
    tail -30 "$CONSOLE_LOG" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g; s/^/  /'
    # The EXIT trap removes $WORK, so show what the host side logged first.
    for log in dnsmasq listener; do
        [ -s "$WORK/$log.log" ] && { echo "$log:"; sed 's/^/  /' "$WORK/$log.log"; }
    done
    exit 1
}
# $3, when given, is shown on failure. require stops at a check the rest depend on.
check() { if eval "$1"; then pass "$2"; else fail "$2${3:+ ($3)}"; fi; }
require() { check "$@" || true; [ "$FAIL" -eq 0 ] || summary; }

echo "-- Host side of the VM network (vfkit's NAT stand-in) --"
ip link del "$TAP" 2>/dev/null
ip tuntap add dev "$TAP" mode tap || die "could not create $TAP"
ip addr add "$HOST_IP/24" dev "$TAP"
ip link set "$TAP" up
# DHCP only (port=0: no DNS). The guest's own dnsmasq names nothing the test needs.
dnsmasq --keep-in-foreground --port=0 --interface="$TAP" --bind-interfaces --except-interface=lo \
    --dhcp-range="$NET_PREFIX.10,$NET_PREFIX.20,1h" --dhcp-option=option:router,"$HOST_IP" \
    --dhcp-leasefile="$WORK/leases" --pid-file="$WORK/dnsmasq.pid" 2>"$WORK/dnsmasq.log" &
PIDS+=($!)

# The stand-in for isx-proxy, on the address and port isx-proxy listens on for the VM. Each
# connection gets back who it came from, as the proxy sees it, and the first line it sent.
printf '%s\n' '#!/bin/sh' 'read -r line' 'printf "peer=%s line=%s\n" "$SOCAT_PEERADDR" "$line"' > "$WORK/listener"
chmod 755 "$WORK/listener"
socat TCP-LISTEN:"$MITM_PORT",bind="$HOST_IP",reuseaddr,fork EXEC:"$WORK/listener" 2>"$WORK/listener.log" &
PIDS+=($!)
# A listener left by an earlier run would answer in this one's place.
sleep 0.5
kill -0 "${PIDS[-1]}" 2>/dev/null || die "could not listen on $HOST_IP:$MITM_PORT: $(cat "$WORK/listener.log")"

echo
echo "-- Boot (isx.proxy=remote) --"
# snapshot=on: writes go to a temporary overlay, so disk.img is unchanged afterwards. The
# cmdline is VmManager.kernelCmdline()'s, less the keys this test needs no part of (the agent
# and guest-agent vsock ports, the shared folder, the clock).
# shellcheck disable=SC2086
"$QEMU" -machine "$MACHINE" $ACCEL \
    -m 2G -smp 2 -nographic -no-reboot -nodefaults -serial stdio \
    -kernel "$BUILD_DIR/vmlinuz" \
    -drive file="$BUILD_DIR/disk.img",format=raw,if=virtio,snapshot=on \
    -netdev tap,id=net0,ifname="$TAP",script=no,downscript=no \
    -device virtio-net-pci,netdev=net0 \
    -device vhost-vsock-pci,guest-cid="$CID" \
    -append "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=$CONSOLE mitigations=off isx.gateway=$GATEWAY isx.mitm_port=$MITM_PORT isx.proxy=remote isx.vsock_incus=8443" \
    < /dev/null > "$CONSOLE_LOG" 2>&1 &
QEMU_PID=$!

start=$(date +%s)
until grep -q 'ISX READY' "$CONSOLE_LOG" 2>/dev/null; do
    if ! kill -0 "$QEMU_PID" 2>/dev/null || [ $(( $(date +%s) - start )) -ge "$BOOT_DEADLINE" ]; then
        fail "appliance reached ISX READY"
        summary
    fi
    sleep 1
done
pass "appliance reached ISX READY after $(( $(date +%s) - start ))s"
# vm-init's own line: the kernel's command line, also on the console, names the mode too.
require 'grep -q "incus-spawn-vm-init: .*proxy=remote" "$CONSOLE_LOG"' "vm-init read isx.proxy=remote"

VM_IP=$(awk '{print $3}' "$WORK/leases" 2>/dev/null | head -1)
require '[ -n "$VM_IP" ]' "the VM leased an address on the host network (${VM_IP:-none})"

# The Incus API through the vsock forwarder, as isx reaches it on macOS.
if [ -n "${VSOCK_BRIDGE:-}" ]; then
    "$VSOCK_BRIDGE" "$CID" "$WORK/incus.sock"=8443 > /dev/null 2>&1 &
else
    socat UNIX-LISTEN:"$WORK/incus.sock",fork,backlog=128 VSOCK-CONNECT:"$CID":8443 2>/dev/null &
fi
PIDS+=($!)
export INCUS_SOCKET="$WORK/incus.sock" INCUS_CONF="$WORK/incus-client"
# Every call is bounded, so a request the tunnel loses fails its check instead of the job. And
# none reads stdin: given one that is not a terminal, incus init waits to read a config from it.
INCUS_BIN=$(command -v incus)
incus() { timeout 120 "$INCUS_BIN" "$@" < /dev/null; }
api_up() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        incus info >/dev/null 2>&1 && return 0
        sleep 1
    done
    return 1
}
require 'api_up' "Incus API reachable over the vsock forwarder"

echo
echo "-- Containers on the VM's bridge --"
# A static busybox is the whole image: sh, ip, nc and timeout are all the test runs in it.
build_image() {
    local img="$WORK/img"
    mkdir -p "$img/rootfs/bin" "$img/rootfs/sbin" "$img/rootfs/etc" \
        "$img/rootfs/dev" "$img/rootfs/proc" "$img/rootfs/sys"
    install -d -m 1777 "$img/rootfs/tmp"
    cp "$(command -v busybox)" "$img/rootfs/bin/busybox"
    ln -s busybox "$img/rootfs/bin/sh"
    ln -s ../bin/busybox "$img/rootfs/sbin/init"
    echo '::respawn:/bin/busybox sleep 3600' > "$img/rootfs/etc/inittab"
    printf 'architecture: %s\ncreation_date: %s\nproperties:\n  description: test-proxy-remote\n' \
        "$ARCH" "$(date +%s)" > "$img/metadata.yaml"
    tar -C "$img" --owner=0 --group=0 --numeric-owner -czf "$img/image.tar.gz" metadata.yaml rootfs \
        && incus image import "$img/image.tar.gz" --alias isx-test --quiet
}
require 'build_image' "imported a busybox image into the appliance"
# busybox runs no DHCP client, so the container sets up the address its NIC was given, as
# an isx instance's static .network does.
launch() {
    incus init local:isx-test "$1" --device eth0,ipv4.address="$2" --device eth0,security.ipv4_filtering=true --quiet \
        || { echo "  incus init $1 failed ($?)"; return 1; }
    incus start "$1" --quiet || { echo "  incus start $1 failed ($?)"; return 1; }
    incus exec "$1" -- /bin/busybox sh -c \
        "busybox ip link set eth0 up && busybox ip addr add $2/24 dev eth0 && busybox ip route add default via $GATEWAY" \
        || { echo "  configuring $1's network failed ($?)"; return 1; }
}
require 'launch c1 "$C1_IP" && launch c2 "$C2_IP"' "started two containers at static addresses with ipv4 filtering"

# Ask through the gateway's port 443, as a tool in an instance reaches the proxy. The reply is
# what the host-side listener saw.
through_gateway() {
    local deadline=$(( $(date +%s) + 30 )) reply
    while [ "$(date +%s)" -lt "$deadline" ]; do
        reply=$(incus exec "$1" -- /bin/busybox sh -c \
            "echo 'GET /from-$1 HTTP/1.1' | busybox timeout 5 busybox nc $GATEWAY 443" 2>/dev/null | tr -d '\r')
        if [ -n "$reply" ]; then echo "$reply"; return; fi
        sleep 1
    done
}

echo
echo "-- Data path: gateway:443 -> DNAT -> host:$MITM_PORT --"
r1=$(through_gateway c1)
r2=$(through_gateway c2)
require '[ -n "$r1" ]' "a container's connection to $GATEWAY:443 reached the host on port $MITM_PORT" "'$r1'"
# The premise of identifying macOS callers by their proof (#1582): the proxy sees the VM, never
# the instance, so an address names no instance.
check '[ "$r1" = "peer=$VM_IP line=GET /from-c1 HTTP/1.1" ]' \
    "the request arrived intact, from the VM's address ($VM_IP), not the container's ($C1_IP)" "'$r1'"
check '[ "$r2" = "peer=$VM_IP line=GET /from-c2 HTTP/1.1" ]' \
    "a second container's request arrived from the same VM address ($VM_IP)" "'$r2'"

summary
