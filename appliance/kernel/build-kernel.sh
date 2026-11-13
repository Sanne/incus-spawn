#!/bin/bash
set -euo pipefail

# Build a minimal custom kernel for the incus-spawn VM appliance, or for the
# VM base image.
#
# Downloads vanilla kernel source from kernel.org, verifies it against the
# pinned KERNEL_SHA256, applies the role's fragments on top of allnoconfig, and
# builds a vmlinuz with zero modules:
#   appliance  isx.config + isx-<arch>.config (the macOS appliance)
#   vm         those, then vm.config + vm-<arch>.config: the VM base image's
#              kernel, an EFI application with its command line built in
#
# Usage:  ./build-kernel.sh [appliance|vm] [output-dir] [arch]
#
# The role defaults to appliance, so the older two-argument form still builds
# the appliance kernel. The default output is ../build for appliance and
# ../build/vm-kernel for vm.
#
# A non-host [arch] is cross-compiled with <arch>-linux-gnu- unless
# CROSS_COMPILE is set.
#
# Requirements: build-essential flex bison bc libelf-dev libssl-dev

KERNEL_VERSION="7.3.4"
# sha256 of linux-${KERNEL_VERSION}.tar.xz, taken from kernel.org's signed
# sha256sums.asc (https://cdn.kernel.org/pub/linux/kernel/v<major>.x/), never
# from a build log. A version bump updates both lines.
KERNEL_SHA256="b11b6df8295e8e991ff040b71aaa4994a8b55caa23b6b092907f195b5840fd1f"
KERNEL_MAJOR="${KERNEL_VERSION%%.*}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROLE="appliance"
case "${1:-}" in
    appliance|vm) ROLE="$1"; shift ;;
esac
# The vm kernel never defaults to the appliance's directory: build.sh reuses a
# vmlinuz it finds there, and the vm kernel does not boot the appliance.
DEFAULT_OUTPUT="$SCRIPT_DIR/../build"
[ "$ROLE" = vm ] && DEFAULT_OUTPUT="$SCRIPT_DIR/../build/vm-kernel"
OUTPUT_DIR="${1:-$DEFAULT_OUTPUT}"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
BUILD_ARCH="${2:-$(uname -m)}"
CACHE_DIR="${KERNEL_CACHE_DIR:-/tmp/kernel-cache}"
# Later fragments override earlier ones: each arch fragment follows its shared
# one, and the vm pair follows the appliance pair it builds on.
FRAGMENTS=("$SCRIPT_DIR/isx.config" "$SCRIPT_DIR/isx-${BUILD_ARCH}.config")
if [ "$ROLE" = vm ]; then
    FRAGMENTS+=("$SCRIPT_DIR/vm.config" "$SCRIPT_DIR/vm-${BUILD_ARCH}.config")
fi

case "$BUILD_ARCH" in
    x86_64)  KARCH="x86";   TARGET="bzImage"; IMAGE="arch/x86/boot/bzImage" ;;
    aarch64) KARCH="arm64";  TARGET="Image";   IMAGE="arch/arm64/boot/Image" ;;
    *) echo "ERROR: unsupported architecture: $BUILD_ARCH" >&2; exit 1 ;;
esac

# Building the other arch needs a cross toolchain (Fedora: gcc-<arch>-linux-gnu
# and binutils-<arch>-linux-gnu). An explicit CROSS_COMPILE still wins.
if [ "$BUILD_ARCH" != "$(uname -m)" ] && [ -z "${CROSS_COMPILE:-}" ]; then
    export CROSS_COMPILE="${BUILD_ARCH}-linux-gnu-"
    command -v "${CROSS_COMPILE}gcc" >/dev/null 2>&1 || {
        echo "ERROR: cross-compiling for $BUILD_ARCH needs ${CROSS_COMPILE}gcc (dnf install gcc-${BUILD_ARCH}-linux-gnu binutils-${BUILD_ARCH}-linux-gnu)" >&2
        exit 1
    }
fi

for f in "${FRAGMENTS[@]}"; do
    [ -f "$f" ] || { echo "ERROR: config fragment not found: $f" >&2; exit 1; }
done

FRAGMENT_NAMES=$(for f in "${FRAGMENTS[@]}"; do basename "$f"; done | paste -sd+ | sed 's/+/ + /g')

echo "Building the $ROLE kernel $KERNEL_VERSION for $BUILD_ARCH..."
echo "  Config: $FRAGMENT_NAMES"
echo "  Output: $OUTPUT_DIR"

mkdir -p "$CACHE_DIR"

TARBALL="$CACHE_DIR/linux-${KERNEL_VERSION}.tar.xz"

# Checked on a cache hit too: CI restores the cache dir from an earlier run.
verify_tarball() {
    local actual
    actual=$(sha256sum "$1" | cut -d' ' -f1)
    if [ "$actual" != "$KERNEL_SHA256" ]; then
        rm -f "$1"
        echo "ERROR: linux-${KERNEL_VERSION}.tar.xz checksum mismatch (expected $KERNEL_SHA256, got $actual); removed it" >&2
        exit 1
    fi
}

# A download is checked before it takes the cache name, so only a verified file ever has it.
if [ -f "$TARBALL" ]; then
    echo "==> Using cached kernel source"
    verify_tarball "$TARBALL"
else
    echo "==> Downloading kernel source..."
    CDN_URL="https://cdn.kernel.org/pub/linux/kernel/v${KERNEL_MAJOR}.x/linux-${KERNEL_VERSION}.tar.xz"
    # No mirror to fall back to (#1128), so ride out a transient CDN failure instead.
    if ! curl -fSL --connect-timeout 10 --retry 3 --retry-all-errors "$CDN_URL" -o "$TARBALL.tmp"; then
        rm -f "$TARBALL.tmp"
        echo "ERROR: failed to download linux-${KERNEL_VERSION} from cdn.kernel.org" >&2
        exit 1
    fi
    verify_tarball "$TARBALL.tmp"
    mv "$TARBALL.tmp" "$TARBALL"
fi

BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT

echo "==> Extracting source..."
tar xf "$TARBALL" -C "$BUILD_DIR" --strip-components=1

cd "$BUILD_DIR"

# kconfig takes a single KCONFIG_ALLCONFIG file, so the fragments are
# concatenated here, in the order FRAGMENTS lists them.
CONFIG_FRAGMENT="$BUILD_DIR/.isx-merged.config"
cat "${FRAGMENTS[@]}" > "$CONFIG_FRAGMENT"

echo "==> Configuring (allnoconfig + $FRAGMENT_NAMES)..."
KCONFIG_ALLCONFIG="$CONFIG_FRAGMENT" make -s ARCH="$KARCH" allnoconfig
make -s ARCH="$KARCH" olddefconfig

echo "==> Validating config fragment..."
APPLIED=0
SKIPPED=0
SKIPPED_KEYS=()
while IFS= read -r line; do
    key="${line%%=*}"
    value="${line#*=}"
    if [ "$value" = "n" ]; then
        # =n is represented as "# CONFIG_X is not set" in .config
        if grep -q "^${key}=" .config 2>/dev/null; then
            actual=$(grep "^${key}=" .config)
            echo "  WARN: $key=n requested but .config has $actual"
            SKIPPED=$((SKIPPED + 1)); SKIPPED_KEYS+=("$key")
        else
            APPLIED=$((APPLIED + 1))
        fi
    elif [ "$value" = "y" ]; then
        if grep -q "^${key}=y" .config 2>/dev/null; then
            APPLIED=$((APPLIED + 1))
        else
            echo "  WARN: $key=y not in .config (arch-specific or unmet dependency)"
            SKIPPED=$((SKIPPED + 1)); SKIPPED_KEYS+=("$key")
        fi
    else
        # Numeric or string value
        actual=$(grep "^${key}=" .config 2>/dev/null || true)
        if [ "$actual" = "$line" ]; then
            APPLIED=$((APPLIED + 1))
        elif [ -n "$actual" ]; then
            echo "  WARN: expected $line but got $actual"
            SKIPPED=$((SKIPPED + 1)); SKIPPED_KEYS+=("$key")
        else
            echo "  WARN: $line not in .config"
            SKIPPED=$((SKIPPED + 1)); SKIPPED_KEYS+=("$key")
        fi
    fi
# A key set twice is checked at its last value, the one kconfig applied.
done < <(grep '^CONFIG_' "$CONFIG_FRAGMENT" \
    | awk -F= '!($1 in last) { order[++n] = $1 } { last[$1] = $0 } END { for (i = 1; i <= n; i++) print last[order[i]] }')
echo "  $APPLIED applied, $SKIPPED skipped"
# The appliance's fragments tolerate an option its arch cannot take, but each
# vm option was added because a guest failed without it: one that no longer
# applies fails the build rather than the guest.
if [ "$ROLE" = vm ]; then
    for key in ${SKIPPED_KEYS[@]+"${SKIPPED_KEYS[@]}"}; do
        if grep -q "^${key}=" "$SCRIPT_DIR/vm.config" "$SCRIPT_DIR/vm-${BUILD_ARCH}.config"; then
            echo "ERROR: $key from a vm fragment did not apply" >&2
            exit 1
        fi
    done
fi

echo "==> Building ($TARGET)..."
make -j"$(nproc)" ARCH="$KARCH" "$TARGET"

if [ ! -f "$IMAGE" ]; then
    echo "ERROR: kernel image not found at $IMAGE" >&2
    exit 1
fi

cp "$IMAGE" "$OUTPUT_DIR/vmlinuz"
chmod 644 "$OUTPUT_DIR/vmlinuz"

# The vm kernel loads no modules, but software asks modprobe for drivers it
# needs, and modprobe answers for a built-in one only from the index of them:
# Incus will not run VMs until `modprobe vhost_vsock` succeeds, and a built-in
# vhost_vsock has no /sys/module entry to find instead. The image build installs
# these in /usr/lib/modules/<kernelrelease>/ and runs depmod there.
if [ "$ROLE" = vm ]; then
    cp modules.builtin modules.builtin.modinfo "$OUTPUT_DIR/"
    cp include/config/kernel.release "$OUTPUT_DIR/kernelrelease"
fi

echo
echo "Kernel built successfully:"
ls -lh "$OUTPUT_DIR/vmlinuz"
