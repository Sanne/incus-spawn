#!/bin/bash
# Build and install incus-spawn as 'isx'
set -e

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BINARY_NAME="isx"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ "$1" = "--native" ]; then
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

echo "Installed. Run 'isx' to get started."
