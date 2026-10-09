#!/bin/bash
# Standalone integration test runner for incus-spawn.
#
# Prerequisites: isx and Incus must be installed and initialized (isx init).
# The MITM proxy must be running (isx proxy start).
#
# Usage:
#   .github/scripts/run-integration-tests.sh               # container only
#   .github/scripts/run-integration-tests.sh --with-vm      # container + VM
#
# What it does:
#   1. Builds tpl-minimal as a container (and optionally as a VM)
#   2. Branches an instance from each template
#   3. Pushes the test script into each instance and runs it
#   4. Cleans up all test instances
#
# The test script (.github/scripts/test-instance.sh) exercises 6 major
# isx features: proxy interception, git clone, sudo, systemd, DNS, and
# login shell environment.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_SCRIPT="$SCRIPT_DIR/test-instance.sh"
WITH_VM=false
FAILED=false

for arg in "$@"; do
    case "$arg" in
        --with-vm) WITH_VM=true ;;
        *) echo "Unknown argument: $arg"; exit 1 ;;
    esac
done

if [ ! -f "$TEST_SCRIPT" ]; then
    echo "Error: test script not found at $TEST_SCRIPT"
    exit 1
fi

cleanup() {
    echo ""
    echo "--- Cleanup ---"
    isx destroy isx-test-container 2>/dev/null || true
    if $WITH_VM; then
        isx destroy isx-test-vm 2>/dev/null || true
    fi
}
trap cleanup EXIT

run_tests() {
    local instance="$1"
    echo ""
    echo "--- Running tests in $instance ---"
    incus file push "$TEST_SCRIPT" "$instance/tmp/"
    incus exec "$instance" -- bash /tmp/test-instance.sh
}

# --- Container ---
echo "=== Building tpl-minimal (container) ==="
isx build tpl-minimal --yes

echo ""
echo "=== Branching and starting container ==="
isx branch isx-test-container --from tpl-minimal --no-start
incus start isx-test-container
echo "Waiting for container network..."
incus exec isx-test-container -- bash -c '
    systemctl start systemd-networkd 2>/dev/null
    for i in $(seq 1 30); do
        ip -4 -o addr show eth0 | grep -q "inet " && break
        sleep 0.5
    done'

run_tests isx-test-container || FAILED=true

bash "$SCRIPT_DIR/test-host-repos.sh" || FAILED=true

# --- VM (optional) ---
if $WITH_VM; then
    echo ""
    echo "=== Building tpl-test-vm ==="
    mkdir -p ~/.config/incus-spawn/images
    cat > ~/.config/incus-spawn/images/test-vm.yaml << 'VMEOF'
    name: tpl-test-vm
    parent: tpl-minimal
    type: vm
VMEOF
    isx build tpl-test-vm --yes

    echo ""
    echo "=== Branching and starting VM ==="
    isx branch isx-test-vm --from tpl-test-vm --no-start
    incus start isx-test-vm
    echo "Waiting for VM agent..."
    for i in $(seq 1 60); do
        if incus exec isx-test-vm -- true 2>/dev/null; then
            echo "VM agent ready after ${i}s"
            break
        fi
        sleep 1
    done

    run_tests isx-test-vm || FAILED=true
fi

if $FAILED; then
    echo ""
    echo "SOME TESTS FAILED"
    exit 1
fi

echo ""
echo "ALL TESTS PASSED"
