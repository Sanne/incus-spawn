#!/bin/bash
# Integration test: an airgapped branch has no network, and a branch of it gets it back.
#
# The default profile provides the NIC, and Incus cannot remove a profile device from one
# instance, only mask it with a `type: none` device of the same name. Airgap branches kept full
# network access for as long as isx removed the device instead (#813), which no fake daemon
# caught: it takes a real Incus to expand the profile's devices.
#
# Requires:
#   - tpl-minimal already built
#   - isx and incus on PATH, and the proxy running
#
# Usage:
#   sg incus-admin -c "bash .github/scripts/test-airgap.sh"

set -euo pipefail

AIRGAPPED=test-airgap
RECONNECTED=test-airgap-reconnected

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

# NICs Incus attaches at start: the expanded view, since a profile's NIC is not in `devices`
nics() {
    incus query "/1.0/instances/$1" \
        | jq -r '.expanded_devices | to_entries[] | select(.value.type == "nic") | .key'
}

no_nics() { [ -z "$(nics "$1")" ]; }
has_nic() { [ -n "$(nics "$1")" ]; }
stamped() { [ "$(incus config get "$1" user.incus-spawn.network-mode)" = "$2" ]; }
no_global_ipv4() { ! incus exec "$1" -- ip -4 -o addr show scope global | grep -q inet; }
cannot_reach_internet() { ! incus exec "$1" -- curl -s -m 5 -o /dev/null https://1.1.1.1; }

wait_for_ipv4() {
    incus exec "$1" -- bash -c '
        systemctl start systemd-networkd 2>/dev/null
        for i in $(seq 1 30); do
            ip -4 -o addr show scope global | grep -q inet && exit 0
            sleep 0.5
        done
        exit 1'
}

cleanup() { incus delete -f "$AIRGAPPED" "$RECONNECTED" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "=== Airgapped branch ==="
isx branch "$AIRGAPPED" --from tpl-minimal --airgap --no-start
incus config show "$AIRGAPPED" --expanded
check "no NIC in expanded_devices" no_nics "$AIRGAPPED"
check "stamped network-mode AIRGAP" stamped "$AIRGAPPED" AIRGAP
incus start "$AIRGAPPED"
# Long enough for DHCP to have handed out an address, had there been a NIC to ask on
sleep 5
check "no global IPv4 address" no_global_ipv4 "$AIRGAPPED"
check "cannot reach the internet" cannot_reach_internet "$AIRGAPPED"
incus stop -f "$AIRGAPPED"

echo "=== Branch with network from the airgapped one ==="
isx branch "$RECONNECTED" --from "$AIRGAPPED" --no-start
check "NIC back in expanded_devices" has_nic "$RECONNECTED"
check "network-mode stamp cleared" stamped "$RECONNECTED" ""
incus start "$RECONNECTED"
check "gets an IPv4 address" wait_for_ipv4 "$RECONNECTED"

echo
echo "$((TESTS - FAIL))/$TESTS passed"
[ "$FAIL" -eq 0 ]
