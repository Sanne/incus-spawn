# incus-spawn

Isolated Linux environments that behave like bare-metal machines, not stripped-down application containers.

Unlike Docker/Podman containers, which package a single application with a minimal filesystem, incus-spawn creates full **system containers** powered by [Incus](https://linuxcontainers.org/incus/). Each environment runs its own init system, has real networking with working `ping`, `traceroute`, and `strace`, can run nested containers (Podman/Docker inside), and supports GUI applications via Wayland passthrough. For untrusted code, KVM virtual machines provide hardware-level isolation with a separate kernel.

**Primary use cases:**
- Running untrusted AI agents (Claude Code, etc.) in isolated environments with pre-configured auth
- Reproducing bug reports from external contributors without risking your host
- Creating reproducible development environments with pre-cloned repos and cached dependencies

Built with [Quarkus](https://quarkus.io/) and [Tamboui](https://tamboui.dev/).

## Quick Start

```shell
# Install via JBang
jbang app install incus-spawn@Sanne/incus-spawn

# One-time host setup (Incus, firewall, auth)
isx init

# Build a golden image (builds parent images automatically)
isx build golden-java

# Launch the interactive TUI
isx
```

## Golden Image Hierarchy

Images are defined in YAML and layered via copy-on-write:

```
golden-minimal   (Base OS only)
  └── golden-dev   (Podman, GitHub CLI, Claude Code)
        └── golden-java  (JDK + Maven)
```

Each image definition specifies packages and tools:

```yaml
# images/java.yaml
name: golden-java
description: JDK + Maven + Claude Code
parent: golden-dev
packages:
  - java-25-openjdk-devel
  - java-25-openjdk-javadoc
  - java-25-openjdk-src
tools:
  - maven-3
```

## Custom Tools

Tools can be defined as YAML files without writing Java:

```yaml
# .incus-spawn/tools/node.yaml
name: node
description: Node.js LTS
run:
  - |
    curl -fsSL https://rpm.nodesource.com/setup_lts.x | bash -
    dnf install -y nodejs
verify: node --version
```

Tool schema fields (all optional except `name`):
- `packages` -- dnf packages to install
- `run` -- shell commands as root
- `run_as_user` -- shell commands as agentuser
- `files` -- files to write (with optional `owner`)
- `env` -- lines appended to agentuser's `.bashrc`
- `verify` -- verification command (logged, non-fatal)

Resolution order: `.incus-spawn/tools/` (project-local) > built-in YAML > Java plugins.

## Features

- **System containers**: full init, real networking, bare-metal-like developer experience
- **KVM VMs**: `--vm` flag for hardware-level isolation with separate kernel
- **Copy-on-write clones**: efficient disk usage with btrfs/zfs/lvm backends
- **Interactive TUI**: Midnight Commander-style interface for managing environments
- **Wayland passthrough**: GUI apps with GPU acceleration
- **Network airgapping**: isolate environments from the network
- **Adaptive resource limits**: CPU, memory, and disk auto-detected from host
- **Claude Code integration**: pre-configured AI agent auth (Vertex AI or API key)
- **GitHub integration**: fine-grained PAT setup for safe agent access

## TUI Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `F2` | Build a golden image |
| `F3` / `Enter` | Shell into selected instance |
| `F4` | Branch from selected image |
| `F5` | Rename selected instance |
| `F6` | Stop selected instance |
| `F7` | Restart selected instance |
| `F8` | Destroy selected instance |
| `F10` / `q` | Quit |
| `Up/Down`, `j/k` | Navigate |

## Installation

```shell
# Install via JBang (recommended)
jbang app install incus-spawn@Sanne/incus-spawn

# Or install directly from the latest release
jbang app install --name incus-spawn https://github.com/Sanne/incus-spawn/releases/latest/download/incus-spawn-runner.jar
```

## Building from source

```shell
# Build
mvn package

# Run tests
mvn test                        # unit tests (no Incus needed)
mvn verify -DskipITs=false      # integration tests (requires Incus)

# Install locally
./install.sh            # JVM
./install.sh --native   # native (requires GraalVM)
```

## Releasing

Releases are automated via GitHub Actions. To create a new release:

```shell
git tag v0.1.0
git push origin v0.1.0
```

This will:
1. Set the project version from the tag (e.g. `v0.1.0` becomes `0.1.0`)
2. Build a self-contained uber-jar
3. Create a GitHub Release with auto-generated release notes and the jar attached

Users can then install or update via `jbang app install incus-spawn@Sanne/incus-spawn`.

## Configuration

- `~/.config/incus-spawn/config.yaml` -- auth credentials and global settings
- `.incus-spawn/tools/*.yaml` -- project-local tool definitions
