#!/bin/bash
# scripts/agent-install/validate.sh -- run the agent install path on a clean Linux host (#2081)
#
# Gives a coding agent only the install prompt, as a fresh user with no sudo, and plays the
# person's part by script: it runs the privileged script isx hands over (or one the agent wrote
# and handed over as `sudo sh <path>`), adds a credential when the agent names the command for
# one, and approves templates when asked. It records every command the agent ran, every attempt
# at sudo, every request for a secret, every write of mcp.yaml, the turns where it got stuck,
# and how long each took. Once the run is over it asks the agent a fixed debrief about the
# instructions, and writes report.md with the transcript's evidence on how clear they were
# (failed commands, --help lookups, web searches, repeats, the pages it read and did not)
# apart from the agent's own account.
#
# Meant for a disposable machine only: it creates a user, installs packages through the script
# isx writes, sets up Incus and changes the firewall. Run it on a host where Incus is not
# installed, so the agent meets the host a new user would.
#
# Requires: Linux, passwordless sudo for the invoking user, jq, and the agent's CLI (`claude`,
# `codex`, `pi`, `gemini`, `opencode` or `copilot`) on PATH. Bob Shell is not driven: its headless
# `bob run` has no flag that approves tool calls.
#
# A run passes when the agent reached a first branch whose template installs its own agent's isx
# tool (or the one the person named, for an agent isx has no tool for), with isx registered in
# its own MCP client: what docs/dev/AGENT-INSTALL-VALIDATION.md "Agents that passed" records.
#
# The person user can authenticate the agent only where its credential reaches it: inside an isx
# instance, the user joins the group that reads /run/isx, so the outer proxy serves its agent,
# and a missing credential is answered with the reserved account `inherit`; codex is handed the
# instance's OpenAI placeholder, as are pi and opencode; gemini and copilot get theirs. On a bare host, the agent must already be signed in as the
# person, and --credential-cmd names a command the person would run to add a credential.
#
# Usage:
#   scripts/agent-install/validate.sh [options]
#     --agent NAME            the agent to drive: claude (default), codex, pi, gemini, opencode or copilot
#     --template-agent TOOL   when the agent asks which agent to put in the template, the person
#                             names this isx tool (claude, codex, pi, gemini-cli, bob or copilot);
#                             for opencode, which isx has no tool for, the run then expects this one
#     --agent-model MODEL     the model pi, gemini, opencode or copilot runs (default: its own)
#     --prompt FILE           the prompt to give it (default: the one docs/AGENT-INSTALL.md
#                             publishes, from its "## The prompt" section)
#     --print-prompt          print that prompt and exit
#     --isx-from DIR          isx is not installed by the agent: DIR holds bin/isx and bin/isx-proxy
#                             of a candidate build, linked into the person's ~/.local/bin, and
#                             DIR/docs its docs, which the agent is told to read instead of isx.run
#     --docs DIR              the agent installs isx itself, but reads the docs in DIR (laid out
#                             as --isx-from's docs/) instead of isx.run, which does not publish them yet
#     --user NAME             the person's account, created if missing (default: isxperson)
#     --max-turns N           stop after N agent turns (default: 12)
#     --turn-timeout SECONDS  limit one agent turn (default: 3600)
#     --credential-cmd CMD    run as the person, with NS set, to add a credential the agent asks for
#     --out DIR               where the transcripts and report go (default: ./agent-install-<time>)
#     --no-debrief            do not ask the agent the debrief once the run is over
#     --fresh-groups          give each agent turn the person's current groups; by default the
#                             agent keeps the groups it started with, as a running agent does, so
#                             the incus-admin group the root script adds reaches it only through sg
#     --allow-dirty           run even though Incus is already installed
set -euo pipefail

die() { echo "Error: $*" >&2; exit 1; }
say() { echo "==> $*" >&2; }

HERE=$(cd "$(dirname "$0")" && pwd)
AGENT=claude
TEMPLATE_AGENT=
AGENT_MODEL=
PROMPT_FILE=
ISX_FROM=
DOCS_FROM=
PRINT_PROMPT=
PERSON=isxperson
MAX_TURNS=12
TURN_TIMEOUT=3600
CREDENTIAL_CMD=
OUT="$PWD/agent-install-$(date -u +%Y%m%dT%H%M%SZ)"
ALLOW_DIRTY=
DEBRIEF=1
FRESH_GROUPS=

while [ $# -gt 0 ]; do
    case "$1" in
        --agent) AGENT=$2; shift 2 ;;
        --template-agent) TEMPLATE_AGENT=$2; shift 2 ;;
        --agent-model) AGENT_MODEL=$2; shift 2 ;;
        --prompt) PROMPT_FILE=$2; shift 2 ;;
        --print-prompt) PRINT_PROMPT=1; shift ;;
        --isx-from) ISX_FROM=$(cd "$2" && pwd); shift 2 ;;
        --docs) DOCS_FROM=$(cd "$2" && pwd); shift 2 ;;
        --user) PERSON=$2; shift 2 ;;
        --max-turns) MAX_TURNS=$2; shift 2 ;;
        --turn-timeout) TURN_TIMEOUT=$2; shift 2 ;;
        --credential-cmd) CREDENTIAL_CMD=$2; shift 2 ;;
        --out) OUT=$2; shift 2 ;;
        --allow-dirty) ALLOW_DIRTY=1; shift ;;
        --no-debrief) DEBRIEF=; shift ;;
        --fresh-groups) FRESH_GROUPS=1; shift ;;
        -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

# The agents the harness drives: the isx tool each one should put in its template (none for
# opencode, which isx has no tool for, so it asks the person), and its name. agent_name also takes
# an isx tool, for --template-agent.
agent_tool() {
    case "$1" in claude|codex|pi|copilot) echo "$1" ;; gemini) echo gemini-cli ;; *) ;; esac
}
agent_name() {
    case "$1" in
        claude) echo "Claude Code" ;; codex) echo "Codex CLI" ;; pi) echo Pi ;;
        gemini|gemini-cli) echo "Gemini CLI" ;; bob) echo "Bob Shell" ;; copilot) echo "GitHub Copilot CLI" ;;
        opencode) echo opencode ;; *) ;;
    esac
}

# The prompt the install page publishes: the first fenced block under its "## The prompt".
page_prompt() {
    awk '/^## /{in_s = ($0 == "## The prompt")} in_s && /^```/{if (f) exit; f = 1; next} f' "$1"
}
[ -z "$ISX_FROM" ] || [ -z "$DOCS_FROM" ] || die "--isx-from already brings its docs: leave out --docs"
if [ -n "$PROMPT_FILE" ]; then
    [ -r "$PROMPT_FILE" ] || die "no prompt at $PROMPT_FILE"
    PROMPT=$(cat "$PROMPT_FILE")
else
    PROMPT_FILE="$(cd "$HERE/../.." && pwd)/docs/AGENT-INSTALL.md"
    PROMPT=$(page_prompt "$PROMPT_FILE")
    [ -n "$PROMPT" ] || die "no prompt under \"## The prompt\" in $PROMPT_FILE"
fi
if [ -n "$PRINT_PROMPT" ]; then printf '%s\n' "$PROMPT"; exit 0; fi

[ "$(uname -s)" = Linux ] || die "Linux only"
case "$AGENT" in claude|codex|pi|gemini|opencode|copilot) ;; *) die "--agent takes claude, codex, pi, gemini, opencode or copilot" ;; esac
case "$TEMPLATE_AGENT" in ''|claude|codex|pi|gemini-cli|bob|copilot) ;; *) false ;; esac || die "--template-agent takes an isx agent tool: claude, codex, pi, gemini-cli, bob or copilot"
command -v jq >/dev/null || die "jq is required"
command -v "$AGENT" >/dev/null || die "$AGENT is not on PATH"
sudo -n true 2>/dev/null || die "passwordless sudo is required (for the person's part)"
if [ -z "$ALLOW_DIRTY" ] && command -v incus >/dev/null; then
    die "Incus is already installed, so this host is not clean (--allow-dirty to run anyway)"
fi
if [ -n "$ISX_FROM" ]; then
    [ -x "$ISX_FROM/bin/isx" ] && [ -x "$ISX_FROM/bin/isx-proxy" ] || die "$ISX_FROM/bin holds no isx and isx-proxy"
    DOCS_FROM=$ISX_FROM/docs
fi
if [ -n "$DOCS_FROM" ]; then
    # docs/dev/ describes this harness, which would tell the agent how the person answers.
    for d in "$DOCS_FROM/docs/dev" "$DOCS_FROM/dev"; do
        [ ! -e "$d" ] || die "$d holds maintainer notes, this harness's among them: leave it out"
    done
fi

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
# The person reads each message from here, since $OUT may sit in a home they cannot enter.
MSG_DIR=$(mktemp -d /tmp/agent-install-msg.XXXXXX)
trap 'rm -rf "$MSG_DIR"' EXIT
chmod 755 "$MSG_DIR"
# What a masked file reads as: Claude Code refuses managed settings that are not a regular file.
: > "$MSG_DIR/empty"
echo '{}' > "$MSG_DIR/empty.json"
chmod 644 "$MSG_DIR/empty" "$MSG_DIR/empty.json"

# The person: a fresh account with no sudo, which the agent runs as.
if ! id "$PERSON" >/dev/null 2>&1; then
    say "Creating the person's account $PERSON (no sudo)"
    sudo useradd -m "$PERSON"
fi
if sudo test -e /run/isx/credential-secret; then
    INSIDE_ISX=1
    ISX_GROUP=$(sudo stat -c %G /run/isx/credential-secret)
    case "$ISX_GROUP" in
        root|wheel|sudo|adm|admin) die "/run/isx/credential-secret belongs to the group $ISX_GROUP, which the person must not join" ;;
    esac
    sudo usermod -aG "$ISX_GROUP" "$PERSON"
else
    INSIDE_ISX=
fi
# The only sudo the person may hold is the btrfs usage rule isx init installs (checked after
# any group the person joins above, since a group can grant sudo too).
if sudo -l -U "$PERSON" 2>/dev/null | sed -n '/may run/,$p' | tail -n +2 | grep -v '/usr/bin/btrfs ' | grep -q .; then
    die "$PERSON may run sudo beyond isx's btrfs usage rule; the agent must not"
fi
sudo loginctl enable-linger "$PERSON"
PHOME=$(getent passwd "$PERSON" | cut -d: -f6)
PHOME_REAL=$(sudo realpath -- "$PHOME")
# The groups the agent's session starts with, which it keeps for the whole run (--fresh-groups:
# a new login each turn, as if the person restarted the agent every time).
AGENT_GROUPS=$(id -G "$PERSON" | tr ' ' ,)
[ -z "$FRESH_GROUPS" ] || AGENT_GROUPS=
AGENT_BIN=$(command -v "$AGENT")
sudo -u "$PERSON" test -x "$AGENT_BIN" || {
    sudo install -m 755 "$(readlink -f "$AGENT_BIN")" "/usr/local/bin/$AGENT"
    AGENT_BIN=/usr/local/bin/$AGENT
}
# The agent's own credential, which is not the person's to hand over: inside an isx instance it
# is the instance's placeholder, which the instance's login profile turns into a proof.
AGENT_ENV=()
if [ -n "$INSIDE_ISX" ]; then
    case "$AGENT" in
        claude) AGENT_ENV=(CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-placeholder) ;;
        # Pi runs on the OpenAI account: upstream refuses Pi on a Claude subscription.
        codex|pi|opencode) AGENT_ENV=(OPENAI_API_KEY=sk-placeholder) ;;
        gemini) AGENT_ENV=(GEMINI_API_KEY=gemini-placeholder) ;;
        copilot) AGENT_ENV=(COPILOT_GITHUB_TOKEN=gho_placeholder) ;;
    esac
fi
if [ -n "$ISX_FROM" ]; then
    sudo -u "$PERSON" test -x "$ISX_FROM/bin/isx" || die "$PERSON cannot run $ISX_FROM/bin/isx: put the candidate where they can read it"
    sudo -u "$PERSON" mkdir -p "$PHOME/.local/bin"
    sudo -u "$PERSON" ln -sf "$ISX_FROM/bin/isx" "$PHOME/.local/bin/isx"
    sudo -u "$PERSON" ln -sf "$ISX_FROM/bin/isx-proxy" "$PHOME/.local/bin/isx-proxy"
fi

# Run a command as the person, in a fresh login shell (not sudo -i, which mangles arguments),
# with an agent's view of the host: an isx instance's own agent instructions are masked, since
# a real host has none. VAR=value arguments before "--" are set before the login profile runs,
# so the instance's profile turns a placeholder among them into a proof.
as_person() {
    case "${1:-}" in *=*|--) ;; *) set -- -- "$@" ;; esac
    sudo unshare -m sh -c '
        m=$1; g=$2; shift 2
        for f in /etc/claude-code/CLAUDE.md /etc/isx/agent-instructions.md /AGENTS.md /etc/codex/requirements.toml; do
            [ -e "$f" ] && mount --bind "$m/empty" "$f"
        done
        f=/etc/claude-code/managed-settings.json
        [ -e "$f" ] && mount --bind "$m/empty.json" "$f"
        u=$1; shift
        h=$(getent passwd "$u" | cut -d: -f6)
        n=0
        for a in "$@"; do [ "$a" = -- ] && break; n=$((n + 1)); done
        vars=$(i=0; for a in "$@"; do [ $i -lt $n ] && printf "%s\n" "$a"; i=$((i + 1)); done)
        shift $((n + 1))
        set -f; IFS="
"
        # The agent keeps the groups it started with, as a running agent does: groups a root
        # script adds reach its commands only through sg, or once the person starts it again.
        # (The prefix is split on the newlines IFS holds now.)
        if [ -n "$g" ]; then run_as="setpriv
--reuid=$u
--regid=$(id -g "$u")
--groups=$g"
        else run_as="sudo
-u
$u"; fi
        exec $run_as env -i HOME="$h" USER="$u" LOGNAME="$u" SHELL=/bin/bash TERM=dumb \
            PATH=/usr/local/bin:/usr/bin:/bin XDG_RUNTIME_DIR=/run/user/$(id -u "$u") DISABLE_AUTOUPDATER=1 $vars \
            bash -lc '"'"'cd ~ && exec "$@"'"'"' bash "$@"
    ' sh "$MSG_DIR" "${AS_GROUPS:-}" "$PERSON" "$@"
}

if [ -n "$ISX_FROM" ]; then
    PROMPT="$PROMPT

(Harness note: isx is already installed for you, a release candidate whose commands are newer than isx.run's docs. Do not install isx again. Read its docs in $DOCS_FROM, which start at llms.txt, instead of isx.run.)"
elif [ -n "$DOCS_FROM" ]; then
    PROMPT="$PROMPT

(Harness note: isx.run does not publish the docs for this release yet. Install isx as they say, but read them in $DOCS_FROM, which start at llms.txt, instead of isx.run.)"
fi

START=$(date +%s)
JOURNAL_SINCE=$(date '+%Y-%m-%d %H:%M:%S')
declare -A RAN_SCRIPTS=()
declare -A FAILED_SCRIPTS=()
declare -A CREDENTIALS=()
APPROVED=
ASKED_AGENT=
REGISTERED=
REACHED_BRANCH=
STUCK=0
: > "$OUT/person.log"
touch "$OUT/.person-ran"
: > "$OUT/turns.tsv"

privileged_script() { echo "$PHOME/.local/state/incus-spawn/init-privileged.sh"; }

# What the person does after a turn: one action, or none. Prints the reply for the next turn.
# Runs a root script the person was handed, unless they already ran it successfully. Returns 1
# when there was nothing to run. A failed script is retried only when it is handed over anew:
# isx's when isx rewrote it (even the same), the agent's when its content changed, so a reply
# that only recalls a failed script does not rerun it.
run_root_script() {
    local script=$1 turn=$2 whose=$3 interp=${4:-sh} sum rc
    sum=$(sudo sha256sum "$script" | cut -d' ' -f1)
    [ -z "${RAN_SCRIPTS[$sum]:-}" ] || return 1
    [ "$whose" = "isx's" ] || [ -z "${FAILED_SCRIPTS[$sum]:-}" ] || return 1
    touch "$OUT/.person-ran"
    sudo cp "$script" "$OUT/privileged-$turn.sh"
    rc=0
    sudo "$interp" "$script" > "$OUT/privileged-$turn.log" 2>&1 || rc=$?
    if [ "$rc" -eq 0 ]; then RAN_SCRIPTS[$sum]=$turn; else FAILED_SCRIPTS[$sum]=$turn; fi
    echo "turn $turn: ran $whose script $script with sudo $interp, exit $rc" >> "$OUT/person.log"
    echo "I read $script and ran it with sudo; it exited $rc. Carry on."
}

person_acts() {
    local turn=$1 text=$2 script ns templates rc interp path owner
    script=$(privileged_script)
    # A script isx wrote since the person last ran one (or since the run began), and not one
    # they already ran: a script left from an earlier round is not handed over again.
    if sudo test -s "$script" && sudo test "$script" -nt "$OUT/.person-ran"; then
        run_root_script "$script" "$turn" "isx's" && return
    fi
    # A script the agent wrote itself and handed over as 'sudo sh <path>' (or bash), in the
    # person's home or /tmp, in the order the reply names them. Prose and markup after a path
    # (a full stop, quotes, a closing bracket, bold) are not part of it. It runs with the interpreter
    # named, and the report calls it the agent's only when the person owns it.
    while read -r interp path; do
        [ -n "$path" ] || continue
        # shellcheck disable=SC2088 # a literal ~ in the agent's text, expanded by hand
        case "$path" in "~/"*) path=$PHOME/${path#"~/"} ;; esac
        # Resolved as root: the harness cannot read the person's home, where an unresolved
        # symlink would pass for a path inside it.
        script=$(sudo realpath -m -- "$path")
        case "$script" in "$PHOME_REAL"/*|/tmp/*) ;; *) continue ;; esac
        [ "$script" = "$(privileged_script)" ] && continue
        sudo test -f "$script" || continue
        owner=$(sudo stat -c %U -- "$script")
        if [ "$owner" = "$PERSON" ]; then owner="the agent's"; else owner="$owner's"; fi
        run_root_script "$script" "$turn" "$owner" "$interp" && return
    done < <(grep -oE 'sudo (sh|bash) [~/][^ `"]+' <<<"$text" \
        | sed -E "s/[].,;:)}>*'\"]+\$//" | awk '!seen[$2 " " $3]++ {print $2, $3}')
    for ns in $(grep -oE 'isx account add [a-z][a-z0-9-]*' <<<"$text" | awk '{print $4}' | sort -u); do
        [ -n "${CREDENTIALS[$ns]:-}" ] && continue
        CREDENTIALS[$ns]=$turn
        if [ -n "$CREDENTIAL_CMD" ]; then
            rc=0
            as_person NS="$ns" -- sh -c "$CREDENTIAL_CMD" >> "$OUT/person.log" 2>&1 || rc=$?
        elif [ -n "$INSIDE_ISX" ]; then
            # Inside an isx instance the person's only credential is the outer instance's.
            rc=0
            as_person sh -c 'f=~/.config/incus-spawn/config.yaml; mkdir -p "${f%/*}"; touch "$f"
                if grep -q "^$1: {}\$" "$f"; then sed -i "s/^$1: {}\$/$1:\n  default: inherit/" "$f"
                elif grep -A1 "^$1:" "$f" | grep -q "^  default: inherit\$"; then echo "config.yaml already inherits $1"
                elif grep -q "^$1:" "$f"; then echo "config.yaml already configures $1 (left alone)"; exit 1
                else printf "%s:\n  default: inherit\n" "$1" >> "$f"; fi' sh "$ns" >> "$OUT/person.log" 2>&1 || rc=$?
        else
            rc=1
        fi
        echo "turn $turn: credential $ns, exit $rc" >> "$OUT/person.log"
        if [ "$rc" -ne 0 ]; then
            echo "I tried to add the $ns credential, but could not (exit $rc): nothing changed. Carry on without it, or say what else you need."
        elif [ -z "$CREDENTIAL_CMD" ] && [ -n "$INSIDE_ISX" ]; then
            # Say what was done: an inherited default is not an account, so isx account list omits it.
            echo "I set the default $ns account to inherit in ~/.config/incus-spawn/config.yaml (exit $rc): this machine is itself an isx instance, so isx uses the outer instance's $ns account, which isx doctor counts and isx account list does not show. Carry on."
        else
            echo "I ran isx account add $ns at my terminal (exit $rc). Carry on."
        fi
        return
    done
    # The agent could not tell which agent it is, or isx has no tool for it: the person names one.
    if [ -z "$ASKED_AGENT" ] && grep -qiE '(which|what) (coding )?agent\b' <<<"$text"; then
        ASKED_AGENT=$turn
        if [ -n "$TEMPLATE_AGENT" ]; then
            echo "turn $turn: asked which agent; answered $(agent_name "$TEMPLATE_AGENT")" >> "$OUT/person.log"
            echo "Put $(agent_name "$TEMPLATE_AGENT") in the template: isx's tool for it is $TEMPLATE_AGENT. Carry on."
        else
            echo "turn $turn: asked which agent; no --template-agent to answer with" >> "$OUT/person.log"
            echo "I don't know which agent to choose. Carry on by yourself, or say exactly what you need from me."
        fi
        return
    fi
    if [ -z "$APPROVED" ] && grep -qiE 'mcp\.yaml|mcp setup|approv' <<<"$text"; then
        # The templates the agent names, or every one when it names none: built ones and the
        # person's own, built or not, since isx mcp setup offers a definition before its build
        # (#2178: Codex asked for the approval before building, and was told there was nothing to do).
        # A name counts only whole: tpl-isx is not named by tpl-isx-agent.
        built=$(as_person isx templates list --format=json 2>/dev/null \
            | jq -r '.[] | select(.built == true or (.source != null and .source != "built-in")) | .name' || true)
        templates=
        for t in $built; do
            if [[ $text =~ (^|[^A-Za-z0-9_-])"$t"($|[^A-Za-z0-9_-]) ]]; then templates=${templates:+$templates,}$t; fi
        done
        [ -n "$templates" ] || templates=$(paste -sd, - <<<"$built")
        if [ -n "$templates" ]; then
            APPROVED=$turn
            as_person sh -c 'mkdir -p ~/.config/incus-spawn && printf "templates: [%s]\n" "$1" > ~/.config/incus-spawn/mcp.yaml' sh "$templates"
            # isx mcp setup also registers isx mcp with Claude Code when it is installed; so does the
            # person. Whether the agent registered itself first is recorded before, so the report
            # does not credit the agent with the person's registration.
            if [ -z "$REGISTERED" ] && mcp_registered; then REGISTERED="by the agent, before turn $((turn + 1))"; fi
            as_person sh -c 'command -v claude >/dev/null || exit 0
                claude mcp list 2>/dev/null | grep -q "^isx:" || claude mcp add --scope user isx -- "$(command -v isx)" mcp' \
                >> "$OUT/person.log" 2>&1 || true
            echo "turn $turn: approved templates [$templates] in mcp.yaml" >> "$OUT/person.log"
            echo "I approved these templates for MCP: $templates. Carry on."
            return
        fi
    fi
    STUCK=$((STUCK + 1))
    echo "turn $turn: nothing for the person to do" >> "$OUT/person.log"
    echo "I have nothing to do for that. Carry on by yourself, or say exactly what you need from me."
}

# One turn of the agent: the message is in $1, its transcript goes to stdout.
agent_turn() {
    local msg=$1 first=$2
    case "$AGENT" in
        claude)
            AS_GROUPS=$AGENT_GROUPS as_person ${AGENT_ENV[@]+"${AGENT_ENV[@]}"} -- sh -c '
                c=; [ "$3" = first ] || c=--continue
                exec timeout "$1" "$2" -p $c --output-format stream-json --verbose --permission-mode bypassPermissions < "$4"' \
                sh "$TURN_TIMEOUT" "$AGENT_BIN" "$first" "$msg" ;;
        codex)
            AS_GROUPS=$AGENT_GROUPS as_person ${AGENT_ENV[@]+"${AGENT_ENV[@]}"} -- sh -c '
                [ -n "${OPENAI_API_KEY:-}" ] && export CODEX_API_KEY=$OPENAI_API_KEY
                m=$(cat "$4")
                if [ "$3" = first ]; then
                    exec timeout "$1" "$2" exec --json --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox "$m"
                fi
                exec timeout "$1" "$2" exec resume --last --json --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox "$m"' \
                sh "$TURN_TIMEOUT" "$AGENT_BIN" "$first" "$msg" ;;
        # Pi asks before no tool call, so it needs no flag for that; -c continues the session of
        # the directory, which is always the home. Pi 1.5 refuses --provider without --model, so
        # the OpenAI provider is its settings' default, as isx's PiSetup writes it (set over any
        # other default, as --provider did), and Pi picks the model unless --agent-model names one.
        pi)
            AS_GROUPS=$AGENT_GROUPS as_person ${AGENT_ENV[@]+"${AGENT_ENV[@]}"} -- sh -c '
                c=; [ "$3" = first ] || c=-c
                s=~/.pi/agent/settings.json
                if [ -e "$s" ]; then
                    t=$(jq ".defaultProvider = \"openai\"" "$s") && printf "%s\n" "$t" > "$s"
                else mkdir -p "${s%/*}" && printf "{\"defaultProvider\": \"openai\"}\n" > "$s"; fi
                exec timeout "$1" "$2" -p --mode json ${5:+--provider openai --model "$5"} $c "$(cat "$4")"' \
                sh "$TURN_TIMEOUT" "$AGENT_BIN" "$first" "$msg" "$AGENT_MODEL" ;;
        # Headless Gemini CLI 0.68 refuses a folder it has not been told to trust, yolo or not.
        gemini)
            AS_GROUPS=$AGENT_GROUPS as_person ${AGENT_ENV[@]+"${AGENT_ENV[@]}"} -- sh -c '
                c=; [ "$3" = first ] || c="--resume latest"
                exec timeout "$1" "$2" -o stream-json --approval-mode yolo --skip-trust ${5:+-m "$5"} $c -p "$(cat "$4")"' \
                sh "$TURN_TIMEOUT" "$AGENT_BIN" "$first" "$msg" "$AGENT_MODEL" ;;
        # Without --auto, opencode run rejects every permission request.
        opencode)
            AS_GROUPS=$AGENT_GROUPS as_person ${AGENT_ENV[@]+"${AGENT_ENV[@]}"} -- sh -c '
                c=; [ "$3" = first ] || c=--continue
                exec timeout "$1" "$2" run --format json --auto ${5:+-m "$5"} $c "$(cat "$4")"' \
                sh "$TURN_TIMEOUT" "$AGENT_BIN" "$first" "$msg" "$AGENT_MODEL" ;;
        copilot)
            AS_GROUPS=$AGENT_GROUPS as_person ${AGENT_ENV[@]+"${AGENT_ENV[@]}"} -- sh -c '
                c=; [ "$3" = first ] || c=--continue
                exec timeout "$1" "$2" --output-format json --allow-all --no-ask-user ${5:+--model "$5"} $c -p "$(cat "$4")"' \
                sh "$TURN_TIMEOUT" "$AGENT_BIN" "$first" "$msg" "$AGENT_MODEL" ;;
    esac
}

# The agent's last message in a turn's transcript.
agent_reply() {
    case "$AGENT" in
        claude) jq -r 'select(.type == "result") | .result // empty' "$1" ;;
        codex) jq -rs '[.[] | select(.type == "item.completed" and .item.type == "agent_message") | .item.text] | last // empty' "$1" ;;
        # The others are read line by line, like claude's commands below: a turn cut off by its
        # timeout can end in a partial line.
        pi) jq -Rrn '[inputs | fromjson?] | [.[] | select(.type == "message_end" and .message.role == "assistant")] | last
            | [.message.content[]? | select(.type == "text") | .text] | join("")' "$1" ;;
        # Its text comes in deltas; the reply is what follows the last tool result.
        gemini) jq -Rrn '[inputs | fromjson?] | ([to_entries[] | select(.value.type == "tool_result") | .key] | last // -1) as $i
            | [.[($i + 1):][] | select(.type == "message" and .role == "assistant") | .content] | join("")' "$1" ;;
        opencode) jq -Rrn '[inputs | fromjson? | select(.type == "text") | .part.text] | last // empty' "$1" ;;
        copilot) jq -Rrn '[inputs | fromjson? | select(.type == "assistant.message") | .data.content | select(. != null and . != "")] | last // empty' "$1" ;;
    esac 2>/dev/null || true
}

# Every tool call in a turn's transcript: turn, tool, command or path, and how it ended (ok,
# error, exit N, or - when the transcript does not say). "ok, output says Error" is a command
# that exited 0 although isx printed an error, as through a pipe to tail. Shell commands are "Bash".
agent_commands() {
    case "$AGENT" in
        # Read line by line: a turn cut off by its timeout can end in a partial line, which must
        # not lose the turn's other commands.
        claude) jq -Rrn --arg n "$2" '
            [inputs | fromjson?] | ([.[] | select(.type == "user") | .message.content[]? | select(.type? == "tool_result")
              | {key: .tool_use_id, value: (if .is_error then "error"
                  elif ((.content | tostring) | test("(^|\\n|\\\\n)\\s*Error:")) then "ok, output says Error" else "ok" end)}] | from_entries) as $r
            | .[] | select(.type == "assistant") | .message.content[]? | select(.type == "tool_use")
            | [$n, .name, (.input.command // .input.file_path // .input.url // .input.query // .input.pattern // (.input | tostring)), ($r[.id] // "-")] | @tsv' "$1" ;;
        codex) jq -r --arg n "$2" 'select(.type == "item.completed") | .item
            | if .type == "command_execution" then [$n, "Bash", .command,
                  (if .exit_code == 0 and ((.aggregated_output // "") | test("(^|\\n|\\\\n)\\s*Error:")) then "ok, output says Error"
                   elif .exit_code == 0 then "ok" elif .exit_code == null then "-" else "exit \(.exit_code)" end)]
              elif .type == "file_change" then (.changes[]? | [$n, "Write", .path, "-"])
              elif .type == "mcp_tool_call" then [$n, "mcp:\(.server)/\(.tool)", ((.arguments // {}) | tostring), (.status // "-")]
              elif .type == "web_search" then [$n, "WebSearch", .query, "-"]
              else empty end | @tsv' "$1" ;;
        # A failed bash call ends its text with "Command exited with code N".
        pi) jq -Rrn --arg n "$2" '
            [inputs | fromjson?] | ([.[] | select(.type == "tool_execution_end") | {key: .toolCallId, value: (if .isError
                  then (([.result.content[]?.text // empty] | join("")) | (capture("exited with code (?<c>[0-9]+)").c // null)) as $c
                    | (if $c then "exit \($c)" else "error" end)
                  else "ok" end)}] | from_entries) as $r
            | .[] | select(.type == "tool_execution_start")
            | [$n, (if .toolName == "bash" then "Bash" elif .toolName == "write" then "Write" elif .toolName == "edit" then "Edit"
                    elif (.toolName | startswith("mcp__")) then "mcp:" + (.toolName | sub("^mcp__"; "") | sub("__"; "/")) else .toolName end),
               (.args.command // .args.path // (.args | tostring)), ($r[.toolCallId] // "-")] | @tsv' "$1" ;;
        # A shell command's exit code is only in its output text, as "Exit Code: N".
        gemini) jq -Rrn --arg n "$2" '
            [inputs | fromjson?] | ([.[] | select(.type == "tool_result") | {key: .tool_id, value: (if .status == "error" then "error"
                  else ((.output // "") | tostring | (capture("Exit Code: (?<c>[0-9]+)").c // "0")) as $c
                    | (if $c == "0" then "ok" else "exit \($c)" end) end)}] | from_entries) as $r
            | .[] | select(.type == "tool_use")
            | [$n, (if .tool_name == "run_shell_command" then "Bash" elif .tool_name == "write_file" then "Write"
                    elif .tool_name == "replace" then "Edit" elif .tool_name == "web_fetch" then "WebFetch"
                    elif .tool_name == "google_web_search" then "WebSearch"
                    elif (.tool_name | startswith("mcp_")) then "mcp:" + (.tool_name | sub("^mcp_"; "") | sub("_"; "/")) else .tool_name end),
               (.parameters.command // .parameters.file_path // .parameters.query // .parameters.prompt // (.parameters | tostring)),
               ($r[.tool_id] // "-")] | @tsv' "$1" ;;
        # A tool call is written once it has ended, with its outcome.
        opencode) jq -Rrn --arg n "$2" '
            inputs | fromjson? | select(.type == "tool_use") | .part
            | [$n, (if .tool == "bash" then "Bash" elif .tool == "write" then "Write" elif .tool == "edit" then "Edit"
                    elif .tool == "webfetch" then "WebFetch" elif .tool == "websearch" then "WebSearch"
                    elif (.tool | startswith("isx_")) then "mcp:isx/" + (.tool | sub("^isx_"; "")) else .tool end),
               (.state.input.command // .state.input.filePath // .state.input.url // .state.input.query // (.state.input | tostring)),
               (if .state.status == "error" then "error" elif (.state.metadata.exit // 0) != 0 then "exit \(.state.metadata.exit)"
                elif .state.status == "completed" then "ok" else "-" end)] | @tsv' "$1" ;;
        copilot) jq -Rrn --arg n "$2" '
            [inputs | fromjson?] | ([.[] | select(.type == "tool.execution_complete") | .data | {key: .toolCallId, value: (
                  if (.shellExecution.exitCode // 0) != 0 then "exit \(.shellExecution.exitCode)"
                  elif .success == false then "error" elif .success then "ok" else "-" end)}] | from_entries) as $r
            | .[] | select(.type == "tool.execution_start") | .data
            | [$n, (if .mcpServerName then "mcp:\(.mcpServerName)/\(.mcpToolName)" elif .toolName == "bash" or .shellToolInfo then "Bash"
                    elif .toolName == "create" then "Write" elif .toolName == "edit" then "Edit"
                    elif .toolName == "web_fetch" then "WebFetch" else .toolName end),
               (.arguments.command // .arguments.path // .arguments.url // (.arguments | tostring)), ($r[.toolCallId] // "-")] | @tsv' "$1" ;;
    esac 2>/dev/null || true
}

# Whether isx is in the agent's own user-wide MCP settings, read from its file: asking the agent
# (claude mcp get, for one) would start the server.
mcp_registered() {
    case "$AGENT" in
        claude) as_person sh -c 'jq -e ".mcpServers.isx" ~/.claude.json' ;;
        codex) as_person sh -c 'grep -qE "^\[mcp_servers\.(isx|\"isx\")\]" ~/.codex/config.toml' ;;
        pi) as_person sh -c 'jq -e ".mcpServers.isx" ~/.pi/agent/mcp.json' ;;
        gemini) as_person sh -c 'jq -e ".mcpServers.isx" ~/.gemini/settings.json' ;;
        # opencode.jsonc may hold comments, which jq does not read: then the name alone counts.
        opencode) as_person sh -c 'd=~/.config/opencode; for f in "$d/opencode.json" "$d/opencode.jsonc" "$d/config.json"; do
                [ -f "$f" ] || continue; jq -e ".mcp.isx" "$f" || grep -qE "\"isx\"[[:space:]]*:" "$f" && exit 0; done; exit 1' ;;
        copilot) as_person sh -c 'jq -e ".mcpServers.isx" ~/.copilot/mcp-config.json' ;;
    esac >/dev/null 2>&1
}

# The isx tool the first template should install: the agent's own, or the one the person named
# when the agent asked.
expected_tool() {
    if [ -n "$TEMPLATE_AGENT" ]; then echo "$TEMPLATE_AGENT"; else agent_tool "$AGENT"; fi
}

# The first template, in any branch's chain, whose definition installs the expected tool, from each
# definition's file (isx templates list --format=json names its source): "tpl-x: tool", or nothing.
own_tool_template() {
    local tool templates t src parent
    tool=$(expected_tool)
    [ -n "$tool" ] || return 0
    templates=$(as_person isx templates list --format=json 2>/dev/null) || return 0
    # Every branch counts, not only the first listed: the agent may have made a scratch one too.
    for t in $(as_person isx list --format=json 2>/dev/null \
        | jq -r '[.[] | select(.parent != null and (.name | startswith("tpl-") | not)) | .parent] | unique[]' 2>/dev/null); do
        while [ -n "$t" ]; do
            src=$(jq -r --arg t "$t" '.[] | select(.name == $t) | .source // empty' <<<"$templates")
            # A tool is named in a list item (- codex, - name: codex) or an inline list ([gh, codex]).
            if [ -n "$src" ] && [ "$src" != built-in ] && sudo grep -qE "^[[:space:]]*-[[:space:]]*(name:[[:space:]]*)?[\"']?${tool}[\"']?[[:space:]]*\$|^[[:space:]]*tools:.*[[,[:space:]]${tool}[],[:space:]]" "$src"; then
                echo "$t: $tool"
                return 0
            fi
            parent=$(jq -r --arg t "$t" '.[] | select(.name == $t) | .parent // empty' <<<"$templates")
            [ "$parent" != "$t" ] || break
            t=$parent
        done
    done
}

has_branch() {
    as_person isx list --format=json 2>/dev/null \
        | jq -e '[.[] | select(.parent != null and (.name | startswith("tpl-") | not))] | length > 0' >/dev/null 2>&1
}

message="$PROMPT"
for turn in $(seq 1 "$MAX_TURNS"); do
    printf '%s\n' "$message" > "$OUT/message-$turn.txt"
    cp "$OUT/message-$turn.txt" "$MSG_DIR/$turn.txt"
    chmod 644 "$MSG_DIR/$turn.txt"
    first=more
    [ "$turn" -eq 1 ] && first=first
    say "Turn $turn"
    t0=$(date +%s)
    rc=0
    agent_turn "$MSG_DIR/$turn.txt" "$first" > "$OUT/turn-$turn.jsonl" 2> "$OUT/turn-$turn.err" || rc=$?
    t1=$(date +%s)
    text=$(agent_reply "$OUT/turn-$turn.jsonl")
    printf '%s\n' "$text" > "$OUT/reply-$turn.txt"
    printf '%s\t%s\t%s\n' "$turn" "$((t1 - t0))" "$rc" >> "$OUT/turns.tsv"
    if [ -z "$REACHED_BRANCH" ] && has_branch; then
        REACHED_BRANCH=$turn
        say "A first branch exists after turn $turn"
    fi
    # No turn follows the last, so the person has nothing left to answer.
    [ "$turn" -lt "$MAX_TURNS" ] || break
    # Not in $(...): the person's memory of what they already did must outlive the call.
    stuck_before=$STUCK
    person_acts "$turn" "$text" > "$OUT/.reply"
    message=$(cat "$OUT/.reply")
    # The run is over once a branch exists and the agent asks for nothing more.
    if [ -n "$REACHED_BRANCH" ] && [ "$STUCK" -gt "$stuck_before" ]; then
        STUCK=$stuck_before
        sed -i '$ s/nothing for the person to do$/nothing more asked: done/' "$OUT/person.log"
        break
    fi
done
END=$(date +%s)

# Read before the debrief, so a command the agent runs during it does not change the outcome.
OWN_TOOL=$(own_tool_template)
if [ -z "$REGISTERED" ] && mcp_registered; then
    if [ -n "$APPROVED" ] && [ "$AGENT" = claude ]; then REGISTERED="by the person's isx mcp setup, after turn $APPROVED"
    else REGISTERED="by the agent"; fi
fi
PLAN=$(as_person isx init --plan --format=json 2>/dev/null || true)
FINAL_LIST=$(as_person isx list 2>&1 || true)

# The debrief, asked only now: asking for notes during the run would change how the agent reads
# the instructions. Its answer is the agent's own account, which the report keeps apart from
# what the transcript shows.
DEBRIEF_PROMPT='The install is over, and nothing more is needed from you. Do not run any commands now. Looking back at the instructions you had (my prompt, the isx docs and the output of isx commands), answer under these headings:
1. Gaps you filled: what you had to work out that the instructions did not say (a command, an order, a precondition, how to tell a step had finished).
2. Extrapolations: instructions you extended or reinterpreted to make progress, and why.
3. Wrong guidance you backed off from: a step you followed as written that failed or misled you, what you did instead, and how many attempts it cost.
4. Ambiguities: wording you could have read two ways, and which way you read it.
5. What you read: the pages and command outputs you used, and any you skipped.
6. What you would change in the instructions, and where: the install page, llms.txt, or an isx command'"'"'s own output.'
if [ -n "$DEBRIEF" ]; then
    say "Debrief"
    printf '%s\n' "$DEBRIEF_PROMPT" > "$MSG_DIR/debrief.txt"
    chmod 644 "$MSG_DIR/debrief.txt"
    agent_turn "$MSG_DIR/debrief.txt" more > "$OUT/debrief.jsonl" 2> "$OUT/debrief.err" || true
    agent_reply "$OUT/debrief.jsonl" > "$OUT/debrief.txt"
    agent_commands "$OUT/debrief.jsonl" debrief > "$OUT/debrief-commands.tsv"
fi

# What the agent ran, from its transcripts.
for f in "$OUT"/turn-*.jsonl; do
    n=${f##*/turn-}; n=${n%.jsonl}
    agent_commands "$f" "$n"
done | sort -n -s -k1,1 > "$OUT/commands.tsv"

SUDO_TRIES=$(awk -F'\t' '$2 == "Bash" && $3 ~ /(^|[;&|( `]|\$\(|\\n)(sudo|pkexec|doas|run0|su)( |$|\\n)/' "$OUT/commands.tsv" || true)
# A redirection to /dev/null or a descriptor writes no file, so it is dropped before looking for one.
MCP_WRITES=$(awk -F'\t' '{ c = $3; gsub(/[0-9&]*>>? *\/dev\/null|[0-9]*>&[0-9-]+/, "", c) }
    ($2 ~ /^(Write|Edit|MultiEdit)$/ && $3 ~ /mcp\.yaml$/) || ($2 == "Bash" && c ~ /mcp\.yaml/ && c ~ /((^|[^-=>])>>?[^&=]|(^|[ ;&|(]|\\n)(tee|cp|mv|install) |sed -i)/)' "$OUT/commands.tsv" || true)
SECRET_ASKS=$(grep -n -iE '(paste|send|give|provide|share|tell|enter)[^.]{0,40}(api key|token|password|secret)' "$OUT"/reply-*.txt || true)
# isx itself runs the btrfs usage reads its init rule allows; those are counted, not listed.
JOURNAL=$(sudo journalctl -q --no-pager --since "$JOURNAL_SINCE" _COMM=sudo 2>/dev/null | grep -E "[: ] $PERSON : " || true)
JOURNAL_SUDO=$(grep -v 'COMMAND=/usr/bin/btrfs ' <<<"$JOURNAL" | grep . || true)
BTRFS_RULE_USES=$(grep -c 'COMMAND=/usr/bin/btrfs ' <<<"$JOURNAL" || true)
# How clear the instructions were, from the transcript alone (the agent's debrief is kept apart).
FAILED=$(awk -F'\t' '$4 != "ok" && $4 != "-" && $4 != "completed"' "$OUT/commands.tsv" || true)
HELP_LOOKUPS=$(awk -F'\t' '$2 == "Bash" && $3 ~ /(--help|isx( [^ |;&]+)* -h( |$|\x27)|(^|[ \x27])help( |$|\x27))/' "$OUT/commands.tsv" || true)
WEB=$(awk -F'\t' '$2 ~ /^Web/ || ($2 == "Bash" && $3 ~ /(curl|wget) / && $3 !~ /isx\.run *\| *(ba)?sh/)' "$OUT/commands.tsv" || true)
REPEATS=$(awk -F'\t' '$2 == "Bash" { n[$3]++; t[$3] = t[$3] (t[$3] == "" ? "" : ",") $1 }
    END { for (c in n) if (n[c] > 1) printf "turns %s: %s\n", t[c], c }' "$OUT/commands.tsv" | sort || true)
DOCS_LIST=$(if [ -n "$DOCS_FROM" ]; then cd "$DOCS_FROM" && find . -name '*.md' -o -name llms.txt; else cd "$HERE/../.." && { echo ./README.md; ls docs/*.md | sed 's|^|./|'; echo ./llms.txt; }; fi 2>/dev/null \
    | sed 's|^\./||; s|^docs/||' | sort -u || true)
DOCS_READ=$(cut -f3 "$OUT/commands.tsv" | grep -oE 'llms(-full)?\.txt|README\.md|[A-Z][A-Z-]*\.md|isx\.run/docs/[a-z-]+' \
    | sed -E 's|isx\.run/docs/([a-z-]+)|\U\1.md|' | grep -vE '^(SKILL|AGENTS-NOTES)\.md$' | sort | uniq -c | sort -rn || true)
DOCS_UNREAD=$(for d in $DOCS_LIST; do awk '{print $2}' <<<"$DOCS_READ" | grep -qxF -- "$d" || echo "$d"; done)

{
    echo "# Agent install run"
    echo
    echo "- Agent: $AGENT ($("$AGENT_BIN" --version 2>/dev/null | head -n1 || true))"
    echo "- isx: $(as_person isx --version 2>/dev/null | head -n1 || echo 'not installed')"
    echo "- Host: $(. /etc/os-release && echo "$PRETTY_NAME"), $(uname -m), inside an isx instance: ${INSIDE_ISX:-no}"
    echo "- Prompt: $PROMPT_FILE${DOCS_FROM:+, with a harness note pointing at $DOCS_FROM}"
    echo "- Reached a first branch: $([ -n "$REACHED_BRANCH" ] && echo "yes, after turn $REACHED_BRANCH" || echo no)"
    echo "- The first branch's template installs $(expected_tool | grep . || echo "the agent's tool (none expected: no --template-agent)"): ${OWN_TOOL:-no}"
    echo "- isx in the agent's own MCP client: ${REGISTERED:-no}"
    echo "- Asked the person which agent to put in the template: $([ -n "$ASKED_AGENT" ] && echo "yes, in turn $ASKED_AGENT" || echo no)"
    echo "- MCP approval asked for and given: $([ -n "$APPROVED" ] && echo "yes, after turn $APPROVED" || echo no)"
    echo "- Passed (a first branch, its template with the agent's tool, isx in its MCP client): $([ -n "$REACHED_BRANCH" ] && [ -n "$OWN_TOOL" ] && [ -n "$REGISTERED" ] && echo yes || echo no)"
    echo "- Wall time: $(( (END - START) / 60 )) min $(( (END - START) % 60 )) s over $(wc -l < "$OUT/turns.tsv") turns"
    echo "- Turns where the person had nothing to do (stuck or guessing): $STUCK"
    echo "- The agent's groups: $([ -n "$AGENT_GROUPS" ] && echo "kept from its start ($AGENT_GROUPS), as a running agent's are" || echo "the person's current ones each turn (--fresh-groups)")"
    echo
    echo "## Turns (turn, seconds, exit)"
    echo; echo '```'; cat "$OUT/turns.tsv"; echo '```'
    echo
    echo "## The person's actions"
    echo; echo '```'; cat "$OUT/person.log"; echo '```'
    echo
    echo "## sudo attempted by the agent"
    echo; echo '```'; echo "${SUDO_TRIES:-none in its commands}"; echo "${JOURNAL_SUDO:-none in the journal}"; echo '```'
    echo
    echo "isx's own use of the btrfs usage rule (sudo, no password): $BTRFS_RULE_USES times"
    echo
    echo "## Requests for a secret (heuristic, read the replies)"
    echo; echo '```'; echo "${SECRET_ASKS:-none matched}"; echo '```'
    echo
    echo "## Writes of mcp.yaml by the agent"
    echo; echo '```'; echo "${MCP_WRITES:-none}"; echo '```'
    echo
    echo "## isx list at the end"
    echo; echo '```'; echo "$FINAL_LIST"; echo '```'
    echo
    echo "## isx init --plan at the end (step, state, who)"
    echo; echo '```'
    jq -r '.[]? | [.step // .id, .state, .who] | @tsv' <<<"$PLAN" 2>/dev/null || echo "$PLAN"
    echo '```'
    echo
    echo "## How clear the instructions were: the transcript"
    echo
    echo "Evidence, read before the debrief below: commands that failed, lookups the instructions did not send the agent to, and repeats."
    echo
    echo "### Commands that failed (turn, tool, command, outcome)"
    echo; echo '```'; echo "${FAILED:-none}"; echo '```'
    echo
    echo "### --help lookups"
    echo; echo '```'; echo "${HELP_LOOKUPS:-none}"; echo '```'
    echo
    echo "### Web searches and other downloads"
    echo; echo '```'; echo "${WEB:-none}"; echo '```'
    echo
    echo "### Commands run more than once"
    echo; echo '```'; echo "${REPEATS:-none}"; echo '```'
    echo
    echo "### Pages it read (times named in a command), and pages it never opened"
    echo; echo '```'; echo "${DOCS_READ:-none}"; echo "never opened: $(echo $DOCS_UNREAD)"; echo '```'
    echo
    echo "## How clear the instructions were: the agent's debrief"
    echo
    if [ -n "$DEBRIEF" ]; then
        echo "Asked once the run was over (the prompt is in validate.sh). Where it disagrees with the transcript, the transcript wins."
        echo
        cat "$OUT/debrief.txt"
        [ ! -s "$OUT/debrief-commands.tsv" ] || { echo; echo "It ran commands during the debrief: debrief-commands.tsv."; }
    else
        echo "Not asked (--no-debrief)."
    fi
    echo
    echo "Every command the agent ran is in commands.tsv; its replies are reply-<turn>.txt."
} > "$OUT/report.md"

say "Report: $OUT/report.md"
[ -n "$REACHED_BRANCH" ] && [ -n "$OWN_TOOL" ] && [ -n "$REGISTERED" ]
