#!/bin/bash
# Riding out a github.com outage in CI (#2042). GitHub has answered release downloads with 503
# and 504 and let connections time out for most of an hour, failing jobs no change caused.
# Neither helper weakens what it guards: a rerun verifies its download as the first run did,
# and the reachability check still needs a reply to arrive through the host's firewall.
#
# Sourced for its functions, or run as `net-retry.sh <function> <args...>`.

# Pauses, in seconds, before each rerun of a command whose download isx gave up on
NET_RETRY_PAUSES=${NET_RETRY_PAUSES:-60 120 240}
# Pauses, in seconds, between rounds of the HTTPS reachability probe
NET_RETRY_PROBE_PAUSES=${NET_RETRY_PROBE_PAUSES:-10 30}

# isx's DownloadCache, once its own retries of a transient failure (a 5xx, a 429, a broken
# connection) ran out: "Download failed: HTTP 504 from github.com for <url> (after 5 attempts)"
NET_RETRY_EXHAUSTED='Download failed: .* \(after [0-9]+ attempts\)'

# Runs a command, and runs it again after a pause while it failed only because isx's own
# download retries ran out. Any other failure is returned at once, unretried.
retry_download_outage() {
    local log rc pause
    log=$(mktemp)
    for pause in $NET_RETRY_PAUSES ""; do
        # Shown as it runs, so a build the job's timeout kills still leaves its output. The
        # pipeline's status is tee's: the command's own is written beside the log.
        { "$@" 2>&1 && echo 0 >"$log.rc" || echo $? >"$log.rc"; } | tee "$log"
        rc=$(cat "$log.rc")
        if [ "$rc" -eq 0 ] || ! grep -Eq "$NET_RETRY_EXHAUSTED" "$log"; then
            rm -f "$log" "$log.rc"
            return "$rc"
        fi
        [ -n "$pause" ] || break
        echo "net-retry: the download failed on every attempt; running '$*' again in ${pause}s"
        sleep "$pause"
    done
    rm -f "$log" "$log.rc"
    echo "net-retry: the download failed on every run of '$*'"
    return "$rc"
}

# Any HTTP status from any of the URLs proves a reply came back through the host's firewall.
# Each round asks every URL, so github.com being down does not read as the host blocking
# HTTPS, and says which it was; only when none answers in any round does the check fail.
https_reply_from_any() {
    local pause url code err first=${1:?https_reply_from_any needs a URL}
    err=$(mktemp)
    for pause in $NET_RETRY_PROBE_PAUSES ""; do
        for url in "$@"; do
            code=$(curl -sS -m 20 -o /dev/null -w '%{http_code}' "$url" 2>"$err") || true
            if [ -n "$code" ] && [ "$code" != 000 ]; then
                [ "$url" = "$first" ] || echo "net-retry: $first did not reply but $url did: the host passes HTTPS replies"
                rm -f "$err"
                return 0
            fi
            echo "net-retry: no reply from $url: $(tr '\n' ' ' <"$err")"
        done
        [ -n "$pause" ] || break
        sleep "$pause"
    done
    rm -f "$err"
    echo "net-retry: none of $* replied in any round: the host does not pass HTTPS replies"
    return 1
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    case "${1:-}" in
        retry_download_outage|https_reply_from_any) "$@" ;;
        *) echo "usage: $0 retry_download_outage <command...> | https_reply_from_any <url...>" >&2; exit 2 ;;
    esac
fi
