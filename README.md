# incus-spawn

Spin up isolated Linux environments in seconds — full system containers with copy-on-write branching and transparent credential isolation. Run AI coding agents, triage untrusted patches, reproduce bug reports — without risking your host.

**Docker and Podman are built for shipping applications** — minimal filesystems, single-process isolation, fast startup. incus-spawn solves a different problem: full **system containers** powered by [Incus](https://linuxcontainers.org/incus/) that behave like real machines. Each environment runs its own init system, has real networking (`ping`, `strace`, nested Podman/Docker), and supports GUI and audio passthrough (Linux only). Templates pre-install your baseline tools and repos, but the environment is a real Linux system — agents and users can freely `dnf install`, `pip install`, build from source, or run Docker Compose just like on a workstation. For untrusted code, KVM virtual machines provide hardware-level isolation with a separate kernel.

**API keys and tokens never enter containers.** A host-side MITM TLS proxy intercepts HTTPS and injects credentials transparently — `claude`, `pi`, `gh`, `git`, `curl`, and any other tool work unmodified, with no configuration or wrappers needed inside the environment. Branches run with full internet, proxy-only egress, or completely airgapped. The proxy also caches container image layers and build artifacts on the host — the same dependency is never downloaded twice.

**Branching is instant.** Like `git branch`, each clone is a copy-on-write snapshot that shares storage with its parent. Build a template once with your preferred tools and repos, then spin up complete, disposable environments in seconds — each with its own filesystem, networking, and process tree. Get your work back via standard `git fetch` using `isx://` remotes.

**Built for developer workflows.** Templates are YAML: packages, tools, repos. Branch and it's all there. Git remotes are managed automatically — `git fetch fix-auth` from your host pulls commits straight out of the container. VS Code Remote, JetBrains Gateway, shell completions, and Claude Code skills plug in via the same tool system.

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

- The proxy uses bridge-level DNS overrides and a custom CA certificate so containers transparently route intercepted domains through the proxy
- The proxy terminates TLS, injects real authentication headers, and forwards to the real upstream over TLS — tools (`curl`, `git`, `gh`, `claude`, `pi`) work unmodified inside containers
- **Vertex AI support**: the proxy transparently translates requests to Vertex AI format — no GCP credentials enter the container
- **Claude Pro/Max support**: authenticate via `claude setup-token`; the proxy injects the OAuth Bearer token transparently
- **HTTPS only**: Git operations must use HTTPS URLs (not SSH). `gh` defaults to HTTPS; for `git clone`, use `https://github.com/...`

The proxy must be running for non-airgapped containers. `isx init` can install it as a systemd user service, or run `isx proxy` in a separate terminal. The CLI verifies proxy reachability and version compatibility before builds, branches, and shell access.

### Network Modes

Each branch runs in one of three network modes:

| Mode | Flag | Description |
|------|------|-------------|
| **Full internet** | *(default)* | Unrestricted network access via NAT, auth via MITM proxy |
| **Proxy only** | `--proxy-only` | Outbound traffic restricted to MITM proxy only (iptables) |
| **Airgapped** | `--airgap` | Network device removed, complete isolation |

### Git Remotes

Containers created with `isx branch` are isolated environments, but you need a way to get your changes back. incus-spawn integrates with git's native remote helper protocol so you can use standard `git fetch`, `git push`, and `git pull` between host repos and container repos:

```shell
# Inside the container, you make some commits...
# Back on the host:
git fetch fix-auth
git cherry-pick fix-auth/main
```

#### isx:// URLs

The remote uses the `isx://` URL scheme (`~` expands to `/home/agentuser`):

```shell
git remote add fix-auth isx://fix-auth/~/quarkus
git fetch fix-auth
git diff main..fix-auth/main
```

The instance must be running for git operations to work.

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

With this configuration, `isx branch` adds a git remote named after the instance in each matching host repo (protocol-lenient — SSH and HTTPS URLs for the same repo are treated as equal), and `isx destroy` removes it.

## Caching

The proxy and build system cache artifacts on the host, shared across all templates and branches. Only immutable, content-addressed artifacts are cached — mutable data (Maven SNAPSHOTs, repository metadata, version listings) always passes through uncached. Every artifact is verified against its content digest or upstream checksum before being committed to the cache; mismatches are discarded and re-fetched.

Proxy caches (from container traffic):

- **Container image layers** — OCI blobs from Docker Hub, GHCR, and Quay, keyed by SHA256 content digest
- **Maven and Gradle artifacts** — release JARs, POMs, and plugins from Maven Central and the Gradle plugin portal
- **Gradle distributions** — verified against the upstream `.sha256` sidecar

Build-time caches:

- **DNF packages** — host-side cache mounted during builds so child images reuse parent downloads
- **Tool downloads** — cached on the host by SHA256; rebuilds reuse unchanged artifacts

All caches live under `~/.cache/incus-spawn/`. There is no automatic eviction — every entry is content-addressed or version-pinned, so it is either correct forever or superseded by a newer version with its own entry.

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

Three images are built-in (`tpl-minimal`, `tpl-dev`, `tpl-java`). The root image (`tpl-minimal`) uses a custom Fedora base from [`Sanne/incus-spawn-images`](https://github.com/Sanne/incus-spawn-images). Use `isx update-base` to check for new base image releases, pin a specific version, or track the latest:

```shell
isx update-base              # interactive — shows versions, prompts for action
isx update-base --list       # list available versions
isx update-base --latest     # always track the newest version
isx update-base fedora-44-v2 # pin to a specific release tag
```

Pinning writes a user-level override to `~/.config/incus-spawn/images/minimal.yaml`. Tracking latest (the default) uses the built-in definition, which is updated with each isx release. After changing the base image version, rebuild with `isx build tpl-minimal`.

Add your own templates by placing YAML files in `~/.config/incus-spawn/images/` (user-level) or `.incus-spawn/images/` (project-local). You can also point to external directories via `searchPaths` in `config.yaml` (see [Configuration](#configuration)).

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

You can also define a custom root image (no `parent`) by specifying `image`, `image_url`, `image_tag`, and `image_sha256` to point at your own pre-baked OS tarball. See the built-in [`minimal.yaml`](src/main/resources/images/minimal.yaml) and the [incus-spawn-images](https://github.com/Sanne/incus-spawn-images) repo for the reference example.

Image schema fields (all optional except `name`):
- `image` -- base OS image, only for root images (default: `images:fedora/44`)
- `image_url` -- download URL for the base image tarball (supports `{arch}` and `{tag}` placeholders)
- `image_tag` -- release tag identifying the base image version
- `image_sha256` -- per-architecture SHA256 checksums for integrity verification
- `parent` -- parent image name (omit for root images)
- `packages` -- dnf packages to install
- `tools` -- tool names to run (resolved from YAML or Java, see [Custom Tools](#custom-tools))
- `repos` -- git repositories to clone as agentuser (see below)
- `skills` -- Claude Code skills to bake into the image (see below); accepts a list shorthand or an object with `repo` and `list` sub-fields
- `host-resources` -- host files/directories to share with containers (see below)
- `workdir` -- default working directory when shelling into a container (see below)
- `shell-command` -- command to run instead of the login shell (see below)
- `default-action` -- tool action to run when pressing Enter on an instance in the TUI (see below)
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

Templates can configure the default working directory, shell command, and default action when connecting to a container:

```yaml
name: tpl-quarkus
parent: tpl-java
tools: [claude]
repos:
  - url: https://github.com/quarkusio/quarkus.git
    path: ~/quarkus
workdir: ~/quarkus
default-action: claude
```

- `workdir` -- the directory to `cd` into when opening a shell. Defaults to the first declared repo's path if omitted.
- `shell-command` -- a command to run instead of the default login shell (e.g. `claude` or `pi`). Falls back to `bash --login` if it fails to start.
- `default-action` -- a tool action to run when pressing Enter on an instance in the TUI. The value is a tool name (e.g. `claude`) if the tool has a single action, or `tool:action-id` (e.g. `claude:launch`) if the tool has multiple actions (see [Tool Actions](#tool-actions) for the `id` field). When set, Enter runs the action and F2 opens a shell; when unset, Enter opens a shell. Inherits from parent templates; a child overrides the parent's default action. No rebuild required when changing this field.

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

Pi works out of the box with all three auth modes (API key, Claude Pro/Max OAuth, Vertex AI) — the [MITM proxy](#credential-isolation) injects credentials transparently. To use Pi without making it the default shell, omit `shell-command` and launch it manually after `isx shell`.

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

Skill source formats:
- `owner/repo@skill-name` -- specific skill from a GitHub repo
- `owner/repo` -- all skills from a GitHub repo
- `./local-path` -- local directory (relative to where `isx build` is run)
- `skill-name` -- bare name, resolved using the `skills.repo` field (required for bare names)

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

If `path` is omitted, it defaults to the same relative path under `/home/agentuser/`. Missing host paths are skipped with a warning, so templates remain portable. Host resources compose across the parent chain, with child entries overriding parent entries matched by container path.

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

Execution order during `install()`: packages → downloads → `run` → `run_as_user` → `files` → `env` → `verify`. Resolution follows the same order as templates (see [Configuration](#configuration)).

### Remote IDE Access

Both VS Code and JetBrains IntelliJ can connect to containers with their UI running natively on the host and all backend processing (indexing, builds, terminals, extensions) running inside the container. SSH keys are managed automatically: `isx init` generates a dedicated passphraseless key pair at `~/.config/incus-spawn/ssh/`, and each branch injects it into the container along with your personal `~/.ssh` key. Container host keys are pre-validated so `ssh <instance-name>` just works — no passphrase prompt, no host key warning. Entries are cleaned up when instances are destroyed.

Both tools declare TUI actions — press **F9** on a running instance to open a repo directly in your IDE.

#### VS Code (Remote - SSH)

The built-in `vscode-remote` tool provides one-click "Open in VS Code" actions. It declares `requires: [sshd]`, so the SSH server is installed automatically. No backend is pre-installed inside the container — VS Code downloads its own server component on first connect.

**Host prerequisite**: install the [Remote - SSH](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-ssh) extension in VS Code.

```yaml
name: tpl-java-vscode
parent: tpl-java
tools:
  - vscode-remote    # auto-installs sshd via requires
```

#### JetBrains IntelliJ (Gateway)

The built-in `idea-backend` tool installs the JetBrains IntelliJ IDEA remote development backend inside the container. It declares `requires: [sshd]`, so the SSH server is installed automatically.

**Host prerequisite**: install [JetBrains Gateway](https://www.jetbrains.com/remote-development/gateway/).

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

### Tool Parameters

Tools can define parameters for build-time configuration. Parameter types: `string` (with optional `pattern`), `integer` (with `min`/`max`), `boolean`, and `enum` (with `options`). Use `${param_name}` to reference values in scripts, env, and file content:

```yaml
# tools/my-server.yaml
name: my-server
parameters:
  memory:
    type: string
    default: "2g"
    pattern: "^[0-9]+[gGmM]$"
env:
  - export SERVER_MEMORY=${param_memory}
```

Pass parameter values using the map form in image definitions (the `idea-backend` memory example above shows this pattern).

### Tool Actions

Tools can declare runtime actions that appear in the TUI (press **F9** on a running instance, or **Enter** to run the template's default action). Actions can be declared in YAML tool definitions or programmatically by Java/CDI tools. The built-in `claude` and `pi` tools automatically contribute shell actions ("Claude Code" and "Pi Coding Agent") when included in a template's `tools` list.

Action entry fields:

- `label` (required) -- display text shown in the F9 menu; supports template variables
- `type` (required) -- one of: `url`, `command`, `shell`, `copy-to-clipboard`
- `id` -- stable identifier for referencing from `default-action: tool:action-id`
- `requires_running` -- whether the instance must be running (default: `true`)
- `expand` -- set to `repos` to generate one action per declared repository
- `auto_return` -- return to TUI automatically after the action completes (default: `false`; only meaningful for `command` and `shell`)

Type-specific fields:

- **`url`**: `url` -- URL to open in the host browser
- **`command`**: `command` -- shell command to run on the host
- **`shell`**: `command` -- command to run inside the container as an interactive terminal session
- **`copy-to-clipboard`**: `text` -- text to copy to the host clipboard

Template variables available in `label`, `url`, `command`, and `text`: `${ip}`, `${name}`, `${parent}`. When `expand: repos` is set, repo-specific variables are also available: `${repo_name}`, `${repo_path}`, `${repo_url}`.

```yaml
actions:
  - label: "Open repo '${repo_name}' in Gateway"
    type: url
    expand: repos
    url: "jetbrains-gateway://connect#host=${ip}&projectPath=${repo_path}"
  - label: "Launch agent"
    id: launch
    type: shell
    command: "my-agent --continue"
    auto_return: true
```

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

## FAQ

### Why can't I mount a host directory read-write to follow agent work in my IDE?

A project directory is not just data — it is an implicit code execution channel. Build tools, package managers, and IDEs all trust its contents and execute them with your full host privileges. A read-write mount turns the agent's output into unreviewed host-side code execution, which is exactly the threat model incus-spawn exists to prevent.

Two attack surfaces make this dangerous even for "just the project directory":

1. **Executable project content.** Build plugins, Makefiles, `gradlew`, `.mvn/jvm.config`, git hooks, IDE run configurations, and dependency declarations with local path references (`<systemPath>`, `file:` deps, Go `replace` directives) all execute when you run a normal build command. An agent that modifies a Maven build plugin or a git pre-commit hook gets code execution on your host with your credentials and network access — without you ever intentionally "running the agent's code."

2. **IDE auto-execution.** VS Code, IntelliJ, and most editors auto-execute project configuration the moment you open a directory: `.vscode/settings.json` (task auto-run), `.idea/` workspace files, ESLint/TypeScript/Pyright configs that load plugins. An agent writing to the project directory can trigger code execution on your host just by the folder being open — no build command required.

These risks are compounded by a **race condition**: with a live read-write mount, files can change between review and execution. You inspect a git hook or build script, decide it's safe, and run your build — but the agent modified the file between your review and your command. Unlike `git fetch`, which gives you a specific immutable commit to review and act on, a live mount means your review is never final.

Beyond security, a shared project directory is also **misleading**. The agent's code runs against the container's execution context — container-local SNAPSHOT dependencies, container-local `node_modules`, container-local pip packages. None of that comes through the mount. The source code on the host *looks* like a complete project, but when you build it locally it may behave differently or break entirely because the dependency state is invisible. The project directory is only a partial view of the agent's environment.

**What to use instead:**

- **`isx://` git remotes** ([Git Remotes](#git-remotes)): `git fetch <instance>` pulls a consistent, atomic snapshot — a specific commit you can review with `git diff` before merging. Even if the agent pushes new commits between your review and your merge, you act on the exact commit you reviewed. Git's content-addressed model eliminates torn reads by design. This is the intended workflow for getting work out of containers.
- **VS Code Remote SSH / JetBrains Gateway** ([Remote IDE Access](#remote-ide-access)): the IDE backend runs inside the container while the UI runs on your host. You see live edits, have full debugging, and the security boundary stays intact. Both are built-in tools (`vscode-remote`, `idea-backend`).
- **`readonly` and `overlay` host-resources** ([Host Resources](#host-resources)): for sharing files *into* the container safely. `readonly` is a read-only bind mount; `overlay` gives the container a writable view backed by an ephemeral layer, without modifying the host.

## CLI Reference

| Command | Description |
|---------|-------------|
| `isx` | Launch the interactive TUI |
| `isx init` | One-time host setup (Incus, firewall, auth) |
| `isx build <template>` | Build or rebuild a template (`--all`, `--missing`, `--out-of-sync`, `--with-parents`) |
| `isx branch <name>` | Create a CoW clone from a template or instance |
| `isx shell <instance>` | Open a shell in an instance |
| `isx destroy <instance>` | Destroy an instance |
| `isx update-base` | Check for and install base image updates (`--list`, `--latest`, or a tag) |
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
