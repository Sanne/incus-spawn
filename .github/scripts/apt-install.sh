#!/usr/bin/env bash
# Installs Ubuntu packages on a GitHub runner without letting a slow mirror eat the job (#1993).
#
#   sudo .github/scripts/apt-install.sh <package>...
#
# A runner's apt reads its archive from a mirror list (/etc/apt/apt-mirrors.txt), Azure's first.
# When that mirror crawls (seen at ~90 kB/s), apt keeps receiving data, so its own
# Acquire::http::Timeout never fires and a plain `apt-get update && apt-get install` runs into
# the job's timeout with no output.
# So the two network phases, the index update and the package download, are each bounded
# with `timeout`, and a phase that stalls or fails drops the mirror apt tried first and runs
# again.
# Killing either phase is safe: neither touches the installed system.
# The install itself runs only once everything is downloaded, with --no-download, so dpkg is
# never interrupted and never waits on the network.
set -euo pipefail

if [ $# -eq 0 ]; then
    echo "usage: $0 <package>..." >&2
    exit 2
fi

ATTEMPTS=${APT_ATTEMPTS:-3}
PHASE_TIMEOUT=${APT_PHASE_TIMEOUT:-120}
MIRRORS=${APT_MIRRORS:-/etc/apt/apt-mirrors.txt}
# For a mirror that fails rather than stalls: apt's own retry, then the next mirror.
NET_OPTS=(-o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30)
export DEBIAN_FRONTEND=noninteractive

# Drops the mirror apt tries first (the lowest priority:N, else the first line) when another remains.
drop_first_mirror() {
    [ -f "$MIRRORS" ] || return 0
    local entries first
    entries=$(grep -Ecv '^[[:space:]]*(#|$)' "$MIRRORS" || true)
    [ "$entries" -gt 1 ] || return 0
    first=$(awk '!/^[[:space:]]*(#|$)/ {
            p = 1e9
            if (match($0, /priority:[0-9]+/)) p = substr($0, RSTART + 9, RLENGTH - 9) + 0
            if (best == "" || p < bestp) { best = NR; bestp = p }
        } END { print best }' "$MIRRORS")
    echo "apt-install: dropping mirror $(awk -v n="$first" 'NR == n { print $1 }' "$MIRRORS")"
    # rewritten in place, keeping the file's owner and mode; no `sed -i`, which BSD sed reads differently
    awk -v n="$first" 'NR != n' "$MIRRORS" > "$MIRRORS.isx-tmp"
    cat "$MIRRORS.isx-tmp" > "$MIRRORS"
    rm -f "$MIRRORS.isx-tmp"
}

# Runs one network phase under the time bound; reports and fails when it stalls or fails.
phase() {
    local what=$1 rc=0
    shift
    timeout "$PHASE_TIMEOUT" "$@" || rc=$?
    if [ "$rc" -eq 124 ]; then
        echo "::warning::apt-install: $what did not finish within ${PHASE_TIMEOUT}s"
    elif [ "$rc" -ne 0 ]; then
        echo "::warning::apt-install: $what failed (exit $rc)"
    fi
    return "$rc"
}

for attempt in $(seq 1 "$ATTEMPTS"); do
    if phase "apt-get update (attempt $attempt)" apt-get -q "${NET_OPTS[@]}" update \
        && phase "downloading $* (attempt $attempt)" \
            apt-get -q "${NET_OPTS[@]}" install -y --download-only "$@"; then
        exec apt-get -qq install -y --no-download "$@"
    fi
    [ "$attempt" -lt "$ATTEMPTS" ] && drop_first_mirror
done
echo "::error::apt-install: no attempt of $ATTEMPTS installed $*"
exit 1
