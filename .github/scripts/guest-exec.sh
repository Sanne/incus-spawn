#!/bin/bash
# incus exec, past Incus failing to attach to a guest that just started (#2084).
# Moments after a container came up, incusd has answered an exec with "Failed to retrieve PID of
# executing child process" (exit 255): its forkexec helper could not attach to the guest, so the
# command never ran. Running it again is then safe, whatever the command does.
# Any other result, the command's own failure included, is returned as it is.
#
# Sourced for its function, or run as `guest-exec.sh <instance> -- <command...>`.

# Pauses, in seconds, before each new try of an exec Incus could not attach
GUEST_EXEC_PAUSES=${GUEST_EXEC_PAUSES:-1 2 4}

# incusd's message, from Exec in internal/server/instance/drivers/driver_lxc.go
GUEST_EXEC_NO_ATTACH='Failed to retrieve PID of executing child process'

# Takes the arguments of `incus exec`. The command's stdout passes straight through; its stderr is
# held until the try ends, since only then is it known whether the try counts.
guest_exec() {
    local err rc pause
    err=$(mktemp)
    for pause in $GUEST_EXEC_PAUSES ""; do
        rc=0
        incus exec "$@" 2>"$err" || rc=$?
        if [ "$rc" -ne 255 ] || ! grep -qF "$GUEST_EXEC_NO_ATTACH" "$err" || [ -z "$pause" ]; then
            break
        fi
        echo "guest-exec: Incus could not attach to $1; trying again in ${pause}s" >&2
        # Why, in liblxc's words: incusd keeps only the exit status, and the log goes with the instance
        sudo -n cat "/var/log/incus/$1/forkexec.log" 2>/dev/null | sed 's/^/guest-exec:   /' >&2 || true
        sleep "$pause"
    done
    cat "$err" >&2
    rm -f "$err"
    return "$rc"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    guest_exec "$@"
fi
