#!/bin/bash
# End-to-end test of isx on a host that is a distribution as a user installs it (#1972): Arch,
# with and without ufw, and Ubuntu with ufw. It runs inside the guest scripts/distro-vm.sh boots,
# as the guest's user, one phase per ssh login, so each phase gets the groups a new login gets.
#
# What it covers is what a user runs: isx init installing Incus from the distribution's own
# repository and configuring the host firewall, the host still passing traffic afterwards (the
# #1932 symptom: ufw failed to load and the host lost loopback and reply traffic), the proxy as a
# user service, a build, a branch reached through the bridge, isx shell to the prompt, stop,
# start and destroy, with isx doctor clean throughout.
#
# Usage (in the guest, with isx and isx-proxy on PATH and this directory's scripts beside it):
#   test-host-distro.sh prepare <shape>   # shape: arch, arch-ufw or ubuntu-ufw; reboot after it
#   test-host-distro.sh firewall <shape>  # enables ufw on the -ufw shapes
#   test-host-distro.sh init              # exits 75 when only a new login can finish it
#   test-host-distro.sh host-checks <shape>
#   test-host-distro.sh proxy
#   test-host-distro.sh build
#   test-host-distro.sh instance
#   test-host-distro.sh shell
#   test-host-distro.sh proxy-only
#   test-host-distro.sh lifecycle

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
INSTANCE=host-distro
# Exit code of 'init' when isx init stopped for a new login: the user was added to incus-admin,
# and without sg (Arch removed it) nothing in this login can take the group.
NEEDS_LOGIN=75
# shellcheck source=SCRIPTDIR/net-retry.sh
source "$HERE/net-retry.sh"
# Every exec goes through guest_exec: Incus can fail to attach to a guest that just started (#2084)
# shellcheck source=SCRIPTDIR/guest-exec.sh
source "$HERE/guest-exec.sh"

TESTS=0
FAIL=0

check() {
    local desc="$1"; shift
    TESTS=$((TESTS + 1))
    if "$@"; then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
    else
        printf '  \033[31mFAIL\033[0m  %s\n' "$desc"
        FAIL=$((FAIL + 1))
    fi
}

finish() {
    echo "$((TESTS - FAIL))/$TESTS passed"
    [ "$FAIL" -eq 0 ]
}

distro() { (. /etc/os-release && echo "$ID"); }

wants_ufw() { [[ $1 == *-ufw ]]; }

# --- prepare ---------------------------------------------------------------------------------

prepare() {
    local shape=$1
    case "$(distro)" in
        arch)
            # The cloud image is a month old at most: bring it current, as a user's host is.
            # The keyring first, or packages signed by a newer key are refused.
            sudo pacman -Sy --noconfirm --needed archlinux-keyring
            sudo pacman -Su --noconfirm
            sudo pacman -S --noconfirm --needed jq
            if wants_ufw "$shape"; then
                sudo pacman -S --noconfirm --needed ufw
                sudo systemctl enable ufw
            fi
            ;;
        ubuntu)
            # sudo drops DEBIAN_FRONTEND, so the frontend and needrestart are told in their files
            echo 'debconf debconf/frontend select Noninteractive' | sudo debconf-set-selections
            sudo mkdir -p /etc/needrestart/conf.d
            echo "\$nrconf{restart} = 'a';" | sudo tee /etc/needrestart/conf.d/isx-ci.conf >/dev/null
            sudo apt-get update -qq
            sudo apt-get install -y -qq jq ufw
            ;;
        *) echo "unsupported distribution: $(distro)" >&2; return 1 ;;
    esac
}

# After the reboot that follows prepare: an upgraded Arch kernel has no modules for the old one.
firewall() {
    wants_ufw "$1" || return 0
    # Allowed before enabling, or the default deny cuts this ssh session
    sudo ufw allow 22/tcp
    sudo ufw --force enable
    sudo ufw status verbose
}

# --- init ------------------------------------------------------------------------------------

init() {
    local rc=0
    isx init </dev/null || rc=$?
    [ "$rc" -eq 0 ] && return 0
    if ! command -v sg >/dev/null && id -nG "$USER" | grep -qw incus-admin && ! id -nG | grep -qw incus-admin; then
        echo "isx init needs a new login for the incus-admin group (no sg on this host)"
        return "$NEEDS_LOGIN"
    fi
    return "$rc"
}

# --- host checks -----------------------------------------------------------------------------

# A disabled ufw "reloads" by skipping, with success
ufw_loads() {
    local out
    out=$(sudo ufw reload 2>&1) || { echo "$out"; return 1; }
    echo "$out"
    ! grep -qi 'not enabled' <<<"$out"
}
ufw_stays_enabled() { sudo grep -qx 'ENABLED=yes' /etc/ufw/ufw.conf && sudo ufw status | grep -q '^Status: active'; }
firewalld_running() { [ "$(sudo firewall-cmd --state 2>&1)" = running ]; }

loopback_answers() {
    local port=18999 pid ok=1
    python3 -m http.server --bind 127.0.0.1 "$port" --directory /tmp >/dev/null 2>&1 &
    pid=$!
    for _ in $(seq 1 20); do
        if curl -sf -m 5 -o /dev/null "http://127.0.0.1:$port/"; then ok=0; break; fi
        sleep 0.5
    done
    kill "$pid" 2>/dev/null || true
    return $ok
}

# Any HTTP status proves the reply came back through the host's firewall. Hosts on other
# networks than GitHub's, asked in rounds, so a github.com outage is told apart (#2042).
https_reply_arrives() {
    https_reply_from_any https://github.com/ https://www.cloudflare.com/ https://dl.fedoraproject.org/
}

# What isx init configures on the host: the firewall, the bridge and the default profile
host_state() {
    if command -v ufw >/dev/null && sudo ufw status | grep -q '^Status: active'; then
        sudo cat /etc/ufw/before.rules /etc/ufw/user.rules
    fi
    if command -v firewall-cmd >/dev/null && firewalld_running; then
        sudo firewall-cmd --zone=trusted --list-all
        sudo firewall-cmd --direct --get-all-rules
    fi
    incus network show incusbr0
    incus profile show default
    incus storage list -f csv
}

second_init_changes_nothing() {
    local before after
    before=$(mktemp); after=$(mktemp)
    host_state > "$before"
    isx init </dev/null >/dev/null 2>&1 || { echo "the second isx init failed"; return 1; }
    host_state > "$after"
    diff -u "$before" "$after"
}

host_checks() {
    local shape=$1
    echo "=== The host after isx init ($shape) ==="
    if wants_ufw "$shape"; then
        check "ufw still loads after isx init" ufw_loads
        check "ufw stays enabled" ufw_stays_enabled
    else
        check "firewalld is running" firewalld_running
    fi
    check "a loopback listener answers" loopback_answers
    check "an outgoing HTTPS request gets its reply" https_reply_arrives
    check "a second isx init changes nothing" second_init_changes_nothing
    # isx doctor comes once the proxy runs: without it, doctor rightly fails
    finish
}

# --- proxy -----------------------------------------------------------------------------------

gateway() { incus network get incusbr0 ipv4.address | cut -d/ -f1; }

proxy_healthy() {
    local gw
    gw=$(gateway)
    for _ in $(seq 1 30); do
        curl -sf -m 5 -o /dev/null "http://$gw:18080/health" && return 0
        sleep 1
    done
    return 1
}

proxy() {
    echo "=== The proxy as a user service ==="
    # The user manager started with this user's first login, before isx init added incus-admin;
    # a user's next login gives it the group, which a restart does here. isx proxy install
    # enables lingering, which keeps it beyond this ssh session.
    sudo systemctl restart "user@$(id -u).service"
    isx proxy install
    check "the proxy service is active" systemctl --user is-active --quiet incus-spawn-proxy
    check "the proxy answers on the bridge" proxy_healthy
    check "isx doctor finds nothing failing" isx doctor
    finish
}

# --- instance --------------------------------------------------------------------------------

wait_for_ipv4() {
    guest_exec "$1" -- bash -c '
        for i in $(seq 1 60); do
            ip -4 -o addr show scope global | grep -q inet && exit 0
            sleep 0.5
        done
        exit 1'
}

reaches_internet() {
    for _ in 1 2 3; do
        guest_exec "$1" -- curl -s -m 10 -o /dev/null http://1.1.1.1/ && return 0
        sleep 5
    done
    return 1
}

# No GitHub credential here, so github.com is not intercepted: the clone goes through NAT
clones_from_github() {
    guest_exec "$1" -- bash -c 'rm -rf /tmp/clone &&
        git clone --depth 1 -q https://github.com/octocat/Hello-World.git /tmp/clone &&
        git -C /tmp/clone log --oneline -1 | grep -q . && rm -rf /tmp/clone'
}

instance() {
    echo "=== A branch, through the bridge ==="
    isx branch "$INSTANCE" --from tpl-minimal
    check "it gets an address on the bridge" wait_for_ipv4 "$INSTANCE"
    check "it reaches the internet through NAT" reaches_internet "$INSTANCE"
    check "it clones from GitHub" clones_from_github "$INSTANCE"
    incus file push "$HERE/test-instance.sh" "$INSTANCE/tmp/"
    # Sections 1 to 6b less GitHub's interception: Maven through the proxy, sudo, systemd, /tmp on
    # disk, DNS, login shell, agent context. The later ones need the native job's fixtures.
    check "the instance checks pass" guest_exec "$INSTANCE" -- bash /tmp/test-instance.sh --core
    finish
}

# --- shell -----------------------------------------------------------------------------------

to_prompt() {
    python3 - "$HERE" "$@" <<'EOF'
import sys
sys.path.insert(0, sys.argv[1])
import isxbench
ms = isxbench.run_to_prompt(sys.argv[2:], 180)
print(f"  prompt after {ms:.0f} ms")
EOF
}

shell() {
    echo "=== isx shell to the prompt ==="
    check "isx shell reaches a prompt" to_prompt isx shell "$INSTANCE"
    finish
}

# --- proxy-only ------------------------------------------------------------------------------

# The Incus API extension a bridged NIC's ACL needs (Incus 6.9, or 6.0.4 on the LTS line); Ubuntu 24.04 ships 6.0.0
has_nic_acls() { incus query /1.0 | jq -e '.api_extensions | index("network_bridge_acl_devices")' >/dev/null; }

# Without NIC ACLs a proxy-only branch cannot be restricted: it must be refused, saying why, before
# anything is created (#1979).
refused_without_nic_acls() {
    local name=host-distro-proxy-only rc=0 out
    out=$(isx branch "$name" --from tpl-minimal --proxy-only </dev/null 2>&1) || rc=$?
    echo "$out"
    local left
    left=$(incus list "^$name\$" -c n -f csv)
    incus delete -f "$name" >/dev/null 2>&1 || true
    [ "$rc" -ne 0 ] && [ -z "$left" ] && grep -q network_bridge_acl_devices <<<"$out"
}

proxy_only() {
    echo "=== Proxy-only branches on this host's netfilter ==="
    if ! has_nic_acls; then
        echo "  This Incus ($(incus version | sed -n 's/^Server version: //p')) has no ACLs on bridged NICs"
        check "a proxy-only branch is refused, and leaves nothing behind" refused_without_nic_acls
        finish
        return
    fi
    # No VM: the guest has no nested virtualization. No echo server: an intercepted domain proves
    # the proxy answers.
    PROXY_ONLY_VM_TEMPLATE='' PROXY_ONLY_PROBE_URL=https://repo1.maven.org/maven2/ \
        bash "$HERE/test-proxy-only.sh"
}

# --- lifecycle -------------------------------------------------------------------------------

status_is() { [ "$(incus list "^$1\$" -c s -f csv)" = "$2" ]; }
gone() { ! incus info "$1" >/dev/null 2>&1; }

lifecycle() {
    echo "=== Stop, start, destroy ==="
    isx stop "$INSTANCE"
    check "isx stop stops it" status_is "$INSTANCE" STOPPED
    isx start "$INSTANCE"
    check "isx start starts it" status_is "$INSTANCE" RUNNING
    check "isx shell reaches a prompt after the restart" to_prompt isx shell "$INSTANCE"
    isx destroy "$INSTANCE" --skip-confirmation
    check "isx destroy removes it" gone "$INSTANCE"
    check "isx doctor finds nothing failing" isx doctor
    finish
}

phase=${1:-}
[ $# -gt 0 ] && shift
case "$phase" in
    prepare)     prepare "$@" ;;
    firewall)    firewall "$@" ;;
    init)        init ;;
    host-checks) host_checks "$@" ;;
    proxy)       proxy ;;
    build)       retry_download_outage isx build tpl-minimal --yes ;;
    instance)    instance ;;
    shell)       shell ;;
    proxy-only)  proxy_only ;;
    lifecycle)   lifecycle ;;
    *)           echo "usage: $0 prepare|firewall|init|host-checks|proxy|build|instance|shell|proxy-only|lifecycle" >&2; exit 2 ;;
esac
