#!/usr/bin/env python3
"""Post-edit an asciicast: cap idle gaps, cut time ranges, scale speed.

Usage:
  compress.py in.cast out.cast [--max-gap 1.2] [--scale 1.0] [--cut START:END ...]

--max-gap  caps the delay between consecutive events (trims silent waits)
--cut      removes a time range entirely (repeatable; seconds in ORIGINAL time);
           the terminal state still catches up because output events are kept
           unless --drop-cut is given (use --cut for fast-forward, not removal)
--cut-mark START:END[:BACK]
           like --cut, between the markers record.py's "mark" steps wrote,
           ending BACK seconds before END (default 0); stays put across retakes
--pad-end  holds the last frame for N more seconds
--drop-cut with --cut, drop the events instead of fast-forwarding them
--scale    divides all resulting durations (1.25 = 25% faster)
"""
import json, sys

def main():
    argv = sys.argv[1:]
    src, dst = argv[0], argv[1]
    max_gap = float(val(argv, "--max-gap", "1.2"))
    scale = float(val(argv, "--scale", "1.0"))
    pad_end = float(val(argv, "--pad-end", "0"))
    drop = "--drop-cut" in argv
    cuts = []
    mark_cuts = []
    i = 0
    while i < len(argv):
        if argv[i] == "--cut":
            a, b = argv[i + 1].split(":")
            cuts.append((float(a), float(b)))
            i += 2
        elif argv[i] == "--cut-mark":
            mark_cuts.append(argv[i + 1].split(":"))
            i += 2
        else:
            i += 1

    with open(src, encoding="utf-8") as f:
        lines = f.readlines()
    header = lines[0]
    evs = [json.loads(l) for l in lines[1:]]
    marks = {data: t for t, kind, data in evs if kind == "m"}
    for spec in mark_cuts:
        missing = [m for m in spec[:2] if m not in marks]
        if missing:
            sys.exit(f"--cut-mark {':'.join(spec)}: {src} has no marker {', '.join(map(repr, missing))}"
                     f" (it has: {', '.join(map(repr, marks)) or 'none'})")
        start, end = marks[spec[0]], marks[spec[1]] - (float(spec[2]) if len(spec) > 2 else 0)
        if end <= start:
            # A quick take leaves less than BACK seconds between the markers: keep it whole.
            sys.stderr.write(f"--cut-mark {':'.join(spec)}: nothing to cut ({start:.1f}s..{end:.1f}s), kept as is\n")
            continue
        cuts.append((start, end))
    # Markers only steer the cuts; the player would show them as chapters.
    evs = [e for e in evs if e[1] != "m"]

    out = []
    t_out = 0.0
    prev = 0.0
    for t, kind, data in evs:
        in_cut = any(a <= t < b for a, b in cuts)
        gap = t - prev
        prev = t
        if in_cut:
            if drop:
                continue
            gap = 0.001  # fast-forward: keep output, collapse time
        t_out += min(gap, max_gap) / scale
        out.append([round(t_out, 4), kind, data])

    if pad_end > 0 and out:
        out.append([round(out[-1][0] + pad_end, 4), "o", "\u001b[0m"])
    with open(dst, "w", encoding="utf-8") as f:
        f.write(header)
        for ev in out:
            f.write(json.dumps(ev, ensure_ascii=False) + "\n")
    sys.stderr.write(f"{src}: {evs[-1][0]:.1f}s -> {dst}: {out[-1][0]:.1f}s\n")

def val(argv, name, default):
    return argv[argv.index(name) + 1] if name in argv else default

if __name__ == "__main__":
    main()
