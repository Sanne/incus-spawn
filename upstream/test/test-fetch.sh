#!/usr/bin/env bash
# The engine's fetch (#2461) with curl and sleep faked: curl fails a set number of times, printing the
# status and Retry-After it was given as its -w output, and sleep records each wait instead of waiting. Checks
# that a download failing twice is tried again and kept; that one failing every time is tried seven
# times, waiting a second doubling each time, and fails naming the URL; that a Retry-After the waits
# can afford is waited, one past what is left of the budget fails at once, and a Retry-After date in
# the past waits nothing; that a failed download leaves no file; and that a 4xx fails at once unless
# it names a Retry-After or is a 403, 408 or 429, while a 5xx or no answer is tried again. Needs bash 4.4+.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; esac; shift; done
n="$(cat "$WORK/calls" 2> /dev/null || echo 0)"; n=$((n + 1)); echo "$n" > "$WORK/calls"
if [ "$n" -gt "$FAILS" ]; then echo served > "$out"; exit 0; fi
echo partial > "$out"
printf '%s %s' "${CODE:-429}" "$RETRY_AFTER"
echo "curl: (22) The requested URL returned error: ${CODE:-429}" >&2
exit 22
FAKE
cat > "$WORK/bin/sleep" <<'FAKE'
#!/bin/bash
printf '%s ' "$1" >> "$WORK/waits"
FAKE
chmod +x "$WORK/bin/curl" "$WORK/bin/sleep"
export PATH="$WORK/bin:$PATH" WORK
unset UPSTREAM_FETCH_PAUSE

# fetch_case <fails> <retry-after> [code]: fetch with curl failing that many times with that status
# (429 by default); sets rc, calls and waits.
fetch_case() {
    rm -f "$WORK/calls" "$WORK/waits" "$WORK/out"
    rc=0
    CODE="${3:-}" FAILS="$1" RETRY_AFTER="$2" bash -c '. "$1/upstream/engine/lib.sh"; fetch https://example.org/f "$2"' _ "$SRC" "$WORK/out" \
        2> "$WORK/err" || rc=$?
    calls="$(cat "$WORK/calls")"; waits="$(cat "$WORK/waits" 2> /dev/null || true)"
}

fetch_case 2 ""
[[ "$rc" == 0 && "$calls" == 3 && "$(cat "$WORK/out")" == served ]] || fail "a download failing twice was not kept: rc=$rc calls=$calls"
[[ "$waits" == "1 2 " ]] || fail "the waits were '$waits', not 1 and 2"

fetch_case 99 ""
[[ "$rc" != 0 && "$calls" == 7 ]] || fail "a download failing every time was tried $calls times (rc=$rc), not 7"
[[ "$waits" == "1 2 4 8 16 32 " ]] || fail "the waits were '$waits', not a second doubling"
grep -qx "ERROR: failed to download https://example.org/f" "$WORK/err" || fail "the failure does not name the URL: $(cat "$WORK/err")"
[[ ! -e "$WORK/out" ]] || fail "a failed download left its partial file"

fetch_case 2 "5"
[[ "$rc" == 0 && "$waits" == "5 5 " ]] || fail "a Retry-After of 5 s was not waited: rc=$rc waits='$waits'"

fetch_case 99 "50"
[[ "$rc" != 0 && "$calls" == 3 && "$waits" == "50 50 " ]] || fail "Retry-After 50 should be waited twice within 120 s: calls=$calls waits='$waits'"

fetch_case 99 "3600"
[[ "$rc" != 0 && "$calls" == 1 && -z "$waits" ]] || fail "a Retry-After past the budget was waited: calls=$calls waits='$waits'"
fetch_case 99 "12345678901"
[[ "$rc" != 0 && "$calls" == 1 && -z "$waits" ]] || fail "a Retry-After of ten digits was not refused: calls=$calls waits='$waits'"

fetch_case 1 "Wed, 21 Oct 2015 07:28:00 GMT"
[[ "$rc" == 0 && "$waits" == "0 " ]] || fail "a Retry-After date in the past did not retry at once: rc=$rc waits='$waits'"
fetch_case 99 "" 404
[[ "$rc" != 0 && "$calls" == 1 && -z "$waits" ]] || fail "a 404 was tried again: calls=$calls waits='$waits'"
fetch_case 1 "2" 404
[[ "$rc" == 0 && "$calls" == 2 && "$waits" == "2 " ]] || fail "a 404 naming a Retry-After was not waited: rc=$rc calls=$calls waits='$waits'"
fetch_case 1 "" 403
[[ "$rc" == 0 && "$calls" == 2 ]] || fail "a 403 was not tried again: rc=$rc calls=$calls"
fetch_case 1 "" 503
[[ "$rc" == 0 && "$calls" == 2 ]] || fail "a 503 was not tried again: rc=$rc calls=$calls"
fetch_case 1 "" 000
[[ "$rc" == 0 && "$calls" == 2 ]] || fail "a connection that failed was not tried again: rc=$rc calls=$calls"
echo "test-fetch: ok"
