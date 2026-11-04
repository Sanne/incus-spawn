#!/bin/bash
# Build and install incus-spawn as 'isx'
# -E so the ERR trap also fires for failures inside the helper functions.
set -eE
# An exported CDPATH would make a relative `cd` land in one of its entries (and print it), so
# SCRIPT_DIR and the Homebrew-prefix check could each resolve a different directory than meant.
unset CDPATH

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
BINARY_NAME="isx"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Say which step died. Without this a mid-install failure just stops, leaving
# some binaries updated and some not, with nothing naming the culprit.
trap 'echo "Error: install.sh failed at line $LINENO: $BASH_COMMAND" >&2' ERR

# Path of a staged (not yet renamed) file, so we don't litter $INSTALL_DIR
# with half-written copies when a build or copy dies partway.
STAGED=""
# install_wrapper's cache of jvm_options(): never inherited from the environment.
unset LAUNCHER_JVM_OPTIONS
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

# JVM options for the launchers. GraalVM's JIT sometimes computes a wrong AES-256
# key schedule in AESCrypt.makeSessionKey while warming up, and the TLS connection
# using it fails with bad_record_mac: an occasional 502 from isx-proxy (#940,
# #1018, oracle/graal#14599). Keeping that one method interpreted avoids it; the
# root pom's argLine does the same for tests. Only for a JDK whose JIT is Graal:
# C2 is not affected, and a stock JDK has no UseJVMCICompiler flag at all.
jvm_options() {
    if "$JAVA_BIN" -XX:+PrintFlagsFinal -version 2>/dev/null \
            | grep -Eq '^ *bool +UseJVMCICompiler += true'; then
        echo "-XX:CompileCommand=quiet -XX:CompileCommand=exclude,com.sun.crypto.provider.AESCrypt::makeSessionKey"
    fi
}

# Same, for the generated JVM launcher scripts.
install_wrapper() {
    local dest="$1" jar="$2"
    # Probed once per install, for the first launcher
    LAUNCHER_JVM_OPTIONS="${LAUNCHER_JVM_OPTIONS-$(jvm_options)}"
    STAGED="$(mktemp "$dest.XXXXXX")"
    cat > "$STAGED" <<WRAPPER
#!/bin/bash
exec "$JAVA_BIN" ${LAUNCHER_JVM_OPTIONS:+$LAUNCHER_JVM_OPTIONS }-jar "$jar" "\$@"
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

# The pom's macos.deployment.target is the minimum (#1086): a native build here links
# for it, so on an older Mac the binary would not even load.
if $NATIVE && [ "$(uname -s)" = "Darwin" ]; then
    MACOS_VERSION="$(sw_vers -productVersion)"
    MACOS_MIN="$(sed -n 's:.*<macos.deployment.target>\([0-9]*\)\..*:\1:p' "$SCRIPT_DIR/pom.xml")"
    # Without it the comparison below errors, and an erroring test is false: say so instead.
    if [ -z "$MACOS_MIN" ]; then
        echo "Error: could not read macos.deployment.target from $SCRIPT_DIR/pom.xml."
        exit 1
    fi
    if [ "${MACOS_VERSION%%.*}" -lt "$MACOS_MIN" ]; then
        echo "Error: isx needs macOS $MACOS_MIN or later; this Mac runs macOS $MACOS_VERSION."
        exit 1
    fi
fi

# A Homebrew prefix may serve every account on the machine, so an install there would hand every
# account's isx, KeepAlive proxy and Git helper this build (#1614, #1666). Refuse INSTALL_DIR
# anywhere in one. Each ancestor of its physical path is compared by inode (-ef), so a trailing
# slash, a symlink or another letter case on macOS cannot spell around it. A directory that does
# not exist yet is judged by its nearest existing parent, where mkdir -p will create it; a `..`
# would let mkdir -p climb out of what it created, so it is refused there.
refuse_homebrew_prefix() {
    local dir="$INSTALL_DIR" real answer brew_prefix="" prefix
    if [ ! -d "$dir" ]; then
        case "/$dir/" in
            */../*) echo "Error: INSTALL_DIR does not exist yet; name it without '..': $INSTALL_DIR"; exit 1 ;;
        esac
        while [ ! -d "$dir" ]; do dir="$(dirname "$dir")"; done
    fi
    if ! real="$(cd -P -- "$dir" 2>/dev/null && pwd -P)"; then
        echo "Error: cannot tell whether $INSTALL_DIR is in a Homebrew prefix: $dir is not accessible."
        exit 1
    fi
    dir="$real"
    # A broken brew (or one for another architecture) must not abort an install: its answer is
    # only one of the places checked below.
    if command -v brew >/dev/null 2>&1 && answer="$(brew --prefix 2>/dev/null)"; then
        brew_prefix="$answer"
    fi
    while :; do
        for prefix in "$brew_prefix" "$HOMEBREW_PREFIX" /opt/homebrew /usr/local /home/linuxbrew/.linuxbrew; do
            [ -n "$prefix" ] && ! [ "$prefix" -ef / ] || continue
            # Past brew's own answer, a location counts only where Homebrew is installed, not every /usr/local.
            [ "$prefix" = "$brew_prefix" ] || [ -x "$prefix/bin/brew" ] || continue
            if [ "$dir" -ef "$prefix" ]; then
                echo "Error: $INSTALL_DIR is in the Homebrew prefix $prefix, which install.sh never writes: other accounts may use it."
                echo "  Install to a directory of your own instead, e.g. INSTALL_DIR=~/.local/bin $0 $*"
                exit 1
            fi
        done
        [ "$dir" != / ] || return 0
        dir="$(dirname "$dir")"
    done
}

# Check we can install the result *before* spending minutes on a native build.
refuse_homebrew_prefix "$@"
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
        # The same Oracle GraalVM line as the Linux graalvm-version in .github/workflows/:
        # 25i4 is Oracle's floating tag of GraalVM 25.4 (Innovation 4), as version: '25.4' is in
        # setup-graalvm, so it follows each 25.4 patch. Not :latest, which is the 25.0 LTS line.
        # -ol10 (Oracle Linux 10, what :latest was) because the bare 25i4 is amd64 only.
        GRAALVM_BASE="container-registry.oracle.com/graalvm/native-image:25i4-ol10"
        # A local cache of the base with WORKDIR /project (Quarkus mounts the project there),
        # named after the base tag and labelled with the base it was built from: rebuilt
        # whenever a pull brings a newer base, used as is when the registry is unreachable.
        BUILDER_TAG="incus-spawn-graalvm-builder:${GRAALVM_BASE##*:}"
        $CTR pull -q "$GRAALVM_BASE" >/dev/null \
            || echo "Note: could not refresh $GRAALVM_BASE, building with what is cached locally."
        BASE_ID=$($CTR image inspect --format '{{.Id}}' "$GRAALVM_BASE" 2>/dev/null || true)
        BUILT_FROM=$($CTR image inspect --format '{{index .Config.Labels "dev.incusspawn.graalvm-base"}}' "$BUILDER_TAG" 2>/dev/null || true)
        [ "$BUILT_FROM" = "<no value>" ] && BUILT_FROM=""  # docker's answer for a missing label
        if [ -z "$BASE_ID$BUILT_FROM" ]; then
            echo "Error: cannot pull $GRAALVM_BASE to build the native image."
            exit 1
        fi
        OLD_IMAGES=""
        if [ -n "$BASE_ID" ] && [ "$BUILT_FROM" != "$BASE_ID" ]; then
            echo "Preparing GraalVM builder image..."
            OLD_BUILDER=$($CTR image inspect --format '{{.Id}}' "$BUILDER_TAG" 2>/dev/null || true)
            printf 'FROM %s\nWORKDIR /project\nLABEL dev.incusspawn.graalvm-base=%s\n' "$GRAALVM_BASE" "$BASE_ID" \
                | $CTR build -q -t "$BUILDER_TAG" - >/dev/null
            # The builder this one replaces and its base, about 1 GB each, are no longer used.
            OLD_IMAGES="$OLD_BUILDER $BUILT_FROM"
        fi
        # Nor is any other incus-spawn builder (a GraalVM bump changes the tag) with its base,
        # or :latest, which older install.sh built FROM. Never the base just built from.
        for OLD in $($CTR images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null \
                | grep -E '^(localhost/)?incus-spawn-graalvm-builder:' \
                | grep -vxF -e "$BUILDER_TAG" -e "localhost/$BUILDER_TAG" || true); do
            OLD_BASE=$($CTR image inspect --format '{{index .Config.Labels "dev.incusspawn.graalvm-base"}}' "$OLD" 2>/dev/null || true)
            [ "$OLD_BASE" = "<no value>" ] || [ "$OLD_BASE" = "$BASE_ID" ] && OLD_BASE=""
            OLD_IMAGES="$OLD_IMAGES $OLD $OLD_BASE"
        done
        for OLD in $OLD_IMAGES container-registry.oracle.com/graalvm/native-image:latest; do
            $CTR rmi "$OLD" >/dev/null 2>&1 || true
        done
        NATIVE_ARGS="$NATIVE_ARGS -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=$CTR -Dquarkus.native.builder-image=$BUILDER_TAG -Dquarkus.native.builder-image.pull=never"
    elif [ "$(uname -s)" = "Darwin" ]; then
        # A shell under Rosetta reports x86_64 on Apple Silicon too, so ask the hardware
        if [ "$(uname -m)" != "arm64" ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null)" != "1" ]; then
            # Quarkus 4 needs GraalVM 25.0.4, and Oracle GraalVM has no macOS x64 build after 25.0.1 (#1693)
            echo "Error: native builds are not supported on Intel Macs."
            echo "  isx needs GraalVM 25.0.4 or later, and Oracle GraalVM ships no Intel macOS build of it."
            echo "  isx is released for Apple Silicon Macs only; v0.4.2 and v0.5.0-dev.2 were the last with an Intel build."
            exit 1
        fi
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
    # Not optional: the CLI cannot serve the proxy in-process, so an isx without isx-proxy has no
    # working proxy at all — better to fail here than at `isx init`.
    PROXY_RUNNER=$(ls -t "$SCRIPT_DIR"/proxy/target/incus-spawn-proxy-*-runner 2>/dev/null | head -1)
    if [ -z "$PROXY_RUNNER" ] || [ ! -f "$PROXY_RUNNER" ]; then
        echo "Error: no native proxy runner found in proxy/target/"
        exit 1
    fi
    atomic_install "$PROXY_RUNNER" "$INSTALL_DIR/isx-proxy"
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
    JARFILE=$(ls "$SCRIPT_DIR"/cli/target/quarkus-app/quarkus-run.jar 2>/dev/null || true)
    if [ -z "$JARFILE" ]; then
        echo "Error: quarkus-run.jar not found in cli/target/quarkus-app/"
        exit 1
    fi
    install_wrapper "$INSTALL_DIR/$BINARY_NAME" "$JARFILE"
    PROXY_JAR=$(ls "$SCRIPT_DIR"/proxy/target/quarkus-app/quarkus-run.jar 2>/dev/null || true)
    if [ -z "$PROXY_JAR" ]; then
        echo "Error: quarkus-run.jar not found in proxy/target/quarkus-app/"
        exit 1
    fi
    install_wrapper "$INSTALL_DIR/isx-proxy" "$PROXY_JAR"
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

# ── Never write a Homebrew prefix ─────────────────────────────────────────
# A Homebrew prefix is machine state: /opt/homebrew serves every account on a
# Mac. Older versions of this script relinked its isx, isx-proxy and
# git-remote-isx to $INSTALL_DIR, so another user's KeepAlive proxy, CLI and
# Git helper then ran this user's build out of this user's home (#1614).
# Only those links of our own are undone: one to this install's binary, or into
# this user's home for an earlier INSTALL_DIR. Each is pointed back into the
# formula's keg as Homebrew links it, when Homebrew counts the keg as linked.
# Not `brew link`: it answers "Already linked" for such a keg without
# restoring anything, and --overwrite would also replace links we did not make.
# Any other link out of the prefix came from another user's install: it is not
# ours to undo, so it is only named. Shadowing is reported below.
# uninstall.sh's "Undo install.sh's Homebrew override" uses the same rule.
BREW_FORMULA="incus-spawn"   # formula name differs from the binary name (isx); as in uninstall.sh
# INSTALL_DIR is never in the prefix: refuse_homebrew_prefix stopped that above.
if command -v brew >/dev/null 2>&1 \
    && BREW_PREFIX="$(brew --prefix)" && BREW_BIN="$BREW_PREFIX/bin"; then
    # The linked keg, relative to the prefix's opt/ and so to its bin/ too.
    # The tap's incus-spawn-dev installs the same binaries, and the two conflict:
    # both may be installed with one unlinked, so the linked one is chosen.
    # One installed but unlinked gets no links back by hand: it may be unlinked on
    # purpose, or by a `brew upgrade` whose link step an old override made fail,
    # so the warning below names `brew link` and leaves the choice to the user.
    KEG=""
    for formula in incus-spawn incus-spawn-dev; do
        if [ -e "$BREW_PREFIX/var/homebrew/linked/$formula" ]; then
            BREW_FORMULA="$formula"
            KEG="$(readlink "$BREW_PREFIX/opt/$formula" || true)"
            break
        fi
    done
    # With none linked, the first one installed is the remedy a warning names.
    UNLINKED=""
    for formula in incus-spawn incus-spawn-dev; do
        if [ -L "$BREW_PREFIX/opt/$formula" ] || [ -e "$BREW_PREFIX/opt/$formula" ]; then
            [ -n "$KEG" ] || [ -n "$UNLINKED" ] || UNLINKED="$formula"
        fi
    done
    LINK_REMEDY=false
    # An empty or "/" HOME names nobody's home, so it makes no link our own.
    OWN_HOME="${HOME%/}"
    for f in "$BINARY_NAME" isx-proxy git-remote-isx; do
        target="$(readlink "$BREW_BIN/$f" 2>/dev/null || true)"
        # -ef: the link resolves to the file just installed, however either is spelled.
        if { [ -n "$target" ] && [ "$BREW_BIN/$f" -ef "$INSTALL_DIR/$f" ]; } \
            || { [ -n "$OWN_HOME" ] && [[ "$target" == "$OWN_HOME"/* && "$target" != "$BREW_PREFIX"/* ]]; }; then
            echo "Removing the Homebrew override an earlier install made: $BREW_BIN/$f"
            if ! rm -f "$BREW_BIN/$f"; then
                echo "Warning: could not remove $BREW_BIN/$f; every user of this prefix still runs $target." >&2
            elif [ -n "$KEG" ] && [ -e "$BREW_PREFIX/opt/$BREW_FORMULA/bin/$f" ]; then
                ln -s "$KEG/bin/$f" "$BREW_BIN/$f" \
                    || echo "Warning: could not restore $BREW_BIN/$f; run: brew unlink $BREW_FORMULA && brew link $BREW_FORMULA" >&2
            else
                echo "Warning: nothing replaces $BREW_BIN/$f: no linked Homebrew keg has it, so no user of this prefix has it there now." >&2
                if [ -n "$UNLINKED" ] && [ -e "$BREW_PREFIX/opt/$UNLINKED/bin/$f" ]; then
                    LINK_REMEDY=true
                fi
            fi
        # Homebrew's own links are relative, into its Cellar.
        elif [[ "$target" == /* && "$target" != "$BREW_PREFIX"/* ]]; then
            echo "Warning: $BREW_BIN/$f links to $target, outside Homebrew, so every user of this prefix runs that build." >&2
            if [ -n "$KEG" ]; then
                echo "  Whoever made it can remove the link, then run: brew unlink $BREW_FORMULA && brew link $BREW_FORMULA" >&2
            else
                echo "  Whoever made it can remove the link." >&2
            fi
        fi
    done
    if $LINK_REMEDY; then
        echo "  $UNLINKED is installed but not linked; to link it, run: brew link $UNLINKED" >&2
        echo "  It stops at any link outside Homebrew still in its way; 'brew link --overwrite $UNLINKED' replaces those too, another user's included." >&2
    fi
fi

# Don't claim success for a binary that dies on first launch (a native image
# built against mismatched glibc, a wrapper pointing at a since-removed JDK).
# --version is self-contained and doesn't need a running Incus daemon.
if ! "$INSTALL_DIR/$BINARY_NAME" --version >/dev/null 2>&1; then
    echo "Error: $INSTALL_DIR/$BINARY_NAME was installed but '$BINARY_NAME --version' failed." >&2
    "$INSTALL_DIR/$BINARY_NAME" --version || true
    exit 1
fi

# The proxy service below follows the same PATH lookup (`which isx`), so a
# shadowed build is not what the service runs either.
RESOLVED="$(command -v "$BINARY_NAME" || true)"
case ":$PATH:" in
    *":${INSTALL_DIR%/}:"*) ;;
    *) echo "Note: $INSTALL_DIR is not on your PATH; add it to run '$BINARY_NAME' by name." ;;
esac
# Compared as files (-ef), so a trailing slash or a symlinked PATH entry is
# not taken for another isx.
if [ -n "$RESOLVED" ] && ! [ "$RESOLVED" -ef "$INSTALL_DIR/$BINARY_NAME" ]; then
    echo "Note: '$BINARY_NAME' on your PATH is $RESOLVED, not this build; the proxy service follows the same one."
    echo "  To use this build, put $INSTALL_DIR ahead of ${RESOLVED%/*} on PATH, then run '$BINARY_NAME proxy install'."
    # Only when it is the formula's own isx: removing the formula does nothing to a foreign link.
    if [ -n "${BREW_PREFIX:-}" ] && [ "$RESOLVED" -ef "$BREW_PREFIX/opt/$BREW_FORMULA/bin/$BINARY_NAME" ]; then
        echo "  Or, if nobody else on this machine uses Homebrew's isx: brew uninstall $BREW_FORMULA"
    fi
fi

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

# On macOS, warn if the running VM has a stale appliance (never fatal). --quiet: an install
# says nothing about the appliance unless a restart would change it.
if [ "$(uname -s)" = "Darwin" ]; then
    "$INSTALL_DIR/$BINARY_NAME" vm check-version --quiet 2>/dev/null || true
fi
