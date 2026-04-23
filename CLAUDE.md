# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

incus-spawn (`isx`) is a CLI tool for managing isolated Incus-based development environments. It creates full Linux system containers (not Docker-style app containers) with copy-on-write branching, a MITM TLS proxy for credential isolation, and an interactive TUI. See README.md for user-facing docs and DESIGN.md for architecture rationale.

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

Templates are YAML definitions (`src/main/resources/images/`) with optional parent inheritance forming a chain: `tpl-minimal` -> `tpl-dev` -> `tpl-java`. Building an image auto-builds missing parents.

`BuildCommand` has two build paths:
- **`buildFromScratch`** (root image, no parent): launches base OS, configures security/DNS/user, installs packages and tools
- **`buildFromParent`** (derived image): copies parent via CoW, applies only the delta (new packages/tools)

Package deduplication: `BuildCommand` collects all ancestor packages and subtracts them from the install list so derived images only install what's new.

### Host Resources

`HostResourceSetup` (`config/HostResourceSetup.java`) handles sharing host files/directories with containers. Three modes: `readonly` (Incus disk device), `overlay` (overlayfs with container-local writable upper layer), `copy` (baked into template). Applied before tools during build so caches are available. Devices are removed from stopped templates and re-attached at branch time from JSON metadata stored in `user.incus-spawn.host-resources`. Overlay mounts persist across reboots via a systemd service inside the container.

### Tool System

`ToolSetup` interface with two implementations:
- **YAML tools** (`ToolDef` + `YamlToolSetup`): declarative definitions in `src/main/resources/tools/`. Execution order: packages -> downloads -> run -> run_as_user -> files -> env -> verify
- **Java tools** (CDI `@Dependent` beans implementing `ToolSetup`): for tools needing programmatic logic (`ClaudeSetup`, `GhSetup`)

Resolution via `ToolDefLoader`: user YAML -> built-in YAML -> Java CDI. First match by name wins.

**Important**: Built-in YAML files are listed in a hardcoded `BUILTIN_FILES` constant (not classpath scanning) because GraalVM native image makes classpath directory listing unreliable. When adding a built-in image or tool, you must update the corresponding `BUILTIN_FILES` list.

### Incus Interaction

`IncusClient` wraps the `incus` CLI via process execution (no SDK/API). `Container` is a helper for running commands inside a specific container (`exec`, `runAsUser`, `runInteractive`). The client auto-detects whether `sg incus-admin` wrapping is needed.

### MITM TLS Proxy

`MitmProxy` (in `proxy/`) is a TLS-terminating proxy that intercepts HTTPS to specific domains and injects real auth credentials, so containers only hold placeholder values. Key design:
- Listens on gateway IP:18443 (iptables redirects 443->18443 on the bridge)
- Per-domain certs signed by a custom CA (installed in templates during build)
- Vertex AI support: three-way routing — passthrough for Vertex-formatted requests from containers running in Vertex mode, standard-to-Vertex translation for `/v1/messages` requests (using `VERTEX_ALLOWED_FIELDS` body allowlist), and direct forwarding for non-messages endpoints
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
