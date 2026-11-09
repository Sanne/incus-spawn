#!/bin/bash
# Record the isx.run homepage casts in a clean, disposable VM instead of on this machine (#2054).
#
# On this host: branches a VM from tpl-demo-recorder (.incus-spawn/images/demo-recorder.yaml, built
# when missing or stale), copies site/demo and scripts/local-incus.sh into it, and runs this script
# there with --inside. The recorded casts are then copied back into site/demo/ and the VM destroyed.
# In the VM (--inside): installs the pinned isx release from COPR, writes a fresh config.yaml whose
# claude and github accounts are 'inherit', brings Incus up with scripts/local-incus.sh, starts
# the proxy, builds tpl-demo, seeds 'workbench', clones isx-demo and runs record-all.sh.
#
# No credential enters the VM: its instances borrow the VM's grants through this host's proxy, so
# the casts show exactly what a viewer would get. The board, paths and binary they show are a
# fresh install of a release, never this machine's.
#
# Usage: site/demo/record-in-vm.sh [--keep] [cast ...]
#   cast    hero, branching, realmachine, creds or tui, as for record-all.sh; all five when none
#   --keep  leave the VM running afterwards, to look inside (isx destroy it when done)
# Environment:
#   ISX_RECORD_VERSION  the isx release to record with (default 0.5.0)
#   ISX_RECORDER        the VM's name (default demo-recorder)
#   ISX_RECORDER_MEMORY  its memory (default 8GB); ISX_RECORDER_DISK  its disk (default the template's)
#
# Requires: Linux with KVM, a working isx setup whose claude and github accounts authenticate, and
# the incus client with access to isx's daemon. A take needs a real Claude to do the hero's work.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
VERSION=${ISX_RECORD_VERSION:-0.5.0}
VM=${ISX_RECORDER:-demo-recorder}
CASTS="hero branching realmachine creds tui"
ROOT=$(cd "$HERE/../.." && pwd)
# Where the kit is copied in the VM, and the marker the casts recorded there are newer than.
VM_KIT=/home/agentuser/incus-spawn
MARK=.record-start

die() { echo "Error: $*" >&2; exit 1; }
step() { echo "==> $*"; }

inside=false
ready=false
keep=false
casts=()
for a in "$@"; do
  case "$a" in
    --inside) inside=true ;;
    --inside-ready) ready=true ;;
    --keep) keep=true ;;
    *)
      case " $CASTS " in *" $a "*) casts+=("$a") ;; *) echo "Unknown argument: $a" >&2; exit 2 ;; esac ;;
  esac
done

# ---- In the VM, once Incus is up: run as a member of incus-admin ----

if $ready; then
  if ! isx proxy status >/dev/null 2>&1; then
    step "Starting the proxy"
    nohup setsid isx proxy start > "$HOME/isx-proxy.log" 2>&1 < /dev/null &
    for _ in $(seq 1 60); do
      isx proxy status >/dev/null 2>&1 && break
      sleep 0.5
    done
    isx proxy status >/dev/null 2>&1 || die "the proxy did not start: see ~/isx-proxy.log"
  fi

  step "Building tpl-demo"
  mkdir -p "$HOME/.config/incus-spawn/images"
  cp "$HERE/tpl-demo.yaml" "$HOME/.config/incus-spawn/images/tpl-demo.yaml"
  isx build tpl-demo --yes

  step "Cloning isx-demo"
  mkdir -p "$HOME/sources"
  [ -d "$HOME/sources/isx-demo/.git" ] || git clone -q https://github.com/Sanne/isx-demo.git "$HOME/sources/isx-demo"

  # Only the branching cast forks 'workbench'; record-all.sh refuses to record it without one.
  wants_branching=false
  case " ${casts[*]:-$CASTS} " in *" branching "*) wants_branching=true ;; esac
  if $wants_branching && ! isx list -q | grep -x workbench >/dev/null; then
    step "Seeding 'workbench' (SCRIPT.md, appendix C)"
    isx branch --from tpl-demo workbench --format=plain >/dev/null
    incus exec workbench -- su - agentuser -c "set -e
      sudo dnf install -y -q postgresql-server
      sudo /usr/bin/postgresql-setup --initdb
      sudo systemctl enable --now postgresql
      sudo -u postgres createuser -s agentuser
      sudo -u postgres createdb agentuser
      psql -qc 'create table orders as select generate_series(1,100000) as id, md5(random()::text) as sku;'"
  fi

  # A fork right after instances were destroyed waits behind btrfs cleaning them up, several
  # times slower (#2061): record-all.sh waits for that before each take.
  pool=$(incus profile device get default root pool)
  if [ "$(incus storage list -f csv | awk -F, -v pool="$pool" '$1 == pool { print $2 }')" = btrfs ]; then
    export ISX_DEMO_SETTLE="sudo -n btrfs subvolume sync /var/lib/incus/storage-pools/$pool >/dev/null"
  fi

  touch "$HERE/$MARK"
  cd "$HOME"
  exec bash "$HERE/record-all.sh" ${casts[@]+"${casts[@]}"}
fi

# ---- In the VM: install the release and bring up Incus ----

if $inside; then
  # Only ever in the fresh recording VM: on a real host this would replace its config.yaml.
  [ ! -e "$HOME/.config/incus-spawn/config.yaml" ] \
    || die "--inside is for the fresh recording VM, and $HOME/.config/incus-spawn/config.yaml already exists"
  step "Installing isx $VERSION"
  sudo dnf install -y -q "incus-spawn-$VERSION"
  installed=$(isx --version | head -n 1)
  case "$installed" in
    "isx $VERSION "*) ;;
    *) die "asked for isx $VERSION, but isx --version says: $installed" ;;
  esac

  # Written before 'isx init' runs, which keeps what it finds: borrowed accounts, so no
  # credential is asked for or stored here, the host path the casts' git remotes are added in,
  # and the status bar the shell takes show.
  config="$HOME/.config/incus-spawn/config.yaml"
  mkdir -p "$(dirname "$config")"
  cat > "$config" <<'EOF'
claude:
  default: inherit
github:
  default: inherit
host-paths:
- ~/sources
features:
- shell-status-bar
EOF

  step "Bringing up Incus"
  bash "$ROOT/scripts/local-incus.sh"
  for key in "default: inherit" "~/sources" "shell-status-bar"; do
    grep -qF -- "$key" "$config" || die "isx init dropped '$key' from $config"
  done

  # This session predates the incus-admin membership local-incus.sh added.
  exec sg incus-admin -c "$(printf '%q ' bash "$0" --inside-ready ${casts[@]+"${casts[@]}"})"
fi

# ---- On this host ----

[ "$(uname -s)" = Linux ] || die "Linux only: the recording VM needs KVM, and on macOS isx's own Incus already runs in a VM"
command -v isx >/dev/null || die "isx is not on PATH"
command -v incus >/dev/null || die "the incus client is not on PATH"
# tpl-demo-recorder is a project-local template, so isx finds it from the checkout.
cd "$ROOT"

if isx list -q | grep -x -- "$VM" >/dev/null; then
  die "'$VM' already exists, from an earlier run: isx destroy $VM (or set ISX_RECORDER to another name)"
fi

# Built when missing, and rebuilt when its definition, isx or its parent changed.
if ! isx templates --format=json | python3 -c '
import json, sys
t = {r["name"]: r for r in json.load(sys.stdin)}.get("tpl-demo-recorder")
if t is None:
    sys.exit("tpl-demo-recorder is not defined: run this from a checkout of incus-spawn")
sys.exit(0 if t["built"] and not (t["version_outdated"] or t["definition_changed"] or t["parent_rebuilt"]) else 1)'; then
  step "Building tpl-demo-recorder"
  isx build tpl-demo-recorder --yes
fi

cleanup() {
  if $keep; then
    echo "'$VM' is kept: isx shell $VM to look inside, isx destroy $VM when done"
  elif isx list -q | grep -x -- "$VM" >/dev/null; then
    isx destroy "$VM" --skip-confirmation >/dev/null || echo "Warning: could not destroy '$VM': isx destroy $VM" >&2
  fi
}
trap cleanup EXIT

step "Branching '$VM'"
limits=(--memory="${ISX_RECORDER_MEMORY:-8GB}")
[ -z "${ISX_RECORDER_DISK:-}" ] || limits+=(--disk="$ISX_RECORDER_DISK")
isx branch --from tpl-demo-recorder "$VM" "${limits[@]}" --format=plain >/dev/null

in_vm() { incus exec "$VM" -- su - agentuser -c "$1"; }

step "Copying the recording kit into '$VM'"
tar -C "$ROOT" --exclude='*.take.cast' --exclude=__pycache__ --exclude=preview.html \
    -cf - site/demo scripts/local-incus.sh \
  | in_vm "rm -rf '$VM_KIT' && mkdir -p '$VM_KIT' && tar -xf - -C '$VM_KIT'"

recorded=true
in_vm "$(printf '%q ' env ISX_RECORD_VERSION="$VERSION" bash "$VM_KIT/site/demo/record-in-vm.sh" --inside ${casts[@]+"${casts[@]}"})" \
  || recorded=false

# Whatever was recorded, a failed take's scratch file included: record-all.sh only replaces the
# casts whose take and compression both worked.
step "Copying the casts back into site/demo"
# No marker (the run stopped before recording): an empty archive, rather than none for tar to refuse.
pulled=true
in_vm "cd '$VM_KIT/site/demo' && { [ ! -e '$MARK' ] || find . -maxdepth 1 -name '*.cast' -newer '$MARK'; } | tar -cf - -T -" \
  | tar -xvf - -C "$HERE" | sed 's|^\./|  |' || pulled=false
if ! $pulled; then
  # Destroying the VM now would lose what it recorded.
  keep=true
  die "could not copy the casts back from '$VM', so it is kept: copy them from $VM_KIT/site/demo, then isx destroy $VM"
fi

$recorded || die "recording failed in '$VM' (see above); rerun with --keep to look inside"
step "Done. Watch each one before committing: asciinema play site/demo/<cast>-web.cast"
