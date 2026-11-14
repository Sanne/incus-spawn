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
#1991 measured faster cold paths for such repositories, below.
Since #1990 a snapshot holds only the branch its template requests, so the other branches moving no longer discard it.

## Git preparation: the cold snapshot of a very large repository (issue #1991)

Measured 2026-11-12 with Git 2.56 on 32 cores, about 7 MiB/s from GitHub, through `bench/GitPreparationBench.java` and the commands it runs ([data](../../bench/data/git-very-large-repository-2026-11-12.json)).
The fixture is nixpkgs: a host checkout of 9.0 million objects, and a `master` snapshot of 8.4 million objects in 3.24 GB.
A first version indexed a copy of the export (`index-pack --stdin`), which took 314 s and needed twice the snapshot's size on disk.

| Cold `master` snapshot | Time | Peak RSS |
| --- | --- | --- |
| From the checkout, before: fetch, `repack -a -d`, `fsck --full` | 981 s (259 + 716 s) | 5.6 GB |
| From the checkout, after: fetch, `pack-objects --stdout`, `index-pack --strict` of that pack in place | 302 s (107 + 189 s) | 5.3 GB |
| No checkout, strict clone from GitHub | 652 s | 3.4 GB |
| No checkout, strict clone over a loopback `git daemon` | 384 s | 3.1 GB |

A warm build takes 6.6 s either way, 4.9 s of it GitHub's `ls-remote`.

The four options of the issue, decided:
- **The full fsck is replaced, but not by option (a) as written.**
  Indexing the repack's pack in place (`index-pack --verify --strict`) took 271 s and 6.8 GB on top of the repack.
  Streaming the ref's closure out of the borrowing repository and indexing it into an empty one does the repack's work and the check in one pass, as host-source exports already do.
  It catches the misnamed checkout object that the fsck caught.
- **The reference stays.**
  Before, a checkout made the cold snapshot slower than GitHub at 7 MiB/s.
  Now it is twice as fast, and faster than a loopback clone with no bandwidth limit, whose server-side pack and strict receive alone took 384 s.
- **The cache limit needs no change.**
  The default has been 16 GiB since #2097, and #1554's warnings name an eviction or an oversized snapshot.
  The `master` snapshot is 3.24 GB, barely smaller than every branch's 3.6 GiB.
- **No up-front warning.**
  The network path cannot know the size before it fetches.
  Instead the check now shows its elapsed time and Git's progress, as the fetch does.

On 4 cores (`taskset -c 0-3`) the export took 104 s and the strict index 209 s, about what they take on 32.
`fsck --full` took 718 s on 4 cores and 716 s on 32: it is effectively serial.
On a CPU with a quarter less single-thread speed it would pass the 15-minute limit of a local step, and the build would clone from the network instead.

## Git preparation: one ref instead of every branch (issue #1990)

Measured 2026-11-08 on the Quarkus checkout with Git 2.56 on 32 cores, counting objects with `rev-list --objects`.

| Selection | Objects | `rev-list` time |
| --- | --- | --- |
| `main` alone, what a snapshot now holds | 1,114,795 | 1.8 s |
| Every branch and tag (657 refs: the checkout's `origin/*` tracking refs, which mirror the remote's branches, and its tags), what it held before | 2,482,104 | 3.1 s |

So the snapshot of `main` holds 45% of the objects it used to, which every build copies into the guest and every rebuild packs and checks.
The guest's and the host's `ls-remote` now ask for one ref instead of every branch and tag, which on nixpkgs is thousands of refs.

## Git preparation: tags into the ref's history (issue #2051)

Measured 2026-11-10 with Git 2.56 on 32 cores, from a container box to GitHub, timing `GitPreparation.withTags` on a fresh snapshot of each default branch.

| Repository | Tags read | Tags added | `withTags` |
| --- | --- | --- | --- |
| `quarkusio/quarkus` | 533, all lightweight | 0 | 13 ms |
| `git/git` | 1,012, annotated | 1,010 | 3.0 s, one fetch of the tag objects included |

Quarkus tags its releases off `main`, so no tag lies in `main`'s history, and a network clone's `git describe` on `main` finds none either.
The guest's and the host's `ls-remote` cost the same with `refs/tags/*` as without: 0.69 s against 0.61 s for Quarkus, within run-to-run noise, since a pattern sends no ref prefix and the server advertises every ref anyway.

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

## VM root disk: bus and host cache mode (issue #810)

isx puts every VM's root disk on virtio-blk and leaves `io.cache` unset.
Incus then picks the mode by the pool: `writeback` on btrfs and ZFS, `none` on `dir` ("VM disk cache mode, per context" below).
The `none` rows on btrfs below set it explicitly, which isx never does.
Neither an NVMe root disk nor a host-cached one was faster on the work a VM does, so both stay as they are (design/macos-and-vms.md "An NVMe or host-cached root disk").

Measured on 2026-11-13 with `bench/vm-disk-bus.sh` inside an isx VM, so **under nested KVM**: the bench host is itself a VM on virtio-blk.
That host had 8 vCPUs (AMD Ryzen 9 9950X3D2), Fedora 45, kernel 7.3 and Incus 6.23, and nothing else ran on it during the run.
The guest was a stock Fedora 45 VM from `images:fedora/45` (4 vCPUs, 6 GiB, Secure Boot off): the isx base VM image's kernel has neither NVMe nor virtio-scsi.
Pools: btrfs on a loop file, as `isx init` makes it, and `dir` as the baseline; ZFS and aarch64 were not measured.
fio ran once per job on a 4 GiB file in the guest's root, O_DIRECT through libaio, so a difference under about 10% between rows is not significant.
Clone (`git clone --no-local` of a 315 MB quarkus mirror with its checkout), unpack (`tar xf` of the quarkus tree, 57,665 entries) and rm (both trees) each end in `sync` and are medians of 3 runs, with the guest's caches dropped first.
Boot is from the start request until an exec answers, median of 3.

| pool | bus | io.cache | boot s | 4k rand read QD1 IOPS (µs) | 4k rand write QD1 IOPS (µs) | read QD32 IOPS | write QD32 IOPS | seq read MB/s | seq write MB/s | clone s | unpack s | rm s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| btrfs | virtio-scsi | none | 26.85 | 3855 (195) | 3741 (211) | 21796 | 21161 | 10115 | 4324 | 15.83 | 1.38 | 14.98 |
| btrfs | virtio-blk | none | 25.56 | 4143 (183) | 3731 (217) | 20603 | 23140 | 9256 | 5746 | 15.42 | 1.35 | 13.32 |
| btrfs | nvme | none | 25.52 | 4540 (161) | 3940 (193) | 27485 | 18325 | 7482 | 4215 | 15.92 | 1.87 | 13.58 |
| dir | virtio-scsi | none | 26.17 | 5720 (112) | 5285 (135) | 19497 | 18458 | 12393 | 5697 | 15.60 | 1.30 | 10.21 |
| dir | virtio-blk | none | 26.59 | 6371 (100) | 5814 (122) | 19574 | 19283 | 15566 | 3732 | 15.54 | 1.13 | 10.22 |
| dir | nvme | none | 25.63 | 5807 (113) | 6003 (105) | 10142 | 10364 | 4074 | 3746 | 15.74 | 1.57 | 11.68 |
| btrfs | virtio-blk | writeback | 26.55 | 6666 (91) | 5047 (146) | 54442 | 23966 | 16186 | 3276 | 16.45 | 1.38 | 9.84 |
| btrfs | virtio-blk | unsafe | 27.05 | 6700 (91) | 5087 (145) | 54911 | 26016 | 13928 | 3629 | 15.80 | 1.25 | 8.92 |
| dir | virtio-blk | writeback | 26.62 | 7252 (81) | 6521 (103) | 56906 | 38848 | 16580 | 3788 | 15.92 | 1.22 | 9.37 |
| dir | virtio-blk | unsafe | 25.46 | 6626 (93) | 7708 (81) | 54231 | 55053 | 16007 | 2888 | 15.11 | 1.05 | 8.84 |

- **The bus does not change boot time**: every row answered exec in 25.5-27 s, within run-to-run noise.
- **A git clone takes the same time on every bus and cache mode**, about 15.5 s.
- **nvme unpacks 30-40% slower than virtio-blk** on both pools (runs: 1.66-1.99 s against 1.34-1.40 s on btrfs), and on `dir` it halves the depth-32 random numbers and cuts sequential reads to a quarter (4074 against 15566 MB/s), while sequential writes are unchanged.
- **virtio-scsi takes about 12 µs more per random read than virtio-blk** at queue depth 1: 112 against 100 µs on `dir`, and on btrfs 195 against 183 µs, about 6% and within the noise of one fio run.
  Random writes differ by 13 µs on `dir` and are within noise on btrfs (211 against 217 µs).
- **`writeback` and `unsafe` sped up direct random reads** about 2.6 times at depth 32, and only removing the trees got faster on the workloads: 13.3 s down to 9.8 s or 8.9 s on btrfs.
  This run does not settle what they change.
  The `none` rows already read 1M direct sequential data at 4-15 GB/s (7.5 and 4 GB/s on NVMe, 9-15 GB/s on virtio-scsi and virtio-blk), which no disk under nested KVM delivers, so a cache below the guest (the btrfs loop file, the outer VM) is serving them.
  The clone, unpack and rm runs drop only the guest's caches, not those.
  The cache modes and the buses' read numbers need a run where nothing below the guest caches, which is part of #2198.
- The first clone after each boot took 27-30 s in every row; runs 2 and 3 took 13.7-16.5 s.

Where `writeback` or `unsafe` may still pay, on a `/tmp` scratch disk or for reads of a template's blocks shared by many branches, is #2198.

## VM disk cache mode, per context (issue #2198)

**On a btrfs pool, isx VMs already run with `io.cache=writeback`, and no other mode is worth taking in any of the four contexts measured.**
- **Incus's default is not `none` on btrfs.**
  With `io.cache` unset, Incus 6.23 opens a VM's disk file without `O_DIRECT` when it sits on btrfs or ZFS: `writeback` with `aio=threads` (`addDriveConfig` in `driver_qemu.go`, "We've seen ZFS lock up and BTRFS checksum issues when using direct I/O on image files").
  Measured on the qemu process: `/proc/<pid>/fdinfo` shows the root image's flags `02100002` when unset, `02140002` (`O_DIRECT`) only when `io.cache=none` is set.
  isx sets no `io.cache`, so on the `cow` pool that `isx init` makes, every VM disk goes through the host's page cache today, and #810's `writeback` rows are today's configuration there.
- **A `/tmp` of its own** helps only flush-heavy work, and not a build.
- **Branches do not share the host's cache of their template's blocks.**
  Each copy caches its own, and the cache outlives the VM.
- **A template build on `unsafe`** installs packages about 15% faster than today's `writeback`, about 2.4 s per 700 MiB installed.

Measured on 2026-11-13 with `bench/vm-disk-cache.sh benchbase all` inside an isx VM, so **under nested KVM**: 8 vCPUs (AMD Ryzen 9 9950X3D2), 16 GiB, Fedora 45, kernel 7.3, Incus 6.23, nothing else running.
The pool was btrfs on a loop file as `isx init` makes it (`compress=zstd:1`), the loop device with direct I/O on.
The guest was a stock Fedora 45 VM (4 vCPUs, 4 GiB, a 30 GiB ext4 root on virtio-blk) with `tmp.mount` masked, as isx's images have it.
Each workload ran after dropping the guest's caches and, except across the copies of the branches context, `sync` and dropping the host's.
Times are medians of 3 runs, in seconds; the first run after each boot was the slowest in most rows.
The Maven build is a cold offline `mvn -q package -DskipTests` of incus-spawn itself.

**`/tmp` on the root disk or on a block volume of its own**, all on btrfs:

| /tmp on | io.cache | offline rpm install s | git clone s | tar unpack s | rm of the three s | Maven build s |
|---|---|---|---|---|---|---|
| root disk | unset: writeback (today) | 4.73 | 17.77 | 2.31 | 11.59 | 24.41 |
| root disk | none | 3.63 | 16.80 | 2.02 | 16.82 | 26.71 |
| own disk | none | 6.87 | 18.14 | 1.67 | 12.02 | 26.25 |
| own disk | writeback | 4.94 | 16.45 | 2.05 | 8.61 | 24.82 |
| own disk | unsafe | 2.30 | 14.91 | 1.03 | 8.24 | 24.97 |

- The rpm install is 23 rpms (gcc-c++, clang, golang, 172 MiB) into an empty `rpm --root`; the clone, unpack and rm are #810's.
- Against today's `/tmp` on the root (`writeback`), a disk of its own on `unsafe` halves the rpm install and the unpack, saves 16% on the clone and 29% on the rm, and nothing on the build.
  Its own disk on `writeback` matches today's root except on the rm (8.61 s against 11.59 s), so the gains on the rpm install, the unpack and the clone come from `unsafe` dropping the flushes.
- The extra device costs two requests at branch time (creating the volume and attaching it, 0.07 s together) and a format on first boot; the boot itself does not change (26-27.5 s).
- The fresh filesystem makes the rm faster on `none` too (12.0 s against 16.8 s on the root), so part of the rm's gain is a new filesystem rather than the cache mode.
- The `unset` row was measured at the end of the same window, with `--tmp-roots=default --caches=`, after review found the table lacked today's configuration.

**Branches reading the same template blocks**, four copies started and measured one after the other, the host's caches dropped once before the first:

| io.cache | branch | read all of /usr s | root image in host cache after the read and the build MiB | Maven build s |
|---|---|---|---|---|
| none | 1 | 31.33 | 725 | 25.09 |
| none | 2 | 32.34 | 720 | 24.33 |
| none | 3 | 33.44 | 727 | 24.80 |
| none | 4 | 34.06 | 713 | 24.32 |
| writeback | 1 | 30.75 | 2559 | 25.05 |
| writeback | 2 | 32.85 | 2558 | 25.02 |
| writeback | 3 | 33.48 | 2557 | 24.49 |
| writeback | 4 | 34.05 | 2559 | 24.94 |

- **The second to fourth branch read no faster than the first**, and each holds its own 2.5 GiB of its root image in the host's page cache (`fincore` on each copy's `root.img`).
  On btrfs a copy's image is a separate file whose extents are shared, and the page cache is per file, so nothing a sibling read is reused.
- **The cache outlives the stop.**
  The host's `Cached` grew by about 2.5 GiB per `writeback` branch and did not fall when the branch stopped: 1.2 GiB before the first branch's read, 8.8 GiB before the fourth's.
- Even with `none` set, about 720 MiB of each image stayed cached after the read, likely the extents btrfs stored compressed, whose direct reads it serves through the page cache.
- The host's `Cached` column the script prints also counts the guest's own page cache, since Incus backs guest RAM with a memfd, which the kernel counts as shared memory; the image's own share is the `fincore` column.

**A Maven build on the root disk, and the host memory it leaves:**

| io.cache | Maven build s | root image in host cache MiB | after the host allocates 12.7 GiB MiB | that allocation s |
|---|---|---|---|---|
| none | 24.00 | 10 | 10 | 24.41 |
| writeback | 24.51 | 539 | 285 | 27.01 |
| unsafe | 25.33 | 530 | 231 | 27.67 |

- The build takes the same time in every mode.
- `writeback` leaves about 540 MiB of the image in the host's cache after a build, which the kernel reclaims only in part under pressure, and the host's allocation took 10% longer while it did.
- QEMU's resident memory did not change under pressure (1874 MiB on `none`, about 2100 MiB on the others): there is no swap, and guest RAM is not reclaimable cache.

**A template build on `unsafe`, made safe before use**: an offline `dnf install` of the same 23 rpms (707 MiB installed) on the root disk, a clean `incus stop`, `sync -f` on the pool, `io.cache` set to `none` and a boot that checks every package is installed:

| io.cache during the install | dnf install s | stop s | host sync s | together s | packages missing after the boot |
|---|---|---|---|---|---|
| none | 15.17 | 1.69 | 0.05 | 16.87 | 0 |
| writeback (today) | 16.26 | 1.66 | 0.04 | 18.02 | 0 |
| unsafe | 13.81 | 1.54 | 0.37 | 15.75 | 0 |

- `unsafe` saves about 2.4 s on the install against today's `writeback`, and the flush after the stop costs 0.37 s of it back.
- Every run found all packages after the switch, but a clean shutdown cannot show what a host crash during the build would do: that is what the sync after the stop exists for.
- The runs spread by under 0.2 s within each row.

What this does not cover: bare metal, ZFS (which Incus also runs on `writeback`) and aarch64.

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

## Quarkus 4.0.0.CR1 against Beta3 (issue #2169)

Measured on 2026-11-12 in a nested isx instance (x86_64, 32 vCPUs), with Oracle GraalVM 25.0.4.1.1 and unprofiled native builds.
Base is `main` at 8b8fd9b4 on Beta3, head that commit with only the version bump to CR1, which keeps Vert.x 5.2.3 and Netty 4.2.20.
Each row is the median of alternated `bench/run.sh --skip-build` runs, per Hyperfoil phase: three pairs for `maven`, two for `npm` (four phases each, as the ladder repeats them) and five for `saturate`.

| Load, concurrency | Beta3 req/s | CR1 req/s | Beta3 p50 / p99 | CR1 p50 / p99 |
|---|---|---|---|---|
| `maven`, 8 | 1,533 | 1,470 | 4.69 / 11.40 ms | 5.05 / 11.80 ms |
| `maven`, 64 | 1,536 | 1,626 | 44.30 / 53.74 ms | 37.75 / 55.05 ms |
| `npm`, 8 | 1,462 | 1,555 | 2.74 / 5.69 ms | 2.49 / 5.34 ms |
| `npm`, 64 | 1,489 | 1,584 | 20.97 / 40.37 ms | 19.92 / 35.65 ms |
| `saturate`, 16 | 93,253 | 92,129 | 0.15 / 0.35 ms | 0.16 / 0.33 ms |
| `saturate`, 256 | 95,663 | 91,580 | 2.62 / 3.41 ms | 2.70 / 4.15 ms |

- **The intercepted HTTPS paths are unchanged.**
  `maven` and `npm` differ by less than run-to-run spread, in both directions.
- **The plain-HTTP ceiling is slightly lower on CR1.**
  `saturate` was 1 to 4% lower at 16 to 256 connections, and its p50 at 256 connections 3% higher, in all five pairs.
  No proxy code changed and Vert.x and Netty are the same, so the likely cause is the unprofiled build's code layout; a profiled release build was not compared.
- **Memory and startup are unchanged.**
  Idle RSS was 66 MB on both, peak 110 MB under `maven` and 113 MB under `npm`; proxy startup was 260 ms.
  The CLI's `--version` took a median 2.5 ms on CR1 and 2.6 ms on Beta3, over 300 runs each.
- **Size:** the proxy is 832 KiB smaller (104,664,400 bytes against 105,516,368), as Netty's DNS resolver left it; the CLI is the same size.
- **Read the phases, not the headline.**
  `bench/run.sh` reports as its headline the phase with the most throughput, which differed between runs of the same binary.
  Its p50 then jumped by 7x between runs that measured the same latency per phase.

## Upstream responses off their connection's event loop (issue #2176)

Measured on 2026-11-12 in a Linux container box (x86_64), Vert.x 5.2.3, NIO transport.
The question was whether Vert.x's own stream queue can pause an HTTP/1.1 client connection and lose the tail on close, as a paused response did in #2088.

| Setup | Answer | Lost |
|---|---|---|
| Plain Vert.x, request on another loop than its pooled connection, handler idle | 20 chunks, then close | 1 of 50 |
| Same, handler busy 200 µs per chunk | 20 chunks, then close | 50 of 50 |
| Same, handler busy 20 µs per chunk | 2000 chunks, then close | 0 of 50 |
| The proxy, as built (one MITM listener) | 20 chunks, then close, on a reused connection | 0 of 600 |
| The proxy with a second listener instance on another loop | same | first loss at download 26 |

The 2000-chunk case loses nothing on NIO because Vert.x turns auto-read off once 8 messages are pending, so the close is read only after the queue drains.
Across the proxy's unit tests (965 tests, 433 HTTP/1.x responses held by `ResponseHold`) no response ran off its connection's loop.

## Native socket transport (issue #475)

Measured on 2026-11-13 in a Linux container box (nested Incus 6.23), x86_64, 32 CPUs, kernel 7.3.3, with no other work on the host.
Three native proxies were built with Oracle GraalVM 25.4, `-O3 -march=haswell`, G1, and no PGO.
`nio` is `main` at `da960615`, `epoll` adds `netty-transport-native-epoll` with `quarkus.vertx.native-transport=if-available`, and `uring` adds io_uring on top with `native-transport-type=io-uring`.
`bench/run.sh` ran them interleaved, `maven` three times each and `saturate` twice each, and every run had 100% 2xx.
The raw runs are in [bench/data/proxy-transport-2026-11-13.json](../../bench/data/proxy-transport-2026-11-13.json).

`maven`, median req/s, with p50 / p99 in ms:

| Users | nio | epoll | uring |
|---|---|---|---|
| 8 | 1653 (4.4 / 9.9) | 1797 (3.9 / 8.1), +9% | 2480 (3.2 / 5.6), +50% |
| 32 | 1744 (18.2 / 22.4) | 1802 (17.6 / 24.0), +3% | 2539 (12.4 / 15.8), +46% |
| 64 | 1577 (40.1 / 46.7) | 1813 (35.1 / 40.9), +15% | 2545 (24.9 / 28.3), +61% |

`saturate`, median req/s:

| Users | nio | epoll | uring |
|---|---|---|---|
| 16 | 96,735 | 98,961 (+2%) | 102,704 (+6%) |
| 64 | 97,536 | 100,462 (+3%) | 103,687 (+6%) |
| 128 | 96,649 | 100,443 (+4%) | 102,590 (+6%) |
| 256 | 95,735 | 99,750 (+4%) | 102,753 (+7%) |

- **Size:** 104,140,112 bytes for `nio`, 106,696,016 for `epoll` (+2.5%) and 107,744,592 for `uring` (+3.5%).
- **Memory on `maven`:** idle RSS 66, 68 and 70 MB, and peak RSS 110, 110 and 119 MB, for `nio`, `epoll` and `uring`.
- **Startup:** 260 to 274 ms for all three.
- **Spread:** runs of one binary agree within 1.5% for `uring` and within about 10% for `nio`.
- **Not measured:** kqueue on macOS (measured since, below), aarch64, and the release toolchain with its PGO profile.

The proxy took epoll, and the current direction is epoll for now, with io_uring not pursued (design/proxy.md "The socket transport: epoll on Linux").

### kqueue on macOS (issue #2215)

Measured on 2026-11-13 on the Mac runner: a Mac mini with Apple Silicon (Mac18,5), macOS 27.2, the isx VM under vfkit with 2 CPUs, and nothing else running.
Two native proxies were built with Oracle GraalVM 25.4 and no PGO, both from branch `issue-2215-kqueue-measured` at `3d0fec256` on the bot's fork (base `main` at `4d1ff50c`).
`kqueue` adds `netty-transport-native-kqueue` (`osx-aarch_64`) with `quarkus.vertx.native-transport=if-available`, and `nio` is the same tree with that profile switched off.
`nio2` is a byte-identical copy of `nio`, run as a noise control.
`bench/run.sh` ran them interleaved, `maven` three times each and `saturate` twice each, with Hyperfoil on the host.
Every run had 100% 2xx, and each proxy's startup line named the transport it ran on.
The raw runs are in [bench/data/proxy-transport-macos-2026-11-13.json](../../bench/data/proxy-transport-macos-2026-11-13.json).

`maven`, median req/s with the three runs, then p50 / p99 in ms:

| Users | nio | nio2 | kqueue |
|---|---|---|---|
| 8 | 1063 (1067, 1063, 1060), 7.4 / 13.8 | 1065 (1065, 1053, 1071), 7.3 / 13.8 | 1076 (1076, 1076, 1074), 7.3 / 13.6, +1.3% |
| 32 | 1076 (1081, 1076, 1067), 29.0 / 57.1 | 1078 (1078, 1062, 1082), 29.0 / 56.9 | 1082 (1081, 1082, 1084), 28.7 / 56.4, +0.6% |
| 64 | 1066 (1067, 1066, 1063), 59.0 / 95.4 | 1067 (1069, 1064, 1066), 59.0 / 100.1 | 1080 (1081, 1080, 1078), 58.2 / 111.7, +1.3% |

`saturate`, median req/s:

| Users | nio | nio2 | kqueue |
|---|---|---|---|
| 16 | 22,095 | 22,033 | 22,146 (+0.2%) |
| 64 | 22,041 | 22,031 | 22,104 (+0.3%) |
| 128 | 22,055 | 22,019 | 22,093 (+0.2%) |
| 256 | 22,089 | 21,995 | 22,119 (+0.1%) |

- **Throughput on `maven`:** kqueue is 0.6 to 1.3% ahead, and the gain is consistent.
  At 8 and at 64 users every kqueue run beats every `nio` and `nio2` run, while kqueue's own runs agree within 0.3%.
  At 32 users the ranges overlap.
- **Throughput on `saturate`:** 0.1 to 0.3% ahead, within the spread of the two NIO copies.
- **Latency:** p50 is the same or slightly lower on kqueue. p99 at 64 users is 111.7 ms against 95.4 ms, but the `nio` runs' p99 there range from 93.8 to 115.3 ms and those of `nio2` from 91.2 to 110.6 ms, so the tails overlap.
- **The load does not scale with users:** `maven` stays near 1,070 req/s from 8 to 64 users, at about 670 MB/s, and only the latency grows. What bounds it was not measured. Whatever it is, the socket transport is a small part of it on this Mac.
- **Size and memory:** 83,331,048 bytes for `nio` and 84,356,216 for `kqueue` (+1.2%). Idle RSS is 45,392 against 47,104 KiB, and peak RSS on `maven` 75,184 against 77,104 KiB (`ps` on macOS). Startup is 293 against 296 ms.

kqueue stays off on macOS, and the proxy there runs on NIO as before (design/proxy.md "The socket transport: epoll on Linux").
The gain is real but about 1%, and epoll's on Linux was 3 to 15%.
Against it stands what enabling kqueue takes in a native image, where Quarkus does nothing for kqueue as it does for epoll:
- The JNI library (`META-INF/native/libnetty_transport_native_kqueue_aarch_64.jnilib`) has to be listed in `quarkus.native.resources.includes`. Without it the library is not in the image, and the proxy logs that it fell back to NIO.
- With the library in but no JNI metadata, startup does not fall back: Vert.x's check for kqueue throws `NoClassDefFoundError: io/netty/channel/kqueue/KQueueStaticallyReferencedJniMethods`, and the proxy exits.
  The metadata measured here is kept in [bench/data/proxy-kqueue-reachability-metadata.json](../../bench/data/proxy-kqueue-reachability-metadata.json).
  It was recorded with the native-image agent over the bind and TLS tests, and is conditioned on `io.netty.channel.kqueue.Native`.
  It would go in `proxy/src/main/resources/META-INF/native-image/dev.incusspawn/incus-spawn-proxy/reachability-metadata.json`, which needs `git add -f`, because `.gitignore` excludes `META-INF/`.
- That metadata must keep up with Netty's native code. If a Netty upgrade made it stale, the proxy would exit at startup on every Mac instead of falling back to NIO.

## Java deserialization in the native images (issue #458)

Measured on 2026-11-12 on `main` at `8c671fb4` (Quarkus 4.0.0.CR1, Vert.x 5.2.3), Oracle GraalVM 25.4 (JDK 25.0.4), x86_64, the proxy at `-O3 -march=haswell`, built with `-H:+PrintAnalysisCallTree`.

| Proxy build | Binary bytes | Reachable methods | `ObjectInputStream.readObject0` reachable |
|---|---|---|---|
| `main` (JCEKS `engineLoad` and Vert.x substitutions) | 104,533,328 | 70,806 | yes, through `JceKeyStore.engineGetKey` |
| + JCEKS `engineGetKey` substitution | 104,140,112 | 70,440 | no |
| + JCEKS `engineGetKey`, without the Vert.x substitutions | 104,467,792 | 70,782 | yes, through `Checker.copySerializable` |

So the `engineGetKey` substitution saves 393,216 bytes (384 KiB) and 366 methods, and the Vert.x substitutions save 327,680 bytes (320 KiB) and 342 methods once it is in.
The CLI binary is 39,652,472 bytes with or without the new substitution, and `readObject0` was already unreachable there.
In the GraalVM 25.5 builder image (`25i5-ol10`), the proxy fails the `readObject0` gate on `main` through the same JCEKS path and passes it with the substitution; the CLI passes it either way.
A hello world with no metadata fails `-H:AbortOnTypeReachable=java.io.ObjectInputStream` on both 25.4 and 25.5, which is why the gate names a method.
A stock `quarkus-vertx-http` app on CR1 loses 327,680 bytes and 375 methods when both the JCEKS and the Vert.x paths are cut.
With the Vert.x substitutions alone its binary keeps the same size and loses one method, because JCEKS keeps the reader in (#529).

## Virtual-thread pollers (issue #2129)

Measured on 2026-11-13 in a Linux VM box (8 vCPUs, AMD Ryzen 9 9950X3D2, kernel 7.3.1, load 0.00 at the start), with nothing else running on the host.
Both binaries are native CLIs from Oracle GraalVM 25.4 (JDK 25.0.4) from the code of a9be9a8b (built at 4957a309, whose main code differs from it only in comments), `-Os`, unprofiled.
`isx-ctl` is that commit with `VirtualThreadPollers.usePlatformThreads()` disabled, so the JDK's Linux default (`VTHREAD_POLLERS`); `isx-fix` is the commit as it is (`jdk.pollerMode=1`).
Each ran `isx ssh-proxy x` against the fake Incus of `bench/poller-mode.sh`, which answers the exec and its websockets at once, under hyperfine with 20 warmups.

| Runs per build | Build | Min | Median | p99 | Mean of runs under 1 s | Runs over 1 s |
|---|---|---|---|---|---|---|
| 300 | `isx-ctl` | 4.9 ms | | | | 5025 ms |
| 300 | `isx-fix` | 5.1 ms | | | | none |
| 1000 | `isx-ctl` | 5.0 ms | 5.4 ms | 6.8 ms | 5.5 ms | none |
| 1000 | `isx-fix` | 5.0 ms | 5.6 ms | 7.5 ms | 5.7 ms | none |
| 3000 | `isx-ctl` | 4.8 ms | 5.4 ms | 6.7 ms | 5.5 ms | 5006, 5025 ms |
| 3000 | `isx-fix` | 5.1 ms | 5.5 ms | 6.5 ms | 5.6 ms | none |
| 10000 | `isx-ctl` | 4.8 ms | 5.4 ms | 7.2 ms | 5.5 ms | 2006, 2006, 5026 ms |
| 10000 | `isx-fix` | 5.0 ms | 5.5 ms | 7.2 ms | 5.6 ms | none |

The 300-run pass kept only hyperfine's summary, so its median and p99 are not recorded; its `isx-fix` maximum was 25.5 ms.
Its one `isx-ctl` stall is read from the mean: 22.3 ms over 300 runs leaves room for only one run of 5 s.
Both builds also had runs of 10 to 30 ms, 71 and 68 of the 10,000, so the mode does not change those.

- **Cost:** platform pollers add about 0.1 ms to a run, on a median of 5.4 ms.
- **Stalls:** the default mode stalled in 6 of 14,300 runs and platform pollers in none of 14,300.
  Were the mode irrelevant, all six falling in one build would happen with a chance of 1 in 32.
- **What ended them:** each stall lasted a timer's length.
  The 5 s ones are `OutputDrain`'s ceiling, which ends a drain only while a reader has not taken a close frame already in its socket.
  The 2 s ones are the stdin forwarder's `join(2000)` in `IncusApi.execWebSocket`.
  So in both a virtual thread waiting on a socket was not woken before a timer gave up on it, the same thing CI showed for 30 s (design/native-image.md "Socket wakeups come from platform threads").

### Stalls by CPU count

Measured on 2026-11-13 in a Linux box with 32 CPUs (AMD Ryzen 9 9950X3D2, kernel 7.3.3), native CLIs from Oracle GraalVM 25.0.5, unprofiled.
The build is 8d489502 with one addition, an environment variable that picks the poller mode, so one binary ran both modes.
Each run was `isx ssh-proxy x` against `bench/fake-incus-exec.py`, one after another, held to its CPUs with `taskset`; the fake Incus was not held.
A stall is a run over 1 s; every one lasted 2.0 s, the stdin forwarder's join.

| Poller mode | CPUs allowed | Runs | Stalls |
|---|---|---|---|
| default (`VTHREAD_POLLERS`) | 32 | 15,000 | 0 |
| default | 2 | 15,000 | 12 |
| default | 1 | 5,000 | 79 |
| default, a second pass | 1 | 5,000 | 42 |
| platform (`jdk.pollerMode=1`), by the variable | 1 | 15,000 | 0 |
| platform, 8d489502 unchanged | 1 | 15,000 | 0 |

- **Fewer free cores, more stalls:** the lost wakeup needs a sub-poller to wait for a CPU between two unparks, so the rate rises from none to about 1 in 80 as the cores go.
  Held to one CPU the native image still counted two processors, so it kept two carriers, which the stall needs.
- **JVM:** the JVM build with `-XX:ActiveProcessorCount=1 -Djdk.pollerMode=2` did not stall in 3,000 runs, six at a time.
- **At the stall:** the stdin forwarder is parked in `Poller.poll` reading its handshake reply, and its sub-poller is parked there too, with no permit and off the master poller's list.
  A build with tracing patched into `sun.nio.ch.Poller` and `java.lang.VirtualThread` (`-J--patch-module=java.base=<classes>` to the image builder) showed the order: the initializing thread's unpark, the master poller's unpark finding the permit set, one resume, then the park that never ends.

## The launcher's machine stops at 248 held connections, on a Mac (issue #2031)

Measured on 2026-11-13 on the Mac runner (macOS 27.2, Mac18,5) by `appliance/launcher-on-mac.sh` with `isx-vm` of commit `695969b8`; the record is on PR #2197.
`appliance/measure-tunnel.sh` held event streams of the Incus API through the tunnel and sampled a request beside them.

| Connections asked for | Accepted | Still open at the end | Request on a new connection, median | On a kept connection, median |
|---|---|---|---|---|
| 0 | 0 | 0 | 0.288 ms | 0.116 ms |
| 200 | 200 | 200 | 0.310 ms | 0.119 ms |
| 400 | 248 | 0 | none answered | none answered |

After the 248th connection the framework stopped the machine: `isx-vm`'s last line is "the machine stopped: Internal Virtualization error. The virtual machine stopped unexpectedly."
`isx-vm` itself held 200 connections, which is 400 descriptors, so its own raised limit was not what ran out.
248 is 8 below 256, the number of descriptors macOS gives a process that launchd starts.
The framework runs the machine in such a process (`com.apple.Virtualization.VirtualMachine`), and that one holds a descriptor for each vsock connection.
So the reading is that it had 8 open anyway and no room for the 249th connection; its count was not read on the Mac that day.
Another project on the same framework reports that process limited to 256 descriptors, its machine stopping as leaked connections add up, and crash reports that name the descriptor limit (openai/tart issue 1410).
vfkit's row is empty: its tunnel stopped answering before the held connections were measured.

What follows is in design/macos-and-vms.md "The launcher's machine": the relays hold at most 192 connections to the guest at once.
What this does not show is how many descriptors the share of the home takes in that process, which the next record on a Mac reads.

## The vsock tunnel's relay under load, on Linux (issue #2031)

Measured on 2026-11-13 in a quiet window, in an isx VM under nested KVM (x86_64, 8 vCPUs, 16 GiB, QEMU 11.1.3), with the scripts as #2197 first proposed them.
The question was whether the launcher's relay holds up under the load that makes vfkit's fail, and what a request costs through it.
This says nothing of Virtualization.framework: that half is `appliance/launcher-on-mac.sh` on a Mac, and its record is on the PR.
The counts held were 0, 200 and 400, the script's defaults then; they are 0, 150 and 400 since the Mac's machine was found to hold fewer than 256.

The v0.5.0 appliance was booted under QEMU with `vhost-vsock-pci` (2 vCPUs, 2 GiB), anew for each bridge, and `appliance/measure-tunnel.sh` was run with its default counts through three bridges in turn:

- **vfkit's relay logic**: tcpproxy's `DialProxy` with the vsock side wrapped as vz wraps it, the stand-in of design/macos-and-vms.md "Where the vsock leak comes from".
  It is not vfkit itself.
- **`socat`**: `UNIX-LISTEN:…,fork,backlog=128 VSOCK-CONNECT:3:<port>`, CI's bridge before the relay.
- **the relay**: `isx-vsock-bridge`, a release build of this commit's `launcher/`.

| | vfkit's relay logic | `socat` | the relay |
|---|---|---|---|
| `test-tunnel.sh`, 24 runs: passed | 0 | 24 | 24 |
| Runs in which a forwarder child outlived its connection | 24 | 0 | 0 |
| The 24 runs took | 1750 s | 299 s | 299 s |
| Forwarder back to its listener after a run, median | 19985 ms | 12 ms | 11 ms |
| Bursts of 70, five of them: answered | 350 of 350 | 350 of 350 | 350 of 350 |
| Bursts of 120, five of them: answered | 600 of 600 | 600 of 600 | 600 of 600 |
| Forwarder back to its listener after a burst, median | 30250 ms | 12 ms | 12 ms |
| Nothing held: request on a new connection, median and 95th percentile | 2.005 and 3.375 ms | 1.856 and 2.420 ms | 1.619 and 2.146 ms |
| Nothing held: request on a kept connection | 1.144 and 1.411 ms | 0.969 and 1.088 ms | 1.039 and 1.531 ms |
| 200 held: request on a new connection | 3.291 and 4.681 ms | 2.465 and 3.314 ms | 2.171 and 2.974 ms |
| 200 held: request on a kept connection | 1.335 and 1.831 ms | 1.057 and 1.677 ms | 1.066 and 1.634 ms |
| 400 held: request on a new connection | 2.412 and 3.846 ms | 2.509 and 3.281 ms | 1.931 and 2.628 ms |
| 400 held: request on a kept connection | 1.198 and 2.157 ms | 1.153 and 1.650 ms | 1.101 and 1.716 ms |
| Forwarder back to its listener after the 200 were closed | 30407 ms | 20 ms | 32 ms |
| Forwarder back to its listener after the 400 were closed | 30353 ms | 564 ms | 557 ms |
| Forwarder count at the end (1 with nothing connected) | 2 | 1 | 1 |
| The whole measurement took | 2186 s | 343 s | 342 s |

A request is `GET /1.0` on the Incus socket, 200 of them for each latency.
Every held connection was accepted and was still open when its sampling ended, with all three bridges.
No request of a burst or of a latency sample failed, timed out or got an empty reply with any bridge.

What it shows:

- The relay ends every connection in the guest when the host ends it, under this load as well as in the unit tests.
  Through vfkit's logic every run leaves forwarder children behind, and the forwarder is back to its listener only 20 to 30 s after the load ends.
  The 24 runs then take 1750 s instead of 299 s.
- The relay costs no more than `socat` for a request.
  On a new connection its median is below `socat`'s in all three rows, and on a kept one the two are within 0.1 ms of each other.
  Each figure is one sample of 200 requests, so a difference of a tenth of a millisecond is not a finding.
- With 200 or 400 connections held, a request through the relay costs 0.3 to 0.6 ms more on a new connection than with none held, and under 0.1 ms more on a kept one.
- After the load the relay's process held 10 descriptors and 6 threads, and its peak resident memory was 19 MB.

What it does not show: the failures a Mac sees beyond the leaked children, missed requests, `Empty reply from server` and a socket that stops answering (P2).
Linux reproduces none of them with vfkit's logic, so only the Mac's record compares the two programs on those.
