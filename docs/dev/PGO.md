# Profile-guided optimization plan

Plan, agreed 2026-10-24, for building the native binaries with real profiles instead of the ML-inferred ones `-O3` uses today.
Nothing here is shipped yet.
Each phase records its outcome in [PERFORMANCE-NOTES.md](PERFORMANCE-NOTES.md) when it runs.

## Goal

The released `isx` and `isx-proxy` binaries are built with profiles collected in the same release run, on every platform.
Collecting profiles that only a benchmark build uses is not the goal.

The release artifacts come out of it measurably better on three axes, in this order of importance:

1. **Faster** on the paths users wait on: proxy throughput on the credential and artifact loads, CLI startup, and the ssh and git relays.
2. **Leaner on memory**: proxy idle RSS and RSS under load no higher, and lower where PGO's smaller cold code allows it.
3. **Smaller**, or at least no larger: the proxy under its 86 MB and the CLI near its 30 MB.

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
- Each instrumented process writes one profile.
  Name each with `-XX:ProfilesDumpFile=<flow>-<n>.iprof`, pass them all to `--pgo`, and have the optimized build write the merged result with `-H:PGODumpLoadedIprofPath`.
  Counts add up, so the mix of runs is the weighting.
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

## Phase 1: workloads

Collection needs a real Incus.
The fake daemon the tests use is an in-process transport, not a socket server, so a native binary cannot talk to it.
Run it on a Linux host set up with `scripts/local-incus.sh`, or in a branched instance with Incus inside.

**Proxy**: one instrumented process, driven in sequence by the `maven` and `saturate` loads and a new Hyperfoil load for Anthropic header injection and Vertex translation against the upstream stub.

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

## Phase 2: measurement

Interleaved A/B in one session, per the caveats in the performance notes.
Decision rules are fixed before measuring.

- Proxy: `-O3` against `-O3` plus PGO on all three loads, startup, idle RSS, RSS under load and size.
  Adopt if any load gains about 5% with no size, RSS or startup regression.
- CLI: `-Os` against `-O3` plus PGO and `-Os` plus PGO on startup median and size, plus two new metrics: scp throughput through the ProxyCommand and wall clock of `ssh <instance> true`.
  Adopt only if startup is at or below `-Os` and size stays near 30 MB, or the relays gain clearly at a size the download cost justifies.

Both new CLI metrics go into `bench/cli.sh` so they are reproducible on demand.
RSS is the noisiest of these figures, as the notes record, so it is measured with repetition and counterbalanced order, never from a single run.

## Verification before merging

The PR that turns PGO on for releases carries the evidence for each axis of the goal, in this order:

1. The phase 2 A/B tables, added to the performance notes, with the conditions they were measured under.
2. The `PGOPrintProfileQuality` output of the optimized build on each of the four release platforms, from a `workflow_dispatch` run of the release's `profiles` and `build` jobs on the PR's commit, so a collection failure, a macOS build failure or an unusable profile is found before a tag.
3. The binary sizes from that run beside the previous release's, for both binaries and all four platforms.
4. The release smoke test passing on the PGO binaries.

A later PR that changes build flags, bumps GraalVM or changes the collection flows repeats items 2 and 3, and item 1 when it claims a speed or memory change.

## Phase 3: collecting and applying profiles in the release run

Profiles are produced inside the release workflow and never stored anywhere else.
Releases are infrequent, so the extra minutes cost little, and the profile always matches the commit being released exactly.
Nothing goes stale, no repository grows, and no asset or cache needs a checksum.

The release workflow gains a `profiles` job that the four platform `build` jobs depend on:

1. On the Linux amd64 runner, set up Incus the way the integration workflow does.
2. Build both binaries instrumented, run the phase 1 collection flows against them, and merge the per-process dumps into one profile per binary.
3. Upload the two merged profiles as a workflow artifact, kept long enough for a local build to fetch them.

Each `build` job then downloads the artifact and builds both binaries once, with `--pgo` and `-H:+PGOPrintProfileQuality`.

- Collect on Linux x86_64 and apply to all four platforms.
  Profiles key on methods, so OS-specific code falls back to inferred profiles.
  Print applicability per platform in the build log and fail under a floor.
- The Intel macOS build runs GraalVM 25.0, so its profile version may mismatch.
  Use `PGOIgnoreVersionCheck` there or skip PGO on that legacy platform.
- A release whose collection fails, fails.
  There is no older profile to fall back on, and shipping an unprofiled binary silently would defeat the goal.
  A flaky collection step is fixed, not tolerated, and the job can be rerun.
- Activate `--pgo` through a Maven property that is empty by default, so a local or Community Edition build from source still works and stays unprofiled unless given a profile.
  PGO is Oracle-only, like G1 on macOS.
  A local build that wants PGO downloads a release run's artifact and passes it.

## Phase 4: docs in the same PR

Performance notes, a DESIGN.md subsection beside "Native image CPU baseline", the module lines in CLAUDE.md that describe `-O3` as ML-inferred PGO, `bench/README.md` and `.claude/rules/native-image.md`.
