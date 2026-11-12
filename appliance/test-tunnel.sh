#!/usr/bin/env bash
set -uo pipefail

# Probe the appliance's host-facing tunnel: the Incus API through the in-guest vsock
# forwarder, and every verb of the in-guest control agent (isx-agent).
#
# Takes the two host-side Unix sockets, however they were bridged to the guest's vsock
# ports: vfkit on macOS (test-boot.sh), or the launcher's relay under QEMU on Linux (CI:
# isx-vsock-bridge, launcher/).
# The guest half under test is the same either way; only the bridge differs.
#
# Usage:  ./test-tunnel.sh <incus-socket> <agent-socket> [<silent-socket>]
#
#   <silent-socket>    the host side of the test-only silent endpoint (isx.test_silent_vsock):
#                      the production forwarder in front of a guest socket that never answers.
#   TUNNEL_BACKSTOP=1  also verify the forwarder's socat -T inactivity backstop against the
#                      silent endpoint: a silent connection must outlive the long-poll floor and
#                      then be reaped. Needs <silent-socket>. Adds about three minutes: the
#                      backstop is 180s.
#
# Prints one PASS/FAIL line per check and exits non-zero if any check failed.

INCUS_SOCK="${1:?usage: test-tunnel.sh <incus-socket> <agent-socket>}"
AGENT_SOCK="${2:?usage: test-tunnel.sh <incus-socket> <agent-socket>}"
SILENT_SOCK="${3:-}"

# Mirrors isx-start-vsock-forwarder's -T. The reap is checked against both edges: not before
# the floor (the /wait long-poll and the client watchdog rely on it), and not long after it.
BACKSTOP_SECONDS=180

PASS=0
FAIL=0
pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
expect() { if eval "$1"; then pass "$2"; else fail "$2${3:+ ($3)}"; fi; }

# One agent verb per connection, as VmAgentClient speaks it: send the verb, then keep the
# write side open until the agent hangs up. Shutting it down early hands every socat on the
# way an EOF, after which each waits only its -t (0.5s) for the reply: forwarder-restart
# answers after more than a second, so it would arrive at a closed connection. OpenBSD nc
# (Ubuntu, macOS) leaves the write side open; nmap's ncat (Fedora's nc) shuts it down on
# stdin EOF unless given --no-shutdown. ncat's -w bounds only the connect, and its -i waits
# out its full timeout even after the agent hangs up, so timeout bounds the call instead: the
# whole call rather than an idle gap, hence the longer limit.
AGENT_NC=(nc -w 8)
if [[ "$(nc --version 2>&1)" == *Ncat* ]]; then
    command -v timeout >/dev/null || { echo "nc is ncat, which needs timeout (coreutils) to bound agent calls" >&2; exit 2; }
    AGENT_NC=(timeout 30 nc --no-shutdown)
fi
agent() { printf '%s\n' "$1" | "${AGENT_NC[@]}" -U "$AGENT_SOCK" 2>/dev/null | tr -d '\r'; }

# Hold a connection to socket $1 that sends $2 (possibly nothing) and then nothing for $3
# seconds; $HELD is the process holding it, which exits as soon as the far end closes. socat
# does that, and so does OpenBSD nc (Ubuntu, macOS); ncat waits for its stdin to end instead.
# The sleep feeding it outlives it, so the EXIT trap ends any that are left.
if command -v socat >/dev/null; then
    hold() { ( printf '%s' "$2"; exec sleep "$3" ) 2>/dev/null | socat -t 0.1 - UNIX-CONNECT:"$1" >/dev/null 2>&1 & HELD=$!; }
elif [[ "$(nc --version 2>&1)" != *Ncat* ]]; then
    hold() { ( printf '%s' "$2"; exec sleep "$3" ) 2>/dev/null | nc -U "$1" >/dev/null 2>&1 & HELD=$!; }
else
    echo "nc is ncat, which does not exit when the far end closes; install socat" >&2
    exit 2
fi
trap 'pkill -P $$ -x sleep 2>/dev/null' EXIT
# Milliseconds since the epoch. macOS ships bash 3.2, which has no EPOCHREALTIME.
if [ -n "${EPOCHREALTIME:-}" ]; then
    now_ms() { local t=${EPOCHREALTIME/[.,]/}; echo $(( t / 1000 )); }
else
    now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
fi
# Wait up to $1 seconds for the process $2 to exit; prints the ms since $T0 it took, or nothing.
wait_gone() {
    local deadline=$(( $(date +%s) + $1 ))
    while kill -0 "$2" 2>/dev/null; do
        [ "$(date +%s)" -ge "$deadline" ] && return
        sleep 0.1
    done
    echo $(( $(now_ms) - T0 ))
}
api() { curl -s --max-time "${2:-10}" --unix-socket "$INCUS_SOCK" "http://localhost$1" 2>/dev/null; }
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

# btrfs-orphan-delete validates before it ever asks Incus or touches a subvolume (#874): a bad
# pool, kind or name, or a name that hides a nested path, must all be refused up front.
expect '[ "$(agent "btrfs-orphan-delete ../x containers foo")" = "error: bad pool name" ]' \
    "btrfs-orphan-delete rejects a path-like pool name"
expect '[ "$(agent "btrfs-orphan-delete cow snapshots foo")" = "error: bad kind" ]' \
    "btrfs-orphan-delete rejects a kind other than containers/virtual-machines"
expect '[ "$(agent "btrfs-orphan-delete cow containers ../etc")" = "error: bad name" ]' \
    "btrfs-orphan-delete rejects a path-like name"
expect '[ "$(agent "btrfs-orphan-delete cow containers nested/path")" = "error: bad name" ]' \
    "btrfs-orphan-delete rejects a name that hides a nested path"
# ".." alone is a valid A-Za-z0-9._- string, so the charset check above does not catch it on its
# own; resolved against the pool root (itself a btrfs subvolume) it would delete every instance,
# image and snapshot on the pool (review on #874).
expect '[ "$(agent "btrfs-orphan-delete cow containers ..")" = "error: bad name" ]' \
    "btrfs-orphan-delete rejects .. on its own"
expect '[ "$(agent "btrfs-orphan-delete cow containers .")" = "error: bad name" ]' \
    "btrfs-orphan-delete rejects a bare dot"
expect '[ "$(agent "btrfs-orphan-delete cow containers -rf")" = "error: bad name" ]' \
    "btrfs-orphan-delete rejects a name that could be read as an option"
# A validated name this fresh appliance never built is not a subvolume at all: the Incus
# re-check (any project, instances and volumes) and the delete must not be reached for it.
# Refusing a name Incus *does* still hold needs a real instance or volume, which a bare-tunnel
# boot has none of yet; that path is exercised once a template exists (test-with-isx.sh /
# a branch workflow), not here.
expect '[ "$(agent "btrfs-orphan-delete cow containers never-built")" = "error: not a subvolume" ]' \
    "btrfs-orphan-delete refuses a name with no subvolume"

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

echo
echo "-- Silent and partial clients (incusd STARTTLS peek) --"
# incusd peeks the first 8 bytes of every new local connection for a STARTTLS upgrade. Before
# lxc/incus@2cb2549 (backported by build.sh, #1313) it did so in its accept loop with no
# deadline, so one connection that sent fewer than 8 bytes stalled every new API connection,
# for up to the forwarder's 180s when vfkit lost the client's close (#791 B.1). Now each
# connection is classified on its own under a 3s read deadline: a request is served next to a
# held connection well inside that deadline (a request that waited for it would take at least
# 3s), and incusd then closes the held one itself, which frees its forwarder child. The held
# connections end on their own after 10s, so a failure here leaves nothing behind.
T0=$(now_ms)
r=$(api /1.0)
expect '[[ "$r" == *"\"metadata\""* ]]' "GET /1.0 answered in $(( $(now_ms) - T0 ))ms, the baseline"
held_check() {
    T0=$(now_ms)
    hold "$INCUS_SOCK" "$1" 10
    local pid=$HELD served closed
    sleep 0.3   # the held connection reaches incusd first
    # Matched in a string, not by grep -q: under pipefail, grep exiting at the first match can
    # fail curl with EPIPE while the rest of the body is still coming.
    if [[ "$(api /1.0 2)" != *'"metadata"'* ]]; then
        fail "request served past a connection that sent $2 (it waited behind it)"
    elif ! kill -0 "$pid" 2>/dev/null; then
        fail "the connection that sent $2 was still open when the request was served (it had closed)"
    else
        served=$(( $(now_ms) - T0 ))
        expect '[ "$served" -lt 2500 ]' "request served past a connection that sent $2 (${served}ms)"
    fi
    closed=$(wait_gone 12 "$pid")
    expect '[ -n "$closed" ] && [ "$closed" -ge 2500 ] && [ "$closed" -le 7000 ]' \
        "incusd closed the connection that sent $2 near its 3s deadline (${closed:-not closed}ms)"
}
held_check "" "nothing"
held_check "GET /1." "7 bytes"

pids=()
for b in "" "" "" "" "GET /1." "GET /1." "GET /1." "GET /1."; do
    hold "$INCUS_SOCK" "$b" 10
    pids+=("$HELD")
done
sleep 0.3
T0=$(now_ms)   # from here, so slow process starts (aarch64 under TCG) do not count
ok=$(burst 3)
served=$(( $(now_ms) - T0 ))
open=0
for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && open=$((open + 1)); done
expect '[ "$ok" -eq 3 ] && [ "$open" -eq 8 ] && [ "$served" -lt 2500 ]' \
    "3 requests served past 8 silent and partial connections ($ok/3, ${served}ms, $open/8 still open)"
for p in "${pids[@]}"; do closed=$(wait_gone 12 "$p"); [ -n "$closed" ] || break; done
expect '[ -n "$closed" ] && [ "$closed" -le 7000 ]' "incusd closed all 8 (${closed:-not closed}ms)"
settled=$(settle_count "$baseline")
expect '[ "$settled" = "$baseline" ]' \
    "their forwarder children exited with them (count $settled, baseline $baseline)"

if [ "${TUNNEL_BACKSTOP:-0}" = "1" ]; then
    echo
    echo "-- Inactivity backstop (socat -T $BACKSTOP_SECONDS) --"
    # The connection the backstop exists for: one that never sends a byte and whose close is
    # lost crossing vfkit. incusd now closes such a connection itself after 3s (above), so the
    # backstop is timed against the test-only silent endpoint instead: the production forwarder
    # invocation in front of a guest socket that accepts and never answers, with no timeout of
    # its own. The forwarder child's exit closes the connection here, so the hold timing it is
    # the reap. Its children are not counted by socat-count, which matches incusd's socket only.
    if [ -z "$SILENT_SOCK" ]; then
        fail "silent endpoint socket given (TUNNEL_BACKSTOP=1 needs the third argument)"
    elif ! [ -S "$SILENT_SOCK" ]; then
        fail "silent endpoint socket exists ($SILENT_SOCK)"
    else
        T0=$(now_ms)
        hold "$SILENT_SOCK" "" $((BACKSTOP_SECONDS + 60))
        idle_pid=$HELD
        sleep 1
        expect 'kill -0 "$idle_pid" 2>/dev/null' "silent endpoint holds the connection open"
        expect 'api /1.0 | grep -q "\"metadata\""' "Incus API answers while the silent connection is held"

        # The wait is silent by nature and lasts the whole backstop. Say so, and report progress, so a
        # run cut short here is not mistaken for a hang: #1093 was two CI cancellations two minutes into
        # this wait, read as a stuck teardown.
        echo "  waiting for the backstop to reap it (up to $((BACKSTOP_SECONDS + 30))s)"
        reaped_ms=""
        next_report=30
        while [ $(( ($(now_ms) - T0) / 1000 )) -lt $((BACKSTOP_SECONDS + 30)) ]; do
            if ! kill -0 "$idle_pid" 2>/dev/null; then
                reaped_ms=$(( $(now_ms) - T0 ))
                break
            fi
            if [ $(( ($(now_ms) - T0) / 1000 )) -ge "$next_report" ]; then
                echo "  ... ${next_report}s, still held"
                next_report=$((next_report + 30))
            fi
            sleep 1
        done
        kill "$idle_pid" 2>/dev/null
        wait "$idle_pid" 2>/dev/null
        if [ -z "$reaped_ms" ]; then
            fail "silent connection reaped by the backstop (still alive after $((BACKSTOP_SECONDS + 30))s)"
        elif [ "$reaped_ms" -lt $(( (BACKSTOP_SECONDS - 10) * 1000 )) ]; then
            fail "silent connection outlived the long-poll floor (reaped after only $((reaped_ms / 1000))s)"
        else
            pass "silent connection reaped by the backstop after ~$((reaped_ms / 1000))s"
        fi
        expect 'api /1.0 | grep -q "\"metadata\""' "Incus API still answers after the reap"
    fi
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

# A caller that hangs up mid-restart (a client timeout, a cancelled isx doctor) must not leave
# the guest without a forwarder. Hang up once the API is down (two failed probes in a row, so
# one stray failure does not count), i.e. after the agent's pkill and before its relaunch a
# second later. The guest's socat then terminates the agent about 0.5s (its -t) after the
# hang-up, so the check only tells a fixed agent from an unfixed one if the hang-up comes
# within about half a second of the pkill (measured under KVM: an unfixed agent fails with
# the hang-up 0.45s after the API went down, and passes at 0.55s). A slower hang-up is
# reported, since the check then passes either way. The pipeline is spelled out rather than
# run as `agent ... &`, so that $! is nc (or the timeout wrapping it) and the kill hangs up.
printf 'forwarder-restart\n' | "${AGENT_NC[@]}" -U "$AGENT_SOCK" >/dev/null 2>&1 &
caller=$!
sent=${EPOCHREALTIME:-}
down=no
misses=0
for _ in $(seq 1 80); do
    case "$(api /1.0)" in
        *'"metadata"'*) misses=0 ;;
        *) misses=$((misses + 1)); [ "$misses" -ge 2 ] && { down=yes; break; }; continue ;;
    esac
    sleep 0.05
done
kill "$caller" 2>/dev/null
if [ -n "$sent" ]; then
    late=$(( (${EPOCHREALTIME/[.,]/} - ${sent/[.,]/}) / 1000 ))
    [ "$late" -gt 400 ] && echo "  NOTE: hung up ${late}ms after the request; the next checks pass with or without the fix"
fi
wait "$caller" 2>/dev/null
if [ "$down" = yes ]; then
    pass "forwarder-restart took the forwarder down before its caller hung up"
    expect 'wait_for_api 20'              "Incus API reachable again after a caller hung up on forwarder-restart"
    expect '[ "$(agent ping)" = "ok" ]'   "control agent still up after a caller hung up on forwarder-restart"
    after=$(settle_count 1)
    expect '[ "$after" -ge 1 ] && [ "$after" -le "$baseline" ]' \
        "one forwarder relaunched after a caller hung up (count $after)"
else
    fail "forwarder-restart took the forwarder down before its caller hung up"
fi

# Last, and only when asked, since it ends the guest: the agent's shutdown verb (#881). The
# hypervisor's own stop request does nothing in this guest, so this verb is the only clean
# stop there is. The caller, which owns the hypervisor, checks that it exited and that rcK ran.
if [ "${TUNNEL_SHUTDOWN:-0}" = 1 ]; then
    echo "-- Shutdown --"
    expect '[ "$(agent shutdown)" = "shutting down" ]' "agent accepts shutdown"
    gone=no
    for _ in $(seq 1 30); do
        if [ "$(agent ping)" != "ok" ]; then gone=yes; break; fi
        sleep 1
    done
    expect '[ "$gone" = yes ]' "the guest went down after shutdown"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "All $PASS tunnel checks passed."
else
    echo "$FAIL of $((PASS + FAIL)) tunnel checks failed."
    exit 1
fi
