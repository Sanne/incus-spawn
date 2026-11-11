# Templates

A template is a reusable base environment defined in YAML.
You build it once with `isx build`, and every instance you branch from it starts with its packages, tools, repositories and settings in place.
This page is the reference for the template schema, declared repositories, shell defaults and host resources.

## Inheritance

Templates can inherit from each other.
Building an image builds any missing parents automatically:

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

## Built-in templates

Three images are built in: `tpl-minimal`, `tpl-dev` and `tpl-java`.
`tpl-dev` and `tpl-java` are deprecated and will be removed from isx: they still build, with a warning.
Define your own templates in their place.

To start from something, their maintained copies live in [`incus-spawn-templates`](https://github.com/Sanne/incus-spawn-templates) under the same names.
Add that checkout to `searchPaths`, or copy its `images/dev.yaml` and `images/java.yaml` into `~/.config/incus-spawn/images/`, and the copies replace the built-ins.
The definitions are the same, so nothing already built goes out of sync.

## Base image updates

The root image (`tpl-minimal`) uses a custom Fedora base from [`Sanne/incus-spawn-images`](https://github.com/Sanne/incus-spawn-images).
`isx update-base` checks for new base image releases, pins a specific version, or tracks the latest:

```shell
isx update-base              # interactive — shows versions, prompts for action
isx update-base --list       # list available versions
isx update-base --list --format=json  # the same for scripts (plain or json)
isx update-base --latest     # track the newest release (remove any pin)
isx update-base fedora-44-v2 # pin to a specific release tag
```

Tracking latest is the default and requires no action.
When the base image is unpinned, `isx build tpl-minimal` resolves the newest release at build time and installs it.
If isx cannot reach the release list, it falls back to the version baked into the binary.

Pinning writes the release to `~/.config/incus-spawn/base-image-pin.yaml`, which changes only the base image `tpl-minimal` downloads.
Everything else still comes from the built-in definition, or from your own `minimal.yaml`.
`--latest` removes that file to resume tracking.

A pin may name another Fedora release, to go back to the last Fedora 44 image for instance.
Its image name and file names then follow that release.
After changing the base image version, rebuild with `isx build tpl-minimal`.

An older isx pinned by replacing the whole built-in definition with `~/.config/incus-spawn/images/minimal.yaml`.
Running `isx update-base <tag>` or `--latest` removes that file, and keeps any `minimal.yaml` you wrote yourself.

## Adding your own

Add your own templates as YAML files in `~/.config/incus-spawn/images/` (user-level) or `.incus-spawn/images/` (project-local).
You can also point to external directories via `searchPaths` in `config.yaml` (see [Configuration](CONFIGURATION.md#configuration)).
isx ignores a key it does not know, such as `repo:` for `repos:`, with a warning that names where it is and the key it probably meant.

## Managing templates from the CLI

`isx templates` lists, creates and edits templates:

```shell
# List all available templates, with what each is for
isx templates list
isx templates list -v          # and where each definition comes from
isx templates list --format=json  # for scripts: adds whether each is built and out of sync

# Create a new template (opens in $EDITOR with a skeleton listing every field, commented out)
isx templates new my-app       # creates ~/.config/incus-spawn/images/my-app.yaml
isx templates new my-app --project  # creates .incus-spawn/images/my-app.yaml
isx templates new              # creates new-template.yaml, to be named in the editor

# Store a definition written elsewhere, with no editor: checked first, and nothing written if it is wrong
isx templates new tpl-agent --from tpl-agent.yaml
isx templates validate tpl-agent.yaml   # the same check alone; --format=json names each field at fault

# Edit an existing template
isx templates edit tpl-java    # opens in $EDITOR, validates on save
```

A template's name is used exactly as you give it, and its file is named after it.
The `tpl-` prefix of the built-in templates is a convention, which isx never adds or requires.

Editing a built-in template creates a user-level override in `~/.config/incus-spawn/images/`.
The override takes precedence over the built-in, but isx upgrades do not update it.
isx validates a template after editing: YAML syntax, required fields and parent references.

## Custom root images

A root image has no `parent`.
To define your own, set `image`, `image_url`, `image_tag` and `image_sha256` to point at your own pre-baked OS tarball.
See the built-in [`minimal.yaml`](../common/src/main/resources/images/minimal.yaml) and the [incus-spawn-images](https://github.com/Sanne/incus-spawn-images) repo for the reference example.

## Image schema fields

All fields are optional except `name`.
`isx templates new` writes them all into the new file, commented out.

| Field | What it does | Default |
|-------|--------------|---------|
| `image` | Base OS image, for root images only | `images:fedora/44` |
| `image_url` | Download URL for the base image tarball; supports `{arch}` and `{tag}` placeholders, and `file://` for testing a locally built image | |
| `image_tag` | Release tag identifying the base image version | |
| `image_sha256` | Per-architecture SHA256 checksums for integrity verification | |
| `type` | Instance type: `container`, `vm` or `kvm` (see [Instance types](#instance-types)) | `container`, or the parent's type |
| `vm_image_url` | Download URL for the VM base image (qcow2 tarball), used only when `type` is `vm`; supports `{arch}` and `{tag}` placeholders | |
| `vm_image_sha256` | Per-architecture SHA256 checksums for the VM base image | |
| `parent` | Parent image name; omit for root images | |
| `packages` | dnf packages to install | |
| `remove_packages` | dnf packages to remove before installing | |
| `package_repos` | Additional package repositories to enable before installing packages, each with `type: copr` and `name: owner/project`; copr is the only kind, and isx installs dnf's copr plugin first when the image lacks it | |
| `tools` | Tool names to run, resolved from YAML or Java (see [Custom Tools](TOOLS.md#custom-tools)) | |
| `repos` | Git repositories to clone as agentuser (see [Declarative Repos](#declarative-repos)) | |
| `skills` | Agent skills to bake into the image: a list, or an object with `repo` and `list` sub-fields (see [Agent Skills](AGENTS.md#agent-skills)) | |
| `agent_note` | An always-true fact an agent must know before acting (see [Agent Environment Context](AGENTS.md#agent-environment-context)) | |
| `host-resources` | Host files and directories to share with instances (see [Host Resources](#host-resources)) | |
| `mask_services` | systemd units to mask in the image | |
| `env` | Environment variables written to `/etc/profile.d/isx-env.sh` (see [Environment Variables](TOOLS.md#environment-variables)) | |
| `gui` | Enable GUI support for branches (see [GUI support](#gui-support)) | |
| `pinned` | Pin the base image to `image_tag` instead of tracking the newest release | |
| `workdir` | Default working directory when shelling into an instance (see [Shell Defaults](#shell-defaults)) | |
| `shell-command` | Command to run instead of the login shell (see [Shell Defaults](#shell-defaults)) | |
| `default-action` | Tool action to run when pressing Enter on an instance in the TUI (see [Shell Defaults](#shell-defaults)) | |
| `description` | Human-readable description for the TUI | |
| `accounts` | Credential account per namespace, such as `claude: work`, overriding the namespace's `default` (see [Credential accounts](CONFIGURATION.md#credential-accounts)) | |
| `deprecated` | `true`, or a note saying what to use instead (see [Deprecating a template](#deprecating-a-template)) | |

### Instance types

`type` selects what a template's instances are.
A child without `type` inherits its parent's type.

| Type | What it is |
|------|------------|
| `container` | A system container, the default. |
| `vm` | A virtual machine with a separate kernel, for hardware-level isolation. Linux only. |
| `kvm` | A container with the host's `/dev/kvm` passed through, so it can run VMs of its own. Linux only; `--no-kvm` skips it per branch. |

With the built-in base image, a VM's kernel is isx's own, which the firmware starts directly: no boot loader, no initrd, no loadable modules.
It is pinned: image rebuilds bring Fedora's userspace updates, not kernel updates, which arrive only when isx's kernel pin is bumped.

By default dnf in the VM excludes Fedora's kernel packages (`/etc/dnf/libdnf5.conf.d/isx-kernel.conf`).
Kernel work belongs in a nested VM inside a `--kvm` branch.

`vm` and `kvm` need Linux: macOS runs containers only, and a `kvm` template's branches go without KVM there.

### GUI support

With `gui: true`, container branches get GUI passthrough by default when isx runs in a Wayland session, unless the definition is project-local.
`isx branch --no-gui` and the TUI dialog opt out.

### Coordinator templates

With `coordinator: {control-over: [tpl-java]}`, every branch is a [coordinator](MCP.md#a-coordinator-in-an-instance) that drives isx over MCP.
`isx branch --no-mcp-client` and the TUI dialog opt out.
`coordinator.control-over` limits coordinators to these templates, of those `mcp.yaml` approves; without it they cannot control any template.
`isx-control-capability` beside the list says it explicitly; it is true by default exactly when the list is not empty.

A child template inherits its parent's `coordinator:` whole, unless it is project-local or its own `coordinator:` sets either key, which replaces the parent's.
It is never baked into the image, so changing it does not need a rebuild, and it is ignored in a project-local definition.

### Deprecating a template

`deprecated` takes `true`, or a note saying what to use instead.
The template still builds, but every build of it, or of a template naming it as `parent`, warns; templates further down the chain do not.
The TUI shows the warning in the template's details.

## Building templates

`isx build` builds a template and any parent it is missing:

```shell
# Build a specific image (builds missing parents automatically)
isx build tpl-java

# Build as a VM instead of a container (overrides the definition's type)
isx build tpl-java --type vm

# Rebuild a template and all its parents from scratch
isx build tpl-java --with-parents

# Rebuild two templates and all their parents, the parents they share once
isx build tpl-isx tpl-quarkus --with-parents

# Rebuild a template and every template that inherits from it
isx build tpl-java --with-descendants

# Rebuild out-of-sync templates (changed definitions, older isx version, or a parent rebuilt since)
isx build --out-of-sync

# Build only the templates that do not exist yet
isx build --missing

# Rebuild all discovered images from scratch
isx build --all
```

`--with-parents` and `--with-descendants` take one or more template names and rebuild each template once, parents first.
Both show the batch and ask before rebuilding; `--yes` skips the question.

The TUI marks a template that is out of sync, or that has no definition:

| Mark | Meaning |
|------|---------|
| `!` | Built with a different isx version. |
| `△` | The image or tool definition has changed since the last build. |
| `↑` | Its parent was rebuilt after it, so it was copied from the parent's earlier build. |
| `◌` | No definition of its name is loaded, such as after its `name:` was changed. |

A VM over a container parent is built from the definitions, never copied, so it is never `↑`.
`isx build --out-of-sync` rebuilds the first three, and everything that inherits from what it rebuilds.

A template marked `◌` can still be branched, but `isx build` refuses to rebuild it.
The error names the file it was built from and says why that file is not loaded.
After a rename, it names the template to build instead.
`isx doctor` lists every such template.

If a build fails, isx promotes the failed build to an inspectable instance named `<template>-failed-build`, so you can shell in and debug.
The previous build of the template, if any, stays in place.

## Declarative Repos

Templates can declare git repositories to clone into the instance.
A declared repository integrates better with other tools, such as Claude Code, than one fetched by shell commands.

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

| Field | What it does | Default |
|-------|--------------|---------|
| `url` | Git clone URL (HTTPS, for proxy compatibility); use either `url` or `host-source` | |
| `host-source` | An absolute host repository path or `~/...`, in trusted templates only; exports local branches and tags without contacting its remote (see [Host sources](#host-sources)) | |
| `path` | Target directory; `~` expands to agentuser's home | `~/<repo name>` |
| `branch` | Branch or tag to check out | the repo's default branch |
| `depth` | A positive number of commits to keep: the clone is shallow and follows only its branch | full history |
| `prime` | Shell command, or a list of them, to run inside the repo directory after cloning, typically to pre-fetch dependencies, such as `mvn dependency:go-offline` or `gradle dependencies` | |

A list runs its commands in turn, and the first that fails stops the rest and fails the build.
Each command runs in its own shell, so a variable one exports is gone in the next.
The build failure names the prime command that failed, and the failure report shows it above that command's output.

The build's log keeps every prime command that ran, and the output of each (`isx build --verbose` shows it).

`depth` is for repositories that store release binaries in their history.
Omit it for development repositories, which get the full history.
In the instance, `git fetch --unshallow && git remote set-branches origin '*' && git fetch` gets all of it.

isx pre-trusts declared repos in `.claude.json`, so Claude Code does not prompt for trust on first use.

### Runtimes a prime downloads

A prime's HTTPS calls go through the proxy, so its runtime must trust the isx CA.
A JDK or Python `certifi` from outside Fedora ships its own root store, which isx binds to the system store.
isx does this in the usual landing directories, such as `/opt` or mise's.
The [Fedora page](FEDORA.md#file-bindings) lists them.

A prime that downloads a JDK and uses it in the same command can run it before isx has bound it.
`mise x -- ./gradlew dependencies` is such a prime.
When a prime fails with `certificate_unknown`, `PKIX path building failed` or `CERTIFICATE_VERIFY_FAILED`, isx binds what landed and runs that command once more.

A runtime unpacked anywhere else stays on its own roots, and the second run fails the same way.
The build then asks you to bind it in the prime, before the command that uses it:

```yaml
prime: ./get-jdk.sh ~/tools/jdk && isx-trust ~/tools/jdk && ./gradlew dependencies
```

## Host sources

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
The instance receives all local branches and tags with their reachable history, including unpushed commits.
`branch` selects the initial branch or tag without removing other branches.
isx reads the host checkout without fetching or modifying it, and the instance's copy remains usable after the source is removed.

Stashes, reflog-only and unreachable objects, uncommitted files, source configuration and hooks are excluded unless their contents are reachable from an exported branch or tag.
The instance has no upstream remote: fetch or update the original repository on the host, then rebuild to export newer work.

Branching registers an `isx://` remote in the resolved host checkout, so `git fetch <instance-name>` retrieves the instance's work.
Destroying the instance removes that remote, and renaming it re-adds the remote under the new name.
No `repo-paths` entry or `host-resources` overlay is needed, on Linux or macOS.

Host sources require complete history: `depth` and shallow source repositories are refused.
Project-local `.incus-spawn/` templates cannot declare them.
An unexported detached host HEAD requires an explicit `branch` or tag.
A `url: file:///...` entry refers to a path inside the instance, not on the host.

## Shell Defaults

Templates can set the working directory, the shell command and the default action for connecting to an instance:

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

| Field | What it does | Default |
|-------|--------------|---------|
| `workdir` | The directory to `cd` into when opening a shell | the first repo of the nearest template that declares any |
| `shell-command` | A command to run instead of the default login shell, such as `claude` or `pi` | the login shell |
| `default-action` | A tool action to run when pressing Enter on an instance in the TUI, or on `isx run <instance>` from the CLI, or `-` for a shell | the parent's, else a shell |

`workdir` inherits from parent templates: the nearest one that sets it wins, even when a child adds repos of its own.
When no template in the chain sets it, it defaults to the first repo of the nearest template that declares any.

`shell-command` falls back to `bash --login` if it fails to start.

`default-action` is a tool name, such as `claude`, if the tool has a single action, or `tool:action-id`, such as `claude:launch`, if the tool has several (see [Tool Actions](TOOLS.md#tool-actions) for the `id` field).

When set, Enter runs the action and F2 opens a shell; when unset, Enter and `isx run` open a shell.
It inherits from parent templates, and a child overrides the parent's default action, or sets `-` to open a shell instead.
Changing this field needs no rebuild.

## Host Resources

Templates can declare host files and directories to make available inside instances.
Use this to share configuration files, pre-populate caches or provide large datasets without copying them into every template.

```yaml
name: tpl-my-java
parent: tpl-java
host-resources:
  - source: ~/.m2/repository
    mode: overlay
  - source: ~/.gitconfig
```

The `~/.m2/repository` entry shares your host Maven cache with the instance.
With `mode: overlay`, the instance sees a normal read-write directory pre-populated with your cached artifacts, but writes go to an instance-local layer: isx never modifies your host cache.
Maven builds that would download hundreds of megabytes of dependencies resolve them instantly from the shared cache instead.

The `~/.gitconfig` entry mounts your git configuration read-only, the default mode.
`git` inside the instance then picks up your name, email, aliases and other settings (see [Git Configuration](../README.md#git-configuration)).

### Modes

| Mode | Default? | Description |
|------|----------|-------------|
| `readonly` | Yes | Read-only bind mount. Simple, safe. |
| `overlay` | No | Read-only lower layer from the host plus an ephemeral writable upper layer in the instance. Tools see a normal read-write directory and the host is fully protected. Linux only: macOS does not support it. |
| `copy` | No | Copied into the instance at build time, so it becomes part of the template. Also supports URL sources. |

`readonly` and `overlay` resources cannot be mounted at `/` or into a system directory: `/bin`, `/boot`, `/dev`, `/etc`, `/lib`, `/lib64`, `/proc`, `/run`, `/sbin`, `/sys`, `/tmp`, `/usr` or `/var`.
Use `/home/agentuser`, `/opt`, `/srv` or `/mnt` instead, or `mode: copy`.

### Entry fields

| Field | What it does | Default |
|-------|--------------|---------|
| `source` | The host file or directory to share | |
| `path` | Where it appears inside the instance; `~` and a relative path are under `/home/agentuser`, and a URL source needs one | the source path, with `~` as `/home/agentuser` |
| `mode` | `readonly`, `overlay` or `copy` | `readonly` |

isx ignores any other key, such as `target:`, with a warning.
isx skips a missing host path with a warning, so templates remain portable.
Host resources compose across the parent chain, with child entries overriding parent entries matched by instance path.

### Project-local templates

Project-local templates (`.incus-spawn/images/`) come from whatever repository you cloned, so they cannot reach outside it.
Their host resources and `file://` images must point inside the project directory, and isx always clones their `repos` from the network, never from your host checkouts.
To give a template you trust wider access, move it to `~/.config/incus-spawn/images/`.

### VMs

VMs mount host resources via virtiofs.
Single-file resources fall back to `copy` mode, since VMs only mount directories.
