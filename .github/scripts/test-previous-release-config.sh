#!/bin/bash
# Integration test: a config.yaml written by the previous release keeps working.
#
# Runs on the host. Swaps in the committed fixture for <release> -- produced by that
# release's own serializer, so every namespace is in the flat, pre-accounts
# layout, empty strings and stray keys included -- and checks, against real
# instances and the real proxy, that nothing its user configured changed meaning:
#
#   - every configured credential is listed, as its account 'default' (#773:
#     a flat github section used to list as nothing at all)
#   - an unpinned instance is served each flat credential
#   - an instance pinned to 'default' in every namespace is accepted -- the
#     pin #773 reported as refused for github -- and served the same values
#   - a pin to any other name is still refused, not quietly served the default
#
# The write side (the first save moving each credential into accounts.default)
# is covered by PreviousReleaseConfigCompatTest, which drives the same calls
# 'isx init' makes; CI runs 'isx init' with no terminal, so it never saves one.
#
# The original config.yaml is restored on exit, whatever happens, so the steps
# after this one see the configuration they were written against.
#
# Requires:
#   - tpl-minimal built, the proxy running, the echo server answering
#     echo.incus-spawn.test, api.github.com, api.openai.com and api.anthropic.com
#   - the test-proxy-tool fixture installed (namespace testProxyTool)
#
# Usage:
#   sg incus-admin -c "bash .github/scripts/test-previous-release-config.sh v0.3.8"

set -uo pipefail

RELEASE="${1:?usage: test-previous-release-config.sh <release-tag>, e.g. v0.3.8}"
FIXTURE="common/src/test/resources/config-compat/${RELEASE}-full.yaml"
CFG="$HOME/.config/incus-spawn/config.yaml"
BACKUP="$(mktemp)"

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

# The proxy watches config.yaml, but a signal makes the reload happen now rather
# than whenever the watcher gets to it.
reload_proxy() {
    local pid="${ISX_PROXY_PID:-$(pgrep -nf 'isx-proxy|incus-spawn-proxy' || true)}"
    [ -n "$pid" ] && kill -HUP "$pid" 2>/dev/null
    sleep 3
}

cleanup() {
    cp "$BACKUP" "$CFG"
    rm -f "$BACKUP"
    reload_proxy
    for i in prev-release-flat prev-release-pinned; do
        isx destroy "$i" >/dev/null 2>&1 || incus delete -f "$i" >/dev/null 2>&1 || true
    done
}

# The value the upstream saw in a header, from inside an instance. Header names
# are matched case-insensitively: which case arrives is up to the proxy and curl.
header_in() {
    local instance="$1" url="$2" header="$3"
    incus exec "$instance" -- curl -sf "$url" 2>/dev/null | python3 -c "
import json, sys
headers = {k.lower(): v for k, v in json.load(sys.stdin)['headers'].items()}
print(headers.get(sys.argv[1].lower(), ''))" "$header" 2>/dev/null
}

wait_for_network() {
    incus exec "$1" -- bash -c '
        systemctl start systemd-networkd 2>/dev/null
        for n in $(seq 1 30); do
            ip -4 -o addr show eth0 | grep -q "inet " && break
            sleep 0.5
        done'
}

cp "$CFG" "$BACKUP"
trap cleanup EXIT

echo "========================================"
echo " ${RELEASE} config.yaml, unchanged"
echo "========================================"
echo ""

# The fixture as that release wrote it, with only what this runner needs changed:
# CI's own bridge gateway, placeholder credentials the echo server can report back,
# and no host paths (the fixture's point at directories this runner does not have).
# Line edits rather than a YAML round trip, which would normalise away exactly the
# shape under test.
python3 - "$FIXTURE" "$BACKUP" "$CFG" <<'EOF'
import re, sys, yaml
fixture, backup, out = sys.argv[1:]
gateway = (yaml.safe_load(open(backup)) or {}).get("incus-bridge-gateway", "")
text = open(fixture).read()
for old, new in {
    "sk-ant-api03-fixture": "sk-ant-placeholder-for-ci",
    "ghp_fixture": "ghp-flat-ci",
    "sk-openai-fixture": "sk-openai-flat-ci",
    "tpt-fixture": "tpt-flat-ci",
}.items():
    assert old in text, old
    text = text.replace(old, new)
text = re.sub(r'(?m)^incus-bridge-gateway: .*$', f'incus-bridge-gateway: "{gateway}"', text)
for key, empty in (("searchPaths", "[]"), ("host-paths", "[]"), ("repo-paths", "{}")):
    text = re.sub(rf'(?m)^{key}:\n(?:[ -].*\n)*', f'{key}: {empty}\n', text)
open(out, "w").write(text)
EOF
cat "$CFG"
echo ""
reload_proxy

echo "[1] Every configured credential is listed as its account 'default'"
LISTING="$(isx account list 2>&1)"
echo "$LISTING" | sed 's/^/       /'
for ns in claude github bob openai testProxyTool; do
    if echo "$LISTING" | python3 -c "
import sys
ns, lines = sys.argv[1], sys.stdin.read().splitlines()
i = lines.index(ns + ':')
sys.exit(0 if lines[i + 1].strip() == 'default  (default)' else 1)" "$ns" 2>/dev/null; then
        pass "$ns lists its flat credential as 'default'"
    else
        fail "$ns lists its flat credential as 'default'"
    fi
done
echo ""

echo "[2] Instances, unpinned and pinned to 'default'"
isx branch prev-release-flat --from tpl-minimal --no-start >/dev/null
if isx branch prev-release-pinned --from tpl-minimal --no-start \
        --account claude=default --account github=default \
        --account openai=default --account testProxyTool=default >/dev/null 2>&1; then
    pass "pinning every namespace to 'default' is accepted"
else
    fail "pinning every namespace to 'default' is accepted" \
        "$(isx branch prev-release-pinned --from tpl-minimal --no-start --account github=default 2>&1 | tail -1)"
fi
for i in prev-release-flat prev-release-pinned; do
    incus info "$i" >/dev/null 2>&1 || continue
    incus start "$i" && wait_for_network "$i"
done
echo ""

echo "[3] Each is served the flat credentials"
for i in prev-release-flat prev-release-pinned; do
    incus info "$i" >/dev/null 2>&1 || { fail "$i exists"; continue; }
    assert_eq "$i: testProxyTool token injected" "Bearer tpt-flat-ci" \
        "$(header_in "$i" https://echo.incus-spawn.test/ Authorization)"
    assert_eq "$i: github token injected" "Bearer ghp-flat-ci" \
        "$(header_in "$i" https://api.github.com/ Authorization)"
    assert_eq "$i: openai key injected" "Bearer sk-openai-flat-ci" \
        "$(header_in "$i" https://api.openai.com/ Authorization)"
    assert_eq "$i: anthropic key injected" "sk-ant-placeholder-for-ci" \
        "$(header_in "$i" https://api.anthropic.com/ x-api-key)"
done
echo ""

echo "[4] A name the flat credential does not have is still refused"
if isx account set prev-release-flat github=work >/dev/null 2>&1; then
    fail "pinning a missing account is refused" "'isx account set ... github=work' succeeded"
else
    pass "pinning a missing account is refused"
fi
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
