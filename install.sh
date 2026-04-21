#!/bin/bash
# Build and install incus-spawn as 'isx'
set -e

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BINARY_NAME="isx"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

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

if $NATIVE; then
    echo "Building native image (this may take a minute)..."
    "$SCRIPT_DIR/mvnw" package -Dnative -Dquarkus.native.container-build=true -DskipTests -q
    echo "Installing to ${INSTALL_DIR}/${BINARY_NAME}..."
    mkdir -p "$INSTALL_DIR"
    RUNNER=$(ls -t "$SCRIPT_DIR"/target/incus-spawn-*-runner 2>/dev/null | head -1)
    if [ -z "$RUNNER" ]; then
        echo "Error: no native runner found in target/"
        exit 1
    fi
    cp "$RUNNER" "$INSTALL_DIR/$BINARY_NAME"
    chmod +x "$INSTALL_DIR/$BINARY_NAME"
else
    echo "Building JVM package..."
    "$SCRIPT_DIR/mvnw" package -DskipTests -q
    echo "Installing to ${INSTALL_DIR}/${BINARY_NAME}..."
    mkdir -p "$INSTALL_DIR"
    # Create a wrapper script that runs the quarkus app jar
    JARFILE=$(ls "$SCRIPT_DIR"/target/quarkus-app/quarkus-run.jar 2>/dev/null)
    if [ -z "$JARFILE" ]; then
        echo "Error: quarkus-run.jar not found in target/quarkus-app/"
        exit 1
    fi
    cat > "$INSTALL_DIR/$BINARY_NAME" <<WRAPPER
#!/bin/bash
exec java -jar "$JARFILE" "\$@"
WRAPPER
    chmod +x "$INSTALL_DIR/$BINARY_NAME"
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
    "$INSTALL_DIR/$BINARY_NAME" completion "$COMPLETIONS_SHELL" > "$COMP_FILE"
    echo "Completions installed. Restart your shell or source the file to activate."
fi

echo "Installed. Run 'isx' to get started."
