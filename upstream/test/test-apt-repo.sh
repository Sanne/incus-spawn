#!/usr/bin/env bash
# The apt-repo source type (#2733) on a fixture Debian repository with the publisher faked: curl serves
# the suite's Release, its signature (by throwaway keys) and the arch's Packages.gz by URL, gh answers
# the issue and PR queries from fixtures. Checks that validate holds the type's rules; that check
# passes a pin the repository confirms and fails a tampered epoch; that plan proposes the newest build
# (7.10 after 7.9, a rebuild after the build it rebuilds, another arch's and another package's never,
# a build without its with-package never),
# waits while it cools, and rewrites only the pin's line; that it fails closed on a Release its
# signature does not cover, one signed by a key of the file that is not pinned, one of another suite
# and a Packages.gz the signed Release does not hash; that a pinned version the repository dropped is
# a pin-mismatch issue, its with-package dropped included, not a failed run, and a Release caught
# mid-republish is downloaded again rather than failing the run, while a repository it cannot read is a failed read; and that
# verify-trees passes the engine's own bump and fails a tampered one.
# Needs bash, git, jq, yq, gpg, gpgv, gzip, curl faked, perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'gpgconf --homedir "$WORK/gnupg" --kill all 2>/dev/null; rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX/http" "$WORK/bin" "$WORK/art"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x
http() { printf '%s' "$1" | tr '/?&=:~' '______'; }
serve() { cp "$2" "$FIX/http/$(http "$1")"; }
hex() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
# stamp <seconds ago>: a build stamp, UTC
stamp() { date -u -d "@$(( $(date -u +%s) - $1 ))" +%Y%m%d%H%M; }
D=86400

# ---- throwaway signers -------------------------------------------------------------------------------
export GNUPGHOME="$WORK/gnupg"; mkdir -m 700 "$GNUPGHOME"
GPG=(gpg --batch --quiet --pinentry-mode loopback --passphrase "")
for who in signer other; do "${GPG[@]}" --quick-gen-key "$who@test.invalid" ed25519 sign never 2> /dev/null; done
FPR="$(gpg --batch --with-colons --fingerprint signer@test.invalid | awk -F: '$1 == "fpr" { print $10; exit }')"
mkdir -p "$WORK/keys"
gpg --batch --armor --export signer@test.invalid other@test.invalid > "$WORK/keys/test.asc"

# ---- the publisher -----------------------------------------------------------------------------------
REPO_URL=https://pkgs.example/tool/stable
S_OLD="$(stamp $((40 * D)))"; S9="$(stamp $((30 * D)))"; S10="$(stamp $((3 * D)))"; S10B="$(stamp $((2 * D)))"
# PKGS: "<package> <arch> <epoch:version>" lines
# tool-base is installed with tool at its exact version: every build but 7.10-$S10B has one.
S10C="$(stamp $((D + 3600)))"
# 7.9-$S9B has no tool-base either, and sorts last as text, so the loop over the listing ends on it
S9B="$(stamp $((20 * D)))"
PKGS="tool amd64 1:7.9-ubuntu24.04-$S_OLD
tool-base amd64 1:7.9-ubuntu24.04-$S_OLD
tool amd64 1:7.9-ubuntu24.04-$S9
tool-base amd64 1:7.9-ubuntu24.04-$S9
tool amd64 1:7.10-ubuntu24.04-$S10
tool-base amd64 1:7.10-ubuntu24.04-$S10
tool amd64 1:7.10-ubuntu24.04-$S10B
tool amd64 1:7.10-ubuntu24.04-$S10C
tool-base amd64 1:7.10-ubuntu24.04-$S10C
tool amd64 1:7.9-ubuntu24.04-$S9B
tool arm64 1:7.11-ubuntu24.04-$S10B
tool-extra amd64 1:7.12-ubuntu24.04-$S10B"
# publish [<signer>] [tamper-release|tamper-packages|other-suite]
publish() {
    local who="${1:-signer}" how="${2:-}" d="$WORK/art" p a v sum suite=noble
    rm -rf "$d"; mkdir -p "$d"
    while read -r p a v; do
        [[ -n "$p" ]] || continue
        printf 'Package: %s\nVersion: %s\nArchitecture: %s\nDepends: libc6\nFilename: pool/main/t/%s/%s_%s_%s.deb\nSHA256: %s\n\n' \
            "$p" "$v" "$a" "$p" "$p" "${v#*:}" "$a" "$(hex "$p $a $v")"
    done <<<"$PKGS" > "$d/Packages"
    gzip -nc "$d/Packages" > "$d/Packages.gz"
    sum="$(sha256sum "$d/Packages.gz" | cut -d' ' -f1)"
    [[ "$how" != tamper-packages ]] || sum="$(hex wrong)"
    [[ "$how" != other-suite ]] || suite=jammy
    cat > "$d/Release" <<X
Origin: pkgs.example
Label: tool-stable
Suite: $suite
Codename: $suite
Architectures: amd64 arm64
Components: main
MD5Sum:
 $(printf '%032d' 0)     1234 main/binary-amd64/Packages.gz
SHA256:
 $(hex arm)     1234 main/binary-arm64/Packages.gz
 $(hex plain)    13602 main/binary-amd64/Packages
 $sum     $(stat -c %s "$d/Packages.gz") main/binary-amd64/Packages.gz
X
    "${GPG[@]}" --local-user "$who@test.invalid" --armor --detach-sign -o "$d/Release.gpg" "$d/Release"
    [[ "$how" != tamper-release ]] || sed -i 's/^Label: .*/Label: evil/' "$d/Release"
    serve "$REPO_URL/dists/noble/Release" "$d/Release"
    serve "$REPO_URL/dists/noble/Release.gpg" "$d/Release.gpg"
    serve "$REPO_URL/dists/noble/main/binary-amd64/Packages.gz" "$d/Packages.gz"
}
publish

cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; -w) shift ;; -*) ;; *) url="$1" ;; esac; shift; done
f="$FIX/http/$(printf '%s' "$url" | tr '/?&=:~' '______')"
# a .first fixture is served once, before the file itself: a publisher caught mid-republish
[ -f "$f.first" ] && { cp "$f.first" "$out"; rm -f "$f.first"; exit 0; }
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
mkdir -p "$REPO/upstream" "$REPO/scripts"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
cp -r "$WORK/keys" "$REPO/upstream/keys"
cat > "$REPO/scripts/tool.env" <<Y
# The tool CI installs.
TOOL_VERSION=1:7.9-ubuntu24.04-$S_OLD
OTHER=1
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
      type: apt-repo
      repo: $REPO_URL/
      suite: noble
      component: main
      arch: amd64
      package: tool
      with: [tool-base]
      variable: TOOL_VERSION
    verify:
      type: signed-file
      key: upstream/keys/test.asc
      fingerprints: [$FPR]
    files: [scripts/tool.env]
    fields: [version, epoch]
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
bad_manifest '.items.tool.source.repo = "http://pkgs.example/tool/stable/"' "a plain http repository"
bad_manifest '.items.tool.source.repo = "https://pkgs.example/tool/stable"' "a repository not ending in /"
bad_manifest '.items.tool.source.suite = "../noble"' "a suite that is not a plain name"
bad_manifest '.items.tool.source.variable = "tool_version"' "a variable that is not an env name"
bad_manifest '.items.tool.source.variable = "OTHER"' "a variable whose value is not a version"
bad_manifest '.items.tool.source.variable = "MISSING"' "a variable the file does not set"
bad_manifest '.items.tool.fields = ["version"]' "fields that drift from the source"
bad_manifest '.items.tool.source.with = ["tool"]' "a with that names the package itself"
echo "validate: ok"

# ---- releases and check ------------------------------------------------------------------------------
# 7.10 after 7.9, each build in stamp order; arm64's 7.11 and tool-extra's 7.12 are never read, and
# 7.10-$S10B, which has no tool-base, is no candidate
want="7.9-ubuntu24.04-$S_OLD 7.9-ubuntu24.04-$S9 7.10-ubuntu24.04-$S10 7.10-ubuntu24.04-$S10C "
[ "$("$E" releases tool | tr '\n' ' ')" = "$want" ] || fail "releases: $("$E" releases tool | tr '\n' ' ')"
"$E" check tool > /dev/null || fail "check fails on a pin the repository confirms"
T="$WORK/tamper"; rm -rf "$T"; mkdir -p "$T/scripts"; cp "$REPO/scripts/tool.env" "$T/scripts/"
sed -i 's/^TOOL_VERSION=1:/TOOL_VERSION=2:/' "$T/scripts/tool.env"
"$E" check tool "$T" > /dev/null 2>&1 && fail "check passed an epoch the repository does not list"
echo "check: ok"

# ---- plan --------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
action() { awk -F'\t' '$1 == "tool" { print $2 }' "$1/plan.tsv"; }
version() { awk -F'\t' '$1 == "tool" { print $3 }' "$1/plan.tsv"; }
P="$(plan one)"
[ "$(action "$P")" = pr ] && [ "$(version "$P")" = "7.10-ubuntu24.04-$S10C" ] || fail "expected a PR for the 7.10 rebuild: $(cat "$P/plan.tsv")"
c="$(jq -r .commit "$P/tool.json")"
git -C "$REPO" show "$c:scripts/tool.env" > "$WORK/t.env"
printf '# The tool CI installs.\nTOOL_VERSION=1:7.10-ubuntu24.04-%s\nOTHER=1\n' "$S10C" | diff - "$WORK/t.env" > /dev/null \
    || fail "the bump did not rewrite exactly the pin's line: $(cat "$WORK/t.env")"
jq -r .body "$P/tool.json" | grep -q 'no package is downloaded' || fail "the PR does not say what proved the version"
# a build an hour old cools first
S11="$(stamp 3600)"; PKGS+=$'\n'"tool amd64 1:7.11-ubuntu24.04-$S11"$'\n'"tool-base amd64 1:7.11-ubuntu24.04-$S11"; publish
P="$(plan cooling)"
[ "$(action "$P")" = wait ] && [ "$(version "$P")" = "7.11-ubuntu24.04-$S11" ] || fail "expected to wait for 7.11: $(cat "$P/plan.tsv")"
PKGS="${PKGS%$'\n'*}"; PKGS="${PKGS%$'\n'*}"; publish
echo "plan: ok"

# ---- verify-trees ------------------------------------------------------------------------------------
verify() { "$E" verify-trees "$BASE" "$1" "$2" yes > "$WORK/verify.log" 2>&1; }
pc="$(jq -r .commit "$WORK/plan-one/tool.json")"
verify "$pc" "$APP_LOGIN" || { cat "$WORK/verify.log" >&2; fail "the engine's own bump failed verification"; }
bad="$(commit_from "$pc" bad sed -i 's/^TOOL_VERSION=1:/TOOL_VERSION=3:/' scripts/tool.env)"
verify "$bad" "$APP_LOGIN" && fail "an app PR with a tampered epoch passed"
verify "$pc" someone || { cat "$WORK/verify.log" >&2; fail "a person's bump with the repository's values failed"; }
verify "$bad" someone && fail "a person's bump with a tampered epoch passed"
echo "verify: ok"

# ---- fails closed ------------------------------------------------------------------------------------
fails() { # <expected message> <what>
    "$E" plan "$WORK/plan-bad" > "$WORK/bad.log" 2>&1 && fail "plan accepted $2"
    grep -q "$1" "$WORK/bad.log" || fail "$2 did not fail with '$1': $(cat "$WORK/bad.log")"
    publish
}
publish signer tamper-release; fails 'not a good signature' "a Release its signature does not cover"
publish other; fails 'not one of the pinned fingerprints' "a Release signed by an unpinned key"
publish signer other-suite; fails 'not of suite noble' "a Release of another suite"
publish signer tamper-packages; fails 'does not hash to the sha256' "a Packages.gz the signed Release does not hash"
# A Release caught mid-republish, its signature not yet for it, is downloaded again and then read
sed 's/^Label: .*/Label: half-published/' "$WORK/art/Release" > "$FIX/http/$(http "$REPO_URL/dists/noble/Release").first"
"$E" plan "$WORK/plan-republish" > "$WORK/republish.log" 2>&1 || fail "a republish caught once failed the run: $(cat "$WORK/republish.log")"
grep -q 'downloaded again' "$WORK/republish.log" || fail "the second download is not logged: $(cat "$WORK/republish.log")"
[ ! -f "$FIX/http/$(http "$REPO_URL/dists/noble/Release").first" ] || fail "the half-published Release was never served"
# The pinned version dropped: an issue, never a failed run, and no PR
saved="$PKGS"; PKGS="$(grep -v -- "-$S_OLD\$" <<<"$PKGS")"; publish
P="$(plan dropped)"
[ "$(action "$P")" = issue ] || fail "a dropped pin is not an issue: $(cat "$P/plan.tsv")"
ls "$P/issues/" | grep -q '^tool-pin-mismatch.json$' || fail "no pin-mismatch issue: $(ls "$P/issues")"
jq -r .body "$P/issues/tool-pin-mismatch.json" | grep -q "its newest build is 7.10-ubuntu24.04-$S10C" || fail "the pin-mismatch issue does not name the newest build"
PKGS="$saved"; publish
# The pinned build's tool-base dropped, tool still listed: the same issue
PKGS="$(grep -v -- "^tool-base amd64 1:7.9-ubuntu24.04-$S_OLD\$" <<<"$PKGS")"; publish
P="$(plan dropped-base)"
[ "$(action "$P")" = issue ] || fail "a pin whose tool-base was dropped is not an issue: $(cat "$P/plan.tsv")"
# No build left with its tool-base: check says so rather than naming an empty build (plan stops
# earlier, on a publisher that lists no release, as for every item)
PKGS="$(grep -v '^tool-base ' <<<"$PKGS")"; publish
rc=0; "$E" check tool > "$WORK/none.log" 2>&1 || rc=$?
[ "$rc" = 1 ] || fail "a repository with no complete build is not a mismatch (rc $rc): $(cat "$WORK/none.log")"
grep -q 'lists no build that has them all' "$WORK/none.log" || fail "check does not say no build is complete: $(cat "$WORK/none.log")"
PKGS="$saved"; publish
"$E" plan "$WORK/plan-ok" > /dev/null 2>&1 || fail "the restored repository is not accepted again"
# A repository that cannot be read while the pin is checked is a failed read (2), never a mismatch (#2261)
rm -f "$FIX/http/$(http "$REPO_URL/dists/noble/Release")"
rc=0; "$E" check tool > "$WORK/unread.log" 2>&1 || rc=$?
[ "$rc" = 2 ] || fail "an unreadable repository is not a failed read (rc $rc): $(cat "$WORK/unread.log")"
grep -q 'no longer lists' "$WORK/unread.log" && fail "an unreadable repository reads as a dropped pin"
publish
echo "fail-closed: ok"
echo "test-apt-repo: ok"
