#!/bin/bash
# The Linux runner's job-started hook (#2519): refuse untrusted code before any step runs.
#
# The runner runs in an isx instance made for one job and destroyed after it, so there is no lock and
# nothing to clean up: the hook only decides whether the job may run here. It exits 1, and GitHub
# fails the job before its first step, unless the job is this repository's own push, schedule or
# workflow_dispatch, or a pull request into it whose head repository is in trusted-heads and which
# Dependabot did not open. Nothing Dependabot started runs here.
#
# A pull request's copy of the workflow decides its runs-on, so this is the check that holds, not
# the workflow's routing (runner-route.sh). It reads the event GitHub wrote for the job and its own
# list beside it, never the pull request's: the template's prime installs both as root, from its
# clone of main, into /opt/isx-ci (.github/linux-runner/images/tpl-isx-ci-linux.yaml), and the
# runner's .env names this file as ACTIONS_RUNNER_HOOK_JOB_STARTED. No variable points it elsewhere.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO=Sanne/incus-spawn
JQ=/usr/bin/jq

refuse() { echo "::error::The project's Linux runner refused this job: $*"; exit 1; }

listed() {   # listed FILE VALUE: VALUE is a line of FILE (comments and blanks aside)
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"; line="$(printf '%s' "$line" | tr -d '[:space:]')"
    [ -n "$line" ] && [ "$line" = "$2" ] && return 0
  done < "$1"
  return 1
}

[ "${GITHUB_REPOSITORY:-}" = "$REPO" ] || refuse "a run of ${GITHUB_REPOSITORY:-an unknown repository}, not $REPO"
[ -f "$here/trusted-heads" ] || refuse "no trusted-heads list is installed beside the hook"
[ "${GITHUB_ACTOR:-}" != "dependabot[bot]" ] || refuse "a run Dependabot started"
[ "${GITHUB_TRIGGERING_ACTOR:-}" != "dependabot[bot]" ] || refuse "a run Dependabot started"
case "${GITHUB_EVENT_NAME:-}" in
  push|schedule|workflow_dispatch) echo "The project's Linux runner takes this job"; exit 0 ;;
  pull_request) ;;
  *) refuse "the event ${GITHUB_EVENT_NAME:-(none)} never runs here" ;;
esac
[ -x "$JQ" ] || refuse "$JQ is missing, so the event cannot be read"
[ -r "${GITHUB_EVENT_PATH:-}" ] || refuse "the job's event payload cannot be read"
head="$("$JQ" -r '.pull_request.head.repo.full_name // ""' "$GITHUB_EVENT_PATH" 2>/dev/null)" || refuse "the event payload is not JSON"
base="$("$JQ" -r '.pull_request.base.repo.full_name // ""' "$GITHUB_EVENT_PATH")"
author="$("$JQ" -r '.pull_request.user.login // ""' "$GITHUB_EVENT_PATH")"
[ "$base" = "$REPO" ] || refuse "a pull request into ${base:-an unknown repository}"
{ [ -n "$head" ] && listed "$here/trusted-heads" "$head"; } || refuse "a pull request from ${head:-a deleted repository}, which is not trusted here"
[ "$author" != "dependabot[bot]" ] || refuse "a Dependabot pull request"
echo "The project's Linux runner takes this job"
