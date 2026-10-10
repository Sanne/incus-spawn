# incus-spawn Design Document

A CLI tool for managing isolated Incus-based development environments.
System containers that behave like bare-metal Linux machines, designed for safely running untrusted AI agents and external reproducers in OSS projects.

## Why not application containers?

**Application containers**, the kind Docker and Podman run by default, isolate a single process with a minimal filesystem, no init system, and restricted networking.
This is ideal for deploying microservices but poor for development environments where you need:

- A real init system (systemd) for services like podman socket, sshd, or dbus
- Full networking: `ping`, `traceroute`, `tcpdump`, DNS resolution that works like a real machine
- Nested containers: running Podman/Docker inside the environment (Testcontainers, CI pipelines)
- Debugging tools: `strace`, `perf`, `gdb` — all require capabilities or sysctls that application containers strip
- GUI applications via Wayland passthrough with GPU acceleration, and audio via PipeWire

Incus **system containers** run a full Linux userspace with their own init, networking stack, and process tree.
They share the host kernel (like application containers) but present as a complete machine rather than a single process.
For stronger isolation, Incus also supports KVM virtual machines with a separate kernel, at the cost of a modest performance overhead.

The tradeoff: system containers are heavier than application containers (~200MB base vs ~5MB Alpine).
This is acceptable for development environments that persist for hours or days, and copy-on-write storage means clones are cheap regardless of base image size.

The choice is what a sandbox *is*: in isx, a branch of a template, as a container by default or a VM.
Other sandboxes for coding agents make it differently, and the README's "Compared with other sandboxes" section says how.

## Goals

- **Secure by default**: isolated environments that prevent untrusted code from accessing host credentials or resources
- **Bare-metal experience**: containers with full init, real networking, working developer tools — developers shouldn't notice they're inside a container
- **Extensible without Java**: image definitions and tool installations defined in YAML; Java only needed for tools requiring programmatic logic
- **Ephemeral and cheap**: copy-on-write clones mean spinning up a new environment costs seconds and minimal disk space
- **Familiar**: CLI patterns inspired by git workflows (branch-name-style naming, auto-detection from cwd)
- **Idempotent setup**: `isx init` can be re-run safely at any time — each step checks whether its work is already done and skips without making changes, never disrupting running containers or reloading services unnecessarily.
  Init completion is tracked by a versioned sentinel (`~/.config/incus-spawn/.init-complete` containing `INIT_VERSION`).
  When `INIT_VERSION` is bumped (new infrastructure step added), existing installations automatically re-run init on the next command.
  On Linux, init writes sysctl overrides (`/etc/sysctl.d/99-incus-spawn.conf`) to raise per-UID inotify limits (`max_user_instances=8192`, `max_user_watches=524288`), preventing inotify exhaustion when running many containers.
  Files init places under `/etc` are installed with `sudo install -m 0644`, not `sudo cp` from a `0600` temp file, so a re-run can read them back to check whether they changed.
  A file it cannot read counts as needing a rewrite, which repairs the `0600` files older releases wrote (#821).
  The Template Search Paths step uses `gh` to auto-detect the user's GitHub identity and offer to fork/clone `incus-spawn-templates` — every failure path (no gh, no auth, API error) degrades gracefully to the existing manual flow

## Tech Stack

- **Java 25**, **Quarkus 3.x** with aesh for CLI commands
- **Tamboui** (https://tamboui.dev/) for interactive TUI (list view, modal dialogs, inline actions)
- **GraalVM native image** for optional zero-dependency distribution
- **JBang** for easy installation (`jbang app install isx` plus `isx-proxy`)

### Module Structure

Three Maven modules under a parent POM:

- **`common`** (`incus-spawn-common`): shared code — Incus client, proxy config, image/tool definitions, configuration loading.
  Not a Quarkus app; uses the Jandex Maven plugin to produce a bean index so Quarkus discovers its CDI beans from dependent modules.
  Its test classes are published as a test-jar, which `cli` depends on in test scope, so a command's wiring is tested against the same `FakeIncusDaemon` as `common`'s flows (see "Request budgets").
  The test-jar is built even under `-Dmaven.test.skip=true`, since `cli` could not resolve it otherwise.
- **`cli`** (`incus-spawn`): the main CLI/TUI binary (`isx`).
  Depends on common.
  Native image: serial GC, `-Os` (size-optimized).
- **`proxy`** (`incus-spawn-proxy`): the standalone MITM proxy binary (`isx-proxy`).
  Depends on common.
  Native image: G1 GC on Linux and serial GC on macOS, `-O3` (throughput-optimized), on x86_64 `-march=haswell` and on aarch64 `-march=armv8.1-a+aes` — see "Native image CPU baseline" and "Proxy garbage collector per platform" below.
  Splitting this out removed Vert.x from the CLI, which means the CLI can no longer serve the proxy itself — see "Every install channel ships both binaries" below.

### Every install channel ships both binaries

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
Waiting longer was rejected: it would cost every first install those ten seconds for a start that takes well under one, and would change nothing on Linux.
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
`bootout` returns before launchd has removed the job: a proxy that does not exit on SIGTERM is killed about five seconds later, and until then `bootstrap` fails with `5: Input/output error` while `launchctl print` still finds the job.
The old sequence took that `print` for success, reported "Proxy service restarted", and five seconds later nothing was loaded at all.
So the automatic restart never recovered a hung proxy while a later `isx proxy start`, finding no job, simply loaded one (#916).
`kickstart -k` is launchd's own restart: the job stays loaded, so there is no teardown to race.
Only a changed plist has to be unloaded and loaded again, and then the restart waits until `print` no longer finds the job.
In both cases the restart has worked when `launchctl` returned 0 and the job is running.
Sleeping between `bootout` and `bootstrap` was rejected as timing-dependent and still blind to a failed `bootstrap`; retrying `bootstrap` on error 5 was rejected because 5 is also what a rejected plist returns.

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

The uber-jars are what JBang users actually run, and nothing else in CI executes them, which is how the inline fallback's removal went unnoticed for six weeks.
The `uber-jar-smoke` job in `test-integration.yml` runs both jars on every PR and asserts the missing-`isx-proxy` path exits 78 with install instructions.

## Architecture

### Container Model

- **System containers** by default (lightweight, full init system), with `--vm` flag for KVM VMs (stronger isolation, separate kernel)
- Containers don't drop capabilities (`lxc.cap.drop =`) and the host kernel relaxes `perf_event_paranoid` to `-1` so profilers work without restrictions; `ptrace_scope` is unrestricted (Yama is not compiled in) and `ping_group_range` is wide by default
- No GUI unless the template asks for it (`gui: true`); Wayland + GPU passthrough available at branch time
- Three network modes at branch time: full internet (default), proxy-only, or airgapped
- Container user: `agentuser` (UID 1000, passwordless sudo)

### Template Image Hierarchy

Images are defined in YAML and layered via copy-on-write.
Built-in definitions live in `src/main/resources/images/*.yaml`; user-defined images in `~/.config/incus-spawn/images/` can extend or override them:

```
tpl-minimal   (Base OS only — no tools)
  └── tpl-dev   (Podman, GitHub CLI, Starship, tmux)
        └── tpl-java  (JDK packages + Maven tool)
```

No coding agent is included in built-in templates by default — users add the ones they need to custom image definitions.
Available Java tools: `claude` (Claude Code), `codex` (Codex CLI), `pi` (Pi coding agent, Anthropic or OpenAI provider), `bob` (Bob Shell), `copilot` (GitHub Copilot CLI).

Each image definition specifies:
- `name` — container name (required)
- `description` — human-readable description for the TUI
- `image` — base OS image, only for root images
- `image_url` — download URL for the base image tarball (supports `{arch}` and `{tag}` placeholders)
- `image_tag` — release tag identifying the base image version
- `image_sha256` — per-architecture checksums for integrity verification
- `type` — instance type: `container` (default), `vm`, or `kvm`.
  Inherits through the parent chain via `inheritTypes()` at load time
- `vm_image_url` — download URL for the VM base image (qcow2 tarball, supports `{arch}` and `{tag}` placeholders)
- `vm_image_sha256` — per-architecture checksums for the VM base image
- `parent` — parent image name (omit for root images)
- `packages` — dnf packages to install
- `tools` — tool names to run (resolved from YAML or Java)
- `agent_note` — always-true fact an agent must know before acting, rendered into the generated agent context file (see below)

Building an image automatically builds missing parents recursively.
`isx build --all` rebuilds every defined image from scratch.
`isx build` takes several templates (#1130).
`--with-parents` and `--with-descendants` turn them into one batch, the union of their chains (or subtrees) with every template once and parents before children.
So leaves that share a customization layer rebuild it once and are both derived from that one build.
Running `--with-parents` once per leaf instead rebuilt the shared parents each time and left the first leaf copied from a build that no longer existed.
That is also why a template whose parent was rebuilt after it now counts as out of sync (below).
Plain `isx build a b` and `--missing` build their targets one after the other, and like a batch (`rebuildAll`) a failure does not stop the run.
A target inheriting from a template that failed in this run is skipped, since its build would copy the old image, the others are built, and the run exits 1 with a single summary naming every template it left unbuilt, a parent a target's chain failed on included (the chain's own summary is held back for it).
One named target behaves as it always did.

**Base image**: The root image (`tpl-minimal`) uses a custom Fedora base image from [`Sanne/incus-spawn-images`](https://github.com/Sanne/incus-spawn-images) instead of linuxcontainers.org.
This image is a pre-baked systemd rootfs with agentuser, systemd-networkd, a connectivity watchdog, container-specific service masking, and a tmpfiles override for device node permissions — all the static setup that `buildFromScratch` would otherwise perform on every build.
A separate VM base image (`vm_image_url`) is also available — a stock Incus Fedora VM image customized with the same base configuration via `virt-customize`.
A base image tag and SHA256 checksums are baked into `src/main/resources/images/minimal.yaml`, but they are only an **offline fallback**.
When the base image is unpinned, `BuildCommand.resolveTrackedBaseImage()` fetches the newest release from the GitHub API at build time (via the shared `baseimage/BaseImageReleases`, whose owning repo is parsed from the definition's `image_url` — the YAML is the single source of truth, and a non-GitHub URL is simply not tracked) and swaps in its tag + per-arch container/VM checksums.
So a plain `isx build tpl-minimal` always installs the latest base image.
Any failure to reach or read the release list leaves the built-in tag in place and the build proceeds.
An event-driven CI job (`.github/workflows/update-base-image.yml`) keeps that built-in fallback from drifting by opening a PR to bump it whenever the images repo publishes a newer release.
`Sanne/incus-spawn-images` fires a `base-image-released` `repository_dispatch` (carrying the new tag and the container/VM checksums it just computed).
So this repo neither polls nor re-parses `SHA256SUMS`.
A manual `workflow_dispatch` re-derives the newest release from the images repo's release list as a backstop if a dispatch is ever missed.

`isx update-base` manages the pin: it fetches the release list from the GitHub API, retrieves per-architecture container **and VM** SHA256 checksums, and writes a user-level override to `~/.config/incus-spawn/images/minimal.yaml` (`pinned: true`) when pinning a specific version.
`--latest` (or menu option 1) simply removes that override, restoring build-time latest tracking — it does not itself download anything.
`BaseImageReleases.parseSha256Sums()` keys checksums by arch and separates the `-vm.tar.xz` disk image from the `.tar.xz` container rootfs, so a pin records the correct digest for each.
See the [incus-spawn-images README](https://github.com/Sanne/incus-spawn-images#releasing-a-new-version) for the full release process.

**Resolution order** (later overrides earlier): built-in YAML (classpath) → user-defined YAML (`~/.config/incus-spawn/images/`) → search paths (`searchPaths` in config.yaml) → project-local (`.incus-spawn/images/`).
Definitions with the same name from a later source override earlier ones — this is the mechanism behind pinning and template customization, so it stays silent.
But two files declaring the same `name:` *within the same directory* is always a mistake (typically a copy that forgot to update `name:`, which silently masks the file you think you are editing).
`ImageDef.loadAllWithConflicts()` distinguishes the two: same-directory collisions become `NameConflict`s (listing every colliding file), cross-layer replacements become `LayerOverride`s.
`isx build` refuses to build while any conflict exists and names the offending files; the TUI degrades to a status warning but still renders; `isx doctor` surfaces both (conflicts as warnings, overrides as informational notes).
Both `ImageDef` and `ToolDefLoader` feed the same `LayeredDefinitions<T>` collector (`config/LayeredDefinitions.java`), which owns the per-directory collision/override bookkeeping and the `NameConflict`/`LayerOverride` record types — so the policy is defined once and applies identically to images and tools.
The collector also records files that failed to parse (`parseFailures()`).
Loading skips them with a warning, but `isx build` refuses while any exist.
An unparsable file's `name:` is unknown.
So it may have been the target, a parent, or an override.
Building anyway would silently use whatever it was meant to replace — a lower layer's definition, or the stale build-source snapshot of an existing template.
Read-only commands keep warning and carry on.

### Tool System

Tools define how software gets installed into template images.
Two formats:

**YAML tools** (primary format) — declarative, no Java needed:

```yaml
name: maven-3
description: Apache Maven (latest 3.x)
run:
  - |
    MAVEN_VERSION=$(curl -s https://dlcdn.apache.org/maven/maven-3/ ...)
    ...
verify: mvn --version
```

Schema fields (all optional except `name`):
- `packages` — dnf install
- `downloads` — artifacts to download and cache on the host, then extract into the container or expose as a file, or both (with optional SHA256 verification and symlink creation)
- `requires` — list of other tool names that must be installed first (resolved transitively)
- `run` — shell commands as root
- `run_as_user` — shell commands as agentuser
- `files` — files to write (path, content, optional owner)
- `env` — environment variables written to `/etc/profile.d/isx-env.sh` (supports structured entries with merge strategies)
- `verify` — verification command (logged, non-fatal), run as `agentuser` in a login shell, so `isx-env.sh` is loaded
- `verify_as_root` — set `true` for a check that needs root (`sshd -t` reads the host keys); it then runs as root in root's own environment (`HOME=/root`), without `isx-env.sh`

Execution order: packages → downloads → run → run_as_user → files.
Environment variables are collected centrally after all tools run, and only then does `ToolVerifier` run each installed tool's `verify` (`ToolSetup.verifyCommand()`), for agentuser checks with `/etc/profile.d/isx-env.sh` loaded.
It runs **as `agentuser`** through a login shell (`Container.shAsUser`, the same path as `run_as_user`), unless the tool sets `verify_as_root`: that is what the image's user gets, and a root run can leave root-owned state in their home when the env points there (0.3.9: zmx's `ZMX_DIR`, then `error: AccessDenied` on every `zmx` run).
A `verify_as_root` check runs in root's own environment instead, `env -i HOME=/root PATH=<root's default> sh -c <verify>`: without `isx-env.sh`, and without whatever the exec itself carries (an instance's `environment.*` keys, such as the agentuser `XDG_RUNTIME_DIR` GUI passthrough sets).
So it cannot rely on another tool's env entries (`sshd -t`, the one there is, never needed them).
No ownership repair runs afterwards.
One did, until #931, and it walked the whole home, cloned repos included, on every build with a root check, while each hardening in review (busybox's `grep`, walk errors, newline paths) added shell for a case root's own environment prevents outright.
A verify often depends on another tool: Maven's `mvn --version` needs the `JAVA_HOME` a JDK tool declares, and verifying it right after its own install (before the JDK, without the env file) failed in an image where Maven works.
A failed verify's warning keeps every line of the reason, joined and capped, since messages such as Maven's wrap mid-sentence.

**Environment variable system** (`EnvEntry` + `EnvResolver`): Env entries from the full template parent chain and all tools are collected by `BuildCommand.writeEnvFile()` into a single `/etc/profile.d/isx-env.sh`.
Four strategies: `set` (unconditional), `set-if-unset` (conditional default), `prepend`/`append` (additive with separator).
Conflict detection: two `set` entries for the same variable with different values fail the build with both sources named.
Templates (`ImageDef`) can also declare env entries.
Java tools participate via `ToolSetup.envEntries()`.
Definitions accept only the structured form: a shell string (`- export FOO=bar`) is rejected at load, with the structured equivalent in the error, because it would bypass conflict detection.
There is no verbatim-line escape hatch, not even for built-in code: every entry has a name and a strategy, so every entry is conflict-checked.
Values are escaped so they are taken literally.
Built-in Java code that needs the shell to expand a value at login (`$HOME` in Claude's `PATH` prepend, `$HOSTNAME` for `ISX_CONTAINER`) marks the entry `expandingAtLogin()`, which leaves plain `$NAME`/`${NAME}` references live but still escapes every other `$` (no `$(...)`), quotes, backslashes and backticks.
YAML cannot set that flag, and an expanded `$HOME` and a literal `$HOME` count as different values.

**Transitive dependency resolution** (`requires`): Tools can declare dependencies on other tools.
During build, `resolveWithDeps()` performs a recursive depth-first traversal to build the full dependency graph.
Circular dependencies are detected and reported.
Auto-added dependencies are logged: "Auto-adding dependency: sshd (required by idea-backend)".
Dependencies are installed before the tools that require them.

**Java tools** (fallback) — for tools needing programmatic logic beyond what YAML supports:
- Implement `ToolSetup` interface (`name()` + `install(Container, Map<String, String>)` + `envEntries(Map<String, String>)`)
- Discovered via CDI (`@Dependent`)
- Currently used by: `claude` (binary install + settings), `codex` (npm install + settings), `copilot` (npm install + settings), `gh` (dnf install), `pi` (npm install + settings), `bob` (npm install)
- npm-distributed CLIs whose real binary is an *optional* per-platform package (`codex`, `copilot`) install through `NpmGlobalInstall`.
  npm treats a failed optional dependency as skippable and still exits 0 -- at any log level below `http` it does not even mention it -- so a transient download failure leaves only the JS launcher and the template is stamped as built anyway (#808).
  After `npm install -g`, and after the `npm update -g` of `isx update-all` / `isx project update`, a node one-liner run as root resolves the platform package the way the launcher does.
  A missing one is reinstalled once, then fails the step with the failure lines from npm's verbose log.
  The check deliberately does not run the CLI: `copilot --version` unpacks ~165 MB into agentuser's cache and `codex --version` leaves lock files, all of which would be baked into the template.

**Resolution order** (later overrides earlier): built-in YAML (`resources/tools/`) → user-defined YAML (`~/.config/incus-spawn/tools/`) → search paths → project-local (`.incus-spawn/tools/`).
A YAML tool with the same name replaces any earlier definition.
Two tool files declaring the same `name:` within one directory are a same-directory conflict, reported by `ToolDefLoader.conflicts()` and treated exactly like image conflicts (see the images section above).
Java CDI implementations (`@Dependent` beans) are used as fallback when no YAML tool matches.

**Feature flags**: Tools can declare a `feature()` that gates them behind an opt-in `features` list in `~/.config/incus-spawn/config.yaml`.
A gated tool is excluded from build resolution, proxy registration and action resolution until the feature is enabled.
The `isx init` credential menu is deliberately *not* gated: a gated tool's credential is usually what the user needs to configure first, so hiding it until the feature is on is a deadlock (Pi with `provider: openai` hit exactly that).
`openai` (gating `codex`) was the first and so far only use; codex graduated to always-available once the flow was validated end to end, so no built-in tool is currently gated and the mechanism waits for the next experimental capability.
Enablement is purely the `features` list: the earlier "a configured `openai.apiKey` implicitly enables `openai`" rule existed so that graduating the flag couldn't break setups that predated it, and went away with the gate.

### Host Repo Refresh

Before building templates or running `isx update-all`, `HostRepoRefresh` fetches all host-side git repos that match repos declared in image definitions (using the same `host-paths`/`repo-paths` resolution as local cloning).
This ensures the local-clone optimization uses current objects.
Fetches run in parallel and are rendered with the shared `TerminalProgress` animated per-repo spinner display (same helper used for parallel repo cloning).
Optionally, missing repos can be cloned — the first prompt accepts `y`/`n`/`always`/`never`, with `always` and `never` persisted to the `auto-clone-repos` config field.
`--skip-git-refresh` bypasses the refresh entirely.
`update-all` only fetches (no clone prompts).

### Build Flow

**`buildFromScratch` (root image, no parent):**
1. Import and launch base image (pre-baked with agentuser, systemd-networkd, service masks)
2. Install MITM proxy CA certificate
3. Configure security (idmap, nesting, syscall interception, no capability dropping) — *skipped for VMs*
4. Prepare container for package install (tmpfiles overrides, temporary DHCP network config, man dirs) — *skipped for VMs*
5. Configure DNS (disable systemd-resolved, point at Incus bridge gateway)
6. Upgrade system packages
7. Install image-defined packages via dnf
8. Install image-defined tools (resolved from YAML/Java)
9. Clone declared repos (with reference optimization — see below)
10. Configure terminal title (`PROMPT_COMMAND` in `.bashrc` sets `isx:<hostname>`)
11. Pre-trust cloned repo directories in `.claude.json` (if Claude Code is installed)
12. Write the agent context file (`/etc/claude-code/CLAUDE.md`) — see below
13. Clean caches (dnf, /tmp)
14. Tag metadata (version, SHA, definition fingerprint, CA fingerprint, build source), stop

**VM-specific build behavior:**

When `type` is `vm` (set in the definition or via `--type`), `buildFromScratch` applies the entire ancestor tool/package chain from YAML definitions alone.
Parent Incus instances are not needed.
So container parent rebuilds are skipped when a type change is detected in `buildChain`.
Additional differences:

- **Base image**: uses `vm_image_url` (pre-baked VM qcow2) when available, falls back to a stock Incus VM image otherwise
- **Disk expansion**: runs `growpart` + `resize2fs`/`xfs_growfs` before package install (both for pre-baked images that ship at 10G and the final build which defaults to 100G)
- **Security config**: container-specific security settings (raw.idmap, nesting, setxattr interception) are skipped — VMs have their own kernel and don't need them
- **No restart**: VMs don't need the container restart that applies security config changes
- **Guest SELinux pinned to `disabled`** (#842): the VM base image ships no SELinux policy and a filesystem nothing ever labelled, but ordinary packages pull the policy in (`perl` -> `selinux-policy-targeted`), and its `%post` writes `SELINUX=enforcing`.
  The build still succeeds, because the policy only loads on the *next* boot, where it denies the incus-agent's vsock `listen` and the instance is unreachable.
  Relabelling does not help: the agent runs from tmpfs as `init_t`, which the targeted policy never allows to listen on a vsock socket.
  So both build paths call `disableGuestSelinux` before any package install, writing `SELINUX=disabled` to `/etc/selinux/config`, or rewriting an existing `SELINUX=` line.
  The rewrite cannot rescue a parent that already says `enforcing`: its copy boots enforcing and the agent is unreachable before the step runs.
  Such a parent was built by an older isx, so `isImageOutdated` makes `buildChain` rebuild it through the fixed path first.
  That `%post` only writes the file when it is missing or empty, so the seeded file survives every later install, in the template and in its branches.
  `assertGuestSelinuxNotEnforcing` then fails the build if anything set the file back to `enforcing`, so the failure cannot ship silently.
- **Waiting for the agent** (#844, #953): `IncusClient.waitForReady` is the one wait for "the instance answers exec", for builds and branches alike.
  A VM gets 120s rather than a container's 30s, since firmware, kernel, systemd and on a first boot cloud-init all come before its incus-agent.
  Every caller passes the known `MachineType`: builds pass `activeBuild.machineType()`, branches and VM-only code pass `MachineType.VM`, and callers on existing instances pass `incus.machineType(name)`.
  A fallback no-arg overload still learns the type from the status GET that follows a failed probe.
  But callers should not rely on it.
  Incus may return a non-exception failure (`exit 0` with an error on stdout, as observed for VMs whose agent is booting) that bypasses the detection and applies the 30s container timeout.
  Both timeouts are configurable via `ready-timeouts:` in config.yaml (see `ReadyTimeoutsConfig`), loaded eagerly at `IncusClient` construction:
  ```yaml
  ready-timeouts:
    container: 60s
    vm: 3m
  ```
  Durations use the same format as `artifact-cache:` (`30s`, `2m`, `5m`); unset values keep the built-in defaults.
  While a VM waits, its console log (`GET /1.0/instances/<n>/console`, what `incus console --show-log` prints) is checked every 2s for the agent failing (`VmAgentFailure`: `Failed to start incus-agent`, or an `avc: denied` for `comm="incus-agent"`).
  Once such a line appears the agent gets 20s to recover through systemd's restart, then the wait fails with those lines quoted instead of running out the full budget.
  Reading that endpoint is not destructive: Incus drains QEMU's ring buffer into a log file and returns the whole file, which it also serves once the VM has stopped.
  Incus rotates that file to `.old` on every start, so an earlier boot's agent failure cannot cut a later wait short.
  A failure names the VM and its agent, never "Container", and always points at `incus console <n> --show-log`.
  The wait prints nothing itself: its callers already show a step line for it, and the TUI calls it too.
  A VM is probed with exec far less often while its state reports the agent disconnected (#954).
  Each iteration first reads `GET /1.0/instances/<n>/state`, whose `processes` Incus reports as -1 until the agent connects -- the moment exec starts working -- and the exec probe, a POST plus WebSockets that almost always fails until then (and leaves `broken pipe` lines in the guest journal), runs on every poll only once `processes >= 0`, and otherwise only at the safety interval below.
  The same read's `status` catches a VM that died.
  Exec stays the authority.
  `processes` says the daemon reached the agent, and every caller needs exec next.
  So the wait still ends only on a probe that ran -- exit 0 *and* `ready` on stdout, because exit 0 alone has been seen from a probe that never reached the agent.
  The gate opens once and stays open, so an agent that restarts later is handled exactly as before, and a state without `processes` opens it at once, degrading to plain exec probing rather than waiting out the budget.
  `processes` is not proof the other way either: Incus also reports -1 when its own state query to a connected agent fails, so while the gate is shut exec still gets one probe every 5s (`ReadyTimeouts.gatedProbeInterval`).
  A wrong -1 then costs at most that much, rather than a healthy VM timing out, and a boot still makes a handful of probes instead of one per poll.
  Containers are never gated: their first probe usually answers, and a state read would be a round trip added to the branch path.
  The budget, the console fail-fast and the messages are the same either way; the gate changes only what is polled inside them.
- **Recovering an unresponsive agent** (#843): when `isx shell`, `isx run` or the TUI find a running VM whose agent does not answer, `VmAgentRecovery.restartForAgent` decides whether to restart it.
  A restart recovers an agent that merely wedged.
  But it is a cold boot that kills the guest's work, and it cannot help an agent that fails to start on this image.
  That boot fails identically, and restarting unconditionally cycled such a VM on every attempt.
  So it restarts only when nothing says that is futile, and at most once per boot.
  The agent is probed through a caught exec, since Incus refuses the exec outright when the agent is down.
  If this boot's console log holds a `VmAgentFailure` line that systemd has not since followed with `Started incus-agent` (`VmAgentFailure.unrecoveredLines`, which `waitForReady` uses too, so a failure the agent recovered from never cuts a wait short), the agent gets `waitForReady`'s restart grace and then those lines are reported instead of restarting (the log is rotated at every start, so they belong to this boot; systemd prints unit status there only during boot, so the two kinds of line are ordered comparably).
  Otherwise it restarts and stamps the new boot's QEMU pid (`IncusClient.pid`, from `GET /state`, fresh at every start) as `user.incus-spawn.agent-restart-boot`, before the wait so a boot whose agent never comes up is recorded however the wait ends.
  An agent unresponsive on the stamped boot is reported, pointing at the console log and `incus restart --force`.
  Any other boot, one the user started or a guest reboot, gets its one restart, so no stamp ever needs clearing, not even on a copy.
  Both callers reach it through `InstanceLifecycle.ensureReady`, the one "start it, or recover its agent" step before a shell.
  The guest is asked to shut down first (ACPI, which needs no agent, 15s) and powered off only if it ignores that.
- **Tool downloads**: large file pushes over vsock are slow, so `YamlToolSetup` uses a mount-and-copy strategy for both extracted archive and downloaded files exposed via `destination_file`

None of this applies to `type: kvm`, which builds a **container**: `effectiveMachineType()` returns `MachineType.CONTAINER` for `kvm`, since `BuildCommand.InstanceType` maps both `container` and `kvm` to `MachineType.CONTAINER`.
The type is stamped as the template's `instance-mode`, and `isx branch` then passes the host's `/dev/kvm` (and `/dev/vhost-vsock`) in as `unix-char` devices (`KvmPassthrough`) so the branch can run VMs itself; `--no-kvm` opts a branch out.
A device node can only be handed to a container, which is why `kvm` is a container type.

**`buildFromParent` (derived image):**
1. Copy parent image, start, wait for network
2. Install image-defined packages via dnf (deduplicated — see below)
3. Install image-defined tools (with transitive `requires` resolution)
4. Install skills, clone declared repos, pre-trust repo directories
5. Write the agent context file (`/etc/claude-code/CLAUDE.md`) — see below
6. Clean caches
7. Tag metadata, stop

### Agent Context File

Every build regenerates `/etc/claude-code/CLAUDE.md`, a short always-loaded primer for agents running inside the box.
Generation happens once per build, in both `buildFromScratch` and `buildFromParent`, and in each path after all layer work is finished.
So it sees the fully resolved image (every ancestor's tools and repos) rather than one layer at a time, and the repos it lists have actually been cloned by the time it is written.
Do not move the call earlier to sit beside `writeEnvFile`: in `buildFromParent` that is before `cloneRepos`.
Content comes from `AgentContextGenerator` (module `common`), a pure function that `BuildCommand.writeAgentContext()` feeds and writes — the same split as `EnvResolver`/`writeEnvFile`, and the reason the whole format is unit-testable without Incus.

**Why the managed-policy layer.**
That path is Claude Code's *managed policy* memory location on Linux: it loads ahead of the user layer (`~/.claude/CLAUDE.md`) and the project layer (`./CLAUDE.md`), all layers concatenate rather than override, and it cannot be suppressed via `claudeMdExcludes`.
Writing there means isx never merges with, prepends to, or overwrites a file a user or a template owns — no marker blocks and no read-modify-write logic, which the obvious alternative (generating into the user layer) would have required.
It also sits beside the `managed-settings.json` and `statusline.sh` that `ClaudeSetup` already owns in that directory, and root ownership is correct, so no `chown` is needed.

**What goes in it.**
One test governs every line: *would omitting it cause a wrong action?*
That admits four framework-level facts (passwordless sudo, so a missing tool gets installed rather than worked around; credentials for proxied services are held by the proxy rather than stored in the box, so they are not worth hunting for (stated in exactly those terms — `host-resources` can mount real credentials in, so a blanket "there are no credentials here" would be false for some templates); autonomy is for investigation, not for scope or outward-facing action such as opening a PR unbidden; and the "already installed / already cloned" lists, which stop an agent re-running `dnf install` or `git clone` for things the template already provides).
It excludes anything the agent would treat identically whether or not it was told — template descriptions, env vars already exported into every login shell via `/etc/profile.d/isx-env.sh`, and host-resource mount semantics.

**Names and paths, never descriptions.**
`ImageDef.contentFingerprint()` covers tool names and repo url/path, so those cannot drift away from the built image.
`description` is deliberately *not* fingerprinted (editing one must not trigger rebuilds), so rendering it could assert something that stopped being true.
`agent_note` *is* fingerprinted on both `ImageDef` and `ToolDef`, because a stale warning baked into an image is worse than an extra rebuild.

**`agent_note` vs `skills`.**
Notes are for always-true constraints and traps that must be known *before* the first relevant action; procedures belong in `skills`, which load on demand when the model recognizes a matching task.
Both are declarable on a tool as well as an image, so they travel with the tool into every template that installs it.
`mvnd` is the worked example.
The note is what gets it reached for at all (the name alone says nothing), and a skill would carry the procedure without spending context in sessions that never build anything.
Tool skills are installed by `installSkills` alongside the image's, deduplicated against them, and bare names resolve against the *tool's* `skills.repo`, since the tool reaches templates that have never heard of its catalog.
Most tools declare neither.

**Package deduplication**: Before installing packages, the build walks the parent chain and collects all packages from ancestor images and their tools.
These are subtracted from the current image's package list so derived images only install what's new.
The build logs both the count being installed and the count already present in ancestors.

**DNF cache sharing**: During builds, a persistent cache is mounted into the build instance at `/var/cache/libdnf5` so downloaded RPMs and metadata are reused across builds.
On every platform the cache is a custom Incus storage volume (`dnf-cache`) on the CoW pool, which works for both instance types: containers get a bind mount, VMs get it over virtiofs.
VMs are included because each VM build otherwise re-downloads the full repo metadata on its first dnf run (~20s).
The volume is attached before the build instance starts (`attachDnfCache`; see "Devices are attached before start"), which keeps it out of a VM's 8 hotplug slots.
It also means incus-agent has mounted it before the first exec, so no dnf run can race an asynchronous hot-plug mount and fill the image's own cache dir.
The package **install/upgrade** paths (and the VM rootfs dependency install, which runs only when `growpart`/`resize2fs`/`xfs_growfs` are missing: the prebaked VM image ships them, and a no-op dnf install still costs seconds of metadata loading) use `--setopt=keepcache=true` so downloaded RPMs persist, `--setopt=metadata_expire=3600` (1 hour) so repeated builds within that window skip metadata downloads, and `--setopt=max_parallel_downloads` (scaled to `CpuInfo.logicalCores()`, capped at dnf's practical max of 20) to parallelize the download phase.
The rpm transaction itself is serial.
These shared flags are centralized in `BuildCommand.DNF_BASE_OPTS` and spliced on by `dnfCommand(...)`.
Repo-management calls that download nothing (`dnf copr enable`, `dnf clean`) run plain `dnf` — the cache/download flags don't apply to them.
The cache device is unmounted before the final cleanup step so the image stays small.
For VMs, `unmountDnfCache` unmounts inside the guest before removing the device (the agent would otherwise tear the mount down asynchronously).
`cleanCaches` skips `dnf clean`/`rm -rf` of the cache dir while it is still a mount point, since cleaning through a live mount would wipe the shared volume for every later build.
`isx clean cache` wipes the cache by deleting the storage volume (via `IncusClient.deleteStorageVolume`).
The volume is automatically recreated by the next build.

**DNF failure recovery**: All DNF install/upgrade commands are wrapped in `runDnf()`, which retries once on any failure.
It runs `dnf clean metadata` to clear potentially stale repo data, then retries with `--refresh` to force fresh metadata from a (potentially different) mirror.
This handles transient mirror issues like packages appearing in metadata before their signatures are available, without requiring manual intervention.

**DNF output**: dnf steps render a single animated `TerminalProgress` spinner line — the same braille-spinner helper used for parallel repo cloning — rather than streaming dnf's verbose per-package output to the terminal, keeping isx's own warnings and caveats visible instead of scrolling off in a flood.
Install/upgrade (`runDnf`) stream dnf's output through a parser instead of echoing it: dnf5's non-TTY output emits one `[N/M] <action> <package>` line per completed step in both the download and transaction phases.
So the spinner shows live "N/M — current package" feedback parsed from dnf's own progress lines.
(dnf has no dedicated single-line progress mode; `--quiet` would suppress exactly those lines, so parsing the native output is the only way to get live feedback.) dnf's non-TTY column truncates each NEVRA's version/arch tail and the width can't be raised (`COLUMNS`/`terminal_width` are ignored without a TTY; a PTY widens it but replaces the tidy per-line output with concurrent ANSI progress-bar redraws).
So `shortenNevra` reduces each `name-epoch:ver-rel.arch` to its bare package name for the live label.
The streaming-without-echo path is `Container.execLines` → `IncusClient.shellExecStreaming` → `util/LineOutputStream`.
COPR-enable and VM rootfs-expansion use `runWithSpinner` with captured exec (single/short operations that don't warrant a parser).
On failure, the full output is printed to stderr after the animated line (so it doesn't interleave with the live display); the `--refresh` retry is surfaced as a live "retrying with --refresh" sub-line.

### Branching

Like `git branch`, branching creates an instant copy-on-write clone of any template image.
Each branch has its own independent filesystem -- changes in one branch cannot affect the template image or any other branch.
The CoW storage backend (btrfs/zfs/lvm) deduplicates unchanged data transparently at the block level, so branches are instant to create and only consume disk space for their own modifications.

**Static IP assignment**: Each branch receives a deterministic static IP on the bridge subnet at creation time.
`StaticIpAllocator` scans all existing instances for claimed `ipv4.address` values on NIC devices and picks the lowest free host address (`.2`–`.254`).
The IP is set on the Incus NIC device (so Incus is authoritative) and a `systemd-networkd` `.network` file is pushed into the stopped container before start, so the interface comes up statically at boot with no DHCP lease to expire.
It is pushed root-owned `0644` explicitly: pushed with the local temp file's `0600`, `systemd-networkd` (which runs as `systemd-network`) could not read it, and only templates that already carried a `10-eth0.network` hid that, since overwriting keeps a file's mode.
Nothing reserves an address between the listing that finds it free and the write that sets it, so `StaticIpAllocator.claim()` holds a `HostLock` (`~/.cache/incus-spawn/locks/.static-ip.lock`; see "Lifecycle locking") from the listing until the caller's write returns (#815).
The name is dot-prefixed because the TUI's per-instance locks share that directory as `<instance>.lock`: an instance called `static-ip` would otherwise open, close and delete the claim's file.
The listing fails closed: one that cannot be read must not make every address look free.
Everything that does not depend on the address is read before the claim, so concurrent branches wait on each other only for one listing, the `.network` push and the write.
That includes the bridge, read once as a `BridgeAddress` (gateway, subnet, prefix length): branching used to read it three times, for the gateway, the allocation and the prefix.
The lock is per user, since it lives under `$HOME`.
Where it cannot be taken at all -- a home on NFS without lockd -- the claim warns once and goes on with only the in-process lock (`HostLock.acquireOrDegrade`) rather than failing every branch.
Incus's check below turns a collision into a failed branch, not a shared address, which is what `main` did before the lock existed.
A holder that never lets go still times out; only an unusable file degrades.
The claim and the stale-subnet repair report through a `StaticIpAllocator.Output` (step, warn), so the TUI -- which runs that repair on its own screen before a shell -- captures them into its warning log instead of printing over itself (see "Warnings while the TUI owns the terminal").
Writers it cannot see -- `sudo isx`, another host user's isx on the same daemon, a manual `incus config device set` -- are mostly caught by Incus itself, which refuses a second NIC with the same `ipv4.address` on the bridge (409) and so fails the branch rather than sharing an address.
Mostly, because Incus validates before it commits and two concurrent writes can both pass; only the lock closes that window.
Nor does that check cover copies.
Incus deliberately creates a copy whose NIC conflicts with another and only logs it.
So a branch of a branch used to start with its source's NIC address and `static-ip` metadata.
The proxy then mapped the address to whichever of the two it listed last, and a copy left behind by an interrupted branch kept it for good.
`IncusClient.copy()` now drops both in the copy request itself (a device in the request replaces the source's whole; an empty config value unsets), from the source read `planCopy` already makes.
An address only ever enters an instance through `claim()`.
Duplicates made before this, or by a manual `incus copy`, can still exist.
So the proxy's `InstanceRegistry` no longer lets listing order pick the owner of a shared `static-ip`.
Incus will not start an instance whose NIC address another NIC holds, so the one running claimant owns it, and with none or several running the address maps to nobody (with a warning) rather than to whoever was listed last.
The lock is on the host rather than in the proxy: the address must be claimed in the branch's one write (#804), full-internet branches need no proxy, and the proxy only rebuilds its view from Incus anyway.
Templates do not have baked-in addresses — all CoW branches share the template filesystem, so a static address in the template would collide.
A build container whose template pins an account holds a claimed address only while it builds, so the proxy can serve it its template's accounts, and keeps DHCP in the guest.
Its claim also skips every address the bridge's DHCP server has leased (see "Builds are served their template's accounts").
The base image (from `Sanne/incus-spawn-images`) provides `systemd-networkd` and bakes in a connectivity watchdog (30s systemd timer) that detects IP loss after host sleep/wake and restarts `systemd-networkd` to recover; `isx` only supplies the per-branch address.

**VM deferred file pushes**: File push to a stopped VM is not possible (it requires the running `incus-agent` inside the VM).
For VMs, `BranchFlow` (behind both `isx branch` and the TUI) skips pre-start file pushes (network config, SSH keys, terminfo) and instead call `InstanceLifecycle.pushDeferredVmFiles()` after starting the VM and waiting for the agent to become ready.
The network config (IP, gateway and the NIC's MAC address) is read from one instance GET at push time.
A VM's file matches its NIC by `PermanentMACAddress=` (the device's `hwaddr`, else `volatile.<nic>.hwaddr`), never by name -- the permanent address, so a VLAN or bridge built on the NIC, which takes over its MAC, does not match too.
Incus renames a container's NIC to the device's `name` (`eth0`).
But a VM guest keeps its kernel's predictable name (`enp5s0` on Incus's PCIe layout).
So the `Name=eth0` file a VM used to get matched nothing and the VM ran on the base image's own DHCP config (#997).
That DHCP config still covers the first boot -- Incus's DHCP server hands out the NIC's `ipv4.address`, so the address is the same -- and the pushed file takes over from the next one.
A VM whose MAC cannot be read keeps DHCP rather than get a file matching no link or every link (a nested `docker0` included).
Because the file now applies, a stale-subnet repair must reach the guest too, or the VM would come up on its old address, which IP filtering drops.
The repair marks the VM `network-push-pending` in the same config write.
`isx init` and `isx doctor` (`migrateAllInstancesToNewSubnet`) cannot repair a running instance in place.
Incus validates a running instance's NIC update against the address it holds now, which the subnet change left off the bridge's subnet.
So it refuses the very update that would move it (#1009).
They stop it, warning that they do, move it, and start it again through `startForUse` (new secret, host devices); a container boots its new file, and a VM gets its file once its agent answers.
A guest that ignores the shutdown is never forced, and a frozen instance is not stopped (a stop waits on a guest that cannot answer).
Both are left as they were, with a warning to repair them again once stopped or resumed.
Any other VM gets it at its next `isx shell` or TUI shell.
`ensureReady` reads the mark from the same instance GET that gives it the status, and pushes whatever the status, since a VM started outside isx after the repair (`incus start`, autostart after a host reboot) is already running and has booted its old file.
A VM branched with `--no-start` owes its file the same way, since the push after start never ran.
`configureBranch` marks it (`BranchSettings.startsNow` false) in its one write, the one that claims its address.
So the mark costs no request.
Its first start through `ensureReady` -- `isx shell`, `isx run`, the TUI's shell, or MCP `start_instance` (`InstancePrep.prepare`) -- delivers the file, as for a repair (a start that does not go through it, such as `isx project update`'s bare `incus.start` or a plain `incus start`, boots it on DHCP with the same address until the next one that does).
Without it such a VM stayed on DHCP for good, with the lease-expiry-over-sleep/wake problem static addresses exist to avoid (#1004).
A started branch is not marked: it gets its file right after the start, and marking it would cost a write to clear.
The mark is not a reassignment, so it does not bring back the "Static IP mismatch" banner, which only `fixStaticIpIfNeeded`'s actual reassignment shows.
The mark clears once the file is pushed and `networkctl reload` succeeded -- a failed reload leaves the guest on its dropped address, so it stays owed and the user is told -- or when there is no file to push (no readable MAC: DHCP covers the VM).
A copy drops it with `static-ip`, since it is owed to the source's guest.
The reload needs no interface name either: it re-reads the files and reconfigures every link whose file changed.
For the same reason the post-start setup script waits for the instance's assigned address on any link (`InstanceLifecycle.addressUpCheck`) rather than for an address on `eth0` (with no recorded address, for a default route, which a nested `docker0` does not add).
On a VM that wait never succeeded.
Every VM branch spent both of `pollUntilReady`'s runs (~35 s) on it before warning that setup may not be complete.
Waiting for the assigned address rather than any address also keeps a nested bridge that comes up first from passing for the instance's network.

The TUI branch modal supports:
- Custom name
- GUI and audio passthrough (Wayland + PipeWire + GPU)
- Network mode selection (full internet / proxy-only / airgapped) via three-state radio
- Inbox mount (read-only host directory for sharing files into the container)
- VM resource limits (CPU, memory, disk)

The modal only collects inputs.
The branch itself is made by `BranchFlow`, the same `preflight()`/`create()` as `isx branch`.
So a TUI branch gets the template's and source's account selection, the proxy refresh before start, and the CA, `resolv.conf` and identity repairs after it.
It used to be a hand-kept copy of the flow that skipped all of those, so a branch reusing a destroyed instance's static IP could be served that instance's credential account (#800).
Its initial values come from `BranchFlow.defaultsFor()` too -- the one rule `create()` applies to whatever a request leaves null: GUI and KVM on when the source's definition sets `gui: true` / `type: kvm` or the source itself has GUI or KVM (GUI only for a container, since passthrough's GPU device would keep a VM from starting; only from a Wayland session, by the checks `configureGui` makes; and never from a project-local definition or a source built with one, since GUI hands over the host's GPU and whole `XDG_RUNTIME_DIR`.
Where the source asks for GUI but does not get it by default, a note says why -- printed by `create()` with the branch's progress, for the dialog too, which leaves an untouched GUI box to the default -- rather than errors; `Request.defaults()`, `isx mcp`'s request, pins GUI off whatever the template says, which `BranchDefaultsTest` holds), and the adaptive CPU, memory and disk limits for its machine type, worked out only when asked for (on macOS the memory default forks `sysctl`).
The dialog used to compute its own, and its KVM rule already differed from the CLI's, which ignored the definition (#869).
For KVM, "the source itself" means a template's (built or project) `instance-mode: kvm`, but any other source's own `kvm-enabled` stamp: a branch inherits its template's `instance-mode` even when made with `--no-kvm`, which would otherwise hand `/dev/kvm` back to that branch's own branches (#1034).
`create()` takes those defaults, its machine type and the copy plan from the source read `preflight()` already made (`Preflight.sourceInstance`), and the dialog reads the source once for its defaults and account rows.
The action the new branch's shell opens with is shared the same way, `ActionResolver.defaultCommandForBranch` from that same read (#868).
The dialog used to resolve it by hand, without feature gates, transitive dependencies or `expand: repos`, and with three reads of its own.
Its reference comes from the current YAML, so changing it needs no rebuild.
But it is matched against the tools the source was built with (`BUILD_SOURCE`), which fails safe.
A tool added to the YAML and not yet built gives a plain shell rather than a launch of a binary that is not there.

### Terminal Output Visual Language

All multi-step command output follows a consistent visual language, centralized in `BuildOutput` (`common/.../util/BuildOutput.java`).
All output helpers live there; individual commands should not define their own ANSI constants or formatting patterns.
Beyond build/branch, this governs the other lifecycle commands too — `vm` (start/stop/resize), `destroy`, `update-all`, `update-base`, and `project` — plus the shared `VmManager`.
(`isx init`'s large interactive first-run flow is the one deliberate exception, kept in its own style for now: its boxed, numbered headers stay, but the lines under them report outcomes rather than attempts, its `[n/N]` counter is counted from the list of steps rather than written down, and host commands show their output only when they fail (#906; the rules are in `.claude/rules/commands.md`, "Init output").)

**Structure:**

- **Section** (column 0): `Refreshing 8 host repos:` or `Updating 6 template(s).` via `section(msg)`.
  Introduces a block of work at the left margin, preceded by a blank line.
  Use for top-level groupings; individual operations within a section get `header()`.
- **Header** (bold bullet): `  ● Building tpl-dev  [1/3]`, `  ● my-branch  ← tpl-dev`, or the generic `  ● Resizing VM data disk` via `header(msg)`.
  Identifies the top-level operation.
  Preceded by a blank line.
- **Group** (`▸` title, children one level deeper): `    ▸ Tools  maven-3, mx` via `try (var g = group(title, detail))`.
  Frames a batch of steps that belong together (packages, tools, skills, repositories); closing it leaves a blank line so the next top-level step does not read as part of it.
  `list(items)` prints a wrapping comma-separated list at the current indent (the packages being installed).
  `header()` resets the nesting, so a group an exception left open cannot skew the next operation.
- **Step** (4-space indent, deeper inside a group): `    Configuring network...` — a complete line for fast or informational actions.
  `ok(msg)` prints a finished result as `✓ msg` without a live step.
- **Live step** (`stepStart` / `stepDone`): on an ANSI terminal the line animates a braille spinner, with an optional dim detail set by `stepProgress()` (`verifying: mx version`), and turns into `✓ label` when done, with the elapsed time once it passes two seconds (`✓ Installing packages and dependencies  41s`).
  `stepDone(detail)` adds a result — `✓ Extracting root disk (4.0G)`.
  `stepBreak()` ends it as `✗ label` before an error.
  Without a terminal the same calls print `Starting container... done.` for logs.
  The step is not done until *all* its work is: each tool's `verify` is its own step under `▸ Verifying`, because a line saying "done" while a first `mx version` takes seconds reads as a hang.
  Never print a `Doing X...` line whose result lands on a separate line.
- **Step detail** (`stepNote`, `stepWarn`): dim or yellow lines one level under the step they describe — a tool's verified version, or why its verify failed.
  A warning (`stepWarn`, `warn`) is followed by a blank line, separating it from the regular flow; a group closing right after it does not add a second one.
- **Note** (dim, 4-space indent): informational messages that should be visible but not alarming — e.g. `    Parent 'tpl-dev' already up-to-date, skipping.` Uses ANSI dim (`\e[2m`).
- **Warning banner** (yellow borders, stderr): `warnBanner(title, lines...)` for diagnostic warnings that need to stand out — subnet conflicts, CA mismatches, firewall issues.
  Title is bold yellow, body lines are plain.
  Distinct from `warn()` which is a single inline yellow line within step output.
- **Success** (green checkmark): `    ✓ my-branch is ready.` — final confirmation, preceded by a blank line.

**Headers live in commands, steps in shared helpers.**
A shared operation that runs both standalone and nested (e.g. `VmManager.start()`/`stop()`, called both by `isx vm start` and as sub-steps of `isx vm resize`) emits only steps/notes and never its own header.
The command class prints the one `● Header`; the shared method's steps then indent correctly under whichever header the caller printed, so no duplicate or nested headers appear.
Where a shared step would otherwise repeat the header verbatim, reword it (the stop step reads `Shutting down VM...` under a `● Stopping VM` header).

**Output during a live step.**
While a step animates, `System.out` and `System.err` are wrapped: the first write from anyone else freezes the step line (`… label`) and moves below it, and the spinner stops drawing, so a warning printed mid-step is never drawn over.
Its `stepDone` then prints `✓ label` on a fresh line.
The wrap is restored when the step ends; `BaseCommand` calls `abandonStep()` after every command so a step an exception cut short shows `✗` and stops guarding the streams (the TUI resumes in the same process after a build).
A child process that writes to the terminal directly — inherited IO, or a `sudo` that may prompt for a password on the tty — bypasses the Java streams, so its caller calls `releaseTerminal()` first (`ProxyService.runQuiet("sudo", …)`).
Host commands run under a step capture their output instead of inheriting it (`YamlToolSetup.runProcess`).

**Warnings and errors** go to stderr.
Notes (dim) are for expected conditions the user may want to know about (skipped steps, cache hits).
Warnings (`System.err`) are for conditions that may need action.
The distinction: a note is "this is fine, FYI"; a warning is "you should look at this."

**Container commands during build:** tool `run:` and `run_as_user:` scripts use `Container.runQuiet()`/`runAsUserQuiet()`, which capture output and only print it on failure.
This prevents leaked output (e.g. systemd's "Created symlink" lines) from breaking alignment.
`runInteractive` is reserved for commands that genuinely need live terminal output (e.g. interactive shells).

**Adding new output:** use `BuildOutput.section()` to introduce a block of work, `header()` to frame a named multi-step operation within it, `group()` for a batch of related steps, `step()`/`ok()` for quick actions, `stepStart()`/`stepDone()`/ `stepDone(detail)` for slow ones (with `stepProgress()` when a phase can take a while), `stepNote()`/`stepWarn()` for detail about the step above, `note()` for informational dim messages, `warnBanner()` for bordered stderr warnings, and `success()` for the final confirmation.
Do not add raw `System.out.println()` with inline ANSI escapes, and do not leave a `Doing X...` line dangling.
Pure reports/tables and interactive prompts (e.g. `proxy` status, `clean` summaries, the TUI) are not step sequences and stay as plain output.

### Output for scripts: `--format=table|plain|json`

A script driving isx needs output it can parse, and the human output is the wrong thing to parse: padded columns, a header, ages like `3h ago` that contain a space, prose when the list is empty.
So query commands take `--format` (#1036), through one shared helper, `OutputFormat` (`common/.../util/OutputFormat.java`), rather than each inventing its own.

- **`table`** is the human output and the default.
  It may change in any release.
- **`plain`** is one record per line, tab-separated, `-` for an empty field, with no header, padding, colour or glyph, and no output at all for no results.
- **`json`** is an array of objects (one object for a single-item command).
  An absent value is `null`, times are ISO-8601 with their offset (`+02:00`, or `Z` on a UTC host; a legacy date-only stamp is an ISO-8601 date, an unreadable one `null`), sizes are bytes.
  A response isx cannot read is an error (exit 1), never an empty result, which a script would take for "nothing there".
- **Exit codes**: 0 success (an empty listing included), 1 when the command fails or rejects a value it checks itself (`--format=yaml`), 2 when aesh cannot parse the command line (unknown option, missing value, stray argument; the usage goes to stderr).
  2 is aesh's, for every command, so the contract documents it rather than remapping it; `ExitCodeTest` pins both.
- **The contract**: `plain` and `json` fields may be added at the end, never renamed, removed or reordered.
  Results go to stdout and only results: errors and diagnostics go to stderr.
- **Control characters** (#1118): both formats are read on terminals too, and a value can be a stamp someone set by hand, so neither writes a control character in a value raw.
  `plain` is lossy: every C0 control (tab and line breaks included), DEL, C1 control and U+2028/U+2029 becomes a space (`OutputFormat.oneLine`), which keeps a record one safe line, and so does every bidi embedding, override and isolate (U+202A-U+202E, U+2066-U+2069), which would make a terminal draw the rest of the line, the following fields included, out of order.
  The marks (U+200E, U+200F, U+061C) stay: they reorder nothing beyond their neighbours.
  `json` is exact: Jackson escapes C0 as JSON requires (tab, line feed, carriage return, backspace and form feed as `\t` `\n` `\r` `\b` `\f`, the rest as `\uXXXX`).
  `OutputFormat` also escapes DEL, C1, U+2028/U+2029 and the bidi controls as `\uXXXX`, which JSON allows and every parser reverses.
  Both formats test one predicate, `OutputFormat.isControl`, so they cannot disagree on the set.
  A script that needs a value exactly reads `json`.
  Escaping in `plain` instead (`\e`, `\\`) was rejected: it would make every reader decode, and change the many values with a backslash for the rare one with a control character.
  The `isx list` table and the TUI show an instance stamp (parent, created, the MCP fields) through the same `oneLine`, so they cannot disagree; `\p{Cntrl}`, which the MCP column used before, is ASCII-only and let U+009B (8-bit CSI) through.
  The `isx templates` and `isx tools` tables (`list -v`, `tools show`) and `isx templates edit`'s validation findings do the same for definition text (#1133).
  A project-local definition ships with whatever repository was cloned, so its name, source and description are as untrusted as a stamp.
  A validation error keeps the line breaks of the advice isx writes into it.
  So the definition text it quotes goes through `oneLine` where the message is built (`HostResourceSetup`'s refusals, `YamlErrors.friendly`).
  Once a message is assembled, a forged line break cannot be told from isx's own.
  Each line is made safe again when it is printed.

A command parses its `--format` with `OutputFormat.parse` (`isx list`, which also has `--plain`, with `OutputFormat.resolve`), builds each record once, as an ordered map of field name to value, and hands the list to `format.print` (or one record to `format.printOne`, an object in `json`).
Both machine formats print the same map, so the field order of `plain` is the field order of `json` and the two cannot drift apart.
Maps rather than records are what Jackson serializes without reflection registration in the native image.
A list value (`tools show`'s `packages`, `account list`'s `pinned_by`) is a JSON array and, in `plain`, its elements joined by `,`, so a record stays one line.

Every query command takes it (#1038): `templates`, `tools list`/`show`, `account list`/`show`, `proxy status`, `doctor`, `vm status`, `update-base --list`, and `branch`, whose record is the new name alone so that `$(isx branch ... --format=plain)` captures it.
The bare group commands (`isx templates`, `isx tools`, `isx account`) run their `list` and take its `--format`.
A command whose work prints progress on the way (`doctor`'s checks, `branch`'s flow) runs it under `BaseCommand.withStdoutOnStderr`, so stdout holds only the result whatever the code underneath prints.
The machine formats never prompt: `doctor` offers no remediation and `branch` opens no shell.
Exit codes are those of the table output, so a script reads the same state from either.
`proxy status` prints its record in every state and exits 0/1/2/3 (when it cannot check at all, on Linux with Incus unreachable, the record says `status: "unknown"` with a `check_error`: it exits 1 like `not_running`, as the table always has, but no longer reads like it), `doctor` exits 1 when a check fails, and `vm status` exits 1 when Incus is unreachable (#1037 made the table do so).
Each command with more than one failing state keeps its codes in one `exitCode` method that every format returns, so they cannot drift.
`account list` prints `null` for `pinned_by`/`following` when Incus could not be asked, never an empty list, which would read as "nobody".
`isx templates` adds each definition's build state and staleness at the end of its record (#1115): `built`, `built_at`, and the TUI's three marks as `version_outdated`, `definition_changed`, `parent_rebuilt`.
Both read one judgement, `TemplateStaleness`, which takes only what it is handed.
So the CLI pays for one `GET /1.0/instances?recursion=1` (the table output still asks Incus nothing) and fingerprints tools only when a built template's definition is compared, never the TUI's reload.
As for `account list`, a listing that cannot be read makes those fields `null` rather than "not built".
Nothing the table tells a person about state is left out of the record.
Problems a template's choice causes (`account show`'s `template_problem`), whether a proxy restart can clear drift (`restart_helps`), a leaking vsock forwarder (`vsock_connections_high`) and a pinned base image (`pinned`) are fields.
`update-base --list` gives the current base image a record of its own (`listed: false`, last, no date) when it is not among the releases fetched -- older than all of them, unpublished or deleted, so not a statement about its age -- and a script always finds it.
Only descriptive detail (a tool's parameter types, the system diagnostics under `vm status`) stays table-only.
`doctor`'s `detail` and `remediation` are text for people, not values a script should parse; the table's wrapping parentheses are dropped from `detail`.
`QueryCommandFormatTest` pins the JSON of each, and `ExitCodeTest` that each rejects `--format=yaml` alike.

**`isx list` from the CLI is cheap.**
Outside the TUI it reads the instance listing once (`GET /1.0/instances?recursion=2`) and nothing else: no definition reload, tool loader, pool or btrfs probes, which the TUI's `reloadData()` runs and a script polling `isx list` should not pay for.
`-q` reads at `recursion=1`: names and states need no live state, and shell completion runs it on every TAB.
`ListCommandOutputTest` pins both requests.
The `created` time comes from `Metadata.createdIso`, next to `Metadata.now()` which writes the stamp, so every command that prints one prints the same instant.
It tells templates apart by the `base` type every build stamps on them, so it needs no definitions, and never by the `tpl-` name prefix: a branch may be called `tpl-anything`.
A copy carries its template's `base` type.
So `BranchFlow` stamps `clone` in the copy request itself, as it does the instance secret.
A branch interrupted before `configureBranch` (Ctrl-C, a failed write) is still listed, never mistaken for a template and leaked (`InterruptedBranchTest`).
Incus instances isx did not create (no isx metadata) are not listed.
`-q`/`--quiet` prints names only, and `--status=running|stopped` filters.

**`isx instances` is deprecated.**
It listed every Incus instance not named `tpl-*`, which disagreed with `isx list` both ways.
Completion scripts installed by older releases call it by name, so it stays, hidden, as an alias for `isx list -q` until a later release removes it; the scripts this release generates call `isx list -q`.

**Commands that act need clean stdout, not a format** (#1037).
`BuildOutput` writes colour only when `BuildOutput.ansi()` says so, which is `TerminalProgress.isAnsiTerminal()`: false without a console, for `TERM=dumb`, and when `NO_COLOR` is set to anything non-empty (no-color.org), so the live spinner also falls back to `label... done.` lines.
`TerminalLink.link()` asks the same gate and prints the bare URL off a terminal, since an OSC 8 hyperlink is an escape too.
The console is decided by stdin and stdout, never stderr, and Java cannot ask whether stderr alone is a terminal without native code.
So the contract is that escapes follow stdout: redirecting stdout or setting `NO_COLOR` clears both streams, while `2>log` alone from a terminal keeps colour in the log.
The README says so rather than promising more.
`warn`, `stepWarn` and `note` go to stderr: a step line is what the command did, a warning or note is a diagnostic about it.
The blank line that closes a warning goes to stdout, because it separates the warning from stdout's own flow.
A following header then neither repeats it on a terminal nor loses it in `>out.log`.
So a line that is the command's result, such as `clean --dry-run`'s "Would delete ...", is a step, not a note.
A status command's report is its result whatever it says.
`isx proxy status` prints it on stdout in every state and tells them apart by exit code (1 not running, 2 stale DNS overrides, 3 stale bridge address), and `isx vm status` exits 1, the reason on stderr, when Incus is unreachable.
Every other styled line (`isx init`, the proxy banners, build failure reports) goes through the same `BuildOutput.styled()` (#1082); only what is drawn on a terminal alone (live steps, `TerminalProgress` formatters, window titles behind `hasTerminal()`) writes escapes directly.
`AnsiEscapeGateTest` fails on any raw CSI or OSC literal unless its line carries a `// raw ANSI: <why>` comment (on that line, so a marker never covers another).
Only the two gates, `TerminalProgress` and the shell status bar are exempt as whole files, so a new escape on an ordinary path in `BuildCommand` still fails.
A styled string is never a `static final`: it would be decided when the native image is built.
`BuildOutputTest` and `StatusCommandOutputTest` pin these.

**Not yet covered**: the other query commands (`templates`, `tools`, `account`, `proxy status`, `doctor`, `vm status`, `update-base --list`) have no `--format` yet.
Each adopts the same helper and contract when it gains one.

### Resource Limits (Adaptive)

Detected at branch time from host resources (`ResourceLimits`):

- **CPU**: VMs get `available_cores - 2` (host keeps 2 cores), no more than 8, and on a hybrid host no more than its top tier's physical cores, minimum 1 (#1238, see "VM boot: where the wait goes"); containers are not pinned
- **Memory**: containers 60% of total RAM; VMs 25% of total RAM, capped at 16 GiB, at least 4 GiB (but never more than the container default, on hosts too small for that floor)
- **Disk**: 100GB root disk (a ceiling: CoW storage is thin-provisioned)

Overridable with `isx branch --cpu/--memory/--disk` and via the TUI branch modal (for VMs, all three fields are shown).

**Why VMs get a separate, smaller memory default.**
`limits.memory` means two different things.
On a container it is a cgroup ceiling: nothing is reserved, and 60% per container just stops one runaway from taking the host down.
On a VM it is the guest's RAM size.
QEMU allocates lazily, but the guest kernel treats all of it as its own and grows its page cache into it, so after one large build a VM holds most of its `limits.memory` on the host.
Two VMs at 60% each overcommit the host.
Idle vCPUs cost almost nothing, so the CPU default is not held to the same standard.

**Free page reporting.**
VMs also get virtio-balloon free page reporting, set through `raw.qemu.conf` (`[device "qemu_balloon"] free-page-reporting = "on"`, which Incus merges into its own balloon device).
Without it QEMU keeps every page the guest ever touched until the VM stops.
With it, pages the guest frees are returned to the host, so memory freed after a build is given back instead of lingering until shutdown.
Page cache still counts as used, so this complements the smaller default rather than replacing it.
`InstanceLifecycle.enableFreePageReporting()` applies it at VM build and at derive (whose parent may predate it).
At branch (whose template may predate it) `configureBranch()` adds it to its one write, at no extra request.
A `raw.qemu.conf` someone else set is left alone, because a merge that breaks the QEMU config leaves a VM that won't start.
QEMU rejects unknown device properties, so this needs QEMU 5.1 or later.

### Container Configuration

**Capabilities**: `lxc.cap.drop =` (don't drop any — the container is the security boundary).

**Host sysctl relaxation** (`/etc/sysctl.d/99-incus-spawn.conf`, applied by `isx init`):
- `kernel.perf_event_paranoid = -1` — unrestricted perf profiling (kernel-inclusive sampling, hardware counters where available)

**Kernel same-page merging** (`/etc/tmpfiles.d/incus-spawn-ksm.conf`, applied by `isx init` on Linux): sets `/sys/kernel/mm/ksm/run` to 1.
QEMU marks guest RAM `MADV_MERGEABLE` by default, and VMs branched from one template hold many identical pages (kernel, JDK, libraries, page cache of the same files), so KSM lets them share one copy.
It costs some host CPU for scanning, bounded by the kernel defaults (`pages_to_scan`, `sleep_millisecs`, and `smart_scan` on 6.7+), which isx does not tune.
Containers are unaffected, because their processes do not opt in.
KSM breaks transparent huge pages it merges into 4K pages, trading some TLB efficiency for memory on the shared pages only.
Init leaves KSM alone when `ksmtuned` is active, since that daemon owns the knob.

These are host-wide because they are not namespace-aware.
`kernel.dmesg_restrict = 0` and `kernel.yama.ptrace_scope = 0` hold by default (`dmesg_restrict`'s default is 0; Yama is not compiled into the appliance kernel, so ptrace is unrestricted).
`ping_group_range` is wide in modern kernels and further ensured by not dropping `CAP_NET_RAW`.

**DNS**: systemd-resolved disabled, `/etc/resolv.conf` points at Incus bridge gateway (`incusbr0`), immutable via `chattr +i`.

**Terminal title**: The host terminal title is set to `isx:<containername>` during `isx shell` sessions (via OSC escape sequences) and restored on exit.
Inside containers, `PROMPT_COMMAND` in `.bashrc` overrides Fedora's default title-setting to maintain the `isx:<hostname>` title.
Claude Code's built-in terminal title override is also suppressed so the container name stays visible.

**Interactive shells need a terminal, and only a lost session is retried.**
The PTY session reads keystrokes from `/dev/tty` and writes to stdout, so with no terminal there is no session to have.
`IncusClient.interactiveShell()` refuses up front (`hasTerminal()`: stdin and stdout a terminal, `Console.isTerminal()`) with a `NoTerminalForShellException`.
`execPty` opens `/dev/tty` before it posts the exec, so a missing controlling terminal is refused the same way rather than after a process was started in the guest.
The reconnect loop retries only an I/O failure of a session that could run; it never retries `NoTerminalForShellException`.
Before #1027 a script or agent running `isx branch` hit the failed `/dev/tty` open after the exec, which read as a lost connection: ten reconnects and ~76 s, each starting another exec, then `PTY exec failed`.
Since the branch itself had succeeded, `isx branch` now reports it, says how to connect, and exits 0 without a shell; `isx shell` refuses before starting the instance, with exit status 1.
Requiring stdout too means `isx shell <name> | tee log` is refused, where it used to work: it is the convention `confirmDestructive` already uses, and a shell whose output is not a terminal cannot be drawn correctly anyway.

**Shell status bar** (feature-flagged as `shell-status-bar`): `ShellStatusBar` pins a two-line bar at the bottom of the terminal during `isx shell` sessions, showing instance name, template, IP and network mode.
F12 opens a quick-action menu populated from tool actions with `shell_menu: true` and a `shortcut` -- only `url` actions, because the menu runs them while the session owns the terminal in raw mode, and a `command` action's process would share that tty with the shell (fighting it for keystrokes and writing past the bar).
Actions run on a virtual thread, so a slow URL launcher never holds up keystrokes, and through `ToolAction.executeWithoutPrompting`, which returns an error where `execute` would ask the user something (VS Code's missing Remote-SSH extension) and by default refuses, so a new action is safe until it says it never prompts.
A prompt would be drawn over the session and its Enter would go to the shell.
The TUI's F9 menu runs its in-process actions (url, clipboard) through it too, since the TUI still owns the terminal there (#982); only `isx run` and a `command` action deferred until the TUI has quit call `execute`, and may prompt.
A bar with an empty menu does not intercept F12 at all, so programs that bind it keep it.
The menu is a `ShellMenu` (actions plus the `ActionContext` they run against, whose `parent` is also the bar's template label), resolved by `ActionResolver.shellMenu` from a single context, which `buildActionContext` builds from one instance read, plus `/state` for the address of a running or frozen guest (#979, pinned in `ActionResolverRequestBudgetTest`).
An `expand: repos` action is offered once, for the repo the session's workdir is in (or below).
Each entry needs its own one-key shortcut (printable ASCII, case-insensitively unique).
`ShellMenu.of` drops, with a `Warnings.warn`, an action the menu could not dispatch or one whose key an earlier action already takes.
So what the menu shows, what a key runs and whether F12 is intercepted all agree.
With the flag off every caller passes `ShellMenu.NONE` and makes no extra Incus request -- the bar is opt-in, so its cost must be too (pinned in `ShellMenuTest`).
The bar uses a scroll region (`\e[1;{h-2}r`) to confine child output above it, and reports `effectiveHeight()` (real height minus bar lines) as the PTY height so the child never draws into the bar area.
Every paint -- after a child write, and for the menu, a flash or a resize alike -- goes through one method that draws only where the child's output stands between escape sequences and characters.
A frame can end inside a CSI, an OSC or a UTF-8 character, and writing there would cut it short.
So `OutputBoundary` tracks just enough of the stream to know, and a paint asked for mid-sequence happens after the next frame that ends on a boundary -- except that a paint the user is waiting for (the menu, a hint, an action's result) is drawn anyway after 150 ms, since a child gone quiet inside a sequence sends no next frame and the parser is already swallowing keys for the menu.
A resize arriving mid-sequence drops its clear, which would land after the child's SIGWINCH redraw and blank it.
`OutputBoundary` follows the terminal on CAN/SUB (abort) and on an ESC that restarts a sequence, and bar text is cut to display columns (wide characters take two), so a row never wraps.
All output goes through a `BufferedOutputStream` so child data, scroll region fixup and bar repaint reach the terminal in one `write()` syscall (one rendering frame), avoiding visible intermediate states.
Cursor position is preserved across the fixup with a single DECSC/DECRC pair wrapping the scroll region and bar rendering.
`emitLine` does not save/restore the cursor itself, since DECSC is a single slot and a nested save would clobber the outer one.
`cleanup()` (on exit and in the SIGTERM shutdown hook) closes the bar.
It erases both bar rows and resets the scroll region between a cursor save and restore (after a CAN if the child left a sequence open), so the host prompt follows the session's last output.
From then on nothing draws until the next `setup()` -- not a frame still in flight when SIGTERM's hook runs, not a flash timer, not an action that finishes after the session, which in the TUI path would draw over the TUI.
Known limits of the scroll-region approach: the repaint restores the bar's own scroll region over any a child program set (vim splits, less, tmux), its DECSC overwrites the child's one saved cursor, and setup and resize clear the screen.
The bar stays on the main screen rather than the alternate screen, which would cost the session its scrollback and erase its output on exit (measured in #888).
`EscapeSequenceParser` handles F12 key detection (`\e[24~`) on the input side.
Normal mode passes non-F12 bytes through -- a bare Esc at the end of a read immediately, since terminals write a key sequence whole and holding it back would delay vim's Esc by a keystroke, and a CSI too long to be F12 (a mouse report) untouched.
Menu mode returns shortcut keys and swallows CSI and SS3 (`\eOA`) sequences whole.
An Esc in menu mode consumes only itself, so a key typed right after it (or Alt+key) reaches the shell once the menu closes.
`OutputBoundary` lets BEL end only an OSC: DCS, APC, PM and SOS end at ST.
Each result reports how many bytes it consumed, and the caller feeds the rest of the read again.

### SSH Key Management

incus-spawn manages a dedicated SSH key pair and per-instance SSH configuration so tools like JetBrains Gateway and `ssh` work without passphrase prompts or host key warnings:

```
~/.config/incus-spawn/ssh/
    id_ed25519          # managed private key (mode 600, no passphrase)
    id_ed25519.pub      # managed public key
    config              # per-instance Host blocks (managed by isx)
    known_hosts         # container host keys (isolated from ~/.ssh/known_hosts)
```

**Lifecycle:**

1. **`isx init`** generates the ed25519 key pair (via `ssh-keygen`) and prepends an `Include ~/.config/incus-spawn/ssh/config` directive to `~/.ssh/config` (idempotent, resolves symlinks for dotfile managers).
   The key pair is also created lazily at first branch for users upgrading from older versions.
2. **`isx branch`** (via `InstanceLifecycle.injectSshKeyIfAvailable`): injects both the managed public key and any personal `~/.ssh/*.pub` key into the container's `authorized_keys`.
   Then regenerates the container's SSH host keys (`ssh-keygen -A` + sshd restart) so CoW-branched instances get unique keys, harvests the new host public key into the managed `known_hosts`, and writes a `Host <instance-name>` block to the managed config with `HostName`, `User agentuser`, `IdentityFile`, `IdentitiesOnly yes`, `UserKnownHostsFile`, and `StrictHostKeyChecking yes`.
   After this, `ssh <instance-name>` just works.
3. **`isx destroy`** (and TUI delete): removes the Host block from the managed config and the host key entry from the managed known_hosts.

**Design decisions:**

- **Dual known_hosts**: the primary store is `~/.config/incus-spawn/ssh/known_hosts`, referenced via `UserKnownHostsFile` in the managed SSH config.
  Host keys are also written to `~/.ssh/known_hosts` because IntelliJ's built-in SSH client does not honor `UserKnownHostsFile` or `Include` directives — without the standard-file entry, IntelliJ Gateway prompts for host key confirmation on every connection.
  Entries in both files are cleaned up on instance destroy.
- **Host key regeneration**: CoW clones inherit the template's host keys, so all branches would share the same host key.
  `harvestHostKey` regenerates them before harvesting to give each instance a unique key.
- **Both keys injected**: the managed key (passphraseless) ensures tools always work, while the user's personal key is also injected so interactive SSH sessions can use their preferred key.
- **Atomic writes**: all config and known_hosts updates use temp-file-then-rename with restrictive permissions to avoid partial writes.
- **Non-fatal**: SSH setup failures never block init or branching — they warn and fall back to manual `ssh agentuser@<ip>`.

### Remote IDE Access

The built-in `idea-backend` tool installs the JetBrains IntelliJ IDEA remote development backend and registers the container for JetBrains Gateway discovery.
It uses the `requires` field to automatically pull in the `sshd` tool, which configures an OpenSSH server with pubkey-only authentication.
SSH key injection and host key validation are handled automatically by the SSH key management subsystem (see above), so Gateway connections work without any manual key setup.
This enables IDE-based development inside containers: Gateway connects over SSH and runs the IntelliJ backend process inside the container, with the full project available.

### GUI and Audio Passthrough

Enables GUI applications and audio inside containers.
A branch gets it with `--gui`, or by default when its template sets `gui: true` (or its source has it) and it is a container branched from a Wayland session; `--no-gui` opts out.
`isx mcp`'s branches never get it.
- GPU device passed through for hardware-accelerated rendering
- Host `XDG_RUNTIME_DIR` bind-mounted, exposing the Wayland socket and PipeWire/PulseAudio socket
- Environment variables written to `/etc/profile.d/wayland.sh` (`WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, toolkit backends)

### Network Modes

Branches run in one of three network modes, selectable via CLI flags or the TUI branch modal:

**Full internet** (default): Container stays on the `incusbr0` bridge with NAT masquerading and a static IP assigned at branch time.
Unrestricted outbound access to the internet.
Traffic to intercepted domains (Anthropic, GitHub) is transparently authenticated by the host MITM proxy — credentials never enter the container in any form.

**Proxy only** (`--proxy-only`): Container stays on the bridge but iptables OUTPUT rules restrict all outbound traffic to the MITM proxy (port 443) and DNS.
The container can only reach intercepted domains via the MITM proxy.

Container-side firewall rules:
```
iptables -A OUTPUT -o lo -j ACCEPT
iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -d <gateway> -p tcp --dport 443 -j ACCEPT     # MITM proxy
iptables -A OUTPUT -d <gateway> -p tcp --dport 18080 -j ACCEPT   # Health check
iptables -A OUTPUT -d <gateway> -p udp --dport 53 -j ACCEPT      # DNS
iptables -P OUTPUT DROP
```

**Airgapped** (`--airgap`): Every NIC is masked with a `type: none` device of the same name, and the instance is stamped `network-mode=AIRGAP`.
Complete network isolation — no egress at all.
Masking is the only way: Incus cannot remove a profile device from one instance, and removing an instance device that overrides one only drops the override, so the profile's NIC applies again.
That is how airgap branches kept full network access until #813.
A branch with network from an airgapped instance writes the profiles' NICs back over the masks.

### Auth & Security: MITM TLS Proxy

**API keys and tokens never enter containers.**
A host-side MITM TLS proxy (`isx proxy`) provides transparent authentication.
Placeholder values satisfy tools' local auth checks (e.g. `GH_TOKEN`, `ANTHROPIC_API_KEY`), but the proxy replaces them with real credentials before requests reach upstream servers.
Every start fills them with proof tokens derived from that start's secret (`gho_isx_<digest>`; see "Proof tokens" below).

**How it works:**

1. The proxy configures bridge-level DNS overrides (via `raw.dnsmasq` on `incusbr0`) so all containers resolve intercepted domains to the gateway IP
2. Template images include a custom CA certificate (generated during `isx init`) so containers trust the proxy's TLS certificates
3. The proxy listens on port 18443 on the gateway IP.
   An iptables PREROUTING redirect rule (installed by `isx init` via `firewall-cmd --permanent --direct`) transparently redirects traffic arriving on `incusbr0` destined for port 443 to port 18443, avoiding conflicts with the Incus daemon on port 443.
   The proxy terminates TLS using per-domain certificates signed by the custom CA
4. Based on the target domain, the proxy injects authentication headers:
   - `api.anthropic.com` — `x-api-key: <anthropic-api-key>` (direct API key mode), `Authorization: Bearer <oauth-token>` (OAuth mode, for Claude Pro/Max subscriptions), or Vertex AI passthrough/translation with GCP Bearer token (Vertex mode, see below).
     Anthropic auth is hardcoded in `MitmProxy` (three auth modes with complex routing)
   - Tool-contributed domains (GitHub, OpenAI, Bob, and user-defined tools) — credential injection is declared in YAML tool definitions via `proxy:` entries (see "Tool-contributed proxy definitions" below)
   - Container registry, Maven, and npm domains — relayed transparently with caching (no auth injection)
5. The proxy re-encrypts and forwards to the real upstream over TLS

**Redirect rule cleanup:** `isx proxy uninstall` removes the systemd service **and** cleans up the PREROUTING redirect rule and bridge DNS overrides unconditionally — even if the service removal itself fails.
`ProxyConfig.clearRedirectRules()` attempts both firewalld and UFW independently (not else-if) so that a system with firewalld installed but inactive still reaches UFW cleanup.
On firewalld it queries `--direct --get-all-rules`, extracts the gateway IP from the matching PREROUTING rule (via `FirewalldCheck.isRedirectRule`), removes the permanent direct rule, and reloads; when the daemon is inactive, it falls back to editing the persistent `/etc/firewalld/direct.xml` directly.
On UFW it reads `before.rules`, derives the subnet from the marked NAT block, and replaces the block with a redirect-free version (preserving the MASQUERADE rule that containers need for general internet access).
If the subnet cannot be derived (malformed block), the entire NAT block is removed.
`runQuiet()` returns a boolean exit status; cleanup callers check and warn on failure.
`ProxyConfig.clearBridgeDns()` removes isx's block of overrides from `incusbr0`'s `raw.dnsmasq`, along with any overrides in the pre-block layout, and keeps every other line.
`isx reset` runs the same cleanup (and also destroys all containers, templates, VM, and host data).

**Vertex AI support:** When the host is configured for Vertex AI (`useVertex=true` in config), containers run Claude Code in **Vertex mode** with `CLAUDE_CODE_USE_VERTEX=1`, `CLAUDE_CODE_SKIP_VERTEX_AUTH=1`, and `ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1`.
This causes the Vertex SDK inside the container to send already-formatted Vertex requests (`/v1/projects/.../models/...:streamRawPredict`) to `api.anthropic.com`, which resolves to the proxy via dnsmasq.
The proxy then forwards to the real Vertex endpoint with GCP credentials.
No GCP credentials enter the container.

Running containers in Vertex mode (rather than standard mode) is required because Claude Code's model list depends on the provider: standard mode ("firstParty") shows a hardcoded subset that may omit newer models, while Vertex mode shows the full model catalogue.
Using Vertex mode in the container ensures the `/model` picker matches what's available on the host.

**Three-way routing for Anthropic traffic:**

The proxy routes requests to `api.anthropic.com` through one of three paths based on the URL:

1. **Vertex passthrough** (`/v1/projects/...`): Requests already in Vertex format (from the container's Vertex SDK).
   The proxy strips `@date` model version suffixes from the URL (the global endpoint rejects them), removes the `anthropic-beta` header, injects a GCP Bearer token, and forwards to the real Vertex endpoint.
   The body is passed through unmodified — the Vertex SDK already formats it correctly.

2. **Standard-to-Vertex translation** (`/v1/messages`): Requests in standard Anthropic API format.
   The proxy buffers the body and translates to Vertex `rawPredict` format (see translation details below).
   This path is used if a container happens to send standard-format requests (e.g. from curl).

3. **Direct forwarding** (all other paths): Non-messages endpoints (settings, bootstrap, feature flags, MCP registry) are forwarded to the real `api.anthropic.com` with credential injection.
   These endpoints don't exist on the Vertex API.

**Standard-to-Vertex translation details** (path 2):

- URL: `/v1/messages` → `/v1/projects/{projectId}/locations/{region}/publishers/anthropic/models/{model}:rawPredict` (or `:streamRawPredict` when `stream=true`)
- Auth: replaces `x-api-key` with `Authorization: Bearer <gcp-token>` (obtained via `gcloud auth print-access-token`, cached ~50 minutes)
- Body: extracts `model` field and moves it into the URL path, stripping date suffixes (e.g. `claude-sonnet-4-6-20251001` → `claude-sonnet-4-6`)
- Body: adds `"anthropic_version": "vertex-2023-10-16"` (required by Vertex rawPredict)
- Body: strips all top-level fields not in the Vertex allowlist (beta features like `context_management` cause "Extra inputs" rejections)
- Body: recursively strips `scope` from nested `cache_control` objects (beta feature unsupported by Vertex)
- Header: removes `anthropic-beta` (Vertex rejects beta feature flags; features are enabled via `anthropic_version`)
- Host: rewrites to the Vertex endpoint hostname for the configured region

The body translation uses an allowlist approach: only known-good fields (`messages`, `system`, `max_tokens`, `temperature`, `top_p`, `top_k`, `stop_sequences`, `stream`, `metadata`, `tools`, `tool_choice`, `thinking`, `output_config`, `anthropic_version`) are kept.
Everything else is dropped.
This is more robust than blocklisting individual beta fields, since new Claude Code beta features are automatically stripped without proxy changes.

**Vertex AI protocol details** (learned from testing):

- **Hostname resolution by region**: The standard pattern is `{region}-aiplatform.googleapis.com` (e.g. `us-east5-aiplatform.googleapis.com`), but some meta-regions use special hostnames: `global` → `aiplatform.googleapis.com`, `us` → `aiplatform.us.rep.googleapis.com`, `eu` → `aiplatform.eu.rep.googleapis.com`
- **Model naming**: The Vertex SDK uses `@` for model version suffixes in URL paths (e.g. `claude-haiku-4-5@20251001`), while the standard API uses `-` (e.g. `claude-haiku-4-5-20251001`).
  The global Vertex endpoint only accepts short model aliases without any version suffix — both `@20251001` and `-20251001` forms are rejected.
  The proxy strips both forms.
- **Beta features**: The `anthropic-beta` header is rejected by Vertex rawPredict with "Unexpected value(s) for anthropic-beta header".
  This includes common beta flags like `claude-code-20250219`, `interleaved-thinking-2025-05-14`, `web-search-2025-03-05`, and `prompt-caching-scope-2026-01-05`.
  Features like extended thinking work without any beta flags on Vertex — they're enabled via `anthropic_version`.
  The Vertex SDK moves `anthropic-beta` header values into the body as an `anthropic_beta` array, but even that is rejected ("invalid beta flag").
  The proxy strips the header entirely without adding it to the body.
- **Auth skipping**: `CLAUDE_CODE_SKIP_VERTEX_AUTH=1` causes the Vertex SDK to skip GCP authentication and send no Authorization header of its own: the only headers it adds are `ANTHROPIC_CUSTOM_HEADERS` (checked against Claude Code 2.1.289 with a local listener).
  A login script sets that to `Authorization: Bearer $ISX_VERTEX_ACCESS_TOKEN`, so the request carries the instance's token for the proxy to check (#1108), and the proxy replaces it with a real GCP token.
  A stub `/usr/local/bin/gcloud` is installed inside Vertex containers (by `ClaudeSetup`) that prints `$ISX_VERTEX_ACCESS_TOKEN` (falling back to `ya29.placeholder-for-proxy`) for `auth print-access-token`, for anything that refreshes a token through `gcloud`.
  It was added for Claude Code's credential refresh, which current releases skip under the flag.
- **Base URL override**: `ANTHROPIC_VERTEX_BASE_URL` redirects all Vertex SDK requests to a custom endpoint.
  Setting it to `https://api.anthropic.com/v1` causes the container's Vertex SDK to send requests to `api.anthropic.com`, which resolves to the proxy via dnsmasq.
- **Response format**: Vertex `rawPredict` returns standard Anthropic response format — no response translation is needed.

**Pi coding agent support:** Pi is a provider-agnostic coding agent that always communicates via the standard Anthropic API (`/v1/messages`).
Unlike Claude Code, Pi does not have a Vertex mode — it always sends standard API requests with an `x-api-key` header.
The proxy handles both direct key injection and standard-to-Vertex translation transparently.
No Vertex-specific environment variables are needed inside the container; `ANTHROPIC_API_KEY=sk-ant-placeholder` is the only auth configuration (declared via `PiSetup.envEntries()`).

**WebSocket passthrough:** The proxy also handles WebSocket upgrade requests.
When a client sends an HTTP Upgrade to a proxied domain, the proxy establishes a corresponding upstream WebSocket connection (injecting credentials on the initial handshake), then relays frames bidirectionally.
The client socket is paused until the upstream connection is established to prevent frame drops.
The client's handshake is completed (`ServerWebSocket.accept()`) before any of this, and a refused one goes no further.
Vert.x 4 runs the WebSocket handler before Netty validates the handshake, and only completes it once the handler returns.
So a request Netty refused (no key, no `Upgrade` token in `Connection`, ...) was answered 400 after the credential had been injected and an upstream socket opened for it (#972).
Copying Netty's checks into the handler was rejected: Vert.x's own pre-checks are looser than Netty's (a substring match on `Connection`), so a copy misses refusals and drifts with Netty.
`accept()` is deprecated and gone in Vert.x 5; a regression test covering each refused shape pins the behaviour for whatever replaces it.
Keepalive pings are sent on both legs, and close codes are propagated.
This is used by Codex CLI, which communicates with `api.openai.com` over WebSocket.

**Intercepted domains:** Built-in: `api.anthropic.com`, `registry-1.docker.io`, `auth.docker.io`, `ghcr.io`, `quay.io`, `repo.maven.apache.org`, `repo1.maven.org`, `plugins.gradle.org`, `services.gradle.org`, `registry.npmjs.org`.
Tool-contributed (via YAML `proxy:` entries): `github.com`, `api.github.com`, `raw.githubusercontent.com`, `objects.githubusercontent.com`, `codeload.github.com`, `uploads.github.com`, `api.openai.com`, `bob.ibm.com` (and all `*.bob.ibm.com` subdomains).
User-defined tools can add additional domains.

**HTTPS only:** The proxy intercepts HTTPS traffic, so Git operations must use HTTPS URLs (not SSH).
`gh` defaults to HTTPS automatically; for `git clone`, use `https://github.com/...` instead of `git@github.com:...`.

All other domains (package mirrors, PyPI, etc.) route normally via Incus bridge NAT and are unaffected by the proxy.

**Credential validation**: Branching (CLI and TUI alike, through `BranchFlow`) refuses up front when the new instance would lack a credential its tools need.
Airgapped branches skip the check, and building a template needs none, since only the proxy spends them at runtime.
`CredentialCheck` answers for the accounts the branch will *actually* be stamped with -- the template chain's `accounts:`, the source instance's own pins and any `--account` or TUI account row, merged exactly as `BranchFlow` stamps them -- and against the leaf template recorded on the source.
So a branch of a branch is checked too (#793).
What a tool needs is declared, not listed.
Every tool the template chain names, plus everything they `requires:`, spends its credential namespaces (`ToolSetup.credentialNamespaces(params)`, with params resolved as the build resolves them, so pi names only the one its `provider` uses).
The tools serving those namespaces, and the tool itself (Copilot's borrowed `github.token`), are then judged as the proxy judges them, by `ToolProxyResolver.missingSecrets()`.
An auth entry is served once every key it references resolves against that selection.
A tool is refused only when none of its entries would be, naming the keys they reference.
A tool with alternative auth entries on different domains is usable with any one configured.
A key with no navigable config path is never asked for (`SecretRegistry.isNavigable`), since nobody could set it.
The chain is the one the source was *built* from, as its build source recorded it (`BranchFlow.Inherited.builtFrom`): a template edited since, but not rebuilt, does not make its existing branches ask for a tool they do not have.
Only a source with no build record falls back to the current YAML.
Tools the proxy does not serve (feature-gated off, or project-local) are not checked, as their credentials would not be injected anyway.
Readiness that is more than a key being set is the tool's to state, through `ToolSetup.credentialProblem()`.
So `CredentialCheck` names no tool: `ClaudeSetup` requires the resolved Claude account to be *complete* (a pre-accounts `useVertex: true` with no region still presents as an account, and fails every request).
`PiSetup` applies the same rule for its Anthropic provider and requires a Vertex account for `vertex`/`google`.

**Auth error reporting**: When credential injection fails the proxy records an `authError` and surfaces it on `/health`, which `isx proxy status`, `isx doctor` and the TUI banner all render.
Injection only runs on real container traffic, so that latch alone makes the status wrong in both directions: it reports failures the user has already fixed, and reports nothing at all before the first API call of a session.
The health endpoint therefore verifies the Vertex token itself, on a worker thread, before answering — clearing a stale error, or reporting a broken credential that no request has hit yet.

The checks it declines are what keep this cheap:

- **Vertex not in use** — returns immediately, before any lock or thread dispatch.
  A non-Vertex setup never invokes `gcloud`, and pays nothing.
- **A valid cached token with no standing error** — the steady state.
  Tokens are cached for 50 minutes, so a healthy Vertex install makes roughly one `gcloud` call per token lifetime regardless of poll rate.
- **A standing OAuth error** (hint `isx init`) — only the user can resolve it; running `gcloud` would prove nothing.
- **A check that ran within 10 seconds, or one in flight** — a failing credential caches nothing, so without this every poll from the TUI or `isx doctor` would fork a fresh `gcloud`.

A failure found by the health check is recorded via `recordProbeAuthError`, which never sends a desktop notification: the check runs on every poll, and the notification belongs to the traffic path where a failure actually blocks the user.
The first transition is still logged.
The `gcloud` invocation is bounded at 15 seconds and an empty token is rejected rather than cached — this path is now reachable with no container traffic to reveal a hang or a blank credential.

**PID in `/health`**: the CLI signals the proxy (SIGUSR1: re-read the instance list) after every branch, destroy and `isx account set`.
Finding its PID by the listening port meant `fuser`, which scans the open files of every process on the host -- a measured 90 ms of every branch.
The proxy now reports `pid` in `/health`, and `ProxyService.signalAccountRefresh()` asks for it at signalling time (never a remembered value, so a restarted proxy cannot be signalled by a stale PID), falling back to `fuser` for proxies that predate it.
`isx proxy stop` finds a proxy started by hand the same way (`ProxyService.stopManualProxy`, #155).
`fuser` alone has no `port/tcp` form on macOS and is missing where psmisc is not installed, so it left that proxy running and said "Proxy is not running."
The signal goes to whatever proxy runs on the machine, so unit tests must never send it.
The one seam is in `signalAccountRefresh()` itself, and the test home extensions (`TempHome`, `IsolatedHome`) replace it with a counter for the classes that use them -- so one opt-in covers every caller a test reaches, rather than each caller needing a hook of its own (#871).
Containers reach `/health` too, so the PID goes only to host callers (`MitmProxy.isHostCaller`: loopback, or a source equal to the bridge address the endpoint listens on -- a container's source is its own address, which `security.ipv4_filtering` stops it spoofing).

**Version drift detection**: The proxy health check (run before builds, branches, and shell access) compares the running proxy's version against the CLI version.
If they differ and the proxy runs as a service, the service is restarted, whether or not instances are running.
A proxy run in the foreground only gets a warning with the command to restart it.
This prevents subtle failures from CA certificate or protocol mismatches.
A restart only helps if it changes the running build, and the service starts the *installed* `isx-proxy`, not a binary matching the CLI.
A CLI built from another commit than the installed proxy (every source build, or a partial upgrade) therefore restarted the proxy on every command.
Each restart added ~2.5 s and cut every instance's connection, and the drift was still there afterwards (#798).
`DriftRestartRecord` ends that loop.
Whenever the service starts onto the binary its files were just pointed at (`reinstallIfChanged()`, `upgradeIfNeeded()`, or `install()` of a stopped service), the CLI records its own build together with that binary's path, mtime and size in `~/.local/state/incus-spawn/`.
If version drift persists while the same CLI and binary are in place, `ProxyHealthCheck.assessDrift()` reports that another restart cannot help, and the CLI warns instead of restarting.
Every drift consumer reads that one `DriftReport`.
The CLI build is part of the key even though a restart runs the same binary whatever the CLI, because install.sh's JVM mode rebuilds the jar behind a byte-identical launcher.
A new CLI build is then the only sign a restart would help.
Installing a different proxy or CLI allows one more restart.
The obvious alternative, asking the installed binary for its version, was rejected: `isx-proxy` releases before v0.3.2 have no `--version` and ignore unknown flags, so the probe started a whole second proxy.
It would also have cost a process start on every drifted command.
The record costs one `stat`, plus at most one futile restart per (CLI, proxy) pair.
Its remaining blind spot is a JBang launcher whose jar changes while the CLI does not; the warning still tells the user how to restart by hand.
Config drift is never gated, since a restart re-reads the config.
**Tool proxy config drift** is detected via file stamps.
At startup and on each reload, `ConfigFingerprint.load()` records a `ConfigFingerprint` -- the mtime and size of `config.yaml` and each tool definition `ToolDefLoader` reads from `tools/` -- just *before* reading the config, and then of each tool definition under the `tools/` of every search path that config names.
Those are stamped after the read, the one place their paths are known, but still before any tool definition is read; an edit to the search paths themselves is an edit to `config.yaml`, stamped first.
The proxy's tools are read from that same config's search paths (`new ToolDefLoader(config.getSearchPaths())`), never from a second `config.yaml` read.
Before #890 the loader read `config.yaml` again for them, so an edit could be served without showing as drift.
Tool definitions under a search path were neither fingerprinted nor watched, so the proxy kept their old domains and credential paths until restarted by hand.
`ConfigWatcher` watches the same tool directories (`tools/` and each search path's), following the config loaded last, so an edit there reloads the proxy as one to `config.yaml` does.
Each `/health` request takes a fresh one and reports drift when it differs in any entry; a file added or removed changes the set of entries.
This avoids the cost of re-parsing configuration on every health poll.
It used to compare mtimes against the wall-clock time of the load instead.
A file with a future mtime (`cp -p` from a machine whose clock ran ahead, a clock stepped back, a restored backup) then read as drifted after every restart, so every command restarted the proxy until the clock caught up (#818).
Comparing recorded values involves no clock, and also catches an edit landing in the same timestamp tick as the load.
Capturing before the read means an edit racing the load shows as drift rather than being recorded as already seen; the premise that a restart always clears config drift, which keeps it out of the `DriftRestartRecord` gate, depends on this.
Everything the proxy serves is derived from that one read: startup and `reload()` both build an immutable `ConfigState` from the loaded config (credentials, routing, tool setups and the per-account caches) and publish it whole.
Startup used to leave the per-account part to a lazy read on the first pinned request, which escaped the fingerprint and could land after a reload, putting the old config back (#837).

**CA certificate mismatch**: At branch time, `BranchCommand` compares the template's `ca-fingerprint` metadata against the current CA certificate.
If they differ (e.g. after `isx init` regenerated the CA), a warning is shown suggesting to rebuild the template.
This prevents TLS failures in branches where the container's trusted CA doesn't match the proxy's signing CA.

**Leaf certificate persistence (clock-skew safety)**: Per-domain leaf certs are not minted fresh on every proxy start.
`CertStore` persists them under `~/.config/incus-spawn/certs/` (keyed by domain — `<domain>.crt`/`.key`, wildcards as `_wildcard.<domain>`) and reuses them across restarts, re-minting only when a cert is missing, was signed by a rotated CA, or is within 30 days of expiry.

This fixes an intermittent "certificate is not yet valid" failure.
A cert's `notBefore` is stamped from the **host** clock at mint time, but it is validated against the **container** clock.
These are independent clocks: on macOS the proxy runs on the Mac host (launchd, `KeepAlive=true`) while containers run inside an Incus VM whose clock lags after the Mac sleeps — `--timesync` only re-seeds at boot, not on resume.
`KeepAlive` relaunches the proxy whenever it exits (including right after wake, when the Mac clock has already jumped forward to real time).
Re-minting at that moment produced a `notBefore` in the lagging container's future, failing validation with "certificate is not yet valid".
No clock ever runs backward — both are monotonic — but the gap between the mint clock (host, ahead) and the validating clock (container, behind) can exceed a day.
Reusing a persisted leaf keeps its original `notBefore` (stamped while the clocks were in sync), so the container's lagging-but-monotonic clock always accepts it.
`CertificateAuthority.BACKDATE_MS` (2 days) backdates `notBefore` as a margin for the rare remaining fresh-mint moments (first install, CA rotation, near-expiry renewal).
The underlying clock drift is now corrected by `chronyd` running inside the VM appliance — it steps the guest clock to NTP time within seconds of network recovery after wake (see `appliance/DESIGN.md`, Clock Synchronization).
Cert persistence and backdating remain as defense-in-depth for the brief window before chrony syncs.

Certs are keyed by domain, never by container: a leaf is a function of `(domain, CA)` and is identical for every container that intercepts that domain.
Planned per-container interception (a different intercepted-domain set per container) is a routing/DNS concern — it decides which domains reach the proxy for a given container — and does not change cert identity, so the store stays domain-keyed.
Certs are already resolved per SNI name on demand against this same on-disk store (see below), so that feature needs no change here.

**Certificates are chosen per SNI name, at any depth (#783)**: dnsmasq's `address=/<domain>/` sends *every* name under an intercepted domain to the proxy, however deep, but a wildcard SAN matches exactly one label (RFC 6125).
The proxy used to serve one JKS holding `<domain>` and `*.<domain>` for each intercepted domain.
A deeper name such as `results-receiver.actions.githubusercontent.com` (where `gh run view --log-failed` fetches logs) matched no alias.
Vert.x then fell back to an arbitrary keystore entry.
The container was offered `*.api.openai.com` for a GitHub host, an error that points at the wrong domain entirely.
`InterceptedCertOptions` (proxy module) now implements Vert.x's `KeyCertOptions.keyManagerFactoryMapper`: `certNameFor()` maps the SNI name to the exact domain, or to a wildcard for the name's own parent (`*.actions.githubusercontent.com`), minted through `CertStore` on first use and so persisted like every other leaf.
Vert.x runs this mapping on a worker thread, so the RSA key generation never blocks the event loop.
The `<domain>`/`*.<domain>` certs are still pre-minted at start.
Because the container picks the names, two bounds apply.
`MAX_ON_DEMAND_CERTS` limits on-demand certs *stored on the host*: it is counted from the cert directory at construction, so neither a reload nor a restart grants a fresh batch.
`CertStore` never deletes, and a name already on disk is always served.
`MAX_SERVED_NAMES` limits distinct SNI names answered per server configuration, because Vert.x caches an `SslContext` per answered name and never evicts.
Without it, `r1…rN.githubusercontent.com`, all served by one pre-minted wildcard, would grow the heap without minting anything.
A reload replaces Vert.x's cache, so that count is per instance.

Anything the proxy cannot serve correctly -- a name outside every intercepted domain, a client sending no SNI, a name past the cap -- is refused, never answered with an unrelated certificate.
The refusal is expressed by the mapper returning `null` and the **default** key manager holding no certificate, so the handshake ends at once with `handshake_failure`.
Throwing from the mapper looks equivalent but is not: Netty's SNI handler then leaves the connection hanging until the handshake timeout.
Returning `null` also keeps Vert.x from caching an SSL context per refused name.
Because SNI names become file names in the cert store, `CertStore.get()` accepts only strict hostnames (`CertStore.isHostname`), optionally as a one-label wildcard.
`InterceptedCertOptionsTest` handshakes with a client that trusts only the isx CA and verifies hostnames, at several depths, and checks every shipped intercepted domain (built-ins plus every bundled tool's `proxy:` domains) resolves to a matching cert name at depths 0-3.
So a newly added domain is covered without being listed.

**Vertex AI token refresh**: Vertex AI requests that receive a 401 response are retried once with a fresh GCP access token (the cached token is invalidated).
This handles token expiry during long-running sessions without user intervention.

**Proxy caching**: The proxy caches three types of upstream content to avoid redundant downloads:
- **OCI blobs** — keyed by SHA256 content digest, verified on store
- **Maven/Gradle artifacts and Gradle distributions** — keyed by repository path, verified on store, confirmed with upstream as recently as `artifact-cache:` asks (see below)
- **npm tarballs** — keyed by package name and version, with ETag-based verification against the npm registry packument.
  When the packument ETag is unchanged, cached tarballs are served without re-verification.
  When the ETag changes, per-version shasum is checked — matching shasum updates the marker, mismatched shasum evicts and re-fetches.
  The per-version shasum is looked up asynchronously through `probeClient`, the same client and verification as Maven/Gradle checksums, so a cold install's lookups run concurrently over shared HTTP/2 connections (#960).
  They used to be a raw `SSLSocket` per tarball inside an *ordered* `executeBlocking`.
  In Vert.x 4.5 an ordered task on a request's duplicated context joins its parent context's single queue, which every MITM connection shares.
  Every lookup from every instance ran one after another, so 540 packages took 195s through the proxy against 53s direct.
  So nothing that waits on the network runs in an ordered `executeBlocking`.
  The npm path's disk checks still do, on purpose: ordering keeps a tarball's ETag read behind the packument's ETag write (`relayNpmPackument`), which an unordered read could overtake and serve a tarball on a stale ETag without a shasum check

**Maven/Gradle cache integrity**: the rule is that a cached artifact is served only when a direct fetch would have returned the same bytes when upstream last confirmed it (#555), and that confirmation is recent enough.
How recent is configurable; with both durations at zero, every hit is confirmed before it is served.
Only an unreachable upstream serves an older one.

- *Confirmation tiers* (`ArtifactCacheTiers`, `artifact-cache:` in config.yaml).
  The stored checksum's modification time is when upstream last confirmed the artifact: set when both are committed, and renewed by a match only from an answer that could have evicted (on Central a client's own `.sha1` request matching confirms nothing, since the header outranks it).
  Renewal is best-effort: a checksum file the proxy may not re-stamp (another owner) just ages into being confirmed first.
  Younger than `fresh` (default 2h), a hit is served from disk with no upstream request.
  Younger than `max-stale` (default 7 days), it is served at once and confirmed again in the background: a match renews the confirmation, a change or withdrawal evicts the copy so the *next* request fetches it, and an answer that settles neither (a 403, an unusable sidecar) expires the confirmation, so the next hit gets the confirm-first path's handling.
  Older, it is confirmed before it is served, as below.
  Background confirmations are single-flight per artifact, at most 16 in flight with the rest queued (dropping them would leave copies unconfirmed build after build under a parallel resolver), and confirm nothing during a domain's backoff.
  A domain that can withdraw releases never gets the tiers (`Revalidation.mayWithdraw`: the Plugin Portal), so its every hit is confirmed first.
  `artifact-cache:` is read from the raw YAML value, so a mistyped section (`artifact-cache: 0`) is warned about and ignored instead of failing config.yaml, which would leave the proxy with no credentials.
  The stored checksum goes out as `X-Checksum-SHA1` with bytes the proxy opens by path (Vert.x 4.5 has no `sendFile` from an open handle).
  So just before `sendFile` the path is checked to still be the file the checksum was read with, and a copy replaced meanwhile is confirmed first.
  The window left is the few microseconds between that stat and the open, on the same thread.
  The hit path reads the copy without the store's lock (commits hold it across an fsync), re-reading under it when the artifact changed between the reads.
  Why tiers: a warm build that asks for 1,500 artifacts one after another paid ~20 ms per `HEAD`, ~30 s against ~3.5 s with no confirmation (Maven's default depth-first collector cannot overlap them).
  What they give up: within `fresh`, a republished or withdrawn artifact is served until the window ends, and in the background tier it is served once more before the eviction.
  Central forbids both and Gradle does not withdraw distributions, which offline serving already relies on; the Plugin Portal, which does withdraw, is excluded.
  A confirmation time in the future (a clock stepped back) is not trusted.
  Hits are not logged one by one: at thousands per build, a line each on the event loop cost more than a hit served on trust.
  `ArtifactCacheStats` counts them by kind, and the first hit after a quiet spell schedules one summary line 5 s later, so an idle proxy neither logs nor wakes up for it.
  Evictions, errors and newly cached artifacts are still logged individually.
- *Confirm a hit* (past the tiers).
  `Revalidation` says how, per domain; a domain without an entry is never cached (`VerifiedArtifactStoreTest` fails if a Maven/Gradle domain lacks one).
  Maven Central sends `X-Checksum-SHA1` with every artifact, so a hit costs one `HEAD` (`HEAD_CHECKSUM`), compared with the stored `.sha1`.
  The Gradle Plugin Portal and Gradle distributions send no checksum headers, so a hit fetches the `.sha1`/`.sha256` fresh (`SIDECAR`, following Gradle's redirect to `downloads.gradle.org`).
  A match serves from disk.
  A different checksum, or a 404/410, evicts the artifact, and the request goes upstream.
  So does any answer that confirms nothing (401/403/429).
  A stale or withdrawn copy is therefore never served, whichever client asks, including Gradle, which never requests sidecars.
- *Pass the checksum on.*
  A confirmed Central hit carries the `X-Checksum-SHA1` upstream just sent, and a miss carries upstream's own `X-Checksum-*` headers.
  Maven Resolver's smart checksums then check the bytes against upstream and skip their own `.sha1` request, so for Maven 3.9+/4 the `HEAD` replaces a request rather than adding one.
  A hit served on a trusted confirmation carries the stored checksum instead, and stored sidecars are served for it: within the window the proxy vouches for the checksum as for the bytes.
  Otherwise Maven would fetch the `.sha1` from upstream and pay the round trip the tiers save.
  Outside the window the header is only ever upstream's current value: one made up from the store for an unconfirmed copy would turn the client's independent check into a check against our own cache.
- *Verify on store.*
  A miss is just the `GET`, streamed to the client and a temp file at once, and committed only if it matches a checksum from upstream: the same response's `X-Checksum-SHA1` on `HEAD_CHECKSUM` domains, else the sidecar fetched once the download is done.
  The sidecar is committed with it.
  With no usable checksum the artifact is served but not cached.
  A host `~/.m2` copy is the exception: its checksum is fetched first, and a matching copy is imported instead of downloading, with `Files.copy`, never a hardlink.
  On JDK 25 `Files.copy` clones where the filesystem can (`copy_file_range`, which btrfs and XFS turn into a reflink, verified in the native image too; `clonefile` on APFS), so the copy costs no space there.
  A hardlink shares the inode, so an in-place rewrite of the `~/.m2` file (`cp` onto it, a truncating writer) would change the cache without it noticing.
- *Sidecar requests go upstream* once the artifact's confirmation is past `fresh`/`max-stale` (within them the stored copy is served, see the tiers), and the answer is reconciled with the stored copy the same way.
  This is a second line of defence; confirming the artifact is what provides correctness.
  A checksum we cannot parse says nothing and evicts nothing (Maven accepts the `sha1sum` and BSD/OpenSSL formats, and so does `Sidecar.hex`).
  On `HEAD_CHECKSUM` domains a sidecar never evicts on its own.
  A separately uploaded `.sha1` can be wrong where the server-computed header is right (old Central artifacts), so a disagreement (`Outcome.DISAGREES`) is settled by a `HEAD`, and only by its header (`SidecarAnswer.fromHeader`), never by the `.sha1` a header-less `HEAD` falls back to.
  When the header confirms the artifact, the stored sidecar follows upstream: a changed `.asc` is kept, a withdrawn one dropped.
- *Resume a broken download.*
  A download to any cache (OCI blob, Maven/Gradle artifact, npm tarball) that stalls or breaks off mid-body asks for the rest with `Range`, and streams it on in the same response, so the client never sees the break (`CachingDownload`).
  npm is why: it treats a failed optional dependency as skippable, so a broken platform-package download (`@openai/codex-linux-x64`, 162 MB) left `npm install -g` exiting 0 without the binary (#808, #925).
  A stall is 20s without a byte from upstream (`downloadIdleSeconds`), not counting time it waits on our own backpressure, a client or disk that stopped reading, or on a resume being asked for.
  Vert.x's request idle timeout stops at the response head, so the proxy watches the body itself.
  Everything is fitted into the client's *silence budget*: 110s without a byte from us (`clientSilenceBudgetSeconds`), below the MITM server's 120s idle timeout, which otherwise drops the client silently while the upstream read is still pending (the upstream client's read-idle timeout, 300s, outlasts it; lowering that for every relay would cut slow uploads, which get no bytes back for as long as they send).
  The budget runs from the client's request, so waiting for the response head gets what the lookups before it (an npm shasum, a Maven confirmation) left, a head that never comes becomes a logged 502 the client can retry, each resume (connect, head, backoff after one that could not be made) gets what is left of it since the client's last byte, and the stall check looks again the moment a pause ends (the temp file opening, a drain, a resume's head), since a head or a resume that arrived late can leave less of the budget than one check.
  Upstream always gets a second after we let it go before it counts as stalled, and only a client that drains renews its budget, not a disk.
  When too little is left, the client gets the error, logged, rather than a silent drop.
  A connect timed out that early says nothing about the domain, so it does not start the unreachable backoff below.
  A resume needs a validator for `If-Range`: a strong `ETag`, or `Last-Modified` when there is no `ETag` at all and it is at least a second older than the response's `Date` (servers ignore a weak ETag, RFC 9110 does not let a date stand in for an entity tag, and a date within the second it was sent could match two versions).
  Without one there is no resume, since a file changed meanwhile would be spliced onto the old one's bytes.
  Only a `206` whose `Content-Range` runs from the byte the client has to the end of the same length continues.
  Anything else (a `200` from an upstream that ignores `Range` or whose file changed) ends the response with an error.
  A response that ends before that length is another break.
  A `5xx` or `429` to the Range request is retried like a resume that could not connect: an overloaded upstream is what stalls a download in the first place.
  Three resumes in a row that get no further give up; one that got further starts the count again.
  A gzip-encoded body is never resumed: compressed on the fly, the same file need not give the same bytes twice.
  A download that could never be resumed (gzip, no length, no validator) is not cut at a stall either, since that could only fail a body that may yet carry on.
  Only the client's budget ends it, with a line, where before the MITM server's 120s did so silently.
  An error sent before the first byte drops the headers set for the artifact, so a 502 never carries its `X-Checksum-SHA1`.
  Verification is unchanged: the whole file is checked before it is committed.
  A client that leaves mid-download, or while its head was still awaited, does not stop it.
  It goes on into the cache, as it always has, so a client that gave up and retries gets a hit.
  With nobody waiting the budget no longer applies.
  Plain relays (`relayRequest`, including an npm tarball whose shasum could not be fetched) are held to the same budget without a resume (`RelayWatchdog`, #929): 110s since the client's last byte either way, not counting the time its request body is still arriving or the client holds the response back.
  So an upload is never cut and nothing is ended that the 120s timeout would have let live.
  A head that never comes is a logged 502, a body that stops a logged reset, and the upstream request is let go rather than left for its 300s read-idle timeout.
  A relay cannot resume, so a shasum lookup answered with a `5xx` is asked once more, while over half the client's budget is left, before the tarball falls back to a relay (`fetchNpmVersion`).
  A `429` is not, since asked again at once it would be refused again.
  The npm fallback logs that it relays, so a codex install that fails without a stall or resume line in the proxy log points at it directly.
  A relayed request's body is claimed as the request is routed (`claimBody`), before the proxy waits on anything (#1164).
  Vert.x drops a body that arrives with nothing reading it, and then refuses to read the request at all.
  So a body read only once the upstream connection was ready was lost whenever it beat the connect -- a small POST that found no idle pooled connection, such as npm's audit request after an install, which sends its tarballs with `Connection: close` -- and the relay waited forever with nothing to cut it.
  A request with a body is therefore never served from a cache (all of them are keyed by path alone).
  A relay whose client left before its body was all sent resets its unsent upstream request, which gives the connection back to the pool instead of holding it for good.
- *Offline.*
  Only when upstream cannot be reached (connect/DNS/TLS failure, an exchange that broke on an established connection, or a 5xx or 429, i.e. cases where the client would otherwise get an error) are cached artifacts and stored sidecars served unconfirmed.
  A broken exchange is retried once first, since a pooled keep-alive connection may simply have died while idle; a timeout is not, which would double the wait on a black-holed network.
  An answer we cannot use (an oversized sidecar, a redirect loop, a redirect to anything but http(s)) is a different thing, `SidecarAnswer.UNUSABLE`: upstream answered, so it confirms nothing and the request goes upstream rather than to the cache.
  Redirects are followed over TLS, an `http` Location included, as the proxy always has.
  Only a failure to connect starts the domain's 30s backoff (not a connect timeout the proxy cut short to fit a client's budget), so a black-holed network does not cost a connect timeout per request.
  The backoff short-circuits only requests that have a cached copy to fall back on (a request without one still asks upstream, which it might answer).
  Only a response from the domain ends it.
  A request on a pooled connection is sent without any network I/O first, so getting the connection proves nothing about reachability now, and would let one request end a backoff a real connect failure started.
  It is kept where every upstream connection is made (`requestWithAsyncDns`), so no fetch path can forget to update it.

*Why only these domains.*
With both tiers at zero an online serve does not depend on a repository's publishing rules, so `Revalidation`'s allowlist is not a claim of immutability.
A domain is still vetted before it is cached, for three reasons.
**Offline serves are unconfirmed, and serves within the tiers are not confirmed now**.
So they are only right if the repository does not change or withdraw what it published: Central forbids both, Gradle does not withdraw distributions, and the Plugin Portal can delete a version (an accepted risk while offline).
**The confirmation must be sound for that repository**: a server-computed header describes exactly the bytes the server would send, so `HEAD_CHECKSUM` is sound even for changing content.
A separately uploaded sidecar is not (`mvn deploy` PUTs the jar and its `.sha1` one by one, so a repository can briefly serve a new jar beside the old `.sha1`).
So `SIDECAR` only fits repositories that publish the two together and never replace them.
**Public repositories only**: the cache is shared by every instance and confirmation carries no client credentials, so a private repository's artifact cached for one instance would be served to others, and offline serving would bypass its authorization entirely.
`maven-metadata.xml` stays relayed because confirming it costs the same round trip as fetching it.
SNAPSHOTs stay relayed because no cached domain serves them, and were one to, sidecar confirmation would be unsound and offline serves of content replaced on every deploy rarely right.

`VerifiedArtifactStore` owns the on-disk half and serializes commit, reconcile and evict per artifact with a striped lock, so a store racing an eviction cannot leave a sidecar beside an artifact it does not describe.
A commit is ordered for crashes as well: the download is flushed to disk before it is renamed into place, sidecars of a previous copy are dropped before the rename, and the new checksum is written after it, so an interrupted commit never leaves a checksum beside bytes it does not describe (an artifact without one is hashed before it is trusted).
Checksums are fetched through a Vert.x client of their own (`probeClient`: hostname verification, the proxy's truststore, async DNS, pooled keep-alive connections), npm's version lookups included, never a raw socket.
A separate pool keeps a cache hit's confirmation from queueing behind large downloads.
Since every hit waits on one, that client is tuned for round trips.
It negotiates HTTP/2 through ALPN (Central offers it; others fall back to HTTP/1.1), so a build's concurrent confirmations share a connection rather than a TLS handshake each.
Idle connections stay open for `PROBE_KEEP_ALIVE_SECONDS` (60s), across short pauses between a build's Maven invocations.
Longer buys little (one handshake per longer pause) and widens the window for a connection dropped silently while idle (NAT, suspend, a VPN coming up).
Such a connection is still found.
The client's read-idle timeout (15s) fires only while an exchange is waiting on the connection.
Being shorter than the exchange's own (30s) it closes the dead connection first, so the exchange fails as broken and gets the one retry above, on a new connection.
Without it an HTTP/2 connection would never be evicted: a timed-out exchange resets only its own stream, and every later confirmation would wait out 30s on the dead connection and be served unconfirmed.
Up to four HTTP/2 connections per host (`setHttp2MaxPoolSize`): with one, Vert.x admits a single connect at a time, so a burst to a host that falls back to HTTP/1.1 (a nested isx's own proxy, the benchmark stub) ran one request at a time, and a burst to one that does not answer failed one connect timeout after another.
Connections are pooled per resolved address, so reuse also ends when the DNS cache (60s) re-resolves a host to a different one.
Hashing a large artifact happens outside the lock, re-checked under it against the file's identity, so it does not stall other artifacts in the same stripe.
The benchmark (`bench/run.sh --load=maven`) points Central at a loopback stub through `ISX_BENCH_UPSTREAM`, since a confirmed hit makes a `HEAD` (with the default tiers, the benchmark's warm hits are fresh and make none).
The end-to-end tests use the same hook.
Accepted residual risk: an artifact corrupted on disk while its sidecar is intact is served until the client's own checksum check fails.
Caches written before this (`~/.cache/incus-spawn/maven`, `gradle`) were stored unverified; the roots moved to `maven-verified`/`gradle-verified` and the proxy deletes the old ones on start.

**Buffered I/O**: The proxy uses 64KB `BufferedInputStream`/`BufferedOutputStream` on both client and upstream connections for throughput.
SSE and chunked streaming responses are flushed after each line/chunk to avoid buffering delays.

**OAuth token support:** Users with a Claude Pro/Max subscription (no API key) can authenticate via `claude setup-token`, which generates a long-lived (~1 year) OAuth token.
The proxy injects `Authorization: Bearer <token>` into requests to `api.anthropic.com` and strips the container's placeholder `x-api-key` header.
Containers are configured identically to direct API key mode (with `ANTHROPIC_API_KEY=sk-ant-placeholder`).
Unlike Vertex AI tokens, OAuth tokens cannot be refreshed automatically — when a 401 is received, the proxy logs an actionable error directing the user to re-run `isx init`.

**Tool-contributed proxy definitions:** Tools declare proxy entries via a `proxy:` block — either in YAML (`ProxyDef` with `configuration:` and `auth:`) or programmatically via `ToolSetup.proxy()` for CDI tools.
A `ProxyDef` has shared `configuration:` (a map of `ConfigEntry` entries with `config-path`, `value`, `secret`, `type`, and `description`) and a list of `auth:` entries (`AuthDef`), each with a `domains:` list and an auth type (`basic`, `bearer`, `header`, or `anthropic`).
Auth fields use `${configKey}` template references resolved against the shared configuration map — literals like `"x-access-token"` are passed through unchanged.
Configuration resolution: `value` (hardcoded literal) > `config-path` (dot-path into `config.yaml`, e.g. `"github.token"`).
User-defined tools use arbitrary config paths (e.g. `"myTool.apiKey"`) stored via `SpawnConfig.setConfigByPath()` and round-tripped through `@JsonAnySetter`/`@JsonAnyGetter`.
Built-in proxy tools are CDI tools declaring `proxy()` directly: `ClaudeSetup` (1 auth entry: `api.anthropic.com`, `type: anthropic`), `GhSetup` (2 auth entries: `github.com` Basic with literal username, `*.github.com`+`*.githubusercontent.com` Bearer), `BobSetup` (1 auth entry for `bob.ibm.com` and regional domains, Header type), `CodexSetup` (1 auth entry: `api.openai.com` Bearer, feature-gated behind `openai`), and `CopilotSetup` (1 auth entry: `*.githubcopilot.com` plus three exact per-tier domains — `api.individual.githubcopilot.com`, `api.business.githubcopilot.com`, `api.enterprise.githubcopilot.com` — declared exactly because each has two labels ahead of `githubcopilot.com`, which a one-label wildcard cert cannot cover; since #783 the proxy mints a cert for any depth on demand, so the exact entries now only make those certs ready at start; its `token` config entry points at `github.token`, reusing the `gh` tool's PAT instead of prompting separately, so it works even when `gh` isn't installed).
`type: anthropic` means the domain is handled by MitmProxy's hardcoded auth logic (three-way routing: Vertex, OAuth, API key) — the generic tool proxy injection path does not apply.
Anthropic entries use relaxed configuration resolution (at least one non-blank credential, matching `ClaudeConfig.hasAuth()` semantics) and are excluded from MitmProxy's domain maps in `buildRouting()`; a change to their credentials is still config drift, since it changes `config.yaml`.
`ToolProxyResolver` (in `common`) handles configuration resolution; `findUnresolved()` returns configuration entries that could not be resolved (excluding `type: anthropic` entries), and `ProxyMain` warns at startup when unresolved entries are found.
`MitmProxy` stores resolved generic entries in exact-match and wildcard-suffix maps for O(1)/O(n) domain lookup.
Domain collisions between tools are warned at startup (exact-domain conflicts and wildcard-suffix overlaps); tool domains that shadow built-in intercepted domains (registry, Maven, Gradle, npm) are warned during validation since built-in routing takes precedence.
A `proxy:` entry also declares `placeholders:` -- each an `env` variable the tool reads its credential from and the `prefix` its value must start with -- which every start fills with the instance's proof token for the entry's namespace (see "Proof tokens" below).
A YAML tool that exports a static placeholder through `env:` declares the same variable here (`typesafe.yaml` does).

**Dynamic credential setup:** `isx init` builds its credential menu dynamically from tool setups via `ToolDefLoader.allToolSetups()`.
Tools with proxy configuration are discovered, filtered by `ToolSetup.hasOwnCredentials()` (a tool whose `proxy()` configuration borrows another tool's credential, like `CopilotSetup` reusing `github.token`, overrides this to `false` so it isn't offered as a separately "configurable" entry), and sorted (known tools first: claude, gh, bob, codex; then alphabetically).
Each entry shows `ToolSetup.description()` and a `[configured]` tag.
Known tools dispatch to specialized setup methods with validation (e.g., `setupClaudeAuth` with env-var detection and API verification).
Unknown tools use a generic prompt (`setupGenericToolCredentials`) that iterates `proxy.getConfiguration()` directly, respects `ConfigEntry.isSecret()` and `ConfigEntry.isConfirm()` (for y/n prompts like license acceptance), and saves via `SpawnConfig.setConfigByPath()` using the entry's `config-path`.
User-defined tool YAMLs with proxy entries appear in the menu automatically.

**Claude accounts:** `claude.accounts` is a named map of credentials, so one host can hold several Claude identities at once (a personal Pro/Max subscription and a work Vertex project, say) instead of swapping whole `config.yaml` files by hand.
Each entry declares what it *is* — `type: api-key`, `oauth` or `vertex` — never what it is used for, because usage is expected to grow.
`claude.default` names the account used when nothing narrower applies.
Accounts are named in `ClaudeAccount`/`ClaudeAccountType` (nested in `SpawnConfig`); `type` may be omitted when the fields make it unambiguous (`effectiveType()`), and an entry missing its credential is ignored rather than half-applied (`isComplete()`).

Selection is by capability, via `ClaudeConfig.accountFor(Predicate)`: the default account when it can do the job, otherwise the first account in file order that can.
`account()` (the unfiltered form) is what instances get today.
`ClaudeConfig`'s familiar accessors (`isOauthMode()`, `getApiKey()`, `isUseVertex()`, …) are **derived from the resolved account**, not from the flat fields, so `ClaudeSetup`, `PiSetup`, `ProxyCredentials` and `DoctorCommand` needed no changes.
Both `ClaudeConfig` and `ClaudeAccount` bind Jackson **by field** (`@JsonAutoDetect` with getters `NONE`).
The derived getters share names with the legacy fields.
An `@JsonIgnore` on one accessor of a split property silently disables the whole property — which is how a flat `config.yaml` stops deserializing.
Claude stays typed because the type drives real behaviour — which variables a build bakes, whether an account can answer `isx ask` — but only as a *reader*: which accounts exist, which one is the default and whether a pin is valid are answered by `AccountResolver`, the same as for every other namespace (see "One accounts model" below).

**Per-instance selection.**
Which account an instance uses is decided in three layers, each explicit, resolved lowest-first: the namespace's `default` in `config.yaml`, an `accounts:` map on the template (merged *per key* down the inheritance chain, so a child re-points one namespace and keeps the parent's choice for the rest), and a per-instance override from `isx branch --account <ns>=<account>` or `isx account set`.
Only the last two are written onto the instance, as `user.incus-spawn.account.<namespace>`, so an instance that says nothing keeps following the global default as it changes.

Selection is always written `<namespace>=<account>`.
A bare account name is deliberately not accepted even though it would read better.
It would have to fan out over whichever namespaces happened to hold an account of that name, so adding a credential namespace later would silently widen the meaning of a command someone had already written down.

**Accounts are generic, not Claude-specific.**
`ToolDef.ProxyDef.fullConfigPath()` already built `<config-namespace>.<config-path>` (`github.token`); `accountConfigPath()` builds `<ns>.accounts.<account>.<path>` beside it, and `ToolProxyResolver` prefers it, falling back to the flat path when the account omits that key.
That one preference is the whole mechanism: every namespace gains named accounts at once, including a tool defined purely in YAML that isx has never heard of, with no per-tool code and no per-tool flag.
`AccountResolver` does the namespace-agnostic resolution off the serialized config tree.

**One accounts model (#773).**
Claude got accounts first and grew its own rules: it presented a pre-accounts flat file as an account named `default`, and always wrote the accounts layout.
Every other namespace kept a flat credential flat until a second account was added, and did not present it as an account at all.
With identical-looking files, `isx account list` showed Claude and nothing else, and `--account claude=default` was accepted while `--account github=default` was refused.
The difference had leaked into generic code as `if (namespace is claude)` branches in `AccountSelection` and a namespace removal in `ProxyCredentials`.
Claude's rules won, for every namespace:

- *A flat credential is the account `default`.*
  `AccountResolver` presents a namespace with no `accounts:` block but with a credential as one account named `default`, with no fields of its own.
  `value()` already falls back from an account's key to the flat one, so that account reads the flat credential.
  Nothing is synthesized into the file, and reading a file written by any earlier isx is unaffected.
  Listing, pin validation and the proxy all ask the same resolver, so a pin the CLI accepts is one the proxy serves.
- *Writing always produces the accounts layout.*
  A single credential is saved as `accounts.default` with `default: default`; `NamespaceAccounts.materialize` moves a flat one there on the first write that has something to save, and never before, so an abandoned `isx init` leaves the file as it found it.
  `isx init` therefore has one vocabulary, `AccountTarget(name, replaceOthers)`, for every namespace, where GitHub used to pass `""` to mean "the flat layout".
  Downgrading is not a goal: an older isx reads a namespace written this way as unconfigured.

What the resolver needs to know about a namespace is an `AccountShape`, declared rather than coded.
`ToolSetup.accountShape()` derives it from the tool's proxy definition: its `secret: true` entries are the credential (so a leftover `github.email`, or the `apiKey: ""` older releases wrote for every namespace, is not an account), every entry the user supplies is held per account, and `confirm` entries — Bob's licence consent — stay at the namespace level, where every account inherits them.
Two built-in tools override it.
`GhSetup` names `email` as per-account, because the commit email belongs to an identity but is not something the proxy injects.
`ClaudeConfig.ACCOUNT_SHAPE` recognises the flat Vertex setup (`useVertex: true` is not a secret) and judges each account by its type.
That judgement is the shape's one hook, `problem(account, namespace)`.
An unusable account is skipped when picking a default, and pinning it is refused as "configured but incomplete: <why>" rather than "not configured", because an incomplete account is easy to overlook in `isx init` (#742).
The flat credential is never judged — the shape already decided it was one, and a flat `useVertex: true` without a region must still reach `ProxyMain` as a Vertex account so it can report exactly that misconfiguration.
The tree-taking resolver methods take the shape as a parameter, so the proxy resolves against the tool setups it already loaded and never scans for tool YAMLs on the event loop.
The `SpawnConfig`-taking conveniences look it up among the built-in Java tools first and scan only for a YAML tool's namespace.

The typed classes follow.
`BobConfig.hasAuth()` and `OpenaiConfig.hasAuth()` used to read the flat field, which is empty once a key lives under `accounts:`.
They now resolve through `NamespaceConfig.defaultValue()`.
The branch-time credential check asks for Bob and OpenAI the way it already asked for GitHub, against the instance's account (see "Credential validation").
Bob and OpenAI got named accounts in the same change, through the generic `isx init` flow rather than code of their own.

The credential namespaces that *do* have a Java class (`GitHubConfig`, `BobConfig`, `OpenaiConfig`) extend `SpawnConfig.NamespaceConfig`, which preserves keys the class does not declare.
Without that an `accounts:` block would deserialize to nothing and then be **deleted** by the next save — the same credential-destroying shape as the `putAccount` bug found in review.
Namespaces with no Java class at all were already safe: they land in `SpawnConfig.extras`.

**How the proxy tells callers apart.**
One shared proxy on `DEFAULT_MITM_PORT` still serves every instance, but it now identifies the caller by source address.
Every branch is given a static IP (`InstanceLifecycle.configureBranch`, recorded as `user.incus-spawn.static-ip`) and the iptables REDIRECT that sends `:443` to the proxy preserves the source address.
So one `/1.0/instances?recursion=1` call maps every address to an instance and its pinned accounts at once.
`InstanceRegistry` holds that snapshot; `lookup()` never blocks, and refreshes run through `executeBlocking` following the single-flight pattern already used for DNS and Vertex tokens.
`isx branch` and `isx account set` signal the proxy (SIGHUP) so a change lands immediately rather than at the next poll.
`RequestContext` carries domain, caller, credentials and routing down the request path in place of the bare domain, and per-selection credentials are cached keyed by the selection itself and cleared on every config reload.

An address that is *not* a known instance — host-side traffic — gets the configured defaults rather than an error, because the proxy serves that too.
An instance that pins nothing gets the same.

**Builds are served their template's accounts (#903).**
A build container used to get a DHCP address, so the proxy could not tell it from host traffic and served every namespace's default, whatever the template's `accounts:` chose.
`repos:` were cloned and `prime` ran with the user's default token -- for a project-local template, handing that identity to code the cloned repository controls -- and `GhSetup` baked the default account's name next to the template account's email, stamped as the template account so nothing ever repaired it.
Before its first start, a build container now gets what a branch gets: an address from `StaticIpAllocator` with `security.ipv4_filtering`, and the template's pins (origin `template:<name>`), in one write (`InstanceLifecycle.assignBuildAddress`), then a SIGUSR1 so the proxy knows it before anything inside asks (`lifecycle/BuildAccounts.start`).
Only a build that pins something does: one pinning nothing -- neither its template nor what it was copied with -- is served the defaults either way, so it keeps plain DHCP and skips the round trips.
The `account-identity` stamps a copy carries from its parent are cleared in the same write, all of them and not only the pinned namespaces'.
They describe the parent's build.
The proxy refuses an account -- pinned *or* default -- whose auth mode does not match one, so it would refuse this build the account its own template chose, or a default changed since the parent was built.
They are read first: after tool setup, `settleIdentities` compares them with this build's accounts to re-derive a parent's identity that no longer matches (#281), and reading them back from the container then would find nothing.
When the build is done it stamps what it baked and puts the parent's back for every other namespace (`BuildAccounts.identityStamps`).
A namespace whose credential is no longer configured derives nothing, yet the parent's `.gitconfig` or Claude environment is still in the rootfs.
No `.network` file is pushed: the guest keeps DHCP, and Incus's DHCP server hands out the NIC's `ipv4.address`.
It will not while another MAC holds a lease on that address, and one usually does when a chain is built.
The allocator hands out the lowest free address, so a child's build is offered the one its parent's build has just given back, whose lease the parent's MAC keeps.
Incus clears only IPv6 leases when a NIC's `ipv4.address` changes, and a force-stopped guest never releases its own.
dnsmasq would then give the child a dynamic address that filtering drops, and the build would fail minutes later as a DNS error.
So a build's claim also skips every address in the bridge's leases (`IncusClient.networkLeaseAddresses`, read before the allocation lock).
Once the guest is up `InstanceLifecycle.requireBuildAddress` fails the build, saying why, if it holds another address on the bridge's subnet anyway -- only that subnet, since a parent's docker0 or podman bridge also shows up in the instance's state.
Incus lists leases per project, so an instance of another Incus project on the same bridge stays invisible to the allocator, as its static reservations always were; the check turns that collision into a clear failure.
The build prints the accounts it is served.
Once stopped, the template gives the address back (`releaseBuildAddress`), returning the bridge NIC it was claimed on -- and no other -- to the profile's, since a template makes no requests and its copies never keep an address.
A release that fails only warns, since the template is built and an address it keeps is freed when it is rebuilt or removed.
A failed build keeps both address and pins as `<template>-failed-build`, so starting it for inspection serves it its template's accounts rather than the defaults.
Deleting it frees the address, as the template's next failed build, `isx clean` or a destroy does.
The allocator reads claimed addresses from the instance listing each time, so a deleted instance's address is free with nothing to undo.
The proxy is another matter: until it re-reads, it would serve the deleted build's accounts to the next holder of the address.
So `isx clean`, `isx clean pool` and `isx doctor`'s remediation signal it after deleting failed builds (`CleanCommand.deleteFailedBuilds`/`deleteFailedBuild`), as `isx destroy` does.
A build or branch signals it before its first start anyway, which leaves only a manual `incus delete` to the proxy's refresh backstop.

**This only works because the identity cannot be forged.**
`security.ipv4_filtering` is set on the NIC alongside the pinned `ipv4.address`.
Without it, root inside a container could re-address its interface as a neighbour and spend that neighbour's subscription — per-instance credentials would *look* like isolation while providing none, which is worse than not having them.
Every stopped start of an existing instance turns it back on where it is off (`InstanceLifecycle.prepareHostDevicesForStart`, which the `isx shell`/`run` prep, the TUI and VM agent recovery all go through), both for instances branched before this and for ones the fallback below left without it.
So an instance regains protection once its host can enforce it, rather than staying open until it is re-branched.
The check reads the instance the other pre-start repairs already read, so it costs no request when filtering is already on.
A host whose firewall backend cannot apply it warns rather than failing the branch, and the warning says plainly what is not enforced.
That warning has to come from the *start* path, not the config path.
Incus accepts `security.ipv4_filtering` on a host that cannot enforce it and only fails when the instance starts, so `InstanceLifecycle.startInstance` recognises that specific failure, drops the setting and retries.
Otherwise the hardening would take a working host and leave it unable to start anything it branched.
The macOS appliance was such a host until its kernel gained the nft `bridge` family (#905): Incus's nftables driver installs the per-NIC rules there, so every Mac branch fell back to no filtering.
`CONFIG_NF_TABLES_BRIDGE` is now built in, and the appliance smoke test fails CI if it is dropped (`appliance/DESIGN.md`).

**A per-start secret, checked together with the address (#934).**
A host-side service that authorises control actions -- grants, leases, a controller reached through the proxy -- needs more than the address, which is only as good as the filter on the day.
So every isx start gives the instance a new random secret (`InstanceSecret`: 256 bits, hex), and `InstanceRegistry.identify()` accepts a caller only when its source address and the secret it presents (`X-Isx-Instance-Secret`) belong to the same instance; neither alone passes.
It is an extra layer, never an alternative: since the secret is only valid from its bound address, a leaked one is useless, so it needs no rotation protocol beyond "every start makes a new one".
Client certificates per instance were the alternative -- a key in the box and a CA to run, for nothing over address plus secret given the filter.

*Where it lives.*
The host keeps only its SHA-256, as `user.incus-spawn.instance-secret-sha256`, which the registry reads from the listing it already makes, so the proxy pays nothing.
Never the secret itself: a guest can read its own `user.*` keys through `/dev/incus`.
Keeping the hash in instance config also means a destroyed instance forgets it with nothing to clean up.
The hash sits beside the accounts in the registry's snapshot rather than inside `InstanceAccounts`, which `MitmProxy` caches by value: a key that changed on every restart would grow those caches.
In the guest it is `/run/isx/instance-secret`, root-owned and readable by the instance user's group (0440), and the login profile exports `ISX_INSTANCE_SECRET_FILE` naming it.
The path is exported, not the value, so the secret is not in the environment of every process, nor in an agent's `env` dump.
`/run` is a tmpfs.
Any reboot isx did not perform (`incus restart`, a reboot inside the guest, autostart after a host reboot) leaves the box without a secret, so it fails closed rather than keeping the previous start's -- until the next `isx shell` or `isx run` notices and gives it one (#1024, below).

*Where it is made, and what it costs.*
The start path is latency-sensitive, so delivery adds no round trip of its own.
A branch's secret is generated in `BranchFlow.create`.
Its hash rides on the copy request (no extra write, and no moment in which a branch of a branch carries its source's hash).
The secret goes into the post-start setup script beside the SSH keys.
A stopped start goes through `InstanceLifecycle.startForUse` -- `isx shell`/`isx run`, the TUI's shell, and the CA repairs before them -- and the TUI's restart through `restartForUse`.
`rotateInstanceSecret` records the new hash before the start or restart, while no boot contends for the Incus API (one PATCH, the only new request, pinned in `InstanceLifecycleRequestBudgetTest`).
`waitForReady`'s probe writes the secret as it checks the guest answers.
`VmAgentRecovery` records the hash in the write that already stamps the boot it restarted into.
The proxy is not signalled: on Linux that costs every start a bridge read, a `/health` call and a fork, and has the proxy list every instance while this one boots.
Nothing needs it sooner -- the previous secret went with the guest's `/run` -- and `identify()` documents that a refusal right after a start is worth one refresh and a retry, throttled by `wantsMissRefresh()` as a lookup miss is.
A guest can present wrong secrets at will, and each refresh lists every instance.
Template builds get none, so no image carries one.
The write is best-effort: a box left without its secret is refused by whatever checks it, which is the safe way for this to fail, and must not fail the start it rides on.
It waits (up to 3 s) for the tmpfs on `/run`, and skips the write without one.
A container answers exec as soon as its init runs.
A secret written before systemd mounts `/run` would land on the rootfs, hidden under the mount and carried into copies.
Every `IncusClient.copy` clears the hash beside the address, so no copy holds its source's; a branch sets its own in that same request.
The secret travels in the exec's environment (`InstanceSecret.guestEnv`), never its arguments.
Any user in the guest can read a process's `/proc/<pid>/cmdline` for as long as it runs -- and the branch setup script can run for many seconds -- while `/proc/<pid>/environ` is readable only by its own uid.
The script moves it into an unexported variable at once, so nothing it starts inherits it.
Nor does it sit in `RuntimeConfig`, which `BranchFlow.create` hands back to its callers: `setupRuntime` takes it as an argument.

*A reboot isx did not do (#1024).*
`InstanceLifecycle.ensureReady`, the step before every `isx shell` and `isx run` (and the TUI's shell), gives a running instance that lost its secret a new one: the hash recorded, then `GUEST_SCRIPT` run in one exec (`giveSecretToThisBoot`, best-effort and warning through `say`, so the shell still opens; a delivery that fails unsets the boot stamp it was recorded with, so the next shell tries again; since `GUEST_SCRIPT` never fails, the same exec runs `GUEST_CHECK` after it, and a secret the guest did not keep counts as a failed delivery and is reported, rather than silently given anew on every shell).
Telling that it is needed costs no request of its own, but how differs by machine type, because the host sees a reboot differently.
Every container reboot -- `incus restart`, `reboot` in the guest, autostart -- is a start.
Incus sets the instance's `last_used_at` on every start.
The instance `ensureReady` already holds carries it, so it is compared with `user.incus-spawn.instance-secret-boot`, the boot the secret was made for (with the mark that it carried proof tokens, #1106; see "Proof tokens").
Since a boot's `last_used_at` exists only once it starts, isx's own starts (`startForUse`, `restartForUse`, a branch's first start) record it after the guest answers, after the boot and not during it, where `setupRuntime` documents that API calls contend.
That takes a read of the instance, which the starts followed by a CA check (`isx shell` and `isx run`, the TUI's shell, a branch that is not airgapped) were about to make anyway, so `startForUse` and `recordSecretBoot` return it and `CertificateAuthority.fixContainerCaIfNeeded(incus, name, instance)` uses it: there the stamp's write is the one request added (an airgapped start and the TUI's restart have no CA check, and pay the read too) (start plus CA check 5 -> 6, branch 18 -> 19 against `FakeIncusDaemon`, pinned in `InstanceLifecycleRequestBudgetTest`).
Two isx processes acting on one container within its start can still leave the guest's secret and the recorded hash out of step for that boot (fails closed; #1065).
Without the record the next shell would take that boot for a stranger's and replace its secret.
A VM is never stamped: QEMU reboots a guest in place when it can, with no new start, no new `last_used_at` and no new QEMU pid (`AGENT_RESTART_BOOT`'s identity), so nothing the host reads changes.
Only the guest can tell, and `ensureReady` already asks a running VM's agent whether it answers on every shell, so that probe now also runs `InstanceSecret.GUEST_CHECK`, which prints a marker when `/run` is a tmpfs with no secret in it.
Asking the guest the same way for containers was the alternative: one exec on every shell into a running container, where the comparison costs nothing.
Guessing from the pre-start state was rejected: a stamp written before a start cannot tell the boot it was made for from a later one.

**Proof tokens: the placeholder proves the start (#1106, plan in #1100).**
The proxy attributes a request to an instance by its source address.
Everything nested in an instance -- a nested container NAT'd behind it, rootless Podman under pasta, an ssh tunnel -- reaches the proxy from that same address, so it was served the instance's credentials too (#1100).
What those have not got, unless someone hands it to them, is the instance's login environment.
So the placeholder each tool already sends in place of its credential becomes a proof of that environment: `ProofToken.derive(secret, namespace)`, an HMAC-SHA256 keyed with this start's secret over the namespace name (128 bits of it, hex), carried as the tool's own prefix plus `isx_` plus the digest -- `gho_isx_…`, `sk-ant-isx_…`, `sk-ant-oat01-isx_…` -- so the tool still accepts its shape and anyone debugging can tell it is isx's.
It changes on every start, differs per namespace (one namespace's proof opens no other's), and reveals neither the secret, which is what the MCP bridge accepts and so must stay out of `env` dumps, nor the recorded hash.
Checking the proof before injecting is #1107; until then the proxy replaces whatever it finds, so only the values change.

*Declared, not listed.*
Which variables carry a namespace's placeholder, with which prefix, is declared on the tool: `placeholders:` (`env`, `prefix`) on its `proxy:` entry, in the one namespace the tool's credential belongs to (`ToolSetup.credentialNamespaces()`, so Copilot's `COPILOT_GITHUB_TOKEN` is `github`'s), surfaced as `ToolSetup.placeholders()`.
Pi, which spends Claude's and OpenAI's credentials with no proxy entry of its own, reads `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` as Claude Code and Codex do -- their declarations cover it -- and overrides `placeholders()` only for `ANTHROPIC_OAUTH_TOKEN`, its own name for Claude's OAuth token.
Claude declares one variable per auth mode: `ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN`, and for Vertex `ISX_VERTEX_ACCESS_TOKEN` (`ya29.isx_…`), which #1108's login script puts in `ANTHROPIC_CUSTOM_HEADERS` and its `gcloud` stub prints: under `CLAUDE_CODE_SKIP_VERTEX_AUTH` Claude Code sends no credential header of its own.
The set an instance is given is that of the tools the proxy serves (`ToolProxyResolver.proxyToolSetups`: feature-gated tools only when enabled, never a project-local tool's proxy entry), merged by `ProofToken.declaredBy`, which drops a declaration that could not be written into a shell safely (`ToolDefValidator` reports it as an error) and a variable two tools declare differently.
Guessing which tool the image's variable belongs to could hand it another namespace's proof, and dropping leaves the static placeholder, which proves nothing.
The start, the proxy's check (#1107) and the in-guest `isx doctor` (#1109) read the same declaration.

*Delivered with the secret, in the same exec.*
`InstanceSecret.guestEnv(secret, placeholders)` hands the guest the secret and the proof exports together, both in the exec's environment.
`GUEST_SCRIPT` writes the exports to `/run/isx/proof-tokens` beside the secret, root-owned and readable by the instance user's group (0440).
Other users of the guest get neither.
It removes the previous proofs first, so none outlive the secret they came from.
`/etc/profile.d/isx-instance-secret.sh`, which the same script writes and which sorts after `isx-env.sh`, sources that file.
Each line replaces a variable only while it holds the build's own placeholder -- every static one starts with its prefix and `placeholder` (`gho_placeholder`, `ya29.placeholder-for-proxy`; `ToolDefValidator` requires that shape of a YAML tool) -- or an earlier proof: `case "${GH_TOKEN-}" in gho_placeholder*|gho_isx_*) export GH_TOKEN='gho_isx_…' ;; esac`.
So the build still decides which variables an instance has (an OAuth-configured Claude never grows an `ANTHROPIC_API_KEY`), and a value somebody else put in the variable -- a template's own `env:` pointing `GH_TOKEN` at another service, a `set-if-unset` that kept the user's -- stays theirs.
Computing the proofs on the host, rather than deriving them in the guest, needs no HMAC tool in the image and gives most instances built before this their proofs at the next start or shell, with no rebuild.

*Except where the tool cannot take a new value on every start.*
A placeholder may carry a guard, a shell condition its line checks in the guest (`Placeholder.onlyWhen`; code only, never from YAML, since it runs in every login).
Claude's `ANTHROPIC_API_KEY` has one: Claude Code asks interactively, defaulting to no, about a key whose last 20 characters `~/.claude.json` has not approved, and an image built before #1108's login script (`/etc/profile.d/isx-zz-claude-auth.sh`, which approves each login's key) approves only `sk-ant-placeholder`.
So the key gets a proof only where that script exists, or where there is no `~/.claude.json` to ask (pi without Claude Code).
An API-key Claude image built before #1108 keeps the static key, and works as before, until it is rebuilt.
Once #1107 checks proofs that image's Claude requests carry none, so #1107 has to ask for the rebuild where it refuses them.
The other namespaces need no guard: gh, Copilot, Bob, pi and Typesafe read the variable on every call, Claude's OAuth token has no approval list, Codex reads `auth.json` (which #1108's script rewrites, and which keeps its build-time key in an older image, where `OPENAI_API_KEY` changes nothing for Codex), and only an image built after #1108 exports `ISX_VERTEX_ACCESS_TOKEN` at all.
Every path that delivers a secret delivers its proofs: a branch's setup script, `startForUse`, `restartForUse`, `VmAgentRecovery`, and `ensureReady`'s new secret after a reboot isx did not do.
Nothing is added to the start's Incus requests (`InstanceLifecycleRequestBudgetTest` is unchanged).
The declarations come from tool definitions on disk (about 160 ms cold on the JVM, a few warm).
So the start paths, the branch's included, read them in the background while the instance is copied or starts (`ProofToken.declaredInBackground`).
A failed read gives no proofs and a warning -- the static placeholders, which prove nothing.
`giveSecretToThisBoot` has only one PATCH to overlap the read with, so on that path -- a reboot isx did not do, or an instance from before proofs, once -- the read is close to synchronous.
They are read as the proxy reads them, never from the current directory's `.incus-spawn/tools/` (`ToolDefLoader.withoutProjectTools`).
The tool setups `BranchFlow.preflight` loads do include it.
A cloned repository that ships its own `gh.yaml` would otherwise leave `GH_TOKEN` without a proof whenever isx runs from it -- untrusted project input deciding what a start delivers (#765).
A YAML tool's placeholder must be one of its own `env:` entries (`YamlToolSetup.placeholders()` drops any other with a warning, `ToolDefValidator` reports it as an error).
A start only fills what the build exported, so another name is a typo, or a variable such as `PATH` or `LD_PRELOAD` a proof would clobber in every login shell.
Proofs live only in `/run`, like the secret, so no image or copy carries one, and a branch gets its own with its own secret before its first start.
*Reaching instances already running.*
An instance an older isx started holds its secret but no proofs, and so does one whose proofs write failed after its secret's.
A VM's agent probe, which runs on every shell, asks for the proofs file as well as the secret (`GUEST_CHECK`; an empty file is a delivery in which no tool declared any), so the next shell delivers both.
A container has no probe: `ensureReady` compares its boot with the boot stamp, which now records that the delivery carried proofs (`InstanceLifecycle.secretBootStamp`: `<last_used_at> proofs`).
A stamp an older isx wrote is the bare boot, so it no longer matches.
The next shell gives the container a new secret with its proofs -- once, from the instance it already read, at no request of its own on every later shell.
A build keeps the static placeholder in `isx-env.sh`, and a long-lived shell keeps the proofs of the start it was opened in until it is replaced (#1109 reports that).

*Keyed with the secret, not its hash.*
The host records only the secret's SHA-256, which a guest can read from `/dev/incus`, so a proof keyed with the hash would be forgeable by any user in the guest.
Keyed with the secret, nothing the host holds lets anyone derive one.
The proxy therefore cannot recompute a proof from what it has today: #1107 decides between recording a hash of each namespace's proof at the start (option 1) and re-keying with the hash (option 2, which gives that property up).

**Fail closed.**
An instance pinned to an account that has since been renamed or deleted gets an error naming it — never a quiet fall back to the default.
In the case this feature exists for (#351, a subcontractor whose subscription depends on the client), the default belongs to someone else, so falling back is the specific harm to avoid.
`isx doctor` reports instances in that state, without an auto-fix: only the user knows whether the account was renamed or the instance should follow the default.

**Seeing and undoing a selection.**
An instance records only its pins, so "which account is this instance spending?" cannot be read off it: an unpinned namespace follows whatever the default is *now*.
`isx account show` answers it through `AccountUsage`, which resolves every namespace with the same `AccountResolver` the proxy serves from and says in a sentence where each account comes from -- never a bare "pinned" or "default", which leave the reader to guess what would change it.

**Who chose a pin is recorded, not inferred.**
A pin always lives on the instance, whoever chose it: `isx branch` copies the template's `accounts:` onto the branch exactly as it writes an `--account`.
Inferring the chooser from whether the pin matches the template was tried first and is wrong in both directions.
An explicit choice of the template's account reads as the template's, and a template edited since makes its own pin read as an override.
So every pin is written with `user.incus-spawn.account-origin.<ns>` (`AccountOrigin`): `template:<name>` (the build stamps it; branches copy it unchanged), `explicit` (`--account`, `isx account set`, the TUI), or `copied:<instance>` for an explicit choice made on the instance a branch was copied from -- the instance where it was chosen, not every hop since.
`AccountSelection.stampUpdates` writes and clears pin and origin together, so no path can set one without the other.
Pins written before origins existed read as unknown and are reported as such.
The one inference made is at branch time, where an unrecorded pin on the source that equals its template's choice is recorded as the template's, since that is how every earlier template build stamped it.
The template is also compared as it reads *now*, which answers what no record can: a template whose `accounts:` was edited after the instance was branched.
A branch keeps what it was branched with, deliberately -- silently re-pointing running work because a YAML file changed is the wrong default for credentials -- so `show` reports the difference and prints the `isx account set` that would follow it, never suggesting an account that no longer exists.
For a template instance the advice is a rebuild instead, since its pins are what its build stamped.
Explaining a pin needs the description of the account it names, which is `ToolSetup.describeAccount()`: Claude's type, GitHub's commit email, nothing for a tool that declares nothing, never the credential.
`isx account list` shows the same descriptions with the instances pinned to each account, and pins naming an account that is gone.

Pinning the account that happens to be the default is not the same as following the default, and looks identical until the default changes.
`isx account set` says so when it happens, and `isx account unset <instance> <ns>` removes the pin (the key is unset, not blanked) so the instance follows the default again.
`unset` runs the same `incompatibilityReason` check as `set`, with a null account meaning "the default", because the default may be a Claude auth mode the instance was not built for.

**In the TUI.**
`a` on an instance -- in the list or its details -- opens the same choice as a form: per namespace, "default (<account>)" first, then each account.
The refusal check runs while the dialog is open, so a Claude cross-mode swap is explained where it was attempted.
The change itself runs in the background through `InstanceLifecycle.changeAccounts`, the path `isx account set/unset` also take.
So the two front ends cannot drift the way the TUI's branch flow did (#800).
The F3 instance details carry the same "Credential accounts" section `isx account show` prints.

**Choosing accounts when branching in the TUI.**
The branch dialog offers a dropdown per credential the template's tools use (`ToolSetup.credentialNamespaces()`, which derives them from proxy entries -- borrowed ones included, as Copilot's `github.token` -- and which `PiSetup` overrides, since Pi spends Claude's or OpenAI's credential without declaring one) and per credential the inheritance already mentions, but only where there is a choice: two or more usable accounts, or an inherited pin that no longer resolves.
A credential with a single account gets no row.
The first entry is always *inherit*: exactly what `isx branch` without `--account` does, computed by the same `BranchFlow.inheritedAccounts`, and labelled with where it comes from ("as tpl-acme chooses", "global default, follows it").
Everything below it is an explicit pin, recorded as `explicit` like `--account` -- the inherited account included, because pinning the account that happens to be the default is a different choice from following the default.
The pin stays when the default moves, and the entry says so.
Offering it twice under two different meanings is what keeps "let it be" and "pin this" distinguishable without a second control; the closed row shows the account with its source in grey when inherited, and highlighted "pinned" when not.
An account the source was not built for (a different Claude auth mode, `AccountSelection.requiredRebuild`) is listed, greyed, with the reason, rather than hidden, so nobody wonders where their account went -- and cannot be chosen.
Tamboui has no dropdown, only the inline `< >` select, so `BranchAccountChoices` draws its own overlay; Space opens it, since Space toggles every other field and Enter confirms the branch.

**Changing the global default.**
An unpinned instance follows the default as it changes -- deliberately, since switching accounts later is what makes per-instance accounts useful, and pinning everything at branch time would take that away.
The cost is that a one-line edit moves every unpinned instance's principal at once, so the change is made visible and made safe rather than prevented.
*Visible*: `isx init`'s "change the default" (`InitCommand.changeDefaultAccount`) lists the instances that follow it (`InstanceRegistry.accountStates`, which, unlike `accountsByInstance`, includes unpinned instances) and offers switch -- the default, since that is what following means -- keep, or cancel.
Keep pins them to the account they use now as an explicit choice, before the default moves, so none is ever served the new one; end of input cancels, never switches.
Removing the default account names its followers the same way.
For a hand edit nothing can ask, so the proxy's `reload()` logs which instances a changed default moved (`logDefaultChanges`), and `isx account list` shows who follows each default.
*Safe*: the two things a default change could break are handled as a pin change is.
GitHub's baked git identity is re-derived for running followers straight away (`refreshIdentities`, the same reconcile `isx account set` runs; stopped ones catch up in `InstancePrep`).
A Claude auth mode cannot be re-derived, and `isx account set` refuses such a move while the user chooses.
But a default change moves instances without asking, so the proxy checks instead.
`AccountSelection.servingMismatches` compares the account an instance would be served (its pin, or the default it follows) with its `account-identity` stamp, which the registry now reads, and for a tool that cannot rebake it fails the request closed with a message naming an account the instance was built for -- the same rule as a pin to an account that is gone, never a mismatched credential.
The refusal is scoped to that credential's domains (`namespacesByDomain`/`namespacesForDomain`, from the tools' own proxy declarations, `*.suffix` patterns included), so the instance's GitHub traffic keeps working while Claude is refused.
Results are cached per registry record inside the proxy's `ConfigState`, which each reload replaces whole.
So a request that read the old config and finishes after the reload can only write its stale answer into a cache nothing reads any more.
Only branches count as followers (`accountStates` skips templates and failed builds): a template makes no requests, and pinning one on "keep" would pin every future branch of it.
`isx doctor` and `isx account show` report the same state.
If #866 makes Claude's auth modes interchangeable, `bakedAccountIdentity` returns "" for Claude and all of this goes quiet by itself.

**Accounts that instances still use.**
Removing or renaming an account breaks every instance pinned to it -- fail closed means their next request errors -- and every template whose `accounts:` names it.
`isx init` therefore names those instances and templates before a removal (including "replace all", which removes every account but one) and asks, defaulting to no.
Rename is offered for the same reason: the alternative, remove and re-add, orphans every pin.
`NamespaceAccounts.rename` keeps the account's position in the file (with no usable default, the first usable account in file order serves) via `SpawnConfig.renameConfigKey`.
`AccountSelection.renameInInstances` re-points the pins in one list request plus one PATCH per affected instance -- and, for a tool whose baked identity names the account (GitHub; `ToolSetup.renameBakedIdentity()`), the identity stamp too, so a rename is not mistaken for a change of identity.
GitHub's fingerprint carries over unchanged: it describes the same token, and recomputing it would hide a token replaced before the rename.
Template YAML is the user's file and is reported, never rewritten.
The instance and template lookups are overridable seams on `InitCommand`, so `AccountMenuTest` covers these flows without Incus.

**What a build derives from an account, and what happens when it changes.**
Credentials never enter a container, so re-pointing an instance between two accounts that the image cannot tell apart takes effect on its next request with nothing inside restarting.
Two tools *can* tell them apart, and each does so differently.
`ClaudeSetup.envEntries()` writes a different set of variables for each auth mode into `/etc/profile.d/isx-env.sh`, so what it bakes is the **mode** — two accounts of one mode remain interchangeable.
`GhSetup` derives `user.name` and `user.email` from whoever the token belongs to, so what it bakes is the **account** — any change is a change of identity.

`ToolSetup.bakedAccountIdentity()` returns whichever of those applies, or `""` for a namespace whose credential is pure header substitution.
The build stamps it as `user.incus-spawn.account-identity.<namespace>`.
When a re-point makes it stale, the tool is asked to bring the instance in line.
`GhSetup.canRebakeForAccount()` is true, and `rebakeForAccount` clears the git identity and asks the API again — which resolves *through the proxy*, so it answers for the new account without this code knowing which one that is.
`InstanceLifecycle.reconcileAccountIdentities` does this, and only stamps back on success.

GitHub's stamp is the account name plus a fingerprint of its token and configured email (`<account>#<12 hex of SHA-256>`), not the name alone.
The name alone missed the most ordinary change of all (#281).
Replacing an account's token with another user's keeps the name, so nothing was stale.
Every instance -- and every child template, which inherits its parent's `.gitconfig` and whose gh setup skips an identity already present -- went on committing as the previous user while pushing as the new one.
The fingerprint is truncated and one-way, so the stamp never carries the credential.
A stamp from a build that recorded only the name cannot say whether its token has changed since, so it is re-derived once.
Child templates are covered by `BuildAccounts.settleIdentities` (from `BuildCommand`), which re-derives an inherited identity that no longer matches after tool setup (before it, a child adding gh to a parent without it would get its identity written first, and gh's setup, finding a `.gitconfig`, would skip its git defaults).
Otherwise the stamp written at the end of the build would claim the current identity over the parent's `.gitconfig` and hide it from the branch-time reconcile too.
Since every build stamps every namespace, instances without gh carry a GitHub stamp as well; `GhSetup.rebakeForAccount` does nothing where gh is not installed.
It runs from every point a pin can change or first take effect: `isx branch --account` after the instance starts, `isx account set` while it is running, and `InstancePrep` on the next `isx shell`/`isx run`, alongside the other `fix*` reconcilers.
Reconciling only in `InstancePrep` was not enough.
The token swaps on the next request, but the identity is baked, so an instance driven over `incus exec`, SSH or an IDE would push as one account and commit as another until someone happened to open a shell.
A tool that cannot re-derive — Claude, whose variables a running agent has already read — has the swap refused at selection time instead, while the user is still choosing.

**An identity that was never baked is reconciled too.** gh's setup derives the git identity only when an account can supply one.
A template built while no GitHub token was configured -- an `isx init` whose GitHub step was skipped -- gets gh's `.gitconfig` defaults and no `[user]`, so every commit in every branch was unattributed.
It used to happen silently and nothing repaired it: with no account, `bakedAccountIdentity` is `""`, no stamp was written, and an absent stamp reads as "nothing baked, nothing to reconcile".
Worse, stamps were computed from config.yaml, not from the guest: a child built once a token existed, on such a parent, was stamped with an identity its `.gitconfig` never got, and the reconcile trusted it.

So the stamp is now decided by the guest.
After tool setup, `BuildAccounts.settleIdentities` (called from `BuildCommand`) asks each tool in the template's chain that owns a namespace and can re-derive (`AccountSelection.rederivableNamespaces`: gh itself, not Copilot, which borrows the GitHub credential but derives nothing; never Claude, where a stamp the account does not match would refuse the account; only namespaces `namespaceSetups` knows, so a gated-out one is never marked) whether its identity is missing (`ToolSetup.lacksBakedIdentity`, one exec for gh).
Missing, with an account to derive from, it is re-derived (`BuildAccounts.toRederive`, which shares `AccountSelection.needsRederive` with the branch-time reconcile, so the two cannot drift) -- whether the parent was stamped `<none>`, unstamped by an older isx, or stamped with an identity it never got.
A failure fails the build, as for a fresh template.
Missing with no account, the build warns (`ToolSetup.unbakedIdentityWarning`, whichever layer installed gh; it names the account when the template uses one that has no token, since "no token is configured" would be untrue while the default has one) and stamps `Metadata.ACCOUNT_IDENTITY_NONE` (`<none>`) over whatever the parent claimed.
A parent that has an identity but no stamp -- built before stamps existed -- keeps it, so a GitHub outage cannot fail a child build that never needed the API.
A token-less account bakes `""`, not its name, so it is never stale against the marker.

The marker differs from every account's identity, so once a token is configured it is an ordinary stale identity.
The branch-time reconcile re-derives it and stamps the real one.
While there is still no token the namespace bakes nothing and is left alone rather than failing on every start.
A real value rather than an absent key, because absence is what an image without gh looks like, and telling the two apart at branch time would cost a guest exec on every branch.

Every template this isx builds is also stamped `Metadata.ACCOUNT_IDENTITY_VERIFIED`, which every branch inherits: its stamps came from the guest and are trusted, and its reconcile costs one instance read and no exec (`InstanceLifecycleRequestBudgetTest`).
An instance without it comes from a template an older isx built, so its stamps -- or their absence -- may lie.
`AccountSelection.identityReconcile` lists its re-derivable namespaces with a configured account as `unverified`.
`InstanceLifecycle.reconcileAccountIdentities` asks the guest once, re-derives what is missing and sets the marker: one exec and one write, once per such instance, and nothing at all while no account is configured.
One that has its identity is stamped with the account's, so a later change of account or token is still reconciled; marked without a stamp, it would be skipped for good.
The marker is written by any reconcile of an unmarked instance that had something to do, so one whose stamp was merely stale is marked once re-derived.
Only a built-in tool can re-derive (it takes Java code a tool YAML cannot supply).
So the plan is decided against those (`AccountSelection.rederivableSetups`) and the tool definitions are read from disk only when it finds something, to drop a namespace the proxy does not serve.
An instance with nothing to do -- verified and current, `<none>` while there is still no token, or an older one while no account is configured, which is never marked and so would pay on every use -- reads nothing from disk.
That repairs templates already built without an identity, and their branches, without a rebuild.

The guest is asked before waiting for the instance's address, which only re-deriving needs, so an older instance that has its identity is marked without that wait.
The marker goes on the instance, not its template, so until an older template is rebuilt every new branch of it pays the exec and the write on its first use.
Marking the template from a branch's answer was rejected.
The template is stopped, so it cannot be asked.
A branch's `.gitconfig` may have changed since the copy, so a marker written from it would be trusted by every later branch.
An airgapped instance is never reconciled: it has no proxy to re-derive through, and it never gets the address the re-derive waits for, so every `isx shell` in one would wait 30 s and warn.
`BranchFlow` already skipped it; `AccountSelection.identityReconcile` now returns nothing for it, which covers `InstancePrep`, `isx account set` and init's refresh alike.
`InstancePrep` and `BranchFlow` hand over the read they already made (for `ensureReady`, and the prefetch before the start), so on the shell and branch paths the reconcile itself costs no request.

What the guest check does not catch: it asks whether an identity is *missing*, never whose it is.
A parent built before stamps existed, holding account A's identity and no stamp, yields a child built for account B that is stamped B and verified while its `.gitconfig` still holds A's.
The build finds an identity, and with no stamp to compare there is nothing stale.
Before the marker that stamp was equally trusted -- an unstamped parent was never re-derived -- but the marker makes it permanent.
The same holds for an older instance with an identity and no stamp, which the reconcile stamps with its account's identity.
Rebuilding the parent fixes it.

That asymmetry is the point.
Refusing is the fallback for what cannot be fixed, not the general rule: GitHub accounts are freely swappable on a built instance precisely because the thing it bakes can be recomputed.
Moving git identity out of template-build time is also what closes the older complaint that every branch of a template shares one GitHub identity (#281).

The deeper fix would be to stop telling the container which mode it is in at all.
`handleApiRequestWithBody` already branches on the request *path*, not on how the container was built, and `translateToVertex()` already converts a standard `/v1/messages` call into a Vertex `rawPredict`.
So dropping `CLAUDE_CODE_USE_VERTEX` and always baking the standard placeholder would make all three Claude types environmentally identical and every swap live.
The open risks are Vertex model-ID mapping and the non-messages endpoints that currently fall through to `api.anthropic.com`; both want measuring against a real Vertex project rather than arguing about.

**AI help credentials (`isx ask` / `?` in the TUI):** `AiHelpClient.targets()` lists the accounts satisfying `ClaudeAccount::servesDirectApi` (the default account first), then `openai`; the first is the one used by default.
An `oauth` account never satisfies it.
A Claude Pro/Max token is only valid for Claude Code itself.
The Messages API rejects it unless `system` is a block array whose *first* block is Claude Code's own identity string — rejecting it with an opaque HTTP 429 `rate_limit_error` whose message is the literal text `Error`, so the failure reads as a generic API error rather than an auth hint.
Satisfying that check would mean isx presenting itself as Claude Code to spend the user's subscription, so a Pro/Max account simply keeps serving instances while an `api-key` or `vertex` account answers here.
This is the one place isx calls a model API on its own behalf; everywhere else (`ProxyCredentials`, `ClaudeSetup`, `PiSetup`) credentials are only forwarded into instances.
Because providers answer errors with varying usefulness, `describeError()` reports the HTTP status, the error `type` and the `request_id` alongside the message.
`isx ask` always uses that first one.
But the TUI's AI Help dialog makes the account explicit, offering every target as a selector -- a fixed line when there is only one -- because which credentials are billed should be visible rather than implied.
`subscriptionAccounts()` names the Pro/Max accounts so the dialog can show them as unavailable with the reason, instead of leaving a subscriber to wonder why their account is missing.
The optional template and tool definitions are presented as an *attachment* with their measured size.
The word itself says they leave the machine with the question, which matters because they include the user's own YAML files.
`HelpContext.definitions()` is built once per opening of the dialog, so the size shown is exactly what gets sent.
**Prompt caching:** every question carries the whole README and DESIGN.md (~205 KB, the bulk of the cost; kept because answers are markedly less complete without the design rationale).
So `HelpContext.systemBlocks()` returns the prompt as two blocks -- the fixed documentation, then the attached definitions -- and `AiHelpClient.cachedSystem()` puts a 5-minute `cache_control` breakpoint on each for Anthropic and Vertex.
A follow-up question within five minutes then reads the documentation from the cache at ~0.1x the input price (the first question pays 1.25x to write it; OpenAI caches the same stable prefix on its own).
The attachment has its own breakpoint so ticking it still reuses the cached documentation, and its content is sorted so it is byte-identical between questions.
Anything that varies per request must never enter the first block: a single changed byte turns every question back into a full-price write, and nothing fails -- the bill just rises.
The AI Help answer view shows the provider-reported token split (`51.2k tokens in (51.2k from cache) · 312 out`) as the visible check that caching still works.

**Configuration**: `~/.config/incus-spawn/config.yaml` (owner-only permissions, `chmod 600`).
CA key and certificate at `~/.config/incus-spawn/ca.key` and `~/.config/incus-spawn/ca.crt`.
Vertex AI users must have `gcloud` installed on the host and `gcloud auth login` completed — the proxy and `isx init` both shell out to `gcloud auth print-access-token`, which reads the gcloud user credential, not application-default credentials.

### Host Resources

Template images can declare host files and directories to share with containers via the `host-resources` YAML key.
Three modes control how the resource is made available:

**Readonly** (default): a read-only Incus disk device bind mount.
The container can read the host file/directory but cannot modify it.
Simple and safe for config files like `~/.gitconfig`.

**Overlay**: the host directory is attached as a read-only lower layer, with an ephemeral writable upper layer inside the container, combined via Linux overlayfs.
The container sees a normal read-write directory, but writes go to the container-local upper layer — the host is fully protected.
This is the right mode for caches (Maven, OCI) where tools expect to write but you don't need writes to persist back to the host.

**Copy**: the file or directory is copied into the container at build time and becomes part of the template.
Supports local paths and URLs.
No runtime dependency on the host.

#### Overlay internals

For a host-resource with `mode: overlay` targeting `/home/agentuser/.m2/repository`:

1. **Build time**: the host directory is attached as a read-only Incus disk device at `/var/lib/incus-spawn/overlays/home/agentuser/.m2/repository/lower`, before the build instance starts (see "Devices are attached before start" below).
   Once it is up, the container creates `upper` and `work` siblings, then runs `mount -t overlay` to present the merged view at the target path.
   A systemd service (`incus-spawn-overlays.service`) is installed and enabled to re-apply the overlay mount on boot.

2. **After build**: the overlay is unmounted and the disk device is removed from the stopped template.
   The upper and work directories remain as container-local files.
   The full host-resource configuration is stored as JSON in `user.incus-spawn.host-resources` metadata.

3. **At branch time**: `BranchCommand` reads the stored metadata and re-attaches the disk device to the stopped instance before starting it.
   On boot, the systemd service re-mounts the overlay.
   The upper layer — now containing build artifacts — was copied via CoW when the instance was branched, so each instance has its own independent writable layer.

4. **On reboot**: the systemd service fires on every boot and re-mounts overlays.
   This works even if the container is started directly via `incus start` rather than through `isx`.
   The unit is ordered `After=incus-agent.service`: in a VM the lower layers are virtiofs shares, which incus-agent mounts before it signals ready (`Type=notify`).
   Without that ordering the service could run first and overlay an empty lower directory, hiding the host content until the next reboot.
   Containers have no such unit, so the ordering is a no-op there.
   Templates built before this ordering existed pick it up on rebuild.

5. **Derived builds**: a template built from a parent with overlays boots with the parent's service, so the overlays are already mounted when `applyForBuild` gets to them.
   The build-time mount checks the target with `findmnt` and leaves an existing overlay in place.
   Stacking a second one would leave one behind when `removeBuildDevices` unmounts once.

The overlay directory structure mirrors the container path directly under `/var/lib/incus-spawn/overlays/`, so the layout is self-documenting:

```
/var/lib/incus-spawn/overlays/home/agentuser/.m2/repository/
  ├── lower/    ← read-only disk device mount (host directory)
  ├── upper/    ← container-local writable layer (follows CoW branching)
  └── work/     ← overlayfs internal bookkeeping
```

Incus disk device names are derived from the container path for readability (e.g. `hr-home-agentuser--m2-repository`).
They are removed from stopped templates to avoid host-path dependencies and re-attached at branch time.

#### Devices are attached before start

Every disk device a build or branch attaches from the host is added while the instance is still **stopped**: the `readonly` mounts, the `overlay` lower layers and the DNF cache volume (`HostResourceSetup.attachBuildDevices` and `BuildCommand.attachDnfCache`, both in the create→start window of `buildFromScratch` and `buildFromParent`), the host resources re-attached at branch time (`integrateWithHost`, already before `prefetchAndStart`), and the `--inbox` directory (`InstanceLifecycle.attachInbox`).
Only the in-guest work waits for start: `copy` pushes, the `mount -t overlay` over the attached lower layer, and `installOverlayService`, all in `HostResourceSetup.applyForBuild` (#828).
The reasons:

- **VMs can hot-plug only 8 devices.**
  Incus's QEMU driver gives each device present at start its own PCIe root port, then allocates exactly 8 spare ports for hot-plugging.
  Measured on Incus 6.23: a VM created with 40 directory disks boots with all 40 mounted and all 8 hotplug slots still free.
  So a boot-time device costs nothing from that pool, while each hot-plug into a running VM takes a slot.
  Repo references (#826) are hot-plugged mid-build, because each one is detached as soon as its clone finishes.
  Each worker attaches its own just before its clone, under a slot budget (see "Parallel cloning"), so more references than slots take turns rather than lose.
  Host resources and the DNF cache used to be hot-plugged too, which pushed a template with 8 repo references past the limit.
  Attached before start, they leave all 8 slots to repo references.
  The inbox moved for the same reason: it used to leave a running instance with 7.
- **Nothing has to wait for a VM mount.**
  A hot-plugged virtiofs share is mounted by incus-agent asynchronously, so the build used to poll `mountpoint -q` for up to 15 seconds per overlay and for the DNF cache, with a degraded fallback for each timeout.
  Boot-time shares are different: incus-agent mounts everything in its `agent-mounts.json` synchronously, *before* it starts the server every `exec` goes through (`cmd/incus-agent/main_agent.go`).
  Once a build can run anything in the guest, the mounts are there.
  Both waits, and their timeout branches, are gone.
  The agent still only *logs* a share it fails to mount, so the overlay mount checks `mountpoint -q` on its lower layer in the same exec, without waiting, and warns instead of silently overlaying an empty directory.
  The DNF cache needs no such check: `cleanCaches` already refuses to clean through a live mount, and an unmounted cache only makes the build slower.
- **A missing source is skipped, not attached.**
  Incus refuses to start an instance whose disk `source` is missing (`Missing source path`), so `attachBuildDevices` attaches only sources that exist.
  `applyForBuild` then warns about the rest once the instance is up.
  That is the same warn-and-skip as "Missing sources" below, and the reason `prepareHostDevicesForStart` exists for later starts.
  That pre-start repair reads the instance once and fixes every device isx added: host resources whose source is gone (`removeStaleDevices`), the zmx socket directory, and the `--inbox` device (`removeStaleInbox`, #854).
  The inbox has no entry in `user.incus-spawn.host-resources`, so it needs its own check.
  A deleted inbox directory is removed from the instance with a warning that says how to add it back, since the user asked for that mount.
  The check maps the device source back to a host path (`hostPathOfDeviceSource`, the inverse of the macOS `/host` translation), and skips a macOS source outside the shared home, which the host cannot check.
  The TUI starts an instance while it still owns the terminal, where a stderr warning would be drawn over and lost, so it passes `prepareHostDevicesForStart` its warning log as the sink, the way the static-IP repair already does.
  The log announces them on the status line when the shell returns to the TUI, and keeps them for the `w` dialog.
  A plain `incus start` still fails on such a device; isx cannot intercept it.
  The zmx device is the exception.
  Its source sits on the `XDG_RUNTIME_DIR` tmpfs, so every reboot would leave it missing, and it is added with `required=false`.
  A start that bypasses isx (`incus start`, `boot.autostart`) skips it, and the guest has no host-visible zmx sockets until isx itself starts the stopped instance and repairs the device first (#1044).
  An instance already running, such as one `boot.autostart` brought up after a reboot, keeps running without the mount until it is stopped and started through isx.
  The pre-start repair also re-adds a device from before that change, which costs no request when the device is already optional.
- **The attach is quiet.**
  It runs inside the "Launching…"/"Deriving…" progress line, so the per-resource "Mounted …" notes and warnings come from `applyForBuild` after start, and a DNF cache failure is returned by `attachDnfCache` and printed once the step is done.
- **The agent's home may already exist.**
  A mount under `/home/agentuser` makes Incus (or incus-agent) create the directory, as root, before a from-scratch build on an image without `agentuser` gets to `useradd -m`.
  `useradd` then succeeds but silently skips `/etc/skel`, and a `chown -R` of the home fails on the read-only mount.
  So user creation runs `BuildCommand.AGENT_HOME_OWNERSHIP`.
  It copies skel with `cp -an` (no clobbering) and ignores the copy's exit status, because GNU coreutils 9.2 to 9.4 exit 1 whenever `-n` leaves a file alone, which after a normal `useradd -m` is every file (#981).
  Only the copy is best-effort: the ownership and mode steps after it still fail the build.
  It then chowns everything except mount points (which belong to the host), and sets the home to the `0700` that `useradd -m` would have given it (a directory created by a mount is `0755`).
  On a fresh home this does exactly what `useradd -m` plus `chown -R` did.
- **Host resources are not best-effort; repo references are.**
  A repo reference has a correct fallback, a network clone that is slower but gives the same result, so #826 degrades to it when slots run out, saying so on the repo's progress line.
  A host-resource mount *is* what the template declared.
  Skipping one would hand the user an instance missing a directory, and they would find out later in confusing ways.
  So a failed host-resource attach fails loudly (`deviceAdd` throws).
  The design keeps it off the hotplug pool so that this does not happen.
  Any future attach against a *running* instance must keep failing loudly on slot exhaustion rather than warn and skip.

#### Mounts may not target system directories

Attaching before start means a from-scratch build has its host resources mounted from first boot, before `dnf upgrade`, user creation and every boot-time service.
A read-only mount over a path the system writes to would break those steps, where it used to be added only after them.
So `HostResourceSetup.requireAllowedMountTarget` rejects a `readonly` or `overlay` target of `/` or anything under `/etc`, `/usr`, `/bin`, `/sbin`, `/lib`, `/lib64`, `/boot`, `/var`, `/run`, `/tmp`, `/proc`, `/sys` or `/dev` (`FORBIDDEN_MOUNT_ROOTS`).
The package-managed trees are there because dnf writes them.
`/var`, `/run` and `/tmp` are there because services write them at boot, and the virtual filesystems because a mount there never makes sense.
The target is checked after normalizing, so `/opt/../etc` does not slip through.
The rules:

- **`copy` is not limited.**
  It writes files rather than mounting, so a template may still place `/etc/containers/storage.conf` that way.
- **Checked on the effective list.**
  `collectEffective` enforces it after merging the chain, so a child that overrides a parent's `/etc` mount with `copy` builds fine.
  The build preflight reports it before anything is created, and `TemplateValidator` shows the same error while the file is being edited.
- **Branching from an older template fails.**
  A template built before the rule can still carry such a mount in `user.incus-spawn.host-resources`.
  `requireBranchableTemplate` refuses to branch from it before `incus copy`, so no half-made branch is left behind, and the error says to fix and rebuild the template.
  `applyForInstance` checks again before attaching anything, as a backstop.
- **Existing instances still start.**
  The start path (`prepareHostDevicesForStart`) does not check: such an instance has been working with that mount, and failing its start would be a surprise with no rebuild on that path.
- **An absolute source without `path:` is also its target.**
  A resource that relies on that default and lives under one of these roots (a project checked out under `/tmp`, say) needs an explicit `path:`.

#### VM behavior

File-level host resources (individual files rather than directories) automatically fall back to `copy` mode on VMs, since Incus disk devices only support directory mounts for virtual machines.
The fallback is logged: "VM: falling back to copy mode for file ...".

#### Missing sources

If a host path doesn't exist at build or branch time, the entry is skipped with a warning.
The build/branch proceeds without it.

#### Inheritance

Host resources compose additively across the parent chain, with override-by-container-path.
If a parent declares `~/.gitconfig` as `readonly` and a child declares `~/.gitconfig` as `copy`, the child's mode wins.
This follows the same last-write-wins pattern as package deduplication.

#### Project-local templates are confined to their project

A project-local template (`.incus-spawn/images/`) is whatever the cloned repository shipped, and the same repository controls the template's `prime` commands and tools, which have network access.
If it could name any host path, running `isx build` in a cloned directory would be enough to copy `~/.ssh` into a template that every branch inherits, bypassing the credential isolation isx exists for (#765).
So `ImageDef.loadFromDirectory` stamps project-local definitions with their `projectRoot` (never read from YAML), and `HostResourceSetup.collectEffective()` confines each of their non-URL sources to that directory.
Trusted layers (user, search paths, built-in) are unaffected, and so are a project-local template's trusted ancestors.

- **Checked on real paths.**
  The source is resolved against the project root, then through its symlinks.
  For a path that does not exist yet, that means its longest existing prefix, and dangling links are followed to their targets.
  So `~`, absolute paths, `..`, and symlinks all get the same treatment.
  For `copy` of a directory the tree is walked too, because the host-side push follows file symlinks.
  Mounts need no walk: symlinks inside a mount resolve inside the container.
- **Rewritten to the checked path.**
  The effective resource carries the absolute real source, an explicit container path, and `confined-to: <root>`.
  A relative source would otherwise be re-resolved against the CWD of a later `isx branch`, so `source: .ssh` checked as `<project>/.ssh` would become `~/.ssh` when branching from home.
  `collectEffective` rebuilds every entry, so a `confined-to` written in YAML is discarded.
- **Re-checked at every use.**
  The project is a working tree, and a later `git pull` can turn a checked directory into a symlink.
  `verifyConfined()` runs again in `attachBuildDevices` and `applyForBuild`, `applyForInstance` (branch), and `removeStaleDevices` (every start).
  There a violation removes the device and warns instead of failing the start.
- **Enforced before the build starts.**
  `BuildCommand.buildSingleImage` calls `collectEffective` before creating anything, so a rejected template leaves no half-built container.
  The `isx templates new/edit --project` validator reports the same error while the file is still open.
- **Covers `file://` base images too.**
  `image_url`/`vm_image_url` accept `file://` (the incus-spawn-images local-testing workflow), so the build preflight applies the same `HostResourceSetup.projectEscape()` check to a project-local template's resolved base image URL.
- **No GUI by default.**
  A branch's GUI passthrough mounts the host's whole `XDG_RUNTIME_DIR` (user bus, agent and podman sockets) and hands over its GPU, so a project-local `gui: true` -- the definition's own, or any the source was built from (`BuildSource.usedProjectLocal()`) -- does not turn it on by default.
  `BranchFlow.defaultsFor()` leaves it off with a note, and `--gui` still turns it on (#869).
- **Survives rebuilds from metadata.**
  `BuildSource` records `projectRoots` alongside `sources`, so a template rebuilt out of scope from `build-source` stays confined.
  For metadata written before the field existed, the root is inferred from a `…/.incus-spawn/images/*.yaml` source.

#### Project-local templates never touch host checkouts

A project-local template's `repos:` get no host reference mount, and `HostRepoRefresh.collectAllRepos()` leaves them out of the host-side fetch/clone.
Otherwise a cloned repository could list the URL of a repo you have checked out and get your working tree mounted into a build it controls.
With `auto-clone-repos: always` it could also name a `file:///` URL and get any local repository cloned into your host-paths first.
Their repos are cloned from the network inside the container, which is slower but reaches nothing of the host's.
Trusted ancestors of a project-local template keep their references.

#### Project-local templates cannot swap shared base images

Local image aliases (`fedora-44-base`) are shared by every template on the host.
Before this, a project-local template could declare `image: fedora-44-base` with its own `image_url` and the genuine `image_tag`.
The build replaced your image with the attacker's rootfs, and every later trusted build reported it "up to date" and ran on it.
So every import is stamped with the `incus-spawn.project` image property: the importing project's root, or empty for a trusted definition.
The stamp is written after import, so a value shipped in the tarball's own metadata never survives, and it is read back before the alias is created, because `setImageProperty` fails silently and an unstamped project import would pass as trusted.
The rules:

- **A project-local template may only replace an image its own project imported.**
  Anything else fails the build with instructions to use its own image name.
- **A trusted template with an `image_url` re-imports a project-owned image even when the tag matches.**
- **`requireImageTrustedFor()` runs just before launch.**
  A build may only start from a local image stamped empty or with its own project.
  This covers trusted templates that name an alias without an `image_url` and so never reach the import path.
  Images imported before the stamp existed read as trusted; the check cannot tell whether one of them was already swapped.
- **Every provenance lookup fails closed.**
  They use `imageAliasTargetOrThrow()`/`imagePropertyOrThrow()`, where only an explicit 404 or a missing property means "absent".
  The lenient `imageAliasTarget()`/`getImageProperty()` return null on any failure, and a transient Incus error would otherwise read as "imported by a trusted definition".

#### Host-side downloads reach only remote hosts

Every `DownloadCache.download()` runs on the host on behalf of a definition, and the result goes into a container.
This covers YAML tool downloads, Java tool setups, `copy` host-resources with a URL source, and appliance images.
So it applies to all definitions, trusted or not:

- **Only `http(s)`.**
  `file://` would copy host files for any tool, and no tool needs it: a local artifact belongs in a host-resource.
  Base images are the one legitimate `file://` user, and they go through `downloadAllowingLocalFile()` instead.
- **No host-local addresses.**
  Hosts resolving to loopback, the wildcard address, or link-local (which includes the `169.254.169.254` cloud metadata service) are refused.
  Those reach services only the host can see, such as the isx proxy, local dev servers, and instance credentials.
  Private LAN ranges stay allowed, since internal mirrors are legitimate.
- **Redirects checked hop by hop.**
  `HttpClient` runs with `Redirect.NEVER`, and `fetch()` follows up to 10 hops itself.
  Each hop goes through the same check and the same no-https-downgrade rule as `Redirect.NORMAL`, because a public URL could otherwise redirect to `127.0.0.1`.
  The check comes before the cache lookup.
- **Retries start over, and are checked again.**
  A 429, 500, 502, 503 or 504, or a connection that fails or breaks mid-body, is retried twice (after 2s, then 8s, or a longer `Retry-After` capped at 30s), because GitHub release downloads have been seen to answer 500 transiently, around when a new base image had just been published and was first pulled.
  Each attempt starts again at the original URL and runs the host-local check on it again just before connecting.
  A retry comes seconds after the first check, long enough for the JDK's address cache to let a rebinding domain move to `127.0.0.1`.
  Starting over also means a short-lived signed redirect target is never reused.
  What another attempt cannot fix fails at once: other statuses, these rules' refusals, an unresolvable name, a TLS handshake or certificate failure, a malformed response, and anything on this side of the connection (a failed write to the cache, a throwing `Listener`).
  `CountingSubscriber` tells the two apart once the body has started, since only the connection signals `onError`.
  The error names each distinct failure with its host, since behind a redirect the failing host is not the one in the URL.
  A retry restarts from byte 0; resuming with `Range` is a possible follow-up.
- **DNS rebinding cannot split check from connect.**
  A rebinding domain answers with a public address for the check and `127.0.0.1` for the connection.
  But both resolve through the JDK's in-process address cache, which keeps a successful lookup for 30 seconds whatever TTL the DNS answer carries, so the connection a few milliseconds after the check sees the same address.
  In the native image that policy is fixed at image build time (`sun.net.InetAddressCachePolicy` is build-time initialized).
  Measured on GraalVM 25: `-Dsun.net.inetaddr.ttl=0`, `-Dnetworkaddress.cache.ttl=0` and `JAVA_TOOL_OPTIONS` at runtime all leave it at 30s, and only the same flag passed to `native-image` changes it.
  So never pass `sun.net.inetaddr.ttl` or `networkaddress.cache.ttl` to the native build.
  On the JVM build, only whoever launches isx can lower it.

This follows `ToolProxyResolver.rejectProjectLocalProxy()`: project-local definitions get the capabilities that stay within the project, and anything that reaches the rest of the host belongs in a location the user owns.

### Metadata Tracking

Containers tagged via Incus `user.*` config keys:

```
user.incus-spawn.type=base
user.incus-spawn.profile=tpl-java
user.incus-spawn.parent=tpl-dev
user.incus-spawn.created=2026-04-07
user.incus-spawn.build-version=0.1.11        # isx version that built the template
user.incus-spawn.build-sha=c434ef9           # git commit SHA of isx at build time
user.incus-spawn.definition-sha=a1b2c3d4     # fingerprint of image def + tool defs
user.incus-spawn.ca-fingerprint=AB:CD:EF:... # CA certificate fingerprint
user.incus-spawn.build-source={...}          # (JSON, full image + tool defs for out-of-scope visibility)
user.incus-spawn.network-mode=PROXY_ONLY     # (proxy-only branches only)
user.incus-spawn.proxy-gateway=10.166.11.1   # (proxy-only branches only)
user.incus-spawn.static-ip=10.166.11.2       # (branches only, assigned at creation)
user.incus-spawn.host-resources=[...]        # (JSON, when host-resources declared)
```

**Metadata outside the fingerprint**: `default-action` changes nothing in the image, so it is left out of `definition-sha` and editing it does not make a template outdated.
Its stamp (`user.incus-spawn.default-action`, which `ActionResolver` falls back to when the YAML chain is gone) was then only ever written by a rebuild, and diverged silently from the YAML (#284).
So every `isx build` that returns, successfully or with a failed template build, ends with `syncDefaultActions()`: one `GET /1.0/instances?recursion=1` covers every existing template, and a stamp is written only where it differs from the definition chain.
Because the value is inherited, the guard covers the whole chain, not just the template's own definition: the chain must reach a root in the loaded definitions (with a parent's YAML gone the inherited value is unknown, and the stamp is exactly the fallback for that case), the template's own `definition-sha` must be current, and its `build-source` must record every ancestor with the content and project root it has now.
So an outdated template, or a child built before its parent added a tool, keeps the stamp matching what it has installed until it is rebuilt.
Ancestors are compared by their own definitions, not their tools' YAML: a child built before a parent's tool gained an action can take a `default-action` naming that action, the same ref the YAML-first path already resolves for it.
A project-local definition, parent overrides included, never reaches a template built from elsewhere (see "Project-local templates are confined to their project").
The build-source snapshots a build borrows for a template whose YAML is gone are not used, since they can be older than the template they describe.
Hooking each skip branch instead (a parent `buildChain` leaves alone, the untouched templates of `--out-of-sync`) was tried first: it missed `--missing`, the type-change path and templates outside the chain built, and cost a round trip per template.
Folding `default-action` into the fingerprint was rejected: a whole rebuild to change one metadata key.

**Staleness detection**: The TUI uses `build-version`, `definition-sha` and `created` to display staleness indicators next to template names, and `isx templates --format=plain|json` reports the same three as fields.
Both come from `TemplateStaleness` (`cli/.../command/`), whose `versionOutdated`, `definitionChanged` and `parentRebuilt` are also what `isx build --out-of-sync` rebuilds on (`BuildCommand.isImageOutdated`, which reads the template's instance in one request and its parent's in a second, only when the rest are current; a parent that is not built is not rebuilt, but Incus failing to answer is thrown, never read as current).
Any one of the three makes a template out of sync (`Staleness.outOfSync()`), which is also the TUI's count for "Rebuild out of sync templates".
`↑` counts since #1130: a child copied from its parent's previous build does not have what the parent has now, and before, nothing short of rebuilding the parent again repaired it.
It applies only to a template copied from its parent, which is one of the same machine type as Incus reports it (`TemplateStaleness.Built.machineType`, so a `--type` override counts).
A VM over a container parent (`tpl-isx-vm` over `tpl-isx`) is built from the definitions alone (`buildChain` skips its parent for the same reason), so its parent's builds are nothing to it.
A change to the parent's definition already reaches it through the descendant cascade.
Counting it would rebuild the VM, for minutes, after every rebuild of its parent.
It stays its own mark so the detail view can say why.
Only `build` acts on it; branch, start and the proxy do not:
- `!` — template was built with a different isx version than the running CLI
- `△` — the image definition or its tool definitions have changed since the last build (fingerprint mismatch)
- `↑` — a parent template was rebuilt more recently than this template

**Build source storage**: `build-source` stores the full image definition hierarchy and tool definitions as JSON.
This allows templates built from definitions that are no longer in scope (e.g. project-local definitions from a different working directory) to still be displayed and rebuilt in the TUI.

### Storage and COW

Copy-on-write storage is essential for efficient branching.
`isx init` automatically creates a btrfs storage pool (`cow`) if no CoW-capable pool exists.
On a CoW-capable pool (btrfs/zfs/lvm), Incus implements a same-pool `type: copy` as a native snapshot (e.g. `btrfs subvolume snapshot`), so copies are instant CoW clones with no data transfer — there is no need to use the Incus snapshot API explicitly.
Full copies happen only in two cases: (1) there is no CoW pool at all (the `dir` driver uses rsync), or (2) the source instance's root disk is on a different pool than the copy target, forcing a cross-pool migration path.

A CoW pool is **required** for instance creation (`create`) and copying (`copy`/`planCopy`).
`requireCowPool()` throws with a specific diagnostic (pool listing failed vs. no CoW pool found) instead of silently falling back to the default profile's pool, which on a pre-configured Ubuntu system is typically a `dir` pool.
`findCowPool()` (nullable) remains for callers that handle absence gracefully (cleanup, diagnostics, TUI display, image import).
`importImage()` uses `findCowPool()` and sends an `X-Incus-Pool` header when a CoW pool exists, so base images land on the CoW pool when possible — but does not throw when absent, since images are still usable on any pool.

`IncusClient.copy()` follows the source instance's pool: if the source's root disk is on a CoW pool, the copy targets that same pool (guaranteeing same-pool CoW); otherwise it targets the first CoW pool (cross-pool full copy).
`planCopy()` also throws if no CoW pool exists.
`isx branch` and `BuildCommand.buildFromParent()` compute a `CopyPlan` before copying and warn the user when the copy will be a full rsync (with a pointer to `isx doctor` for remediation).
`isx doctor` surfaces three failure modes: no CoW pool at all (FAIL with a Linux remediation to create one), the default profile's root disk pointing to a non-CoW pool (WARN with auto-fix remediation), and instances whose root disk landed on a non-CoW pool (WARN naming the affected instances, with a remediation that moves the stopped ones through `IncusClient.moveToPool`; running ones are only listed).

A common trigger for stale profile state is setting up Incus before `isx init` (e.g. `incus admin init --minimal` on Ubuntu), which creates a `default` dir pool and sets the profile's root disk to it.
`ensureDefaultProfile()` now upgrades the root disk to the CoW pool when it finds this mismatch during init.

Supported CoW drivers: **btrfs**, **zfs**, **lvm**.
If btrfs pool creation fails during init (e.g. unsupported filesystem), the user is warned and can continue with the `dir` driver, but clones will be full copies.

**Disk-space visibility in the TUI**: All instances (and templates — templates are just stopped instances) share this one pool, so the pool's total is the real disk budget.
On the macOS appliance the `cow` pool is a fixed-size VM disk, so its `space.total` *is* the VM's disk.
The TUI surfaces this with a full-width **storage gauge** above the panels (green/amber/red by fill percentage) plus a per-row **DISK** column on both panels.
The gauge reads whole-pool usage (`getPoolUsageBytes`); the per-row figures come from `state.disk.<dev>.usage` in the recursion=2 listing the TUI already fetches, so no extra API cost.
Two deliberate honesty choices follow from the CoW model: (1) per-row usage is shown as **absolute used bytes**, never as a percentage of the instance's `root.size` — that limit is a thin-provisioned ceiling (defaults to 100 GB), not an allocation, so a percentage would be meaningless; (2) per-row figures are marked approximate (`~`).

The per-row number is hard because the only figure Incus exposes (`state.disk.<dev>.usage`) is btrfs *exclusive* bytes — blocks unique to that one subvolume — and exclusive **collapses to ~0 for any subvolume that has a CoW descendant**.
So a template's real weight is invisible: the moment a child template or a branch exists, the parent's own layer stops being exclusive to it.
The base image and every shared block belong to no row and appear only in the pool total.
The figure that *does* answer "how big is this template" is btrfs **referenced** (rfer) — a subvolume's full logical size including blocks it shares with ancestors — but Incus exposes no API for it (its btrfs driver reads the qgroup and returns only the exclusive column).

So `isx` reads rfer itself, and caches it.
Because a built template is immutable and its rfer is stable (referenced bytes don't change when a parent is later deleted — the child still references those extents), `BuildCommand` measures rfer **once at build time** and stamps it as `user.incus-spawn.disk-referenced` metadata (excluded from the content fingerprint, so it never triggers a rebuild).
The TUI then reads that stamp for free on every reload and shows each template as a **delta from its parent**.
The root template's delta is its own rfer — i.e. the base-image weight — and each derived template shows only what its layer added (packages, a tools install), which is exactly the intuitive "cost of this template".
Instances keep their exclusive usage: a branch with no descendants has `exclusive ≈ rfer − rfer(parent)`, so exclusive already *is* its delta, and it comes free from the API.
This replaces the older `foldBaseWeightIntoRootTemplate`, which — lacking rfer — could only dump the *entire* pool remainder onto the root template.
So on a real inheritance chain the base template ballooned to include every layer's shared blocks while the intermediate templates read ~0.
The fold survives only as a fallback: when a template predates the stamp (cache-only, no backfill — rebuild to populate it) or the pool isn't btrfs, the display falls back to exclusive-plus-fold.

Instances (unlike templates) are mutable, so they are never stamped — each instance row always shows its **live exclusive** usage from the Incus API, recomputed every reload.
The reading is "space reclaimed by deleting just this row".
An instance branched from another instance shares its inherited blocks with the source, so those blocks are exclusive to neither and float in the pool total (visible in the gauge, attributed to no row) exactly like template-shared blocks.
This makes instance→instance branching safe by construction.
Because nothing is cached, deleting the branched-from instance needs no invalidation.
On the next reload the surviving branch's exclusive usage simply grows to absorb the blocks that were shared, since they are now unique to it.
The delete-confirmation note closes the loop in the other direction.
Before the shared-with-parent caveat it checks whether anything was branched or derived from the target (`hasDescendant`, using the parent each row already records for free) and, if so, warns that deleting it reclaims little while its descendants remain — the instance analog of the base-template note.

Deleting a template is safe: rfer is a property of the *extents a subvolume references*, so a child's stamped rfer stays accurate after its parent is deleted (the child still references — and keeps alive — those blocks).
Only the delta arithmetic has to cope with a missing parent, and it does.
The row's parent is resolved by climbing the *definitional* chain (the on-disk YAML, which outlives the deleted instance) to the nearest ancestor that still exists and is stamped (`nearestStampedAncestorRfer`).
So deleting an intermediate template just makes the next survivor down the chain subtract the next survivor up — no phantom subtraction, no crash — and if the entire chain above a row is gone, its parent resolves to `null` and it shows full rfer, correctly re-absorbing the base weight that no other row now claims.
The rows keep reconciling with the gauge; the only residual imprecision is that blocks shared *between siblings* of a deleted fork point get counted in each sibling — bounded, and already flagged by the `~` marker.
The delete-confirmation dialog restates the caveat at the moment it matters — for a clone, "blocks shared with `<parent>` stay until the parent is removed"; for the base template, that most of its size is the shared base image and won't be reclaimed while derivatives exist.

Reading rfer needs root (the qgroup ioctls need `CAP_SYS_ADMIN` and the pool directory is `0700 root`), and `isx` normally runs as an unprivileged user.
Rather than prompt for sudo on every TUI refresh, the privileged read is split by platform (`BtrfsUsage`).
On **Linux** the pool is on the host, so `isx init` installs a tightly-scoped NOPASSWD sudoers rule (`/etc/sudoers.d/incus-spawn-btrfs`, validated with `visudo -cf`) permitting *only* read-only `btrfs qgroup show -re --raw [--sync]`/`subvolume list` against the pool mount (both the plain and `--sync` command forms are listed, since sudo matches argv exactly).
On **macOS** the pool lives inside the appliance VM, where the in-VM control agent already runs as root, so it gains a `btrfs-usage` verb (still an allowlisted one-verb dispatcher, not a general guest-exec channel).
Both paths feed one unit-tested parser (`BtrfsUsage.parse`, joining qgroup rfer to subvolume paths).
Since rfer is read only at build time (plus the fallback), the privileged surface is exercised rarely, not on every refresh.

Two btrfs quirks make the stamp-time read fragile, both handled in `BuildCommand.probeReferencedSize` (which measures) / `stampReferencedSize` (which records) and `BtrfsUsage`: (1) qgroup accounting only reflects *committed* transactions, so a read right after a build can miss its final writes; (2) deleting a subvolume can mark the pool's qgroup accounting inconsistent, and a rebuild does exactly that (`deleteIfExists` of the previous template) immediately before stamping.
For (2), `probeReferencedSize` measures rfer against the freshly-built *temp* subvolume **before** the delete/rename, not after — rfer is per-subvolume, so the temp name reports the same value the canonical name will.
For (1), the read comes in two flavours (`BtrfsUsage.probe(pool, sync)`).
The stamp-time read passes `--sync` to force a commit first, while the plain read (the default, and the intended path for periodic sampling) skips it, because forcing a whole-filesystem commit on every sampling tick would be wasteful.
The `--sync` flavour must stay in step across three layers — the Java command, the `isx-agent`'s `btrfs-usage <pool> [sync]` verb (an allowlisted second token), and the sudoers rule (which lists both command forms) — or the privileged read is denied.

Getting a stamp wrong no longer collapses the whole display.
`canUseReferencedModel` gates the delta model on every built **root** template being stamped (the root carries the base-image weight, so without its rfer the shared-base fold is the better model).
A built *derived* template missing its stamp is tolerated — `applyReferencedTemplateDeltas` leaves that one row on its exclusive usage instead of dropping every template to ~0.
So a transient read failure at build time degrades a single row, not the entire panel.

A missing stamp also self-heals on reload without a rebuild: `fillMissingReferencedSizes` backfills it with a single live probe — using the light (non-sync) flavour, and *only* when there's an actual gap (an unstamped built template).
This keeps the privileged read out of the healthy per-refresh path (a fully-stamped install never probes on reload) while recovering pre-feature templates and the rare failed stamp.
The backfill is in-memory (a rebuild re-stamps permanently) and never overwrites an existing stamp, which is authoritative and free.

**The accounting itself can be silently wrong, so it is checked and repaired.**
Ordering the read before the delete addresses a *transient* effect; the real failure is persistent and filesystem-wide.
The kernel flags a pool's qgroup accounting `inconsistent` — and from then on freezes every counter at its last value — when quotas are enabled on a pool that already holds data, or when dropping a subvolume would require walking a shared subtree deeper than `drop_subtree_threshold`.
Both are routine for isx.
Incus enables quotas lazily, when the first instance size limit is applied (by which time the image and every template already exist), and the appliance kernel runs with the threshold at 3, so template rebuilds keep re-tripping it.
Nothing clears the flag except a completed `btrfs quota rescan`.
The frozen values are not zeros but plausible numbers (each snapshot inherits its source's rfer, so every subvolume reports the base image's ~113 MiB with 16 KiB exclusive), which is why this went unnoticed: they pass the `<= 0` stamp guard, get recorded, and the delta model subtracts identical stamps to exactly `~0B` per layer.
The exclusive fallback and Incus's own `state.disk` usage read the same frozen qgroups, so there is no untainted fallback — only the statfs-based gauge stays right.
The fix is a trust gate plus an automatic repair, both in `BtrfsUsage`.
The kernel exposes the flags world-readable in sysfs (`/sys/fs/btrfs/<fsid>/qgroups/{enabled,inconsistent,mode,drop_subtree_threshold}`), so *detection* needs no privilege at all.
`BtrfsSysfs` resolves the pool's fsid on Linux (via the containing btrfs mount's *source device* in `/proc/self/mountinfo` — btrfs `st_dev` numbers are per-subvolume, so neither `stat` nor mountinfo's `major:minor` identify the block device), and the agent's `btrfs-status` verb does the same lookup inside the VM.
One `parseStatus` serves both.
`repairIfInconsistent` reads the status and, if the accounting is inconsistent, starts the rescan asynchronously — the kernel rebuilds the counters in the background and clears the flag when done, seconds on a developer-sized pool — throttled to one trigger per minute and five per process (on Linux via a `btrfs quota rescan <pool>` entry added to the sudoers rule at `INIT_VERSION` 6; on macOS via the agent's `btrfs-rescan` verb).
Consumers act on `untrusted()` only — a status that *can't be read* (older agent, pre-6.1 kernel, quotas off) keeps the pre-check behaviour rather than blanking the display.
`IncusClient.delete()` — the single method every subvolume delete funnels through, `deleteIfExists` included — calls it right after the delete succeeds (best-effort, never failing a delete that already worked).
A delete is exactly the operation that can cause the inconsistency, so this catches it immediately instead of waiting for the next reload or build.
Deletes arrive in bursts (`isx clean` sweeping failed builds, destroying a set of branches).
So that path uses `repairIfInconsistentThrottled`, which collapses a burst to one check (5s minimum spacing) and defers the caller's pool lookup behind the same gate.
Otherwise N deletes would mean N status reads, each an agent round trip on macOS.
Nothing is missed by collapsing: only the deletes in the burst could have set the flag, the first check already caught that and started the rescan, and the TUI cadence and next build re-check regardless.
The rescan-trigger budget (`MAX_RESCAN_TRIGGERS`) counts *consecutive* attempts and resets the moment accounting reads consistent, so it bounds a stuck repair rather than capping how many times a long-lived session may legitimately self-heal.
The TUI also calls it every reload (cadence-limited: 30s normally, 2s while a repair is pending, so an agent round trip on macOS isn't on the hot path).
While untrusted it keeps showing existing stamps (a stamp taken from consistent accounting stays valid, templates being immutable), reads nothing live (no backfill, no shared-base fold) and shows a one-shot "repairing" hint.
The reload that first sees the flag clear re-validates the stamps with one live probe and re-stamps whatever differs — the one path that overwrites a stamp, which also fires once per session on the poisoned-stamp signature (a derived template stamped with exactly its parent's value) so pools broken before the check existed heal without a rebuild.
`BuildCommand.probeReferencedSize` runs the same detect-and-repair before stamping, with a bounded wait (`awaitConsistent`, 20s) so the usual case still gets an immediate, correct stamp.
`isx doctor` reports the state and offers the rescan for when the automatic one couldn't run.
The `C` key reclaims space via `isx clean pool` (failed builds, unused images, build caches).
Downloaded base images, the container one and the `-vm` disk image, are a category of their own and opt-in, like the DNF cache.
They are not unused, only re-downloadable, and on a slow connection a VM base image is the most expensive thing to lose.
Labelled "Storage" rather than "pool" to avoid leaking the Incus term, and the gauge/column degrade gracefully to hidden/`-` on `dir` pools that report no per-volume usage.

Reclaiming isn't always enough: on the macOS appliance the pool is capped by the VM's data disk, so once real usage approaches the ceiling the fix is to *grow the disk*, not delete work.
`isx vm resize <size>` does this (macOS only — on native Linux the pool grows with the host filesystem, so the command isn't registered there at all).
The data disk is a sparse raw image on the host.
Resizing extends the file (`RandomAccessFile.setLength`, grow-only) while the VM is stopped, then the guest expands the btrfs filesystem to fill the larger device on the next boot — a one-line `btrfs filesystem resize max /var/lib/incus` in the appliance init that mirrors the root disk's existing auto-resize.
The data disk is deliberately separate from the root disk and survives root-disk upgrades, so growth persists across appliance version bumps.
The resize verifies the pool total actually grew after reboot and warns if the running appliance predates the auto-resize step (the image grew but the filesystem didn't).
The critical-fill TUI warning names both remedies — `C` to reclaim, `isx vm resize` to expand.

#### Orphaned subvolumes and dangling records

A pool's instance subvolumes and Incus's records can drift apart (#717).
A rename that moves the subvolume while the record (and its `backup.yaml`) keeps the old name, followed by a delete of that record, leaves data on disk with nothing pointing at it: an *orphan*.
Incus cannot see it, so neither can isx, until something creates an instance of that name and fails with "file exists".
For a template rebuild, which creates `<name>-rebuilding` and renames it to `<name>`, that failure used to come after the whole build.
The stage before is a *dangling record*: an instance whose subvolume is not at its path.
Whether Incus then deletes such a record may depend on its version: 6.23 refuses ("Not a Btrfs subvolume"), while the orphans found on the #717 appliance (Incus 6.21) fit a delete that went through.

`InstanceSubvolumes` compares the two sides.
The disk side is a names-only parse of `btrfs subvolume list` (`BtrfsUsage.subvolumeList`, the same sudoers entry and agent verb as the rfer read).
The rfer join in `BtrfsUsage.parse` cannot serve, because it drops every subvolume without a qgroup row, which would hide orphans and invent dangling records; it places subvolumes with the same parser, though, so sizes and the scan agree on names.
Only top-level `containers/<name>` and `virtual-machines/<name>` count, anchored at the pool (the filesystem root, or `…/storage-pools/<pool>` with no instance or pool directory above it): a guest running its own Incus nests a pool of the same name inside its rootfs.
The records side is the pool's volumes across all projects, which live on disk as `<project>_<name>` outside `default`, plus the default-project instances whose root disk is on the pool.
An empty listing while Incus has instances there is treated as "unknown", not as every instance being dangling, because the agent answers an empty section when `btrfs` fails.

Four consumers use it, and each **fails open** when the pool cannot be listed (not btrfs, no sudoers rule, an unprivileged host): refusing every delete there would be worse than the rare orphan.

- `IncusClient.delete` throws `DanglingRecordException` before sending the DELETE when the instance's subvolume is missing, because that delete is what strands the data.
- `IncusClient.rename` checks afterwards that Incus lists the new name and that the subvolume moved with it.
  That turns a half-completed rename (the build's swap, promotion to `-failed-build`, a TUI rename) into an error at the rename rather than a latent orphan.
- `BuildCommand.buildSingleImage` refuses up front when either `<name>` or `<name>-rebuilding` is an orphan or a dangling record, since its swap would otherwise fail only after the whole build.
- `isx doctor` reports orphans (FAIL when the name is a template's or its `-rebuilding` name, since that build cannot succeed; WARN otherwise, with referenced sizes when qgroups have them) and dangling records (WARN).
  On Linux it prints the `sudo btrfs subvolume delete -R` command instead of widening the read-only sudoers rule — removal stays manual there.
  On macOS it offers a real remediation, one orphan at a time, through the `btrfs-orphan-delete` agent verb (#874).
  Wiping the whole pool with `isx vm reset` was the only option before, which is disproportionate for a handful of stray subvolumes and loses every instance, not just the orphaned ones.
  The agent re-checks, from inside the VM and across every Incus project, that nothing still references the name before it deletes anything — a separate trust boundary from `isx doctor`'s own scan, which runs on the host and can be stale by the time a user confirms the remediation (another branch could finish, or fail and get cleaned up, in between).
  It also confirms the path is still a *subvolume*, not "exists": Incus 6.23 recreates a plain directory with `backup.yaml` at a dangling record's path whenever it rewrites that record, and that directory is not the orphan's data.
  `subvolumeFindings` stays pure (no agent call) so it is unit-testable without a VM.
  `DoctorCommand.checkSubvolumes` is where the live action is wired in, replacing the finding's description and remediation only on macOS and only when there are orphans and `VmAgentClient.supportsOrphanDelete` confirms the appliance has the verb.
  That probe sends a name no real subvolume has, so a supported appliance answers `error: not a subvolume` — never reaching the Incus re-check or a delete — while one built before the verb existed answers `error: unknown verb`.
  Either way nothing is touched.
  Probing before offering, rather than discovering it after the user confirms, is what lets the remediation keep pointing at `isx vm reset` on an older appliance instead of a destructive action reporting, too late, that it cannot do what it just offered.
  `DoctorCommand.deleteOrphans` draws the same distinction on the delete calls themselves.
  A timeout (`VmAgentClient.send`'s own 5s watchdog, same as an unreachable agent) answers `Optional.empty()`, which is not the literal `error: unknown verb` string — the only reply that actually means "unsupported" — so a slow-but-working verb on a later orphan is not told its appliance is too old while its delete finishes inside the VM regardless.

`isx vm reset` (`VmManager.resetDataDisk`) is the macOS last resort for a pool with nothing worth keeping.
It lists what will be lost, booting a stopped VM to ask Incus, because the host keeps per-instance state (SSH config, git remotes) that only instance names can find.
Then, holding the VM lifecycle lock, it stops the VM and waits for the hypervisor process to exit.
It replaces the data disk with a blank sparse one **of the same size**, so a disk grown with `isx vm resize` stays grown, and boots.
The appliance's `rcS` formats the blank disk and `incus-spawn-vm-init` recreates the bridge, the `cow` pool and the default profile.
The command then removes the host integration of every instance it listed, signals the proxy, and checks that Incus answers with an empty CoW pool.
Like every command that deletes data, it refuses to run without a terminal to confirm on unless given its skip flag (`BaseCommand.confirmDestructive`).
The older `confirm()` proceeds there, which is how `echo n | isx vm reset` once wiped a pool during testing.
`resetDataDisk` returns a `ResetResult` rather than a boolean because the cleanup hinges on it.
`NOT_RESET` (lock held, the VM would not exit, the disk could not be deleted) leaves every instance in place and must not touch their host state, while `VM_DOWN` means the disk is gone even though the VM did not come back.

**Tidying "state" keeps the data disk (#1155).**
The data disk lives in `~/.local/state/incus-spawn/` beside pid files, sockets, logs and the root disk, so anything that tidies that directory took every instance with it.
`isx clean state` and `isx clean all` deleted it as "state" (and `clean all` claimed the pool was untouched), and `uninstall.sh` listed it as "remove disk image".
`uninstall.sh` is also the natural step when moving from a source build to Homebrew, which is how a whole pool was lost to a confirmed prompt.
All three now delete everything in the directory *except* `data.img` unless given `--delete-instances`, and say what they keep and what it holds (the script counts instances and built templates with the isx being uninstalled when the VM is up; with it down nothing can tell, and it says so).
Keeping only that file is enough: it is mounted at `/var/lib/incus`, so it carries the Incus database as well as the pool, and the appliance boots on it as it does after a root-disk upgrade.
That makes how the VM stops matter: before, a disk about to be deleted could be killed mid-write, but one that is kept is stopped through the isx being uninstalled (`isx vm stop`) first.
That asks the guest to shut down through its agent (`shutdown`: `poweroff`, so init runs `rcK`, which stops Incus and its instances and syncs) and waits up to 30 s for the hypervisor to exit by itself.
Only then does `VmManager.stopLocked` fall back to vfkit's stop request and its own signals.
Before #881 there was no such verb, and vfkit's request is a power button nothing in the guest listens for.
So every `isx vm stop` on a Mac ended in the signal: the next boot replayed the data disk's btrfs tree log, and with the disks mounted `commit=300` an instance deleted seconds before the stop came back as an orphaned subvolume (see appliance/DESIGN.md, "Stop sequence").
An appliance from before the verb is still stopped that way, until a restart applies a newer one.
Because the VM is gone by the time the script looks, it cannot see a cut-off itself, so `stopLocked` reports how the VM stopped (`VmManager.StopResult`: `SHUT_DOWN` or `SIGNALLED`).
`stopLocked` warns on `SIGNALLED` whenever the disks are kept (`isx vm stop`, `restart`, `resize`, doctor's restart); `isx reset` and `isx vm reset`, which delete them next, stop quietly (`VmManager.stopToDelete`).
`isx vm stop --require-clean` exits 4 on `SIGNALLED` and 1 on a stop that failed.
The script passes that flag and, on 4 or when the VM outlives isx (then it signals the VM itself), warns that the kept disk may need recovery, then and in its closing lines.
With an appliance from before #881 that warning appears on every Mac uninstall with a running VM: "kept" means "as intact as after `isx vm stop`", which there is a tree-log replay.
The exit status is opt-in so that `isx vm stop && ...` keeps working where every stop is still a signalled one.
An isx from before the flag rejects it with 2; the script then runs a plain `isx vm stop` and, since that isx cannot say how it went, warns that it cannot tell.
`isx` is asked for the counts through a temporary file rather than a pipe, so `$(...)` does not wait for a child isx left holding its output.
`bounded` runs the command in a process group of its own under a watchdog that SIGKILLs the group, since a bare `alarm` does nothing to a command that ignores SIGALRM (an ignored disposition survives `exec`) and reaches nothing the command started.
The 20 s is a hard bound.
`uninstall.sh --binaries-only` removes just the binaries and install.sh's Homebrew override, for switching install channel.
On Linux the proxy service needs no change there, since the next isx command rewrites a start script that names another `isx-proxy` (`ProxyService.reinstallIfChanged`).
On macOS that is not enough, as a Mac showed.
The launch agents name the binaries by path, `reinstallIfChanged` rewrites only the proxy's, and only once it sees a proxy of another version answering, so with the same version in both channels nothing was repaired and the proxy lived on only as the old process.
The VM agent was left naming a removed `isx` in every case, to fail at the next login.
So when a plist names the directory the binaries were removed from, the script runs `isx proxy stop` and then `isx proxy install` with the isx that remains on `PATH`.
The stop is needed: `isx proxy install` writes both agents only for a service that is not loaded (`installMacOs`), and on a loaded one rewrites the proxy's alone.
The VM keeps running throughout; the proxy is down for the second or two between the two commands.
The script then looks at the plists again and warns, naming the two commands, when they still name a removed binary, or when no isx remains to run them.
Agents written by another install are left alone.
The script refuses an unknown option rather than ignoring it, so a mistyped `--binaries-only` cannot run a full uninstall.
The root fix, moving the data disk out of the state directory, needs a migration and is a separate change.
`isx clean state` and `isx clean all` remove the appliance (`~/.local/share/incus-spawn`) along with the rest, so the VM boots on the kept disk only after `isx init` downloads it again, after either command.
The VM's launch agent has no `KeepAlive` (checked on a Mac in the plists 0.3.9 and this change write; 0.3.10's was not available there), so stopping the VM before booting out the agent cannot have launchd start it again.

### Repo Cloning and Reference Optimization

Repos declared in an image definition are cloned into the container during build as `agentuser`.
Clones use `--single-branch` to fetch only the target branch (or the default branch when none is specified), avoiding the download of hundreds of release/PR branches and thousands of tags that are present on large upstream repos but rarely needed in a dev container.
After cloning, `git remote set-branches origin '*'` immediately widens the fetch refspec — this is a pure metadata write with no network traffic — so the clone is indistinguishable from a regular one.
Other branches populate lazily on first `git fetch` or `git checkout`.

**Parallel cloning**: when a template declares multiple repos they are cloned concurrently, bounded to the host's high-performance ("P") core count (`CpuInfo.highPerfCores()` — `sysctl hw.perflevel0.logicalcpu` on macOS, on Linux the top tier of the sysfs scan described in "VM boot: where the wait goes" (the hybrid PMU's `cpu_core` list, else `cpu_capacity`, else `cpufreq`), falling back to all logical processors).
`CpuInfo` is the shared home for CPU-topology detection.
It also backs the appliance VM's vCPU default (`VmManager.detectCpus()` → `performanceCores()`) and the native-image-safe host processor count (`ResourceLimits.hostProcessorCount()` → `logicalCores()`, which reads `/proc/cpuinfo`/`sysctl` rather than `Runtime.availableProcessors()` since the CLI native image pins the latter via `-R:ActiveProcessorCount`).
Cloning runs entirely with captured (non-streamed) exec so the many parallel clones don't garble the terminal.
Progress is rendered by the shared `TerminalProgress` helper as one animated braille-spinner line per repo (green ✓ / red ✗ on completion), falling back to plain per-repo log lines on a non-ANSI terminal.
Any clone failure is surfaced after the batch and aborts the build.
Which repos have a host reference is decided on the host before the parallel section; each worker attaches its own reference just before its clone and detaches it as soon as the clone is done (#826).
Attaches and detaches share a lock, because a device removal is a read-modify-write of the whole device map and would drop an attach that landed in between.
Attaching lazily is what keeps a VM within its 8 PCI hotplug slots (see "Devices are attached before start"): mounting every reference up front lost the 9th one to the network, silently.
The slots are a budget (`HotplugSlots`) that starts at `min(references, 8)` on a VM and is unbounded for a container.
It is a hint, not a count.
An attach Incus refuses for want of a slot retires one permit for good and waits for another, so a device nobody counted shrinks the budget instead of reintroducing the bug.
Only when the budget would reach zero does a repo clone from the network, with a note saying why on its line.
The latch the primes wait on counts *planned* references, each counted down once, in a `finally`, after its attach is resolved and anything attached is detached.
So a failed or re-queued attach can neither hang the primes nor land after them.
Each repo's declared `prime` command (also captured, no PTY) runs in the same worker once that repo's clone is done **and every reference has been detached**, so priming still pipelines with the network clones in flight.
The reason is that a reference mount is the host checkout's whole working tree: untracked `.env` files, stashes, unpushed branches, and credentials in `.git/config` URLs.
A prime command is arbitrary code with network access, and the old "remove all references after everything" order let any repo's prime read every mounted checkout (#765).
A failed detach fails the build rather than prime next to a mount.
The concurrency bound is a semaphore in `prepareOne` taken around the clone and the prime separately, not `TerminalProgress`'s per-task slot.
A worker waiting for the detaches must not hold a slot, or with more referenced repos than slots the clones that would detach them could never start.
A single progress line per repo advances Cloning → Priming → ✓/✗.
Failures are aggregated and abort the build; as a best-effort fail-fast, once any repo fails a clone that completes afterward skips launching its (potentially expensive) prime — primes already in flight run to completion.

**Local-clone optimization**: When `host-paths` or `repo-paths` is configured in `~/.config/incus-spawn/config.yaml`, the build checks whether a matching host-side checkout exists before cloning.
The lookup first checks direct children of each configured base directory, then recursively scans subdirectories up to 4 levels deep (skipping known non-project directories like `.git`, `node_modules`, `target`, `build`, `vendor`, etc.) to handle repos organized in nested folder structures (e.g. `~/Code/java/repo-a`).
When a repo subdirectory exists in more than one location, the build fails with an error instructing the user to add an explicit `repo-paths` entry to disambiguate.
Matching uses URL normalization (strips scheme, `user@`, SSH colon separator, trailing `.git`, `www.`, then lowercases) and checks **all** git remotes, not just `origin`.
This handles the common case where the user's fork is `origin` and the canonical upstream is `upstream`.
If a match is found, the host directory is temporarily mounted into the container as a read-only Incus disk device (`readonly=true shift=true`) at a fixed path under `/var/lib/incus-spawn/repo-ref/` and cloned locally via `git clone --no-hardlinks`.
This copies pack files directly — no network transfer and, critically, no `git repack` or dissociation step.
The old approach used `git clone --reference` and then ran `git repack -a -d` to make the clone self-contained before unmounting the reference; for large repos this repack was the dominant cost (re-reading, re-deltifying, and rewriting every object).
The local-clone approach avoids it entirely.
Pack files are copied as-is, then the remote URL is fixed to the real origin and a `git fetch` picks up any commits added since the last host refresh (usually nothing — `HostRepoRefresh` just ran — so only ref advertisements travel the network).
If a specific branch was requested, it is checked out after the fetch supplies the ref.
The clone includes objects reachable from all branches in the reference (pack files can't be efficiently subsetted), but only the needed refs are set up; extra objects are harmless dead weight cleaned by a future `git gc`.
Only committed history is copied, but the whole checkout (working tree, untracked files, `.git/config`) is *visible* inside the build container while the reference is mounted.
That is why no prime command runs until every reference is detached (see above), and why project-local templates get no references at all.
If the reference mount, clone, or fetch fails for any reason the build cleans up the partial checkout and falls back transparently to a plain remote clone.

**TUI visibility**: The F3 template detail view shows the host repo link status for each declared repo — the resolved host path when matched, or "No matching host checkout found" when not.
This lets users verify their `host-paths`/`repo-paths` configuration without running a build.
The view is full screen and shows the template's effective settings resolved across the inheritance chain (`TemplateDetails`), using the build's own resolvers so what it shows is what a build produces -- including the type a template was actually built as (its `instance-mode` stamp), flagged when the definition has since changed it.
Under the template's source it names the definition that one overrides from an earlier search layer, and the file the image was built from when that is another one -- the "the built image doesn't match my file" confusion of #593, answered where people look rather than only in `isx doctor` (#1099).
Both come from data the TUI already holds: the layered load and the listing's `build-source` stamp, so the view adds no Incus request.
A definitions directory reached twice (listed again, or through a symlink) is the same file, not an override.
`LayeredDefinitions.put()` still records the later layer's path and definition, exactly as for any layer, because project-local confinement is read from them -- a tool's from its recorded path (`projectLocalToolNames()`), an image's from its definition's `projectRoot` -- and a project's `.incus-spawn/` that is also a search path, under whatever spelling, must stay confined.
What changes is only the override list: no self-override is recorded, and the override the earlier spelling made is re-spelled to the later path, so the real override below it still shows.

### Git Remote Helper

Containers cloned via `isx branch` are isolated development environments, but developers need a way to get their changes back to the host.
Rather than inventing a custom sync mechanism, incus-spawn integrates with git's native remote helper protocol so standard `git fetch`/`git push`/`git pull` work between host repos and container repos.

**Architecture: bash shim + Java command**

The git remote helper is split into two processes:

1. **`git-remote-isx`** (bash script): installed alongside `isx` in `$PATH`.
   Git discovers it automatically when a remote URL uses the `isx://` scheme.
   The script handles the text-based git remote helper protocol (advertising the `connect` capability), then `exec`s `isx git-remote-helper` to handle the actual transport.

2. **`isx git-remote-helper`** (Java/picocli command): validates the instance is running, validates the requested service against an allowlist, and uses `IncusClient.execBidirectional` to run `<service> '<path>'` inside the container with stdin/stdout forwarded over WebSocket.
   The git pack protocol flows directly between the host git process and the container git process.

The bash `exec` replaces the shell process with the Java process before any data flows through stdin.
This is critical: Java's `BufferedInputStream` would consume bytes from the stdin pipe that are meant for the git pack protocol, corrupting the stream.
By having bash handle only the text protocol exchange (a few short lines) and then `exec`-replacing itself, the Java process inherits the raw file descriptors with no buffered-ahead data.

Stderr is captured in a virtual thread so the command can detect "not a git repository" errors and print hints listing known repos from the image definition chain.

**URL scheme**

`isx://<instance-name>/<path-to-repo>` — for example, `isx://fix-auth/home/agentuser/quarkus` or `isx://fix-auth/~/quarkus` (tilde expands to `/home/agentuser`).

**Auto-remote management**

When the user configures `host-paths` (and optionally `repo-paths`) in `config.yaml`, incus-spawn automatically adds and removes git remotes in host repositories:

- **On `isx branch`**: for each repo declared in the image definition chain, resolve the corresponding host repo via `repo-paths` (exact match) or `host-paths` (base directories scanned recursively up to 4 levels deep).
  Direct-child matches take priority; recursive scanning only fires when no direct match exists.
  When the same repo name appears in more than one location, the operation fails with an error instructing the user to add an explicit `repo-paths` entry.
  Verify that any of the host repo's remotes (not just `origin`) match the container repo's URL (protocol-lenient comparison).
  If a match is found, add a remote named after the instance.
- **On `isx destroy`**: scan candidate host repos for any remote with a URL matching `isx://<instance-name>/` and remove it.

The removal is stateless — rather than tracking which remotes were added, we scan for `isx://` URLs matching the instance name.
This avoids a class of bugs where state gets out of sync (e.g., the user manually removes a remote, or the add failed silently).

**Protocol-lenient URL matching**

Host repos may use SSH URLs (`git@github.com:org/repo.git`) while container repos use HTTPS (`https://github.com/org/repo.git`).
The URL matcher normalizes both formats by stripping the scheme, `user@` prefix, SSH `:` separator, trailing `.git`, `www.` prefix, and lowercasing.
The result is a canonical form like `github.com/org/repo` that matches regardless of protocol.

### MCP server: delegating work to isx instances

`isx mcp` lets an agent on the host -- the user's own Claude Code -- use isx the way the user does: create disposable instances from approved templates, run commands in them, and hand whole tasks to the Claude Code inside one.
The host agent plans and reviews; instances do the work, with the same isolation and credential injection as any branch.

**Hand-rolled protocol, measured.**
The Quarkus MCP extension was tried first, initialized only on demand (`quarkus.mcp.server.stdio.initialization-enabled=false`, started explicitly by the command).
It still cost every isx command.
Native `isx --help` went from 3.2 to 4.9 ms (+55%; Vert.x alone was +0.7 ms), the binary grew 11% and RSS 5 MB, because the extension's config mappings and metadata are built at runtime init whether or not MCP is served.
What isx needs of the protocol is small -- `initialize`, `ping`, `tools/list`, `tools/call`, cancellation, progress -- so `cli/.../mcp/` implements it over Jackson trees (no reflection registration), and startup is unchanged.
`McpTransport` is the seam for a later HTTP transport.

**Stdout is the protocol.**
Much of isx prints progress to `System.out`.
Rather than teach every helper about MCP, `StdioGuard` hands the raw fd 0/1 to the transport, points `System.out` at stderr (which the client logs) and empties `System.in`.
The `Headless` flag makes `TerminalProgress` stop animating and `Prompts.console()` return nothing, so no prompt can consume protocol bytes.
The MCP path never calls `requireInit()` or anything that `System.exit`s: each becomes a tool error telling the agent what to ask the user.

**Who is calling.**
Over stdio there is no endpoint: the client spawned `isx mcp` and holds both pipes, so authentication is the OS user -- anyone who could reach the server could run `isx` anyway.
A session is the `isx mcp` process, identified as `<pid>-<start>`; the start makes the id safe to test for liveness after the pid is reused.
On Linux it is the kernel's `starttime` in clock ticks since boot, not the JDK's start instant: that is derived from `/proc/stat`'s `btime`, which moves when NTP steps the clock, so a later process could compute a different start for a live holder and treat its instances as orphans -- adoptable without `force`, then destroyed. macOS records the start instant once, at fork, so it uses the JDK's.
The client's self-reported name, its pid and working directory are stamped too, for display only.

**Owned by the user, held by a session (#898).**
The first version tied an instance to the process that created it: destroyed when that process ended, unreachable through MCP once kept, reaped on sight by the next session.
That fits one agent using isx from its shell, and fails the consumer it was built for -- a coordinating agent that dispatches issues to workers and restarts (its context fills, it crashes, the machine reboots) while those workers wait on CI or a person's reply.
Every restart stranded or destroyed them.
Keeping everything was no way out: kept instances were unreachable and nothing cleaned them up.
So an instance now *belongs* to the host user (`mcp-owner`) and carries a free-text `mcp-purpose`; `mcp-session` says which session *holds* it, and that session's liveness is all it is read for.
`adopt_instance` lets any session of the same user take over an instance whose holder is dead (or, with `force`, a stuck live one).
It re-stamps the holder in one write and reads it back, so of two sessions adopting at once only the last writer passes, and the loser's next `requireOwned` fails.
The task state was always in the instance (`~/.isx-mcp/tasks/<id>/`, now with each task's physical `cwd`), so adoption rebuilds the task registry from it and the ids keep working.
Task ids carry a random per-session tag rather than the pid, so they cannot collide with the adopting session's.
`max-instances` counts per user across sessions -- one listing per create -- including orphans, which are still using the machine; kept instances are the user's and never count.
`max-concurrent-tasks` counts per user the same way (#1015), and for the same reason.
The machine runs every task whichever process holds it, and a controller is one session running ten tasks, which a per-session cap sized for an editor stopped at once.
The truth about a task is in its instance (its files and systemd unit).
So a reservation asks each running instance of the user's that another session holds or that is orphaned, with one exec in parallel (`TaskScripts.busy()`), and asks nothing when there is none.
A kept instance counts while a live session still holds it (that session can go on starting tasks there) and stops counting with it, as for `max-instances`.
The count is taken again after a delegate's instance is branched, since other sessions may have started tasks meanwhile.
A host-side ledger lost: each session's own belief goes stale-high as soon as its tasks finish unobserved, and would block the others for nothing.
An instance that cannot be asked counts nothing, so it cannot block every later task; `max-instances` still bounds it.
Nor can one that never answers hold up every task start or pile up processes.
The probe runs isx's script as agentuser's uid without a login shell (`IncusClient.execProbe`), so a profile that hangs `su -` -- which an agent in the instance can arrange -- never runs; and it is a bounded exec (`execStreamWithin`, killed at ten seconds and given up on shortly after), so even a guest whose own binaries hang releases the host.
There is never more than one probe per instance.
One that did not answer in time, or answered but took more than half of it (a FIFO a loop feeds just in time would otherwise cost every task start nearly ten seconds), is not asked for ten minutes and counts nothing meanwhile.
Counting its last answer instead was tried and lost.
A count frozen for ten minutes goes stale-high and blocks starts for nothing, which is what asking the guest exists to avoid.
The undercount is the same one the timeout path accepts, and `max-instances` bounds it.
Every script the host waits on reads only regular task files (`TaskScripts.ifFile`), so a FIFO in place of a task file holds neither these probes nor an `adopt_instance`, `task_status` or `task_result` (#1035).
Checking the path before opening it is not enough, as a FIFO can be swapped in between (#1080).
The file is opened read-write, which never waits on a FIFO, and the type of what was opened is checked (`[ -f /dev/fd/3 ]`).
The path is still checked first, since a read-write open creates a missing file.
Writes never open the file they write: `launch`, `cancel`, every exit record and `exec`'s pid file write into a file `mktemp` creates in the same directory and rename it into place, which replaces a FIFO without opening it (#1074).
Bounding these execs instead was the alternative, and it would turn a stalled read into a slow one rather than none.
`task_diff` (and `ask` on a diff) is the exception that gets a bound as well (#1105).
Its own reads go through the same helper, `base.txt` and the copy of the index included, but git then opens the repository's files, and anyone in the instance can replace one of those with a FIFO.
So the diff runs under the guest's `timeout` (five minutes, which a diff against a copied index never needs), and one that does not finish fails as `unavailable` rather than holding the call.
The orphan sweep asks whether an orphan is in use the same way, and takes no answer as in use.
What the guest reports is counted once per task id, and the refusal names the instances the count came from, so a controller can see which one to look at.
Between one session's count (up to ten seconds old by its check) and another's launch, both can take the last slot, as with `max-instances`.
Both limits are a net against runaway creation, not a budget, and a host lock held across a guest exec is not worth closing that.
The defaults (8 and 8) are sized for that net, not for one editor's task or two; the only cost of a high number is host memory.

**Ownership is stamped by the copy.**
`BranchFlow.Request.extraConfig` rides the `POST /1.0/instances` copy request (Incus lays the request's `config` over the source's) and `configureBranch`'s one write, so an agent's instance never exists without its owner and costs no extra request.
Every tool that names an instance checks the session's registry *and* the stamp read back from Incus, which refuses a user's instance recreated under a name the session once used, and one another session has since adopted.
`configureBranch` drops copied `mcp-*` keys a caller does not re-stamp, so a user's branch of an agent's instance is never mistaken for an orphan.

**A person sees what the agent sees (#1053).**
`isx list` and the TUI's instance details say which instances a session made, what for, and whether they are `held`, `orphaned` or `kept`, so a person can tell an agent's worker from their own instance before destroying or entering it.
`McpStanding` decides the state with `list_instances`' rules (`Orphans.othersOf`, kept first), and a test holds the two together.
It reads nothing beyond the listing it is given.
A process session's liveness is a local `/proc` read, and a coordinator instance's is whether that instance is in the same listing with the same grant, so `isx list` still costs one request.
The TUI has no column for it, because the instance table has no width to spare, only rows in the detail pane.
An orphan a sweep stopped as dormant (#1028) is still `orphaned` there, as in `list_instances`; the detail pane says when it was stopped and that `mcp.dormant-grace-hours`, not the orphan grace, comes next.
`isx list --format=plain|json` gains `mcp_state` and `mcp_purpose` at the end, and its table gains an `MCP` column only when some instance has those stamps.
Human output flattens control characters in these stamps: `isx mcp` refuses them in a purpose, but `incus config set` does not.

**Orphans are quarantined, not reaped.**
A name is registered before its copy starts, so a session ending mid-create still covers it.
On end of input or SIGTERM a session destroys nothing: it stamps `mcp-orphaned` with the time on what it holds.
After a SIGKILL nobody stamps that, so the first later session to see the dead holder stamps it then.
Either way, the grace period starts when the orphan is first known, without a per-call heartbeat write (each instance write costs Incus a backup-file rewrite).
Each new session sweeps this user's orphans and destroys those past `mcp.orphan-grace-hours` (default 24: long enough to outlast a night's CI wait, short enough that forgotten instances do not pile up) -- never kept ones, never ones in a pending operation, never one being adopted, never one a person is working in, and never one whose delegated agent is still running.
That check is one exec: a probe (`Presence`) reading `/proc` for a process on a pseudo-terminal (an `isx shell`, including a tmux or zmx session left detached) or a Claude Code that does not carry the `ISX_MCP_TASK` marker of a task's own processes, and `TaskScripts.unfinished()`, which asks systemd about every agent task whose current run has no exit file.
Background commands do not count: a dev server never finishes, and would keep its orphan forever.
`Presence` deliberately ignores a task's own processes, so without the second half a delegate still working when its coordinator's grace period ran out was destroyed with its unpushed work.
A coordinator that never comes back leaves such an instance until the task ends, and the next sweep after that reaps it.
A running instance the probe cannot reach, or whose systemd cannot answer, counts as in use: what cannot be looked into is left for the user.
A *stopped* one is not probed at all: nobody can be in it and no task can be running, and counting it as in use leaked every orphan a session stopped before it died -- which `stop_instance` made routine (#1013).
Its status comes from the listing the sweep already makes, so the delete re-checks under its mark that it is still stopped: one started since (an `isx shell`) was never looked into.
An instance adopted while stopped cannot have its task records read; `start_instance` reads them, so their ids work again.
"Still running" means what systemd says.
A run cut off by an instance restart left no exit file, but its unit is gone, so it reads as over -- like a finished run, whose uncommitted work the sweep does not protect either.
A coordinator that wants that work adopts the instance within the grace period.
Adoption and the sweep can meet: the sweep runs as a session starts, which is when a restarted coordinator adopts.
So each writes its own mark before reading the other's.
The sweep, holding the instance's delete lock, stamps `pending-op: deleting` and then re-reads `mcp-session` (`InstanceDestroyer.deleteHeldIf`), backing off (and taking the mark back) if it changed.
`adopt_instance` stamps `mcp-session` and then reads `pending-op`.
Incus orders the two writes, so at least one side sees the other.
A mark after the stamp does not say which way the sweep went, so adoption waits (up to 30 s) for it to go and answers from what is left: gone, taken by another session, or held -- never reported adopted while being deleted, and never left stamped as this session's without being held.
A failed re-read under the mark takes the mark back, and taking it back is strict, since a mark left behind would leave the orphan busy for good.
The `mcp-orphaned` stamp names the session it was written for (`<time> <session>`): a sweep stamping a killed holder's orphan just as another session adopts it leaves a stamp for the old holder, which never starts the new holder's grace period.

**Dormant orphans are stopped, not kept forever (#1028).**
The rules above leave one case open-ended: an orphan "in use" with nobody coming back -- a delegate hung on a dead API call, or one waiting longer than anyone will.
It cannot be destroyed (the sweep cannot tell hung from waiting, and its unpushed work is on the disk), but keeping it running holds its memory forever.
So a third outcome sits between keep and destroy.
An orphan past its grace period that the sweep would keep *only* because a delegated agent has not finished (or systemd cannot say) -- never one a person is in -- is **stopped** once nothing in it has moved for `mcp.dormant-after-hours` (default 24), and stamped `mcp-dormant: <time> <session>`, in the shape of `mcp-orphaned`.
"Nothing moved" needs two quiet signals, both free of new machinery: the newest mtime among delegated agents' task files (`TaskScripts.agentIdle()`, in the same probe exec, as seconds by the guest's own clock), and the CPU time in the instance's Incus state (`IncusClient.cpuUsage`, one GET, no exec) compared with the sample the previous sweep recorded on the instance (`mcp-cpu-sample: <time> <cpu-nanos> <quiet-since>`).
The CPU counts as quiet while it grows by under 1% of one CPU between two samples (`Orphans.QUIET_CPU_SHARE`), taken at least an hour apart (`MIN_SAMPLE_GAP`, or the window if shorter).
Sessions starting seconds apart would otherwise hold the probes' own CPU against an allowance of milliseconds and keep a hung delegate forever, so a sweep that close to the last sample neither compares nor records.
More starts the window again, and so does a first look, a counter that went back (a restart) or a state Incus cannot give -- so a stop always needs two sweeps at least `dormant-after-hours` apart.
A delegate polling CI writes no events but burns CPU; one hung on a dead call does neither.
The stop goes through `InstanceBackend.stopIfHeldBy`, the guard the delete uses: mark `pending-op: stopping`, re-read the holder, then stamp and stop -- stamped first, since a stopped orphan without the stamp is destroyed by the next sweep, and taken back if the stop fails and the instance still runs, so a later stop by anything else is not given the dormant clock.
A dormant orphan is then destroyed without being looked into once `mcp.dormant-grace-hours` (default 168) have passed since its stop.
A stopped orphan without the stamp, or with one naming an earlier holder, is destroyed past its orphan grace period as before -- the dormant clock only ever adds time.
Stop rather than freeze: a frozen instance returns no memory, the resource that matters on the appliance VM, and a frozen Claude Code loses its in-flight API call on thaw anyway, so stopping is no worse and reuses `stop_instance`'s machinery.
`adopt_instance` starts a dormant instance again (`McpSession.wakeIfDormant`, with a progress notification and an audit line) before `Tasks.adopt` reads its tasks: any instance it holds and finds stopped with an `mcp-dormant` stamp -- its own, or one a sweep wrote for the previous holder just after the adoption's stamp -- and clears the stamp only once started.
Adoption itself clears `mcp-cpu-sample` but leaves that stamp, so a repeated `adopt_instance` on an instance the session already holds still does the start.
A start that fails is `unavailable`, with the instance held, and the retry `unavailable` promises is the same call.
`start_instance` and `stop_instance` clear a stamp left that way: once the holder has started or stopped the instance itself, a later adoption must not start what an agent stopped on purpose.
The delegate's run was cut off, so it reads as over, and `send_message` resumes its conversation from the recorded `session_id`.
The sweep's stop can outlast adoption's 30 s wait for its mark (the stop alone may take Incus's 30 s graceful timeout): reported adopted then, the instance would be stopped under its new holder with nothing saying so.
So an adoption that still finds the sweep's `stopping` mark after the wait holds the instance (it is stamped as the session's) but is refused `busy`, and the repeat starts it once the stop is done.
`list_instances` adds `dormant_since` and `dormant_until` to such an orphan, which stays `state: orphaned`.
Nothing an agent can call changed: the stop is the sweep's, an instance write like its delete.

**A person in the box is a task state.**
Taking over a worker's conversation (`isx shell`, then `claude --resume`) is how a person steers a design, so it must not race the coordinator's `send_message`, which runs `claude -p --resume` on the same session.
The task status script runs the same probe and reports the Claude Code that resumes the task's `session_id`, or works in its recorded `cwd` (where `--continue` or the resume picker would find the conversation), excluding processes that carry a task's `ISX_MCP_TASK` marker.
`task_status` then says `attached`, and `send_message` refuses.
The check is made when a message is sent, so a person joining in the following seconds is not seen; the alternative was every client racing its own `pgrep` before each wake.

**Results stay small.**
The coordinator's context is the scarce resource.
`task_result` returns the report capped (`max_bytes`, default 16 KB) and the event tail only on request.
`get_diff(stat)` is `git diff --numstat` plus `--shortstat` per repository, small and exact, which is what a coordinator needs to keep new dispatches from overlapping work in flight.
`ask` on `exec`, `task_result` and `get_diff` goes further.
The text is produced in the instance and piped, there, into `claude -p --model <summary-model> --tools '' --no-session-persistence` in an empty directory with the question, and only the answer (plus what was read: lines and bytes) comes back.
A fresh one-shot rather than the worker's session, which would spend the worker's context and inherit its framing; no tools, because the text is untrusted and may carry instructions; the default model is the alias `haiku`, so a template whose Claude Code maps models elsewhere (Vertex, Bedrock) is followed.
The answer is as untrusted as any output and lossy besides, so the tool descriptions say never to gate a merge on it.

**Fewer calls per tick.**
`wait_any` blocks until any of several tasks finishes, probing each instance once per two seconds, instead of the coordinator polling N tasks in turn.
`delegate(skill, args)` runs `/<skill> <args>` so that the brief lives in the instance, versioned with the template, and the coordinator sends a name.
The delegate's permission mode is always passed explicitly (`--permission-mode`, from `mcp.delegate-permission-mode` or its per-template override, default `bypassPermissions` as isx's managed settings already set).
A headless agent that meets a permission prompt has nobody to answer it.
The refusals it does meet are surfaced from the result event's `permission_denials` in `task_status` and `task_result` instead of failing silently.

**Activity from the proxy (#898).**
"Working", "stuck" and "finished but never reported" look alike from outside: a task is `running` in all three.
Asking the instance costs an exec and reads what the agent says about itself.
The proxy already sees every model call and knows which instance made it, so `instance_activity` asks the proxy instead -- VISION.md's "the proxy is the control plane" applied to its first consumer.
`MitmProxy` counts, per instance, the Messages API creates (`/v1/messages`, and Vertex `:rawPredict`/`:streamRawPredict`; not `count_tokens`, batches, settings or telemetry, which an idle Claude Code keeps sending): requests made and in flight, when the last one started and ended, and the tokens their 2xx responses report in `usage`.
The usage is read off the relayed stream (`ApiActivity.UsageTap`, one SSE line held at a time, a plain JSON body up to 4 MB).
A stream reports it in `message_start` and, cumulatively, in `message_delta`, so each field keeps its largest value rather than summing.
Those calls are sent upstream with `Accept-Encoding: identity`, since a compressed answer would hide its usage; a response compressed anyway is relayed and not counted.
A call ends once, on the client response's end or close, so a failed or abandoned call never stays in flight.
The proxy serves the counts as `/activity` on the health port, to host callers only (`isHostCaller`, as `/health`'s pid), a container getting a 404: what its neighbours do is not its business.
Counts live in memory, so every way they can start over must be visible to a client subtracting two reads.
`counting_since` is therefore per instance -- when the proxy created that instance's counters, at the first read or call after it learned of the instance -- not the proxy's start.
A read drops the counters of an instance the registry no longer lists, so those of destroyed instances do not pile up, and the registry can also miss a live one for a moment (an address two instances claim, a listing entry that failed to parse).
A proxy-wide `counting_since` would let a dropped instance's counts start again from zero under an unchanged stamp, and a before/after subtraction would read a wrong or negative spend (found in review of #1052).
With it per instance, counters made again carry a later stamp.
Since only idle counters are dropped -- atomically per key with `begin`, so a call never lands on counters the map no longer holds -- `requests_in_flight` is never undercounted.
A task's spend is the difference of two reads with the same `counting_since`, and nothing else: a read creates counters for every instance the registry knows, so a known instance always carries one.
An earlier rule that also let a first read without one count as zero was unsafe -- counters created after it could be dropped and made again before the second read, and the subtraction would undercount without a sign.
Dropping on a read is also not enough on its own: a hand-named instance destroyed and branched again before anything reads `/activity` would carry on from the old one's counts under its stamp (#1063).
So counters also belong to one incarnation of a name, the instance's Incus `created_at`, which the registry already reads in its listing and keeps beside the address (not in `InstanceAccounts`, which the proxy caches by value).
A call or read naming another incarnation starts fresh counters (and `requests_in_flight` counts that incarnation's calls only).
A call's incarnation is read in the same registry lookup that names its instance (`InstanceRegistry.resolve`, carried in `RequestContext`), so a call is never paired with another instance's `created_at`.
A call and an `/activity` read can still see different snapshots, so each listing the registry parses gets a higher *view*, and counters never go back to an earlier view.
A call identified before the name changed hands is counted nowhere, and a read from an older listing neither replaces nor prunes counters a newer one made, or the two would reset them in turn.
Views, not `created_at`, order them: a rename keeps the instance's own `created_at`, so a name can pass to an instance created before the one that had it.
Per-task baselines kept by the session were rejected: they would not survive the adoption that #898 exists for, and the subtraction is the client's to do.
The contract can grow additively (a `task_id`, other domains) without changing what is there.

**Structured results for programs (#1010).**
A daemon driving `isx mcp` is a client that is not a model, and it should not have to parse a sentence to find a task id or a state: the wording of those sentences changes in nearly every follow-up.
So every tool declares an `outputSchema` in `tools/list` and returns a matching `structuredContent` object (MCP 2025-06-18), while the text the model reads stays as it was.
The five tools that already answered in JSON print the structure itself, followed by guidance that is not part of it.
Field names are shared across tools (`instance`, `template`, `task_id`, `state`, `exit_code`, `run`, matching the `isx/task_changed` notification).
One word means one thing everywhere: a task's `state` is always from `OutputSchemas.TASK_STATES` (the notification adds `released` and never says `unknown`; `McpOutputSchemaTest` pins both), including a task entry in `list_instances`, which gives the state as the session last saw it -- `unknown` when nothing read how it ended, as after a `cancel` (a boolean `running` there would have been frozen at release: once the session knew more, saying it would take a second field for the same fact), a no-op lifecycle call says `already: true` (`stop`, `start`, `destroy`), `max_turns` is the budget the run actually gets (the ceiling applied) in every tool, and a field isx cannot fill truthfully is left out rather than given a placeholder.
Every schema object is closed, and the schemas live in `OutputSchemas`, built only when `isx mcp` lists its tools.
They are a contract, so `McpOutputSchemaTest` keeps a golden copy (a change is a deliberate diff), compiles each one against the 2020-12 metaschema with networknt's validator -- the one the official MCP Java SDK uses -- and refuses a `$schema` naming another dialect, since clients dispatch on it.
`StructuredResults` validates every result the tool tests produce, and one test drives every tool.
A refusal carries **no** `structuredContent` at all.
Its `{code, message, instance?, task_id?}` goes in the result's `_meta` under `dev.incusspawn/error`, with `code` from a small `ToolError.Code` set (`invalid_argument`, `not_approved`, `not_found`, `not_held`, `wrong_state`, `busy`, `limit`, `unavailable`, `task_failed`, `refused`, `internal`), which every throw site names -- there is no default, so a new refusal cannot go unclassified -- and an `IncusException` escaping a tool is `unavailable`, not `internal`.
`busy` also answers a call repeating one with the same `idempotency_key` that is still under way, `not_held` one whose instance another live session holds or is still making, and `invalid_argument` a key repeated for something other than what it made (#1011).
`unavailable` also answers an `adopt_instance` that took a dormant instance but could not start it again, and `busy` one that took an instance the orphan sweep is still stopping (#1028).
The instance is held then, either way, and the retry is the same call, which starts it.
`unavailable` and `busy` are the codes that may succeed on a retry as is (not every `unavailable` does: an instance that cannot do what was asked fails the same way again).
A `create_instance` refused by `BranchFlow.preflight` (a missing credential, a template built with a different CA, the name in use, the proxy down) is `refused`, since the user has to act first, as is an `instance_activity` asked of a proxy older than `/activity` (`ProxyActivity.Outdated`), which answers the same until restarted.
Do not move it into `structuredContent`.
The 1.x TypeScript SDKs validate `structuredContent` against the tool's output schema even on an error result (2.x and the Python SDK do not), and Claude Code ships both generations, so an error object there would turn every refusal into a client-side failure.
`get_diff` reads `git diff --numstat -z --no-renames`, so no file name can forge a record and a rename reports both paths it touched.
Its text is rendered from the parsed structure, with a path holding a control character C-quoted as git quotes it (the structure keeps it verbatim), and `ask` hands the in-guest model git's own quoted, line-based `--numstat`, so no name forges a line in either.
`task_status` adds when the run started and last wrote output, from the mtimes of its `current` and output files in the same exec.

**Told, for a client that asks (#1014).**
A daemon driving `isx mcp` would rather be told than hold a `wait_any`: `notifications/isx/task_changed` (`{task_id, instance, run, state, exit_code?}`, state `running`, `finished`, `lost`, `attached` or `released`) is sent on every transition.
Server and client both list `isx/task_changed` under `capabilities.experimental` to switch it on; it starts only once the `initialize` response is out.
A client that does not ask -- Claude Code -- gets nothing and costs nothing.
The server only learns task states when asked, so notifying means polling, and an always-on poller would spend an exec per instance every two seconds on messages that are dropped.
`TaskWatcher` keeps the last state it reported per task and the paths that learn one for free feed it (launch, `task_status`, forgetting an instance), so a transition is reported once whichever path saw it first.
The poller fills in the rest, including a task never reported (adopted) or last reported running (cancelled since): an extra exec in adoption would only say it two seconds sooner.
A cancel must not read as a loss on the way.
It stops the unit, sweeps the task's processes for two seconds and only then records exit 143, and a unit that is inactive with no exit file is otherwise `lost`.
So `cancel` stamps `cancelling-<n>` before the stop.
Every state read calls the run `running` while that stamp is under five minutes old -- still true, its processes are being killed, and the stop alone may wait systemd's 90 s -- so a poll landing in the sweep reports nothing and the client hears one `finished 143`.
A stamp older than that (or from the future) is a cancel that died midway, and the run is lost after all -- once the exit file has been looked at again, since a cancel records its exit before removing its stamp and may finish between a reader's two checks.
Every state read means `status()` too: it now prints the guest's own `RUN_STATE` rather than the raw unit state for Java to interpret, so the rules are written once.
A cancel is also the one path that reports at once, and costs nothing: the watch script rides the cancel's own exec, because `stop_instance(force)` stops the instance straight after and a stopped instance cannot be asked.
An instance the poller cannot exec into (stopped behind the session's back, unreachable) is left until the next slow round rather than costing a failed exec every two seconds with `TaskScripts.watch()` (one exec per instance, re-reading the exit file after asking systemd so a run that just ended is never called lost, and one `Presence` scan only when a finished agent is among the tasks): tasks last reported running every two seconds, as `wait_any` does; every fifth round, finished agents (a person may join or leave) and whether the session still holds each instance, so a set of idle tasks does not become a steady stream of execs and Incus reads.
`unknown` is never reported, as it never changes a recorded state.
A task whose instance is gone is `lost` if it was running (a finished task lost nothing).
One whose instance another session adopted is `released`, whatever its state, because its id no longer works here and calling it lost would claim the run died.
Which of the two is not asked of Incus when the session has already let go.
Every place that drops an instance knows why (`destroy_instance` and a 404 mean gone, another session's stamp means adopted) and the session remembers it, since by the time a poll round asks, an instance destroyed a moment earlier would read as merely not held.
Either is the last word for the task: the watcher tombstones its id, so a `task_status` or poll that read the task just before and reports just after cannot tell the client the session still has it.

**The agent gets `isx branch`, not options.**
Instances are created through `BranchFlow` with `Request.defaults()`: the template's network mode, account pins, KVM and resource defaults, no GUI, no inbox.
Template approval lives in the `mcp:` section of `config.yaml`, read on every call; a template must be listed, not built from a project-local definition (`BuildSource.usedProjectLocal()`), and built.
Definitions come from `ImageDef.loadTrusted()`, which leaves out the working directory's `.incus-spawn/`.
`isx mcp` runs in whatever repository the agent was started in, and a definition there must not reach an agent's instance even by overriding a parent of an approved template (which would decide, among other things, which credential accounts it gets).
Nothing in the package can write config or definitions; `McpNoWritePathTest` pins that at the source level, so widening it means deleting a line of that test.

**Forks: prepare once, try N things (#1013).**
A review that builds a PR once and then runs several specialist reviewers wants each in an identical, warm environment -- the built checkout, the primed `~/.m2`, the compiled `target/` -- without rebuilding per specialist or sharing one `target/` and one failure domain.
`create_instance(from_instance)` is `isx branch --from <instance>` through the same `BranchFlow`, which already resolves a branch of a branch: the leaf template from `PROFILE`, the source's account pins on top of the template's, credentials checked against the source's recorded build.
So the trust boundary does not move: the source must be held by this session, its lineage must still be approved and trusted (`TemplatePolicy.requireLineage`, which, like adoption, does not need the template built: a fork copies the instance, not the image), and network mode and accounts are still never arguments.
The source must be **stopped**.
A running container can be copied, but only crash-consistently (a half-written `target/`, a live lock in `.m2`), and a running VM not at all.
So `stop_instance` and `start_instance` exist, and `stop_instance` is refused while a task runs unless `force`, which cancels the tasks first so none stays recorded as running in an instance nobody can ask.
Having the fork stop and restart the source by itself would pay a restart per fork and hide a state change from the client.
A fork is stamped, counted against `max-instances`, orphaned and reaped like any instance; `configureBranch` drops the `mcp-*` keys it copied, so a fork of a kept instance is not kept.
Two things a copy carries had to be undone: the guest's `~/.isx-mcp/tasks/`, which would let adopting the fork claim the source's task ids, is cleared with one exec after the start (a fork whose tasks cannot be cleared is removed); and the template of an MCP instance is read from `PROFILE`, not `PARENT`, which for a fork names the source instance and made forks impossible to adopt.

**Idempotency keys: repeating a call is safe by construction (#1011).**
A controller recovers from a crash by re-running every intent with no recorded result.
So it needs "did my `create_instance` happen before I died?" answered by the thing it drives, not by a lookup by `purpose` whose race and convention it owns.
`create_instance`, `delegate` and `exec(background)` take an optional `idempotency_key`.
A call whose key already made something gets it back -- the original result shape, rebuilt from what Incus and the task's files hold, plus `replayed: true` -- instead of a second one.
- *The key lives exactly as long as what it made.*
  On the instance it is `user.incus-spawn.mcp-idempotency-key`, stamped by the copy request with the other ownership stamps: host-side config the guest cannot change, and no request of its own.
  On a task it is `~/.isx-mcp/tasks/<id>/key`, which `TaskScripts.list()` reads back, so it survives a server restart, a session replacement and an adoption.
  A host-side ledger would also remember keys past a destroy.
  But the only thing it could add is "that key made an instance that is now gone", to which a controller's right answer is to create again -- which a forgotten key does.
  It would be host state that `McpNoWritePathTest` forbids and that would need reconciling with Incus.
  Every destroy is deliberate (the controller, a person, or the orphan sweep, which already defines an abandoned instance as gone with all it held).
- *Scope.*
  An instance key is matched among the host user's instances (`mcp-owner`).
  A task key is matched only among the session's tasks, and a guest-written key only answers for a call naming its own instance.
  The call that starts a task names the instance anyway, and a user-wide match would let one instance's agent plant a key and capture a keyed `delegate` meant for another.
  `delegate(template)` gets user-wide scope through the instance stamp.
  A replay is decided before the call is compared with what the key made (`McpSession.replayable`): one still being made -- this session's create under way (`busy`), or a live session's (`not_held`, pointing at `adopt_instance`) -- cannot be compared yet, because a copy carries its source's config, its parent included, until it is configured, and a mismatch's advice to use a new key would make a second instance.
  One this session holds is returned as it is.
  An orphan, or one its holder released, is adopted with all of `adopt()`'s checks, tasks included (a running instance whose tasks cannot be read is let go of again and the call refused as `unavailable`: a repeated `delegate` would not find its task and would start a second agent).
  A kept one is refused.
- *The same key asking for something else is refused, not redirected:* another template or source, another instance, a command where an agent was started.
  `name_hint` and `purpose` only describe, so a retry that rewords them still replays -- the reason not to hash every parameter, Stripe-style.
- *Two calls in one session* cannot both make one: the key is registered with the reservation, under its lock, and a second call while the first is under way is `busy` ("call again"), never a duplicate.
- *Two sessions* can: a crashed session's copy that Incus finishes after a new session looked, or two live ones racing.
  A keyed create therefore looks again once its copy exists, and of two instances under one key the one Incus recorded first stays; the other destroys its copy and replays it.
  The order is Incus's `created_at`, then the name, the same comparison everywhere one is chosen (`McpSession.FIRST_MADE`).
  Measured against Incus 6.23: a copy is listed, with the config its request stamped, from the moment its record exists -- while its files are still being copied -- and `created_at` is set then and never changes.
  So if only one of two racing creates sees the other, it was made after the other looked, it is the later of the two, and it gives way: exactly one of them destroys its copy.
  `user.incus-spawn.created` cannot serve: a copy carries its source's until `configureBranch` writes its own, and that value is taken before the write that a reader would see.
  A copy whose session died before `configureBranch` (no `static-ip`, which a copy never carries from its source -- the copy request unsets it -- and which only `configureBranch` gives; never started) is not what a create promises.
  So it never answers for its key, and the orphan sweep removes it.
- *Partial intent.*
  `delegate(template)` whose keyed instance exists without its task (the call was cut off between the two) starts the task there, finishing the intent instead of making a second box.
- *Forks.*
  A copy brings its source's `user.*` config, the key included, and `configureBranch` drops the `mcp-*` keys it did not stamp only once the copy is already listed.
  So `BranchFlow.create` applies the same rule in the copy request itself: every `mcp-*` key of the source the caller does not stamp gets an empty value, which Incus takes as removing it.
  That covers every ownership key, not just this one, at no extra request.

**Exec has no server-imposed time limit.**
How long a build may run is the controlling agent's call: `timeout_seconds` is optional with no default.
What matters is that a command nobody waits for does not keep running: `exec` runs the command in its own session (`setsid`) and records the session id, and a cancelled call kills that session's process tree from a second exec.
Output is bounded to the tail (`TailBuffer`), which is about the size of a tool result, not about how long a command runs.

**Tasks run as systemd units in the guest.**
`exec(background)` and `delegate` start a transient unit (`sudo -n systemd-run`, each run `isx-task-<id>-<n>`) writing into `~/.isx-mcp/tasks/<id>/`.
They outlive the call, the connection and the session's process; only destroying the instance ends them, and a person can inspect one with `isx shell`.
The files and the unit are the task's state: nothing about a task is kept on the host beyond which session owns it.
Instructions and prompts arrive on stdin and run scripts as base64, so nothing an agent sends is interpolated into a shell.
The unit's cgroup is not where the run ends up: `su -` goes through PAM, which moves it into a user session scope, so stopping the unit left a delegated `claude` running (seen on a real instance).
Every run therefore exports `ISX_MCP_TASK=<id>`, inherited by all it starts, and `cancel_task` signals every process carrying it after stopping the unit.
Unit state is read through `sudo` too: an unprivileged login session in a container cannot reach systemd's system bus, and read directly every running task looked lost.

**Delegation.**
The inner agent is `claude -p --output-format stream-json`, resumed with `--resume <session_id>` for each `send_message`.
The appended brief frames the role -- a delegate reporting to a coordinator -- and forbids nothing: what a delegate can reach is bounded by the credentials the template's proxy account carries, what it does by the instruction.
That is what makes "fix it, don't push" -> review `get_diff` -> "push and open the PR" a controlled flow rather than a prompt-level honour system.
`get_diff` compares against the commit each repository was at when the task started, using a throwaway index so untracked files are included and the instance's own index is untouched.
One agent per instance, because two would clash in one working tree; `delegate(template=...)` gives parallelism instead.

**Per-task profiles (#1012).**
A coordinator spends tokens per kind of work -- a small model and a short budget for a rebase, a large one for a design -- so `delegate` and `send_message` take `model` and `max_turns`, passed as `--model` and `--max-turns`.
Without them nothing changes: no `--model`, so the template's `settings.json` model applies (`list_templates` shows it as `delegate_model`: the `model` parameter of the nearest layer listing the `claude` tool, which the build reconfigures with that layer's parameters alone), and `mcp.delegate-max-turns`.
The alternative, a template per model, made every profile a build.
Three bounds:
- *The budget is the user's.*
  `max_turns` may narrow `mcp.delegate-max-turns`, never exceed it, and a budget recorded under a higher ceiling is held to a lower one set since -- the same rule as everything in `mcp:`, which an agent can narrow and never widen.
- *The model is the account's.*
  A model the template's credential cannot use must fail the call, not the task, which would cost a branch and a poll to discover.
  The check (`ModelCheck`) is a one-turn, tool-less `claude -p --model <m>` in the instance, through its proxy account: the same path the task takes, so it answers alike for a model id, an alias (`haiku`, `opus[1m]`) and a Vertex account's naming.
  A host-side list or the Models API would cover neither aliases nor Vertex.
  It costs one tiny request per (template, account, model) per session -- the account being the one the instance's pin, or the configured default, resolves to at the call, so a default changed meanwhile is checked again; a success is remembered, a refusal is not, since the user may fix the account -- and nothing when no model is chosen.
  It runs once the task's slot is reserved (a refusal gives the slot back), so a call refused anyway -- a busy task, a full session -- spends no request, and under `timeout`, since Claude Code retries an overloaded API for minutes while the call waits.
  A fresh instance's pin is the one its branch stamped (`CreatedInstance.accounts`), not read back from Incus.
  On `delegate(template=...)` the fresh instance is destroyed with the refusal, as for any failed start.
  The cache is per template, not per instance.
  Instances of one template share its Claude Code, except one branched before the template was rebuilt with another version, which could resolve an alias differently.
  A pass on the newer one then spares the older one its check, and a model it cannot use fails in its task.
  Keying on the instance would re-check every fresh `delegate(template=...)` instead, the case the cache exists for.
  A chosen model stays chosen for the task's later turns: Claude Code has no flag meaning "the template's own", so going back to it is naming it or a new task.
- *The permission mode is not part of it* (#858): what a delegate may do stays the template's.

The model passes `McpConfig.isModelName()` (also what `summary-model` must match; it starts with a letter or digit, so it cannot read as an option) and is shell-quoted.
A task's profile is recorded with the task, in `~/.isx-mcp/tasks/<id>/model` and `max-turns`, where all its state lives, and `TaskScripts.list()` reports it, so an adopting session keeps it.
Read back from the instance, it is taken only if it is still a model name and a positive number.
Once adopted, the recorded profile is advisory: the delegate could have rewritten those files, so a later turn that names no model may run on one the coordinator did not choose, shown by `task_status` as the task's.
A turn that names one is always checked, even when it names the recorded one (the account may have changed too); the cache makes the repeat free.
That is no escalation -- the ceiling still clamps every run, and the guest can run `claude --model` itself through the same account.

**A coordinator in a box (#915).**
The coordinating agent runs untrusted text all day -- reports and triage lines come out of the boxes it drives -- and on the host it acts with the user's shell and the user's `gh` login.
So it may run in an isx instance instead, holding only that instance's credentials (a bot's), with the MCP tool list as its only reach into the host.
The pieces, and why each is the way it is:

- *Who may call: a stamp the user sets.*
  `isx branch --mcp-client` stamps `user.incus-spawn.mcp-caller` on the branch it makes (`mcp-client` was the name proposed, but that key already holds the client's display name).
  It is an `mcp-` key, so `configureBranch` drops it from every copy: `isx branch --from coord` makes an ordinary instance, and so does an agent's fork.
  The MCP create path refuses it outright (`IncusInstanceBackend.branch`), so a coordinator cannot make another one.
  Like account pins it is the user's act, never an agent's: a guest can read its own `user.*` keys, never write them.
- *How a caller is known: address and secret.*
  The proxy already identifies instances by source address (`security.ipv4_filtering` makes that trustworthy), and #934 added the per-start secret for exactly this kind of host-side service.
  `InstanceRegistry.identifyMcpCaller()` passes only when the address, the secret in `X-Isx-Instance-Secret` and the stamp all name one instance, read from the listing the proxy makes anyway.
  A refusal is decided before the body is read, and a refusal right after a start is worth one throttled refresh, as for `identify()`.
  A bearer token issued to the box (#859's sketch) was rejected: it is a secret the box would hold for good, where the start secret is useless from any other address and dies with the start.
- *Where it is served: by the proxy, as a name.*
  `mcp.isx.internal` is a built-in intercepted domain, so bridge DNS sends it to the gateway, the 443 redirect to the proxy, and it works in proxy-only mode, whose rules allow only the gateway.
  No new port, and no new service to install on either platform.
  `.internal` is reserved for private use, so no real host answers to it; the proxy answers it itself and never relays it.
- *What serves it: a host `isx mcp` per instance.*
  The MCP server lives in the CLI, which the proxy (a separate binary, the only one with Vert.x) cannot run in process; moving it would move most of `cli` into `common`.
  So `McpBridge` speaks MCP's Streamable HTTP and runs `isx mcp --caller-instance <name>` per session, bridging each POSTed message to a line on its stdin and each line back to the POST that asked, by JSON-RPC id.
  Anything else (notifications) goes to the GET stream.
  The `isx` is the one beside `isx-proxy`, else `~/.local/bin/isx`, else PATH.
  The child re-checks the stamp before serving.
  A request is answered as an event stream with a comment every 30 s, below the MITM server's 120 s idle timeout, because `exec` has no time limit.
- *One process per instance.*
  A new `initialize` from an instance ends its previous process first (SIGTERM, then SIGKILL after 10 s) and only then starts the next.
  Two processes serving one instance session would each count only the instances in their own registry against `max-instances`: a coordinator could multiply its cap by opening connections.
- *The session is the instance -- this one, not its name.*
  `mcp-caller` holds a random grant id (`Metadata.newMcpCallerGrant()`, 128 bits), and `SessionId.ofInstance()` stamps `instance:<name>:<grant>` as the holder.
  The name alone would let a coordinator destroyed and recreated under the same name take over its predecessor's workers: hold them again, stop their orphan clocks and count them against its cap.
  Incus's own `volatile.uuid` would do as well but is not among the `user.*` keys every read here already carries; the grant costs nothing.
  It is not a secret (the guest can read it), since who calls is settled by address and per-start secret.
  The session is alive while the instance exists and carries that grant (`CallerLiveness`: one read per instance, remembered 5 s; a failed read counts as alive, so it never orphans anything) -- running or stopped, because a coordinator box being restarted has not let go of its workers.
  So nothing is released when a connection ends (`release()` does nothing for it), and a new connection takes back what the instance holds (`McpSession.resume()`, one listing before the first tool call so the cap counts them, then the running ones' tasks in the background) -- only what `adopt` would take.
  An instance whose template is no longer approved is released instead, its `mcp-orphaned` stamp naming the session (`Orphans.releasedByHolder`), which every session counts as an orphan though the holder lives on.
  So withdrawing approval reaches a coordinator as it does a host session that ends.
  Once the coordinator is destroyed or its stamp removed, its workers are orphans like a dead process's, and the proxy ends its session at the next keepalive tick.
  Older isx versions read an `instance:` stamp as unparseable, which they treat as held.
- *How its Claude Code finds the server: registered from the stamp on every start (#1182).*
  Branching with `--mcp-client` is the only per-instance act.
  The exec that delivers each start's secret (`InstanceSecret.GUEST_SCRIPT`, root in the guest) also gets `ISX_MCP_CLIENT` (`InstanceSecret.guestEnv(secret, mcpCaller)`), which every start path reads from the instance it already holds, so it costs no request.
  `McpClientRegistration` compares it with a root-owned marker in the rootfs (`/var/lib/isx/mcp-client-registered`, holding a digest of the entry it registered, `ENTRY_VERSION`, so an isx that changes the entry re-registers existing coordinators on their next start), and checks that Claude Code's own config still holds the entry (`CONFIG_HAS_ENTRY`, so an entry the agent removed comes back; root reads that agent-owned file through `timeout -k 1 2 head -c 16MiB`, since the agent may swap in a FIFO or a vast sparse file and the readiness exec has no time limit of its own -- a read cut short answers "no entry", which costs a re-registration, never a hang).
  A copy gets one removal attempt, whatever it answers: an entry it cannot remove reaches nothing without the stamp, and retrying it would cost every later start; only when either says otherwise, runs Claude Code's own `claude mcp add-json --scope user isx` or `claude mcp remove --scope user isx` and updates the marker.
  So the registration is the standard one (`claude mcp list` and `/mcp` show it, and every way of launching Claude Code finds it), a steady-state start runs no Claude Code at all, and a reboot isx did not do changes nothing.
  Nothing is inherited for good: a copy of a coordinator carries the registration until its first isx start, which a branch always is, removes it.
  Only `1` switches it on; a missing or unknown value removes, so a start path that forgot to say would fail closed.
  `claude` lives in the agent's home, where the agent can replace it, so the script runs it as the agent and never as root; the marker is root's, so only the script decides what isx believes it registered.
  The registration is not the capability -- the proxy still checks address, secret and stamp -- so one left behind or added by hand reaches nothing.
  The secret rotates every start while the registration is written once, so the entry carries a `headersHelper` that reads `/run/isx/instance-secret` by its absolute path: a `claude -p` under a systemd unit never read the login profile that names it.
- *The playbook is served, not installed.*
  `isx mcp` offers the coordination workflow (create, delegate, `wait_any`, `get_diff`, review, destroy; limits; orphans) as an MCP prompt, `coordinate` (`McpPrompt`), which Claude Code shows as a slash command; `INSTRUCTIONS` stays the tool contract every client reads.
  A skill file in the rootfs was rejected: copies would inherit it, and it would drift from the server it describes, where a prompt exists only where the server is reachable and changes with it.
  `McpPromptTest` fails if it names a tool the server does not have.
- *The branch checks the result.*
  After the start, `isx branch --mcp-client` runs one `initialize` and one `tools/list` from inside the guest against `mcp.isx.internal` (`McpClientCheck`: curl, with the secret in a root-only header file, never on a command line), ends that session, and prints the server's version and tool count or why it was not reached -- and whether Claude Code's own user config (`~/.claude.json`) holds an entry for the endpoint, by the same `CONFIG_HAS_ENTRY` test the reconcile makes, read as a file because `claude mcp get` connects to the server and can hang.
  A reachable endpoint with no registration is the very failure #1182 was filed for.
  What the guest returns (curl's errors, the server's version) passes through `OutputFormat.oneLine` before it is printed.
  It is a warning, never a failure: the branch is made either way, and the user sees a coordinator that cannot work while still there to fix it.
  One exec, only for `--mcp-client` branches.

The tool contract is the stdio one, unchanged: the same tools, approved templates, `mcp:` limits and audit log, the session's host-side stamps (`mcp-cwd`, `mcp-client-pid`) simply absent.
Out of scope: gating outward actions such as pushes at the proxy (the complement to this), and reaching Incus on another host.

### Native image CPU baseline

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
ADX buys nothing on this path, so there is no reason to accept its narrowing (it would drop Intel Haswell 2013-14 and AMD Excavator, which run v3 code).

Two things that look like they should help and do not, both measured:

- **`-H:RuntimeCheckedCPUFeatures` does not cover the crypto intrinsics.**
  Adding `AES,CLMUL` to a v3 build left throughput unchanged, and adding the AVX-512 set to a build with AES recovered only ~1 of the ~11 points `native` holds.
  The AES-GCM intrinsic is selected at build time from `-march`; runtime dispatch applies to a narrower set of operations, and its AMD64 default is already `AVX,AVX2`.
  Note the option *replaces* that default rather than extending it, so anything added must re-state `AVX,AVX2`.
- **Raising to `x86-64-v4`** buys nothing and costs all non-AVX-512 hardware.

### aarch64: `+aes`

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

### Why the CLI takes the same flag

The CLI downloads tool tarballs and VM images over HTTPS (`DownloadCache`, `SkillsCache`, `VmManager`), all through the JDK's `HttpClient`, so it pays the same software-AES cost.
Measured directly on AES-256-GCM in a native image from this toolchain: **79 MB/s at `x86-64-v3` vs ~3100 MB/s at `haswell`** — a 39x difference.
At 79 MB/s a 384 MB tool tarball costs ~4.9s of CPU on crypto alone, so any link faster than ~630 Mbit/s makes the CLI crypto-bound rather than network-bound.

The CLI's build is tuned for size and startup (`-Os`, serial GC), which is why `-O3` was rejected there at +131% size.
`-march=haswell` costs neither: the binary is **byte-identical** (32,115,704 B either way) and startup is ~11% *faster* (median 3489 vs 3924 us over three interleaved rounds of 40 runs).
The startup gain is unexplained; `haswell` adds CLMUL over `x86-64-v3` alongside AES, which backs the CRC32 intrinsic, but that is a hypothesis.

Reproduce with `bench/run.sh --load=maven`.
Use that harness rather than a shell loop of `curl`: process-spawn overhead caps such a loop around 550 req/s, which silently pins every build faster than v3 to the same wrong number and hides the differences between them.

### Proxy garbage collector per platform

**Chosen:** G1 on Linux, serial on macOS (both architectures).
`proxy.native.gc` in `proxy/pom.xml` is `serial`, and the `linux-g1` profile sets `G1`.

**Alternative:** G1 on macOS aarch64 too.
Native Image has supported it there since Oracle GraalVM 25.1, and the Apple Silicon release job builds with Oracle GraalVM 25.4, so nothing prevents it.

**Why not:** it was measured on Apple Silicon (#1140) and buys nothing.
Three interleaved rounds of `bench/run.sh`, same commit, Oracle GraalVM 25.4.4.1.1: 118 req/s for both collectors on `--load=maven`, and 53,900 (serial) against 53,700 (G1) on `--load=saturate`, a difference inside the noise.
G1 costs 9.0 MB of binary (+12%), 5.4 MB of idle RSS (+13%) and 6 to 12 MB more after load.
A larger G1 heap (`MaxRAM=512m`, 128 MB) changes nothing either, so `MaxRAM` stays 256m on every platform.
G1 on macOS is also an Oracle GraalVM feature: `native-image` from GraalVM Community Edition, which is what Homebrew's `graalvm` formula installs, stops with "'G1' is not an accepted value".
So `install.sh --native` would fail on such a Mac. macOS x86_64 has no choice in any case: GraalVM dropped it after 25.0, before G1 reached macOS.

The figures and their conditions are in `docs/PERFORMANCE-NOTES.md`, "G1 on Apple Silicon".

### The minimum macOS is set by the build

isx supports **macOS 15 (Sequoia) or later**; macOS 14 and older were dropped by decision (#1086).
Apple Silicon is supported, and the bot's Apple Silicon Mac (the `mac-runner` agent, not a CI runner) verified this change on it.
Intel stays among the release artifacts as **best effort**.
It is built on GitHub's `macos-15-intel`, linked for the same minimum, smoke-tested there and checked like the rest, but not verified on an Intel Mac (`isx vm`, the proxy service), as no Intel Mac is available to the project.

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
`WorkflowRunnerLabelsTest` fails if `MACOS_MIN` is not the target's Homebrew name, if a release runner is older than the target, or if `release.yml` no longer runs the check over `artifacts/native-macos-*/*`.
That the glob matches what `gh release create` uploads rests on the artifact layout (each macOS artifact is one build's `dist/`), not on the test.
A runner newer than the target is fine: it builds for the older version.

There is no runtime check in `isx`: on an older macOS, dyld refuses the native binary before any of its code runs, so such a message could never print.
`install.sh --native` refuses on an older Mac instead (reading the pom's value), where it would otherwise build a binary its own host cannot load; its JVM install is left alone, since that still runs there, unsupported.

Raising the minimum is changing the pom value, `MACOS_MIN` and the docs that state it (README, `docs/HOMEBREW.md`); the tests name `MACOS_MIN` if it is left behind, not the prose.

### CLI latency baseline: native vs JVM

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

### CLI latency on macOS: the vsock tunnel is not where the time goes

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

### VM boot: where the wait goes

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

### Why nothing is pushed into an instance just before it starts

`bench/trace-branch.sh` showed a full second of every 2.2 s branch spent idle inside Incus's start operation, right after it logged "Stopping forkfile".
Forkfile is the helper Incus runs to serve file pushes to a stopped container.
On start, Incus sends it SIGINT and waits for it to exit; forkfile exits once no transfer is open, but it re-checks only **once a second** (`cmd/incusd/main_forkfile.go`, Incus 6.23).
A transfer still counts as open until forkfile has finished `syncfs` on the root filesystem, which can outlast the HTTP response to the push.
So a start issued immediately after a push races that flush, and losing costs exactly one second (measured: 1149 ms against 142 ms with a 200 ms pause, same host).
isx pushed terminfo, and for SSH-capable templates the authorized keys, 1 ms before every branch start.

Both now travel inside the post-start setup script as heredocs (`buildSetupScript`), which costs no extra request, and `InstanceLifecycle.prefetchAndStart()` -- shared by `isx branch` and the TUI -- pushes nothing between reading the config and starting.
Two related savings on the same path: `GuiPassthrough.removeGui()` returns after one read when the instance has no GUI state (it used to rewrite devices and config and push two empty files on every branch), and the setup script polls for the network address every 50 ms rather than every 0.5 s, which was ~400 ms of idle time per branch.

Two kinds of file still go in before the start, because they must be in place at boot: the static `.network` file, and the Wayland profile.d/tmpfiles.d files of a `--gui` branch (or the empty files that clear them, when a branch drops GUI state it inherited).
Both are pushed well before the start.
GUI setup runs first, right after the copy, and `configureBranch()` pushes the `.network` file just before its combined settings write, leaving that write, host integration and the runtime prefetch between it and the start.
That has been enough to finish the flush; if a trace ever shows the one-second gap again, the `.network` push is the next candidate.

### Why a branch is configured in one write

Between the copy and the start, a branch used to change the instance's settings in nine separate writes: resource limits and the root disk size, the proxy-only keys, the NIC address, IP spoofing protection, the static IP metadata, type/parent/created, the account pins, and the removal of inherited KVM passthrough (a full PUT even when there was nothing to remove).
Each step did its own read-modify-write, and each write costs Incus ~11-13 ms on btrfs, mostly rewriting the instance's backup file (`UpdateInstanceBackupFile`) -- ~110 ms of the ~250 ms before start (#804).
The same write costs 6-10 ms inside the macOS appliance; the vsock tunnel adds about a tenth of a millisecond to it (see "CLI latency on macOS").

`InstanceLifecycle.configureBranch()`, shared by `isx branch` and the TUI, reads the instance once and collects every change into an `InstanceUpdate` (config sets and unsets, device properties, device removals), which `IncusClient.update()` sends as **one PATCH**.
PATCH cannot remove a device, so when the copy inherited KVM devices the write is **one PUT** of the whole instance carrying all the other changes as well.
A removal of a device the instance does not declare is dropped rather than forcing that PUT (which also makes `devicesRemoveAll` of absent devices a read only).
Devices are sent complete -- their expanded config with the new properties merged in -- so a profile device such as `root` or `eth0` is overridden, not replaced by a fragment Incus rejects.
`InstanceLifecycleRequestBudgetTest` pins the one write.

One write cannot say which of its settings Incus refused, and the old separate writes existed partly to keep two apart: failing to pin the address is fatal, failing to enable `security.ipv4_filtering` only warns.
Incus rolls a refused write back whole, so on failure the write is retried once without filtering: failure surfaces the real error.
Success does not prove filtering was the problem -- the first failure may have been transient -- so filtering is then enabled on its own with `applyIpFiltering()`, which warns as before if Incus refuses it.
The source address is what identifies an instance to the proxy, so a first failure must not quietly leave the branch without it.
Both extra writes happen only on the failure path.

Airgap rides in the same write: it masks each NIC with `type: none` (`InstanceUpdate.replaceDevice`), which a PATCH can carry since it replaces a device whole.
Branching with network from an airgapped instance reads the profiles and writes their NICs over the masks, in that same write.
Enabling GUI or KVM passthrough and host resources still add their own devices afterwards.

### Build-time initialization must not capture host paths

Quarkus initializes application classes at image-build time unless they are listed in `--initialize-at-run-time`, so anything reachable from a static initializer is constructed by the *builder* and snapshotted into the image heap — fields and all.
On Linux the builder is **root inside the GraalVM builder container** (`install.sh` drives native-image through docker/podman, where `$HOME=/root`), so a field holding an `Environment` path freezes the builder's home rather than the user's.

Host-derived state that must be resolved eagerly therefore lives in exactly two classes, both on the flag: **`RuntimeConstants`** in `common` (the download and skills cache directories, plus the Java tool setups holding them) and **`RuntimeServices`** in the CLI (Incus client, lock manager, tool-def loader).
Both say so in their javadoc; `Environment` itself is on the list too and stays method-based, which is also what lets tests retarget `user.home`.
Everywhere else, call the `Environment` method rather than storing its result.

Deferring the class that *resolves* the path is not sufficient on its own — the **holder** must be deferred too, which is why `CDI_TOOLS` lives in `RuntimeConstants`.
GraalVM does not reject a build-time initializer that touches a run-time-initialized class; it initializes it early and folds the value, with no error (what `Environment`'s header comment warns about, confirmed by building it both ways).
That is how this shipped: `DownloadCache` resolves `RuntimeConstants.DOWNLOAD_CACHE_DIR` in its constructor and `ClaudeSetup`/`BobSetup` each hold one.
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

## Testing

**Unit tests** (`mvn test`, no Incus needed):
- `ToolDefTest` — YAML tool parsing, fingerprinting, composite fingerprints with transitive dependencies
- `ToolDefLoaderTest` — resolution order (builtins, user overrides, unknown tools)
- `YamlToolSetupTest` — execution order with mocked Container
- `ImageDefTest` — image definition loading, parent chain, descriptions, fingerprinting
- `BuildCommandTest` — `.claude.json` trust configuration, skill deduplication across inheritance chains, agent context collection (notes root-first, ancestor repos), shell quoting, GitHub URL parsing
- `AgentContextGeneratorTest` — generated `/etc/claude-code/CLAUDE.md` content: preamble, empty-section omission, note ordering, heredoc-marker neutralization
- `GitRemoteUtilsTest` — URL normalization (SSH/HTTPS/case), protocol-lenient matching, reference device naming (hash-based, truncation, collision resistance), host repo matching across multiple remotes
- `IncusApiTest` — REST API request/response parsing, exec body format, default exec environment, LOGIN_PATH_PREFIX
- `InstanceLifecycleRequestBudgetTest` — exact Incus round-trip counts for the start/shell/branch flows (see "Request budgets" below)
- `ActionResolverRequestBudgetTest` — the same for building an action context, paid before every `isx run` action
- `HelpChatModalTest` — the TUI's AI Help dialog, rendered headlessly and compared against golden text snapshots (see below), plus its key handling

CI runs them on Linux (`unit-tests`) and on an arm64 macOS runner (`unit-tests-macos`) on every PR.
A test that leans on GNU tools or Linux kernel behaviour fails on a Mac, and with only release builds on macOS, eight such tests failed on every Mac without CI noticing (#970).
A test that genuinely needs Linux skips with `assumeTrue(Platform.isLinux())` and a reason.
Intel Macs are not covered: arm64 is what Mac users run, and macOS runners are scarce and, where billed, cost several times Linux ones (#974).

**The test JVM keeps `AESCrypt.makeSessionKey` out of the JIT.**
CI tests on GraalVM 25, whose JIT now and then gives one AES-256 cipher a wrong key schedule while it deoptimizes the compiled and OSR versions of `com.sun.crypto.provider.AESCrypt.makeSessionKey` during warm-up.
TLS 1.3 prefers `TLS_AES_256_GCM_SHA384`, so that connection fails with `bad_record_mac` (or `Tag mismatch`, followed by a "record" of zeros, which is the JDK wiping the plaintext it refused).
The proxy tests run every TLS hop in one JVM.
So this showed up as a flake on whichever hop and test happened to be running: client to MITM, MITM to mock upstream, in either direction, sometimes twice within milliseconds as the method was recompiled (#940).
A standalone loop of `Cipher.init` + `doFinal` with fresh AES-256 keys, run in fresh JVMs, gave a wrong ciphertext in 24 of 960 GraalVM JVMs.
It gave none in 480 with `-XX:-UseOnStackReplacement`, none in 480 with the method excluded from compilation, none in 96 with C2 in the same JDK (`-XX:-UseJVMCICompiler`), and none on OpenJDK's C2.
AES-128 never failed.
The root pom's `argLine` property excludes the method, which runs once per cipher and costs nothing interpreted.
It is a property, not surefire configuration, because the Quarkus plugin adds its own arguments to it.
`JitWorkaroundTest` fails if the proxy tests' JVM loses the flag, or if a JDK running the Graal JIT stops having that method.
On any other JDK the exclude is a no-op and that check is skipped: JDK 27 renamed the class to `AES_Crypt` and has no `makeSessionKey`, so requiring it there failed every build on a stock JDK 27 (#1050).
A native image is unaffected, since it never deoptimizes into a recompiled method.
A JVM install run on a GraalVM JDK is affected, though, so the launchers carry the same exclude (#1018).
`install.sh` adds it to the generated wrapper only when the JDK it pins runs the Graal JIT (`UseJVMCICompiler` is `true`; a stock JDK has no such flag).
The JBang aliases set it as `java-options` unconditionally, since a catalog cannot ask which JDK it lands on and every HotSpot JDK accepts it.
`LauncherJitWorkaroundTest` keeps both equal to the `-XX:CompileCommand` options in the pom's `argLine`.
The upstream finding that `-Djdk.graal.OptimisticAliasingAnalysis=false` also avoids the bug is not used: it disables a whole optimization rather than one method.
The bug is GraalVM-only (Temurin 25 and 27 do not reproduce it) and is reported upstream as [oracle/graal#14599](https://github.com/oracle/graal/issues/14599); once it is fixed there, the fix here is dropping the flag.

**Request budgets**: CLI latency regressions almost always arrive as extra Incus round trips -- a `configGet` per key, a GET per listed instance -- and each one is paid in sequence before the user gets a prompt, at well under a millisecond each, over a local Unix socket and over the macOS vsock tunnel alike (see "CLI latency baseline" and "CLI latency on macOS").
A settings write costs about 10 ms: 6-10 ms in a branch on macOS, 11-13 ms on Linux btrfs.
Wall-clock benchmarks cannot gate PRs on shared CI runners, but a round-trip count is deterministic.
`FakeIncusDaemon` (test scope, `common/src/test/.../incus/`) is an in-memory `IncusTransport` behind a real `IncusClient` (via its package-private `IncusClient(IncusApi)` constructor) that records every request; budget tests pin the exact count for a flow.
`common` publishes its test classes as a test-jar that `cli` depends on in test scope, so a command's own wiring is pinned against the same fake (`BuildCommandAccountsWiringTest`, #932).
Exact rather than at-most, so they ratchet: an improvement fails the test until the budget is lowered in the same change.
The failure lists every request made, which is usually enough to spot the duplicate.
A flow on the start/shell/branch path gets a budget; a known waste is pinned at its current count with a comment saying what the fixed count will be.
Wall-clock measurement belongs in `bench/`, not in the shipped CLI: `bench/cli.sh` times real `isx` commands, JVM against native, on a throwaway instance.

**TUI snapshot tests**: TUI screens are only reviewable if they can be rendered without a terminal or an Incus daemon.
`TuiSnapshot` (test scope, `cli/src/test/.../tui/`) renders into an in-memory Tamboui `Buffer` via `Frame.forTesting` and compares the plain text against golden files in `cli/src/test/resources/tui-snapshots/`.
Every run also writes the actual rendering to `cli/target/tui-snapshots/` as `.txt` and `.ansi` (colours; view with `less -R`), which is how a reviewer -- human or agent -- sees a UI change.
A missing golden fails rather than being created silently.
Accept a changed rendering with `-Dtui.snapshots.update=true` and review the golden diff.
This requires the screen to be decoupled from `ListCommand`: a modal gets its own class holding its state, key handling and rendering, with side effects (the AI call, the executor) injected -- `HelpChatModal` is the first.
Snapshot at several terminal sizes (80×24 is the floor), since truncation and wrapping bugs only show at some widths.

**Interactive init flows**: `isx init`'s credential steps are where a token gets written to the wrong place, silently cleared, or an operator's personal `gh` identity reused when a dedicated one was intended -- failures that announce nothing.
CI cannot reach them (it runs `isx init </dev/null`, so every prompt hits EOF), and `java.io.Console` is final, so code that takes one cannot be driven by a test at all; that is how #740 shipped.
So interactive code reads from `Prompts` (line / secret, `null` at EOF) instead: production wraps the terminal, tests pass `ScriptedPrompts`.
Each step's logic lives in a `(SpawnConfig, Prompts)` overload, and every effect outside the process -- token verification, the host's `gh` login, the browser, environment variables -- is a package-private method a test overrides.
`GitHubAuthFlowTest`, `ClaudeAuthFlowTest`, `CredentialPromptsTest`, `PathListPromptsTest` and `AccountMenuTest` drive the flows end to end under `IsolatedHome` and assert on the config.yaml actually written.
`ScriptedPrompts` also fails a test when a value scripted as a secret is read through an echoing prompt, so a token prompt cannot quietly start printing the token.

**The macOS tunnel, without a Mac**: the compensations described in "macOS vsock robustness" exist because of how vfkit's tunnel *behaves*, and Java never touches vsock -- vfkit hands the host an ordinary Unix socket.
So the behaviour is faked instead of the hypervisor.
`LossyIncusServer` (test scope, `common/src/test/.../incus/`) binds a real Unix socket, speaks just enough of the REST API for exec (`POST .../exec`, the four fd WebSockets, operation `/wait`), and can withhold close frames and EOF, keep sending output after the process exits, accept and never answer, stall after reading a request, drop an idle keep-alive connection, or truncate a body.
`IncusApiLossyTunnelTest` asserts exec completes from `/wait` with no close frame, `/wait` is re-polled while `Running`, the adaptive drain both extends for late output and stops at its ceiling, every exec fd is pinged, and `tryConnect` fails at the probe timeout.
`UnixSocketTransportTest`, `ConnectionPoolTest`, `KeepAliveConnectionTest` and `HttpResponseReaderTest` cover the watchdogs (the WebSocket handshake's included), responses and handshakes cut off at every stage, the once-only stale retry (and that a truncated, possibly-executed request is never replayed), pool expiry, and that the connection gauge returns to its baseline after every failure.
Each of these was checked by breaking the compensation and watching its test fail; the cut-off cases were found this way, as real bugs, and fixed with tests that failed first.
Timing seams are package-private constructors (`IncusApi(transport, pingIntervalMs)`, `ConnectionPool(idleTtlNanos)`) and `IncusApi.tryConnect(List)`, rather than waiting out production intervals.
Output a test needs inside the drain's window is timed by the drain itself: `IncusApi(transport, pingIntervalMs, DrainClock)` replaces the time the drain reads and its pause between polls, and the test sends each chunk from the pause that reaches it.
A fake that slept between chunks on its own thread assumed the thread woke on time; on a loaded macOS runner it woke late, the gap outgrew the window, and the drain, correctly, read the output as finished (#1208).
The guest half is covered too, in `integration-tests`.
The appliance boots under QEMU with a `vhost-vsock-pci` device, host `socat` bridges its vsock ports to Unix sockets on the paths isx uses (the shape vfkit gives it), and `appliance/test-tunnel.sh` checks the forwarder, every `isx-agent` verb, concurrent-connection accounting, the `-T` backstop against a silent connection, and no-reboot recovery.
The native `isx` then connects through the same socket.
Both arches run it: x86_64 under KVM, aarch64 (the arch Apple Silicon runs) under TCG, since GitHub's arm64 runners expose no `/dev/kvm`.
What remains uncovered is vfkit itself.
It cannot run on GitHub-hosted macOS runners at all -- they are macOS VMs, and Virtualization.framework nests only Linux guests -- so it needs a bare-metal Mac, as a self-hosted release gate or the manual pre-release run of `test-boot.sh` / `test-with-isx.sh` (#774).

**Live tests** (`sg incus-admin -c "mvn test"`, requires Incus daemon + cached test image):
- `IncusApiLiveTest` — REST protocol against real Incus: all endpoints, exec capture/stream, device ops, copy, launch, logs
- `IncusClientSmokeTest` — high-level API: pollUntilReady, shellExec, execBidirectional, execPty, runAsUser, copy, filePush, filePushRecursive (directory placement + permission preservation), login PATH
- `BuildPipelineSmokeTest` — full buildFromScratch + buildFromParent operation sequence

**Integration tests** (`mvn verify -DskipITs=false`, requires Incus):
- `TemplateBuildIT` — builds actual images, verifies metadata, agentuser, and that the generated agent context file lands without touching the user-layer CLAUDE.md

## Technical Tradeoffs

### System containers vs application containers
System containers run a full init system and present as a complete machine.
This means higher base image size (~200MB vs ~5MB Alpine) and longer first-build time (system upgrade, user creation, tool installation).
However, clones are instant and near-zero cost with CoW storage, which is the common operation — you build once, branch many times.

### No capability dropping (`lxc.cap.drop =`)
Standard Incus containers drop many Linux capabilities for defense-in-depth.
We don't, because the container *is* the security boundary and developers expect `ping`, `strace`, `perf`, raw sockets, and `dmesg` to work.
The risk is that a container escape exploit has more host capabilities to abuse.
For the strongest isolation, use `--vm`: a separate kernel under KVM, so a kernel exploit inside the guest does not reach the host kernel.

### YAML tools vs a full plugin system (Packer, Ansible, etc.)
We evaluated Packer (null builder + shell provisioner) and Ansible but rejected both.
Packer's null builder is just indirection over what Java already does, and Ansible adds a Python dependency and playbook complexity for what amounts to "install some packages and run some scripts."
YAML tool definitions give 90% of the flexibility with zero dependencies.
Java `ToolSetup` implementations remain available as an escape hatch for tools that need programmatic logic (reading host config, conditional branching).

### Hardcoded built-in tool list vs classpath scanning
Built-in YAML tools are loaded from a hardcoded list of filenames rather than scanning the classpath.
This is a deliberate choice: Quarkus native image compilation makes classpath directory listing unreliable, and the list only changes when a developer adds a built-in tool (at which point they also update the loader).
User-defined tools in `.incus-spawn/tools/` are discovered via filesystem scanning.

### DNS: static resolv.conf + bridge dnsmasq
systemd-resolved (127.0.0.53) doesn't work reliably inside Incus containers because it expects to manage the network configuration.
We disable it, point `/etc/resolv.conf` directly at the Incus bridge gateway (which runs dnsmasq), and make the file immutable with `chattr +i`.
This is less flexible than systemd-resolved (no per-link DNS, no DNSSEC validation) but works reliably across container restarts and network changes.
Domain interception for the MITM proxy is configured at the bridge level via `raw.dnsmasq` (dnsmasq `address=` directives), not via per-container `/etc/hosts`.
This avoids a class of bugs where Incus overwrites `/etc/hosts` on container start.
Each intercepted domain gets two lines: `address=/<domain>/<gateway>` for A, and `local=/<domain>/`, which makes dnsmasq answer every other record type for it locally with no data.
Without the `local=` line dnsmasq forwards AAAA (and HTTPS/SVCB, which can carry address hints) upstream, and the domain's real IPv6 addresses bypass the proxy.
Released versions closed that hole with `address=/<domain>/::`, but `::` is the wildcard address, which clients treat as loopback.
A nested isx refused every intercepted domain as host-local (`DownloadCache` rejects a host if any resolved address is loopback, wildcard or link-local), and an IPv6-preferring client would connect to itself (#814).
The `address=` + `local=` pair gives NODATA for AAAA on every dnsmasq from 2.80 to 2.92.
The lines sit between `# BEGIN incus-spawn intercepted domains` and `# END incus-spawn intercepted domains` markers (`BridgeDns`), like the UFW blocks, so a rewrite replaces exactly what isx wrote and keeps every user line.
Inferring ownership from line shapes is not safe with this layout: a `local=/<domain>/` left behind without its `address=` partner answers NXDOMAIN for the whole domain.
The pre-block layout is recognised by its `address=/<domain>/::` lines, which every released version wrote, and migrated.
A downgrade to such a release keeps the block's `local=` lines as user lines, so a domain that release stops intercepting stays NXDOMAIN until isx is upgraded again, and the upgrade then rewrites everything cleanly.
`isBridgeDnsComplete()` and `isx doctor` report domains missing from the block, domains whose `address=` line points at another address than the bridge's current gateway, and domains still answered with `::` separately.
An override for a gateway the bridge has since given up counts as wrong, not as configured: until the next proxy start rewrites the block, it sends the domain where the proxy does not listen (#840).
Overrides that all point there fail doctor like having none; both values come from one read of the bridge (`ProxyConfig.bridgeDnsStatus()`).
Having no overrides at all is not complete: instances then resolve intercepted domains to their real addresses and bypass the proxy, so while the proxy is up `isx doctor` fails that case (#839).
A failed read of `raw.dnsmasq` is reported as "could not check", never as complete (`readDnsOverrides()` throws; only `getDnsOverrides()`, for callers looking for overrides to act on, turns it into `""`).
Doctor offers to write the overrides only while the proxy is listening.
With it down, they would send every intercepted domain to a gateway where nothing answers (`STALE_DNS`), which is worse than bypassing it, so doctor leaves the "Proxy running" finding to carry the problem.
A proxy that outlived a change of the bridge's address is still running, bound to the old gateway, and its block still points there.
Probing the new gateway finds nothing and probing the old one cannot help either: once the bridge drops the address nothing answers on it, though the socket bound to it lives on.
So when the probe fails and the block points elsewhere, `ProxyHealthCheck` looks for a listener on the health port at that address in `/proc/net/tcp` and `tcp6`, and, when the host no longer has that address, reports `STALE_GATEWAY` (restart the proxy) instead of `STALE_DNS` (start it) (#919).
An address the host still has is a proxy started there on purpose with `--gateway-ip`, which a restart would not move (#933).
The automatic restart before a command, and the TUI's, restart the service for `STALE_GATEWAY` only when the service is active.
When the stale proxy runs in the foreground, starting the service would put a second proxy beside it.
The user is shown the restart advice instead.
It reads two local files, never the network, and only after the probe has already failed, so a healthy proxy pays nothing.
Doctor does not rewrite the block in that state either: the current gateway is where nothing listens.
The proxy also heals itself when its gateway came from the bridge (Linux, no `--gateway-ip`) (#966).
Every 60 s, and before a reload rewrites the block, it reads the bridge's address again (`ProxyMain.Addresses.movedTo()`).
When the bridge now has another address, it does not write the block, which would point every intercepted domain back at an address nothing reaches.
Instead it stops and exits 1, and the unit's `Restart=on-failure` starts it again, and startup binds to the new address and writes it into the block.
Rebinding in place was the alternative, rejected because it would need the listener, the health endpoint and the block moved together, which is exactly what startup already does.
A foreground proxy just exits with the reason in its log.
A bridge that cannot be read, or has no address, is not a move: a restart would find nothing better, and the gateway `isx init` cached is not the bridge's word.
An explicit `--gateway-ip` is never compared with the bridge, since it is the user's choice of address (#933).
On macOS the gateway is the VM link, which the Incus bridge does not move.
One Incus request a minute is the cost; it is not on any branch, start or shell path.
On Linux the proxy points the block at the gateway it resolved at startup and listens on, which `--gateway-ip` can set, passing it in rather than reading the bridge again.
A second read ignored the override and failed outright on a bridge with no address, leaving DNS unconfigured (#892).
The override is written into dnsmasq verbatim, so it must be a unicast IPv4 address in canonical dotted-quad form, and anything else is a configuration error (`EXIT_CONFIG`).
A hostname, IPv6, link-local, multicast, broadcast or wildcard address would become every intercepted domain's answer, and most of them break the bridge's dnsmasq outright.
A shorthand such as `10.1`, or an IPv4-mapped `::ffff:10.99.0.1`, parses as the right address in Java, but dnsmasq would not read it the same way: the mapped form becomes an AAAA answer, leaving intercepted domains with no A record.
The override is also the bind address of the credential-injecting listener, which serves the default accounts to a caller it cannot place, so a LAN or public address would inject the user's credentials into requests from other hosts.
On Linux it must therefore also be a private (RFC 1918) or loopback address, or the bridge's current gateway itself (`BridgeAddress.read()`, not `ProxyConfig.resolveGatewayIp()`, whose fallback to the gateway `isx init` cached could vouch for an address the bridge has since given up).
Anything else is `EXIT_CONFIG` (#1022).
A private or loopback override is accepted without reading the bridge, because working around a bridge whose address cannot be read is what the override is for (#892).
The bridge is read only for an address outside those ranges (a bridge on, say, `100.64.0.0/10`), and when that read fails the proxy stops with exit 1, as it does without an override.
Requiring exactly the bridge address, as on macOS, was the alternative, rejected because it would break that workaround; the cost is that a private LAN address remains accepted, which is the user's explicit choice of a network they control.
Loopback is accepted though, as an A record, it sends instances to themselves: it exposes nothing.
On macOS the address the proxy listens on is the host's end of the VM link, so the block, which dnsmasq serves inside the VM, still points at the VM's own bridge gateway.
There `--gateway-ip` is not written anywhere but it is the bind address of the credential-injecting listener, and a caller the proxy cannot place is served the default accounts (build containers and host traffic rely on that).
So an override of `0.0.0.0` or a LAN address would inject the user's credentials into any host's requests.
On macOS the override must therefore be exactly the host's end of the VM link that `VmNetwork.discoverHostBridgeIp()` finds, and anything else is `EXIT_CONFIG`.
When no VM bridge is found the override cannot be checked and the proxy stops as it would without one (#937).
Refusing only wildcard and public addresses was the alternative, rejected because a private LAN address is just as reachable from other hosts.
The TUI's auto-heal and doctor's remediation rewrite the block from `ToolProxyResolver.resolvedDomains()`, which resolves across all accounts exactly as the proxy does: the default account's narrower set would drop a domain that only a named account can serve.

### Credential isolation via MITM TLS proxy
A TLS-terminating MITM proxy intercepts HTTPS connections to specific domains (Anthropic API, GitHub, IBM Bob), injects authentication headers server-side, and forwards to the real upstream.
Containers resolve these domains to the gateway IP via bridge-level dnsmasq overrides (configured when `isx proxy` starts) and trust the proxy's certificates via a custom CA installed in the template image.
This approach was chosen over simpler alternatives (reverse proxy with `ANTHROPIC_BASE_URL`, credential helpers, shell wrappers) because those approaches still expose credentials to code running inside the container — either as environment variables, in process memory via `curl` calls, or through accessible endpoints.
The MITM proxy provides complete isolation: there is no API, endpoint, environment variable, or file that container code can access to obtain credentials.

### Vertex AI: container in Vertex mode vs standard mode
We initially ran containers in standard (non-Vertex) mode with proxy-side API translation — the container sent `/v1/messages` and the proxy rewrote to Vertex `rawPredict`.
This had a critical flaw: Claude Code's model list is provider-dependent.
In standard "firstParty" mode the model picker is a hardcoded subset that omits newer models (e.g. Opus 4.6 was missing).
In Vertex mode the full catalogue is shown.

The solution: containers now run in Vertex mode with `CLAUDE_CODE_USE_VERTEX=1`, `CLAUDE_CODE_SKIP_VERTEX_AUTH=1` (skips GCP auth — the SDK uses stub credentials that produce empty auth headers), and `ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1` (redirects the Vertex SDK to the proxy).
The Vertex SDK formats requests in Vertex URL format (`/v1/projects/.../models/...:streamRawPredict`), sends them to the proxy, and the proxy injects real GCP credentials before forwarding to the actual Vertex endpoint.
The proxy also retains the standard-to-Vertex translation path for `/v1/messages` requests — this is the primary path for tools like Pi that use the standard Anthropic API format, and also serves manual `curl` calls inside the container.

**Fragility and mitigation:** The standard-to-Vertex translation path uses an allowlist (`VERTEX_ALLOWED_FIELDS` in `MitmProxy.java`) that may drift as Anthropic adds new standard fields.
However, the primary traffic flow (Vertex passthrough) doesn't use the allowlist — the Vertex SDK already formats the body correctly.
The allowlist only affects the fallback translation path.
The `anthropic_version: "vertex-2023-10-16"` value is hardcoded in the translation path — this matches the Anthropic Vertex SDK and has been stable since Vertex support launched.
The Vertex passthrough path doesn't set this value; the SDK does it itself.

### Git remote helper: bash + Java split
The git remote helper is split into a bash shim and a Java command rather than implementing the full protocol in Java.
The reason is stdin buffering: Java's `BufferedInputStream` (used by `System.in` and `ProcessBuilder`) reads ahead into an internal buffer.
In the git remote helper protocol, the initial text exchange ("capabilities", "connect git-upload-pack") is followed by a binary pack protocol stream on the same stdin pipe.
If Java reads even one byte too many during the text phase, the binary stream is corrupted.
The bash shim handles only the text protocol (a few short lines via `read`), then `exec`-replaces itself with the Java process.
The Java process inherits raw file descriptors with no buffered-ahead data and can safely use `execBidirectional` (WebSocket-based stdin/stdout forwarding) to pipe the git pack protocol to the container.

The alternative — implementing the full protocol in Java with careful single-byte reads — is fragile and would need to be re-validated with every JDK update that touches `System.in` buffering behaviour.

### Auto-remote: stateless cleanup vs state tracking
When an instance is destroyed, its git remotes need to be removed from host repos.
Two approaches: (1) track which remotes were added in metadata and remove exactly those, or (2) scan host repos for `isx://` URLs matching the instance name.
We chose stateless scanning because it's simpler and eliminates a class of state-sync bugs (user manually removes a remote, add failed silently, metadata gets corrupted).
The cost is scanning a few git repos on every destroy, which takes milliseconds.

### Single-branch clone with lazy refspec restoration
When cloning from the remote (the fallback path when no host reference is available), template builds use `--single-branch` to avoid fetching objects for all remote branches and tags.
On a large project like Quarkus this is the difference between ~3 MiB and ~100 MiB of network traffic.
The fetch refspec is immediately widened with `git remote set-branches origin '*'`, which costs nothing (no network, no object transfer) and makes the clone behave like a regular one.
Users never need to know they received a single-branch clone; `git fetch`, `git branch -r`, and `git checkout other-branch` all work as expected — other branches just populate on first access.

The local-clone path (from a host reference) does not use `--single-branch` because it copies pack files wholesale regardless — the flag would only narrow the refs, which the follow-up `fetch` and `set-branches` fix anyway.
`--single-branch` is reserved for the remote-clone fallback where it actually reduces network transfer.

The alternative — a full remote clone — would download objects for hundreds of branches and thousands of tags that most container workflows never touch.
Post-hoc pruning is not straightforward because git doesn't garbage-collect fetched objects unless explicitly told to.
The single-branch + refspec-restore approach gets the performance benefit without any user-visible limitation.

### Auto-remote: opt-in via configuration
Auto-remote management requires explicit `host-paths` or `repo-paths` configuration — we don't scan `~` or `/` to auto-discover repos.
Within configured `host-paths`, subdirectories are scanned recursively up to 4 levels deep, skipping known non-project directories (`.git`, `node_modules`, `target`, `build`, `vendor`, etc.).
This handles the common case where repos are organized in category subfolders (e.g. `~/Code/java/`, `~/Code/go/`).
Direct-child matches take priority over nested matches.
If the same repo name appears in multiple locations, the operation fails with an error instructing the user to add an explicit `repo-paths` entry to disambiguate.

### Fedora-specific
The base image and package management are Fedora-specific (`dnf`, `images:fedora/44`).
This is intentional — supporting multiple distros adds complexity for a tool primarily targeting developer workstations where Fedora is a common choice.
The YAML tool system is distro-agnostic in principle (tools can use any shell commands), but the built-in base image setup assumes Fedora.

### Incus Daemon Connection

The CLI communicates with the Incus daemon via its REST API.
The transport depends on the platform:

**Linux** (direct): The Incus daemon exposes a Unix domain socket at `/run/incus/unix.socket`.
The CLI speaks plain HTTP/1.1 over this socket — no TLS, no authentication (access is governed by Unix socket permissions and the `incus-admin` group).
WebSocket-based exec sessions (for `isx shell`, file push, etc.) use the same socket.

**macOS** (via VM): Incus runs inside a VM managed by vfkit.
The CLI connects via a **vsock tunnel** — a direct host↔VM communication channel that bypasses the IP network entirely:

```
UnixSocketTransport (plain HTTP/1.1)
  → ~/.local/state/incus-spawn/vm.incus.sock  (Unix socket on host)
    → vfkit virtio-vsock device (port 8443)
      → socat VSOCK-LISTEN:8443 inside VM
        → /run/incus/unix.socket (Incus daemon)
```

vfkit exposes the VM's vsock port 8443 as a Unix domain socket on the host.
Inside the VM, socat bridges the vsock listener to the Incus daemon's local Unix socket.
The result is that the macOS path reuses the same `UnixSocketTransport` as Linux — plain HTTP, no TLS, no certificates.

**Why vsock instead of HTTPS:** The original macOS transport used HTTPS over TCP to the VM's DHCP-assigned IP (192.168.64.0/24 subnet).
This required client certificate generation, server certificate capture, hostname verification bypass (the self-signed cert doesn't include the DHCP IP), and IP rediscovery on VM restart.
More critically, corporate VPN software (notably Cisco AnyConnect) installs a macOS socket filter that blocks non-Apple-signed binaries from TCP connections to the VM subnet — even when the VPN is disconnected.
Since `isx` is an ad-hoc-signed GraalVM native binary, AnyConnect blocks it from reaching the VM over TCP.
vsock bypasses this entirely because it operates outside the IP network stack (`AF_VSOCK`, not `AF_INET`), so socket filters that target TCP connections cannot intercept it.
The MITM proxy is unaffected because its traffic flows in the opposite direction — containers inside the VM connect outward to the host, which arrives as inbound traffic to the proxy process, not as an outbound `connect()` from `isx`.

**No HTTPS fallback:** `IncusApi.tryConnect()` selects a transport in order: Linux Unix sockets → vsock Unix socket.
The earlier HTTPS-over-TCP path (mutual TLS to the VM's DHCP IP) has been removed.
It reintroduced exactly the problems vsock exists to avoid (macOS Local Network permission prompts and VPN socket-filter blocking, described above).
Maintaining two transports made field issues hard to diagnose because it was unknowable which path a given user was actually on.
(`HttpsTransport` still exists in the tree but is no longer wired into connection selection.)

**An operation is done when it says so, not when its wait returns.**
State changes (start, copy, create, image import) answer 202 with an operation, and `IncusApi.waitForOperation` long-polls its `/wait?timeout=120`.
A poll that times out answers HTTP 200 with the operation still `Running` -- a full copy onto a non-CoW pool, an image Incus is still downloading, a large import over the macOS tunnel all outlast it -- so the wait asks again while the status is `Running`, `Pending` or `Cancelling`, up to a ceiling as long as the exec wait's (4 hours), and past that throws rather than return (#1089).
A `Cancelled` operation throws like a `Failure`.
Taking the timed-out poll (or a cancellation) for success, as it once did, sent callers on against a missing or half-written instance.
A fast operation still costs one wait, which the request budgets pin; `OperationWaitTest` pins the re-poll and the ceiling.

### macOS vsock robustness

The vfkit vsock tunnel (`AF_VSOCK` across `host unix socket → vfkit → in-VM socat → Incus`) does **not reliably propagate connection close/EOF** — particularly after macOS sleep/resume, when in-flight streams are left half-open.
That single fault surfaced in two directions and shaped several design choices:

- **Exec completion is derived from the operation, not the socket.**
  WebSocket exec (`isx shell`, package installs, git) originally read stdout/stderr until the server closed the fds.
  When close frames are dropped, `readPayload()` blocks forever.
  The fix (`IncusApi.execWebSocket`) unifies capture/stream/bidirectional exec and takes the operation `/wait` endpoint — the daemon's operation state, over a normal HTTP request — as the authoritative completion + exit-code signal, then drains and force-closes the data sockets.
  Completion no longer depends on close-frame delivery.
  Every exec fd is keepalive-pinged (each is a separate socat child that an inactivity reaper would otherwise collect on a quiet command).
  The post-exit drain is **adaptive**: it waits a short minimum for bytes in flight, extends while output is still arriving (so trailing output isn't truncated), and closes shortly after it goes idle (`OutputDrain`).
  Idle is counted only while the drain was watching.
  When the host process pauses (a VM's vCPU descheduled, a GC), the first poll after the pause used to read the pause as quiet output and close the sockets before the reader threads, paused with it, had taken what arrived meanwhile -- truncating the output (#1062, #1084, seen on macOS CI runners).
  So a gap between two polls counts as idle for at most half the window, and a late poll ends the drain only if the poll before it already found the output idle, which gives the readers the sleep in between to catch up.
  (Restarting the whole window on each late poll, the first form of this, held every drain to its ceiling on the macOS runners, where polls come late again and again: #1122.)
  The ceiling stays wall time, so pauses never hold the caller past it.
  The drain also waits for bytes that reached the host but that no reader has taken yet.
  Closing a socket discards what is queued in it, so a reader held up by a slow sink or woken late lost the end of output that had already arrived (#1208).
  Any byte waiting unread in a data socket counts as output arriving now (`WsConnection.hasUnread`, the socket's `available()`); the ceiling still bounds a sink that never takes it.
  A caller that asks a guest it cannot trust to answer (`isx mcp` counting another session's tasks, or sweeping orphans) passes a limit (`execStreamWithin`, `IncusClient.execProbe`, which also skips the login shell).
  The wait's long-polls shrink to it, the process is then sent SIGKILL over the control fd, and after a short grace the caller is released either way, since a killed process whose child still holds its output keeps the operation open.
  The kill goes out on every way the bounded wait can end before the command was reported finished -- a `/wait` that overran its slack (likely over the vsock tunnel), an operation lost, an interrupt -- so giving up on a bounded exec never leaves its command running unowned.
  (An unbounded one keeps its old behaviour: it waits, up to its four-hour ceiling.)

- **The forwarder leaks, so it needs a backstop and a recovery path.**
  The same close-propagation gap means the in-VM `socat` forwarder never reaps connections whose close didn't cross the boundary.
  They pile up as vfkit-held host fds and degrade every new connection (observed: hundreds of leaked streams, `list` latency from sub-second to ~30s).
  Mitigations: a `socat -T` **inactivity timeout** reaps orphaned children (sized above the 120s `/wait` long-poll, with keepalives so live connections are never reaped); a **keep-alive connection cache** (`ConnectionPool`/`KeepAliveConnection`, via `requestPooled`) reuses a warm connection for short request-path calls (`get`/`post`/`/wait`) instead of reconnecting each time, cutting the churn that feeds the leak (exec WebSocket fds are per-operation and not poolable); and a per-process connection gauge + high-water mark in `UnixSocketTransport` makes accumulation visible.

- **A cut-off exchange is an error, never a smaller success.**
  A tunnel that drops connections mid-exchange turns any parser that reads EOF as "end of line" or "end of headers" into a source of silent data loss.
  The one-shot request path used to: a response cut off in its headers came back as a 200 with an empty body, a cut-off chunked body spun until the watchdog fired, and a WebSocket upgrade the peer hung up on "succeeded" -- handing exec a dead fd whose output then came back empty while the exit code (from `/wait`) looked fine.
  So there is one strict parser, `HttpResponseReader`, shared by the one-shot path, `KeepAliveConnection` and the WebSocket handshake.
  Every early EOF is an `EOFException` (EOF before the first byte is `NoResponseException`, which the keep-alive path maps to its retry-safe stale case), and malformed numbers are `IOException`s rather than runtime exceptions that escape callers' handlers.
  WebSocket frames follow the same rule (EOF inside a frame is an error; EOF between frames is end-of-stream).
  The WebSocket connect + handshake is bounded by the transport timeout like any request, since a wedged tunnel accepts and then says nothing.
  Only the handshake is bounded -- an open exec or shell socket legitimately stays quiet, and its liveness comes from the keepalive pings.

- **One silent connection can wedge the whole API.** incusd wraps its local Unix listener in a `StarttlsListener` whose `Accept()` peeks 8 bytes to detect a STARTTLS upgrade, synchronously and with no deadline.
  So a connection that has sent nothing blocks every connection behind it until it speaks or closes (found by the vsock-bridged CI boot: a new request completed exactly when a silent one finally sent its first bytes).
  On Linux a client's close reaches incusd at once and unblocks the peek.
  Through vfkit it may never arrive, and then the forwarder keeps the silent connection open until `socat -T` reaps it -- up to 180s with no API at all, which reads exactly like the field "wedged tunnel" that `forwarder-restart` clears instantly by killing the child.
  isx therefore never opens a tunnel connection without writing a request straight away.
  The one place that used to (the vsock branch of `diagnoseConnectionFailure`, a connect-and-close reachability check that ran precisely when the tunnel was struggling) now sends a real `GET /1.0` under the probe timeout.
  The server-side fix belongs upstream.
  LXD fixed the identical code (canonical/lxd#18705, peek moved off the accept loop with a read deadline); incus had not as of 7.5.1.
  incusd also closes idle keep-alive connections after 30s (`IdleTimeout`), a close vfkit may not deliver either.
  So `ConnectionPool`'s TTL (5s) must stay well under it.

- **The forwarder's accept queue must absorb one exec.** socat's default listen backlog is 5, and a vsock listener with a full accept queue refuses the connection rather than letting it wait.
  One exec opens four fd WebSockets plus `/wait` at once, so any overlap with other traffic was refused; CI measured 3 of 16 and 12 of 32 concurrent connects refused.
  Both guest listeners (forwarder and agent) set `backlog=128`.

- **Diagnosis and layer-aware recovery.**
  The tunnel has two independent failure points: the host-side vfkit forwarding (link 2) and the guest-side socat forwarder (link 3).
  `isx doctor` and the automatic recovery in `VmManager.ensureRunning()` both compare the host-side fd count (`lsof`) against the in-VM socat child count (agent `socat-count` verb) to localize the wedge via `leakLayer()`: if the guest count is low while the host count is high, vfkit is not reaping (VFKIT); if both are high, the forwarder is lingering children (FORWARDER); if the guest count is zero, the forwarder is not running (always FORWARDER — restarting it is the correct fix regardless of host count).
  The recovery decision tree in `recoverReachability()`: 10s grace probe → `detectLeakLayer()` → for a VFKIT wedge, fail fast with "run `isx vm restart`" (a forwarder restart cannot fix a host-side problem); for FORWARDER or unknown layer, restart the forwarder via the agent → 15s post-restart probe → 30s backstop.
  `probeTunnelHealth()` provides the same detection as a public API for proactive TUI/build-time checks, returning `HEALTHY`/`VFKIT_WEDGED`/`FORWARDER_ISSUE`/`UNKNOWN`.
  Recovery is provided by a small **allowlisted in-VM control agent** (`isx-agent`) on its own vsock port — verbs `ping`, `version`, `socat-count`, `sshd-status`, `forwarder-restart`, `btrfs-usage`, `btrfs-status`, `btrfs-rescan`, no arbitrary exec — reached over an independent channel so it works even when the Incus tunnel is wedged.
  `forwarder-restart` drops and relaunches the forwarder **without rebooting the VM or stopping containers**, and now verifies the new process started (polls `pgrep` 4×0.5s) before confirming; stderr goes to `/dev/console` (the virtio-serial feeding `vm.log`) so startup errors are visible.
  See appliance/DESIGN.md for the in-VM side.

### The macOS VM outlives whatever started it

vfkit is started as the leader of a new session, not as a plain child of `isx`.
A plain child stays in the starting command's process group and on its terminal, and both take it down.
Closing the terminal hangs up every process on it.
Ctrl+C reaches the whole foreground group, so interrupting the command that happened to start the VM stopped the VM.
And launchd kills whatever is left in a job's process group once the job exits, so the login agent, whose `isx vm start` exits as soon as the VM is up, killed its own VM a few seconds later (#971).

Java has no `setsid`, and macOS ships no `setsid` command, so the VM is started through `/usr/bin/perl -MPOSIX=setsid -e 'setsid(); exec …'`: perl replaces itself with vfkit, so the pid `isx` records is the VM's.
Ignoring SIGHUP (`nohup`) was rejected because it covers one of the three cases; `AbandonProcessGroup` in the agent's plist covers another one only.
Launching the app bundle through LaunchServices (`open`) would detach it too, but returns no pid.
If perl is ever gone from macOS the command is run as before, and this has to be solved again.

QEMU, the Linux path, is started the same way (#993): its exposure to a hangup or Ctrl+C is the same.
Its console is qemu's own stdout, appended to `vm.log`, so perl's diagnostics share that file without the double-writer problem below.

vfkit's own stdout/stderr (perl's diagnostics if the exec above fails; otherwise whatever vfkit itself prints) go to `Environment.vfkitLogFile()`, never to `vm.log`: vfkit opens `vm.log` itself, non-append, for the VM's virtio-serial console.
A second writer appending to the same file corrupts both — vfkit's own periodic lines ("machine awake" on host wake, timesync setup) land at EOF over console bytes `isx vm console` has already read past, and the console's next write lands over those in turn.
A separate file also keeps `vm.log`'s absence a reliable signal of a first launch, which gates the one-time TCC permissions note.
Before the split, a perl failure still created `vm.log` (the append-mode redirect creates the file as soon as the process starts), so a retry after a perl failure saw `vm.log` already there and silently dropped the note.

### Lifecycle locking

Multiple `isx` processes can modify VM or proxy state concurrently (e.g. `isx vm restart` in one terminal while `ensureRunning()` auto-starts in another).
`VmManager`, `ProxyService` and static IP allocation all guard their critical sections with one `HostLock`: an `fcntl` advisory file lock (`FileChannel.tryLock()`), auto-released on process death, behind a per-path in-process `ReentrantLock`.
The in-process half is what makes it safe for a multi-threaded TUI: a second `tryLock` from the same JVM throws instead of waiting, and closing any channel on the file drops every lock the process holds on it.
Each lock used to be open-coded, and only the newest had that half.
`HostLock` is `AutoCloseable` and not reentrant, so public methods (`start`, `stop`, `restart`, `ensureRunning`, `install`, etc.) acquire it then delegate to private `*Locked()` variants.
A method that calls another mutating method (e.g. `restart` → `stopLocked` + `startLocked`) uses the locked variant.
Lock files live at `~/.local/state/incus-spawn/vm.lock` (VM), `~/.config/incus-spawn/proxy.lock` (proxy) and `~/.cache/incus-spawn/locks/.static-ip.lock`.
A waiter prints one wait message and polls with backoff from 10 ms to 50 ms, and times out after 30 seconds.
The cap is low for fairness, not only speed.
`fcntl` keeps no queue, so whoever polls first after a release wins, and a waiter backed off to half a second kept losing to newcomers polling every 10 ms until it timed out while the lock changed hands all along.
The in-process lock is keyed by the lock file's real path, since `fcntl` locks a file rather than a path spelling.

**Replacing a template that others copy from (#1212).**
A rebuild (`isx build`, `isx project create`) builds under `<name>-rebuilding` and swaps it in only on success, so a failed rebuild keeps the previous template.
Incus cannot rename onto an existing name, and has no atomic swap: the swap is a delete and a rename, and in between the template does not exist.
Reordering the requests doesn't close that gap.
Renaming the old template aside first still leaves a moment with nothing under the name.
A lock that only the swap took would not help either, because readers don't ask for the template once.
`isx branch` checks that it exists, `BranchFlow.preflight` reads it, and the copy request names it, and a gap that falls anywhere among them fails the branch ("does not exist", or a copy whose source vanished mid-request).
So the lock is a read-write one.
`TemplateLock.replace` holds the template's `HostLock` exclusively for the delete and the rename.
Every path that copies from a template holds it shared, from the lookup until the copy is made: `isx branch`, the TUI's branch, `isx mcp`'s `create_instance(template)` and `delegate(template)`, and a build or project create copying its parent.
`isx build`'s check of whether a parent is missing or outdated (`parentNeedsBuild`) holds it and its own parent too, or a parent caught mid-swap would read as missing and be rebuilt a second time, racing the first (and a grandparent caught mid-swap would make the parent look current); so does the chain's lookup before the rebuild confirmation, or a template in the gap would be replaced without asking.
A reader that arrives during the gap waits the two requests out and then finds the new template.
Readers never hold each other off, so concurrent branches of one template, such as a coordinator's fan-out, cost each other nothing; the lock adds a few file syscalls and no Incus request to the branch path.
The lock file is `~/.cache/incus-spawn/locks/templates/<name>.lock`, one per name and never deleted, since deleting it would let two processes lock different files.
Names come from untrusted places (a project-local `name:`, an agent's `template` argument), so only a name Incus would accept for an instance gets a file (any other cannot be a template, and is held by nothing), and `isx mcp` holds only templates the user listed under `mcp.templates`.
`isx branch` cannot do the same.
Whether its `--from` names a template is only known from a lookup, which has to be made under the hold, so a mistyped `--from`, or a branch of a plain instance, leaves a lock file behind.
That is an empty file per name the user typed, against a branch that never has to wait out a swap.
A template's name may be at most 52 characters, since `isx build` and `isx project create` both build it as `<name>-rebuilding` first and Incus allows 63; both refuse a longer one before doing anything (`BuildCommand.reportNameTooLong`).
A process holds one shared `fcntl` lock for all its readers, released with the last of them (`HostLock.acquireShared`), because closing any channel on the file drops every lock the process holds on it.

What it costs.
A branch's readers hold the lock through the whole branch, start included (`BranchFlow.create`), so a swap waits for branches under way, a VM's start among them; its timeout is ten minutes rather than the usual thirty seconds, since timing out fails a build whose work is done.
`fcntl` has no queue, so a steady overlap of readers can delay a swap until they stop.
The lock covers only isx processes of this user on this host: a raw `incus copy`, `sudo isx`, or isx on another host against the same Incus can still meet the gap.
An unusable lock file (a home without working `fcntl` locks) degrades both sides to the in-process lock with one warning, as the static IP claim does, rather than failing builds or branches.

**Post-launch readiness.**
`startLocked()` only spawns the hypervisor process — it cannot detect an immediate crash or a guest that never reaches Incus.
Every code path that launches the VM verifies readiness within the lock scope: `start()` and `restart()` call `awaitReady()` (polling `waitUntilReady(60)` with `BuildOutput` step output); `ensureRunningLocked()` does its own inline wait with `System.err` output and recovery logic.
`startLocked()` returns a `StartResult` enum (`LAUNCHED`/`ALREADY_RUNNING`/`FAILED`) so callers can distinguish a fresh launch (needs readiness wait) from a no-op (VM was already up).

### Keeping a long-lived TUI current

The TUI is routinely left open for days, while instances are created, stopped and deleted from other terminals, scripts, or `incus` itself.
Three things used to go stale: ages ("today 21:20" still showing at 08:00), instance state (listed as running after being stopped elsewhere, deleted instances lingering, new ones missing), and -- worst -- actions against instances that no longer existed, which failed with raw Incus errors.

**Event-driven, not polled.**
Incus publishes every instance state change on `/1.0/events?type=lifecycle`, so the TUI subscribes once per session (`InstanceEventWatcher`) and refreshes when something relevant happens: changes appear within about a second, and an idle TUI costs nothing.
Polling was rejected as the primary mechanism because an interval short enough to feel live would re-list every instance continuously across a days-long session -- over vsock on macOS -- mostly to learn nothing changed.
Polling survives only as the fallback while the subscription is down (every 60s), so an older daemon or a flaky appliance still converges.
Lost events are the subscription's inherent gap, so every successful (re)connect triggers one full re-read rather than trusting the stream to be complete.

**Live updates yield to the Incus channel.**
The subscription is a convenience layered on the connection every other isx operation depends on, so when the two conflict, live updates lose.
The watcher gives up for the session after three failures in a row -- a connect that fails, or a subscription that drops within a minute -- and the TUI falls back to 60s polling with a one-line notice.
The reason is the macOS tunnel.
Every attempt is a new vsock stream through the appliance's forwarder, where closed streams can linger until reaped (see "macOS vsock robustness"), so an unbounded reconnect loop over a days-long session is exactly the leak pattern that degraded every connection there.
A subscription that lived past a minute resets the budget, so a daemon restart is survived.
`tui-live-refresh: false` in `config.yaml` turns the whole mechanism off -- no subscription, no polling -- for anyone who would rather refresh by hand.
The action allowlist excludes `instance-exec`/`-console`/`-file-*`: the TUI's own shells and the proxy's probes produce those constantly and they change no row.
A start also schedules two follow-up reads, because the instance's address appears seconds after the start event and no event announces it.

**Keepalive and liveness.**
An event subscription can be silent for hours, which is exactly what the macOS forwarder's inactivity reaper (`socat -T`) and a half-open vsock stream after sleep/resume (see "macOS vsock robustness") punish.
Incus heartbeats its event listeners with a ping about every 10s and drops listeners that never answer, so the event stream's reads (`readMessage()`) answer pings with pongs.
The exec/shell read path (`readPayload()`) deliberately still skips them without replying.
Incus doesn't ping exec sockets, and a pong written from the reader thread would have to wait for the lock a stdin write holds -- a stall risk on the channel that matters most, for no benefit.
`IncusApi.openEvents()` also pings every 15s and closes the stream after 50s with nothing received (five missed heartbeats), turning a half-open connection into an end-of-stream the watcher reconnects from, instead of a read blocked forever while the TUI believes it is live.
Event messages are read with `readMessage()`, which reassembles fragmented frames: the exec path could ignore frame boundaries because its payloads are byte streams, but an event is one JSON document per message.

**A cheap refresh path.**
`reloadData()` does far more than list instances -- it re-reads every YAML definition, measures pool usage, reads btrfs accounting status (an agent round trip on macOS), may run a privileged rfer probe, and checks proxy health -- all on the UI thread.
Running that per event would stall the UI during bursts (`isx clean`, a template rebuild's create/rename/delete).
So events drive a *light* refresh: the instance listing and pool usage are fetched on a background thread and merged on the UI thread with the definitions already in memory, reusing the last full reload's disk-accounting results.
Definitions cannot change because an instance did, and the accounting reads are deliberately on their own cadence ("the privileged read is rare, not per-refresh").
Full reloads remain on `r`, on TUI re-entry and after the TUI's own background tasks, and a generation counter discards a light result that a full reload overtook.

**Races are closed at the point of action.**
No refresh latency makes "the list is current" true at the instant a key is pressed, so actions re-check existence immediately before running and report "no longer exists" in the TUI instead of an Incus error.
While the subscription is up and every received event has been applied, the listing itself answers -- asking Incus on each keypress would let a wedged daemon freeze the UI -- and only otherwise does the check cost one `GET /1.0/instances/<name>`.
The one gap, an event still in transit, is covered by the action's own error handling and by the shell path re-checking before it connects.
Dialogs whose target disappears are closed with the same notice on the next refresh.
The detail and actions dialogs render the current table selection, so otherwise a refresh that moved the selection off a deleted row would silently repoint them at a different instance.
A failing existence check does not block the action: an Incus hiccup should surface as the action's own error, not freeze the UI.

**Ages as elapsed time.**
A wall-clock age ("today 21:20") is wrong the moment the date changes unless re-rendered, and misleading across midnight even when it is.
Ages are now elapsed-time wording ("3h ago") computed on whole minutes, so the text is a pure function of the current minute and the TUI re-renders rows exactly when the minute changes -- no Incus call involved.
The absolute timestamp is still in the F3 detail view.

### Warnings while the TUI owns the terminal

A warning printed while the TUI renders is drawn over and scrambles the screen, so shared code reports through a sink each caller routes (CLAUDE.md).
Two things were still wrong after that rule was written (#872).

**Some code has no caller to take a sink from.**
`ToolDefLoader` printed tool-definition problems to stderr, and loaders are built far from any command: `ToolProxyResolver.proxyToolSetups(config)` builds one for account validation, which the TUI reaches through `AccountSelection`.
Threading a `Consumer<String>` through every such path would change a dozen signatures for a warning none of them care about.
`Warnings` (`common`) is the process-wide default sink for this case: `warn(message)` goes to stderr, or to whichever `Warnings.Channel` the terminal's owner redirected it to (`Warnings.redirect`).
The TUI redirects around its reloads and while its runner draws -- not while it has released the terminal to a build or a shell, which must print their own errors.
A TUI-launched build that refuses an unparsable file says "see the error above", so the error has to be above it.
Taking a sink from the caller stays the first choice; `Warnings` is for code where there is none.
The loaders also keep what they found (`ToolDefLoader.warnings()`, like `conflicts()` and `parseFailures()`), so tests and callers that want to act on it need not listen.
The default sinks of `ImageDef.loadAll()`/`loadAllWithConflicts()` go through it too, since `BranchFlow` -- which the TUI branches through -- calls `loadAll()`.

Each channel reports a distinct message once.
The same files are loaded many times in one command (every fresh loader re-reads them), and before this `isx branch` could print one broken tool file's warning several times.
The memory is per channel, not per process.
The TUI's channel lives for the whole TUI session, so returning from a shell does not re-announce the same definition problems, while stderr's channel is separate, so that build still prints a warning the TUI already logged.
`forgetReported()` clears a channel's memory for the one case that wants repeats: the user asking for a reload.

**One status line cannot hold several warnings.**
The TUI used to put each warning on its status line, which shows one message until the next key.
Two operations warning close together -- a reload that finds a broken tool file, then a start that drops a missing inbox -- overwrote each other.
Each site had grown its own "first warning (+N more)" squeeze with no way to read the rest.
They now all go to one `WarningLog`, whichever thread raised them.
The status line only *announces* what arrived since the last frame (the latest, led by how many arrived, since a long line is cut off at the terminal width), and only when it is empty or holds an older announcement, so an action's result ("Build failed") is never replaced -- the warnings wait for the next key; the header keeps "⚠ N warnings (w)" up while any are unread, so an announcement cleared by a keypress is not lost; and `w` opens a dialog listing every warning, newest first, with its lines intact -- several warnings carry a command or a YAML fix to copy, which a one-line status bar cannot show.
A warning raised again moves to the end with the new time instead of appearing twice, and the log is capped at 100 entries.
Warnings still unread when the TUI exits are printed once the terminal is released, so quitting does not discard them.

The log is not cleared by a reload, because it records warnings that happened, not the definitions' current state: a fixed tool file's warning stays, with its time, until cleared (`c` in the dialog).
Clearing also calls the TUI channel's `forgetReported()`, so a warning that is still true comes back on the next reload rather than staying hidden for the rest of the session.

**What isx says before the TUI opens is drawn over too** (#1154).
Bare `isx` gets ready first -- on macOS it starts the VM, which also checks the running appliance against the installed one; a fresh host runs `isx init` -- and anything printed then sits on the screen only until the TUI takes it over, to be read after quitting.
Fixing each message would leave the next one to be found the same way, so `IncusSpawn.launchTui()` opens a window (`PreTuiOutput`) that the TUI closes before its first reload (so time spent at the pause leaves no stale listing).
Inside it, `Warnings` go to a held list that is handed to the TUI's `warningChannel` (so it does not announce the same one again) and from there to the `WarningLog`, which announces them as it does any other warning.
The stale-appliance notice reaches it this way (`VmManager.warnOfSkew`), at no cost to the user.
The header's "Appliance X — restart VM for Y" stays where the skew remains visible, and keeps skipping the first load so opening the TUI asks the VM nothing extra.
Everything else still reaches the terminal when it is written (a VM start or init that hangs must show its progress).
But `System.out`/`System.err` are wrapped to note that something was printed, and if anything was, isx asks for Enter before opening the TUI.
The held warnings are then printed above that prompt as well, since a Ctrl-C there would otherwise lose them (the TUI that would have shown them never opens).
An Enter typed while isx got ready is discarded so it cannot end the pause unseen.
The window wraps `isx init` too: init's own text is exactly what the pause must not let the TUI cover.
The cost is that a `Warnings` warning raised during init is shown at the pause rather than among init's prompts.
The two remedies the issue named are both used, each where it fits.
Deferring a warning keeps the common case free of a keypress, while progress and prompts are not warnings, and replaying them into the log would bury the warnings that are.
A TUI that never opens (init declined) prints the held warnings as it closes, so none is lost.
Without a terminal there is nothing to wait on and no TUI to cover anything, so no pause.

## VM Appliance

A minimal Alpine Linux VM image with Incus pre-installed, providing CI integration testing and macOS support.
Uses BusyBox init (not systemd or OpenRC) for fastest possible boot.
Custom kernel from kernel.org source (zero modules, no initrd) with musl libc for fast dynamic linking.
The build produces a rootfs tarball (~30-40 MB) and kernel (~10.5 MB on aarch64, ~5 MB on x86_64, both gzipped to ~5 MB for release download); a writable btrfs disk image is created on first boot.
See [`appliance/DESIGN.md`](appliance/DESIGN.md) for full architecture details.

## Security Considerations

### Container vs VM Trade-off
- **Containers** (default): share host kernel.
  A kernel exploit could escape.
  Suitable for semi-trusted code (AI agents with scoped permissions, community bug reproducers).
- **VMs** (`--vm` flag): hardware-level isolation via KVM.
  Recommended for actively malicious code.
  Separate kernel eliminates kernel exploit as an escape vector. ~10% performance overhead.

### Credential Isolation

Real API keys and tokens never enter containers, regardless of network mode.
Containers hold only placeholder values that satisfy tools' local auth checks; the proxy replaces them with real credentials before requests reach upstream servers.
A started instance's placeholders are proof tokens (`gho_isx_<digest>`, see "Proof tokens" under the per-start secret): the table shows the static value a build bakes, which a start replaces.

| Credential | Container has | How it works |
|-----------|--------------|--------------|
| Claude API key (direct mode) | Placeholder `sk-ant-placeholder` | Proxy replaces `x-api-key` header with real key |
| Claude OAuth token (Pro/Max) | Placeholder `sk-ant-placeholder` | Proxy strips `x-api-key` and injects `Authorization: Bearer <oauth-token>`. Container configuration is identical to direct API key mode |
| GCP credentials (Vertex mode) | Placeholder `ya29.placeholder-for-proxy` in `ISX_VERTEX_ACCESS_TOKEN` | Container runs Claude Code in Vertex mode with `CLAUDE_CODE_SKIP_VERTEX_AUTH=1`, which sends only `ANTHROPIC_CUSTOM_HEADERS`; a login script puts `Authorization: Bearer $ISX_VERTEX_ACCESS_TOKEN` there. Proxy injects GCP Bearer token from `gcloud` on the host. No GCP credentials, service accounts, or access tokens enter the container |
| Pi Anthropic key | Placeholder `sk-ant-placeholder` in `ANTHROPIC_API_KEY` | Same as Claude direct/OAuth mode. Pi always uses standard API format; the proxy handles key injection, OAuth Bearer injection, or Vertex translation transparently |
| OpenAI API key | Placeholder `sk-placeholder` in `OPENAI_API_KEY` (and, for `codex`, copied into `~/.codex/auth.json` at login) | Proxy replaces `Authorization: Bearer` header with real key for `api.openai.com`. Used by `codex` and by `pi` with `provider: openai` |
| GitHub token | Placeholder `gho_placeholder` in `GH_TOKEN` | Proxy replaces `Authorization` header with real token for GitHub domains (Basic auth for `github.com` git HTTP, Bearer for API) |

**A placeholder is read when the tool runs, never fixed at build time (#1108).**
The placeholders become per-start proofs derived from the instance secret (#1106), which the proxy checks before it injects anything (#1107, #1100).
A value a build copied into a file would be the wrong one after the next start, and lose the credential.
So every consumer takes the variable its login exports, and where a tool insists on reading a file, a login script rewrites that file from the variable.
What each tool reads, checked against its source or binary:

| Tool | Reads | What isx does |
|------|-------|---------------|
| `gh`, and git through `gh auth git-credential` | `GH_TOKEN` (before `hosts.yml`; the helper asks on every call, and gh writes no env token to disk) | Nothing more. `GhSetup`'s identity lookups, run from the host, take `GH_TOKEN` as the instance's `/etc/profile` exports it (`GhSetup.LOGIN_TOKEN`), with the build's placeholder only as a fallback |
| Claude Code, OAuth | `CLAUDE_CODE_OAUTH_TOKEN` | Nothing more |
| Claude Code, API key | `ANTHROPIC_API_KEY`, but an interactive session first asks whether to use a key whose last 20 characters are not in `customApiKeyResponses.approved` of `~/.claude.json`, defaulting to no | `/etc/profile.d/isx-zz-claude-auth.sh` approves the key of this start at login |
| Claude Code, Vertex | Under `CLAUDE_CODE_SKIP_VERTEX_AUTH` no Authorization header of its own and no `gcloud` call, only `ANTHROPIC_CUSTOM_HEADERS` | The same login script adds an Authorization line from `ISX_VERTEX_ACCESS_TOKEN` to `ANTHROPIC_CUSTOM_HEADERS`, after any headers already there and in place of an Authorization line already there; the `gcloud` stub prints that variable when called |
| Codex | `~/.codex/auth.json` only: `OPENAI_API_KEY` is never sent, `CODEX_API_KEY` is `codex exec`-only, and the built-in `openai` provider cannot be pointed at a variable | `/etc/profile.d/isx-zz-codex-auth.sh` rewrites `auth.json` from `OPENAI_API_KEY` when it still holds a key isx put there |
| Copilot CLI | `COPILOT_GITHUB_TOKEN` (before any stored login, of which there is none) | Nothing more |
| Pi | `ANTHROPIC_OAUTH_TOKEN` / `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`, after a stored `~/.pi/agent/auth.json` that isx never writes | Nothing more |
| Bob | `BOBSHELL_API_KEY`, copied into `BOB_API_KEY`; nothing stored | Nothing more |
| YAML tools with a `proxy:` entry | Whatever the author wrote | The loader warns when a tool's `files:` or build steps hold the value of one of its own variables named as a credential, if that value is no path, URL or phrase (`ToolDefValidator.embeddedTokens`): read the variable when the tool runs |

The two login scripts are named `isx-zz-*` so they sort after every other `isx-*.sh` in `/etc/profile.d`, and see the values a start exports rather than the build's.
Both are POSIX sh, silent, and do nothing but a grep when nothing changed.
Neither may break the file it edits.
Claude Code's approval is added by replacing the last line of a file shaped as Claude Code writes it (`{` first, `}` last) with the field and the closing brace -- `JSON.parse` keeps the last of a repeated key and Claude Code rewrites the file with one -- and anything else is left alone, which costs a prompt, never a config.
Codex's file is replaced only while it still holds `sk-placeholder` or an `isx_` proof in `apikey` mode, so a `codex login` of the user's own survives.
A child build rewrites both scripts and syncs the `gcloud` stub for a Claude or Codex it inherits without setting up again (`ToolSetup.refreshInherited`), so a parent built before #1108 does not pass on files that hold or lack the wrong thing.
Instances branched from such a parent get them only once it is rebuilt.
A tool in an instance reads what its *login* environment holds: a process started some other way gets the build's placeholder, which the proxy will refuse once it checks.

The MITM TLS proxy provides credential isolation:
1. Bridge-level dnsmasq overrides (configured by `isx proxy`) route intercepted domains to the gateway IP
2. A custom CA certificate (installed in template images) lets containers trust the proxy's TLS certs
3. The proxy terminates TLS, replaces placeholder auth with real credentials, and forwards to real upstream over TLS
4. Placeholder values cannot authenticate against any service — they only bypass local tool checks
5. In proxy-only mode, iptables OUTPUT rules additionally block all egress except the proxy port (443) and DNS

### Agents driving isx over MCP

`isx mcp` exposes a deliberately narrow surface: approved, trusted, built templates only; each instance usable only by the host user's session holding it (another session of that user must adopt it first, never another user's); no host command execution and no host file access; results returned as text.
It runs as the user, so that boundary holds for the MCP tools, not for an agent that may also run `isx` or edit `~/.config/incus-spawn` through its shell.
The README gives the Claude Code permission rules that close that path.
Text coming back from an instance (command output, a delegate's report, a diff) is produced inside the sandbox and may try to instruct the host agent; tool descriptions say to treat it as data.
The same holds for `ask` answers, which a model wrote after reading that text: the summarising Claude Code gets no tools, so injected instructions cannot make it act, only mislead, and its answers are never a basis for merging.
A delegated agent can do anything its template's credentials allow, which is why those are the user's choice per template and not the agent's.
A coordinator in an instance (`isx branch --mcp-client`, #915) closes the shell path altogether.
Its only reach into the host is the tool list, granted by a stamp the user sets and no copy inherits, and checked by address, per-start secret and stamp together.

Idempotency keys (#1011) add no capability: a replay goes through `requireOwned` or `adopt()`, never around them.
What a controller must know: a replay naming another instance (or template, or kind of task) than the call that used the key is refused, not redirected, so a controller that picks the instance at run time must record that choice as part of its intent; and a key whose instance was kept is refused, the one outcome to handle by looking the instance up rather than retrying.
Task keys are written inside the guest and so are untrusted: they are read only if they match the key charset, exactly, and answer only for a call naming the instance that holds them.
An agent there could plant one, which misdirects a keyed call on an instance it already controls; and, while this session holds that instance, it could make a keyed call naming another instance be refused as a mismatch -- a refusal, never a capture.

### Doctor: a finding that is neither healthy nor broken

`isx doctor` reports four statuses, not three.
`OK`/`WARN`/`FAIL` cover "fine", "you should look at this" and "this is broken"; `NOTE` (`·`) states something about the setup that nothing is waiting on.
Only `WARN` and `FAIL` answer `Status.isProblem()`, which is what the exit code, the "N issue(s) can be addressed" list and the closing summary all consult.
So a note can never turn a clean run into a reported problem, and never dilutes the exit status a script checks.

It exists because of unconfigured credentials.
Every tool's credential is offered unconditionally (`ToolProxyResolver.findUnresolved`), so "OpenAI key not set" says nothing about health on its own.
It is missing for the user who has never touched codex exactly as it is for the user whose template installs it.
Before `NOTE` those were the same yellow line.
The graduation of `codex` out of the `openai` feature flag would have added one to every existing user's `doctor` output — the classic way a diagnostic becomes something people learn to ignore.

Doctor distinguishes the two by asking whether a template *the user wrote* declares the tool.
Built-in definitions are excluded deliberately: they ship with isx and declare tools for everyone (`tpl-dev` has `gh`), so counting them would warn about every credential again and put us back where we started.
Templates are only the triage signal, not the gate — `isx branch` already refuses to run without the credentials a template actually needs (`CredentialCheck`), so a note here cannot let a broken build through.
Anthropic credentials stay a `WARN` regardless: isx itself uses them for `isx ask`, so there is no template to check against.
If the definitions can't be read at all, every tool counts as in use — the conservative direction is to keep reporting.

### Doctor and build remediations act through isx, never name an `incus` command

A remediation says *what* is done, and isx does it: through `IncusClient` over REST where it can, so it works the same over the Unix socket and over the macOS vsock tunnel (#986).
A hint telling the user to run `incus storage|profile|move|image ...` fails on a Mac, whose host has no `incus` CLI (#939).
On Linux it ties the advice to Incus's CLI syntax for an operation isx already knows how to perform.
So the default profile's root disk is repointed with `updateProfileRootDiskPool`, and stopped instances off the CoW pool are moved with `moveToPool` (`instance_pool_move`).
That move copies to a temporary name, deletes the original and renames the copy back, so it gets `rename()`'s post-check (#717); a full copy can outlast one `/wait` poll, which the shared operation wait (#1089) already sees through.
A running or frozen instance is listed, never stopped for the move; one in any other state (stopped, or `Error` after a failed start) is moved.
A build whose base image's `/sbin/init` fails with "Exec format error" deletes the failed instance and the image its `volatile.base_image` names without asking when isx can get it back, since the cache is known corrupt.
A remote image is launched once more, and a local one isx imported from the template's `image_url` is re-imported by the re-run it asks for.
A local image with no `image_url` behind it (imported by hand) is the only copy, so it is kept, and the error says to replace it.
The check sits around the start (`BuildCommand.launchBuildInstance`), because that is where the fault shows.
Creating the instance runs nothing in it, so Incus fails the *start* (forkstart exits 1) and LXC writes `<errno> - Failed to exec "/sbin/init"` to the instance's `lxc.log`.
Until #986 the hint was checked after the create and so could never fire.
A base image imported by another project stays a trust gate: at a terminal the build offers to delete it (`--yes` does not answer for it) and stops either way.
Without a terminal it says to re-run in one.
A new `isx image delete` or `isx move` command, or "run it in `isx vm shell`", were the alternatives, rejected because each exposes an operation the user should not need to know about.
The hints `isx init` prints during Linux setup, before isx manages a pool or profile, are the exception, along with `noCowPoolMsg`'s Linux arm. #1139 extended the rule to `incus network` and `incus remote`: the bridge-subnet conflict diagnostic now says only to run `isx init`, which reconfigures the subnet itself and prints the manual command (Linux setup) only when it finds no free one; an image naming an unknown remote says on macOS which remotes isx knows and which file it reads others from, and the `incus remote add` hint stays on Linux.
`gatewayUnavailableHint` and the proxy's `--gateway-ip` check already named `incus network` only on Linux.
`NoIncusCliHintTest` allowlists those literals one by one, scanning `cli`, `common` and `proxy`, and fails on any other.
The `incus console` and `incus config device` hints are not covered yet.
The macOS remediation for "no CoW pool" is still open (#1085): doctor offers none, and `noCowPoolMsg` names `isx vm delete`, which does not exist.

### Support bundles: redaction by construction

`isx doctor --bundle` exists so that diagnosing a remote failure is an attachment rather than an hour of screen-sharing.
That only works if a user can send the archive without auditing it first, which makes "no credential ever enters the bundle" a property the code must guarantee rather than a rule contributors are asked to remember.

**Secret locations come from the tool declarations, never from a list.**
Every tool that injects credentials already describes them: a `ToolDef.ConfigEntry` with `secret: true` and a `config-path`, resolved against the tool's `config-namespace` by `ProxyDef.fullConfigPath()`.
`SecretRegistry` (in `common/config/`) collects exactly those declarations and answers one question — where do secrets live in `config.yaml`.
Knowing what a credential *looks like* is a separate job, and lives in `SecretRedactor` alongside the token-shape patterns, so the registry stays provably declaration-derived.
The first implementation instead hardcoded two keys, and it went stale the way a hardcoded list always does: `bob.apiKey`, `openai.apiKey` and every YAML-declared tool secret (`typesafe.apiKey`) were written into support archives in the clear.
A registry fed by the declarations cannot drift, because the same declaration that makes the proxy inject a credential is what makes the redactor remove it.
A future pluggable secrets backend — keychain, 1Password, environment — reads the same locations; that is the seam it plugs into.

**A declaration holds wherever the key appears.**
A declared leaf is redacted at its exact path *and* anywhere else under its tool's namespace.
That is not breadth for its own sake.
When Claude credentials became a named map, `claude.accounts.<name>.apiKey` appeared in every real config, and an exact-path-only redactor wrote each account's token into the bundle while reporting that it had found no credentials to redact.
The tool declared that `apiKey` under `claude` is a credential; honouring that at any depth keeps the declaration true as the config grows a dimension, with no second list to update.
It stays narrow because only a declared namespace/leaf pair earns the deep walk — a map whose keys are user data, like `repo-paths`, is neither.

**Structural redaction is the mechanism; scrubbing is a net.**
The config is serialized to a tree and the values at the registry's locations are replaced before anything is written, so nothing depends on a secret looking the way we expected it to look.
Logs and `instances.json` have no model to scrub, so they get the secondary treatment: exact replacement of the values structural redaction just removed, then well-known credential shapes (`sk-ant-`, `gh[pousr]_`, `github_pat_`, `Authorization:` headers, `user:pass@host` URLs, PEM private-key blocks).
The distinction is deliberate and stated in the bundle's own `REDACTIONS.txt`: a net can miss, which is why the config never relies on one.

**A redacted value is marked, not blanked.**
Secrets become `<isx:redacted:github.token>` rather than `""`, because empty is itself a valid value — without the marker, a credential that was removed and one that was never configured look identical, and those describe opposite bugs.
Unset keys are left empty for the same reason.

**One funnel.**
`SupportBundle.add()` is the only path into the archive: it scrubs, tallies, then writes.
A collector added later cannot leak by forgetting to redact, because there is no other way in.
The tally becomes `REDACTIONS.txt`, so the recipient can see what was withheld and the sender can check before sending.

**`--bundle` implies `--deep`.**
The in-container DNS and TLS probes are the definitive end-to-end evidence; a bundle without them is one we would have to ask the user to generate again.

The undeclared-key backstop (`SecretRedactor.looksSecret`) matches whole words, not substrings, so `apiKey` and `AUTH_TOKEN` are withheld while `monkey` and `keyboardLayout` survive — substring matching would quietly blank out the diagnostics the bundle exists to carry.
How far a matched name's authority reaches follows the same instinct: **the nearest key name governs**.
A string is redacted; a *list* inherits the name, its elements having none of their own (`tokens: [...]` is a list of tokens); an *object* does not, because its fields carry their own names and those are better evidence than the parent's.
Blanket-redacting an object cost more than it protected — an ordinary `auth: {enabled, endpoint, timeoutMs}` block lost every field, and the endpoint, now counted as a secret value, was scrubbed out of every log in the archive.
A declaration outranks all of this: a declared path is taken at its word and redacted whole, whatever shape it holds, so a tool that stores a structured credential should declare it rather than rely on the name.

### Secret Scanning in CI

`.github/workflows/gitleaks.yml` guards against a real leaked credential landing in the repo, as distinct from the credential-isolation work above, which guards containers against ever holding one.
It runs `gitleaks/gitleaks-action@v2` on every push and PR targeting `main` (on a push, root jobs are gated with `if: github.event_name != 'push' || github.ref_name == 'main' || github.event.repository.fork`, so feature-branch pushes only run on forks), scanning full git history rather than just the diff, since a secret introduced and later reverted is still live in history.

The scan runs twice, against two separate rulesets, because gitleaks' `[extend]` table only supports one base ruleset per config file (`path` and `useDefault` are mutually exclusive — see `gitleaks/config/config.go`) and `extend.url` is an unimplemented stub in gitleaks itself.
So a ruleset can't be pulled in live at scan time either.
One pass extends gitleaks' own built-in rules (`.gitleaks.toml`).
The other extends a vendored copy of [leaktk/patterns](https://github.com/leaktk/patterns)' generated gitleaks config (`.gitleaks-leaktk.toml` → `.gitleaks/leaktk-gitleaks-8.27.0.toml`).
leaktk/patterns is not incidental.
It's the closest public equivalent to the internal Red Hat "Pattern Server" that `rh-gitleaks` (an internal tool wrapping gitleaks) draws from, and its own README says as much — a false positive it produced during setup was on a rule literally named "Authorization Header," matching a finding category `rh-gitleaks` had independently reported against this repo.
`rh-gitleaks` itself can't be wired into this workflow: it needs to reach that internal Pattern Server, which a public-repo GitHub Actions runner has no path to.

Both rulesets miss one thing neither maintains: an Anthropic-key-specific rule.
`.gitleaks.toml` and `.gitleaks-leaktk.toml` each add `anthropic-api-key`/`anthropic-oauth-token` rules for `sk-ant-api03-`/`sk-ant-oat01-`, gated on a 40+ character suffix rather than the prefix alone.
Real keys run 90+ chars past the prefix, while this repo's own test fixtures (which legitimately need key-shaped strings to exercise `SpawnConfig`/`SecretRedactor` parsing) top out at 28, so the length gate catches a real leak without re-flagging a fake one.
The same length-vs-shape distinction shows up in each config's `[allowlist]`.
Known-fixture paths are allowlisted outright (matching on path means it also covers commits from before the allowlist existed), and a secondary regex net for short (≤35 char) placeholder suffixes is deliberately capped below the 40-char detection floor, so it can never widen enough to swallow a genuine leak.

### Filesystem Isolation
- Inbox mount is strictly read-only
- Host resources default to read-only; overlay mode provides an ephemeral writable layer but the host directory is never modified
- Clone filesystems are independent CoW copies — changes in one clone don't affect others or the template image
