#!/bin/bash
# Integration test: a client that forces strict host key checking reaches an instance (#1863).
#
# herdr passes StrictHostKeyChecking=yes on ssh's command line, which beats the Host block's
# `no`, so it connects only when the managed known_hosts holds the instance's key. isx pins the
# keys the guest prints during the readiness exec a branch and a start already make. Only a real
# Incus runs that script through `incus exec` against a real sshd, so only this proves the probe
# carries the keys.
#
# Checks, with herdr's own option set (its ConnectTimeout only where the start alias is timed):
#   - a fresh branch is reachable by its name (HostKeyAlias), and by its address for a client that
#     drops the alias and dials Hostname;
#   - a key regenerated in the guest is refused until the next start pins it again;
#   - a stopped instance is not started by its name, and is started by the start alias herdr's
#     machine targets (#2038), within herdr's ConnectTimeout;
#   - destroy removes the instance's pins and its Host block.
#
# A connection that should work and fails prints what each side saw (#2129): ssh's own debug log,
# Incus's account of the exec that carried it, and the guest's sshd journal.
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
START_ALIAS=$(mktemp)
DIAG=$(mktemp -d)
SSH_RUNS=0

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

# What herdr 0.9.5 passes on every connection, compiled in, but for its ConnectTimeout=10, which
# only the start alias's check keeps (#2119). The others are about host keys, and a branch's first
# connection runs sshd-session for the first time: its copy-on-write files start with a cold page
# cache, so on a disk the previous step left busy the banner can take longer than 10 s.
HERDR_CONNECT_TIMEOUT=10
# Its debug log goes to $SSH_LOG, each line stamped with the time it was written, since when the
# banner came is the question a failure leaves (#2129)
strict_ssh() {
    local config="$1"; shift
    SSH_RUNS=$((SSH_RUNS + 1))
    SSH_LOG="$DIAG/ssh-$SSH_RUNS.log"
    timeout 90 ssh -vvv -F "$config" -o BatchMode=yes -o NumberOfPasswordPrompts=0 \
        -o StrictHostKeyChecking=yes -o ConnectTimeout="${SSH_CONNECT_TIMEOUT:-60}" -o ConnectionAttempts=1 \
        "${TARGET:-$NAME}" true 2>&1 | stamped > "$SSH_LOG"
    return "${PIPESTATUS[0]}"
}

stamped() {
    local line t
    while IFS= read -r line; do
        t=$EPOCHREALTIME
        TZ=UTC printf '%(%T)T.%s %s\n' "${t%.*}" "${t#*.}" "$line"
    done
}

# Incus's own account of every exec while the checks run: when its sockets connected, when the
# command started and how it ended, and the operation's status and error
EVENTS="$DIAG/incus-events.json"
incus monitor --type=logging --type=operation --format=json > "$EVENTS" 2> "$DIAG/incus-monitor.err" &
MONITOR=$!

# What each side saw of the connection $SSH_LOG logged, for a check that expected it to work
diagnose() {
    echo "    --- ssh -vvv (last 40 lines; UTC) ---"
    if [ -n "$SSH_LOG" ]; then
        tail -n 40 "$SSH_LOG" | sed 's/^/    /'
    else
        echo "    (the check failed before it ran ssh)"
    fi
    echo "    --- Incus exec events for $NAME (last 40; UTC) ---"
    jq -rs --arg n "$NAME" '
        def mine: (.metadata.resources.instances // []) | any(endswith("/instances/" + $n));
        ([.[] | select(.type == "operation" and mine) | .metadata.id] | unique) as $ops
        | .[]
        | if .type == "operation" then
            select(mine) | "\(.timestamp) operation \(.metadata.id) \(.metadata.status) \(.metadata.err) \(.metadata.metadata.return // "")"
          else
            .metadata.context as $c
            | select(($c.instance // "") == $n or (($c.operation // "") as $o | $ops | index($o)))
            | "\(.timestamp) \(.metadata.message) \($c | del(.instance, .project, .class, .description) | tostring)"
          end' "$EVENTS" 2>&1 | tail -n 40 | sed 's/^/    /'
    tail -n 5 "$DIAG/incus-monitor.err" | sed 's/^/    incus monitor: /'
    echo "    --- the guest's sshd journal (UTC) ---"
    incus exec "$NAME" -- env TZ=UTC journalctl -b --no-pager -o short-precise -u sshd.service 2>&1 \
        | tail -n 20 | sed 's/^/    /'
    echo "    --- the host's kernel log (last 10 lines) ---"
    sudo -n dmesg 2>&1 | tail -n 10 | sed 's/^/    /'
}

# A connection that should work, and if it does not, why (#2129)
connects() { SSH_LOG=; "$@" && return 0; diagnose; return 1; }

strict_by_name() { strict_ssh "$CONFIG"; }
strict_refused() { ! strict_ssh "$CONFIG"; }

# A client that ignores HostKeyAlias looks the key up by the block's Hostname
strict_by_address() {
    grep -v "^ *HostKeyAlias $NAME\$" "$CONFIG" > "$NO_ALIAS"
    ! grep -q "HostKeyAlias $NAME\$" "$NO_ALIAS" && strict_ssh "$NO_ALIAS"
}

running() { [ "$(incus list "$NAME" -c s -f csv)" = "RUNNING" ]; }
stopped_by_name() { strict_refused && ! running; }

# The block SshKeyManager.addStartEntry writes for herdr's machine: the instance's own, under
# Host <name>.start.isx, with ssh-proxy --start and no Hostname (no herdr on the runner to ask)
strict_by_start_alias() {
    awk -v n="$NAME" '
        /^Host / { inblock = ($2 == n); if (inblock) { print "Host " n ".start.isx"; next } }
        inblock && /^ *Hostname / { next }
        inblock { sub(" ssh-proxy " n "$", " ssh-proxy --start " n); print }' "$CONFIG" > "$START_ALIAS"
    grep -q "ssh-proxy --start $NAME\$" "$START_ALIAS" && TARGET="$NAME.start.isx" SSH_CONNECT_TIMEOUT=$HERDR_CONNECT_TIMEOUT strict_ssh "$START_ALIAS" && running
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
    kill "$MONITOR" 2>/dev/null || true
    rm -rf "$NO_ALIAS" "$START_ALIAS" "$DIAG"
    incus delete -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== A branch pins its host keys ==="
isx branch "$NAME" --from tpl-test-ssh
check "keys pinned under the name and the stamped address" pinned_under_name_and_address
check "strict ssh by name (HostKeyAlias)" connects strict_by_name
check "strict ssh by address (no HostKeyAlias)" connects strict_by_address

echo "=== A regenerated key is pinned again on the next start ==="
incus exec "$NAME" -- sh -c 'rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub && ssh-keygen -A >/dev/null && systemctl restart sshd'
check "strict ssh refuses the changed key" strict_refused
isx stop "$NAME"
isx start "$NAME"
check "the new keys are pinned" pinned_under_name_and_address
check "strict ssh by name after the start" connects strict_by_name

echo "=== herdr's start alias starts a stopped instance ==="
isx stop "$NAME"
check "strict ssh by name neither connects nor starts it" stopped_by_name
check "strict ssh by the start alias starts it and connects" connects strict_by_start_alias

echo "=== Destroy removes the pins ==="
isx destroy "$NAME"
check "no known_hosts line names the instance" no_pins_left
check "no Host block for the instance" no_host_block

echo "$((TESTS - FAIL))/$TESTS passed"
[ "$FAIL" -eq 0 ]
