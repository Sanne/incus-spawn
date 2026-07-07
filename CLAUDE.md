# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

incus-spawn (`isx`) is a CLI tool for managing isolated Incus-based development environments. It creates full Linux system containers (not Docker-style app containers) with copy-on-write branching, a MITM TLS proxy for credential isolation, and an interactive TUI. See README.md for user-facing docs, DESIGN.md for architecture rationale, and [docs/CHARACTER.md](docs/CHARACTER.md) for the project's mission and design philosophy.

## Build and Test Commands

```shell
mvn package                    # Build (produces target/quarkus-app/quarkus-run.jar)
mvn test                       # Unit tests only (no Incus required)
mvn verify -DskipITs=false     # Unit + integration tests (requires running Incus)
mvn test -Dtest=ToolDefTest    # Run a single test class
mvn test -Dtest=ToolDefTest#testAllFields  # Run a single test method

mvn package -Prelease -DskipTests          # Uber-jar for distribution
mvn package -Dnative -Dquarkus.native.container-build=true  # GraalVM native binary

./install.sh                   # Build and install JVM version to ~/.local/bin/isx
./install.sh --native          # Build and install native binary
```

## Tech Stack

- **Java 17**, **Quarkus 3.x** with picocli for CLI commands
- **Tamboui** for the interactive TUI (terminal UI framework)
- **Jackson YAML** for configuration/definition parsing
- **Quarkus CDI** for dependency injection (tool discovery, command wiring)

## Architecture

### Entry Point and Command Structure

`IncusSpawn.java` is the picocli `@TopCommand`. With no subcommand, it launches the TUI (`ListCommand`). Each subcommand in `command/` is a picocli `@Command` with Quarkus DI.

### Image Hierarchy and Build System

Templates are YAML definitions (`src/main/resources/images/`) with optional parent inheritance forming a chain: `tpl-minimal` -> `tpl-dev` -> `tpl-java`. Building an image auto-builds missing parents. Each definition can set `type` (`container`, `vm`, or `kvm`) which inherits through the parent chain via `inheritTypes()` at `ImageDef.loadAll()` time. VM definitions also support `vm_image_url` and `vm_image_sha256` for a pre-baked VM base image.

`BuildCommand` has two build paths:
- **`buildFromScratch`** (root image, no parent): launches base OS, configures security/DNS/user, installs packages and tools
- **`buildFromParent`** (derived image): copies parent via CoW, applies only the delta (new packages/tools)

For VMs, `buildFromScratch` applies the entire ancestor chain from YAML definitions — parent Incus instances are not needed. `buildChain` detects type changes (container→VM) and skips unnecessary parent rebuilds. Container-specific security config (raw.idmap, nesting, setxattr interception) is skipped for VMs. Tool downloads use a mount-and-copy strategy instead of file push (vsock can't handle large pushes). The `--type` CLI flag overrides the definition's type; `effectiveVm()` resolves the effective VM status considering both the flag and definition.

Package deduplication: `BuildCommand` collects all ancestor packages and subtracts them from the install list so derived images only install what's new.

### Host Resources

`HostResourceSetup` (`config/HostResourceSetup.java`) handles sharing host files/directories with containers. Three modes: `readonly` (Incus disk device), `overlay` (overlayfs with container-local writable upper layer), `copy` (baked into template). Applied before tools during build so caches are available. Devices are removed from stopped templates and re-attached at branch time from JSON metadata stored in `user.incus-spawn.host-resources`. Overlay mounts persist across reboots via a systemd service inside the container. VM-specific: virtiofs disk devices are mounted asynchronously by the incus-agent, so overlay mounts poll `mountpoint -q` for up to 15s before overlaying. File-level resources (not directories) fall back to `copy` mode on VMs since disk devices only support directories.

### Tool System

`ToolSetup` interface with two implementations:
- **YAML tools** (`ToolDef` + `YamlToolSetup`): declarative definitions in `src/main/resources/tools/`. Execution order: packages -> downloads -> run -> run_as_user -> files -> verify. Environment variables are declared via `env:` entries and collected centrally by `BuildCommand.writeEnvFile()`.
- **Java tools** (CDI `@Dependent` beans implementing `ToolSetup`): for tools needing programmatic logic (`ClaudeSetup`, `GhSetup`, `PiSetup`). Declare env vars via `envEntries(Map<String,String>)` method.

Resolution via `ToolDefLoader` (later overrides earlier): built-in YAML -> user YAML -> search paths -> project-local YAML. Java CDI tools are used as fallback when no YAML tool matches.

Tools can declare runtime actions (`ActionEntry`) shown in the TUI's F9 actions menu and available via `RunCommand` (`isx run`). Both YAML tools (via `actions:` in the YAML) and Java/CDI tools (via `ToolSetup.actions()`) can contribute actions. Templates select a default action via `ImageDef.defaultAction` (`default-action` in YAML), which is run on Enter in the TUI or when executing `isx run <instance>`. The reference format is `tool-name` (single action) or `tool-name:action-id` (multiple actions). `default-action` inherits through the parent chain (child overrides parent) and is intentionally excluded from `contentFingerprint()` so changing it doesn't trigger template rebuilds.

Action resolution logic is centralized in `ActionResolver`, shared by both `ListCommand` (TUI) and `RunCommand` (CLI). `ActionResolver` handles discovering actions from installed tools, resolving default actions from template inheritance chains, finding specific actions by reference, and building `ActionContext` for execution.

**Important**: Built-in YAML files are listed in a hardcoded `BUILTIN_FILES` constant (not classpath scanning) because GraalVM native image makes classpath directory listing unreliable. When adding a built-in image or tool, you must update the corresponding `BUILTIN_FILES` list.

### Environment Variable System

`EnvEntry` (`config/EnvEntry.java`) models a declarative env var with four strategies: `SET`, `SET_IF_UNSET`, `PREPEND`, `APPEND`. Supports backward-compatible raw shell strings via a custom `ListDeserializer` that handles mixed-type YAML lists (strings and maps). Both `ToolDef.env` and `ImageDef.env` use this model.

`EnvResolver` (`config/EnvResolver.java`) collects sourced entries from the template parent chain and all tools, validates consistency (set+set with different values → `EnvConflictException` naming both sources), and generates the shell script for `/etc/profile.d/isx-env.sh`.

`BuildCommand.writeEnvFile()` orchestrates collection: built-in entries (`ISX_CONTAINER`, `ISX_TEMPLATE`, `JAVA_TOOL_OPTIONS` truststore prepend) → template chain env → tool `envEntries()`. Called after `runToolSetup()` in both `buildFromScratch` and `buildFromParent`.

### Incus Interaction

`IncusClient` communicates with the Incus daemon via its REST API. On Linux, requests go over a Unix domain socket (`UnixSocketTransport`); on macOS, over a vsock tunnel exposed as a Unix socket (same `UnixSocketTransport`). `IncusApi.tryConnect()` selects Linux Unix sockets → vsock Unix socket; there is no HTTPS fallback (the old HTTPS-over-TCP path was removed — it hit macOS Local Network prompts and VPN socket filters, and two transports made field issues undiagnosable; `HttpsTransport` remains in the tree but is unwired). `IncusApi` handles request serialization, async operation waiting, and WebSocket-based exec (capture, stream, PTY). `Container` is a helper for running commands inside a specific container (`exec`, `runAsUser`, `runInteractive`). The `incus` CLI binary is not required at runtime.

**macOS vsock robustness**: the vfkit vsock tunnel does not reliably propagate connection close/EOF, which drives several design choices (see DESIGN.md "Transport" and appliance/DESIGN.md):
- **Exec completion via `/wait`, not close frames.** `IncusApi.execWebSocket` unifies capture/stream/bidirectional exec and takes the operation `/wait` endpoint (daemon operation state over HTTP) as the authoritative completion + exit-code signal, then drains and force-closes the data sockets — so a lost close frame can't hang exec. Every exec fd is keepalive-pinged; the drain is adaptive.
- **Keep-alive connection cache.** Short request-path calls (`get`/`post`/`/wait`) reuse a warm connection via `requestPooled` → `ConnectionPool`/`KeepAliveConnection` instead of reconnecting per call; the exec WebSocket fds are per-operation and not pooled.
- **Forwarder leak + recovery.** The same close-propagation gap makes the in-VM `socat` forwarder leak connections. A `socat -T` inactivity backstop reaps them; `isx doctor` diagnoses it (host-side connection gauge in `UnixSocketTransport` / `vm status` vs the in-VM `isx-agent`'s socat count, localizing vfkit vs forwarder) and can restart the forwarder via the agent **without rebooting the VM**. `ClientLog` is a file-only (TUI-safe) diagnostic log for expected-but-noisy events like stale-connection recycling.

### MITM TLS Proxy

`MitmProxy` (in `proxy/`) is a TLS-terminating proxy that intercepts HTTPS to specific domains and injects real auth credentials, so containers only hold placeholder values. Key design:
- Listens on gateway IP:18443 (iptables redirects 443->18443 on the bridge)
- Per-domain certs signed by a custom CA (installed in templates during build). The CA lives at `~/.config/incus-spawn/ca.{crt,key}`; leaf certs are persisted by `CertStore` under `~/.config/incus-spawn/certs/` (`<domain>.crt`/`.key`, wildcards as `_wildcard.<domain>`) and reused across proxy restarts, re-minting only on miss/CA-rotation/near-expiry. Persisting is what keeps each leaf's `notBefore` stable: the proxy is relaunched frequently (macOS launchd `KeepAlive`), and re-minting on a host whose clock has jumped ahead of a lagging container clock (e.g. an Incus VM after macOS resume) produced certs the container rejected as "not yet valid". Certs are keyed by domain, never by container (a leaf is a function of `(domain, CA)`), so this composes with future per-container interception, which is a routing/DNS concern. `CertificateAuthority.BACKDATE_MS` backdates `notBefore` as a skew margin for the rare fresh-mint moments.
- Three auth modes for Anthropic domains (priority: Vertex > OAuth > API key): OAuth mode strips `x-api-key` and injects `Authorization: Bearer <token>` for Claude Pro/Max users; Vertex mode does three-way routing — passthrough for Vertex-formatted requests, standard-to-Vertex translation for `/v1/messages` (using `VERTEX_ALLOWED_FIELDS` body allowlist), and direct forwarding for non-messages endpoints; API key mode replaces `x-api-key` with the real key
- Caches OCI blobs by SHA256 and Maven artifacts by coordinate

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

## Benchmarking

`bench/run.sh` measures native image performance: binary size, startup time, memory (idle and peak RSS), throughput, and latency. See `bench/README.md` for full documentation.

```shell
bench/run.sh                              # Build native image + benchmark
bench/run.sh --skip-build                 # Reuse existing binary
bench/run.sh --label "before-my-change"   # Tag results for comparison
```

Requires Oracle GraalVM with `native-image`, a running Incus daemon, and a working `isx init` setup. Results are saved as JSON to `bench/results/` and automatically compared with the previous run. Use this before and after changes to the proxy, Vert.x configuration, or native image settings to catch regressions.
