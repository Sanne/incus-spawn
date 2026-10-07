#!/bin/bash
# Tests podman with mode=rootless, short-names=permissive, compose=true.
# Validates the three podman tool parameters introduced alongside the
# existing rootful default (tested by test-podman.sh).
#
# Usage: incus file push test-podman-rootless.sh <instance>/tmp/
#        incus exec <instance> -- bash /tmp/test-podman-rootless.sh

set -uo pipefail

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
echo " Podman parameters integration test"
echo " (mode=rootless, short-names=permissive,"
echo "  compose=true)"
echo "========================================"
echo ""

echo "[1] DOCKER_HOST and socket setup (mode=rootless)"
assert_eq "DOCKER_HOST points to /var/run/docker.sock" "unix:///var/run/docker.sock" \
    su -l agentuser -c 'echo $DOCKER_HOST'
assert "/var/run/docker.sock is a symlink" \
    test -L /var/run/docker.sock
assert_eq "/var/run/docker.sock -> rootless socket" "/run/user/1000/podman/podman.sock" \
    readlink /var/run/docker.sock
assert "/run/docker.sock is a symlink" \
    test -L /run/docker.sock
assert_eq "/run/docker.sock -> rootless socket" "/run/user/1000/podman/podman.sock" \
    readlink /run/docker.sock
# The rootful system socket should NOT be enabled
assert "rootful podman.socket is not enabled" \
    sh -c "! systemctl is-enabled podman.socket 2>/dev/null"

echo ""
echo "[2] Short image names (short-names=permissive)"
# Pull using a short name -- without permissive mode this would prompt
echo "  Pulling alpine (short name, with retries)..."
alpine_pulled=false
for attempt in 1 2 3; do
    if su -l agentuser -c 'podman pull alpine' 2>&1; then
        alpine_pulled=true
        break
    fi
    echo "  Pull attempt $attempt failed, retrying..."
    sleep 2
done
if $alpine_pulled; then
    assert_eq "podman run with short name" "hello" \
        su -l agentuser -c "podman run --rm alpine echo hello"
else
    echo "  FAIL: podman pull alpine (short name) failed after 3 attempts"
    FAIL=$((FAIL + 1))
    ERRORS="${ERRORS}  - podman pull alpine (short name) failed\n"
fi

echo ""
echo "[3] podman-compose (compose=true)"
assert "podman-compose is installed" \
    su -l agentuser -c "podman-compose --version"

echo ""
echo "[4] Rootless PostgreSQL via short name"
echo "  Pulling postgres:17-alpine (with retries)..."
pull_ok=false
for attempt in 1 2 3; do
    if su -l agentuser -c 'podman pull postgres:17-alpine' 2>&1; then
        pull_ok=true
        break
    fi
    echo "  Pull attempt $attempt failed, retrying..."
    sleep 2
done

echo "  Starting PostgreSQL container as agentuser (rootless)..."
if ! $pull_ok; then
    echo "  FAIL: podman pull failed after 3 attempts"
    FAIL=$((FAIL + 1))
    ERRORS="${ERRORS}  - podman pull failed\n"
elif ! su -l agentuser -c '
    podman run -d --name test-pg \
        -e POSTGRES_PASSWORD=testpass \
        -p 15432:5432 \
        postgres:17-alpine
' 2>&1; then
    echo "  FAIL: podman run failed"
    FAIL=$((FAIL + 1))
    ERRORS="${ERRORS}  - podman run failed\n"
else
    echo "  Waiting for PostgreSQL to accept queries..."
    query_out=""
    for i in $(seq 1 60); do
        query_out=$(su -l agentuser -c \
            "podman exec test-pg psql -U postgres -tAc 'SELECT 1'" 2>/dev/null | tr -d '[:space:]')
        if [ "$query_out" = "1" ]; then
            echo "  PostgreSQL accepting queries after ${i}s"
            break
        fi
        sleep 1
    done
    if [ "$query_out" != "1" ]; then
        echo "  PostgreSQL did not accept queries within 60s"
        su -l agentuser -c "podman logs test-pg" 2>&1 | tail -20
    fi
    assert_eq "SELECT 1 returns 1" "1" echo "$query_out"
fi

echo ""
echo "  Cleaning up..."
su -l agentuser -c "podman rm -f test-pg" >/dev/null 2>&1

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
