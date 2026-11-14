#!/usr/bin/perl
# Reads or rewrites the pins of one file for a signed-file item (#2210, source-signed-file.sh).
# A pin is a literal text of the file with {version} and {sum} (or {sum:<label>}) where the values
# are, so the file holds each value where the manifest says and the rewrite lands on that text only.
#   pins.pl read  <file>   env TEXT: prints "count=<n>", then per occurrence its "version=<v>" and
#                          "sum:<label>=<hash>" lines in the order the text names them
#   pins.pl write <file>   env TEXT, COUNT, VERSION, SUM_<label>: rewrites every occurrence; it
#                          fails unless the text occurs exactly COUNT times
# env LABEL is what a bare {sum} means. The text is never a regular expression: only its two
# placeholders are, so a value from anywhere cannot change what a pin matches.
use strict;
use warnings;

my ($mode, $file) = @ARGV;
my $text = $ENV{TEXT} // die "no TEXT\n";
my $default = $ENV{LABEL} // '';
my (@parts, @slots);
my $re = '';
pos($text) = 0;
while ($text =~ /\G(.*?)\{(version|sum(?::([a-z0-9_]+))?)\}/gcs) {
    $re .= quotemeta($1);
    if ($2 eq 'version') { $re .= '([0-9]+(?:\.[0-9]+)*)'; push @slots, ['version', ''] }
    else {
        my $l = defined $3 ? $3 : $default;
        die "a {sum} in a pin with no label\n" if $l eq '';
        $re .= '([0-9a-f]{64,128})'; push @slots, ['sum', $l];
    }
}
$re .= quotemeta(substr($text, pos($text) // 0));
my $content = do { local $/; open(my $fh, '<', $file) or die "cannot read $file: $!\n"; <$fh> };
my $n = 0;
$n++ while $content =~ /$re/g;
if ($mode eq 'read') {
    print "count=$n\n";
    while ($content =~ /$re/g) {
        my @v = ($1, $2, $3, $4, $5, $6, $7, $8)[0 .. $#slots];
        for my $i (0 .. $#slots) {
            print $slots[$i][0] eq 'version' ? "version=$v[$i]\n" : "sum:$slots[$i][1]=$v[$i]\n";
        }
    }
} elsif ($mode eq 'write') {
    die "$file holds the pin $n times, not $ENV{COUNT}\n" unless $n == $ENV{COUNT};
    my $tpl = '';
    pos($text) = 0;
    while ($text =~ /\G(.*?)\{(version|sum(?::([a-z0-9_]+))?)\}/gcs) {
        $tpl .= $1;
        if ($2 eq 'version') { $tpl .= $ENV{VERSION} // die "no VERSION\n" }
        else { my $l = defined $3 ? $3 : $default; $tpl .= $ENV{"SUM_$l"} // die "no SUM_$l\n" }
    }
    $tpl .= substr($text, pos($text) // 0);
    $content =~ s/$re/$tpl/ge;
    open(my $out, '>', $file) or die "cannot write $file: $!\n";
    print $out $content;
    close $out;
} else { die "usage: pins.pl read|write <file>\n" }
