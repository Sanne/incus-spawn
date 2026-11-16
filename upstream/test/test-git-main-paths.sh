#!/usr/bin/env bash
# The git-main-paths source type and a sibling repository's watch (#2210), with incus-spawn served by
# a local bare repository (git's url.insteadOf) and gh faked. The engine runs from a copy of its own,
# outside the watched repository, which UPSTREAM_REPO_ROOT names, as a sibling's verify job and the
# watch's sibling legs run it. Checks that validate holds the type's rules (a first-party repository
# only, plain paths, the pin's file among the item's and its text found once) and refuses main-commit
# on a mechanical item of another owner; that plan prepares the bump in the sibling's own history to
# the newest commit on main that changed a watched path, never to a later commit that changed none,
# never back, and that a pin not on main is a pin-mismatch issue whose title names the sibling; that
# verify-trees passes the app's regeneration and a person's edit beside the pin, and fails a commit
# not on main, a move back and a person's pin off main; and that publish refuses a token minted for
# another repository. A forked repository is not first party, and an app PR to a commit that
# changed no watched path fails. Needs bash, git, jq, yq, perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
mkdir -p "$WORK/bin"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x

cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
case "$1 $2" in
    "pr list"|"issue list") echo '[]' ;;
    "api repos/Sanne/incus-spawn") echo "${FORK:-false}" ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH"

# ---- incus-spawn's main, served as https://github.com/Sanne/incus-spawn.git ---------------------------
ISX="$WORK/isx"
mkdir -p "$ISX/appliance/kernel" "$ISX/upstream/engine" "$ISX/docs"
cd "$ISX" && git init -q -b main
c() { # <file> <message>: one commit changing <file>; prints its id
    echo "$2" >> "$1"; git -C "$ISX" add -A; git -C "$ISX" -c user.name=s -c user.email=s@s commit -qm "$2"; git -C "$ISX" rev-parse HEAD
}
C1="$(c appliance/kernel/vm.config 'Kernel 7.3.1')"
E1="$(c upstream/engine/upstream.sh 'Engine one')"
C2="$(c docs/x.md 'Docs, nothing the image reads')"
C3="$(c appliance/kernel/vm.config 'Kernel 7.3.4, see #2045 and `code`')"
E2="$(c upstream/engine/upstream.sh 'Engine two')"
C4="$(c docs/x.md 'More docs')"
git -C "$ISX" switch -q -c side "$C1"
OFF="$(c appliance/kernel/vm.config 'A branch never merged')"
git -C "$ISX" switch -q main
git clone -q --bare "$ISX" "$WORK/isx.git"
git -C "$WORK/isx.git" config uploadpack.allowFilter true
git -C "$WORK/isx.git" fetch -q "$ISX" side:side
printf '[url "file://%s"]\n\tinsteadOf = https://github.com/Sanne/incus-spawn.git\n' "$WORK/isx.git" > "$WORK/gitconfig"
export GIT_CONFIG_GLOBAL="$WORK/gitconfig"

# ---- the engine, apart from the watched repository ---------------------------------------------------
mkdir -p "$WORK/engine/upstream"
cp -r "$SRC/upstream/engine" "$WORK/engine/upstream/engine"
E="$WORK/engine/upstream/engine/upstream.sh"

# ---- the sibling --------------------------------------------------------------------------------------
SIB="$WORK/images"
mkdir -p "$SIB/upstream" "$SIB/fedora"
cat > "$SIB/fedora/build-vm-kernel.sh" <<SH
#!/bin/bash
ISX_REPO=https://github.com/Sanne/incus-spawn.git
ISX_REF=$C1
echo "ISX_REF is not this line's text"
SH
echo "$E1" > "$SIB/upstream/engine.ref"
cat > "$SIB/upstream/manifest.yaml" <<'Y'
repo: Sanne/incus-spawn-images
app:
  login: isx-upstream[bot]
items:
  isx-ref:
    class: mechanical
    within: any
    cooling: 0
    source:
      type: git-main-paths
      repo: Sanne/incus-spawn
      paths: [appliance/kernel/build-kernel.sh, 'appliance/kernel/*.config']
      pin:
        file: fedora/build-vm-kernel.sh
        text: "ISX_REF={version}\n"
    verify:
      type: main-commit
    files: [fedora/build-vm-kernel.sh]
    fields: [commit]
  engine:
    class: mechanical
    within: any
    cooling: 0
    source:
      type: git-main-paths
      repo: Sanne/incus-spawn
      paths: [upstream/engine]
      pin:
        file: upstream/engine.ref
        text: "{version}\n"
    verify:
      type: main-commit
    files: [upstream/engine.ref]
    fields: [commit]
Y
cd "$SIB" && git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm fixture
BASE="$(git -C "$SIB" rev-parse HEAD)"
export UPSTREAM_REPO_ROOT="$SIB"
commit_from() { # <start> <author email> <command...>: a commit of the sibling's; prints its id
    local start="$1" who="$2"; shift 2
    local wt="$WORK/wt"; rm -rf "$wt"; git -C "$SIB" worktree prune; git -C "$SIB" worktree add -q --detach "$wt" "$start"
    ( cd "$wt" && "$@" && git add -A && git -c user.name=a -c user.email="$who" commit -qm change && git rev-parse HEAD )
}
set_ref() { sed -i "s/^ISX_REF=.*/ISX_REF=$1/" fedora/build-vm-kernel.sh; }

# ---- validate -----------------------------------------------------------------------------------------
"$E" validate > /dev/null || fail "validate refuses the sibling's manifest"
bad_manifest() {
    local m="$WORK/bad.yaml"; yq "$1" "$SIB/upstream/manifest.yaml" > "$m"
    MANIFEST="$m" "$E" validate > "$WORK/bad.log" 2>&1 && fail "validate accepted a manifest with $2"
    grep -q "$3" "$WORK/bad.log" || fail "validate refused $2 for another reason: $(cat "$WORK/bad.log")"
}
bad_manifest '.items.isx-ref.source.repo = "Someone/incus-spawn"' "a repository of another owner" "first-party\|manifest's owner"
bad_manifest '.items.isx-ref.source.paths = [":(top)appliance"]' "a pathspec with magic" "plain relative path"
bad_manifest '.items.isx-ref.source.paths = ["../x"]' "a path out of the repository" "plain relative path"
bad_manifest '.items.isx-ref.source.paths = []' "no path" "lists nothing"
bad_manifest '.items.isx-ref.source.pin.file = "README.md"' "a pin file that is not the item's" "not one of its files"
bad_manifest '.items.isx-ref.source.pin.text = "ISX_REF="' "a pin text without {version}" "{version} once"
bad_manifest '.items.isx-ref.verify.type = "publisher-sums"' "a verifier the type does not provide" "not what source type"
# A pin found twice: which one the build reads is not the watch's to guess.
cp fedora/build-vm-kernel.sh "$WORK/k.bak"; echo "ISX_REF=$C3" >> fedora/build-vm-kernel.sh
"$E" validate > "$WORK/bad.log" 2>&1 && fail "validate accepted a pin found twice"
grep -q "2 times, not once" "$WORK/bad.log" || fail "the pin found twice failed for another reason: $(cat "$WORK/bad.log")"
cp "$WORK/k.bak" fedora/build-vm-kernel.sh
# A commit with more after it is not a pin: the text's final newline is lost to $(...).
sed -i "s/^ISX_REF=.*/ISX_REF=${C1}abcd/" fedora/build-vm-kernel.sh
"$E" validate > "$WORK/bad.log" 2>&1 && fail "validate accepted a pin with characters after the commit"
grep -q "0 times, not once" "$WORK/bad.log" || fail "the pin with a tail failed for another reason: $(cat "$WORK/bad.log")"
cp "$WORK/k.bak" fedora/build-vm-kernel.sh
# main-commit is the bot's only when the repository is the manifest owner's own.
m="$WORK/owner.yaml"; yq '.repo = "Someone/incus-spawn-images"' "$SIB/upstream/manifest.yaml" > "$m"
MANIFEST="$m" "$E" validate > "$WORK/bad.log" 2>&1 && fail "validate accepted main-commit of another owner's repository"
# A fork of the owner's is not first party: its main holds what its upstream merged.
FORK=true "$E" validate > "$WORK/bad.log" 2>&1 && fail "validate accepted main-commit of a forked repository"
grep -q "manifest's owner" "$WORK/bad.log" || fail "the fork failed for another reason: $(cat "$WORK/bad.log")"
echo "validate: ok"

# ---- current, check, releases ----------------------------------------------------------------------------
[ "$("$E" current isx-ref)" = "$C1" ] || fail "current reads $("$E" current isx-ref), not $C1"
"$E" check isx-ref > /dev/null 2>&1 || fail "check fails on a pin on main"
[ "$("$E" releases isx-ref | tr '\n' ' ')" = "$C1 $C3 " ] || fail "releases are not the commits that changed the paths: $("$E" releases isx-ref)"
echo "current, check, releases: ok"

# ---- plan ---------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
P="$(plan one)"
grep -qP "^isx-ref\tpr\t$C3\tprepared$" "$P/plan.tsv" || fail "plan did not prepare $C3 (the newest kernel commit, not $C4): $(cat "$P/plan.tsv")"
grep -qP "^engine\tpr\t$E2\tprepared$" "$P/plan.tsv" || fail "plan did not prepare the engine's $E2: $(cat "$P/plan.tsv")"
PREP="$(jq -r .commit "$P/isx-ref.json")"
git -C "$SIB" fetch -q "$P/isx-ref.bundle" "refs/upstream-prepared/isx-ref:refs/prep/isx-ref"
[ "$(git -C "$SIB" rev-parse refs/prep/isx-ref)" = "$PREP" ] || fail "the bundle does not carry the prepared commit"
[ "$(git -C "$SIB" rev-parse "$PREP^")" = "$BASE" ] || fail "the prepared commit is not on the sibling's own history"
[ "$(git -C "$SIB" diff --name-only "$BASE" "$PREP")" = fedora/build-vm-kernel.sh ] || fail "the bump changes more than its file"
[ "$(git -C "$SIB" diff "$BASE" "$PREP" | grep -c '^[-+]ISX_REF=')" = 2 ] || fail "the bump changes more than the pin's line"
[ "$(git -C "$SIB" log -1 --format=%ae "$PREP")" = "$APP_EMAIL" ] || fail "the prepared commit is not the app's"
jq -r .body "$P/isx-ref.json" | grep -qF '`Kernel 7.3.4, see #2045 and code`' || fail "the PR body does not list the commit in a code span: $(jq -r .body "$P/isx-ref.json")"
jq -r .body "$P/isx-ref.json" | grep -q 'Docs, nothing' && fail "the PR body lists a commit that changed no watched path"
[ "$(jq -r .title "$P/isx-ref.json")" = "Update isx-ref to ${C3:0:8}" ] || fail "the PR title does not name the commit short: $(jq -r .title "$P/isx-ref.json")"
jq -r .body "$P/isx-ref.json" | grep -qF '(Sanne/incus-spawn#2036)' || fail "the sibling's PR body names the watch's issue as its own repository's"
git -C "$SIB" log -1 --format=%b "$PREP" | grep -qF '(Sanne/incus-spawn#2036)' || fail "the sibling's commit names the watch's issue as its own repository's"
[ -z "$(ls "$P/issues")" ] || fail "a bump opened an issue: $(ls "$P/issues")"
git -C "$SIB" worktree list | grep -q plan- && fail "plan left a worktree in the sibling"
# At the newest kernel commit, and at a later commit that changed no watched path: nothing moves.
for at in "$C3" "$C4"; do
    git -C "$SIB" checkout -q "$(commit_from "$BASE" t@t set_ref "$at")"
    P="$(plan "at-$at")"
    grep -qP "^isx-ref\tnone\t$at\tcurrent$" "$P/plan.tsv" || fail "plan moved a pin at $at: $(cat "$P/plan.tsv")"
done
# A pin on a branch never merged: the publisher no longer confirms it, an issue that names the sibling.
git -C "$SIB" checkout -q "$(commit_from "$BASE" t@t set_ref "$OFF")"
P="$(plan off)"
grep -qP "^isx-ref\tissue\t$OFF\tpin mismatch$" "$P/plan.tsv" || fail "a pin off main is not a pin-mismatch issue: $(cat "$P/plan.tsv")"
I="$(ls "$P"/issues/isx-ref-*.json)"
[ "$(jq -r .title "$I")" = "[upstream] incus-spawn-images/isx-ref: pinned $OFF no longer matches its publisher" ] \
    || fail "the sibling's issue does not name it: $(jq -r .title "$I")"
jq -r .body "$I" | grep -q '^In `Sanne/incus-spawn-images`: ' || fail "the sibling's issue body does not name it"
# A pin of a commit incus-spawn does not have at all: a mismatch too, never a failed read.
NONE=0123456789abcdef0123456789abcdef01234567
git -C "$SIB" checkout -q "$(commit_from "$BASE" t@t set_ref "$NONE")"
P="$(plan none)"
grep -qP "^isx-ref\tissue\t$NONE\tpin mismatch$" "$P/plan.tsv" || fail "a pin of an unknown commit is not a pin-mismatch issue: $(cat "$P/plan.tsv")"
git -C "$SIB" checkout -q main
# incus-spawn unreachable: a failed read fails the run, never a pin-mismatch issue (#2261).
mv "$WORK/isx.git" "$WORK/isx.gone"
out="$WORK/plan-unreachable"; "$E" plan "$out" > "$out.log" 2>&1 && fail "plan passed with incus-spawn unreachable"
[ -z "$(ls "$out/issues" 2> /dev/null)" ] || fail "an unreachable incus-spawn opened an issue: $(ls "$out/issues")"
# check, which plan runs with its output in a file, reports the failed fetch and returns 2, the code
# plan fails the run on after printing that file.
rc=0; "$E" check isx-ref > "$WORK/check.log" 2>&1 || rc=$?
[ "$rc" = 2 ] || fail "check of an unreachable incus-spawn returned $rc, not 2: $(cat "$WORK/check.log")"
grep -q "cannot fetch main of Sanne/incus-spawn" "$WORK/check.log" || fail "check does not say why: $(cat "$WORK/check.log")"
mv "$WORK/isx.gone" "$WORK/isx.git"
echo "plan: ok"

# ---- verify-trees -------------------------------------------------------------------------------------
vt() { "$E" verify-trees "$BASE" "$1" "$2" "$3" > "$WORK/vt.log" 2>&1; }
vt "$PREP" "$APP_LOGIN" yes || { cat "$WORK/vt.log"; fail "verify-trees refused the engine's own regeneration"; }
X="$(commit_from "$BASE" "$APP_EMAIL" set_ref "$OFF")"
vt "$X" "$APP_LOGIN" yes && fail "verify-trees passed an app PR to a commit not on main"
grep -q 'not a commit on main' "$WORK/vt.log" || fail "the off-main app PR failed for another reason: $(cat "$WORK/vt.log")"
NEWER="$(commit_from "$BASE" t@t set_ref "$C4")"
X="$(commit_from "$NEWER" "$APP_EMAIL" set_ref "$C3")"
"$E" verify-trees "$NEWER" "$X" "$APP_LOGIN" yes > "$WORK/vt.log" 2>&1 && fail "verify-trees passed an app PR that moves back"
X="$(commit_from "$BASE" a@a set_ref "$C3")"
vt "$X" someone no || { cat "$WORK/vt.log"; fail "verify-trees refused a person's bump to a commit on main"; }
# A commit on main that changed no watched path: plan never proposes it, so the app's PR fails,
# while a person may pin it (its inputs are a proposed commit's).
X="$(commit_from "$BASE" "$APP_EMAIL" set_ref "$C2")"
vt "$X" "$APP_LOGIN" yes && fail "verify-trees passed an app PR to a commit that changed no watched path"
grep -q 'changed none of' "$WORK/vt.log" || fail "the app PR to $C2 failed for another reason: $(cat "$WORK/vt.log")"
X="$(commit_from "$BASE" a@a set_ref "$C2")"
vt "$X" someone no || { cat "$WORK/vt.log"; fail "verify-trees refused a person's pin of a commit on main that changed no watched path"; }
X="$(commit_from "$BASE" a@a set_ref "$OFF")"
vt "$X" someone no && fail "verify-trees passed a person's pin off main"
X="$(commit_from "$BASE" a@a sed -i 's/^echo .*/echo "another line"/' fedora/build-vm-kernel.sh)"
vt "$X" someone no || { cat "$WORK/vt.log"; fail "verify-trees refused an edit beside the pin"; }
grep -q "no watched item's pins changed" "$WORK/vt.log" || fail "an edit beside the pin was re-proven: $(cat "$WORK/vt.log")"
echo "verify-trees: ok"

# ---- publish ------------------------------------------------------------------------------------------
P="$(plan publish)"
REPO=Sanne/incus-spawn "$E" publish "$P" > "$WORK/pub.log" 2>&1 && fail "publish took a token of another repository for the sibling"
grep -q "not the manifest's repository" "$WORK/pub.log" || fail "publish failed for another reason: $(cat "$WORK/pub.log")"
echo "publish: ok"
echo "git-main-paths: ok"
