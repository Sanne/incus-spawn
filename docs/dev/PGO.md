# Profile-guided optimization plan

Plan, agreed 2026-10-24, for building the native binaries with real profiles instead of the ML-inferred ones `-O3` used.
Each phase records its outcome in [PERFORMANCE-NOTES.md](PERFORMANCE-NOTES.md) when it runs.
Where the implementation differs from the plan, this page says so beside the plan's text, marked **Implemented:**.

## Status (2026-10-25)

| Phase | State |
|---|---|
| 0, feasibility | Done (#1639) |
| 1, workloads | Done (#1639) |
| 2, measurement | Done (#1641): adopt PGO for both binaries |
| 3, release wiring | Done in #1642; first released as v0.5.0-dev.1 on 2026-10-25 (PERFORMANCE-NOTES.md, "First release with profiles") |
| 4, docs | Done in #1642 |

Still open:

- **Done:** the quality floors in `.github/scripts/check-pgo-quality.sh` are 12% applicability and 90% relevance.
  The lowest figures measured are 15.04% applicability (the macOS aarch64 CLI, in every dispatch and in v0.5.0-dev.1) and 97.04% relevance (the macOS aarch64 proxy, Phase 3 dispatch run 40186966586).
  That leaves 3 points of applicability and 7 of relevance for drift between releases, while a profile that did not apply or fit, which reads near zero, still fails.
- Host routes and the MCP bridge have no load, so they compile as cold code.
  **Done (#1651):** the npm path and the WebSocket relay now have loads (`--load=npm`, `--load=websocket`) and are in the release profile, collected at a lighter weight so they do not outweigh the rest.
  Throughput on both is unchanged on the measuring host, since TLS and the network bound them, for about 1.5 MB more proxy; see the performance notes, "The npm and WebSocket paths".
- The Intel macOS build stays unprofiled (Phase 3 below).

## Goal

The released `isx` and `isx-proxy` binaries are built with profiles collected in the same release run, on every platform.
Collecting profiles that only a benchmark build uses is not the goal.

The release artifacts come out of it measurably better on three axes, in this order of importance:

1. **Faster** on the paths users wait on: proxy throughput on the credential and artifact loads, CLI startup, and the ssh and git relays.
2. **Leaner on memory**: proxy idle RSS and RSS under load no higher, and lower where PGO's smaller cold code allows it.
3. **Smaller**, or at least no larger: the proxy under its 86 MB and the CLI near its 30 MB.

**Implemented:** those sizes were stale when the plan was written.
The release builds at the time measured 99.3 MB (proxy) and 37.9 MB (CLI), and the goal was judged against those.
With PGO the proxy is 39.5 to 42.9 MB on the profiled platforms and the CLI 38.6 to 40.3 MB (Phase 3 below).

Every axis has a measurement in `bench/`, and no change from this plan merges without the measurements below showing the goal is met.
A result that is faster but larger, or smaller but slower, is a trade to decide on explicitly, not to merge by default.

## Why it can pay off, and where it cannot

Every release platform, `bench/run.sh` and the install script's builder image use Oracle GraalVM, the edition that ships `--pgo-instrument` and `--pgo`.
No toolchain change is needed.

- **Proxy, cached artifact path.** The event loop is idle half the time while serving, and the rest is socket writes and AES.
  A compiler cannot move that, so the 960 MB/s headline is not expected to change.
- **Proxy, credential paths.** Header rewriting, Vertex body translation, WebSocket relay and jjq parsing are Java code per request.
  They are unmeasured today and are where PGO has something to bite.
- **Proxy size and startup.** PGO compiles cold code small and lays hot code out contiguously, so 85.8 MB and 514 ms may both drop.
- **CLI.** `-Os` won over `-O3` because 216 µs was not worth 41 MB.
  PGO changes that trade: hot code gets `-O3`, cold code stays small, and `CodeSectionLayoutOptimization` exists only with `--pgo` and cuts startup page faults.
  The question is whether a PGO CLI starts faster than `-Os` at near `-Os` size.
- **CLI relays.** `isx ssh-proxy` and `git-remote-isx` pump every byte of a session through Java.
  Those loops are sustained throughput, not startup, and are the CLI paths PGO is built for.

## Mechanics

- Two builds per binary: an instrumented one runs the workload and dumps a profile at exit, then an optimized one reads it.
- The existing property plumbing carries both with no pom change: `-Dnative.optimization=-O3,--pgo-instrument`, later `-Dnative.optimization=-O3,--pgo=<file>`.
  Once adopted, the flag gets its own placeholder in the argument list so `NativeImageInitializationTest` keeps its guarantees.
  **Implemented:** the placeholder is `native.pgo.args`, last in each module's list, set by two pom profiles: `-Dnative.pgo.instrument` and `-Dnative.pgo=<dir>` (an absolute path holding `cli.iprof.gz` and `proxy.iprof.gz`; the CLI then builds at `-O3`).
- Each instrumented process writes one profile.
  Name each with `-XX:ProfilesDumpFile=<flow>-<n>.iprof`, pass them all to `--pgo`, and have the optimized build write the merged result with `-H:PGODumpLoadedIprofPath`.
  Counts add up, so the mix of runs is the weighting.
  **Implemented:** release builds pass `-H:-ParseRuntimeOptions`, so an instrumented binary ignored `-XX:ProfilesDumpFile` and every process overwrote `default.iprof`.
  The instrument profile appends `-H:+ParseRuntimeOptions`.
  `scripts/pgo-merge.sh` merges one binary's dumps with a build using `-H:PGODumpLoadedIprofPath`.
- `-H:+PGOPrintProfileQuality` prints relevance and applicability at the optimized build.
  That is the gate for cross-platform profiles.
- An unrepresentative profile is worse than none: PGO optimizes what the counters call hot and compiles the rest for size.
  A profile from `--help` would penalize the branch and shell paths.
- Instrumented binaries are slow and large.
  Never quote a number measured on one.
- Quarkus 3.40 has `quarkus.native.pgo.enabled`, which builds instrumented, runs native integration tests and rebuilds.
  It ties the profile to the test workload and needs Incus, so it is not the release mechanism.

## Phase 0: feasibility, measured 2026-10-24

Instrumented proxy build on a 32-core Linux x86_64 box, Oracle GraalVM 25.4.4.1.1, `-O3,--pgo-instrument` through the property above.

| What | Result |
|---|---|
| Instrumented build, wall clock | 53 s |
| Instrumented binary | 148 MB, against 86 MB for the release build |
| Profile from one `--help` run | 10.3 MB JSON, 1.8 MB gzipped |
| Profile contents | 119k methods, 16k call-count and 12k branch profiles |

A clean exit dumps the profile.
Most of the file is the type and method tables, so size grows little with workload.
Still to check: that the proxy dumps when the bench stops it with `kill`, and the CLI's instrumented build.

**Done:** SIGTERM dumps the profile, and the CLI builds instrumented (132 MB, 94 s on an 8-core VM).

## Phase 1: workloads

Collection needs a real Incus.
The fake daemon the tests use is an in-process transport, not a socket server, so a native binary cannot talk to it.
Run it on a Linux host set up with `scripts/local-incus.sh`, or in a branched instance with Incus inside.

**Proxy**: one instrumented process, driven in sequence by the `maven` and `saturate` loads and a new Hyperfoil load for Anthropic header injection and Vertex translation against the upstream stub.

**Implemented:** one process per load (`maven`, `saturate`, `npm`, `anthropic`, `vertex`, `websocket`), since counts add up across files, plus the proxy that serves the CLI flows.
The `npm` and `websocket` loads (#1651) collect from shorter definitions of their own: at full length they made the Vertex path 12% slower.
The model loads run Hyperfoil inside an instance branched from `tpl-isx-bench`, because the proxy injects only for a caller it knows by address and proof.
They run on a config of their own (`ISX_BENCH_CONFIG`), never the user's.

**CLI**, by weight:

- Heavy: branch, shell, stop and delete, many repetitions.
  `ssh-proxy` in both patterns: many short `ssh <instance> true` connections, and a few scp transfers of a large file.
  `git-remote-isx`: a clone, a fetch and a push against an instance, repeated.
  `isx list` in table, plain and json.
- Moderate: TUI open, navigate, instance details, quit, scripted under tmux.
- Once: `--help` and `templates list`, so their code is present but not weighted.
- Out: `doctor`, template builds, `init`, account commands, completion generation.
  Rare operations, and a build's heavy work is native git anyway.

The collection scripts live in `bench/` as a collect mode of `bench/run.sh` and `bench/cli.sh`, writing profiles to a known directory.

**Implemented:** `bench/pgo-collect.sh` runs both (`--collect=DIR`).
The TUI is scripted on a pseudo-terminal with `bench/isxbench.py`, not tmux.
`isx` has no `stop` command, so the cycle stops the instance with `incus stop` and shells into it again.

## Phase 2: measurement

Interleaved A/B in one session, per the caveats in the performance notes.
Decision rules are fixed before measuring.

- Proxy: `-O3` against `-O3` plus PGO on all three loads, startup, idle RSS, RSS under load and size.
  Adopt if any load gains about 5% with no size, RSS or startup regression.
- CLI: `-Os` against `-O3` plus PGO and `-Os` plus PGO on startup median and size, plus two new metrics: scp throughput through the ProxyCommand and wall clock of `ssh <instance> true`.
  Adopt only if startup is at or below `-Os` and size stays near 30 MB, or the relays gain clearly at a size the download cost justifies.

Both new CLI metrics go into `bench/cli.sh` so they are reproducible on demand.
RSS is the noisiest of these figures, as the notes record, so it is measured with repetition and counterbalanced order, never from a single run.

**Done** with `bench/ab.py`; the tables are in the performance notes, "Phase 2".
Proxy with PGO: 57.6% smaller, 39% lighter idle, faster on every load (cached artifacts +19%, Vertex calls +83%), same startup.
Adopted.
CLI at `-O3` with PGO: starts 17 to 20% faster than `-Os` at 0.66 MB more, and nothing regresses; its scp relay gains about 14%, a noisy figure.
Adopted.

## Verification before merging

The PR that turns PGO on for releases carries the evidence for each axis of the goal, in this order:

1. The phase 2 A/B tables, added to the performance notes, with the conditions they were measured under.
2. The `PGOPrintProfileQuality` output of the optimized build on each of the four release platforms, from a `workflow_dispatch` run of the release's `profiles` and `build` jobs on the PR's commit, so a collection failure, a macOS build failure or an unusable profile is found before a tag.
3. The binary sizes from that run beside the previous release's, for both binaries and all four platforms.
4. The release smoke test passing on the PGO binaries.

A later PR that changes build flags, bumps GraalVM or changes the collection flows repeats items 2 and 3, and item 1 when it claims a speed or memory change.

**Done for #1642**, from `workflow_dispatch` run 40186966586 of `release.yml` (on the bot's fork); items 2 and 3:

| Platform | CLI applicability / relevance | Proxy applicability / relevance | `isx-proxy`, v0.4.2 → PGO | `isx`, v0.4.2 → PGO |
|---|---|---|---|---|
| Linux amd64 | 15.21% / 100.00% | 20.04% / 100.00% | 99.09 → 42.21 MB | 37.88 → 38.60 MB |
| Linux aarch64 | 15.16% / 99.75% | 20.00% / 99.96% | 89.59 → 42.93 MB | 39.39 → 40.04 MB |
| macOS aarch64 | 15.05% / 98.49% | 19.58% / 97.04% | 79.46 → 39.49 MB | 39.53 → 40.25 MB |
| macOS x86_64 | unprofiled | unprofiled | 83.31 → 83.56 MB | 38.51 → 38.64 MB |

Item 4: the release smoke test passed on all four platforms, and the keychain smoke on both macOS builds.

## Phase 3: collecting and applying profiles in the release run

Profiles are produced inside the release workflow and never stored anywhere else.
Releases are infrequent, so the extra minutes cost little, and the profile always matches the commit being released exactly.
Nothing goes stale, no repository grows, and no asset or cache needs a checksum.

The release workflow gains a `profiles` job that the four platform `build` jobs depend on:

1. On the Linux amd64 runner, set up Incus the way the integration workflow does.
   **Implemented:** the collection itself runs under `sudo --preserve-env -u "$USER" env PATH="$PATH"`, never `sg incus-admin`.
   `sg` makes `incus-admin` the primary group, and the model loads' rootless podman (which extracts Hyperfoil) then fails: `newuidmap` refuses a process "owned by a different user".
   `sudo -u` gives the user its groups as a login would, `incus-admin` included, and keeps its own primary group.
2. Build both binaries instrumented, run the phase 1 collection flows against them, and merge the per-process dumps into one profile per binary.
3. Upload the two merged profiles as a workflow artifact, kept long enough for a local build to fetch them.
   **Implemented:** the `pgo-profiles` artifact (`cli.iprof.gz`, 1.7 MB; `proxy.iprof.gz`, 2.5 MB; `commit`) is kept 90 days.

Each `build` job then downloads the artifact and builds both binaries once, with `--pgo` and `-H:+PGOPrintProfileQuality`.

- Collect on Linux x86_64 and apply to all four platforms.
  Profiles key on methods, so OS-specific code falls back to inferred profiles.
  Print applicability per platform in the build log and fail under a floor.
  **Implemented:** `.github/scripts/check-pgo-quality.sh` fails a build unless both binaries print their quality line at or above 12% applicability and 90% relevance.
  Each build also refuses profiles collected from another commit (the artifact's `commit` file).
- The Intel macOS build runs GraalVM 25.0, so its profile version may mismatch.
  Use `PGOIgnoreVersionCheck` there or skip PGO on that legacy platform.
  **Implemented:** skipped; that build stays unprofiled (`pgo: false` in the matrix), and `ReleasePgoWorkflowTest` holds every profiled build to the profiles job's GraalVM.
- A release whose collection fails, fails.
  There is no older profile to fall back on, and shipping an unprofiled binary silently would defeat the goal.
  A flaky collection step is fixed, not tolerated, and the job can be rerun.
- Activate `--pgo` through a Maven property that is empty by default, so a local or Community Edition build from source still works and stays unprofiled unless given a profile.
  PGO is Oracle-only, like G1 on macOS.
  A local build that wants PGO downloads a release run's artifact and passes it.

## Phase 4: docs in the same PR

Performance notes, a DESIGN.md subsection beside "Native image CPU baseline", the module lines in CLAUDE.md that describe `-O3` as ML-inferred PGO, `bench/README.md` and `.claude/rules/native-image.md`.

**Done** in #1642, which also updates `.claude/rules/ci.md` and this page.
