#!/bin/sh
set -e

REPO="Sanne/incus-spawn"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"

ARCH=$(uname -m)
case "$ARCH" in
  x86_64) ASSET="incus-spawn-linux-amd64" ;;
  *) echo "Error: unsupported architecture: $ARCH (only x86_64 is supported)"; exit 1 ;;
esac

OS=$(uname -s)
if [ "$OS" != "Linux" ]; then
  echo "Error: unsupported OS: $OS (only Linux is supported)"
  exit 1
fi

VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name"' | cut -d'"' -f4)
if [ -z "$VERSION" ]; then
  echo "Error: could not determine latest release version"
  exit 1
fi

echo "Installing incus-spawn $VERSION to $INSTALL_DIR/isx..."
mkdir -p "$INSTALL_DIR"
curl -fsSL "https://github.com/$REPO/releases/download/$VERSION/$ASSET" -o "$INSTALL_DIR/isx"
chmod +x "$INSTALL_DIR/isx"

# Install git remote helper shim for isx:// URLs
curl -fsSL "https://raw.githubusercontent.com/$REPO/$VERSION/src/main/resources/git-remote-isx" -o "$INSTALL_DIR/git-remote-isx"
chmod +x "$INSTALL_DIR/git-remote-isx"

echo "Installed incus-spawn $VERSION to $INSTALL_DIR/isx"
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) echo "Note: add $INSTALL_DIR to your PATH if not already present." ;;
esac
echo "Run 'isx init' to get started."
