#!/usr/bin/env bash
# The maven-central source type (#2210) on a fixture repository with Maven Central faked: curl serves
# each artifact's listing, poms and their .sha1 by URL, gh answers the issue and PR queries from
# fixtures. Checks that validate holds the type's rules (class review or judgement, never mechanical;
# groupId:artifactId members; a pin in one of the item's files; a property that versions an artifact
# the member does not declare; a pin found another number of times); that check passes the pins and,
# re-hashing, fails a pom whose sha1 Central does not list; that plan proposes, for a family, each
# member's newest cooled release within its major (a qualifier like -M1 never counts, nor a version
# one of a member's artifacts lacks) and opens the new-series issue for a new major beside that PR,
# and makes a single item wait while its release cools; that apply rewrites every pin byte for byte
# and refuses to move a member back; and that verify-trees passes the engine's own regeneration, fails
# an app PR that moves two items or a version Central does not list, and passes a person's edit
# beside the pins without asking Central. Needs bash, git, jq, yq, perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX/http" "$WORK/bin"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x
NOW="$(date -u +%s)"
when() { date -u -d "@$((NOW - $1))" '+%Y-%m-%d %H:%M'; }
http() { printf '%s' "$1" | tr '/?&=:' '_____'; }
D=86400

# ---- Maven Central -----------------------------------------------------------------------------------
C=https://repo.maven.apache.org/maven2/org/example
# central <artifact> <version@age>...: the artifact's listing, and a pom with its .sha1 per version.
central() {
    local a="$1" e v f; shift
    f="$WORK/listing-$a.html"
    printf '<html><body><pre id="contents">\n<a href="../">../</a>\n' > "$f"
    for e in "$@"; do
        v="${e%@*}"
        printf '<a href="%s/" title="%s/">%s/</a>        %s         -\n' "$v" "$v" "$v" "$(when "${e#*@}")" >> "$f"
        printf '<project><artifactId>%s</artifactId><version>%s</version></project>\n' "$a" "$v" > "$FIX/http/$(http "$C/$a/$v/$a-$v.pom")"
        sha1sum "$FIX/http/$(http "$C/$a/$v/$a-$v.pom")" | cut -d' ' -f1 > "$FIX/http/$(http "$C/$a/$v/$a-$v.pom.sha1")"
    done
    printf '<a href="maven-metadata.xml" title="maven-metadata.xml">maven-metadata.xml</a>   %s   412\n</pre></body></html>\n' "$(when 0)" >> "$f"
    cp "$f" "$FIX/http/$(http "$C/$a/")"
}
# ex-plugin: 1.1.0 is the newest cooled in its major (1.2.0 still cools, 1.3.0-M1 is no release), and
# 2.0.0 a cooled new major. twin-a and twin-b are one member: 3.1.0 exists for twin-a only.
central ex-plugin 1.0.0@$((90 * D)) 1.1.0@$((10 * D)) 1.2.0@3600 1.3.0-M1@$((9 * D)) 2.0.0@$((5 * D))
central twin-a 3.0.0@$((90 * D)) 3.0.1@$((10 * D)) 3.1.0@$((9 * D))
central twin-b 3.0.0@$((90 * D)) 3.0.1@$((10 * D))
central lib 0.5.0@$((90 * D)) 0.6.0@3600

cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; -*) ;; *) url="$1" ;; esac; shift; done
echo "$url" >> "$FIX/requests"
f="$FIX/http/$(printf '%s' "$url" | tr '/?&=:' '_____')"
[ -f "$f" ] || { echo "curl: (22) no fixture for $url" >&2; exit 22; }
cp "$f" "$out"
FAKE
cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
case "$1 $2" in
    "issue list") cat "$FIX/issues.json" ;;
    "pr list") echo '[]' ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/curl" "$WORK/bin/gh"
echo '[]' > "$FIX/issues.json"
export PATH="$WORK/bin:$PATH" FIX

# ---- the fixture repository --------------------------------------------------------------------------
REPO="$WORK/repo"
mkdir -p "$REPO/upstream" "$REPO/sub" "$REPO/other"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
cat > "$REPO/pom.xml" <<'Y'
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <properties>
        <twin.version>3.0.0</twin.version>
        <lib.version>0.5.0</lib.version>
    </properties>
</project>
Y
cat > "$REPO/sub/pom.xml" <<'Y'
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <dependencies>
        <dependency>
            <groupId>org.example</groupId>
            <artifactId>lib</artifactId>
            <version>${lib.version}</version>
        </dependency>
        <dependency>
            <groupId>org.example</groupId>
            <artifactId>twin-a</artifactId>
            <version>${twin.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.example</groupId>
            <artifactId>twin-b</artifactId>
            <version>${twin.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.example</groupId>
                <artifactId>ex-plugin</artifactId>
                <version>1.0.0</version>
            </plugin>
        </plugins>
    </build>
</project>
Y
cat > "$REPO/other/pom.xml" <<'Y'
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <build>
        <plugins>
            <plugin>
                <groupId>org.example</groupId>
                <artifactId>ex-plugin</artifactId>
                <version>1.0.0</version>
            </plugin>
        </plugins>
    </build>
</project>
Y
cat > "$REPO/upstream/manifest.yaml" <<'Y'
repo: Sanne/incus-spawn
app:
  login: isx-upstream[bot]
items:
  plugins:
    class: review
    within: series
    cooling: 72h
    judgement: build
    source:
      type: maven-central
      members:
        ex:
          artifacts: [org.example:ex-plugin]
          pins:
            - file: sub/pom.xml
              text: |-
                <artifactId>ex-plugin</artifactId>
                                <version>{version}</version>
            - file: other/pom.xml
              text: |-
                <artifactId>ex-plugin</artifactId>
                                <version>{version}</version>
        twin:
          artifacts: [org.example:twin-a, org.example:twin-b]
          pins:
            - file: pom.xml
              text: '<twin.version>{version}</twin.version>'
    verify:
      type: central-exists
    files: [pom.xml, sub/pom.xml, other/pom.xml]
    fields: [ex.version, twin.version]
  lib:
    class: review
    within: series
    cooling: 72h
    judgement: build
    source:
      type: maven-central
      members:
        lib:
          artifacts: [org.example:lib]
          pins:
            - file: pom.xml
              text: '<lib.version>{version}</lib.version>'
    verify:
      type: central-exists
    files: [pom.xml, sub/pom.xml]
    fields: [lib.version]
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
    MANIFEST="$m" "$E" validate > "$WORK/bad.log" 2>&1 && fail "validate accepted a manifest with $2"
    [[ -z "${3:-}" ]] || grep -q "$3" "$WORK/bad.log" || fail "$2 was not what failed: $(cat "$WORK/bad.log")"
}
bad_manifest '.items.lib.class = "mechanical"' "a mechanical item on Central's record alone" "is not signed-sums, signed-file or publisher-sums"
bad_manifest '.items.plugins.source.members.ex.artifacts = ["ex-plugin"]' "an artifact that is not groupId:artifactId" "is not groupId:artifactId"
bad_manifest '.items.plugins.source.members.twin.artifacts = ["org.example:twin-a"]' "a property that versions an undeclared artifact" "also versions org.example:twin-b"
bad_manifest '.items.plugins.source.members.dup = .items.plugins.source.members.ex' "an artifact in two members" "belongs to two members"
bad_manifest '.items.plugins.source.members.ex.pins[0].file = "README"' "a pin outside the item's files" "not one of the item's files"
bad_manifest '.items.plugins.source.members.ex.pins[0].count = 2' "a pin found once where the manifest says twice" "not 2"
bad_manifest '.items.plugins.source.members.ex.pins[0].text = "<artifactId>ex-plugin</artifactId>"' "a pin with no {version}" "has no {version}"
bad_manifest '.items.plugins.fields = ["ex.version"]' "fields other than the source reads" "fields differ"
echo "validate: ok"

# ---- current, check ----------------------------------------------------------------------------------
[ "$("$E" current plugins)" = "ex.1.0.0_twin.3.0.0" ] || fail "current reads '$("$E" current plugins)'"
[ "$("$E" current lib)" = "0.5.0" ] || fail "current reads '$("$E" current lib)' for lib"
"$E" check plugins > /dev/null 2>&1 || fail "check fails on pins Central lists"
good_pom="$FIX/http/$(http "$C/twin-b/3.0.0/twin-b-3.0.0.pom")"; cp "$good_pom" "$WORK/pom.bak"
echo tampered >> "$good_pom"
UPSTREAM_REHASH=1 "$E" check plugins > "$WORK/check.log" 2>&1 && fail "check passed a pom that does not hash to Central's sha1"
cp "$WORK/pom.bak" "$good_pom"
# A listing Central does not serve (a 429 that outlasted fetch's retries) fails with why, never with a
# path the lost download left empty.
mv "$FIX/http/$(http "$C/lib/")" "$WORK/listing.bak"
"$E" check lib > "$WORK/check.log" 2>&1 && fail "check passed with no listing of lib"
grep -qF "failed to download $C/lib/" "$WORK/check.log" || fail "the missing listing was not what failed: $(cat "$WORK/check.log")"
! grep -qE "cannot read|lists no release" "$WORK/check.log" || fail "a failed listing read as an empty one: $(cat "$WORK/check.log")"
mv "$WORK/listing.bak" "$FIX/http/$(http "$C/lib/")"
echo "current, check: ok"

# ---- plan --------------------------------------------------------------------------------------------
P="$WORK/plan"
"$E" plan "$P" > "$WORK/plan.log" 2>&1 || { cat "$WORK/plan.log" >&2; fail "plan failed"; }
# ex: 1.1.0 within its major (1.2.0 cools, 1.3.0-M1 is no release); twin: 3.0.1, as twin-b has no 3.1.0.
grep -qP "^plugins\tpr\tex.1.1.0_twin.3.0.1\t" "$P/plan.tsv" || fail "plan did not propose ex 1.1.0 and twin 3.0.1: $(cat "$P/plan.tsv")"
[ -f "$P/issues/plugins-ex.2_twin.3.json" ] || fail "the new major opened no series issue beside the PR: $(ls "$P/issues")"
grep -qP "^lib\twait\t0.6.0\t" "$P/plan.tsv" || fail "lib did not wait for 0.6.0 to cool: $(cat "$P/plan.tsv")"
pc="$(jq -r .commit "$P/plugins.json")"
[ "$(git -C "$REPO" diff --name-only "$BASE" "$pc" | sort | tr '\n' ' ')" = "other/pom.xml pom.xml sub/pom.xml " ] || fail "the bump touches other files"
diff <(git -C "$REPO" show "$BASE:sub/pom.xml" | sed 's|<version>1.0.0</version>|<version>1.1.0</version>|') <(git -C "$REPO" show "$pc:sub/pom.xml") > /dev/null \
    || fail "the bump changed more of sub/pom.xml than the plugin's version"
diff <(git -C "$REPO" show "$BASE:pom.xml" | sed 's|<twin.version>3.0.0<|<twin.version>3.0.1<|') <(git -C "$REPO" show "$pc:pom.xml") > /dev/null \
    || fail "the bump changed more of pom.xml than the twin property"
jq -r .body "$P/plugins.json" | grep -qF '| `org.example:twin-b` | 3.0.1 |' || fail "the PR body does not list twin-b: $(jq -r .body "$P/plugins.json")"
echo "plan: ok"

# ---- apply -------------------------------------------------------------------------------------------
cp -r "$REPO" "$WORK/applied"
"$E" apply plugins ex.1.0.0_twin.3.0.1 "$WORK/applied" > /dev/null || fail "apply failed"
"$E" apply plugins ex.1.0.0_twin.3.0.0 "$WORK/applied" > /dev/null 2>&1 && fail "apply moved twin back"
for v in 3.0.1 twin.3.0.1_ex.1.0.0 ex.1.0.0_twin.3.1.0 ex.1.3.0-M1_twin.3.0.1 'ex.1.0.0_twin.3.0.1;rm'; do
    "$E" apply plugins "$v" "$WORK/applied" > /dev/null 2>&1 && fail "apply accepted '$v'"
done
echo "apply: ok"

# ---- verify-trees ------------------------------------------------------------------------------------
verify() { "$E" verify-trees "$BASE" "$1" "$2" "$3" > "$WORK/verify.log" 2>&1; }
verify "$pc" "$APP_LOGIN" yes || { cat "$WORK/verify.log" >&2; fail "the engine's own family bump failed verification"; }
central lib 0.5.0@$((90 * D)) 0.6.0@$((5 * D))
both="$(commit_from "$pc" both sed -i 's|<lib.version>0.5.0<|<lib.version>0.6.0<|' pom.xml)"
verify "$both" "$APP_LOGIN" yes && fail "an app PR bumping two items passed"
grep -q "one item" "$WORK/verify.log" || fail "the two items were not what failed: $(cat "$WORK/verify.log")"
nope="$(commit_from "$BASE" nope sed -i 's|<lib.version>0.5.0<|<lib.version>0.7.0<|' pom.xml)"
verify "$nope" someone yes && fail "a person's bump to a version Central does not list passed"
verify "$pc" someone yes || { cat "$WORK/verify.log" >&2; fail "a person's bump to versions Central lists failed"; }
: > "$FIX/requests"
edit="$(commit_from "$BASE" edit sed -i 's|<scope>test</scope>|<scope>test</scope><!-- edited -->|' sub/pom.xml)"
verify "$edit" someone yes || { cat "$WORK/verify.log" >&2; fail "an edit beside the pins failed"; }
grep -q "no watched item's pins changed" "$WORK/verify.log" || fail "the edit beside the pins was not recognised: $(cat "$WORK/verify.log")"
[ ! -s "$FIX/requests" ] || fail "an edit beside the pins asked Central: $(cat "$FIX/requests")"
echo "verify: ok"
echo "test-maven-central: ok"
