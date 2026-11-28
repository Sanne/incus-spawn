#!/usr/bin/perl
# Reads a Debian repository's metadata for an apt-repo item (#2733, source-apt-repo.sh). The files are
# the publisher's, so each is matched against the shape apt reads, and anything else fails.
#   apt-repo.pl release  <Release>    env SUITE, INDEX: prints the sha256 the Release file gives INDEX,
#                                     once its Suite or Codename is SUITE
#   apt-repo.pl packages <Packages>   env PACKAGES (space separated), ARCH: prints
#                                     "<package>\t<epoch>\t<version>" for each stanza of one of those
#                                     packages and that arch, the version without its epoch
# A stanza of that package and arch whose version cannot be read fails the read rather than being
# skipped.
use strict;
use warnings;

my ($mode, $file) = @ARGV;
my $text = do { local $/; open(my $fh, '<', $file) or die "cannot read $file: $!\n"; <$fh> };

if ($mode eq 'release') {
    my $suite = $ENV{SUITE} // die "no SUITE\n";
    my $index = $ENV{INDEX} // die "no INDEX\n";
    my ($s) = $text =~ /^Suite: (\S+)$/m;
    my ($c) = $text =~ /^Codename: (\S+)$/m;
    die "the Release file is not of suite $suite\n" unless (defined $s && $s eq $suite) || (defined $c && $c eq $suite);
    my ($block) = $text =~ /^SHA256:\n((?: .*\n?)*)/m;
    die "the Release file has no SHA256 list\n" unless defined $block;
    my @sums = $block =~ /^ ([0-9a-f]{64}) +[0-9]+ \Q$index\E$/mg;
    die "the Release file gives $index " . scalar(@sums) . " sha256 sums, not one\n" unless @sums == 1;
    print "$sums[0]\n";
} elsif ($mode eq 'packages') {
    my %want = map { $_ => 1 } split ' ', ($ENV{PACKAGES} // die "no PACKAGES\n");
    my $arch = $ENV{ARCH} // die "no ARCH\n";
    for my $p (split /\n\n+/, $text) {
        my ($name) = $p =~ /^Package: (\S+)$/m;
        my ($a) = $p =~ /^Architecture: (\S+)$/m;
        next unless defined $name && defined $a && $want{$name} && $a eq $arch;
        my ($epoch, $ver) = $p =~ /^Version: (?:([0-9]+):)?([0-9A-Za-z.+~-]+)$/m;
        die "a $name package for $arch has no version this can read\n" unless defined $ver;
        print("$name\t", ($epoch // 0), "\t$ver\n");
    }
} else { die "usage: apt-repo.pl release|packages <file>\n" }
