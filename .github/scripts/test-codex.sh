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
# The selected profile must be the one whose tables grant full access.
PROFILE=$(sed -n 's/^default_permissions = "\(.*\)"$/\1/p' "$CONFIG")
# Succeeds if TOML table header $1 exists and its body (up to the next header)
# holds line $2; both matched literally. Scoped to the table, because the same
# line in another table grants the selected profile nothing. Bash only: the
# guest image has no awk.
table_has() {
    local line in_t=0
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$line" = "$1" ]; then in_t=1
        elif [[ $line == '['* ]]; then in_t=0
        elif [ "$in_t" = 1 ] && [ "$line" = "$2" ]; then return 0
        fi
    done < "$CONFIG"
    return 1
}
assert "the isx permission profile is selected" \
    grep -q '^default_permissions = "Running unconstrained in isx ' "$CONFIG"
assert "the selected profile writes the whole filesystem (the container is the sandbox)" \
    table_has "[permissions.\"$PROFILE\".filesystem]" '":root" = "write"'
assert "the selected profile has network access" \
    table_has "[permissions.\"$PROFILE\".network]" 'enabled = true'
assert "live web search (a custom profile defaults to cached)" \
    grep -q '^web_search = "live"$' "$CONFIG"
assert "the footer keeps model and directory beside the permissions" \
    grep -q '^status_line = \["model-with-reasoning", "current-dir", "permissions"\]$' "$CONFIG"
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
# A login refreshes auth.json from this start's environment.
PROOF=$(su -l agentuser -c 'printf "%s" "$OPENAI_API_KEY"')
assert "the login shell holds a namespace proof" \
    bash -c '[[ "$1" =~ ^sk-isx_[0-9a-f]{32}$ ]]' _ "$PROOF"
assert "auth.json holds the current proof" \
    grep -Fq "\"OPENAI_API_KEY\": \"$PROOF\"" "$AUTH"
assert "the real API key is nowhere under ~/.codex" \
    bash -c "! grep -rq '$REAL_KEY' /home/agentuser/.codex"
assert "the real API key is not in the environment" \
    bash -c "! su -l agentuser -c env | grep -q '$REAL_KEY'"
echo ""

# --- 4. Codex accepts the generated config ---
# One `codex exec` run, against a provider on a closed local port so no
# request leaves; it never gets an answer and runs out the timeout.
echo "[4] Config Parsing"
EXEC_OUT=$(su -l agentuser -c 'timeout 10 codex exec --skip-git-repo-check -c model_provider=isx-test \
    -c "model_providers.isx-test={name=\"isx-test\",base_url=\"http://127.0.0.1:9/v1\",wire_api=\"responses\"}" \
    test </dev/null' 2>&1)
# It must not fail on config parsing -- that was the "full-auto" regression.
assert "codex loads config.toml without errors" \
    bash -c '! grep -q "Error loading config" <<<"$1"' _ "$EXEC_OUT"
# Full access rests on how Codex classifies the custom profile, which a Codex
# upgrade can change. `codex exec` prints the effective policy before its first
# request: a narrower one reads "read-only" or "external-sandbox" (0.162.1).
assert_eq "Codex runs the selected profile as danger-full-access" "sandbox: danger-full-access" \
    bash -c 'grep -m1 "^sandbox: " <<<"$1"' _ "$EXEC_OUT"
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
