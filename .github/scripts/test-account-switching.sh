#!/bin/bash
# Integration test: changing which credential account an instance uses.
#
# Runs on the host and drives 'isx account' against real branched instances,
# checking the effect from inside them. Three behaviours, each of which can only
# be observed end to end:
#
#   - re-pointing a RUNNING instance takes effect on its next request, with
#     nothing inside it restarted (credentials live in the proxy, never in the
#     container -- that is what makes the swap possible at all)
#   - an instance pinned to an account that no longer exists is refused, rather
#     than quietly served the default, which would spend the wrong subscription
#   - the refusal is scoped to that instance; its neighbours keep working
#
# Requires:
#   - instances acct-alpha and acct-beta branched and running
#   - accounts alpha/beta configured under the testAccountTool namespace
#   - the echo server answering echo-accounts.incus-spawn.test
#
# Usage:
#   sg incus-admin -c "bash .github/scripts/test-account-switching.sh"

set -uo pipefail

TESTS=0
PASS=0
FAIL=0
ERRORS=""

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; TESTS=$((TESTS+1)); PASS=$((PASS+1)); }
fail() {
    printf '  \033[31mFAIL\033[0m  %s\n' "$1"
    [ -n "${2:-}" ] && printf '         %s\n' "$2"
    TESTS=$((TESTS+1)); FAIL=$((FAIL+1)); ERRORS="${ERRORS}  - ${1}\n"
}

assert_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$actual" = "$expected" ]; then pass "$desc"
    else fail "$desc" "expected '$expected', got '$actual'"; fi
}

# The token the proxy injected, as the upstream saw it.
injected_in() {
    incus exec "$1" -- curl -sf https://echo-accounts.incus-spawn.test/ 2>/dev/null \
        | tr ',' '\n' | grep -i '"Authorization"' \
        | sed 's/.*Bearer \([^"]*\)".*/\1/'
}

echo "========================================"
echo " credential account switching"
echo "========================================"
echo ""

echo "[1] Each instance starts on its own account"
assert_eq "acct-alpha is served alpha's credential" "acct-token-alpha" "$(injected_in acct-alpha)"
assert_eq "acct-beta is served beta's credential"   "acct-token-beta"  "$(injected_in acct-beta)"
echo ""

echo "[2] Re-pointing a running instance"
# Deliberately not restarted, and deliberately still running: the claim is that
# the next request picks up the change, not the next boot.
isx account set acct-alpha testAccountTool=beta >/dev/null
assert_eq "acct-alpha follows its new account on the next request" \
    "acct-token-beta" "$(injected_in acct-alpha)"
assert_eq "acct-beta is unaffected by its neighbour's change" \
    "acct-token-beta" "$(injected_in acct-beta)"

isx account set acct-alpha testAccountTool=alpha >/dev/null
assert_eq "and back again" "acct-token-alpha" "$(injected_in acct-alpha)"
echo ""

echo "[3] An account that no longer exists fails closed"
# Pinned straight through Incus rather than 'isx account set', which refuses an
# unknown name up front -- here we want the state a renamed or deleted account
# leaves behind, and what the proxy does when it meets it.
incus config set acct-alpha user.incus-spawn.account.testAccountTool ghost
# The proxy re-reads instance pinning on SIGUSR1; 'isx account set' sends it,
# a raw 'incus config set' does not.
PROXY_PID="${ISX_PROXY_PID:-$(pgrep -f 'isx-proxy|incus-spawn-proxy' | head -1)}"
[ -n "$PROXY_PID" ] && kill -USR1 "$PROXY_PID" 2>/dev/null
sleep 2

if incus exec acct-alpha -- curl -sf https://echo-accounts.incus-spawn.test/ >/dev/null 2>&1; then
    fail "a request from an instance pinned to a missing account is refused" \
        "the request succeeded, so some other account's credential was served"
else
    pass "a request from an instance pinned to a missing account is refused"
fi

BODY=$(incus exec acct-alpha -- curl -s https://echo-accounts.incus-spawn.test/ 2>/dev/null)
case "$BODY" in
    *ghost*) pass "the refusal names the account that is missing" ;;
    *)       fail "the refusal names the account that is missing" "got: $BODY" ;;
esac

assert_eq "a neighbouring instance keeps working throughout" \
    "acct-token-beta" "$(injected_in acct-beta)"

# Leave the instance usable for anything that runs after this.
isx account set acct-alpha testAccountTool=alpha >/dev/null 2>&1 \
    || incus config set acct-alpha user.incus-spawn.account.testAccountTool alpha
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
