---
paths:
  - "cli/src/main/java/dev/incusspawn/command/BuildCommand.java"
  - "cli/src/main/java/dev/incusspawn/command/BuildProgress.java"
  - "cli/src/main/java/dev/incusspawn/command/BuildTools.java"
  - "cli/src/main/java/dev/incusspawn/command/GuestProvisioning.java"
  - "cli/src/main/java/dev/incusspawn/command/RepoCloner.java"
  - "cli/src/main/java/dev/incusspawn/command/SkillInstaller.java"
  - "cli/src/main/java/dev/incusspawn/command/CleanCommand.java"
  - "cli/src/main/java/dev/incusspawn/command/BranchCommand.java"
  - "cli/src/main/java/dev/incusspawn/command/ProjectCommand.java"
  - "common/src/main/java/dev/incusspawn/lifecycle/TemplateLock.java"
  - "cli/src/main/java/dev/incusspawn/command/UpdateBaseCommand.java"
  - "cli/src/main/java/dev/incusspawn/baseimage/**"
  - "common/src/main/java/dev/incusspawn/config/HostResourceSetup.java"
  - "common/src/main/java/dev/incusspawn/git/**"
  - "common/src/main/java/dev/incusspawn/incus/**"
  - "common/src/main/java/dev/incusspawn/tool/DownloadCache.java"
  - "common/src/main/java/dev/incusspawn/util/CpuInfo.java"
  - "common/src/main/java/dev/incusspawn/util/TerminalProgress.java"
  - "common/src/main/resources/images/**"
---

# Image Hierarchy and Build System

Templates are YAML definitions (`common/src/main/resources/images/`) with optional parent inheritance forming a chain: `tpl-minimal` -> `tpl-dev` -> `tpl-java`.
A `deprecated:` definition (#1195) still builds, but `ImageDef.deprecationWarning` gives one warning when the template itself is deprecated or its direct parent is, both in one line when both are (the parent named, its note left out), and none for deprecated templates further up (#1353), which `BuildCommand` prints once per template per run; the TUI shows it in template details, and copies every `BuildOutput.warn` of a build it runs into its warning log (`BuildOutput.copyWarningsTo`), since it redraws over the released terminal.
`deprecated` is not fingerprinted.
`tpl-dev` and `tpl-java` are deprecated, to be removed; their copies live in `incus-spawn-templates` under the same names.
Building an image auto-builds missing parents.
Each definition can set `type` (`container`, `vm`, or `kvm` -- the last is a *container* that branches get `/dev/kvm` passed into, not a VM) which inherits through the parent chain via `inheritTypes()` at `ImageDef.loadAll()` time.
VM definitions also support `vm_image_url` and `vm_image_sha256` for a pre-baked VM base image.

`BuildCommand` has two build paths:
- **`buildFromScratch`** (root image, no parent): launches base OS, configures security/DNS/user, installs packages and tools
- **`buildFromParent`** (derived image): copies parent via CoW, applies only the delta (new packages/tools)

For VMs, `buildFromScratch` applies the entire ancestor chain from YAML definitions -- parent Incus instances are not needed.
`buildChain` detects type changes (container->VM) and skips unnecessary parent rebuilds.
Container-specific security config (raw.idmap, nesting, setxattr interception) is skipped for VMs.
Both build paths restore Claude Code and Codex trust for repositories from the whole template chain after tool setup finishes.
A child's Claude or Codex reconfiguration rewrites that tool's config, so it can erase inherited repository trust even when the child declares no repositories.
Tool downloads use a mount-and-copy strategy instead of file push (vsock can't handle large pushes).
The `--type` CLI flag overrides the definition's type; `effectiveMachineType()` returns the effective `MachineType` considering both the flag and definition.
`BuildCommand.InstanceType` (`container`, `vm`, `kvm`) is the CLI/definition-level enum; `MachineType` (`CONTAINER`, `VM`) is the Incus API-level type -- KVM is a container with `/dev/kvm` passthrough, not an Incus VM.
`ActiveBuild` holds the temp name, canonical name, and resolved `MachineType` for the duration of a build, so `buildFromParent`/`buildFromScratch` and the shutdown hook read it from one place.

**VM guest SELinux** (#842): VM builds pin `/etc/selinux/config` to `SELINUX=disabled` before any dnf run (`disableGuestSelinux`, both build paths) and fail at the end if it reads `enforcing` (`assertGuestSelinuxNotEnforcing`).
The guest filesystem is unlabelled and the targeted policy denies the incus-agent's vsock `listen`, so an enforcing guest builds fine but is unreachable after its next boot.
Seeding works because `selinux-policy`'s `%post` only writes the file when it is missing or empty.
Do not replace this with a relabel: that does not fix the agent denial.

**Swap safety** (#717): `buildSingleImage` builds under `<name>-rebuilding` and swaps it in at the end.
Before anything is created, `reportStrandedStorage` refuses (in `isx project create` too) when either name is an orphaned subvolume or a dangling record (`IncusClient.scanSubvolumes()`), since the swap would otherwise fail only after the whole build.
The swap's `IncusClient.rename` checks that the record and the subvolume both moved, and a failure there goes through the build's failure handling (report, promote to `-failed-build`).

**Swapping a template others copy from** (#1212): the swap is a delete and a rename (Incus cannot rename onto an existing name), so between the two the template does not exist.
`TemplateLock.replace` does it holding the template's `HostLock` exclusively (`~/.cache/incus-spawn/locks/templates/<name>.lock`).
Everything that copies from a template holds it shared (`TemplateLock.reading`) from looking the template up until the copy is made, through the start for a branch: `isx branch`, the TUI's branch, `isx mcp`'s `create_instance(template)` and `delegate(template)` (`InstanceBackend.holdTemplate`), `buildFromParent`, `isx build`'s missing-or-outdated check of a parent and of the parent's own parent (`parentNeedsBuild`), its rebuild confirmation's lookup of the chain and `isx project create` from its parent lookup to the copy.
A new path that copies a template takes `TemplateLock.reading` the same way.
`isx project create` builds under `<name>-rebuilding` too and swaps through the same call, so a failed re-create keeps the previous template (a failed swap keeps the build).
Only names Incus accepts get a lock file (a project-local `name:` or an agent's argument is untrusted), and `isx mcp` takes the hold only after `TemplatePolicy.requireListed`.

Package deduplication: `BuildCommand` collects all ancestor packages and subtracts them from the install list so derived images only install what's new.

**Base image version tracking**: The root template (`tpl-minimal`) downloads a pre-baked base image from `Sanne/incus-spawn-images`, whose tag/checksums are baked into `minimal.yaml`.
That built-in tag is only an **offline fallback**.
When the root def is unpinned (`pinned: false`), `BuildCommand.resolveTrackedBaseImage()` (called at the top of `buildFromScratch` on the root def) fetches the newest release at build time via `baseimage/BaseImageReleases` and returns its tag + per-arch container/VM checksums for the download, so a plain build always tracks the latest base image.
It never rewrites the definition: `definition-sha` and `build-source` fingerprint it as loaded, the tracked tag goes in `build-source`'s `trackedImageTag`, and a newer release being published does not mark the root as changed (#1244).
Any network/API failure falls back to the built-in tag with a `note()`.
`isx update-base` only manages the *pin* -- pinning writes a `pinned: true` user override (`~/.config/incus-spawn/images/minimal.yaml`) with the selected tag and both `image_sha256`+`vm_image_sha256`; `--latest`/menu option 1 just deletes that override to resume build-time tracking (it downloads nothing).
`BaseImageReleases.fetchChecksums()` reads `SHA256SUMS.v2` when the release has one (else `SHA256SUMS`) and looks checksums up by the exact file names the definition's URLs download (`checksumsFor`), never by markers in a name: `SHA256SUMS` must stay what isx releases before #1255 parse, and they file any name containing `-vm` as the VM image.
A tracked release without the definition's VM image keeps the VM on the built-in release (`BaseImage.vmTag`), tag and checksum together.
`downloadAndAliasImage` reuses an alias only when its fingerprint is the expected checksum (Incus fingerprints a one-tarball import by its sha256), or with none expected when it recorded the same URL (`IMAGE_SOURCE_PROPERTY`), since a release's two VM images share its tag.
`BuildCommand.vmImageAlias` picks the VM alias by file: the stock `-vm.tar.xz` under `<image>-vm`, which isx releases before #1255 share, and `-vm-direct.tar.xz` under `<image>-vm-direct`, which they never read.
Before downloading, an image whose fingerprint is the expected checksum, imported by the same project, is aliased as it is, since Incus keeps one image per fingerprint.
Incus resolves a fingerprint by prefix, so `downloadAndAliasImage` refuses a checksum that is not 64 hex digits before it looks anything up.
An event-driven CI job (`.github/workflows/update-base-image.yml`) opens a PR to bump the built-in fallback when the images repo publishes a newer release.
The images repo (`Sanne/incus-spawn-images`) fires a `base-image-released` `repository_dispatch` carrying the tag + container/VM checksums, so the consumer neither polls nor re-derives them (a manual `workflow_dispatch` backstop re-derives from the release list if a dispatch is missed).
This tracking behavior is unrelated to `INIT_VERSION`.

**DNF output and parallelism**: dnf steps render a single animated `TerminalProgress` spinner line instead of flooding the terminal -- so isx's own warnings stay visible.
Install/upgrade go through `runDnf`, which **streams** dnf's output through a parser (`onDnfLine` + the `DNF_STEP` regex) rather than echoing it: dnf5's non-TTY output prints one `[N/M] <action> <package>` line per completed step (both the download and transaction phases), so the spinner shows live "N/M -- current package" feedback (`formatDnfLine`).
dnf's non-TTY column truncates the version/arch tail off each NEVRA (and it can't be widened -- `COLUMNS`/`terminal_width` are ignored without a TTY, and a PTY only trades the clean per-line format for concurrent ANSI progress-bar redraws), so `shortenNevra` reduces each `name-epoch:ver-rel.arch` to its bare package name for the display detail.
Streaming without terminal echo is provided by `Container.execLines` -> `IncusClient.shellExecStreaming` -> `util/LineOutputStream` (UTF-8 line splitter).
COPR-enable and VM rootfs-expansion use `runWithSpinner` (captured exec, no live detail -- they're single/short ops).
Its `runSpinnerWork` wrapper (and `dnfWork`) convert a thrown exception into a recorded `failed` state, because `TerminalProgress` swallows task exceptions.
Otherwise the step would stay RUNNING and `finishSpinner` would throw a generic message with no cause.
The VM rootfs dependency install goes through `dnfCommand(...)` too, and is skipped when the resize tools are already present (the prebaked VM image ships them); `dnf copr enable`/`dnf clean` run plain `dnf` (the download/cache flags don't apply).
Full dnf output is printed to stderr only on failure, after the animated line, via the shared `finishSpinner`.
`runDnf` still retries once with `--refresh` on failure (the spinner shows "retrying with --refresh"). dnf has no built-in single-line progress mode -- `-q` would suppress the very lines we parse -- so consuming its native `[N/M]` output is the approach.
Shared dnf flags live in `DNF_BASE_OPTS` (built via `dnfCommand(...)`): `keepcache`, `metadata_expire`, `tsflags=nodocs`, and `max_parallel_downloads` (scaled to `CpuInfo.logicalCores()`, capped at dnf's practical max of 20) to speed the download phase; the rpm transaction itself is serial.

**Shared DNF cache volume**: builds of **both** instance types mount the `dnf-cache` custom volume at `/var/cache/libdnf5` (`mountDnfCache`) -- containers via bind mount, VMs via virtiofs.
Do not reintroduce a VM exclusion: without the volume every VM build re-downloads full repo metadata.
Two invariants protect the volume, which every build shares: (1) the volume is attached before the build instance starts (`attachDnfCache`), so a VM's incus-agent has mounted it before the first dnf run (no polling), and `unmountDnfCache` unmounts in the guest *before* `deviceRemove`; (2) `cleanCaches` must never `dnf clean`/`rm -rf` the cache path while it is a mount point in the build instance -- cleaning through a live mount would wipe the volume for every later build.
The check is per-instance and that is sufficient: another build attaching the volume to *its* instance cannot make it a mount point here.
`BuildCommandTest` covers both invariants (the cleanup guard by running the real script against stubbed `mountpoint`/`dnf`).

# Agent Context File

Every build regenerates `/etc/claude-code/CLAUDE.md` via `GuestProvisioning.writeAgentContext()`, called once in **both** build paths, in each case after all layer work (tools, skills, repo clones) is done.
So it sees the fully resolved image rather than one layer at a time, and the repos it lists have actually been cloned.
Content comes from `AgentContextGenerator` (`common/config/`), a pure renderer that takes a template name, tool names, repos and notes, which is what makes the format unit-testable without Incus.
The path constant lives in `ClaudeSetup.MANAGED_MEMORY_PATH` beside the rest of `/etc/claude-code`, since the generator itself is vendor-neutral.

When the resolved tools include `gemini-cli`, the same content is written to `~/.gemini/GEMINI.md` and owned by `agentuser`.
This file is build-owned and regenerated for each child template, including when Gemini is inherited without reconfiguration.
Project context files are untouched and `context.fileName` stays unset.

That path is Claude Code's **managed policy** memory layer: it loads ahead of `~/.claude/CLAUDE.md` and any project `CLAUDE.md`, layers concatenate rather than override, and it can't be suppressed via `claudeMdExcludes`.
So isx never merges with or overwrites a file a user or template owns -- no marker blocks, no read-modify-write.
It sits beside the `managed-settings.json`/`statusline.sh` that `ClaudeSetup` already owns there, and root ownership is correct, so **no chown**.

**What may go in the file**: one test governs every line -- *would omitting it cause a wrong action?*
That admits the framework preamble (passwordless sudo; credentials are in the proxy, not the container; autonomy is for investigation, not scope or outward-facing action; the "already installed / already cloned" lists that stop redundant `dnf install`/`git clone`) plus `agent_note` blocks.
Deliberately excluded: template/tool `description`, env vars (already in every login shell via `/etc/profile.d/isx-env.sh`), and host-resource mount semantics.
Do not "helpfully" add inventory back -- it costs context in every session and, for anything not fingerprinted, can assert something that stopped being true.

**Names and paths, never descriptions**: `ImageDef.contentFingerprint()` covers tool names and repo url/path, so those can't drift from the built image; `description` is deliberately *not* fingerprinted.
`agent_note` **is** fingerprinted on both `ImageDef` and `ToolDef` -- a stale warning baked into an image is worse than an extra rebuild.

The preamble must not assert anything conditionally true.
It says "you are running autonomously here" rather than naming `bypassPermissions`, because that mode comes from `ClaudeSetup`'s managed settings and only when the `claude` tool is installed; passwordless sudo, by contrast, is unconditional (`/etc/sudoers.d/agentuser`, written in `buildFromScratch`).
`Container.writeFile` picks a heredoc delimiter that no line of the content equals (extending `INCUS_EOF` with `_` until it is unambiguous), so user-authored content -- agent notes, tool `files:` blocks, template env, skills -- cannot close the heredoc early and is never mutated to avoid it.
Callers do not escape anything themselves.

# Host Repo Refresh

Builds no longer create or refresh user checkouts for acceleration (#1395); `--skip-git-refresh` is a compatibility no-op.
Explicit `update-all` still calls `HostRepoRefresh` and preserves its managed-shallow policy.
Checkout discovery and instance push/pull linking are unchanged.

# Parallel Repo Cloning

`RepoCloner.cloneRepos()` clones the repos a template declares first in its chain (`ImageDef.chainRepos`, one per destination; an inherited or repeated one is reused, and `ImageDef.repoConflict` refuses a differing redeclaration before the build, #1394) concurrently, bounded to `CpuInfo.highPerfCores()` (`util/CpuInfo.java` -- P-core count via macOS `sysctl`/the Linux sysfs scan below, else all logical CPUs).
`CpuInfo` is the single source of CPU-topology counts: `logicalCores()` (real host count, bypassing the native image's `-R:ActiveProcessorCount` cap; `ResourceLimits.hostProcessorCount()` delegates to it), `performanceCores()` (P/big cores, or 0 when indistinguishable; `VmManager.detectCpus()` uses it), `highPerfCores()`, and `hybridTopTierCores()` (on a hybrid host, the top tier's physical cores, SMT threads once; 0 on a host with one tier or unknown tiers; read once per process).
`ResourceLimits.defaultVmCpus()` gives a VM branch `max(1, min(8, host CPUs - 2))`, and on a hybrid host also at most `hybridTopTierCores()` (#1238, Sanne's choice).
Its Linux counts come from one sysfs scan of the online CPUs, whose top tier is taken from the hybrid PMU's `/sys/devices/cpu_core/cpus` where it exists (Intel hybrids with Hyper-Threading report equal `cpu_capacity`), else from `cpu_capacity` where it differs (ARM, Intel hybrids without SMT), else from `cpufreq/cpuinfo_max_freq` where it differs (AMD Zen 5 + Zen 5c hybrids), the top tier being every CPU within 80% of the highest, else all of them.
A physical core is its `core_cpus_list` (`thread_siblings_list` before 5.3), never package and core id, which repeat across clusters on device-tree Arm.
Whatever leaves the tier in doubt (an unreadable capacity, a capacity on some CPUs only, a missing core list) makes it unknown, which the VM default treats as one tier.
The scan of the real sysfs is made once per process.
`CpuInfoTest` pins each source with sysfs fixtures (an i7-12700H, a Ryzen AI 300, an X3D, a three-tier ARM SoC, an RK3588 device-tree layout).
For full clones, `GitPreparation` selects all advertised remote branches and tags in owned host staging, borrowing objects directly from a matching checkout without copying its packs first.
It never fetches into or maintains the user's repository.
Only HTTPS, SSH and Git network URLs qualify, with a protocol allowlist blocking local/file transports and remote helpers, including rewritten URLs.
SSH uses batch mode unless the user supplied an SSH command or executable.
The guest checks repository access using the template's accounts, and its complete advertised selection must match the snapshot before mounting.
All guest access checks finish before any prime can alter guest tools.
Only that remote's tracking tips are used for negotiation.
A failed reference clone, dissociation or integrity check discards the partial output and retries once without a reference before publication.
A warm snapshot requires matching fresh remote heads, tags, default HEAD and object format, plus a valid SHA-256 seal over all repository bytes.
Changed refs first fetch into staging that shares only the verified snapshot's immutable packs, with separate metadata and no automatic maintenance.
Incoming objects and new packs are checked, retained bytes must still match their sealed hashes, and every old root must remain reachable from the new selection.
Fast-forwards, merged branch deletions and HEAD changes can therefore reuse old packs without full-history repacking or fsck.
Removed history, a fetch/verification failure, an advertisement mismatch or more than 32 packs uses a fresh `clone --bare --no-local --reference … --dissociate` instead.
Failed staging is discarded before the rebuild; it never becomes a reference.
Bare dissociation avoids older Git's commit-graph/checkout bug, and rebuilt output passes full fsck before publication.
`PreparationCache` uses per-URL writer locks with a two-hour wait for slow preparation, coordinates leased readers, publishes complete generations atomically, and bounds retained snapshots even when a host checkout exists.
Only the clean prepared bare repository is mounted for `clone --no-hardlinks`; guest SHA-256 checks precede priming, and a failed check falls back to the remote.
Both prepared and network full clones start with all advertised branches and tags and a broad future-fetch refspec.
Shallow clones bypass the snapshot cache and report their depth plus guest/attach/detach timings.
See [Git preparation](../../docs/GIT-PREPARATION.md) for the selection, retention and failure contracts.
A repo with a `depth` never takes the local fast path (it ignores `--depth`): it clones the reference with `--no-local --depth`, then fetches its branch at that depth from the real origin (`shallowFixupCommand`).
From the network it is `--single-branch --depth`, and its refspec stays narrow (#1344).
Full preparation refuses shallow references and uses a standalone snapshot or the remote.
The shallow fixup then drops the host checkout's own branch and origin/HEAD, leaving a network clone's refs.
Clones and `prime` commands use captured (non-streamed) exec so parallel output doesn't garble; progress renders via `util/TerminalProgress.java`, the shared animated braille-spinner display also used by `HostRepoRefresh`'s parallel fetch and by `BuildCommand`'s dnf operations.
Each repo's `prime` command runs in the same worker once that repo's clone finishes **and every raw shallow host reference is detached** (Cloning -> Waiting to prime -> Priming within one progress line).
The shallow path still mounts the host checkout's working tree, so no prime may run next to any raw shallow reference (#765).
A prepared clone detaches its own clean snapshot before priming, while other clean preparations and copies may continue.
Which repos have a reference is decided on the host up front (`planReference`).
Each worker attaches its own just before its clone and detaches it right after (`DeviceMounts`, #826), both under one lock, because a device removal rewrites the whole device map and would drop an attach that landed meanwhile.
A VM has only 8 PCI hotplug slots, so attaches go through `HotplugSlots`: a budget starting at `min(references, 8)` (unbounded for containers), where an attach Incus refuses for want of a slot (`IncusClient.NoHotplugSlotException`) retires a permit for good and waits for another.
Only when the budget would reach zero does the repo clone from the network, with a `RepoReference.skipped` note on its line.
A VM's incus-agent mounts the share some time after the device add returns (#1233), so the reference clone's exec starts with `awaitMount` (`mountpoint -q` under `timeout`, at most `MOUNT_WAIT_SECONDS`, no extra request; skipped where the guest has no `mountpoint`); a share that never appears exits `MOUNT_TIMEOUT_EXIT` and the repo clones from the network.
A failed clone from a reference also says why on its line (`CloneResult.fallbackReason`): no fallback to the network is silent.
The `referencesDetached` latch counts *planned raw shallow* references, each counted down once in a `finally` after its attach is resolved and anything attached is detached.
A failed detach fails the build, before the count-down.
Concurrency is bounded by a semaphore in `prepareOne` held around clone and prime separately, never while waiting (`repoConcurrency()`).
Holding a slot while waiting would deadlock once referenced repos outnumber slots.
Project-local templates get no references (see `.claude/rules/config.md`).
Clone/prime failures are aggregated and abort the build; once any repo fails, workers whose clone finishes afterward skip launching their prime (best-effort fail-fast -- primes already running finish).

# Host Resources

`HostResourceSetup` (`config/HostResourceSetup.java`) handles sharing host files/directories with containers.
Three modes: `readonly` (Incus disk device), `overlay` (overlayfs with container-local writable upper layer), `copy` (baked into template).
Applied before tools during build so caches are available.
Devices are removed from stopped templates and re-attached at branch time from JSON metadata stored in `user.incus-spawn.host-resources`.
Overlay mounts persist across reboots via a systemd service inside the container.
**Attach before start (#828):** every disk device a build needs (host-resource `readonly` mounts and `overlay` lower layers via `attachBuildDevices`, the DNF cache via `attachDnfCache`) is added in the create→start window; `applyForBuild` does only the in-guest half after start.
On a VM a device present at boot gets its own PCIe port, while a hot-plug takes one of only 8 spare slots, which repo references need; and incus-agent mounts boot-time shares before serving any exec, so nothing polls for them.
Do not move a device attach after `start` (the inbox is attached pre-start too, `InstanceLifecycle.attachInbox`).
Mounts can create `/home/agentuser` before `useradd`, which is why user creation uses `AGENT_HOME_OWNERSHIP` rather than `chown -R`.
Because mounts exist from first boot, `readonly`/`overlay` targets under system directories (`FORBIDDEN_MOUNT_ROOTS`) are rejected at build, validation and branch, but not at start (`requireAllowedMountTarget`).
The overlay unit is ordered `After=incus-agent.service` so a VM's lower layers are mounted before it runs.
See DESIGN.md "Devices are attached before start" and "Mounts may not target system directories".
File-level resources (not directories) fall back to `copy` mode on VMs since disk devices only support directories.

# Download Caching

`DownloadCache` handles host-side download caching with SHA256 verification.
`download()` accepts only `http(s)` and refuses loopback/wildcard/link-local hosts, checking every redirect hop itself (`Redirect.NEVER`).
A 429/500/502/503/504 or a failed or broken connection is retried twice from the original URL (`RETRY_DELAYS`, or `Retry-After` up to 30s), re-running the host-local check before each attempt; unresolvable names, TLS failures and local write errors are not retried.
A retry after a body broke off resumes it with `Range`/`If-Range` when the `200` had a strong validator and a length, appending only a `206` whose validator, `Content-Range` and length match (`Partial`); a `416` or any other `206` starts over from 0 within the same attempt.
`Listener.retrying()` lets `TransferProgress` reset its rate and show the retry; the next `received()` reports the offset it resumes from, which the rate does not count.
Errors name the failing hop's host.
`file://` goes only through `downloadAllowingLocalFile()`, which only base images use; see DESIGN.md "Host-side downloads reach only remote hosts".
A `DownloadCache.Listener` observes a download (cache verification, bytes against `Content-Length`, checksum).
Base images pass `BuildProgress.TransferProgress`, which the `runLiveStep` spinner formats on each frame into percentage, size, rate and time left.
The Incus import that follows is a separate spinner showing elapsed time.
YAML tool downloads may be exposed as a file via `destination_file`, extracted, or both.
Containers receive files directly and host-extracted archive contents recursively; VMs use temporary read-only mounts and copy locally to avoid slow incus-agent file transfers over vsock.
This avoids needing curl inside containers, while `extract_in_container` remains available when extraction must happen there.
