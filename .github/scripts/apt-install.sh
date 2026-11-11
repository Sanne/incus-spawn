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
# The runner's resolver has also failed every ubuntu.com name for hours at a time (#2146), and
# then each index waits out the resolver on every listed mirror.
# So the list gains a mirror outside ubuntu.com, and mirrors whose host does not resolve are
# dropped before the first attempt; the archive's signatures are checked whichever mirror serves it.
set -euo pipefail

if [ $# -eq 0 ]; then
    echo "usage: $0 <package>..." >&2
    exit 2
fi

ATTEMPTS=${APT_ATTEMPTS:-3}
PHASE_TIMEOUT=${APT_PHASE_TIMEOUT:-120}
MIRRORS=${APT_MIRRORS:-/etc/apt/apt-mirrors.txt}
# Empty for none.
FALLBACK_MIRROR=${APT_FALLBACK_MIRROR-https://mirrors.edge.kernel.org/ubuntu/}
RESOLVE_TIMEOUT=${APT_RESOLVE_TIMEOUT:-15}
# For a mirror that fails rather than stalls: apt's own retry, then the next mirror.
NET_OPTS=(-o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30)
export DEBIAN_FRONTEND=noninteractive

# Rewrites the mirror list with only the given line numbers, keeping the file's owner and mode;
# no `sed -i`, which BSD sed reads differently.
keep_lines() {
    awk -v keep=" $* " 'index(keep, " " NR " ")' "$MIRRORS" > "$MIRRORS.isx-tmp"
    cat "$MIRRORS.isx-tmp" > "$MIRRORS"
    rm -f "$MIRRORS.isx-tmp"
}

# Lists the fallback mirror last, then drops every mirror whose host does not resolve, unless none does.
prepare_mirrors() {
    [ -f "$MIRRORS" ] || return 0
    # The fallback carries amd64's archive only, so a ports list (arm64 runners) gets none.
    if [ -n "$FALLBACK_MIRROR" ] && ! grep -q 'ubuntu-ports' "$MIRRORS" \
        && ! awk -v m="$FALLBACK_MIRROR" '$1 == m { found = 1 } END { exit !found }' "$MIRRORS"; then
        local last
        [ ! -s "$MIRRORS" ] || [ -z "$(tail -c 1 "$MIRRORS")" ] || echo >> "$MIRRORS"
        # apt tries an entry without priority:N after every one with it, so then the fallback goes untagged.
        last=$(awk '!/^[[:space:]]*(#|$)/ {
                if (!match($0, /priority:[0-9]+/)) { untagged = 1; next }
                p = substr($0, RSTART + 9, RLENGTH - 9) + 0; if (p > max) max = p
            } END { print untagged ? "" : max + 0 }' "$MIRRORS")
        if [ -n "$last" ]; then
            printf '%s\tpriority:%d\n' "$FALLBACK_MIRROR" "$((last + 1))" >> "$MIRRORS"
        else
            printf '%s\n' "$FALLBACK_MIRROR" >> "$MIRRORS"
        fi
    fi
    local dir n=0 live=0 line host resolved=() dropped=()
    dir=$(mktemp -d)
    while IFS= read -r line; do
        n=$((n + 1))
        [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
        host=${line#"${line%%[![:space:]]*}"}
        host=${host%%[[:space:]]*}
        host=${host#*://}
        host=${host%%[/:]*}
        # in parallel: a host that does not resolve costs the resolver's whole timeout
        { timeout "$RESOLVE_TIMEOUT" getent ahosts "$host" > /dev/null && touch "$dir/$n"; } &
    done < "$MIRRORS"
    wait
    n=0
    while IFS= read -r line; do
        n=$((n + 1))
        if [[ "$line" =~ ^[[:space:]]*(#|$) ]]; then
            resolved+=("$n")
        elif [ -f "$dir/$n" ]; then
            resolved+=("$n")
            live=$((live + 1))
        else
            dropped+=("${line%%[[:space:]]*}")
        fi
    done < "$MIRRORS"
    rm -rf "$dir"
    [ ${#dropped[@]} -gt 0 ] || return 0
    if [ "$live" -eq 0 ]; then
        echo "::warning::apt-install: no mirror's host resolves, keeping them all: ${dropped[*]}"
        return 0
    fi
    echo "::warning::apt-install: dropping mirrors whose host does not resolve: ${dropped[*]}"
    keep_lines "${resolved[@]}"
}

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
    keep_lines $(awk -v n="$first" 'NR != n { print NR }' "$MIRRORS")
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

prepare_mirrors
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
