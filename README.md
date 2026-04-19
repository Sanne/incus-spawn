# incus-spawn

Isolated Linux environments that behave like bare-metal machines, not stripped-down application containers.

Unlike Docker/Podman containers, which package a single application with a minimal filesystem, incus-spawn creates full **system containers** powered by [Incus](https://linuxcontainers.org/incus/). Each environment runs its own init system, has real networking with working `ping`, `traceroute`, and `strace`, can run nested containers (Podman/Docker inside), and supports GUI and audio passthrough via Wayland. For untrusted code, KVM virtual machines provide hardware-level isolation with a separate kernel.

**Primary use cases:**
- Running untrusted AI agents (Claude Code, etc.) in isolated environments with pre-configured auth
- Reproducing bug reports from external contributors without risking your host
- Creating reproducible development environments with pre-cloned repos and cached dependencies

Built with [Quarkus](https://quarkus.io/) and [Tamboui](https://tamboui.dev/).

## Requirements

- **Linux** -- Incus system containers require a Linux kernel. macOS and Windows are not yet supported but are on the roadmap (likely via a managed Linux VM).
- **[JBang](https://www.jbang.dev/)** -- used to install and run incus-spawn. Alternatively, build from source with `./install.sh --native` for a standalone native binary that needs neither JBang nor a JVM
- **[Incus](https://linuxcontainers.org/incus/)** -- `isx init` auto-installs via the detected package manager (`dnf`, `apt`, `zypper`, or `pacman`); on other distros, install manually before running init

## Quick Start

```shell
# Install via JBang
jbang app install isx@Sanne/incus-spawn

# One-time host setup (Incus, firewall, auth)
isx init

# Build a template (builds parent images automatically)
isx build tpl-java

# Launch the interactive TUI
isx
```

## Branching

Like `git branch`, branching creates an instant copy-on-write clone of any template. Each branch has its own independent filesystem -- changes in one branch cannot affect the template or any other branch. The storage backend (btrfs/zfs/lvm) deduplicates unchanged data automatically, so branches are instant to create and only consume disk space for their own modifications. `isx init` automatically creates a btrfs storage pool if needed.

```
tpl-java  (stopped template, ~2GB)
  ├── fix-nasty-bug    (running, uses ~50MB extra)
  ├── review-pr-423    (running, uses ~30MB extra)
  └── experiment       (stopped, uses ~10MB extra)
```

You can install packages, break things, and destroy a branch when done. The template and other branches are completely unaffected.

Branches can optionally enable GUI/audio passthrough (Wayland), restricted networking, or an inbox mount to share files read-only from the host.

### Credential Isolation

**API keys and tokens never enter containers in any form.** A host-side MITM TLS proxy (`isx proxy`) provides completely transparent authentication:

- The proxy configures bridge-level DNS overrides (via dnsmasq on `incusbr0`) so containers resolve `api.anthropic.com`, `github.com`, and related domains to the Incus bridge gateway IP
- Template images include a custom CA certificate so containers trust the proxy's TLS certificates
- The proxy terminates TLS, injects authentication headers, and forwards to the real upstream over TLS
- Tools (`curl`, `git`, `gh`, `claude`) work transparently inside containers — placeholder auth values satisfy local checks, but the proxy replaces them with real credentials before requests reach upstream
- **Vertex AI support**: when the host uses Vertex AI, the proxy transparently translates standard API requests to Vertex AI `rawPredict` format — containers run Claude Code in standard mode with zero knowledge of Vertex, no GCP credentials
- There is no mechanism for code inside a container to read, extract, or exfiltrate real credentials
- **HTTPS only**: the proxy intercepts HTTPS traffic, so Git operations must use HTTPS URLs (not SSH). `gh` defaults to HTTPS automatically; for `git clone`, use `https://github.com/...` instead of `git@github.com:...`

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

## Template Images

Template images are reusable base environments defined in YAML. They can inherit from each other -- building an image automatically builds any missing parents:

```yaml
# images/java.yaml
name: tpl-java
description: JDK + Maven + Claude Code
parent: tpl-dev
packages:
  - java-25-openjdk-devel
  - java-25-openjdk-javadoc
  - java-25-openjdk-src
tools:
  - maven-3
```

Three images are built-in (`tpl-minimal`, `tpl-dev`, `tpl-java`). Add your own by placing YAML files in `~/.config/incus-spawn/images/` (user-level) or `.incus-spawn/images/` (project-local).
You can also point to external directories via `searchPaths` in `config.yaml` (see [Configuration](#configuration)); this is useful to version your templates in a separate git project.
Later sources override earlier ones: built-in → user → search paths → project-local.

Image schema fields (all optional except `name`):
- `image` -- base OS image, only for root images (default: `images:fedora/43`)
- `parent` -- parent image name (omit for root images)
- `packages` -- dnf packages to install
- `tools` -- tool names to run (resolved from YAML or Java)
- `repos` -- git repositories to clone as agentuser (see below)
- `skills` -- Claude Code skills to bake into the image (see below); accepts a list shorthand or an object with `repo` and `list` sub-fields
- `description` -- human-readable description for the TUI

```shell
# Build a specific image (builds missing parents automatically)
isx build tpl-java

# Rebuild all discovered images from scratch
isx build --all
```

### Declarative Repos

Images can declare git repositories to clone into the container.
Declaring a git repository rather than using shell commands to fetch it allows for better integration into other tools, such as Claude Code.

```yaml
name: tpl-quarkus
description: Quarkus development
parent: tpl-java
tools:
  - podman
  - gradle
repos:
  - url: https://github.com/quarkusio/quarkus.git
    path: ~/quarkus
    prime: mvn -B dependency:go-offline
```

Repo entry fields:
- `url` (required) -- git clone URL (HTTPS, for proxy compatibility)
- `path` (required) -- target directory (`~` expands to agentuser's home)
- `branch` (optional) -- branch or tag to check out; defaults to the repo's default branch
- `prime` (optional) -- shell command to run inside the repo directory after cloning, typically to pre-fetch dependencies (e.g. `mvn dependency:go-offline`, `gradle dependencies`)

### Claude Code Skills

Template images can declare [Claude Code skills](https://skills.sh) to bake in at build time. Skills are installed once into the template and inherited by every instance branched from it.

```yaml
name: tpl-agent
description: Agent with security skills
parent: tpl-dev
skills:
  repo: myorg/claude-skills      # default catalog for bare skill names
  list:
    - security-review            # short name → myorg/claude-skills@security-review
    - code-review                # short name → myorg/claude-skills@code-review
    - xixu-me/skills@xget        # explicit owner/repo@skill-name
    - myorg/catalog              # all skills from a repo
```

There is no implicit default catalog -- `repo` is only needed to resolve bare skill names (like `security-review` above). When all entries use the fully qualified `owner/repo@skill` or `owner/repo` form, you can omit `repo` and use the list shorthand:

```yaml
skills:
  - xixu-me/skills@xget
  - myorg/catalog
```

For local skills (e.g. skills you are developing), point to a directory containing a `SKILL.md` or subdirectories each with their own `SKILL.md`. Relative paths are resolved from the directory where `isx build` is run:

```yaml
skills:
  - ./my-skills/code-review      # single skill: my-skills/code-review/SKILL.md
  - ./my-skills                  # all skills: one per subdirectory with SKILL.md
```

Skill source formats:
- `owner/repo@skill-name` -- specific skill from a GitHub repo
- `owner/repo` -- all skills from a GitHub repo
- `https://github.com/owner/repo` -- full GitHub URL
- `./local-path` -- local directory (always read from disk, not cached)
- `skill-name` -- bare name, resolved as `repo@skill-name` using the `skills.repo` field. There is no built-in default catalog, so bare names require `repo` to be set -- otherwise the build will stop with an error explaining how to fix it.

Skills are fetched on the host at build time and cached at `~/.cache/incus-spawn/skills/`. They are not installed on the host — each SKILL.md is written directly into the container at `~/.claude/skills/<skill-name>/SKILL.md`, the global skills directory that Claude Code reads automatically. Subsequent builds reuse the cached files without hitting the network.

Skills are deduplicated across the parent chain: if a parent already declares a skill, child images skip it.

To find available skills, browse [skills.sh](https://skills.sh).

## Custom Tools

Template inheritance forms a single chain -- a template has exactly one parent. Tools provide composition: reusable capabilities that any template can mix in independently. A `gradle` tool can be added to a Java template, a Kotlin template, or a project-local template without duplicating definitions or creating diamond inheritance.

Tools are defined as YAML files and referenced from image definitions via `tools:`:

```yaml
# .incus-spawn/tools/gradle.yaml
name: gradle
description: Gradle 9.4.1

downloads:
  - url: https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
    sha256: 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
    extract: /opt
    links:
      /opt/gradle-9.4.1/bin/gradle: /usr/local/bin/gradle

verify: gradle --version

```

Downloads declared this way are cached on the host at `~/.cache/incus-spawn/downloads/`, so rebuilding images doesn't re-download unchanged artifacts.
Extraction happens on the host -- the container doesn't need `tar`, `unzip`, or `curl`.

Tool schema fields (all optional except `name`):
- `packages` -- dnf packages to install
- `downloads` -- artifacts to download, cache on the host, and extract into the container
- `run` -- shell commands as root
- `run_as_user` -- shell commands as agentuser
- `files` -- files to write (with optional `owner`)
- `env` -- lines appended to agentuser's `.bashrc`
- `verify` -- verification command (logged, non-fatal)

Download entry fields:
- `url` (required) -- download URL
- `sha256` (recommended) -- SHA-256 checksum; enables cache reuse and verifies integrity
- `extract` (required) -- directory in the container to extract into
- `links` (optional) -- map of `source_path: symlink_path` to create after extraction

Supported archive formats: `.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz`, `.zip`.

Execution order during `install()`: packages → downloads → `run` → `run_as_user` → `files` → `env` → `verify`.

Resolution order: built-in YAML → `~/.config/incus-spawn/tools/` (user) → search paths → `.incus-spawn/tools/` (project-local) → Java plugins.

## Features

- **Instant branching**: copy-on-write clones that share storage with the parent image
- **System containers**: full init, real networking, bare-metal-like developer experience
- **KVM VMs**: `--vm` flag for hardware-level isolation with separate kernel (optional)
- **Interactive TUI**: Midnight Commander-style interface for managing environments
- **GUI and audio passthrough**: Wayland + PipeWire with GPU acceleration
- **Inbox mount**: share a host directory read-only into the container
- **MITM TLS proxy**: transparent auth injection — credentials never enter containers in any form
- **Proxy caching**: OCI registry blobs and Maven/Gradle artifacts cached on the host, shared across all branches
- **Proxy-only networking**: (optional) iptables restricts egress to the MITM proxy only
- **Network airgapping**: fully isolate environments from the network
- **Adaptive resource limits**: CPU, memory, and disk auto-detected from host
- **Claude Code integration**: auth via MITM proxy — API key never enters containers
- **Claude Code skills**: bake skills into templates so they are available in every branched instance
- **GitHub integration**: auth via MITM proxy — token never enters containers

## TUI Keyboard Shortcuts

The TUI has two panels: **Templates** (top) and **Instances** (bottom). Press `Tab` to switch between them. Each function key has a consistent meaning across panels, with `Shift` variants for bulk/escalated operations.

**Templates panel:**

| Key | Action |
|-----|--------|
| `Enter` / `F4` | Branch from selected template |
| `F3` | Show template details |
| `F5` | Build selected template |
| `Shift+F5` | Rebuild all templates |
| `F8` | Destroy selected template |
| `Shift+F8` | Destroy all templates |

**Instances panel:**

| Key | Action |
|-----|--------|
| `Enter` / `F3` | Shell into selected instance |
| `F4` | Branch from selected instance |
| `F6` | Rename selected instance |
| `F7` | Stop selected instance |
| `Shift+F7` | Restart selected instance |
| `F8` | Destroy selected instance |
| `Shift+F8` | Destroy all instances |

**Global:**

| Key | Action |
|-----|--------|
| `Tab` | Switch panel |
| `Up/Down`, `j/k` | Navigate |
| `Ctrl+L` | Refresh |
| `F10` / `q` | Quit |

**Branch modal** (`F4`):

| Key | Action |
|-----|--------|
| `Alt-g` | Toggle GUI passthrough |
| `Alt-n` | Cycle network mode (Full / Proxy only / Airgapped) |
| `Alt-i` | Toggle inbox mount |
| `Tab` | Next field |
| `Enter` | Confirm |
| `Esc` | Cancel |

## Small Luxuries

Details that save time and avoid frustration:

- **Shared DNF cache**: building a chain of templates (e.g. `tpl-java` which derives from `tpl-dev` which derives from `tpl-minimal`) mounts a host-side cache (`~/.cache/incus-spawn/dnf`) into each container during the build. DNF metadata and downloaded packages are shared across all builds, so child images don't re-download what the parent just fetched. The cache is unmounted before the image is finalized, keeping templates clean.
- **Registry blob caching**: the MITM proxy caches OCI container image layers (`~/.cache/incus-spawn/registry/`) by content-addressed SHA256 digest. Pulling the same container image in different branches downloads each layer only once, with integrity verification.
- **Maven/Gradle artifact caching**: the MITM proxy caches artifacts from Maven Central, Maven repository, and Gradle plugin portal (`~/.cache/incus-spawn/maven/`). Release artifacts are immutable and cached permanently; SNAPSHOT and metadata requests pass through uncached.
- **CoW pool auto-creation**: `isx init` creates a btrfs storage pool if no copy-on-write pool exists, so branches are instant from the start.
- **Sudo ready**: your agents and scripts can invoke sudo at will, no password will be required.

## Installation

```shell
# Install via JBang (recommended)
jbang app install isx@Sanne/incus-spawn

# Or install directly from the latest release
jbang app install --name isx https://github.com/Sanne/incus-spawn/releases/latest/download/incus-spawn-runner.jar
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

Users can then install or update via `jbang app install isx@Sanne/incus-spawn`.

## Configuration

- `~/.config/incus-spawn/config.yaml` -- auth credentials and global settings
- `~/.config/incus-spawn/images/*.yaml` -- user-level template definitions
- `~/.config/incus-spawn/tools/*.yaml` -- user-level tool definitions
- `.incus-spawn/images/*.yaml` -- project-local template definitions
- `.incus-spawn/tools/*.yaml` -- project-local tool definitions

The `config.yaml` supports a `searchPaths` list for loading templates and tools from external directories. Each directory should contain `images/` and/or `tools/` subdirectories following the same YAML schema as the built-in definitions:

```yaml
searchPaths:
  - /home/user/my-templates
```

```
my-templates/
  images/
    quarkus.yaml
  tools/
    gradle.yaml
```

Resolution order (later sources override earlier ones with the same name):
1. Built-in (bundled with isx)
2. User (`~/.config/incus-spawn/`)
3. Search paths (in listed order)
4. Project-local (`.incus-spawn/`)
