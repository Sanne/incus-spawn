#!/usr/bin/env bash
# The antigravity source type (#2366) on a fixture repository with Google faked: curl serves the two
# stable Linux manifests, each build's manifest.json, the bucket's object records and the tarballs by
# URL. Checks that validate reads the pin from the Java file; that check passes a pin the stable
# manifests no longer name, from its build's own manifest.json; that plan proposes the stable
# release at once, or waits while it cools where the item sets a cooling and then proposes it, with
# the sha256 of tarballs that matched Google's sha512; that check and plan report a stable release
# rolled back below the pin as a pin mismatch, and a manifest that cannot be read as a failed read;
# that plan fails closed on a tarball the sha512 does not match, a build manifest whose sha512
# differs from the stable one and a URL off the bucket, and proposes nothing while the Linux
# manifests name two builds; that verify-trees passes exactly the engine's
# regeneration and a person's correct bump, and fails a tampered sum, an app PR for a version the
# stable manifests no longer name and one made during a split; and that a Java file whose pin lines
# changed shape has no fields.
# Needs bash, git, jq, yq, perl; curl and gh faked.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX/http" "$WORK/bin" "$WORK/art"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x
NOW="$(date -u +%s)"
ts() { date -u -d "@$((NOW - $1))" +%FT%T.000Z; }
http() { printf '%s' "$1" | tr '/?&=:%' '______'; }
UPD=https://antigravity-cli-auto-updater-974169037036.us-central1.run.app
BUCKET=https://storage.googleapis.com/antigravity-public/antigravity-cli
OBJ=https://storage.googleapis.com/storage/v1/b/antigravity-public/o/antigravity-cli%2F
declare -A DIR=([x86_64]=linux-x64 [aarch64]=linux-arm) FILE=([x86_64]=cli_linux_x64.tar.gz [aarch64]=cli_linux_arm64.tar.gz) \
    PLAT=([x86_64]=linux_amd64 [aarch64]=linux_arm64)
serve() { cp "$2" "$FIX/http/$(http "$1")"; }

# build <version> <execution id> <age seconds>: serves both tarballs, their manifest.json and records.
build() {
    local v="$1" id="$2" age="$3" a f url
    for a in x86_64 aarch64; do
        f="$WORK/art/$v-$a"; printf 'agy %s %s\n' "$v" "$a" > "$f"
        url="$BUCKET/$v-$id/${DIR[$a]}/${FILE[$a]}"
        serve "$url" "$f"
        jq -n --arg v "$v" --arg u "$url" --arg s "$(sha512sum "$f" | cut -d' ' -f1)" '{version: $v, url: $u, sha512: $s}' > "$WORK/art/$v-$a.json"
        serve "$BUCKET/$v-$id/${DIR[$a]}/manifest.json" "$WORK/art/$v-$a.json"
        jq -n --arg t "$(ts "$age")" '{timeCreated: $t}' > "$WORK/art/$v-$a.obj"
        serve "$OBJ$v-$id%2F${DIR[$a]}%2F${FILE[$a]}" "$WORK/art/$v-$a.obj"
    done
}
# stable <version>: the updater's manifests name that build.
stable() { local a; for a in x86_64 aarch64; do serve "$UPD/manifests/${PLAT[$a]}.json" "$WORK/art/$1-$a.json"; done; }
sum256() { sha256sum "$WORK/art/$1-$2" | cut -d' ' -f1; }
build 1.5.10 100 $((6 * 86400)); build 1.5.11 111 $((5 * 86400)); build 1.5.12 222 $((3 * 86400)); build 1.5.13 333 3600
stable 1.5.13

# ---- fakes -------------------------------------------------------------------------------------------
cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; -*) ;; *) url="$1" ;; esac; shift; done
f="$FIX/http/$(printf '%s' "$url" | tr '/?&=:%' '______')"
[ -f "$f" ] || { echo "curl: (22) no fixture for $url" >&2; exit 22; }
cp "$f" "$out"
FAKE
cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
case "$1 $2" in
    "auth setup-git") exit 0 ;;
    "issue list"|"pr list") echo '[]' ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/curl" "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH" FIX

# ---- the fixture repository --------------------------------------------------------------------------
REPO="$WORK/repo"
J="common/src/main/java/dev/incusspawn/tool/AntigravitySetup.java"
mkdir -p "$REPO/upstream" "$REPO/$(dirname "$J")"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
# The real file, pinned at 1.5.12 of the fixture.
perl -0pe 's/VERSION = "[^"]*"/VERSION = "1.5.12"/; s/BUILD = "[^"]*"/BUILD = "1.5.12-222"/;
    s/(X86_64 = new Download\([^\n]*\n\s*")[0-9a-f]{64}/${1}'"$(sum256 1.5.12 x86_64)"'/;
    s/(AARCH64 = new Download\([^\n]*\n\s*")[0-9a-f]{64}/${1}'"$(sum256 1.5.12 aarch64)"'/' "$SRC/$J" > "$REPO/$J"
yq '{"repo": .repo, "app": .app, "items": {"antigravity": .items.antigravity}}' "$SRC/upstream/manifest.yaml" > "$REPO/upstream/manifest.yaml"
cd "$REPO" && git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm fixture
git init -q --bare "$WORK/remote.git" && git remote add origin "$WORK/remote.git" && git push -q origin main
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
bad_manifest '.items.antigravity.fields = ["version"]' "fields that drift from the source"
bad_manifest '.items.antigravity.verify.type = "asset-digest"' "a verify.type the source does not provide"
cp "$REPO/$J" "$WORK/java.bak"
sed -i 's|antigravity-public/antigravity-cli/" + BUILD|evil/" + BUILD|' "$REPO/$J"
"$E" validate > /dev/null 2>&1 && fail "validate accepted a Java file whose URLs leave the bucket"
cp "$WORK/java.bak" "$REPO/$J"
[ "$("$E" current antigravity)" = 1.5.12 ] || fail "current does not read 1.5.12"
[ "$("$E" releases antigravity)" = 1.5.13 ] || fail "releases is not the stable version alone"
echo "validate: ok"

# ---- check -------------------------------------------------------------------------------------------
UPSTREAM_REHASH=1 "$E" check antigravity > /dev/null || fail "check fails on a pin the stable manifests no longer name"
UPSTREAM_REHASH=0 "$E" check antigravity > /dev/null || fail "check without a re-hash fails on the pin"
printf 'replaced\n' > "$WORK/art/repl"; cp "$FIX/http/$(http "$BUCKET/1.5.12-222/linux-x64/cli_linux_x64.tar.gz")" "$WORK/art/keep"
serve "$BUCKET/1.5.12-222/linux-x64/cli_linux_x64.tar.gz" "$WORK/art/repl"
UPSTREAM_REHASH=1 "$E" check antigravity > /dev/null 2>&1 && fail "check passed a pinned tarball that no longer matches its sha512"
serve "$BUCKET/1.5.12-222/linux-x64/cli_linux_x64.tar.gz" "$WORK/art/keep"
# the pinned build's manifest.json gone: a failed read even without a re-hash, never the pin's own sum
mf="$FIX/http/$(http "$BUCKET/1.5.12-222/linux-x64/manifest.json")"; mv "$mf" "$WORK/art/mf"
rc=0; UPSTREAM_REHASH=0 "$E" check antigravity > "$WORK/mf.log" 2>&1 || rc=$?
[ "$rc" = 2 ] || fail "check without a re-hash passed a pin whose manifest.json is gone (rc $rc): $(cat "$WORK/mf.log")"
grep -qF "1.5.12-222/linux-x64/manifest.json" "$WORK/mf.log" || fail "the missing manifest.json was not what failed: $(cat "$WORK/mf.log")"
mv "$WORK/art/mf" "$mf"
echo "check: ok"

# ---- plan --------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
row() { awk -F'\t' '$1 == "antigravity" { print $2 " " $3 }' "$1/plan.tsv"; }
# The manifest's cooling is 0: a tarball uploaded an hour ago is proposed at once.
P="$(plan fresh)"
[ "$(row "$P")" = "pr 1.5.13" ] || fail "expected a PR for a fresh 1.5.13 with no cooling: $(cat "$P/plan.tsv")"
# An item that sets a cooling waits for it, counted from the tarballs' upload.
yq -i '.items.antigravity.cooling = "24h"' "$REPO/upstream/manifest.yaml"
P="$(plan cooling)"
[ "$(row "$P")" = "wait 1.5.13" ] || fail "expected to wait for 1.5.13: $(cat "$P/plan.tsv")"
for a in x86_64 aarch64; do jq -n --arg t "$(ts $((2 * 86400)))" '{timeCreated: $t}' > "$WORK/art/1.5.13-$a.obj"
    serve "$OBJ"'1.5.13-333%2F'"${DIR[$a]}%2F${FILE[$a]}" "$WORK/art/1.5.13-$a.obj"; done
P="$(plan cooled)"
[ "$(row "$P")" = "pr 1.5.13" ] || fail "expected a PR for 1.5.13 once cooled: $(cat "$P/plan.tsv")"
git -C "$REPO" checkout -q -- upstream/manifest.yaml
C="$(jq -r .commit "$P/antigravity.json")"
[ "$(git -C "$REPO" diff --name-only "$BASE" "$C")" = "$J" ] || fail "the bump touches more than the Java file"
git -C "$REPO" show "$C:$J" > "$WORK/new.java"
grep -q 'VERSION = "1.5.13";' "$WORK/new.java" && grep -q 'BUILD = "1.5.13-333";' "$WORK/new.java" || fail "version or build not moved"
grep -q "\"$(sum256 1.5.13 x86_64)\");" "$WORK/new.java" && grep -q "\"$(sum256 1.5.13 aarch64)\");" "$WORK/new.java" || fail "the sums are not the tarballs' sha256"
jq -r .body "$P/antigravity.json" | grep -q 'the bot never approves or merges it' || fail "the review item's PR does not say the bot leaves it"
echo "plan: ok"

# ---- plan fails closed -------------------------------------------------------------------------------
fails() { rm -rf "$WORK/plan-bad"; git -C "$REPO" worktree prune; "$E" plan "$WORK/plan-bad" > "$WORK/bad.log" 2>&1 && fail "plan accepted $1"; grep -q "$2" "$WORK/bad.log" || fail "$1 was not what failed: $(cat "$WORK/bad.log")"; }
T13="$BUCKET/1.5.13-333/linux-arm/cli_linux_arm64.tar.gz"
serve "$T13" "$WORK/art/repl"
fails "a tarball its sha512 does not match" "hashes to"
serve "$T13" "$WORK/art/1.5.13-aarch64"
jq '.sha512 = ("0" * 128)' "$WORK/art/1.5.13-x86_64.json" > "$WORK/art/m"; serve "$BUCKET/1.5.13-333/linux-x64/manifest.json" "$WORK/art/m"
fails "a build manifest whose sha512 differs from the stable one" "different sha512s"
serve "$BUCKET/1.5.13-333/linux-x64/manifest.json" "$WORK/art/1.5.13-x86_64.json"
# a rollout that reached one platform first: the item waits, and plan goes on
serve "$UPD/manifests/linux_arm64.json" "$WORK/art/1.5.11-aarch64.json"
P="$(plan split)"
[ "$(row "$P")" = "none 1.5.12" ] || fail "Linux manifests naming two builds proposed something: $(cat "$P/plan.tsv")"
jq '.url = "https://evil.example/1.5.13-333/linux-x64/cli_linux_x64.tar.gz"' "$WORK/art/1.5.13-x86_64.json" > "$WORK/art/m"
serve "$UPD/manifests/linux_amd64.json" "$WORK/art/m"; serve "$UPD/manifests/linux_arm64.json" "$WORK/art/1.5.13-aarch64.json"
fails "a stable URL off the bucket" "is not a build of"
stable 1.5.13
echo "plan fail-closed: ok"

# ---- a rollback below the pin ------------------------------------------------------------------------
# Google takes 1.5.13 back and names 1.5.11 stable under a 1.5.12 pin: the pin is a build it pulled,
# though its manifest.json stays in the bucket, so check says so and plan opens a pin-mismatch issue
stable 1.5.11
UPSTREAM_REHASH=0 "$E" check antigravity > "$WORK/rb.log" 2>&1 && fail "check passed a pin above the stable release"
grep -q "rolled back" "$WORK/rb.log" || fail "the rollback was not what check reported: $(cat "$WORK/rb.log")"
P="$(plan rollback)"
[ "$(row "$P")" = "issue 1.5.12" ] || fail "a rollback below the pin did not become an issue: $(cat "$P/plan.tsv")"
ls "$P/issues/" | grep -q '^antigravity-pin-mismatch.json$' || fail "no pin-mismatch issue for the rollback: $(ls "$P/issues")"
# a rollback hidden by a split rollout is a rollback still
serve "$UPD/manifests/linux_arm64.json" "$WORK/art/1.5.11-aarch64.json"; serve "$UPD/manifests/linux_amd64.json" "$WORK/art/1.5.10-x86_64.json"
P="$(plan rollback-split)"
[ "$(row "$P")" = "issue 1.5.12" ] || fail "a rollback during a split did not become an issue: $(cat "$P/plan.tsv")"
stable 1.5.11
# a split is not a rollback, and an unreadable manifest fails the run rather than read as either
serve "$UPD/manifests/linux_amd64.json" "$WORK/art/1.5.13-x86_64.json"
P="$(plan split-again)"
[ "$(row "$P")" = "none 1.5.12" ] || fail "a split read as a rollback: $(cat "$P/plan.tsv")"
rm "$FIX/http/$(http "$UPD/manifests/linux_arm64.json")"
fails "a stable manifest that cannot be read" "failed to download"
rc=0; UPSTREAM_REHASH=0 "$E" check antigravity > "$WORK/rd.log" 2>&1 || rc=$?
[ "$rc" = 2 ] && grep -q "cannot read Google's stable manifests" "$WORK/rd.log" || fail "check did not report the unreadable manifest as a failed read (rc $rc): $(cat "$WORK/rd.log")"
stable 1.5.13
echo "rollback: ok"

# ---- verify-trees ------------------------------------------------------------------------------------
verify() { "$E" verify-trees "$BASE" "$1" "$2" "$3" > "$WORK/verify.log" 2>&1; }
verify "$C" "$APP_LOGIN" yes || { cat "$WORK/verify.log" >&2; fail "the engine's own bump failed verification"; }
verify "$C" someone yes || { cat "$WORK/verify.log" >&2; fail "a person's correct bump failed verification"; }
bad="$(commit_from "$C" bad sed -i "s/$(sum256 1.5.13 aarch64)/$(printf x | sha256sum | cut -d' ' -f1)/" "$J")"
verify "$bad" "$APP_LOGIN" yes && fail "an app PR with a tampered sum passed"
verify "$bad" someone yes && fail "a person's PR with a tampered sum passed"
build 1.5.14 444 $((2 * 86400)); stable 1.5.14
verify "$C" "$APP_LOGIN" yes && fail "an app PR for a version the stable manifests no longer name passed"
grep -q "not 1.5.13" "$WORK/verify.log" || fail "the moved stable version was not what failed: $(cat "$WORK/verify.log")"
# during a split rollout the app PR fails, and says why
serve "$UPD/manifests/linux_arm64.json" "$WORK/art/1.5.13-aarch64.json"
verify "$C" "$APP_LOGIN" yes && fail "an app PR passed while the Linux manifests name two builds"
grep -q "name two builds" "$WORK/verify.log" || fail "the split was not what failed: $(cat "$WORK/verify.log")"
stable 1.5.14
# a Java file whose pin lines cannot be read has no fields
sed -i 's|new Download(DOWNLOAD_BASE + "/linux-arm/cli_linux_arm64.tar.gz",|new Download(DOWNLOAD_BASE + "/linux-arm/" + "cli_linux_arm64.tar.gz",|' "$REPO/$J"
( cd "$REPO/upstream/engine" && TREE="$REPO" ITEM=antigravity MANIFEST="$REPO/upstream/manifest.yaml" UPSTREAM_CACHE="$WORK" \
    bash -c '. ./lib.sh; load_source antigravity; if f="$(src_fields)"; then exit 0; else exit 1; fi' ) > "$WORK/fields.log" 2>&1 \
    && fail "src_fields read a Java file whose lines changed shape, in an if as _narrow reads it"
grep -q "matches .* 0 times, not once" "$WORK/fields.log" || fail "the changed line was not what failed: $(cat "$WORK/fields.log")"
git -C "$REPO" checkout -q -- "$J"
echo "verify: ok"
echo "test-antigravity: ok"
