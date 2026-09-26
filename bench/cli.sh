#!/bin/bash
# bench/cli.sh — Wall-clock latency of the isx CLI against a live Incus daemon, JVM vs native
#
# Complements the request budgets in `mvn test` (InstanceLifecycleRequestBudgetTest), which
# count Incus round trips deterministically: this measures what those round trips, process
# startup and the runtime actually cost on this host. Too noisy to gate PRs; use it to
# compare before and after a change, or JVM against native, on one machine.
#
# Requires: working isx setup (isx init), running Incus daemon and proxy, a built template
#           to branch from (tpl-minimal by default), python3; GraalVM native-image unless
#           --skip-build or --runtime=jvm.
#
# Usage:
#   bench/cli.sh                          # build JVM + native CLIs, benchmark both
#   bench/cli.sh --skip-build             # reuse binaries in cli/target
#   bench/cli.sh --runtime=native         # only one runtime
#   bench/cli.sh --label "before-refactor"
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# A subdirectory, so run.sh's comparison never picks up a CLI result as a proxy one.
RESULTS_DIR="$SCRIPT_DIR/results/cli"

SKIP_BUILD=false
LABEL=""
RUNTIME="both"
RUNS=20
FROM="tpl-minimal"

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=true ;;
        --label=*) LABEL="${1#--label=}" ;;
        --label) shift; LABEL="${1:-}" ;;
        --runtime=*) RUNTIME="${1#--runtime=}" ;;
        --runtime) shift; RUNTIME="${1:-}" ;;
        --runs=*) RUNS="${1#--runs=}" ;;
        --runs) shift; RUNS="${1:-}" ;;
        --from=*) FROM="${1#--from=}" ;;
        --from) shift; FROM="${1:-}" ;;
        --help|-h)
            echo "Usage: bench/cli.sh [--skip-build] [--label=NAME] [--runtime=both|jvm|native] [--runs=N] [--from=TEMPLATE]"
            echo ""
            echo "Measures isx CLI latency against the local Incus daemon, JVM and/or native."
            echo "Creates a throwaway instance from TEMPLATE and destroys it on exit; no"
            echo "existing instance or template is started, stopped or modified."
            echo ""
            echo "Options:"
            echo "  --skip-build     Reuse the CLI builds already in cli/target"
            echo "  --label=NAME     Tag results with a label (e.g. 'baseline')"
            echo "  --runtime=MODE   both (default), jvm or native"
            echo "  --runs=N         Timed runs per operation (default 20, after 2 warmups)"
            echo "  --from=TEMPLATE  Template to branch the throwaway instance from (default tpl-minimal)"
            exit 0
            ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "Error: $*" >&2; exit 1; }

case "$RUNTIME" in
    both|jvm|native) ;;
    *) die "Unknown --runtime '$RUNTIME' (expected: both, jvm, native)" ;;
esac
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || die "--runs must be a positive integer"
command -v python3 &>/dev/null || die "python3 not found on PATH"

ISX_CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/incus-spawn"
[ -f "$ISX_CONFIG_DIR/config.yaml" ] || die "isx not initialized ($ISX_CONFIG_DIR/config.yaml missing). Run 'isx init' first."

wants() { [ "$RUNTIME" = both ] || [ "$RUNTIME" = "$1" ]; }

echo "=== Benchmark: isx CLI latency ==="
echo ""

# ── 1. Build ────────────────────────────────────────────────────────────────

# shellcheck disable=SC2012  # -t ordering is the point; names have no spaces
native_runner() { ls -t "$PROJECT_DIR"/cli/target/incus-spawn-*-runner 2>/dev/null | head -1 || true; }
JVM_JAR="$PROJECT_DIR/cli/target/quarkus-app/quarkus-run.jar"

if ! $SKIP_BUILD; then
    # Native first: the JVM package that follows leaves the native runner alone, while the
    # reverse order is not guaranteed to leave quarkus-app/ intact.
    if wants native; then
        command -v native-image &>/dev/null || die "native-image not found on PATH (or use --skip-build / --runtime=jvm)"
        echo "Building native CLI (this takes a few minutes)..."
        "$PROJECT_DIR/mvnw" -f "$PROJECT_DIR/pom.xml" package -Dnative -DskipTests -q -pl cli -am
    fi
    if wants jvm; then
        echo "Building JVM CLI..."
        "$PROJECT_DIR/mvnw" -f "$PROJECT_DIR/pom.xml" package -DskipTests -q -pl cli -am
    fi
fi

RUNTIMES=()
if wants native; then
    NATIVE="$(native_runner)"
    [ -n "$NATIVE" ] || die "No native CLI in cli/target/. Run without --skip-build first."
    RUNTIMES+=("native=$NATIVE")
    echo "  native: $NATIVE"
fi
if wants jvm; then
    [ -f "$JVM_JAR" ] || die "No JVM CLI at $JVM_JAR. Run without --skip-build first."
    RUNTIMES+=("jvm=java -jar $JVM_JAR")
    echo "  jvm:    java -jar $JVM_JAR ($(java -version 2>&1 | head -1))"
fi

# Lifecycle steps (branch, cold start, destroy) run once, with the first runtime.
ISX="${RUNTIMES[0]#*=}"

GIT_SHA="$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
GIT_SUBJECT="$(git -C "$PROJECT_DIR" log -1 --format=%s 2>/dev/null || echo "")"
echo "  git:    $GIT_SHA $GIT_SUBJECT"
echo ""

# ── 2. Throwaway instance ───────────────────────────────────────────────────

# $ISX is deliberately unquoted below: for the JVM it is "java -jar <path>".
# shellcheck disable=SC2086
$ISX instances >/dev/null 2>&1 || die "isx cannot reach Incus ('isx instances' failed). Is the daemon running?"
INSTANCE="isx-bench-$$"
# Destroy the throwaway instance if a failure left it behind; a completed run times its own
# destroy, so by then it is already gone.
cleanup() {
    # shellcheck disable=SC2086
    if $ISX instances 2>/dev/null | grep -qx "$INSTANCE"; then
        echo ""
        echo "Destroying $INSTANCE..."
        # shellcheck disable=SC2086
        $ISX destroy "$INSTANCE" --skip-confirmation >/dev/null 2>&1 || \
            echo "Warning: could not destroy $INSTANCE; remove it with: isx destroy $INSTANCE" >&2
    fi
}
trap cleanup EXIT

# The timing, statistics, result file and comparison all live in Python, so each sample is
# measured around the child process alone instead of around a `date` fork per timestamp.
export PROJECT_DIR RESULTS_DIR LABEL RUNS FROM INSTANCE GIT_SHA GIT_SUBJECT ISX
export RUNTIME_SPECS
RUNTIME_SPECS="$(printf '%s\n' "${RUNTIMES[@]}")"

python3 - <<'PY'
import json, os, shlex, statistics, subprocess, sys, time
from datetime import datetime, timezone

env = os.environ
runs = int(env["RUNS"])
instance, source = env["INSTANCE"], env["FROM"]
lifecycle_cmd = shlex.split(env["ISX"])
runtimes = [line.split("=", 1) for line in env["RUNTIME_SPECS"].splitlines() if line]

# An unknown action makes `isx run` do everything `isx shell` does before attaching a
# terminal (instance checks, proxy health, IP/CA/resolv.conf repair, readiness) and then
# exit 1 with this message. Anything else means we would be timing a different failure.
PREPARE_ACTION = "isx-bench-no-such-action"
PREPARE_MARKER = f"action '{PREPARE_ACTION}' not found"

def run(cmd, expect_exit=0, marker=None):
    start = time.perf_counter_ns()
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True)
    elapsed_ms = (time.perf_counter_ns() - start) / 1e6
    output = proc.stdout + proc.stderr
    if proc.returncode != expect_exit or (marker and marker not in output):
        sys.exit(f"\nError: {' '.join(cmd)} exited {proc.returncode} (expected {expect_exit})"
                 + (f" without '{marker}'" if marker else "") + ":\n" + output.strip())
    return elapsed_ms

# (name, args, expected exit, output marker)
OPERATIONS = [
    ("startup", ["--help"], 0, None),
    ("instances", ["instances"], 0, None),
    ("accountShow", ["account", "show", instance], 0, None),
    ("prepareRunning", ["run", instance, "--action", PREPARE_ACTION], 1, PREPARE_MARKER),
]

def stats(samples):
    ordered = sorted(samples)
    p90 = ordered[min(len(ordered) - 1, int(round(0.9 * (len(ordered) - 1))))]
    return {"medianMs": round(statistics.median(ordered), 1), "p90Ms": round(p90, 1),
            "minMs": round(ordered[0], 1), "runs": len(ordered)}

print(f"Branching {instance} from {source}...")
lifecycle = {}
lifecycle["branchMs"] = round(run(lifecycle_cmd + ["branch", instance, "--from", source, "--no-start"]), 1)
print("Cold start (first prepare starts the instance)...")
lifecycle["coldPrepareMs"] = round(run(lifecycle_cmd + ["run", instance, "--action", PREPARE_ACTION],
                                       1, PREPARE_MARKER), 1)

results = {}
for name, cmd in runtimes:
    base = shlex.split(cmd)
    results[name] = {}
    print(f"\n[{name}]")
    for op, args, code, marker in OPERATIONS:
        for _ in range(2):
            run(base + args, code, marker)
        s = stats([run(base + args, code, marker) for _ in range(runs)])
        results[name][op] = s
        print(f"  {op:<16} {s['medianMs']:>8.1f} ms median  {s['p90Ms']:>8.1f} p90  {s['minMs']:>8.1f} min")

print("\nDestroying (timed)...")
lifecycle["destroyMs"] = round(run(lifecycle_cmd + ["destroy", instance, "--skip-confirmation"]), 1)

os.makedirs(env["RESULTS_DIR"], exist_ok=True)
result = {
    "label": env["LABEL"],
    "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "gitSha": env["GIT_SHA"],
    "gitSubject": env["GIT_SUBJECT"],
    "source": source,
    "runtimes": results,
    # Single samples: indicative only, dominated by Incus rather than the CLI.
    "lifecycle": lifecycle,
}
path = os.path.join(env["RESULTS_DIR"],
                    f"{env['GIT_SHA']}-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json")
with open(path, "w") as f:
    json.dump(result, f, indent=2)
    f.write("\n")

print("\n=== Lifecycle (single samples, indicative only) ===")
for key, value in lifecycle.items():
    print(f"  {key:<16} {value:>8.1f} ms")
if "native" in results and "jvm" in results:
    print("\n=== JVM / native (median) ===")
    for op, *_ in OPERATIONS:
        n, j = results["native"][op]["medianMs"], results["jvm"][op]["medianMs"]
        print(f"  {op:<16} {j / n:>6.1f}x" if n else f"  {op:<16}    n/a")
print(f"\nResults saved to: {path}")

# Compare each runtime's medians with the most recent earlier result that measured it.
previous = []
for entry in os.listdir(env["RESULTS_DIR"]):
    other = os.path.join(env["RESULTS_DIR"], entry)
    if entry.endswith(".json") and not os.path.samefile(other, path):
        try:
            with open(other) as f:
                previous.append((os.path.getmtime(other), json.load(f), entry))
        except (OSError, ValueError):
            pass
for name in results:
    candidates = [p for p in previous if name in p[1].get("runtimes", {})]
    if not candidates:
        continue
    _, prev, entry = max(candidates, key=lambda p: p[0])
    print(f"\n=== {name}: comparison with {prev.get('label') or prev.get('gitSha')} ({entry}) ===")
    for op, *_ in OPERATIONS:
        curr_ms = results[name][op]["medianMs"]
        prev_ms = prev["runtimes"][name].get(op, {}).get("medianMs")
        if not prev_ms:
            print(f"  {op:<16} {curr_ms:>8.1f} ms  (no previous)")
            continue
        pct = (curr_ms - prev_ms) / prev_ms * 100
        # Run-to-run noise on a desktop is several percent; flag only what exceeds it.
        flag = " !!!" if pct >= 10 else (" (better)" if pct <= -10 else "")
        print(f"  {op:<16} {curr_ms:>8.1f} ms  ({pct:+.1f}%{flag})")
PY

echo ""
echo "Done."
