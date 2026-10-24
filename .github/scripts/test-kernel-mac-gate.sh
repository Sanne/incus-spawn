#!/bin/bash
# Runs the kernel-mac-verified gate (kernel-mac-gate.sh) and the Mac's record of a boot
# (appliance/test-kernel-on-mac.sh) offline, on a fixture repository with gh faked: the fake
# applies each --jq filter to fixture JSON, so the filters are tested too (#1355). Checks that the
# gate passes a PR whose merge leaves the appliance kernel's inputs alone, and otherwise only a
# verifier's newest success for the merge's fingerprint, on the head or a commit a verifier's
# record names; that a stale merge, an unclassified file in appliance/kernel/ and an API error
# fail; and that the record carries what the build says it built and marks a failed boot or the
# wrong kernel as failure. Needs bash, git and jq.
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir -p "$WORK/bin"
# Answers `api repos/O/R/<path>` from $FIX/<path, / as _>.json through the caller's --jq ([] when
# there is no fixture; $FIX/<same>.err makes it fail with that text), and the other calls the
# scripts make from $FIX. Every call is recorded in $CALLS.
cat > "$WORK/bin/gh" <<'EOF'
#!/bin/bash
echo "gh $(printf '%s' "$*" | tr '\n' ' ')" >> "$CALLS"
jqf=""; args=()
while [ $# -gt 0 ]; do case "$1" in --jq) jqf=$2; shift ;; --paginate) ;; *) args+=("$1") ;; esac; shift; done
set -- "${args[@]}"
case "$1 ${2:-}" in
    "api repos/"*)
        p="${2#repos/*/*/}"; f="$FIX/$(echo "${p%%\?*}" | tr / _)"
        [ -f "$f.err" ] && { cat "$f.err" >&2; exit 1; }
        { [ -f "$f.json" ] && cat "$f.json" || echo '[]'; } | jq -r "$jqf" ;;
    "api -X") ;;
    "pr view") jq -r "$jqf" "$FIX/pr.json" ;;
    "run list") w=""; for a in "$@"; do case "$a" in *.yml) w=$a ;; esac; done; jq -r "$jqf" "$FIX/runs-$w.json" ;;
    "run download") d=""; while [ $# -gt 0 ]; do [ "$1" = -D ] && d=$2; shift; done; cp -r "$FIX/artifact/." "$d/" ;;
    "pr comment") echo "https://github.com/Sanne/incus-spawn/pull/9#issuecomment-1" ;;
    "run rerun") ;;
    *) echo "unexpected: gh $*" >&2; exit 2 ;;
esac
EOF
chmod +x "$WORK/bin/gh"

# The fixture: main at BASE, PR heads branching from it, and merges as GitHub makes them.
SRC="$WORK/src"
mkdir -p "$SRC/appliance/kernel" "$SRC/.github/scripts"
cp "$REPO_DIR/appliance/kernel/kernel-fingerprint.sh" "$SRC/appliance/kernel/"
cp "$REPO_DIR/.github/scripts/kernel-mac-gate.sh" "$SRC/.github/scripts/"
for f in isx.config isx-x86_64.config isx-aarch64.config vm.config update-kernel-pin.sh; do echo "# $f" > "$SRC/appliance/kernel/$f"; done
echo "# build" > "$SRC/appliance/build.sh"
printf 'KERNEL_VERSION="7.2.9"\n' > "$SRC/appliance/kernel/build-kernel.sh"
git -C "$SRC" init -q -b main
git init -q --bare "$WORK/origin.git"
git -C "$SRC" remote add origin "$WORK/origin.git"
G() { git -C "$SRC" -c user.name=t -c user.email=t@t "$@"; }
commit() { G add -A && G commit -qm "$1" && G rev-parse HEAD; }
BASE=$(commit base)
# branch <from> <file> <line>: a commit on <from> appending <line> to <file>
branch() { G checkout -q "$1" && echo "$3" >> "$SRC/$2" && commit "change $2"; }
# pin <from> <version>: a commit on <from> moving the pin
pin() { G checkout -q "$1" && printf 'KERNEL_VERSION="%s"\n' "$2" > "$SRC/appliance/kernel/build-kernel.sh" && commit "pin $2"; }
# merge <base> <head>: the merge GitHub would make, base first
merge() { G checkout -q "$1" && G merge -q --no-ff --no-edit "$2" > /dev/null && G rev-parse HEAD; }

VM_ONLY=$(branch "$BASE" appliance/kernel/vm.config CONFIG_X=y)
BUMP=$(pin "$BASE" 7.2.10)
BUILD_SH=$(branch "$BASE" appliance/build.sh "# another compiler")
UNCLASSIFIED=$(branch "$BASE" appliance/kernel/README "# docs")
REBASED=$(branch "$BUMP" elsewhere "# unrelated")
MOVED=$(branch "$BASE" appliance/kernel/isx.config CONFIG_Y=y)
OTHER=$(branch "$BASE" elsewhere "# not the kernel")
fp() { (cd "$SRC" && appliance/kernel/kernel-fingerprint.sh "$1"); }
M_BUMP=$(merge "$BASE" "$BUMP")
FP=$(fp "$M_BUMP")
[ "$FP" = "$(fp "$(merge "$BASE" "$REBASED")")" ] || fail "an unrelated change moved the fingerprint"
[ "$(fp "$BASE")" = "$(fp "$VM_ONLY")" ] || fail "a vm fragment moved the appliance kernel's fingerprint"
[ "$FP" != "$(fp "$BASE")" ] || fail "a version bump did not move the fingerprint"
[ "$(fp "$BASE")" != "$(fp "$BUILD_SH")" ] || fail "a change to build.sh did not move the fingerprint"

status() { printf '{"context": "isx/mac-kernel-boot", "state": "%s", "description": "fingerprint %s", "creator": {"login": "%s"}, "target_url": "https://example.invalid/%s"}' "$@"; }
record_of() { printf '{"user": {"login": "%s"}, "body": "### Mac kernel verification\\nResult: success\\nCommit: %s\\nFingerprint: %s"}' "$@"; }

# serve <merge>: origin's refs/pull/9/merge
serve() { git -C "$SRC" push -q -f origin "$1:refs/pull/9/merge"; }
# files <base> <head>: the PR's files as the API lists them
files() { git -C "$SRC" diff --name-only "$1" "$2" | jq -R '{filename: .}' | jq -s .; }

# gate <name> <head> <merge> [comments.json [<sha>=<statuses.json or .err:text>...]]: prints PASS or
# FAIL; origin serves <merge>, and the PR's files are those from BASE to <head>.
gate() {
    local name=$1 head=$2 merge=$3 fix="$WORK/gate-$1" s
    mkdir -p "$fix"
    printf '%s' "${4:-[]}" > "$fix/issues_9_comments.json"
    files "$BASE" "$head" > "$fix/pulls_9_files.json"
    printf '{"mergeable": true, "head": {"sha": "%s"}}' "$head" > "$fix/pulls_9.json"
    serve "$merge"
    shift 4 2>/dev/null || shift $#
    for s in "$@"; do
        case "${s#*=}" in
            .err:*) printf '%s' "${s#*=.err:}" > "$fix/commits_${s%%=*}_statuses.err" ;;
            *) printf '%s' "${s#*=}" > "$fix/commits_${s%%=*}_statuses.json" ;;
        esac
    done
    # A clone of its own: the gate's shallow fetch would stop the fixture serving later merges.
    git -c advice.detachedHead=false clone -q --no-local "$SRC" "$fix/repo" && git -C "$fix/repo" remote set-url origin "$WORK/origin.git"
    if (cd "$fix/repo" && PATH="$WORK/bin:$PATH" CALLS="$fix/calls" FIX="$fix" GH_TOKEN=x REPO=Sanne/incus-spawn PR=9 \
            MERGE_WAIT=0 VERIFIERS="Sanne Sanne-ai-bot" .github/scripts/kernel-mac-gate.sh > "$fix/out" 2>&1); then
        echo PASS
    else
        echo FAIL
    fi
}
expect() { [ "$2" = "$3" ] || { cat "$WORK/gate-$1/out" >&2; fail "$1: expected $3, got $2"; }; }
booted() { echo "$BUMP=[$(status success "$FP" "${1:-Sanne-ai-bot}" x)]"; }

# A PR touching neither build.sh nor appliance/kernel/ is passed from its file list alone, even
# with GitHub's merge stale and a kernel change come in on the base.
expect untouched "$(gate untouched "$OTHER" "$(merge "$MOVED" "$BUMP")")" PASS
[ "$(grep -vc 'pulls/9/files' "$WORK/gate-untouched/calls")" = 0 ] || fail "untouched: the gate went past the PR's files"
# appliance/kernel/ but not an input: fingerprinted, and passed.
expect vm-only "$(gate vm-only "$VM_ONLY" "$(merge "$BASE" "$VM_ONLY")")" PASS
expect no-boot "$(gate no-boot "$BUMP" "$M_BUMP")" FAIL
grep -q "fingerprint $FP" "$WORK/gate-no-boot/out" || fail "no-boot: the failure does not name the fingerprint"
expect build-sh "$(gate build-sh "$BUILD_SH" "$(merge "$BASE" "$BUILD_SH")")" FAIL
expect booted "$(gate booted "$BUMP" "$M_BUMP" '[]' "$(booted)")" PASS
expect stranger "$(gate stranger "$BUMP" "$M_BUMP" '[]' "$(booted someone)")" FAIL
expect other-kernel "$(gate other-kernel "$BUMP" "$M_BUMP" '[]' "$BUMP=[$(status success "$(fp "$BASE")" Sanne x)]")" FAIL
expect withdrawn "$(gate withdrawn "$BUMP" "$M_BUMP" '[]' \
    "$BUMP=[$(status failure "$FP" Sanne y), $(status success "$FP" Sanne-ai-bot x), $(status success "$FP" someone z)]")" FAIL
expect not-withdrawn-by-stranger "$(gate not-withdrawn-by-stranger "$BUMP" "$M_BUMP" '[]' \
    "$BUMP=[$(status failure "$FP" someone y), $(status success "$FP" Sanne x)]")" PASS
# Rebased: the head is new, and the boot is on the old head, which a verifier's record names.
expect rebased "$(gate rebased "$REBASED" "$(merge "$BASE" "$REBASED")" "[$(record_of Sanne-ai-bot "$BUMP" "$FP")]" "$(booted)")" PASS
expect rebased-stranger "$(gate rebased-stranger "$REBASED" "$(merge "$BASE" "$REBASED")" "[$(record_of someone "$BUMP" "$FP")]" "$(booted)")" FAIL
# main changed the kernel under the PR: the merged result was never booted.
expect base-moved "$(gate base-moved "$BUMP" "$(merge "$MOVED" "$BUMP")" '[]' "$(booted)")" FAIL
# GitHub has not recomputed the merge for the new head yet.
expect stale-merge "$(gate stale-merge "$REBASED" "$M_BUMP" '[]' "$(booted)")" FAIL
grep -q "not the PR's head" "$WORK/gate-stale-merge/out" || fail "stale-merge: the reason is not given"
expect unclassified "$(gate unclassified "$UNCLASSIFIED" "$(merge "$BASE" "$UNCLASSIFIED")")" FAIL
grep -q "appliance/kernel/README is neither" "$WORK/gate-unclassified/out" || fail "unclassified: the file was not named"
# A commit a record names may be gone after a force-push; an API failure is not "no boot".
GONE=$(printf '%040d' 7)
expect gone "$(gate gone "$REBASED" "$(merge "$BASE" "$REBASED")" "[$(record_of Sanne "$GONE" "$FP"), $(record_of Sanne "$BUMP" "$FP")]" \
    "$GONE=.err:gh: No commit found for SHA (HTTP 422)" "$(booted)")" PASS
expect api-error "$(gate api-error "$BUMP" "$M_BUMP" '[]' "$BUMP=.err:gh: API rate limit exceeded (HTTP 403)")" FAIL
grep -q "HTTP 403" "$WORK/gate-api-error/out" || fail "api-error: the API's error was not shown"

# The record, on a faked Apple silicon Mac.
cat > "$WORK/bin/uname" <<'EOF'
#!/bin/bash
case "$1" in -s) echo Darwin ;; -m) echo arm64 ;; esac
EOF
printf '#!/bin/bash\necho 15.6\n' > "$WORK/bin/sw_vers"
printf '#!/bin/bash\necho Mac16,10\n' > "$WORK/bin/sysctl"
printf '#!/bin/bash\n[ "$1 $2" = "-a 256" ] && sha256sum "$3"\n' > "$WORK/bin/shasum"
printf '#!/bin/bash\n[ "$1" = --version ] && echo "isx 0.3.11"\nexit 0\n' > "$WORK/bin/isx"
chmod +x "$WORK/bin/"*
# The Mac's checkout of main: a clone of the fixture, whose origin serves the PR's merge.
git -c advice.detachedHead=false clone -q --no-local "$SRC" "$WORK/mac" && git -C "$WORK/mac" remote set-url origin "$WORK/origin.git"
MAC="$WORK/mac/appliance"
cp "$REPO_DIR/appliance/test-kernel-on-mac.sh" "$MAC/"

# record <name> <banner version> <test-boot exit> [<kernel-inputs> [<merge origin serves> [<head>]]]:
# runs test-kernel-on-mac.sh 9, by default on BUMP's build
record() {
    local fix="$WORK/rec-$1"
    mkdir -p "$fix/artifact"
    serve "${5:-$M_BUMP}"
    printf '{"headRefOid": "%s"}' "${6:-$BUMP}" > "$fix/pr.json"
    printf '[{"databaseId": 42}]' > "$fix/runs-build-appliance.yml.json"
    printf '[{"databaseId": 43}]' > "$fix/runs-kernel-mac-verified.yml.json"
    printf 'MZ\0\0Linux version %s (builder@ci) #1 SMP\0' "$2" > "$fix/artifact/vmlinuz"
    echo disk | gzip > "$fix/artifact/disk.img.gz"
    printf '%s' "${4:-$(printf 'commit %s\nfingerprint %s\nversion 7.2.10\n' "$M_BUMP" "$FP")}" > "$fix/artifact/kernel-inputs"
    printf '#!/bin/bash\necho booting "$@"\nexit %s\n' "$3" > "$MAC/test-boot.sh"
    printf '#!/bin/bash\n[ -f "$APPLIANCE_DIR/disk.img.gz" ] || exit 9\necho isx ok\n' > "$MAC/test-with-isx.sh"
    chmod +x "$MAC/test-boot.sh" "$MAC/test-with-isx.sh"
    set +e
    PATH="$WORK/bin:$PATH" CALLS="$fix/calls" FIX="$fix" "$MAC/test-kernel-on-mac.sh" 9 > "$fix/out" 2>&1
    echo $? > "$fix/exit"
    set -e
    touch "$fix/calls"
}
posted() { grep -- "$2" "$WORK/rec-$1/calls" | head -1; }

record good 7.2.10 0
[ "$(cat "$WORK/rec-good/exit")" = 0 ] || { cat "$WORK/rec-good/out" >&2; fail "good: the record failed"; }
comment=$(posted good 'pr comment')
for want in "### Mac kernel verification" "Result: success" "Commit: $BUMP" "Built: $M_BUMP" "Fingerprint: $FP" \
        "Linux version 7.2.10" "actions/runs/42" "disk.img.gz sha256: " "macOS 15.6, Mac16,10, isx 0.3.11" \
        "test-boot.sh: pass" "test-with-isx.sh: pass" "kernel release: 7.2.10"; do
    [[ "$comment" == *"$want"* ]] || fail "good: the record lacks '$want': $comment"
done
[[ "$(posted good "statuses/$BUMP")" == *"state=success"*"context=isx/mac-kernel-boot"*"description=fingerprint $FP"* ]] \
    || fail "good: no success status for the fingerprint: $(cat "$WORK/rec-good/calls")"
[ -n "$(posted good 'run rerun 43')" ] || fail "good: the check was not re-run"
# What the record posts is what the gate accepts.
expect recorded "$(gate recorded "$BUMP" "$M_BUMP" "[$(record_of Sanne-ai-bot "$BUMP" "$FP")]" "$(booted)")" PASS

record boot-fails 7.2.10 1
[ "$(cat "$WORK/rec-boot-fails/exit")" != 0 ] || fail "boot-fails: a failed boot exited 0"
[[ "$(posted boot-fails 'pr comment')" == *"Result: failure"*"test-boot.sh: FAIL (exit 1)"*"booting"* ]] \
    || fail "boot-fails: the record does not show the failure and its log"
[[ "$(posted boot-fails "statuses/$BUMP")" == *"state=failure"* ]] || fail "boot-fails: the status is not failure"

record wrong-kernel 7.2.9 0
[[ "$(posted wrong-kernel 'pr comment')" == *"kernel release: FAIL (7.2.9, but it was built from KERNEL_VERSION 7.2.10)"* ]] \
    || fail "wrong-kernel: a kernel other than the pinned one was not caught"
[[ "$(posted wrong-kernel "statuses/$BUMP")" == *"state=failure"* ]] || fail "wrong-kernel: the status is not failure"

record no-inputs 7.2.10 0 "fingerprint $FP"
[ "$(cat "$WORK/rec-no-inputs/exit")" != 0 ] || fail "no-inputs: an artifact that names no commit was accepted"
[ -z "$(posted no-inputs 'pr comment')$(posted no-inputs statuses/)" ] || fail "no-inputs: something was posted"

# The base moved since the build: the artifact is of a merge that would no longer be merged.
record base-moved 7.2.10 0 "" "$(merge "$MOVED" "$BUMP")"
[ "$(cat "$WORK/rec-base-moved/exit")" != 0 ] || fail "base-moved: a build of an older merge was booted"
grep -q "its base moved" "$WORK/rec-base-moved/out" || fail "base-moved: the reason is not given"
[ -z "$(posted base-moved 'pr comment')$(posted base-moved statuses/)" ] || fail "base-moved: something was posted"

# A series' first release is pinned as X.Y and reports X.Y.0.
FIRST=$(pin "$BASE" 7.3)
M_FIRST=$(merge "$BASE" "$FIRST")
record first 7.3.0 0 "$(printf 'commit %s\n' "$M_FIRST")" "$M_FIRST" "$FIRST"
[[ "$(posted first 'pr comment')" == *"kernel release: 7.3.0"* ]] || { cat "$WORK/rec-first/out" >&2; fail "first: 7.3.0 was not taken for 7.3"; }

echo "kernel-mac-verified gate and Mac record: all cases pass"
