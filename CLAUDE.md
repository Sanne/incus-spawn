# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

incus-spawn (`isx`) is a CLI tool for managing isolated Incus-based development environments. It creates full Linux system containers (not Docker-style app containers) with copy-on-write branching, a MITM TLS proxy for credential isolation, and an interactive TUI. See README.md for user-facing docs, DESIGN.md for architecture rationale, and [docs/CHARACTER.md](docs/CHARACTER.md) for the project's mission and design philosophy.

**Keep docs in sync**: When making architectural changes (new proxy capabilities, new tool types, new init steps, module structure changes, CI job changes, new intercepted domains, etc.), update both this file and DESIGN.md in the same PR. CLAUDE.md is the quick-reference for contributors; DESIGN.md is the full rationale. Both must stay current.

## Build and Test Commands

```shell
mvn package                    # Build both modules (CLI: cli/target/, proxy: proxy/target/)
mvn test                       # Unit tests only (no Incus required)
mvn verify -DskipITs=false     # Unit + integration tests (requires running Incus)
mvn test -Dtest=ToolDefTest    # Run a single test class
mvn test -Dtest=ToolDefTest#testAllFields  # Run a single test method

mvn package -Dnative -DskipTests           # GraalVM native binaries (isx + isx-proxy)

./install.sh                   # Build and install JVM version to ~/.local/bin/isx
./install.sh --native          # Build and install native binaries
```

## Tech Stack

- **Java 25**, **Quarkus 3.x** with aesh for CLI commands
- **Tamboui** for the interactive TUI (terminal UI framework)
- **Jackson YAML** for configuration/definition parsing
- **Quarkus CDI** for dependency injection (tool discovery, command wiring)

## Module Structure

Three Maven modules under a parent POM:

- **`common`** (`incus-spawn-common`): shared code — Incus client, proxy config, image/tool definitions, configuration loading. Not a Quarkus app; uses the Jandex Maven plugin to produce a `META-INF/jandex.idx` so Quarkus discovers its CDI beans and `@RegisterForReflection` annotations from dependent modules.
- **`cli`** (`incus-spawn`): the main CLI/TUI binary (`isx`). Depends on common. Native image: serial GC, `-Os` (size-optimized), `-H:-AllowVMInternalThreads`.
- **`proxy`** (`incus-spawn-proxy`): the standalone MITM proxy binary (`isx-proxy`). Depends on common. Native image: G1 GC, `-O3` (throughput-optimized, enables ML-inferred PGO).

Both `cli` and `proxy` are independent Quarkus applications that produce separate native binaries. When `isx-proxy` is not installed, `isx proxy start` falls back to running the proxy inline within the CLI process.

## Architecture

### Entry Point and Command Structure

`IncusSpawn.java` is the aesh `@CommandDefinition` top command. With no subcommand, it launches the TUI (`ListCommand`). Each subcommand in `command/` is an aesh `@CommandDefinition` with Quarkus DI.

### Init and Versioned Completion

`InitCommand` runs first-time setup (dependencies, Incus, firewall, CA, proxy service, etc.). Commands that need a working environment call `InitCommand.requireInit()`, which auto-launches init if it hasn't completed. Completion is tracked by a sentinel file `~/.config/incus-spawn/.init-complete` containing `INIT_VERSION` — a version integer defined in `InitCommand`. Every init step is idempotent, so re-running is safe.

**When to bump `INIT_VERSION`**: increment it when adding a new infrastructure step to init that existing installations need (new dependency, firewall rule, systemd service, config field). A sentinel *older* than `INIT_VERSION` causes `hasBeenInitialized()` to return false, triggering a re-run on the next command. Do NOT bump it for changes that don't affect host configuration (new template features, TUI changes, proxy logic changes).

The comparison is `>=`, not equality: the sentinel is a monotonic floor, so a binary that finds a *newer* sentinel treats itself as initialized rather than concluding init never ran. This matters when two isx builds are installed at once (e.g. `/usr/bin/isx` and `~/.local/bin/isx`) — under equality the older one both hard-failed the proxy service and re-ran init to write the sentinel back down, ping-ponging with the newer binary. Never renumber `INIT_VERSION` downward.

### Image Hierarchy and Build System

Templates are YAML definitions (`common/src/main/resources/images/`) with optional parent inheritance forming a chain: `tpl-minimal` -> `tpl-dev` -> `tpl-java`. Building an image auto-builds missing parents. Each definition can set `type` (`container`, `vm`, or `kvm`) which inherits through the parent chain via `inheritTypes()` at `ImageDef.loadAll()` time. VM definitions also support `vm_image_url` and `vm_image_sha256` for a pre-baked VM base image.

`BuildCommand` has two build paths:
- **`buildFromScratch`** (root image, no parent): launches base OS, configures security/DNS/user, installs packages and tools
- **`buildFromParent`** (derived image): copies parent via CoW, applies only the delta (new packages/tools)

For VMs, `buildFromScratch` applies the entire ancestor chain from YAML definitions — parent Incus instances are not needed. `buildChain` detects type changes (container→VM) and skips unnecessary parent rebuilds. Container-specific security config (raw.idmap, nesting, setxattr interception) is skipped for VMs. Tool downloads use a mount-and-copy strategy instead of file push (vsock can't handle large pushes). The `--type` CLI flag overrides the definition's type; `effectiveVm()` resolves the effective VM status considering both the flag and definition.

Package deduplication: `BuildCommand` collects all ancestor packages and subtracts them from the install list so derived images only install what's new.

**Host repo refresh**: Before building, `HostRepoRefresh` (`git/HostRepoRefresh.java`) fetches host-side git repos matching image definition repos so reference-clone optimization uses current objects. Optionally clones missing repos (persisted via `auto-clone-repos` config). `--skip-git-refresh` bypasses the refresh. `update-all` only fetches.

**Parallel repo cloning**: `BuildCommand.cloneRepos()` clones a template's declared repos concurrently, bounded to `CpuInfo.highPerfCores()` (`util/CpuInfo.java` — P-core count via macOS `sysctl`/Linux `cpu_capacity`, else all logical CPUs). `CpuInfo` is the single source of CPU-topology counts: `logicalCores()` (real host count, bypassing the native image's `-R:ActiveProcessorCount` cap; `ResourceLimits.hostProcessorCount()` delegates to it), `performanceCores()` (P/big cores, or 0 when indistinguishable; `VmManager.detectCpus()` uses it), and `highPerfCores()`. Clones and `prime` commands use captured (non-streamed) exec so parallel output doesn't garble; progress renders via `util/TerminalProgress.java`, the shared animated braille-spinner display also used by `HostRepoRefresh`'s parallel fetch. Each repo's `prime` command runs in the same worker as soon as that repo's clone finishes (Cloning → Priming within one progress line), so priming pipelines with the remaining clones instead of waiting at a barrier. Incus device add/remove (host-reference mounts) must not run concurrently, so they bracket the parallel section in serial phases: mount-all → clone-and-prime-all-in-parallel → remove-all. Clone/prime failures are aggregated and abort the build; once any repo fails, workers whose clone finishes afterward skip launching their prime (best-effort fail-fast — primes already running finish).

### Host Resources

`HostResourceSetup` (`config/HostResourceSetup.java`) handles sharing host files/directories with containers. Three modes: `readonly` (Incus disk device), `overlay` (overlayfs with container-local writable upper layer), `copy` (baked into template). Applied before tools during build so caches are available. Devices are removed from stopped templates and re-attached at branch time from JSON metadata stored in `user.incus-spawn.host-resources`. Overlay mounts persist across reboots via a systemd service inside the container. VM-specific: virtiofs disk devices are mounted asynchronously by the incus-agent, so overlay mounts poll `mountpoint -q` for up to 15s before overlaying. File-level resources (not directories) fall back to `copy` mode on VMs since disk devices only support directories.

### Tool System

`ToolSetup` interface with two implementations:
- **YAML tools** (`ToolDef` + `YamlToolSetup`): declarative definitions in `common/src/main/resources/tools/`. Execution order: packages -> downloads -> run -> run_as_user -> files -> verify. Environment variables are declared via `env:` entries and collected centrally by `BuildCommand.writeEnvFile()`.
- **Java tools** (CDI `@Dependent` beans implementing `ToolSetup`): for tools needing programmatic logic (`ClaudeSetup`, `CodexSetup`, `GhSetup`, `PiSetup`, `BobSetup`). Declare env vars via `envEntries(Map<String,String>)` method. Tools can declare a `feature()` to gate themselves behind an opt-in feature flag in `SpawnConfig.features`.

Resolution via `ToolDefLoader` (later overrides earlier): built-in YAML -> user YAML -> search paths -> project-local YAML. Java CDI tools are used as fallback when no YAML tool matches.

Tools can declare runtime actions (`ActionEntry`) shown in the TUI's F9 actions menu and available via `RunCommand` (`isx run`). Both YAML tools (via `actions:` in the YAML) and Java/CDI tools (via `ToolSetup.actions()`) can contribute actions. Templates select a default action via `ImageDef.defaultAction` (`default-action` in YAML), which is run on Enter in the TUI or when executing `isx run <instance>`. The reference format is `tool-name` (single action) or `tool-name:action-id` (multiple actions). `default-action` inherits through the parent chain (child overrides parent) and is intentionally excluded from `contentFingerprint()` so changing it doesn't trigger template rebuilds.

Action resolution logic is centralized in `ActionResolver`, shared by both `ListCommand` (TUI) and `RunCommand` (CLI). `ActionResolver` handles discovering actions from installed tools, resolving default actions from template inheritance chains, finding specific actions by reference, and building `ActionContext` for execution.

**Important**: Built-in YAML files are listed in a hardcoded `BUILTIN_FILES` constant (not classpath scanning) because GraalVM native image makes classpath directory listing unreliable. When adding a built-in image or tool, you must update the corresponding `BUILTIN_FILES` list.

### Environment Variable System

`EnvEntry` (`config/EnvEntry.java`) models a declarative env var with four strategies: `SET`, `SET_IF_UNSET`, `PREPEND`, `APPEND`. Supports backward-compatible raw shell strings via a custom `ListDeserializer` that handles mixed-type YAML lists (strings and maps). Both `ToolDef.env` and `ImageDef.env` use this model.

`EnvResolver` (`config/EnvResolver.java`) collects sourced entries from the template parent chain and all tools, validates consistency (set+set with different values → `EnvConflictException` naming both sources), and generates the shell script for `/etc/profile.d/isx-env.sh`.

`BuildCommand.writeEnvFile()` orchestrates collection: built-in entries (`ISX_CONTAINER`, `ISX_TEMPLATE`) → template chain env → tool `envEntries()`. Called after `runToolSetup()` in both `buildFromScratch` and `buildFromParent`. `linkJavaTrustStores()` runs after `writeEnvFile()` and symlinks any JDK `cacerts` under `/usr/lib/jvm` or `/opt` to the system trust store (`/etc/pki/java/cacerts`), so non-Fedora JDKs (GraalVM, labsjdk, etc.) trust the MITM CA without needing `JAVA_TOOL_OPTIONS`.

### Incus Interaction

`IncusClient` communicates with the Incus daemon via its REST API. On Linux, requests go over a Unix domain socket (`UnixSocketTransport`); on macOS, over a vsock tunnel exposed as a Unix socket (same `UnixSocketTransport`). `IncusApi.tryConnect()` selects Linux Unix sockets → vsock Unix socket; there is no HTTPS fallback (the old HTTPS-over-TCP path was removed — it hit macOS Local Network prompts and VPN socket filters, and two transports made field issues undiagnosable; `HttpsTransport` remains in the tree but is unwired). `IncusApi` handles request serialization, async operation waiting, and WebSocket-based exec (capture, stream, PTY). `Container` is a helper for running commands inside a specific container (`exec`, `runAsUser`, `runInteractive`). The `incus` CLI binary is not required at runtime.

**macOS vsock robustness**: the vfkit vsock tunnel does not reliably propagate connection close/EOF, which drives several design choices (see DESIGN.md "Transport" and appliance/DESIGN.md):
- **Exec completion via `/wait`, not close frames.** `IncusApi.execWebSocket` unifies capture/stream/bidirectional exec and takes the operation `/wait` endpoint (daemon operation state over HTTP) as the authoritative completion + exit-code signal, then drains and force-closes the data sockets — so a lost close frame can't hang exec. Every exec fd is keepalive-pinged; the drain is adaptive.
- **Keep-alive connection cache.** Short request-path calls (`get`/`post`/`/wait`) reuse a warm connection via `requestPooled` → `ConnectionPool`/`KeepAliveConnection` instead of reconnecting per call; the exec WebSocket fds are per-operation and not pooled.
- **Forwarder leak + recovery.** The same close-propagation gap makes the in-VM `socat` forwarder leak connections. A `socat -T` inactivity backstop reaps them; `isx doctor` diagnoses it (host-side connection gauge in `UnixSocketTransport` / `vm status` vs the in-VM `isx-agent`'s socat count, localizing vfkit vs forwarder) and can restart the forwarder via the agent **without rebooting the VM**. `ClientLog` is a file-only (TUI-safe) diagnostic log for expected-but-noisy events like stale-connection recycling.

### MITM TLS Proxy

`MitmProxy` (in `common/src/main/java/dev/incusspawn/proxy/`) is a TLS-terminating proxy that intercepts HTTPS to specific domains and injects real auth credentials, so containers only hold placeholder values. Key design:
- Listens on gateway IP:18443 (iptables redirects 443->18443 on the bridge)
- Per-domain certs signed by a custom CA (installed in templates during build). The CA lives at `~/.config/incus-spawn/ca.{crt,key}`; leaf certs are persisted by `CertStore` under `~/.config/incus-spawn/certs/` (`<domain>.crt`/`.key`, wildcards as `_wildcard.<domain>`) and reused across proxy restarts, re-minting only on miss/CA-rotation/near-expiry. Persisting is what keeps each leaf's `notBefore` stable: the proxy is relaunched frequently (macOS launchd `KeepAlive`), and re-minting on a host whose clock has jumped ahead of a lagging container clock (e.g. an Incus VM after macOS resume) produced certs the container rejected as "not yet valid". Certs are keyed by domain, never by container (a leaf is a function of `(domain, CA)`), so this composes with future per-container interception, which is a routing/DNS concern. `CertificateAuthority.BACKDATE_MS` backdates `notBefore` as a skew margin for the rare fresh-mint moments.
- Both CA and leaf certs carry RFC 5280 key identifiers: SKI on the CA, SKI + AKI on leaves. Strict validators (OpenSSL 3.5, and so Python 3.13+, which turns on `VERIFY_X509_STRICT` by default) reject a chain without them — including the trust anchor, so leaf-only extensions are not enough. A CA generated before this is re-issued on load over its **existing key** (`reissueWithSki`), which keeps every leaf valid and un-re-minted; the replaced cert is kept as `ca-superseded.crt`. Images stamped with that superseded fingerprint carry a stale-but-not-foreign anchor: `BranchCommand` lets them branch (the new cert is pushed into the instance by `InstancePrep`/`fixContainerCaIfNeeded` on first use) instead of demanding a rebuild the way a real CA rotation does.
- Three auth modes for Anthropic domains (priority: Vertex > OAuth > API key): OAuth mode strips `x-api-key` and injects `Authorization: Bearer <token>` for Claude Pro/Max users; Vertex mode does three-way routing — passthrough for Vertex-formatted requests, standard-to-Vertex translation for `/v1/messages` (using `VERTEX_ALLOWED_FIELDS` body allowlist), and direct forwarding for non-messages endpoints; API key mode replaces `x-api-key` with the real key
- OpenAI support (behind `openai` feature flag): intercepts `api.openai.com` and injects `Authorization: Bearer <openai-api-key>`
- WebSocket passthrough: handles Upgrade requests by establishing an upstream WebSocket connection (with credential injection), then relaying frames bidirectionally with keepalive pings and close-code propagation. Used by Codex CLI for `api.openai.com`
- Caches OCI blobs by SHA256, Maven artifacts by coordinate, and npm tarballs from `registry.npmjs.org` with ETag-based packument verification

### TUI

`ListCommand` is the TUI implementation (~1800 lines) using Tamboui widgets. Two-panel layout (Templates + Instances) with modal dialogs for branching, renaming, and building.

### Configuration Loading

- `SpawnConfig`: global config from `~/.config/incus-spawn/config.yaml`
- `ImageDef.loadAll()`: discovers all image definitions across resolution layers
- `ToolDefLoader`: discovers tools across resolution layers
- `ProjectConfig`: per-project config from `incus-spawn.yaml` or `.incus-spawn/incus-spawn.yaml`

Resolution order for both images and tools (later overrides earlier): built-in -> user (`~/.config/incus-spawn/`) -> search paths -> project-local (`.incus-spawn/`).

### Download Caching

`DownloadCache` handles host-side download caching with SHA256 verification. Archives are downloaded and extracted on the host, then pushed into containers. This avoids needing tar/curl inside containers.

## CI Integration Tests

`.github/workflows/test-integration.yml` runs on every push/PR to `main`. Key jobs:

- **`unit-tests`**: `mvn package` (no Incus required)
- **`build-native-cli`**: builds the CLI native image, uploads artifact
- **`build-native-proxy`**: builds the proxy native image, uploads artifact
- **`integration-tests`**: boots the appliance VM image under QEMU, checks it reaches `ISX READY` and passes an Incus smoke test
- **`isx-integration-tests-jvm`**: installs Incus on Ubuntu 24.04, builds isx from the unit-tests artifact, runs `isx init`, starts the MITM proxy, builds templates (`tpl-minimal`, `tpl-test-podman`, `tpl-test-vm`), then runs test scripts inside branched instances
- **`isx-integration-tests-native`**: same as jvm but uses native binaries from the build-native jobs
- **`fresh-daemon-init`**: verifies `isx init` on a daemon that has never been initialized

Each job runs on its own freshly-provisioned runner, so jobs never inherit each other's Incus state.

`fresh-daemon-init` exists because `isx-integration-tests` runs `incus admin init --minimal` *before*
`isx init`, which populates the default profile — so it cannot catch `isx init` failing to populate it
itself. It installs Incus and creates only the storage pool, with no `admin init`, reproducing the
state where a pool exists but the default profile is empty (every instance creation then fails with
"Failed getting root disk: No root device could be found"). It asserts the profile has a root disk and
a NIC, then launches a real instance — a profile that merely looks right can still name a bad pool.

Note that `isx init` cannot be tested against the QEMU appliance VM on Linux: isx connects to the
*natively installed* Incus over `/run/incus/unix.socket` and the QEMU boot path exposes no vsock
socket, so it would hit the runner's own daemon and report a misleading success (see the guard in
`appliance/test-with-isx.sh`). The appliance also provisions Incus with its own shell script and never
calls `isx init` — the "ensure default profile has a root disk and NIC" invariant is implemented twice,
in `incus-spawn-vm-init` (shell, in-VM) and `IncusClient.ensureDefaultProfileDevices` (Java, host).
Keep the two in sync.

The `isx-integration-tests` job exercises three environments: a container (from `tpl-minimal`), a rootless-podman container (from `tpl-test-podman`), and a VM (from `tpl-test-vm`). Test scripts live in `.github/scripts/`:

- **`test-instance.sh`**: pushed into containers and VMs, tests proxy interception (Maven/GitHub HTTPS), git clone, passwordless sudo, systemd lifecycle, DNS interception, login shell env vars, and TLS certificate quality. Uses `assert()` / `assert_eq()` shell helpers.
- **`test-podman.sh`**: pushed into the podman container, tests rootless podman (pull, run, build).

When adding a new end-to-end test, add an `assert` call in the appropriate script under a new numbered section. The test runs as root inside the container; use `su -l agentuser -c "..."` to test user-level behavior. The `tpl-minimal` base image is Fedora with only git, curl, which, procps-ng, and findutils — install extra packages with `dnf install` inside the test if needed.

## Benchmarking

`bench/run.sh` measures native image performance: binary size, startup time, memory (idle and peak RSS), throughput, and latency. See `bench/README.md` for full documentation.

```shell
bench/run.sh                              # Build native image + benchmark
bench/run.sh --skip-build                 # Reuse existing binary
bench/run.sh --label "before-my-change"   # Tag results for comparison
```

Requires Oracle GraalVM with `native-image`, a running Incus daemon, and a working `isx init` setup. Results are saved as JSON to `bench/results/` and automatically compared with the previous run. Use this before and after changes to the proxy, Vert.x configuration, or native image settings to catch regressions.
