#!/bin/bash
# incus-spawn's self-hosted Linux runner: one fresh isx instance per job (#2519).
# Runs on the Linux server as the user that runs isx, under the systemd user unit beside this file
# (docs/dev/LINUX-CI-SETUP.md).
#
#   isx-ci-runner.sh run       the loop: register a just-in-time runner, branch an instance of the
#                              template, run the runner in it for one job, destroy the instance, again
#   isx-ci-runner.sh once      one job that way, then exit: to try the setup by hand
#   isx-ci-runner.sh pause     take no new job: the running one finishes, an idle runner goes now,
#                              and a heartbeat is queued so the route sends new jobs to GitHub at once.
#                              The pause holds every slot, but ends only this slot's idle runner:
#                              with more slots, run it with each ISX_CI_SLOT
#   isx-ci-runner.sh resume    take jobs again
#   isx-ci-runner.sh status    paused or not, the instance and its runner
#
# A just-in-time runner takes exactly one job and is then removed by GitHub, so no job sees what
# another left: its instance is destroyed with it, and the next one is a fresh copy of the template.
# Its configuration is a credential for that one job; it reaches the instance on the exec's stdin,
# never on the host's command line.
#
# Settings, from the environment (the unit sets them):
#   ISX_CI_SLOT        the slot's number, in the instance's name isx-ci-linux-<slot>   (default 1)
#   ISX_CI_TEMPLATE    the template                                    (default tpl-isx-ci-linux)
#   ISX_CI_CPUS        CPU cores for the instance                                    (default 8)
#   ISX_CI_MEMORY      memory for the instance                                    (default 16GiB)
#   ISX_CI_TOKEN_FILE  a GitHub token, mode 600, with Administration and Actions write on the
#                      repository alone            (default ~/.config/isx-ci-runner/token)

set -u

REPO=Sanne/incus-spawn
SLOT=${ISX_CI_SLOT:-1}
NAME=isx-ci-linux-$SLOT
TEMPLATE=${ISX_CI_TEMPLATE:-tpl-isx-ci-linux}
CPUS=${ISX_CI_CPUS:-8}
MEMORY=${ISX_CI_MEMORY:-16GiB}
TOKEN_FILE=${ISX_CI_TOKEN_FILE:-$HOME/.config/isx-ci-runner/token}
STATE=${XDG_STATE_HOME:-$HOME/.local/state}/isx-ci-runner
PAUSED=$STATE/paused
RUNNER_ID=$STATE/runner-id-$SLOT
LABELS=(self-hosted Linux X64 isx-linux)
HEARTBEAT=linux-heartbeat.yml
ISX=${ISX_CI_ISX:-isx}      # the tests point these at stand-ins
GH=${ISX_CI_GH:-gh}
IDLE_POLL=${ISX_CI_IDLE_POLL:-15}
BACKOFF_MAX=${ISX_CI_BACKOFF_MAX:-600}

log() { echo "isx-ci-runner[$SLOT]: $*" >&2; }

token() {
    local mode
    [ -f "$TOKEN_FILE" ] || { log "no token at $TOKEN_FILE (docs/dev/LINUX-CI-SETUP.md)"; return 1; }
    mode=$(stat -c %a "$TOKEN_FILE" 2>/dev/null) || return 1
    case "$mode" in 600|400) ;; *) log "$TOKEN_FILE is mode $mode: chmod 600 it"; return 1 ;; esac
    cat "$TOKEN_FILE"
}

gh_api() {
    local t
    t=$(token) || return 1
    GH_TOKEN=$t "$GH" api "$@"
}

# Prints "<runner id> <encoded configuration>" for a new just-in-time runner of this slot.
register() {
    local args=(-X POST "repos/$REPO/actions/runners/generate-jitconfig"
        -f "name=$NAME-$(date +%s)" -F runner_group_id=1 -f work_folder=_work) label
    for label in "${LABELS[@]}"; do args+=(-f "labels[]=$label"); done
    gh_api "${args[@]}" --jq '"\(.runner.id) \(.encoded_jit_config)"'
}

# A runner that never took its job stays registered: remove it. One that did is already gone (404).
deregister() {
    [ -n "${1:-}" ] || return 0
    gh_api -X DELETE "repos/$REPO/actions/runners/$1" >/dev/null 2>&1 || true
}

destroy() {
    "$ISX" destroy "$NAME" --skip-confirmation >/dev/null 2>&1 || true
}

# One job: a runner, an instance, the job, and then neither.
one_job() {
    local registered id config rc
    destroy   # what a crash or a reboot left of this slot
    if [ -f "$RUNNER_ID" ]; then deregister "$(cat "$RUNNER_ID")"; rm -f "$RUNNER_ID"; fi
    registered=$(register) || { log "could not register a runner"; return 1; }
    read -r id config <<< "$registered"
    case "$id" in ''|*[!0-9]*) log "GitHub answered no runner id"; return 1 ;; esac
    [ -n "$config" ] || { log "GitHub answered no configuration"; deregister "$id"; return 1; }
    echo "$id" > "$RUNNER_ID"
    if ! "$ISX" branch "$NAME" --from "$TEMPLATE" --cpu "$CPUS" --memory "$MEMORY" \
            --purpose "CI runner $id for $REPO, destroyed after one job" --format plain >/dev/null; then
        log "could not branch $NAME from $TEMPLATE"
        deregister "$id"; rm -f "$RUNNER_ID"
        return 1
    fi
    if [ -e "$PAUSED" ]; then   # a pause that came while the instance was made: it takes no job
        destroy; deregister "$id"; rm -f "$RUNNER_ID"
        return 0
    fi
    log "runner $id waits for a job in $NAME"
    printf '%s\n' "$config" | "$ISX" exec -i "$NAME" --cwd /home/agentuser/actions-runner -- \
        bash -c 'IFS= read -r config && exec ./run.sh --jitconfig "$config"'
    rc=$?
    log "runner $id ended ($rc)"
    destroy
    deregister "$id"
    rm -f "$RUNNER_ID"
    return "$rc"
}

run_loop() {
    local backoff=0
    mkdir -p "$STATE"
    trap 'destroy; [ -f "$RUNNER_ID" ] && deregister "$(cat "$RUNNER_ID")"; rm -f "$RUNNER_ID"; exit 0' INT TERM
    while :; do
        if [ -e "$PAUSED" ]; then sleep "$IDLE_POLL"; continue; fi
        if one_job; then
            backoff=0
        elif [ ! -e "$PAUSED" ]; then   # a pause ends the runner on purpose: no backoff for it
            backoff=$(( backoff < 30 ? 30 : backoff * 2 ))
            [ "$backoff" -le "$BACKOFF_MAX" ] || backoff=$BACKOFF_MAX
            log "trying again in ${backoff}s"
            sleep "$backoff"
        fi
    done
}

busy() {   # prints true or false: whether GitHub has given this slot's runner a job
    local id
    id=$(cat "$RUNNER_ID" 2>/dev/null) || { echo false; return; }
    local answer
    # gh prints an error's body too, so only a successful call's answer is used
    answer=$(gh_api "repos/$REPO/actions/runners/$id" --jq '.busy' 2>/dev/null) || answer=unknown
    echo "$answer"
}

pause() {
    mkdir -p "$STATE"
    touch "$PAUSED"
    case "$(busy)" in
        false) destroy; echo "paused: the idle runner is gone" ;;
        true) echo "paused: the running job finishes, and no other starts" ;;
        *) echo "paused: whether the runner has a job is unknown, so it is left to finish" ;;
    esac
    # A heartbeat no runner takes: the route sees a job waiting for this runner and picks GitHub's
    # With the runner's token alone: an empty GH_TOKEN would fall back to the user's own gh login
    local t
    if t=$(token) && GH_TOKEN=$t "$GH" workflow run "$HEARTBEAT" -R "$REPO" --ref main >/dev/null 2>&1; then
        echo "a heartbeat waits for the runner, so new jobs go to GitHub's runners"
    else
        echo "could not queue a heartbeat: new jobs may still be sent here for up to 10 minutes" >&2
    fi
}

case "${1:-}" in
    run) run_loop ;;
    once) mkdir -p "$STATE"; one_job ;;
    pause) pause ;;
    resume) rm -f "$PAUSED"; echo "resumed" ;;
    status)
        if [ -e "$PAUSED" ]; then echo "paused"; else echo "taking jobs"; fi
        if [ -f "$RUNNER_ID" ]; then echo "slot $SLOT: $NAME, runner $(cat "$RUNNER_ID"), busy: $(busy)"; else echo "slot $SLOT: no runner"; fi ;;
    *) echo "usage: isx-ci-runner.sh run|once|pause|resume|status" >&2; exit 2 ;;
esac
