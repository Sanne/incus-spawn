# Benchmarking

Performance benchmarks for the native image build of the MITM proxy. Use these to catch regressions in binary size, memory usage, startup time, or request latency before and after changes.

## Prerequisites

- **Linux x86_64** (benchmarks read `/proc/<pid>/status` for RSS)
- **Oracle GraalVM** with `native-image` on `PATH` — release builds use Oracle GraalVM (not Community Edition), so benchmarks should too for comparable results. Download from https://www.oracle.com/java/technologies/downloads/ or use sdkman: `sdk install java 25.0.3-oracle`
- **Running Incus daemon** with the default `incusbr0` bridge
- **Working isx setup** — run `isx init` first so `~/.config/incus-spawn/config.yaml` and the CA cert exist. The proxy needs an API key configured (it doesn't make real API calls during benchmarks, but validates the config)
- **Podman** — [Hyperfoil](https://hyperfoil.io/) runs inside a Podman container to work around a [CpuWatchdog bug](https://github.com/Hyperfoil/Hyperfoil/issues/833) on machines with non-contiguous CPU numbering in `/proc/stat`. The container virtualizes `/proc/stat` with contiguous numbering.
- **curl** and **python3** (used for Hyperfoil REST API interaction and stats parsing)

The script auto-pulls the Hyperfoil container image (`quay.io/hyperfoil/hyperfoil`) on first run.

## Quick Start

```shell
# Full run: build native image + benchmark
bench/run.sh

# Skip the native build (reuse existing binary in target/)
bench/run.sh --skip-build

# Tag results for easy identification
bench/run.sh --label "before-proxy-refactor"
```

## Comparing Before/After a Change

```shell
# 1. Benchmark the current code
bench/run.sh --label "before"

# 2. Make your changes, then benchmark again
bench/run.sh --label "after"
```

The script automatically compares with the most recent previous result and prints a delta table:

```
=== Comparison with previous run ===
Previous: before (c633805-20260611-150712.json)

  Binary size              38210632 B  (+0.0%)
  Startup time                 1815 ms  (+16.6% !!!)
  Idle RSS                   152744 KB  (+39.5% !!!)
  Throughput                 5000.3 req/s  (+0.0%)
  Latency p50                  62.7 us  (-3.4% (better))
  Latency p99                 110.6 us  (-32.8% (better))
```

Regressions of 1% or more are flagged with `!!!`; improvements with `(better)`.

## What Gets Measured

| Metric | How | Why |
|---|---|---|
| Binary size | `stat` on the native runner | Detects image bloat from dependency changes |
| Startup time | Wall-clock to first healthy `/health` response | Native startup regression |
| Idle RSS | `/proc/<pid>/status` VmRSS after 2s settle | Memory footprint at rest |
| Peak RSS | VmRSS after the load test | Memory under pressure |
| Throughput | Hyperfoil constant-rate at 5000 req/s | HTTP server efficiency |
| Latency p50/p99/max | Hyperfoil steady phase stats | Per-request overhead |

## How It Works

1. Validates the environment (GraalVM, Podman, isx config, Incus bridge)
2. Builds a native image (`mvnw package -Dnative -DskipTests`), skippable with `--skip-build`
3. Starts the proxy against the Incus bridge gateway IP
4. Measures startup time (polling `/health` at 250ms intervals)
5. Records idle RSS after a 2-second settle period
6. Starts a Hyperfoil controller in a Podman container (`--network=host`)
7. Uploads `bench/proxy-health.hf.yaml` via the Hyperfoil REST API
8. Runs a 5-second warmup at 1000 req/s (results discarded)
9. Runs a 15-second steady-state benchmark at 5000 req/s with 50 connections
10. Records peak RSS after load
11. Saves results as JSON to `bench/results/<git-sha>-<timestamp>.json`
12. Prints summary and comparison with the previous run

## Result Format

Results are JSON files in `bench/results/`:

```json
{
  "label": "after-vertx-refactor",
  "timestamp": "2026-06-11T15:08:49Z",
  "gitSha": "831a617",
  "gitSubject": "Use Quarkus-managed Vert.x instance in MITM proxy",
  "graalvm": "native-image 25.0.3 2026-04-21",
  "binarySizeBytes": 38210632,
  "startupMs": 1815,
  "idleRssKb": 152744,
  "peakRssKb": 160108,
  "throughput": {
    "requestCount": 75655,
    "meanReqPerSec": 5043.7,
    "p50Us": 62.7,
    "p99Us": 110.6,
    "p999Us": 1810.4
  },
  "loadTool": "hyperfoil",
  "hyperfoilConfig": {
    "image": "quay.io/hyperfoil/hyperfoil:latest",
    "connections": 50,
    "warmupDuration": "5s",
    "steadyDuration": "15s",
    "targetRate": 5000
  }
}
```

Result files are git-ignored.

## Interpreting Results

**Stable metrics:** Binary size is deterministic for a given commit — any change is real. Throughput at constant rate (5000 req/s) should show 100% success; a drop means the proxy can't keep up.

**Noisy metrics:** Startup time and RSS vary between runs due to OS scheduling, memory fragmentation, and background load. Run benchmarks on a quiet machine for reliable comparisons. Expect ~10% variance in startup time and ~5% in RSS.

**Latency tails:** p99 and max latency are the most sensitive to system noise. A single GC pause or context switch can spike max. Focus on p50 and p99 for trends; treat max as a ceiling indicator.

**Hyperfoil CpuWatchdog warnings:** The benchmark may report CPU usage warnings (e.g. "CPU 4 was used for 98%"). These are informational — Hyperfoil flags host CPUs exceeding 80% utilization during the test. They don't affect results but indicate the machine was under load, which may increase latency variance.

## File Layout

```
bench/
  run.sh                # Main benchmark script
  proxy-health.hf.yaml  # Hyperfoil benchmark definition
  README.md             # This file
  results/              # JSON result files (git-ignored)
```
