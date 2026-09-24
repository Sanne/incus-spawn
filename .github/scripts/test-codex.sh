#!/bin/bash
# Tests the built-in codex tool inside an incus-spawn container.
# Runs in an instance branched from tpl-test-codex (tools: [codex] with
# non-default model/effort parameters), so every assertion here is against
# what CodexSetup actually installed and wrote -- a hand-written config
# fixture would keep passing after CodexSetup changed underneath it.
#
# Usage: incus file push test-codex.sh <instance>/tmp/
#        incus exec <instance> -- bash /tmp/test-codex.sh

set -uo pipefail

CONFIG=/home/agentuser/.codex/config.toml
AUTH=/home/agentuser/.codex/auth.json
# Injected by the proxy, never present in the container. Must match the value
# the workflow writes into openai.apiKey before starting the proxy.
REAL_KEY=sk-test-openai-key-for-ci

PASS=0
FAIL=0
ERRORS=""

assert() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s\n' "$desc"
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc}\n"
    fi
}

assert_eq() {
    local desc="$1" expected="$2"; shift 2
    local actual
    actual=$("$@" 2>/dev/null) || true
    if [ "$actual" = "$expected" ]; then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s  (expected: %s, got: %s)\n' "$desc" "$expected" "$actual"
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc} (expected '${expected}', got '${actual}')\n"
    fi
}

echo "========================================"
echo " Codex CLI tool integration test"
echo "========================================"
echo ""

# --- 1. Installation ---
# CodexSetup declares the nodejs package and installs @openai/codex globally
# through the npm registry interception.
echo "[1] Installation"
assert "codex is on agentuser's PATH" \
    su -l agentuser -c "command -v codex"
assert "codex --version runs" \
    su -l agentuser -c "codex --version"
echo ""

# --- 2. Generated configuration ---
# Written by CodexSetup.configureSettings() at build time, including the
# model/effort parameters the template declared.
echo "[2] Generated Configuration"
assert "config.toml exists" test -f "$CONFIG"
assert_eq "config.toml is owned by agentuser" "agentuser" \
    stat -c '%U' "$CONFIG"
assert "template's model parameter reached the config" \
    grep -q '^model = "gpt-5.3-codex"$' "$CONFIG"
assert "template's effort parameter reached the config" \
    grep -q '^model_reasoning_effort = "medium"$' "$CONFIG"
assert "unattended approval policy" \
    grep -q '^approval_policy = "never"$' "$CONFIG"
assert "full filesystem access (the container is the sandbox)" \
    grep -q '^sandbox_mode = "danger-full-access"$' "$CONFIG"
assert "API login is forced (no interactive ChatGPT sign-in)" \
    grep -q '^forced_login_method = "api"$' "$CONFIG"
assert "home directory is trusted" \
    grep -q '^\[projects\."/home/agentuser"\]$' "$CONFIG"
echo ""

# --- 3. Credential isolation ---
# The container only ever holds a placeholder: the real key stays on the host
# and is injected by the proxy. A leak here defeats the whole design.
echo "[3] Credential Isolation"
assert "auth.json uses API-key mode" \
    grep -q '"auth_mode": "apikey"' "$AUTH"
assert "auth.json holds the placeholder key" \
    grep -q '"OPENAI_API_KEY": "sk-placeholder"' "$AUTH"
assert_eq "OPENAI_API_KEY in the login shell is the placeholder" "sk-placeholder" \
    su -l agentuser -c 'printf "%s" "$OPENAI_API_KEY"'
assert "the real API key is nowhere under ~/.codex" \
    bash -c "! grep -rq '$REAL_KEY' /home/agentuser/.codex"
assert "the real API key is not in the environment" \
    bash -c "! su -l agentuser -c env | grep -q '$REAL_KEY'"
echo ""

# --- 4. Codex accepts the generated config ---
# Codex fails on auth (the placeholder key is rejected upstream), but it must
# not fail on config parsing -- that was the "full-auto" regression.
echo "[4] Config Parsing"
assert "codex loads config.toml without errors" \
    bash -c "output=\$(su -l agentuser -c 'timeout 10 codex \"test\" </dev/null' 2>&1 || true); \
        ! echo \"\$output\" | grep -q 'Error loading config'"
echo ""

# --- 5. Proxy interception for api.openai.com ---
# CodexSetup's proxy() registers api.openai.com for Bearer injection from
# openai.apiKey. The host echo server answers with the headers it received,
# so this proves the real key is added on the way out.
echo "[5] Proxy Interception (api.openai.com)"
gateway=$(grep nameserver /etc/resolv.conf | head -1 | cut -d' ' -f2)
assert "api.openai.com resolves to the proxy gateway" \
    bash -c "getent ahostsv4 api.openai.com | grep -q '$gateway'"
assert "proxy injects the real Bearer token" \
    bash -c "curl -sf https://api.openai.com/ | grep -q 'Bearer $REAL_KEY'"
echo ""

echo "========================================"
TOTAL=$((PASS + FAIL))
printf " Results: \033[1m%d/%d passed\033[0m" "$PASS" "$TOTAL"
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
