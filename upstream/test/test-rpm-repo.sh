#!/usr/bin/env bash
# The rpm-repo source type (#2653) on a fixture repository with the publisher faked: curl serves each
# arch's repomd.xml, its signature (by throwaway keys) and its primary metadata by URL, gh answers the
# issue and PR queries from fixtures. Checks that validate holds the type's rules; that check passes
# a pin the repository confirms and fails a tampered one; that plan proposes the newest version every
# arch lists (a pre-release before its release, preview.10 after preview.9, the highest release of a
# version), waits while it cools, and rewrites the description, URLs and sums; that it fails closed on
# a repomd.xml its signature does not cover, one signed by a key of the file that is not pinned, a
# primary metadata the signed repomd.xml does not hash and a package off its location; that a pinned
# version the repository dropped is a pin-mismatch issue, not a failed run, while a repository it cannot
# read is a failed read; and that verify-trees passes the engine's own bump and fails a tampered sum.
# Needs bash, git, jq, yq, gpg, gpgv, gzip, curl faked, perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'gpgconf --homedir "$WORK/gnupg" --kill all 2>/dev/null; rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX/http" "$WORK/bin" "$WORK/art"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x
NOW="$(date -u +%s)"
D=86400
http() { printf '%s' "$1" | tr '/?&=:~' '______'; }
serve() { cp "$2" "$FIX/http/$(http "$1")"; }
hex() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }

# ---- throwaway signers -------------------------------------------------------------------------------
export GNUPGHOME="$WORK/gnupg"; mkdir -m 700 "$GNUPGHOME"
GPG=(gpg --batch --quiet --pinentry-mode loopback --passphrase "")
for who in signer other; do "${GPG[@]}" --quick-gen-key "$who@test.invalid" ed25519 sign never 2> /dev/null; done
fpr() { gpg --batch --with-colons --fingerprint "$1@test.invalid" | awk -F: '$1 == "fpr" { print $10; exit }'; }
FPR="$(fpr signer)"
mkdir -p "$WORK/keys"
gpg --batch --armor --export signer@test.invalid other@test.invalid > "$WORK/keys/test.asc"

# ---- the publisher -----------------------------------------------------------------------------------
REPO_URL=https://pkgs.example/tool/rpm/preview
# pkg_sum <arch> <ver> <rel> [<salt>]: the sha256 the metadata gives that rpm
pkg_sum() { hex "$1 $2 $3 ${4:-}"; }
# PKGS_<arch>: "<ver> <rel> <age> [<location>] [<salt>]" lines
PKGS_x86_64="0.1.0~preview.8 1 $((40 * D))
0.1.0~preview.9 1 $((30 * D))
0.1.0~preview.10 1 $((3 * D))
0.1.0~preview.10 2 $((2 * D))
0.1.1~preview.1 1 3600"
PKGS_aarch64="0.1.0~preview.8 1 $((40 * D))
0.1.0~preview.9 1 $((30 * D))
0.1.0~preview.10 1 $((3 * D))
0.1.0~preview.10 2 $((2 * D))"
# publish <arch> [<signer>] [tamper-repomd|tamper-primary]
publish() {
    local a="$1" who="${2:-signer}" how="${3:-}" var="PKGS_$1" d="$WORK/art/$1" ver rel age loc salt sum
    rm -rf "$d"; mkdir -p "$d"
    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo '<metadata xmlns="http://linux.duke.edu/metadata/common" packages="9">'
        # another package of the arch, and the tool for another arch, which are never read
        printf '<package type="rpm">\n  <name>other</name>\n  <arch>%s</arch>\n  <version epoch="0" ver="9.9.9" rel="1"/>\n</package>\n' "$a"
        while read -r ver rel age loc salt; do
            [[ -n "$ver" ]] || continue
            [[ -n "$loc" && "$loc" != - ]] || loc="tool-$ver-$rel.$a.rpm"
            sum="$(pkg_sum "$a" "$ver" "$rel" "$salt")"
            cat <<X
<package type="rpm">
  <name>tool</name>
  <arch>$a</arch>
  <version epoch="0" ver="$ver" rel="$rel"/>
  <checksum type="sha256" pkgid="YES">$sum</checksum>
  <summary>A tool.</summary>
  <time file="$((NOW - age))" build="$((NOW - age))"/>
  <location href="$loc"/>
</package>
X
        done <<<"${!var}"
        echo '</metadata>'
    } > "$d/primary.xml"
    gzip -nc "$d/primary.xml" > "$d/primary.xml.gz"
    sum="$(sha256sum "$d/primary.xml.gz" | cut -d' ' -f1)"
    [[ "$how" != tamper-primary ]] || sum="$(hex wrong)"
    cat > "$d/repomd.xml" <<X
<?xml version="1.0" encoding="UTF-8"?>
<repomd xmlns="http://linux.duke.edu/metadata/repo">
  <revision>1</revision>
  <data type="primary">
    <checksum type="sha256">$sum</checksum>
    <location href="repodata/$sum-primary.xml.gz"/>
  </data>
  <data type="filelists">
    <checksum type="sha256">$(hex filelists)</checksum>
    <location href="repodata/$(hex filelists)-filelists.xml.gz"/>
  </data>
</repomd>
X
    "${GPG[@]}" --local-user "$who@test.invalid" --armor --detach-sign -o "$d/repomd.xml.asc" "$d/repomd.xml"
    [[ "$how" != tamper-repomd ]] || sed -i 's/<revision>1</<revision>2</' "$d/repomd.xml"
    serve "$REPO_URL/$a/repodata/repomd.xml" "$d/repomd.xml"
    serve "$REPO_URL/$a/repodata/repomd.xml.asc" "$d/repomd.xml.asc"
    serve "$REPO_URL/$a/repodata/$sum-primary.xml.gz" "$d/primary.xml.gz"
}
publish_all() { publish x86_64; publish aarch64; }
publish_all

cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; -w) shift ;; -*) ;; *) url="$1" ;; esac; shift; done
f="$FIX/http/$(printf '%s' "$url" | tr '/?&=:~' '______')"
[ -f "$f" ] || { echo "curl: (22) no fixture for $url" >&2; printf '404 '; exit 22; }
cp "$f" "$out"
FAKE
cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
case "$1 $2" in
    "issue list") echo '[]' ;;
    "pr list") echo '[]' ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/curl" "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH" FIX

# ---- the fixture repository --------------------------------------------------------------------------
REPO="$WORK/repo"
mkdir -p "$REPO/upstream" "$REPO/tools"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
cp -r "$WORK/keys" "$REPO/upstream/keys"
cat > "$REPO/tools/tool.yaml" <<Y
name: tool
description: Tool 0.1.0-preview.8 – a tool
downloads:
  - url: $REPO_URL/x86_64/tool-0.1.0~preview.8-1.x86_64.rpm
    sha256: $(pkg_sum x86_64 0.1.0~preview.8 1)
    destination_file: /tmp/tool.rpm
    arch: x86_64
  - url: $REPO_URL/aarch64/tool-0.1.0~preview.8-1.aarch64.rpm
    sha256: $(pkg_sum aarch64 0.1.0~preview.8 1)
    destination_file: /tmp/tool.rpm
    arch: aarch64
run:
  - dnf install -y /tmp/tool.rpm
Y
cat > "$REPO/upstream/manifest.yaml" <<Y
repo: Sanne/incus-spawn
app:
  login: isx-upstream[bot]
items:
  tool:
    class: review
    within: any
    cooling: 24h
    judgement: build
    source:
      type: rpm-repo
      repo: $REPO_URL/{arch}/
      package: tool
      title: Tool
      arches: [x86_64, aarch64]
    verify:
      type: signed-file
      key: upstream/keys/test.asc
      fingerprints: [$FPR]
    files: [tools/tool.yaml]
    fields: [version, sha256.x86_64, sha256.aarch64]
Y
cd "$REPO" && git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm fixture
E="$REPO/upstream/engine/upstream.sh"
BASE="$(git rev-parse HEAD)"
commit_from() {
    local start="$1" msg="$2"; shift 2
    local wt="$WORK/wt"; rm -rf "$wt"; git -C "$REPO" worktree prune; git -C "$REPO" worktree add -q --detach "$wt" "$start"
    ( cd "$wt" && "$@" && git add -A && git -c user.name=a -c user.email="$APP_EMAIL" commit -qm "$msg" && git rev-parse HEAD )
}

# ---- validate ----------------------------------------------------------------------------------------
"$E" validate > /dev/null || fail "validate refuses the fixture manifest"
bad_manifest() {
    local m="$WORK/bad.yaml"; yq "$1" "$REPO/upstream/manifest.yaml" > "$m"
    MANIFEST="$m" "$E" validate > /dev/null 2>&1 && fail "validate accepted a manifest with $2"
    return 0
}
bad_manifest '.items.tool.verify.fingerprints = ["0000000000000000000000000000000000000000"]' "a fingerprint that is no key of the file"
bad_manifest '.items.tool.verify.type = "publisher-sums"' "a verify.type the source does not provide"
bad_manifest '.items.tool.source.repo = "http://pkgs.example/tool/{arch}/"' "a plain http repository"
bad_manifest '.items.tool.source.repo = "https://pkgs.example/tool/x86_64/"' "a repository with no {arch}"
bad_manifest '.items.tool.source.repo = "https://pkgs.example/{arch}/{version}/"' "an unknown placeholder"
bad_manifest '.items.tool.source.arches = []' "no arches"
bad_manifest '.items.tool.source.arches = ["x86_64", "riscv64"]' "an arch the file does not pin"
bad_manifest '.items.tool.source.title = "Other"' "a title the description does not start with"
bad_manifest '.items.tool.fields = ["version"]' "fields that drift from the source"
echo "validate: ok"

# ---- releases and check ------------------------------------------------------------------------------
# preview.10 after preview.9; 0.1.1~preview.1 only on x86_64, so no candidate
[ "$("$E" releases tool | tr '\n' ' ')" = "0.1.0-preview.8 0.1.0-preview.9 0.1.0-preview.10 " ] || fail "releases: $("$E" releases tool | tr '\n' ' ')"
"$E" check tool > /dev/null || fail "check fails on a pin the repository confirms"
T="$WORK/tamper"; rm -rf "$T"; mkdir -p "$T/tools"; cp "$REPO/tools/tool.yaml" "$T/tools/"
sed -i "0,/sha256: .*/s//sha256: $(hex wrong)/" "$T/tools/tool.yaml"
"$E" check tool "$T" > /dev/null 2>&1 && fail "check passed a tampered sum"
cp "$REPO/tools/tool.yaml" "$T/tools/"; sed -i 's/^description: Tool 0.1.0-preview.8/description: Tool 0.1.0-preview.9/' "$T/tools/tool.yaml"
"$E" check tool "$T" > /dev/null 2>&1 && fail "check passed a description at odds with the URLs"
echo "check: ok"

# ---- plan --------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
action() { awk -F'\t' '$1 == "tool" { print $2 }' "$1/plan.tsv"; }
version() { awk -F'\t' '$1 == "tool" { print $3 }' "$1/plan.tsv"; }
P="$(plan one)"
[ "$(action "$P")" = pr ] && [ "$(version "$P")" = 0.1.0-preview.10 ] || fail "expected a PR for 0.1.0-preview.10: $(cat "$P/plan.tsv")"
c="$(jq -r .commit "$P/tool.json")"
git -C "$REPO" show "$c:tools/tool.yaml" > "$WORK/t.yaml"
grep -qx 'description: Tool 0.1.0-preview.10 – a tool' "$WORK/t.yaml" || fail "the description was not rewritten"
for a in x86_64 aarch64; do
    # the highest release of the version
    grep -qx "  - url: $REPO_URL/$a/tool-0.1.0~preview.10-2.$a.rpm" "$WORK/t.yaml" || fail "the $a URL was not rewritten to release 2"
    grep -qx "    sha256: $(pkg_sum "$a" 0.1.0~preview.10 2)" "$WORK/t.yaml" || fail "the $a sum was not rewritten"
done
grep -qx '  - dnf install -y /tmp/tool.rpm' "$WORK/t.yaml" || fail "the bump changed a line it does not own"
jq -r .body "$P/tool.json" | grep -q 'no rpm is downloaded' || fail "the PR does not say what proved the sums"
# the release of 0.1.0 is newer than every preview of it, and cools first
PKGS_x86_64+=$'\n'"0.1.0 1 3600"; PKGS_aarch64+=$'\n'"0.1.0 1 7200"
publish_all
P="$(plan cooling)"
[ "$(action "$P")" = wait ] && [ "$(version "$P")" = 0.1.0 ] || fail "expected to wait for 0.1.0: $(cat "$P/plan.tsv")"
echo "plan: ok"

# ---- verify-trees ------------------------------------------------------------------------------------
PKGS_x86_64="${PKGS_x86_64%$'\n'*}"; PKGS_aarch64="${PKGS_aarch64%$'\n'*}"
publish_all
verify() { "$E" verify-trees "$BASE" "$1" "$2" yes > "$WORK/verify.log" 2>&1; }
pc="$(jq -r .commit "$WORK/plan-one/tool.json")"
verify "$pc" "$APP_LOGIN" || { cat "$WORK/verify.log" >&2; fail "the engine's own bump failed verification"; }
bad="$(commit_from "$pc" bad sed -i "0,/sha256: .*/s//sha256: $(hex wrong)/" tools/tool.yaml)"
verify "$bad" "$APP_LOGIN" && fail "an app PR with a tampered sum passed"
verify "$pc" someone || { cat "$WORK/verify.log" >&2; fail "a person's bump with the repository's values failed"; }
verify "$bad" someone && fail "a person's bump with a tampered sum passed"
echo "verify: ok"

# ---- fails closed ------------------------------------------------------------------------------------
fails() { # <how> <expected message> <what>
    "$E" plan "$WORK/plan-bad" > "$WORK/bad.log" 2>&1 && fail "plan accepted $3"
    grep -q "$2" "$WORK/bad.log" || fail "$3 did not fail with '$2': $(cat "$WORK/bad.log")"
    publish_all
}
publish aarch64 signer tamper-repomd; fails x 'not a good signature' "a repomd.xml its signature does not cover"
publish aarch64 other; fails x 'not one of the pinned fingerprints' "a repomd.xml signed by an unpinned key"
publish x86_64 signer tamper-primary; fails x 'does not hash to the sha256' "a primary metadata the signed repomd.xml does not hash"
PKGS_aarch64+=$'\n'"0.1.0~preview.11 1 $((2 * D)) ../evil.rpm"; publish aarch64
fails x 'is not at tool-' "a package off its location"
PKGS_aarch64="${PKGS_aarch64%$'\n'*}"; publish_all
# The pinned version dropped: an issue, never a failed run, and no PR
saved="$PKGS_x86_64"; PKGS_x86_64="$(grep -v '^0.1.0~preview.8 ' <<<"$PKGS_x86_64")"; publish x86_64
P="$(plan dropped)"
[ "$(action "$P")" = issue ] || fail "a dropped pin is not an issue: $(cat "$P/plan.tsv")"
ls "$P/issues/" | grep -q '^tool-pin-mismatch.json$' || fail "no pin-mismatch issue: $(ls "$P/issues")"
PKGS_x86_64="$saved"; publish_all
"$E" plan "$WORK/plan-ok" > /dev/null 2>&1 || fail "the restored repository is not accepted again"
# A repository that cannot be read while the pin is checked is a failed read (2), never a mismatch (#2261)
rm -f "$FIX/http/$(http "$REPO_URL/aarch64/repodata/repomd.xml")"
rc=0; "$E" check tool > "$WORK/unread.log" 2>&1 || rc=$?
[ "$rc" = 2 ] || fail "an unreadable repository is not a failed read (rc $rc): $(cat "$WORK/unread.log")"
grep -q 'no longer lists' "$WORK/unread.log" && fail "an unreadable repository reads as a dropped pin"
publish_all
echo "fail-closed: ok"
echo "test-rpm-repo: ok"
