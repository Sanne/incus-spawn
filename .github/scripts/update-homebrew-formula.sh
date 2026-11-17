#!/usr/bin/env bash
# Rewrites a Homebrew formula of the tap (Sanne/homebrew-tap) for a release: release.yml's
# "Update Homebrew tap" step runs it on the channel's formula, and HomebrewFormulaUpdateTest on
# copies of both of the tap's formulas.
#
#   update-homebrew-formula.sh <formula> <version> <sha-isx> <sha-isx-proxy> <sha-isx-vm> \
#                              <sha-git-remote-isx> <sha-completions>
#
# Every asset of the formula moves to <version> with its sum. What a release added since a
# formula was written is added to it: the isx-proxy resource (the CLI and proxy split) and the
# isx-vm resource, the launcher that runs the VM in place of vfkit (#2031), whose dependency goes.
# The macOS minimum is rewritten from MACOS_MIN, ahead of the first depends_on.
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }
[ $# -eq 7 ] || die "usage: $0 <formula> <version> <sha-isx> <sha-isx-proxy> <sha-isx-vm> <sha-git-remote-isx> <sha-completions>"
FORMULA=$1 VERSION=$2 SHA_CLI=$3 SHA_PROXY=$4 SHA_VM=$5 SHA_GIT_REMOTE=$6 SHA_COMPLETIONS=$7
# Homebrew's name for pom.xml's macos.deployment.target; WorkflowRunnerLabelsTest keeps the two equal.
MACOS_MIN=sequoia
BASE="https://github.com/Sanne/incus-spawn/releases/download"

# No Intel macOS binary is released any more (#1693). Rewritten, a formula that still names one
# would point Intel Macs at a missing v#{version} binary beside the previous isx-proxy. The tap's
# formulas must declare depends_on arch: :arm64 instead, which brew reports to an Intel Mac before
# downloading.
if grep -q 'macos-x86_64\|on_intel\|depends_on arch: :x86_64' "$FORMULA" || ! grep -q '^  depends_on arch: :arm64' "$FORMULA"; then
  die "$FORMULA still serves Intel Macs: make it Apple Silicon only (depends_on arch: :arm64, no x86_64 asset) before this release updates it"
fi

# add_resource <name> <asset> <installed-as> <sha>: a resource and its install block, before
# git-remote-isx's, when the formula has none of that name.
add_resource() {
  grep -q "resource \"$1\"" "$FORMULA" && return 0
  awk -v name="$1" -v asset="$2" -v as="$3" -v ver="$VERSION" -v sha="$4" -v base="$BASE" '
    /resource "git-remote-isx"/ {
      print "  resource \"" name "\" do"
      print "    url \"" base "/v" ver "/" asset "\""
      print "    sha256 \"" sha "\""
      print "  end"
      print ""
    }
    /resource\("git-remote-isx"\)/ {
      print "    resource(\"" name "\").stage do"
      print "      bin.install \"" asset "\" => \"" as "\""
      print "    end"
      print ""
    }
    {print}
  ' "$FORMULA" > "$FORMULA.tmp" && mv "$FORMULA.tmp" "$FORMULA"
  grep -q "resource(\"$1\").stage" "$FORMULA" || die "$FORMULA has no git-remote-isx resource to put $1 before"
}
add_resource isx-proxy isx-proxy-macos-aarch64 isx-proxy "$SHA_PROXY"
add_resource isx-vm isx-vm-macos-aarch64 isx-vm "$SHA_VM"

# isx-vm runs the VM, so vfkit is no longer needed (#2031). brew upgrade leaves an installed vfkit
# alone, and brew autoremove takes it away once nothing else depends on it.
sed -i.bak '/^  depends_on "vfkit"$/d' "$FORMULA" && rm -f "$FORMULA.bak"

awk -v min="$MACOS_MIN" '
  /^  depends_on macos:/ {next}
  /^  depends_on / && !done {print "  depends_on macos: :" min; done=1}
  {print}
  END {exit !done}
' "$FORMULA" > "$FORMULA.tmp" || die "no depends_on line in $FORMULA to place the macOS minimum before"
mv "$FORMULA.tmp" "$FORMULA"

# asset <name> <sha>: the url and sha256 lines after the one naming <name>.
sed -i.bak \
  -e "s/version \"[^\"]*\"/version \"${VERSION}\"/" \
  -e "/incus-spawn-macos-aarch64\"$/,/sha256/{s|/v[0-9][^/]*/|/v${VERSION}/|; s/sha256 \"[^\"]*\"/sha256 \"${SHA_CLI}\"/;}" \
  -e "/isx-proxy-macos-aarch64\"$/,/sha256/{s|/v[0-9][^/]*/|/v${VERSION}/|; s/sha256 \"[^\"]*\"/sha256 \"${SHA_PROXY}\"/;}" \
  -e "/isx-vm-macos-aarch64\"$/,/sha256/{s|/v[0-9][^/]*/|/v${VERSION}/|; s/sha256 \"[^\"]*\"/sha256 \"${SHA_VM}\"/;}" \
  -e "/resource \"git-remote-isx\"/,/end/{s|url \"[^\"]*\"|url \"${BASE}/v${VERSION}/git-remote-isx\"|;}" \
  -e "/resource \"git-remote-isx\"/,/end/{s/sha256 \"[^\"]*\"/sha256 \"${SHA_GIT_REMOTE}\"/;}" \
  -e "/resource \"completions\"/,/end/{s|url \"[^\"]*\"|url \"${BASE}/v${VERSION}/completions.tar.gz\"|;}" \
  -e "/resource \"completions\"/,/end/{s/sha256 \"[^\"]*\"/sha256 \"${SHA_COMPLETIONS}\"/;}" \
  "$FORMULA" && rm -f "$FORMULA.bak"

# A line the ranges above did not match would keep the previous release's sum, which brew reports
# only as a checksum mismatch on every user's next upgrade.
for sum in "$SHA_CLI" "$SHA_PROXY" "$SHA_VM" "$SHA_GIT_REMOTE" "$SHA_COMPLETIONS"; do
  grep -q "sha256 \"$sum\"" "$FORMULA" || die "$FORMULA: no asset took the sum $sum"
done
