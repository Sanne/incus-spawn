# Performance Notes

Running notes on measured performance, kept as raw material for a future landing page.
Add to it whenever a benchmark produces a number worth quoting. Every figure here must
be reproducible with `bench/run.sh` and carry the conditions it was measured under —
a marketing claim we cannot re-measure on demand is a liability, not an asset.

## Read this before quoting any number below

**The load test hits `/health` over plain HTTP on port 18080. It does not exercise TLS
termination, certificate minting, credential injection, or upstream forwarding.** What it
characterises is the proxy's Vert.x HTTP server path — the floor under everything else, not
the MITM work itself. "The proxy sustains 166k req/s" is therefore true of the server
core and *not* a statement about intercepted HTTPS throughput, which nobody has measured
yet. See "Open questions" below.

Latency figures are also load-dependent by construction: a closed-loop benchmark trades
latency for concurrency, so p50 at 256 concurrent (1.5 ms) and p50 at 16 concurrent (90 µs)
describe the same server at the same throughput. Quote the pair, never the flattering half.

## Headline figures

Measured 2026-08-28 on commit `fbb10d9`, Linux x86_64, 32 logical cores, quiet host.
Oracle GraalVM 25.3.4.1, native image, `-O3` + G1 (the release configuration).

| What | Figure | Conditions |
|---|---|---|
| Proxy HTTP capacity | **~166,000 req/s** | closed-loop, 16–256 concurrent, 100% 2xx |
| Latency at capacity | **90 µs p50**, 184 µs p99 | 16 concurrent, saturated |
| Latency at realistic load | **15 µs p50**, 33 µs p99 | 5,000 req/s (3% of capacity) |
| Proxy idle memory | **~82 MB RSS** | after 2s settle |
| Proxy startup | **~514 ms** | to first healthy `/health` |
| CLI startup | **~3.8 ms** | median of 20 `isx --help` |
| CLI binary | **30.1 MB** | `-Os`, serial GC |
| Proxy binary | **85.8 MB** | `-O3`, G1 |

The capacity number is worth understanding before it gets used: the proxy is deliberately
confined to **2 Vert.x event loops** (`quarkus.vertx.event-loops-pool-size=2`,
`-R:ActiveProcessorCount=4`). It reaches ~166k req/s on two event loops while the other 30
cores on the box run the load generator. The honest framing is efficiency-per-core, not raw
throughput — it is a background service for a handful of agent containers that costs almost
nothing to keep running.

## Behaviour above capacity

Driven open-loop at 200,000 req/s (25.3 proxy):

- delivered 168,360 req/s — the ceiling, reproduced by a second, independent load model
- **100% 2xx** — nothing failed
- p50 collapsed to 21.5 ms, p99 to 167.8 ms, max 220.2 ms

The proxy degrades by queueing, not by shedding. Latency is the early-warning signal;
errors never appear. Useful for an honest "what happens under overload" section.

## GraalVM 25.2 → 25.3 (issue #590)

Same commit, same host, both toolchains from the release builder images.

- **Throughput: 25.3 ahead in 8/8 rungs across two counterbalanced rounds, +0.6% to +3.8%.**
  Direction is credible; magnitude is soft. Roughly +1–2%.
- **Binary size: proxy −1.0%, CLI −2.6%** under 25.3.
- **Startup: flat** (515 ms → 514 ms).
- **RSS: no signal.** −5.2% in round 1, +21% in round 2. G1's adaptive sizing under a
  10-second burst is not a stable measurement. Do not quote RSS deltas between toolchains.

Not a marketing story on its own — a ~1–2% throughput drift is invisible next to the
efficiency-per-core framing above. Recorded so the next upgrade has a baseline.

## CLI `-Os` vs `-O3` (GraalVM 25.3)

Interleaved A/B/A/B, 60 pairs, so drift hits both variants equally:

| | `-Os` | `-O3` |
|---|---|---|
| Binary | 31,525,880 B | 72,682,488 B (**+131%**) |
| Startup median | 3,952 µs | 3,736 µs (**−5.5%**) |

`-O3` buys ~216 µs of startup for +41 MB. Verdict: **keep `-Os`.** A CLI invocation cannot
perceive 216 µs, and 41 MB is a real download and disk cost for users.

## Measurement caveats worth remembering

- **Native builds are not byte-reproducible here.** The same commit and toolchain produced
  proxy binaries 65,536 bytes apart (the `git-commit-id` plugin bakes build metadata into
  the image heap). Treat size deltas below ~100 KB as noise.
- **Startup drifts ~8% between sessions.** Cross-session startup comparisons are worthless;
  only interleaved A/B within one session is trustworthy.
- **Single-run RSS and tail latency are noise.** Anything under ~5% needs repetition with
  the run order counterbalanced.

## Open questions before a landing page

1. **Throughput through the actual MITM path** — TLS termination + credential injection +
   upstream forward. This is the number a landing page would really want, and it does not
   exist yet. It needs a benchmark with an upstream stub, not `/health`.
2. **Cache effectiveness** — the OCI blob / Maven artifact / npm tarball caches are a
   strong story (bandwidth and latency saved on repeat container builds) and are entirely
   unmeasured.
3. **Container branch time** — CoW branching is a headline feature; "new environment in
   N seconds" would be the most compelling figure on the page, and it is not measured here.
4. **Comparison baseline** — every figure above is absolute. A landing page needs a
   reference point (a plain HTTPS forward proxy? no proxy at all?) for any of it to land.
