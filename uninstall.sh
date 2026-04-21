#!/bin/bash
# Uninstall incus-spawn (isx) from the local system
set -e

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BINARY_NAME="isx"
SERVICE_NAME="incus-spawn-proxy"

CONFIG_DIR="$HOME/.config/incus-spawn"
CACHE_DIR="$HOME/.cache/incus-spawn"
STATE_DIR="$HOME/.local/state/incus-spawn"
SYSTEMD_SERVICE="$HOME/.config/systemd/user/${SERVICE_NAME}.service"

# Shell completion paths (installed manually via `isx completion --install`)
ZSH_COMPLETION="$HOME/.zsh/completions/_isx"
BASH_COMPLETION="$HOME/.local/share/bash-completion/completions/isx"
FISH_COMPLETION="$HOME/.config/fish/completions/isx.fish"

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
echo "  - Cache:             $CACHE_DIR/"
echo "  - State:             $STATE_DIR/"
echo "  - Systemd service:   $SYSTEMD_SERVICE"
echo "  - Shell completions: (if installed)"
if $PURGE; then
    echo "  - Config:            $CONFIG_DIR/  (--purge)"
else
    echo ""
    echo "Config preserved:      $CONFIG_DIR/"
    echo "  (use --purge to also remove configuration)"
fi
echo ""

if ! $YES; then
    read -rp "Proceed? [y/N] " confirm
    case "$confirm" in
        [yY]|[yY][eE][sS]) ;;
        *) echo "Aborted."; exit 0 ;;
    esac
fi

# ── Stop and remove the systemd proxy service ───────────────────────────────

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

# ── Remove the binary ───────────────────────────────────────────────────────

if [ -f "$INSTALL_DIR/$BINARY_NAME" ]; then
    echo "Removing $INSTALL_DIR/$BINARY_NAME..."
    rm -f "$INSTALL_DIR/$BINARY_NAME"
else
    echo "Binary not found at $INSTALL_DIR/$BINARY_NAME (skipping)"
fi

# ── Remove shell completions ────────────────────────────────────────────────

for f in "$ZSH_COMPLETION" "$BASH_COMPLETION" "$FISH_COMPLETION"; do
    if [ -f "$f" ]; then
        echo "Removing completion: $f"
        rm -f "$f"
    fi
done

# ── Remove cache and state directories ───────────────────────────────────────

for dir in "$CACHE_DIR" "$STATE_DIR"; do
    if [ -d "$dir" ]; then
        echo "Removing $dir/..."
        rm -rf "$dir"
    fi
done

# ── Remove config (only with --purge) ───────────────────────────────────────

if $PURGE; then
    if [ -d "$CONFIG_DIR" ]; then
        echo "Removing $CONFIG_DIR/..."
        rm -rf "$CONFIG_DIR"
    fi
fi

echo ""
echo "incus-spawn has been uninstalled."
if ! $PURGE && [ -d "$CONFIG_DIR" ]; then
    echo "Configuration preserved in $CONFIG_DIR/"
fi
echo ""
echo "Note: Incus containers and images created by isx are still present."
echo "Run 'incus list' to see them, and 'incus delete <name>' to remove them."
