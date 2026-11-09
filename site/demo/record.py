#!/usr/bin/env python3
"""PTY-driven asciicast v2 recorder for isx demo casts.

Runs a scenario (JSON list of steps) inside a real PTY running the given
command (default: an interactive shell), simulates human typing with jitter,
and writes an asciinema .cast file capturing genuine program output.

Step kinds:
  {"type": "hello.txt\n"}            type text with per-key jitter (\n sends Enter)
  {"wait": "\\$ $"}                   wait until output tail matches regex (prompt), then continue
  {"sleep": 2.0}                      idle pause (recorded, so keep short; player idle-limit trims)
  {"send": "..."}                     send raw bytes immediately (no typing cadence)
  {"send": "...", "repeat": N, "gap": 0.4}
                                      send it N times, pausing gap seconds between sends; N is a
                                      number or the name of an environment variable holding one
  {"settle": 1.0}                     wait until no output arrives for N seconds
  {"mark": "agent-start"}             write an asciicast marker ("m" event) at this moment;
                                      compress.py --cut-mark cuts between two of them

Usage: record.py scenario.json out.cast [--cols 100] [--rows 28] [--cmd "zsh -f"]
"""
import codecs
import fcntl
import json
import os
import pty
import random
import re
import select
import struct
import sys
import termios
import time

def main():
    scenario_path, out_path = sys.argv[1], sys.argv[2]
    cols = int(arg("--cols", 100))
    rows = int(arg("--rows", 28))
    cmd = arg("--cmd", os.environ.get("SHELL", "/bin/zsh"))

    with open(scenario_path, encoding="utf-8") as f:
        steps = json.load(f)
    # Before the take starts, so a bad count never cuts one short.
    for step in steps:
        if "repeat" in step:
            repeat_count(step["repeat"])
        if "gap" in step:
            gap_seconds(step["gap"])
    events = []
    t0 = None
    timed_out = False

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm-256color"
        os.execvp("/bin/sh", ["/bin/sh", "-c", cmd])

    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    t0 = time.monotonic()
    tail = b""
    # Incremental decoder: a UTF-8 sequence split across reads is buffered
    # until complete instead of decoding to U+FFFD. Genuinely invalid bytes
    # still decode to U+FFFD (errors="replace") rather than aborting.
    decoder = codecs.getincrementaldecoder("utf-8")("replace")

    def pump(timeout=0.05):
        """Drain available output into events; return bytes read."""
        nonlocal tail
        got = b""
        while True:
            r, _, _ = select.select([fd], [], [], timeout)
            if not r:
                break
            try:
                data = os.read(fd, 65536)
            except OSError:
                break
            if not data:
                break
            got += data
            text = decoder.decode(data)
            if text:
                events.append([round(time.monotonic() - t0, 4), "o", text])
            timeout = 0.02
        tail = (tail + got)[-4096:]
        return got

    def idle(seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            pump(0.05)

    def type_text(text):
        for ch in text:
            os.write(fd, ch.encode())
            pump(0.01)
            base = 0.042 if ch != "\n" else 0.12
            time.sleep(base + random.uniform(0.0, 0.058))
            pump(0.01)

    for step in steps:
        if "type" in step:
            type_text(step["type"])
        elif "send" in step:
            for i in range(repeat_count(step.get("repeat", 1))):
                if i:
                    idle(gap_seconds(step.get("gap", 0)))
                os.write(fd, step["send"].encode())
                pump(0.05)
        elif "sleep" in step:
            idle(step["sleep"])
        elif "wait" in step:
            pat = re.compile(step["wait"].encode())
            deadline = time.monotonic() + step.get("timeout", 120)
            while time.monotonic() < deadline:
                pump(0.1)
                if pat.search(clean_tail(tail)):
                    break
            else:
                sys.stderr.write(f"TIMEOUT waiting for {step['wait']!r}\n")
                sys.stderr.write("tail: " + clean_tail(tail)[-500:].decode("utf-8", "replace") + "\n")
                timed_out = True
                break
        elif "mark" in step:
            pump(0.05)
            events.append([round(time.monotonic() - t0, 4), "m", step["mark"]])
        elif "settle" in step:
            quiet = step["settle"]
            last = time.monotonic()
            deadline = time.monotonic() + step.get("timeout", 180)
            while time.monotonic() < deadline:
                if pump(0.1):
                    last = time.monotonic()
                elif time.monotonic() - last >= quiet:
                    break
            else:
                # Still busy at the timeout: a mark after this would land mid-work.
                sys.stderr.write(f"TIMEOUT waiting for {quiet}s of quiet\n")
                sys.stderr.write("tail: " + clean_tail(tail)[-500:].decode("utf-8", "replace") + "\n")
                timed_out = True
                break

    pump(0.3)
    try:
        os.close(fd)
    except OSError:
        pass

    header = {"version": 2, "width": cols, "height": rows,
              "timestamp": int(time.time()),
              "env": {"TERM": "xterm-256color", "SHELL": "/bin/zsh"}}
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(json.dumps(header) + "\n")
        for ev in events:
            f.write(json.dumps(ev, ensure_ascii=False) + "\n")
    dur = events[-1][0] if events else 0
    sys.stderr.write(f"wrote {out_path}: {len(events)} events, {dur:.1f}s\n")
    if timed_out:
        sys.exit(f"{scenario_path}: the take stopped at a timeout; {out_path} is incomplete")

ANSI = re.compile(rb"\x1b\[[0-9;?]*[a-zA-Z]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\r")
# What is drawn between save-cursor and restore-cursor (ESC 7 ... ESC 8) lands elsewhere on the
# screen, not at the cursor: isx shell's status bar is redrawn this way after every prompt.
SAVED_CURSOR = re.compile(rb"\x1b7.*?\x1b8", re.S)

def clean_tail(tail):
    """Strip ANSI/OSC sequences so wait patterns match visible text.

    Patterns should end-anchor with \\Z to match the *current* prompt,
    not a stale one earlier in the buffer.
    """
    return ANSI.sub(b"", SAVED_CURSOR.sub(b"", tail))

def repeat_count(value):
    """A step's repeat: a count, or the name of the environment variable that holds one."""
    if isinstance(value, str):
        if value not in os.environ:
            sys.exit(f"repeat names {value}, which is not set")
        value = os.environ[value]
    elif isinstance(value, bool) or not isinstance(value, int):
        # int() would read true as 1 and cut 2.5 to 2
        sys.exit(f"repeat must be a whole number, not {value!r}")
    try:
        count = int(value)
    except ValueError:
        sys.exit(f"repeat must be a whole number, not {value!r}")
    if count < 0:
        sys.exit(f"repeat must not be negative, not {count}")
    return count

def gap_seconds(value):
    """A step's gap: a number of seconds, not negative."""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
        sys.exit(f"gap must be a number of seconds, not {value!r}")
    return value

def arg(name, default):
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    return default

if __name__ == "__main__":
    main()
