#!/bin/bash
# scripts/local-incus.sh — Bring up a real Incus on this Linux host for end-to-end checks of isx
#
# Mirrors the setup of CI's isx-integration-tests-native job, so what works here is what CI
# tests: install the packages, run `isx init` non-interactively (subuid/subgid, the btrfs 'cow'
# pool, the bridge, firewall, CA), then point the default profile at the 'cow' pool. Safe to
# re-run: every step skips work already done.
#
# Meant for a disposable machine (an isx instance, a scratch VM): it installs packages, changes
# the firewall and host sysctls through `isx init`, and may add /etc/hosts entries (see below).
#
# Requires: Fedora or Debian/Ubuntu, passwordless sudo, and `isx` + `isx-proxy` on PATH
#           (./install.sh from the working tree you want to test).
#
# The current session does not gain the incus-admin group this adds, so run Incus and isx
# commands through it afterwards:
#   sg incus-admin -c "isx build tpl-minimal --yes"
#
# Usage:
#   scripts/local-incus.sh
set -euo pipefail

die() { echo "Error: $*" >&2; exit 1; }
step() { echo "==> $*"; }

# Inside a user namespace that maps only part of the ID space: a nested isx instance, or any
# unprivileged container. The kernel then refuses some of what Incus and isx init do on a host.
limited_userns() { ! awk '$1 == 0 && $2 == 0 && $3 == 4294967295 { full = 1 } END { exit !full }' /proc/self/uid_map; }

[ "$(uname -s)" = "Linux" ] || die "Linux only (on macOS isx runs Incus in its own appliance)"
sudo -n true 2>/dev/null || die "needs passwordless sudo"
command -v isx >/dev/null || die "isx is not on PATH; run ./install.sh first"
command -v isx-proxy >/dev/null || die "isx-proxy is not on PATH; run ./install.sh first"

# btrfs-progs: without it Incus cannot create the btrfs pool isx branches copy-on-write from.
# jq: for reading `incus query` output while checking results.
packages=(incus btrfs-progs jq)
missing=()
for p in "${packages[@]}"; do
    if command -v rpm >/dev/null; then
        rpm -q "$p" >/dev/null 2>&1 || missing+=("$p")
    else
        dpkg -s "$p" >/dev/null 2>&1 || missing+=("$p")
    fi
done
if [ ${#missing[@]} -gt 0 ]; then
    step "Installing ${missing[*]}"
    if command -v dnf >/dev/null; then
        sudo dnf install -y -q "${missing[@]}"
    elif command -v apt-get >/dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y -qq "${missing[@]}"
    else
        die "no dnf or apt-get; install ${missing[*]} by hand"
    fi
fi

# virtiofsd: without it Incus shares every VM disk device over 9p, which cannot be hot-plugged, so
# VM builds with host resources or the DNF cache fail (#853). Fedora's incus does not pull it in.
# Debian and Ubuntu releases that predate the standalone package ship it in qemu-system-common.
virtiofsd=
if command -v dnf >/dev/null; then
    rpm -q virtiofsd >/dev/null 2>&1 || virtiofsd=virtiofsd
elif ! dpkg -s virtiofsd >/dev/null 2>&1; then
    # Ask apt only after an update: on a fresh host its package lists are empty.
    sudo apt-get update -qq
    if apt-cache show virtiofsd >/dev/null 2>&1; then
        virtiofsd=virtiofsd
    else
        dpkg -s qemu-system-common >/dev/null 2>&1 || virtiofsd=qemu-system-common
    fi
fi
if [ -n "$virtiofsd" ]; then
    step "Installing $virtiofsd"
    if command -v dnf >/dev/null; then
        sudo dnf install -y -q "$virtiofsd"
    else
        sudo apt-get install -y -qq "$virtiofsd"
    fi
    # Incus looks for virtiofsd only when it starts: a daemon already running would keep using 9p.
    sudo systemctl try-restart incus.service
fi

getent group incus-admin | grep -qw "$USER" || sudo usermod -aG incus-admin "$USER"
sudo systemctl enable --now incus.socket >/dev/null

# Nested, isx init's bridge cannot come up: Incus's own firewall rules for it use 'udp checksum
# set', which nftables refuses in a user namespace, and its fixed 10.166.11.1/24 is the subnet the
# outer isx gave this instance. Create it here, as isx init would but without those rules and on a
# free subnet; isx init then finds it and reads its gateway from Incus.
if limited_userns && ! sg incus-admin -c "incus network show incusbr0" >/dev/null 2>&1; then
    routes=$(ip -4 route)
    for n in $(seq 11 254); do
        grep -q "^10\.166\.$n\." <<<"$routes" || break
    done
    step "Nested: creating incusbr0 on 10.166.$n.1/24 without Incus's firewall rules"
    sg incus-admin -c "incus network create incusbr0 ipv4.address=10.166.$n.1/24 ipv4.nat=true \
        ipv6.address=none ipv4.firewall=false ipv6.firewall=false"
fi

step "Running isx init"
sg incus-admin -c "isx init </dev/null"
# isx init runs 'sudo incus admin init', which can leave ~/.config/incus owned by root.
[ -d "$HOME/.config/incus" ] && sudo chown -R "$USER:$USER" "$HOME/.config/incus"

# isx init gives Incus root:1000000:1000000000 in /etc/subuid and /etc/subgid, which a nested
# instance's namespace does not map, so every container fails with "Failed to handle idmapped
# storage". Hand Incus the top 65536 IDs of the largest range that is mapped instead. isx init
# adds its entry back on every run, so drop it again when ours is already there.
if limited_userns && grep -qx "root:1000000:1000000000" /etc/subuid /etc/subgid; then
    base=$(awk '$3 > max { max = $3; top = $1 + $3 } END { if (max >= 65536) print top - 65536 }' /proc/self/uid_map)
    [ -n "$base" ] || die "this namespace maps no range of 65536 IDs for containers"
    step "Nested: giving Incus root:$base:65536 in /etc/subuid and /etc/subgid"
    for f in /etc/subuid /etc/subgid; do
        if grep -qx "root:$base:65536" "$f"; then
            sudo sed -i '/^root:1000000:1000000000$/d' "$f"
        else
            sudo sed -i "s/^root:1000000:1000000000\$/root:$base:65536/" "$f"
        fi
    done
    sudo systemctl restart incus
fi

# isx init adds the 443 -> proxy redirect through firewalld's direct rules or UFW, both of which
# need legacy iptables' nat table. Where that is missing (nested instances on Fedora, whose
# kernel has no ip_tables module) the step fails quietly and containers reach the real hosts
# instead of the proxy. Add the same redirect with nftables.
# (Rules are read into variables first: under pipefail, 'grep -q' closing the pipe early would
# fail the pipeline and read as "missing".)
gateway=$(sg incus-admin -c "incus network get incusbr0 ipv4.address"); gateway=${gateway%/*}
nft_rules=$(sudo nft list ruleset 2>/dev/null || true)
ipt_rules=$(sudo iptables -t nat -S PREROUTING 2>/dev/null || true)
if ! grep -q "dport 443 .*redirect to :18443" <<<"$nft_rules" && ! grep -q "to-ports 18443" <<<"$ipt_rules"; then
    step "Adding the $gateway:443 -> 18443 redirect with nftables (isx init could not)"
    sudo nft -f - <<EOF
table ip isx_redirect {
    chain prerouting {
        type nat hook prerouting priority dstnat; policy accept;
        iifname "incusbr0" ip daddr $gateway tcp dport 443 redirect to :18443
    }
}
EOF
fi

# isx init adds a 'cow' btrfs pool when the default one cannot copy-on-write, but on Incus 6.x
# that fails on an option only newer Incus knows (#820): create it without. Remove once fixed.
if ! sg incus-admin -c "incus storage list -f csv" | awk -F, '$2 == "btrfs" { found = 1 } END { exit !found }'; then
    step "Creating the 'cow' btrfs pool isx init could not (#820)"
    sudo incus storage create cow btrfs size=100GiB
fi
# isx init leaves the profile alone (isx doctor warns instead), but branches must land on the
# 'cow' pool to be copy-on-write, and to be timed fairly.
if sg incus-admin -c "incus storage show cow" >/dev/null 2>&1 \
        && [ "$(sg incus-admin -c 'incus profile device get default root pool')" != "cow" ]; then
    step "Pointing the default profile's root disk at the 'cow' pool"
    sg incus-admin -c "incus profile device set default root pool=cow"
fi

# Inside an isx instance whose *outer* isx predates the fix for #814, the outer DNS answers AAAA
# for the domains it intercepts with '::', which isx's download guard refuses as a local address
# -- so `isx build` cannot fetch its base image. Pin those hosts to their IPv4 answer, and stop the
# bridge's dnsmasq from serving /etc/hosts to the nested instances, where it would shadow the
# proxy's own overrides (CI does the same for its echo-server entries). Under a fixed outer isx no
# host answers '::', so nothing is pinned; the block can go once no outer host runs such a release.
pinned=false
for host in github.com release-assets.githubusercontent.com objects.githubusercontent.com; do
    [ "$(getent ahostsv6 "$host" | awk 'NR==1{print $1}')" = "::" ] || continue
    grep -qE "[[:space:]]$host([[:space:]]|$)" /etc/hosts && continue
    ipv4=$(getent ahostsv4 "$host" | awk 'NR==1{print $1}')
    [ -n "$ipv4" ] || continue
    echo "$ipv4 $host  # scripts/local-incus.sh, nested isx (#814)" | sudo tee -a /etc/hosts >/dev/null
    pinned=true
done
if $pinned || grep -q "scripts/local-incus.sh" /etc/hosts; then
    dnsmasq=$(sg incus-admin -c "incus network get incusbr0 raw.dnsmasq")
    if ! grep -qx "no-hosts" <<<"$dnsmasq"; then
        step "Nested isx: keeping /etc/hosts out of the bridge's dnsmasq"
        printf -v dnsmasq 'no-hosts\n%s' "$dnsmasq"
        sg incus-admin -c "incus network set incusbr0 raw.dnsmasq '$dnsmasq'"
    fi
fi

step "Ready. Next, from this shell:"
cat <<'EOF'
    sg incus-admin -c "isx proxy start"          # stays in the foreground: run it in the background
    sg incus-admin -c "isx build tpl-minimal --yes"
    sg incus-admin -c "isx branch t1 --from tpl-minimal --no-start"
EOF
