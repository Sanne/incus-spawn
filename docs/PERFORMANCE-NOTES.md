# Performance Notes

Running notes on measured performance, kept as raw material for a future landing page.
Add to it whenever a benchmark produces a number worth quoting. Every figure here must
be reproducible with `bench/run.sh` and carry the conditions it was measured under —
a marketing claim we cannot re-measure on demand is a liability, not an asset.

## Which number describes what

The harness has three load profiles, and they measure genuinely different things. Quoting
one as if it were the other is the easiest way to publish something false.

| Profile | Path exercised | Result |
|---|---|---|
| `--load=constant` | `/health`, plain HTTP | 5,000 req/s tripwire; ~3% of that endpoint's capacity |
| `--load=saturate` | `/health`, plain HTTP | ~166,000 req/s — **HTTP server core only** |
| `--load=maven` | intercepted HTTPS, TLS + cached 642 KB artifact | **~116 req/s, ~73 MB/s** |

**`--load=maven` is the one that describes the product.** It terminates TLS with a minted
per-domain cert, routes by SNI, classifies the domain, hits the artifact cache and streams a
real body back — the work a container build actually causes. The `/health` figures describe
the Vert.x server underneath and must never be quoted as "the proxy's throughput".

## Headline figures

Measured 2026-08-28, Linux x86_64, 32 logical cores, quiet host. Oracle GraalVM 25.3.4.1,
native image, `-O3` + G1 (the release configuration).

| What | Figure | Conditions |
|---|---|---|
| Cached artifact serving | **~73 MB/s** (116 req/s × 642 KB) | 8–64 concurrent, 100% 2xx |
| Cached artifact latency | **~11 ms** single client | 642 KB over TLS, cache hit |
| Cold artifact (upstream) | **~177 ms** | first fetch, network-bound |
| HTTP server core | ~166,000 req/s, 90 µs p50 | `/health`, no TLS, no body |
| Proxy idle memory | **~82 MB RSS** | after 2s settle |
| Proxy startup | **~514 ms** | to first healthy `/health` |
| CLI startup | **~3.8 ms** | median of 20 `isx --help` |
| CLI binary | **30.1 MB** | `-Os`, serial GC |
| Proxy binary | **85.8 MB** | `-O3`, G1 |

The cache-hit story is the strongest honest claim available today: **a 642 KB dependency
served in ~11 ms from local cache instead of ~177 ms from Maven Central** — a 16× latency
improvement on repeat builds, which is what agent containers do constantly.

The proxy is also deliberately confined to **2 Vert.x event loops**
(`quarkus.vertx.event-loops-pool-size=2`, `-R:ActiveProcessorCount=4`), so all of the above
is achieved on two cores of a 32-core box. Efficiency-per-core is the framing, not raw
throughput.

## Known bottleneck: cached artifact throughput caps at ~70 MB/s

Confirmed three independent ways, all flat across concurrency:

- single `curl`: 58 MB/s
- parallel `curl` (OpenSSL), 8 and 32 concurrent: 65 MB/s — **identical at both**
- Hyperfoil, 8/32/64 concurrent: 73 / 72 / 72 MB/s

Throughput stays pinned while latency scales linearly (p50 60 ms at 8 concurrent → 545 ms at
64), which is the signature of a hard serialisation point. For AES-GCM on hardware with
AES-NI this is roughly an order of magnitude low, and the load generator is ruled out (curl
with OpenSSL hits the same wall). The suspect is how `serveCachedFile` streams from disk.

Practical impact: a build pulling 500 MB of cached dependencies spends ~7 s minimum on proxy
egress. Worth its own issue; not a #590 concern.

## GraalVM 25.2 → 25.3 (issue #590)

Same commit, same host, both toolchains from the release builder images.

**On the real path (`--load=maven`): no measurable difference.** Four runs with the order
counterbalanced, 100% 2xx throughout:

| Rung | 25.2 | 25.2b | 25.3 | 25.3b |
|---|---|---|---|---|
| 8 | 116 | 116 | 116 | 116 |
| 32 | 115 | 115 | 114 | 116 |
| 64 | 114 | 114 | 114 | 114 |

Differences of 0–2 req/s, under 1%. Expected, given the ~70 MB/s bottleneck above: the path
is not bound by compiled-code speed, so a better inliner has nothing to bite on.

**On `/health` (`--load=saturate`): 25.3 ahead in 8/8 rungs, +0.6% to +3.8%.** Real for that
path, but it does not transfer to the workload above. Treat as a curiosity.

**Binary size: proxy −1.0%, CLI −2.6%** under 25.3. **Startup: flat** (515 → 514 ms).

**RSS: no signal.** −5.2% in one round, +21% in the next. G1's adaptive sizing under a
10-second burst is not a stable measurement. Do not quote RSS deltas between toolchains.

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
- **Closed-loop latency is a function of concurrency.** p50 at 64 concurrent and p50 at 8
  concurrent describe the same server at the same throughput. Quote the pair, never the
  flattering half.

## Open questions before a landing page

1. **Credential injection overhead** — the Anthropic/OpenAI paths rewrite headers and, for
   Vertex, translate request bodies. Unmeasured, and closer to the product's core claim than
   artifact caching is.
2. **Cache effectiveness in aggregate** — the 16× per-artifact figure is good; bandwidth and
   wall-clock saved across a realistic full build would be better.
3. **Container branch time** — CoW branching is a headline feature; "new environment in N
   seconds" would likely be the most compelling number on the page, and it is not measured.
4. **Comparison baseline** — every figure here is absolute. A landing page needs a reference
   point (a plain forward proxy? no proxy at all?) for any of it to land.
