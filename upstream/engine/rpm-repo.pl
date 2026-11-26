#!/usr/bin/perl
# Reads a repository's metadata for an rpm-repo item (#2653, source-rpm-repo.sh). The files are the
# publisher's, so each is matched against the shape createrepo writes, and anything else fails.
#   rpm-repo.pl repomd  <repomd.xml>            prints "<sha256> <href>" of the primary metadata,
#                                               whose href must be repodata/<hex>-primary.xml.gz
#   rpm-repo.pl primary <primary.xml>           env PACKAGE, ARCH: prints "<ver>\t<rel>\t<sha256>\t<build time>"
#                                               for each package of that name and arch
# A package of that name and arch whose version, checksum, time or location cannot be read, or whose
# epoch is not 0 (it would order differently), or whose location is not <name>-<ver>-<rel>.<arch>.rpm,
# fails the read rather than being skipped.
use strict;
use warnings;

my ($mode, $file) = @ARGV;
my $xml = do { local $/; open(my $fh, '<', $file) or die "cannot read $file: $!\n"; <$fh> };

if ($mode eq 'repomd') {
    my @data = $xml =~ m{<data type="primary">(.*?)</data>}gs;
    die "repomd.xml names " . scalar(@data) . " primary metadata files, not one\n" unless @data == 1;
    my ($sum) = $data[0] =~ m{<checksum type="sha256">([0-9a-f]{64})</checksum>};
    my ($href) = $data[0] =~ m{<location href="(repodata/[0-9a-f]{64}-primary\.xml\.gz)"/>};
    die "repomd.xml gives the primary metadata no sha256 checksum\n" unless defined $sum;
    die "repomd.xml's primary location is not repodata/<hex>-primary.xml.gz\n" unless defined $href;
    print "$sum $href\n";
} elsif ($mode eq 'primary') {
    my $want = $ENV{PACKAGE} // die "no PACKAGE\n";
    my $arch = $ENV{ARCH} // die "no ARCH\n";
    for my $p ($xml =~ m{<package type="rpm">(.*?)</package>}gs) {
        my ($name) = $p =~ m{<name>([^<]*)</name>};
        my ($a) = $p =~ m{<arch>([^<]*)</arch>};
        next unless defined $name && defined $a && $name eq $want && $a eq $arch;
        my ($epoch, $ver, $rel) = $p =~ m{<version epoch="([0-9]+)" ver="([0-9A-Za-z.~+]+)" rel="([0-9]+)"/>};
        die "a $want package for $arch has no version this can read\n" unless defined $rel;
        die "$want $ver-$rel for $arch has epoch $epoch, not 0\n" unless $epoch eq '0';
        my ($sum) = $p =~ m{<checksum type="sha256" pkgid="YES">([0-9a-f]{64})</checksum>};
        die "$want $ver-$rel for $arch has no sha256\n" unless defined $sum;
        my ($time) = $p =~ m{<time file="[0-9]+" build="([0-9]+)"/>};
        die "$want $ver-$rel for $arch has no build time\n" unless defined $time;
        my ($loc) = $p =~ m{<location href="([^"]*)"/>};
        die "$want $ver-$rel for $arch is not at $want-$ver-$rel.$arch.rpm\n" unless defined $loc && $loc eq "$want-$ver-$rel.$arch.rpm";
        print "$ver\t$rel\t$sum\t$time\n";
    }
} else { die "usage: rpm-repo.pl repomd|primary <file>\n" }
