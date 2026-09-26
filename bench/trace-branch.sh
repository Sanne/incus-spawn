#!/bin/bash
# bench/trace-branch.sh — Where the time goes in one `isx branch`, as seen by the Incus daemon
#
# Records the daemon's event stream (`incus monitor`) while one `isx branch --shell` runs to a
# usable prompt, then prints a timeline: every API request, operation and command the daemon
# saw, with the time since the branch started and since the previous event, followed by the
# longest gaps and the longest operations. A long operation is Incus or the instance doing the
# work (an exec, a start); a long gap with no event is isx itself working or waiting between
# polls. Nothing is added to isx: this observes it from outside.
#
# Requires: the `incus` client with access to the daemon isx uses (Linux host), a working isx
#           setup with the proxy running, a built template, python3, and an isx build in
#           cli/target (run bench/cli.sh or mvn package first).
#
# Usage:
#   bench/trace-branch.sh                       # native build, from tpl-minimal
#   bench/trace-branch.sh --from=tpl-java --runtime=jvm
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results/trace"

RUNTIME="native"
FROM="tpl-minimal"

while [ $# -gt 0 ]; do
    case "$1" in
        --runtime=*) RUNTIME="${1#--runtime=}" ;;
        --runtime) shift; RUNTIME="${1:-}" ;;
        --from=*) FROM="${1#--from=}" ;;
        --from) shift; FROM="${1:-}" ;;
        --help|-h)
            echo "Usage: bench/trace-branch.sh [--runtime=native|jvm] [--from=TEMPLATE]"
            echo ""
            echo "Runs one 'isx branch --shell' to a usable prompt while recording the Incus"
            echo "daemon's events, then prints a timeline, the longest gaps and the longest"
            echo "operations. The instance is destroyed afterwards. Run it on a quiet host:"
            echo "the daemon's events include anything else happening on it."
            echo ""
            echo "Options:"
            echo "  --runtime=MODE   native (default) or jvm; uses the build already in cli/target"
            echo "  --from=TEMPLATE  Template to branch from (default tpl-minimal)"
            exit 0
            ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "Error: $*" >&2; exit 1; }

command -v python3 &>/dev/null || die "python3 not found on PATH"
command -v incus &>/dev/null || die "the incus client is not on PATH; it is needed to record the daemon's events"
incus info >/dev/null 2>&1 || die "'incus info' failed: this user cannot reach the Incus daemon"

case "$RUNTIME" in
    native)
        # shellcheck disable=SC2012  # -t ordering is the point; names have no spaces
        ISX="$(ls -t "$PROJECT_DIR"/cli/target/incus-spawn-*-runner 2>/dev/null | head -1 || true)"
        [ -n "$ISX" ] || die "No native CLI in cli/target/. Build it first (bench/cli.sh, or mvn package -Dnative -pl cli -am)."
        ;;
    jvm)
        JAR="$PROJECT_DIR/cli/target/quarkus-app/quarkus-run.jar"
        [ -f "$JAR" ] || die "No JVM CLI at $JAR. Build it first (mvn package -DskipTests -pl cli -am)."
        ISX="java -jar $JAR"
        ;;
    *) die "Unknown --runtime '$RUNTIME' (expected: native, jvm)" ;;
esac

INSTANCE="isx-trace-$$"
cleanup() {
    # shellcheck disable=SC2086  # $ISX is "java -jar <path>" for the JVM
    if $ISX instances 2>/dev/null | grep -qx "$INSTANCE"; then
        echo "Destroying $INSTANCE..."
        # shellcheck disable=SC2086
        $ISX destroy "$INSTANCE" --skip-confirmation >/dev/null 2>&1 || \
            echo "Warning: could not destroy $INSTANCE; remove it with: isx destroy $INSTANCE" >&2
    fi
}
trap cleanup EXIT

GIT_SHA="$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
export SCRIPT_DIR RESULTS_DIR ISX INSTANCE FROM GIT_SHA RUNTIME

python3 - <<'PY'
import json, os, re, shlex, subprocess, sys, time
from datetime import datetime

sys.dont_write_bytecode = True  # keep bench/ free of __pycache__
sys.path.insert(0, os.environ["SCRIPT_DIR"])
import isxbench

env = os.environ
isx = shlex.split(env["ISX"])
instance, source = env["INSTANCE"], env["FROM"]
os.makedirs(env["RESULTS_DIR"], exist_ok=True)
stem = os.path.join(env["RESULTS_DIR"],
                    f"{env['GIT_SHA']}-{env['RUNTIME']}-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
raw_path, timeline_path = stem + ".events.json", stem + ".timeline.txt"

# ── Record ──────────────────────────────────────────────────────────────────

print(f"Recording Incus events while branching {instance} from {source} ({env['RUNTIME']})...")
with open(raw_path, "wb") as raw:
    monitor = subprocess.Popen(["incus", "monitor", "--format=json"], stdout=raw,
                               stderr=subprocess.DEVNULL)
    time.sleep(1.0)  # let the monitor subscribe before anything happens
    if monitor.poll() is not None:
        sys.exit("Error: 'incus monitor' exited immediately; cannot record events")
    t0 = time.time()
    try:
        prompt_ms = isxbench.run_to_prompt(isx + ["branch", instance, "--from", source, "--shell"], 300)
    except isxbench.NoPrompt as e:
        monitor.terminate()
        sys.exit(f"\nError: {e}\nRaw events so far: {raw_path}")
    t_end = time.time()
    time.sleep(0.5)  # events are delivered asynchronously; catch the last ones
    monitor.terminate()
    monitor.wait()
print(f"Prompt after {prompt_ms:.0f} ms. Raw events: {raw_path}")

# ── Parse ───────────────────────────────────────────────────────────────────

def parse_ts(text):
    # RFC 3339 with up to nanoseconds; fromisoformat takes at most microseconds.
    text = re.sub(r"(\.\d{6})\d+", r"\1", text.replace("Z", "+00:00"))
    return datetime.fromisoformat(text).timestamp()

def load_events(path):
    # One JSON document per event, whether the client writes them compact or indented.
    text, decoder, events, i = open(path).read(), json.JSONDecoder(), [], 0
    while True:
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text):
            return events
        try:
            event, i = decoder.raw_decode(text, i)
        except ValueError:
            return events  # a truncated last event when the monitor was stopped
        events.append(event)

def compact(context, limit=160):
    parts = []
    for key, value in (context or {}).items():
        if isinstance(value, (dict, list)):
            value = json.dumps(value, separators=(",", ":"))
        parts.append(f"{key}={value}")
    text = " ".join(parts)
    return text if len(text) <= limit else text[:limit - 3] + "..."

def describe(event):
    kind, meta = event.get("type", "?"), event.get("metadata") or {}
    if kind == "logging":
        context = meta.get("context") or {}
        if "url" in context:
            return f"{context.get('method', '')} {context['url']}"
        return f"{meta.get('message', '')} {compact(context)}".strip()
    if kind == "operation":
        return f"op {meta.get('id', '')[:8]} {meta.get('description', '')}: {meta.get('status', '')}"
    if kind == "lifecycle":
        return f"{meta.get('action', '')} {meta.get('source', '')}"
    return compact(meta)

events = []
for event in load_events(raw_path):
    try:
        ts = parse_ts(event["timestamp"])
    except (KeyError, ValueError):
        continue
    if t0 - 0.05 <= ts <= t_end + 0.5:
        events.append((ts, event))
events.sort(key=lambda e: e[0])

# ── Report ──────────────────────────────────────────────────────────────────

prompt_ts = t0 + prompt_ms / 1000
lines = [f"isx branch {instance} --from {source} --shell ({env['RUNTIME']}, {env['GIT_SHA']}): "
         f"prompt after {prompt_ms:.0f} ms, {len(events)} events", "",
         f"{'t (ms)':>8} {'+Δ (ms)':>8}  {'type':<9} event"]
previous_ts, printed_prompt = t0, False
gaps = []
for ts, event in events:
    if not printed_prompt and ts > prompt_ts:
        lines.append(f"{prompt_ms:>8.0f} {'':>8}  {'--':<9} ===== usable prompt =====")
        printed_prompt = True
    delta = (ts - previous_ts) * 1000
    text = describe(event)
    lines.append(f"{(ts - t0) * 1000:>8.0f} {delta:>8.0f}  {event.get('type', '?'):<9} {text}")
    if ts <= prompt_ts:
        gaps.append((delta, previous_ts, ts, text))
    previous_ts = ts
if not printed_prompt:
    lines.append(f"{prompt_ms:>8.0f} {'':>8}  {'--':<9} ===== usable prompt =====")
# The quiet stretch between the last event and the prompt is a gap too.
before_prompt = [e for e in events if e[0] <= prompt_ts]
if before_prompt:
    gaps.append(((prompt_ts - before_prompt[-1][0]) * 1000, before_prompt[-1][0], prompt_ts,
                 "(usable prompt)"))

# Operations carry their own created/updated timestamps: the daemon's view of how long each
# async step (start, exec, file push) took.
operations = {}
for ts, event in events:
    if event.get("type") != "operation":
        continue
    meta = event.get("metadata") or {}
    op = operations.setdefault(meta.get("id", "?"), {"description": meta.get("description", "")})
    try:
        op["start"] = parse_ts(meta["created_at"])
        if meta.get("status") in ("Success", "Failure", "Cancelled"):
            op["end"] = parse_ts(meta["updated_at"])
            op["status"] = meta["status"]
    except (KeyError, ValueError):
        pass
finished = [(o["end"] - o["start"], o) for o in operations.values() if "end" in o and "start" in o]
lines += ["", "Longest operations (daemon-side duration):"]
for duration, op in sorted(finished, key=lambda f: f[0], reverse=True)[:10]:
    lines.append(f"  {duration * 1000:>7.0f} ms  from {(op['start'] - t0) * 1000:>6.0f} ms  "
                 f"{op['description']} ({op['status']})")
if not finished:
    lines.append("  (no completed operations recorded)")

# A quiet stretch inside a running operation is Incus or the instance doing the work; one
# outside every operation is isx itself computing, or waiting between polls.
# An operation's updated_at is stamped just before the event that carries it, hence the slack.
SLACK_S = 0.005
def covering(start, end):
    for op in operations.values():
        if (op.get("start", float("inf")) - SLACK_S <= start
                and op.get("end", float("inf")) + SLACK_S >= end):
            return op["description"]
    return None

lines += ["", "Longest gaps before the prompt (no daemon event in between):"]
for delta, start, end, text in sorted(gaps, key=lambda g: g[0], reverse=True)[:10]:
    if delta < 1:
        break
    inside = covering(start, end)
    where = f"inside '{inside}'" if inside else "outside any operation"
    lines.append(f"  {delta:>7.0f} ms  {(start - t0) * 1000:>6.0f}-{(end - t0) * 1000:<6.0f} ms  "
                 f"{where:<34} next: {text}")

report = "\n".join(lines) + "\n"
with open(timeline_path, "w") as f:
    f.write(report)
print()
print(report)
print(f"Timeline saved to: {timeline_path}")
PY
