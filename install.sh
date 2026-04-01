#!/bin/bash
# Build and install incus-spawn native binary as 'isx'
set -e

INSTALL_DIR="${1:-$HOME/.local/bin}"
BINARY_NAME="isx"

echo "Building native image (this may take a minute)..."
./mvnw package -Dnative -Dquarkus.native.container-build=true -DskipTests -q

echo "Installing to ${INSTALL_DIR}/${BINARY_NAME}..."
mkdir -p "$INSTALL_DIR"
cp target/incus-spawn-*-runner "$INSTALL_DIR/$BINARY_NAME"
chmod +x "$INSTALL_DIR/$BINARY_NAME"

echo "Installed. Run 'isx' to get started."
