#!/bin/bash
set -uo pipefail

# Compare the Mac's VM under two isx builds: one from before the launcher, which runs the VM under
# vfkit, and one that runs it under isx-vm (#2031). Everything is measured on this Mac's own VM,
# through isx, so what is compared is what a user gets after the upgrade.
#
#   bench/vm-launcher-compare.sh --old=<isx> --new=<isx> [--rounds=5] [--sleep] [--out=<dir>]
#
# --old is an isx that runs vfkit (Homebrew's previous release, or a build of the base commit),
# --new one with isx-vm beside it. Each round restarts the VM under each in turn, in alternating
# order (old first in odd rounds), and measures, for each:
#
#   start_ms            isx vm start until isx list answers
#   stop_ms             isx vm stop, of the VM the round started
#   idle_*              after IDLE_SETTLE seconds idle, over IDLE_WINDOW seconds: the launcher's
#                       and the framework's machine process's resident memory and CPU
#   machine_fds         descriptors the machine process has open, idle (lsof's numbered rows only)
#   branch_prompt_ms    bench/trace-branch.sh --isx: a branch to its prompt (TRACES times)
#   exec_ms             incus exec <instance> -- true, through the VM's socket (EXECS times)
#   disk_write_mbs      an instance writes IO_MIB MiB to its root disk, with fsync
#   disk_read_mbs       and reads it back, past its page cache (O_DIRECT)
#   share_read_mbs      an instance reads an IO_MIB MiB file of the home through its inbox (the share)
#   net_mbs             an instance downloads NET_URL (net_failed: a download that was not one,
#                       its status in the log, never counted as 0 MB/s)
#   probe_timeouts      execs in the instance that ran past PROBE_TIMEOUT (150 s) and were cut
#   held_* / burst_*    appliance/measure-tunnel.sh on the VM's sockets: latency with 0 and 150
#                       connections held, 192 held (the launcher's room), bursts of 70 and 120,
#                       and the machine's descriptors while they are held
#   share_busy_*        the same with an instance holding SHARE_FILES (40) files of the home open
#                       through the share, and the room full: the machine process's descriptors
#   relay_*_refused_*   isx-vm alone: what each relay refused, from its control socket, per phase
#
# --sleep, once per build after the rounds: a minute of sleep with 20 connections held, then how
# soon the API answers, what the host still holds and the guest's clock (needs sudo -n pmset).
#
# Prints a table of every metric: each build's median, its range and its sample count, and
# whether the new build is worse by more than a tenth (a regression to look at). Writes the raw
# values to <out>/values-<old|new> (round key=value), so a reading can be redone.
#
# A measurement: run it on a Mac that is otherwise idle, on mains power, and say where it was
# not. It changes this Mac's VM (stops and starts it) and leaves it under the new build.
#
# Needs: macOS on Apple silicon, incus (the client), curl, perl, python3, and a template
# TEMPLATE (tpl-minimal) built in the VM.

ROUNDS=5
SLEEP=0
OLD=""
NEW=""
OUT=""
for arg in "$@"; do
    case "$arg" in
        --old=*) OLD="${arg#--old=}" ;;
        --new=*) NEW="${arg#--new=}" ;;
        --rounds=*) ROUNDS="${arg#--rounds=}" ;;
        --sleep) SLEEP=1 ;;
        --out=*) OUT="${arg#--out=}" ;;
        *) echo "unknown option $arg" >&2; exit 2 ;;
    esac
done
[ -n "$OLD" ] && [ -n "$NEW" ] || { echo "usage: $0 --old=<isx> --new=<isx> [--rounds=N] [--sleep] [--out=<dir>]" >&2; exit 2; }
[[ "$ROUNDS" =~ ^[1-9][0-9]*$ ]] || { echo "--rounds takes a number" >&2; exit 2; }
[ "$(uname -s)" = Darwin ] || { echo "this measures the Mac's VM, so it runs on macOS" >&2; exit 2; }
for tool in incus curl perl python3 lsof; do
    command -v "$tool" > /dev/null || { echo "needs $tool" >&2; exit 2; }
done

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TEMPLATE="${TEMPLATE:-tpl-minimal}"
IDLE_SETTLE="${IDLE_SETTLE:-30}"
IDLE_WINDOW="${IDLE_WINDOW:-60}"
TRACES="${TRACES:-5}"
EXECS="${EXECS:-20}"
IO_MIB="${IO_MIB:-512}"
# The first 100 MiB of a kernel tarball from kernel.org's CDN (served whole, the range asked of it):
# a large static file any client may fetch. speed.cloudflare.com's test URL answers 403 from the VM.
NET_URL="${NET_URL:-https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.12.tar.xz}"
# isx's own VM state directory (Environment.vmStateDir).
STATE="$HOME/.local/state/incus-spawn"
[ -n "$OUT" ] || OUT="$HOME/.cache/incus-spawn/launcher-compare/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT" "$HOME/.cache/incus-spawn/launcher-compare" || exit 2
: > "$OUT/values-old"; : > "$OUT/values-new"
CONF="$(mktemp -d)"
SHARE="$(mktemp -d "$HOME/.cache/incus-spawn/launcher-compare/share.XXXXXX")"
PROBE=""
CURRENT=""
cleanup() {
    [ -z "$PROBE" ] || "$CURRENT" destroy "$PROBE" --skip-confirmation > /dev/null 2>&1
    rm -rf "$CONF" "$SHARE"
}
trap cleanup EXIT

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
say() { echo "[$(date +%H:%M:%S)] $*"; }
note() { echo "$2 $3=$4" >> "$OUT/values-$1"; }   # note <side> <round> <key> <value>

launcher_pid() { cat "$STATE/vm.pid" 2> /dev/null; }
launcher_name() { ps -o comm= -p "$(launcher_pid)" 2> /dev/null | sed 's#.*/##'; }
# The framework's process that runs the machine: the newest, which is the one the round started.
machine_pid() { pgrep -n -f com.apple.Virtualization.VirtualMachine 2> /dev/null; }
# Numbered descriptors only: lsof also lists the working directory, the program and mapped files.
open_descriptors() { lsof -n -P -p "$1" -F f 2> /dev/null | grep -c '^f[0-9]'; }
# CPU time of a process in centiseconds (ps prints [[dd-]hh:]mm:ss.cc).
cpu_cs() {
    ps -o time= -p "$1" 2> /dev/null | python3 -c 'import sys
t = sys.stdin.read().strip()
if not t: sys.exit(0)
d, _, rest = t.rpartition("-")
parts = [float(p) for p in rest.split(":")]
s = 0
for p in parts: s = s * 60 + p
print(int(((int(d) if d else 0) * 86400 + s) * 100))'
}

api_up() { "$1" list -q > /dev/null 2>&1; }
# Every exec in the probe instance is bounded (PROBE_TIMEOUT seconds): one stalled exec must not
# cost every later sample of its side. A sample that timed out is left out, and counted per round
# as probe_timeouts. macOS has no timeout(1), and a plain alarm does not end incus (Go's runtime
# takes SIGALRM), so perl forks it, and on the alarm sends it SIGTERM, then SIGKILL 5 s later.
# It is above curl's --max-time of the download, so a slow download still says its status.
PROBE_TIMEOUT="${PROBE_TIMEOUT:-150}"
bounded() {
    perl -e '
        my $limit = shift;
        my $pid = fork;
        defined $pid or exit 127;
        if ($pid == 0) { exec @ARGV or exit 127 }
        $SIG{ALRM} = sub {
            kill "TERM", $pid;
            for (1 .. 50) { exit 142 if waitpid($pid, 1) > 0; select(undef, undef, undef, 0.1) }
            kill "KILL", $pid; waitpid($pid, 0); exit 142;
        };
        alarm $limit;
        waitpid($pid, 0);
        exit($? & 127 ? 128 + ($? & 127) : $? >> 8);
    ' "$@"
}
in_probe() {
    local rc=0
    INCUS_CONF="$CONF" bounded "$PROBE_TIMEOUT" incus exec "isx-appliance:$PROBE" -- "$@" < /dev/null || rc=$?
    # 142: the bound
    # A file, not a variable: most calls run in a command substitution's subshell.
    [ "$rc" != 142 ] || { echo "$*" >> "$OUT/timeouts-$SIDE-$ROUND"; say "an exec in the probe ran past ${PROBE_TIMEOUT}s: $*" >&2; }
    return "$rc"
}

# A file of the home, read through the share.
dd if=/dev/urandom of="$SHARE/blob" bs=1048576 count="$IO_MIB" 2> /dev/null
# Small files the busy-share phase holds open, one descriptor each in the machine's process.
SHARE_FILES="${SHARE_FILES:-40}"
for i in $(seq 1 "$SHARE_FILES"); do echo "$i" > "$SHARE/f$i"; done
chmod 755 "$SHARE"; chmod 644 "$SHARE"/*
INCUS_CONF="$CONF" incus remote add isx-appliance "unix://$STATE/vm.incus.sock" > /dev/null 2>&1

measure() {
    local side="$1" isx="$2" round="$3" want t0 rc=0
    SIDE="$side" ROUND="$round"
    : > "$OUT/timeouts-$side-$round"
    CURRENT="$isx"
    if [ "$side" = old ]; then want=vfkit; else want="isx-vm"; fi
    say "round $round: $side ($want)"

    # The VM under this build. The stop is the previous build's VM, stopped by the build that
    # started it and timed in that build's column: an isx from before the launcher does not
    # recognise an isx-vm VM as running, and would start vfkit beside it on the same disks.
    if [ -s "$STATE/vm.pid" ]; then
        local prev; prev="$(launcher_name)"
        case "$prev" in
            vfkit) t0=$(now_ms); "$OLD" vm stop > "$OUT/stop-$side-$round.log" 2>&1; note old "$round" stop_ms $(( $(now_ms) - t0 )) ;;
            isx-vm) t0=$(now_ms); "$NEW" vm stop > "$OUT/stop-$side-$round.log" 2>&1; note new "$round" stop_ms $(( $(now_ms) - t0 )) ;;
            *) "$NEW" vm stop > "$OUT/stop-$side-$round.log" 2>&1 ;;
        esac
        [ -z "$(launcher_name)" ] || { say "FAIL: the VM is still running under $(launcher_name) after its stop"; return 1; }
    fi
    t0=$(now_ms)
    "$isx" vm start > "$OUT/start-$side-$round.log" 2>&1
    until api_up "$isx"; do
        [ $(( $(now_ms) - t0 )) -lt 120000 ] || { say "FAIL: the API did not answer within 120 s"; return 1; }
        sleep 0.2
    done
    note "$side" "$round" start_ms $(( $(now_ms) - t0 ))
    [ "$(launcher_name)" = "$want" ] || { say "FAIL: the VM runs under '$(launcher_name)', not $want"; return 1; }

    # Idle: memory and CPU of both processes, descriptors of the machine's.
    sleep "$IDLE_SETTLE"
    local lp mp lc0 mc0 lc1 mc1
    lp="$(launcher_pid)"; mp="$(machine_pid)"
    lc0="$(cpu_cs "$lp")"; mc0="$(cpu_cs "$mp")"
    sleep "$IDLE_WINDOW"
    lc1="$(cpu_cs "$lp")"; mc1="$(cpu_cs "$mp")"
    note "$side" "$round" idle_launcher_rss_kb "$(ps -o rss= -p "$lp" | tr -d ' ')"
    note "$side" "$round" idle_machine_rss_kb "$(ps -o rss= -p "$mp" | tr -d ' ')"
    note "$side" "$round" idle_launcher_cpu_pct "$(python3 -c "print(round(($lc1 - $lc0) / $IDLE_WINDOW, 2))")"
    note "$side" "$round" idle_machine_cpu_pct "$(python3 -c "print(round(($mc1 - $mc0) / $IDLE_WINDOW, 2))")"
    note "$side" "$round" machine_fds "$(open_descriptors "$mp")"

    # Branch to the prompt.
    local line
    for _ in $(seq 1 "$TRACES"); do
        line="$("$ROOT/bench/trace-branch.sh" --isx="$(command -v "$isx" || echo "$isx")" --from="$TEMPLATE" 2>&1 \
            | tee -a "$OUT/trace-$side-$round.log" | grep '^Prompt after ' || true)"
        [ -n "$line" ] && note "$side" "$round" branch_prompt_ms "$(echo "$line" | sed -E 's/^Prompt after ([0-9]+) ms.*/\1/')"
    done

    # One instance for exec, IO and network, with the share as its inbox.
    PROBE="compare-$side-$round-$$"
    if ! "$isx" branch --from "$TEMPLATE" "$PROBE" --inbox "$SHARE" --format=plain < /dev/null > "$OUT/probe-$side-$round.log" 2>&1; then
        say "FAIL: no probe instance"; PROBE=""; return 1
    fi
    for _ in $(seq 1 30); do in_probe true 2> /dev/null && break; sleep 1; done
    for _ in $(seq 1 "$EXECS"); do
        t0=$(now_ms); in_probe true && note "$side" "$round" exec_ms $(( $(now_ms) - t0 ))
    done
    # Seconds as the guest counts them, so the exec's own cost is not in the throughput.
    local secs
    secs="$(in_probe sh -c "s=\$(date +%s%N); dd if=/dev/zero of=/var/tmp/io bs=1M count=$IO_MIB conv=fsync 2>/dev/null; e=\$(date +%s%N); echo \$(( (e - s) / 1000000 ))")"
    [[ "$secs" =~ ^[0-9]+$ ]] && [ "$secs" -gt 0 ] && note "$side" "$round" disk_write_mbs "$(( IO_MIB * 1000 / secs ))"
    secs="$(in_probe sh -c "s=\$(date +%s%N); dd if=/var/tmp/io of=/dev/null bs=1M iflag=direct 2>/dev/null; e=\$(date +%s%N); echo \$(( (e - s) / 1000000 ))")"
    [[ "$secs" =~ ^[0-9]+$ ]] && [ "$secs" -gt 0 ] && note "$side" "$round" disk_read_mbs "$(( IO_MIB * 1000 / secs ))"
    secs="$(in_probe sh -c "s=\$(date +%s%N); dd if=/home/agentuser/inbox/blob of=/dev/null bs=1M 2>/dev/null; e=\$(date +%s%N); echo \$(( (e - s) / 1000000 ))")"
    [[ "$secs" =~ ^[0-9]+$ ]] && [ "$secs" -gt 0 ] && note "$side" "$round" share_read_mbs "$(( IO_MIB * 1000 / secs ))"
    # The status and size too: an answer that is not the download (a refusal, a challenge page)
    # is no throughput, and is counted as net_failed, its status in the log, never as 0 MB/s.
    local got code size speed
    got="$(in_probe curl -sL -r 0-104857599 -o /dev/null --max-time 120 -w '%{http_code} %{size_download} %{speed_download}' "$NET_URL")"
    read -r code size speed <<< "$got"
    if { [ "$code" = 200 ] || [ "$code" = 206 ]; } && [[ "$size" =~ ^[0-9]+$ ]] && [ "$size" -gt 0 ] && [[ "$speed" =~ ^[0-9.]+$ ]]; then
        note "$side" "$round" net_mbs "$(python3 -c "print(round($speed / 1048576, 1))")"
    else
        note "$side" "$round" net_failed 1
        say "the download of $NET_URL got status '${code:-none}' and ${size:-0} bytes" >&2
    fi

    # The tunnel under load, with the machine's descriptors read while connections are held.
    # 192 is the launcher's room (GuestRoom): its relays hold no more at once.
    local probe="$OUT/held-probe.sh"
    printf '#!/bin/sh\nlsof -n -P -p "$(pgrep -n -f com.apple.Virtualization.VirtualMachine)" -F f 2>/dev/null | grep -c "^f[0-9]"\n' > "$probe"
    chmod +x "$probe"
    tunnel() {  # tunnel <label> <HELD> <BURSTS>
        RUNS=0 BURSTS="$3" BURST_ROUNDS=3 HELD="$2" ROOM=192 REQUESTS=200 HELD_PROBE="$probe" OUT="$OUT/tunnel-$1-$side-$round" \
            "$ROOT/appliance/measure-tunnel.sh" "$STATE/vm.incus.sock" "$STATE/vm.agent.sock" > "$OUT/tunnel-$1-$side-$round.log" 2>&1 || rc=$?
    }
    # The relays' own counts, which only isx-vm has: what each relay refused is its line's last.
    relay_counts() {
        [ -S "$STATE/vm.control.sock" ] || return 0
        local line
        perl "$ROOT/appliance/agent-call.pl" "$STATE/vm.control.sock" counts 2> /dev/null | tr -d '\r' > "$OUT/counts-$1-$side-$round"
        while read -r line; do
            set -- $line
            [ "$#" = 9 ] && note "$side" "$round" "relay_$1_refused_after_$PHASE" "$9"
        done < "$OUT/counts-$PHASE-$side-$round"
    }
    PHASE=load; tunnel load "0 150 192" "70 120"; relay_counts load
    local key v
    for key in held_0_new_median_ms held_0_kept_median_ms held_150_new_median_ms held_150_kept_median_ms \
            held_150_machine_fds held_192_accepted held_192_machine_fds burst_70_missed burst_120_missed wedged; do
        v="$(sed -n "s/^$key=//p" "$OUT/tunnel-load-$side-$round/values" 2> /dev/null | tail -n 1)"
        [ -n "$v" ] && note "$side" "$round" "$key" "$v"
    done

    # A busy share with the room full: the instance holds SHARE_FILES files of the home open, and
    # the machine's process opens each of them too, beside a descriptor for each connection.
    in_probe sh -c "cd /home/agentuser/inbox && setsid nohup tail -f f* > /dev/null 2>&1 < /dev/null & echo \$! > /tmp/holder" 2> /dev/null
    sleep 2
    note "$side" "$round" share_busy_machine_fds "$(open_descriptors "$(machine_pid)")"
    PHASE=share; tunnel share "192" ""; relay_counts share
    for key in held_192_accepted held_192_machine_fds wedged; do
        v="$(sed -n "s/^$key=//p" "$OUT/tunnel-share-$side-$round/values" 2> /dev/null | tail -n 1)"
        [ -n "$v" ] && note "$side" "$round" "share_busy_$key" "$v"
    done
    in_probe sh -c 'kill "$(cat /tmp/holder)"' 2> /dev/null
    "$isx" destroy "$PROBE" --skip-confirmation > /dev/null 2>&1
    PROBE=""
    note "$side" "$round" probe_timeouts "$(wc -l < "$OUT/timeouts-$side-$round" | tr -d ' ')"
    return "$rc"
}

# A minute of sleep with 20 event streams held; then the API's answer, what is still held, the clock.
clock_off() {
    local guest
    guest="$(curl -sI --max-time 10 --unix-socket "$STATE/vm.incus.sock" http://localhost/1.0 | tr -d '\r' | sed -n 's/^[Dd]ate: //p')"
    python3 -c 'import email.utils, sys, time
print(round(email.utils.parsedate_to_datetime(sys.argv[1]).timestamp() - time.time()))' "$guest" 2> /dev/null
}
sleep_wake() {
    local side="$1" isx="$2" dir="$OUT/sleep-$1" t gone asleep="" holder
    sudo -n pmset -g > /dev/null 2>&1 || { say "sudo -n pmset is refused: no sleep test"; return 1; }
    mkdir -p "$dir"; rm -f "$dir/hold.in"; mkfifo "$dir/hold.in"
    perl "$ROOT/appliance/tunnel-hold.pl" "$STATE/vm.incus.sock" 20 > "$dir/hold.out" 2> /dev/null < "$dir/hold.in" &
    holder=$!
    exec 8> "$dir/hold.in"
    for _ in $(seq 1 60); do grep -q '^held ' "$dir/hold.out" && break; sleep 1; done
    note "$side" sleep sleep_clock_off_before_s "$(clock_off)"
    if ! sudo -n pmset relative wake 60; then exec 8>&-; wait "$holder"; say "no wake could be scheduled"; return 1; fi
    pmset sleepnow
    for _ in $(seq 1 60); do
        t=$(date +%s); sleep 2; gone=$(( $(date +%s) - t ))
        if [ "$gone" -ge 20 ]; then asleep=$gone; break; fi
    done
    [ -n "$asleep" ] || { exec 8>&-; wait "$holder"; say "the Mac did not sleep"; return 1; }
    local woke; woke=$(now_ms)
    while [ $(( $(now_ms) - woke )) -lt 120000 ]; do
        if api_up "$isx"; then note "$side" sleep wake_api_ms $(( $(now_ms) - woke )); break; fi
        sleep 0.5
    done
    sleep 10
    exec 8>&-; wait "$holder" 2> /dev/null
    note "$side" sleep wake_asleep_s "$asleep"
    note "$side" sleep wake_still_open "$(sed -n 's/^open //p' "$dir/hold.out")"
    note "$side" sleep wake_clock_off_s "$(clock_off)"
}

for round in $(seq 1 "$ROUNDS"); do
    if [ $(( round % 2 )) = 1 ]; then order="old new"; else order="new old"; fi
    for side in $order; do
        [ "$side" = old ] && isx="$OLD" || isx="$NEW"
        measure "$side" "$isx" "$round" || say "round $round, $side: not every measurement ran (see $OUT)"
    done
done
# switch_to <old|new>: the VM under that build, the previous one stopped by the build that started it.
switch_to() {
    local want isx
    if [ "$1" = old ]; then want=vfkit; isx="$OLD"; else want="isx-vm"; isx="$NEW"; fi
    [ "$(launcher_name)" = "$want" ] && return 0
    local t0; t0=$(now_ms)
    case "$(launcher_name)" in
        vfkit) "$OLD" vm stop > /dev/null 2>&1; note old switch stop_ms $(( $(now_ms) - t0 )) ;;
        isx-vm) "$NEW" vm stop > /dev/null 2>&1; note new switch stop_ms $(( $(now_ms) - t0 )) ;;
    esac
    "$isx" vm start > /dev/null 2>&1
    local waited=0
    until api_up "$isx"; do
        [ "$waited" -lt 300 ] || { say "FAIL: the API did not answer within 300 s under $want"; return 1; }
        sleep 1; waited=$((waited + 1))
    done
}
if [ "$SLEEP" = 1 ]; then
    for side in old new; do
        [ "$side" = old ] && isx="$OLD" || isx="$NEW"
        switch_to "$side" && sleep_wake "$side" "$isx"
    done
fi
# The VM stays under the new build.
CURRENT="$NEW"
switch_to new

echo
echo "Mac: macOS $(sw_vers -productVersion), $(sysctl -n hw.model), $(sysctl -n hw.memsize | awk '{print $1/1073741824 " GiB"}')"
echo "old: $("$OLD" --version 2> /dev/null | head -1), new: $("$NEW" --version 2> /dev/null | head -1), rounds: $ROUNDS"
python3 - "$OUT/values-old" "$OUT/values-new" <<'EOF'
import statistics, sys
def load(path):
    out = {}
    for line in open(path):
        _, _, kv = line.strip().partition(" ")
        k, _, v = kv.partition("=")
        try: out.setdefault(k, []).append(float(v))
        except ValueError: pass
    return out
old, new = load(sys.argv[1]), load(sys.argv[2])
# Higher is better for these; lower for everything else.
higher = ("_mbs", "_accepted", "_still_open")
print("| metric | old (vfkit) median [min-max] n | new (isx-vm) median [min-max] n | new vs old | |")
print("|---|---|---|---|---|")
def cell(v):
    if not v: return "-"
    m = statistics.median(v)
    return f"{m:g} [{min(v):g}-{max(v):g}] {len(v)}"
# Counts of failures, recorded only or mostly when one happens: a missing value is none. relay_*
# counts are isx-vm's alone and are never compared.
def is_failure_count(k):
    return k in ("probe_timeouts", "net_failed", "wedged", "share_busy_wedged") or \
        (k.startswith("burst_") and k.endswith("_missed"))
for k in sorted(set(old) | set(new)):
    o, n = old.get(k, []), new.get(k, [])
    change, flag = "", ""
    if o and n and statistics.median(o):
        r = statistics.median(n) / statistics.median(o) - 1
        change = f"{r:+.0%}"
        worse = -r if k.endswith(higher) else r
        # Spread: a difference inside both ranges is not read as one.
        overlap = min(n) <= max(o) and min(o) <= max(n)
        if worse > 0.10 and not overlap: flag = "REGRESSION"
        elif worse > 0.10: flag = "worse, ranges overlap"
    elif n and is_failure_count(k) and statistics.median(n) > 0:
        # A failure count from none to some (a timeout, a wedge, a missed request, a failed
        # download): no ratio to take, and the most important difference there is. Only those: a
        # metric the old side did not record at all (every vfkit exec timed out, so no exec_ms)
        # is no regression of the new side's.
        flag = "REGRESSION" if not o or max(o) == 0 else "worse, ranges overlap"
    print(f"| {k} | {cell(o)} | {cell(n)} | {change} | {flag} |")
EOF
echo
echo "Raw values and logs: $OUT"
