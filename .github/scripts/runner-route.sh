#!/bin/bash
# Where a job of test-integration.yml runs: one of the project's own runners, or GitHub's (#2298, #2519).
#
#   mac     unit-tests-macos and launcher-macos: the project's Mac Mini, or GitHub's macos-15
#   linux   unit-tests: a fresh isx instance on the project's Linux server, or GitHub's ubuntu-26.04
#
# The project's runner takes a job only when all of these hold; anything else, or anything unknown,
# is GitHub's runner:
#   - the code is trusted: a push, schedule or workflow_dispatch of this repository, or a pull
#     request whose head lives in a repository of self-hosted/trusted-heads that Dependabot did not open;
#   - the runner is up: its heartbeat workflow completed a run within the last 25 minutes, and no run
#     of it has waited more than 5 minutes for the runner;
#   - the runner is free: no job of another run is queued for it or running on it. Each has one
#     runner, so a second run's jobs would wait behind the first's where GitHub's runs them at once.
#
# Routing is a convenience, not the protection: a pull request's own copy of the workflow decides
# its runs-on, so each runner's job-started hook refuses untrusted code by itself (the Mini's in
# isx-workflow, MAC-SETUP.md "The CI runner user"; the Linux one in .github/linux-runner/).
#
#   runner-route.sh mac|linux     reads the run's context from the environment, prints runner=<json>
#   runner-route.sh mac|linux decide EVENT HEAD_REPO ACTOR NOW LAST_COMPLETED OLDEST_QUEUED BUSY_JOBS
#                                 the decision alone, the times in epoch seconds ("" for none),
#                                 BUSY_JOBS the other runs' jobs on the runner ("" if unknown)
#
# Bash 3.2: unit-tests-macos runs its test on macOS.

set -u

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TRUSTED_HEADS="$here/../self-hosted/trusted-heads"

FRESH_SECONDS=1500   # a heartbeat completed this recently: the runner is up
WAITING_SECONDS=300  # a heartbeat waiting this long: the runner is busy or gone

# Per runner: the labels it is registered with, GitHub's runner, the label that tells its jobs
# apart, its heartbeat's workflow and run title, and the workflows with a job that can run on it,
# which MacRunnerWorkflowTest and LinuxRunnerTest hold equal to the workflows, so a new routed workflow fails it until
# added here.
case "${1:-}" in
    mac)
        OWN='["self-hosted","macOS","ARM64","isx-mac"]'
        HOSTED='"macos-15"'
        LABEL=isx-mac
        HEARTBEAT=mac-heartbeat.yml
        HEARTBEAT_TITLE="Mac runner heartbeat"
        MAC_WORKFLOWS="test-integration.yml mac-heartbeat.yml"
        WORKFLOWS=$MAC_WORKFLOWS
        WHERE_OWN="the project's Mac runner"
        WHERE_HOSTED="GitHub's macos-15"
        ;;
    linux)
        OWN='["self-hosted","Linux","X64","isx-linux"]'
        HOSTED='"ubuntu-26.04"'
        LABEL=isx-linux
        HEARTBEAT=linux-heartbeat.yml
        HEARTBEAT_TITLE="Linux runner heartbeat"
        LINUX_WORKFLOWS="test-integration.yml linux-heartbeat.yml"
        WORKFLOWS=$LINUX_WORKFLOWS
        WHERE_OWN="the project's Linux runner"
        WHERE_HOSTED="GitHub's ubuntu-26.04"
        ;;
    *)
        echo "usage: runner-route.sh mac|linux [decide EVENT HEAD_REPO ACTOR NOW LAST_COMPLETED OLDEST_QUEUED BUSY_JOBS]" >&2
        exit 2
        ;;
esac
shift

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

trusted() {
    local event=$1 head=$2 actor=$3
    case "$event" in
        push|schedule|workflow_dispatch) return 0 ;;
        pull_request) trusted_head "$head" && [ "$actor" != "dependabot[bot]" ] ;;
        *) return 1 ;;
    esac
}

decide() {
    local event=$1 head=$2 actor=$3 now=$4 completed=$5 queued=$6 busy=$7
    trusted "$event" "$head" "$actor" || { echo "$HOSTED"; return; }
    case "$completed" in ''|*[!0-9]*) echo "$HOSTED"; return ;; esac
    [ $((now - completed)) -le "$FRESH_SECONDS" ] || { echo "$HOSTED"; return; }
    case "$queued" in
        '') ;;
        *[!0-9]*) echo "$HOSTED"; return ;;
        *) [ $((now - queued)) -lt "$WAITING_SECONDS" ] || { echo "$HOSTED"; return; } ;;
    esac
    [ "$busy" = 0 ] || { echo "$HOSTED"; return; }
    echo "$OWN"
}

if [ "${1:-}" = decide ]; then
    [ $# -eq 8 ] || { echo "usage: runner-route.sh mac|linux decide EVENT HEAD_REPO ACTOR NOW LAST_COMPLETED OLDEST_QUEUED BUSY_JOBS" >&2; exit 2; }
    shift
    decide "$@"
    exit 0
fi

: "${GITHUB_OUTPUT:?}" "${GITHUB_REPOSITORY:?}" "${EVENT_NAME:?}"

say() {
    echo "runner=$1" >> "$GITHUB_OUTPUT"
    if [ "$1" = "$HOSTED" ]; then echo "The job runs on $WHERE_HOSTED"; else echo "The job runs on $WHERE_OWN"; fi
}

# Untrusted code goes to GitHub's runner without a request: a fork's pull request costs nothing more.
if ! trusted "$EVENT_NAME" "${HEAD_REPO:-}" "${ACTOR:-}"; then
    say "$HOSTED"
    exit 0
fi

now=$(date +%s)
completed=""
queued=""
# Read with the run's own read-only token; a fork's run reads these as well as any other. Only runs
# titled as the heartbeat count: the workflow titles a run with its switch off otherwise.
# No heartbeat workflow, an API error or an empty answer all leave the times empty: GitHub's runner.
runs=$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/$HEARTBEAT/runs?per_page=20" \
    --jq '.workflow_runs | map(select(.display_title == "'"$HEARTBEAT_TITLE"'"))
          | [(map(select(.status == "completed" and .conclusion == "success")) | map(.updated_at | fromdate) | max // ""),
             (map(select(.status == "queued" or .status == "pending" or .status == "waiting")) | map(.created_at | fromdate) | min // "")]
          | map(tostring) | join(",")' 2>/dev/null) || runs=""
IFS=, read -r completed queued <<< "$runs"

# The other runs' jobs queued for the runner or running on it, heartbeats included; "" when any read
# fails. Only runs of WORKFLOWS are listed: no other can hold the runner, and every call counts
# against the repository's hourly token budget.
busy_jobs() {
    local ids="" id n workflow status total=0
    for workflow in $WORKFLOWS; do
        for status in in_progress queued; do
            id=$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/$workflow/runs?status=$status&per_page=50" \
                --jq '.workflow_runs[].id' 2>/dev/null) || return 0
            ids="$ids $id"
        done
    done
    for id in $ids; do
        [ "$id" != "${GITHUB_RUN_ID:-}" ] || continue
        n=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$id/jobs?per_page=100" \
            --jq '[.jobs[] | select((.labels | index("'"$LABEL"'")) and (.status == "queued" or .status == "in_progress" or .status == "waiting"))] | length' 2>/dev/null) || return 0
        case "$n" in ''|*[!0-9]*) return 0 ;; esac
        total=$((total + n))
    done
    echo "$total"
}
busy=""
if [ -n "${completed:-}" ]; then busy=$(busy_jobs); fi   # no heartbeat: GitHub's runner whatever the jobs
say "$(decide "$EVENT_NAME" "${HEAD_REPO:-}" "${ACTOR:-}" "$now" "${completed:-}" "${queued:-}" "$busy")"
