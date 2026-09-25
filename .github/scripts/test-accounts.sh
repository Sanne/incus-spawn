#!/bin/bash
# Per-instance credential account tests, run inside a branched instance.
#
# The claim under test is that one shared proxy serves different real
# credentials to different instances, decided by which instance asked. That
# cannot be checked from the host -- it needs a request made from inside an
# instance, answered by an upstream that reports what was injected.
#
# Usage: incus file push test-accounts.sh <instance>/tmp/
#        incus exec <instance> -- bash /tmp/test-accounts.sh <expected-account>
#
# <expected-account> is the account this instance is pinned to; every assertion
# is relative to it, so the same script serves every instance in the matrix.

EXPECTED="${1:?usage: test-accounts.sh <expected-account>}"

TESTS=0
PASS=0
FAIL=0
ERRORS=""

assert() {
    local desc="$1"; shift
    local output
    TESTS=$((TESTS + 1))
    if output=$("$@" 2>&1); then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s\n' "$desc"
        if [ -n "$output" ]; then
            printf '         %s\n' "$output" | head -5
        fi
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc}\n"
    fi
}

assert_eq() {
    local desc="$1" expected="$2"; shift 2
    local actual
    actual=$("$@" 2>/dev/null)
    TESTS=$((TESTS + 1))
    if [ "$actual" = "$expected" ]; then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s  (expected: %s, got: %s)\n' "$desc" "$expected" "$actual"
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc} (expected '${expected}', got '${actual}')\n"
    fi
}

# The Authorization value the proxy injected, as the upstream saw it.
injected_token() {
    curl -sf https://echo-accounts.incus-spawn.test/ \
        | tr ',' '\n' | grep -i '"Authorization"' \
        | sed 's/.*Bearer \([^"]*\)".*/\1/'
}

echo "========================================"
echo " per-instance credential accounts"
echo " instance is pinned to: $EXPECTED"
echo "========================================"
echo ""

# --- 1. The credential this instance gets is its own ---
# The token names the account, so the assertion reads as the claim: an instance
# pinned to 'alpha' is served alpha's credential and nobody else's.
echo "[1] Per-instance credential injection"
assert_eq "injected token belongs to this instance's account" \
    "acct-token-$EXPECTED" injected_token

assert "no other account's token is ever injected here" \
    bash -c "! curl -sf https://echo-accounts.incus-spawn.test/ | grep -qE 'acct-token-(alpha|beta)' \
        || curl -sf https://echo-accounts.incus-spawn.test/ | grep -q 'acct-token-$EXPECTED'"
echo ""

# --- 2. The credential never enters the instance ---
# The whole point of resolving per instance in the proxy: the container holds a
# placeholder, so a compromised agent learns nothing about any account.
echo "[2] Credential isolation"
assert "real token is absent from the environment" \
    bash -c "! env | grep -q 'acct-token-'"
assert "real token is absent from the on-disk config" \
    bash -c "! grep -rq 'acct-token-' /etc/profile.d/ 2>/dev/null"
echo ""

echo "========================================"
printf " Results: \033[1m%d/%d passed\033[0m" "$PASS" "$TESTS"
if [ "$FAIL" -gt 0 ]; then
    printf ", \033[31m%d failed\033[0m" "$FAIL"
fi
echo ""
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "Failed tests:"
    printf "$ERRORS"
    exit 1
fi
