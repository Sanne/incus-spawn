#!/bin/bash
# Re-record the isx.run homepage casts against the isx on this machine, then compress them into
# the *-web.cast files site/build.sh publishes. See SCRIPT.md for the story of each cast and the
# one-time staging (tpl-demo, workbench).
#
# Usage: site/demo/record-all.sh [--clean] [cast ...]
#   cast     hero, branching, realmachine, creds or tui; all five when none is given
#   --clean  destroy the instances a previous take left (fix-bug, exp-a, exp-b, review-pr-512, and
#            'experiment' when creds, realmachine or tui is recorded), and reset ~/sources/isx-demo
#            to origin/main.
#            Without it, the script stops and names what is in the way.
# Every creds or realmachine run branches 'experiment' fresh from tpl-demo and destroys it after
# its last take.
#
# The shell's TIMEFORMAT makes 'time' print one line, for the branching cast's timed forks.
# Environment: ISX_DEMO_REPO (default ~/sources/isx-demo). The TUI cast shows your own board.
# Runs on macOS's bash 3.2.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=${ISX_DEMO_REPO:-$HOME/sources/isx-demo}
COLS=80
ROWS=26
LEFTOVERS="fix-bug exp-a exp-b review-pr-512"

# The order the casts are recorded in, whatever order they are named in: creds before realmachine,
# since both use 'experiment' and creds needs it fresh (realmachine runs dnf and podman in it).
ORDER="hero branching creds realmachine tui"

clean=false
named=" "
for a in "$@"; do
  case "$a" in
    --clean) clean=true ;;
    hero|branching|realmachine|creds|tui) named="$named$a " ;;
    *) echo "Unknown argument: $a" >&2; exit 2 ;;
  esac
done
casts=()
for c in $ORDER; do
  case "$named" in " ") casts+=("$c") ;; *" $c "*) casts+=("$c") ;; esac
done

die() { echo "Error: $*" >&2; exit 1; }
step() { echo "==> $*"; }
wants() { case " ${casts[*]} " in *" $1 "*) return 0 ;; esac; return 1; }
# Not grep -q: under pipefail, grep exiting at the first match could fail the pipe with SIGPIPE.
has_instance() { isx list -q | grep -x -- "$1" >/dev/null; }

command -v isx >/dev/null || die "isx is not on PATH"
command -v python3 >/dev/null || die "python3 is not on PATH"

# Every check before anything changes, all reported at once.
step "Preflight"
problems=()
problem() { problems+=("$*"); }

isx templates --format=json | python3 -c '
import json, sys
t = {r["name"]: r for r in json.load(sys.stdin)}
sys.exit(0 if t.get("tpl-demo", {}).get("built") else 1)' \
  || problem "tpl-demo is not built: see SCRIPT.md, appendix A"

if ! $clean; then
  for n in $LEFTOVERS; do
    if has_instance "$n"; then problem "'$n' is left from an earlier take: isx destroy $n (or rerun with --clean)"; fi
  done
  if { wants creds || wants realmachine; } && has_instance experiment; then
    # A used branch remembers its rehearsals: the creds take must find nothing, and realmachine
    # must install btop and pull its image on camera, not find them there.
    problem "'experiment' is left from an earlier take, and the creds and realmachine casts need a fresh one: isx destroy experiment (or rerun with --clean)"
  elif wants tui && has_instance experiment; then
    problem "'experiment' is left from an earlier take, and the TUI board must not show it: isx destroy experiment (or rerun with --clean)"
  fi
fi

dirty_repo=false
# Every shell take starts with cd '$REPO', and hero and branching also need it at origin/main.
if wants hero || wants branching || wants creds || wants realmachine; then
  if [ ! -d "$REPO/.git" ]; then
    problem "$REPO is not a checkout of https://github.com/Sanne/isx-demo"
  elif wants hero || wants branching; then
    if ! git -C "$REPO" fetch -q origin; then
      problem "git -C $REPO fetch origin failed, so its checkout cannot be compared with origin/main"
    elif [ "$(git -C "$REPO" rev-parse HEAD)" != "$(git -C "$REPO" rev-parse origin/main)" ] \
        || [ -n "$(git -C "$REPO" status --porcelain)" ]; then
      dirty_repo=true
      $clean || problem "$REPO is not a clean origin/main: git -C $REPO checkout main && git -C $REPO reset --hard origin/main (or rerun with --clean)"
    fi
  fi
fi

if wants branching && ! has_instance workbench; then
  problem "no 'workbench' instance: see SCRIPT.md, appendix C"
fi

if [ ${#problems[@]} -gt 0 ]; then
  echo "Not ready to record:" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  exit 1
fi

if $clean; then
  for n in $LEFTOVERS; do
    if has_instance "$n"; then isx destroy "$n" --skip-confirmation; fi
  done
  if { wants creds || wants realmachine || wants tui; } && has_instance experiment; then
    isx destroy experiment --skip-confirmation
  fi
  if $dirty_repo; then
    git -C "$REPO" checkout -q -f main
    git -C "$REPO" reset -q --hard origin/main
  fi
fi

last_experiment=""
if wants creds || wants realmachine; then
  # A fresh branch of tpl-demo has neither btop nor the hello image, so it needs no preparation
  # (and no way to run commands in it: tpl-demo has no sshd). The order puts creds first.
  # Preflight and --clean have made sure no earlier 'experiment' is left.
  step "Branching a fresh 'experiment'"
  isx branch --from tpl-demo experiment --format=plain >/dev/null
  # Destroyed after the last take that uses it, so the TUI board does not show it.
  if wants realmachine; then last_experiment=realmachine; else last_experiment=creds; fi
fi

SHELL_CMD="cd '$REPO'; PATH=\$HOME/.local/bin:\$PATH PS1='➜ ' GIT_PAGER=cat TIMEFORMAT='took %Rs' BASH_SILENCE_DEPRECATION_WARNING=1 exec bash --norc"

failed=""
skip_tui=false
for c in "${casts[@]}"; do
  if [ "$c" = tui ] && $skip_tui; then continue; fi
  step "Recording $c.cast"
  if [ "$c" = tui ]; then
    cmd="PATH=\$HOME/.local/bin:\$PATH exec isx"
  else
    cmd=$SHELL_CMD
  fi
  # A failed take or compress still gets its cleanup below; the script fails at the end.
  ok=true
  # The take goes to a scratch file, so a failed one leaves the committed $c.cast alone too.
  take="$HERE/$c.take.cast"
  python3 "$HERE/record.py" "$HERE/scenario-$c.json" "$take" --cols $COLS --rows $ROWS --cmd "$cmd" || ok=false

  case "$c" in
    # The agent's minutes of work fast-forward to its last 8 seconds (the markers sit 10s after
    # the task is sent and 12s of quiet after the agent's last output).
    hero) opts=(--max-gap 0.8 --scale 1.22 --cut-mark agent-start:agent-done:20 --pad-end 4) ;;
    tui)  opts=(--max-gap 0.8 --pad-end 7) ;;
    *)    opts=(--max-gap 0.8 --pad-end 4) ;;
  esac
  if $ok; then
    python3 "$HERE/compress.py" "$take" "$HERE/$c-web.cast" "${opts[@]}" || ok=false
  fi
  if $ok; then mv "$take" "$HERE/$c.cast"; fi
  $ok || failed="$failed $c"

  case "$c" in
    hero) isx destroy fix-bug --skip-confirmation 2>/dev/null || true
          git -C "$REPO" reset -q --hard origin/main ;;
    branching) for n in exp-a exp-b; do isx destroy "$n" --skip-confirmation 2>/dev/null || true; done ;;
    tui) isx destroy review-pr-512 --skip-confirmation 2>/dev/null || true ;;
  esac
  if [ "$c" = "$last_experiment" ] && ! isx destroy experiment --skip-confirmation; then
    # The preflight refuses a tui take with 'experiment' on the board; so does this.
    if wants tui; then
      echo "Error: could not destroy 'experiment', so the tui take is skipped: isx destroy experiment, then rerun with tui" >&2
      skip_tui=true
      failed="$failed tui"
    else
      echo "Warning: could not destroy 'experiment', so the TUI board may show it: isx destroy experiment" >&2
    fi
  fi
done

[ -z "$failed" ] || die "these casts failed or were skipped, so neither their .cast nor their -web.cast is updated (a failed take is in <cast>.take.cast):$failed"
step "Done. Watch each one before committing: asciinema play site/demo/<cast>-web.cast"
