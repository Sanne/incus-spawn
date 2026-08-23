#!/bin/bash
# Integration tests for the aider tool setup.
# Verifies aider is installed, configured, and env vars are set.
#
# Usage: incus file push test-aider.sh <instance>/tmp/
#        incus exec <instance> -- bash /tmp/test-aider.sh

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

echo "========================================"
echo " aider tool integration tests"
echo "========================================"
echo ""

# --- 1. Binary installed and in PATH ---
echo "[1] aider binary"
assert "aider is installed in /usr/local/bin" \
    test -x /usr/local/bin/aider
assert "aider is on PATH" \
    bash -c "which aider"
assert "aider --version runs successfully" \
    bash -c "aider --version"
echo ""

# --- 2. Configuration file ---
echo "[2] aider configuration"
assert "config file exists" \
    test -f /home/agentuser/.aider.conf.yml
assert "config file is owned by agentuser" \
    bash -c "stat -c '%U' /home/agentuser/.aider.conf.yml | grep -q agentuser"
assert "auto-commits disabled" \
    bash -c "grep -q 'auto-commits: false' /home/agentuser/.aider.conf.yml"
assert "update check disabled" \
    bash -c "grep -q 'check-update: false' /home/agentuser/.aider.conf.yml"
assert "analytics disabled" \
    bash -c "grep -q 'analytics-disable: true' /home/agentuser/.aider.conf.yml"
assert "yes-always enabled" \
    bash -c "grep -q 'yes-always: true' /home/agentuser/.aider.conf.yml"
assert "model set to anthropic/claude-sonnet-4-6" \
    bash -c "grep -q 'model: anthropic/claude-sonnet-4-6' /home/agentuser/.aider.conf.yml"
echo ""

# --- 3. Environment variables ---
echo "[3] Environment variables"
assert "ANTHROPIC_API_KEY is set in login shell" \
    su -l agentuser -c 'bash -c "source ~/.bashrc 2>/dev/null; test -n \"\$ANTHROPIC_API_KEY\""'
assert_eq "ANTHROPIC_API_KEY is the proxy placeholder" "sk-ant-placeholder" \
    su -l agentuser -c 'bash -c "source ~/.bashrc 2>/dev/null; echo \$ANTHROPIC_API_KEY"'
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
