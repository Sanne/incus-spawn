#!/bin/bash
# Uninstall incus-spawn (isx) from the local system
#
#   --purge             also remove configuration and cache
#   --delete-instances  also remove the macOS VM's data disk: every instance,
#                       template and image (kept by default)
#   --binaries-only     remove only the binaries and a Homebrew override, e.g. to
#                       switch from a source build to Homebrew; keeps everything else
#   --yes               do not ask for confirmation
set -e

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BINARY_NAME="isx"
SERVICE_NAME="incus-spawn-proxy"

CONFIG_DIR="$HOME/.config/incus-spawn"
CACHE_DIR="$HOME/.cache/incus-spawn"
STATE_DIR="$HOME/.local/state/incus-spawn"
APPLIANCE_DIR="$HOME/.local/share/incus-spawn"
SYSTEMD_SERVICE="$HOME/.config/systemd/user/${SERVICE_NAME}.service"

# macOS launchd plist paths
LAUNCHD_VM_PLIST="$HOME/Library/LaunchAgents/dev.incusspawn.vm.plist"
LAUNCHD_PROXY_PLIST="$HOME/Library/LaunchAgents/dev.incusspawn.proxy.plist"

# The macOS VM's data disk, mounted at /var/lib/incus in the VM: the Incus
# database and storage pool, so every instance, template and image. Nothing
# else in the state directory is worth keeping, and nothing else is user data.
DATA_DISK="$STATE_DIR/data.img"

# Shell completion paths (installed manually via `isx completion --install`)
ZSH_COMPLETION="$HOME/.zsh/completions/_isx"
BASH_COMPLETION="$HOME/.local/share/bash-completion/completions/isx"
FISH_COMPLETION="$HOME/.config/fish/completions/isx.fish"

IS_MACOS=false
[[ "$(uname)" == "Darwin" ]] && IS_MACOS=true

PURGE=false
YES=false
DELETE_INSTANCES=false
BINARIES_ONLY=false
usage() { echo "Usage: $0 [--purge] [--delete-instances] [--binaries-only] [--yes]"; }
for arg in "$@"; do
    case "$arg" in
        -h|--help)          usage; sed -n '4,10s/^# \{0,1\}//p' "$0"; exit 0 ;;
        --purge)            PURGE=true ;;
        --yes)              YES=true ;;
        --delete-instances) DELETE_INSTANCES=true ;;
        --binaries-only)    BINARIES_ONLY=true ;;
        *) echo "Unknown option: $arg" >&2
           usage >&2
           exit 2 ;;
    esac
done
if $BINARIES_ONLY && { $PURGE || $DELETE_INSTANCES; }; then
    echo "--binaries-only keeps all data; it cannot be combined with --purge or --delete-instances." >&2
    exit 2
fi

# The VM is up when vm.pid names a live vfkit or qemu process. Sets VM_PID and
# VM_NAME for any live process the pid file names, VM or not.
vm_running() {
    VM_PID="$(cat "$STATE_DIR/vm.pid" 2>/dev/null || true)"
    VM_NAME=""
    [ -n "$VM_PID" ] && kill -0 "$VM_PID" 2>/dev/null || return 1
    VM_NAME="$(ps -p "$VM_PID" -o comm= 2>/dev/null || true)"
    case "$VM_NAME" in *vfkit*|*qemu*) return 0 ;; esac
    return 1
}

# The isx being uninstalled, else whichever is on PATH.
find_isx() {
    if [ -x "$INSTALL_DIR/$BINARY_NAME" ]; then echo "$INSTALL_DIR/$BINARY_NAME"
    else command -v "$BINARY_NAME" 2>/dev/null; fi
}

# Run "$@" for at most $1 seconds (macOS ships no timeout(1)).
bounded() { perl -e 'alarm shift; exec @ARGV' "$@"; }

# How long to wait for isx to list what the data disk holds.
LIST_TIMEOUT="${ISX_UNINSTALL_LIST_TIMEOUT:-20}"

# Print what "$@" writes to stdout, run for at most $LIST_TIMEOUT seconds.
# Through a file, never a pipe: $(...) waits until every process holding the
# pipe has closed it, so a child isx left behind would outlast the bound.
bounded_output() {
    local out status=0
    out="$(mktemp)"
    bounded "$LIST_TIMEOUT" "$@" > "$out" 2>/dev/null </dev/null || status=$?
    [ "$status" -eq 0 ] && cat "$out"
    rm -f "$out"
    return "$status"
}

# What the data disk holds, e.g. "3 instance(s) and 2 built template(s)", or
# why it cannot tell: that needs the VM up and a working isx, and is bounded so
# a wedged VM cannot hang the uninstaller.
describe_data_disk() {
    local unknown="could not tell how many instances and templates" isx instances templates
    vm_running || { echo "$unknown: the VM is not running"; return; }
    isx="$(find_isx)" || { echo "$unknown: no isx to ask"; return; }
    if ! instances="$(bounded_output "$isx" list -q)" \
            || ! templates="$(bounded_output "$isx" templates list --format=plain)"; then
        echo "$unknown: isx could not list them"; return
    fi
    # A template's "built" is empty ("-") when isx could not ask Incus
    if printf '%s\n' "$templates" | awk -F'\t' 'NF && $5 == "-" { found = 1 } END { exit !found }'; then
        echo "$unknown: isx could not list them"; return
    fi
    instances="$(printf '%s\n' "$instances" | grep -c . || true)"
    templates="$(printf '%s\n' "$templates" | awk -F'\t' '$5 == "true"' | grep -c . || true)"
    echo "$instances instance(s) and $templates built template(s)"
}

echo "incus-spawn uninstaller"
echo "======================="
echo ""

# The data disk (macOS only) is kept, or deleted with --delete-instances.
KEEP_DATA_DISK=false
DELETE_DATA_DISK=false
VM_STOPPED_UNCLEANLY=false
DATA_DISK_HOLDS=""
if [ -f "$DATA_DISK" ] && ! $BINARIES_ONLY; then
    if $DELETE_INSTANCES; then DELETE_DATA_DISK=true; else KEEP_DATA_DISK=true; fi
    DATA_DISK_HOLDS="$(describe_data_disk)"
fi

if $BINARIES_ONLY; then
    echo "This will remove only the binaries:"
    echo "  - Binaries:          $INSTALL_DIR/{$BINARY_NAME,isx-proxy,git-remote-isx}"
    echo "  - Homebrew override: (if install.sh made one; the Homebrew version is relinked)"
    echo ""
    echo "Kept: instances, templates, configuration, cache, state and services."
    echo "The next isx command points the proxy service at the isx-proxy it finds."
else
    echo "This will remove:"
    echo "  - Binary:            $INSTALL_DIR/$BINARY_NAME"
    echo "  - Git remote helper: $INSTALL_DIR/git-remote-isx"
    if $KEEP_DATA_DISK; then
        echo "  - State:             $STATE_DIR/  (all but the data disk, see below)"
    else
        echo "  - State:             $STATE_DIR/"
    fi
    echo "  - Appliance:         $APPLIANCE_DIR/"
    if $IS_MACOS; then
        echo "  - VM:                stop running VM, remove its root disk and logs"
        echo "  - LaunchAgents:      VM and proxy services"
        echo "  - VM client config:  $CONFIG_DIR/vm/"
        echo "  - TCC permissions:   reset home folder and local network approvals"
    else
        echo "  - Systemd service:   $SYSTEMD_SERVICE"
    fi
    echo "  - Shell completions: (if installed)"
    if $DELETE_DATA_DISK; then
        echo "  - Data disk:         $DATA_DISK  (--delete-instances)"
        echo "                       EVERY instance, template and image: $DATA_DISK_HOLDS"
    fi
    if $PURGE; then
        echo "  - Cache:             $CACHE_DIR/  (--purge)"
        echo "  - Config:            $CONFIG_DIR/  (--purge)"
    else
        echo ""
        echo "Preserved:             $CONFIG_DIR/"
        echo "                       $CACHE_DIR/"
        echo "  (use --purge to also remove configuration and cache)"
    fi
    if $KEEP_DATA_DISK; then
        echo ""
        echo "Kept:                  $DATA_DISK"
        echo "  the VM's data disk, with every instance, template and image"
        echo "  ($DATA_DISK_HOLDS);"
        echo "  reinstalling isx picks them up again (use --delete-instances to remove it)"
    fi
    echo ""
    echo "To only switch install channel (e.g. a source build to Homebrew), use --binaries-only."
fi
echo ""

if ! $YES; then
    read -rp "Proceed? [y/N] " confirm
    case "$confirm" in
        [yY]|[yY][eE][sS]) ;;
        *) echo "Aborted."; exit 0 ;;
    esac
fi

# ── macOS: stop VM and remove launchd services ─────────────────────────────

if $IS_MACOS && ! $BINARIES_ONLY; then
    UID_VAL="$(id -u)"

    # Stop the VM first. A data disk that is kept should be shut down cleanly, so
    # 'isx vm stop' asks the guest first (it waits only a few seconds before it
    # signals vfkit itself); signals here are only the fallback, and say so.
    if vm_running && $KEEP_DATA_DISK && ISX="$(find_isx)"; then
        bounded 60 "$ISX" vm stop </dev/null || true
    fi
    if vm_running; then
        if $KEEP_DATA_DISK; then
            VM_STOPPED_UNCLEANLY=true
            echo "Warning: the VM did not shut down cleanly; stopping it with signals."
            echo "  The kept data disk may need filesystem recovery when the VM next boots."
        fi
        echo "Stopping VM (pid=$VM_PID, $VM_NAME)..."
        kill "$VM_PID" 2>/dev/null || true
        sleep 2
        kill -0 "$VM_PID" 2>/dev/null && kill -9 "$VM_PID" 2>/dev/null || true
    elif [ -n "$VM_NAME" ]; then
        echo "Warning: PID $VM_PID is not a VM process ($VM_NAME), skipping kill"
    fi

    # Stop and remove launchd services
    if [ -f "$LAUNCHD_PROXY_PLIST" ]; then
        echo "Stopping and removing proxy service..."
        launchctl bootout "gui/$UID_VAL" "$LAUNCHD_PROXY_PLIST" 2>/dev/null || true
        rm -f "$LAUNCHD_PROXY_PLIST"
    fi

    if [ -f "$LAUNCHD_VM_PLIST" ]; then
        echo "Stopping and removing VM service..."
        launchctl bootout "gui/$UID_VAL" "$LAUNCHD_VM_PLIST" 2>/dev/null || true
        rm -f "$LAUNCHD_VM_PLIST"
    fi

    # Stop any proxy process on the health port
    lsof -t -i :18080 2>/dev/null | xargs kill 2>/dev/null || true

    # Remove VM client config (certs, remote config)
    VM_CONFIG="$CONFIG_DIR/vm"
    if [ -d "$VM_CONFIG" ]; then
        echo "Removing VM client config ($VM_CONFIG/)..."
        rm -rf "$VM_CONFIG"
    fi

    # Reset TCC permissions so dialogs appear on next install.
    # Home folder access: tracked per TCC service category (not per-app for CLI tools).
    # Local network access: tracked in /Library/Preferences/com.apple.networkextension.plist
    # (requires sudo to modify directly, so we prompt the user if needed).
    echo "Resetting macOS permissions..."
    tccutil reset All dev.incusspawn.vm 2>/dev/null || true
    tccutil reset SystemPolicyAllFiles dev.incusspawn.vm 2>/dev/null || true
    tccutil reset SystemPolicyDocumentsFolder dev.incusspawn.vm 2>/dev/null || true
    tccutil reset SystemPolicyDesktopFolder dev.incusspawn.vm 2>/dev/null || true
    tccutil reset SystemPolicyDownloadsFolder dev.incusspawn.vm 2>/dev/null || true

    # Local network permissions are in a system plist (not TCC).
    # Removing the entries requires sudo.
    if grep -q "incus-spawn" /Library/Preferences/com.apple.networkextension.plist 2>/dev/null; then
        echo ""
        echo "Local network permissions require sudo to reset."
        echo "To reset manually, run:"
        echo "  sudo defaults delete /Library/Preferences/com.apple.networkextension"
        echo "  (this resets local network permissions for ALL apps)"
        echo ""
    fi
fi

# ── Linux: stop and remove systemd proxy service ──────────────────────────

if ! $IS_MACOS && ! $BINARIES_ONLY; then
    if systemctl --user is-active "$SERVICE_NAME" &>/dev/null; then
        echo "Stopping proxy service..."
        systemctl --user stop "$SERVICE_NAME"
    fi

    if [ -f "$SYSTEMD_SERVICE" ]; then
        echo "Disabling and removing proxy service..."
        systemctl --user disable "$SERVICE_NAME" 2>/dev/null || true
        rm -f "$SYSTEMD_SERVICE"
        systemctl --user daemon-reload
    fi
fi

# ── Remove the binary ─────────────────────────────────────────────────────

if [ -f "$INSTALL_DIR/$BINARY_NAME" ]; then
    echo "Removing $INSTALL_DIR/$BINARY_NAME..."
    rm -f "$INSTALL_DIR/$BINARY_NAME"
else
    echo "Binary not found at $INSTALL_DIR/$BINARY_NAME (skipping)"
fi

for f in isx-proxy git-remote-isx; do
    if [ -f "$INSTALL_DIR/$f" ]; then
        echo "Removing $INSTALL_DIR/$f..."
        rm -f "$INSTALL_DIR/$f"
    fi
done

# ── Undo install.sh's Homebrew override ───────────────────────────────────
# install.sh points the brew prefix bin entries at our build (a symlink into
# $INSTALL_DIR; older installs left a real-file copy). Remove only our own
# override — a symlink back into $INSTALL_DIR, or a legacy copy when the
# formula is no longer tracked — then relink so `isx` resolves to the
# Homebrew-managed version again if that formula is still installed.
BREW_FORMULA="incus-spawn"   # formula name differs from the binary name (isx)
if command -v brew >/dev/null 2>&1; then
    BREW_BIN="$(brew --prefix)/bin"
    if [ "$INSTALL_DIR" != "$BREW_BIN" ]; then
        FORMULA_INSTALLED=false
        brew list --formula "$BREW_FORMULA" >/dev/null 2>&1 && FORMULA_INSTALLED=true
        for f in "$BINARY_NAME" isx-proxy git-remote-isx; do
            target="$(readlink "$BREW_BIN/$f" 2>/dev/null || true)"
            if [ "$target" = "$INSTALL_DIR/$f" ]; then
                echo "Removing Homebrew override symlink: $BREW_BIN/$f"
                rm -f "$BREW_BIN/$f"
            elif [ -f "$BREW_BIN/$f" ] && [ ! -L "$BREW_BIN/$f" ] && ! $FORMULA_INSTALLED; then
                # Legacy real-file copy left by an older install.sh.
                echo "Removing Homebrew copy: $BREW_BIN/$f"
                rm -f "$BREW_BIN/$f"
            fi
        done
        if $FORMULA_INSTALLED; then
            echo "Restoring Homebrew-managed $BREW_FORMULA link..."
            brew link --overwrite "$BREW_FORMULA" >/dev/null 2>&1 || true
        fi
    fi
fi

if $BINARIES_ONLY; then
    echo ""
    echo "isx binaries removed; instances, templates, configuration and state are untouched."
    if command -v "$BINARY_NAME" >/dev/null 2>&1; then
        echo "isx now resolves to $(command -v "$BINARY_NAME")"
        echo "Run any isx command (e.g. isx doctor) to point the proxy service at its isx-proxy."
    else
        echo "No other isx is on PATH: install one, e.g. brew install Sanne/tap/incus-spawn"
    fi
    exit 0
fi

# ── Remove shell completions ──────────────────────────────────────────────

for f in "$ZSH_COMPLETION" "$BASH_COMPLETION" "$FISH_COMPLETION"; do
    if [ -f "$f" ]; then
        echo "Removing completion: $f"
        rm -f "$f"
    fi
done

# ── Remove state directory (VM disks, logs, app bundle) ───────────────────

if [ -d "$STATE_DIR" ]; then
    if $KEEP_DATA_DISK; then
        echo "Removing $STATE_DIR/ except the data disk..."
        find "$STATE_DIR" -mindepth 1 -maxdepth 1 ! -name data.img -exec rm -rf {} +
    else
        echo "Removing $STATE_DIR/..."
        rm -rf "$STATE_DIR"
    fi
fi

# ── Remove appliance artifacts (kernel, compressed disk) ──────────────────

if [ -d "$APPLIANCE_DIR" ]; then
    echo "Removing $APPLIANCE_DIR/..."
    rm -rf "$APPLIANCE_DIR"
fi

# ── Remove cache and config (only with --purge) ──────────────────────────

if $PURGE; then
    for dir in "$CACHE_DIR" "$CONFIG_DIR"; do
        if [ -d "$dir" ]; then
            echo "Removing $dir/..."
            rm -rf "$dir"
        fi
    done
fi

echo ""
echo "incus-spawn has been uninstalled."
if ! $PURGE; then
    [ -d "$CONFIG_DIR" ] && echo "Configuration preserved in $CONFIG_DIR/"
    [ -d "$CACHE_DIR" ]  && echo "Cache preserved in $CACHE_DIR/"
fi
if $KEEP_DATA_DISK; then
    echo "Data disk preserved in $DATA_DISK (every instance, template and image)"
    echo "  to delete it: rm $DATA_DISK"
    if $VM_STOPPED_UNCLEANLY; then
        echo "  The VM was stopped with signals, not shut down: on the next boot the"
        echo "  guest may need to recover the disk, and the newest writes may be lost."
    fi
fi
if $IS_MACOS; then
    echo ""
    echo "To reinstall: brew install Sanne/tap/incus-spawn && isx init"
else
    echo ""
    echo "Note: Incus containers and images created by isx are still present."
    echo "Run 'incus list' to see them, and 'incus delete <name>' to remove them."
fi
