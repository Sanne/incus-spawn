#!/bin/bash
# Integration test: a client that forces strict host key checking reaches an instance (#1863).
#
# herdr passes StrictHostKeyChecking=yes on ssh's command line, which beats the Host block's
# `no`, so it connects only when the managed known_hosts holds the instance's key. isx pins the
# keys the guest prints during the readiness exec a branch and a start already make. Only a real
# Incus runs that script through `incus exec` against a real sshd, so only this proves the probe
# carries the keys.
#
# Checks, with herdr's own option set:
#   - a fresh branch is reachable by its name (HostKeyAlias), and by its address for a client that
#     drops the alias and dials Hostname;
#   - a key regenerated in the guest is refused until the next start pins it again;
#   - destroy removes the instance's pins and its Host block.
#
# Requires:
#   - tpl-test-ssh (tpl-minimal with the sshd tool) already built
#   - isx and incus on PATH
#
# Usage:
#   sg incus-admin -c "bash .github/scripts/test-ssh-host-keys.sh"

set -euo pipefail

NAME=test-ssh-keys
SSH_DIR="$HOME/.config/incus-spawn/ssh"
CONFIG="$SSH_DIR/config"
KNOWN_HOSTS="$SSH_DIR/known_hosts"
NO_ALIAS=$(mktemp)

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

# What herdr 0.9.5 passes on every connection, compiled in
strict_ssh() {
    local config="$1"; shift
    timeout 30 ssh -F "$config" -o BatchMode=yes -o NumberOfPasswordPrompts=0 \
        -o StrictHostKeyChecking=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 "$@" "$NAME" true
}

strict_by_name() { strict_ssh "$CONFIG"; }
strict_refused() { ! strict_ssh "$CONFIG" 2>/dev/null; }

# A client that ignores HostKeyAlias looks the key up by the block's Hostname
strict_by_address() {
    grep -v "^ *HostKeyAlias $NAME\$" "$CONFIG" > "$NO_ALIAS"
    ! grep -q "HostKeyAlias $NAME\$" "$NO_ALIAS" && strict_ssh "$NO_ALIAS"
}

static_ip() { incus config get "$NAME" user.incus-spawn.static-ip; }

# Every key the guest's sshd has, under "<name>,<static ip>"
pinned_under_name_and_address() {
    local ip types
    ip=$(static_ip)
    [ -n "$ip" ] || return 1
    types=$(incus exec "$NAME" -- sh -c 'cat /etc/ssh/ssh_host_*_key.pub' | awk '{print $1 " " $2}' | sort)
    [ -n "$types" ] || return 1
    [ "$(grep " isx:$NAME\$" "$KNOWN_HOSTS" | awk -v h="$NAME,$ip" '$1 == h {print $2 " " $3}' | sort)" = "$types" ]
}

no_pins_left() { ! grep -Eq "(^|,)$NAME[ ,]| isx:$NAME\$" "$KNOWN_HOSTS"; }
no_host_block() { ! grep -q "^Host $NAME\$" "$CONFIG"; }

cleanup() {
    rm -f "$NO_ALIAS"
    incus delete -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== A branch pins its host keys ==="
isx branch "$NAME" --from tpl-test-ssh
check "keys pinned under the name and the stamped address" pinned_under_name_and_address
check "strict ssh by name (HostKeyAlias)" strict_by_name
check "strict ssh by address (no HostKeyAlias)" strict_by_address

echo "=== A regenerated key is pinned again on the next start ==="
incus exec "$NAME" -- sh -c 'rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub && ssh-keygen -A >/dev/null && systemctl restart sshd'
check "strict ssh refuses the changed key" strict_refused
isx stop "$NAME"
isx start "$NAME"
check "the new keys are pinned" pinned_under_name_and_address
check "strict ssh by name after the start" strict_by_name

echo "=== Destroy removes the pins ==="
isx destroy "$NAME"
check "no known_hosts line names the instance" no_pins_left
check "no Host block for the instance" no_host_block

echo "$((TESTS - FAIL))/$TESTS passed"
[ "$FAIL" -eq 0 ]
