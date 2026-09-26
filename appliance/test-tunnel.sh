#!/usr/bin/env bash
set -uo pipefail

# Probe the appliance's host-facing tunnel: the Incus API through the in-guest vsock
# forwarder, and every verb of the in-guest control agent (isx-agent).
#
# Takes the two host-side Unix sockets, however they were bridged to the guest's vsock
# ports: vfkit on macOS (test-boot.sh), or socat VSOCK-CONNECT under QEMU on Linux (CI).
# The guest half under test is the same either way; only the bridge differs.
#
# Usage:  ./test-tunnel.sh <incus-socket> <agent-socket>
#
#   TUNNEL_BACKSTOP=1  also verify the forwarder's socat -T inactivity backstop: a silent
#                      connection must outlive the long-poll floor, then be reaped, and the
#                      API must recover. Adds about three minutes: the backstop is 180s.
#
# Prints one PASS/FAIL line per check and exits non-zero if any check failed.

INCUS_SOCK="${1:?usage: test-tunnel.sh <incus-socket> <agent-socket>}"
AGENT_SOCK="${2:?usage: test-tunnel.sh <incus-socket> <agent-socket>}"

# Mirrors isx-start-vsock-forwarder's -T. The reap is checked against both edges: not before
# the floor (the /wait long-poll and the client watchdog rely on it), and not long after it.
BACKSTOP_SECONDS=180

PASS=0
FAIL=0
pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
expect() { if eval "$1"; then pass "$2"; else fail "$2${3:+ ($3)}"; fi; }

# One agent verb per connection, as VmAgentClient speaks it.
agent() { printf '%s\n' "$1" | nc -U -w 8 "$AGENT_SOCK" 2>/dev/null | tr -d '\r'; }
api() { curl -s --max-time 10 --unix-socket "$INCUS_SOCK" "http://localhost$1" 2>/dev/null; }
socat_count() { agent socat-count | tr -d '\n'; }

# Poll until the Incus API answers through the forwarder: incusd comes up during boot and
# the socket may not exist yet.
wait_for_api() {
    local deadline=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ -S "$INCUS_SOCK" ] && api /1.0 | grep -q '"metadata"' && return 0
        sleep 1
    done
    return 1
}

wait_for_agent() {
    local deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ -S "$AGENT_SOCK" ] && [ "$(agent ping)" = "ok" ] && return 0
        sleep 1
    done
    return 1
}

# A closing child takes a moment to exit, so poll the count until it reaches $1 (or give up).
settle_count() {
    local c=""
    for _ in $(seq 1 10); do
        c=$(socat_count)
        [ "$c" = "$1" ] && break
        sleep 1
    done
    echo "$c"
}

echo "-- Incus API over the forwarded socket --"
if ! wait_for_api 30; then
    fail "Incus API reachable over forwarded host socket"
    echo "$FAIL of $((PASS + FAIL)) tunnel checks failed."
    exit 1
fi
pass "Incus API reachable over forwarded host socket"
expect 'api /1.0/storage-pools | grep -q "storage-pools/cow"' "cow storage pool visible via API"
expect 'api /1.0/networks | grep -q "incusbr0"'                 "incusbr0 bridge visible via API"

echo
echo "-- Control agent --"
if ! wait_for_agent; then
    fail "control agent answered ping"
    echo "$FAIL of $((PASS + FAIL)) tunnel checks failed."
    exit 1
fi
pass "control agent answered ping"

# Every forwarder child exits when its connection closes, so once the probes above are done
# the count is the listener alone. The concurrency and backstop checks measure against this.
baseline=$(socat_count)
expect '[[ "$baseline" =~ ^[0-9]+$ ]] && [ "$baseline" -ge 1 ]' \
    "socat-count reports the forwarder (got '$baseline')"

v=$(agent version)
expect '[ -n "$v" ] && [[ "$v" != error* ]]' "version answered ('$(echo "$v" | head -1)')"
s=$(agent sshd-status)
expect '[ "$s" = running ] || [ "$s" = stopped ]' "sshd-status answered ('$s')"

# The pool's qgroup lookup must resolve and answer key=value lines. A fresh pool has quotas
# off (Incus enables them lazily), so "available=0" is a valid answer: what is tested is the
# lookup and the reply shape, not the flag.
st=$(agent "btrfs-status cow")
expect 'echo "$st" | grep -Eq "^(enabled|available)=[01]$"' \
    "btrfs-status resolved the cow pool ($(echo "$st" | tr '\n' ' '))"
u=$(agent "btrfs-usage cow")
expect 'echo "$u" | grep -q "^---ISX-SUBVOL---$"' "btrfs-usage answered in sections"
u=$(agent "btrfs-usage cow sync")
expect 'echo "$u" | grep -q "^---ISX-SUBVOL---$"' "btrfs-usage accepted the sync option"
# With quotas off, btrfs-progs refuses a rescan; the agent must still answer in its protocol.
r=$(agent "btrfs-rescan cow")
expect '[ "$r" = started ] || [ "$r" = running ] || [[ "$r" == error:* ]]' \
    "btrfs-rescan answered ('$(echo "$r" | head -1)')"

# The agent is a trust boundary: arguments are validated there, not only on the host.
expect '[ "$(agent "btrfs-status ../x")" = "error: bad pool name" ]' "agent rejects a path-like pool name"
expect '[ "$(agent "btrfs-usage cow now")" = "error: bad option" ]' "agent rejects an unknown option"
expect '[ "$(agent "sh -c id")" = "error: unknown verb" ]'           "agent rejects an unknown verb"

echo
echo "-- Forwarder connection accounting --"
# Concurrent requests fork one forwarder child each. Every one must be accepted (one isx exec
# opens about five at once, and socat's default backlog of 5 refused them), and every child
# must exit once its request is done, which is the baseline isx doctor's leak count assumes.
burst() {
    local n=$1 dir pids=() i ok=0
    dir=$(mktemp -d)
    for i in $(seq 1 "$n"); do
        api /1.0 > "$dir/$i" & pids+=($!)
    done
    wait "${pids[@]}"
    for i in $(seq 1 "$n"); do
        grep -q '"metadata"' "$dir/$i" && ok=$((ok + 1))
    done
    rm -rf "$dir"
    echo "$ok"
}
ok=$(burst 16)
expect '[ "$ok" -eq 16 ]' "16 concurrent API requests all answered ($ok/16)"
ok=$(burst 32)
expect '[ "$ok" -eq 32 ]' "32 concurrent API requests all answered ($ok/32)"

settled=$(settle_count "$baseline")
expect '[ "$settled" = "$baseline" ]' \
    "forwarder children exit with their connections (count $settled, baseline $baseline)"

if [ "${TUNNEL_BACKSTOP:-0}" = "1" ]; then
    echo
    echo "-- Inactivity backstop (socat -T $BACKSTOP_SECONDS) --"
    # The connection the backstop exists for: one that never sends a byte, as when a client's
    # close is lost crossing vfkit. It is also the one incusd cannot shed on its own. Its local
    # listener peeks 8 bytes inside Accept() with no deadline, so every new API connection
    # queues behind this one until it speaks or goes away, and a keep-alive connection is no
    # substitute: incusd closes those itself after 30s idle. Nothing else may run meanwhile.
    fifo=$(mktemp -u)
    mkfifo "$fifo"
    nc -U "$INCUS_SOCK" < "$fifo" >/dev/null 2>&1 &
    idle_pid=$!
    exec 3>"$fifo"   # held open: nc's stdin never reaches EOF, so it never sends anything
    idle_opened=$(date +%s)
    held=$(settle_count $((baseline + 1)))
    expect '[ "$held" = $((baseline + 1)) ]' "silent connection holds a forwarder child (count $held)"

    # Informational: this is incusd's behaviour, not ours, and an upstream fix would flip it.
    if curl -s --max-time 3 --unix-socket "$INCUS_SOCK" http://localhost/1.0 | grep -q '"metadata"'; then
        echo "  INFO: incusd served a new connection past the silent one (upstream accept fix?)"
    else
        echo "  INFO: incusd stalls new connections behind the silent one (StarttlsListener peek)"
    fi

    reaped_at=""
    deadline=$((idle_opened + BACKSTOP_SECONDS + 30))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ "$(socat_count)" = "$baseline" ]; then
            reaped_at=$(( $(date +%s) - idle_opened ))
            break
        fi
        sleep 5
    done
    exec 3>&-
    kill "$idle_pid" 2>/dev/null
    wait "$idle_pid" 2>/dev/null
    rm -f "$fifo"
    if [ -z "$reaped_at" ]; then
        fail "silent connection reaped by the backstop (still alive after $((BACKSTOP_SECONDS + 30))s)"
    elif [ "$reaped_at" -lt $((BACKSTOP_SECONDS - 10)) ]; then
        fail "silent connection outlived the long-poll floor (reaped after only ${reaped_at}s)"
    else
        pass "silent connection reaped by the backstop after ~${reaped_at}s"
    fi
    expect 'wait_for_api 10' "Incus API answers again once the backstop reaped it"
fi

echo
echo "-- No-reboot forwarder recovery --"
# The repair isx doctor offers: drop every forwarder socat and relaunch it, then the Incus
# API must answer again over the new forwarder, and the agent listener must survive it.
rec=$(agent forwarder-restart)
if [ "$rec" = restarted ]; then
    pass "forwarder-restart confirmed"
    expect 'wait_for_api 20'              "Incus API reachable again after forwarder-restart"
    expect '[ "$(agent ping)" = "ok" ]'   "control agent still up after forwarder recovery"
    # Recovery also clears children a leaky bridge left behind, so this can land below baseline.
    after=$(settle_count 1)
    expect '[ "$after" -ge 1 ] && [ "$after" -le "$baseline" ]' \
        "forwarder relaunched with no lingering children (count $after)"
else
    fail "forwarder-restart confirmed (got '$rec')"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "All $PASS tunnel checks passed."
else
    echo "$FAIL of $((PASS + FAIL)) tunnel checks failed."
    exit 1
fi
