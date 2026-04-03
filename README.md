# incus-spawn

A CLI tool for managing isolated [Incus](https://linuxcontainers.org/incus/) development environments, designed for safely running untrusted AI agents and external reproducers in OSS projects.

Built with [Quarkus](https://quarkus.io/) and [Tamboui](https://tamboui.dev/) (a ratatui-inspired Java TUI framework).

## Quick Start

```shell
# Build and install
./install.sh          # installs as 'isx' to ~/.local/bin

# One-time host setup
isx init

# Build a base golden image
isx build golden-java

# Launch the interactive TUI
isx
```

## Features

- **Golden image layering**: base images, project images, and ephemeral clones (like Docker image inheritance)
- **Copy-on-write clones**: efficient disk usage with btrfs/zfs/lvm storage backends
- **Interactive TUI**: Midnight Commander-style interface for managing environments
- **Container and VM support**: lightweight system containers by default, KVM VMs (`--vm`) for stronger isolation
- **Wayland passthrough**: run GUI apps inside containers with GPU acceleration
- **Network airgapping**: isolate containers from the network for analyzing untrusted code
- **Adaptive resource limits**: CPU, memory, and disk limits auto-detected from host resources
- **Claude Code integration**: pre-configured AI agent auth (Vertex AI or API key)
- **GitHub integration**: fine-grained PAT setup for safe agent access

## Commands

| Command | Description |
|---------|-------------|
| `isx init` | One-time host setup (Incus, auth, storage) |
| `isx build <name>` | Build a base golden image (`--vm` for KVM) |
| `isx project create <name>` | Create a project golden image from `incus-spawn.yaml` |
| `isx project update <name>` | Update a project golden image |
| `isx update-all` | Update all golden images |
| `isx create <name>` | Spawn a clone from a golden image |
| `isx shell <name>` | Open a shell in an existing clone |
| `isx list` | Interactive TUI (also the default when running `isx`) |
| `isx destroy <name>` | Destroy a clone |
| `isx branch <src> <dst>` | Create an independent branch from a base image |

## TUI Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `q` | Quit |
| `Enter` | Shell into selected instance |
| `c` | Clone selected image |
| `b` | Branch selected base image |
| `d` | Destroy selected instance |
| `n` | Rename selected instance |
| `s` | Stop selected instance |
| `r` | Restart selected instance |
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
# JVM build
./mvnw package

# Native build (requires GraalVM)
./mvnw package -Dnative

# Install locally (JVM or native)
./install.sh            # JVM
./install.sh --native   # native
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

## Project Configuration

Place `incus-spawn.yaml` in your project repo root:

```yaml
name: golden-myproject
parent: golden-java
repos:
  - https://github.com/org/service-a.git
  - https://github.com/org/service-b.git
pre_build: "cd service-a && mvn dependency:go-offline"
```

## Configuration

- `~/.config/incus-spawn/` — auth credentials and global settings
- `incus-spawn.yaml` — per-project configuration (version-controlled)
