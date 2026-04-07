# incus-spawn Design Document

A CLI tool for managing isolated Incus-based development environments. System containers that behave like bare-metal Linux machines, designed for safely running untrusted AI agents and external reproducers in OSS projects.

## Goals

- **Secure by default**: isolated environments that prevent untrusted code from accessing host credentials or resources
- **Bare-metal experience**: containers with full init, real networking, working developer tools (ping, strace, nested containers)
- **Extensible without Java**: image definitions and tool installations defined in YAML; Java only needed for tools requiring programmatic logic
- **Familiar**: CLI patterns inspired by git workflows (branch-name-style naming, auto-detection from cwd)

## Tech Stack

- **Quarkus CLI** with picocli extension
- **Tamboui** (https://tamboui.dev/) for interactive TUI (list view, modal dialogs, inline actions)
- **GraalVM native image** for optional zero-dependency distribution
- **JBang** for easy installation (`jbang app install incus-spawn`)

## Architecture

### Container Model

- **System containers** by default (lightweight, full init system), with `--vm` flag for KVM VMs (stronger isolation, separate kernel)
- Containers don't drop capabilities (`lxc.cap.drop =`) and relax kernel paranoia (`ptrace_scope`, `perf_event_paranoid`, `ping_group_range`) to match bare-metal behaviour
- No GUI by default; Wayland + GPU passthrough available at branch time
- Full internet by default; network airgapping available at branch time
- Container user: `agentuser` (UID 1000, passwordless sudo)

### Golden Image Hierarchy

Images are defined in YAML (`src/main/resources/images/*.yaml`) and layered via copy-on-write:

```
golden-minimal   (Base OS only — no tools)
  └── golden-dev   (Podman, GitHub CLI, Claude Code)
        └── golden-java  (JDK packages + Maven tool)
```

Each image definition specifies:
- `name` — container name (required)
- `description` — human-readable description for the TUI
- `image` — base OS image, only for root images (default: `images:fedora/43`)
- `parent` — parent image name (omit for root images)
- `packages` — dnf packages to install
- `tools` — tool names to run (resolved from YAML or Java)

Building an image automatically builds missing parents recursively.

### Tool System

Tools define how software gets installed into golden images. Two formats:

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
- `run` — shell commands as root
- `run_as_user` — shell commands as agentuser
- `files` — files to write (path, content, optional owner)
- `env` — lines appended to agentuser's `.bashrc`
- `verify` — verification command (logged, non-fatal)

Execution order: packages → run → run_as_user → files → env → verify.

**Java tools** (fallback) — for tools needing programmatic logic (e.g. reading SpawnConfig for credentials):
- Implement `ToolSetup` interface (`name()` + `install(Container)`)
- Discovered via CDI (`@Dependent`)
- Currently used by: `claude` (conditional auth), `gh` (token injection)

**Resolution order**: user-defined YAML (`.incus-spawn/tools/`) → built-in YAML (`resources/tools/`) → Java CDI implementations. First match by name wins.

### Build Flow

**`buildFromScratch` (root image, no parent):**
1. Launch base OS image
2. Configure security (idmap, nesting, syscall interception, no capability dropping)
3. Relax kernel paranoia (sysctl: ping_group_range, dmesg, perf, ptrace)
4. Configure DNS (disable systemd-resolved, point at Incus bridge gateway)
5. Upgrade system packages
6. Create agentuser (UID 1000, passwordless sudo)
7. Install base packages (git, curl, which, procps-ng, findutils)
8. Install image-defined packages via dnf
9. Install image-defined tools (resolved from YAML/Java)
10. Clean caches (dnf, /tmp)
11. Tag metadata, stop

**`buildFromParent` (derived image):**
1. Copy parent image, start, wait for network
2. Install image-defined packages via dnf
3. Install image-defined tools
4. Clean caches
5. Tag metadata, stop

### Branching

Base images can be branched to create independent copies. The branch modal supports:
- Custom name
- GUI passthrough (Wayland + GPU)
- Network airgapping
- Inbox mount (read-only host directory)
- VM resource limits (CPU, memory, disk)

### Resource Limits (Adaptive)

Detected at branch time from host resources:

- **CPU**: `available_cores - 2` (host keeps 2 cores, minimum 1)
- **Memory**: 60% of total RAM
- **Disk**: 20GB root disk

Overridable via TUI branch modal (for VMs, all three fields are shown).

### Container Configuration

**Capabilities**: `lxc.cap.drop =` (don't drop any — the container is the security boundary).

**Sysctl relaxation** (`/etc/sysctl.d/99-dev-container.conf`):
- `net.ipv4.ping_group_range = 0 2147483647` — unprivileged ping
- `kernel.dmesg_restrict = 0` — read kernel logs
- `kernel.perf_event_paranoid = 1` — perf profiling
- `kernel.yama.ptrace_scope = 0` — strace/debuggers

**DNS**: systemd-resolved disabled, `/etc/resolv.conf` points at Incus bridge gateway (`incusbr0`), immutable via `chattr +i`.

### Wayland Passthrough

Enables GUI applications inside containers:
- GPU device passed through for hardware-accelerated rendering
- Host `XDG_RUNTIME_DIR` bind-mounted for Wayland socket access
- Environment variables written to `/etc/profile.d/wayland.sh`

### Network Airgapping

Isolates a branch from the network by detaching from the Incus bridge (`incusbr0`) or removing the `eth0` device.

### Auth & Security

**Claude Code credentials**:
- Credential forwarding via environment variables (Vertex AI or API key)
- Gcloud credentials mounted read-only when using Vertex AI
- Future: host-side proxy that injects auth, container never sees credentials

**GitHub credentials**:
- Fine-grained PAT forwarded as `GH_TOKEN`
- `init` guides user to create a scoped, throwaway token

**Configuration**: `~/.config/incus-spawn/config.yaml` (owner-only permissions)

### Metadata Tracking

Containers tagged via Incus `user.*` config keys:

```
user.incus-spawn.type=base
user.incus-spawn.profile=golden-java
user.incus-spawn.parent=golden-dev
user.incus-spawn.created=2026-04-07
```

### Storage and COW

Clone efficiency depends on the Incus storage backend:
- **btrfs, zfs, lvm**: copy-on-write — clones share data with golden images
- **dir**: no COW — each clone is a full copy

`isx init` checks the configured storage pool and warns if COW is not available.

## Testing

**Unit tests** (`mvn test`, no Incus needed):
- `ToolDefTest` — YAML tool parsing (all fields, defaults, unknown fields)
- `ToolDefLoaderTest` — resolution order (builtins, user overrides, unknown tools)
- `YamlToolSetupTest` — execution order with mocked Container
- `ImageDefTest` — image definition loading, parent chain, descriptions

**Integration tests** (`mvn verify -DskipITs=false`, requires Incus):
- `GoldenImageBuildIT` — builds actual images, verifies metadata and agentuser

## Security Considerations

### Container vs VM Trade-off
- **Containers** (default): share host kernel. A kernel exploit could escape. Suitable for semi-trusted code.
- **VMs** (`--vm` flag): hardware-level isolation via KVM. Recommended for actively malicious code. Modest performance overhead.

### Credential Isolation
- Credentials forwarded as environment variables (v1)
- Upgrade path: host-side proxy that injects credentials, container never sees them

### Filesystem Isolation
- Inbox mount is strictly read-only
- No host filesystem access beyond the inbox and auth credential mounts
- Clone filesystems are independent CoW copies
