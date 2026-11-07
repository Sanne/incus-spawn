#!/bin/bash
# Runs the kernel-mac-verified gate (kernel-mac-gate.sh) and the Mac's record of a boot
# (appliance/test-kernel-on-mac.sh) offline, on a fixture repository with gh faked: the fake
# applies each --jq filter to fixture JSON, so the filters are tested too (#1355). Checks that the
# gate passes a PR whose merge leaves the appliance kernel's inputs alone, and otherwise only a
# verifier's newest success for the merge's fingerprint, on the head or a commit a verifier's
# record names; that a stale merge, an unclassified file in appliance/kernel/ and an API error
# fail; and that the record carries what the build says it built and marks a failed boot or the
# wrong kernel as failure, and that the Mac refuses, before posting anything, to run from anything
# but a clean origin/main, under a login the check does not take, without strings, or on a PR not by
# and from Sanne or the bot (#1385). Needs bash, git and jq.
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
    "api user") echo "{\"login\": \"${GH_LOGIN:-Sanne-ai-bot}\"}" | jq -r "$jqf" ;;
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
mkdir -p "$SRC/appliance/kernel" "$SRC/.github/scripts" "$SRC/.github/workflows"
cp "$REPO_DIR/appliance/kernel/kernel-fingerprint.sh" "$SRC/appliance/kernel/"
cp "$REPO_DIR/.github/scripts/kernel-mac-gate.sh" "$SRC/.github/scripts/"
cp "$REPO_DIR/.github/workflows/kernel-mac-verified.yml" "$SRC/.github/workflows/"
# The Mac runs main's copies of these, so they are main's in the fixture too; the boots are faked.
cp "$REPO_DIR/appliance/test-kernel-on-mac.sh" "$SRC/appliance/"
# A failed boot prints a long log, a failed check with its counts, the tally, and this machine's
# work directory, home, name and user, as test-boot.sh and test-tunnel.sh do (#2033).
cat > "$SRC/appliance/test-boot.sh" <<'EOF'
#!/bin/bash
echo booting "$@"
if [ "${FAKE_BOOT_EXIT:-0}" != 0 ]; then
    echo "  the first line of a long log"
    for i in $(seq 60); do echo "  serial line $i"; done
    echo "  FAIL: forwarder children exit with their connections (count 3, baseline 1)"
    echo "  ERROR: cannot read $1/disk.img"
    echo "  see $HOME/.local/state/incus-spawn/vm.log on $(uname -n) as $(id -un)"
    echo "1 of 30 checks failed."
    echo
    echo "Last 40 log lines:"
    echo "ERROR: a line the guest wrote"
    [ -z "${SERIAL_LOG:-}" ] || echo "the guest's whole console" > "$SERIAL_LOG"
fi
exit "${FAKE_BOOT_EXIT:-0}"
EOF
# It boots isx's VM, which writes vm.log, unless FAKE_NO_VM is set.
cat > "$SRC/appliance/test-with-isx.sh" <<'EOF'
#!/bin/bash
[ -f "$APPLIANCE_DIR/disk.img.gz" ] || exit 9
[ -n "${FAKE_NO_VM:-}" ] || echo "the VM's console" > "$HOME/.local/state/incus-spawn/vm.log"
echo isx ok
EOF
chmod +x "$SRC/appliance/"*.sh
for f in isx.config isx-x86_64.config isx-aarch64.config vm.config update-kernel-pin.sh; do echo "# $f" > "$SRC/appliance/kernel/$f"; done
echo "# build" > "$SRC/appliance/build.sh"
printf 'KERNEL_VERSION="7.2.9"\n' > "$SRC/appliance/kernel/build-kernel.sh"
git -C "$SRC" init -q -b main
git init -q --bare -b main "$WORK/origin.git"
git -C "$SRC" remote add origin "$WORK/origin.git"
G() { git -C "$SRC" -c user.name=t -c user.email=t@t "$@"; }
commit() { G add -A && G commit -qm "$1" && G rev-parse HEAD; }
BASE=$(commit base)
git -C "$SRC" push -q origin "$BASE:refs/heads/main"
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

# status <state> <fingerprint> <creator> <url> [<created_at>]
status() { printf '{"context": "isx/mac-kernel-boot", "state": "%s", "description": "fingerprint %s", "creator": {"login": "%s"}, "target_url": "https://example.invalid/%s", "created_at": "%s"}' "$1" "$2" "$3" "$4" "${5:-2026-10-01T00:00:00Z}"; }
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
# A verifier's later failed boot of the same inputs, on the new head, withdraws the old head's success;
# a later success does not need one.
expect rebased-then-failed "$(gate rebased-then-failed "$REBASED" "$(merge "$BASE" "$REBASED")" "[$(record_of Sanne-ai-bot "$BUMP" "$FP")]" \
    "$(booted)" "$REBASED=[$(status failure "$FP" Sanne-ai-bot y 2026-10-02T00:00:00Z)]")" FAIL
expect rebased-failed-before "$(gate rebased-failed-before "$REBASED" "$(merge "$BASE" "$REBASED")" "[$(record_of Sanne-ai-bot "$BUMP" "$FP")]" \
    "$BUMP=[$(status success "$FP" Sanne-ai-bot x 2026-10-02T00:00:00Z)]" "$REBASED=[$(status failure "$FP" Sanne-ai-bot y)]")" PASS
# A failure as old as the success, to the second, withdraws it.
expect rebased-failed-same-time "$(gate rebased-failed-same-time "$REBASED" "$(merge "$BASE" "$REBASED")" "[$(record_of Sanne-ai-bot "$BUMP" "$FP")]" \
    "$(booted)" "$REBASED=[$(status failure "$FP" Sanne-ai-bot y)]")" FAIL
# On one commit, a verifier's newer status of another fingerprint hides an older success of this one.
expect other-kernel-newer "$(gate other-kernel-newer "$BUMP" "$M_BUMP" '[]' \
    "$BUMP=[$(status failure "$(fp "$BASE")" Sanne y 2026-10-02T00:00:00Z), $(status success "$FP" Sanne x)]")" FAIL
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
case "$1" in -s) echo Darwin ;; -m) echo arm64 ;; -n) echo Fake-Mac.local ;; esac
EOF
printf '#!/bin/bash\necho 15.6\n' > "$WORK/bin/sw_vers"
printf '#!/bin/bash\necho Mac16,10\n' > "$WORK/bin/sysctl"
printf '#!/bin/bash\n[ "$1 $2" = "-a 256" ] && sha256sum "$3"\n' > "$WORK/bin/shasum"
printf '#!/bin/bash\n[ "$1" = --version ] && echo "isx 0.3.11"\nexit 0\n' > "$WORK/bin/isx"
chmod +x "$WORK/bin/"*
# The Mac's checkout of main: a full clone of origin, which serves main and the PR's merge.
git -c advice.detachedHead=false clone -q --no-local "$WORK/origin.git" "$WORK/mac"
MAC="$WORK/mac/appliance"
# PATH without strings, for the case that needs it missing.
mkdir "$WORK/nostrings"
for f in /usr/bin/* /bin/*; do [ "${f##*/}" = strings ] || ln -sf "$f" "$WORK/nostrings/" 2> /dev/null || true; done

# record <name> <banner version> <test-boot exit> [<kernel-inputs> [<merge origin serves> [<head>]]]:
# runs test-kernel-on-mac.sh 9, by default on BUMP's build. AUTHOR, OWNER (the head repository's,
# "null" for a deleted one), GH_LOGIN and SYS_PATH (where the system's tools come from) override
# the defaults for one case.
record() {
    local fix="$WORK/rec-$1" owner="\"${OWNER:-Sanne-ai-bot}\""
    mkdir -p "$fix/artifact"
    serve "${5:-$M_BUMP}"
    [ "${OWNER:-}" = null ] && owner=null
    printf '{"headRefOid": "%s", "author": {"login": "%s"}, "headRepositoryOwner": {"login": %s}}' \
        "${6:-$BUMP}" "${AUTHOR:-Sanne-ai-bot}" "$owner" > "$fix/pr.json"
    printf '[{"databaseId": 42}]' > "$fix/runs-build-appliance.yml.json"
    printf '[{"databaseId": 43}]' > "$fix/runs-kernel-mac-verified.yml.json"
    printf 'MZ\0\0Linux version %s (builder@ci) #1 SMP\0' "$2" > "$fix/artifact/vmlinuz"
    echo disk | gzip > "$fix/artifact/disk.img.gz"
    echo rootfs > "$fix/artifact/rootfs.tar.zst"
    printf '%s' "${4:-$(printf 'commit %s\nfingerprint %s\nversion 7.2.10\n' "$M_BUMP" "$FP")}" > "$fix/artifact/kernel-inputs"
    set +e
    # Its own home and temporary directory, so what a run leaves behind can be told.
    mkdir -p "$fix/home/.local/state/incus-spawn" "$fix/tmp"
    echo "an older VM's console" > "$fix/home/.local/state/incus-spawn/vm.log"
    touch -t 202601010000 "$fix/home/.local/state/incus-spawn/vm.log"
    env -u XDG_CACHE_HOME -u XDG_STATE_HOME HOME="$fix/home" TMPDIR="$fix/tmp" PATH="$WORK/bin:${SYS_PATH:-$PATH}" \
        CALLS="$fix/calls" FIX="$fix" FAKE_BOOT_EXIT="$3" "$MAC/test-kernel-on-mac.sh" 9 > "$fix/out" 2>&1
    echo $? > "$fix/exit"
    set -e
    touch "$fix/calls"
}
posted() { grep -- "$2" "$WORK/rec-$1/calls" | head -1; }
# refused <name> <reason>: the case failed for <reason> before downloading or posting anything
refused() {
    [ "$(cat "$WORK/rec-$1/exit")" != 0 ] || fail "$1: the Mac went ahead"
    grep -q -- "$2" "$WORK/rec-$1/out" || { cat "$WORK/rec-$1/out" >&2; fail "$1: the reason '$2' is not given"; }
    [ -z "$(posted "$1" 'run download')$(posted "$1" 'pr comment')$(posted "$1" statuses/)" ] || fail "$1: it went on after refusing"
}

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
[[ "$(posted good 'run list' )" == *"--event pull_request"* ]] || fail "good: a push run's build of the head could be taken"
[ "$(git -C "$WORK/mac" rev-parse --is-shallow-repository)" = false ] || fail "good: fetching the merge made the Mac's clone shallow"
# A pass leaves nothing behind.
[ -z "$(ls -A "$WORK/rec-good/tmp")" ] || fail "good: the work directory was kept: $(ls -A "$WORK/rec-good/tmp")"
[ ! -e "$WORK/rec-good/home/.cache/incus-spawn/kernel-mac" ] || fail "good: logs were saved"
# What the record posts is what the gate accepts.
expect recorded "$(gate recorded "$BUMP" "$M_BUMP" "[$(record_of Sanne-ai-bot "$BUMP" "$FP")]" "$(booted)")" PASS

record boot-fails 7.2.10 1
[ "$(cat "$WORK/rec-boot-fails/exit")" != 0 ] || fail "boot-fails: a failed boot exited 0"
[[ "$(posted boot-fails 'pr comment')" == *"Result: failure"*"test-boot.sh: FAIL (exit 1)"*"last 40 lines"*"serial line 60"* ]] \
    || fail "boot-fails: the record does not show the failure and its log"
[[ "$(posted boot-fails "statuses/$BUMP")" == *"state=failure"* ]] || fail "boot-fails: the status is not failure"
# The record names the failed checks with their counts, past the tail, and none of this machine (#2033).
comment=$(posted boot-fails 'pr comment')
for want in "test-boot.sh, failing checks:" "FAIL: forwarder children exit with their connections (count 3, baseline 1)" \
        "1 of 30 checks failed." "ERROR: cannot read <work>/boot/disk.img" \
        "see ~/.local/state/incus-spawn/vm.log on <host> as <user>" \
        "Logs: kept on the Mac in ~/.cache/incus-spawn/kernel-mac/pr-9"; do
    [[ "$comment" == *"$want"* ]] || fail "boot-fails: the record lacks '$want': $comment"
done
for leak in "$WORK/rec-boot-fails" "Fake-Mac"; do
    [[ "$comment" != *"$leak"* ]] || fail "boot-fails: the record names '$leak': $comment"
done
! grep -qw -- "$(id -un)" <<< "$comment" || fail "boot-fails: the record names the user: $comment"
# The full logs outlive the run: in the work directory, left without its disk images, and per PR.
kept=$(ls -d "$WORK/rec-boot-fails/tmp/"*)
[ "$(echo "$kept" | wc -l)" = 1 ] && grep -q "Kept the logs of this run in $kept" "$WORK/rec-boot-fails/out" \
    || fail "boot-fails: the work directory was not kept and named: $kept"
grep -q "the first line of a long log" "$kept/test-boot.sh.log" || fail "boot-fails: the kept log is not whole"
# Of the artifact, only kernel-inputs: the images and the kernel are tens of MB each.
left=$(cd "$kept" && find . -type f | sort | tr '\n' ' ')
[ "$left" = "./build/kernel-inputs ./serial.log ./started ./test-boot.sh.log ./test-with-isx.sh.log " ] \
    || fail "boot-fails: the work directory kept more than its logs and kernel-inputs: $left"
saved="$WORK/rec-boot-fails/home/.cache/incus-spawn/kernel-mac/pr-9"
grep -q "the first line of a long log" "$saved/test-boot.sh.log" && [ -f "$saved/test-with-isx.sh.log" ] \
    && grep -q "the guest's whole console" "$saved/serial.log" && grep -q "^the VM's console" "$saved/vm.log" \
    || fail "boot-fails: the logs were not saved per PR: $(ls -A "$saved" 2>&1)"
# A guest's line in the console tail is not one of the scripts' failed checks.
[[ "${comment%%last 40 lines*}" != *"a line the guest wrote"* ]] || fail "boot-fails: a guest's line was taken for a check: $comment"
# A vm.log this run's boots did not write is not kept as theirs.
FAKE_NO_VM=1 record boot-fails-no-vm 7.2.10 1
[ ! -e "$WORK/rec-boot-fails-no-vm/home/.cache/incus-spawn/kernel-mac/pr-9/vm.log" ] || fail "boot-fails-no-vm: an older vm.log was kept"

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

# The Mac boots no image an external PR built, with no way around it (#1385).
AUTHOR=someone record external 7.2.10 0
refused external "the Mac boots only PRs by and from Sanne Sanne-ai-bot"
OWNER=someone record external-head-repo 7.2.10 0
refused external-head-repo "from someone's repository"
OWNER=null record deleted-head-repo 7.2.10 0
refused deleted-head-repo "from -'s repository"
GH_LOGIN=someone record not-a-verifier 7.2.10 0
refused not-a-verifier "logged in as someone"
SYS_PATH="$WORK/nostrings" record no-strings 7.2.10 0
refused no-strings "strings is missing"
# The checkout is what runs with the verifier's credentials: only a clean origin/main.
touch "$WORK/mac/appliance/extra.sh"
record dirty 7.2.10 0
refused dirty "has local changes"
rm "$WORK/mac/appliance/extra.sh"
git -C "$WORK/mac" -c advice.detachedHead=false checkout -q "$BUMP"
record pr-branch 7.2.10 0
refused pr-branch "is not at origin/main"
git -C "$WORK/mac" checkout -q main
git -C "$SRC" push -q origin "$OTHER:refs/heads/main"
record behind-main 7.2.10 0
refused behind-main "is not at origin/main"

echo "kernel-mac-verified gate and Mac record: all cases pass"
