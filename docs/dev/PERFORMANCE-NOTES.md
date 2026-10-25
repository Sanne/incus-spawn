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

`release.yml` collects and applies the profiles (DESIGN.md "Profile-guided optimization").
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
