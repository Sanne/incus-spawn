# incus-spawn

Spin up isolated Linux environments in seconds — full system containers with copy-on-write branching and transparent credential isolation. Run AI coding agents, triage untrusted patches, reproduce bug reports — without risking your host.

**Docker and Podman are built for shipping applications** — minimal filesystems, single-process isolation, fast startup. incus-spawn solves a different problem: full **system containers** powered by [Incus](https://linuxcontainers.org/incus/) that behave like real machines. Each environment runs its own init system, has real networking (`ping`, `strace`, nested Podman/Docker), and supports GUI and audio passthrough (Linux only). Templates pre-install your baseline tools and repos, but the environment is a real Linux system — agents and users can freely `dnf install`, `pip install`, build from source, or run Docker Compose just like on a workstation. For untrusted code, KVM virtual machines provide hardware-level isolation with a separate kernel.

**API keys and tokens never enter containers.** A host-side MITM TLS proxy intercepts HTTPS and injects credentials transparently — `claude`, `pi`, `gh`, `git`, `curl`, and any other tool work unmodified, with no configuration or wrappers needed inside the environment. Branches run with full internet, proxy-only egress, or completely airgapped. The proxy also caches container image layers and build artifacts on the host — the same dependency is never downloaded twice.

**Branching is instant.** Like `git branch`, each clone is a copy-on-write snapshot that shares storage with its parent. Build a template once with your preferred tools and repos, then spin up complete, disposable environments in seconds — each with its own filesystem, networking, and process tree. Get your work back via standard `git fetch` using `isx://` remotes.

**Built for developer workflows.** Templates are YAML: packages, tools, repos. Branch and it's all there. Git remotes are managed automatically — `git fetch fix-auth` from your host pulls commits straight out of the container. JetBrains Gateway, shell completions, and Claude Code skills plug in via the same tool system.

Built with [Quarkus](https://quarkus.io/) and [Tamboui](https://tamboui.dev/).

## Quick Start

Requires **Linux or macOS (Apple Silicon)**. On Linux, [Incus](https://linuxcontainers.org/incus/) runs natively and `isx init` auto-installs it via your package manager. On macOS, `isx init` provisions a lightweight Linux VM automatically via [vfkit](https://github.com/crc-org/vfkit). The VM starts automatically when needed and can be managed with `isx vm start|stop|status`. Windows and Intel Macs are not yet supported.

**macOS limitations**: GUI/audio passthrough (Wayland + PipeWire) and `overlay` mode for host-resources are Linux-only features. On macOS, use `readonly` or `copy` modes for host-resources instead.

On macOS (Apple Silicon):

```shell
brew install Sanne/tap/incus-spawn
```

On Linux (x86_64):

```shell
curl -fsSL https://raw.githubusercontent.com/Sanne/incus-spawn/main/get-isx.sh | sh
```

On other Linux architectures:

```shell
jbang app install isx@Sanne/incus-spawn
```

```shell
# One-time host setup (Incus, firewall, auth)
isx init

# Build a template (builds parent images automatically)
isx build tpl-java

# Launch the interactive TUI
isx
```

Fedora users can also install via `dnf`, and JBang users via `jbang` — see [Installation](#installation) for all options. Shell completions are available for bash, zsh, and fish via `isx completion <shell>`.

## Branching

Like `git branch`, branching creates an instant copy-on-write clone of any template. Each branch has its own independent filesystem -- changes in one branch cannot affect the template or any other branch. The storage backend (btrfs/zfs/lvm) deduplicates unchanged data automatically, so branches are instant to create and only consume disk space for their own modifications. `isx init` automatically creates a btrfs storage pool if needed.

```
tpl-java  (stopped template, ~2GB)
  ├── fix-nasty-bug    (running, uses ~50MB extra)
  ├── review-pr-423    (running, uses ~30MB extra)
  └── experiment       (stopped, uses ~10MB extra)
```

You can install packages, break things, and destroy a branch when done. The template and other branches are completely unaffected. Sudo works without a password, and shell sessions set the terminal title to `isx:<containername>` so you always know which environment you're in.

Branches can optionally enable GUI/audio passthrough (Wayland + PipeWire with GPU acceleration, Linux only), restricted networking, or an inbox mount to share files read-only from the host. Resource limits (CPU, memory, disk) are auto-detected from the host but can be overridden. The interactive TUI (`isx` with no arguments) provides a Midnight Commander-style interface with modal dialogs for branching, renaming, and building, plus F3 detail views and F9 tool actions.

### Credential Isolation

**API keys and tokens never enter containers in any form.** A host-side MITM TLS proxy (`isx proxy`) provides completely transparent authentication:

- The proxy configures bridge-level DNS overrides (via dnsmasq on `incusbr0`) so containers resolve `api.anthropic.com`, `github.com`, and related domains to the Incus bridge gateway IP
- Template images include a custom CA certificate so containers trust the proxy's TLS certificates
- The proxy terminates TLS, injects authentication headers, and forwards to the real upstream over TLS
- Tools (`curl`, `git`, `gh`, `claude`, `pi`) work transparently inside containers — placeholder auth values satisfy local checks, but the proxy replaces them with real credentials before requests reach upstream
- **Vertex AI support**: when the host uses Vertex AI, the proxy transparently translates standard API requests to Vertex AI `rawPredict` format — containers run Claude Code in standard mode with zero knowledge of Vertex, no GCP credentials
- **Claude Pro/Max support**: users with a Claude subscription (no API key) can authenticate via `claude setup-token`. The proxy injects the OAuth Bearer token transparently — no API key required
- There is no mechanism for code inside a container to read, extract, or exfiltrate real credentials
- **HTTPS only**: the proxy intercepts HTTPS traffic, so Git operations must use HTTPS URLs (not SSH). `gh` defaults to HTTPS automatically; for `git clone`, use `https://github.com/...` instead of `git@github.com:...`

The proxy must be running for non-airgapped containers. `isx init` can install it as a systemd user service that starts automatically and survives reboots. Alternatively, run `isx proxy` in a separate terminal. View proxy logs with `isx proxy logs`. Builds, branches, and shell access verify the proxy is reachable before proceeding, so you get a clear error instead of mysterious connection failures. If the running proxy version doesn't match the CLI, it is automatically restarted (when no containers are running) or a warning is shown. Branching from a template built with a different CA certificate also warns you before you hit TLS failures.

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

### Git Remotes

Containers created with `isx branch` are isolated environments, but you need a way to get your changes back. incus-spawn integrates with git's native remote helper protocol so you can use standard `git fetch`, `git push`, and `git pull` between host repos and container repos:

```shell
# Inside the container, you make some commits...
# Back on the host:
git fetch fix-auth
git cherry-pick fix-auth/main
```

#### isx:// URLs

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

#### Automatic remotes

If you configure `host-paths` in `~/.config/incus-spawn/config.yaml`, remotes are managed automatically:

```yaml
# Base directories where your repos live on the host
# If a repo exists in multiple host-paths, you must add an explicit repo-paths entry
host-paths:
  - ~/projects
  - ~/workspace

# Explicit overrides for repos in non-standard locations or to resolve ambiguity
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

## Caching

The proxy caches to save bandwidth, not to cut corners. Every cache hit is guaranteed to return the same bytes you'd get by fetching directly from upstream — pure speed boost with zero risk. Mutable artifacts (Maven SNAPSHOTs, repository metadata, version listings) are never cached. Immutable artifacts are only committed to the cache after verification against their content digest or upstream checksum — a mismatch means the artifact is discarded and re-fetched. When possible, the proxy also validates artifacts already on the host (e.g. your `~/.m2/repository`) against upstream checksums before serving, avoiding both a redundant download and the risk of serving stale local data.

The MITM proxy caches artifacts from container traffic on the host, shared across all templates and branches:

- **Container image layers** — OCI blobs from Docker Hub, GHCR, and Quay, keyed and verified by SHA256 content digest
- **Maven and Gradle artifacts** — release JARs, POMs, and plugins from Maven Central and the Gradle plugin portal. SNAPSHOTs and metadata pass through uncached
- **Gradle distributions** — verified against the upstream `.sha256` sidecar

Build-time caches avoid redundant work across the template chain:

- **DNF packages** — a host-side cache is mounted into each container during builds, so child images don't re-download what the parent already fetched
- **Tool downloads** — artifacts declared in tool YAML definitions are downloaded and extracted on the host, then pushed into containers. Rebuilds reuse cached files when the SHA256 matches

All caches live under `~/.cache/incus-spawn/` (`registry/`, `maven/`, `gradle/`, `dnf/`, `downloads/`). There is no automatic eviction — cached artifacts accumulate until you delete them manually. This is deliberate: every cached artifact is either content-addressed or version-pinned, so it's either correct forever or superseded by a newer version that gets its own cache entry.

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
- `image` -- base OS image, only for root images (default: `images:fedora/44`)
- `parent` -- parent image name (omit for root images)
- `packages` -- dnf packages to install
- `tools` -- tool names to run (resolved from YAML or Java, see [Custom Tools](#custom-tools))
- `repos` -- git repositories to clone as agentuser (see below)
- `skills` -- Claude Code skills to bake into the image (see below); accepts a list shorthand or an object with `repo` and `list` sub-fields
- `host-resources` -- host files/directories to share with containers (see below)
- `workdir` -- default working directory when shelling into a container (see below)
- `shell-command` -- command to run instead of the login shell (see below)
- `description` -- human-readable description for the TUI

```shell
# Build a specific image (builds missing parents automatically)
isx build tpl-java

# Rebuild a template and all its parents from scratch
isx build tpl-java --with-parents

# Rebuild out-of-sync templates (changed definitions or older isx version)
isx build --out-of-sync

# Rebuild all discovered images from scratch
isx build --all
```

The TUI marks templates with `!` when they were built with a different isx version, and `△` when the image or tool definition has changed since the last build — `isx build --out-of-sync` rebuilds these automatically. If a build fails, the container is promoted to an inspectable instance so you can shell in and debug.

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

Declared repos are automatically pre-trusted in `.claude.json` so Claude Code doesn't prompt for trust on first use.

### Shell Defaults

Templates can configure the default working directory and shell command when connecting to a container via `isx shell` or the TUI:

```yaml
name: tpl-quarkus
parent: tpl-java
repos:
  - url: https://github.com/quarkusio/quarkus.git
    path: ~/quarkus
workdir: ~/quarkus
shell-command: claude
```

- `workdir` -- the directory to `cd` into when opening a shell. If omitted, defaults to the checkout path of the first declared repo. When a template inherits from a parent, the child's repos take priority; if the child has no repos, the parent's first repo is used. If no repos exist anywhere in the chain, the shell opens in the home directory.
- `shell-command` -- a command to run instead of the default login shell (e.g. `claude` to launch Claude Code directly, or `pi` to launch the Pi coding agent). If the command fails to start, the shell falls back to `bash --login`.

Both values are stored as Incus metadata at build time and propagate automatically to branches. A missing `workdir` directory never blocks shell access -- the `cd` fails silently and the shell opens in the home directory.

### Pi Coding Agent

Pi is a provider-agnostic CLI coding agent that uses the standard Anthropic API. Add it to any template with `tools: [pi]`:

```yaml
name: tpl-pi-dev
description: Isolated dev environment with Pi coding agent
parent: tpl-dev
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - pi
shell-command: pi
```

Pi is pre-configured at build time with a placeholder `ANTHROPIC_API_KEY`. The [MITM proxy](#credential-isolation) intercepts all requests to `api.anthropic.com` and injects your real credentials transparently — Pi works out of the box, and your API key never enters the container. This works with all three auth modes: direct API key, Claude Pro/Max OAuth token, and Vertex AI. If your host is configured for Vertex AI, the proxy automatically translates Pi's standard Anthropic API requests to Vertex AI format, with no configuration needed inside the container.

To run Pi without making it the default shell, omit `shell-command` and just include it in `tools:`:

```yaml
tools:
  - pi
```

You can then launch it manually with `pi` after connecting via `isx shell <instance>`.

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
| `overlay` | No | Read-only lower layer from host + ephemeral writable upper in the container. Tools see a normal read-write directory. Host is fully protected. **Linux only** — not yet supported on macOS. |
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
- `actions` -- runtime actions available from the TUI when the tool is installed (see [Tool Actions](#tool-actions))

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

The `idea-backend` tool accepts a `memory` parameter to control the JVM heap size (default `2g`). Use the map form to customize it:

```yaml
tools:
  - idea-backend:
      memory: "8g"
```

SSH keys are managed automatically: `isx init` generates a dedicated passphraseless key pair at `~/.config/incus-spawn/ssh/`, and each branch injects it into the container along with your personal `~/.ssh` key. Container host keys are pre-validated so `ssh <instance-name>` just works — no passphrase prompt, no host key warning. Entries are cleaned up when instances are destroyed.

The `idea-backend` tool also declares a tool action that lets you open repos directly in Gateway from the TUI — press F9 on a running instance to see available actions, including an "Open repo in Gateway" entry for each declared repository.

### Tool Parameters

Tools can define parameters to allow configuration at build time. Parameters support validation by type (string, integer, boolean, enum) and constraints (regex patterns, min/max ranges, allowed values).

Define parameters in the tool YAML:

```yaml
# tools/example-tool.yaml
name: example-tool
description: Example parameterized tool
parameters:
  memory:
    type: string
    default: "2g"
    description: "JVM heap size"
    pattern: "^[0-9]+[gGmM]$"
  port:
    type: integer
    default: "8080"
    min: 1024
    max: 65535
    description: "Server port"
  debug:
    type: boolean
    default: "false"
    description: "Enable debug mode"
  mode:
    type: enum
    default: "production"
    options:
      - "production"
      - "development"
      - "testing"
    description: "Deployment mode"

run_as_user:
  - echo "Memory: ${param_memory}, Port: ${param_port}"
  - |
    if [ "${param_debug}" = "true" ]; then
      export DEBUG=1
    fi

env:
  - export APP_MEMORY=${param_memory}
  - export APP_PORT=${param_port}
```

Reference parameterized tools in image definitions:

```yaml
# images/example.yaml
name: tpl-example
tools:
  - maven-3                      # Simple string form (uses defaults)
  - example-tool:                # Map form with custom parameters
      memory: "8g"
      port: "9000"
      debug: "true"
      mode: "development"
```

Parameter values are substituted during tool installation. Use `${param_name}` in tool scripts, environment variables, and file content.

**Validation**: When a tool defines parameters, any unknown or invalid parameters will trigger validation errors. Tools that do not define a `parameters` section will reject any parameters provided to them.

### Tool Actions

Tools can declare runtime actions that appear in the TUI when the tool is installed on an instance. Press **F9** on a selected instance to open the actions menu. Actions are only shown for tools that are part of the instance's template chain.

Three action types are supported:

| Type | Description |
|------|-------------|
| `url` | Opens a URL in the default browser (via `xdg-open`) |
| `command` | Runs a shell command on the host (exits the TUI, re-enters after) |
| `copy-to-clipboard` | Copies text to the system clipboard (via `xclip`) |

Actions support template variables that are interpolated at execution time:

| Variable | Value |
|----------|-------|
| `${ip}` | Instance IPv4 address |
| `${name}` | Instance name |
| `${parent}` | Parent template name |
| `${repo_name}` | Repository directory name (when expanded) |
| `${repo_path}` | Repository path inside the container (when expanded) |
| `${repo_url}` | Repository clone URL (when expanded) |

Use `expand: repos` to generate one action per declared repository in the template's inheritance chain. This is how the `idea-backend` tool creates a separate "Open in Gateway" entry for each repo:

```yaml
# tools/idea-backend.yaml (excerpt)
actions:
  - label: "Open repo '${repo_name}' in Gateway"
    type: url
    expand: repos
    url: "jetbrains-gateway://connect#idePath=%2Fopt%2Fidea&host=${ip}&port=22&user=agentuser&type=ssh&deploy=false&projectPath=${repo_path}"
```

A standalone action (without `expand`) looks like:

```yaml
actions:
  - label: "Open web UI"
    type: url
    url: "http://${ip}:8080"
  - label: "Copy SSH command"
    type: copy-to-clipboard
    text: "ssh agentuser@${ip}"
  - label: "Run migrations"
    type: command
    command: "incus exec ${name} -- sudo -u agentuser /home/agentuser/app/migrate.sh"
```

Action entry fields:
- `label` (required) -- text shown in the actions menu (supports template variables)
- `type` (required) -- one of `url`, `command`, or `copy-to-clipboard`
- `url` -- URL to open (for `url` type)
- `command` -- shell command to run on the host (for `command` type)
- `text` -- text to copy (for `copy-to-clipboard` type)
- `expand` -- set to `repos` to generate one action per declared repository
- `requires_running` -- whether the instance must be running (default: `true`)
- `auto_return` -- for `command` type, skip the "press any key" prompt after execution (default: `false`)

Actions can also be contributed programmatically by CDI beans implementing the `ToolAction` interface, for cases that need logic beyond what YAML declarations can express.

## Installation

### macOS (Homebrew)

```shell
brew install Sanne/tap/incus-spawn
```

Requires Apple Silicon. Updates with `brew upgrade incus-spawn`. See [docs/HOMEBREW.md](docs/HOMEBREW.md) for details.

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

## Website Development

The project website is hosted on GitHub Pages. To preview changes locally:

```shell
# Build the site (generates _site/ directory from README.md)
./site/build.sh

# Serve locally
cd _site && python3 -m http.server 8000
```

Then open http://localhost:8000 in your browser. The build script converts README.md to HTML and generates the table of contents for the docs page.

## Releasing

Releases are automated via GitHub Actions. To create a new release, run:

```shell
./release.sh
```

The script derives the version from the POM snapshot (e.g. `0.1.9-SNAPSHOT` → `v0.1.9`), validates the working tree, creates the tag, and pushes it. You can also pass an explicit version: `./release.sh 0.2.0`.

Pushing the tag triggers a workflow that will:
1. Set the project version from the tag
2. Build a self-contained uber-jar (for JBang users)
3. Build native binaries via GraalVM (Linux amd64/aarch64, macOS aarch64)
4. Create a GitHub Release with auto-generated release notes and all artifacts attached
5. Update the [Homebrew tap](https://github.com/Sanne/homebrew-tap) with new checksums
6. Publish the native binary as an RPM to [Fedora COPR](https://copr.fedorainfracloud.org/coprs/sanne/incus-spawn/)
7. Bump the POM version to the next snapshot

Users can then install or update via `brew upgrade` (macOS), `dnf upgrade` (Fedora), `curl -fsSL .../get-isx.sh | sh` (native), or `jbang app install isx@Sanne/incus-spawn` (JVM).

## Configuration

- `~/.config/incus-spawn/config.yaml` -- auth credentials and global settings
- `~/.config/incus-spawn/ssh/` -- managed SSH key pair, per-instance config, and known_hosts
- `~/.config/incus-spawn/images/*.yaml` -- user-level template definitions
- `~/.config/incus-spawn/tools/*.yaml` -- user-level tool definitions
- `.incus-spawn/images/*.yaml` -- project-local template definitions
- `.incus-spawn/tools/*.yaml` -- project-local tool definitions

The `config.yaml` also supports git remote auto-management via `host-paths` and `repo-paths` (see [Git Remotes](#git-remotes)), and a `searchPaths` list for loading templates and tools from external directories. Each directory should contain `images/` and/or `tools/` subdirectories following the same YAML schema as the built-in definitions. Tilde (`~`) expansion is supported for all path settings:

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

## CLI Reference

| Command | Description |
|---------|-------------|
| `isx` | Launch the interactive TUI |
| `isx init` | One-time host setup (Incus, firewall, auth) |
| `isx build <template>` | Build or rebuild a template image |
| `isx build <tpl> --with-parents` | Rebuild a template and all its parents |
| `isx build --all` | Rebuild all discovered templates |
| `isx build --out-of-sync` | Rebuild out-of-sync templates |
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
| `isx vm start` | Start the VM (macOS only) |
| `isx vm stop` | Stop the VM (macOS only) |
| `isx vm status` | Show VM status and system diagnostics (macOS only) |
| `isx vm console` | Follow VM serial console output (macOS only) |
| `isx completion <shell>` | Print shell completion script (bash, zsh, fish) |

Use `isx <command> --help` for detailed options on any command.
