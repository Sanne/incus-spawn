#!/bin/bash
# Tests rootless podman inside an incus-spawn container.
# Validates subordinate UID/GID support (security.idmap.size + subuid/subgid)
# by running a PostgreSQL container as agentuser without root privileges.
#
# Usage: incus file push test-podman.sh <instance>/tmp/
#        incus exec <instance> -- bash /tmp/test-podman.sh

set -uo pipefail

PASS=0
FAIL=0
ERRORS=""

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
echo " Rootless podman integration test"
echo "========================================"
echo ""

echo "[1] Rootless PostgreSQL via podman"

# Diagnostics — helps debug user namespace issues
echo "  subuid: $(cat /etc/subuid)"
echo "  subgid: $(cat /etc/subgid)"
echo "  newuidmap: $(which newuidmap 2>&1 || echo 'not found')"
echo "  newuidmap perms: $(ls -la "$(which newuidmap 2>/dev/null)" 2>&1 || echo 'n/a')"
echo "  user_namespaces max: $(cat /proc/sys/user/max_user_namespaces 2>/dev/null || echo 'unknown')"
echo "  unprivileged_userns_clone: $(cat /proc/sys/kernel/unprivileged_userns_clone 2>/dev/null || echo 'not present')"
echo "  apparmor: $(cat /sys/module/apparmor/parameters/enabled 2>/dev/null || echo 'unknown')"
su -l agentuser -c "id"
echo "  unshare --user test:"
su -l agentuser -c "unshare --user true" 2>&1 && echo "    OK" || echo "    FAILED"
su -l agentuser -c "podman info --format '{{.Host.IDMappings.UIDMap}}'" 2>&1 || true

# Run PostgreSQL as agentuser (rootless podman).
# This requires working subordinate UID/GID mappings:
# - security.idmap.size must cover the subordinate range
# - /etc/subuid and /etc/subgid must have entries for agentuser
echo "  Pulling PostgreSQL image (with retries)..."
pull_ok=false
for attempt in 1 2 3; do
    if su -l agentuser -c 'podman pull docker.io/library/postgres:17-alpine' 2>&1; then
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
        docker.io/library/postgres:17-alpine
' 2>&1; then
    echo "  FAIL: podman run failed (see diagnostics above)"
    FAIL=$((FAIL + 1))
    ERRORS="${ERRORS}  - podman run failed\n"
else
    # Poll the query rather than pg_isready: during initdb the postgres
    # entrypoint runs a temporary bootstrap server on the same unix socket,
    # so pg_isready reports ready before the real server has restarted, and a
    # query issued in that gap comes back empty.
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
