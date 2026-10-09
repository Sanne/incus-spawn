# Templates

Template images are reusable base environments defined in YAML. They can inherit from each other -- building an image automatically builds any missing parents:

```yaml
# images/java.yaml
name: tpl-java
description: JDK + Maven
parent: tpl-dev
packages:
  - java-25-openjdk-devel
  - java-25-openjdk-javadoc
  - java-25-openjdk-src
tools:
  - maven-3
```

Three images are built-in (`tpl-minimal`, `tpl-dev`, `tpl-java`). `tpl-dev` and `tpl-java` are deprecated and will be removed from isx: they still build, with a warning. Define your own templates in their place; to start from something, their maintained copies live in [`incus-spawn-templates`](https://github.com/Sanne/incus-spawn-templates) under the same names. Add that checkout to `searchPaths` (or copy its `images/dev.yaml` and `images/java.yaml` into `~/.config/incus-spawn/images/`) and the copies replace the built-ins; the definitions are the same, so nothing already built goes out of sync. The root image (`tpl-minimal`) uses a custom Fedora base from [`Sanne/incus-spawn-images`](https://github.com/Sanne/incus-spawn-images). Use `isx update-base` to check for new base image releases, pin a specific version, or track the latest:

```shell
isx update-base              # interactive — shows versions, prompts for action
isx update-base --list       # list available versions
isx update-base --latest     # track the newest release (remove any pin)
isx update-base fedora-44-v2 # pin to a specific release tag
```

Tracking latest is the default and requires no action: when the base image is unpinned, `isx build tpl-minimal` resolves the newest release at build time and installs it (falling back to the version baked into the binary if it can't reach the release list). Pinning writes a user-level override to `~/.config/incus-spawn/images/minimal.yaml`; `--latest` removes that override to resume tracking. After changing the base image version, rebuild with `isx build tpl-minimal`.

Add your own templates by placing YAML files in `~/.config/incus-spawn/images/` (user-level) or `.incus-spawn/images/` (project-local). You can also point to external directories via `searchPaths` in `config.yaml` (see [Configuration](CONFIGURATION.md#configuration)).

Use `isx templates` to manage templates from the CLI:

```shell
# List all available templates
isx templates list
isx templates list -v          # with source path and description

# Create a new template (opens in $EDITOR with a skeleton listing every field, commented out)
isx templates new my-app       # creates ~/.config/incus-spawn/images/my-app.yaml
isx templates new my-app --project  # creates .incus-spawn/images/my-app.yaml

# Edit an existing template
isx templates edit tpl-java    # opens in $EDITOR, validates on save
```

Editing a built-in template automatically creates a user-level override in `~/.config/incus-spawn/images/`. The override takes precedence over the built-in but will not auto-update with isx upgrades. Templates are validated after editing: YAML syntax, required fields, and parent references are checked.

You can also define a custom root image (no `parent`) by specifying `image`, `image_url`, `image_tag`, and `image_sha256` to point at your own pre-baked OS tarball. See the built-in [`minimal.yaml`](../common/src/main/resources/images/minimal.yaml) and the [incus-spawn-images](https://github.com/Sanne/incus-spawn-images) repo for the reference example.

Image schema fields (all optional except `name`; `isx templates new` writes them all into the new file, commented out):
- `image` -- base OS image, only for root images (default: `images:fedora/44`)
- `image_url` -- download URL for the base image tarball (supports `{arch}` and `{tag}` placeholders; `file://` works for testing a locally built image)
- `image_tag` -- release tag identifying the base image version
- `image_sha256` -- per-architecture SHA256 checksums for integrity verification
- `type` -- instance type: `container` (default), `vm`, or `kvm`. VMs use a separate kernel for hardware-level isolation. With the built-in base image that is isx's own kernel, which the firmware starts directly (no boot loader, no initrd, no loadable modules). It is pinned: image rebuilds bring Fedora's userspace updates, not kernel updates, which arrive only when isx's kernel pin is bumped. By default dnf in the VM excludes Fedora's kernel packages (`/etc/dnf/libdnf5.conf.d/isx-kernel.conf`); kernel work belongs in a nested VM inside a `--kvm` branch. `kvm` is a container with the host's `/dev/kvm` passed through, so it can run VMs of its own (skip it per branch with `--no-kvm`). `vm` and `kvm` need Linux: macOS runs containers only, and a `kvm` template's branches go without KVM there. Inherits from parent -- a child without `type` inherits its parent's type
- `vm_image_url` -- download URL for the VM base image (qcow2 tarball). Only used when `type` is `vm`. Supports `{arch}` and `{tag}` placeholders
- `vm_image_sha256` -- per-architecture SHA256 checksums for the VM base image
- `parent` -- parent image name (omit for root images)
- `packages` -- dnf packages to install
- `remove_packages` -- dnf packages to remove before installing
- `package_repos` -- additional package repositories to enable (e.g. COPR)
- `tools` -- tool names to run (resolved from YAML or Java, see [Custom Tools](TOOLS.md#custom-tools))
- `repos` -- git repositories to clone as agentuser (see below)
- `skills` -- agent skills to bake into the image (see below); accepts a list shorthand or an object with `repo` and `list` sub-fields
- `agent_note` -- always-true fact an agent must know before acting (see [Agent Environment Context](AGENTS.md#agent-environment-context))
- `host-resources` -- host files/directories to share with containers (see below)
- `mask_services` -- systemd units to mask in the image
- `env` -- environment variables written to `/etc/profile.d/isx-env.sh` (see [Environment Variables](TOOLS.md#environment-variables))
- `gui` -- enable GUI support: container branches get GUI passthrough by default when isx runs in a Wayland session, unless the definition is project-local (`isx branch --no-gui` and the TUI dialog opt out)
- `pinned` -- pin the base image to `image_tag` instead of tracking the newest release
- `workdir` -- default working directory when shelling into a container (see below)
- `shell-command` -- command to run instead of the login shell (see below)
- `default-action` -- tool action to run when pressing Enter on an instance in the TUI (see below)
- `description` -- human-readable description for the TUI
- `accounts` -- credential account per namespace (e.g. `claude: work`), overriding the namespace's `default` (see [Credential accounts](CONFIGURATION.md#credential-accounts))
- `deprecated` -- `true`, or a note saying what to use instead: the template still builds, but every build of it or of a template naming it as `parent` warns (templates further down do not), and the TUI shows the warning in the template's details

```shell
# Build a specific image (builds missing parents automatically)
isx build tpl-java

# Build as a VM instead of a container (overrides the definition's type)
isx build tpl-java --type vm

# Rebuild a template and all its parents from scratch
isx build tpl-java --with-parents

# Rebuild two templates and all their parents, the parents they share once
isx build tpl-isx tpl-quarkus --with-parents

# Rebuild out-of-sync templates (changed definitions, older isx version, or a parent rebuilt since)
isx build --out-of-sync

# Rebuild all discovered images from scratch
isx build --all
```

The TUI marks templates with `!` when they were built with a different isx version, `△` when the image or tool definition has changed since the last build, and `↑` when their parent was rebuilt after them (so they were copied from its earlier build; a VM over a container parent is built from the definitions, never copied, so it is never `↑`) — `isx build --out-of-sync` rebuilds all three, and everything that inherits from what it rebuilds. If a build fails, the container is promoted to an inspectable instance so you can shell in and debug.

## Declarative Repos

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
- `url` -- git clone URL (HTTPS, for proxy compatibility), or a confined host-only local path or `file:` URL; use either `url` or `host-source`
- `host-source` -- an absolute host repository path or `~/...`, available in trusted templates; exports local branches and tags without contacting its remote
- `path` (optional) -- target directory (`~` expands to agentuser's home); defaults to `~/<repo name>`
- `branch` (optional) -- branch or tag to check out; defaults to the repo's default branch
- `depth` (optional) -- a positive number of commits to keep: the clone is shallow and follows only its branch, for repositories that store release binaries in their history. Omit it for development repositories, which get the full history. In the instance, `git fetch --unshallow && git remote set-branches origin '*' && git fetch` gets all of it.
- `prime` (optional) -- shell command to run inside the repo directory after cloning, typically to pre-fetch dependencies (e.g. `mvn dependency:go-offline`, `gradle dependencies`)

Declared repos are automatically pre-trusted in `.claude.json` so Claude Code doesn't prompt for trust on first use.

For a repository accessible only on the host, declare its source directly:

```yaml
name: tpl-private
parent: tpl-java
repos:
  - host-source: ~/work/private-repo
    path: ~/private-repo
    branch: my-feature
```

Build and branch this template as usual.
The guest receives all local branches and tags with their reachable history, including unpushed commits; `branch` selects the initial branch or tag without removing other branches.
Stashes, reflog-only and unreachable objects, uncommitted files, source configuration and hooks are excluded unless their contents are reachable from an exported branch or tag.
The host checkout is read without fetching or modifying it, and the guest copy remains usable after the source is removed.
The guest has no upstream remote; fetch or update the original repository on the host, then rebuild to export newer work.
Branching registers an `isx://` remote in the resolved host checkout, so `git fetch <instance-name>` retrieves the instance's work; destroying or renaming the instance cleans up that remote.
No `repo-paths` entry or `host-resources` overlay is needed, on Linux or macOS.
Host sources require complete history (`depth` and shallow source repositories are refused), and project-local `.incus-spawn/` templates cannot declare them.
An unexported detached host HEAD requires an explicit `branch` or tag.


### Host-only local URLs

To build from committed work on your host, including commits you have not pushed:

```yaml
repos:
  # For a template in .incus-spawn/images/, clone the enclosing project:
  - url: ../..
    path: ~/my-project
```

Local sources accept a working copy, its `.git` directory, or a bare repository, independent of `host-paths` and `repo-paths`:

| Relative to | Accepted forms |
| --- | --- |
| Host filesystem root | `/path/to/repo`, `file:/path/to/repo`, `file:///path/to/repo` |
| Host user's home | `~/repo`, `file:~/repo`, `file://~/repo` |
| Directory containing the declaring template | `repo`, `dir/repo`, `./dir/repo`, `file:repo`, `file:dir/repo`, `file:./dir/repo`, `file://repo`, `file://./dir/repo` |

Relative paths resolve against the directory containing the declaring template. All local forms (including absolute paths, `~/`, and `file:` URLs) must stay within the template's allowed root after normalization and symlink resolution. For templates loaded from `<project>/.incus-spawn/images`, the root is `<project>`, the directory containing `.incus-spawn`. For example, `/project/.incus-spawn/images/app.yaml` may use `/project` via `../..`, but `../../..` escapes that root. For templates loaded from a configured `searchPaths` entry, the root is that search path. For templates in `~/.config/incus-spawn/images`, it is `~/.config/incus-spawn`. Parent components (`..`) are allowed within the root. Escapes through symlinks, including a symlinked `.git`, are rejected with the failed rule and allowed root. Stored build definitions retain their original roots; a stored definition without a recorded root must be reloaded before building host sources. `file://repo` is a single-name shorthand; `file://host/path` is rejected, including `localhost`. For nested relative paths use `file:dir/repo` or `file://./dir/repo`. URL-encode spaces and reserved characters in `file:` URLs; plain paths use literal characters.

During **build**, isx mounts only the Git directory read-only, copies its objects and refs without hardlinks, and creates a clean working tree at the host's HEAD. The current branch is preserved; a detached HEAD stays detached, and an empty repository keeps its unborn branch. Staged changes, unstaged edits, untracked files, host hooks, and unrelated host Git settings are not copied.

The host's repository remote configuration (including push URLs and refspecs) and branch tracking settings are recreated. URL userinfo is removed before copying (for example, `https://user:token@example.com/repo` becomes `https://example.com/repo`), including fetch, push and proxy URLs. Other URL components are retained; using them later requires the corresponding container-side connectivity and authentication. Preparation performs no remote fetch, does not refresh the host repository, and never falls back to a remote clone. Missing or unsupported sources fail the build. `branch:` and `depth:` are not allowed with a local source: the host's HEAD selects the checkout and the copy includes full history.

Sources must have a standalone Git directory with complete local objects. Linked worktrees, shallow/partial repositories, and repositories borrowing objects through alternates are unsupported. Submodules are not initialized and Git LFS content is not downloaded. A `prime` command still runs normally and can perform any network operations you explicitly put in it.

`isx branch` inherits the built repository; it does not reread the host. Changes to host HEAD, refs, or remote/branch configuration mark the template outdated. Rebuild to capture them. Automatic template/project updates skip fetching these repositories; ordinary Git commands remain available inside the container. Host remote integration also uses the explicit source path, without requiring configured search paths.

## Shell Defaults

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

- `workdir` -- the directory to `cd` into when opening a shell. Inherits from parent templates (the nearest one that sets it wins), even when a child adds repos of its own; when no template in the chain sets it, defaults to the first repo of the nearest template that declares any.
- `shell-command` -- a command to run instead of the default login shell (e.g. `claude` or `pi`). Falls back to `bash --login` if it fails to start.
- `default-action` -- a tool action to run when pressing Enter on an instance in the TUI or when running `isx run <instance>` from the CLI. The value is a tool name (e.g. `claude`) if the tool has a single action, or `tool:action-id` (e.g. `claude:launch`) if the tool has multiple actions (see [Tool Actions](TOOLS.md#tool-actions) for the `id` field). When set, Enter runs the action and F2 opens a shell; when unset, Enter/`isx run` opens a shell. Inherits from parent templates; a child overrides the parent's default action. No rebuild required when changing this field.

## Host Resources

Template images can declare host files and directories to make available inside containers. This is useful for sharing configuration files, pre-populating caches, or providing large datasets without copying them into every template.

```yaml
name: tpl-my-java
parent: tpl-java
host-resources:
  - source: ~/.m2/repository
    mode: overlay
  - source: ~/.gitconfig
```

The `~/.m2/repository` entry shares your host Maven cache with the container. With `mode: overlay`, the container sees a normal read-write directory pre-populated with your cached artifacts, but writes go to a container-local layer -- your host cache is never modified. Maven builds that would normally download hundreds of megabytes of dependencies can instead resolve them instantly from the shared cache.

The `~/.gitconfig` entry mounts your git configuration read-only (the default mode), so `git` inside the container picks up your name, email, aliases, and other settings (see [Git Configuration](../README.md#git-configuration)).

Three modes are available:

| Mode | Default? | Description |
|------|----------|-------------|
| `readonly` | Yes | Read-only bind mount. Simple, safe. |
| `overlay` | No | Read-only lower layer from host + ephemeral writable upper in the container. Tools see a normal read-write directory. Host is fully protected. **Linux only** — not yet supported on macOS. |
| `copy` | No | Copied into the container at build time. Becomes part of the template. Also supports URL sources. |

`readonly` and `overlay` resources can't be mounted into system directories such as `/`, `/etc`, `/usr`, `/var` or `/tmp`. Use `/home/agentuser`, `/opt`, `/srv` or `/mnt` instead, or `mode: copy`.

If `path` is omitted, it defaults to the same relative path under `/home/agentuser/`. Missing host paths are skipped with a warning, so templates remain portable. Host resources compose across the parent chain, with child entries overriding parent entries matched by container path.

**Project-local templates** (`.incus-spawn/images/`) come from whatever repository you cloned, so they can't reach outside it: host resources and `file://` images must point inside the project directory, and their remote `repos` are cloned from the network.
Explicit host-only Git sources must stay inside the project directory containing `.incus-spawn`. To give a template you trust wider access, move it to `~/.config/incus-spawn/images/`.

**VMs** mount host resources via virtiofs. Single-file resources fall back to `copy` mode, since VMs only mount directories.
