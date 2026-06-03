#!/bin/bash
set -euo pipefail

# Build a minimal custom kernel for the incus-spawn VM appliance.
#
# Downloads vanilla kernel source from kernel.org, applies the isx.config
# fragment on top of allnoconfig, and builds a vmlinuz with zero modules.
#
# Usage:  ./build-kernel.sh [output-dir] [arch]
#
# Requirements: build-essential flex bison bc libelf-dev libssl-dev

KERNEL_VERSION="7.0.10"
KERNEL_MAJOR="${KERNEL_VERSION%%.*}"
KERNEL_URL="https://cdn.kernel.org/pub/linux/kernel/v${KERNEL_MAJOR}.x/linux-${KERNEL_VERSION}.tar.xz"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${1:-$SCRIPT_DIR/../build}"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
BUILD_ARCH="${2:-$(uname -m)}"
CACHE_DIR="${KERNEL_CACHE_DIR:-/tmp/kernel-cache}"
CONFIG_FRAGMENT="$SCRIPT_DIR/isx.config"

case "$BUILD_ARCH" in
    x86_64)  KARCH="x86";   TARGET="bzImage"; IMAGE="arch/x86/boot/bzImage" ;;
    aarch64) KARCH="arm64";  TARGET="Image";   IMAGE="arch/arm64/boot/Image" ;;
    *) echo "ERROR: unsupported architecture: $BUILD_ARCH" >&2; exit 1 ;;
esac

echo "Building kernel $KERNEL_VERSION for $BUILD_ARCH..."
echo "  Config: $CONFIG_FRAGMENT"
echo "  Output: $OUTPUT_DIR"

mkdir -p "$CACHE_DIR"

TARBALL="$CACHE_DIR/linux-${KERNEL_VERSION}.tar.xz"
if [ ! -f "$TARBALL" ]; then
    echo "==> Downloading kernel source..."
    curl -fSL "$KERNEL_URL" -o "$TARBALL.tmp"
    mv "$TARBALL.tmp" "$TARBALL"
else
    echo "==> Using cached kernel source"
fi

BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT

echo "==> Extracting source..."
tar xf "$TARBALL" -C "$BUILD_DIR" --strip-components=1

cd "$BUILD_DIR"

echo "==> Configuring (allnoconfig + isx.config)..."
KCONFIG_ALLCONFIG="$CONFIG_FRAGMENT" make -s ARCH="$KARCH" allnoconfig
make -s ARCH="$KARCH" olddefconfig

echo "==> Validating config fragment..."
APPLIED=0
SKIPPED=0
while IFS= read -r line; do
    key="${line%%=*}"
    value="${line#*=}"
    if [ "$value" = "n" ]; then
        # =n is represented as "# CONFIG_X is not set" in .config
        if grep -q "^${key}=" .config 2>/dev/null; then
            actual=$(grep "^${key}=" .config)
            echo "  WARN: $key=n requested but .config has $actual"
            SKIPPED=$((SKIPPED + 1))
        else
            APPLIED=$((APPLIED + 1))
        fi
    elif [ "$value" = "y" ]; then
        if grep -q "^${key}=y" .config 2>/dev/null; then
            APPLIED=$((APPLIED + 1))
        else
            echo "  WARN: $key=y not in .config (arch-specific or unmet dependency)"
            SKIPPED=$((SKIPPED + 1))
        fi
    else
        # Numeric or string value
        actual=$(grep "^${key}=" .config 2>/dev/null || true)
        if [ "$actual" = "$line" ]; then
            APPLIED=$((APPLIED + 1))
        elif [ -n "$actual" ]; then
            echo "  WARN: expected $line but got $actual"
            SKIPPED=$((SKIPPED + 1))
        else
            echo "  WARN: $line not in .config"
            SKIPPED=$((SKIPPED + 1))
        fi
    fi
done < <(grep '^CONFIG_' "$CONFIG_FRAGMENT" | grep -v '^#')
echo "  $APPLIED applied, $SKIPPED skipped"

echo "==> Building ($TARGET)..."
make -j"$(nproc)" ARCH="$KARCH" "$TARGET"

if [ ! -f "$IMAGE" ]; then
    echo "ERROR: kernel image not found at $IMAGE" >&2
    exit 1
fi

cp "$IMAGE" "$OUTPUT_DIR/vmlinuz"
chmod 644 "$OUTPUT_DIR/vmlinuz"

echo
echo "Kernel built successfully:"
ls -lh "$OUTPUT_DIR/vmlinuz"
