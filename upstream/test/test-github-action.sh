#!/usr/bin/env bash
# The github-action source type (#2210) on a fixture repository with GitHub faked: gh answers the
# releases, tag refs and tag objects from fixtures. Checks that validate holds the type's rules (class
# judgement only, members of owner/repo shape, every external uses: pinned to a commit with its tag,
# no action outside the members, one commit per member); that check passes pins whose tags point at
# them, an annotated tag followed to its commit, and fails a tag that moved; that plan opens one issue
# under the family's own title for every newer cooled release, skipping a draft, a prerelease and one
# still cooling, and none when every member is current; that apply rewrites every use of a member, a
# sub-path action's included, and nothing else; and that verify-trees passes a person's bump whose
# commits are the tags' and an edit beside the pins, and fails a commit its tag does not name, a tag
# instead of a commit and an action that is not a member. Needs bash, git, jq, yq, perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX" "$WORK/bin"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x
NOW="$(date -u +%s)"
ts() { date -u -d "@$((NOW - $1))" +%FT%TZ; }
sha() { printf '%s' "$1" | sha1sum | cut -d' ' -f1; }
D=86400

# ---- GitHub ------------------------------------------------------------------------------------------
# releases <repo> <tag:age[:draft|pre]...>
releases() {
    local repo="$1" t; shift
    for t in "$@"; do
        IFS=: read -r tag age kind <<<"$t"
        jq -n --arg t "$tag" --arg at "$(ts "$age")" --argjson d "$([[ "$kind" == draft ]] && echo true || echo false)" \
            --argjson p "$([[ "$kind" == pre ]] && echo true || echo false)" '{tag_name: $t, published_at: $at, draft: $d, prerelease: $p}'
    done | jq -s . > "$FIX/rel-${repo//\//_}.json"
}
# tag <repo> <tag> <commit> [annotated]: the ref, lightweight or through a tag object.
tag() {
    local f="$FIX/tags-${1//\//_}.json" obj
    [[ -f "$f" ]] || echo '{}' > "$f"
    if [[ "${4:-}" == annotated ]]; then
        obj="$(sha "tagobj $1 $2 $3")"
        jq --arg s "$obj" --arg c "$3" '.[$s] = {object: {type: "commit", sha: $c}}' "$FIX/tagobjs.json" > "$FIX/t.tmp" && mv "$FIX/t.tmp" "$FIX/tagobjs.json"
        jq --arg t "$2" --arg s "$obj" '.[$t] = {ref: "refs/tags/\($t)", object: {type: "tag", sha: $s}}' "$f" > "$f.tmp"
    else
        jq --arg t "$2" --arg s "$3" '.[$t] = {ref: "refs/tags/\($t)", object: {type: "commit", sha: $s}}' "$f" > "$f.tmp"
    fi
    mv "$f.tmp" "$f"
}
echo '{}' > "$FIX/tagobjs.json"
A1="$(sha act-1.0.0)"; A11="$(sha act-1.1.0)"; A12="$(sha act-1.2.0)"; A2="$(sha act-2.0.0)"
O1="$(sha other-3.0.0)"; O2="$(sha other-3.1.0)"
# example/act: 1.1.0 is cooled, 1.2.0 is still cooling, 1.3.0 a draft, 1.4.0 a prerelease, 2.0.0 a new major.
releases example/act v1.0.0:$((90 * D)) v1.1.0:$((10 * D)) v1.2.0:3600 v1.3.0:$((9 * D)):draft v1.4.0:$((9 * D)):pre v2.0.0:$((5 * D))
tag example/act v1.0.0 "$A1"; tag example/act v1.1.0 "$A11"; tag example/act v1.2.0 "$A12"; tag example/act v2.0.0 "$A2"
# example/other: annotated tags, and nothing newer than the pin.
releases example/other v3.0.0:$((40 * D))
tag example/other v3.0.0 "$O1" annotated

cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
args=("$@")
jqf() { local i; for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = --jq ] && echo "${args[i+1]}"; done; }
nf() { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
case "$1 $2" in
    "api --paginate") r="${3#repos/}"; r="${r%/releases}"; jq -r "$(jqf)" "$FIX/rel-${r//\//_}.json" ;;
    "api repos/"*)
        case "$2" in
            */git/ref/tags/*) [ -f "$FIX/fail-tag-read" ] && { echo "gh: HTTP 502" >&2; exit 1; }
                r="${2#repos/}"; r="${r%%/git/*}"; t="${2##*/tags/}"
                jq -e --arg t "$t" '.[$t]' "$FIX/tags-${r//\//_}.json" > /dev/null 2>&1 || nf
                jq -c --arg t "$t" '.[$t]' "$FIX/tags-${r//\//_}.json" | jq -r "$(jqf)" ;;
            */git/tags/*) s="${2##*/}"
                jq -e --arg s "$s" '.[$s]' "$FIX/tagobjs.json" > /dev/null 2>&1 || nf
                jq -c --arg s "$s" '.[$s]' "$FIX/tagobjs.json" | jq -r "$(jqf)" ;;
            *) nf ;;
        esac ;;
    "issue list") cat "$FIX/issues.json" ;;
    "pr list") echo '[]' ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/gh"
echo '[]' > "$FIX/issues.json"
export PATH="$WORK/bin:$PATH" FIX

# ---- the fixture repository --------------------------------------------------------------------------
REPO="$WORK/repo"
mkdir -p "$REPO/upstream" "$REPO/.github/workflows" "$REPO/.github/actions/up"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
W=".github/workflows/ci.yml"; ACT=".github/actions/up/action.yml"
cat > "$REPO/$W" <<Y
name: ci
jobs:
  build:
    steps:
      - uses: example/act@$A1 # v1.0.0
      - name: restore
        uses: example/act/restore@$A1 # v1.0.0
      - uses: ./.github/actions/up
      - uses: docker://alpine:3
      - run: echo "uses: example/act@v1 is only text here"
      - uses: example/other@$O1   # v3.0.0
Y
cat > "$REPO/$ACT" <<Y
runs:
  using: composite
  steps:
    - uses: example/act@$A1 # v1.0.0
Y
cat > "$REPO/upstream/manifest.yaml" <<Y
repo: Sanne/incus-spawn
app:
  login: isx-upstream[bot]
items:
  actions:
    class: judgement
    judgement: build
    cooling: 72h
    source:
      type: github-action
      members:
        act: example/act
        other: example/other
    files: [$ACT, $W]
Y
cd "$REPO" && git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm fixture
E="$REPO/upstream/engine/upstream.sh"
BASE="$(git rev-parse HEAD)"
commit_from() {
    local start="$1" msg="$2"; shift 2
    local wt="$WORK/wt"; rm -rf "$wt"; git -C "$REPO" worktree prune; git -C "$REPO" worktree add -q --detach "$wt" "$start"
    ( cd "$wt" && "$@" && git add -A && git -c user.name=a -c user.email=a@a commit -qm "$msg" && git rev-parse HEAD )
}
CUR="act.v1.0.0_other.v3.0.0"

# ---- validate ----------------------------------------------------------------------------------------
"$E" validate > /dev/null || fail "validate refuses the fixture manifest"
bad_manifest() {
    local m="$WORK/bad.yaml"; yq "$1" "$REPO/upstream/manifest.yaml" > "$m"
    MANIFEST="$m" "$E" validate > "$WORK/bad.log" 2>&1 && fail "validate accepted a manifest with $2"
    return 0
}
bad_manifest '.items.actions.class = "mechanical"' "a mechanical family the app could not push"
bad_manifest '.items.actions.class = "review"' "a review family the app could not push"
bad_manifest '.items.actions.source.members.act = "evil"' "a member that is not owner/repo"
bad_manifest '.items.actions.source.members.Act = "example/act2"' "a member key that is not [a-z0-9-]"
bad_manifest '.items.actions.source.members.dup = "example/act"' "a repository that is a member twice"
bad_manifest '.items.actions.source.members.unused = "example/unused"' "a member no file uses"
bad_manifest 'del(.items.actions.source.members.other)' "a use of an action that is not a member"
bad_manifest '.items.actions.cooling = "soon"' "a cooling that is not a duration"
# bad_tree <sed expression> <what>: the tree's own pins break a rule.
bad_tree() {
    cp "$REPO/$W" "$WORK/w.bak"
    sed -i "$1" "$REPO/$W"
    "$E" validate > "$WORK/bad.log" 2>&1 && { cp "$WORK/w.bak" "$REPO/$W"; fail "validate accepted a workflow with $2"; }
    cp "$WORK/w.bak" "$REPO/$W"
}
bad_tree "s|example/other@$O1   # v3.0.0|example/other@v3|" "an action used by tag"
bad_tree "s|example/other@$O1   # v3.0.0|example/other@$O1|" "a commit with no tag comment"
bad_tree "s|example/other@$O1   # v3.0.0|example/other@$O1 # latest|" "a tag comment that is not a release tag"
bad_tree "s|restore@$A1 # v1.0.0|restore@$A11 # v1.1.0|" "one member at two commits"
bad_tree "s|- uses: docker://alpine:3|- uses: evil/thing@$A1 # v1.0.0|" "an action that is not a member"
grep -q 'not a member' "$WORK/bad.log" || fail "the non-member was not what failed: $(cat "$WORK/bad.log")"
echo "validate: ok"

# ---- current, check ----------------------------------------------------------------------------------
[ "$("$E" current actions)" = "$CUR" ] || fail "current reads $("$E" current actions), not $CUR"
"$E" check actions > /dev/null 2>&1 || fail "check fails on pins whose tags point at them (other's tag is annotated)"
echo "current, check: ok"

# ---- plan --------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
P="$(plan one)"
# The newest cooled release of example/act is 2.0.0 (1.2.0 is cooling, 1.3.0 a draft, 1.4.0 a prerelease).
grep -qP "^actions\tissue\tact.v2.0.0_other.v3.0.0\t" "$P/plan.tsv" || fail "plan did not open an issue for act 2.0.0: $(cat "$P/plan.tsv")"
I="$(ls "$P"/issues/actions-*.json)"
[ "$(jq -r .title "$I")" = "[upstream] actions: newer releases available" ] || fail "the issue is not under the family's title: $(jq -r .title "$I")"
jq -r .body "$I" | grep -qF '| `example/act` | v1.0.0 | v2.0.0 (new major) |' || fail "the issue does not list act's new major: $(jq -r .body "$I")"
jq -r .body "$I" | grep -qF "apply actions act.v1.1.0_other.v3.0.0" || fail "the issue does not give the in-major command: $(jq -r .body "$I")"
jq -r .body "$I" | grep -q 'example/other' && fail "the issue lists a member with no newer release"
# Moved past cooling, 2.0.0 out: the newest in-major is the target, and the body says nothing of a major.
releases example/act v1.0.0:$((90 * D)) v1.1.0:$((10 * D)) v1.2.0:3600
P="$(plan inmajor)"
grep -qP "^actions\tissue\tact.v1.1.0_other.v3.0.0\t" "$P/plan.tsv" || fail "plan did not propose act 1.1.0: $(cat "$P/plan.tsv")"
jq -r .body "$P"/issues/actions-*.json | grep -q 'new major' && fail "an in-major move is called a new major"
# A pin newer than every cooled release stays where it is: the target never goes back.
releases example/act v1.0.0:$((90 * D))
P="$(plan current)"
grep -qP "^actions\tnone\t$CUR\tcurrent$" "$P/plan.tsv" || fail "plan did not find the family current: $(cat "$P/plan.tsv")"
[ -z "$(ls "$P/issues")" ] || fail "a current family opened an issue: $(ls "$P/issues")"
releases example/act v1.0.0:$((90 * D)) v1.1.0:$((10 * D)) v1.2.0:3600 v2.0.0:$((5 * D))
echo "plan: ok"

# ---- a tag that moved ---------------------------------------------------------------------------------
cp "$FIX/tags-example_act.json" "$WORK/tags.bak"
tag example/act v1.0.0 "$(sha moved)"
"$E" check actions > "$WORK/moved.log" 2>&1 && fail "check passed a pin whose tag now points elsewhere"
P="$(plan moved)"
grep -qP "^actions\tissue\t$CUR\tpin mismatch$" "$P/plan.tsv" || fail "a moved tag is not a pin-mismatch issue: $(cat "$P/plan.tsv")"
cp "$WORK/tags.bak" "$FIX/tags-example_act.json"
echo "moved tag: ok"

# ---- a publisher that cannot be read -------------------------------------------------------------------
# A failed read while the pin is checked is a failing watch, never a tamper alarm (#2261).
touch "$FIX/fail-tag-read"
rc=0; "$E" plan "$WORK/plan-unreadable" > "$WORK/unreadable.log" 2>&1 || rc=$?
rm -f "$FIX/fail-tag-read"
[ "$rc" != 0 ] || fail "plan survived an unreadable publisher: $(cat "$WORK/plan-unreadable/plan.tsv")"
grep -q 'cannot derive' "$WORK/unreadable.log" || fail "an unreadable publisher was not said to be one: $(cat "$WORK/unreadable.log")"
! compgen -G "$WORK/plan-unreadable/issues/*" > /dev/null || fail "an unreadable publisher opened an issue"
echo "unreadable publisher: ok"

# ---- apply -------------------------------------------------------------------------------------------
cp -r "$REPO" "$WORK/applied"
"$E" apply actions act.v2.0.0_other.v3.0.0 "$WORK/applied" > /dev/null || fail "apply failed"
[ "$(grep -c "example/act[a-z/]*@$A2 # v2.0.0" "$WORK/applied/$W" "$WORK/applied/$ACT" | awk -F: '{ s += $2 } END { print s }')" = 3 ] \
    || fail "apply did not move every use of example/act: $(cat "$WORK/applied/$W" "$WORK/applied/$ACT")"
diff <(sed "s|$A1 # v1.0.0|$A2 # v2.0.0|" "$REPO/$W") "$WORK/applied/$W" > /dev/null || fail "apply changed something beside the pins"
for v in act.v1.0.0 act.v1.0.0_other.v3.0.0_x.v1.0.0 other.v3.0.0_act.v1.0.0 act.v9.9.9_other.v3.0.0 'act.v1.0.0_other.v3.0.0;rm'; do
    "$E" apply actions "$v" "$WORK/applied" > /dev/null 2>&1 && fail "apply accepted '$v'"
done
echo "apply: ok"

# ---- verify-trees --------------------------------------------------------------------------------------
verify() { "$E" verify-trees "$BASE" "$1" "$2" "${3:-no}" > "$WORK/verify.log" 2>&1; }
good="$(commit_from "$BASE" bump bash -c "\"\$0\" apply actions act.v1.1.0_other.v3.0.0 . > /dev/null" "$E")"
verify "$good" someone || { cat "$WORK/verify.log" >&2; fail "a person's bump to the tags' commits failed"; }
beside="$(commit_from "$BASE" beside sed -i 's/^name: ci$/name: ci2/' "$W")"
verify "$beside" someone || { cat "$WORK/verify.log" >&2; fail "an edit beside the pins failed"; }
grep -q "no watched item's pins changed" "$WORK/verify.log" || fail "an edit beside the pins asked GitHub: $(cat "$WORK/verify.log")"
wrong="$(commit_from "$BASE" wrong sed -i "s|$A1 # v1.0.0|$A12 # v1.1.0|" "$W" "$ACT")"
verify "$wrong" someone && fail "a commit its tag does not name passed"
bytag="$(commit_from "$BASE" bytag sed -i "s|example/other@$O1   # v3.0.0|example/other@v3|" "$W")"
verify "$bytag" someone && fail "an action used by tag passed"
grep -q 'not at a commit' "$WORK/verify.log" || fail "the tag was not what failed: $(cat "$WORK/verify.log")"
stranger="$(commit_from "$BASE" stranger sed -i "s|- uses: docker://alpine:3|- uses: evil/thing@$A1 # v1.0.0|" "$W")"
verify "$stranger" someone && fail "an action that is not a member passed"
verify "$good" "$APP_LOGIN" yes && fail "an app PR bumped a judgement family"
grep -q "class judgement, so no PR of the app" "$WORK/verify.log" || fail "the class was not what failed: $(cat "$WORK/verify.log")"
echo "verify: ok"
echo "test-github-action: ok"
