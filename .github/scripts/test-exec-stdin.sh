#!/bin/bash
# An exec whose stdin ends must keep running and return its output (#2734).
#
# That is the path of `git push` to an isx:// remote and of `isx exec -i`:
# isx forwards stdin, ends it, and the command still has work to do.
# Incus 7.7 sends SIGPIPE to the exec's process unless the stdin socket closes
# with WebSocket status 1000 (#2720), so `git receive-pack` died after reading
# the pack and the push failed with "unexpected disconnect while reading
# sideband packet". No other PR job pushes to an instance or forwards stdin.
#
# Only the git checks catch #2720. `isx exec` runs its command under
# `su - agentuser`, and su survives SIGPIPE and waits for its child, so the
# `isx exec -i` check passed on Incus 7.7 without the fix. It stays as a check
# that stdin is forwarded whole and the answer still comes back.
#
# Runs on the host, with isx, git-remote-isx and incus on PATH and
# tpl-minimal built.
#
# Usage: sg incus-admin -c "bash .github/scripts/test-exec-stdin.sh"

set -uo pipefail

NAME=test-exec-stdin
WORK=$(mktemp -d)
REMOTE="isx://$NAME/~/stdin.git"
GIT_ID=(-c user.name="isx ci" -c user.email=ci@isx.invalid)

TESTS=0
PASS=0
FAIL=0

pass() { TESTS=$((TESTS + 1)); PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() {
    TESTS=$((TESTS + 1)); FAIL=$((FAIL + 1))
    printf '  \033[31mFAIL\033[0m  %s\n' "$1"
    [ -s "$2" ] && sed 's/^/         /' "$2" | tail -n 15
}

# Runs a command, its output kept in a log the failure shows. A hang fails the check
# within a minute, so a hang in every check still ends well inside the job's timeout.
check() {
    local desc="$1"; shift
    local log="$WORK/check.log"
    if timeout 60 "$@" > "$log" 2>&1; then pass "$desc"; else fail "$desc" "$log"; fi
}

# Setup that is not under test: a failure here ends the script.
setup() {
    "$@" > "$WORK/setup.log" 2>&1 || { cat "$WORK/setup.log"; echo "setup failed: $*"; exit 1; }
}

cleanup() {
    isx destroy "$NAME" --skip-confirmation > /dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

echo "=== An exec outlives its stdin ==="
# Only Incus 7.7 and later kill the command (#2720): an older one passes either way.
incus version
isx branch "$NAME" --from tpl-minimal > "$WORK/branch.log" 2>&1 || {
    cat "$WORK/branch.log"; echo "could not branch $NAME"; exit 1
}
setup isx exec --cwd '~' "$NAME" -- git init -q --bare stdin.git

# 16 MiB that do not compress, so the pack is larger than the 13 MB push that failed in #2720.
seed="$WORK/seed"
setup git init -q -b main "$seed"
for i in 1 2 3 4; do head -c 4194304 /dev/urandom > "$seed/blob-$i.bin" || exit 1; done
setup git -C "$seed" add -A
setup git -C "$seed" "${GIT_ID[@]}" commit -q -m seed

check "git push of 16 MiB to isx://" \
    git -C "$seed" push -q "$REMOTE" HEAD:refs/heads/main
check "the instance holds the pushed commit" \
    bash -c '[ "$(isx exec --cwd "~" "$1" -- git -C stdin.git rev-parse main)" = "$(git -C "$2" rev-parse HEAD)" ]' _ "$NAME" "$seed"

clone="$WORK/clone"
check "git clone from isx://" git clone -q -b main "$REMOTE" "$clone"
check "the clone holds the seed's commit" \
    bash -c '[ "$(git -C "$1" rev-parse HEAD)" = "$(git -C "$2" rev-parse HEAD)" ]' _ "$clone" "$seed"

if git -C "$clone" rev-parse -q --verify HEAD > /dev/null 2>&1; then
    head -c 1048576 /dev/urandom > "$clone/more.bin"
    setup git -C "$clone" add more.bin
    setup git -C "$clone" "${GIT_ID[@]}" commit -q -m more
fi
check "git push of a second commit from the clone" git -C "$clone" push -q origin main
check "git fetch from isx://" git -C "$seed" fetch -q "$REMOTE" main
check "the fetch brought the clone's commit" \
    bash -c '[ "$(git -C "$1" rev-parse FETCH_HEAD)" = "$(git -C "$2" rev-parse HEAD)" ]' _ "$seed" "$clone"

# The shape of IncusApiLiveTest#execBidirectionalCommandOutlivesItsStdin: read stdin to its end,
# work for a second after it ended, then answer. The answer says how much stdin the command read.
# This does not catch #2720 (see the top), since su takes the SIGPIPE.
head -c 2097152 /dev/urandom > "$WORK/input.bin"
out="$WORK/exec.out"
errs="$WORK/exec.err"
rc=0
timeout 60 isx exec -i "$NAME" -- sh -c 'n=$(wc -c); sleep 1; echo "read $n"' < "$WORK/input.bin" > "$out" 2> "$errs" || rc=$?
if [ "$rc" -eq 0 ] && [ "$(cat "$out")" = "read 2097152" ]; then
    pass "isx exec -i answers after its stdin ended"
else
    { echo "exit code $rc"; echo "stderr:"; cat "$errs"; } >> "$out"
    fail "isx exec -i answers after its stdin ended" "$out"
fi

echo ""
echo "Results: $PASS/$TESTS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
