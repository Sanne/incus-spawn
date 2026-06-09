#!/bin/bash
# Uninstall incus-spawn (isx) from the local system
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

# Shell completion paths (installed manually via `isx completion --install`)
ZSH_COMPLETION="$HOME/.zsh/completions/_isx"
BASH_COMPLETION="$HOME/.local/share/bash-completion/completions/isx"
FISH_COMPLETION="$HOME/.config/fish/completions/isx.fish"

IS_MACOS=false
[[ "$(uname)" == "Darwin" ]] && IS_MACOS=true

PURGE=false
YES=false
for arg in "$@"; do
    case "$arg" in
        --purge) PURGE=true ;;
        --yes)   YES=true ;;
    esac
done

echo "incus-spawn uninstaller"
echo "======================="
echo ""
echo "This will remove:"
echo "  - Binary:            $INSTALL_DIR/$BINARY_NAME"
echo "  - Git remote helper: $INSTALL_DIR/git-remote-isx"
echo "  - State:             $STATE_DIR/"
echo "  - Appliance:         $APPLIANCE_DIR/"
if $IS_MACOS; then
    echo "  - VM:                stop running VM, remove disk image"
    echo "  - LaunchAgents:      VM and proxy services"
    echo "  - VM client config:  $CONFIG_DIR/vm/"
    echo "  - TCC permissions:   reset home folder and local network approvals"
else
    echo "  - Systemd service:   $SYSTEMD_SERVICE"
fi
echo "  - Shell completions: (if installed)"
if $PURGE; then
    echo "  - Cache:             $CACHE_DIR/  (--purge)"
    echo "  - Config:            $CONFIG_DIR/  (--purge)"
else
    echo ""
    echo "Preserved:             $CONFIG_DIR/"
    echo "                       $CACHE_DIR/"
    echo "  (use --purge to also remove configuration and cache)"
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

if $IS_MACOS; then
    UID_VAL="$(id -u)"

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

    # Stop any running VM process (verify it's actually vfkit/qemu before killing)
    if [ -f "$STATE_DIR/vm.pid" ]; then
        PID="$(cat "$STATE_DIR/vm.pid" 2>/dev/null || true)"
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
            PROC_NAME="$(ps -p "$PID" -o comm= 2>/dev/null || true)"
            case "$PROC_NAME" in
                *vfkit*|*qemu*)
                    echo "Stopping VM (pid=$PID, $PROC_NAME)..."
                    kill "$PID" 2>/dev/null || true
                    sleep 2
                    kill -0 "$PID" 2>/dev/null && kill -9 "$PID" 2>/dev/null || true
                    ;;
                *)
                    echo "Warning: PID $PID is not a VM process ($PROC_NAME), skipping kill"
                    ;;
            esac
        fi
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
    tccutil reset SystemPolicyAllFiles 2>/dev/null || true
    tccutil reset SystemPolicyDocumentsFolder 2>/dev/null || true
    tccutil reset SystemPolicyDesktopFolder 2>/dev/null || true
    tccutil reset SystemPolicyDownloadsFolder 2>/dev/null || true

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

if ! $IS_MACOS; then
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

if [ -f "$INSTALL_DIR/git-remote-isx" ]; then
    echo "Removing $INSTALL_DIR/git-remote-isx..."
    rm -f "$INSTALL_DIR/git-remote-isx"
fi

# ── Remove shell completions ──────────────────────────────────────────────

for f in "$ZSH_COMPLETION" "$BASH_COMPLETION" "$FISH_COMPLETION"; do
    if [ -f "$f" ]; then
        echo "Removing completion: $f"
        rm -f "$f"
    fi
done

# ── Remove state directory (VM disk, logs, app bundle) ────────────────────

if [ -d "$STATE_DIR" ]; then
    echo "Removing $STATE_DIR/..."
    rm -rf "$STATE_DIR"
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
if $IS_MACOS; then
    echo ""
    echo "To reinstall: ./install.sh && isx init"
else
    echo ""
    echo "Note: Incus containers and images created by isx are still present."
    echo "Run 'incus list' to see them, and 'incus delete <name>' to remove them."
fi
