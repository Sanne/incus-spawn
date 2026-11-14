#!/usr/bin/env bash
# The signed-file source type (#2210) on a fixture repository with every publisher faked: curl serves
# directory listings, files, signatures and signed sums files by URL (signed with throwaway keys), gh
# answers the issue and PR queries from fixtures. Checks that validate holds the type's rules (a
# fingerprint that is not a primary key of the committed file, a pin the file does not name, a label
# with no hash pin, an unknown proof or algo); that check passes a pin the publisher confirms and fails
# a tampered one; that plan proposes the newest cooled release of the series (an rc never counts, a
# new series is an issue) and rewrites every occurrence of every pin, two hash algorithms and a
# signed-sums item whose image is never downloaded included; that it fails closed on a tampered file,
# a signature by a key of the file that is not pinned, one by a key not in it and a tampered sums file;
# and that verify-trees passes the engine's regeneration of each of two items sharing one file, fails
# a tampered sum, and passes a person's unrelated edit of the shared file without asking any publisher.
# Needs bash, git, jq, yq, gpg, gpgv, curl faked, perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'gpgconf --homedir "$WORK/gnupg" --kill all 2>/dev/null; rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX/http" "$WORK/bin" "$WORK/art"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x
NOW="$(date -u +%s)"
when() { date -u -d "@$((NOW - $1))" '+%Y-%m-%d %H:%M'; }
http() { printf '%s' "$1" | tr '/?&=:' '_____'; }
serve() { cp "$2" "$FIX/http/$(http "$1")"; }
unserve() { rm -f "$FIX/http/$(http "$1")"; }
sum256() { sha256sum "$1" | cut -d' ' -f1; }
sum512() { sha512sum "$1" | cut -d' ' -f1; }

# ---- throwaway signers -------------------------------------------------------------------------------
export GNUPGHOME="$WORK/gnupg"; mkdir -m 700 "$GNUPGHOME"
GPG=(gpg --batch --quiet --pinentry-mode loopback --passphrase "")
for who in signer other rogue; do "${GPG[@]}" --quick-gen-key "$who@test.invalid" ed25519 sign never 2> /dev/null; done
fpr() { gpg --batch --with-colons --fingerprint "$1@test.invalid" | awk -F: '$1 == "fpr" { print $10; exit }'; }
FPR="$(fpr signer)"
mkdir -p "$WORK/keys"
# the key file holds the signer and another key (as Apache's KEYS holds dozens); rogue is not in it
gpg --batch --armor --export signer@test.invalid other@test.invalid > "$WORK/keys/test.asc"
sign() { # <who> <file> <armor|binary> -> the detached signature's path
    local out="$2.sig.$1"; rm -f "$out"
    "${GPG[@]}" --local-user "$1@test.invalid" $([ "$3" = armor ] && echo --armor) --detach-sign -o "$out" "$2"
    echo "$out"
}

# ---- the publishers ----------------------------------------------------------------------------------
PKG=https://dl.example/pkg
IMG=https://img.example
# mk_pkg <version> <age> [signer]: pkg-<v>.tar.xz and pkg-<v>-wrapper.zip, each signed
mk_pkg() {
    local v="$1" who="${3:-signer}" n f
    for n in "pkg-$v.tar.xz" "pkg-$v-wrapper.zip"; do
        f="$WORK/art/$n"; printf 'content of %s\n' "$n" > "$f"
        serve "$PKG/$n" "$f"; serve "$PKG/$n.asc" "$(sign "$who" "$f" armor)"
    done
}
# mk_img <version> [signer]: the signed sums file of an image, which is never itself served
mk_img() {
    local v="$1" who="${2:-signer}" f="$WORK/art/SHA256SUMS-$1"
    printf '%s *disk-%s.img\n%s  other-%s.img\n' "$(printf 'image %s' "$v" | sha256sum | cut -d' ' -f1)" "$v" "$(printf x | sha256sum | cut -d' ' -f1)" "$v" > "$f"
    serve "$IMG/v$v/SHA256SUMS" "$f"; serve "$IMG/v$v/SHA256SUMS.gpg" "$(sign "$who" "$f" binary)"
}
for v in 1.0.0 1.0.1 1.0.2 1.0.3 1.0.4-rc1 1.1.0; do mk_pkg "$v" 0; done
for v in 20261001 20261101; do mk_img "$v"; done
listing() { # lines "<name>@<age>"...
    printf '<html><body><pre>\n<a href="../">../</a>\n<a href="?C=N;O=D">Name</a>\n'
    for e in "$@"; do printf '<a href="%s">%s</a>     %s    1M\n' "${e%@*}" "${e%@*}" "$(when "${e#*@}")"; done
    printf '</pre></body></html>\n'
}
D=86400
set_pkg_listing() { # <age of 1.0.3>
    local a="pkg-%s.tar.xz"
    listing "pkg-1.0.0.tar.xz@$((90 * D))" "pkg-1.0.0.tar.xz.asc@$((90 * D))" "pkg-1.0.1.tar.xz@$((60 * D))" "pkg-1.0.2.tar.xz@$((30 * D))" \
        "pkg-1.0.3.tar.xz@$1" "pkg-1.0.4-rc1.tar.xz@$((D))" "pkg-1.1.0.tar.xz@$((D * 9))" > "$WORK/pkg.html"
    serve "$PKG/" "$WORK/pkg.html"
}
set_pkg_listing 3600
listing "v20261001/@$((60 * D))" "v20261101/@$((3 * D))" "latest/@$((3 * D))" > "$WORK/img.html"; serve "$IMG/" "$WORK/img.html"

cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; -*) ;; *) url="$1" ;; esac; shift; done
f="$FIX/http/$(printf '%s' "$url" | tr '/?&=:' '_____')"
[ -f "$f" ] || { echo "curl: (22) no fixture for $url" >&2; exit 22; }
cp "$f" "$out"
FAKE
cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
case "$1 $2" in
    "auth setup-git") exit 0 ;;
    "issue list") cat "$FIX/issues.json" ;;
    "pr list") echo '[]' ;;
    "issue create")
        title=""; args=("$@"); for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = --title ] && title="${args[i+1]}"; done
        n=$(( $(jq length "$FIX/issues.json") + 1 ))
        jq --arg t "$title" --argjson n "$n" '. + [{number: $n, title: $t}]' "$FIX/issues.json" > "$FIX/issues.tmp" && mv "$FIX/issues.tmp" "$FIX/issues.json"
        echo "https://github.com/x/y/issues/$n" ;;
    "issue close"|"issue comment") ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/curl" "$WORK/bin/gh"
echo '[]' > "$FIX/issues.json"
export PATH="$WORK/bin:$PATH" FIX

# ---- the fixture repository --------------------------------------------------------------------------
REPO="$WORK/repo"
mkdir -p "$REPO/upstream" "$REPO/tools"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
cp -r "$WORK/keys" "$REPO/upstream/keys"
art_sum() { sum256 "$WORK/art/$1"; }
img_sum() { printf 'image %s' "$1" | sha256sum | cut -d' ' -f1; }
cat > "$REPO/build.sh" <<Y
#!/bin/sh
GRUB_TIMEOUT=5
PKG_VERSION="1.0.1"
PKG_SHA256="$(art_sum pkg-1.0.1.tar.xz)"
IMG_URL=$IMG/v20261001/disk-20261001.img
IMG_SHA256=$(img_sum 20261001)
Y
cat > "$REPO/tools/pkg.yaml" <<Y
name: pkg
description: Pkg 1.0.1
downloads:
  - url: $PKG/pkg-1.0.1-wrapper.zip
    sha512: $(sum512 "$WORK/art/pkg-1.0.1-wrapper.zip")
env:
  - name: PKG_HOME
    value: /opt/pkg-1.0.1
  - name: PKG_BIN
    value: /opt/pkg-1.0.1/bin
Y
cat > "$REPO/upstream/manifest.yaml" <<Y
repo: Sanne/incus-spawn
app:
  login: isx-upstream[bot]
items:
  pkg:
    class: mechanical
    within: series
    cooling: 24h
    judgement: build
    source:
      type: signed-file
      index:
        url: $PKG/
        regex: 'pkg-([0-9]+\.[0-9]+\.[0-9]+)\.tar\.xz'
      files:
        any:
          url: $PKG/pkg-{version}.tar.xz
          proof: artifact
        wrapper:
          url: $PKG/pkg-{version}-wrapper.zip
          algo: sha512
          proof: artifact
      pins:
        - file: build.sh
          label: any
          text: |-
            PKG_VERSION="{version}"
            PKG_SHA256="{sum}"
        - file: tools/pkg.yaml
          text: 'description: Pkg {version}'
        - file: tools/pkg.yaml
          text: |-
            - url: $PKG/pkg-{version}-wrapper.zip
                sha512: {sum:wrapper}
        - file: tools/pkg.yaml
          count: 2
          text: 'value: /opt/pkg-{version}'
    verify:
      type: signed-file
      key: upstream/keys/test.asc
      fingerprints: [$FPR]
    files: [build.sh, tools/pkg.yaml]
    fields: [version, sha256.any, sha512.wrapper]
  img:
    class: mechanical
    within: any
    cooling: 24h
    judgement: build
    source:
      type: signed-file
      index:
        url: $IMG/
        regex: 'v([0-9]{8})/'
      files:
        any:
          url: $IMG/v{version}/disk-{version}.img
          proof: sums
          sums-url: $IMG/v{version}/SHA256SUMS
          sig-url: $IMG/v{version}/SHA256SUMS.gpg
      pins:
        - file: build.sh
          text: |-
            IMG_URL=$IMG/v{version}/disk-{version}.img
            IMG_SHA256={sum}
    verify:
      type: signed-file
      key: upstream/keys/test.asc
      fingerprints: [$FPR]
    files: [build.sh]
    fields: [version, sha256.any]
Y
cd "$REPO" && git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm fixture
git init -q --bare "$WORK/remote.git" && git remote add origin "$WORK/remote.git" && git push -q origin main
git --git-dir="$WORK/remote.git" config uploadpack.allowAnySHA1InWant true
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
bad_manifest '.items.pkg.verify.fingerprints = ["0000000000000000000000000000000000000000"]' "a fingerprint that is no key of the file"
bad_manifest ".items.pkg.verify.fingerprints = [\"$(fpr rogue)\"]" "a fingerprint of a key that is not in the file"
bad_manifest '.items.pkg.verify.fingerprints = []' "no pinned signer"
bad_manifest '.items.pkg.verify.key = "upstream/keys/none.asc"' "a key file that is not committed"
bad_manifest '.items.pkg.verify.type = "publisher-sums"' "a verify.type the source does not provide"
bad_manifest '.items.pkg.source.pins[0].file = "elsewhere.sh"' "a pin in a file that is not the item's"
bad_manifest '.items.pkg.source.pins[2].text = "- url: x\n    sha512: {sum:nope}"' "a pin naming no file label"
bad_manifest 'del(.items.pkg.source.pins[2])' "a file label no pin holds the hash of"
bad_manifest '.items.pkg.source.pins[3].count = 3' "a pin count the file does not have"
bad_manifest '.items.pkg.source.files.any.algo = "md5"' "an unknown algo"
bad_manifest '.items.pkg.source.files.any.proof = "trust-me"' "an unknown proof"
bad_manifest 'del(.items.img.source.files.any.sig-url)' "a sums proof with no signature"
bad_manifest '.items.pkg.source.files.any.url = "http://dl.example/pkg-{version}.tar.xz"' "a plain http URL"
bad_manifest '.items.pkg.source.files.any.url = "https://dl.example/{arch}/pkg-{version}.tar.xz"' "an unknown placeholder"
bad_manifest 'del(.items.pkg.source.index.regex)' "an index with no regex"
bad_manifest '.items.pkg.fields = ["version"]' "fields that drift from the source"
bad_manifest '.items.pkg.cooling = "1h"' "cooling under the floor"
echo "validate: ok"

# ---- check -------------------------------------------------------------------------------------------
for i in pkg img; do "$E" check "$i" > /dev/null || fail "check $i fails on a pin the publisher confirms"; done
T="$WORK/tamper"; rm -rf "$T"; mkdir -p "$T/tools"; cp "$REPO/build.sh" "$T/"; cp "$REPO/tools/pkg.yaml" "$T/tools/"
sed -i "s/^PKG_SHA256=\".*\"/PKG_SHA256=\"$(printf wrong | sha256sum | cut -d' ' -f1)\"/" "$T/build.sh"
"$E" check pkg "$T" > /dev/null 2>&1 && fail "check passed a tampered sum"
cp "$REPO/build.sh" "$T/"; sed -i 's|value: /opt/pkg-1.0.1/bin|value: /opt/pkg-1.0.0/bin|' "$T/tools/pkg.yaml"
"$E" check pkg "$T" > "$WORK/check.log" 2>&1 && fail "check passed pins that name two versions"
# the pinned artifact signed by a key of the file that is not pinned: a signature failure, said as one
mk_pkg 1.0.1 0 other
"$E" check pkg > "$WORK/check.log" 2>&1 && fail "check passed an artifact signed by an unpinned key"
grep -q 'not one of the pinned fingerprints' "$WORK/check.log" || fail "check did not say the signer is not pinned: $(cat "$WORK/check.log")"
mk_pkg 1.0.1 0
echo "check: ok"

# ---- plan --------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
action() { awk -F'\t' -v i="$2" '$1 == i { print $2 }' "$1/plan.tsv"; }
version() { awk -F'\t' -v i="$2" '$1 == i { print $3 }' "$1/plan.tsv"; }
P="$(plan one)"
# 1.0.3 is the newest of the 1.0 series and is cooling; 1.0.4-rc1 is no release and 1.1.0 is a new series
[ "$(action "$P" pkg)" = wait ] && [ "$(version "$P" pkg)" = 1.0.3 ] || fail "pkg: expected to wait for 1.0.3: $(cat "$P/plan.tsv")"
ls "$P/issues/" | grep -q '^pkg-1.1.json$' || fail "no issue for pkg's new series: $(ls "$P/issues")"
[ "$(action "$P" img)" = pr ] && [ "$(version "$P" img)" = 20261101 ] || fail "img: expected a PR for 20261101: $(cat "$P/plan.tsv")"
set_pkg_listing $((3 * D))
P="$(plan cooled)"
[ "$(action "$P" pkg)" = pr ] && [ "$(version "$P" pkg)" = 1.0.3 ] || fail "pkg: expected a PR for 1.0.3 once cooled: $(cat "$P/plan.tsv")"
for i in pkg img; do
    c="$(jq -r .commit "$P/$i.json")"
    [ "$(git -C "$REPO" log -1 --format=%ae "$c")" = "$APP_EMAIL" ] || fail "$i: the bump is not committed as the app"
done
pc="$(jq -r .commit "$P/pkg.json")"; ic="$(jq -r .commit "$P/img.json")"
[ "$(git -C "$REPO" diff --name-only "$BASE" "$pc" | sort | tr '\n' ' ')" = "build.sh tools/pkg.yaml " ] || fail "pkg's bump touches other files"
[ "$(git -C "$REPO" diff --name-only "$BASE" "$ic")" = build.sh ] || fail "img's bump touches other files"
git -C "$REPO" show "$pc:build.sh" > "$WORK/b.sh"; git -C "$REPO" show "$pc:tools/pkg.yaml" > "$WORK/p.yaml"
grep -qx 'PKG_VERSION="1.0.3"' "$WORK/b.sh" && grep -qx "PKG_SHA256=\"$(art_sum pkg-1.0.3.tar.xz)\"" "$WORK/b.sh" || fail "pkg's version or sha256 was not rewritten"
grep -qx 'GRUB_TIMEOUT=5' "$WORK/b.sh" || fail "the bump changed a line it does not own"
grep -qx "    sha512: $(sum512 "$WORK/art/pkg-1.0.3-wrapper.zip")" "$WORK/p.yaml" || fail "the sha512 was not rewritten"
grep -qx 'description: Pkg 1.0.3' "$WORK/p.yaml" || fail "the description kept the old version"
[ "$(grep -c '/opt/pkg-1.0.3' "$WORK/p.yaml")" = 2 ] || fail "both occurrences of a pin were not rewritten"
git -C "$REPO" show "$ic:build.sh" > "$WORK/i.sh"
grep -qx "IMG_URL=$IMG/v20261101/disk-20261101.img" "$WORK/i.sh" && grep -qx "IMG_SHA256=$(img_sum 20261101)" "$WORK/i.sh" || fail "img's pins were not rewritten from the signed sums"
jq -r .body "$P/pkg.json" | grep -q 'the signature of the file itself' || fail "the PR does not say what proved the file"
jq -r .body "$P/img.json" | grep -q 'the file itself is not downloaded' || fail "the PR does not say the image was not downloaded"
echo "plan: ok"

# ---- plan fails closed -------------------------------------------------------------------------------
cp "$WORK/art/pkg-1.0.3.tar.xz" "$WORK/good"
printf 'tampered\n' > "$WORK/art/pkg-1.0.3.tar.xz"; serve "$PKG/pkg-1.0.3.tar.xz" "$WORK/art/pkg-1.0.3.tar.xz"
"$E" plan "$WORK/plan-bad" > "$WORK/bad.log" 2>&1 && fail "a file its signature does not cover was accepted"
grep -q 'not a good signature' "$WORK/bad.log" || fail "the signature was not what failed: $(cat "$WORK/bad.log")"
cp "$WORK/good" "$WORK/art/pkg-1.0.3.tar.xz"; serve "$PKG/pkg-1.0.3.tar.xz" "$WORK/good"
mk_pkg 1.0.3 0 other
"$E" plan "$WORK/plan-other" > "$WORK/other.log" 2>&1 && fail "a signature by a key of the file that is not pinned was accepted"
grep -q 'not one of the pinned fingerprints' "$WORK/other.log" || fail "the signer was not what failed: $(cat "$WORK/other.log")"
mk_pkg 1.0.3 0 rogue
"$E" plan "$WORK/plan-rogue" > "$WORK/rogue.log" 2>&1 && fail "a signature by a key outside the file was accepted"
mk_pkg 1.0.3 0
printf 'tampered\n' >> "$WORK/art/SHA256SUMS-20261101"; serve "$IMG/v20261101/SHA256SUMS" "$WORK/art/SHA256SUMS-20261101"
"$E" plan "$WORK/plan-sums" > "$WORK/sums.log" 2>&1 && fail "a tampered sums file was accepted"
mk_img 20261101
"$E" plan "$WORK/plan-ok" > /dev/null 2>&1 || fail "the restored publishers are not accepted again"
echo "plan fail-closed: ok"

# ---- verify-trees ------------------------------------------------------------------------------------
verify() { "$E" verify-trees "$BASE" "$1" "$2" "$3" > "$WORK/verify.log" 2>&1; }
# both items live in build.sh: each bump touches it, and only its own fields tell them apart
verify "$pc" "$APP_LOGIN" yes || { cat "$WORK/verify.log" >&2; fail "the engine's own pkg bump failed verification"; }
verify "$ic" "$APP_LOGIN" yes || { cat "$WORK/verify.log" >&2; fail "the engine's own img bump failed verification"; }
bad="$(commit_from "$pc" bad sed -i "s/^PKG_SHA256=.*/PKG_SHA256=\"$(printf wrong | sha256sum | cut -d' ' -f1)\"/" build.sh)"
verify "$bad" "$APP_LOGIN" yes && fail "an app PR with a tampered sum passed"
both="$(commit_from "$pc" both bash -c "git show $ic:build.sh > build.sh; sed -i 's/^PKG_VERSION=.*/PKG_VERSION=\"1.0.3\"/' build.sh")"
verify "$both" "$APP_LOGIN" yes && fail "an app PR bumping two items passed"
# a person's PR: the values must be the publisher's
verify "$pc" someone yes || { cat "$WORK/verify.log" >&2; fail "a person's bump with the publisher's values failed"; }
verify "$bad" someone yes && fail "a person's bump with a tampered sum passed"
# an unrelated edit of the shared file asks no publisher: with every signature gone it must still pass
for u in "$PKG/pkg-1.0.1.tar.xz.asc" "$PKG/pkg-1.0.1-wrapper.zip.asc" "$IMG/v20261001/SHA256SUMS.gpg"; do unserve "$u"; done
edit="$(commit_from "$BASE" edit sed -i 's/^GRUB_TIMEOUT=5/GRUB_TIMEOUT=7/' build.sh)"
verify "$edit" someone yes || { cat "$WORK/verify.log" >&2; fail "an edit of a line no item pins re-proved a pin"; }
grep -q "no watched item's pins changed" "$WORK/verify.log" || fail "the unrelated edit was not recognised as such: $(cat "$WORK/verify.log")"
echo "verify: ok"
echo "test-signed-file: ok"
