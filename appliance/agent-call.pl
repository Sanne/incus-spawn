#!/usr/bin/env perl
# One verb to the appliance's control agent (isx-agent), spoken as isx's VmAgentClient speaks
# it: send the verb, then keep the write side open and read until the agent hangs up.
#
# Usage:  agent-call.pl <agent-socket> <verb and its arguments> [<idle seconds>]
#
# Prints what the agent answered. Gives up when the agent sends nothing for <idle seconds>
# (8 unless given); a kill of this program hangs up, and it exits 0 whatever was answered,
# since the caller compares the answer.
#
# This is a program of its own because no nc does it everywhere. A client that shuts its
# write side down after the verb sends an EOF, and the tunnel does not carry a half-close
# (design/macos-and-vms.md "It gives up half-close"): the launcher's relay ends the
# connection there, before the agent's answer, and the guest's socat waits only its -t
# (0.5 s) after one, while forwarder-restart answers after more than a second. OpenBSD's nc
# (Ubuntu) leaves the write side open, nmap's ncat (Fedora) shuts it down unless told not to,
# and Apple's nc shuts it down always (#2031). Used by test-tunnel.sh and measure-tunnel.sh;
# test-agent-call.sh holds it to the above.
use strict; use Socket;
# A verb written to a connection the other side has already closed must not end this program
# with a signal: the read below sees the close.
$SIG{PIPE} = 'IGNORE';
my ($path, $verb, $idle) = @ARGV;
die "usage: agent-call.pl <agent-socket> <verb> [<idle seconds>]\n" unless defined $verb;
$idle = 8 unless defined $idle;
socket(my $s, PF_UNIX, SOCK_STREAM, 0) or exit 0;
connect($s, pack_sockaddr_un($path)) or exit 0;
syswrite($s, "$verb\n") or exit 0;
$| = 1;
my $rin = "";
vec($rin, fileno($s), 1) = 1;
while (1) {
    my $ready = select(my $r = $rin, undef, undef, $idle);
    # An interrupted wait is -1 and not the end of anything.
    next if $ready < 0 && $!{EINTR};
    last if $ready <= 0;
    sysread($s, my $chunk, 65536) or last;
    print $chunk;
}
