#!/usr/bin/env bash
set -uo pipefail

# Hold agent-call.pl, the agent client of test-tunnel.sh and measure-tunnel.sh, to what the
# tunnel needs of a client: it never shuts its write side down before the agent has answered.
#
# A Mac found the reason (#2031). The scripts used to pipe the verb into nc, and Apple's nc
# shuts its write side down at the end of its input. vfkit's relay does not carry that to
# the guest, so the checks passed under vfkit; the launcher's relay ends the connection
# there, as designed, and every agent call under isx-vm came back empty. CI did not see it:
# Ubuntu's nc leaves the write side open.
#
# This needs no appliance: the agent here is a stand-in on a Unix socket that answers late,
# as forwarder-restart does, and writes down whether its client's EOF came first. Runs on
# Linux and macOS (bash 3.2), with perl alone.
#
# Usage:  ./test-agent-call.sh
#
# Prints one PASS/FAIL line per check and exits non-zero if any check failed.

HERE="$(cd "$(dirname "$0")" && pwd)"
command -v perl >/dev/null || { echo "test-agent-call.sh needs perl" >&2; exit 2; }

# Short: a Unix socket's path holds about 100 bytes on macOS.
WORK="$(mktemp -d /tmp/isx-agent-call.XXXXXX)"
SOCK="$WORK/agent.sock"
LOG="$WORK/agent.log"
AGENT_PID=""
cleanup() {
    # Waited for, so the shell does not report the killed job with its whole command.
    [ -n "$AGENT_PID" ] && kill "$AGENT_PID" 2>/dev/null && wait "$AGENT_PID" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

PASS=0
FAIL=0
pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
expect() { if eval "$1"; then pass "$2"; else fail "$2${3:+ ($3)}"; fi; }

# The stand-in agent: one connection at a time, one verb each, one log line per event.
#   late      answers "ok" after 1.5 s and hangs up, as a slow verb does
#   twice     answers in two pieces, 0.3 s apart
#   say ...   answers with its arguments
#   silent    never answers, and waits for its client to hang up
# Whatever the verb, an EOF from the client before the answer is logged as "eof-first".
# The log exists before the agent does: the socket appears before the agent opens it.
: > "$LOG"
perl -e '
    use strict; use Socket; use Time::HiRes qw(time sleep);
    $SIG{PIPE} = "IGNORE";
    my ($path, $log) = @ARGV;
    socket(my $l, PF_UNIX, SOCK_STREAM, 0) or die "socket: $!";
    bind($l, pack_sockaddr_un($path)) or die "bind: $!";
    listen($l, 16) or die "listen: $!";
    open(my $out, ">>", $log) or die "log: $!";
    select((select($out), $| = 1)[0]);
    # True when the client sent an EOF within $wait seconds.
    sub eof_within {
        my ($c, $wait) = @_;
        my $rin = ""; vec($rin, fileno($c), 1) = 1;
        return 0 unless select(my $r = $rin, undef, undef, $wait) > 0;
        return !sysread($c, my $rest, 4096);
    }
    while (accept(my $c, $l)) {
        my $verb = "";
        while ($verb !~ /\n/) { sysread($c, $verb, 4096, length $verb) or last }
        chomp $verb;
        print $out "verb $verb\n";
        my $started = time;
        if ($verb eq "silent") {
            my $hung_up = eof_within($c, 30);
            printf $out "%s after %d ms\n", $hung_up ? "hung-up" : "still-open", (time - $started) * 1000;
        } else {
            my $eof = eof_within($c, $verb eq "late" ? 1.5 : 0.2);
            print $out "eof-first\n" if $eof;
            if ($verb eq "twice") { syswrite($c, "one\n"); sleep 0.3; syswrite($c, "two\n") }
            elsif ($verb =~ /^say (.*)/) { syswrite($c, "$1\n") }
            else { syswrite($c, "ok\n") }
            print $out "answered\n";
        }
        close $c;
    }
' "$SOCK" "$LOG" &
AGENT_PID=$!
for _ in $(seq 1 50); do [ -S "$SOCK" ] && break; sleep 0.1; done
[ -S "$SOCK" ] || { echo "the stand-in agent did not start" >&2; exit 2; }

# Every call starts at a mark, so a check reads only what the agent logged for that call.
# The mark is set outside call: $(call ...) runs in a subshell.
MARK=0
mark() { MARK=$(( $(wc -l < "$LOG") )); }
call() { perl "$HERE/agent-call.pl" "$SOCK" "$@" 2>/dev/null; }
last_call() { tail -n +$((MARK + 1)) "$LOG"; }
logged() { last_call | grep -q "$1"; }
# Wait up to $2 seconds for the last call's log to hold $1.
wait_logged() {
    local i
    for i in $(seq 1 $(( $2 * 10 ))); do logged "$1" && return 0; sleep 0.1; done
    return 1
}

echo "-- The agent's client --"
mark; answer=$(call late)
expect '[ "$answer" = "ok" ]' "a late answer reaches the client" "got '$answer'"
expect 'logged "^answered$" && ! logged "^eof-first$"' \
    "the client sent no EOF before the agent answered" "$(last_call | tr '\n' ' ')"

mark; answer=$(call twice | tr '\n' ' ')
expect '[ "$answer" = "one two " ]' "an answer in two pieces arrives whole" "got '$answer'"

mark; answer=$(call "say btrfs-usage cow sync")
expect '[ "$answer" = "btrfs-usage cow sync" ]' "a verb's arguments go as one line" "got '$answer'"

# The idle limit: an agent that never answers does not hold its caller.
mark
started=$(date +%s)
answer=$(call silent 1)
took=$(( $(date +%s) - started ))
expect '[ -z "$answer" ] && [ "$took" -le 5 ]' "a silent agent is given up on after the idle limit" "got '$answer' after ${took}s"
expect 'wait_logged "^hung-up" 5' "and the agent sees the hang-up" "$(last_call | tr '\n' ' ')"

# test-tunnel.sh hangs up in the middle of forwarder-restart by killing its client.
# Started as test-tunnel.sh starts it, not through call: $! has to be the client itself.
mark
perl "$HERE/agent-call.pl" "$SOCK" silent 30 >/dev/null 2>&1 &
caller=$!
expect 'wait_logged "^verb silent$" 5' "a client in the background reaches the agent"
kill "$caller" 2>/dev/null
wait "$caller" 2>/dev/null
expect 'wait_logged "^hung-up" 5' "killing the client hangs up" "$(last_call | tr '\n' ' ')"

started=$(date +%s)
answer=$(perl "$HERE/agent-call.pl" "$WORK/no-such.sock" ping 2>/dev/null)
took=$(( $(date +%s) - started ))
expect '[ -z "$answer" ] && [ "$took" -le 2 ]' "a socket nothing listens on answers nothing, at once" "got '$answer' after ${took}s"

# Not a check: what this host's own nc does with the same call, for the record. It is why
# the client is not nc.
if command -v nc >/dev/null; then
    mark
    ( printf 'late\n' | nc -w 8 -U "$SOCK" > /dev/null 2>&1 ) &
    nc_call=$!
    wait_logged "^answered$" 10
    kill "$nc_call" 2>/dev/null
    wait "$nc_call" 2>/dev/null
    if logged "^eof-first$"; then
        echo "  note: this host's nc shuts its write side down after the verb; the tunnel would end the call there"
    elif logged "^answered$"; then
        echo "  note: this host's nc keeps its write side open until the agent hangs up"
    else
        echo "  note: this host's nc did not make the call"
    fi
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "All $PASS agent client checks passed."
else
    echo "$FAIL of $((PASS + FAIL)) agent client checks failed."
    exit 1
fi
