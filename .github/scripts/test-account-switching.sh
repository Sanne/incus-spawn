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
#   - 'isx account unset' returns an instance to the configured default, live
#   - a global default of a Claude auth mode an instance was not built for is refused for
#     that instance's Claude requests only, rather than served mismatched
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

echo "[4] Unpinning follows the default again"
# acct-beta was branched with --account testAccountTool=beta; the default is alpha.
isx account unset acct-beta testAccountTool >/dev/null
assert_eq "acct-beta is served the default account's credential on its next request" \
    "acct-token-alpha" "$(injected_in acct-beta)"
assert_eq "the pin is removed from the instance, not blanked" \
    "" "$(incus config get acct-beta user.incus-spawn.account.testAccountTool)"
# The account line, then the sentence saying where it comes from.
SHOW=$(isx account show acct-beta 2>/dev/null | grep -A1 '^  testAccountTool ' | tr '\n' ' ')
case "$SHOW" in
    *alpha*"Not pinned: follows the global default"*) pass "'isx account show' reports it as following the default" ;;
    *) fail "'isx account show' reports it as following the default" "got: $SHOW" ;;
esac
assert_eq "acct-alpha, still pinned, is unaffected" \
    "acct-token-alpha" "$(injected_in acct-alpha)"

isx account set acct-beta testAccountTool=beta >/dev/null 2>&1 \
    || incus config set acct-beta user.incus-spawn.account.testAccountTool beta
echo ""

echo "[5] A default of another Claude auth mode is refused, not served"
# The instances were built for Claude 'api-key' (CI's flat apiKey) and do not pin claude,
# so they follow the global default. Point that default at a Pro/Max account: serving it
# would hand them a credential their environment does not match, so the proxy must refuse
# their Claude requests -- and only those -- with a message saying how to fix it.
CFG="$HOME/.config/incus-spawn/config.yaml"
BACKUP="$(mktemp)"
cp "$CFG" "$BACKUP"
reload_proxy() {
    local pid="${ISX_PROXY_PID:-$(pgrep -nf 'isx-proxy|incus-spawn-proxy' || true)}"
    [ -n "$pid" ] && kill -HUP "$pid" 2>/dev/null
    sleep 3
}
claude_body() {
    incus exec "$1" -- curl -s https://api.anthropic.com/v1/models 2>/dev/null
}
case "$(claude_body acct-alpha)" in
    *"was built for claude"*) fail "before the change, Claude requests are not refused" ;;
    *)                        pass "before the change, Claude requests are not refused" ;;
esac
python3 - "$CFG" <<'PY'
import sys, yaml
path = sys.argv[1]
with open(path) as f:
    cfg = yaml.safe_load(f) or {}
claude = cfg.setdefault('claude', {})
accounts = claude.setdefault('accounts', {})
if not accounts and claude.get('apiKey'):
    accounts['default'] = {'type': 'api-key', 'apiKey': claude.pop('apiKey')}
accounts['subscription'] = {'type': 'oauth', 'oauthToken': 'sk-ant-oat01-placeholder-for-ci'}
claude['default'] = 'subscription'
with open(path, 'w') as f:
    yaml.safe_dump(cfg, f, default_flow_style=False)
PY
reload_proxy
BODY="$(claude_body acct-alpha)"
case "$BODY" in
    *"was built for claude 'api-key'"*"isx account set acct-alpha claude="*)
        pass "an unpinned instance's Claude requests are refused, saying how to pin it" ;;
    *)  fail "an unpinned instance's Claude requests are refused, saying how to pin it" "got: $BODY" ;;
esac
assert_eq "its other credentials are still served" \
    "acct-token-alpha" "$(injected_in acct-alpha)"
cp "$BACKUP" "$CFG"
rm -f "$BACKUP"
reload_proxy
case "$(claude_body acct-alpha)" in
    *"was built for claude"*) fail "restoring the default serves it again" ;;
    *)                        pass "restoring the default serves it again" ;;
esac
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
