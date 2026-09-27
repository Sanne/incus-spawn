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

getent group incus-admin | grep -qw "$USER" || sudo usermod -aG incus-admin "$USER"
sudo systemctl enable --now incus.socket >/dev/null

step "Running isx init"
sg incus-admin -c "isx init </dev/null"
# isx init runs 'sudo incus admin init', which can leave ~/.config/incus owned by root.
[ -d "$HOME/.config/incus" ] && sudo chown -R "$USER:$USER" "$HOME/.config/incus"

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

# Inside an isx instance the outer isx's DNS answers AAAA for the domains it intercepts with
# '::', which isx's download guard refuses as a local address (#814) -- so `isx build` cannot
# fetch its base image. Pin those hosts to their IPv4 answer, and stop the bridge's dnsmasq from
# serving /etc/hosts to the nested instances, where it would shadow the proxy's own overrides
# (CI does the same for its echo-server entries). Remove this block once #814 is fixed.
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
