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
  {"settle": 1.0}                     wait until no output arrives for N seconds

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
    events = []
    t0 = None

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
            os.write(fd, step["send"].encode())
            pump(0.05)
        elif "sleep" in step:
            deadline = time.monotonic() + step["sleep"]
            while time.monotonic() < deadline:
                pump(0.05)
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
                break
        elif "settle" in step:
            quiet = step["settle"]
            last = time.monotonic()
            deadline = time.monotonic() + step.get("timeout", 180)
            while time.monotonic() < deadline:
                if pump(0.1):
                    last = time.monotonic()
                elif time.monotonic() - last >= quiet:
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

ANSI = re.compile(rb"\x1b\[[0-9;?]*[a-zA-Z]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\r")

def clean_tail(tail):
    """Strip ANSI/OSC sequences so wait patterns match visible text.

    Patterns should end-anchor with \\Z to match the *current* prompt,
    not a stale one earlier in the buffer.
    """
    return ANSI.sub(b"", tail)

def arg(name, default):
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    return default

if __name__ == "__main__":
    main()
