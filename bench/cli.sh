#!/bin/bash
# bench/cli.sh — Wall-clock latency of the isx CLI against a live Incus daemon, JVM vs native
#
# Complements the request budgets in `mvn test` (InstanceLifecycleRequestBudgetTest), which
# count Incus round trips deterministically: this measures what those round trips, process
# startup and the runtime actually cost on this host. Too noisy to gate PRs; use it to
# compare before and after a change, or JVM against native, on one machine.
#
# Requires: working isx setup (isx init), running Incus daemon (on macOS: the VM, isx vm start)
#           and proxy, a built template to branch from (tpl-minimal by default), python3, curl;
#           GraalVM native-image unless --skip-build or --runtime=jvm. The running proxy must
#           be the build the CLI under test comes from (./install.sh --native from this
#           commit), unless --allow-drift.
#
# Usage:
#   bench/cli.sh                          # build JVM + native CLIs, benchmark both
#   bench/cli.sh --skip-build             # reuse binaries in cli/target
#   bench/cli.sh --runtime=native         # only one runtime
#   bench/cli.sh --label "before-refactor"
#   bench/cli.sh --allow-drift            # time a CLI against a proxy from another build
#   bench/cli.sh --cli=BIN                # time this native binary (an A/B of build flags), no build
#   bench/cli.sh --collect=DIR --cli=BIN  # PGO profile collection with an instrumented BIN (Linux)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# A subdirectory, so run.sh's comparison never picks up a CLI result as a proxy one.
RESULTS_DIR="$SCRIPT_DIR/results/cli"

SKIP_BUILD=false
LABEL=""
RUNTIME="both"
RUNS=20
BRANCH_RUNS=3
FROM=""
ALLOW_DRIFT=false
CLI_BIN=""
COLLECT_DIR=""
RUNTIME_GIVEN=false

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=true ;;
        --label=*) LABEL="${1#--label=}" ;;
        --label) shift; LABEL="${1:-}" ;;
        --runtime=*) RUNTIME="${1#--runtime=}"; RUNTIME_GIVEN=true ;;
        --runtime) shift; RUNTIME="${1:-}"; RUNTIME_GIVEN=true ;;
        --runs=*) RUNS="${1#--runs=}" ;;
        --runs) shift; RUNS="${1:-}" ;;
        --branch-runs=*) BRANCH_RUNS="${1#--branch-runs=}" ;;
        --branch-runs) shift; BRANCH_RUNS="${1:-}" ;;
        --from=*) FROM="${1#--from=}" ;;
        --from) shift; FROM="${1:-}" ;;
        --allow-drift) ALLOW_DRIFT=true ;;
        --cli=*) CLI_BIN="${1#--cli=}" ;;
        --cli) shift; CLI_BIN="${1:-}" ;;
        --collect=*) COLLECT_DIR="${1#--collect=}" ;;
        --collect) shift; COLLECT_DIR="${1:-}" ;;
        --help|-h)
            echo "Usage: bench/cli.sh [--skip-build] [--label=NAME] [--runtime=both|jvm|native] [--runs=N] [--branch-runs=N] [--from=TEMPLATE] [--allow-drift]"
            echo "                    [--cli=BIN] [--collect=DIR]"
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
            echo "  --branch-runs=N  Full branches timed per runtime (default 3; each creates an instance)"
            echo "  --from=TEMPLATE  Template to branch the throwaway instance from (default tpl-minimal)"
            echo "  --allow-drift    Run even though the running proxy is a different build from the CLI"
            echo "                   (every command then warns about drift, and the first restarts the proxy)"
            echo "  --cli=BIN        The native CLI to time instead of cli/target's (implies --runtime=native, no build)"
            echo "  --collect=DIR    PGO profile collection: BIN (--cli, required) is an instrumented build"
            echo "                   (--pgo-instrument, -H:+ParseRuntimeOptions). Runs the weighted CLI flows"
            echo "                   (branch, shell, ssh-proxy, git-remote-isx, list, TUI) and writes one"
            echo "                   DIR/cli-<flow>-*.iprof.gz per process; nothing is timed. TEMPLATE needs"
            echo "                   sshd (default tpl-isx-bench, built from bench/). Linux only."
            exit 0
            ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "Error: $*" >&2; exit 1; }

COLLECTING=false
if [ -n "$COLLECT_DIR" ]; then
    COLLECTING=true
    [ -n "$CLI_BIN" ] || die "--collect needs --cli=BIN, an instrumented CLI build"
    [ "$(uname -s)" = Linux ] || die "--collect drives instances through the incus client; it runs on Linux only"
    command -v incus &>/dev/null || die "incus client not found; --collect needs it"
    command -v git &>/dev/null || die "git not found; --collect runs the git-remote-isx flows"
    { command -v ssh && command -v scp; } &>/dev/null || die "ssh and scp not found; --collect runs the ssh-proxy flows"
    mkdir -p "$COLLECT_DIR" || die "Cannot create $COLLECT_DIR"
    COLLECT_DIR="$(cd "$COLLECT_DIR" && pwd)"
    FROM="${FROM:-tpl-isx-bench}"
fi
FROM="${FROM:-tpl-minimal}"
if [ -n "$CLI_BIN" ]; then
    [ -x "$CLI_BIN" ] || die "No executable CLI at $CLI_BIN"
    if $RUNTIME_GIVEN && [ "$RUNTIME" != native ]; then
        die "--cli=BIN is a native binary; it cannot be timed as --runtime=$RUNTIME"
    fi
    CLI_BIN="$(cd "$(dirname "$CLI_BIN")" && pwd)/$(basename "$CLI_BIN")"
    RUNTIME=native
    SKIP_BUILD=true
fi
if $COLLECTING; then
    # Every isx process of a flow, whoever starts it (this script, git through git-remote-isx,
    # ssh as its ProxyCommand), runs through this wrapper, so each writes a profile of its own
    # named after its flow. Setup runs outside a flow write theirs to a directory that is dropped.
    COLLECT_WORK="$(mktemp -d)"
    mkdir -p "$COLLECT_WORK/bin" "$COLLECT_WORK/discard"
    cat > "$COLLECT_WORK/bin/isx" <<WRAPPER
#!/bin/sh
exec "$CLI_BIN" "-XX:ProfilesDumpFile=\${ISX_BENCH_PROFILES:-$COLLECT_WORK/discard}/cli-\${ISX_BENCH_FLOW:-setup}-\$(date +%s%N)-\$\$.iprof" "\$@"
WRAPPER
    cp "$PROJECT_DIR/common/src/main/resources/git-remote-isx" "$COLLECT_WORK/bin/"
    chmod +x "$COLLECT_WORK/bin/isx" "$COLLECT_WORK/bin/git-remote-isx"
    export PATH="$COLLECT_WORK/bin:$PATH"
    CLI_BIN="$COLLECT_WORK/bin/isx"
fi

case "$RUNTIME" in
    both|jvm|native) ;;
    *) die "Unknown --runtime '$RUNTIME' (expected: both, jvm, native)" ;;
esac
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || die "--runs must be a positive integer"
[[ "$BRANCH_RUNS" =~ ^[1-9][0-9]*$ ]] || die "--branch-runs must be a positive integer"
command -v python3 &>/dev/null || die "python3 not found on PATH"
command -v curl &>/dev/null || die "curl not found on PATH"

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
    NATIVE="${CLI_BIN:-$(native_runner)}"
    [ -n "$NATIVE" ] || die "No native CLI in cli/target/. Run without --skip-build first."
    RUNTIMES+=("native=$NATIVE")
    echo "  native: $NATIVE"
fi
if wants jvm; then
    [ -f "$JVM_JAR" ] || die "No JVM CLI at $JVM_JAR. Run without --skip-build first."
    RUNTIMES+=("jvm=java -jar $JVM_JAR")
    echo "  jvm:    java -jar $JVM_JAR ($(java -version 2>&1 | head -1))"
fi

# A CLI built from a different commit than the running proxy sees version drift: the first
# drifted command restarts the proxy (~2.5 s, inside a timed sample) and every later one warns
# and reads the restart record. Neither is what a clean install does, so refuse up front, as
# run.sh refuses a proxy it would not be measuring.
if [ "$(uname -s)" = Darwin ]; then
    # Incus runs in the VM and the host has no incus client; the proxy's health endpoint
    # is on loopback there (ProxyHealthCheck.healthAddress).
    HEALTH_ADDR=127.0.0.1
    # The proxy answers /health with the VM down too, so look for the VM here, as the bridge
    # lookup does for Incus on Linux (the socket is Environment.vmVsockSocket).
    [ -S "$HOME/.local/state/incus-spawn/vm.incus.sock" ] || \
        die "No appliance socket. Is the VM running? (isx vm start)"
else
    HEALTH_ADDR=$(incus network get incusbr0 ipv4.address 2>/dev/null | cut -d/ -f1) || true
    [ -n "$HEALTH_ADDR" ] || die "Could not determine the Incus bridge gateway IP. Is Incus running?"
fi
PROXY_HEALTH=$(curl -sf --max-time 2 "http://$HEALTH_ADDR:18080/health") || \
    die "No proxy answering on $HEALTH_ADDR:18080. Start it first: isx proxy start"
# "<displayVersion> (<gitSha>)", the form `isx --version` prints; older proxies only send version.
PROXY_BUILD=$(python3 -c 'import json, sys; h = json.load(sys.stdin); print(h.get("displayVersion") or h.get("version", ""), "(" + h.get("gitSha", "") + ")")' \
    <<<"$PROXY_HEALTH") || die "Unreadable /health response from $HEALTH_ADDR:18080: $PROXY_HEALTH"
echo "  proxy:  $PROXY_BUILD"
for spec in "${RUNTIMES[@]}"; do
    # shellcheck disable=SC2086  # "java -jar <path>" for the JVM
    CLI_BUILD=$(${spec#*=} --version 2>/dev/null | sed -nE '1s/^(isx|incus-spawn) (.*)$/\2/p') || true
    [ -n "$CLI_BUILD" ] || die "Could not read the ${spec%%=*} CLI's version (${spec#*=} --version)"
    [ "$CLI_BUILD" = "$PROXY_BUILD" ] && continue
    if $ALLOW_DRIFT; then
        echo "  Warning: ${spec%%=*} CLI is $CLI_BUILD; drift warnings and one proxy restart will skew its timings." >&2
        continue
    fi
    die "The ${spec%%=*} CLI under test is $CLI_BUILD, but the running proxy is $PROXY_BUILD.
Every command would warn about the drift, and the first would restart the proxy inside a timed sample.
  Install matching binaries:  ./install.sh --native   (restarts the proxy service too)
  Or run from the commit the proxy was built at, or pass --allow-drift to compare across builds."
done

# Lifecycle steps (branch, cold start, destroy) run once, with the first runtime.
ISX="${RUNTIMES[0]#*=}"

GIT_SHA="$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
GIT_SUBJECT="$(git -C "$PROJECT_DIR" log -1 --format=%s 2>/dev/null || echo "")"
echo "  git:    $GIT_SHA $GIT_SUBJECT"
echo ""

# ── 2. Throwaway instance ───────────────────────────────────────────────────

# $ISX is deliberately unquoted below: for the JVM it is "java -jar <path>".
# shellcheck disable=SC2086
$ISX list -q >/dev/null 2>&1 || die "isx cannot reach Incus ('isx list -q' failed). Is the daemon running?"
INSTANCE="isx-bench-$$"
# Destroy whatever throwaway instances a failure left behind: $INSTANCE and the per-sample
# $INSTANCE-<runtime>-<n> branches. A completed run destroys its own, so by then none are left.
cleanup() {
    local leftover
    # shellcheck disable=SC2086
    for leftover in $($ISX list -q 2>/dev/null | grep -E "^$INSTANCE(-|\$)"); do
        echo ""
        echo "Destroying $leftover..."
        # shellcheck disable=SC2086
        $ISX destroy "$leftover" --skip-confirmation >/dev/null 2>&1 || \
            echo "Warning: could not destroy $leftover; remove it with: isx destroy $leftover" >&2
    done
    # After the destroys, which go through its wrapper
    [ -n "${COLLECT_WORK:-}" ] && rm -rf "$COLLECT_WORK" || true
}
trap cleanup EXIT

if $COLLECTING; then
    export SCRIPT_DIR FROM INSTANCE ISX COLLECT_DIR COLLECT_WORK
    python3 - <<'PY'
# The workload a PGO profile is collected from, weighted the way the CLI is used: what users
# wait on most often runs most often, since the counts of all runs add up and the mix is the
# weighting. Heavy: the branch, shell and stop/start cycle, the ssh-proxy and git-remote-isx
# relays, isx list. Moderate: the TUI. Once: --help and templates list, so their code is in the
# profile without weight. Left out: doctor, builds, init, accounts, completion (rare, and a
# build's own work is native git, not isx). See docs/dev/PGO.md.
import collections, glob, gzip, os, shutil, subprocess, sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.environ["SCRIPT_DIR"])
import isxbench

env = os.environ
isx = env["ISX"]
source, instance = env["FROM"], env["INSTANCE"]
work, profiles = env["COLLECT_WORK"], env["COLLECT_DIR"]

LIFECYCLES = 6      # branch to a prompt, shell twice, stopped-start shell, destroy
LISTS = 6           # each of table, plain and json
SSH_SHORT = 30      # `ssh <instance> true`: connection setup through the ProxyCommand
SCP_RUNS = 3        # 256 MiB each way: the relay's sustained throughput
GIT_ROUNDS = 4      # clone, commit, push, fetch through git-remote-isx
TUI_RUNS = 4        # open, move, details on an instance and a template, quit
PREPARE = "isx-bench-no-such-action"


def flow_env(flow):
    return dict(env, ISX_BENCH_FLOW=flow, ISX_BENCH_PROFILES=profiles)


def run(flow, cmd, expect=0, marker=None):
    proc = subprocess.run(cmd, env=flow_env(flow), stdin=subprocess.DEVNULL,
                          capture_output=True, text=True)
    out = proc.stdout + proc.stderr
    if proc.returncode != expect or (marker and marker not in out):
        sys.exit(f"\nError: {' '.join(cmd)} exited {proc.returncode} (expected {expect}):\n{out.strip()[-2000:]}")
    return out


def on_pty(flow, fn, *args):
    try:
        return fn(*args, env=flow_env(flow))
    except isxbench.NoPrompt as e:
        sys.exit(f"\nError: {e}")


def guest(script):
    subprocess.run(["incus", "exec", instance, "--", "su", "-l", "agentuser", "-c", script],
                   check=True, stdout=subprocess.DEVNULL)


print(f"Collecting CLI profiles into {profiles} (from {source})")
run("help", [isx, "--help"])
run("templates", [isx, "templates", "list"])

for i in range(LIFECYCLES):
    name = f"{instance}-{i}"
    print(f"  lifecycle {i + 1}/{LIFECYCLES}")
    on_pty("branch", isxbench.run_to_prompt, [isx, "branch", name, "--from", source, "--shell"], 300)
    for _ in range(2):
        on_pty("shell", isxbench.run_to_prompt, [isx, "shell", name], 120)
    run("prepare", [isx, "run", name, "--action", PREPARE], 1, f"action '{PREPARE}' not found")
    # isx has no stop command (the TUI's F7 is one), so stop it underneath and shell into the
    # stopped instance: the start path every first shell of the day takes.
    subprocess.run(["incus", "stop", name], check=True)
    on_pty("shell", isxbench.run_to_prompt, [isx, "shell", name], 180)
    run("destroy", [isx, "destroy", name, "--skip-confirmation"])

print(f"  {instance}: the instance the relays, list and TUI use")
run("branch", [isx, "branch", instance, "--from", source])
for _ in range(LISTS):
    run("list", [isx, "list"])
    run("list", [isx, "list", "--format=plain"])
    run("list", [isx, "list", "--format=json"])

ssh_config = os.path.join(os.path.expanduser("~"), ".config", "incus-spawn", "ssh", "config")
ssh = ["-F", ssh_config, "-o", f"ProxyCommand={isx} ssh-proxy %n", "-o", "LogLevel=ERROR"]
print(f"  ssh-proxy: {SSH_SHORT} short connections, {SCP_RUNS} transfers each way")
for _ in range(SSH_SHORT):
    run("ssh", ["ssh", *ssh, instance, "true"])
big = os.path.join(work, "big.bin")
with open(big, "wb") as f:
    for _ in range(256):
        f.write(os.urandom(1 << 20))
for i in range(SCP_RUNS):
    run("scp", ["scp", "-q", *ssh, big, f"{instance}:/tmp/big.bin"])
    run("scp", ["scp", "-q", *ssh, f"{instance}:/tmp/big.bin", os.path.join(work, "back.bin")])
os.remove(big)

print(f"  git-remote-isx: {GIT_ROUNDS} rounds of clone, push and fetch")
guest("git init -q --bare ~/bench.git")
# The project's own tree as one commit: a realistic size, and none of the history a shallow
# checkout (the release's) could not push.
seed = os.path.join(work, "seed")
project = os.path.dirname(env["SCRIPT_DIR"])
os.makedirs(seed)
archive = subprocess.run(["git", "-C", project, "archive", "HEAD"], check=True, capture_output=True).stdout
subprocess.run(["tar", "-C", seed, "-xf", "-"], input=archive, check=True)
git_id = ["-c", "user.name=isx bench", "-c", "user.email=bench@isx.invalid"]
subprocess.run(["git", "-C", seed, "init", "-q", "-b", "main"], check=True)
subprocess.run(["git", "-C", seed, "add", "-A"], check=True)
subprocess.run(["git", "-C", seed, *git_id, "commit", "-q", "-m", "seed"], check=True)
run("git", ["git", "-C", seed, "push", "-q", f"isx://{instance}/~/bench.git", "HEAD:refs/heads/main"])
for i in range(GIT_ROUNDS):
    clone = os.path.join(work, f"clone-{i}")
    run("git", ["git", "clone", "-q", "-b", "main", f"isx://{instance}/~/bench.git", clone])
    with open(os.path.join(clone, "bench.txt"), "a") as f:
        f.write(f"round {i}\n" * 1000)
    subprocess.run(["git", "-C", clone, "add", "bench.txt"], check=True)
    subprocess.run(["git", "-C", clone, *git_id, "commit", "-q", "-m", f"round {i}"], check=True)
    run("git", ["git", "-C", clone, "push", "-q", "origin", "main"])
    run("git", ["git", "-C", seed, "fetch", "-q", f"isx://{instance}/~/bench.git", "main"])
    shutil.rmtree(clone)
shutil.rmtree(seed)

# Once the instance shows as running: Down, Down, F3 (details), Esc, Tab (the other panel), Down,
# F3, Esc, F10 (quit). The name is cut to the column's width, so the screen is matched on its state.
TUI_KEYS = (b"\x1b[B", b"\x1b[B", b"\x1bOR", b"\x1b", b"\t", b"\x1b[B", b"\x1bOR", b"\x1b", b"\x1b[21~")
print(f"  TUI: {TUI_RUNS} sessions")
for _ in range(TUI_RUNS):
    on_pty("tui", isxbench.run_on_pty, [isx], 60,
           lambda out: b"Running" in isxbench.ANSI.sub(b"", out), b"", TUI_KEYS)

run("destroy", [isx, "destroy", instance, "--skip-confirmation"])

dumps = sorted(glob.glob(os.path.join(profiles, "cli-*.iprof")))
if not dumps:
    sys.exit(f"Error: no profiles in {profiles}. Is {isx}'s binary an instrumented build "
             "(--pgo-instrument, -H:+ParseRuntimeOptions)?")
flows = collections.Counter()
for path in dumps:
    with open(path, "rb") as src, gzip.open(path + ".gz", "wb", compresslevel=6) as dst:
        shutil.copyfileobj(src, dst)
    os.remove(path)
    flows[os.path.basename(path).split("-")[1]] += 1
size = sum(os.path.getsize(p + ".gz") for p in dumps)
print(f"\n{len(dumps)} profiles, {size / 1048576:.1f} MiB gzipped: "
      + ", ".join(f"{k} {v}" for k, v in sorted(flows.items())))
PY
    echo "Done."
    exit 0
fi

# The timing, statistics, result file and comparison all live in Python, so each sample is
# measured around the child process alone instead of around a `date` fork per timestamp.
export SCRIPT_DIR PROJECT_DIR RESULTS_DIR LABEL RUNS BRANCH_RUNS FROM INSTANCE GIT_SHA GIT_SUBJECT ISX PROXY_BUILD ISX_CONFIG_DIR
export RUNTIME_SPECS
RUNTIME_SPECS="$(printf '%s\n' "${RUNTIMES[@]}")"

python3 - <<'PY'
import atexit, json, os, shlex, shutil, subprocess, sys, tempfile, time
from datetime import datetime, timezone

sys.dont_write_bytecode = True  # keep bench/ free of __pycache__
sys.path.insert(0, os.environ["SCRIPT_DIR"])
import isxbench

env = os.environ
runs = int(env["RUNS"])
branch_runs = int(env["BRANCH_RUNS"])
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

def run_to_prompt(cmd, timeout_s):
    try:
        return isxbench.run_to_prompt(cmd, timeout_s)
    except isxbench.NoPrompt as e:
        sys.exit(f"\nError: {e}")

# (name, args, expected exit, output marker)
OPERATIONS = [
    ("startup", ["--help"], 0, None),
    ("instances", ["list", "-q"], 0, None),  # the name listing; named for isx instances, which it replaced
    ("accountShow", ["account", "show", instance], 0, None),
    ("prepareRunning", ["run", instance, "--action", PREPARE_ACTION], 1, PREPARE_MARKER),
]

stats, report = isxbench.stats, isxbench.report

print(f"Branching {instance} from {source}...")
lifecycle = {}
lifecycle["branchMs"] = round(run(lifecycle_cmd + ["branch", instance, "--from", source, "--no-start"]), 1)
print("Cold start (first prepare starts the instance)...")
lifecycle["coldPrepareMs"] = round(run(lifecycle_cmd + ["run", instance, "--action", PREPARE_ACTION],
                                       1, PREPARE_MARKER), 1)

# The relays: `isx ssh-proxy` as ssh's ProxyCommand. They need sshd in the instance, and the
# key and host entry that only a branch which starts gets (#1640), so they have an instance of
# their own, and are skipped for a template without sshd.
SSH_INSTANCE = f"{instance}-ssh"
SCP_MIB = 256
SCP_RUNS = 3
# Where isx writes it (Environment.sshConfigFile), which XDG_CONFIG_HOME does not move.
ssh_config = os.path.join(os.path.expanduser("~"), ".config", "incus-spawn", "ssh", "config")


def ssh_opts(base):
    # isx's own host entry for the instance, with the runtime under test as its ProxyCommand.
    # BatchMode: a key that does not work fails at once instead of asking for a password.
    return ["-F", ssh_config, "-o", f"ProxyCommand={shlex.join(base)} ssh-proxy %n",
            "-o", "BatchMode=yes", "-o", "LogLevel=ERROR"]


def ssh_ready(base):
    for _ in range(30):
        if subprocess.run(["ssh", *ssh_opts(base), SSH_INSTANCE, "true"], stdin=subprocess.DEVNULL,
                          capture_output=True).returncode == 0:
            return True
        time.sleep(0.5)
    return False


def has_sshd():
    # Asked of the running throwaway instance where an incus client can (not on macOS); the
    # relay instance is then branched only for a template that can serve it.
    if not shutil.which("incus"):
        return True
    return subprocess.run(["incus", "exec", instance, "--", "test", "-x", "/usr/sbin/sshd"],
                          capture_output=True).returncode == 0


relays = False
if not (shutil.which("ssh") and shutil.which("scp")):
    print("  No ssh or scp on PATH: relays skipped")
elif not has_sshd():
    print(f"  {source} has no sshd (the sshd tool, as tpl-isx-bench has): relays skipped")
else:
    print(f"Branching {SSH_INSTANCE} for the ssh-proxy relay...")
    run(lifecycle_cmd + ["branch", SSH_INSTANCE, "--from", source])
    relays = ssh_ready(lifecycle_cmd)
    if not relays and shutil.which("incus"):
        sys.exit(f"\nError: {SSH_INSTANCE} has sshd but answers no `ssh {SSH_INSTANCE} true` through isx ssh-proxy")
    if not relays:
        # No incus client (macOS) to ask whether the template has sshd: no answer is taken for no sshd.
        print(f"  {SSH_INSTANCE} answers no ssh (the template needs the sshd tool, as tpl-isx-bench has): relays skipped")
        run(lifecycle_cmd + ["destroy", SSH_INSTANCE, "--skip-confirmation"])
scp_dir = tempfile.mkdtemp()
atexit.register(shutil.rmtree, scp_dir, True)
scp_file = os.path.join(scp_dir, "scp.bin")
if relays:
    with open(scp_file, "wb") as f:
        for _ in range(SCP_MIB):
            f.write(os.urandom(1 << 20))

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
        report(op, s)

    # The instance is running by now, so this is the everyday `isx shell` into it.
    run_to_prompt(base + ["shell", instance], 120)
    s = stats([run_to_prompt(base + ["shell", instance], 120) for _ in range(runs)])
    results[name]["shellToPrompt"] = s
    report("shellToPrompt", s)

    # A full branch: copy, start, runtime setup, CA/resolv.conf repair, identity reconcile
    # and the shell attach. --shell keeps a template's default action (which may never print
    # the marker) out of it. Each sample creates an instance, so there are fewer of them.
    samples = []
    for i in range(branch_runs):
        branch = f"{instance}-{name}-{i}"
        samples.append(run_to_prompt(base + ["branch", branch, "--from", source, "--shell"], 300))
        run(lifecycle_cmd + ["destroy", branch, "--skip-confirmation"])
    s = stats(samples)
    results[name]["branchToPrompt"] = s
    report("branchToPrompt", s)

    if relays:
        # A short connection, as a git-over-ssh fetch or an IDE's probe makes: dominated by the
        # ProxyCommand's startup and its exec into the instance.
        ssh = ssh_opts(base)
        s = stats([run(["ssh", *ssh, SSH_INSTANCE, "true"]) for _ in range(runs)])
        results[name]["sshTrue"] = s
        report("sshTrue", s)
        # Sustained throughput, every byte through the CLI's relay loop; the connection's setup
        # (sshTrue) is inside the wall clock, so this understates the loop alone a little.
        samples = [run(["scp", "-q", *ssh, scp_file, f"{SSH_INSTANCE}:/tmp/scp.bin"]) for _ in range(SCP_RUNS)]
        s = stats(samples)
        s["mibPerSec"] = round(SCP_MIB / (s["medianMs"] / 1000), 1)
        results[name]["scpUpload"] = s
        report("scpUpload", s)
        print(f"  {'':<16} {s['mibPerSec']:>8.1f} MiB/s ({SCP_MIB} MiB, median)")

if relays:
    run(lifecycle_cmd + ["destroy", SSH_INSTANCE, "--skip-confirmation"])
print("\nDestroying (timed)...")
lifecycle["destroyMs"] = round(run(lifecycle_cmd + ["destroy", instance, "--skip-confirmation"]), 1)

os.makedirs(env["RESULTS_DIR"], exist_ok=True)
result = {
    "label": env["LABEL"],
    "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "gitSha": env["GIT_SHA"],
    "gitSubject": env["GIT_SUBJECT"],
    # Differs from the CLI's build only under --allow-drift.
    "proxyBuild": env["PROXY_BUILD"],
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
    for op in results["native"]:
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
    for op in results[name]:
        curr_ms = results[name][op]["medianMs"]
        prev_ms = prev["runtimes"][name].get(op, {}).get("medianMs")
        if not prev_ms:
            print(f"  {op:<16} {curr_ms:>8.1f} ms  (no previous)")
            continue
        pct = (curr_ms - prev_ms) / prev_ms * 100
        # Run-to-run noise on a desktop is several percent, and a few tenths of a millisecond
        # on the fastest operations; flag only what exceeds both.
        significant = abs(curr_ms - prev_ms) >= 1
        flag = " !!!" if pct >= 10 and significant else (
            " (better)" if pct <= -10 and significant else "")
        print(f"  {op:<16} {curr_ms:>8.1f} ms  ({pct:+.1f}%{flag})")
PY

echo ""
echo "Done."
