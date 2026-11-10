#!/bin/bash
# Integration test: a --proxy-only branch reaches the isx proxy and nothing else, however it is
# started, and the restriction lives on the host (#1137).
#
# It is an Incus network ACL on the instance's NIC, installed in the host's nftables by Incus at
# every start. FakeIncusDaemon proves isx asks for it; only a real Incus shows that the ACL lets the
# proxy through, drops the rest, and is there again after a start isx did not make (#1311). The
# branches come from tpl-minimal, whose image has no iptables at all (#1312): nothing in the guest
# takes part.
#
# Requires:
#   - tpl-minimal and tpl-test-vm (or PROXY_ONLY_VM_TEMPLATE, below) already built
#   - isx and incus on PATH, and the proxy running
#
# Usage:
#   sg incus-admin -c "bash .github/scripts/test-proxy-only.sh"
#
# PROXY_ONLY_VM_TEMPLATE names the VM template (default tpl-test-vm); empty skips the VM, for a
# host without nested virtualization. PROXY_ONLY_PROBE_URL is what proves the proxy answers
# (default the native job's echo server); any intercepted domain does.

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# Every exec goes through guest_exec: Incus can fail to attach to a guest that just started (#2084)
source "$HERE/guest-exec.sh"

STARTED=test-proxy-only
UNSTARTED=test-proxy-only-nostart
VM=test-proxy-only-vm
FULL=test-proxy-only-full
VM_TEMPLATE=${PROXY_ONLY_VM_TEMPLATE-tpl-test-vm}
PROBE_URL=${PROXY_ONLY_PROBE_URL:-https://echo.incus-spawn.test/}

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

# The bridge NIC, whatever its name, as isx finds it
nic() {
    incus query "/1.0/instances/$1" \
        | jq -r '[.expanded_devices[] | select(.type == "nic" and .network == "incusbr0")][0]["security.acls"] // ""'
}

restricted_on_host() { [ "$(nic "$1")" = isx-proxy-only ]; }
not_restricted() { [ -z "$(nic "$1")" ]; }
# A few tries: a branch that just took the address of one deleted moments before can wait on the
# bridge's neighbour entry for the old one. A positive check may retry; the no-egress checks never do.
reaches_internet() {
    for _ in 1 2 3; do
        guest_exec "$1" -- curl -s -m 10 -o /dev/null http://1.1.1.1/ && return 0
        sleep 5
    done
    return 1
}

# Root in the guest takes another address on the bridge and tries from it. The ACL is on the
# host side of the NIC, whatever address a packet carries, and spoofing protection drops it too.
no_egress_from_another_address() {
    local spare
    spare=$(guest_exec "$1" -- ip -4 -o addr show eth0 | awk '{print $4}' | sed -E 's#\.[0-9]+/#.250/#')
    # Within check, a failing command does not stop the function: without the address there is no test
    [ -n "$spare" ] && guest_exec "$1" -- ip addr add "$spare" dev eth0 || return 1
    ! guest_exec "$1" -- curl -s -m 5 -o /dev/null --interface "${spare%/*}" http://1.1.1.1/
}

# Any HTTP answer is the proxy's: an intercepted domain resolves to the gateway, and a 401 for
# the missing credential still proves the connection got through.
reaches_proxy() {
    local code
    code=$(guest_exec "$1" -- curl -s -m 15 -o /dev/null -w '%{http_code}' "$PROBE_URL" || true)
    [ -n "$code" ] && [ "$code" != 000 ]
}
resolves() { guest_exec "$1" -- getent hosts example.com >/dev/null; }
no_direct_ipv4() { ! guest_exec "$1" -- curl -s -m 5 -o /dev/null http://1.1.1.1/; }
no_direct_https() { ! guest_exec "$1" -- curl -s -m 5 -o /dev/null https://example.com/; }
no_direct_ipv6() { ! guest_exec "$1" -- curl -6 -s -m 5 -o /dev/null 'http://[2606:4700:4700::1111]/'; }

wait_for_ipv4() {
    guest_exec "$1" -- bash -c '
        systemctl start systemd-networkd 2>/dev/null
        for i in $(seq 1 30); do
            ip -4 -o addr show scope global | grep -q inet && exit 0
            sleep 0.5
        done
        exit 1'
}

wait_for_agent() {
    for _ in $(seq 1 90); do
        guest_exec "$1" -- true 2>/dev/null && return 0
        sleep 1
    done
    return 1
}

restricted() {
    check "$2: the proxy answers" reaches_proxy "$1"
    check "$2: DNS resolves" resolves "$1"
    check "$2: no direct IPv4 egress" no_direct_ipv4 "$1"
    check "$2: no direct HTTPS to a domain the proxy does not intercept" no_direct_https "$1"
    check "$2: no direct IPv6 egress" no_direct_ipv6 "$1"
}

cleanup() { incus delete -f "$STARTED" "$UNSTARTED" "$VM" "$FULL" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# NIC ACLs need Incus's nftables firewall driver; under xtables isx refuses --proxy-only
incus info | grep -E '^ *firewall:' || true

echo "=== A proxy-only branch of an image without iptables ==="
isx branch "$STARTED" --from tpl-minimal --proxy-only
check "its NIC carries the host ACL" restricted_on_host "$STARTED"
guest_exec "$STARTED" -- sh -c '! command -v iptables' && echo "  (no iptables in the guest)"
wait_for_ipv4 "$STARTED"
restricted "$STARTED" "after isx branch"

check "after isx branch: root in the guest gains nothing from another address" no_egress_from_another_address "$STARTED"

echo "=== After a restart isx did not make (#1311) ==="
incus restart "$STARTED"
wait_for_ipv4 "$STARTED"
restricted "$STARTED" "after incus restart"

echo "=== A --no-start branch, first started by Incus ==="
isx branch "$UNSTARTED" --from tpl-minimal --proxy-only --no-start
check "its NIC carries the host ACL before any start" restricted_on_host "$UNSTARTED"
incus start "$UNSTARTED"
wait_for_ipv4 "$UNSTARTED"
restricted "$UNSTARTED" "after incus start"
incus delete -f "$UNSTARTED"

echo "=== A branch with full network of a proxy-only instance ==="
incus stop "$STARTED"
isx branch "$FULL" --from "$STARTED"
check "its NIC carries no ACL" not_restricted "$FULL"
wait_for_ipv4 "$FULL"
check "it reaches the internet" reaches_internet "$FULL"
incus delete -f "$FULL"

if [ -n "$VM_TEMPLATE" ]; then
    echo "=== A VM ==="
    isx branch "$VM" --from "$VM_TEMPLATE" --proxy-only
    check "its NIC carries the host ACL" restricted_on_host "$VM"
    check "its agent answers" wait_for_agent "$VM"
    restricted "$VM" "VM"
fi

echo
echo "$((TESTS - FAIL))/$TESTS passed"
[ "$FAIL" -eq 0 ]
