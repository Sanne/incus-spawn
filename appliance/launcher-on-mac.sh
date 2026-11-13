#!/bin/bash
set -euo pipefail

# Measure isx-vm, the launcher's prototype, against vfkit on this Mac, and post the
# record that the go/no-go on replacing vfkit is read from (#2031, design/macos-and-vms.md
# "The launcher's machine").
#
# Two halves, each run once with vfkit and once with isx-vm:
#
#   the machine alone   test-boot.sh boots the PR's appliance with the hypervisor and runs
#                       its tunnel checks, which hold the two forwarder checks: a connection
#                       the host closed leaves no forwarder child in the guest. A second boot
#                       runs measure-tunnel.sh before those checks: 24 runs of test-tunnel.sh,
#                       bursts of 70 and 120 requests, and the latency of a request with 0,
#                       200 and 400 connections held.
#   under isx           the VM isx already has on this Mac is restarted with the hypervisor,
#                       so what an upgrade would meet is what is booted. Then
#                       bench/trace-branch.sh three times: a branch to its prompt, the
#                       shell's end and the destroy. Then one instance shows the VM's network
#                       and its share of the home: it gets an answer over HTTPS, and it reads
#                       a file this script wrote in the home. With --sleep the Mac is also put
#                       to sleep for a minute with connections held.
#
# isx finds its hypervisor as `vfkit` on the PATH, so isx-vm is given to it as a copy of that
# name in a directory put first. isx-vm reads the arguments isx writes for vfkit. The VM is
# back under vfkit when this script ends, however it ends.
#
# Usage:  ./launcher-on-mac.sh <pr-number> [--print] [--skip-machine] [--skip-isx] [--sleep]
#
#   --print         print the record and post nothing
#   --skip-machine  leave out the half that boots the machine alone (about an hour of the whole)
#   --skip-isx      leave out the half under isx
#   --sleep         also put this Mac to sleep for a minute under each hypervisor. It needs
#                   `sudo -n pmset`, and a Mac that does not wake needs a person.
#
# Run it from a checkout of the PR's head (gh pr checkout <pr>): the scripts are the PR's. The
# appliance and isx-vm are the artifacts of the head's test-integration.yml run. ISX_VM=<path>
# takes an isx-vm built here instead ("$(launcher/build-isx-vm.sh)").
#
# Needs gh, vfkit, perl, curl and nc; under isx also isx (or ISX=...) with its VM set up,
# the template TEMPLATE (tpl-minimal) built with curl in it, python3 and the incus client.
# REACH_URL (https://github.com/) is what the instance asks over HTTPS. RUNS, BURSTS,
# BURST_ROUNDS, HELD and REQUESTS are measure-tunnel.sh's, and a record of other counts than
# its defaults says so.

REPO=Sanne/incus-spawn
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SELF="$SCRIPT_DIR/$(basename "$0")"

# test-boot.sh calls back here while a machine is up (WHILE_UP below), with the three sockets:
# measure it, then say what the hypervisor's own process holds after the load.
if [ -n "${WHILE_UP_OUT:-}" ]; then
    rc=0
    OUT="$WHILE_UP_OUT" "$SCRIPT_DIR/measure-tunnel.sh" "$@" || rc=$?
    {
        echo "measure_exit=$rc"
        echo "process_rss_kb=$(ps -o rss= -p "$HYPERVISOR_PID" | tr -d ' ')"
        echo "process_threads=$(ps -M -p "$HYPERVISOR_PID" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')"
        echo "process_fds=$(lsof -p "$HYPERVISOR_PID" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')"
    } >> "$WHILE_UP_OUT/values"
    # isx-vm prints each relay's counts on SIGUSR1, on its own output. vfkit would end on it.
    if [ "${WHILE_UP_COUNTS:-0}" = 1 ]; then
        kill -USR1 "$HYPERVISOR_PID" 2>/dev/null || true
        sleep 1
    fi
    exit 0
fi

# Whose PRs this Mac boots, as test-kernel-on-mac.sh: the appliance is the PR's own build.
TRUSTED="Sanne Sanne-ai-bot"
PR="${1:?Usage: $0 <pr-number> [--print] [--skip-machine] [--skip-isx] [--sleep]}"
[[ "$PR" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: $PR is not a PR number" >&2; exit 1; }
shift
POST=1; MACHINE=1; UNDER_ISX=1; SLEEP=0
for arg in "$@"; do
    case "$arg" in
        --print) POST=0 ;;
        --skip-machine) MACHINE=0 ;;
        --skip-isx) UNDER_ISX=0 ;;
        --sleep) SLEEP=1 ;;
        *) echo "ERROR: unknown option $arg" >&2; exit 1 ;;
    esac
done
ISX="${ISX:-isx}"
TEMPLATE="${TEMPLATE:-tpl-minimal}"
REACH_URL="${REACH_URL:-https://github.com/}"
# isx's own VM state directory (Environment.vmStateDir), which does not follow XDG_STATE_HOME.
STATE="$HOME/.local/state/incus-spawn"
# measure-tunnel.sh's counts, exported so both hypervisors get the same ones.
export RUNS="${RUNS:-24}" BURSTS="${BURSTS-70 120}" BURST_ROUNDS="${BURST_ROUNDS:-5}"
export HELD="${HELD-0 200 400}" REQUESTS="${REQUESTS:-200}"

die() { echo "ERROR: $*" >&2; exit 1; }
listed() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }
now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }

[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] \
    || die "this measures hypervisors on Virtualization.framework, so it runs only on an Apple silicon Mac"
for tool in gh vfkit perl curl nc codesign plutil; do
    command -v "$tool" > /dev/null || die "$tool is not on the PATH"
done
if [ "$UNDER_ISX" = 1 ]; then
    for tool in "$ISX" python3 incus; do
        command -v "$tool" > /dev/null || die "$tool is not on the PATH, and the half under isx needs it (--skip-isx leaves that half out)"
    done
fi

read -r head author owner <<< "$(gh pr view "$PR" -R "$REPO" --json headRefOid,author,headRepositoryOwner \
    --jq '"\(.headRefOid) \(.author.login) \(.headRepositoryOwner.login // "-")"')"
if ! listed "$author" "$TRUSTED" || ! listed "$owner" "$TRUSTED"; then
    die "PR #$PR is by $author from $owner's repository: the Mac boots only PRs by and from $TRUSTED"
fi
[ "$(git -C "$ROOT" rev-parse HEAD)" = "$head" ] \
    || die "$ROOT is not at the head of PR #$PR ($head); run this from 'gh pr checkout $PR'"
echo "==> PR #$PR at $head"

WORK="$(mktemp -d)"
STANDIN="$WORK/standin"
SWITCHED=0
PATH_BEFORE="$PATH"
# Where this run's logs stay, once there is something to keep. isx's own cache directory
# (Environment.cacheDir), which does not follow XDG_CACHE_HOME.
SAVED=""
# The directory of the home an instance reads through the VM's share, and that instance, while
# there is one (reach below).
PROBE=""
PROBE_INSTANCE=""
finish() {
    [ -z "$PROBE_INSTANCE" ] || "$ISX" destroy "$PROBE_INSTANCE" --skip-confirmation > /dev/null 2>&1 || true
    [ -z "$PROBE" ] || rm -rf "$PROBE"
    # The VM this Mac had is back under vfkit, whatever stopped the script.
    if [ "$SWITCHED" = 1 ]; then
        PATH="$PATH_BEFORE"
        "$ISX" vm stop > /dev/null 2>&1 || true
        "$ISX" vm start > /dev/null 2>&1 || echo "WARNING: could not start the VM under vfkit again; run: $ISX vm start" >&2
    fi
    # Logs only: the images are tens of MB each, and an unattended Mac would fill up with them.
    rm -rf "$WORK/build" "$WORK/launcher" "$STANDIN" "$WORK"/*/boot
    if [ -n "$SAVED" ] && rm -rf "$SAVED" && mkdir -p "$SAVED" && cp -R "$WORK"/. "$SAVED"/ 2> /dev/null; then
        echo "==> The logs of this run are in $SAVED"
    fi
    rm -rf "$WORK"
}
trap finish EXIT

run="$(gh run list -R "$REPO" --workflow test-integration.yml --commit "$head" --event pull_request \
    --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
[ -n "$run" ] || die "no test-integration.yml run of $head yet"
run_url="https://github.com/$REPO/actions/runs/$run"
if [ "$MACHINE" = 1 ]; then
    mkdir "$WORK/build"
    gh run download "$run" -R "$REPO" -n appliance-aarch64 -D "$WORK/build" \
        || die "run $run has no appliance-aarch64 artifact (yet)"
    for f in vmlinuz disk.img.gz; do
        [ -f "$WORK/build/$f" ] || die "the appliance-aarch64 artifact of run $run has no $f"
    done
fi
if [ -n "${ISX_VM:-}" ]; then
    [ -x "$ISX_VM" ] || die "ISX_VM=$ISX_VM is not a program that can be run"
    launcher_from="built on this Mac"
else
    mkdir "$WORK/launcher"
    gh run download "$run" -R "$REPO" -n isx-vm-macos-aarch64 -D "$WORK/launcher" \
        || die "run $run has no isx-vm-macos-aarch64 artifact (yet)"
    ISX_VM="$WORK/launcher/isx-vm"
    [ -f "$ISX_VM" ] || die "the isx-vm-macos-aarch64 artifact of run $run has no isx-vm"
    # An artifact does not keep the mode. The signature is in the file and is kept.
    chmod +x "$ISX_VM"
    launcher_from="isx-vm-macos-aarch64 of $run_url"
fi

# From here on a failure is a result: each step records its own, and the record is posted
# whatever failed, so nothing below ends the script by failing.
set +e
SAVED="$HOME/.cache/incus-spawn/launcher-mac/pr-$PR"
RESULTS=""
LOGS=""
result() { RESULTS+="- $1"$'\n'; }
# Runs one step with its output in a log; a failure is recorded and the rest still runs.
step() {
    local name="$1" log="$WORK/$2.log"; shift 2
    echo "==> $name"
    local rc=0
    "$@" > "$log" 2>&1 || rc=$?
    if [ "$rc" = 0 ]; then
        result "$name: pass"
    else
        result "$name: FAIL (exit $rc)"
        # The lines a failed check printed, never the guest's console that test-boot.sh quotes
        # after its summary. Cut short, so the record stays under GitHub's comment limit.
        local checks
        checks="$(perl -ne 'last if /^Last 40 log lines:$/; s/\e\[[0-9;]*m//g; print' "$log" \
            | grep -E '^[[:space:]]*(FAIL|FAILED|ERROR|WEDGED)[: ]|^Error: |checks failed\.$' | head -n 30 | cut -c 1-300 || true)"
        LOGS+=$'\n'"<details><summary>$name: what failed</summary>"$'\n\n```\n'"${checks:-$(tail -n 15 "$log" | cut -c 1-300)}"$'\n```\n</details>\n'
    fi
    return 0
}
# One value measure-tunnel.sh or this script wrote: value_of <vfkit|isx-vm> <key>.
value_of() { sed -n "s/^$2=//p" "$WORK/$1/values" 2> /dev/null | tail -n 1; }
note() { echo "$2=$3" >> "$WORK/$1/values"; }

# ── The signature (f) ────────────────────────────────────────────────────────
signature() {
    codesign --verify --strict "$ISX_VM" || return 1
    local signed
    signed="$(codesign -d --entitlements - --xml "$ISX_VM" 2> /dev/null | plutil -convert json -o - -- -)"
    echo "entitlements: $signed"
    [ "$signed" = '{"com.apple.security.virtualization":true}' ] || return 1
    codesign -dv "$ISX_VM" 2>&1 | grep -x 'Signature=adhoc'
}
step "isx-vm's signature is ad hoc, with com.apple.security.virtualization alone" signature signature

# ── The machine alone ────────────────────────────────────────────────────────
fresh_disk() {
    rm -rf "$1"; mkdir -p "$1"
    cp "$WORK/build/vmlinuz" "$1/"
    gunzip -c "$WORK/build/disk.img.gz" > "$1/disk.img"
}
machine() {
    local label="$1" program="$2" counts="$3" dir="$WORK/$1"
    fresh_disk "$dir/boot"
    # TUNNEL_KNOWN_LEAK is empty for both programs: this measures vfkit's leak, so the tunnel
    # checks must not let it pass as known, as a boot under vfkit otherwise may.
    step "$label: test-boot.sh, with both forwarder checks" "$label-boot" \
        env VFKIT="$program" SERIAL_LOG="$dir/serial.log" HYPERVISOR_LOG="$dir/hypervisor.log" \
        TUNNEL_KNOWN_LEAK= \
        "$SCRIPT_DIR/test-boot.sh" "$dir/boot"
    fresh_disk "$dir/boot"
    step "$label: the tunnel checks again, after the measurement" "$label-measure" \
        env VFKIT="$program" SERIAL_LOG="$dir/serial-measure.log" HYPERVISOR_LOG="$dir/hypervisor-measure.log" \
        TUNNEL_KNOWN_LEAK= \
        WHILE_UP="$SELF" WHILE_UP_OUT="$dir" WHILE_UP_COUNTS="$counts" \
        "$SCRIPT_DIR/test-boot.sh" "$dir/boot"
    rm -rf "${dir:?}/boot"
    # Noted after the measurement, which starts the values file anew.
    local first="forwarder children exit with their connections" second="their forwarder children exited with them"
    if grep -q "PASS: $first" "$WORK/$label-boot.log" && grep -q "PASS: $second" "$WORK/$label-boot.log"; then
        note "$label" forwarder_checks pass
    elif grep -qE "FAIL: ($first|$second)" "$WORK/$label-boot.log"; then
        note "$label" forwarder_checks FAIL
    fi
    # When the guest's random numbers were ready: its kernel stamps the console's lines.
    note "$label" crng_ready_s "$(perl -ne 'if (/\[\s*([0-9.]+)\] random: crng init done/) { print $1; exit }' "$dir/serial.log" 2> /dev/null || true)"
    # What measure-tunnel.sh printed, which test-boot.sh passed on between its own lines.
    LOGS+=$'\n'"<details><summary>$label: measure-tunnel.sh</summary>"$'\n\n```\n'"$(perl -ne '
        $on = 1 if /^forwarder count with nothing connected/; $on = 0 if /^=== Boot Summary/ || /^  [a-z-]+ exited /;
        print if $on' "$WORK/$label-measure.log" | cut -c 1-200 | head -n 80)"$'\n```\n</details>\n'
    if [ "$counts" = 1 ]; then
        LOGS+=$'\n'"<details><summary>$label: its relays after the measurement</summary>"$'\n\n```\n'"$(grep -E ' waiting [0-9]+ held [0-9]+ total ' "$dir/hypervisor-measure.log" 2> /dev/null | sed -E 's|^.*/([^/ ]+ waiting )|\1|' | cut -c 1-200 | head -n 6)"$'\n```\n</details>\n'
    fi
}
mkdir -p "$WORK/vfkit" "$WORK/isx-vm"
: > "$WORK/vfkit/values"; : > "$WORK/isx-vm/values"
load_before="$(sysctl -n vm.loadavg 2> /dev/null | tr -d '{}' | sed 's/^ *//; s/ *$//' || true)"
if [ "$MACHINE" = 1 ]; then
    machine vfkit vfkit 0
    machine isx-vm "$ISX_VM" 1
fi

# ── Under isx ────────────────────────────────────────────────────────────────
# vm_restart <vfkit|isx-vm> [unmeasured]: with the second word the start is not timed into the
# program's values. The restore at the end is one: it is vfkit's second boot, after isx-vm,
# and vfkit's row is its first.
# Where this boot's lines start in vfkit.log, which isx appends to across boots.
VM_LOG_FROM=1
vm_restart() {
    local label="$1" measured="${2:-measured}"
    "$ISX" vm stop || true
    VM_LOG_FROM=$(( $(wc -c < "$STATE/vfkit.log" 2> /dev/null || echo 0) + 1 ))
    local started; started=$(now_ms)
    "$ISX" vm start || return 1
    local deadline=$(( $(date +%s) + 120 ))
    until "$ISX" list -q > /dev/null 2>&1; do
        [ "$(date +%s)" -lt "$deadline" ] || { echo "the Incus API did not answer within 120 s of the start"; return 1; }
        sleep 1
    done
    [ "$measured" != measured ] || note "$label" isx_api_after_start_ms "$(( $(now_ms) - started ))"
    # Which program runs the VM: isx-vm says how many sockets it relays, on the output isx
    # keeps in vfkit.log, and vfkit never prints that line.
    # Counted, not `grep -q`: that one leaves at its first match, and tail's broken pipe would
    # then be the pipeline's status.
    local relays=0
    [ "$(tail -c "+$VM_LOG_FROM" "$STATE/vfkit.log" 2> /dev/null | grep -c '^relaying [0-9]* sockets to the guest')" = 0 ] || relays=1
    if [ "$label" = isx-vm ] && [ "$relays" = 0 ]; then echo "the VM does not run under isx-vm"; return 1; fi
    if [ "$label" = vfkit ] && [ "$relays" = 1 ]; then echo "the VM runs under isx-vm, not vfkit"; return 1; fi
    echo "the VM runs under $label, pid $(cat "$STATE/vm.pid")"
}
traces() {
    local label="$1" n line failed=0
    for n in 1 2 3; do
        line="$("$ROOT/bench/trace-branch.sh" --isx="$(command -v "$ISX")" --from="$TEMPLATE" 2>&1 \
            | tee -a "$WORK/$label-trace-full.log" | grep '^Prompt after ' || true)"
        echo "run $n: ${line:-no result}"
        [ -n "$line" ] || { failed=1; continue; }
        echo "$line" | sed -E 's/^Prompt after ([0-9]+) ms, the shell ended ([0-9]+) ms later, destroy took ([0-9]+) ms.*/\1 \2 \3/' >> "$WORK/$label/traces"
    done
    [ "$failed" = 0 ] || { tail -n 5 "$WORK/$label-trace-full.log"; return 1; }
}
# The middle one of the values in column $2 of a hypervisor's traces.
trace_median() { [ -s "$WORK/$1/traces" ] && cut -d' ' -f"$2" "$WORK/$1/traces" | sort -n | sed -n "$(( ($(wc -l < "$WORK/$1/traces") + 1) / 2 ))p"; }
# The guest's clock less the host's, to a second: the Date header of its Incus API.
clock_off() {
    local guest
    guest="$(curl -sI --max-time 10 --unix-socket "$1" http://localhost/1.0 | tr -d '\r' | sed -n 's/^[Dd]ate: //p')"
    python3 -c 'import email.utils, sys, time
print(round(email.utils.parsedate_to_datetime(sys.argv[1]).timestamp() - time.time()))' "$guest" 2> /dev/null || true
}
# (d): a minute of sleep with 20 event streams held. After it: how many the host still held,
# how soon the API answered, and how far the guest's clock is from the host's.
sleep_wake() {
    local label="$1" dir="$WORK/$1" sock="$STATE/vm.incus.sock" t gone asleep=""
    sudo -n pmset -g > /dev/null 2>&1 || { echo "sudo -n pmset is refused, and scheduling the wake needs it"; return 1; }
    rm -f "$dir/hold.in"
    mkfifo "$dir/hold.in"
    # The output first: measure-tunnel.sh left a hold.out of its own here, and the holder
    # waits in opening the pipe until descriptor 8 is opened below.
    perl "$SCRIPT_DIR/tunnel-hold.pl" "$sock" 20 > "$dir/hold.out" 2> /dev/null < "$dir/hold.in" &
    local holder=$!
    exec 8> "$dir/hold.in"
    for _ in $(seq 1 60); do grep -q '^held ' "$dir/hold.out" && break; sleep 1; done
    note "$label" sleep_clock_off_s "$(clock_off "$sock")"
    echo "before the sleep: $(cat "$dir/hold.out"), clock off by $(value_of "$label" sleep_clock_off_s)s"
    # Never asleep with no wake scheduled: that Mac would need a person.
    if ! sudo -n pmset relative wake 60; then
        exec 8>&-; wait "$holder" 2> /dev/null || true
        echo "the wake could not be scheduled, so the Mac was not put to sleep"; return 1
    fi
    pmset sleepnow
    # This script stops with the Mac, so a two-second wait that took far longer was the sleep.
    for _ in $(seq 1 60); do
        t=$(date +%s); sleep 2; gone=$(( $(date +%s) - t ))
        if [ "$gone" -ge 20 ]; then asleep=$gone; break; fi
    done
    if [ -z "$asleep" ]; then
        exec 8>&-; wait "$holder" 2> /dev/null || true
        echo "the Mac did not go to sleep within two minutes"; return 1
    fi
    # A time only when the API answered: a tunnel that stayed dead is not "120000 ms".
    local woke answered=0; woke=$(now_ms)
    while [ $(( $(now_ms) - woke )) -lt 120000 ]; do
        if "$ISX" list -q > /dev/null 2>&1; then answered=1; break; fi
        sleep 1
    done
    if [ "$answered" = 1 ]; then
        note "$label" wake_api "$(( $(now_ms) - woke )) ms"
    else
        note "$label" wake_api "no answer within 120 s"
        echo "FAIL: the Incus API did not answer within 120 s of the wake"
    fi
    # isx-vm looks for a wake every two seconds, and then sets the clock.
    sleep 10
    exec 8>&-; wait "$holder" 2> /dev/null || true
    note "$label" wake_asleep_s "$asleep"
    note "$label" wake_still_open "$(sed -n 's/^open //p' "$dir/hold.out")"
    note "$label" wake_clock_off_s "$(clock_off "$sock")"
    echo "asleep ${asleep}s; after it: $(tail -n 1 "$dir/hold.out"), clock off by $(value_of "$label" wake_clock_off_s)s"
    [ "$answered" = 1 ] && [ -n "$(value_of "$label" wake_clock_off_s)" ]
}
# What an upgrade could lose without a failed boot: the VM's network, and its share of the home
# (/host in the VM, which the appliance mounts with a failure ignored). Neither the API nor a
# branch needs them. One instance shows both: it gets an answer over HTTPS, and it reads a
# file this script wrote in the home, through a directory mounted as its inbox.
reach() {
    local label="$1" name="launcher-probe-$1-$$" token conf status="" got="" failed=0
    note "$label" isx_network "not shown: no instance to ask"
    note "$label" isx_share "not shown: no instance to ask"
    # Under the home, since that is all the VM is given, and open to the instance's users.
    mkdir -p "$HOME/.cache/incus-spawn/launcher-mac" || return 1
    PROBE="$(mktemp -d "$HOME/.cache/incus-spawn/launcher-mac/share.XXXXXX")" || return 1
    chmod 755 "$PROBE"
    token="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
    echo "$token" > "$PROBE/token"
    chmod 644 "$PROBE/token"
    # The incus client, as bench/trace-branch.sh has it: the appliance's socket as a remote of a
    # client configuration of its own.
    conf="$(mktemp -d)"
    PROBE_INSTANCE="$name"
    if ! "$ISX" branch --from "$TEMPLATE" "$name" --inbox "$PROBE" --format=plain < /dev/null; then
        echo "FAIL: no instance of $TEMPLATE with a directory of the home as its inbox"
        failed=1
    elif ! INCUS_CONF="$conf" incus remote add isx-appliance "unix://$STATE/vm.incus.sock" > /dev/null; then
        echo "FAIL: the incus client did not take the appliance's socket as a remote"
        failed=1
    else
        # The instance's network comes up after its start, so the request is tried for a while.
        for _ in 1 2 3 4 5 6; do
            status="$(INCUS_CONF="$conf" incus exec "isx-appliance:$name" -- \
                curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$REACH_URL" < /dev/null)"
            case "$status" in 2??|3??) break ;; esac
            sleep 5
        done
        case "$status" in
            2??|3??) note "$label" isx_network pass; echo "PASS: $REACH_URL answered $status" ;;
            *) note "$label" isx_network FAIL; failed=1
               echo "FAIL: an instance got no answer from $REACH_URL through the VM's network (status '$status')" ;;
        esac
        got="$(INCUS_CONF="$conf" incus exec "isx-appliance:$name" -- cat /home/agentuser/inbox/token < /dev/null)"
        if [ "$got" = "$token" ]; then
            note "$label" isx_share pass; echo "PASS: the instance read the file written in the home"
        else
            note "$label" isx_share FAIL; failed=1
            echo "FAIL: an instance did not read a file of the home through the VM's share (read '$(printf '%s' "$got" | head -c 80)')"
        fi
    fi
    "$ISX" destroy "$name" --skip-confirmation || true
    PROBE_INSTANCE=""
    rm -rf "$PROBE" "$conf"
    PROBE=""
    return "$failed"
}
under_isx() {
    local label="$1"
    step "$label under isx: the VM this Mac has boots, and the API answers" "$label-isx-start" vm_restart "$label"
    case "$RESULTS" in *"$label under isx: the VM this Mac has boots, and the API answers: FAIL"*) return 0 ;; esac
    step "$label under isx: bench/trace-branch.sh, three times" "$label-trace" traces "$label"
    step "$label under isx: an instance reaches the network, and reads a file of the home through the VM's share" "$label-reach" reach "$label"
    [ "$SLEEP" = 0 ] || step "$label under isx: a minute of sleep with 20 connections held" "$label-sleep" sleep_wake "$label"
    local pid; pid="$(cat "$STATE/vm.pid" 2> /dev/null || true)"
    [ -n "$pid" ] || return 0
    note "$label" isx_process_rss_kb "$(ps -o rss= -p "$pid" | tr -d ' ')"
    if [ "$label" = isx-vm ]; then
        local log_from=$(( $(wc -c < "$STATE/vfkit.log") + 1 ))
        kill -USR1 "$pid" 2> /dev/null || true
        sleep 1
        # The wake's lines of this boot alone: vfkit.log also holds every earlier run's.
        LOGS+=$'\n'"<details><summary>isx-vm under isx: its relays, and what it said of a wake</summary>"$'\n\n```\n'"$(tail -c "+$log_from" "$STATE/vfkit.log" | cut -c 1-200 | head -n 4)"$'\n'"$(tail -c "+$VM_LOG_FROM" "$STATE/vfkit.log" | grep -E '^woke after |clock' | tail -n 4 | cut -c 1-200)"$'\n```\n</details>\n'
    fi
}
if [ "$UNDER_ISX" = 1 ]; then
    under_isx vfkit
    # isx takes the first `vfkit` on the PATH, and recognises its VM by that word.
    mkdir "$STANDIN"
    cp "$ISX_VM" "$STANDIN/vfkit"
    SWITCHED=1
    PATH="$STANDIN:$PATH_BEFORE"
    under_isx isx-vm
    PATH="$PATH_BEFORE"
    step "vfkit again: the VM this Mac has boots, and the API answers" restore vm_restart vfkit unmeasured
    # Only a restart that passed: after a failed one, finish tries again and says so if it cannot.
    if echo "$RESULTS" | grep -q '^- vfkit again.*: pass$'; then SWITCHED=0; fi
fi

# ── The record ───────────────────────────────────────────────────────────────
# The record is public: what it takes from the logs must not name this machine, its user, its
# home or an address. Longest first, so a home inside the work directory's path is not split.
scrub() {
    local host; host="$(uname -n)"
    W="$WORK" WP="$(cd "$WORK" && pwd -P)" H="$HOME" N="$host" S="${host%%.*}" U="$(id -un)" perl -pe '
        for my $k (qw(WP W H)) { my $v = $ENV{$k}; s/\Q$v\E(?![\w.-])/$k eq "H" ? "~" : "<work>"/ge if length $v > 1 }
        for my $k (qw(N S)) { my $v = $ENV{$k}; s/\b\Q$v\E\b/<host>/gi if length $v }
        my $u = $ENV{U}; s/\b\Q$u\E\b/<user>/g if length $u;
        s/\b\d{1,3}(?:\.\d{1,3}){3}\b/<address>/g;'
}
cell() { local v; v="$(value_of "$1" "$2")"; if [ -n "$v" ]; then echo "$v${3:-}"; else echo "not measured"; fi; }
row() { echo "| $1 | $(cell vfkit "$2" "${3:-}") | $(cell isx-vm "$2" "${3:-}") |"; }
table="| | vfkit | isx-vm |
|---|---|---|
$(row "both forwarder checks of test-boot.sh (a)" forwarder_checks)
$(row "runs of test-tunnel.sh that failed, of $RUNS (b)" runs_failed)
$(row "runs in which a forwarder child outlived its connection (a)" close_failed)
$(row "forwarder back to its listener after a run, median" settle_after_run_median_ms " ms")
$(for n in $BURSTS; do
    row "bursts of $n: requests sent" "burst_${n}_requests"
    row "bursts of $n: not answered (b)" "burst_${n}_missed"
    row "bursts of $n: empty replies (b)" "burst_${n}_empty"
    row "bursts of $n: forwarder back to its listener, median" "burst_${n}_settle_median_ms" " ms"
done)
$(row "the tunnel stopped answering (b)" wedged)
$(for h in $HELD; do
    row "$h held: accepted" "held_${h}_accepted"
    row "$h held: still open at the end" "held_${h}_open_at_end"
    row "$h held: request on a new connection, median (c)" "held_${h}_new_median_ms" " ms"
    row "$h held: request on a new connection, 95th percentile" "held_${h}_new_p95_ms" " ms"
    row "$h held: request on a kept connection, median (c)" "held_${h}_kept_median_ms" " ms"
    row "$h held: request on a kept connection, 95th percentile" "held_${h}_kept_p95_ms" " ms"
done)
$(row "the hypervisor's own process after the load: memory" process_rss_kb " kB")
$(row "the hypervisor's own process after the load: threads" process_threads)
$(row "the hypervisor's own process after the load: open files" process_fds)
$(row "the guest's random numbers ready" crng_ready_s " s into the boot")
$(row "the measurement took" seconds " s")
$(row "under isx: the API answers after isx vm start" isx_api_after_start_ms " ms")"
for label in vfkit isx-vm; do
    note "$label" trace_prompt_ms "$(trace_median "$label" 1 || true)"
    note "$label" trace_exit_ms "$(trace_median "$label" 2 || true)"
    note "$label" trace_destroy_ms "$(trace_median "$label" 3 || true)"
done
table+="
$(row "under isx: a branch to its prompt, median of three (c)" trace_prompt_ms " ms")
$(row "under isx: the shell ended after the prompt, median of three (e)" trace_exit_ms " ms")
$(row "under isx: the destroy, median of three" trace_destroy_ms " ms")
$(row "under isx: an instance gets an answer over HTTPS, through the VM's network" isx_network)
$(row "under isx: an instance reads a file of the home, through the VM's share" isx_share)
$(row "under isx: the hypervisor's own process at the end: memory" isx_process_rss_kb " kB")"
if [ "$SLEEP" = 1 ]; then
    table+="
$(row "sleep (d): asleep for" wake_asleep_s " s")
$(row "sleep (d): of 20 held connections, still open on the host after the wake" wake_still_open)
$(row "sleep (d): the API answers after the wake" wake_api)
$(row "sleep (d): the guest's clock less the host's, before the sleep" sleep_clock_off_s " s")
$(row "sleep (d): the guest's clock less the host's, 10 s after the wake" wake_clock_off_s " s")"
fi
# With no bursts or no held counts asked for, their rows are an empty line, which would end
# the table there.
table="$(printf '%s\n' "$table" | sed '/^$/d')"

# The design's rule: go when (a) holds and (b) and (c) are no worse than vfkit. "Worse" for a
# latency is this script's reading, since two medians never agree to the digit: above vfkit's
# by more than a tenth and by more than 0.2 ms (a branch: by more than a tenth).
# And a latency with connections held counts only when isx-vm held no fewer of them than vfkit.
reading="$(awk -F= -v bursts="$BURSTS" -v held="$HELD" '
    FILENAME == ARGV[1] { a[$1] = $2; next } { b[$1] = $2 }
    function line(what, ok, detail) { printf "- %s: %s (%s)\n", what, ok ? "holds" : "does NOT hold", detail; if (!ok) bad = 1 }
    # A value that is not a number was not measured ("-": no request answered), and holds nothing.
    function number(v) { return v ~ /^[0-9]+(\.[0-9]+)?$/ }
    function no_worse(key) { return number(b[key]) && (!number(a[key]) || b[key] + 0 <= a[key] + 0) }
    function latency(key, slack) { return number(b[key]) && (!number(a[key]) || b[key] + 0 <= a[key] * 1.1 || b[key] + 0 <= a[key] + slack) }
    function no_fewer(key) { return number(b[key]) && (!number(a[key]) || b[key] + 0 >= a[key] + 0) }
    END {
        if (b["seconds"] == "" && b["wedged"] == "") { print "- the machine alone was not measured with isx-vm"; print "reading=incomplete"; exit }
        line("(a) a connection the host closed is closed in the guest", b["forwarder_checks"] == "pass" && b["close_failed"] == "0",
             "test-boot.sh: " (b["forwarder_checks"] == "pass" ? "both checks pass" : "a check fails") ", runs with a child left: " b["close_failed"])
        ok = no_worse("runs_failed") && b["wedged"] != "1"; detail = "failed runs " a["runs_failed"] " and " b["runs_failed"]
        n = split(bursts, sizes, " ")
        for (i = 1; i <= n; i++) {
            ok = ok && no_worse("burst_" sizes[i] "_missed") && no_worse("burst_" sizes[i] "_empty")
            detail = detail ", bursts of " sizes[i] " not answered " a["burst_" sizes[i] "_missed"] " and " b["burst_" sizes[i] "_missed"]
        }
        line("(b) failures no worse than vfkit, vfkit first", ok, detail (b["wedged"] == "1" ? ", isx-vm stopped answering" : ""))
        ok = 1; detail = ""
        n = split(held, counts, " ")
        for (i = 1; i <= n; i++) {
            k = "held_" counts[i]
            # A latency taken with fewer connections held than vfkit had is not the same load.
            ok = ok && no_fewer(k "_accepted") && no_fewer(k "_open_at_end")
            ok = ok && latency(k "_new_median_ms", 0.2) && latency(k "_kept_median_ms", 0.2)
            detail = detail (i > 1 ? ", " : "") counts[i] " held (still open at the end " a[k "_open_at_end"] " and " b[k "_open_at_end"] "): " a[k "_new_median_ms"] " and " b[k "_new_median_ms"] " ms new, " a[k "_kept_median_ms"] " and " b[k "_kept_median_ms"] " ms kept"
        }
        if (b["trace_prompt_ms"] != "" && a["trace_prompt_ms"] != "") {
            ok = ok && latency("trace_prompt_ms", 0)
            detail = detail ", a branch to its prompt " a["trace_prompt_ms"] " and " b["trace_prompt_ms"] " ms"
        } else detail = detail ", a branch to its prompt not measured with both"
        line("(c) latency no worse than vfkit, vfkit first", ok, detail)
        print "reading=" (bad ? "no-go" : "go")
    }' "$WORK/vfkit/values" "$WORK/isx-vm/values")"
verdict="$(echo "$reading" | sed -n 's/^reading=//p')"
[ "$MACHINE" = 1 ] || verdict="incomplete"
# A step that fails with vfkit is the baseline's result, and the rule already compares it. One
# that fails with isx-vm, or the VM not coming back under vfkit, is said beside a go.
if [ "$verdict" = go ] && echo "$RESULTS" | grep -qE '^- (isx-vm|vfkit again).*: FAIL'; then
    verdict="go by the rule, with failed steps of isx-vm below"
fi

counts="RUNS=$RUNS BURSTS=\"$BURSTS\" BURST_ROUNDS=$BURST_ROUNDS HELD=\"$HELD\" REQUESTS=$REQUESTS"
[ "$counts" = 'RUNS=24 BURSTS="70 120" BURST_ROUNDS=5 HELD="0 200 400" REQUESTS=200' ] \
    && counts="$counts (the defaults)" || counts="$counts (NOT the defaults)"
body="### Launcher measurement on the Mac
Reading: $verdict
Commit: $head
Appliance: $([ "$MACHINE" = 1 ] && echo "appliance-aarch64 of $run_url" || echo "not booted alone (--skip-machine)")
isx-vm: $launcher_from, sha256 $(shasum -a 256 "$ISX_VM" | cut -d' ' -f1)
Machine: macOS $(sw_vers -productVersion), $(sysctl -n hw.model), $(vfkit --version 2> /dev/null | head -1 || echo "vfkit unknown"), $("$ISX" --version 2> /dev/null | head -1 || echo "isx unknown")
Load before: ${load_before:-unknown} (1, 5 and 15 minutes)
Counts: $counts
Scripts: $(git -C "$SCRIPT_DIR" rev-parse HEAD) (this checkout)
Logs: kept on the Mac in $(printf '%s' "$SAVED" | scrub)

The rule (#2031): go when (a) holds and (b) and (c) are no worse than vfkit.
A latency counts as worse when it is above vfkit's by more than a tenth and by more than 0.2 ms.
One with connections held also needs isx-vm to have held no fewer of them than vfkit.
$(echo "$reading" | grep -v '^reading=')

$table

$RESULTS$(printf '%s' "$LOGS" | scrub)"

if [ "$POST" = 1 ]; then
    url="$(gh pr comment "$PR" -R "$REPO" --body "$body")"
    echo "==> Posted the record: $url"
else
    echo "==> The record, not posted (--print):"
    echo
    echo "$body"
fi
case "$verdict" in go) exit 0 ;; *) exit 1 ;; esac
