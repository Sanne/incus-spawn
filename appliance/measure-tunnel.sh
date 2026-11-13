#!/usr/bin/env bash
set -uo pipefail

# Measure the appliance's host-facing tunnel under load, for comparing two programs that
# carry it: vfkit and the launcher's prototype on macOS (launcher-on-mac.sh, #2031), or two
# bridges under QEMU on Linux.
#
# test-tunnel.sh says whether the tunnel works. This says how often it fails and what it
# costs: repeated runs of test-tunnel.sh, bursts of concurrent requests, the latency of a
# request while hundreds of connections are held open, and how long the guest's forwarder
# children outlive their connections. It never stops the guest.
#
# Usage:  ./measure-tunnel.sh <incus-socket> <agent-socket> [<silent-socket>]
#
#   RUNS=24            runs of test-tunnel.sh
#   BURSTS="70 120"    sizes of the bursts of concurrent GET /1.0
#   BURST_ROUNDS=5     bursts of each size
#   HELD="0 200 400"   connections held open while latency is sampled
#   REQUESTS=200       requests per latency sample
#                      (RUNS=0, BURSTS="" or HELD="" leaves that phase out)
#   OUT=<dir>          keeps every log and `values` (key=value, what the summary is made
#                      of) in <dir>, which is otherwise a temporary directory deleted on exit
#
# Prints a summary. Exits 0 when every phase ran, 3 when the tunnel stopped answering (the
# wedge of #785), which ends the measurement, and 2 when it could not start.
#
# The numbers are timings: run it on a quiet machine, and say so where it was not.

INCUS_SOCK="${1:?usage: measure-tunnel.sh <incus-socket> <agent-socket> [<silent-socket>]}"
AGENT_SOCK="${2:?usage: measure-tunnel.sh <incus-socket> <agent-socket> [<silent-socket>]}"
SILENT_SOCK="${3:-}"
RUNS="${RUNS:-24}"
BURSTS="${BURSTS-70 120}"
BURST_ROUNDS="${BURST_ROUNDS:-5}"
HELD="${HELD-0 200 400}"
REQUESTS="${REQUESTS:-200}"
HERE="$(cd "$(dirname "$0")" && pwd)"

for tool in curl perl nc; do
    command -v "$tool" >/dev/null || { echo "measure-tunnel.sh needs $tool" >&2; exit 2; }
done

KEEP_OUT=1
if [ -z "${OUT:-}" ]; then OUT="$(mktemp -d)"; KEEP_OUT=0; fi
mkdir -p "$OUT" || exit 2
: > "$OUT/values"
HOLDER=""
cleanup() {
    [ -z "$HOLDER" ] || kill "$HOLDER" 2>/dev/null
    [ "$KEEP_OUT" = 1 ] || rm -rf "$OUT"
}
trap cleanup EXIT

# A held connection is one descriptor here, and a burst is one curl each: macOS allows a
# shell 256 open files unless asked.
ulimit -n 4096 2>/dev/null || ulimit -n "$(ulimit -Hn)" 2>/dev/null || true

# One agent verb per connection, with the client test-tunnel.sh uses: it never shuts its
# write side down, which the tunnel would not carry.
agent() { perl "$HERE/agent-call.pl" "$AGENT_SOCK" "$1" 2>/dev/null | tr -d '\r'; }
socat_count() { agent socat-count | tr -d '\n'; }
api() { curl -s --max-time "${2:-10}" --unix-socket "$INCUS_SOCK" "http://localhost$1" 2>/dev/null; }
# Milliseconds since the epoch. macOS ships bash 3.2, which has no EPOCHREALTIME.
if [ -n "${EPOCHREALTIME:-}" ]; then
    now_ms() { local t=${EPOCHREALTIME/[.,]/}; echo $(( t / 1000 )); }
else
    now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
fi
value() { echo "$1=$2" >> "$OUT/values"; }
# 1 to $1, and nothing for 0: BSD seq, which macOS has, counts down from 1 to 0.
upto() { local i=1; while [ "$i" -le "$1" ]; do echo "$i"; i=$((i + 1)); done; }

# Median, 95th percentile and maximum of the numbers on stdin, as "median p95 max count".
spread() {
    perl -e 'my @v = sort { $a <=> $b } grep { /^[0-9.]+$/ } map { chomp; $_ } <STDIN>;
             if (!@v) { print "- - - 0\n"; exit }
             my $at = sub { $v[int($_[0] * $#v + 0.5)] };
             printf "%s %s %s %d\n", $at->(0.5), $at->(0.95), $v[-1], scalar @v;'
}

# Wait up to $1 seconds for the forwarder to be down to its listener; prints the milliseconds
# it took, or nothing when it never got there. It asks the agent alone: a request to the API
# would itself be a connection whose child a leaking tunnel keeps for 30 s.
settle_ms() {
    local t0 deadline c
    t0=$(now_ms)
    deadline=$(( $(date +%s) + $1 ))
    while :; do
        c=$(socat_count)
        [[ "$c" =~ ^[0-9]+$ ]] && [ "$c" -le "$BASELINE" ] && { echo $(( $(now_ms) - t0 )); return; }
        [ "$(date +%s)" -ge "$deadline" ] && return
        sleep 0.5
    done
}

# Does the tunnel still answer? One request, then up to 30 s of them: a wedged tunnel
# answers none, a busy one answers late.
answers() {
    local deadline=$(( $(date +%s) + 30 ))
    while :; do
        [[ "$(api /1.0 5)" == *'"metadata"'* ]] && return 0
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 2
    done
}

wedged() {
    echo "WEDGED: the tunnel stopped answering $1. The measurement ends here."
    value wedged 1
    value wedged_at "$2"
    exit 3
}

STARTED=$(date +%s)
[[ "$(api /1.0)" == *'"metadata"'* ]] || { echo "the Incus API does not answer on $INCUS_SOCK" >&2; exit 2; }
[ "$(agent ping)" = "ok" ] || { echo "the control agent does not answer on $AGENT_SOCK" >&2; exit 2; }

# The forwarder's count when nothing is connected: readings a second apart until 35 in a row
# agree. A tunnel that loses the host's close keeps a child until incusd's idle close, 30 s
# after its request, and the request above was one.
BASELINE=""
same=0
for _ in $(seq 1 180); do
    c=$(socat_count)
    [[ "$c" =~ ^[0-9]+$ ]] || { echo "socat-count answered '$c'" >&2; exit 2; }
    if [ "$c" = "$BASELINE" ]; then same=$((same + 1)); else same=0; BASELINE=$c; fi
    [ "$same" -ge 34 ] && break
    sleep 1
done
[ "$same" -ge 34 ] || echo "WARNING: the forwarder's count did not settle in 180 s; $BASELINE is its last reading, and the counts below are read against it" >&2
echo "forwarder count with nothing connected: $BASELINE"
value baseline "$BASELINE"

# ── 1. Repeated runs of test-tunnel.sh ───────────────────────────────────────
phase=$(date +%s)
runs_failed=0
close_failed=0   # runs in which a forwarder child outlived its connection: check (a) of #2031
: > "$OUT/run-waits"
for r in $(upto "$RUNS"); do
    if [ -n "$SILENT_SOCK" ]; then
        "$HERE/test-tunnel.sh" "$INCUS_SOCK" "$AGENT_SOCK" "$SILENT_SOCK" > "$OUT/run-$r.log" 2>&1
    else
        "$HERE/test-tunnel.sh" "$INCUS_SOCK" "$AGENT_SOCK" > "$OUT/run-$r.log" 2>&1
    fi
    rc=$?
    [ "$rc" -eq 0 ] || runs_failed=$((runs_failed + 1))
    # The next run starts from the listener alone, so one run's leftovers are not the next
    # one's baseline. How long that takes is itself a result, on top of the 10 s for which
    # test-tunnel.sh's own two checks already waited.
    waited=$(settle_ms 90)
    echo "${waited:-}" >> "$OUT/run-waits"
    if grep -Eq 'FAIL: (forwarder children exit with their connections|their forwarder children exited with them)' "$OUT/run-$r.log"; then
        close_failed=$((close_failed + 1))
    fi
done
runs_s=$(( $(date +%s) - phase ))
never=$(grep -c '^$' "$OUT/run-waits")
read -r wait_med _ wait_max _ < <(spread < "$OUT/run-waits")
[ "$RUNS" -gt 0 ] && echo
[ "$RUNS" -gt 0 ] && echo "test-tunnel.sh, $RUNS runs in ${runs_s}s: $((RUNS - runs_failed)) passed, $runs_failed failed"
[ "$RUNS" -gt 0 ] && echo "  runs in which a forwarder child outlived its connection: $close_failed"
grep -h '^  FAIL: ' "$OUT"/run-*.log 2>/dev/null | sed -E 's/^  FAIL: //; s/ \([^()]*\)$//; s/[0-9]+ms/Nms/g' \
    | sort | uniq -c | sort -rn | sed 's/^ *\([0-9]*\) /  \1 x FAIL: /'
[ "$RUNS" -gt 0 ] && echo "  forwarder back to its listener after a run: median ${wait_med}ms, longest ${wait_max}ms, never within 90s: $never"
value runs "$RUNS"
value runs_failed "$runs_failed"
value close_failed "$close_failed"
value settle_after_run_median_ms "$wait_med"
value settle_after_run_never "$never"
answers || wedged "after the runs of test-tunnel.sh" runs

# ── 2. Bursts ────────────────────────────────────────────────────────────────
# One curl per request, all started at once. curl's exit status says how a request ended:
# 52 is "Empty reply from server" (the connection was accepted and then closed with nothing
# on it), 28 a timeout, 7 a refused connection.
[ -z "$BURSTS" ] || echo
for n in $BURSTS; do
    phase=$(date +%s)
    answered=0; empty=0; timed_out=0; refused=0; other=0
    : > "$OUT/burst-$n-waits"
    for round in $(upto "$BURST_ROUNDS"); do
        dir="$OUT/burst-$n-$round"
        mkdir -p "$dir"
        pids=()
        for i in $(upto "$n"); do
            ( curl -s --max-time 20 --unix-socket "$INCUS_SOCK" http://localhost/1.0 > "$dir/$i.body" 2>/dev/null
              echo $? > "$dir/$i.rc" ) &
            pids+=($!)
        done
        wait "${pids[@]}"
        for i in $(upto "$n"); do
            rc=$(cat "$dir/$i.rc" 2>/dev/null || echo 99)
            case "$rc" in
                0) if grep -q '"metadata"' "$dir/$i.body"; then answered=$((answered + 1)); else other=$((other + 1)); fi ;;
                52) empty=$((empty + 1)) ;;
                28) timed_out=$((timed_out + 1)) ;;
                7) refused=$((refused + 1)) ;;
                *) other=$((other + 1)) ;;
            esac
        done
        rm -rf "$dir"
        waited=$(settle_ms 90)
        echo "${waited:-}" >> "$OUT/burst-$n-waits"
        answers || wedged "after burst $round of $n" "burst_$n"
    done
    total=$((n * BURST_ROUNDS))
    never=$(grep -c '^$' "$OUT/burst-$n-waits")
    read -r wait_med _ wait_max _ < <(spread < "$OUT/burst-$n-waits")
    echo "bursts of $n, $BURST_ROUNDS of them in $(( $(date +%s) - phase ))s: $answered of $total answered, $empty empty replies, $timed_out timed out, $refused refused, $other failed otherwise"
    echo "  forwarder back to its listener after a burst: median ${wait_med}ms, longest ${wait_max}ms, never within 90s: $never"
    value "burst_${n}_requests" "$total"
    value "burst_${n}_missed" "$((total - answered))"
    value "burst_${n}_empty" "$empty"
    value "burst_${n}_settle_median_ms" "$wait_med"
done

# ── 3. Latency with connections held ─────────────────────────────────────────
# A held connection is an event stream, as isx's own long-lived connections are: a silent
# one would be closed by incusd after 3 s. The holder answers the daemon's keepalive pings,
# since the daemon drops a listener that does not.
#
# Both are called in this shell, never in a command substitution: the holder lives for as
# long as descriptor 9 is open, and a subshell would close it when it ended. So each leaves
# its answer in a variable.
# Sets HELD_NOW: how many connections were accepted.
hold() {
    mkfifo "$OUT/hold.in"
    # The output first: the holder waits in opening the pipe until descriptor 9 is opened
    # below, and the last hold's lines must be gone by then.
    perl "$HERE/tunnel-hold.pl" "$INCUS_SOCK" "$1" > "$OUT/hold.out" 2>"$OUT/hold.err" < "$OUT/hold.in" &
    HOLDER=$!
    exec 9> "$OUT/hold.in"
    for _ in $(seq 1 240); do
        grep -q '^held ' "$OUT/hold.out" 2>/dev/null && break
        kill -0 "$HOLDER" 2>/dev/null || break
        sleep 0.5
    done
    HELD_NOW=$(sed -n 's/^held //p' "$OUT/hold.out")
    HELD_NOW=${HELD_NOW:-0}
}
# Ends the holder. Sets OPEN_AT_END: how many of its connections were still open.
release() {
    exec 9>&-
    wait "$HOLDER" 2>/dev/null
    HOLDER=""
    rm -f "$OUT/hold.in"
    OPEN_AT_END=$(sed -n 's/^open //p' "$OUT/hold.out")
    OPEN_AT_END=${OPEN_AT_END:-0}
}

[ -z "$HELD" ] || echo
[ -z "$HELD" ] || echo "latency of GET /1.0 in ms (median, 95th percentile, longest), $REQUESTS requests each:"
for h in $HELD; do
    held=0
    if [ "$h" -gt 0 ]; then hold "$h"; held=$HELD_NOW; fi
    # A new connection for every request: the dial, the request and the close.
    : > "$OUT/new-$h"
    failed=0
    for _ in $(upto "$REQUESTS"); do
        # Only an answered request has a latency: a refusal is fast and a timeout is 20 s, and
        # either would move the median.
        if t=$(curl -sf -o /dev/null -w '%{time_total}' --max-time 20 --unix-socket "$INCUS_SOCK" http://localhost/1.0 2>/dev/null); then
            echo "$t" >> "$OUT/new-$h"
        else
            failed=$((failed + 1))
        fi
    done
    # Every request on one connection: what the relay adds to bytes it already carries. The
    # first is dropped, since it includes the dial, and so is one that was not answered.
    curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --max-time 120 --unix-socket "$INCUS_SOCK" \
        "http://localhost/1.0?n=[0-$REQUESTS]" 2>/dev/null | sed 1d | sed -n 's/^200 //p' > "$OUT/kept-$h"
    open=$held
    if [ "$h" -gt 0 ]; then release; open=$OPEN_AT_END; fi
    # curl prints seconds; the summary is in milliseconds.
    read -r n_med n_p95 n_max _ < <(perl -ne 'printf "%.3f\n", $_ * 1000 if /^[0-9.]+$/' "$OUT/new-$h" | spread)
    read -r k_med k_p95 k_max k_count < <(perl -ne 'printf "%.3f\n", $_ * 1000 if /^[0-9.]+$/' "$OUT/kept-$h" | spread)
    echo "  $h held ($held accepted, $open still open at the end):"
    echo "    a new connection per request: $n_med, $n_p95, $n_max ($failed of $REQUESTS failed)"
    echo "    one connection for all:       $k_med, $k_p95, $k_max ($k_count of $REQUESTS answered)"
    value "held_${h}_accepted" "$held"
    value "held_${h}_open_at_end" "$open"
    value "held_${h}_new_median_ms" "$n_med"
    value "held_${h}_new_p95_ms" "$n_p95"
    value "held_${h}_new_failed" "$failed"
    value "held_${h}_kept_median_ms" "$k_med"
    value "held_${h}_kept_p95_ms" "$k_p95"
    value "held_${h}_kept_answered" "$k_count"
    if [ "$h" -gt 0 ]; then
        # The host closed every held connection at once: how long the guest keeps their children.
        settled=$(settle_ms 120)
        echo "    forwarder back to its listener after the $held were closed: ${settled:-never within 120s}${settled:+ms}"
        value "held_${h}_settle_ms" "${settled:-never}"
    fi
    answers || wedged "after holding $h connections" "held_$h"
done

echo
echo "at the end: the API answers, the forwarder count is $(socat_count) (with nothing connected: $BASELINE)"
echo "measured in $(( $(date +%s) - STARTED ))s"
value wedged 0
value seconds "$(( $(date +%s) - STARTED ))"
