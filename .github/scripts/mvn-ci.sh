#!/bin/bash
# Maven in CI, as little dependent on Maven Central's rate limit as it can be (#2307).
# Maven Central answers a runner's address with 429 once it sees too many requests, and one 429
# failed a job whose cache had restored (#2272).
#
# Usage: mvn-ci.sh <exact-cache-hit: true|false> <mvn arguments...>
#
# After an exact hit on the job's own cache, the build runs offline: it sends no request, so no
# throttle can fail it. The cache is saved by the job that needs it, once that job succeeded, so
# it holds all the job needs. Should an offline build fail anyway, the cache was incomplete: it
# is said so with a warning, and the build runs again online with the difference to download.
# Without an exact hit the build runs online, and the job saves the cache it leaves.
#
# Online, the resolver waits and asks again when Maven Central answers 429 or 503: 8 retries,
# the pause growing from 5 s to at most 60 s. Its default retries 503 alone, 3 times. These
# properties are those of maven-resolver 1.9.x, which Maven 3.9 ships (checked on 3.9.16).

set -u

if [ $# -lt 2 ]; then
    echo "usage: mvn-ci.sh <true|false> <mvn arguments...>" >&2
    exit 2
fi
hit=$1
shift

RESOLVER_RETRY=(
    -Daether.connector.http.retryHandler.count=8
    -Daether.connector.http.retryHandler.interval=5000
    -Daether.connector.http.retryHandler.intervalMax=60000
    -Daether.connector.http.retryHandler.serviceUnavailable=429,503
)

if [ "$hit" = true ]; then
    log=$(mktemp)
    # Shown as it runs; the pipeline's status is tee's, so mvn's own is written beside the log
    { mvn -o "${RESOLVER_RETRY[@]}" "$@" 2>&1 && echo 0 >"$log.rc" || echo $? >"$log.rc"; } | tee "$log"
    rc=$(cat "$log.rc")
    # A build that failed for another reason (a test) is not run again: that would double a failing job
    if [ "$rc" -eq 0 ] || ! grep -q 'in offline mode' "$log"; then
        rm -f "$log" "$log.rc"
        exit "$rc"
    fi
    rm -f "$log" "$log.rc"
    echo "::warning::The offline build lacked an artifact under an exact Maven cache hit (status $rc), so the cache is incomplete; building again online"
fi
mvn "${RESOLVER_RETRY[@]}" "$@"
