#!/usr/bin/env perl
# Hold connections open through the appliance's tunnel: <n> event streams of the Incus API on
# <socket>, as isx's own long-lived connections are. A connection that sends nothing would be
# closed by incusd after 3 s, and a listener that leaves the daemon's keepalive pings
# unanswered is dropped, so each ping is answered.
#
# Usage:  tunnel-hold.pl <incus-socket> <n>  < <something that ends when the hold should>
#
# Prints "held <k>" once every connection was tried (k of them were accepted), holds them
# until its stdin ends, and then prints "open <k>": how many the other side had not closed.
# Used by measure-tunnel.sh and launcher-on-mac.sh (#2031).
use strict; use Socket; use MIME::Base64;
# A pong written to a connection the other side has just closed must not end this program
# before it prints "open <k>": the failed write is left to the next read, which sees the close.
$SIG{PIPE} = 'IGNORE';
my ($path, $n) = @ARGV;
# The handshake's nonce: 16 bytes, made here as the protocol asks. Nothing checks the answer.
my $nonce = encode_base64(pack("C16", map { int rand 256 } 1 .. 16), "");
my $ask = "GET /1.0/events?type=lifecycle HTTP/1.1\r\nHost: incus\r\nConnection: Upgrade\r\n"
        . "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: $nonce\r\n\r\n";
my (%sock, %buf, $held);
for (1 .. $n) {
    socket(my $s, PF_UNIX, SOCK_STREAM, 0) or last;
    connect($s, pack_sockaddr_un($path)) or last;
    syswrite($s, $ask) or last;
    my ($rin, $head) = ("", "");
    vec($rin, fileno($s), 1) = 1;
    while ($head !~ /\r\n\r\n/ && select(my $r = $rin, undef, undef, 10) > 0) {
        sysread($s, $head, 4096, length $head) or last;
    }
    next unless $head =~ m{^HTTP/1\.1 101 };
    $held++;
    $sock{fileno $s} = $s;
    $buf{fileno $s} = $head =~ /\r\n\r\n(.+)\z/s ? $1 : "";
}
$| = 1;
print "held ", $held // 0, "\n";
# Until stdin ends: answer each ping (0x89) with a masked pong, and drop anything else.
while (1) {
    my $rin = "";
    vec($rin, 0, 1) = 1;
    vec($rin, $_, 1) = 1 for keys %sock;
    # An error is -1, which is true, and leaves stdin's bit set in $r: not the end of stdin.
    next if select(my $r = $rin, undef, undef, undef) <= 0;
    last if vec($r, 0, 1);
    for my $fd (keys %sock) {
        next unless vec($r, $fd, 1);
        my $s = $sock{$fd};
        if (!sysread($s, $buf{$fd}, 65536, length $buf{$fd})) { close $s; delete $sock{$fd}; next }
        while (length $buf{$fd} >= 2) {
            my ($op, $len) = unpack "CC", $buf{$fd};
            my $skip = 2;
            if ($len == 126) { last if length $buf{$fd} < 4; $len = unpack "n", substr($buf{$fd}, 2, 2); $skip = 4 }
            elsif ($len == 127) { last if length $buf{$fd} < 10; $len = unpack "Q>", substr($buf{$fd}, 2, 8); $skip = 10 }
            last if length $buf{$fd} < $skip + $len;
            my $body = substr($buf{$fd}, $skip, $len);
            substr($buf{$fd}, 0, $skip + $len) = "";
            syswrite($s, pack("CC", 0x8A, 0x80 | length $body) . "\0\0\0\0" . $body) if ($op & 0x0F) == 9 && $skip == 2;
        }
    }
}
print "open ", scalar(keys %sock), "\n";
