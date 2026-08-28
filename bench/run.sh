#!/bin/bash
# bench/run.sh — Benchmark native image: binary size, memory, proxy throughput
#
# Requires: Oracle GraalVM with native-image, working isx setup (isx init),
#           running Incus daemon, Podman (for Hyperfoil container)
#
# Usage:
#   bench/run.sh                    # full build + benchmark
#   bench/run.sh --skip-build       # reuse existing native binary
#   bench/run.sh --label "baseline" # tag results with a label
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"

HYPERFOIL_IMAGE="quay.io/hyperfoil/hyperfoil:latest"
HYPERFOIL_CONTAINER="isx-bench-hf"
HYPERFOIL_PORT=8090
BENCHMARK_YAML="$SCRIPT_DIR/proxy-health.hf.yaml"
LOAD_MODE="constant"

SKIP_BUILD=false
LABEL=""
GRAALVM_DIR=""
BUILDER_IMAGE=""

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
        --help|-h)
            echo "Usage: bench/run.sh [--skip-build] [--label=NAME] [--graalvm=DIR|--builder-image=TAG]"
            echo ""
            echo "Benchmarks the native image build of the MITM proxy."
            echo "Measures: binary size, startup time, memory (RSS), throughput, latency."
            echo ""
            echo "Options:"
            echo "  --skip-build    Reuse existing native binaries in cli/target and proxy/target"
            echo "  --label=NAME    Tag results with a label (e.g. 'baseline')"
            echo "  --load=MODE     constant (default): 5000 req/s, ~3% of capacity — a"
            echo "                  regression tripwire, blind to throughput changes."
            echo "                  saturate: closed-loop concurrency ladder that drives the"
            echo "                  proxy to its actual ceiling. Use this to compare toolchains."
            echo "  --graalvm=DIR   Build with this GraalVM instead of whatever is on PATH."
            echo "  --builder-image=TAG"
            echo "                  Build in this container image — the path Linux releases"
            echo "                  actually use (see install.sh). Preferred over --graalvm on"
            echo "                  Linux, since it reproduces the release toolchain exactly."
            echo ""
            echo "                  Either flag lets you A/B two toolchains on one host:"
            echo "                    bench/run.sh --builder-image=incus-spawn-graalvm-builder:25.2 --label=25.2"
            echo "                    bench/run.sh --builder-image=incus-spawn-graalvm-builder:25.3 --label=25.3"
            echo ""
            echo "Requirements:"
            echo "  - Oracle GraalVM with native-image on PATH (or via --graalvm/--builder-image)"
            echo "  - Working isx setup (run 'isx init' first)"
            echo "  - Running Incus daemon"
            echo "  - Podman (for running Hyperfoil in a container)"
            exit 0
            ;;
    esac
    shift
done

# ── Helpers ──────────────────────────────────────────────────────────────────

die() { echo "Error: $*" >&2; exit 1; }

cleanup() {
    echo ""
    echo "Cleaning up..."
    [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null && wait "$PROXY_PID" 2>/dev/null || true
    podman stop "$HYPERFOIL_CONTAINER" 2>/dev/null && podman rm "$HYPERFOIL_CONTAINER" 2>/dev/null || true
}
trap cleanup EXIT

get_rss_kb() {
    local pid=$1
    awk '/^VmRSS:/ { print $2 }' "/proc/$pid/status" 2>/dev/null || echo "0"
}

epoch_ms() {
    date +%s%3N
}

# ── 1. Validate environment ─────────────────────────────────────────────────

echo "=== Benchmark: native image proxy ==="
echo ""

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

# Check Podman
if ! command -v podman &>/dev/null; then
    die "podman not found on PATH. Hyperfoil runs inside a Podman container to work around non-contiguous CPU numbering in /proc/stat."
fi

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
    *) die "Unknown --load mode '$LOAD_MODE' (expected: constant, saturate)" ;;
esac

# Check benchmark definition
if [ ! -f "$BENCHMARK_YAML" ]; then
    die "Benchmark definition not found at $BENCHMARK_YAML"
fi

# The name in the YAML is what the REST API addresses the benchmark by, so read
# it rather than hardcoding — otherwise adding a profile silently starts the wrong one.
BENCHMARK_NAME=$(awk '/^name:/ { print $2; exit }' "$BENCHMARK_YAML")
[ -n "$BENCHMARK_NAME" ] || die "No 'name:' in $BENCHMARK_YAML"
echo "Load:     $LOAD_MODE ($BENCHMARK_NAME)"

# Resolve gateway IP from Incus bridge
GATEWAY_IP=$(incus network get incusbr0 ipv4.address 2>/dev/null | cut -d/ -f1) || true
if [ -z "$GATEWAY_IP" ]; then
    die "Could not determine Incus bridge gateway IP. Is Incus running?"
fi
echo "Gateway:  $GATEWAY_IP"

# A proxy already bound to the health port is the worst failure mode here: the
# freshly built one loses the bind and exits, but /health still answers — from
# the *old* process — so the run would report its startup, RSS and throughput.
if curl -sf --max-time 2 "http://$GATEWAY_IP:18080/health" &>/dev/null; then
    die "Something is already serving $GATEWAY_IP:18080 — results would come from that process, not the build under test.
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

if $SKIP_BUILD; then
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
PROXY_BUILT_WITH=$("$PROXY_RUNNER" --version 2>/dev/null | tail -1 || true)
CLI_BUILT_WITH=$("$CLI_RUNNER" --version 2>/dev/null | tail -1 || true)
echo "  built with: ${PROXY_BUILT_WITH:-unknown}"
echo ""

# ── 3. Binary size and CLI startup ──────────────────────────────────────────

BINARY_SIZE=$(stat -c %s "$PROXY_RUNNER")
BINARY_SIZE_MB=$(awk "BEGIN { printf \"%.1f\", $BINARY_SIZE / 1048576 }")
CLI_BINARY_SIZE=$(stat -c %s "$CLI_RUNNER")
CLI_BINARY_SIZE_MB=$(awk "BEGIN { printf \"%.1f\", $CLI_BINARY_SIZE / 1048576 }")
echo "Proxy binary: $BINARY_SIZE_MB MB ($BINARY_SIZE bytes)"
echo "CLI binary:   $CLI_BINARY_SIZE_MB MB ($CLI_BINARY_SIZE bytes)"

# The CLI is short-lived and startup-bound, so its cost is process launch, not
# throughput. Take the median of 20 `--help` runs (--help touches no daemon).
CLI_SAMPLES=$(for _ in $(seq 1 20); do
    s=$(date +%s%N)
    "$CLI_RUNNER" --help >/dev/null 2>&1 || true
    e=$(date +%s%N)
    echo $(( (e - s) / 1000 ))
done | sort -n | tr '\n' ' ')
CLI_STARTUP_US=$(echo "$CLI_SAMPLES" | awk '{ print $10 }')
echo "CLI startup:  ${CLI_STARTUP_US} us (median of 20)"

# ── 4. Start proxy and measure startup time ─────────────────────────────────

echo "Starting proxy..."
PROXY_START=$(epoch_ms)
"$PROXY_RUNNER" --gateway-ip "$GATEWAY_IP" &>/dev/null &
PROXY_PID=$!

HEALTH_URL="http://$GATEWAY_IP:18080/health"
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

# ── 6. Start Hyperfoil ──────────────────────────────────────────────────────

echo ""

# Ensure image is available
if ! podman image exists "$HYPERFOIL_IMAGE" 2>/dev/null; then
    echo "Pulling Hyperfoil image..."
    podman pull "$HYPERFOIL_IMAGE" >/dev/null 2>&1
fi

# Remove any leftover container from a previous run
podman rm -f "$HYPERFOIL_CONTAINER" 2>/dev/null || true

echo "Starting Hyperfoil controller..."
podman run -d --name "$HYPERFOIL_CONTAINER" --network=host "$HYPERFOIL_IMAGE" standalone >/dev/null 2>&1

# Wait for controller to be ready
HF_READY=false
for i in $(seq 1 30); do
    if curl -sf "http://localhost:$HYPERFOIL_PORT/benchmark" &>/dev/null; then
        HF_READY=true
        break
    fi
    sleep 1
done

if ! $HF_READY; then
    echo "Hyperfoil logs:"
    podman logs "$HYPERFOIL_CONTAINER" 2>&1 | tail -20
    die "Hyperfoil controller failed to start after 30s"
fi

HYPERFOIL_VERSION=$(podman logs "$HYPERFOIL_CONTAINER" 2>&1 | grep -oP 'Hyperfoil: \K[0-9.]+' | head -1)
echo "Hyperfoil:   $HYPERFOIL_VERSION (container)"

# ── 7. Run load test ────────────────────────────────────────────────────────

# Upload benchmark definition
curl -sf -X POST "http://localhost:$HYPERFOIL_PORT/benchmark" \
    -H "Content-Type: text/vnd.yaml" \
    --data-binary "@$BENCHMARK_YAML" >/dev/null

TARGET_URL="http://$GATEWAY_IP:18080"

# Start the benchmark run
RESPONSE=$(curl -sf "http://localhost:$HYPERFOIL_PORT/benchmark/$BENCHMARK_NAME/start?templateParam=TARGET=$TARGET_URL")
RUN_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Run started: $RUN_ID"

# Poll for completion
echo "Running benchmark (warmup 5s + steady 15s)..."
BENCH_OK=false
for i in $(seq 1 60); do
    COMPLETED=$(curl -sf "http://localhost:$HYPERFOIL_PORT/run/$RUN_ID" | \
        python3 -c "import sys,json; print(json.load(sys.stdin).get('completed', False))" 2>/dev/null)
    if [ "$COMPLETED" = "True" ]; then
        BENCH_OK=true
        break
    fi
    sleep 1
done

if ! $BENCH_OK; then
    die "Benchmark did not complete within 60s"
fi

# Fetch and display results
STATS=$(curl -sf "http://localhost:$HYPERFOIL_PORT/run/$RUN_ID/stats/total")

echo "$STATS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f\"  {'phase':<8}{'req/s':>10}{'2xx%':>8}{'p50us':>9}{'p99us':>10}{'p99.9us':>10}{'maxus':>10}\")
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
    print(f\"  {name:<8}{rate:>10.0f}{ok:>8.1f}{pct.get('50.0',0)/1000:>9.1f}\"
          f\"{pct.get('99.0',0)/1000:>10.1f}{pct.get('99.9',0)/1000:>10.1f}\"
          f\"{summary['maxResponseTime']/1000:>10.1f}\")
"

# ── 8. Peak RSS ─────────────────────────────────────────────────────────────

PEAK_RSS=$(get_rss_kb "$PROXY_PID")
echo ""
echo "Peak RSS:    ${PEAK_RSS} KB"

# ── 9. Stop proxy and Hyperfoil ─────────────────────────────────────────────

kill "$PROXY_PID" 2>/dev/null; wait "$PROXY_PID" 2>/dev/null || true
PROXY_PID=""
podman stop "$HYPERFOIL_CONTAINER" 2>/dev/null && podman rm "$HYPERFOIL_CONTAINER" 2>/dev/null || true

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
    'proxyBuiltWith': '''$PROXY_BUILT_WITH''',
    'cliBuiltWith': '''$CLI_BUILT_WITH''',
    'binarySizeBytes': $BINARY_SIZE,
    'cliBinarySizeBytes': $CLI_BINARY_SIZE,
    'cliStartupUs': $CLI_STARTUP_US,
    'startupMs': $STARTUP_MS,
    'idleRssKb': $IDLE_RSS,
    'peakRssKb': $PEAK_RSS,
    'throughput': throughput,
    'loadTool': 'hyperfoil',
    'loadMode': '''$LOAD_MODE''',
    'benchmarkName': '''$BENCHMARK_NAME''',
    # Read the shape of the load off the definition that actually ran; the old
    # hardcoded block described proxy-health and silently mislabelled any other profile.
    'hyperfoilConfig': {
        'image': '$HYPERFOIL_IMAGE',
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
