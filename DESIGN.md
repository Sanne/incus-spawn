# incus-spawn Design Document

A CLI tool for managing isolated Incus-based development environments, designed for safely running untrusted agents and external reproducers in OSS projects.

## Goals

- **Secure by default**: isolated environments that prevent untrusted code from accessing host credentials or resources
- **Convenient**: easy enough for OSS communities to adopt as standard practice
- **Familiar**: CLI patterns inspired by git workflows (branch-name-style naming, auto-detection from cwd)

## Tech Stack

- **Quarkus CLI** with picocli extension
- **Tamboui** (https://tamboui.dev/) for interactive TUI (list view, modal dialogs, inline actions)
- **GraalVM native image** for optional zero-dependency distribution
- **JBang** for easy installation (`jbang app install incus-spawn`)

## Architecture

### Container Model

- **System containers** by default (lightweight), with `--vm` flag for KVM VMs (stronger isolation, separate kernel)
- No GUI by default; Wayland + GPU passthrough available at clone time
- Full internet by default; network airgapping available at clone time
- Container user: `agentuser` (UID 1000)

### Golden Image Layering

Three-tier model, similar to Docker image inheritance:

```
Base Fedora image
  └── Base golden image (golden-minimal, golden-java, ...)
        └── Project golden image (golden-myproject, ...)
              └── Ephemeral clone (fix-nasty-bug, ...)
```

1. **Base golden images**: define the tooling profile
   - `golden-minimal` — bare Fedora + Claude Code + gh CLI
   - `golden-java` — OpenJDK (incl. dev packages from DNF) + latest Maven (from Apache, not DNF) + Claude Code + gh CLI
   - All base images include: Claude Code, GitHub CLI (`gh`), podman
   - Built with `isx build <name>` — supports `--vm` to build as a KVM VM

2. **Project golden images**: inherit from a base, add project-specific repos and dependencies
   - Defined by `incus-spawn.yaml` in the project repo
   - Include git repos (pre-cloned), Maven dependencies (pre-fetched), custom setup

3. **Ephemeral clones**: CoW copies of project (or base) golden images for actual work
   - Persist until explicitly destroyed
   - Tagged with metadata for tracking
   - Auto-inherit type from source (container clones stay containers, VM clones stay VMs)

### Branching

Base images can be branched to create independent copies with their own lineage. This is useful for creating specialized base images from existing ones without rebuilding from scratch. Branches track their parent for provenance.

### Project Configuration

File: `incus-spawn.yaml` (in the project repo root)

```yaml
name: golden-myproject
parent: golden-java
repos:
  - https://github.com/org/service-a.git
  - https://github.com/org/service-b.git
pre_build: "cd service-a && mvn dependency:go-offline"
```

### Resource Limits (Adaptive)

Detected at creation time from host resources:

- **CPU**: `available_cores - 2` (host keeps 2 cores responsive, minimum 1)
- **Memory**: 60% of total RAM (prevents OOM)
- **Disk**: 20GB root disk (prevents filling host filesystem)

For VM clones, resource fields (CPU, memory, disk) are presented in the clone modal with defaults from the source image's current values. Disk can be grown but not shrunk.

All overridable via CLI flags or the TUI clone modal.

### Wayland Passthrough

Enables running GUI applications (Firefox, IDEs, etc.) inside containers:

- **GPU device** passed through for hardware-accelerated rendering
- **Disk device** bind-mounts the host's `XDG_RUNTIME_DIR` into the container, exposing the Wayland socket (and PipeWire/PulseAudio) directly
- **Environment variables** written to `/etc/profile.d/wayland.sh` so they survive `su -` login shells:
  - `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, `GDK_BACKEND`, `QT_QPA_PLATFORM`, `SDL_VIDEODRIVER`, `MOZ_ENABLE_WAYLAND`, `ELECTRON_OZONE_PLATFORM_HINT`
- Configured after container start, after all device mounts, with a final `chown -R` to fix any root-owned files in the home directory

### Network Airgapping

Isolates a clone from the network by detaching the instance from the Incus bridge (`incusbr0`) or removing the `eth0` device. Useful for analyzing suspicious reproducers.

### Auth & Security

**Claude Code credentials**:
- v1: credential forwarding with tight permissions (credentials mounted read-only)
- Future: HTTP proxy on host that injects auth, container never sees credentials
- Supports Vertex AI setup (CLAUDE_CODE_USE_VERTEX, CLOUD_ML_REGION, ANTHROPIC_VERTEX_PROJECT_ID) and direct API key

**GitHub credentials**:
- Dedicated fine-grained PAT (not human tokens)
- `init` guides user to create a scoped, throwaway token (named e.g. `incus-spawn-agent`)
- Recommended scopes: repo read/write, issues, PRs — no admin/org access

**Configuration storage**:
- `~/.config/incus-spawn/` — auth credentials, global defaults (security-sensitive)
- `incus-spawn.yaml` in project repo — project definition (version-controlled, shareable)

### Metadata Tracking

Containers tagged via Incus `user.*` config keys:

```
user.incus-spawn.type=base|project|clone
user.incus-spawn.project=golden-myproject
user.incus-spawn.parent=golden-java
user.incus-spawn.profile=minimal|java
user.incus-spawn.created=2026-03-30
```

Enables grouping by project in `list`, tracking age, finding forgotten clones.

### Storage and COW

Clone efficiency depends on the Incus storage backend:
- **btrfs, zfs, lvm**: support copy-on-write — clones share data with golden images and use minimal extra space
- **dir**: no COW — each clone is a full copy

`isx init` checks the configured storage pool driver and warns if COW is not available.

### Data Fetching

The TUI fetches instance data using `incus list --format=json` — a single call that returns all instance configs, devices, and metadata. This replaces multiple per-instance calls and keeps the UI responsive.

### Directory Layout in Container

```
/home/agentuser/
  ├── inbox/          # read-only mount from host (optional)
  ├── .config/gcloud/ # read-only mount for Vertex AI auth (if configured)
  └── <project repos> # pre-cloned git repos (in project golden images)
```

## CLI Commands

### `incus-spawn init`
One-time host setup:
1. Install Incus, enable service, configure firewall (including Docker coexistence rules)
2. Configure subuid/subgid for UID mapping
3. Initialize Incus (`incus admin init --minimal`)
4. Check storage pool for COW support (warn if not btrfs/zfs/lvm)
5. Claude Code auth setup (detect Vertex vs API key, test connectivity)
6. GitHub auth setup (guide PAT creation, test with `gh auth status`)

### `incus-spawn build <name> [--profile <profile>] [--vm] [--image <image>]`
Build or rebuild a base golden image (e.g. `golden-java`, `golden-minimal`).
Installs profile-specific tooling, Claude Code, gh CLI.
Use `--vm` to build as a KVM virtual machine instead of a system container.

### `incus-spawn project create <name> [--config incus-spawn.yaml]`
Create a project golden image from a parent base image.
Clones git repos, runs pre-build steps (e.g. Maven dependency pre-fetch).

### `incus-spawn project update <name>`
Update a single project golden image:
- `dnf update` for system packages
- `git fetch --all` in all seeded repos
- Re-run dependency pre-fetch (e.g. `mvn dependency:go-offline`)
- Update Claude Code and gh CLI

### `incus-spawn update-all`
Run update on all golden images (base + project).

### `incus-spawn create <name> [--project <name>]`
Spawn an ephemeral clone from a golden image:
- Auto-detects project from `incus-spawn.yaml` in cwd if `--project` omitted
- Creates CoW clone, starts it
- Opens shell as `agentuser`, launches Claude Code

Optional flags:
- `--vm` — use VM instead of container
- `--gui` — enable Wayland + GPU passthrough
- `--airgapped` — no network access
- `--inbox /path/to/dir` — mount host directory read-only at `/home/agentuser/inbox`
- `--cpu`, `--memory`, `--disk` — override adaptive resource limits

### `incus-spawn shell <name>`
Reconnect to an existing clone (shell as `agentuser`). Auto-starts if stopped.

### `incus-spawn list`
Interactive TUI (Tamboui) showing all environments:
- Grouped by project
- Shows name, status, type, parent, runtime, age
- Midnight Commander-style toolbar with keyboard shortcuts
- Modal dialogs for clone, branch, rename, destroy confirmation
- Clone modal supports Wayland/airgap toggles, VM resource fields (CPU/RAM/disk)
- Greyed-out buttons for unavailable actions
- Visual feedback on button press

Also available as `--plain` for non-interactive output.

### `incus-spawn destroy <name>`
Destroy a clone. Refuse to destroy golden images without `--force`.

### `incus-spawn branch <source> <target>`
Create an independent branch from a base image. Copies the source, sets it as a new base image with its own lineage. Tracks parent for provenance.

## Security Considerations

### Container vs VM Trade-off
- **Containers** (default): share host kernel. A kernel exploit could escape. Suitable for semi-trusted code.
- **VMs** (`--vm` flag): hardware-level isolation via KVM. Recommended for actively malicious code. Modest performance overhead.

### Credential Isolation
- Claude Code credentials and GitHub tokens are sensitive
- v1: forwarded into container with restricted permissions
- Upgrade path: host-side proxy that injects credentials, container never sees them

### Network Isolation
- Default: full internet access
- Airgapped: no network access (for analyzing suspicious reproducers)
- Host LAN access follows Incus default bridged networking

### Filesystem Isolation
- Inbox mount is strictly read-only
- No host filesystem access beyond the inbox and auth credential mounts
- Clone filesystems are independent CoW copies

## Future Enhancements

- Auth proxy for Claude/GitHub credentials (eliminates credential exposure)
- Additional base profiles (Python, Rust, Node.js, etc.)
- Resource usage monitoring in `list` view
- Clone expiry warnings (flag clones older than configurable threshold)
- Multi-architecture support
