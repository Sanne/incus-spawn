#!/bin/bash
# What CI's Maven checks ask of the host (#2447), sourced by test-host-distro.sh and the native job.
# Maven Central blocks a runner's shared address for hours, so a cold download comes from Google's
# mirror of Central (CI's config.yaml names it in `maven-mirrors:`), and Central is asked exactly
# once per run: the proxy confirms the mirrored copy of the test jar at its next use.

# The exit code of test-instance.sh when its only failures were Maven Central's rate limit
THROTTLED_EXIT=75

# The `maven-mirrors:` section CI's config.yaml gets, for both of Central's names.
maven_ci_mirrors_yaml() {
    local mirror=https://maven-central.storage-download.googleapis.com/maven2/ repository
    echo "maven-mirrors:"
    for repository in https://repo.maven.apache.org/maven2/ https://repo1.maven.org/maven2/; do
        echo "  - repository: $repository"
        echo "    mirror: $mirror"
    done
}

# When the instance's only failures are such throttles, this host asks Maven Central itself, without
# the proxy and from the same address: a throttle here too (a 429, or the 404 with a Retry-After
# that Central's edge sends, #2422) is the runner being throttled, so the check passes with a
# warning in the run's summary; any other answer means only the proxied requests were refused,
# which is the proxy's fault.
maven_throttle_tiebreak() {
    local url=${MAVEN_CONTROL_URL:-https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom}
    local headers status retry_after
    headers=$(mktemp)
    status=$(curl -sS -m 30 -D "$headers" -o /dev/null -w '%{http_code}' "$url") || status=none
    retry_after=$(grep -i '^retry-after:' "$headers" | tail -n 1 | cut -d: -f2- | tr -d '\r ') || true
    rm -f "$headers"
    if [ "$status" = 429 ] || { [ "$status" = 404 ] && [[ "$retry_after" =~ ^[1-9][0-9]*$ ]]; }; then
        echo "::warning title=Maven Central throttled this runner::HTTP $status through the proxy and directly from the host: the Maven downloads were not checked on this leg"
        return 0
    fi
    if [ "$status" = none ]; then
        echo "The host could not reach Maven Central directly either, so the proxied throttles cannot be told apart from a proxy fault"
        return 1
    fi
    echo "Maven Central answered the host directly with HTTP $status while every proxied request was throttled: the proxy's fault"
    return 1
}

# Grades what the proxy logged, after byte $2 of its log $1, for the test jar's two fetches: the
# cold one came from the mirror, and the second one made the proxy confirm the copy with Central.
# Its cache summary says "confirmed first" when Central answered, and "unconfirmed" when Central
# was throttled, which warns: the mirror still worked. Anything else fails.
grade_maven_proxy_log() {
    local log=$1 offset=$2 seen i
    for i in $(seq 1 "${GRADE_WAIT:-20}"); do
        seen=$(tail -c "+$((offset + 1))" "$log")
        grep -qE 'Maven/Gradle cache: .*(confirmed first|unconfirmed)' <<<"$seen" && break
        sleep 1
    done
    if ! grep -qE 'Fetching .*junit.* from its mirror' <<<"$seen"; then
        echo "The proxy log does not say the test jar came from the mirror: CI's maven-mirrors: is not in effect"
        return 1
    fi
    if grep -qE 'Maven/Gradle cache: .*confirmed first' <<<"$seen"; then
        echo "The mirrored test jar was confirmed with Maven Central"
        return 0
    fi
    if grep -qE 'Maven/Gradle cache: .*unconfirmed' <<<"$seen"; then
        echo "::warning title=Maven Central throttled this runner::The proxy could not confirm the mirrored test jar with Maven Central, so that check was skipped on this leg"
        return 0
    fi
    echo "The proxy log has no cache summary saying Maven Central confirmed the mirrored test jar"
    return 1
}
