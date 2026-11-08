#!/bin/bash
# Runs as root in a guest that may still be booting: waits for systemd, then for a lingering
# user's manager, which owns /run/user/<uid> and the user bus rootless Podman needs (#2048).
# It never starts the manager itself, so a build that stops enabling lingering still fails.
# Usage: incus exec <instance> -- bash -s <user> < wait-for-user-manager.sh
set -uo pipefail
user=${1:?usage: wait-for-user-manager.sh <user>}
uid=$(id -u "$user") || exit 1
# Waits up to 30 seconds for a command to succeed.
wait_for() {
    local what=$1 n
    shift
    for n in $(seq 1 60); do
        "$@" >/dev/null 2>&1 && return 0
        sleep 0.5
    done
    echo "$what within 30s" >&2
    return 1
}
# Right after a start, PID 1 may not answer on the system bus yet.
wait_for "systemd did not answer on the system bus" systemctl show-environment || exit 1
# Boot starts a lingering user's manager, possibly after the guest answers.
wait_for "user@$uid.service did not become active (is $user lingering?)" \
    systemctl is-active --quiet "user@$uid.service"
