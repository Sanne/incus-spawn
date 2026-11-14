#!/usr/bin/env bash
# The github-asset source type (#2210) on a fixture repository with GitHub faked: curl serves each
# release asset and its .sha256 by URL, gh answers the releases queries from fixtures. Checks that
# validate holds the type's rules (a GitHub digest is for a review item only, a mechanical item needs
# a publisher verifier); that check passes a pin the release confirms; that plan proposes the newest
# cooled release of the pinned major as a PR (a review item's says the bot leaves it), opens an issue
# for a new major, skips a draft, a prerelease, a release missing an asset and one still cooling, and
# fails closed on a sum the asset does not have; and that verify-trees passes exactly the engine's
# regeneration of an app PR for each of two items sharing one file, and fails a tampered sum, a
# change to both items at once and a link off the release. Needs bash, git, jq, yq, curl faked, perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX/http" "$WORK/bin" "$WORK/art"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x
NOW="$(date -u +%s)"
ts() { date -u -d "@$((NOW - $1))" +%FT%TZ; }
http() { printf '%s' "$1" | tr '/?&=:' '_____'; }
sumof() { sha256sum "$1" | cut -d' ' -f1; }

# ---- the releases --------------------------------------------------------------------------------------
# mk_asset <repo> <tag> <name> <sums: file|digest|none>: serves the asset; prints its asset JSON.
mk_asset() {
    local repo="$1" tag="$2" name="$3" kind="$4" f="$WORK/art/$2-$3" url sum
    url="https://github.com/$repo/releases/download/$tag/$name"
    printf 'asset %s %s\n' "$tag" "$name" > "$f"
    sum="$(sumof "$f")"
    cp "$f" "$FIX/http/$(http "$url")"
    [[ "$kind" == file ]] && printf '%s  %s\n' "$sum" "$name" > "$FIX/http/$(http "$url.sha256")"
    jq -n --arg n "$name" --arg u "$url" --arg d "sha256:$sum" '{name: $n, digest: $d, browser_download_url: $u}'
    if [[ "$kind" == file ]]; then
        jq -n --arg n "$name.sha256" --arg u "$url.sha256" '{name: $n, digest: "sha256:0", browser_download_url: $u}'
    fi
}
# release <tag> <age seconds> <draft> <prerelease> <assets json...>
release() {
    local tag="$1" age="$2" draft="$3" pre="$4"; shift 4
    printf '%s\n' "$@" | jq -s --arg t "$tag" --arg at "$(ts "$age")" --argjson d "$draft" --argjson p "$pre" \
        '{tag_name: $t, draft: $d, prerelease: $p, published_at: $at, assets: .}'
}
star() { # <version> <age> [draft] [pre]: both Linux assets
    release "v$1" "$2" "${3:-false}" "${4:-false}" \
        "$(mk_asset starship/starship "v$1" starship-x86_64-unknown-linux-gnu.tar.gz file)" \
        "$(mk_asset starship/starship "v$1" starship-aarch64-unknown-linux-musl.tar.gz file)"
}
S="starship-x86_64-unknown-linux-gnu.tar.gz"
{
    star 1.26.0 $((90 * 86400)); star 1.27.0 $((10 * 86400)); star 1.28.0 3600
    star 1.27.1 $((9 * 86400)) true false; star 1.29.0 $((9 * 86400)) false true; star 2.0.0 $((9 * 86400))
    # 1.27.2 lacks the aarch64 asset, so it is not a release of this item
    release v1.27.2 $((9 * 86400)) false false "$(mk_asset starship/starship v1.27.2 "$S" file)"
} | jq -s . > "$FIX/rel-starship_starship.json"
plug() { release "$1" "$2" false false "$(mk_asset hechtcarmel/jetbrains-index-mcp-plugin "$1" "jetbrains-index-mcp-plugin-$1-signed.zip" digest)"; }
{ plug 5.20.3 $((30 * 86400)); plug 5.20.4 $((20 * 86400)); plug 5.20.5 $((10 * 86400)); } | jq -s . > "$FIX/rel-hechtcarmel_jetbrains-index-mcp-plugin.json"
oth() { release "v$1" "$2" false false "$(mk_asset example/other "v$1" "other-$1.zip" file)"; }
{ oth 1.0.0 $((30 * 86400)); oth 1.1.0 $((10 * 86400)); } | jq -s . > "$FIX/rel-example_other.json"

# ---- fakes -------------------------------------------------------------------------------------------
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
echo "gh $(printf "%s" "$*" | tr "\n" " ")" >> "$CALLS"
args=("$@")
jqf() { local i; for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = --jq ] && echo "${args[i+1]}"; done; }
case "$1 $2" in
    "auth setup-git") exit 0 ;;
    "api --paginate") r="${3#repos/}"; r="${r%/releases}"; jq -c "$(jqf)" "$FIX/rel-${r//\//_}.json" ;;
    "api repos/"*)
        case "$2" in
            */releases/tags/*) r="${2#repos/}"; r="${r%%/releases/*}"; tag="${2##*/}"
                jq -c --arg t "$tag" '[.[] | select(.tag_name == $t)][0]' "$FIX/rel-${r//\//_}.json" | jq -r "$(jqf)" ;;
            */compare/*) echo "deadbeef 0" ;;
        esac ;;
    "issue list") cat "$FIX/issues.json" ;;
    "pr list") echo '[]' ;;
    "issue create")
        title=""; for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = --title ] && title="${args[i+1]}"; done
        n=$(( $(jq length "$FIX/issues.json") + 1 ))
        jq --arg t "$title" --argjson n "$n" '. + [{number: $n, title: $t}]' "$FIX/issues.json" > "$FIX/issues.tmp" && mv "$FIX/issues.tmp" "$FIX/issues.json"
        echo "https://github.com/x/y/issues/$n" ;;
    "issue close"|"issue comment") ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/curl" "$WORK/bin/gh"
echo '[]' > "$FIX/issues.json"
export PATH="$WORK/bin:$PATH" FIX CALLS="$WORK/calls"; : > "$CALLS"

# ---- the fixture repository --------------------------------------------------------------------------
REPO="$WORK/repo"
mkdir -p "$REPO/upstream" "$REPO/common/src/main/resources/tools" "$REPO/.github/scripts" "$REPO/appliance/kernel"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
cp "$SRC/.github/scripts/base-image-sums.sh" "$REPO/.github/scripts/"
T="common/src/main/resources/tools"
sum_of_asset() { sumof "$WORK/art/$1-$2"; }
cat > "$REPO/$T/starship.yaml" <<Y
name: starship
description: Starship prompt 1.26.0 with an indicator
downloads:
  - url: https://github.com/starship/starship/releases/download/v1.26.0/starship-x86_64-unknown-linux-gnu.tar.gz
    sha256: $(sum_of_asset v1.26.0 starship-x86_64-unknown-linux-gnu.tar.gz)
    extract: /tmp/starship
    arch: x86_64
  - url: https://github.com/starship/starship/releases/download/v1.26.0/starship-aarch64-unknown-linux-musl.tar.gz
    sha256: $(sum_of_asset v1.26.0 starship-aarch64-unknown-linux-musl.tar.gz)
    extract: /tmp/starship
    arch: aarch64
Y
PNAME="jetbrains-index-mcp-plugin"
cat > "$REPO/$T/idea-backend.yaml" <<Y
name: idea-backend
downloads:
  - url: https://github.com/hechtcarmel/$PNAME/releases/download/5.20.3/$PNAME-5.20.3-signed.zip
    sha256: $(sum_of_asset 5.20.3 "$PNAME-5.20.3-signed.zip")
    destination_file: /opt/index-mcp-plugin.zip
  - url: https://github.com/example/other/releases/download/v1.0.0/other-1.0.0.zip
    sha256: $(sum_of_asset v1.0.0 other-1.0.0.zip)
    destination_file: /opt/other.zip
Y
cat > "$REPO/upstream/manifest.yaml" <<Y
repo: Sanne/incus-spawn
app:
  login: isx-upstream[bot]
items:
  starship:
    class: mechanical
    within: series
    cooling: 48h
    judgement: build
    source:
      type: github-asset
      repo: starship/starship
      tag-prefix: v
      sums: sha256-asset
      versioned-description: true
      assets:
        x86_64: starship-x86_64-unknown-linux-gnu.tar.gz
        aarch64: starship-aarch64-unknown-linux-musl.tar.gz
    verify:
      type: publisher-sums
    files: [$T/starship.yaml]
    fields: [description, url.x86_64, sha256.x86_64, url.aarch64, sha256.aarch64]
  idea-index-plugin:
    class: review
    within: series
    cooling: 48h
    judgement: build
    source:
      type: github-asset
      repo: hechtcarmel/$PNAME
      tag-prefix: ""
      sums: github-digest
      assets:
        any: $PNAME-{version}-signed.zip
    verify:
      type: asset-digest
    files: [$T/idea-backend.yaml]
    fields: [url.any, sha256.any]
  other:
    class: review
    within: series
    cooling: 48h
    judgement: build
    source:
      type: github-asset
      repo: example/other
      tag-prefix: v
      sums: sha256-asset
      assets:
        any: other-{version}.zip
    verify:
      type: publisher-sums
    files: [$T/idea-backend.yaml]
    fields: [url.any, sha256.any]
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
bad_manifest '.items.starship.source.sums = "github-digest"' "a GitHub digest on a mechanical item"
bad_manifest '.items.starship.verify.type = "asset-digest"' "a verify.type the source does not provide"
bad_manifest '.items.idea-index-plugin.class = "mechanical"' "a mechanical item resting on a GitHub digest"
bad_manifest '.items.starship.source.sums = "trust-me"' "an unknown sums kind"
bad_manifest '.items.starship.source.repo = "evil"' "a repo that is not owner/name"
bad_manifest '.items.starship.source.assets.x86_64 = "../starship.tar.gz"' "an asset that is not a file name"
bad_manifest '.items.starship.source.assets.x86_64 = "starship-{arch}.tar.gz"' "an unknown placeholder"
bad_manifest 'del(.items.starship.source.assets)' "no assets"
bad_manifest '.items.starship.source.assets.riscv = "starship-riscv.tar.gz"' "an asset the YAML has no download for"
bad_manifest '.items.starship.fields = ["url.x86_64"]' "fields that drift from the source"
bad_manifest '.items.starship.cooling = "1h"' "cooling under the floor"
echo "validate: ok"

# ---- check -------------------------------------------------------------------------------------------
for i in starship idea-index-plugin other; do "$E" check "$i" > /dev/null || fail "check $i fails on a pin the release confirms"; done
echo "check: ok"

# ---- plan --------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
action() { awk -F'\t' -v i="$2" '$1 == i { print $2 }' "$1/plan.tsv"; }
version() { awk -F'\t' -v i="$2" '$1 == i { print $3 }' "$1/plan.tsv"; }
P="$(plan one)"
# 1.28.0 is the newest of the major and is cooling; 1.27.1 is a draft, 1.29.0 a prerelease, 1.27.2
# lacks an asset and 2.0.0 is a new major, so none of them is it
[ "$(action "$P" starship)" = wait ] && [ "$(version "$P" starship)" = 1.28.0 ] || fail "starship: expected to wait for 1.28.0: $(cat "$P/plan.tsv")"
ls "$P/issues/" | grep -q '^starship-2.json$' || fail "no issue for starship's new major: $(ls "$P/issues")"
[ ! -f "$P/starship.json" ] || fail "a release inside its cooling period got a commit"
cp "$FIX/rel-starship_starship.json" "$WORK/rel.bak"
jq '(.[] | select(.tag_name == "v1.28.0") | .published_at) = "'"$(ts $((10 * 86400)))"'"' "$WORK/rel.bak" > "$FIX/rel-starship_starship.json"
P="$(plan cooled)"
[ "$(action "$P" starship)" = pr ] && [ "$(version "$P" starship)" = 1.28.0 ] || fail "starship: expected a PR for 1.28.0 once cooled: $(cat "$P/plan.tsv")"
[ "$(action "$P" idea-index-plugin)" = pr ] && [ "$(version "$P" idea-index-plugin)" = 5.20.5 ] || fail "plugin: expected a PR for 5.20.5: $(cat "$P/plan.tsv")"
[ "$(action "$P" other)" = pr ] && [ "$(version "$P" other)" = 1.1.0 ] || fail "other: expected a PR for 1.1.0: $(cat "$P/plan.tsv")"
for i in starship idea-index-plugin other; do
    c="$(jq -r .commit "$P/$i.json")"
    [ "$(git -C "$REPO" log -1 --format=%ae "$c")" = "$APP_EMAIL" ] || fail "$i: the bump is not committed as the app"
    [ "$(git -C "$REPO" diff --name-only "$BASE" "$c")" = "$(yq -r ".items[\"$i\"].files[0]" "$REPO/upstream/manifest.yaml")" ] || fail "$i: the bump touches more than the item's file"
done
sc="$(jq -r .commit "$P/starship.json")"
git -C "$REPO" show "$sc:$T/starship.yaml" > "$WORK/star-new.yaml"
grep -q '^description: Starship prompt 1.28.0 with an indicator$' "$WORK/star-new.yaml" || fail "the description kept the old version"
grep -q "releases/download/v1.28.0/starship-aarch64-unknown-linux-musl.tar.gz" "$WORK/star-new.yaml" || fail "the aarch64 URL was not moved"
grep -q "$(sum_of_asset v1.28.0 starship-x86_64-unknown-linux-gnu.tar.gz)" "$WORK/star-new.yaml" || fail "the x86_64 sum is not the release's"
jq -r .body "$P/idea-index-plugin.json" | grep -q 'the bot never approves or merges it' || fail "the review item's PR does not say the bot leaves it"
jq -r .body "$P/idea-index-plugin.json" | grep -q "GitHub recorded" || fail "the plugin's PR does not say its sum is GitHub's record"
jq -r .body "$P/starship.json" | grep -q 'only then may the bot approve and merge it' || fail "starship's PR lost its merge sentence"
echo "plan: ok"

# ---- plan fails closed -------------------------------------------------------------------------------
x="https://github.com/starship/starship/releases/download/v1.28.0/$S"
cp "$FIX/http/$(http "$x.sha256")" "$WORK/sha.bak"
printf '%s  %s\n' "$(printf wrong | sha256sum | cut -d' ' -f1)" "$S" > "$FIX/http/$(http "$x.sha256")"
"$E" plan "$WORK/plan-bad" > "$WORK/bad.log" 2>&1 && fail "a sum the asset does not have was accepted"
grep -q 'hashes to' "$WORK/bad.log" || fail "the mismatch was not what failed: $(cat "$WORK/bad.log")"
cp "$WORK/sha.bak" "$FIX/http/$(http "$x.sha256")"
# a link off the release: GitHub lists the asset elsewhere
cp "$FIX/rel-starship_starship.json" "$WORK/rel.bak"
jq '(.[] | select(.tag_name == "v1.28.0") | .assets[0].browser_download_url) = "https://evil.example/x.tar.gz"' "$WORK/rel.bak" > "$FIX/rel-starship_starship.json"
"$E" plan "$WORK/plan-link" > "$WORK/link.log" 2>&1 && fail "an asset listed off the release was accepted"
cp "$WORK/rel.bak" "$FIX/rel-starship_starship.json"
# the plugin's digest is not the asset's hash
cp "$FIX/rel-hechtcarmel_$PNAME.json" "$WORK/prel.bak"
jq '(.[] | select(.tag_name == "5.20.5") | .assets[0].digest) = "sha256:\("0" * 64)"' "$WORK/prel.bak" > "$FIX/rel-hechtcarmel_$PNAME.json"
"$E" plan "$WORK/plan-digest" > "$WORK/digest.log" 2>&1 && fail "a digest the asset does not have was accepted"
cp "$WORK/prel.bak" "$FIX/rel-hechtcarmel_$PNAME.json"
echo "plan fail-closed: ok"

# ---- verify-trees ------------------------------------------------------------------------------------
verify() { "$E" verify-trees "$BASE" "$1" "$2" "$3" > "$WORK/verify.log" 2>&1; }
P="$(plan verify)"
for i in starship idea-index-plugin other; do
    verify "$(jq -r .commit "$P/$i.json")" "$APP_LOGIN" yes || { cat "$WORK/verify.log" >&2; fail "the engine's own $i bump failed verification"; }
done
PC="$(jq -r .commit "$P/idea-index-plugin.json")"
bad="$(commit_from "$PC" bad sed -i "s/^    sha256: $(sum_of_asset 5.20.5 "$PNAME-5.20.5-signed.zip")\$/    sha256: $(printf wrong | sha256sum | cut -d' ' -f1)/" "$T/idea-backend.yaml")"
verify "$bad" "$APP_LOGIN" yes && fail "an app PR for the plugin with a tampered sum passed"
grep -q "is not main's engine's regeneration\|hashes to" "$WORK/verify.log" || fail "the tampered sum was not what failed: $(cat "$WORK/verify.log")"
OC="$(jq -r .commit "$P/other.json")"
# the merge of both bumps in one file: two items change at once
both="$(commit_from "$BASE" both bash -c "perl -0pi -e 's/5\\.20\\.3/5.20.5/g' $T/idea-backend.yaml; sed -i 's|v1\\.0\\.0|v1.1.0|; s|other-1\\.0\\.0|other-1.1.0|' $T/idea-backend.yaml")"
verify "$both" "$APP_LOGIN" yes && fail "an app PR bumping two items passed"
grep -q "changes one item" "$WORK/verify.log" || fail "two items was not what failed: $(cat "$WORK/verify.log")"
verify "$PC" "someone" yes || { cat "$WORK/verify.log" >&2; fail "a person's bump with the release's values failed"; }
echo "verify: ok"
echo "test-github-asset: ok"
