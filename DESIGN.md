# incus-spawn Design Document

A CLI tool for managing isolated Incus-based development environments. System containers that behave like bare-metal Linux machines, designed for safely running untrusted AI agents and external reproducers in OSS projects.

## Why not Docker?

Docker and Podman are **application containers**: they isolate a single process with a minimal filesystem, no init system, and restricted networking. This is ideal for deploying microservices but poor for development environments where you need:

- A real init system (systemd) for services like podman socket, sshd, or dbus
- Full networking: `ping`, `traceroute`, `tcpdump`, DNS resolution that works like a real machine
- Nested containers: running Podman/Docker inside the environment (Testcontainers, CI pipelines)
- Debugging tools: `strace`, `perf`, `gdb` — all require capabilities or sysctls that Docker strips
- GUI applications via Wayland passthrough with GPU acceleration, and audio via PipeWire

Incus **system containers** run a full Linux userspace with their own init, networking stack, and process tree. They share the host kernel (like Docker) but present as a complete machine rather than a process jail. For stronger isolation, Incus also supports KVM virtual machines with a separate kernel, at the cost of a modest performance overhead.

The tradeoff: system containers are heavier than application containers (~200MB base vs ~5MB Alpine). This is acceptable for development environments that persist for hours or days, and copy-on-write storage means clones are cheap regardless of base image size.

## Goals

- **Secure by default**: isolated environments that prevent untrusted code from accessing host credentials or resources
- **Bare-metal experience**: containers with full init, real networking, working developer tools — developers shouldn't notice they're inside a container
- **Extensible without Java**: image definitions and tool installations defined in YAML; Java only needed for tools requiring programmatic logic
- **Ephemeral and cheap**: copy-on-write clones mean spinning up a new environment costs seconds and minimal disk space
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
- Three network modes at branch time: full internet (default), proxy-only, or airgapped
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

Like `git branch`, branching creates an instant copy-on-write clone of any golden image. Each branch has its own independent filesystem -- changes in one branch cannot affect the golden image or any other branch. The CoW storage backend (btrfs/zfs/lvm) deduplicates unchanged data transparently at the block level, so branches are instant to create and only consume disk space for their own modifications.

The TUI branch modal supports:
- Custom name
- GUI and audio passthrough (Wayland + PipeWire + GPU)
- Network mode selection (full internet / proxy-only / airgapped) via three-state radio
- Inbox mount (read-only host directory for sharing files into the container)
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

### GUI and Audio Passthrough

Enables GUI applications and audio inside containers:
- GPU device passed through for hardware-accelerated rendering
- Host `XDG_RUNTIME_DIR` bind-mounted, exposing the Wayland socket and PipeWire/PulseAudio socket
- Environment variables written to `/etc/profile.d/wayland.sh` (`WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, toolkit backends)

### Network Modes

Branches run in one of three network modes, selectable via CLI flags or the TUI branch modal:

**Full internet** (default): Container stays on the `incusbr0` bridge with NAT masquerading. Unrestricted outbound access to the internet. Traffic to intercepted domains (Anthropic, GitHub) is transparently authenticated by the host MITM proxy — credentials never enter the container in any form.

**Proxy only** (`--proxy-only`): Container stays on the bridge but iptables OUTPUT rules restrict all outbound traffic to the MITM proxy (port 443) and DNS. The container can only reach intercepted domains via the MITM proxy.

Container-side firewall rules:
```
iptables -A OUTPUT -o lo -j ACCEPT
iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -d <gateway> -p tcp --dport 443 -j ACCEPT     # MITM proxy
iptables -A OUTPUT -d <gateway> -p tcp --dport 18080 -j ACCEPT   # Health check
iptables -A OUTPUT -d <gateway> -p udp --dport 53 -j ACCEPT      # DNS
iptables -P OUTPUT DROP
```

**Airgapped** (`--airgap`): Network device detached or removed. Complete network isolation — no egress at all.

### Auth & Security: MITM TLS Proxy

**Credentials never enter containers in any form.** A host-side MITM TLS proxy (`isx proxy`) provides completely transparent authentication. There are no environment variables, credential helpers, shell wrappers, or any other mechanism that could expose credentials to code running inside containers.

**How it works:**

1. Golden images include DNS overrides (`/etc/hosts`) that resolve intercepted domains to the Incus bridge gateway IP
2. Golden images include a custom CA certificate (generated during `isx init`) so containers trust the proxy's TLS certificates
3. The proxy listens on port 443 on the gateway IP, terminates TLS using per-domain certificates signed by the custom CA
4. Based on the target domain, the proxy injects authentication headers:
   - `api.anthropic.com` (direct API) — `x-api-key: <anthropic-api-key>`
   - `api.anthropic.com` (Vertex AI mode) — translates the entire request to Vertex AI format (see below)
   - GitHub domains — `Authorization: Bearer <github-token>`
5. The proxy re-encrypts and forwards to the real upstream over TLS

**Vertex AI support:** When the host is configured for Vertex AI (`useVertex=true` in config), the proxy transparently translates standard Anthropic API requests into Vertex AI API calls. The container runs Claude Code in **standard mode** (no Vertex env vars, no GCP credentials). The proxy:
- Intercepts `POST /v1/messages` to `api.anthropic.com` (same as direct API)
- Extracts the model name from the JSON body
- Rewrites the URL to `https://{region}-aiplatform.googleapis.com/v1/projects/{projectId}/locations/{region}/publishers/anthropic/models/{model}:rawPredict`
- Replaces `x-api-key` with `Authorization: Bearer <gcp-token>` (obtained on the host via `gcloud auth print-access-token`, cached for ~50 minutes)
- The response format is identical (Vertex `rawPredict` passes through the Anthropic response), so Claude Code doesn't know the difference

**Intercepted domains:** `api.anthropic.com`, `github.com`, `api.github.com`, `raw.githubusercontent.com`, `objects.githubusercontent.com`, `codeload.github.com`, `uploads.github.com`

All other domains (package mirrors, PyPI, etc.) route normally via Incus bridge NAT and are unaffected by the proxy.

**Credential validation**: Building a golden image that includes `claude` or `gh` tools requires the corresponding credentials to be configured on the host. Both the CLI and TUI check this before starting a build and abort with a clear error if credentials are missing.

**Configuration**: `~/.config/incus-spawn/config.yaml` (owner-only permissions, `chmod 600`). CA key and certificate at `~/.config/incus-spawn/ca.key` and `~/.config/incus-spawn/ca.crt`. Vertex AI users must have `gcloud` installed on the host and `gcloud auth application-default login` completed.

### Metadata Tracking

Containers tagged via Incus `user.*` config keys:

```
user.incus-spawn.type=base
user.incus-spawn.profile=golden-java
user.incus-spawn.parent=golden-dev
user.incus-spawn.created=2026-04-07
user.incus-spawn.network-mode=PROXY_ONLY     # (proxy-only branches only)
user.incus-spawn.proxy-gateway=10.166.11.1    # (proxy-only branches only)
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

## Technical Tradeoffs

### System containers vs application containers
System containers run a full init system and present as a complete machine. This means higher base image size (~200MB vs ~5MB Alpine) and longer first-build time (system upgrade, user creation, tool installation). However, clones are instant and near-zero cost with CoW storage, which is the common operation — you build once, branch many times.

### No capability dropping (`lxc.cap.drop =`)
Standard Incus containers drop many Linux capabilities for defense-in-depth. We don't, because the container *is* the security boundary and developers expect `ping`, `strace`, `perf`, raw sockets, and `dmesg` to work. The risk is that a container escape exploit has more host capabilities to abuse. For untrusted code where this matters, use `--vm` for KVM isolation with a separate kernel.

### YAML tools vs a full plugin system (Packer, Ansible, etc.)
We evaluated Packer (null builder + shell provisioner) and Ansible but rejected both. Packer's null builder is just indirection over what Java already does, and Ansible adds a Python dependency and playbook complexity for what amounts to "install some packages and run some scripts." YAML tool definitions give 90% of the flexibility with zero dependencies. Java `ToolSetup` implementations remain available as an escape hatch for tools that need programmatic logic (reading host config, conditional branching).

### Hardcoded built-in tool list vs classpath scanning
Built-in YAML tools are loaded from a hardcoded list of filenames rather than scanning the classpath. This is a deliberate choice: Quarkus native image compilation makes classpath directory listing unreliable, and the list only changes when a developer adds a built-in tool (at which point they also update the loader). User-defined tools in `.incus-spawn/tools/` are discovered via filesystem scanning.

### DNS: static resolv.conf vs systemd-resolved
systemd-resolved (127.0.0.53) doesn't work reliably inside Incus containers because it expects to manage the network configuration. We disable it, point `/etc/resolv.conf` directly at the Incus bridge gateway (which runs dnsmasq), and make the file immutable with `chattr +i`. This is less flexible than systemd-resolved (no per-link DNS, no DNSSEC validation) but works reliably across container restarts and network changes.

### Credential isolation via MITM TLS proxy
A TLS-terminating MITM proxy intercepts HTTPS connections to specific domains (Anthropic API, GitHub), injects authentication headers server-side, and forwards to the real upstream. Containers resolve these domains to the gateway IP via `/etc/hosts` and trust the proxy's certificates via a custom CA installed in the golden image. This approach was chosen over simpler alternatives (reverse proxy with `ANTHROPIC_BASE_URL`, credential helpers, shell wrappers) because those approaches still expose credentials to code running inside the container — either as environment variables, in process memory via `curl` calls, or through accessible endpoints. The MITM proxy provides complete isolation: there is no API, endpoint, environment variable, or file that container code can access to obtain credentials.

### Fedora-specific
The base image and package management are Fedora-specific (`dnf`, `images:fedora/43`). This is intentional — supporting multiple distros adds complexity for a tool primarily targeting developer workstations where Fedora is a common choice. The YAML tool system is distro-agnostic in principle (tools can use any shell commands), but the built-in base image setup assumes Fedora.

## Security Considerations

### Container vs VM Trade-off
- **Containers** (default): share host kernel. A kernel exploit could escape. Suitable for semi-trusted code (AI agents with scoped permissions, community bug reproducers).
- **VMs** (`--vm` flag): hardware-level isolation via KVM. Recommended for actively malicious code. Separate kernel eliminates kernel exploit as an escape vector. ~10% performance overhead.

### Credential Isolation

Credentials never enter containers in any form, regardless of network mode:

| Credential | Container exposure | How it works |
|-----------|-------------------|--------------|
| Claude API key | **Never in container** | MITM proxy intercepts TLS to `api.anthropic.com`, injects `x-api-key` header server-side |
| GCP credentials (Vertex AI) | **Never in container** | Proxy translates standard API requests to Vertex format, obtains GCP token on the host via `gcloud`, injects `Authorization: Bearer` — container has no Vertex env vars or GCP credentials |
| GitHub token | **Never in container** | MITM proxy intercepts TLS to GitHub domains, injects `Authorization: Bearer` header server-side |

The MITM TLS proxy provides complete credential isolation:
1. DNS overrides in `/etc/hosts` route intercepted domains to the gateway IP
2. A custom CA certificate (installed in golden images) lets containers trust the proxy's TLS certs
3. The proxy terminates TLS, injects auth headers, and forwards to real upstream over TLS
4. No environment variables, credential helpers, shell wrappers, or accessible endpoints expose credentials
5. In proxy-only mode, iptables OUTPUT rules additionally block all egress except the proxy port (443) and DNS

### Filesystem Isolation
- Inbox mount is strictly read-only
- No host filesystem access beyond the inbox
- Clone filesystems are independent CoW copies — changes in one clone don't affect others or the golden image
