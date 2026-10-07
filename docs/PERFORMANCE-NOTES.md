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
| `--load=maven` | intercepted HTTPS, TLS + cached 642 KB artifact | **~1,530 req/s, ~960 MB/s** |

**`--load=maven` is the one that describes the product.** It terminates TLS with a minted
per-domain cert, routes by SNI, classifies the domain, hits the artifact cache and streams a
real body back — the work a container build actually causes. The `/health` figures describe
the Vert.x server underneath and must never be quoted as "the proxy's throughput".

## Headline figures

Measured 2026-08-28, Linux x86_64, 32 logical cores, quiet host. Oracle GraalVM 25.3.4.1,
native image, `-O3` + G1 (the release configuration).

| What | Figure | Conditions |
|---|---|---|
| Cached artifact serving | **~960 MB/s** (1,530 req/s × 642 KB) | 8–64 concurrent, 100% 2xx |
| Cached artifact latency | **~2.8 ms** single client | 642 KB over TLS, cache hit (2.0 ms of it handshake) |
| Cold artifact (upstream) | **~177 ms** | first fetch, network-bound |
| HTTP server core | ~166,000 req/s, 90 µs p50 | `/health`, no TLS, no body |
| Proxy idle memory | **~82 MB RSS** | after 2s settle |
| Proxy startup | **~514 ms** | to first healthy `/health` |
| CLI startup | **~3.8 ms** | median of 20 `isx --help` |
| CLI binary | **30.1 MB** | `-Os`, serial GC |
| Proxy binary | **85.8 MB** | `-O3`, G1 |

The cache-hit story is the strongest honest claim available today: **a 642 KB dependency
served in ~2.8 ms from local cache instead of ~177 ms from Maven Central** — a ~60× latency
improvement on repeat builds, which is what agent containers do constantly. Note ~2.0 ms of
that 2.8 ms is the TLS handshake, so a client reusing its connection does better still.

The proxy is also deliberately confined to **2 Vert.x event loops**
(`quarkus.vertx.event-loops-pool-size=2`, `-R:ActiveProcessorCount=4`), so all of the above
is achieved on two cores of a 32-core box. Efficiency-per-core is the framing, not raw
throughput.

## Resolved: the ~70 MB/s ceiling was software AES

Earlier notes recorded cached artifact serving as capping at ~70 MB/s regardless of
concurrency, and guessed at `serveCachedFile`'s disk streaming. That was wrong. The native
image was built with GraalVM's default `-march=x86-64-v3`, which excludes AES and CLMUL, so
TLS bulk encryption ran as software AES. Building with `-march=haswell` took the same code
from 73 MB/s to 960 MB/s — see DESIGN.md "Native image CPU baseline".

A profile of the fixed build shows nothing left to chase on this path: the single event loop
is idle more than half the time while serving over 1 GB/s, and its working time is socket
writes plus TLS encryption, both irreducible. The blocking pool is idle during serving
entirely (its only samples are one-time keystore construction at startup).

## GraalVM 25.2 → 25.3 (issue #590)

Same commit, same host, both toolchains from the release builder images.

**On the real path (`--load=maven`): no measurable difference.** Four runs with the order
counterbalanced, 100% 2xx throughout. Note these were taken *before* the `-march` fix, so
software AES was saturating the event loop throughout — which is itself why there was nothing
for a better inliner to improve. Worth re-running on a build where crypto is not the
bottleneck:

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

## G1 on Apple Silicon (issue #1140)

Measured 2026-10-07 on an Apple Silicon Mac (Apple M6, 12 cores, 16 GB, macOS 27.0), quiet
host. Oracle GraalVM 25.4.4.1.1, native image, `-O3`, same commit for every build. Three
rounds per comparison, the builds interleaved and the order rotated each round. Load from
Hyperfoil 0.29.3 running on the host (`bench/run.sh --hyperfoil=DIR`), 100% 2xx throughout.

**Verdict: keep serial GC on macOS.** G1 gives no throughput on either path and costs binary
size and memory.

| | serial, `MaxRAM=256m` (shipped) | G1, `MaxRAM=256m` | G1, `MaxRAM=512m` |
|---|---|---|---|
| Proxy binary | 75,665,944 B | 84,657,624 B (**+11.9%**) | 85,780,552 B |
| Maximum heap | 80% = 205 MB | 25% = 64 MB | 25% = 128 MB |
| Startup | 297 to 315 ms | 300 to 311 ms | 303 to 304 ms |
| Idle RSS | 42.6 MB | 48.0 MB (**+13%**) | 47.9 MB |
| `--load=maven`, best rung, 3 rounds | 118 / 118 / 118 req/s | 118 / 118 / 117 req/s | not run |
| `--load=maven`, p50 / p99 at 8 concurrent | 59 / 120 ms | 60 / 119 ms | not run |
| RSS after `--load=maven` | 71.2 MB | 83.2 MB | not run |
| `--load=saturate`, best rung, 3 rounds | 53,765 / 53,684 / 54,286 | 53,644 / 53,436 / 53,934 | 53,864 / 53,803 / 53,666 |
| `--load=saturate`, mean | 53,911 req/s | 53,672 req/s (−0.4%) | 53,778 req/s (−0.2%) |
| `--load=saturate`, p50 / p99 / p99.9 | 275 / 560 / 661 µs | 275 / 564 / 676 µs | 274 / 562 / 668 µs |
| RSS after `--load=saturate` | 61.7 MB | 68.0 MB | 69.6 MB |

Reading it:

- **Throughput is the same.** The largest gap between collectors is 0.4%, well under the
  spread between rounds of one build (1.1% for serial on `saturate`).
- **A 64 MB G1 heap is not short of room here.** Doubling `MaxRAM` to 512m moved neither
  throughput nor tail latency, so there is no case for raising it on any platform.
- **Startup is flat**, to the resolution this harness has for it (it polls `/health` every
  250 ms).
- **G1 on macOS needs Oracle GraalVM.** GraalVM CE 25.4 rejects `--gc=G1` there ("Accepted
  values are 'epsilon', 'serial'"), and Oracle GraalVM 25.0, the LTS line, rejects it as
  "only supported on Linux". Only the 25.1+ Oracle builds accept it.

Both `saturate` and `maven` are flat across the whole concurrency ladder on this machine
(`saturate`: 53,900 at 16 users and 53,700 at 256), so each sits at a ceiling that more
clients do not move. For `maven` that ceiling is known, and it is not the collector:

### The `maven` path on aarch64 is bound by software AES

118 req/s is 74 MB/s, the figure the x86_64 build had before `-march=haswell` (see "Resolved:
the ~70 MB/s ceiling was software AES"). The cause is the same. On aarch64 the proxy passes
no `-march`, GraalVM's default there is `armv8-a` or `armv8.1-a`, and `native-image
-march=list` shows neither includes AES or PMULL. The same commit built with
`-march=armv8.1-a+aes`, three interleaved rounds again:

| | default `-march` | `-march=armv8.1-a+aes` |
|---|---|---|
| serial, `--load=maven` | 118 req/s, 74 MB/s | 998 / 1,000 / 1,002 req/s, **627 MB/s** |
| G1, `--load=maven` | 118 req/s, 74 MB/s | 1,000 / 1,000 / 1,004 req/s, 628 MB/s |
| p50 / p99 at 32 concurrent | 261 / 521 ms | 31 / 60 ms |
| Binary (serial) | 75,665,944 B | 75,682,472 B |

That is **8.5x**, and it applies to every aarch64 build, Linux arm64 included (not measured
there). It is not changed here: which aarch64 CPUs a `+aes` build would stop running on is a
decision of its own. With AES the two collectors are still level (1,000 against 1,001 req/s),
so the verdict above does not depend on it; RSS after load is 95 MB for serial and 106 to
124 MB for G1.

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
- **Do not measure with a shell loop of `curl`.** One process spawn per request caps such a
  loop near 550 req/s. Against a slow server it agrees with a real load generator, which
  makes it look trustworthy; against a fast one it silently pins every build to the same
  number and hides the differences between them. Every figure on this page that was once
  wrong was wrong for this reason. Use `bench/run.sh`.
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
