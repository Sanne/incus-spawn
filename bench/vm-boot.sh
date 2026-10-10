#!/bin/bash
# bench/vm-boot.sh — Where a VM instance's start goes, from the copy to exec answering
#
# Copies a stopped VM (copy-on-write, as `isx branch` does), starts the copy with its serial
# console attached, and stamps every console line with the host's clock, so the phases before
# the guest kernel -- which the guest cannot see -- are timed from outside. The guest then
# reports the rest: systemd-analyze's kernel/initrd/userspace split and when incus-agent became
# active. Then the copy is deleted. Nothing is added to isx: this is plain `incus`, to measure
# the boot itself rather than isx around it (bench/trace-branch.sh does that).
#
# Columns: copy is the copy alone (any --set writes follow it, untimed). The rest count from the
# start request, on the host's clock unless noted:
#   firmware   until the firmware hands over to shim ("BdsDxe: loading" on the console)
#   grub       until GRUB boots its entry ("Booting `...'")
#   kernel     the guest kernel's start, read from the guest's clock (date - /proc/uptime)
#   agent      incus-agent active (guest clock: kernel start + its monotonic timestamp)
#   connected  until Incus's GET /state reports the agent connected (processes >= 0, or no
#              such field), polled every 50 ms: the gate isx's waitForReady waits for before it
#              probes with exec (isx also probes now and then while it is shut; this does not)
#   exec       the first exec that answers, probed every 50 ms once connected
# kernel and agent come from the guest's clock, which can be a few tenths of a second off the
# host's: agent may then show later than connected. A column the run cannot fill stays empty:
# firmware and grub on a console that prints neither, agent when the guest reports no
# activation.
#
# Requires: Linux, the `incus` client with access to the daemon, `script` (util-linux), and a
#           stopped VM whose guest has systemd (any isx VM template, e.g. one built from
#           tpl-minimal with --vm).
#
# Usage:
#   bench/vm-boot.sh tpl-isx-vm                                  # 3 runs
#   bench/vm-boot.sh tpl-isx-vm --runs=5 --set limits.cpu=8      # config on the copy only
#   bench/vm-boot.sh tpl-isx-vm --set security.secureboot=false
# A copy that does not answer exec within 300 s stops the run, and is deleted.
set -euo pipefail
# $EPOCHREALTIME and awk must agree on the decimal point
export LC_ALL=C

RUNS=3
TIMEOUT=300
SETS=()
BASE=""

usage() { echo "Usage: bench/vm-boot.sh <stopped-vm> [--runs=N] [--set key=value]..."; }
die() { echo "Error: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
    case "$1" in
        --runs=*) RUNS="${1#--runs=}" ;;
        --set) shift; SETS+=("${1:?--set needs key=value}") ;;
        --set=*) SETS+=("${1#--set=}") ;;
        --help|-h)
            usage
            echo ""
            echo "Boots N copy-on-write copies of the VM one after another and prints, for each,"
            echo "the time from the start request to the firmware handing over, GRUB booting, the"
            echo "guest kernel starting, incus-agent active, Incus reporting it connected and exec"
            echo "answering, plus the guest's systemd-analyze. --set applies instance config to"
            echo "each copy, never the source."
            exit 0
            ;;
        -*) die "unknown option: $1 (see --help)" ;;
        *) [ -z "$BASE" ] || die "one VM only"; BASE="$1" ;;
    esac
    shift
done

[ -n "$BASE" ] || { usage >&2; exit 2; }
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || die "--runs must be a positive integer"
[ "$(uname -s)" = "Linux" ] || die "Linux only"
command -v script >/dev/null || die "needs script(1) from util-linux"
read -r type status <<<"$(incus list "^${BASE}\$" -c ts -f csv | tr ',' ' ')"
[ "${type:-}" = VIRTUAL-MACHINE ] || die "$BASE is not a virtual machine"
[ "${status:-}" = STOPPED ] || die "$BASE must be stopped: a branch copies a stopped template"
# A plain copy keeps what isx's own copy clears, a branch's static address among it, and two
# instances on one address do not both start
! incus query "/1.0/instances/$BASE" | grep -q '"ipv4.address"' \
    || die "$BASE has a static address, as an isx branch does: copy its template instead"

LOG="$(mktemp)"
COPY="vm-boot-bench-$$"
console=""
teardown_copy() {
    [ -z "$console" ] || kill "$console" 2>/dev/null || true
    pkill -f "incus start --console $COPY\$" 2>/dev/null || true
    incus delete -f "$COPY" >/dev/null 2>&1 || true
}
trap 'teardown_copy; rm -f "$LOG"' EXIT

# First console line matching $1, as seconds since $2
console_at() {
    grep -a -m1 -E "$1" "$LOG" | awk -v t0="$2" '{ printf "%.2f", $1 - t0 }'
}

# Whether Incus reports $COPY running with its agent connected: processes >= 0, or no
# processes field at all, as isx's waitForReady reads it
connected() {
    local state
    state=$(incus query "/1.0/instances/$COPY/state" 2>/dev/null) || return 1
    [[ "$state" =~ \"status\":\ *\"Running\" ]] || return 1
    [[ "$state" =~ \"processes\":\ *(-?[0-9]+) ]] || return 0
    (( BASH_REMATCH[1] >= 0 ))
}

printf '%-4s %7s %9s %7s %7s %7s %9s %7s  %s\n' run copy firmware grub kernel agent connected exec \
    "guest systemd-analyze"
for run in $(seq 1 "$RUNS"); do
    t0=$EPOCHREALTIME
    incus copy "$BASE" "$COPY"
    tc=$EPOCHREALTIME
    for kv in "${SETS[@]}"; do incus config set "$COPY" "$kv"; done
    ts=$EPOCHREALTIME
    # The console needs a terminal; script(1) gives it one and the loop stamps each line
    # with the shell's own clock, so no process is started per line
    ( script -qfc "incus start --console $COPY" /dev/null 2>&1 \
        | while IFS= read -r line; do printf '%s %s\n' "$EPOCHREALTIME" "$line"; done > "$LOG" ) \
        </dev/null >/dev/null 2>&1 &
    console=$!
    deadline=$(( ${ts%.*} + TIMEOUT ))
    tconn=""
    until [ -n "$tconn" ] && timeout 10 incus exec "$COPY" -- true >/dev/null 2>&1; do
        if ! kill -0 "$console" 2>/dev/null; then
            cat "$LOG" >&2
            die "incus start $COPY ended; its output is above"
        fi
        if (( ${EPOCHREALTIME%.*} >= deadline )); then
            cat "$LOG" >&2
            die "$COPY did not answer exec within ${TIMEOUT}s; its console output is above"
        fi
        # Probe exec at once when the agent has just connected
        if [ -z "$tconn" ] && connected; then tconn=$EPOCHREALTIME; continue; fi
        sleep 0.05
    done
    te=$EPOCHREALTIME
    read -r kernel agent analyze <<<"$(incus exec "$COPY" -- sh -c '
        up=$(cut -d" " -f1 /proc/uptime); n=$(date +%s.%N)
        a=$(systemctl show -p ActiveEnterTimestampMonotonic --value incus-agent.service)
        timeout 120 systemctl is-system-running --wait >/dev/null 2>&1 || true
        s=$(systemd-analyze 2>/dev/null | head -1 | sed "s/Startup finished in //; s/ = .*//; s/ //g")
        awk -v n="$n" -v u="$up" -v a="$a" -v s="$s" "BEGIN { printf \"%.3f %s %s\", n - u, (a > 0 ? sprintf(\"%.3f\", n - u + a / 1e6) : \"-\"), s }"' || true)"
    awk -v run="$run" -v t0="$t0" -v tc="$tc" -v ts="$ts" -v tconn="$tconn" -v te="$te" -v k="$kernel" -v a="$agent" \
        -v fw="$(console_at 'BdsDxe: loading' "$ts")" -v grub="$(console_at "Booting \`" "$ts")" -v sa="$analyze" \
        'function since(t) { return t == "" || t == "-" ? "" : sprintf("%.2f", t - ts) }
         BEGIN { printf "%-4s %7.2f %9s %7s %7s %7s %9s %7.2f  %s\n", run, tc - t0, fw, grub, since(k), since(a), since(tconn), te - ts, sa }'
    teardown_copy
    wait 2>/dev/null || true
    console=""
done
