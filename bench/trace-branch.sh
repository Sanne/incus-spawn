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
            echo "Runs one 'isx branch --shell' to a usable prompt, then 'isx destroy' on the"
            echo "running instance, while recording the Incus daemon's events. Prints a timeline,"
            echo "the longest gaps and the longest operations for each. Run it on a quiet host:"
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
    time.sleep(0.5)  # let the branch's last events arrive before the destroy starts
    # The destroy of the (running) instance, timed to the moment `isx destroy` exits: time
    # isx spends on the host after Incus has finished shows up as a trailing gap.
    d0 = time.time()
    destroyed = subprocess.run(isx + ["destroy", instance, "--skip-confirmation"],
                               stdin=subprocess.DEVNULL, capture_output=True, text=True)
    d_end = time.time()
    time.sleep(0.5)  # events are delivered asynchronously; catch the last ones
    monitor.terminate()
    monitor.wait()
if destroyed.returncode != 0:
    print(f"Warning: isx destroy exited {destroyed.returncode}:\n{destroyed.stdout}{destroyed.stderr}")
print(f"Prompt after {prompt_ms:.0f} ms, destroy took {(d_end - d0) * 1000:.0f} ms. Raw events: {raw_path}")

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
        # Some messages carry a whole pretty-printed response body (the VM agent's /1.0 dump);
        # its first line says what it is.
        message = (meta.get("message") or "").strip().splitlines() or [""]
        suffix = " [...]" if len(message) > 1 else ""
        return f"{message[0]}{suffix} {compact(context)}".strip()
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
    events.append((ts, event))
events.sort(key=lambda e: e[0])

# ── Report ──────────────────────────────────────────────────────────────────

# An operation's updated_at is stamped just before the event that carries it, hence the slack.
SLACK_S = 0.005

def timeline(title, t0, t_end, mark_ts, mark_label, window):
    """Timeline, longest operations and longest gaps for the events in one command's window;
    gaps count up to mark_ts (the usable prompt, or the command exiting)."""
    lines = [title, "", f"{'t (ms)':>8} {'+Δ (ms)':>8}  {'type':<9} event"]
    mark_ms = (mark_ts - t0) * 1000
    previous_ts, printed_mark, gaps = t0, False, []
    for ts, event in window:
        if not printed_mark and ts > mark_ts:
            lines.append(f"{mark_ms:>8.0f} {'':>8}  {'--':<9} ===== {mark_label} =====")
            printed_mark = True
        delta = (ts - previous_ts) * 1000
        text = describe(event)
        lines.append(f"{(ts - t0) * 1000:>8.0f} {delta:>8.0f}  {event.get('type', '?'):<9} {text}")
        if ts <= mark_ts:
            gaps.append((delta, previous_ts, ts, text))
        previous_ts = ts
    if not printed_mark:
        lines.append(f"{mark_ms:>8.0f} {'':>8}  {'--':<9} ===== {mark_label} =====")
    # The quiet stretch between the last event and the mark is a gap too.
    before_mark = [e for e in window if e[0] <= mark_ts]
    last_ts = before_mark[-1][0] if before_mark else t0
    gaps.append(((mark_ts - last_ts) * 1000, last_ts, mark_ts, f"({mark_label})"))

    # How long each async step (create, start, exec, delete) took on the daemon, from the
    # operation's own events. The end is the timestamp of the event reporting the final
    # status: Incus does not advance updated_at for task operations.
    operations = {}
    for ts, event in window:
        if event.get("type") != "operation":
            continue
        meta = event.get("metadata") or {}
        op = operations.setdefault(meta.get("id", "?"), {"description": meta.get("description", "")})
        try:
            created = parse_ts(meta["created_at"])
        except (KeyError, ValueError):
            created = ts
        op["start"] = min(op.get("start", ts), created, ts)
        if meta.get("status") in ("Success", "Failure", "Cancelled"):
            op["end"] = ts
            op["status"] = meta["status"]
    finished = [(o["end"] - o["start"], o) for o in operations.values() if "end" in o and "start" in o]
    lines += ["", "Longest operations (daemon-side duration):"]
    for duration, op in sorted(finished, key=lambda f: f[0], reverse=True)[:10]:
        lines.append(f"  {duration * 1000:>7.0f} ms  from {(op['start'] - t0) * 1000:>6.0f} ms  "
                     f"{op['description']} ({op['status']})")
    if not finished:
        lines.append("  (no completed operations recorded)")

    # A quiet stretch inside a running operation is Incus or the instance doing the work; one
    # outside every operation is isx itself computing, or waiting between polls.
    def covering(start, end):
        for op in operations.values():
            if (op.get("start", float("inf")) - SLACK_S <= start
                    and op.get("end", float("inf")) + SLACK_S >= end):
                return op["description"]
        return None

    lines += ["", f"Longest gaps before '{mark_label}' (no daemon event in between):"]
    for delta, start, end, text in sorted(gaps, key=lambda g: g[0], reverse=True)[:10]:
        if delta < 1:
            break
        inside = covering(start, end)
        where = f"inside '{inside}'" if inside else "outside any operation"
        lines.append(f"  {delta:>7.0f} ms  {(start - t0) * 1000:>6.0f}-{(end - t0) * 1000:<6.0f} ms  "
                     f"{where:<34} next: {text}")
    return lines

def within(start, end):
    return [e for e in events if start <= e[0] < end]

label = f"({env['RUNTIME']}, {env['GIT_SHA']})"
lines = timeline(f"isx branch {instance} --from {source} --shell {label}: prompt after "
                 f"{prompt_ms:.0f} ms", t0, t_end, t0 + prompt_ms / 1000, "usable prompt",
                 # Ends where the destroy's window starts, so no event is in both. The pause
                 # before the destroy lets the branch's trailing events (its shell exiting) in.
                 within(t0 - 0.05, d0))
destroy_ms = (d_end - d0) * 1000
lines += ["", "=" * 78, ""]
lines += timeline(f"isx destroy {instance} (running) {label}: {destroy_ms:.0f} ms",
                  d0, d_end, d_end, "isx destroy exited", within(d0, d_end + 0.5))

report = "\n".join(lines) + "\n"
with open(timeline_path, "w") as f:
    f.write(report)
print()
print(report)
print(f"Timeline saved to: {timeline_path}")
PY
