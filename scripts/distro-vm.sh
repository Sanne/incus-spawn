#!/bin/bash
# Boots a distribution's own cloud image as a KVM guest and drives it over ssh, for the
# host-distro integration jobs (#1972): isx init, build, branch and shell run on a host that is
# Arch or Ubuntu as a user installs it, not on the CI runner.
#
# The image is pinned by URL and the publisher's SHA-256, downloaded once into a cache directory
# and verified on every boot. Each guest runs on a copy-on-write overlay, so the cached image is
# never changed. cloud-init (NoCloud seed) creates the user 'tester' with passwordless sudo and a
# key made for this guest; QEMU's user-mode network forwards a loopback port to its sshd.
#
# Usage:
#   scripts/distro-vm.sh boot <dir> <image-url> <sha256> [cache-dir]
#   scripts/distro-vm.sh ssh <dir> [command...]      # a new login each time, stdin passed through
#   scripts/distro-vm.sh push <dir> <local-path>... <remote-dir>
#   scripts/distro-vm.sh reboot <dir>
#   scripts/distro-vm.sh console <dir>               # the guest's serial console so far
#   scripts/distro-vm.sh stop <dir>
#
# Environment: DISTRO_VM_CPUS (default: all), DISTRO_VM_MEMORY (default 8G), DISTRO_VM_DISK
# (default 40G). DISTRO_VM_TRUST names a PEM CA to add to the guest's system trust store at boot:
# run inside an isx instance, the guest's HTTPS goes through that instance's proxy, as it does
# for the instance itself. Requires qemu-system-x86_64 with /dev/kvm, qemu-img, genisoimage (or
# xorriso), ssh, curl and sha256sum.

set -euo pipefail

SSH_USER=tester

die() { echo "distro-vm: $*" >&2; exit 1; }

ssh_opts() {
    local dir=$1
    echo "-i $dir/key -p $(cat "$dir/port") -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null" \
        "-o LogLevel=ERROR -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=8" \
        "-o BatchMode=yes"
}

# A file name of its own for each pin: a bumped image never reuses a stale download.
fetch_image() {
    local url=$1 sha=$2 cache=$3 file
    [[ $sha =~ ^[0-9a-f]{64}$ ]] || die "not a SHA-256: $sha"
    mkdir -p "$cache"
    file="$cache/$sha-$(basename "$url")"
    if [ ! -f "$file" ] || ! echo "$sha  $file" | sha256sum -c --status; then
        echo "Downloading $url" >&2
        curl -fL --retry 5 --retry-all-errors -sS -o "$file.part" "$url"
        mv "$file.part" "$file"
    fi
    echo "$sha  $file" | sha256sum -c --status \
        || { rm -f "$file"; die "$url does not match its pinned SHA-256 $sha"; }
    echo "$file"
}

make_seed() {
    local dir=$1 seed
    seed=$(mktemp -d)
    cat > "$seed/meta-data" <<EOF
instance-id: isx-host-distro
local-hostname: isx-host
EOF
    cat > "$seed/user-data" <<EOF
#cloud-config
users:
  - name: $SSH_USER
    shell: /bin/bash
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    lock_passwd: true
    ssh_authorized_keys:
      - $(cat "$dir/key.pub")
ssh_pwauth: false
EOF
    if command -v genisoimage >/dev/null; then
        genisoimage -quiet -output "$dir/seed.iso" -volid cidata -joliet -rock "$seed/user-data" "$seed/meta-data"
    else
        xorriso -as mkisofs -quiet -output "$dir/seed.iso" -volid cidata -joliet -rock "$seed/user-data" "$seed/meta-data"
    fi
    rm -rf "$seed"
}

free_port() {
    python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'
}

wait_for_ssh() {
    local dir=$1 timeout=${2:-300} deadline
    deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        # shellcheck disable=SC2046
        if ssh $(ssh_opts "$dir") "$SSH_USER@127.0.0.1" true </dev/null 2>/dev/null; then
            return 0
        fi
        kill -0 "$(cat "$dir/pid")" 2>/dev/null || { tail -n 50 "$dir/console.log" >&2; die "QEMU exited"; }
        sleep 2
    done
    tail -n 50 "$dir/console.log" >&2
    die "no ssh after ${timeout}s"
}

boot() {
    local dir=$1 url=$2 sha=$3 cache=${4:-$HOME/.cache/distro-vm} image
    mkdir -p "$dir"
    dir=$(cd "$dir" && pwd)
    image=$(fetch_image "$url" "$sha" "$cache")
    rm -f "$dir/key" "$dir/key.pub"
    ssh-keygen -q -t ed25519 -N '' -C distro-vm -f "$dir/key"
    make_seed "$dir"
    qemu-img create -q -f qcow2 -F qcow2 -b "$image" "$dir/disk.qcow2" "${DISTRO_VM_DISK:-40G}"
    free_port > "$dir/port"
    qemu-system-x86_64 \
        -machine q35,accel=kvm -cpu host \
        -smp "${DISTRO_VM_CPUS:-$(nproc)}" -m "${DISTRO_VM_MEMORY:-8G}" \
        -drive "file=$dir/disk.qcow2,if=virtio,discard=unmap" \
        -drive "file=$dir/seed.iso,if=virtio,media=cdrom,readonly=on" \
        -netdev "user,id=net0,hostfwd=tcp:127.0.0.1:$(cat "$dir/port")-:22" \
        -device virtio-net-pci,netdev=net0 \
        -device virtio-rng-pci \
        -display none -serial "file:$dir/console.log" \
        -daemonize -pidfile "$dir/pid"
    wait_for_ssh "$dir"
    # cloud-init may still be growing the disk or writing the user's files
    remote "$dir" "sudo cloud-init status --wait >/dev/null || true" </dev/null
    [ -z "${DISTRO_VM_TRUST:-}" ] || trust_ca "$dir" "$DISTRO_VM_TRUST"
    echo "Guest up: $(remote "$dir" '. /etc/os-release; echo "$PRETTY_NAME, kernel $(uname -r)"' </dev/null)"
}

trust_ca() {
    local dir=$1 pem=$2
    remote "$dir" 'if [ -d /etc/ca-certificates/trust-source/anchors ]; then
            sudo tee /etc/ca-certificates/trust-source/anchors/distro-vm.crt >/dev/null && sudo update-ca-trust
        else
            sudo tee /usr/local/share/ca-certificates/distro-vm.crt >/dev/null && sudo update-ca-certificates >/dev/null 2>&1
        fi' < "$pem"
}

remote() {
    local dir=$1; shift
    # shellcheck disable=SC2046
    ssh $(ssh_opts "$dir") "$SSH_USER@127.0.0.1" "$@"
}

push() {
    local dir=$1; shift
    local target=${*: -1} port
    port=$(cat "$dir/port")
    remote "$dir" "mkdir -p '$target'" </dev/null
    scp -q -r -i "$dir/key" -P "$port" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -o LogLevel=ERROR -o BatchMode=yes "${@:1:$#-1}" "$SSH_USER@127.0.0.1:$target/"
}

reboot_guest() {
    local dir=$1
    remote "$dir" "sudo systemctl reboot" </dev/null || true
    # Until sshd is gone, a probe would reach the old boot
    local deadline=$((SECONDS + 60))
    # shellcheck disable=SC2046
    while [ $SECONDS -lt $deadline ] && ssh $(ssh_opts "$dir") "$SSH_USER@127.0.0.1" true </dev/null 2>/dev/null; do
        sleep 1
    done
    wait_for_ssh "$dir"
    echo "Guest rebooted: kernel $(remote "$dir" 'uname -r' </dev/null)"
}

stop() {
    local dir=$1 pid
    [ -f "$dir/pid" ] || return 0
    pid=$(cat "$dir/pid")
    remote "$dir" "sudo systemctl poweroff" </dev/null 2>/dev/null || true
    for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 1
    done
    kill "$pid" 2>/dev/null || true
}

[ $# -ge 2 ] || die "usage: $0 boot|ssh|push|reboot|console|stop <dir> ..."
cmd=$1; shift
case "$cmd" in
    boot)    [ $# -ge 3 ] || die "usage: $0 boot <dir> <image-url> <sha256> [cache-dir]"; boot "$@" ;;
    ssh)     remote "$@" ;;
    push)    [ $# -ge 3 ] || die "usage: $0 push <dir> <local-path>... <remote-dir>"; push "$@" ;;
    reboot)  reboot_guest "$1" ;;
    console) cat "$1/console.log" ;;
    stop)    stop "$1" ;;
    *)       die "unknown command: $cmd" ;;
esac
