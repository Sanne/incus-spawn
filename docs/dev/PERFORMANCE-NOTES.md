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
from 73 MB/s to 960 MB/s — see design/native-image.md "Native image CPU baseline".

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

## Profile-guided optimization (issue #1637)

The plan is [PGO.md](PGO.md).
Outcomes of each phase are recorded here as it runs.

### Phase 0, finished 2026-10-24

Linux x86_64 VM, 8 cores (AMD Ryzen 9 9950X3D2 host), Oracle GraalVM 25.4.4.1.1, commit `77c5f656`.

| What | Result |
|---|---|
| Release builds today | proxy 99,290,400 B (`-O3`), CLI 37,948,488 B (`-Os`) |
| Instrumented builds | proxy 148 MB in 91 s, CLI 132 MB in 94 s |
| Proxy stopped with SIGTERM | dumps its profile (12 MB), as a clean exit does |
| CLI profile, one process | 6.3 MB, 1.2 MB gzipped |

The sizes the plan quotes (86 MB, 30 MB) predate this commit; today's are above.

One thing the plan did not foresee: release builds pass `-H:-ParseRuntimeOptions`, so an
instrumented binary ignores `-XX:ProfilesDumpFile` (the CLI even takes it for an argument) and
every process writes `default.iprof` in its working directory, over the previous one. The
instrumented builds therefore append `-H:+ParseRuntimeOptions`
(`-Dquarkus.native.additional-build-args-append`, which lands after the property list). The
optimized build keeps the release's `-H:-ParseRuntimeOptions`.

### Phase 1, workloads (2026-10-24)

`bench/pgo-collect.sh` runs them (bench/README.md, "PGO Profile Collection"): about six
minutes on the host above, 5 proxy profiles (11 MB gzipped) and 111 CLI profiles (141 MB).
The credential paths now have loads of their own, `--load=anthropic` and `--load=vertex`,
which answers open question 1 below once phase 2 measures them.

A first look at what the profiles do, one optimized build each from that collection
(`--pgo` over every file of a side, `-O3`), single runs, so indicative only until phase 2's
interleaved A/B:

| | Release build | PGO build |
|---|---|---|
| Proxy binary | 99,290,400 B | 42,077,472 B |
| Proxy profile applicability / relevance | | 20.01% / 97.69% |
| Proxy idle RSS, `--load=maven` | 60,340 KB | 35,964 KB |
| Proxy peak RSS, `--load=maven` | 119,612 KB | 79,700 KB |
| Proxy `--load=maven`, c008 | 290 req/s | 343 req/s |
| Proxy `--load=anthropic`, c004 | 2,889 req/s | 4,050 req/s |
| Proxy `--load=vertex`, c004 | 1,596 req/s | 2,682 req/s |
| CLI binary | 37,948,488 B (`-Os`) | 38,603,848 B (`-O3` + PGO) |
| CLI profile applicability / relevance | | 15.18% / 99.14% |
| CLI `--help`, median of 60 interleaved | 3,682 µs | 2,921 µs |

Low applicability is the point, not a defect: the code no workload reached is compiled for
size, which is where the proxy's 57 MB went. It is also the risk phase 2 has to rule out, since
a path the workloads miss now runs as cold code. The `maven` throughput on this VM is far below
the 32-core figure above for both builds; only the comparison between them means anything here.

### Phase 2, measurement (2026-10-25)

Same host and toolchain as phase 0, builds of commit `77c5f656`, profiles from one
`bench/pgo-collect.sh` run of that commit. Interleaved with `bench/ab.py`, three rounds, the
order reversed every other round; every figure is the median of the three. The raw runs are in
[pgo-ab-2026-10-25-proxy.json](../../bench/data/pgo-ab-2026-10-25-proxy.json) (25 minutes) and
[pgo-ab-2026-10-24-cli.json](../../bench/data/pgo-ab-2026-10-24-cli.json) (4 minutes).
The decision rules are the plan's, fixed before measuring.
Each file's `provenance` (builds' commit, GraalVM and flags, host) was added afterwards from this
page, since `ab.py` did not record it yet; it does now.

**Proxy, `-O3` against `-O3` + PGO.** Rule: adopt if any load gains about 5% with no size, RSS
or startup regression.

| | `-O3` | `-O3` + PGO |
|---|---|---|
| Binary | 99,290,400 B | 42,077,472 B (**−57.6%**) |
| Startup | 261 ms | 262 ms (+0.4%, 1 ms: within noise) |
| Idle RSS (`maven` run) | 60,164 KB | 36,520 KB (**−39.3%**) |
| Peak RSS, `maven` | 132,404 KB | 87,316 KB (−34.1%) |
| Peak RSS, `anthropic` | 106,576 KB | 77,580 KB (−27.2%) |
| `constant`, 5,000 req/s | p50 120 µs | p50 102 µs (−14.9%) |
| `saturate`, best rung | 16,241 req/s | 21,773 req/s (**+34.1%**) |
| `maven`, best rung | 287 req/s | 343 req/s (**+19.4%**) |
| `anthropic`, best rung | 3,007 req/s | 4,171 req/s (**+38.7%**) |
| `vertex`, best rung | 1,732 req/s | 3,172 req/s (**+83.1%**) |

Every load gains, every latency percentile measured falls, and nothing regresses. Verdict:
**adopt PGO for the proxy.** The Vertex path gains most, as the plan expected: its body
rewrite is Java per byte. On this VM `maven` is far below the 32-core headline for both builds
(180 and 215 MB/s, against 960), so its absolute figures describe this host only.

**CLI, `-Os` against `-O3` + PGO and `-Os` + PGO.** Rule: adopt only if startup is at or below
`-Os` and the size stays near it, or the relays gain clearly at a size the download justifies.

| | `-Os` | `-O3` + PGO | `-Os` + PGO |
|---|---|---|---|
| Binary | 37,948,488 B | 38,603,848 B (+1.7%) | 38,145,096 B (+0.5%) |
| `isx --help` | 3.5 ms | 2.9 ms (**−17.1%**) | 3.0 ms (−14.3%) |
| `isx list -q` | 6.7 ms | 6.0 ms (−10.4%) | 6.3 ms (−6.0%) |
| `isx account show` | 11.2 ms | 9.7 ms (−13.4%) | 9.9 ms (−11.6%) |
| `isx run` preparation | 77.9 ms | 74.0 ms (−5.0%) | 75.7 ms (−2.8%) |
| `isx shell` to a prompt | 270 ms | 276 ms (+2.2%) | 274 ms (+1.3%) |
| `isx branch --shell` to a prompt | 2,074 ms | 2,090 ms (+0.8%) | 1,936 ms (−6.7%) |
| `ssh <instance> true` | 155 ms | 153 ms (−1.7%) | 156 ms (+0.2%) |
| `scp` 256 MiB through `ssh-proxy`, setup included | 255 MiB/s | 330 MiB/s (**+29.5%**) | 302 MiB/s (+18.4%) |

The CLI A/B was run again, six rounds, to see whether `isx shell`'s +2.2% was real: in the three
rounds above, `-O3` + PGO sat at or above `-Os`'s median every time (275.8, 275.9, 278.5 ms against
276.6, 270.0, 268.0). Six more rounds, same binaries and host
([pgo-ab-2026-10-25-cli-rerun.json](../../bench/data/pgo-ab-2026-10-25-cli-rerun.json)):

| Six rounds | `-Os` | `-O3` + PGO | `-Os` + PGO |
|---|---|---|---|
| `isx --help` | 3.5 ms | 2.8 ms (**−20.0%**) | 3.0 ms (−14.3%) |
| `isx list -q` / `account show` | 6.9 / 10.9 ms | 6.2 / 9.6 ms | 6.4 / 10.4 ms |
| `isx run` preparation | 78.0 ms | 76.6 ms (−1.9%) | 76.3 ms (−2.2%) |
| `isx shell` to a prompt | 274.1 ms | 270.6 ms (−1.3%) | 277.0 ms (+1.1%) |
| `ssh <instance> true` | 157.7 ms | 156.3 ms (−0.9%) | 156.3 ms (−0.9%) |
| `scp` 256 MiB, setup included | 258 MiB/s | 270 MiB/s (+4.5%) | 282 MiB/s (+9.2%) |

`isx shell` does not regress: over six rounds `-O3` + PGO is below `-Os` (per round 265 to 275 ms
against 262 to 280), and the three-round pattern did not recur. The relay's gain is smaller than
the first run said. `scp` varies widely between runs (161 to 340 MiB/s within one build), and over
all nine rounds the medians are 258 (`-Os`), 293 (`-O3` + PGO, +14%) and 294 MiB/s (`-Os` + PGO,
+14%). `isx branch --shell` to a prompt is bimodal for every build (about 1.2 s or 2.1 s, with no
pattern by build), so it is not compared here.

Verdict: **adopt `-O3` + PGO for the CLI.** By the plan's first rule it starts faster than `-Os`
(by 17 to 20%) at a size near it (+0.66 MB), and nothing measured regresses. `-Os` + PGO saves
0.46 MB of that and relays as fast, but gives back a sixth to a quarter of the startup gain, the
gain every command has.

The proxy A/B's first result file records checkout `fe1d7c7c` and the other 29 `f7ec4070`: a
commit was made while it ran. It added `bench/ab.py` (already running) and `bench/README.md`, and
changed nothing `run.sh` or the binaries use.

Low applicability (20% of the proxy's methods, 15% of the CLI's, have a profile) is what shrinks
the proxy: the rest is compiled for size. A path no workload reaches therefore runs as cold
code. Phase 2 measured the paths the plan names; npm caching, the WebSocket relay, host routes
and the MCP bridge are not among them.

### Phase 3, release wiring (2026-10-25)

`release.yml` collects and applies the profiles (design/native-image.md "Profile-guided optimization").
Its `profiles` job and the Linux amd64 build were first reproduced on the host above, from templates not yet built.
Collection, merge and the two profiled builds took 10.5 minutes.

| Linux amd64, on the host above | v0.4.2 release | PGO build | Profile applicability / relevance |
|---|---|---|---|
| `isx` | 37,882,184 B | 38,603,848 B | 15.19% / 100.00% |
| `isx-proxy` | 99,093,024 B | 41,880,864 B | 20.02% / 100.00% |

Then a `workflow_dispatch` of `release.yml` built all four platforms: run 40186966586 on `Sanne-ai-bot/incus-spawn`, commit `f4fdb36e`, the commit before this note.
Its profiles job took 18 minutes, and each build 6 to 14.
The merged profiles are 1.7 MB (CLI) and 2.5 MB (proxy) gzipped.

| Platform | `isx` (v0.4.2 → PGO) | CLI applicability / relevance | `isx-proxy` (v0.4.2 → PGO) | Proxy applicability / relevance |
|---|---|---|---|---|
| Linux amd64 | 37,882,184 → 38,603,080 B | 15.21% / 100.00% | 99,093,024 → 42,207,776 B | 20.04% / 100.00% |
| Linux aarch64 | 39,389,296 → 40,044,656 B | 15.16% / 99.75% | 89,590,168 → 42,928,536 B | 20.00% / 99.96% |
| macOS aarch64 | 39,526,904 → 40,253,728 B | 15.05% / 98.49% | 79,463,872 → 39,493,272 B | 19.58% / 97.04% |
| macOS x86_64 (unprofiled) | 38,511,200 → 38,638,480 B | | 83,309,152 → 83,555,056 B | |

A profile is fully relevant on the platform that collected it.
Profiles collected on Linux x86_64 fit the other platforms almost as well: relevance is 97% at the lowest, macOS's proxy.
Every profiled platform's proxy is about half its released size; the unprofiled Intel macOS build is unchanged.
The release smoke test passed on every build, and the keychain smoke on both macOS builds.
The floors in `.github/scripts/check-pgo-quality.sh` were 10% applicability and 80% relevance for this dispatch and for v0.5.0-dev.1, and are 12% and 90% since.
That leaves 3 points of applicability and 7 of relevance under the lowest figures seen (15.04% and 97.04%): room for drift between releases, while a profile that did not apply or fit, which reads near zero, still fails.
Two earlier dispatches of this change (runs 40180623569 and 40183611322, before rebases) gave the same figures within 0.02 points.

### The npm and WebSocket paths (issue #1651)

The release profile left two proxy paths as cold code: the npm path (packument relay, version lookup, tarball cache) and the WebSocket relay Codex uses.
`bench/pgo-coverage.py` on that profile (run 40186966586) counted calls into 1 of `ArtifactCacheHandler`'s 29 npm methods and none of `MitmProxy`'s 17 WebSocket methods.
Two loads now drive them: `--load=npm` from the host, `--load=websocket` from `WsLoad.java` inside an instance.
Same host and toolchain as phase 0.

**Weighting matters.**
A collection's profile is weighted by the mix of its runs.
With both loads at full length (35 s each, like the others), an interleaved A/B against the release profile, three rounds, measured:

| Full-length loads | Release profile | With npm and WebSocket |
|---|---|---|
| Proxy binary | 42,143,008 B | 44,436,768 B (+5.4%) |
| Idle RSS (`npm` run) | 35,020 KB | 37,648 KB (+7.5%) |
| `npm`, best rung | 332 req/s | 321 req/s (−3.5%) |
| `websocket`, best rung | 2,434 turns/s | 2,539 turns/s (+4.3%) |
| `maven`, best rung | 348 req/s | 344 req/s (−1.3%) |
| `vertex`, best rung | 3,097 req/s | 2,728 req/s (**−11.9%**) |

The Vertex loss was the same in every round, so the full-length loads outweighed the rest of the profile.
Collected at about a seventh of their length (`proxy-npm.collect.hf.yaml`, the WebSocket load's `collect-phases`), they profile the same methods and leave Vertex alone.
These two A/Bs' raw files were lost with a scratch worktree; their medians come from the runs' printed tables.

**The final change, as a release collects it.**
`workflow_dispatch` run 40215545458 of `release.yml`, commit `7b384046`:

| Platform | Proxy applicability / relevance, before → after | `isx-proxy`, before → after |
|---|---|---|
| Linux amd64 | 20.04% / 100.00% → 21.63% / 100.00% | 42,207,776 → 43,715,104 B |
| Linux aarch64 | 20.00% / 99.96% → 21.60% / 99.96% | 42,928,536 → 44,108,184 B |
| macOS aarch64 | 19.58% / 97.04% → 21.16% / 97.24% | 39,493,272 → 40,731,560 B |
| macOS x86_64 (unprofiled) | | 83,555,056 → 83,542,784 B |

"Before" is run 40186966586, the release profile on `main`.
The CLI's profile and sizes are unchanged: 15.21% / 99.14% at the merge.
In that run's profile, 18 of the 29 npm methods and 12 of the 17 WebSocket methods are counted; those not counted are error, retry and keepalive paths.
`ArtifactCacheHandler` as a whole goes from 31 to 62 of 152 methods, and `MitmProxy` from 104 to 121 of 208.

Interleaved A/B on this host, the two profiles built at that commit, three rounds ([pgo-ab-2026-10-25-npm-ws-final.json](../../bench/data/pgo-ab-2026-10-25-npm-ws-final.json)):

| | Release profile | With npm and WebSocket |
|---|---|---|
| Proxy binary | 41,946,400 B | 43,650,336 B (+4.1%) |
| Idle RSS, across the five loads | 35.4 to 37.2 MB | 36.9 to 39.1 MB |
| `npm`, best rung | 324 req/s | 325 req/s (+0.1%) |
| `websocket`, best rung | 2,574 turns/s | 2,560 turns/s (−0.5%) |
| `maven`, best rung | 343 req/s | 341 req/s (−0.5%) |
| `anthropic`, best rung | 4,082 req/s | 4,143 req/s (+1.5%) |
| `vertex`, best rung | 2,730 req/s | 3,094 req/s (+13.3%) |

The npm and WebSocket paths are now compiled from their profile, but neither got measurably faster on this host.
Like `maven`, they are bound by TLS, the network and the stub, not by the Java PGO compiles.
The cost is about 1.5 MB of proxy in the release build (3.6%), and 0.1 to 2 MB of idle RSS.
Vertex is noisy here (2,441 to 3,099 req/s across the release profile's rounds), but it gained 12% in the lighter-weighted A/B against a profile collected the same way without the two loads, too.

## The `ssh-proxy` relay (issue #1643)

Measured 2026-10-25 on an 8-core Linux VM (AES-NI, AVX2), native `-Os` builds of one commit.
The guest was a Fedora 44 container with sshd, and each transfer copied 256 MiB from and to `/dev/shm`.
CPU is the relay process's and incusd's, sampled from `/proc`; figures are medians of four interleaved rounds.

| 256 MiB `scp` | upload | relay CPU | incusd CPU | download | relay CPU |
|---|---|---|---|---|---|
| Direct to the instance's IP, no relay | ~495 MiB/s | | | ~425 MiB/s | |
| `incus exec … nc localhost 22` (Go client) | 234 MiB/s | 0.30 s | 0.45 s | 342 MiB/s | 0.24 s |
| `isx ssh-proxy`, before | 242 MiB/s | 0.82 s | 0.62 s | 318 MiB/s | 0.34 s |
| `isx ssh-proxy`, after | 375 MiB/s (**+55%**) | 0.31 s | 0.30 s | 332 MiB/s | 0.29 s |

A rerun of the final commit, four more rounds, agreed.
Upload rose from 274 to 422 MiB/s (+54%), relay CPU fell from 0.76 to 0.27 s and incusd's from 0.59 to 0.26 s.
Download measured 351 against 341 MiB/s, within noise.

The upload was bound by isx's own framing, not by encryption.
The relay does no cryptography: ssh encrypts end to end, and the Incus socket is plain.
Stdin went in 4 KiB frames, each written as three unbuffered socket writes, with the payload masked a byte at a time.
That kept the relay near a full core, and the small frames doubled incusd's work too.
Frames of up to 64 KiB, written once and masked a word at a time, cut both by more than half.
Reading frames through a read-ahead, instead of a read per header byte, trims the download side a little.
Download was already at the Go client's rate, bounded by incusd and `nc` in the guest.

What was ruled out:
- The CLI's `-march`: it is `haswell`, like the proxy's, and nothing on this path uses AES.
- ssh's cipher: `aes128-gcm` with AES-NI measured no faster than the default `chacha20-poly1305` (about 240 against 265 MiB/s up).
- The JVM build, for this comparison: its half second of startup is a large share of a transfer of one to three seconds.

The PGO A/B above measured the relay before this change, while it was CPU-bound in isx.
Its relay gain (+14%) was likely that hot path's, so it may not carry over.
Release builds are now `-O3` + PGO (phase 3), and these figures are not theirs.
The change removes work rather than compiling it better, so a similar gain is expected there, but it was not measured.
`ssh <instance> true` is unchanged (113 against 117 ms, twenty runs each).

### First release with profiles, v0.5.0-dev.1 (2026-10-25)

Run 40229855170 on `Sanne/incus-spawn`, commit `c09f6dd9`, the first release built by the merged workflow.
The whole run took 37 minutes: the profiles job 19.5 (instrumented builds 7.3, collection 6.7, merge 4.5), each platform build 6.5 to 9.3 after it, and the release job 7.6.
The `pgo-profiles` artifact is 4.3 MB.

| Platform | `isx` (v0.4.2 → v0.5.0-dev.1) | CLI applicability / relevance | `isx-proxy` (v0.4.2 → v0.5.0-dev.1) | Proxy applicability / relevance |
|---|---|---|---|---|
| Linux amd64 | 37,882,184 → 38,668,616 B | 15.20% / 100.00% | 99,093,024 → 43,321,888 B | 21.24% / 100.00% |
| Linux aarch64 | 39,389,296 → 40,044,656 B | 15.15% / 99.75% | 89,590,168 → 43,911,576 B | 21.20% / 99.96% |
| macOS aarch64 | 39,526,904 → 40,220,800 B | 15.04% / 98.49% | 79,463,872 → 40,567,576 B | 20.77% / 97.23% |
| macOS x86_64 (unprofiled) | 38,511,200 → 38,659,056 B | | 83,309,152 → 84,559,552 B | |

The CLI's figures match every earlier dispatch within 0.05 points.
The proxy's are 1.2 points above the Phase 3 dispatch because the npm and WebSocket loads of #1651 joined the collection in between: against the dispatch that followed them (run 40215545458, 21.63% / 21.60% / 21.16%), the release is about 0.4 points lower per platform.
The lowest figures a floor has to stay below are unchanged: 15.04% applicability (the macOS aarch64 CLI, here and in dispatch run 40183611322; 15.05% in the Phase 3 dispatch) and 97.04% relevance (the macOS aarch64 proxy in the Phase 3 dispatch, run 40186966586); this release's 97.23% is above it.

### The released binaries, v0.4.2 against v0.5.0-dev.1 (2026-10-25)

The goal of the plan is measurably better release artifacts, so the shipped Linux amd64 binaries of both releases were measured against each other, downloaded from their GitHub releases.
Host: a nested isx instance (an LXC container, 32 usable cores of an AMD Ryzen 9 9950X3D2, 51 GB), with Incus brought up by `scripts/local-incus.sh`; the throughput figures are comparable only with each other.
Interleaved A/B with `bench/ab.py` for the proxy, and for the CLI a script of the same shape over `bench/cli.sh`'s operations, since the nested kernel refuses the stock CLI's branches: a measurement-only patched driver built from `main` did the one branch, and the released binaries were timed.
On a host that is not nested, `bench/ab.py cli` reproduces it.
The raw runs are in `bench/data/pgo-released-2026-10-25-*.json`.

**The credential paths cannot be driven on the v0.4.2 proxy** by today's harness: it predates `ISX_BENCH_CONFIG`, so it saw no bench account and answered 401.
Instead an unprofiled `-O3` proxy of the release commit `c09f6dd9` was built on this host with the same Oracle GraalVM 25.4.4.1.1 the release used, and compared with the shipped PGO proxy, three rounds, all five loads ([pgo-released-2026-10-25-proxy-o3-vs-pgo.json](../../bench/data/pgo-released-2026-10-25-proxy-o3-vs-pgo.json)):

| Same commit | `-O3`, no profile | Shipped PGO | Delta |
|---|---|---|---|
| Binary | 101.5 MB | 43.3 MB | −57% |
| Startup | 260 ms | 260 ms | 0 |
| Idle RSS | 62 to 65 MB | 36 to 39 MB | −39 to −44% |
| Peak RSS | 106 to 150 MB | 78 to 104 MB | −23 to −45% |
| `anthropic`, best rung | 7,621 req/s | 9,432 req/s | +24% |
| `vertex`, best rung | 2,511 req/s | 4,750 req/s | +89% |
| `websocket`, best rung | 3,709 turns/s | 4,123 turns/s | +11% (flat at 4 and 16 connections) |
| `maven`, best rung | 1,604 req/s | 1,714 req/s | +7% |
| `npm`, best rung | 1,498 req/s | 1,591 req/s | +6% |

Every round of every load went the same way, and the figures match phase 2's on another host.

**Shipped against shipped**, v0.4.2 against v0.5.0-dev.1, on the loads that need no credential: six rounds over two sessions ([v042-vs-pgo](../../bench/data/pgo-released-2026-10-25-proxy-v042-vs-pgo.json), [rerun](../../bench/data/pgo-released-2026-10-25-proxy-v042-vs-pgo-rerun.json)).
The first session's cache loads swung by 30% within a build and in both directions, so the second added three rounds; the six-round medians are what the table gives.

| Shipped | v0.4.2 | v0.5.0-dev.1 | Delta |
|---|---|---|---|
| Binary | 99.1 MB | 43.3 MB | −56% |
| Startup | 261 to 263 ms | 260 to 261 ms | 0 |
| Idle RSS | 60 MB | 36 MB | −40% |
| Peak RSS, `maven` | 142 to 146 MB | 85 to 113 MB | −23 to −40% |
| Peak RSS, `npm` | 141 to 143 MB | 83 to 135 MB | −6 to −41% |
| `maven`, best rung | 1,521 req/s | 1,609 req/s | +6%, within this host's noise |
| `npm`, best rung | 1,451 req/s | 1,387 req/s | −4%, within this host's noise |
| `saturate`, best rung (3 rounds) | 101,609 req/s | 116,819 req/s | +15% |

The cache paths are bound by TLS and the socket, as the AES note above says, so flat throughput there is the expected result; what changed on them is memory and size.

**The CLIs**, v0.4.2 against v0.5.0-dev.1, ten alternating rounds of sixty runs each ([pgo-released-2026-10-25-cli-v042-vs-pgo.json](../../bench/data/pgo-released-2026-10-25-cli-v042-vs-pgo.json)).
A first run of three rounds of twenty drifted by a factor of two between rounds for both builds and is not quoted; the ten-round run is steady.

| CLI | v0.4.2 | v0.5.0-dev.1 | Delta | Rounds won |
|---|---|---|---|---|
| Binary | 37,882,184 B | 38,668,616 B | +2.1% | |
| `isx --help` | 2.8 ms | 2.3 ms | −18 to −20% | 10 of 10 |
| `isx list -q` | 4.1 ms | 3.4 ms | −17% | 10 of 10 |
| `isx account show` | 6.1 ms | 4.8 ms | −21% | 10 of 10 |
| `ssh <instance> true` through `ssh-proxy` | 94.4 ms | 93.8 ms | −1%, flat | 7 of 10 |
| `scp` 256 MiB through `ssh-proxy` | 456 MiB/s | 677 MiB/s | +39 to +49% | 10 of 10 |

**Against the plan's goal**, on shipped artifacts: faster on every compiled-code path (model calls, CLI startup, the relay loop), with TLS-bound cache serving flat; leaner, with proxy idle RSS down 40% and peak RSS down on every load; and smaller, with the proxy at 44% of its size and the CLI 2% larger.
The short ssh connection is flat because it is dominated by the exec setup in Incus, not by the CLI.

## Git preparation of a very large repository: nixpkgs (issue #1980)

Measured 2026-11-04 by hand, running the commands `GitPreparation` runs, on a 32-core isx instance with Git 2.56 and about 5 MiB/s to GitHub.
nixpkgs had 8,980,547 objects in a 3.4 GiB pack; the snapshot is 3.6 GiB.

| Step | Time |
| --- | --- |
| Strict network clone (`fetch.fsckObjects=true`), no reference | 12m39s wall, network-bound |
| `index-pack` of the pack, plain vs `--strict` | 83 s vs 218 s |
| Reference clone from a host checkout (`--reference --dissociate`) | 262 s, mostly the repack |
| `fsck --full --no-dangling` | 708 s, 5 GB resident |
| Guest-style `clone --no-hardlinks` plus SHA-256 of the packs | about 5 s |

Within 40 minutes `master`, `release-26.05`, a `backport-*` branch and a `gh-readonly-queue/*` branch moved.
Before #1980 that made the guest's selection differ from any cold snapshot, which was then discarded for a full network clone in the guest.
Faster cold paths for such repositories are researched in #1991.
Since #1990 a snapshot holds only the branch its template requests, so the other branches moving no longer discard it.

## Git preparation: one ref instead of every branch (issue #1990)

Measured 2026-11-08 on the Quarkus checkout with Git 2.56 on 32 cores, counting objects with `rev-list --objects`.

| Selection | Objects | `rev-list` time |
| --- | --- | --- |
| `main` alone, what a snapshot now holds | 1,114,795 | 1.8 s |
| Every branch and tag (657 refs: the checkout's `origin/*` tracking refs, which mirror the remote's branches, and its tags), what it held before | 2,482,104 | 3.1 s |

So the snapshot of `main` holds 45% of the objects it used to, which every build copies into the guest and every rebuild packs and checks.
The guest's and the host's `ls-remote` now ask for one ref instead of every branch and tag, which on nixpkgs is thousands of refs.

## Measurement caveats worth remembering

- **Native builds are not byte-reproducible here.** The same commit and toolchain produced
  proxy binaries 65,536 bytes apart (build metadata is baked into
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

## CLI latency baseline: native vs JVM

Measured with `bench/cli.sh` at 7d3389b on an AMD Ryzen 9 9950X3D2 (16 cores), Fedora 44, kernel 7.2.6, Incus 6.23 over the local Unix socket on a btrfs pool, with the native CLI built by GraalVM 25.4.
Medians of 20 runs after 2 warmups:

| Operation | Native | JVM | JVM / native |
|---|---|---|---|
| `isx --help` (process start only) | 2.8 ms | 195 ms | 70x |
| `isx instances` (connect + one listing) | 6.4 ms | 268 ms | 42x |
| `isx account show` (two instance reads) | 4.5 ms | 258 ms | 57x |
| preparation before `isx shell` attaches | 53 ms | 773 ms | 15x |

Single samples, indicative only: branch 350 ms, first start 223 ms, destroy of a running instance 846 ms.

What it shows:

- **Native is what makes the CLI feel instant.**
  The JVM costs roughly 200 ms at startup and another ~580 ms running the shell preparation, all of it cold code that a short-lived process never gets to warm up.
  Native does the same preparation in ~50 ms.
- **Incus round trips are cheap locally**: `instances` costs ~3.6 ms above bare startup, and a single request well under a millisecond.
  The request budgets are still worth having -- round trips run in sequence before the user gets a prompt -- but a few extra reads do not show up as user-visible latency.
  The same holds over the macOS vsock tunnel (see "CLI latency on macOS" below).
- **The host-side preparation is not where users wait.**
  It takes ~50 ms, below the ~100 ms at which a delay becomes noticeable.

These figures stop short of what users actually wait through, and both commands feel slow in practice.
The `prepareRunning` figure ends before the terminal attaches: it leaves out the terminfo push, the exec session, and the login shell (or tmux, zmx or the default action) starting inside the instance.
The branch figure is `--no-start`, so it leaves out the start, runtime setup, CA and `resolv.conf` repair, the account-identity reconcile (which polls the instance until it answers) and the shell attach.
`bench/cli.sh` has since gained `shellToPrompt` and `branchToPrompt`, which measure to a usable prompt; this Linux baseline predates them.

## CLI latency on macOS: the vsock tunnel is not where the time goes

On macOS every Incus request crosses the vfkit vsock tunnel into the appliance VM, and until
#803 nobody had measured what that costs. The worry was that a branch's ~40 requests, cheap
over a local socket, would dominate there.
They do not.

Measured with `bench/cli.sh --runtime=native` on 2026-10-10, with the build of the change that added this section, on an Apple M6 (12 cores, 16 GB), macOS 27.0.1, vfkit 0.6.4, appliance 0.3.11 (Incus 6.21, kernel 7.2.9, 2 vCPUs, btrfs pool), with the native CLI built by Oracle GraalVM 25.4.
Medians of 20 runs after 2 warmups (3 runs for the branch); the figure is the middle one of three passes, with the range the three covered.
Passes the day before, on an older appliance, were within a tenth of these, except that `isx shell` reached its prompt later (82-96 ms):

| Operation | macOS, native | Linux baseline, native |
|---|---|---|
| `isx --help` (process start only) | 5.3 ms (5.3-5.7) | 2.8 ms |
| `isx list -q` (connect + one listing) | 7.9 ms (7.8-8.1) | 6.4 ms |
| `isx account show` (instance reads only) | 10.2 ms (10.2-10.5) | 4.5 ms |
| preparation before `isx shell` attaches | 30 ms (30-31) | 53 ms |
| `isx shell` to a usable prompt | 79 ms (75-79) | not in the baseline |
| `isx branch --shell` to a usable prompt | 372 ms (372-402) | not in the baseline |

Single samples, indicative only: branch (`--no-start`) 58-75 ms, first start 122-170 ms, destroy of a running instance 653-668 ms.
The two columns are different machines, different Incus versions and different numbers of instances in the listing, and the Linux listing was `isx instances`, which `isx list -q` has since replaced.
They show the order of magnitude and nothing finer.

What it shows:

- **A request over the tunnel costs well under a millisecond, as on Linux.**
  `bench/request-cost.sh` times plain HTTP requests on the appliance's socket, with no isx involved.
  On one kept-alive connection: `GET /1.0` 0.13-0.14 ms, one instance 0.30-0.39 ms, the instance listing 0.43-0.47 ms, the bridge 0.70-0.82 ms (medians of 300, three sessions).
  Opening a new connection and reading one instance takes 0.46-0.52 ms.
  `GET /1.0` does almost nothing in the daemon, so the tunnel's own share of a round trip is about a tenth of a millisecond.
- **Request count does not dominate `isx branch` on macOS.**
  `bench/trace-branch.sh` counted 41-42 requests before the prompt.
  Their cost is an estimate, the count times the 0.3-0.8 ms above, not a figure the trace gives: 12-34 ms of a ~400 ms branch.
  The rest is the same work as on Linux.
  Over six traces: 160-200 ms inside the first exec (the setup script running in the new instance), 42-52 ms starting it, 21-51 ms creating it, and about 30 ms each for process start and connect, for the gap before the shell's exec, and for the shell reaching its prompt.
- **The preparation before `isx shell` stays below the ~100 ms at which a delay becomes noticeable**: 30 ms.
  The whole of `isx shell`, to a prompt that accepts a command, is under it too.
- **What costs on macOS is what costs on Linux: a write, not a read.**
  In the traces a branch's settings write takes 6-10 ms, from the daemon logging the `PATCH` to its `UpdateInstanceBackupFile finished` (the backup file itself is the last millisecond of that).
  Repeated on an idle, stopped instance (`bench/request-cost.sh --write`), the same kind of write takes 3.3-3.5 ms: an order of magnitude more than a read either way.
  So the argument for configuring a branch in one write (below) is the same on both platforms, and trimming reads would gain no more on a Mac than on Linux.
- **`isx account show` is slower than its reads explain, and the tunnel is not why.**
  It costs 5 ms above process start here, against 1.7 ms in the Linux baseline.
  With the daemon's request log beside it: the connect, `GET /1.0` and the first instance read are over 2.3 ms after start (`isx account show` on a name that does not exist, which stops there, takes 6.9 ms against 4.6 ms for `--help` in the same session).
  The other ~2.6 ms come after: isx loading `config.yaml` and working out the accounts, with two more instance reads at 0.3-0.4 ms each.
  The command made two reads when the Linux baseline was taken and makes three now, so the two columns do not measure the same code.
  Why the host-side part takes about 2 ms on this Mac was not looked into further.

The request budgets keep their purpose: a count is deterministic where a wall clock is not.
What changes is one of the reasons given for them.
"Far more expensive on macOS" was an assumption; measured, it is not so on this hardware and this vfkit.

## Claude Code's first screen: where the wait goes

For a template whose default action is Claude Code, the wait a user feels ends at Claude's first screen, not at a shell prompt, so `bench/cli.sh` (which keeps the default action out with `--shell`) did not measure it (#802).
`bench/claude-start.sh` does, and splits it.

Measured on 2026-10-23 with the native CLI from the change that added this section, on an AMD Ryzen 9 9950X3D2 (16 cores), Fedora 44, kernel 7.2.11, Incus 6.23.
That Incus ran nested inside an isx instance, with the `validate-on-real-incus` skill's measurement-only patches, and Claude's traffic crossed two isx proxies, the inner one a JVM build.
The template was `tpl-minimal` plus the `claude` tool (Claude Code 2.1.309).
The ranges are the medians of two passes: 10 runs each after a warmup, and 3 branches each with none, in [`bench/data/claude-start-2026-10-23.json`](../../bench/data/claude-start-2026-10-23.json) with the experiments below:

| What | Median |
|---|---|
| `isx branch` to Claude's first screen | 1259-1613 ms |
| `isx branch --shell` to a usable prompt | 700-730 ms |
| `isx run` on a running instance, to Claude's first screen | 391-405 ms |
| `isx shell` on the same instance, to a usable prompt | 147-163 ms |
| the action's pre-launch `find`/`grep`, the instance's own history | 1.4-1.7 ms |
| the same over 300 synthetic sessions (900 transcripts) | 5.2-5.6 ms |
| `claude --version` in the instance | 5.0-5.2 ms |

What it shows:

- **The pre-launch scan of the session history is not where users wait.**
  It costs 1-2 ms with an empty history, about 6 ms with 300 sessions and 12 ms with 1000, against a first screen of ~400 ms.
  Most of it is process start-up for the pipeline, and it was left as it is.
- **Node start-up is not it either.**
  `claude --version` takes 5 ms, so it answers without loading Claude's application, and cannot stand for Node's share.
  Claude's own start on a running instance is the difference between the two `isx` figures, about 245 ms.
- **Claude's first run in each instance waits on the network.**
  The first start in a fresh instance takes 675-810 ms against ~390 ms for the next ones, after the shell has been warmed (a first `isx shell` costs about 80 ms more than later ones).
  It is not the binary's page cache: reading the 280 MB binary first took 31 ms and changed nothing.
  The first run writes `policy-limits.json` and `remote-settings.json` under `~/.claude`.
  With `api.anthropic.com` made unreachable before that first run, every start waited about 5.4 s for the first screen; made unreachable after it, starts stayed at ~390 ms.
  So the fetch that writes them is on the first screen's path until it has succeeded once.
  Here it crosses two proxies; on a host it crosses one, so this share is smaller there.
- **A proxy restart costs little.**
  The first Claude start after one took 435 ms against ~390 ms for the later ones.
  The proxy pre-mints and stores the intercepted domains' certificates on disk (`InterceptedCertOptions`), so a restart does not mint them again; what is left is the proxy's own warm-up.

Claude's first-run fetch is made with the instance's credential, so its answer belongs to an account.
Caching it in a template is therefore not the obvious fix it looks like: a template outlives an account switch, and builds scrub temporary credential state, Claude's included, before publication.
It is recorded here rather than changed.

## VM boot: where the wait goes

A container answers exec in well under a second; a VM from `tpl-isx-agent-vm` took about 11 s on Sanne's host (#1238).
Almost none of that is isx: it is firmware, the guest's boot, and Incus noticing the agent.
`bench/vm-boot.sh` splits it, with the host's clock on the serial console and on Incus's answers, and the guest's own clock for what happens inside it.

Measured on 2026-10-10 with `bench/vm-boot.sh` inside an isx VM, so **under nested KVM** (AMD Ryzen 9 9950X3D2, Incus 6.23, btrfs pool, 8 vCPUs and 4 GiB unless stated), copying a stopped VM of base image `fedora-44-20261009`.
Nesting inflates every exit to the hypervisor, so these figures rank the phases and the effect of each change; they are not what a VM on bare metal waits:

| Phase | Nested KVM | Clock |
|---|---|---|
| Firmware until shim (OVMF, Secure Boot) | 25 s; 37 s with 30 vCPUs; 5 s with Secure Boot off, at 8 or 30 | host |
| GRUB loading kernel and initrd | ~3-4 s | host, guest |
| Guest `systemd-analyze` (kernel, initrd, userspace), image as released | ~19 s | guest |
| Start request until Incus's `GET /state` reports the agent connected, image as released | 49.4 s | host |
| That, until an exec answers (probed every 50 ms) | 0.06-0.11 s | host |

The time between `incus-agent` becoming active and Incus reporting it connected is not in the table: the first is read from the guest's clock, which was a few tenths of a second off the host's, as much as that gap.

What it shows:

- **The Secure Boot firmware is the largest block, and its cost grows with the vCPU count.**
  With `security.secureboot` on, Incus boots `OVMF_CODE.secboot.fd`, which needs SMM; every SMM entry is expensive under nesting, and OVMF enters it per vCPU.
  With it off, the firmware takes ~5 s at 8 or 30 vCPUs.
  On Sanne's host, one level of virtualization, #1238 measured ~4.3 s from the request to the guest kernel starting, copy and QEMU start included, so there Secure Boot costs at most that.
- **The kernel's console output was the next largest.**
  The released image boots with `console=tty1 console=ttyS0` at the default log level, ~125 KB per boot through an emulated UART and framebuffer.
  incus-spawn-images#19 (a draft; it reaches users only with an image release and the `minimal.yaml` bump that follows) proposes `loglevel=5`, set for every kernel in `/etc/kernel/cmdline`.
  Built locally, it cut start-to-exec from 49.5 s to 41.3 s and `systemd-analyze` from 19.4 s to 12.1 s (three alternating runs each).
  The console then shows warnings, errors and panics, ~300 bytes on a normal boot, and systemd's status lines, which are not kernel messages, so `VmAgentFailure` still finds `Failed to start incus-agent.service`.
  `quiet` caps the kernel's console at level 4, which drops its warnings too, and turns systemd's status lines off until a unit fails or stalls; measured against `loglevel=4`, that second part was worth ~0.8 s.
  The SELinux avc denials `VmAgentFailure` also matches (#842) are notices and no longer reach the console, so a report would quote the failure but not that cause; the image pins SELinux off, which is what keeps that case from happening.
- **The initrd is not worth trimming.**
  Dropping `fips`, `tpm2-tss`, `memstrack`, `i18n`, `kernel-modules-extra` and others took it from 34 MB to 28 MB with no measurable change.
  A host-only initrd is not an option anyway: the image is built in a chroot on a CI runner, whose hardware a host-only dracut would detect.
- **Memory slows the kernel, vCPUs slow the firmware.** 12 GiB instead of 4 added ~1.7 s of kernel time (memory zone setup).
  30 vCPUs instead of 8 added ~12 s of firmware, all of it SMM, which turning Secure Boot off removes.
  The VM in #1238 had 32 vCPUs and 15 GiB (isx's default VM memory is a quarter of host RAM, up to 16 GiB).
- **isx's own polling can add at most a quarter of a second, by its code.**
  Exec answered 0.06-0.11 s after Incus reported the agent connected, with the script probing every 50 ms.
  `waitForReady` reads the same report every 250 ms (`VM_POLL_INTERVAL_MS`) and probes with exec in the pass that sees it, so what it adds is the delay in noticing, up to that interval.
  This was read from the code, not measured through isx.

Not measured: a VM on bare metal (one level of virtualization, as on Sanne's host) and VMs inside the macOS appliance (aarch64 firmware has no SMM, so the Secure Boot finding is x86-only).

What was decided (Sanne, 2026-10-10, on #1240):

- **Secure Boot is off for every VM.**
  It guards the guest's boot chain against tampering from inside the guest, which is not what an isx VM is for.
  The agent in it has root anyway, and the isolation isx stands for is the VM boundary and the proxy, which this leaves alone.
  VM builds set `security.secureboot=false` in the one write `InstanceLifecycle.prepareVmBuild` makes, `InstanceLifecycle.configureBranch` sets it on every VM branch in the write it already makes, and `startForUse` (`isx shell`, `isx run`, the TUI, MCP) on a VM that still has it on, in the write that rotates its secret.
  None of these adds a request.
  `isx project create` sets it on its copy of the parent with one write of its own, off the latency path.
  Other starts of an existing VM -- template updates, agent recovery -- leave it as it is: a template built before this keeps Secure Boot until it is rebuilt, though its branches never get it.
- **A VM's default vCPU count is capped at 8, and on a hybrid host at its top tier** (Sanne's choice "C" on #1240).
  `ResourceLimits.defaultVmCpus()` is `max(1, min(8, host CPUs - 2))` on a host with one tier of cores, and `max(1, min(8, host CPUs - 2, top-tier physical cores))` on a hybrid one (`CpuInfo.hybridTopTierCores()`, read once per process).
  A host whose tiers cannot be told apart follows the first rule.
  A 16-core, 32-thread desktop gets 8 instead of 30, a 4-core, 8-thread host 6 and a 4-core host without SMT 2, as before #1238, an i7-12700H (6 P-cores, 8 E-cores, 20 threads) 6 instead of 18, and an i9-13900K (8 P-cores, 32 threads) 8.
  It is a default, not a ceiling: `--cpu` still sets any count.
  A host is hybrid when its online CPUs fall into more than one tier; the top tier's cores are then counted with a core's SMT threads once.
  On Linux the tier comes from the first source that tells CPUs apart: the hybrid PMU's list of performance CPUs (`/sys/devices/cpu_core/cpus`), which Intel 12th-14th gen hybrids with Hyper-Threading need because intel_pstate leaves every `cpu_capacity` at 1024 there, and Arrow Lake, whose E-cores reach about 80% of its P-cores by every other measure; then `cpu_capacity` where it differs (ARM big.LITTLE, Intel hybrids without SMT); then `cpufreq/cpuinfo_max_freq` where it differs (AMD Zen 5 + Zen 5c hybrids, which have neither).
  With the last two, the top tier is every CPU within 80% of the highest: favoured cores and a dual-CCD X3D's slower CCD stay one tier (so such a host is not hybrid), and a three-tier ARM SoC's top tier is its prime cores.
  A physical core is the set of CPUs in its `topology/core_cpus_list` (`thread_siblings_list` before 5.3), not its package and core id.
  On device-tree Arm with Linux 6.x, `core_id` restarts in every cluster and the package is 0 throughout, so an RK3588's four A76 cores would count as two.
  Anything that leaves the tier in doubt makes it unknown, which choice C maps to the one-tier rule: an unreadable capacity, a capacity on some CPUs but not others, a top-tier CPU without a core list.
  CPU detection stops here for #1238: hardware nobody can test here degrades to the one-tier rule.
  The same scan feeds `performanceCores()`, so clone concurrency on such hybrids now counts only their top tier's threads (an i7-12700H: 12 of 20).
  On macOS a host is hybrid when `hw.nperflevels` is above 1, and its top tier is `hw.perflevel0.logicalcpu`, which equals the physical count on performance cores; both come from one `sysctl`, and a failed read is not kept.
- **Of the services #1238 listed, only `dnf-makecache.timer` is masked** (incus-spawn-images, `configure-base.sh`, container and VM alike).
  The LVM units have a user in a nested Incus with an LVM pool, and `upower` and `rtkit-daemon` come from templates, rtkit for GUI audio.
- **Resuming a VM from a stateful snapshot is deferred**; it would need its own design.

## Branch setup: the static address (issue #801)

A branch's setup script used to wait for systemd-networkd to configure the static address isx had already written into `/etc/systemd/network/10-eth0.network`.
Measured on 2026-11-06 on `main` @ `7c01a242` with base image `fedora-45-v1` (systemd 262.1, iproute 7.0.0), Incus 6.23 on a btrfs pool, in a KVM guest with 8 vCPUs, `tpl-minimal`; medians, with the range in brackets:

| What | Waiting for networkd | Applying the file with `ip` |
|---|---|---|
| Guest uptime at the first exec / at the default route | 0.08 s / 0.235 s [0.22–0.29] | – |
| `incus start` return to address and default route (n=10) | 252 ms [239–276] | 56 ms [52–66] |
| Branch setup exec, `bench/trace-branch.sh` | 283 ms [229–297] (n=20) | 83.5 ms [75–89] (n=10) |
| `isx branch` to prompt, JVM | 1448 ms [1323–1543] | 1244 ms [1217–1317] |

- **The wait was boot ordering.**
  networkd is ordered after `systemd-udevd` and was active about 226 ms into boot.
  The script's `systemctl start systemd-networkd` failed at once in 10 of 10 runs, since D-Bus is not up at the first exec.
- **networkd adopts what `ip` set only when it is identical** (`ip -ts monitor address route`, five runs per variant).
  With plain `ip addr replace` and `ip route replace default`, it deleted and re-added the address (0.20 ms) and the route (0.35 ms).
  With `brd +` on the address and `proto static` on the route, it deleted neither, in 5 of 5.
- **`raw.lxc` with `lxc.net.0.ipv4.*` was as fast (50 ms) and was rejected.**
  With an extra NIC device the address landed on `eth1` as well, and with the NIC masked (airgap) the start failed.
- **VMs have nothing to gain.**
  DHCP gave the reserved address about 1.5 s before `incus-agent` was up, so their first exec already sees it.
- **A cold start won its race by little.**
  A native `isx shell` of a stopped container handed over at uptime 0.27–0.29 s, against the route at 0.22–0.29 s, so its probe now applies the address too.

## Branching after a bulk delete: the btrfs cleaner (issue #2061)

A branch made soon after many instances were deleted can take seconds where it took a fraction of one.
isx makes the same Incus requests either way, and the extra time is all in Incus's copy, the btrfs snapshot.
Measured on 2026-11-10 in a KVM guest (8 vCPUs, 15 GB, kernel 7.3.1, btrfs-progs 7.1, Incus 6.23), on the `cow` pool in a loop file.
The source was a running instance of `tpl-minimal` filled to 2.7 GiB (200,000 small files and one 1.5 GiB file).
Each series made 20 copies of it, deleted them with `incus delete -f`, then timed `incus copy` of the same source every 2 s.

| Pool | `incus copy` while the cleaner works | Cleaner time for 20 |
|---|---|---|
| qgroups off | 0.11–0.14 s, flat | under 6 s |
| Simple quotas (`btrfs quota enable --simple`) | 0.11–0.13 s, flat | about 10 s |
| Full qgroups, `commit=30` | 0.15 s, then **4.5–6.4 s** for each copy from t+11 s to t+147 s | about 140 s |
| Full qgroups, `commit=300`, first run | 0.15–0.33 s for 153 s; the cleaner had not started | not reached |
| Full qgroups, `commit=300`, second run, one copy every 10 s | 0.67–6.0 s from t+10 s to t+96 s | about 105 s |
| Full qgroups, `commit=30`, a 116 MiB source | 0.13–0.22 s, flat | about 2 s |

- **The cause is full qgroup accounting in `btrfs-cleaner`.**
  A delete returns at once, and the cleaner frees the subvolume's tree later.
  With full qgroups it accounts every extent the subvolume shared with its source, holding the transaction a new snapshot must commit.
  During the window `btrfs-cleaner` used 100% of one CPU and `commit_stats` showed commits of up to 11.8 s.
  With qgroups off or simple quotas the same series had no window at all.
- **It scales with the deleted data and with how many copies share it.**
  20 copies freed in about 7 s each; a backlog of 70 took 663 s to drain, about 9.5 s each, and an `incus copy` started meanwhile waited behind it for minutes.
  A copy of a small source costs little to free, which is why the window is easy to miss on `tpl-minimal`.
- **isx's root `size` is what turns full qgroups on.**
  The pool had no quotas until the first `isx branch`, whose 100GB root `size` makes Incus run `btrfs quota enable`, in full mode.
  A plain `incus copy` of an instance with the `size` re-enabled them after `btrfs quota disable`.
- **`commit=300` does not remove the window, and its start is unpredictable.**
  In one run the cleaner had not started 153 s after the deletes, with a copy every 2 s; in the next it started within 10 s.
  Every isx loop pool has these scratch options, so the window comes sooner or later all the same.
- **Simple quotas would avoid it but cost the referenced column.**
  Under simple quotas a subvolume's referenced bytes count only what it wrote since quotas were enabled.
  Every template read 0 and the source only its 236 KiB of later writes, so `BtrfsUsage` would report no template's weight.
- **What isx does:** `isx doctor` reports a backlog as a note when the pool uses full qgroups, from the `qgroup show` read `BtrfsUsage` already makes.
  It counts the qgroups btrfs-progs paths as `<under deletion>`.
  A freed subvolume's qgroup stays until a later commit, up to 300 s with `commit=300`, with its referenced bytes unchanged and pathed `<stale>`, so a missing subvolume alone would report a backlog already gone.
  `sudo btrfs subvolume sync <pool>` waits for the backlog.
