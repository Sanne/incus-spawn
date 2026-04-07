# incus-spawn

Isolated Linux environments that behave like bare-metal machines, not stripped-down application containers.

Unlike Docker/Podman containers, which package a single application with a minimal filesystem, incus-spawn creates full **system containers** powered by [Incus](https://linuxcontainers.org/incus/). Each environment runs its own init system, has real networking with working `ping`, `traceroute`, and `strace`, can run nested containers (Podman/Docker inside), and supports GUI and audio passthrough via Wayland. For untrusted code, KVM virtual machines provide hardware-level isolation with a separate kernel.

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

## Branching

Like `git branch`, branching creates an instant copy-on-write clone of any golden image. Each branch has its own independent filesystem -- changes in one branch cannot affect the golden image or any other branch. The storage backend (btrfs/zfs/lvm) deduplicates unchanged data automatically, so branches are instant to create and only consume disk space for their own modifications.

```
golden-java  (stopped template, ~2GB)
  ├── fix-nasty-bug    (running, uses ~50MB extra)
  ├── review-pr-423    (running, uses ~30MB extra)
  └── experiment       (stopped, uses ~10MB extra)
```

You can install packages, break things, and destroy a branch when done. The golden image and other branches are completely unaffected.

Branches can optionally enable GUI/audio passthrough (Wayland), restricted networking, or an inbox mount to share files read-only from the host.

### Credential Isolation

**API keys and tokens never enter containers in any form.** A host-side MITM TLS proxy (`isx proxy`) provides completely transparent authentication:

- Golden images include DNS overrides (`/etc/hosts`) that resolve `api.anthropic.com`, `github.com`, and related domains to the Incus bridge gateway IP
- Golden images include a custom CA certificate so containers trust the proxy's TLS certificates
- The proxy terminates TLS, injects authentication headers (`x-api-key` for Anthropic, `Authorization: Bearer` for GitHub), and forwards to the real upstream over TLS
- Tools (`curl`, `git`, `gh`, `claude`) work completely unmodified inside containers — no environment variables, no credential helpers, no shell wrappers
- **Vertex AI support**: when the host uses Vertex AI, the proxy transparently translates standard Anthropic API requests to Vertex format and injects GCP Bearer tokens — containers run Claude Code in standard mode with zero GCP configuration
- There is no mechanism for code inside a container to read, extract, or exfiltrate credentials

The proxy must be running for non-airgapped containers: `isx proxy` (run in a separate terminal or as a background service).

### Network Modes

Each branch runs in one of three network modes:

| Mode | Flag | Description |
|------|------|-------------|
| **Full internet** | *(default)* | Unrestricted network access via NAT, auth via MITM proxy |
| **Proxy only** | `--proxy-only` | Outbound traffic restricted to MITM proxy only (iptables) |
| **Airgapped** | `--airgap` | Network device removed, complete isolation |

In all non-airgapped modes, credentials are injected transparently by the MITM proxy. The network modes only control what *other* traffic the container can access:

- **Full internet**: containers can reach any destination; traffic to intercepted domains (Anthropic, GitHub) is transparently routed through the MITM proxy for auth injection
- **Proxy only**: iptables OUTPUT rules restrict all outbound traffic to the MITM proxy port (443) and DNS — the container cannot reach any external endpoint directly
- **Airgapped**: no network device, no traffic at all

## Golden Images

Golden images are reusable templates defined in YAML. They can inherit from each other -- building an image automatically builds any missing parents:

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

- **Instant branching**: copy-on-write clones that share storage with the parent image
- **System containers**: full init, real networking, bare-metal-like developer experience
- **KVM VMs**: `--vm` flag for hardware-level isolation with separate kernel
- **Interactive TUI**: Midnight Commander-style interface for managing environments
- **GUI and audio passthrough**: Wayland + PipeWire with GPU acceleration
- **Inbox mount**: share a host directory read-only into the container
- **MITM TLS proxy**: transparent auth injection — credentials never enter containers in any form
- **Proxy-only networking**: iptables restricts egress to the MITM proxy only
- **Network airgapping**: fully isolate environments from the network
- **Adaptive resource limits**: CPU, memory, and disk auto-detected from host
- **Claude Code integration**: auth via MITM proxy — API key never enters containers
- **GitHub integration**: auth via MITM proxy — token never enters containers

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

**Branch modal** (`F4`):

| Key | Action |
|-----|--------|
| `Alt-g` | Toggle GUI passthrough |
| `Alt-n` | Cycle network mode (Full / Proxy only / Airgapped) |
| `Alt-i` | Toggle inbox mount |
| `Tab` | Next field |
| `Enter` | Confirm |
| `Esc` | Cancel |

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
