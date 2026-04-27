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
- **[Incus](https://linuxcontainers.org/incus/)** -- `isx init` auto-installs via the detected package manager (`dnf`, `apt`, `zypper`, or `pacman`); on other distros, install manually before running init

## Quick Start

```shell
# Install
curl -fsSL https://raw.githubusercontent.com/Sanne/incus-spawn/main/get-isx.sh | sh

# One-time host setup (Incus, firewall, auth)
isx init

# Build a template (builds parent images automatically)
isx build tpl-java

# Launch the interactive TUI
isx
```

Fedora users can also install via `dnf`, and JBang users via `jbang` — see [Installation](#installation) for all options.

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

The proxy must be running for non-airgapped containers. `isx init` can install it as a systemd user service that starts automatically and survives reboots. Alternatively, run `isx proxy` in a separate terminal. View proxy logs with `isx proxy logs`.

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

## Git Remotes

Containers created with `isx branch` are isolated environments, but you need a way to get your changes back. incus-spawn integrates with git's native remote helper protocol so you can use standard `git fetch`, `git push`, and `git pull` between host repos and container repos:

```shell
# Inside the container, you make some commits...
# Back on the host:
git fetch fix-auth
git cherry-pick fix-auth/main
```

### isx:// URLs

The remote uses the `isx://` URL scheme:

```
isx://<instance-name>/<path-to-repo>
```

For example:

```shell
# Tilde expands to /home/agentuser
git remote add fix-auth isx://fix-auth/~/quarkus

# Absolute paths work too
git remote add fix-auth isx://fix-auth/home/agentuser/quarkus

# Then use standard git commands
git fetch fix-auth
git log fix-auth/main
git diff main..fix-auth/main
git pull fix-auth main
git push fix-auth main
```

The instance must be running for git operations to work. If you specify a wrong path, the error message lists known repositories from the image definition.

### Automatic remotes

If you configure `host-path` in `~/.config/incus-spawn/config.yaml`, remotes are managed automatically:

```yaml
# Base directory where your repos live on the host
host-path: ~/projects

# Optional: explicit overrides for repos in non-standard locations
repo-paths:
  quarkus: ~/work/quarkus
  hibernate: /opt/hibernate
```

With this configuration:

- **`isx branch`** adds a git remote named after the instance in each matching host repo. A host repo matches when its `origin` URL corresponds to a repo declared in the template's image definition (protocol-lenient — SSH and HTTPS URLs for the same repo are treated as equal).
- **`isx destroy`** removes the remote from host repos.

```shell
# Branch from a template that declares a quarkus repo
isx branch fix-auth tpl-quarkus

# The remote is automatically added in ~/work/quarkus:
#   git remote add fix-auth isx://fix-auth/home/agentuser/quarkus
cd ~/work/quarkus
git fetch fix-auth

# When done, destroy the instance — the remote is cleaned up
isx destroy fix-auth
```

If a remote with the instance name already exists, a warning is printed with instructions to add it under a different name.

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

Use `isx templates` to manage templates from the CLI:

```shell
# List all available templates
isx templates list
isx templates list -v          # with source path and description

# Create a new template (opens in $EDITOR with a commented skeleton)
isx templates new my-app       # creates ~/.config/incus-spawn/images/my-app.yaml
isx templates new my-app --project  # creates .incus-spawn/images/my-app.yaml

# Edit an existing template
isx templates edit tpl-java    # opens in $EDITOR, validates on save
```

Editing a built-in template automatically creates a user-level override in `~/.config/incus-spawn/images/`. The override takes precedence over the built-in but will not auto-update with isx upgrades. Templates are validated after editing: YAML syntax, required fields, and parent references are checked.

Image schema fields (all optional except `name`):
- `image` -- base OS image, only for root images (default: `images:fedora/43`)
- `parent` -- parent image name (omit for root images)
- `packages` -- dnf packages to install
- `tools` -- tool names to run (resolved from YAML or Java)
- `repos` -- git repositories to clone as agentuser (see below)
- `skills` -- Claude Code skills to bake into the image (see below); accepts a list shorthand or an object with `repo` and `list` sub-fields
- `host-resources` -- host files/directories to share with containers (see below)
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

### Host Resources

Template images can declare host files and directories to make available inside containers. This is useful for sharing configuration files, pre-populating caches, or providing large datasets without copying them into every template.

```yaml
name: tpl-java
parent: tpl-dev
packages:
  - java-25-openjdk-devel
tools:
  - maven-3
host-resources:
  - source: ~/.m2/repository
    mode: overlay
  - source: ~/.gitconfig
```

The `~/.m2/repository` entry shares your host Maven cache with the container. With `mode: overlay`, the container sees a normal read-write directory pre-populated with your cached artifacts, but writes go to a container-local layer -- your host cache is never modified. Maven builds that would normally download hundreds of megabytes of dependencies can instead resolve them instantly from the shared cache.

The `~/.gitconfig` entry mounts your git configuration read-only (the default mode), so `git` inside the container picks up your name, email, aliases, and other settings.

Three modes are available:

| Mode | Default? | Description |
|------|----------|-------------|
| `readonly` | Yes | Read-only bind mount. Simple, safe. |
| `overlay` | No | Read-only lower layer from host + ephemeral writable upper in the container. Tools see a normal read-write directory. Host is fully protected. |
| `copy` | No | Copied into the container at build time. Becomes part of the template. Also supports URL sources. |

If `path` is omitted, it defaults to the same relative path under `/home/agentuser/`. For example, `source: ~/.m2/repository` maps to `/home/agentuser/.m2/repository` inside the container.

More examples:

```yaml
host-resources:
  # Share SSH config (read-only)
  - source: ~/.ssh/config

  # Copy a custom gitconfig from a URL
  - source: https://example.com/team-gitconfig
    path: /home/agentuser/.gitconfig
    mode: copy

  # Share Gradle cache with overlay (writable inside container, host protected)
  - source: ~/.gradle/caches
    mode: overlay
```

If a host path doesn't exist at build or branch time, the entry is skipped with a warning -- the build proceeds without it. This means templates with host-resources remain portable: they work on machines that have the declared paths and gracefully degrade on machines that don't.

Host resources compose across the parent chain: a child image inherits its parent's host-resources and can override individual entries (matched by container path) to change the mode.

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
- `requires` -- list of other tool names that must be installed first (resolved transitively; circular dependencies are detected and rejected)
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

### Remote IDE Access

The built-in `idea-backend` tool installs the JetBrains IntelliJ IDEA remote development backend, allowing you to connect from JetBrains Gateway on the host. It declares `requires: [sshd]`, so the SSH server is installed automatically:

```yaml
name: tpl-java-ide
parent: tpl-java
tools:
  - idea-backend    # auto-installs sshd via requires
```

After branching, connect from JetBrains Gateway using the container's IP (visible in the TUI via F3) over SSH as `agentuser`. Add your SSH public key to the template's `~/.ssh/authorized_keys` (e.g. via a host-resource or a custom tool) so authentication works automatically in every branch.

## Features

- **Instant branching**: copy-on-write clones that share storage with the parent image
- **System containers**: full init, real networking, bare-metal-like developer experience
- **KVM VMs**: `--vm` flag for hardware-level isolation with separate kernel (optional)
- **Interactive TUI**: Midnight Commander-style interface with F3 detail views, template staleness indicators, and modal dialogs for branching, renaming, and building
- **GUI and audio passthrough**: Wayland + PipeWire with GPU acceleration
- **Host resources**: share host files and directories with containers (read-only, overlay, or copy)
- **Inbox mount**: share a host directory read-only into the container
- **MITM TLS proxy**: transparent auth injection — credentials never enter containers in any form
- **Proxy caching**: OCI registry blobs and Maven/Gradle artifacts cached on the host, shared across all branches
- **Proxy-only networking**: (optional) iptables restricts egress to the MITM proxy only
- **Network airgapping**: fully isolate environments from the network
- **Adaptive resource limits**: CPU, memory, and disk auto-detected from host
- **Claude Code integration**: auth via MITM proxy — API key never enters containers
- **Claude Code skills**: bake skills into templates so they are available in every branched instance
- **GitHub integration**: auth via MITM proxy — token never enters containers
- **Git remotes**: `git fetch`/`git push` between host and container repos via `isx://` URLs, with automatic remote management
- **Remote IDE**: JetBrains Gateway support via built-in `idea-backend` tool with transitive `sshd` dependency
- **Tool dependencies**: tools can declare `requires` for automatic transitive dependency resolution
- **Version drift detection**: warns when templates were built with a different isx version or when definitions have changed since the last build
- **Shell completions**: bash, zsh, and fish via `isx completion {bash,zsh,fish}`

## CLI Commands

| Command | Description |
|---------|-------------|
| `isx` | Launch the interactive TUI |
| `isx init` | One-time host setup (Incus, firewall, auth) |
| `isx build <template>` | Build or rebuild a template image |
| `isx build --all` | Rebuild all discovered templates |
| `isx build --missing` | Build only templates that don't exist yet |
| `isx branch <name>` | Create a CoW clone from a template or instance |
| `isx shell <instance>` | Open a shell in an instance |
| `isx destroy <instance>` | Destroy an instance |
| `isx update-all` | Update all templates (packages, repos, tools) |
| `isx templates` | List available templates |
| `isx templates list -v` | List templates with source and description |
| `isx templates new <name>` | Create a new template definition |
| `isx templates edit <name>` | Edit a template in `$EDITOR` |
| `isx instances` | List connectable instance names (excludes templates) |
| `isx project create <name>` | Create a project template from `incus-spawn.yaml` |
| `isx project update <name>` | Update an existing project template |
| `isx proxy start` | Start the MITM auth proxy |
| `isx proxy stop` | Stop the proxy |
| `isx proxy status` | Show proxy status |
| `isx proxy install` | Install proxy as a systemd user service |
| `isx proxy uninstall` | Stop and remove the systemd proxy service |
| `isx proxy logs` | View proxy logs |
| `isx proxy dump` | Run a local pass-through proxy for API traffic capture |
| `isx completion <shell>` | Print shell completion script (bash, zsh, fish) |

Use `isx <command> --help` for detailed options on any command.

## Small Luxuries

Details that save time and avoid frustration:

- **Shared DNF cache**: building a chain of templates (e.g. `tpl-java` which derives from `tpl-dev` which derives from `tpl-minimal`) mounts a host-side cache (`~/.cache/incus-spawn/dnf`) into each container during the build. DNF metadata and downloaded packages are shared across all builds, so child images don't re-download what the parent just fetched. The cache is unmounted before the image is finalized, keeping templates clean.
- **Registry blob caching**: the MITM proxy caches OCI container image layers (`~/.cache/incus-spawn/registry/`) by content-addressed SHA256 digest. Pulling the same container image in different branches downloads each layer only once. Each blob is verified against its SHA256 digest before being committed to the cache.
- **Maven/Gradle artifact caching**: the MITM proxy caches artifacts from Maven Central, Maven repository, and Gradle plugin portal (`~/.cache/incus-spawn/maven/`). Release artifacts are immutable and cached permanently; SNAPSHOT and metadata requests pass through uncached. When artifacts already exist in the host's `~/.m2/repository`, the proxy verifies their SHA1 against the upstream checksum before serving — stale or corrupted local artifacts are never served.
- **CoW pool auto-creation**: `isx init` creates a btrfs storage pool if no copy-on-write pool exists, so branches are instant from the start.
- **Sudo ready**: your agents and scripts can invoke sudo at will, no password will be required.
- **Failed build inspection**: if a template build fails, the container is promoted to an inspectable instance so you can shell in and debug.
- **Proxy health check**: builds, branches, and shell access verify the proxy is reachable before proceeding, so you get a clear error instead of mysterious connection failures.
- **Template staleness indicators**: the TUI marks templates with `!` when they were built with a different isx version, and `△` when the image or tool definition has changed since the last build. This tells you at a glance which templates need rebuilding.
- **Version drift remediation**: if the running proxy version doesn't match the CLI, the proxy is automatically restarted (when no containers are running) or a warning is shown.
- **CA certificate mismatch warning**: branching from a template built with a different CA certificate warns you before you hit TLS failures.
- **Claude Code auto-trust**: when templates declare repos, the build pre-trusts those directories in `.claude.json` so Claude Code doesn't prompt for trust on first use.
- **Terminal title**: shell sessions set the terminal title to `isx:<containername>`, and the container prompt maintains it. Easy to identify which terminal belongs to which container.

## Installation

### Fedora (DNF)

```shell
sudo dnf copr enable sanne/incus-spawn
sudo rpm --import https://download.copr.fedorainfracloud.org/results/sanne/incus-spawn/pubkey.gpg
sudo dnf install incus-spawn
```

Updates automatically with `sudo dnf upgrade`.

### Any Linux distro (native binary)

```shell
curl -fsSL https://raw.githubusercontent.com/Sanne/incus-spawn/main/get-isx.sh | sh
```

Installs a self-contained native binary to `~/.local/bin/isx`. No JVM required. Set `INSTALL_DIR` to change the install location. To update, re-run the same command. To uninstall, run `uninstall.sh` (caches at `~/.cache/incus-spawn/` are preserved unless you pass `--purge`).

### JVM via JBang

```shell
jbang app install isx@Sanne/incus-spawn
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
./install.sh --native   # native (requires Docker, Podman, or GraalVM)
```

## Releasing

Releases are automated via GitHub Actions. To create a new release, run:

```shell
./release.sh
```

The script derives the version from the POM snapshot (e.g. `0.1.9-SNAPSHOT` → `v0.1.9`), validates the working tree, creates the tag, and pushes it. You can also pass an explicit version: `./release.sh 0.2.0`.

Pushing the tag triggers a workflow that will:
1. Set the project version from the tag
2. Build a self-contained uber-jar (for JBang users)
3. Build a native binary via container-based GraalVM compilation
4. Create a GitHub Release with auto-generated release notes and both artifacts attached
5. Publish the native binary as an RPM to [Fedora COPR](https://copr.fedorainfracloud.org/coprs/sanne/incus-spawn/)
6. Bump the POM version to the next snapshot

Users can then install or update via `dnf upgrade` (Fedora), `curl -fsSL .../get-isx.sh | sh` (native), or `jbang app install isx@Sanne/incus-spawn` (JVM).

## Configuration

- `~/.config/incus-spawn/config.yaml` -- auth credentials and global settings
- `~/.config/incus-spawn/images/*.yaml` -- user-level template definitions
- `~/.config/incus-spawn/tools/*.yaml` -- user-level tool definitions
- `.incus-spawn/images/*.yaml` -- project-local template definitions
- `.incus-spawn/tools/*.yaml` -- project-local tool definitions

The `config.yaml` also supports git remote auto-management via `host-path` and `repo-paths` (see [Git Remotes](#git-remotes)), and a `searchPaths` list for loading templates and tools from external directories. Each directory should contain `images/` and/or `tools/` subdirectories following the same YAML schema as the built-in definitions. Tilde (`~`) expansion is supported for all path settings:

```yaml
searchPaths:
  - ~/my-templates
  - /absolute/path/to/templates
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
