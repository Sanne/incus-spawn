#!/usr/bin/perl
# The entries of a directory listing or a feed page for a signed-file item (#2210): for each link
# (href="...") or, on a line with none, the whole line, that matches ^REGEX$ (one capture group: the
# version), prints "<version>\t<date>", the date being the first one after the entry on its line as
# "YYYY-MM-DD HH:MM" (empty when the line has none). Apache, nginx and Ubuntu listings all end in one.
use strict;
use warnings;
my $re = $ENV{REGEX} // die "no REGEX\n";
$re = qr/^(?:$re)$/;
my %mon = (Jan => 1, Feb => 2, Mar => 3, Apr => 4, May => 5, Jun => 6, Jul => 7, Aug => 8, Sep => 9, Oct => 10, Nov => 11, Dec => 12);
open(my $fh, '<', $ARGV[0]) or die "cannot read $ARGV[0]: $!\n";
while (my $line = <$fh>) {
    chomp $line;
    my @hits;
    while ($line =~ /href="([^"]*)"/g) { push @hits, [$1, substr($line, pos($line))] }
    # an entry's date is before the next link on its line
    $_->[1] =~ s/href=".*//s for @hits;
    @hits = ([$line, '']) unless @hits;
    for my $h (@hits) {
        my ($token, $rest) = @$h;
        next unless $token =~ $re && defined $1;
        my $version = $1;
        my $date = '';
        if ($rest =~ /(\d{4})-(\d{2})-(\d{2})(?: (\d{2}):(\d{2}))?/) { $date = sprintf('%s-%s-%s %s:%s', $1, $2, $3, $4 // '00', $5 // '00') }
        elsif ($rest =~ /(\d{2})-([A-Z][a-z]{2})-(\d{4})(?: (\d{2}):(\d{2}))?/ && $mon{$2}) { $date = sprintf('%s-%02d-%s %s:%s', $3, $mon{$2}, $1, $4 // '00', $5 // '00') }
        print "$version\t$date\n";
    }
}
