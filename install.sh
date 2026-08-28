#!/bin/bash
# Build and install incus-spawn as 'isx'
# -E so the ERR trap also fires for failures inside the helper functions.
set -eE

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BINARY_NAME="isx"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Say which step died. Without this a mid-install failure just stops, leaving
# some binaries updated and some not, with nothing naming the culprit.
trap 'echo "Error: install.sh failed at line $LINENO: $BASH_COMMAND" >&2' ERR

# Path of a staged (not yet renamed) file, so we don't litter $INSTALL_DIR
# with half-written copies when a build or copy dies partway.
STAGED=""
trap '[ -n "$STAGED" ] && rm -f "$STAGED"; :' EXIT

# Install a file by staging it beside the target and renaming over it.
#
# rename(2) is atomic and swaps the *directory entry*, not the inode, which
# matters twice over. A running isx/isx-proxy keeps executing the old image
# instead of making the write fail with ETXTBSY ("Text file busy") -- the
# systemd proxy service in particular is usually running during an upgrade.
# And bash reads a script lazily, so truncating a running wrapper or
# git-remote-isx in place can make it execute garbage mid-run. A failed or
# short copy also leaves the previous working version in place rather than a
# truncated stump, and there is never a window where the binary is missing.
atomic_install() {
    local src="$1" dest="$2"
    STAGED="$(mktemp "$dest.XXXXXX")"
    cp "$src" "$STAGED"
    chmod 755 "$STAGED"
    mv -f "$STAGED" "$dest"
    STAGED=""
}

# Same, for the generated JVM launcher scripts.
install_wrapper() {
    local dest="$1" jar="$2"
    STAGED="$(mktemp "$dest.XXXXXX")"
    cat > "$STAGED" <<WRAPPER
#!/bin/bash
exec "$JAVA_BIN" -jar "$jar" "\$@"
WRAPPER
    chmod 755 "$STAGED"
    mv -f "$STAGED" "$dest"
    STAGED=""
}

NATIVE=false
COMPLETIONS_SHELL=""
for arg in "$@"; do
    case "$arg" in
        --native) NATIVE=true ;;
        --completions=*) COMPLETIONS_SHELL="${arg#--completions=}" ;;
        --completions) echo "Error: --completions requires a value (bash, zsh, or fish)"; exit 1 ;;
    esac
done

if [ -n "$COMPLETIONS_SHELL" ]; then
    case "$COMPLETIONS_SHELL" in
        bash|zsh|fish) ;;
        *) echo "Error: unsupported shell '$COMPLETIONS_SHELL'. Use bash, zsh, or fish."; exit 1 ;;
    esac
fi

# Check we can install the result *before* spending minutes on a native build.
mkdir -p "$INSTALL_DIR" 2>/dev/null || true
if [ ! -d "$INSTALL_DIR" ] || [ ! -w "$INSTALL_DIR" ]; then
    echo "Error: $INSTALL_DIR is not a writable directory."
    echo "  Point INSTALL_DIR elsewhere, e.g. INSTALL_DIR=~/bin $0 $*"
    exit 1
fi

if $NATIVE; then
    echo "Building native image (this may take a minute)..."
    NATIVE_ARGS="-Dnative -DskipTests -q"
    if [ "$(uname -s)" = "Linux" ]; then
        # Detect container runtime in the same order Quarkus does (docker first)
        if [ -n "$CONTAINER_RUNTIME" ]; then
            CTR="$CONTAINER_RUNTIME"
        elif command -v docker >/dev/null 2>&1; then
            CTR=docker
        elif command -v podman >/dev/null 2>&1; then
            CTR=podman
        else
            echo "Error: docker or podman is required for native builds on Linux."
            exit 1
        fi
        GRAALVM_BASE="container-registry.oracle.com/graalvm/native-image:latest"
        # The tag is a local cache name, not a pull ref: the builder FROMs :latest.
        # Bumping it here (25.2 -> 25.3) invalidates the cached builder so the next
        # build re-pulls :latest and picks up the new GraalVM (25.3: Priority Inlining
        # default, compressed references, Adaptive2 serial GC, loop vectorization).
        BUILDER_TAG="incus-spawn-graalvm-builder:25.3"
        if ! $CTR image inspect "$BUILDER_TAG" >/dev/null 2>&1; then
            echo "Preparing GraalVM builder image (one-time)..."
            $CTR rmi "$GRAALVM_BASE" >/dev/null 2>&1 || true
            printf 'FROM %s\nWORKDIR /project\n' "$GRAALVM_BASE" | $CTR build -t "$BUILDER_TAG" -
        fi
        NATIVE_ARGS="$NATIVE_ARGS -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=$CTR -Dquarkus.native.builder-image=$BUILDER_TAG -Dquarkus.native.builder-image.pull=never"
    elif [ "$(uname -s)" = "Darwin" ]; then
        if [ -z "$GRAALVM_HOME" ] || [ ! -x "$GRAALVM_HOME/bin/native-image" ]; then
            GRAALVM_HOME=$(/usr/libexec/java_home -V 2>&1 | grep -i graal | awk '{print $NF}' | head -1)
        fi
        if [ -z "$GRAALVM_HOME" ] || [ ! -x "$GRAALVM_HOME/bin/native-image" ]; then
            echo "Error: GraalVM with native-image is required for native builds on macOS."
            echo "  Install with: brew install graalvm-jdk@25"
            echo "  Or set GRAALVM_HOME to a GraalVM installation."
            exit 1
        fi
        export JAVA_HOME="$GRAALVM_HOME"
        PLIST="$(cd "$SCRIPT_DIR" && pwd)/cli/src/main/resources/Info.plist"
        NATIVE_ARGS="$NATIVE_ARGS -Dmacos.info.plist=$PLIST"
    fi
    "$SCRIPT_DIR/mvnw" package $NATIVE_ARGS
    echo "Installing to ${INSTALL_DIR}/${BINARY_NAME}..."
    RUNNER=$(ls -t "$SCRIPT_DIR"/cli/target/incus-spawn-*-runner 2>/dev/null | head -1)
    if [ -z "$RUNNER" ] || [ ! -f "$RUNNER" ]; then
        echo "Error: no native runner found in cli/target/"
        exit 1
    fi
    atomic_install "$RUNNER" "$INSTALL_DIR/$BINARY_NAME"
    PROXY_RUNNER=$(ls -t "$SCRIPT_DIR"/proxy/target/incus-spawn-proxy-*-runner 2>/dev/null | head -1)
    if [ -n "$PROXY_RUNNER" ] && [ -f "$PROXY_RUNNER" ]; then
        atomic_install "$PROXY_RUNNER" "$INSTALL_DIR/isx-proxy"
    fi
else
    # Resolve the Java binary so the wrapper always uses the JDK it was built with,
    # even if a different version is the default at runtime.
    if [ -n "$JAVA_HOME" ]; then
        JAVA_BIN="$JAVA_HOME/bin/java"
    else
        JAVA_BIN="$(command -v java)"
    fi
    if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
        echo "Error: no Java binary found."
        echo "  Set JAVA_HOME to a Java 25+ installation, or build with --native to avoid the Java requirement."
        exit 1
    fi
    JAVA_VER=$("$JAVA_BIN" -version 2>&1 | grep -oE '"[^"]+"' | head -1 | tr -d '"')
    case "$JAVA_VER" in
        1.*) JAVA_MAJOR=$(echo "$JAVA_VER" | cut -d. -f2) ;;
        *)   JAVA_MAJOR=$(echo "$JAVA_VER" | cut -d. -f1) ;;
    esac
    if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 25 ] 2>/dev/null; then
        echo "Error: Java 25+ is required, but $JAVA_BIN reports version ${JAVA_MAJOR:-unknown}."
        echo "  Set JAVA_HOME to a Java 25+ installation, or build with --native to avoid the Java requirement."
        exit 1
    fi
    echo "Building JVM package..."
    "$SCRIPT_DIR/mvnw" package -DskipTests -q
    echo "Installing to ${INSTALL_DIR}/${BINARY_NAME}..."
    # Create a wrapper script that runs the quarkus app jar
    JARFILE=$(ls "$SCRIPT_DIR"/cli/target/quarkus-app/quarkus-run.jar 2>/dev/null)
    if [ -z "$JARFILE" ]; then
        echo "Error: quarkus-run.jar not found in cli/target/quarkus-app/"
        exit 1
    fi
    install_wrapper "$INSTALL_DIR/$BINARY_NAME" "$JARFILE"
    PROXY_JAR=$(ls "$SCRIPT_DIR"/proxy/target/quarkus-app/quarkus-run.jar 2>/dev/null)
    if [ -n "$PROXY_JAR" ]; then
        install_wrapper "$INSTALL_DIR/isx-proxy" "$PROXY_JAR"
    fi
fi

# ── Install shell completions (if requested) ──────────────────────────────
if [ -n "$COMPLETIONS_SHELL" ]; then
    case "$COMPLETIONS_SHELL" in
        zsh)
            COMP_DIR="$HOME/.zsh/completions"
            COMP_FILE="$COMP_DIR/_isx"
            ;;
        bash)
            COMP_DIR="$HOME/.local/share/bash-completion/completions"
            COMP_FILE="$COMP_DIR/isx"
            ;;
        fish)
            COMP_DIR="$HOME/.config/fish/completions"
            COMP_FILE="$COMP_DIR/isx.fish"
            ;;
    esac
    echo "Installing $COMPLETIONS_SHELL completions to $COMP_FILE..."
    mkdir -p "$COMP_DIR"
    # Generate to a staging file: a failure here must not abort the rest of the
    # install, nor leave a truncated completion script that breaks shell startup.
    STAGED="$(mktemp "$COMP_FILE.XXXXXX")"
    if "$INSTALL_DIR/$BINARY_NAME" completion "$COMPLETIONS_SHELL" > "$STAGED"; then
        chmod 644 "$STAGED"
        mv -f "$STAGED" "$COMP_FILE"
        STAGED=""
        echo "Completions installed. Restart your shell or source the file to activate."
    else
        rm -f "$STAGED"
        STAGED=""
        echo "Warning: could not generate $COMPLETIONS_SHELL completions; leaving $COMP_FILE alone." >&2
    fi
fi

# Install git remote helper shim for isx:// URLs
atomic_install "$SCRIPT_DIR/common/src/main/resources/git-remote-isx" "$INSTALL_DIR/git-remote-isx"

# ── Override a Homebrew installation if present ───────────────────────────
# The brew prefix bin (e.g. /opt/homebrew/bin) usually sorts ahead of
# $INSTALL_DIR on PATH, so a brew-managed isx would shadow the build we just
# installed. Point the brew location at our build via a symlink instead of a
# copy: one source of truth, self-describing, and uninstall can safely detect
# and remove only links that point back into $INSTALL_DIR.
if command -v brew >/dev/null 2>&1 \
    && BREW_BIN="$(brew --prefix)/bin" \
    && [ -x "$BREW_BIN/isx" ] && [ "$INSTALL_DIR" != "$BREW_BIN" ]; then
    echo "Homebrew installation detected at $BREW_BIN/isx"
    echo "Linking it to the locally built binary..."
    for f in "$BINARY_NAME" isx-proxy git-remote-isx; do
        if [ -e "$INSTALL_DIR/$f" ] && { [ -e "$BREW_BIN/$f" ] || [ -L "$BREW_BIN/$f" ]; }; then
            ln -sf "$INSTALL_DIR/$f" "$BREW_BIN/$f"
        fi
    done
fi

# Don't claim success for a binary that dies on first launch (a native image
# built against mismatched glibc, a wrapper pointing at a since-removed JDK).
# --version is self-contained and doesn't need a running Incus daemon.
if ! "$INSTALL_DIR/$BINARY_NAME" --version >/dev/null 2>&1; then
    echo "Error: $INSTALL_DIR/$BINARY_NAME was installed but '$BINARY_NAME --version' failed." >&2
    "$INSTALL_DIR/$BINARY_NAME" --version || true
    exit 1
fi

case ":$PATH:" in
    *":$INSTALL_DIR:"*) ;;
    *) echo "Note: $INSTALL_DIR is not on your PATH; add it to run '$BINARY_NAME' by name." ;;
esac

echo "Installed. Run 'isx' to get started."

# ── Post-upgrade: restart services if running ────────────────────────────
# A failed unit counts as "was meant to be running" -- e.g. a previous install
# that died partway and left the proxy stopped. `proxy install` is idempotent:
# it restarts an active service and installs a missing one. Never fatal, the
# binaries are already in place by this point.
if systemctl --user is-active --quiet incus-spawn-proxy 2>/dev/null \
    || systemctl --user is-failed --quiet incus-spawn-proxy 2>/dev/null; then
    "$INSTALL_DIR/$BINARY_NAME" proxy install \
        || echo "Warning: could not restart the proxy service; run '$BINARY_NAME proxy install' by hand." >&2
elif [ "$(uname -s)" = "Darwin" ] && launchctl print "gui/$(id -u)/dev.incusspawn.proxy" &>/dev/null; then
    echo "Updating macOS proxy service..."
    "$INSTALL_DIR/$BINARY_NAME" proxy install \
        || echo "Warning: could not restart the proxy service; run '$BINARY_NAME proxy install' by hand." >&2
fi
