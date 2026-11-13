# Modules and native images

How the code is split into modules, how the binaries are built, and what the native images must not do.

- **Java 25**, **Quarkus 4.x** with aesh for CLI commands (the proxy runs on Vert.x 5 and Netty 4.2; see [docs/dev/QUARKUS-4.md](../docs/dev/QUARKUS-4.md))
- **Tamboui** (https://tamboui.dev/) for interactive TUI (list view, modal dialogs, inline actions)
- **GraalVM native image** for optional zero-dependency distribution
- **jjq** for YAML and JSON (see "jjq rather than Jackson" below)
- **JBang** for easy installation (`jbang app install isx` plus `isx-proxy`)

## Module Structure

Three Maven modules under a parent POM:

- **`common`** (`incus-spawn-common`): shared code — Incus client, proxy config, image/tool definitions, configuration loading.
  Not a Quarkus app; uses the Jandex Maven plugin to produce a bean index so Quarkus discovers its CDI beans from dependent modules.
  Its test classes are published as a test-jar, which `cli` depends on in test scope, so a command's wiring is tested against the same `FakeIncusDaemon` as `common`'s flows (see design/testing.md "Request budgets").
  The test-jar is built even under `-Dmaven.test.skip=true`, since `cli` could not resolve it otherwise.
- **`cli`** (`incus-spawn`): the main CLI/TUI binary (`isx`).
  Depends on common.
  Native image: serial GC, `-Os` (size-optimized); release builds are `-O3` with a PGO profile, see "Profile-guided optimization" below.
- **`proxy`** (`incus-spawn-proxy`): the standalone MITM proxy binary (`isx-proxy`).
  Depends on common.
  Native image: G1 GC on Linux and serial GC on macOS, `-O3` (throughput-optimized) with a PGO profile in release builds, on x86_64 `-march=haswell` and on aarch64 `-march=armv8.1-a+aes` — see "Native image CPU baseline", "Profile-guided optimization" and "Proxy garbage collector per platform" below.
  Splitting this out removed Vert.x from the CLI, which means the CLI can no longer serve the proxy itself — see "Every install channel ships both binaries" below.

## Every install channel ships both binaries

Before the module split the CLI could run the proxy inline, so an installation that somehow lacked `isx-proxy` still worked.
It cannot now: `isx proxy start` only launches the separate binary.
That makes "both binaries are installed" an invariant of every channel — `get-isx.sh`, `install.sh` (in both native and JVM modes), Homebrew, apt, and JBang — rather than a nicety.

JBang is where this went wrong (issue #701).
Its catalog installs one alias per command, so publishing only `isx` produced installations with no proxy at all, and the failure did not surface as a missing binary: `ProxyService` wrote a unit exec'ing `isx proxy start` as a fallback, that command found the service installed and unhealthy, and restarted it — restarting the unit running the very process making the call.
`restart()` clears systemd's start rate limiter with `reset-failed` on every pass, so the loop could not even burn itself out.
These things keep it fixed:

- `jbang-catalog.json` publishes `isx` **and** `isx-proxy`, and the release uploads `isx-proxy-runner.jar` alongside `incus-spawn-runner.jar`.
  `JbangCatalogTest` fails if an alias ever names an asset `gh release create` does not upload — the build step alone does not satisfy it, because a jar that is built and not uploaded is exactly this bug.
- The unit and the launchd plist exec `isx-proxy` directly; there is no `isx proxy start` fallback, and `install()` refuses to write service files without a proxy binary rather than installing something that cannot work.
- `ProxyStartCommand` resolves the proxy binary *before* any service management, so a missing binary produces an `EXIT_CONFIG` failure instead of a restart that cannot possibly help.
- On macOS that exit code is not enough.
  launchd has no `RestartPreventExitStatus`, so a `KeepAlive` job that exits 78 is started again every `ThrottleInterval` — the error is reprinted every ten seconds and nothing changes — and `RunAtLoad` would bring a merely booted-out job back at the next login.
  `haltUnusableMacOsService()` therefore boots the job out *and* removes the plist, which `isx init` rewrites once `isx-proxy` is present.
  It declines when the proxy is answering on 127.0.0.1, so a resolution quirk can never take down a working install.
- `ProxyService.isSupervisedInvocation()` makes `isx proxy start` skip service management when it *is* the service: on Linux by matching systemd's `INVOCATION_ID` against the proxy unit's own invocation, on macOS by matching this process's own pid against what `launchctl print` reports for the job (#977 — restarting the job from inside it can end this very process via `bootout` before it reloads, which is worse than Linux's loop).
  It exists only for a unit/plist written by an older build — this build's execs `isx-proxy`, which never re-enters the CLI — and converts such a unit's loop into a single `EXIT_CONFIG` failure that `RestartPreventExitStatus` halts (or, once `isx-proxy` is installed, into a working proxy), and turns macOS's `bootout` race into this process just running the proxy in the foreground instead.
  Running in the foreground never calls `restartLocked()`, so the supervised invocation would otherwise never rewrite its own stale plist either.
  `ProxyService.migrateMacOsPlistIfSupervised()` does that one job — no bootout or bootstrap, since either would end this very process — so the next login execs `isx-proxy` directly instead of finding the legacy plist stale again.

`isx init` marks itself complete *before* its last step touches the service.
`isx-proxy` exits `EXIT_CONFIG` until that marker exists, so a service installed ahead of it failed its first start on every first-time install: launchd retried only after the ten-second `ThrottleInterval`, by which time init had given up waiting and reported "proxy is not responding" about a proxy that came up by itself moments later (#938), and systemd, told not to restart that exit code, did not retry at all.
The step therefore asks, marks, then acts.
Waiting longer was rejected: it would cost every first install those ten seconds for a start that takes well under one, and would have no effect on Linux.
Dropping the proxy's check was rejected too: it is what turns a proxy started on a half-configured host into a clear message.
The price is that an install which fails after the marker leaves init recorded as complete, and nothing offers the service again; it is optional, its failure is printed, and `isx proxy install` remains.

That fixed one reason for a failed first start, not the waiting: any other (the VM-facing bridge not discoverable yet, say) still reached a five-second wait for health that ended before launchd's second start.
On macOS the waits after the proxy job is started or restarted therefore outlast `ThrottleInterval` (`ProxyService.awaitStarted`, whose `startWaitSeconds` shares `LAUNCHD_THROTTLE_SECONDS` with the plist).
A wait that still times out prints what `launchctl print` says of the job (`state`, `runs`, and `last exit code` or the `last terminating signal`), so "not responding" tells a job launchd is about to retry from one that keeps exiting (#969).
Unlike making init wait out the throttle, this costs a healthy start nothing: the wait ends at the first answer from `/health`.
The price falls on the failures.
A proxy that never comes up, one that exits on a configuration error at every retry included, is reported after fifteen seconds on macOS instead of five to ten.
The `launchd:` line under the message is what says the time went to retries.

Outside init, the same marker gates `isx proxy install` itself (`ProxyService.initComplete()`, in `install()` for every caller and at the top of the command).
Before init, or after an upgrade that raised `INIT_VERSION`, it refuses with "run `isx init` first" instead of writing a service that can only report a proxy that is not responding (#968).
The command checks before its running-service path too, because `install.sh` runs it after every upgrade: a restart there would replace a working proxy with one that refuses to start, while refusing leaves the old one serving until init runs.

Every other path that starts or restarts the service refuses the same way (#1048): `restartLocked()` (so `isx proxy restart`, `isx doctor`'s remediations and the auto-restart), `startService()`, and `reinstallIfChanged()`/`upgradeIfNeeded()`, which fire on exactly the version drift an upgrade causes.
Those two refuse before rewriting the service files, which would otherwise compare equal on the run of `isx init` that could restart onto them, but after the macOS halt of a service whose binary has gone.
`restartLocked()` checks under the lock, after `restartIfUnhealthy()` has found the proxy unhealthy, so a healthy proxy is never refused.
`isx init` restarts the proxy in its firewall step, before the marker exists.
On a re-run after an `INIT_VERSION` bump that restart waits for the last step, which restarts a running service it found with a stale marker once the marker is written (unless the upgrade there did).
It reads the marker rather than a flag the firewall step set, so a run that stopped between the two still has the restart made by the next.

On macOS a restart of the service is `launchctl kickstart -k`, not `bootout` followed by `bootstrap`.
`bootout` returns before launchd has removed the job: a proxy that does not exit on SIGTERM is killed only at the plist's `ExitTimeOut`, and until then `bootstrap` fails with `5: Input/output error` while `launchctl print` still finds the job.
The old sequence took that `print` for success, reported "Proxy service restarted", and moments later nothing was loaded at all.
So the automatic restart never recovered a hung proxy while a later `isx proxy start`, finding no job, simply loaded one (#916).
`kickstart -k` is launchd's own restart: the job stays loaded, so there is no teardown to race.
The proxy's plist sets that `ExitTimeOut` to 15 s, past the proxy's own 10 s force-exit, so launchd does not kill a slow stop before it has warned about what did not close; a reload's wait for the job to be gone outlasts it, or it would give up and leave no job loaded (#1905).
Only a changed plist has to be unloaded and loaded again, and then the restart waits until `print` no longer finds the job.
In both cases the restart has worked when `launchctl` returned 0 and the job is running.
Sleeping between `bootout` and `bootstrap` was rejected as timing-dependent and still blind to a failed `bootstrap`; retrying `bootstrap` on error 5 was rejected because 5 is also what a rejected plist returns.
`isx proxy stop` waits the same way until `print` no longer finds the job, and fails when it still does after that wait (#1959).
Returning at `bootout` let an `isx proxy start` straight after it find the dying job loaded, answer "already running", and leave no job loaded once launchd was done.
Making the start treat a job being unloaded as not running was the alternative, but a stop that returns with the job still there misleads every caller, not only the start, and `isActive()` would have to read launchd's state names to tell the two apart.
The price is a stop of a hung proxy that takes until launchd kills it, about 15 s.

Two gaps surfaced once `kickstart -k` restarted a loaded job in place rather than always reloading it.
First, comparing the plist on disk to what this build would generate (`needsMacOsPlistUpdate`) cannot see every reason to reload.
A drift restart assessed from a health signal (a stale cert, a wrong bridge address) has nothing to do with the plist file.
So `reinstallIfChanged` and `upgradeIfNeeded` now force a reload explicitly instead of asking that comparison, which would otherwise answer "nothing changed" and leave `kickstart -k` running the old job in place.
Second, several `isx` commands can find the proxy unhealthy within the same window — two terminals running `isx branch` at once is the common case — and each one restarting in turn would `kickstart -k` a proxy the first one had just brought up, cutting every instance's connection a second time for no reason.
`ProxyHealthCheck.tryAutoRestart` now re-checks `/health` under `proxy.lock` (`ProxyService.restartIfUnhealthy`) immediately before restarting, the same recheck `reinstallIfChanged` already did for drift.
So the second command finds the proxy healthy and does nothing.

`installMacOs`'s failure message depends on *why* the reinstall's restart failed, which `isActive()` alone cannot say.
A job still being unloaded when the install's own teardown timed out, and a job that loaded fine but is failing to come up (bad config, VM unreachable), both leave the job loaded.
`LaunchdJob.start`/`restart` therefore return an `Outcome` (`RUNNING`/`STILL_UNLOADING`/`FAILED`/`EXITED`) instead of a boolean, so `installMacOs` can report "the previous job had not finished unloading" only for the first case and fall through to the ordinary "not responding" message, which points at logs, for the second.

`Outcome` also keeps "launchctl refused" (`FAILED`) apart from "launchctl did it and the job did not stay up" (`EXITED`).
Only `RUNNING` is success, for `restart` as before (#916).
But `isx proxy start` on a stopped service used to print "failed to start" for both, at once, although after `EXITED` the job is loaded and launchd runs it again after `ThrottleInterval`.
With a cause that clears by itself the proxy was healthy ten seconds after being reported dead, #969's symptom on the one path that never reached a wait.
`startService()` therefore answers with `Outcome.launched()`, as it does on Linux with "the unit is active", and the command goes on to `awaitStarted`, which outlasts the throttle and prints the `launchd:` line when the proxy still does not answer (#1098).
Changing only the message ("launchd will retry in ten seconds") was rejected: it keeps a non-zero exit for a start that succeeds, and tells the user to wait where the command can.
The price is the same as #969's: a proxy that exits at every start is reported after about fifteen seconds instead of two, as "started but is not responding" with launchd's count of runs and last exit code.

In the foreground, `isx proxy start` runs `isx-proxy` as a child sharing the terminal, and a shutdown hook stops that child when the CLI is terminated: SIGTERM, then SIGKILL after 15 seconds, longer than the proxy's own 10-second forced exit.
Ctrl+C reaches both through the process group.
But a signal to the CLI alone (`kill`, a supervisor, a cancelled CI step) used to leave the proxy orphaned, holding its ports, and possibly serving an older build than the one being tested (#882).
Exec'ing the proxy in place of the CLI would avoid the child entirely, but Java cannot exec.
A CLI that runs no hook (SIGKILL, the OOM killer, a JVM crash) is covered from the other side (#923).
The CLI passes the child `--exit-with-pid <its own pid>`.
That is an internal argument between isx and its own proxy, not a user-facing option: it is not in `isx-proxy --help`, only `ProxyStartCommand.foregroundCommand()` sets it, and the service units never pass it, because their parent is the service manager.
The proxy (`ExitWith`) checks every second that the pid is still among its ancestors, and once it is not, calls `System.exit`, the same shutdown path as SIGTERM.
The check is about ancestry, not whether the process is alive.
A SIGKILLed CLI whose own parent never reaps it (a script's `Popen` with no `wait()`, or a container whose PID 1 does not reap) stays a zombie, which `ProcessHandle.onExit()` counts as alive forever.
Its children are still reparented the moment it dies, so it stops being an ancestor at once.
That holds even when the CLI died before the proxy started watching, and through a launcher that does not `exec`.
An explicit pid, not "the parent I started with", is what makes that last case and the startup race work.
Alternatives were a pipe on the child's stdin with exit on EOF, which a service's `/dev/null` stdin would trip unless it was also behind a flag, and `prctl(PR_SET_PDEATHSIG)`, which is Linux-only and not reachable from Java.

A healthy `/health` does not say whose proxy answered (#1570).
On a Mac two users share, one user's service retried its bind forever while the other user's `isx-proxy` held the fixed ports.
`isx proxy install` reported success, and that user's instances reached the other user's CA, config and credentials.
`ProxyHolder` compares the pid `/health` reports (to host callers only) with the active service's own: systemd's `MainPID`, or the `pid` of `launchctl print`.
The answering proxy is the service's when it is that process or one of its descendants, which covers older units that exec `isx proxy start`.
A service with no process owns nothing that answers.
That covers launchd between two retries, and a systemd unit in auto-restart, which reports `activating` rather than `active`.
A service that cannot bind spends most of its time there, so the check asks an installed service, not only an active one.
An installed service that is stopped or failed (`isx proxy stop`, a halt) has no process either, and then only another user's holder is reported, since instances reach its CA and credentials; one of this user's stays unreported, as before (`ProxyHolder.forService`).
The holder's owner comes from `ProcessHandle.info().user()`; an owner the host will not give is never called another user's.
`isx proxy install`, `isx proxy start` and `isx proxy restart` fail when the answering proxy is not the service's, naming its version, pid and owner (`ProxyService.answeringIsTheService`).
A holder of this user's gets up to ten seconds first, since a restarted service's previous process frees the health port last (#1219).
The stopping proxy waits for those closes, its listeners and upstream clients together, within one budget of eight seconds (`MitmProxy.STOP_BUDGET_SECONDS`, #1894).
The upstream clients close beside the MITM listener, and the health listener gets at least one second of its own after them.
A close takes milliseconds on an idle host and was measured at 1.5 seconds under a 10% CPU quota (#1887), so the old limits of one and two seconds were too short.
A close that outlasts the budget is logged as a warning naming the listener or client, so a port still bound after the stop is explained.
Together they stay under the ten seconds after which `ProxyMain`'s shutdown hook halts the process.
`isx proxy install` refuses another user's holder before judging drift, which would be that user's, and reads the drift again after an upgrade restarted the service, so it never restarts the new proxy for the old one's drift.
`isx proxy status` reports it with `pid`, `pid_owner` and `foreign_holder`, and exits 4 only when another user owns it, since 0 means instances can use the proxy.
`isx doctor` fails on another user's holder and warns on an orphan of this user's.
With another user's holder, status and doctor leave out its drift and auth error, which are not this user's to fix; `--format` leaves `restart_helps` null too, so no script restarts a service that cannot take the ports.
A holder owned by root is most likely this user's own `sudo isx proxy start`, so its remedy is `sudo kill`.
The proxy's own bind wait names the holder too, and does not wait for another user's, which none of its restarts can end.
It still exits 1, so the service manager keeps retrying, and binds once the ports are free.
Killing another user's proxy is not offered as the fix: that user's service starts it again within a second.
Only one proxy can serve a host while the ports are fixed, so the remedy is that user stopping their service.
Offering to stop an orphan of the same user's from `isx proxy install` was left out: the command runs unattended from `install.sh`, and the report names the `kill` that does it.
`install.sh` relinking a Homebrew prefix that other users share is a separate question, left for its own decision.

The uber-jars are what JBang users actually run, and nothing else in CI executes them, which is how the inline fallback's removal went unnoticed for six weeks.
The `uber-jar-smoke` job in `test-integration.yml` runs both jars on every PR and asserts the missing-`isx-proxy` path exits 78 with install instructions.

## Native image CPU baseline

Both binaries are built with `-march=haswell` on x86_64 and `-march=armv8.1-a+aes` on aarch64 (Linux arm64, Apple Silicon), set by arch-gated Maven profiles in `proxy/pom.xml` and `cli/pom.xml` — a `-march` value for the other architecture is a hard build failure.
The aarch64 choice is covered in "aarch64: `+aes`" below.

GraalVM's default is `-march=x86-64-v3`, and the numbered psABI levels **do not include AES or CLMUL**.
Without those the image cannot emit AES-NI/GHASH intrinsics, so TLS bulk encryption runs as software AES.
Every byte through this proxy is AES-GCM encrypted on both legs — once to the client on a cache hit, twice on a miss (decrypt from upstream, re-encrypt to the client) — so that fallback dominates.
Serving a cached 642 KB Maven artifact, measured with `bench/run.sh --load=maven`, same commit and host, Oracle GraalVM 25.3:

| `-march` | Throughput | Note |
|---|---|---|
| `x86-64-v3` (GraalVM default) | 73 MB/s | no AES/CLMUL |
| `x86-64-v4` | 73 MB/s | AVX-512 but still no AES — a trap |
| **`haswell`** | **960 MB/s** | v3 + AES + CLMUL; what we ship |
| `skylake` | 950 MB/s | + ADX; indistinguishable from haswell |
| `native` | 1067 MB/s | not portable |

That is a **~13x** difference, and `haswell` costs no hardware support whatsoever: AES-NI shipped in 2010, three years *before* the AVX2/BMI2 that `x86-64-v3` already demands, so every CPU able to run a v3 build already has it.
Binary size is unchanged.

`skylake` (haswell + ADX) was measured over three interleaved rounds and came out indistinguishable — 1530 vs 1514 req/s, with the ordering reversing between rounds.
ADX gives no measurable gain on this path, so there is no reason to accept its narrowing (it would drop Intel Haswell 2013-14 and AMD Excavator, which run v3 code).

Two things that look like they should help and do not, both measured:

- **`-H:RuntimeCheckedCPUFeatures` does not cover the crypto intrinsics.**
  Adding `AES,CLMUL` to a v3 build left throughput unchanged, and adding the AVX-512 set to a build with AES recovered only ~1 of the ~11 points `native` holds.
  The AES-GCM intrinsic is selected at build time from `-march`; runtime dispatch applies to a narrower set of operations, and its AMD64 default is already `AVX,AVX2`.
  Note the option *replaces* that default rather than extending it, so anything added must re-state `AVX,AVX2`.
- **Raising to `x86-64-v4`** gives no gain and costs all non-AVX-512 hardware.

## aarch64: `+aes`

GraalVM's aarch64 default, `armv8.1-a`, has the same gap: it includes neither AES nor PMULL, so until #1144 the aarch64 proxy also encrypted TLS in software.
On an Apple Silicon Mac (Oracle GraalVM 25.4, `bench/run.sh --load=maven`, three interleaved rounds):

| `-march` | Throughput | p50 / p99 at 32 concurrent |
|---|---|---|
| `armv8.1-a` (GraalVM default) | 118 req/s, 74 MB/s | 261 / 521 ms |
| **`armv8.1-a+aes`** | **~1,000 req/s, 627 MB/s** | **31 / 60 ms** |

The proxy binary grows by 16 KB (75,665,944 to 75,682,472 B, serial GC).
Three things from GraalVM's own source decide the shape of this:

- **`+aes` means AES *and* PMULL** (`CPUTypeAArch64`: `case "aes" -> List.of(AES, PMULL)`), so it enables the GHASH intrinsic too; there is no separate modifier to add.
- **There is no runtime CPU dispatch on aarch64.**
  `RuntimeCPUFeatureCheck.getSupportedFeatures()` is empty for every architecture but AMD64, so `-H:RuntimeCheckedCPUFeatures` cannot offer AES there even in principle; it is `-march` or nothing.
- **A CPU without the features exits at startup** with GraalVM's CPU-feature error (`verifyHostSupportsArchitectureEarlyOrExit`), rather than faulting later.

The crypto extension is optional in ARMv8-A, which is why GraalVM leaves it out of its default.
Every Apple Silicon Mac has it, as do Graviton, Ampere, the Raspberry Pi 5 and Linux VMs on Apple Silicon.
The Raspberry Pi 3 and 4 do not, but they were already excluded before #1144.
Their Cortex-A53/A72 are ARMv8.0 cores without LSE, so they fail the default `armv8.1-a`'s startup check (`CPUTypeAArch64.getDefaultName()` picks `armv8.1-a` whenever the build host has it, and every release builder does).
What `+aes` drops is only an ARMv8.1+ core built without the optional crypto extension, which none of the platforms above is; an 8.5x cost to everyone else for such a core would be the wrong trade.
The JVM install channels (`install.sh`, JBang) run anywhere, as HotSpot detects AES at run time.
One side effect: a native build *on* a Raspberry Pi 4 used to fall back to `compatibility` and run there, and now yields binaries that exit at startup on that same Pi; build with `-Dnative.march.args=` to get GraalVM's default back.

The base stays `armv8.1-a` rather than `compatibility`, which would drop LSE atomics.
`native` would add the SHA1/SHA2/SHA3/SHA512 intrinsics but is tied to the build host's CPU, and there is no portable modifier for them; nothing beyond `+aes` is reachable without giving up portability.
The CLI takes the same flag for the reason below; its effect on aarch64 downloads was not measured separately.

## Why the CLI takes the same flag

The CLI downloads tool tarballs and VM images over HTTPS (`DownloadCache`, `SkillsCache`, `VmManager`), all through the JDK's `HttpClient`, so it pays the same software-AES cost.
Measured directly on AES-256-GCM in a native image from this toolchain: **79 MB/s at `x86-64-v3` vs ~3100 MB/s at `haswell`** — a 39x difference.
At 79 MB/s a 384 MB tool tarball costs ~4.9s of CPU on crypto alone, so any link faster than ~630 Mbit/s makes the CLI crypto-bound rather than network-bound.

The CLI's build is tuned for size and startup (`-Os`, serial GC), which is why `-O3` was rejected there at +131% size.
`-march=haswell` costs neither: the binary is **byte-identical** (32,115,704 B either way) and startup is ~11% *faster* (median 3489 vs 3924 us over three interleaved rounds of 40 runs).
The startup gain is unexplained; `haswell` adds CLMUL over `x86-64-v3` alongside AES, which backs the CRC32 intrinsic, but that is a hypothesis.

Reproduce with `bench/run.sh --load=maven`.
Use that harness rather than a shell loop of `curl`: process-spawn overhead caps such a loop around 550 req/s, which silently pins every build faster than v3 to the same wrong number and hides the differences between them.

## Profile-guided optimization

**Chosen:** release binaries are built with profiles collected from instrumented builds of the same commit, in the same release run (#1637).
The proxy builds at `-O3` with its profile, and the CLI at `-O3` with its own instead of `-Os`.
Measured in interleaved A/B (docs/dev/PERFORMANCE-NOTES.md, "Phase 2"): the proxy is 57.6% smaller and 39% lighter when idle, and faster on every load.
The gain ranges from 19% on cached artifacts to 83% on Vertex calls, at the same startup.
The CLI starts 17 to 20% faster than at `-Os`, relays `scp` about 14% faster (a noisy figure), and costs 0.66 MB.
That relay figure predates #1643, which removed most of the relay's CPU work (design/macos-and-vms.md "A bulk exec stream is framed large and read ahead").

Why it shrinks the proxy: PGO compiles the code the profile marks hot at full speed and everything else for size.
About 20% of the proxy's methods and 15% of the CLI's have a profile.
That is also the cost: a path no workload reaches runs as cold code.
The workloads are `bench/pgo-collect.sh`'s, listed in `bench/README.md`: the proxy under the `maven`, `saturate`, `npm`, `anthropic`, `vertex` and `websocket` loads and serving the CLI flows, and the CLI's branch, shell, list, `ssh-proxy`, `git-remote-isx` and TUI flows.
Host routes and the MCP bridge have no load, so they run as cold code; `bench/pgo-coverage.py` says which methods of a class a profile counts.
An unrepresentative profile is worse than none, so `--help` is run once, never as a workload.

**How a release collects them.**
The `profiles` job runs on the Linux amd64 runner, prepares Incus as `isx-integration-tests-native` does, and builds both binaries with `-Dnative.pgo.instrument`.
It runs the collection through `sudo -u "$USER"`, never `sg incus-admin`: `sg` makes `incus-admin` the primary group, and rootless podman's `newuidmap`, which the model loads need for Hyperfoil, refuses such a process.
It then merges each binary's per-process dumps into one profile with `scripts/pgo-merge.sh` (a build with `-H:PGODumpLoadedIprofPath`).
The merged profiles are a workflow artifact kept 90 days, so a local build can fetch them.
Each build job applies them with `-Dnative.pgo=<dir>` and `-H:+PGOPrintProfileQuality`, and `check-pgo-quality.sh` fails it unless both binaries print an applicability and relevance above the floors.
Profiles key on methods, so one collected on Linux x86_64 applies elsewhere, OS-specific code falling back to inferred profiles.
Nothing is stored between releases, so the profile always matches the released commit, and a collection that fails fails the release: there is no older profile to fall back on.
`ReleasePgoWorkflowTest` holds the profiled builds to the profiles job's GraalVM.

**Instrumented builds parse runtime options.**
Release builds pass `-H:-ParseRuntimeOptions`, so an instrumented binary would ignore `-XX:ProfilesDumpFile` and every process would overwrite `default.iprof` in its working directory.
The `pgo-instrument` profile appends `-H:+ParseRuntimeOptions`, after the list's own, for that build alone.
`cli.sh --collect` puts a wrapper first on `PATH` that gives every `isx` process its own dump file, git's `git-remote-isx` and ssh's `ProxyCommand` included.

**Credential paths need an instance.**
The proxy injects only for a caller it knows by address and proof, so the model-call loads run Hyperfoil inside a throwaway instance, against a loopback stub, on a config of their own (`ISX_BENCH_CONFIG`).

**Rejected:** Quarkus's `quarkus.native.pgo.enabled`, which ties the profile to the native integration tests and so to their workload.
A profile kept in the repository or a release asset would go stale with every commit and need a checksum.
`-Os` with a profile for the CLI saves 0.46 MB over `-O3` with one and relays as fast, but gives back a sixth to a quarter of the startup gain.

## Proxy garbage collector per platform

**Chosen:** G1 on Linux, serial on macOS (both architectures).
`proxy.native.gc` in `proxy/pom.xml` is `serial`, and the `linux-g1` profile sets `G1`.

**Alternative:** G1 on macOS aarch64 too.
Native Image has supported it there since Oracle GraalVM 25.1, and the Apple Silicon release job builds with Oracle GraalVM 25.5, so nothing prevents it.

**Why not:** it was measured on Apple Silicon (#1140) and gives no gain.
Three interleaved rounds of `bench/run.sh`, same commit, Oracle GraalVM 25.4.4.1.1: 118 req/s for both collectors on `--load=maven`, and 53,900 (serial) against 53,700 (G1) on `--load=saturate`, a difference inside the noise.
G1 costs 9.0 MB of binary (+12%), 5.4 MB of idle RSS (+13%) and 6 to 12 MB more after load.
A larger G1 heap (`MaxRAM=512m`, 128 MB) makes no difference either, so `MaxRAM` stays 256m on every platform.
G1 on macOS is also an Oracle GraalVM feature: `native-image` from GraalVM Community Edition, which is what Homebrew's `graalvm` formula installs, stops with "'G1' is not an accepted value".
So `install.sh --native` would fail on such a Mac.
macOS x86_64 has no choice in any case: GraalVM dropped it after 25.0.1, before G1 reached macOS.

The figures and their conditions are in `docs/dev/PERFORMANCE-NOTES.md`, "G1 on Apple Silicon".

## The minimum macOS is set by the build

isx supports **macOS 15 (Sequoia) or later**; macOS 14 and older were dropped by decision (#1086).
Apple Silicon is supported, and the bot's Apple Silicon Mac (the `mac-runner` agent, not a CI runner) verified this change on it.
Intel Macs are not shipped any more; see "Intel Macs are not shipped" below.

The value lives once, as `macos.deployment.target` in the root pom, and a profile activated on any macOS host adds `-H:NativeLinkerOption=-mmacosx-version-min=<it>` to both modules' argument lists (`macos.min.args`), so release builds and local `./install.sh --native` alike link for it.

Before this nothing set it, and clang took the build host's own version: v0.3.9 shipped arm64 binaries recording `minos 14.0` (built on `macos-14`) and Intel ones recording `minos 15.0` (built on `macos-15-intel`).
So the minimum was an accident of the runner and differed by architecture.
Two details decide how it is set:

- **Not `MACOSX_DEPLOYMENT_TARGET`.** clang honours that variable, but `native-image` hands the builder, and so the linker it spawns, only `PATH`, `PWD`, `HOME`, `LANG` and `LC_*`; a workflow-level variable would be dropped without a word.
  Passing it through with `-E` is what `NativeImageInitializationTest` forbids.
  The linker option reaches clang directly.
- **The link step alone decides it.**
  GraalVM's own object file records only a 10.7 `LC_VERSION_MIN_MACOSX`, so the final `LC_BUILD_VERSION` is whatever the link targets.

`release.yml` checks it on the very files it uploads: `scripts/check-macos-minos.py` reads each macOS artifact's `LC_BUILD_VERSION` and fails the release unless every `minos` equals the pom's value (or the file is not a thin Mach-O with one).
It is plain Python so it runs on the Linux job that publishes, after the macOS builds, and it reads the expected value from the pom rather than repeating it.
The Homebrew formula declares the same minimum (`depends_on macos: :sequoia`, from `MACOS_MIN` in the tap step).
`WorkflowRunnerLabelsTest` fails if `MACOS_MIN` is not the target's Homebrew name, if a release runner is older than the target, or if `release.yml` no longer runs the check over `artifacts/native-macos-*/*-macos-*` (every macOS binary, not the build's `SHA256SUMS` beside them).
That the glob matches what `gh release create` uploads rests on the artifact layout (each macOS artifact is one build's `dist/`), not on the test.
A runner newer than the target is fine: it builds for the older version.

There is no runtime check in `isx`: on an older macOS, dyld refuses the native binary before any of its code runs, so such a message could never print.
`install.sh --native` refuses on an older Mac instead (reading the pom's value), where it would otherwise build a binary its own host cannot load; its JVM install is left alone, since that still runs there, unsupported.

Raising the minimum is changing the pom value, `MACOS_MIN` and the docs that state it (README, `docs/dev/HOMEBREW.md`); the tests name `MACOS_MIN` if it is left behind, not the prose.

## Intel Macs are not shipped

isx is released for Apple Silicon Macs only, from the first release on Quarkus 4 (#1693).
v0.4.2 and v0.5.0-dev.2 are the last releases with Intel macOS binaries (`incus-spawn-macos-x86_64`, `isx-proxy-macos-x86_64`).
Quarkus 4 refuses a GraalVM before 25.0.4 (quarkusio/quarkus#57922), because 25.0.0 to 25.0.3 silently drop constant bootstrap registrations (oracle/graal#12453).
Oracle GraalVM, and GraalVM Community Edition too, ship no macOS x64 build after 25.0.1, so no Oracle toolchain can build isx for an Intel Mac.
BellSoft's Liberica NIK still ships one and built both binaries in a release dispatch, but Sanne decided against shipping a release built with another vendor's toolchain.
Intel had been **best effort** since #1086: built on GitHub's `macos-15-intel` and smoke-tested there, never verified on an Intel Mac, as none is available to the project.

What an Intel Mac user meets instead of a broken download:

- Homebrew refuses before downloading, once the tap's formulas declare `depends_on arch: :arm64` and name no Intel asset.
  That change is made in `Sanne/homebrew-tap` by hand, before the first release on Quarkus 4, since the tap step will not rewrite an Intel formula.
  The release's tap step refuses to update a formula that still names an Intel asset or lacks that line.
  Rewritten, such a formula would point an Intel Mac at a missing `v#{version}` binary beside the previous release's `isx-proxy`.
  A refusal is not fatal, since the step is `continue-on-error`: the release still publishes, with an `::error::` annotation, and every Mac stays on the previous formula until the tap is edited and that formula is bumped by hand, or the next release updates it.
- `get-isx.sh` says isx is not released for Intel Macs, and on Apple Silicon points to Homebrew.
- `./install.sh --native` stops with the reason on an Intel Mac.
- The JBang jars still run wherever Java 25 does, but an Intel Mac is not supported or tested.
  The x86_64 appliance is still published, since its build and tests also serve Linux CI, so such a user gets no broken download either.

`IntelMacNotShippedTest` fails if a release build targets an Intel Mac, if `release.yml` names an Intel macOS asset, or if the tap step stops refusing such a formula.

## One GraalVM line

Every build runs on one Oracle GraalVM innovation line: the `graalvm-version` of `release.yml`, set up by every workflow, and its floating builder image (`25i5-ol10` for 25.5) in `install.sh` and both modules' `quarkus.native.builder-image`.
`InstallScriptGraalVmTest` keeps the copies equal.
The native proxy is a TLS MITM built from the JDK's `javax.net.ssl`, `java.net` and `java.security`, so a JDK advisory against them is a reason to move the line, as the advisory of 2026-10-20 moved it from 25.4 to 25.5 (#2105).
A version change is verified with a `workflow_dispatch` run of `release.yml` before merging ("Profile-guided optimization").

## The install script verifies its downloads

`get-isx.sh`, which `curl -fsSL https://isx.run | sh` runs, checks every file it downloads against the release's `SHA256SUMS` before anything reaches `INSTALL_DIR` (#2080).
It downloads the sums and the three files (`isx`, `isx-proxy`, `git-remote-isx`) into a temporary directory, and each file must be listed there exactly once and match.
A missing sums file, an unlisted asset, a mismatch or a failed transfer installs nothing and says so.
Only then is each file copied beside its destination and renamed over it, so an interrupted install never leaves a truncated `isx` on the PATH, and a refused one leaves the previous install as it was.

`release.yml` writes that `SHA256SUMS` over every asset it uploads, from the one list `gh release create` is given, so an asset cannot be uploaded without a sum.
A binary's sum is the one the build job that compiled it recorded (`SHA256SUMS` in each uploaded artifact, by the "Record checksums" step).
`.github/scripts/release-sha256sums.sh` takes that sum, and refuses an artifact that no longer matches it, is not listed in it, or shares its name with another asset.
Assets the release job makes itself (completions, the compressed kernel) or takes from a job that records none (the appliance disk, `git-remote-isx` from the checkout) are summed there.
A `workflow_dispatch` does not publish anything, so its `checksums` job writes the same file for the binaries it built and uploads it as an artifact.
`GetIsxScriptTest` runs the script against a stand-in `curl` serving a fake release whose sums the release script wrote, and fails when the script fetches an asset the release's list does not hold.

What this covers, honestly: the sums and the binaries come from the same release, both from GitHub.
So it catches corruption and truncation, and a release asset replaced without its sums, but not a compromised release or account, where an attacker would publish matching sums.
Covering that needs something an attacker with write access to the release cannot produce:

- GitHub artifact attestations (`actions/attest-build-provenance`) bind each asset's digest to the workflow run that built it, signed through Sigstore.
  Checking one needs `gh attestation verify` or cosign on the installing host, which a `curl | sh` path cannot assume, so the script could only check one where `gh` is present.
- A signature whose public key the script pins (minisign or `ssh-keygen -Y verify` over `SHA256SUMS`) is checkable with tools most hosts have, but the key must be held outside GitHub Actions to be worth more than the sums, which makes every release a step done by hand.

Both are a follow-up, not this change: they need a decision on who holds a key, or on which hosts may skip the check, which the sums do not.
The script itself is fetched from `main` over TLS, so whoever can change `main` can change what it checks; the attestation would at least tie the binaries to this repository's workflow.

## Build-time initialization must not capture host paths

Quarkus initializes application classes at image-build time unless they are listed in `--initialize-at-run-time`, so anything reachable from a static initializer is constructed by the *builder* and snapshotted into the image heap — fields and all.
On Linux the builder is **root inside the GraalVM builder container** (`install.sh` drives native-image through docker/podman, where `$HOME=/root`), so a field holding an `Environment` path freezes the builder's home rather than the user's.

Host-derived state that must be resolved eagerly therefore lives in exactly two classes, both on the flag: **`RuntimeConstants`** in `common` (the download and skills cache directories, plus the Java tool setups holding them) and **`RuntimeServices`** in the CLI (Incus client, lock manager, tool-def loader).
Both say so in their javadoc; `Environment` itself is on the list too and stays method-based, which is also what lets tests retarget `user.home`.
Everywhere else, call the `Environment` method rather than storing its result.

Deferring the class that *resolves* the path is not sufficient on its own — the **holder** must be deferred too, which is why `CDI_TOOLS` lives in `RuntimeConstants`.
GraalVM does not reject a build-time initializer that touches a run-time-initialized class; it initializes it early and folds the value, with no error (what `Environment`'s header comment warns about, confirmed by building it both ways).
That is how this shipped: `DownloadCache` resolves `RuntimeConstants.DOWNLOAD_CACHE_DIR` in its constructor and `ClaudeSetup` holds one.
Those instances used to be created in `RuntimeServices`, and commit 08a8ea0 (2026-08-26) moved the list into `ToolDefLoader`, which is not on the flag.
So the binary carried `/root/.cache/incus-spawn/downloads` as a constant and `isx build` failed on any template with a `claude` or `bob` tool.
The diagnosis was a bare path: `Failed to install Claude Code: /root/.cache/incus-spawn`, an `AccessDeniedException` message from `Files.createDirectories` walking into a directory only root can read.
Everything else kept working, because every other `Environment` read happens at runtime — so it read as a container problem, not a build-host leak.
`DownloadCache` now names the directory it failed to create.

Two mechanisms keep it from recurring.
`BakedHostStateFeature` registers an object replacer (the analysis calls it for every object scanned into the image heap) and aborts the build if a constant reflects the build machine.
Its primary check tests the configuration rather than the builder's identity.
Before analysis — and so before any build-time class initialization — it sets the builder's `user.home` and `user.name` to canaries carrying a random name, so a holder wrongly initialized at build time bakes the canary, which no literal can contain.
Matching the builder's *real* home is kept as a second check, because some values reach the heap without reading the property during analysis — config Quarkus recorded in the Maven JVM, `getenv("HOME")`, JDK internals that cached `user.home` at startup.
But that check needs the home to be distinctive, and it is not when building inside an isx instance.
The build then runs as `agentuser`, and every `"/home/agentuser/..."` literal the tool setups write into instances looks like a leak (issue #708).
Allowing the prefix would blind the check silently, exactly where isx is dogfooded, so instead the guard switches real-home matching off *and says so* when the two coincide, leaving the canaries to cover that build.
An earlier version guessed at precision the other way, flagging only paths containing `incus-spawn`, which would have tolerated a baked `~/.m2/repository`, `~/.config/incus/` or bare `$HOME`.
The guard also watches the builder's `user.dir`, and checks `Path` objects as well as their string form, because `sun.nio.fs.UnixPath` stores bytes and computes its `String` lazily.
A folded path can reach the heap with no matching `String` object at all (the regression produced both, and the guard reports both).

Environment variables are covered by GraalVM itself: `native-image` hands the builder a sanitized environment (measured: `HOME`, `LANG`, `PATH`, `PWD`), so a build-time `getenv("GITHUB_TOKEN")` in a CI release build returns null rather than baking the token into a public binary.
Only `-E<name>` widens it, and `NativeImageInitializationTest` rejects that.
The guard keeps a backstop anyway — any builder variable that is a credential by name or shape must not appear in the heap — and names the variable while withholding the value, since build logs are often public.

Second, `NativeImageInitializationTest` parses both modules' build arguments — each declared once, in its `resources-filtered/application.properties` — and fails in `mvn test` if one stops deferring a class, stops registering a guard, or passes an environment variable through with `-E`.
It also fails if a pom redefines the list.
A platform's own arguments join it through placeholders a profile sets (`svm.target.name.args`, `macos.plist.args`), because a pom property overrides the file and a platform's private copy is exercised by no build on the other.
The CLI's `macos-native` profile carried such a copy until #489, and it had drifted: macOS release builds kept `-R:MaxRAM=128m` after Linux moved to 512m.

The sibling guard `SyscallReachabilityFeature` targets something else — keeping lazy system-property resolvers off the startup path of short-lived commands — and currently cannot fail, because it resolves its targets on an abstract GraalVM class whose concrete overrides are what the analysis reaches.
It now prints `INCONCLUSIVE` per target instead of `ok`, so the report stops reading as evidence; `.claude/rules/native-image.md` records what fixing it involves.

## Java deserialization is not compiled in

Neither binary reads Java serialized objects, so the JDK's object reader stays out of both images (#458).
That is less code to ship, and no deserialization gadget surface for a future bug to reach.
Four paths kept it in, and a substitution cuts each one.
The JDK's JCEKS keystore reached it twice: `JceKeyStore.engineLoad`, from the default SSL context, and `JceKeyStore.engineGetKey` → `KeyProtector.unseal` → `SealedObject`, from `KeyManagerFactory.init` → `KeyStore.getKey`.
`NativeImageSubstitutions` (`common`) makes both throw.
Neither binary uses JCEKS, and with `engineLoad` throwing a JCEKS keystore never initializes, so `KeyStore.getKey` throws before it reaches `engineGetKey`.
Vert.x reaches it in the proxy through `SerializableCodec` on the event bus and through `Checker.copySerializable` for shared data, which `VertxDeserializationSubstitutions` (`proxy`) cuts; the proxy uses neither feature.
The `engineGetKey` path alone cost the proxy 384 KiB and 366 methods (docs/dev/PERFORMANCE-NOTES.md "Java deserialization in the native images").

Both builds pass `-H:AbortOnMethodReachable=java.io.ObjectInputStream.readObject0`, so a new path fails the build instead of growing the binary silently.
`readObject0` is where every object read starts, and the gate traces the path that reached it.
`NativeImageInitializationTest` fails if either list drops the gate.
The gate is a method, not the type, on purpose.
`-H:AbortOnTypeReachable=java.io.ObjectInputStream` fails even for a hello world on GraalVM 25.
GraalVM registers every declared method of a class registered for reflection as queried, which marks their parameter types reachable, and many JDK classes declare a private `readObject(ObjectInputStream)`.
That makes the type reachable without compiling any of its code.
`TreeMap.buildFromSorted` still calls `ObjectInputStream.readObject(Class)`, but with no reader ever created the analysis goes no further.

What it gives up: a dependency that really needs Java deserialization fails the native build until its path is substituted or the gate is lifted with a reason.
The Vert.x substitutions break local event-bus delivery of a `Serializable` body and `Serializable` values in a Vert.x `LocalMap`, both unused here.
An opt-in switch for them in Quarkus itself is proposed in #529.

## Regular expressions are compiled once

`Pattern.compile` is expensive, and `String.matches`, `replaceAll`, `replaceFirst` and a regex `split` each call it on every run (#1929).
So every regular expression in `main` is a `static final Pattern`, used through `matcher(...)` or `Pattern.split`.
A constant's class is initialized at build time in the native image, so the compiled pattern is in the image heap and costs nothing at startup either.
A one-character `split` (`"\n"`, `"\t"`, `":"`) is exempt: `String.split` takes a fast path that does not compile a pattern.
A pattern known only at run time (a tool definition's `pattern:`, one per placeholder at load) is compiled once where it is known and carries a `// regex at run time: <why>` comment.
`PatternConstantGateTest` (`cli`) fails on any other compile, in all three modules.

## Socket wakeups come from platform threads

Both binaries wait on sockets from virtual threads: the exec relay behind `isx ssh-proxy`, `isx shell` and git, and the proxy's MCP bridge and host port relay.
Since JDK 22 the JDK wakes such a thread on Linux through pollers that are themselves virtual threads (`VTHREAD_POLLERS`), driven by one platform `MasterPoller`.
A socket's readiness reaches the waiting reader only after the master poller has unparked a sub-poller and that sub-poller has run.

The strict ssh check of `test-ssh-host-keys.sh` failed in CI on that path (#2129).
Incus had connected all four exec sockets of the ssh connection within 6 ms, and the guest's sshd was listening.
`isx ssh-proxy` delivered neither direction for 30 s.
Then the stdin socket's handshake watchdog closed that socket, on time.
sshd then saw `nc` close the connection, and ssh got the banner 35 s after it connected, too late to use it.
The watchdog is itself a virtual thread that sleeps, so carriers were free and the scheduler ran.
Its close unparks the blocked reader from the closing thread itself (`Poller.stopPoll`), without a sub-poller.
So what did not arrive was the poller's readiness wakeup, while a direct close did.
The trace does not show why the sub-poller path lost it.
JDK-8371045 is one known way virtual sub-pollers can starve: with every carrier pinned by class initialization, no sub-poller runs.
That starvation lasts as long as every carrier stays pinned, and it is not what happened here, since the watchdog ran.

So both entry points, `IncusSpawn.run` and `ProxyMain.run`, first call `VirtualThreadPollers.usePlatformThreads()`, which sets `jdk.pollerMode=1` (`SYSTEM_THREADS`).
Its `Read-Poller` and `Write-Poller` platform threads block in `epoll_wait` and unpark the waiting reader themselves, which takes the virtual sub-pollers and the master poller out of the wakeup path.
A woken reader still needs a free carrier to run, as it does in either mode.
The JDK reads the property once, when the first virtual thread waits on a socket, a pipe or a selector, so the call comes before any command or proxy work runs.
In the native image `sun.nio.ch.Poller` is initialized at run time, so the property set there decides it.
A native CLI showed it against a fake Incus socket: `MasterPoller` without the call, `Read-Poller` and `Write-Poller` with it.
A mode already set, such as `-Djdk.pollerMode=2` on a JVM, is kept, to compare the two.
`VirtualThreadPollersTest` checks it in a JVM of its own, since a test JVM's mode is whatever its first socket read chose.

What it gives up: the I/O wakeups no longer go through the virtual thread scheduler, which the JDK's default mode does for fairness under heavy socket load.
Neither binary runs that load on virtual threads: the proxy's traffic is on Netty's event loops, and the CLI's relays move one connection's bytes.
If a later JDK drops mode 1, its `Poller` fails to initialize and every socket wait on a virtual thread fails, which this test catches on the JDK bump.

## jjq rather than Jackson
YAML definitions, `config.yaml` and every JSON isx reads or writes go through jjq (#1250, from #1249): `Jjq` holds one mapper with the generated `@JqMapped` mappings registered explicitly (no reflection, no `ServiceLoader`), YAML in `YamlOptions.STRICT` (duplicate keys fail), JSON through `JqValues.parseStrict`.
Every DTO it binds has a registered generated mapping (`JjqGeneratedMappingTest`), `ImageDef` included, so no DTO carries `@RegisterForReflection`: jjq's reflective fallback would need it in the native image (#1316).
The exceptions are the types a field's `@JqConverter` builds from the tree itself (`ToolRefListConverter`, `SkillsDefConverter`, `EnvEntryListConverter`), which the mapper never binds.
An enum needs registration all the same: jjq reads an enum's `@JqEnum` and the `@JqName` on its constants by reflection even under a generated mapping, and unregistered in the native image `ClaudeAccountType` binds every `type:` to null, so the account is inferred from its fields and a save drops the line.
Such an enum keeps `@RegisterForReflection`, and the same test fails for a reachable enum without it.
A generated mapping still reaches a private field without a setter through a `java.lang.reflect.Field` it looks up in its static initializer.
That works without registration because the mappings are initialized at image-build time, like every application class not on the `--initialize-at-run-time` lists in the cli's and the proxy's `application.properties`: the initializer runs in the builder and the `Field`s go into the image heap, where native-image keeps them usable (checked on both native binaries: Claude accounts read by `isx account list`, and the proxy injecting OAuth, API-key and tool credentials and reading the Vertex region and project).
Putting a mapping, or `Jjq`, on either run-time list would move that lookup to run time, where it needs reflection metadata again.
Cold Jackson databind cost about 108 ms of a ~194 ms CLI start; with jjq `isx tools` takes 4.8 ms native instead of 9.6 (with 100 user tools), and the JVM distribution no longer carries Jackson or SnakeYAML.
Tests read and write JSON with jjq too (#1702): `FakeIncusDaemon` parses and answers requests with the parser production uses, and hands its instances to the lifecycle code as `JqValue`s.
No test imports Jackson; networknt's schema validator brings Jackson 3 into the `cli` tests, and Vert.x 5 carries Jackson 3 core into the proxy in production.
Jackson's semantics are the ones users' files were written against, so where jjq differs isx restores them rather than following it:
- A plain scalar bound to a `String` keeps its source text (`0022`, `3.10`, `yes`); converters that read the tree themselves use `Jjq.text`, which reads `JqValue.sourceText()`, since `asText()` renders the resolved number or boolean.
- `asList()` on a JSON object is empty, where Jackson iterated its values: an object (a devices map, `state.network`) is walked as a `JqObject`.
- A tree is immutable: code that changes one (the support-bundle redaction) rebuilds it along key/index segments, never along a dotted path re-split, since keys may contain `.` or `[`.
- A strict-parse failure quotes the whole document; messages show `Jjq.brief`.
- A JSON document with a repeated key is refused, where Jackson kept the last value: a request body, an MCP message or `~/.claude.json` with one fails like malformed JSON rather than being read one way or the other.
  The one exception is the `customApiKeyResponses` approval the login script repeats on the last line of `~/.claude.json`: `ClaudeSetup.parseClaudeJson` drops that line before parsing (#1638).
  It reads the rest as JSON another program wrote (`Jjq.parseForeign`, below), since a merge that cannot read the file drops what earlier layers put there.
- isx's own JSON is read with `JqValues.parseStrict`: its stamps, its proxy's endpoints, `isx mcp`'s output, and Incus's answers, where isx keeps its state and whose Go encoder cannot write a lone surrogate.
  JSON another program wrote is read with `Jjq.parseForeign`: a tool's file (`~/.claude.json`, the Gemini settings), Claude Code's, Codex's and Pi's events, an MCP client's message, an API's answer.
  It differs on one point: a lone surrogate escape reads as U+FFFD.
  RFC 8259 allows one, and `JSON.stringify` writes one for a string cut inside a pair, but jjq 0.2.4 refuses the whole document for it (Hyperfoil/jjq#146), and a user cannot fix a file another tool keeps rewriting.
  Anything else malformed still fails, as `<source> is not valid JSON: <why> at line L, column C`.
  Invalid UTF-8 is one of those, so a reader hands over the bytes it received (`Jjq.parseForeign(byte[], ...)`), never a `String` it decoded: `InputStreamReader` and `BodyHandlers.ofString()` turn a malformed byte into U+FFFD before jjq sees it (#1752).
  Stdio `isx mcp` and the AI providers' event streams read a line's bytes through `ByteLines`.
  Exec output reaches its readers already decoded, so readers of it, such as the agents' event tails, `ModelCheck.refusal`, `HerdrIntegration`, `GeminiSetup.selectApiKeyAuth` and `ClaudeSetup.parseClaudeJson`, still parse a `String`.
  The escapes are rewritten, length for length, only after the strict parse refused one, so a clean document costs nothing and a later error's position holds.
  The rewrite goes when jjq offers the choice in its `ParseOptions`.
- An `Integer` field binds through `IntegerConverter`: jjq bound `.inf` and `.nan` as 0 before 0.2.4, which for `orphan-grace-hours` in `mcp.yaml` or `max-instances` would fail open, and refuses `""`, which Jackson left unset (a word, a boolean, a fraction or an out-of-range number it refuses itself since 0.2.3).
  Since 0.2.4 taking their decimal value throws, so `Jjq.exactInt` refuses a non-finite number before it does.
The upstream issues behind these are Hyperfoil/jjq#126-#128 (fixed in 0.2.2), #131-#134 (fixed in 0.2.3), #150 and #163 (fixed in 0.2.4), #146 (a lone surrogate escape refused by strict parsing since 0.2.4) and #130 (open).

## Considered and deferred

### Vert.x's HTTP client in the CLI

Moving the CLI's HTTP clients off `java.net.http` onto Vert.x's `HttpClient` was considered, to drop that module (about 583 KB) from the native image (#40).
It is deferred because the premise is gone: the proxy split removed Vert.x from the CLI, and Quarkus 4 does not bring it back to `cli` or `common`.
Pulling vertx-core and Netty into the CLI would add more code than it removes, add startup cost to every command, and bring Vert.x's native-image workarounds with it.
The only form worth revisiting is replacing `java.net.http` with `HttpsURLConnection`, and only with a fresh native build report showing the size win.
