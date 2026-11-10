#!/bin/sh
set -e

REPO="Sanne/incus-spawn"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"

OS=$(uname -s)
if [ "$OS" = "Darwin" ]; then
  # A shell under Rosetta reports x86_64 on Apple Silicon too, so ask the hardware
  if [ "$(uname -m)" = "arm64" ] || [ "$(sysctl -n hw.optional.arm64 2>/dev/null)" = "1" ]; then
    echo "On macOS, install isx with Homebrew: brew install Sanne/tap/incus-spawn"
  else
    echo "Error: isx is not released for Intel Macs, only for Apple Silicon (v0.4.2 and v0.5.0-dev.2 were the last with an Intel build)."
  fi
  exit 1
fi

ARCH=$(uname -m)
case "$ARCH" in
  x86_64)  ASSET="incus-spawn-linux-amd64"; PROXY_ASSET="isx-proxy-linux-amd64" ;;
  aarch64) ASSET="incus-spawn-linux-aarch64"; PROXY_ASSET="isx-proxy-linux-aarch64" ;;
  *) echo "Error: unsupported architecture: $ARCH (only x86_64 and aarch64 are supported)"; exit 1 ;;
esac

if [ "$OS" != "Linux" ]; then
  echo "Error: unsupported OS: $OS (only Linux is supported)"
  exit 1
fi

VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name"' | cut -d'"' -f4)
if [ -z "$VERSION" ]; then
  echo "Error: could not determine latest release version"
  exit 1
fi

if ! command -v sha256sum >/dev/null 2>&1; then
  echo "Error: sha256sum is required to verify the download (it is part of coreutils)"
  exit 1
fi

# Everything is downloaded and checked here first, so a tampered or interrupted download never
# reaches INSTALL_DIR. The release's SHA256SUMS is required: a release without one installs nothing.
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"; rm -f "$INSTALL_DIR"/.isx-install.*' EXIT
trap 'exit 1' HUP INT TERM

BASE_URL="https://github.com/$REPO/releases/download/$VERSION"
status=0
curl -fsSL "$BASE_URL/SHA256SUMS" -o "$TMP_DIR/SHA256SUMS" || status=$?
if [ "$status" -eq 22 ]; then
  echo "Error: release $VERSION publishes no SHA256SUMS, so its downloads cannot be verified; nothing was installed."
  exit 1
elif [ "$status" -ne 0 ]; then
  echo "Error: could not download the SHA256SUMS of release $VERSION (curl exit $status); nothing was installed."
  exit 1
fi

echo "Downloading incus-spawn $VERSION..."
for name in "$ASSET" "$PROXY_ASSET" git-remote-isx; do
  if ! curl -fsSL "$BASE_URL/$name" -o "$TMP_DIR/$name"; then
    echo "Error: could not download $name; nothing was installed."
    exit 1
  fi
  expected=$(awk -v n="$name" '$2 == n || $2 == "*" n { print $1 }' "$TMP_DIR/SHA256SUMS")
  if [ -z "$expected" ] || [ "$(printf '%s\n' "$expected" | wc -l)" -ne 1 ]; then
    echo "Error: the release's SHA256SUMS does not list $name exactly once; nothing was installed."
    exit 1
  fi
  actual=$(sha256sum "$TMP_DIR/$name" | cut -d' ' -f1)
  if [ "$actual" != "$expected" ]; then
    echo "Error: $name does not match the release's SHA256SUMS (expected $expected, got $actual); nothing was installed."
    exit 1
  fi
done
echo "Verified the downloads against the release's SHA256SUMS."

# Each file is copied beside its destination and renamed over it, so an existing isx is replaced
# whole or not at all.
mkdir -p "$INSTALL_DIR"
for pair in "$ASSET:isx" "$PROXY_ASSET:isx-proxy" "git-remote-isx:git-remote-isx"; do
  cp "$TMP_DIR/${pair%%:*}" "$INSTALL_DIR/.isx-install.${pair#*:}"
  chmod +x "$INSTALL_DIR/.isx-install.${pair#*:}"
done
for target in isx isx-proxy git-remote-isx; do
  mv -f "$INSTALL_DIR/.isx-install.$target" "$INSTALL_DIR/$target"
done

echo "Installed incus-spawn $VERSION to $INSTALL_DIR/isx"
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) echo "Note: add $INSTALL_DIR to your PATH if not already present." ;;
esac
echo "Run 'isx init' to get started."
