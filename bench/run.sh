#!/bin/bash
# bench/run.sh — Benchmark native image: binary size, memory, proxy throughput
#
# Requires: Oracle GraalVM with native-image, working isx setup (isx init),
#           running Incus daemon, Podman (for Hyperfoil container)
#           On macOS: the VM running (isx vm start), the proxy service stopped,
#           and --hyperfoil=DIR, an unpacked Hyperfoil distribution (no Podman)
#           For --load=anthropic|vertex (Linux only): the tpl-isx-bench template,
#           built from bench/ with: isx build tpl-isx-bench --yes
#
# Usage:
#   bench/run.sh                    # full build + benchmark
#   bench/run.sh --skip-build       # reuse existing native binary
#   bench/run.sh --label "baseline" # tag results with a label
#   bench/run.sh --proxy=BIN --cli=BIN  # measure these binaries (an A/B of build flags)
#   bench/run.sh --collect=DIR --proxy=BIN --cli=BIN  # PGO profile collection (bench/pgo-collect.sh)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"

# A release pins it by digest (release.yml's profiles job)
HYPERFOIL_IMAGE="${HYPERFOIL_IMAGE:-quay.io/hyperfoil/hyperfoil:latest}"
HYPERFOIL_CONTAINER="isx-bench-hf"
HYPERFOIL_PORT=8090
BENCHMARK_YAML="$SCRIPT_DIR/proxy-health.hf.yaml"
LOAD_MODE="constant"

SKIP_BUILD=false
LABEL=""
GRAALVM_DIR=""
BUILDER_IMAGE=""
HYPERFOIL_DIR=""
PROXY_BIN=""
CLI_BIN=""
COLLECT_DIR=""
FROM="tpl-isx-bench"
MODEL_LOAD=false
CACHE_LOAD=false
WS_LOAD=false

IS_MACOS=false
[ "$(uname -s)" = Darwin ] && IS_MACOS=true

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=true ;;
        --label=*) LABEL="${1#--label=}" ;;
        --label) shift; LABEL="${1:-}" ;;
        --graalvm=*) GRAALVM_DIR="${1#--graalvm=}" ;;
        --graalvm) shift; GRAALVM_DIR="${1:-}" ;;
        --builder-image=*) BUILDER_IMAGE="${1#--builder-image=}" ;;
        --builder-image) shift; BUILDER_IMAGE="${1:-}" ;;
        --load=*) LOAD_MODE="${1#--load=}" ;;
        --load) shift; LOAD_MODE="${1:-}" ;;
        --hyperfoil=*) HYPERFOIL_DIR="${1#--hyperfoil=}" ;;
        --hyperfoil) shift; HYPERFOIL_DIR="${1:-}" ;;
        --proxy=*) PROXY_BIN="${1#--proxy=}" ;;
        --proxy) shift; PROXY_BIN="${1:-}" ;;
        --cli=*) CLI_BIN="${1#--cli=}" ;;
        --cli) shift; CLI_BIN="${1:-}" ;;
        --collect=*) COLLECT_DIR="${1#--collect=}" ;;
        --collect) shift; COLLECT_DIR="${1:-}" ;;
        --from=*) FROM="${1#--from=}" ;;
        --from) shift; FROM="${1:-}" ;;
        --help|-h)
            echo "Usage: bench/run.sh [--skip-build] [--label=NAME] [--load=MODE] [--graalvm=DIR|--builder-image=TAG] [--hyperfoil=DIR]"
            echo "                    [--proxy=BIN --cli=BIN] [--collect=DIR] [--from=TEMPLATE]"
            echo ""
            echo "Benchmarks the native image build of the MITM proxy."
            echo "Measures: binary size, startup time, memory (RSS), throughput, latency."
            echo ""
            echo "Options:"
            echo "  --skip-build    Reuse existing native binaries in cli/target and proxy/target"
            echo "  --label=NAME    Tag results with a label (e.g. 'baseline')"
            echo "  --load=MODE     constant (default): 5000 req/s at /health. ~3% of that"
            echo "                  endpoint's capacity — a regression tripwire only."
            echo "                  saturate: concurrency ladder against /health. Finds the"
            echo "                  HTTP server ceiling; no TLS, no body, no cache."
            echo "                  maven: fetches a real cached artifact over the intercepted"
            echo "                  HTTPS path. The workload containers actually generate —"
            echo "                  use this to compare builds or toolchains."
            echo "                  anthropic: streamed ~45 KiB Messages calls from inside an"
            echo "                  instance, the proxy injecting an API key (Linux only)."
            echo "                  vertex: the same calls, translated for Vertex AI (Linux only)."
            echo "                  Both answer from a local stub; neither needs a real account."
            echo "                  npm: a packument and a cached tarball per iteration, the npm path."
            echo "                  websocket: Codex-shaped WebSocket turns from inside an instance"
            echo "                  (WsLoad.java), the proxy injecting an OpenAI key (Linux only)."
            echo "  --graalvm=DIR   Build with this GraalVM instead of whatever is on PATH."
            echo "  --builder-image=TAG"
            echo "                  Build in this container image — the path Linux releases"
            echo "                  actually use (see install.sh). Preferred over --graalvm on"
            echo "                  Linux, since it reproduces the release toolchain exactly."
            echo ""
            echo "                  Either flag lets you A/B two toolchains on one host:"
            echo "                    bench/run.sh --builder-image=bench-graalvm-builder:25i4-ol10 --label=25.4"
            echo "                    bench/run.sh --builder-image=incus-spawn-graalvm-builder:25i5-ol10 --label=25.5"
            echo ""
            echo "  --hyperfoil=DIR Run Hyperfoil from this unpacked distribution, with the java on"
            echo "                  PATH, instead of in a Podman container. Required on macOS,"
            echo "                  where a container runs in Podman's VM and the load would"
            echo "                  cross its user-mode network before reaching the proxy."
            echo "  --proxy=BIN, --cli=BIN"
            echo "                  Measure these binaries instead of the ones in proxy/target and"
            echo "                  cli/target (no build): how the A/B of two builds' flags is run."
            echo "  --collect=DIR   PGO profile collection: BIN must be instrumented (--pgo-instrument,"
            echo "                  -H:+ParseRuntimeOptions). The proxy writes DIR/proxy-<load>.iprof.gz;"
            echo "                  no CLI timing, nothing measured is saved. Driven by bench/pgo-collect.sh."
            echo "  --from=TEMPLATE Template the model loads branch their instance from (default"
            echo "                  tpl-isx-bench: needs a JVM for the load generator)."
            echo ""
            echo "Requirements:"
            echo "  - Oracle GraalVM with native-image on PATH (or via --graalvm/--builder-image)"
            echo "  - Working isx setup (run 'isx init' first)"
            echo "  - Running Incus daemon"
            echo "  - Podman (for running Hyperfoil in a container), or --hyperfoil=DIR"
            echo "  - On macOS: the VM running and the proxy service stopped (isx proxy stop)"
            exit 0
            ;;
    esac
    shift
done

# ── Helpers ──────────────────────────────────────────────────────────────────

die() { echo "Error: $*" >&2; exit 1; }

# The load generator's instance, destroyed while its proxy still runs.
destroy_instance() {
    [ -n "${HF_INSTANCE:-}" ] || return 0
    cli destroy "$HF_INSTANCE" --skip-confirmation >/dev/null 2>&1 \
        || echo "Warning: could not destroy $HF_INSTANCE; remove it with: isx destroy $HF_INSTANCE" >&2
    HF_INSTANCE=""
}

cleanup() {
    echo ""
    echo "Cleaning up..."
    destroy_instance
    [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null && wait "$PROXY_PID" 2>/dev/null || true
    [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null && wait "$STUB_PID" 2>/dev/null || true
    [ -n "${STUB_DIR:-}" ] && rm -rf "$STUB_DIR" || true
    # The stub's payload was cached under a coordinate that exists nowhere else; drop it
    [ -n "${BENCH_CACHE_DIR:-}" ] && rm -rf "$BENCH_CACHE_DIR" || true
    stop_hyperfoil
    [ -n "${TRUSTSTORE_DIR:-}" ] && rm -rf "$TRUSTSTORE_DIR" || true
    [ -n "${HF_WORK_DIR:-}" ] && rm -rf "$HF_WORK_DIR" || true
    [ -n "${WORK_DIR:-}" ] && rm -rf "$WORK_DIR" || true
}

stop_hyperfoil() {
    if $MODEL_LOAD; then
        return  # it ran in the instance, and goes with it
    elif [ -n "$HYPERFOIL_DIR" ]; then
        if [ -n "${HF_PID:-}" ]; then
            # standalone.sh does not exec java, so the JVM is the launcher's child. Wait for
            # it to go: a run started straight after would find its port still taken.
            local jvm
            jvm=$(pgrep -P "$HF_PID" 2>/dev/null | head -1) || true
            pkill -P "$HF_PID" 2>/dev/null || true
            kill "$HF_PID" 2>/dev/null && wait "$HF_PID" 2>/dev/null || true
            for _ in $(seq 1 40); do
                [ -n "$jvm" ] && kill -0 "$jvm" 2>/dev/null || break
                sleep 0.25
            done
        fi
        HF_PID=""
    else
        podman stop "$HYPERFOIL_CONTAINER" 2>/dev/null && podman rm "$HYPERFOIL_CONTAINER" 2>/dev/null || true
    fi
}
trap cleanup EXIT

get_rss_kb() {
    local pid=$1
    if $IS_MACOS; then
        # No /proc; ps reports the resident set in KiB
        ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ' || echo "0"
    else
        awk '/^VmRSS:/ { print $2 }' "/proc/$pid/status" 2>/dev/null || echo "0"
    fi
}

epoch_ms() {
    if $IS_MACOS; then
        # BSD date has no %N
        perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'
    else
        date +%s%3N
    fi
}

file_size() {
    # stat's flags differ between GNU and BSD; wc does not
    wc -c < "$1" | tr -d ' '
}

# The host's address on the VM-facing bridge, found as VmNetwork.discoverHostBridgeIp
# finds it: the interface on the /24 that holds the VM's DHCP lease.
macos_bridge_ip() {
    local vm_ip
    vm_ip=$(awk -F= '
        /^[[:space:]]*ip_address=/ { ip = $2 }
        /^[[:space:]]*hw_address=/ { if (tolower($2) ~ /,4a:53:58:0?0:0?0:0?1$/) { print ip; exit } }
    ' /var/db/dhcpd_leases 2>/dev/null)
    [ -n "$vm_ip" ] || return 0
    ifconfig | awk -v prefix="${vm_ip%.*}." '$1 == "inet" && index($2, prefix) == 1 { print $2; exit }'
}

# ── 1. Validate environment ─────────────────────────────────────────────────

echo "=== Benchmark: native image proxy ==="
echo ""

# The two toolchain flags are mutually exclusive: a container build ignores the
# local GRAALVM_HOME entirely, so accepting both would silently measure the
# builder image while the run is labelled with the --graalvm one. That is the
# exact class of mislabelled A/B this harness exists to prevent.
if [ -n "$GRAALVM_DIR" ] && [ -n "$BUILDER_IMAGE" ]; then
    die "--graalvm and --builder-image are mutually exclusive: a container build ignores the local GraalVM, so the result would not reflect --graalvm. Pick one."
fi

# Pin a toolchain if asked. Quarkus picks native-image up from GRAALVM_HOME/JAVA_HOME
# or PATH, so set all three: that is what makes a 25.2-vs-25.3 A/B on one host possible.
if [ -n "$GRAALVM_DIR" ]; then
    GRAALVM_DIR="${GRAALVM_DIR/#\~/$HOME}"
    [ -x "$GRAALVM_DIR/bin/native-image" ] || die "No native-image at $GRAALVM_DIR/bin/native-image"
    export GRAALVM_HOME="$GRAALVM_DIR"
    export JAVA_HOME="$GRAALVM_DIR"
    export PATH="$GRAALVM_DIR/bin:$PATH"
fi

MVN_TOOLCHAIN_ARGS=()
if [ -n "$BUILDER_IMAGE" ]; then
    podman image exists "$BUILDER_IMAGE" 2>/dev/null \
        || die "Builder image '$BUILDER_IMAGE' not found locally. Build or pull it first."
    MVN_TOOLCHAIN_ARGS=(
        -Dquarkus.native.container-build=true
        -Dquarkus.native.container-runtime=podman
        -Dquarkus.native.builder-image="$BUILDER_IMAGE"
        -Dquarkus.native.builder-image.pull=never
    )
fi

# Check native-image
if [ -n "$BUILDER_IMAGE" ]; then
    GRAALVM_VERSION="$(podman run --rm "$BUILDER_IMAGE" native-image --version 2>&1 | head -1) (in $BUILDER_IMAGE)"
    echo "GraalVM:  $GRAALVM_VERSION"
elif command -v native-image &>/dev/null; then
    GRAALVM_VERSION="$(native-image --version 2>&1 | head -1)"
    GRAALVM_FULL="$(native-image --version 2>&1)"
    if ! echo "$GRAALVM_FULL" | grep -qi "oracle"; then
        echo "Warning: native-image does not appear to be Oracle GraalVM."
        echo "  Detected: $GRAALVM_VERSION"
        echo "  Release builds use Oracle GraalVM. Results may not be comparable."
        echo ""
    fi
    echo "GraalVM:  $GRAALVM_VERSION"
elif [ "$SKIP_BUILD" = true ]; then
    GRAALVM_VERSION="unknown (native-image not on PATH)"
    echo "GraalVM:  $GRAALVM_VERSION (skip-build mode)"
else
    die "native-image not found on PATH. Install Oracle GraalVM and ensure native-image is available."
fi

# Check the load generator
if [ -n "$HYPERFOIL_DIR" ]; then
    HYPERFOIL_DIR="${HYPERFOIL_DIR/#\~/$HOME}"
    [ -x "$HYPERFOIL_DIR/bin/standalone.sh" ] || die "No Hyperfoil distribution at $HYPERFOIL_DIR (bin/standalone.sh missing). Unpack a release zip from https://github.com/Hyperfoil/Hyperfoil/releases."
    java -version &>/dev/null || die "java not found on PATH; --hyperfoil runs Hyperfoil with it. Pass --graalvm=DIR or put a JDK on PATH."
elif $IS_MACOS; then
    die "On macOS pass --hyperfoil=DIR (an unpacked Hyperfoil distribution). A Podman container there runs in a VM, so the load would cross Podman's user-mode network and the run would measure that instead of the proxy."
elif ! command -v podman &>/dev/null; then
    die "podman not found on PATH. Hyperfoil runs inside a Podman container to work around non-contiguous CPU numbering in /proc/stat."
fi

command -v python3 &>/dev/null || die "python3 not found on PATH; it drives the Hyperfoil REST API and parses results."

# Check isx setup
ISX_CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/incus-spawn"
if [ ! -f "$ISX_CONFIG_DIR/config.yaml" ]; then
    die "isx not initialized ($ISX_CONFIG_DIR/config.yaml missing). Run 'isx init' first."
fi
if [ ! -f "$ISX_CONFIG_DIR/ca.key" ]; then
    die "isx CA not initialized ($ISX_CONFIG_DIR/ca.key missing). Run 'isx init' first."
fi

# Select the load profile
case "$LOAD_MODE" in
    constant) BENCHMARK_YAML="$SCRIPT_DIR/proxy-health.hf.yaml" ;;
    saturate) BENCHMARK_YAML="$SCRIPT_DIR/proxy-saturate.hf.yaml" ;;
    maven)    BENCHMARK_YAML="$SCRIPT_DIR/proxy-maven.hf.yaml"; CACHE_LOAD=true ;;
    npm)      BENCHMARK_YAML="$SCRIPT_DIR/proxy-npm.hf.yaml"; CACHE_LOAD=true ;;
    anthropic|vertex) BENCHMARK_YAML="$SCRIPT_DIR/proxy-messages.hf.yaml"; MODEL_LOAD=true ;;
    # Hyperfoil speaks no WebSocket: WsLoad.java drives this one, from inside the instance
    websocket) BENCHMARK_YAML="$SCRIPT_DIR/proxy-websocket.yaml"; MODEL_LOAD=true; WS_LOAD=true ;;
    *) die "Unknown --load mode '$LOAD_MODE' (expected: constant, saturate, maven, npm, anthropic, vertex, websocket)" ;;
esac

COLLECTING=false
if [ -n "$COLLECT_DIR" ]; then
    COLLECTING=true
    [ -n "$PROXY_BIN" ] || die "--collect needs --proxy=BIN, an instrumented proxy build"
    mkdir -p "$COLLECT_DIR" || die "Cannot create $COLLECT_DIR"
    COLLECT_DIR="$(cd "$COLLECT_DIR" && pwd)"
fi

# A collection's weighting is the mix of its runs, so a load that would outweigh the others in a
# profile collects from a shorter definition of its own beside it (<name>.collect.hf.yaml):
# full-length npm and WebSocket runs made the Vertex path 12% slower (PERFORMANCE-NOTES.md).
if $COLLECTING && [ -f "${BENCHMARK_YAML%.hf.yaml}.collect.hf.yaml" ]; then
    BENCHMARK_YAML="${BENCHMARK_YAML%.hf.yaml}.collect.hf.yaml"
fi

# Check benchmark definition
if [ ! -f "$BENCHMARK_YAML" ]; then
    die "Benchmark definition not found at $BENCHMARK_YAML"
fi

# The name in the YAML is what the REST API addresses the benchmark by, so read
# it rather than hardcoding — otherwise adding a profile silently starts the wrong one.
BENCHMARK_NAME=$(awk '/^name:/ { print $2; exit }' "$BENCHMARK_YAML")
[ -n "$BENCHMARK_NAME" ] || die "No 'name:' in $BENCHMARK_YAML"
echo "Load:     $LOAD_MODE ($BENCHMARK_NAME)"

if $CACHE_LOAD; then
    # e.g. "host: https://repo1.maven.org:18443" -> repo1.maven.org
    CACHE_HOST=$(awk '/^  host:/ { print $2; exit }' "$BENCHMARK_YAML" | sed -E 's#^https?://##; s#:[0-9]+$##')
    CACHE_PORT=$(awk '/^  host:/ { print $2; exit }' "$BENCHMARK_YAML" | sed -nE 's#.*:([0-9]+)$#\1#p')
    # The last GET is the cached artifact (npm's packument, before it, is relayed every time)
    CACHE_PATH=$(awk '/GET:/ { p = $2 } END { print p }' "$BENCHMARK_YAML")
    [ -n "$CACHE_HOST" ] && [ -n "$CACHE_PATH" ] || die "Could not parse host/GET path from $BENCHMARK_YAML"
    CACHE_URL="https://$CACHE_HOST:$CACHE_PORT$CACHE_PATH"
    echo "Artifact: $CACHE_URL (served by a local stub)"
    command -v java &>/dev/null || die "java not found; needed to run the upstream stub for --load=$LOAD_MODE"
    JAVA_MAJOR=$(java -XshowSettings:properties -version 2>&1 | sed -nE 's/^ *java\.specification\.version = ([0-9]+).*/\1/p')
    [ "${JAVA_MAJOR:-0}" -ge 21 ] || die "java ${JAVA_MAJOR:-unknown} found; the upstream stub for --load=$LOAD_MODE needs JDK 21+"
    command -v keytool &>/dev/null || die "keytool not found; needed for --load=$LOAD_MODE"
fi

if $MODEL_LOAD; then
    # The proxy injects credentials only for a caller it knows by address and proof, so the
    # load has to come from an instance: Hyperfoil runs inside one, and is driven over `incus exec`.
    $IS_MACOS && die "--load=$LOAD_MODE drives a load generator inside an instance through the incus client; it runs on Linux only."
    command -v incus &>/dev/null || die "incus client not found; --load=$LOAD_MODE drives its load generator through it"
    incus info "$FROM" &>/dev/null || die "No template $FROM to branch the load generator's instance from. Build it from bench/:
  (cd bench && isx build tpl-isx-bench --yes)"
    command -v java &>/dev/null || die "java not found; needed to run the upstream stub for --load=$LOAD_MODE"
    command -v keytool &>/dev/null || die "keytool not found; needed for --load=$LOAD_MODE"
    if $WS_LOAD; then
        MODEL_HOSTS=(api.openai.com)
        # The same rule as the collect definitions above: a collection runs collect-phases
        WS_PHASES=$(awk -v key="$($COLLECTING && echo collect-phases: || echo phases:)" '$1 == key { print $2; exit }' "$BENCHMARK_YAML")
        [ -n "$WS_PHASES" ] || die "No 'phases:' in $BENCHMARK_YAML"
    else
        MODEL_HOSTS=(api.anthropic.com us-east5-aiplatform.googleapis.com)
    fi
    echo "Upstream: ${MODEL_HOSTS[*]} (served by a local stub)"
fi

# Resolve the address the proxy listens on: the Incus bridge gateway, or on macOS
# (where Incus runs in the VM) the host's address on the VM-facing bridge
if $IS_MACOS; then
    GATEWAY_IP=$(macos_bridge_ip) || true
    [ -n "$GATEWAY_IP" ] || die "Could not determine the VM bridge address. Is the VM running? (isx vm start)"
    # There the health endpoint is on loopback (ProxyHealthCheck.healthAddress); only
    # the MITM port is on the bridge
    HEALTH_ADDR=127.0.0.1
else
    GATEWAY_IP=$(incus network get incusbr0 ipv4.address 2>/dev/null | cut -d/ -f1) || true
    if [ -z "$GATEWAY_IP" ]; then
        die "Could not determine Incus bridge gateway IP. Is Incus running?"
    fi
    HEALTH_ADDR="$GATEWAY_IP"
fi
echo "Gateway:  $GATEWAY_IP"

# A proxy already bound to the health port is the worst failure mode here: the
# freshly built one loses the bind and exits, but /health still answers — from
# the *old* process — so the run would report its startup, RSS and throughput.
if curl -sf --max-time 2 "http://$HEALTH_ADDR:18080/health" &>/dev/null; then
    if $IS_MACOS; then
        die "Something is already serving $HEALTH_ADDR:18080 — results would come from that process, not the build under test.
  Stop it first:  isx proxy stop
  Restart after:  isx proxy start"
    fi
    die "Something is already serving $HEALTH_ADDR:18080 — results would come from that process, not the build under test.
  Stop it first:  systemctl --user stop incus-spawn-proxy
  Restart after:  systemctl --user start incus-spawn-proxy"
fi

GIT_SHA="$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
GIT_SUBJECT="$(git -C "$PROJECT_DIR" log -1 --format=%s 2>/dev/null || echo "")"
echo "Git:      $GIT_SHA $GIT_SUBJECT"
echo ""

# ── 2. Build native image ───────────────────────────────────────────────────

resolve_runner() {
    # shellcheck disable=SC2012  # -t ordering is the point; names have no spaces
    ls -t $1 2>/dev/null | head -1 || true
}

CLI_RUNNER=$(resolve_runner "$PROJECT_DIR/cli/target/incus-spawn-*-runner")
PROXY_RUNNER=$(resolve_runner "$PROJECT_DIR/proxy/target/incus-spawn-proxy-*-runner")

if [ -n "$PROXY_BIN" ] || [ -n "$CLI_BIN" ]; then
    # Binaries built elsewhere, e.g. the two sides of an A/B of build flags: never rebuilt.
    PROXY_RUNNER="${PROXY_BIN:-$PROXY_RUNNER}"
    CLI_RUNNER="${CLI_BIN:-$CLI_RUNNER}"
    [ -x "$PROXY_RUNNER" ] || die "No executable proxy binary at '${PROXY_RUNNER}'"
    [ -x "$CLI_RUNNER" ] || die "No executable CLI binary at '${CLI_RUNNER}'"
    echo "Using the given binaries, no build."
elif $SKIP_BUILD; then
    [ -n "$PROXY_RUNNER" ] || die "No native proxy binary in proxy/target/. Run without --skip-build first."
    [ -n "$CLI_RUNNER" ] || die "No native CLI binary in cli/target/. Run without --skip-build first."
    echo "Skipping build, using existing binaries."
else
    echo "Building native images (this takes a few minutes)..."
    BUILD_START=$(epoch_ms)
    "$PROJECT_DIR/mvnw" -f "$PROJECT_DIR/pom.xml" package -Dnative -DskipTests -q "${MVN_TOOLCHAIN_ARGS[@]}"
    BUILD_END=$(epoch_ms)
    BUILD_TIME_MS=$((BUILD_END - BUILD_START))
    CLI_RUNNER=$(resolve_runner "$PROJECT_DIR/cli/target/incus-spawn-*-runner")
    PROXY_RUNNER=$(resolve_runner "$PROJECT_DIR/proxy/target/incus-spawn-proxy-*-runner")
    [ -z "$CLI_RUNNER" ] && die "Native build succeeded but no runner binary found in cli/target/"
    [ -z "$PROXY_RUNNER" ] && die "Native build succeeded but no runner binary found in proxy/target/"
    echo "Build completed in $((BUILD_TIME_MS / 1000))s"
fi
echo "  proxy: $PROXY_RUNNER"
echo "  cli:   $CLI_RUNNER"

# The binaries bake org.graalvm.version in, so they report the toolchain that
# actually produced them — the only trustworthy label for a 25.2-vs-25.3 run.
# An instrumented binary writes a profile at every exit; the ones from these setup runs are
# no workload's, so they go to a scratch directory and are dropped.
WORK_DIR="$(mktemp -d)"
setup_run() {
    local bin=$1
    shift
    if $COLLECTING; then
        "$bin" -XX:ProfilesDumpFile="$WORK_DIR/setup-$RANDOM$RANDOM.iprof" "$@"
    else
        "$bin" "$@"
    fi
}
cli() { setup_run "$CLI_RUNNER" "$@"; }
PROXY_BUILT_WITH=$(setup_run "$PROXY_RUNNER" --version 2>/dev/null | tail -1 || true)
CLI_BUILT_WITH=$(cli --version 2>/dev/null | tail -1 || true)
echo "  built with: ${PROXY_BUILT_WITH:-unknown}"
echo ""

# ── 3. Binary size and CLI startup ──────────────────────────────────────────

BINARY_SIZE=$(file_size "$PROXY_RUNNER")
BINARY_SIZE_MB=$(awk "BEGIN { printf \"%.1f\", $BINARY_SIZE / 1048576 }")
CLI_BINARY_SIZE=$(file_size "$CLI_RUNNER")
CLI_BINARY_SIZE_MB=$(awk "BEGIN { printf \"%.1f\", $CLI_BINARY_SIZE / 1048576 }")
echo "Proxy binary: $BINARY_SIZE_MB MB ($BINARY_SIZE bytes)"
echo "CLI binary:   $CLI_BINARY_SIZE_MB MB ($CLI_BINARY_SIZE bytes)"

# The CLI is short-lived and startup-bound, so its cost is process launch, not
# throughput. Take the median of 20 `--help` runs (--help touches no daemon).
cli_startup_samples() {
    if $IS_MACOS; then
        # BSD date has no %N, and a clock process per sample would cost as much as the
        # launch being timed, so one perl takes all 20 samples around fork+exec+wait.
        perl -MTime::HiRes=time -e '
            for (1..20) {
                my $s = time;
                my $pid = fork;
                if (!$pid) { open STDOUT, ">", "/dev/null"; open STDERR, ">", "/dev/null"; exec $ARGV[0], "--help"; exit 127 }
                waitpid $pid, 0;
                printf "%d\n", (time - $s) * 1e6;
            }' "$CLI_RUNNER"
    else
        for _ in $(seq 1 20); do
            s=$(date +%s%N)
            "$CLI_RUNNER" --help >/dev/null 2>&1 || true
            e=$(date +%s%N)
            echo $(( (e - s) / 1000 ))
        done
    fi
}
if $COLLECTING; then
    # Twenty --help runs would weight the profile towards --help; bench/cli.sh runs it once.
    CLI_STARTUP_US=0
else
    CLI_SAMPLES=$(cli_startup_samples | sort -n | tr '\n' ' ')
    CLI_STARTUP_US=$(echo "$CLI_SAMPLES" | awk '{ print $10 }')
    echo "CLI startup:  ${CLI_STARTUP_US} us (median of 20)"
fi

# ── 4. Start proxy and measure startup time ─────────────────────────────────

PROXY_ENV=()
if $CACHE_LOAD || $MODEL_LOAD; then
    # The proxy confirms cache hits with upstream (a HEAD for Maven Central) once their
    # artifact-cache: tiers stop trusting them, so pointed at the real repository this
    # run could send its load there and measure that repository's latency. A local stub
    # stands in for it instead: any HEAD the proxy makes goes, over TLS it verifies, to
    # a peer that answers at once. With the default tiers the warmed payload is fresh,
    # so hits make none (see bench/README.md).
    # The payload lives at a coordinate no real repository has, so the cached copy
    # can never stand in for a real artifact; cleanup() removes it anyway.
    STUB_DIR="$(mktemp -d)"
    if $MODEL_LOAD; then
        # The model loads need no artifact: the stub answers their POSTs instead.
        STUB_HOSTS=("${MODEL_HOSTS[@]}")
        CACHE_PATH=/isx-bench-no-artifact
    else
        STUB_HOSTS=("$CACHE_HOST")
    fi
    # The address too: a WebSocket dials an overridden host at the stub's address (MitmProxy)
    STUB_SAN="$(printf 'dns:%s,' "${STUB_HOSTS[@]}")ip:127.0.0.1"
    keytool -genkeypair -alias stub -keyalg EC -groupname secp256r1 \
        -dname "CN=${STUB_HOSTS[0]}" -ext "SAN=$STUB_SAN" -validity 2 \
        -keystore "$STUB_DIR/stub.p12" -storetype PKCS12 -storepass changeit &>/dev/null \
        || die "Failed to create the stub's certificate"
    keytool -exportcert -rfc -alias stub -keystore "$STUB_DIR/stub.p12" -storepass changeit \
        -file "$STUB_DIR/stub.pem" &>/dev/null || die "Failed to export the stub's certificate"
    # 642 KiB, the size of the commons-lang3 jar earlier runs fetched from Central
    head -c 657408 /dev/urandom > "$STUB_DIR/payload.jar"
    STUB_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
    # The credentials the proxy should inject: the stub refuses a call without them.
    STUB_API_KEY="isx-bench-api-key-$RANDOM$RANDOM"
    STUB_VERTEX_TOKEN="isx-bench-vertex-token-$RANDOM$RANDOM"
    STUB_WS_PORT=0
    $WS_LOAD && STUB_WS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
    java -Dstub.apiKey="$STUB_API_KEY" -Dstub.vertexToken="$STUB_VERTEX_TOKEN" \
        -Dstub.wsPort="$STUB_WS_PORT" -Dstub.openaiKey="$STUB_API_KEY" \
        "$SCRIPT_DIR/UpstreamStub.java" "$STUB_PORT" "$STUB_DIR/stub.p12" changeit \
        "$CACHE_PATH" "$STUB_DIR/payload.jar" > "$STUB_DIR/stub.log" 2>&1 &
    STUB_PID=$!
    for _ in $(seq 1 60); do
        grep -q '^ready' "$STUB_DIR/stub.log" 2>/dev/null && break
        kill -0 "$STUB_PID" 2>/dev/null || die "Upstream stub failed to start: $(cat "$STUB_DIR/stub.log")"
        sleep 0.5
    done
    grep -q '^ready' "$STUB_DIR/stub.log" || die "Upstream stub did not start within 30s"
    echo "Stub:        ${STUB_HOSTS[*]} -> 127.0.0.1:$STUB_PORT"
    case "$LOAD_MODE" in
        maven) BENCH_CACHE_DIR="$HOME/.cache/incus-spawn/maven-verified/$CACHE_HOST$(dirname "$CACHE_PATH")" ;;
        npm) BENCH_CACHE_DIR="$HOME/.cache/incus-spawn/npm/${CACHE_PATH#/}"; BENCH_CACHE_DIR="${BENCH_CACHE_DIR%%/-/*}" ;;
    esac
    if $WS_LOAD; then
        # The WebSocket server is on a port of its own
        STUB_UPSTREAMS="api.openai.com=127.0.0.1:$STUB_WS_PORT,"
    else
        STUB_UPSTREAMS=$(printf "%s=127.0.0.1:$STUB_PORT," "${STUB_HOSTS[@]}")
    fi
    PROXY_ENV=(ISX_BENCH_UPSTREAM="${STUB_UPSTREAMS%,}"
               ISX_BENCH_UPSTREAM_CERT="$STUB_DIR/stub.pem")
fi

if $MODEL_LOAD; then
    # The proxy reads this instead of the user's config.yaml (ISX_BENCH_CONFIG), which is never
    # edited: one Claude account, the default, so the instance needs no pin.
    if [ "$LOAD_MODE" = vertex ]; then
        printf 'claude:\n  default: isx-bench\n  accounts:\n    isx-bench:\n      type: vertex\n      cloudMlRegion: us-east5\n      vertexProjectId: isx-bench\n' \
            > "$STUB_DIR/config.yaml"
    elif $WS_LOAD; then
        # Codex's credential (CodexSetup): an OpenAI key, sent as a bearer token
        printf 'openai:\n  default: isx-bench\n  accounts:\n    isx-bench:\n      apiKey: %s\n' \
            "$STUB_API_KEY" > "$STUB_DIR/config.yaml"
    else
        printf 'claude:\n  default: isx-bench\n  accounts:\n    isx-bench:\n      type: api-key\n      apiKey: %s\n' \
            "$STUB_API_KEY" > "$STUB_DIR/config.yaml"
    fi
    # The proxy gets its Vertex token from gcloud, so a stand-in on its PATH hands it the one
    # the stub expects.
    mkdir -p "$STUB_DIR/bin"
    printf '#!/bin/sh\necho %s\n' "$STUB_VERTEX_TOKEN" > "$STUB_DIR/bin/gcloud"
    chmod +x "$STUB_DIR/bin/gcloud"
    PROXY_ENV+=(ISX_BENCH_CONFIG="$STUB_DIR/config.yaml" PATH="$STUB_DIR/bin:$PATH")
fi

PROXY_ARGS=(--gateway-ip "$GATEWAY_IP")
if $COLLECTING; then
    PROFILE="$COLLECT_DIR/proxy-$LOAD_MODE.iprof"
    rm -f "$PROFILE" "$PROFILE.gz"
    PROXY_ARGS=(-XX:ProfilesDumpFile="$PROFILE" "${PROXY_ARGS[@]}")
fi

echo "Starting proxy..."
PROXY_START=$(epoch_ms)
env ${PROXY_ENV[@]+"${PROXY_ENV[@]}"} "$PROXY_RUNNER" "${PROXY_ARGS[@]}" &>/dev/null &
PROXY_PID=$!

HEALTH_URL="http://$HEALTH_ADDR:18080/health"
STARTUP_OK=false
for i in $(seq 1 60); do
    if curl -sf "$HEALTH_URL" &>/dev/null; then
        STARTUP_OK=true
        break
    fi
    if ! kill -0 "$PROXY_PID" 2>/dev/null; then
        die "Proxy failed to start"
    fi
    sleep 0.25
done

if ! $STARTUP_OK; then
    die "Proxy health check failed after 15s"
fi

PROXY_READY=$(epoch_ms)
STARTUP_MS=$((PROXY_READY - PROXY_START))
echo "Startup:     ${STARTUP_MS}ms (PID $PROXY_PID)"

# ── 5. Idle RSS ─────────────────────────────────────────────────────────────

sleep 2
IDLE_RSS=$(get_rss_kb "$PROXY_PID")
echo "Idle RSS:    ${IDLE_RSS} KB"

ARTIFACT_BYTES=0
if $CACHE_LOAD; then
    # Prime the on-disk cache and prove the intercepted path works before handing
    # it to the load generator; a cold first request would otherwise be measured
    # as an upstream fetch and skew the whole run.
    WARM=$(curl -s -o /dev/null -w "%{http_code}:%{size_download}" \
        --resolve "$CACHE_HOST:$CACHE_PORT:$GATEWAY_IP" --cacert "$ISX_CONFIG_DIR/ca.crt" \
        "$CACHE_URL") || die "Artifact fetch through the proxy failed"
    [ "${WARM%%:*}" = "200" ] || die "Artifact fetch returned HTTP ${WARM%%:*} (expected 200)"
    ARTIFACT_BYTES="${WARM##*:}"
    echo "Cache warm:  $ARTIFACT_BYTES bytes"
    # npm's load also fetches the packument, so its requests are not all the artifact
    [ "$LOAD_MODE" = npm ] && ARTIFACT_BYTES=0
fi

ensure_hyperfoil_image() {
    if ! podman image exists "$HYPERFOIL_IMAGE" 2>/dev/null; then
        echo "Pulling Hyperfoil image..." >&2
        podman pull "$HYPERFOIL_IMAGE" >/dev/null 2>"$WORK_DIR/pull.log" \
            || die "Could not pull $HYPERFOIL_IMAGE: $( (grep -i 'error' "$WORK_DIR/pull.log" || cat "$WORK_DIR/pull.log") | tail -5)"
    fi
}

# The Hyperfoil distribution to run inside the instance: the one given, else the image's.
hyperfoil_distribution() {
    if [ -n "$HYPERFOIL_DIR" ]; then
        echo "$HYPERFOIL_DIR"
        return
    fi
    ensure_hyperfoil_image
    local cid
    cid=$(podman create "$HYPERFOIL_IMAGE") || die "Could not create a container from $HYPERFOIL_IMAGE"
    podman cp "$cid:/deployment" "$WORK_DIR/hyperfoil" >/dev/null || die "Could not copy Hyperfoil out of $HYPERFOIL_IMAGE"
    podman rm "$cid" >/dev/null
    echo "$WORK_DIR/hyperfoil"
}

HF_INSTANCE=""
if $MODEL_LOAD; then
    # Branched by the CLI under test, through this proxy: it starts the instance and hands it
    # its proofs, which the proxy checks before it injects anything. No terminal, so no shell.
    HF_INSTANCE="isx-bench-hf-$$"
    echo "Instance:    $HF_INSTANCE (branching from $FROM)"
    cli branch "$HF_INSTANCE" --from "$FROM" </dev/null >"$WORK_DIR/branch.log" 2>&1 \
        || die "Could not branch $HF_INSTANCE from $FROM: $(tail -5 "$WORK_DIR/branch.log")"
    if $WS_LOAD; then
        # Read as a login reads it: the profile replaces the build's placeholder with the proof.
        # shellcheck disable=SC2016  # expanded in the instance
        PROOF=$(incus exec "$HF_INSTANCE" -- sh -c \
            'OPENAI_API_KEY=sk-placeholder; . /run/isx/proof-tokens; printf %s "$OPENAI_API_KEY"')
        case "$PROOF" in sk-isx_*) ;; *) die "$HF_INSTANCE was given no OpenAI proof (/run/isx/proof-tokens)" ;; esac
        incus exec "$HF_INSTANCE" -- mkdir -p /opt/isx-bench
        incus file push "$SCRIPT_DIR/WsLoad.java" "$HF_INSTANCE/opt/isx-bench/WsLoad.java" >/dev/null \
            || die "Could not copy WsLoad.java into $HF_INSTANCE"
        ws_load() {
            incus exec "$HF_INSTANCE" -- java /opt/isx-bench/WsLoad.java wss://api.openai.com/v1/responses "$PROOF" "$1"
        }
        # Five seconds of one socket first (time for a cold JVM's first handshake), so a proxy
        # that does not inject (the stub refuses the handshake) fails here, not as a run of errors.
        CHECK=$(ws_load check=1x5) || die "WsLoad failed in $HF_INSTANCE"
        python3 -c 'import json, sys; s = json.load(sys.stdin)["statistics"][0]["summary"]; sys.exit(0 if s["extensions"]["http"]["status_2xx"] > 0 and s["requestCount"] == s["extensions"]["http"]["status_2xx"] else 1)' \
            <<<"$CHECK" || die "WebSocket turns through the proxy failed (the stub refuses a socket without the injected credential): $CHECK"
        echo "WebSocket:   turns answered, credential injected"
    fi
fi
if $MODEL_LOAD && ! $WS_LOAD; then
    # As above, for the Claude proof.
    # shellcheck disable=SC2016  # expanded in the instance
    PROOF=$(incus exec "$HF_INSTANCE" -- sh -c \
        'ANTHROPIC_API_KEY=sk-ant-placeholder; . /run/isx/proof-tokens; printf %s "$ANTHROPIC_API_KEY"')
    case "$PROOF" in sk-ant-isx_*) ;; *) die "$HF_INSTANCE was given no Claude proof (/run/isx/proof-tokens)" ;; esac
    HF_SOURCE_DIR=$(hyperfoil_distribution)
    python3 -I "$SCRIPT_DIR/messages-body.py" "$WORK_DIR/messages.json" || die "Could not write the request body"
    # The proof goes into the definition, not a template parameter: Hyperfoil uploads no
    # files with a template, and the body is one.
    sed "s|!param PROOF|$PROOF|" "$BENCHMARK_YAML" > "$WORK_DIR/load.hf.yaml"
    incus exec "$HF_INSTANCE" -- mkdir -p /opt/hyperfoil /opt/isx-bench
    tar -C "$HF_SOURCE_DIR" -cf - . | incus exec "$HF_INSTANCE" -- tar -C /opt/hyperfoil -xf - \
        || die "Could not copy Hyperfoil into $HF_INSTANCE"
    tar -C "$WORK_DIR" -cf - messages.json load.hf.yaml | incus exec "$HF_INSTANCE" -- tar -C /opt/isx-bench -xf - \
        || die "Could not copy the load into $HF_INSTANCE"
    # One call first, so a proxy that does not inject (the stub answers 401) fails here, not
    # as a run of errors.
    CALL=$(incus exec "$HF_INSTANCE" -- curl -s -o /dev/null -w "%{http_code}" https://api.anthropic.com/v1/messages \
        -H "x-api-key: $PROOF" -H "anthropic-version: 2023-06-01" -H "content-type: application/json" \
        --data-binary @/opt/isx-bench/messages.json) || true
    [ "$CALL" = 200 ] || die "A model call through the proxy returned HTTP ${CALL:-nothing} (expected 200: the stub refuses a call without the injected credential)"
    echo "Model call:  200, credential injected ($LOAD_MODE)"
fi

# The Hyperfoil controller's REST API: on this host, or inside the instance for the model loads.
hf_curl() {
    if [ -n "$HF_INSTANCE" ]; then
        incus exec "$HF_INSTANCE" -- curl "$@"
    else
        curl "$@"
    fi
}

# ── 6. Start Hyperfoil ──────────────────────────────────────────────────────

echo ""

if $WS_LOAD; then
    echo "Running WsLoad in the instance ($WS_PHASES)..."
    STATS=$(ws_load "$WS_PHASES") || die "WsLoad failed in $HF_INSTANCE"
    HYPERFOIL_SOURCE="WsLoad.java, in $FROM"
else

if [ -z "$HYPERFOIL_DIR" ] && [ -z "$HF_INSTANCE" ]; then
    ensure_hyperfoil_image

    # Remove any leftover container from a previous run
    podman rm -f "$HYPERFOIL_CONTAINER" 2>/dev/null || true
fi

HF_RUN_ARGS=(-d --name "$HYPERFOIL_CONTAINER" --network=host)
HF_JAVA_OPTS=""
BENCHMARK_UPLOAD="$BENCHMARK_YAML"

# The maven profile talks HTTPS to the proxy's MITM port as a real client would,
# so the generator needs (a) repo1.maven.org pointed at the gateway instead of
# the internet and (b) trust in the leaf cert the proxy mints, which is signed by
# our own CA. Without the truststore every request fails the handshake.
if $CACHE_LOAD; then
    TRUSTSTORE_DIR="$(mktemp -d)"
    TRUSTSTORE="$TRUSTSTORE_DIR/isx-truststore.p12"
    keytool -importcert -noprompt -trustcacerts -alias isx-ca \
        -file "$ISX_CONFIG_DIR/ca.crt" \
        -keystore "$TRUSTSTORE" -storetype PKCS12 -storepass changeit &>/dev/null \
        || die "Failed to build truststore from $ISX_CONFIG_DIR/ca.crt"
    HF_RUN_ARGS+=(
        --add-host "$CACHE_HOST:$GATEWAY_IP"
        -v "$TRUSTSTORE:/ca/truststore.p12:ro,Z"
        -e JAVA_OPTS="-Djavax.net.ssl.trustStore=/ca/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
    )
    HF_JAVA_OPTS="-Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
fi

echo "Starting Hyperfoil controller..."
if [ -n "$HF_INSTANCE" ]; then
    # The instance's Java trusts the isx CA through the system store, so no truststore.
    incus exec "$HF_INSTANCE" -- sh -c 'cd /opt/hyperfoil && nohup bin/standalone.sh </dev/null > /tmp/hyperfoil.log 2>&1 &'
elif [ -n "$HYPERFOIL_DIR" ]; then
    HF_WORK_DIR="$(mktemp -d)"
    if $CACHE_LOAD; then
        # No container, so no --add-host: the definition itself names the address to
        # connect to, and the host stays repo1.maven.org for SNI and the Host header.
        BENCHMARK_UPLOAD="$HF_WORK_DIR/$(basename "$BENCHMARK_YAML")"
        awk -v addr="$GATEWAY_IP:$CACHE_PORT" '
            { print }
            /^  host:/ { print "  addresses:"; print "  - " addr }
        ' "$BENCHMARK_YAML" > "$BENCHMARK_UPLOAD"
    fi
    curl -sf --max-time 2 "http://localhost:$HYPERFOIL_PORT/benchmark" &>/dev/null \
        && die "Something is already serving localhost:$HYPERFOIL_PORT; a Hyperfoil controller left running would take this run's load definition."
    JAVA_OPTS="$HF_JAVA_OPTS" "$HYPERFOIL_DIR/bin/standalone.sh" > "$HF_WORK_DIR/hyperfoil.log" 2>&1 &
    HF_PID=$!
else
    podman run "${HF_RUN_ARGS[@]}" "$HYPERFOIL_IMAGE" standalone >/dev/null 2>&1
fi

# Wait for controller to be ready
HF_READY=false
for i in $(seq 1 30); do
    if hf_curl -sf "http://localhost:$HYPERFOIL_PORT/benchmark" &>/dev/null; then
        HF_READY=true
        break
    fi
    sleep 1
done

hyperfoil_logs() {
    if [ -n "$HF_INSTANCE" ]; then
        incus exec "$HF_INSTANCE" -- cat /tmp/hyperfoil.log 2>/dev/null
    elif [ -n "$HYPERFOIL_DIR" ]; then
        cat "$HF_WORK_DIR/hyperfoil.log" 2>/dev/null
    else
        podman logs "$HYPERFOIL_CONTAINER" 2>&1
    fi
}

if ! $HF_READY; then
    echo "Hyperfoil logs:"
    hyperfoil_logs | tail -20
    die "Hyperfoil controller failed to start after 30s"
fi

# sed, not grep -oP: BSD grep has no PCRE
HYPERFOIL_VERSION=$(hyperfoil_logs | sed -nE 's/.*Hyperfoil: ([0-9.]+).*/\1/p' | head -1)
HYPERFOIL_SOURCE="$HYPERFOIL_IMAGE"
[ -n "$HYPERFOIL_DIR" ] && HYPERFOIL_SOURCE="$(basename "$HYPERFOIL_DIR")"
if [ -n "$HF_INSTANCE" ]; then
    echo "Hyperfoil:   $HYPERFOIL_VERSION ($HYPERFOIL_SOURCE, in the instance)"
    HYPERFOIL_SOURCE="$HYPERFOIL_SOURCE, in $FROM"
elif [ -n "$HYPERFOIL_DIR" ]; then
    echo "Hyperfoil:   $HYPERFOIL_VERSION ($HYPERFOIL_SOURCE, no container)"
else
    echo "Hyperfoil:   $HYPERFOIL_VERSION (container)"
fi

# ── 7. Run load test ────────────────────────────────────────────────────────

# Upload benchmark definition
if [ -n "$HF_INSTANCE" ]; then
    hf_curl -sf "http://localhost:$HYPERFOIL_PORT/benchmark" \
        -F "benchmark=@/opt/isx-bench/load.hf.yaml;type=text/vnd.yaml" \
        -F "messages.json=@/opt/isx-bench/messages.json;type=application/octet-stream" >/dev/null \
        || die "Hyperfoil refused the load definition"
else
    curl -sf -X POST "http://localhost:$HYPERFOIL_PORT/benchmark" \
        -H "Content-Type: text/vnd.yaml" \
        --data-binary "@$BENCHMARK_UPLOAD" >/dev/null
fi

TARGET_URL="http://$HEALTH_ADDR:18080"

# Start the benchmark run
if grep -q '!param TARGET' "$BENCHMARK_YAML"; then
    START_URL="http://localhost:$HYPERFOIL_PORT/benchmark/$BENCHMARK_NAME/start?templateParam=TARGET=$TARGET_URL"
else
    START_URL="http://localhost:$HYPERFOIL_PORT/benchmark/$BENCHMARK_NAME/start"
fi
RESPONSE=$(hf_curl -sf "$START_URL")
RUN_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Run started: $RUN_ID"

# Poll for completion
echo "Running benchmark (the phases of $(basename "$BENCHMARK_YAML"))..."
BENCH_OK=false
for i in $(seq 1 90); do
    COMPLETED=$(hf_curl -sf "http://localhost:$HYPERFOIL_PORT/run/$RUN_ID" | \
        python3 -c "import sys,json; print(json.load(sys.stdin).get('completed', False))" 2>/dev/null)
    if [ "$COMPLETED" = "True" ]; then
        BENCH_OK=true
        break
    fi
    sleep 1
done

if ! $BENCH_OK; then
    die "Benchmark did not complete within 90s"
fi

# Fetch and display results
STATS=$(hf_curl -sf "http://localhost:$HYPERFOIL_PORT/run/$RUN_ID/stats/total")
fi  # the Hyperfoil run; WsLoad printed the same totals

echo "$STATS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
artifact_bytes = $ARTIFACT_BYTES
hdr = f\"  {'phase':<8}{'req/s':>10}{'2xx%':>8}{'p50us':>9}{'p99us':>10}{'p99.9us':>10}{'maxus':>10}\"
print(hdr + (f\"{'MB/s':>9}\" if artifact_bytes else ''))
for s in data.get('statistics', []):
    name = s.get('phase') or s.get('name', '?')
    if s.get('isWarmup', False) or 'warmup' in name:
        continue
    summary = s['summary']
    pct = summary.get('percentileResponseTime', {})
    http = summary.get('extensions', {}).get('http', {})
    dur = (summary['endTime'] - summary['startTime']) / 1000
    rate = summary['requestCount'] / dur if dur else 0
    ok = http.get('status_2xx', 0) / max(summary['requestCount'], 1) * 100
    row = (f\"  {name:<8}{rate:>10.0f}{ok:>8.1f}{pct.get('50.0',0)/1000:>9.1f}\"
           f\"{pct.get('99.0',0)/1000:>10.1f}{pct.get('99.9',0)/1000:>10.1f}\"
           f\"{summary['maxResponseTime']/1000:>10.1f}\")
    if artifact_bytes:
        row += f\"{rate * artifact_bytes / 1048576:>9.1f}\"
    print(row)
"

# ── 8. Peak RSS ─────────────────────────────────────────────────────────────

PEAK_RSS=$(get_rss_kb "$PROXY_PID")
echo ""
echo "Peak RSS:    ${PEAK_RSS} KB"

# ── 9. Stop proxy and Hyperfoil ─────────────────────────────────────────────

destroy_instance
kill "$PROXY_PID" 2>/dev/null; wait "$PROXY_PID" 2>/dev/null || true
PROXY_PID=""
stop_hyperfoil

if $COLLECTING; then
    # SIGTERM runs the shutdown hooks, and the instrumented image dumps its profile in one.
    [ -s "$PROFILE" ] || die "The proxy wrote no profile at $PROFILE. Is $PROXY_RUNNER an instrumented build (--pgo-instrument, -H:+ParseRuntimeOptions)?"
    gzip -f "$PROFILE"
    echo ""
    echo "Profile:     $PROFILE.gz ($(file_size "$PROFILE.gz") bytes); an instrumented build's numbers above are not results."
    exit 0
fi

# ── 10. Parse Hyperfoil stats ──────────────────────────────────────────────

# Hyperfoil latencies are in nanoseconds
THROUGHPUT_DATA=$(echo "$STATS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
rungs = []
for s in data.get('statistics', []):
    name = s.get('phase') or s.get('name', '?')
    if s.get('isWarmup', False) or 'warmup' in name:
        continue
    summary = s['summary']
    pct = summary.get('percentileResponseTime', {})
    http = summary.get('extensions', {}).get('http', {})
    duration_s = (summary['endTime'] - summary['startTime']) / 1000
    rungs.append({
        'phase': name,
        'requestCount': summary['requestCount'],
        'meanReqPerSec': round(summary['requestCount'] / duration_s, 1) if duration_s else 0,
        'success2xxPct': round(http.get('status_2xx', 0) / max(summary['requestCount'], 1) * 100, 1),
        'p50Us': round(pct.get('50.0', 0) / 1000.0, 1),
        'p99Us': round(pct.get('99.0', 0) / 1000.0, 1),
        'p999Us': round(pct.get('99.9', 0) / 1000.0, 1),
        'mbPerSec': round(summary['requestCount'] / duration_s * $ARTIFACT_BYTES / 1048576, 1) if duration_s and $ARTIFACT_BYTES else None,
    })
# The headline is the best rung: under a saturating ladder that is the plateau,
# i.e. the proxy's actual ceiling. Under the constant-rate profile there is only
# one rung, so this stays identical to what the harness has always reported.
best = max(rungs, key=lambda r: r['meanReqPerSec']) if rungs else {}
out = dict(best)
out['ladder'] = rungs
print(json.dumps(out))
" 2>/dev/null) || THROUGHPUT_DATA='{"requestCount":0,"meanReqPerSec":0,"p50Us":0,"p99Us":0,"p999Us":0,"ladder":[]}'

# ── 11. Save results ────────────────────────────────────────────────────────

mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
RESULT_FILE="$RESULTS_DIR/${GIT_SHA}-$(date +%Y%m%d-%H%M%S).json"

python3 -c "
import json, sys
throughput = json.loads('''$THROUGHPUT_DATA''')
result = {
    'label': '''$LABEL''',
    'timestamp': '$TIMESTAMP',
    'gitSha': '$GIT_SHA',
    'gitSubject': '''$GIT_SUBJECT''',
    'graalvm': '''$GRAALVM_VERSION''',
    'platform': '$(uname -sm)',
    'proxyBuiltWith': '''$PROXY_BUILT_WITH''',
    'cliBuiltWith': '''$CLI_BUILT_WITH''',
    # The binaries measured: those --proxy/--cli named, which need not be this checkout's
    # build (gitSha and graalvm then describe the checkout and PATH, not them).
    'proxyBinary': '''$PROXY_RUNNER''',
    'cliBinary': '''$CLI_RUNNER''',
    'binarySizeBytes': $BINARY_SIZE,
    'cliBinarySizeBytes': $CLI_BINARY_SIZE,
    'cliStartupUs': $CLI_STARTUP_US,
    'startupMs': $STARTUP_MS,
    'idleRssKb': $IDLE_RSS,
    'peakRssKb': $PEAK_RSS,
    'throughput': throughput,
    'loadTool': '$($WS_LOAD && echo wsload || echo hyperfoil)',
    'loadMode': '''$LOAD_MODE''',
    'benchmarkName': '''$BENCHMARK_NAME''',
    # Read the shape of the load off the definition that actually ran; the old
    # hardcoded block described proxy-health and silently mislabelled any other profile.
    'hyperfoilConfig': {
        'image': '$HYPERFOIL_SOURCE',
        'definition': '''$(basename "$BENCHMARK_YAML")''',
        'connections': $(awk '/sharedConnections:/ { print $2; exit }' "$BENCHMARK_YAML"),
    },
}
with open('$RESULT_FILE', 'w') as f:
    json.dump(result, f, indent=2)
    f.write('\n')
print(json.dumps(result, indent=2))
" || die "Failed to write results"

echo ""
echo "Results saved to: $RESULT_FILE"

# ── 12. Summary and comparison ──────────────────────────────────────────────

echo ""
echo "=== Results ==="
printf "%-20s %s\n" "Built with:" "${PROXY_BUILT_WITH:-unknown}"
printf "%-20s %s\n" "Proxy binary:" "$BINARY_SIZE_MB MB"
printf "%-20s %s\n" "CLI binary:" "$CLI_BINARY_SIZE_MB MB"
printf "%-20s %s\n" "CLI startup:" "${CLI_STARTUP_US} us"
printf "%-20s %s\n" "Startup time:" "${STARTUP_MS} ms"
printf "%-20s %s\n" "Idle RSS:" "${IDLE_RSS} KB"
printf "%-20s %s\n" "Peak RSS:" "${PEAK_RSS} KB"

REQ_PER_SEC=$(echo "$THROUGHPUT_DATA" | python3 -c "import sys,json; print(json.load(sys.stdin)['meanReqPerSec'])")
P50=$(echo "$THROUGHPUT_DATA" | python3 -c "import sys,json; print(json.load(sys.stdin)['p50Us'])")
P99=$(echo "$THROUGHPUT_DATA" | python3 -c "import sys,json; print(json.load(sys.stdin)['p99Us'])")
P999=$(echo "$THROUGHPUT_DATA" | python3 -c "import sys,json; print(json.load(sys.stdin)['p999Us'])")

printf "%-20s %s\n" "Throughput:" "${REQ_PER_SEC} req/s"
printf "%-20s %s\n" "Latency p50:" "${P50} us"
printf "%-20s %s\n" "Latency p99:" "${P99} us"
printf "%-20s %s\n" "Latency p99.9:" "${P999} us"

# Compare with most recent previous result
# Only compare against a run of the same load profile. A constant-rate result and
# a saturating one differ by two orders of magnitude, so a cross-mode delta table
# is not a regression signal — it is noise dressed up as a 3000% improvement.
PREV_RESULT=$(python3 - "$RESULTS_DIR" "$RESULT_FILE" "$LOAD_MODE" <<'PY' || true
import json, os, sys
results_dir, current, mode = sys.argv[1], sys.argv[2], sys.argv[3]
candidates = []
for name in os.listdir(results_dir):
    path = os.path.join(results_dir, name)
    if not name.endswith('.json') or os.path.samefile(path, current):
        continue
    try:
        with open(path) as f:
            # Results written before loadMode existed were all constant-rate.
            if json.load(f).get('loadMode', 'constant') == mode:
                candidates.append((os.path.getmtime(path), path))
    except Exception:
        continue
if candidates:
    print(max(candidates)[1])
PY
)
if [ -n "$PREV_RESULT" ]; then
    echo ""
    echo "=== Comparison with previous run ==="
    PREV_LABEL=$(python3 -c "import json; d=json.load(open('$PREV_RESULT')); print(d.get('label','') or d.get('gitSha',''))")
    echo "Previous: $PREV_LABEL ($(basename "$PREV_RESULT"))"
    echo ""

    python3 -c "
import json

with open('$RESULT_FILE') as f:
    curr = json.load(f)
with open('$PREV_RESULT') as f:
    prev = json.load(f)

def delta(name, curr_val, prev_val, unit, lower_is_better=True):
    if prev_val == 0:
        print(f'  {name:<20s} {curr_val:>12} {unit}  (no previous)')
        return
    diff = curr_val - prev_val
    pct = (diff / prev_val) * 100
    sign = '+' if diff >= 0 else ''
    indicator = ''
    if abs(pct) >= 1:
        if lower_is_better:
            indicator = ' !!!' if diff > 0 else ' (better)'
        else:
            indicator = ' (better)' if diff > 0 else ' !!!'
    print(f'  {name:<20s} {curr_val:>12} {unit}  ({sign}{pct:.1f}%{indicator})')

ct = curr.get('throughput', {})
pt = prev.get('throughput', {})

delta('Proxy binary', curr['binarySizeBytes'], prev.get('binarySizeBytes', 0), 'B')
delta('CLI binary', curr.get('cliBinarySizeBytes', 0), prev.get('cliBinarySizeBytes', 0), 'B')
delta('CLI startup', curr.get('cliStartupUs', 0), prev.get('cliStartupUs', 0), 'us')
delta('Startup time', curr['startupMs'], prev['startupMs'], 'ms')
delta('Idle RSS', curr['idleRssKb'], prev['idleRssKb'], 'KB')
delta('Peak RSS', curr['peakRssKb'], prev['peakRssKb'], 'KB')
delta('Throughput', ct.get('meanReqPerSec',0), pt.get('meanReqPerSec',0), 'req/s', lower_is_better=False)
delta('Latency p50', ct.get('p50Us',0), pt.get('p50Us',0), 'us')
delta('Latency p99', ct.get('p99Us',0), pt.get('p99Us',0), 'us')
delta('Latency p99.9', ct.get('p999Us',0), pt.get('p999Us',0), 'us')
"
fi

echo ""
echo "Done."
