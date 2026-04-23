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
- **JBang** for easy installation (`jbang app install isx`)

## Architecture

### Container Model

- **System containers** by default (lightweight, full init system), with `--vm` flag for KVM VMs (stronger isolation, separate kernel)
- Containers don't drop capabilities (`lxc.cap.drop =`) and relax kernel paranoia (`ptrace_scope`, `perf_event_paranoid`, `ping_group_range`) to match bare-metal behaviour
- No GUI by default; Wayland + GPU passthrough available at branch time
- Three network modes at branch time: full internet (default), proxy-only, or airgapped
- Container user: `agentuser` (UID 1000, passwordless sudo)

### Template Image Hierarchy

Images are defined in YAML and layered via copy-on-write. Built-in definitions live in `src/main/resources/images/*.yaml`; user-defined images in `~/.config/incus-spawn/images/` can extend or override them:

```
tpl-minimal   (Base OS only — no tools)
  └── tpl-dev   (Podman, GitHub CLI, Claude Code)
        └── tpl-java  (JDK packages + Maven tool)
```

Each image definition specifies:
- `name` — container name (required)
- `description` — human-readable description for the TUI
- `image` — base OS image, only for root images (default: `images:fedora/43`)
- `parent` — parent image name (omit for root images)
- `packages` — dnf packages to install
- `tools` — tool names to run (resolved from YAML or Java)

Building an image automatically builds missing parents recursively. `isx build --all` rebuilds every defined image from scratch.

**Resolution order**: built-in YAML (classpath) first, then user-defined YAML (`~/.config/incus-spawn/images/`). User definitions with the same name override built-ins.

### Tool System

Tools define how software gets installed into template images. Two formats:

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

**Java tools** (fallback) — for tools needing programmatic logic beyond what YAML supports:
- Implement `ToolSetup` interface (`name()` + `install(Container)`)
- Discovered via CDI (`@Dependent`)
- Currently used by: `claude` (binary install + settings), `gh` (dnf install)

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

Like `git branch`, branching creates an instant copy-on-write clone of any template image. Each branch has its own independent filesystem -- changes in one branch cannot affect the template image or any other branch. The CoW storage backend (btrfs/zfs/lvm) deduplicates unchanged data transparently at the block level, so branches are instant to create and only consume disk space for their own modifications.

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

**API keys and tokens never enter containers.** A host-side MITM TLS proxy (`isx proxy`) provides transparent authentication. Placeholder values satisfy tools' local auth checks (e.g. `GH_TOKEN`, `ANTHROPIC_API_KEY`), but the proxy replaces them with real credentials before requests reach upstream servers.

**How it works:**

1. The proxy configures bridge-level DNS overrides (via `raw.dnsmasq` on `incusbr0`) so all containers resolve intercepted domains to the gateway IP
2. Template images include a custom CA certificate (generated during `isx init`) so containers trust the proxy's TLS certificates
3. The proxy listens on port 18443 on the gateway IP. An iptables PREROUTING redirect rule (installed by `isx init` via `firewall-cmd --permanent --direct`) transparently redirects traffic arriving on `incusbr0` destined for port 443 to port 18443, avoiding conflicts with the Incus daemon on port 443. The proxy terminates TLS using per-domain certificates signed by the custom CA
4. Based on the target domain, the proxy injects authentication headers:
   - `api.anthropic.com` — `x-api-key: <anthropic-api-key>` (direct mode) or Vertex AI passthrough/translation with GCP Bearer token (Vertex mode, see below)
   - `github.com` (git HTTP) — `Authorization: Basic <base64(x-access-token:token)>`
   - Other GitHub domains (API, CDN) — `Authorization: Bearer <github-token>`
   - Container registry and Maven domains — relayed transparently with caching (no auth injection)
5. The proxy re-encrypts and forwards to the real upstream over TLS

**Vertex AI support:** When the host is configured for Vertex AI (`useVertex=true` in config), containers run Claude Code in **Vertex mode** with `CLAUDE_CODE_USE_VERTEX=1`, `CLAUDE_CODE_SKIP_VERTEX_AUTH=1`, and `ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1`. This causes the Vertex SDK inside the container to send already-formatted Vertex requests (`/v1/projects/.../models/...:streamRawPredict`) to `api.anthropic.com`, which resolves to the proxy via dnsmasq. The proxy then forwards to the real Vertex endpoint with GCP credentials. No GCP credentials enter the container.

Running containers in Vertex mode (rather than standard mode) is required because Claude Code's model list depends on the provider: standard mode ("firstParty") shows a hardcoded subset that may omit newer models, while Vertex mode shows the full model catalogue. Using Vertex mode in the container ensures the `/model` picker matches what's available on the host.

**Three-way routing for Anthropic traffic:**

The proxy routes requests to `api.anthropic.com` through one of three paths based on the URL:

1. **Vertex passthrough** (`/v1/projects/...`): Requests already in Vertex format (from the container's Vertex SDK). The proxy strips `@date` model version suffixes from the URL (the global endpoint rejects them), removes the `anthropic-beta` header, injects a GCP Bearer token, and forwards to the real Vertex endpoint. The body is passed through unmodified — the Vertex SDK already formats it correctly.

2. **Standard-to-Vertex translation** (`/v1/messages`): Requests in standard Anthropic API format. The proxy buffers the body and translates to Vertex `rawPredict` format (see translation details below). This path is used if a container happens to send standard-format requests (e.g. from curl).

3. **Direct forwarding** (all other paths): Non-messages endpoints (settings, bootstrap, feature flags, MCP registry) are forwarded to the real `api.anthropic.com` with credential injection. These endpoints don't exist on the Vertex API.

**Standard-to-Vertex translation details** (path 2):

- URL: `/v1/messages` → `/v1/projects/{projectId}/locations/{region}/publishers/anthropic/models/{model}:rawPredict` (or `:streamRawPredict` when `stream=true`)
- Auth: replaces `x-api-key` with `Authorization: Bearer <gcp-token>` (obtained via `gcloud auth print-access-token`, cached ~50 minutes)
- Body: extracts `model` field and moves it into the URL path, stripping date suffixes (e.g. `claude-sonnet-4-6-20251001` → `claude-sonnet-4-6`)
- Body: adds `"anthropic_version": "vertex-2023-10-16"` (required by Vertex rawPredict)
- Body: strips all top-level fields not in the Vertex allowlist (beta features like `context_management` cause "Extra inputs" rejections)
- Body: recursively strips `scope` from nested `cache_control` objects (beta feature unsupported by Vertex)
- Header: removes `anthropic-beta` (Vertex rejects beta feature flags; features are enabled via `anthropic_version`)
- Host: rewrites to the Vertex endpoint hostname for the configured region

The body translation uses an allowlist approach: only known-good fields (`messages`, `system`, `max_tokens`, `temperature`, `top_p`, `top_k`, `stop_sequences`, `stream`, `metadata`, `tools`, `tool_choice`, `thinking`, `output_config`, `anthropic_version`) are kept. Everything else is dropped. This is more robust than blocklisting individual beta fields, since new Claude Code beta features are automatically stripped without proxy changes.

**Vertex AI protocol details** (learned from testing):

- **Hostname resolution by region**: The standard pattern is `{region}-aiplatform.googleapis.com` (e.g. `us-east5-aiplatform.googleapis.com`), but some meta-regions use special hostnames: `global` → `aiplatform.googleapis.com`, `us` → `aiplatform.us.rep.googleapis.com`, `eu` → `aiplatform.eu.rep.googleapis.com`
- **Model naming**: The Vertex SDK uses `@` for model version suffixes in URL paths (e.g. `claude-haiku-4-5@20251001`), while the standard API uses `-` (e.g. `claude-haiku-4-5-20251001`). The global Vertex endpoint only accepts short model aliases without any version suffix — both `@20251001` and `-20251001` forms are rejected. The proxy strips both forms.
- **Beta features**: The `anthropic-beta` header is rejected by Vertex rawPredict with "Unexpected value(s) for anthropic-beta header". This includes common beta flags like `claude-code-20250219`, `interleaved-thinking-2025-05-14`, `web-search-2025-03-05`, and `prompt-caching-scope-2026-01-05`. Features like extended thinking work without any beta flags on Vertex — they're enabled via `anthropic_version`. The Vertex SDK moves `anthropic-beta` header values into the body as an `anthropic_beta` array, but even that is rejected ("invalid beta flag"). The proxy strips the header entirely without adding it to the body.
- **Auth skipping**: `CLAUDE_CODE_SKIP_VERTEX_AUTH=1` causes the Vertex SDK to skip GCP authentication and return stub credentials that produce empty auth headers. The proxy then replaces these with real GCP tokens.
- **Base URL override**: `ANTHROPIC_VERTEX_BASE_URL` redirects all Vertex SDK requests to a custom endpoint. Setting it to `https://api.anthropic.com/v1` causes the container's Vertex SDK to send requests to `api.anthropic.com`, which resolves to the proxy via dnsmasq.
- **Response format**: Vertex `rawPredict` returns standard Anthropic response format — no response translation is needed.

**Intercepted domains:** `api.anthropic.com`, `github.com`, `api.github.com`, `raw.githubusercontent.com`, `objects.githubusercontent.com`, `codeload.github.com`, `uploads.github.com`, `registry-1.docker.io`, `auth.docker.io`, `ghcr.io`, `quay.io`, `repo.maven.apache.org`, `repo1.maven.org`, `plugins.gradle.org`

**HTTPS only:** The proxy intercepts HTTPS traffic, so Git operations must use HTTPS URLs (not SSH). `gh` defaults to HTTPS automatically; for `git clone`, use `https://github.com/...` instead of `git@github.com:...`.

All other domains (package mirrors, PyPI, etc.) route normally via Incus bridge NAT and are unaffected by the proxy.

**Credential validation**: Building a template image that includes `claude` or `gh` tools requires the corresponding credentials to be configured on the host. Both the CLI and TUI check this before starting a build and abort with a clear error if credentials are missing.

**Configuration**: `~/.config/incus-spawn/config.yaml` (owner-only permissions, `chmod 600`). CA key and certificate at `~/.config/incus-spawn/ca.key` and `~/.config/incus-spawn/ca.crt`. Vertex AI users must have `gcloud` installed on the host and `gcloud auth application-default login` completed.

### Host Resources

Template images can declare host files and directories to share with containers via the `host-resources` YAML key. Three modes control how the resource is made available:

**Readonly** (default): a read-only Incus disk device bind mount. The container can read the host file/directory but cannot modify it. Simple and safe for config files like `~/.gitconfig`.

**Overlay**: the host directory is attached as a read-only lower layer, with an ephemeral writable upper layer inside the container, combined via Linux overlayfs. The container sees a normal read-write directory, but writes go to the container-local upper layer — the host is fully protected. This is the right mode for caches (Maven, OCI) where tools expect to write but you don't need writes to persist back to the host.

**Copy**: the file or directory is copied into the container at build time and becomes part of the template. Supports local paths and URLs. No runtime dependency on the host.

#### Overlay internals

For a host-resource with `mode: overlay` targeting `/home/agentuser/.m2/repository`:

1. **Build time**: the host directory is attached as a read-only Incus disk device at `/var/lib/incus-spawn/overlays/home/agentuser/.m2/repository/lower`. The container creates `upper` and `work` siblings, then runs `mount -t overlay` to present the merged view at the target path. A systemd service (`incus-spawn-overlays.service`) is installed and enabled to re-apply the overlay mount on boot.

2. **After build**: the overlay is unmounted and the disk device is removed from the stopped template. The upper and work directories remain as container-local files. The full host-resource configuration is stored as JSON in `user.incus-spawn.host-resources` metadata.

3. **At branch time**: `BranchCommand` reads the stored metadata and re-attaches the disk device to the stopped instance before starting it. On boot, the systemd service re-mounts the overlay. The upper layer — now containing build artifacts — was copied via CoW when the instance was branched, so each instance has its own independent writable layer.

4. **On reboot**: the systemd service fires on every boot and re-mounts overlays. This works even if the container is started directly via `incus start` rather than through `isx`.

The overlay directory structure mirrors the container path directly under `/var/lib/incus-spawn/overlays/`, so the layout is self-documenting:

```
/var/lib/incus-spawn/overlays/home/agentuser/.m2/repository/
  ├── lower/    ← read-only disk device mount (host directory)
  ├── upper/    ← container-local writable layer (follows CoW branching)
  └── work/     ← overlayfs internal bookkeeping
```

Incus disk device names are derived from the container path for readability (e.g. `hr-home-agentuser--m2-repository`). They are removed from stopped templates to avoid host-path dependencies and re-attached at branch time.

#### Missing sources

If a host path doesn't exist at build or branch time, the entry is skipped with a warning. The build/branch proceeds without it.

#### Inheritance

Host resources compose additively across the parent chain, with override-by-container-path. If a parent declares `~/.gitconfig` as `readonly` and a child declares `~/.gitconfig` as `copy`, the child's mode wins. This follows the same last-write-wins pattern as package deduplication.

### Metadata Tracking

Containers tagged via Incus `user.*` config keys:

```
user.incus-spawn.type=base
user.incus-spawn.profile=tpl-java
user.incus-spawn.parent=tpl-dev
user.incus-spawn.created=2026-04-07
user.incus-spawn.network-mode=PROXY_ONLY     # (proxy-only branches only)
user.incus-spawn.proxy-gateway=10.166.11.1    # (proxy-only branches only)
user.incus-spawn.host-resources=[...]         # (JSON, when host-resources declared)
```

### Storage and COW

Copy-on-write storage is essential for efficient branching. `isx init` automatically creates a btrfs storage pool (`cow`) if no CoW-capable pool exists. All instance creation (`launch` and `copy`) auto-detects the best CoW pool and uses it via `--storage`, regardless of what the default Incus profile points to.

Supported CoW drivers: **btrfs**, **zfs**, **lvm**. If btrfs pool creation fails during init (e.g. unsupported filesystem), the user is warned and can continue with the `dir` driver, but clones will be full copies.

## Testing

**Unit tests** (`mvn test`, no Incus needed):
- `ToolDefTest` — YAML tool parsing (all fields, defaults, unknown fields)
- `ToolDefLoaderTest` — resolution order (builtins, user overrides, unknown tools)
- `YamlToolSetupTest` — execution order with mocked Container
- `ImageDefTest` — image definition loading, parent chain, descriptions

**Integration tests** (`mvn verify -DskipITs=false`, requires Incus):
- `TemplateBuildIT` — builds actual images, verifies metadata and agentuser

## Technical Tradeoffs

### System containers vs application containers
System containers run a full init system and present as a complete machine. This means higher base image size (~200MB vs ~5MB Alpine) and longer first-build time (system upgrade, user creation, tool installation). However, clones are instant and near-zero cost with CoW storage, which is the common operation — you build once, branch many times.

### No capability dropping (`lxc.cap.drop =`)
Standard Incus containers drop many Linux capabilities for defense-in-depth. We don't, because the container *is* the security boundary and developers expect `ping`, `strace`, `perf`, raw sockets, and `dmesg` to work. The risk is that a container escape exploit has more host capabilities to abuse. For untrusted code where this matters, use `--vm` for KVM isolation with a separate kernel.

### YAML tools vs a full plugin system (Packer, Ansible, etc.)
We evaluated Packer (null builder + shell provisioner) and Ansible but rejected both. Packer's null builder is just indirection over what Java already does, and Ansible adds a Python dependency and playbook complexity for what amounts to "install some packages and run some scripts." YAML tool definitions give 90% of the flexibility with zero dependencies. Java `ToolSetup` implementations remain available as an escape hatch for tools that need programmatic logic (reading host config, conditional branching).

### Hardcoded built-in tool list vs classpath scanning
Built-in YAML tools are loaded from a hardcoded list of filenames rather than scanning the classpath. This is a deliberate choice: Quarkus native image compilation makes classpath directory listing unreliable, and the list only changes when a developer adds a built-in tool (at which point they also update the loader). User-defined tools in `.incus-spawn/tools/` are discovered via filesystem scanning.

### DNS: static resolv.conf + bridge dnsmasq
systemd-resolved (127.0.0.53) doesn't work reliably inside Incus containers because it expects to manage the network configuration. We disable it, point `/etc/resolv.conf` directly at the Incus bridge gateway (which runs dnsmasq), and make the file immutable with `chattr +i`. This is less flexible than systemd-resolved (no per-link DNS, no DNSSEC validation) but works reliably across container restarts and network changes. Domain interception for the MITM proxy is configured at the bridge level via `raw.dnsmasq` (dnsmasq `address=` directives), not via per-container `/etc/hosts`. This avoids a class of bugs where Incus overwrites `/etc/hosts` on container start.

### Credential isolation via MITM TLS proxy
A TLS-terminating MITM proxy intercepts HTTPS connections to specific domains (Anthropic API, GitHub), injects authentication headers server-side, and forwards to the real upstream. Containers resolve these domains to the gateway IP via bridge-level dnsmasq overrides (configured when `isx proxy` starts) and trust the proxy's certificates via a custom CA installed in the template image. This approach was chosen over simpler alternatives (reverse proxy with `ANTHROPIC_BASE_URL`, credential helpers, shell wrappers) because those approaches still expose credentials to code running inside the container — either as environment variables, in process memory via `curl` calls, or through accessible endpoints. The MITM proxy provides complete isolation: there is no API, endpoint, environment variable, or file that container code can access to obtain credentials.

### Vertex AI: container in Vertex mode vs standard mode
We initially ran containers in standard (non-Vertex) mode with proxy-side API translation — the container sent `/v1/messages` and the proxy rewrote to Vertex `rawPredict`. This had a critical flaw: Claude Code's model list is provider-dependent. In standard "firstParty" mode the model picker is a hardcoded subset that omits newer models (e.g. Opus 4.6 was missing). In Vertex mode the full catalogue is shown.

The solution: containers now run in Vertex mode with `CLAUDE_CODE_USE_VERTEX=1`, `CLAUDE_CODE_SKIP_VERTEX_AUTH=1` (skips GCP auth — the SDK uses stub credentials that produce empty auth headers), and `ANTHROPIC_VERTEX_BASE_URL=https://api.anthropic.com/v1` (redirects the Vertex SDK to the proxy). The Vertex SDK formats requests in Vertex URL format (`/v1/projects/.../models/...:streamRawPredict`), sends them to the proxy, and the proxy injects real GCP credentials before forwarding to the actual Vertex endpoint. The proxy also retains the standard-to-Vertex translation path for any `/v1/messages` requests (e.g. from manual `curl` calls inside the container).

**Fragility and mitigation:** The standard-to-Vertex translation path uses an allowlist (`VERTEX_ALLOWED_FIELDS` in `MitmProxy.java`) that may drift as Anthropic adds new standard fields. However, the primary traffic flow (Vertex passthrough) doesn't use the allowlist — the Vertex SDK already formats the body correctly. The allowlist only affects the fallback translation path. The `anthropic_version: "vertex-2023-10-16"` value is hardcoded in the translation path — this matches the Anthropic Vertex SDK and has been stable since Vertex support launched. The Vertex passthrough path doesn't set this value; the SDK does it itself.

### Fedora-specific
The base image and package management are Fedora-specific (`dnf`, `images:fedora/43`). This is intentional — supporting multiple distros adds complexity for a tool primarily targeting developer workstations where Fedora is a common choice. The YAML tool system is distro-agnostic in principle (tools can use any shell commands), but the built-in base image setup assumes Fedora.

## Security Considerations

### Container vs VM Trade-off
- **Containers** (default): share host kernel. A kernel exploit could escape. Suitable for semi-trusted code (AI agents with scoped permissions, community bug reproducers).
- **VMs** (`--vm` flag): hardware-level isolation via KVM. Recommended for actively malicious code. Separate kernel eliminates kernel exploit as an escape vector. ~10% performance overhead.

### Credential Isolation

Real API keys and tokens never enter containers, regardless of network mode. Containers hold only placeholder values that satisfy tools' local auth checks; the proxy replaces them with real credentials before requests reach upstream servers.

| Credential | Container has | How it works |
|-----------|--------------|--------------|
| Claude API key (direct mode) | Placeholder `sk-ant-placeholder` | Proxy replaces `x-api-key` header with real key |
| GCP credentials (Vertex mode) | **Nothing** | Container runs Claude Code in Vertex mode with `CLAUDE_CODE_SKIP_VERTEX_AUTH=1`. Proxy injects GCP Bearer token from `gcloud` on the host. No GCP credentials, service accounts, or access tokens enter the container |
| GitHub token | Placeholder `gho_placeholder` in `GH_TOKEN` | Proxy replaces `Authorization` header with real token for GitHub domains (Basic auth for `github.com` git HTTP, Bearer for API) |

The MITM TLS proxy provides credential isolation:
1. Bridge-level dnsmasq overrides (configured by `isx proxy`) route intercepted domains to the gateway IP
2. A custom CA certificate (installed in template images) lets containers trust the proxy's TLS certs
3. The proxy terminates TLS, replaces placeholder auth with real credentials, and forwards to real upstream over TLS
4. Placeholder values cannot authenticate against any service — they only bypass local tool checks
5. In proxy-only mode, iptables OUTPUT rules additionally block all egress except the proxy port (443) and DNS

### Filesystem Isolation
- Inbox mount is strictly read-only
- Host resources default to read-only; overlay mode provides an ephemeral writable layer but the host directory is never modified
- Clone filesystems are independent CoW copies — changes in one clone don't affect others or the template image
