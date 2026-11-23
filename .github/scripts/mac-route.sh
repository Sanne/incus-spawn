#!/bin/bash
# Where test-integration.yml's macOS jobs run: the project's own Mac Mini, or GitHub's macos-15 (#2298).
#
# The Mini runs a job only when all of these hold; anything else, or anything unknown, is macos-15:
#   - the code is trusted: a push, schedule or workflow_dispatch of this repository, or a pull
#     request whose head lives in a repository of mac-runner/trusted-heads that Dependabot did not open;
#   - the Mini is up: its heartbeat workflow (mac-heartbeat.yml) completed a run within the last
#     25 minutes, and no run of it has waited more than 5 minutes for the runner;
#   - the Mini is free: no job of another run is queued for it or running on it. It has one runner,
#     so a second run's jobs would wait behind the first's where macos-15 runs them at once.
#
# Routing is a convenience, not the protection: a pull request's own copy of this workflow decides
# its runs-on, so the Mini's job-started hook refuses untrusted code by itself (isx-workflow,
# MAC-SETUP.md "The CI runner user").
#
#   mac-route.sh                  reads the run's context from the environment, prints runner=<json>
#   mac-route.sh decide EVENT HEAD_REPO ACTOR NOW LAST_COMPLETED OLDEST_QUEUED MINI_JOBS
#                                 the decision alone, the times in epoch seconds ("" for none),
#                                 MINI_JOBS the other runs' jobs on the Mini ("" if unknown)
#
# Bash 3.2: unit-tests-macos runs its test on macOS.

set -u

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TRUSTED_HEADS="$here/../mac-runner/trusted-heads"

# The labels the Mini's runner is registered with, and GitHub's runner.
MINI='["self-hosted","macOS","ARM64","isx-mac"]'
HOSTED='"macos-15"'

FRESH_SECONDS=1500   # a heartbeat completed this recently: the Mini is up
WAITING_SECONDS=300  # a heartbeat waiting this long: the Mini is busy or gone

trusted_head() {
    local repo=$1 line
    [ -n "$repo" ] || return 1
    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%%#*}
        line=$(printf '%s' "$line" | tr -d '[:space:]')
        [ -n "$line" ] && [ "$line" = "$repo" ] && return 0
    done < "$TRUSTED_HEADS"
    return 1
}

decide() {
    local event=$1 head=$2 actor=$3 now=$4 completed=$5 queued=$6 busy=$7
    case "$event" in
        push|schedule|workflow_dispatch) ;;
        pull_request)
            trusted_head "$head" || { echo "$HOSTED"; return; }
            [ "$actor" != "dependabot[bot]" ] || { echo "$HOSTED"; return; }
            ;;
        *) echo "$HOSTED"; return ;;
    esac
    case "$completed" in ''|*[!0-9]*) echo "$HOSTED"; return ;; esac
    [ $((now - completed)) -le "$FRESH_SECONDS" ] || { echo "$HOSTED"; return; }
    case "$queued" in
        '') ;;
        *[!0-9]*) echo "$HOSTED"; return ;;
        *) [ $((now - queued)) -lt "$WAITING_SECONDS" ] || { echo "$HOSTED"; return; } ;;
    esac
    [ "$busy" = 0 ] || { echo "$HOSTED"; return; }
    echo "$MINI"
}

if [ "${1:-}" = decide ]; then
    [ $# -eq 8 ] || { echo "usage: mac-route.sh decide EVENT HEAD_REPO ACTOR NOW LAST_COMPLETED OLDEST_QUEUED MINI_JOBS" >&2; exit 2; }
    shift
    decide "$@"
    exit 0
fi

: "${GITHUB_OUTPUT:?}" "${GITHUB_REPOSITORY:?}" "${EVENT_NAME:?}"
now=$(date +%s)
completed=""
queued=""
# Read with the run's own read-only token; a fork's run reads these as well as any other. Only runs
# titled "Mac runner heartbeat" count: the workflow titles a run with its switch off otherwise.
# No heartbeat workflow, an API error or an empty answer all leave the times empty: macos-15.
runs=$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/mac-heartbeat.yml/runs?per_page=20" \
    --jq '.workflow_runs | map(select(.display_title == "Mac runner heartbeat"))
          | [(map(select(.status == "completed" and .conclusion == "success")) | map(.updated_at | fromdate) | max // ""),
             (map(select(.status == "queued" or .status == "pending" or .status == "waiting")) | map(.created_at | fromdate) | min // "")]
          | map(tostring) | join(",")' 2>/dev/null) || runs=""
IFS=, read -r completed queued <<< "$runs"

# The other runs' jobs queued for the Mini or running on it, heartbeats included; "" when any read fails.
mini_jobs() {
    local ids id n total=0
    ids=$( { gh api "repos/$GITHUB_REPOSITORY/actions/runs?status=in_progress&per_page=50" --jq '.workflow_runs[].id' &&
             gh api "repos/$GITHUB_REPOSITORY/actions/runs?status=queued&per_page=50" --jq '.workflow_runs[].id'; } 2>/dev/null) || return 0
    for id in $ids; do
        [ "$id" != "${GITHUB_RUN_ID:-}" ] || continue
        n=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$id/jobs?per_page=100" \
            --jq '[.jobs[] | select((.labels | index("isx-mac")) and (.status == "queued" or .status == "in_progress" or .status == "waiting"))] | length' 2>/dev/null) || return 0
        case "$n" in ''|*[!0-9]*) return 0 ;; esac
        total=$((total + n))
    done
    echo "$total"
}
busy=""
if [ -n "${completed:-}" ]; then busy=$(mini_jobs); fi   # no heartbeat: macos-15 whatever the jobs
runner=$(decide "$EVENT_NAME" "${HEAD_REPO:-}" "${ACTOR:-}" "$now" "${completed:-}" "${queued:-}" "$busy")
echo "runner=$runner" >> "$GITHUB_OUTPUT"
if [ "$runner" = "$HOSTED" ]; then
    echo "macOS jobs run on GitHub's macos-15"
else
    echo "macOS jobs run on the project's Mac runner"
fi
