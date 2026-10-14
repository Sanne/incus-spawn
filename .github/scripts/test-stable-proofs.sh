#!/bin/bash
# Host entry point; guest modes run as agentuser in the existing Podman fixture.
# The child keeps its original environment in Podman's configuration across boots.
set -euo pipefail

case "${1:-}" in
    create)
        export GH_TOKEN=gho_placeholder
        . /run/isx/proof-tokens
        [[ "$GH_TOKEN" == gho_isx_* ]]
        podman rm -f isx-proof-lifetime >/dev/null 2>&1 || true
        podman create --name isx-proof-lifetime -e GH_TOKEN docker.io/library/alpine sh -c \
            'wget -T 30 --no-check-certificate -qO- --header="Authorization: Bearer $GH_TOKEN" https://api.github.com/' >/dev/null
        exit ;;
    authorized|revoked)
        reply=$(podman start --attach isx-proof-lifetime)
        [[ "$reply" == *'"headers"'* && "$reply" == *'Bearer '* ]]
        if [[ "$1" == authorized ]]; then
            [[ "$reply" != *isx_* && "$reply" != *placeholder* ]]
        else
            [[ "$reply" == *'Bearer gho_isx_'* ]]
        fi
        exit ;;
esac

instance=test-podman
copy=isx-proof-copy
guest() { incus exec "$instance" -- su -l agentuser -c "bash /home/agentuser/test-stable-proofs.sh $1"; }
generation() { incus config get "$1" user.incus-spawn.instance-proof-id; }
boot_hash() { incus config get "$instance" user.incus-spawn.instance-secret-sha256; }
prepare() {
    local output
    # run performs normal readiness before its default shell action; CI has no terminal.
    if ! output=$(isx run "$instance" 2>&1); then
        if [[ "$output" != *'no terminal:'* ]]; then
            printf '%s\n' "$output" >&2
            return 1
        fi
    fi
    incus exec "$instance" -- bash -c '
        systemctl start systemd-networkd
        for n in $(seq 1 30); do
            ip -4 -o addr show eth0 | grep -q "inet " && exit 0
            sleep 0.5
        done
        exit 1'
}
cleanup() {
    incus exec "$instance" -- su -l agentuser -c 'podman rm -f isx-proof-lifetime' >/dev/null 2>&1 || true
    incus exec "$instance" -- rm -f /home/agentuser/test-stable-proofs.sh >/dev/null 2>&1 || true
    incus delete -f "$copy" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# /tmp is cleared on reboot; the helper must survive beside Podman's saved configuration.
incus file push .github/scripts/test-stable-proofs.sh "$instance/home/agentuser/"
original=$(generation "$instance")
[[ "$original" =~ ^[0-9a-f]{64}$ ]]
guest create
guest authorized

for restart in managed raw; do
    echo "Checking saved child proof after $restart restart"
    previous_boot=$(boot_hash)
    if [[ "$restart" == managed ]]; then incus stop "$instance"; else incus restart "$instance"; fi
    prepare
    [[ "$(generation "$instance")" == "$original" ]]
    [[ "$(boot_hash)" != "$previous_boot" ]]
    guest authorized
done
echo 'Nested child keeps its configured proof across managed start and raw reboot recovery'

previous_boot=$(boot_hash)
isx account reset-proofs "$instance"
[[ "$(generation "$instance")" != "$original" ]]
[[ "$(boot_hash)" == "$previous_boot" ]]
guest revoked
guest create
guest authorized
echo 'Acknowledged reset revokes the saved child proof; explicitly passing the new proof restores access'

isx branch "$copy" --from "$instance" --no-start
first_copy=$(generation "$copy")
[[ "$first_copy" =~ ^[0-9a-f]{64}$ && "$first_copy" != "$(generation "$instance")" ]]
incus delete "$copy"
isx branch "$copy" --from "$instance" --no-start
[[ "$(generation "$copy")" != "$first_copy" ]]
echo 'Branches and replacements receive new proof identities, including when reusing a name'
