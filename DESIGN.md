# incus-spawn Design Document

A CLI tool for managing isolated Incus-based development environments, designed for safely running untrusted agents and external reproducers in OSS projects.

## Goals

- **Secure by default**: isolated environments that prevent untrusted code from accessing host credentials or resources
- **Convenient**: easy enough for OSS communities to adopt as standard practice
- **Familiar**: CLI patterns inspired by git workflows (branch-name-style naming, auto-detection from cwd)

## Tech Stack

- **Quarkus CLI** with picocli extension
- **Tamboui** (https://tamboui.dev/) for interactive TUI (list view, inline actions)
- **GraalVM native image** for optional zero-dependency distribution
- **JBang** for easy installation (`jbang app install incus-spawn`)

## Architecture

### Container Model

- **System containers** by default (lightweight), with `--vm` flag for stronger isolation (separate kernel)
- No GUI by default, `--gui` flag enables Wayland + GPU passthrough
- Full internet by default, `--airgapped` flag for network isolation
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
   - All base images include: Claude Code, GitHub CLI (`gh`)

2. **Project golden images**: inherit from a base, add project-specific repos and dependencies
   - Defined by `incus-spawn.yaml` in the project repo
   - Include git repos (pre-cloned), Maven dependencies (pre-fetched), custom setup

3. **Ephemeral clones**: CoW copies of project (or base) golden images for actual work
   - Persist until explicitly destroyed
   - Tagged with metadata for tracking

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

- **CPU**: `total_cores - 2` (host keeps 2 cores responsive)
- **Memory**: ~50-60% of total RAM (prevents OOM)
- **Disk**: 20GB root disk (prevents filling host filesystem)

All overridable via CLI flags.

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
user.incus-spawn.project=golden-myproject
user.incus-spawn.created=2026-03-30
user.incus-spawn.type=base|golden|project|clone
```

Enables grouping by project in `list`, tracking age, finding forgotten clones.

### Directory Layout in Container

```
/home/agentuser/
  ├── inbox/          # read-only mount from host
  ├── .run/           # XDG_RUNTIME_DIR (for Wayland, when --gui)
  └── <project repos> # pre-cloned git repos (in project golden images)
```

## CLI Commands

### `incus-spawn init`
One-time host setup:
1. Install Incus, enable service, configure firewall
2. Configure subuid/subgid for UID mapping
3. Initialize Incus (`incus admin init --minimal`)
4. Claude Code auth setup (detect Vertex vs API key, test connectivity)
5. GitHub auth setup (guide PAT creation, test with `gh auth status`)

### `incus-spawn build <name>`
Build or rebuild a base golden image (e.g. `golden-java`, `golden-minimal`).
Installs profile-specific tooling, Claude Code, gh CLI.

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

### `incus-spawn shell <name>`
Reconnect to an existing clone (shell as `agentuser`).

### `incus-spawn list`
Interactive TUI (Tamboui) showing all environments:
- Grouped by project
- Shows name, type, age, status
- Keyboard shortcuts for shell, destroy, inspect

### `incus-spawn destroy <name>`
Destroy a clone. Refuse to destroy golden images without `--force`.

## Security Considerations

### Container vs VM Trade-off
- **Containers** (default): share host kernel. A kernel exploit could escape. Suitable for semi-trusted code.
- **VMs** (`--vm` flag): hardware-level isolation. Recommended for actively malicious code. Modest performance overhead with KVM.

### Credential Isolation
- Claude Code credentials and GitHub tokens are sensitive
- v1: forwarded into container with restricted permissions
- Upgrade path: host-side proxy that injects credentials, container never sees them
- Document the trade-off clearly

### Network Isolation
- Default: full internet access
- `--airgapped`: no network access (for analyzing suspicious reproducers)
- Host LAN access follows Incus default bridged networking

### Filesystem Isolation
- Inbox mount is strictly read-only
- No host filesystem access beyond the inbox
- Clone filesystems are independent CoW copies

## Future Enhancements

- Auth proxy for Claude/GitHub credentials (eliminates credential exposure)
- Additional base profiles (Python, Rust, Node.js, etc.)
- Snapshot/restore for clones
- Resource usage monitoring in `list` view
- Clone expiry warnings (flag clones older than configurable threshold)
- Multi-architecture support
