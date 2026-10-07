# isx

**Give your AI coding agents their own machines — not your credentials.**

You're about to hand an AI agent a terminal. On your laptop, that terminal can read your API keys, your GitHub token, your `~/.ssh`, and every repo you have checked out — and anything it writes, your IDE and build tools will happily execute.

isx onboards agents the way you'd onboard a new teammate:

- **A real machine of their own.** Each agent gets a full Linux workstation — its own filesystem, init system, networking, and process tree. It can `dnf install`, run Docker Compose, use `strace` and nested containers — everything works, because it *is* a real system, not an app container. Hardware-isolated KVM virtual machines are one flag away for untrusted code.
- **Zero credential exposure.** API keys and tokens never enter the environment in any form. A host-side TLS proxy injects real credentials upstream, so `claude`, `pi`, `gh`, `git`, and `curl` work unmodified inside — yet no credential is ever stored there. See [Credential Isolation](#credential-isolation).
- **Disposable in seconds.** Branch a prepared template like you'd branch a repo — instant copy-on-write clones. Use them, throw them away, branch again from a clean state.
- **Full autonomy, no babysitting.** Agents commit under their own identity and run without permission prompts — safe to let run, because the blast radius is the branch.

Runs on Linux and macOS, on your hardware. Your code and credentials never leave the building. Everything an agent does can be traced back to it — and nothing it does arrives uninvited.

Agents are the headline, not the limit: the same disposable machines are ideal for triaging untrusted patches, reproducing bug reports, and testing on a clean system — anything you'd rather not run on your host.

Built with [Quarkus](https://quarkus.io/) and [Tamboui](https://tamboui.dev/), powered by [Incus](https://linuxcontainers.org/incus/) system containers. Written in Java, compiled to native binaries with [GraalVM](https://www.graalvm.org/). *(isx was formerly known as incus-spawn.)*

## Quick Start

Requires **Linux, or macOS 15 (Sequoia) or later**; older macOS releases are not supported. On macOS, Apple Silicon is supported; Intel Macs get a binary built for the same macOS 15 minimum on a best-effort basis: it is released, but not verified on an Intel Mac. On Linux, [Incus](https://linuxcontainers.org/incus/) runs natively and `isx init` auto-installs it via your package manager. On macOS, `isx init` provisions a lightweight Linux VM automatically via [vfkit](https://github.com/crc-org/vfkit). The VM starts automatically when needed and can be managed with `isx vm start|stop|restart|status|resize|reset`. Windows is not supported.

**macOS limitations**: GUI/audio passthrough (Wayland + PipeWire) and `overlay` mode for host-resources are Linux-only features. On macOS, use `readonly` or `copy` modes for host-resources instead.

<!-- tabs:os -->

#### macOS

```shell
brew install Sanne/tap/incus-spawn
```

#### Fedora / RHEL

```shell
sudo dnf copr enable sanne/incus-spawn
sudo rpm --import https://download.copr.fedorainfracloud.org/results/sanne/incus-spawn/pubkey.gpg
sudo dnf install incus-spawn
```

#### Ubuntu / Debian

```shell
curl -fsSL https://sanne.github.io/isx-apt-releases/public.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/incus-spawn.gpg
echo "deb [signed-by=/usr/share/keyrings/incus-spawn.gpg] https://sanne.github.io/isx-apt-releases stable main" | sudo tee /etc/apt/sources.list.d/incus-spawn.list
sudo apt update && sudo apt install incus-spawn
```

#### Other Linux

```shell
curl -fsSL https://isx.run | sh
```

#### JBang

```shell
jbang app install isx@Sanne/incus-spawn
jbang app install isx-proxy@Sanne/incus-spawn
```

<!-- tabs:end -->

```shell
# One-time host setup (Incus, firewall, auth)
isx init

# Build a template (builds parent images automatically)
isx build tpl-java

# Launch the interactive TUI
isx
```

See [Installation](#installation) for all options and update instructions. Shell completions are available for bash, zsh, and fish via `isx completion <shell>`.

## Credential Isolation

**API keys and tokens never enter containers in any form.** A host-side MITM TLS proxy (`isx proxy`) provides completely transparent authentication:

- The proxy uses bridge-level DNS overrides and a custom CA certificate so containers transparently route intercepted domains through the proxy
- The proxy terminates TLS, injects real authentication headers, and forwards to the real upstream over TLS — tools (`curl`, `git`, `gh`, `claude`, `pi`) work unmodified inside containers
- Containers hold only placeholder values (e.g. `sk-ant-placeholder`) that satisfy tools' local auth checks; placeholders cannot authenticate against any real service
- **Vertex AI support**: the proxy transparently translates requests to Vertex AI format — no GCP credentials enter the container
- **Claude Pro/Max support**: authenticate via `claude setup-token`; the proxy injects the OAuth Bearer token transparently
- **Several accounts per credential**: configure e.g. a personal and a client Claude subscription, or two GitHub identities, and choose per template or per instance which one is spent -- see [Credential accounts](#credential-accounts)
- **HTTPS only**: Git operations must use HTTPS URLs (not SSH). `gh` defaults to HTTPS; for `git clone`, use `https://github.com/...`

There is no API, endpoint, environment variable, or file that code inside the container can access to obtain real credentials — the injection happens entirely outside the trust boundary.

Each instance also gets a random **instance secret** every time isx starts it, at the path in `$ISX_INSTANCE_SECRET_FILE` (`/run/isx/instance-secret`, readable by the instance user). It is not a credential for anything upstream: it lets a service on the host tell instances apart by source address *and* secret together, so it is useless from any other instance. A restart replaces it; a reboot isx did not perform leaves the instance without one until isx next starts it.

The proxy must be running for non-airgapped containers. `isx init` can install it as a systemd user service, or run `isx proxy` in a separate terminal. The CLI verifies proxy reachability and version compatibility before builds, branches, and shell access.

The proxy also caches container image layers and build artifacts on the host — the same dependency is never downloaded twice (see [Caching](#caching)).

### Git Configuration

With a GitHub PAT configured, containers get a ready-made `.gitconfig` with your GitHub name and email (the private `@users.noreply.github.com` address when there is one) and sensible defaults.

- **Dedicated agent account (recommended)**: give `isx init` the PAT of a separate GitHub account, so the agent's commits and PRs are clearly its own.
- **Your personal account**: use your own PAT. To bring your aliases and settings along, mount `~/.gitconfig` as a [host resource](#host-resources); it takes precedence over the generated config.

**Commit signing** isn't supported inside containers yet. To sign, fetch the changes to your host through [git remotes](#git-remotes) and rebase there ([#271](https://github.com/Sanne/incus-spawn/issues/271)).

## Branching

Like `git branch`, branching creates an instant copy-on-write clone of any template. Each branch has its own independent filesystem -- changes in one branch cannot affect the template or any other branch. The storage backend (btrfs/zfs/lvm) deduplicates unchanged data automatically, so branches are instant to create and only consume disk space for their own modifications. `isx init` automatically creates a btrfs storage pool if needed.

```
tpl-java  (stopped template, ~2GB)
  ├── fix-nasty-bug    (running, uses ~50MB extra)
  ├── review-pr-423    (running, uses ~30MB extra)
  └── experiment       (stopped, uses ~10MB extra)
```

You can install packages, break things, and destroy a branch when done. The template and other branches are completely unaffected. Sudo works without a password, and shell sessions set the terminal title to `isx:<containername>` so you always know which environment you're in.

Branches can optionally enable GUI/audio passthrough (Wayland + PipeWire with GPU acceleration, Linux only), restricted networking, an inbox mount to share files read-only from the host, or different [credential accounts](#credential-accounts) than their template's. Resource limits (CPU, memory, disk) are auto-detected from the host but can be overridden. The interactive TUI (`isx` with no arguments) provides a Midnight Commander-style interface with modal dialogs for branching, renaming, and building, plus F3 detail views and F9 tool actions.

The TUI shows a storage gauge and per-row disk usage so you can see what's filling the pool. Sizes are approximate (marked `~`): the base template carries the shared base image, while CoW branches show only the data unique to them. Press **C** to reclaim space, or on macOS grow the disk with `isx vm resize <size>`.

Templates pre-install your baseline tools and repos, and integrations plug in through the same tool system: VS Code Remote, JetBrains Gateway, shell completions, and Claude Code skills.

### Network Modes

Each branch runs in one of three network modes:

| Mode | Flag | Description |
|------|------|-------------|
| **Full internet** | *(default)* | Unrestricted network access via NAT, auth via MITM proxy |
| **Proxy only** | `--proxy-only` | Outbound traffic restricted to MITM proxy only (iptables) |
| **Airgapped** | `--airgap` | No network device at all, complete isolation |

### Git Remotes

Containers created with `isx branch` are isolated environments, but you need a way to get your changes back. isx integrates with git's native remote helper protocol so you can use standard `git fetch`, `git push`, and `git pull` between host repos and container repos:

```shell
# Inside the container, the agent makes some commits...
# Back on the host:
git fetch fix-auth
git diff main..fix-auth/main    # review exactly what it did
git cherry-pick fix-auth/main   # take what you like
```

This is the intended review workflow: you always act on a specific, immutable commit — never on a live directory the agent can still modify (see the [FAQ](#faq) for why there is deliberately no read-write project mount).

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
# Subdirectories are scanned recursively (up to 4 levels deep),
# so ~/projects finds ~/projects/java/my-repo, ~/projects/go/another-repo, etc.
# If a repo name appears in multiple locations, add an explicit repo-paths entry
host-paths:
  - ~/projects
  - ~/workspace

# Explicit overrides for repos in non-standard locations or to resolve ambiguity
repo-paths:
  quarkus: ~/work/quarkus
  hibernate: /opt/hibernate
```

With this configuration, `isx branch` adds a git remote named after the instance in each matching host repo (protocol-lenient — SSH and HTTPS URLs for the same repo are treated as equal), and `isx destroy` removes it.

## Delegating from an agent on your host (MCP)

An agent running on your machine -- Claude Code, say -- can use isx itself: create disposable instances from templates you approved, run builds and tests in them, and hand whole tasks to the Claude Code inside an instance. `isx mcp` serves the [Model Context Protocol](https://modelcontextprotocol.io) over stdio:

`isx init` offers to set this up (it is experimental, so off by default): it registers `isx mcp` with Claude Code for all projects and asks, for each template that installs Claude Code, whether agents may use it. To do the same by hand:

```shell
claude mcp add --scope user isx -- ~/.local/bin/isx mcp
```

Nothing is available until you approve templates in `~/.config/incus-spawn/config.yaml`. Agents never can -- `isx mcp` reads this section on every call and has no way to write it:

```yaml
mcp:
  templates: [tpl-java, tpl-dev]   # the only templates an agent may branch from
  max-instances: 8                 # per host user, across sessions (default 8)
  max-concurrent-tasks: 8          # background commands and delegated agents, per host user, across sessions (default 8)
  delegate-max-turns: 200          # optional turn budget for delegated agents; a task may ask for less, never more
  delegate-permission-mode: bypassPermissions   # the delegates' --permission-mode (default shown)
  delegate-permission-modes:       # per-template overrides, e.g. a reviewer that only plans
    tpl-review: plan
  orphan-grace-hours: 24           # how long an instance outlives its session (default 24)
  dormant-after-hours: 24          # past that, one kept only for a delegate showing no activity
                                   # this long is stopped instead (default 24)
  dormant-grace-hours: 168         # and destroyed this long after (default 168)
  summary-model: haiku             # answers the tools' `ask` inside the instance (default haiku)
```

`max-instances` and `max-concurrent-tasks` are the two knobs to raise for a controller, one session running many tasks at once. They are a safety net against runaway creation, not a budget: the only cost of a high number is host memory. `list_templates` reports both, under the same names (`max_instances`, `max_concurrent_tasks`). Every tool also returns its facts as `structuredContent` matching the `outputSchema` it lists, so a program need not parse the text. A task's `state` is the same word in every tool: `running`, `finished`, `attached` (finished, and a person is in its conversation), `lost` or `unknown` (`list_instances` gives it as the session last saw it, so `unknown` there means nothing has read how it ended yet, as after `cancel_task`; `task_status` reads it). A refusal says why in `_meta["dev.incusspawn/error"].code`.

| Tool | What it does |
|------|--------------|
| `list_templates` | The approved templates, whether they are built, which can take a delegated task, in which permission mode and on which model |
| `create_instance` | A fresh CoW branch of an approved template, as `isx branch` would make it, with an optional `purpose`; or, with `from_instance`, a fork of one of your stopped instances -- prepare once, fork N times |
| `stop_instance` / `start_instance` | Stop an instance (to fork it; refused while a task runs, unless `force`), and start it again |
| `list_instances` / `adopt_instance` | Your instances and their tasks, including those an ended session left behind; take one back |
| `exec` | Run a command as `agentuser`; no time limit unless the agent sets one, or `background: true` for a task |
| `delegate` | Give an instruction, or a skill name and arguments, to the Claude Code inside an instance (or a fresh one from a template), optionally on its own `model` and `max_turns` |
| `task_status` / `wait_any` / `task_result` | Follow a task (optionally waiting), wait for whichever of several finishes first, read its outcome or the agent's report |
| `send_message` | Continue a delegated agent's conversation (e.g. "push and open a PR") |
| `get_diff` | What a delegated task changed, committed or not: a patch, or with `stat` just the files and line counts |
| `instance_activity` | What the proxy saw of an instance's Claude model calls: made and in flight, when the last one ended, tokens spent |
| `cancel_task` / `destroy_instance` | Stop a task, or throw an instance away |
| `keep_instance` | Hand an instance over to you for good |

A delegated task can run under its own profile: `model` (a Claude Code model id or alias, e.g. `haiku` for a rebase, the template's own for a design) and `max_turns`, which later `send_message` turns keep unless they choose again. A model is checked against the template's credential account first, with one tiny request inside the instance that is remembered for the session, so a model the account cannot use fails the call rather than the task. `max_turns` can only narrow your `delegate-max-turns`. The permission mode is never the agent's to choose.

A client that drives `isx mcp` from code rather than from a model can ask to be told instead of waiting: list `isx/task_changed` under `capabilities.experimental` in `initialize`, and every change of a task's state arrives as a `notifications/isx/task_changed` (`task_id`, `instance`, `run`, `state`: `running`, `finished`, `lost`, `attached` or `released`, and `exit_code` once finished). Clients that do not ask are not polled for it.

A controller that recovers from a crash by re-running what it has no result for can make that safe: `create_instance`, `delegate` and `exec` with `background: true` take an optional `idempotency_key` (1-64 characters of `A-Za-z0-9._:-`). A call whose key already made something returns that instance or task, with `replayed: true`, instead of making a second one -- across sessions too: an orphan made under the key is adopted. The key lives exactly as long as what it made. A repeat must ask for the same thing (template or source, instance, kind of task): one that asks for something else is refused, never redirected, so a controller that picks the instance at run time records that choice with its intent. One outcome needs looking up rather than retrying: a key whose instance was kept with `keep_instance` is refused.

`instance_activity` answers from the host proxy's view, without touching the instance: a call in flight means the agent is working, a growing `idle_seconds` that it is stuck or finished without reporting. Its token counts (`input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`, as the API reports them) run from `counting_since`, when the proxy began counting that instance (absent while the proxy does not know the instance yet). For what a task spent, read before and after it and subtract -- only when both reads carry the same `counting_since`; a different or missing one means the counts started afresh in between (the proxy restarted) and the difference is not the task's.

`exec`, `task_result` and `get_diff` take an optional `ask`: instead of the text, a one-shot Claude Code on `summary-model` reads it *inside the instance* and answers the question ("which tests fail?"), so a long log or patch never fills the host agent's context. The answer is a model's reading -- untrusted and lossy -- so anything that gates a merge stays deterministic: `get_diff(stat)`, CI, a reviewer.

What an agent gets is deliberately narrow:

- **Only approved templates.** Never a project-local (`.incus-spawn/`) definition or an image built from one, and never an unbuilt one -- it is told to ask you to run `isx build`.
- **Template defaults, no choices.** Network mode, credential accounts, resources: exactly what `isx branch --from <template>` gives. Credentials stay in the host proxy as always; which GitHub account a delegate can push with is the one you pinned on the template.
- **Your instances, one session at a time.** Every instance is stamped with your host user, an optional purpose (`#870 implement`), and the session holding it; every tool checks that stamp. Instances outlive their session, because a coordinating agent restarts while its workers wait: when a session ends (or dies), its instances become orphans that any later session of yours can take back with `adopt_instance`, tasks included. An orphan nobody adopts is destroyed by the next `isx mcp` to start once `orphan-grace-hours` have passed -- never while someone is in it with `isx shell` or an agent it delegated to is still working, and never one handed to you with `keep_instance`. Instances are named `mcp-<template>-<hint>-<suffix>`; don't give your own instances `mcp-*` names. `isx list` adds an `MCP` column when there are any, showing each instance's state and purpose (`held: #870 implement`, `orphaned`, `kept: spike`). The TUI shows the same in an instance's details (F3), with who holds it, and has no column for it.
- **Taking over is safe.** You can join a delegate's conversation yourself: `isx shell` into the instance, then `claude --resume <session>`. While you are in it, `task_status` says `attached` and `send_message` is refused, so the host agent never races you on one conversation.
- **No host access.** Nothing runs on the host and no host files are read or written: results come back as text, and `get_diff` returns a patch rather than touching your checkout.
- **An audit trail.** Every call is logged, with credentials scrubbed, to `~/.local/state/incus-spawn/mcp.log`.

`isx mcp` runs as you, so these limits hold for the MCP tools, not for an agent that can also run `isx` through its shell. Allow the MCP tools and deny the rest in Claude Code's settings:

```json
{
  "permissions": {
    "allow": ["mcp__isx__*"],
    "deny": ["Bash(isx:*)", "Edit(~/.config/incus-spawn/**)", "Write(~/.config/incus-spawn/**)"]
  }
}
```

Delegated agents spend the Claude account of the template they run in. Text coming back from an instance -- command output, a delegate's report, a diff -- is data produced inside the sandbox, and the tool descriptions tell the host agent to treat it that way.

### A coordinator in an instance

The agent that coordinates the others can itself run in an isx instance, so that it acts with the instance's credentials -- a bot's GitHub login, say -- instead of yours, and reaches your machine only through the MCP tools. Branch it with `--mcp-client`:

```shell
isx branch coord --from tpl-dev --proxy-only --mcp-client
```

Inside it, `isx mcp` is served at `https://mcp.isx.internal/mcp` (MCP's Streamable HTTP transport) by the host proxy, which runs one host `isx mcp` for the instance. Each request must come from the instance's own address and carry the secret isx gives it at every start, in `X-Isx-Instance-Secret`; the file holding it is named by `$ISX_INSTANCE_SECRET_FILE`. In the instance:

```shell
claude mcp add-json --scope user isx "$(cat <<'EOF'
{"type": "http", "url": "https://mcp.isx.internal/mcp",
 "headersHelper": "printf '{\"X-Isx-Instance-Secret\":\"%s\"}' \"$(cat \"$ISX_INSTANCE_SECRET_FILE\")\""}
EOF
)"
```

Claude Code runs a `headersHelper` only in a workspace you have trusted, so start it once interactively in the directory the coordinator works in. It gets exactly the tools, approved templates and limits a host agent gets, and the same `mcp:` configuration applies. What differs:

- **The session is the instance.** What it creates stays held by it across restarts of its Claude Code and of the instance itself, and is picked up again on the next connection: nothing to adopt. Its instances become orphans only once it is destroyed, or no longer allowed to call; a new coordinator under the same name is another session and gets none of them. One whose template you no longer approve is released when the coordinator next connects, as a host session's would be when it ends.
- **One connection at a time.** A new connection from the instance ends its previous one.
- **No coordinator makes another.** Only `isx branch --mcp-client` grants it, never to a copy: not to `isx branch --from coord`, and not to anything created through MCP.
- **Only the host proxy is reached.** `mcp.isx.internal` resolves to the bridge gateway, so this works with `--proxy-only`; an `--airgap` instance cannot use it.

## Why full system containers?

**Docker and Podman are built for shipping applications** — minimal filesystems, single-process isolation, fast startup. isx solves a different problem: full **system containers** powered by [Incus](https://linuxcontainers.org/incus/) that behave like real machines. Each environment runs its own init system, has real networking (`ping`, `strace`, nested Podman/Docker), and supports GUI and audio passthrough (Linux only). Templates pre-install your baseline tools and repos, but the environment is a real Linux system — agents and users can freely `dnf install`, `pip install`, build from source, or run Docker Compose just like on a workstation.

This matters for agents in particular: an agent boxed into an app container hits walls constantly (no systemd services, no nested containers for Testcontainers, no debugging tools). An agent on an isx branch works exactly as it would on a developer workstation — because that's what it has.

For untrusted code, KVM virtual machines (`--vm`) provide hardware-level isolation with a separate kernel.

## Template Images

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

Three images are built-in (`tpl-minimal`, `tpl-dev`, `tpl-java`). The root image (`tpl-minimal`) uses a custom Fedora base from [`Sanne/incus-spawn-images`](https://github.com/Sanne/incus-spawn-images). Use `isx update-base` to check for new base image releases, pin a specific version, or track the latest:

```shell
isx update-base              # interactive — shows versions, prompts for action
isx update-base --list       # list available versions
isx update-base --latest     # track the newest release (remove any pin)
isx update-base fedora-44-v2 # pin to a specific release tag
```

Tracking latest is the default and requires no action: when the base image is unpinned, `isx build tpl-minimal` resolves the newest release at build time and installs it (falling back to the version baked into the binary if it can't reach the release list). Pinning writes a user-level override to `~/.config/incus-spawn/images/minimal.yaml`; `--latest` removes that override to resume tracking. After changing the base image version, rebuild with `isx build tpl-minimal`.

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
- `image_url` -- download URL for the base image tarball (supports `{arch}` and `{tag}` placeholders; `file://` works for testing a locally built image)
- `image_tag` -- release tag identifying the base image version
- `image_sha256` -- per-architecture SHA256 checksums for integrity verification
- `type` -- instance type: `container` (default), `vm`, or `kvm`. VMs use a separate kernel for hardware-level isolation. `kvm` is a container with the host's `/dev/kvm` passed through, so it can run VMs of its own (skip it per branch with `--no-kvm`). Inherits from parent -- a child without `type` inherits its parent's type
- `vm_image_url` -- download URL for the VM base image (qcow2 tarball). Only used when `type` is `vm`. Supports `{arch}` and `{tag}` placeholders
- `vm_image_sha256` -- per-architecture SHA256 checksums for the VM base image
- `parent` -- parent image name (omit for root images)
- `packages` -- dnf packages to install
- `remove_packages` -- dnf packages to remove before installing
- `package_repos` -- additional package repositories to enable (e.g. COPR)
- `tools` -- tool names to run (resolved from YAML or Java, see [Custom Tools](#custom-tools))
- `repos` -- git repositories to clone as agentuser (see below)
- `skills` -- Claude Code skills to bake into the image (see below); accepts a list shorthand or an object with `repo` and `list` sub-fields
- `agent_note` -- always-true fact an agent must know before acting (see [Agent Environment Context](#agent-environment-context))
- `host-resources` -- host files/directories to share with containers (see below)
- `mask_services` -- systemd units to mask in the image
- `env` -- environment variables written to `/etc/profile.d/isx-env.sh` (see [Environment Variables](#environment-variables))
- `gui` -- enable GUI support: container branches get GUI passthrough by default when isx runs in a Wayland session, unless the definition is project-local (`isx branch --no-gui` and the TUI dialog opt out)
- `pinned` -- pin the base image to `image_tag` instead of tracking the newest release
- `workdir` -- default working directory when shelling into a container (see below)
- `shell-command` -- command to run instead of the login shell (see below)
- `default-action` -- tool action to run when pressing Enter on an instance in the TUI (see below)
- `description` -- human-readable description for the TUI

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
- `default-action` -- a tool action to run when pressing Enter on an instance in the TUI or when running `isx run <instance>` from the CLI. The value is a tool name (e.g. `claude`) if the tool has a single action, or `tool:action-id` (e.g. `claude:launch`) if the tool has multiple actions (see [Tool Actions](#tool-actions) for the `id` field). When set, Enter runs the action and F2 opens a shell; when unset, Enter/`isx run` opens a shell. Inherits from parent templates; a child overrides the parent's default action. No rebuild required when changing this field.

### Claude Code

Claude Code is Anthropic's official CLI for Claude. Add it to any template with `tools: [claude]`:

```yaml
name: tpl-agent
description: Isolated dev environment with Claude Code
parent: tpl-dev
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - claude
default-action: claude
```

The `claude` tool downloads the latest Claude Code binary, configures permissions for unattended agent use, and sets up authentication. All three auth modes work transparently — the [MITM proxy](#credential-isolation) injects credentials so no real API keys or tokens enter the container.

To preconfigure which model Claude Code uses, pass the `model` parameter:

```yaml
tools:
  - claude:
      model: claude-sonnet-4-6
```

When omitted, Claude Code uses its own default. Model IDs follow the `claude-*` naming convention (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5-20251001`).

### Codex CLI

Codex CLI is OpenAI's coding agent. Add it to any template with `tools: [codex]`:

```yaml
name: tpl-codex-dev
description: Isolated dev environment with Codex CLI
parent: tpl-dev
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - codex
default-action: codex
```

Codex needs an OpenAI API key ([create one here](https://platform.openai.com/api-keys); API usage needs billing credits, even on free accounts). Run `isx init` to configure it: the key stays on the host and the [MITM proxy](#credential-isolation) injects it.

The build configures Codex for unattended use and trusts the template's repos. The default action resumes your most recent session.

To pin a model or reasoning effort (by default Codex picks both):

```yaml
tools:
  - codex:
      model: gpt-5.3-codex
      effort: high
```

`model` is any model your OpenAI account can use ([model list](https://platform.openai.com/docs/models)); `effort` is `minimal`, `low`, `medium`, `high` or `xhigh`.

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

Pi works out of the box with all three Anthropic auth modes (API key, Claude Pro/Max OAuth, Vertex AI) — the [MITM proxy](#credential-isolation) injects credentials transparently. To use Pi without making it the default shell, omit `shell-command` and launch it manually after `isx shell`.

Pi can also run against OpenAI, using the same key as [Codex CLI](#codex-cli):

```yaml
tools:
  - pi:
      provider: openai
      model: gpt-5.3
```

`provider` defaults to `anthropic` and also accepts `vertex`/`google`; `model` defaults to `claude-sonnet-4-6`.

### Bob Shell

Bob Shell is IBM's AI-powered coding assistant. Add it to any template with `tools: [bob]`:

```yaml
name: tpl-bob-dev
description: Isolated dev environment with Bob Shell
parent: tpl-dev
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - bob
default-action: bob
```

Bob Shell requires an IBM API key ([create one here](https://bob.ibm.com/docs/ide/account/api-keys#create-an-api-key)). Run `isx init` to configure it — the real key stays on the host and the [MITM proxy](#credential-isolation) injects it transparently. Containers only hold a placeholder value. During `isx init` you'll be asked to accept the IBM license agreement; accepting pre-configures it in all containers so Bob Shell won't prompt again. The default action launches Bob with `--auto-approve` (auto-approve all tool calls); running `bob` manually in a shell uses normal approval mode.

### GitHub Copilot CLI

Add GitHub Copilot CLI to a template with `tools: [copilot]`:

```yaml
name: tpl-copilot-dev
description: Isolated dev environment with GitHub Copilot CLI
parent: tpl-dev
repos:
  - url: https://github.com/myorg/myproject.git
    path: ~/myproject
workdir: ~/myproject
tools:
  - copilot
shell-command: copilot
```

Copilot uses the same GitHub PAT as the `gh` tool -- run `isx init` to configure your GitHub token if you haven't already. No separate credential is needed.

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

### Agent Environment Context

Every build writes a short primer to `/etc/claude-code/CLAUDE.md`, so Claude Code starts each session knowing where it is: the box is disposable, `sudo` needs no password, credentials are handled by the proxy, and outward-facing actions such as opening a PR wait until asked. It also lists the tools and repositories the template provides. Your own `~/.claude/CLAUDE.md` and project `CLAUDE.md` files load as usual.

Templates and tools can add their own lines with `agent_note`:

```yaml
name: tpl-openjdk
parent: tpl-dev
agent_note: |
  The build needs a boot JDK of 26/27/28. This box ships 26 at $JAVA_HOME and
  deliberately omits 25.
```

A tool's note, and any `skills` it ships, come along to every template that installs the tool. Bare skill names in a tool resolve against the tool's own `skills.repo`:

```yaml
name: mvnd
description: Apache Maven Daemon 1.x
agent_note: |
  `mvnd` is Maven running as a resident daemon: prefer it over `mvn` for repeated
  builds in the same repo. It takes the same goals and flags as `mvn`.
skills:
  repo: myorg/catalog
  list:
    - mvnd-builds
```

#### When to write a note

Notes are read in every session, so save them for things an agent would otherwise get wrong: a required flag, a version to avoid, an "obvious" fix that isn't. Be specific:

```yaml
agent_note: |
  Run configure with CC=/usr/bin/gcc CXX=/usr/bin/g++. Without them it aborts on
  /usr/lib64/ccache/gcc being a symlink; dropping --enable-ccache "fixes" it and
  silently costs every later rebuild.
```

Some content is better placed elsewhere:

| Instead of | Use |
| --- | --- |
| `agent_note: Maven 3.9 build tool` | `description`, which is what template and tool listings show |
| `agent_note: maven, podman and jtreg are installed` | Nothing -- the generated file already lists installed tools and cloned repos |
| `agent_note: Build with 'make images', test with 'make test TEST=tier1'` | A [skill](#claude-code-skills), which loads on demand rather than in every session |
| `agent_note: Be careful when editing the parser` | A specific constraint, or nothing at all |
| `agent_note: Follow the Quarkus code style` | The repository's own `CLAUDE.md`, which applies only to work in that repo |

Changing a note marks the templates that use it as out of sync (`△` in the TUI); rebuild them with `isx build --out-of-sync`.

### Host Resources

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

The `~/.gitconfig` entry mounts your git configuration read-only (the default mode), so `git` inside the container picks up your name, email, aliases, and other settings (see [Git Configuration](#git-configuration)).

Three modes are available:

| Mode | Default? | Description |
|------|----------|-------------|
| `readonly` | Yes | Read-only bind mount. Simple, safe. |
| `overlay` | No | Read-only lower layer from host + ephemeral writable upper in the container. Tools see a normal read-write directory. Host is fully protected. **Linux only** — not yet supported on macOS. |
| `copy` | No | Copied into the container at build time. Becomes part of the template. Also supports URL sources. |

`readonly` and `overlay` resources can't be mounted into system directories such as `/`, `/etc`, `/usr`, `/var` or `/tmp`. Use `/home/agentuser`, `/opt`, `/srv` or `/mnt` instead, or `mode: copy`.

If `path` is omitted, it defaults to the same relative path under `/home/agentuser/`. Missing host paths are skipped with a warning, so templates remain portable. Host resources compose across the parent chain, with child entries overriding parent entries matched by container path.

**Project-local templates** (`.incus-spawn/images/`) come from whatever repository you cloned, so they can't reach outside it: host resources and `file://` images must point inside the project directory, and their `repos` are always cloned from the network, never from your host checkouts. To give a template you trust wider access, move it to `~/.config/incus-spawn/images/`.

**VMs** mount host resources via virtiofs. Single-file resources fall back to `copy` mode, since VMs only mount directories.

## Built-in Tools

These tools ship with incus-spawn and can be referenced by name in a template's `tools:` list:

| Tool | Description |
|------|-------------|
| `claude` | Claude Code: AI coding assistant |
| `gh` | GitHub: PAT for git operations |
| `pi` | Pi: AI coding assistant |
| `bob` | Bob Shell: IBM AI coding assistant |
| `codex` | Codex CLI: OpenAI coding assistant |
| `copilot` | GitHub Copilot CLI: AI coding assistant (shares `gh` token) |
| `maven-3` | Apache Maven |
| `mvnd` | Apache Maven Daemon |
| `podman` | Podman container runtime configured for Testcontainers |
| `sshd` | OpenSSH server for remote access |
| `idea-backend` | JetBrains IntelliJ IDEA Remote Development backend |
| `vscode-remote` | VS Code Remote Development via SSH |
| `starship` | Starship cross-shell prompt with incus-spawn indicator |
| `tmux` | Terminal multiplexer with incus-spawn session integration |
| `zmx` | zmx: session attach/detach for the terminal |
| `headroom` | Headroom context optimization for Claude Code |

Run `isx tools list -v` to see all available tools including user-defined and project-local definitions. Use `isx tools show <name>` to inspect a tool's dependencies, packages, configurable parameters, downloads, TUI actions, and proxy domains.

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
- `downloads` -- artifacts to download, cache on the host, and copy and/or extract into the container
- `requires` -- list of other tool names that must be installed first (resolved transitively; circular dependencies are detected and rejected)
- `run` -- shell commands as root
- `run_as_user` -- shell commands as agentuser
- `files` -- files to write (with optional `owner`)
- `env` -- environment variables written to `/etc/profile.d/isx-env.sh` (supports structured entries with merge strategies; see below)
- `verify` -- verification command (logged, non-fatal); runs after every tool is installed, as `agentuser` in a login shell with the image environment (`/etc/profile.d/isx-env.sh`) loaded, so it may rely on another tool's variables such as `JAVA_HOME`
- `verify_as_root` -- set to `true` when the verify needs root (for example `sshd -t`, which reads the host keys); it then runs as root in root's own environment (`HOME=/root`, the default `PATH`), **without** the environment file, so it cannot rely on another tool's variables
- `actions` -- runtime actions available from the TUI when the tool is installed (see [Tool Actions](#tool-actions))
- `agent_note` -- always-true fact about this tool that an agent must know before acting (see [Agent Environment Context](#agent-environment-context))
- `skills` -- skills to install alongside the tool, teaching an agent how to drive it; same forms as the image-level field, but bare names resolve against this tool's own `skills.repo`
- `proxy` -- credential injection rules for the MITM proxy (see [Proxy Credentials](#proxy-credentials))

Download entry fields:
- `url` (required) -- `http://` or `https://` download URL (local addresses such as `localhost` are refused)
- `sha256` (recommended) -- SHA-256 checksum; enables cache reuse and verifies integrity
- `extract` (optional) -- directory in the container to extract into
- `destination_file` (optional) -- exact path at which to expose the downloaded file in the container; `~/` resolves to `/home/agentuser/`
- `extract_in_container` (optional) -- extract inside the container instead of on the host
- `links` (optional) -- map of `source_path: symlink_path` to create after extraction

Each download must set `extract`, `destination_file`, or both. Setting both preserves the downloaded archive at `destination_file` and also exposes its extracted contents. Supported archive formats for `extract` are `.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz`, `.zip`.

Execution order during `install()`: packages → downloads → `run` → `run_as_user` → `files`. Environment variables are collected from all tools and the template chain after install, then written centrally; each tool's `verify` runs after that. Resolution follows the same order as templates (see [Configuration](#configuration)).

#### Environment Variables

Environment variables support four merge strategies:

```yaml
env:
  # Simple set (default strategy):
  - name: MAVEN_HOME
    value: /opt/apache-maven-3.9.16

  # Set only if not already defined:
  - name: DOCKER_HOST
    value: "unix:///var/run/docker.sock"
    strategy: set-if-unset

  # Prepend to existing value (with separator):
  - name: MAVEN_OPTS
    value: "-Xmx2g"
    strategy: prepend
    separator: " "

  # Append to existing value:
  - name: PATH
    value: /opt/bin
    strategy: append
    separator: ":"
```

All env entries from the template chain and installed tools are collected by the build system and written to `/etc/profile.d/isx-env.sh`. **Conflicting definitions are caught at build time**: if two tools both `set` the same variable to different values, the build fails with a descriptive error naming both sources. Every entry must use the structured `name`/`value` form; a shell string such as `- export FOO=bar` is rejected when the definition is loaded.

Templates can also declare environment variables directly:

```yaml
name: tpl-my-project
parent: tpl-dev
env:
  - name: MY_DEBUG
    value: "true"
    strategy: set-if-unset
```

#### Proxy Credentials

Tools can register domains with the [MITM proxy](#credential-isolation) for transparent credential injection. This lets you add authentication to any HTTPS API without exposing secrets inside containers. The built-in `claude`, `gh`, `bob`, `codex`, and `copilot` tools use this mechanism -- but you can declare the same for your own tools.

A `proxy:` block has three parts: a `config-namespace` that scopes config paths, a `configuration` map that declares what credentials are needed, and `auth` entries that map domains to authentication rules.

```yaml
# ~/.config/incus-spawn/tools/artifactory.yaml
name: artifactory
description: JFrog Artifactory

proxy:
  config-namespace: artifactory
  configuration:
    token:
      config-path: "token"
      description: "Artifactory API token"
      secret: true
  auth:
    - domains:
        - artifactory.internal.example.com
      type: bearer
      token: "${token}"
```

After placing this file, `isx init` will prompt for the Artifactory token alongside other credentials. The token is stored in `~/.config/incus-spawn/config.yaml` under `artifactory.token` (the namespace prefixes the config path). Inside containers, HTTPS requests to `artifactory.internal.example.com` get a `Authorization: Bearer <token>` header injected automatically -- no configuration inside the container needed.

Three auth types are supported:

| Type | Injected header | Required fields |
|------|----------------|-----------------|
| `bearer` | `Authorization: Bearer <token>` | `token` |
| `basic` | `Authorization: Basic <base64(username:password)>` | `username`, `password` |
| `header` | Custom header | `name`, `value` |

Auth fields can be literal values (e.g. `username: "deploy-bot"`) or `${configKey}` references resolved against the `configuration` map. A tool with all three types:

```yaml
proxy:
  config-namespace: myService
  configuration:
    api-key:
      config-path: "apiKey"
      description: "API key for myservice.com"
      secret: true
    password:
      config-path: "password"
      description: "Registry password"
      secret: true
  auth:
    - domains:
        - api.myservice.com
      type: bearer
      token: "${api-key}"
    - domains:
        - registry.myservice.com
      type: basic
      username: "deploy-bot"
      password: "${password}"
    - domains:
        - "*.internal.myservice.com"
      type: header
      name: X-API-Key
      value: "${api-key}"
```

Wildcard domains (`*.internal.myservice.com`) match any subdomain. The most specific wildcard wins when multiple tools register overlapping suffixes.

Configuration entries can also use `value` for hardcoded literals (no prompt during `isx init`) and `type: confirm` for yes/no prompts like license acceptance.

Tool YAML files with `proxy:` blocks must be placed in `~/.config/incus-spawn/tools/` or a configured search path -- project-local tools (`.incus-spawn/tools/`) cannot declare proxy rules because the proxy daemon runs independently of any project directory.

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

### Terminal Session Persistence (zmx)

[zmx](https://zmx.sh) provides session attach/detach for the terminal — persistent shell sessions that survive disconnections, with native scrollback and multi-client support. Unlike tmux, it delegates window management to your OS rather than reimplementing it.

```yaml
name: tpl-agent
parent: tpl-dev
tools:
  - zmx
```

By default, `auto_attach` is enabled: shelling into the container automatically attaches to a persistent zmx session named `isx`. To install zmx without auto-attaching:

```yaml
tools:
  - zmx:
      auto_attach: "false"
```

On Linux, the container's zmx socket directory is shared with the host via a disk device. Container sessions appear in your native zmx socket directory with an `isx-` prefix — no configuration needed:

```bash
zmx list                              # shows isx-my-branch alongside local sessions
zmx attach isx-my-branch              # attach to the container's session from the host
zmx history isx-my-branch             # view scrollback from the container
zmx run isx-my-branch git status      # run a command in the container's session
```

All zmx commands work natively, including interactive `zmx attach`. On macOS, where Incus runs inside a VM, host-side socket sharing is not available. zmx sessions still work inside containers — `isx shell` (or Enter in the TUI) auto-attaches, and sessions persist across disconnections.

### Context Optimization (headroom)

The built-in `headroom` tool installs [Headroom](https://github.com/nicobrenner/headroom), a context optimization proxy for Claude Code. It runs as a local proxy on port 8787 that compresses conversation context, reducing token usage and improving response quality on long sessions. An MCP server is also registered with Claude Code for direct integration.

```yaml
name: tpl-agent
parent: tpl-java
tools:
  - headroom    # auto-installs claude via requires
```

The tool sets `ANTHROPIC_BASE_URL=http://localhost:8787` so Claude Code routes through the optimization proxy. The proxy runs as a systemd user service and starts automatically on container boot.

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
  - name: SERVER_MEMORY
    value: ${param_memory}
```

Pass parameter values using the map form in image definitions (the `idea-backend` memory example above shows this pattern).

### Tool Actions

Tools can declare runtime actions that appear in the TUI (press **F9** on a running instance, or **Enter** to run the template's default action) and can be invoked from the CLI via `isx run <instance> --action <tool:action-id>`. Running `isx run <instance>` with no `--action` flag executes the template's default action. Actions can be declared in YAML tool definitions or programmatically by Java/CDI tools. The built-in `claude` and `pi` tools automatically contribute shell actions ("Claude Code" and "Pi Coding Agent") when included in a template's `tools` list.

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

Action visibility fields:

- `shell_menu` -- when `true`, the action also appears in the **F12 shell status bar menu** during `isx shell` sessions (only `url` actions, which open on the host without taking over the terminal)
- `shortcut` -- the single key that runs the action from that menu: one printable ASCII character, not shared with another menu action (case-insensitively); an action without a usable one is left out of the menu with a warning

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

## Caching

The proxy and build system cache downloads on the host, shared by all templates and branches. Only content that can be verified is cached; everything is checked against its digest or upstream checksum before it's stored.

Proxy caches (from container traffic):

- **Container image layers** — OCI blobs from Docker Hub, GHCR, and Quay, keyed by SHA256 content digest
- **Maven and Gradle artifacts** — release JARs, POMs, and plugins from Maven Central and the Gradle plugin portal, verified against the upstream checksum and re-confirmed with upstream (a `HEAD` on Maven Central, the checksum file elsewhere) as it ages; see below. When the repository is unreachable, cached artifacts are served unconfirmed so builds keep working offline
- **Gradle distributions** — verified against the upstream `.sha256` sidecar, and re-confirmed against it the same way

How recently a cached Maven or Gradle artifact must have been confirmed with upstream is configurable. Within `fresh` of its last confirmation it is served straight from the cache. Within `max-stale` it is served at once and confirmed again in the background, and a copy upstream has changed or withdrawn is evicted for the next request. Older than that, it is confirmed before it is served. Gradle Plugin Portal artifacts are always confirmed first, since the portal can delete a published version. The defaults:

```yaml
artifact-cache:
  fresh: 2h        # durations: 30m, 2h, 7d ...
  max-stale: 7d
```

Setting both to `0` confirms every hit before serving it. This is not recommended: Maven Central does not allow published artifacts to change, so it only adds latency.

Build-time caches:

- **DNF packages**, shared by every build, so builds reuse each other's downloads
- **Tool downloads**, reused by rebuilds

All caches live under `~/.cache/incus-spawn/`. Nothing expires by age or size; an artifact is dropped only when upstream changes or withdraws it.

## Roadmap

isx is evolving from a container manager into **mission control for parallel coding agents**. Per-instance identities have begun with [credential accounts](#credential-accounts) — each instance can use its own Claude and GitHub accounts; next come audited commit signing without exposing private keys ([#271](https://github.com/Sanne/incus-spawn/issues/271)), proxy-derived monitoring of agent status and spend, task dispatch, and an in-TUI review lane ([#322](https://github.com/Sanne/incus-spawn/issues/322)). Dispatch is also coming to agents themselves: an experimental `isx mcp` server lets an agent on your host create instances from templates you approved, run builds and tests in them, and delegate whole tasks to the agents inside ([#859](https://github.com/Sanne/incus-spawn/issues/859), [#898](https://github.com/Sanne/incus-spawn/issues/898)). Local-first stays the core conviction — your hardware, your network, your repos. See [docs/VISION.md](docs/VISION.md) for the full direction.

## Installation

<!-- tabs:os -->

### macOS (Homebrew)

```shell
brew install Sanne/tap/incus-spawn
```

Updates with `brew upgrade incus-spawn`. See [docs/HOMEBREW.md](docs/HOMEBREW.md) for details.

### Fedora / RHEL (DNF)

```shell
sudo dnf copr enable sanne/incus-spawn
sudo rpm --import https://download.copr.fedorainfracloud.org/results/sanne/incus-spawn/pubkey.gpg
sudo dnf install incus-spawn
```

Available for Fedora 43+, RHEL 9–10, CentOS Stream 9, AlmaLinux 9, and Rocky Linux 9 (via EPEL). Updates automatically with `sudo dnf upgrade`.

### Ubuntu / Debian (APT)

```shell
curl -fsSL https://sanne.github.io/isx-apt-releases/public.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/incus-spawn.gpg
echo "deb [signed-by=/usr/share/keyrings/incus-spawn.gpg] https://sanne.github.io/isx-apt-releases stable main" | sudo tee /etc/apt/sources.list.d/incus-spawn.list
sudo apt update && sudo apt install incus-spawn
```

Updates automatically with `sudo apt upgrade`. See [docs/APT.md](docs/APT.md) for details.

### Any Linux distro (native binary)

```shell
curl -fsSL https://isx.run | sh
```

Installs a self-contained native binary to `~/.local/bin/isx`. No JVM required. Set `INSTALL_DIR` to change the install location. To update, re-run the same command. To uninstall, run `uninstall.sh` (caches at `~/.cache/incus-spawn/` are preserved unless you pass `--purge`).

### JVM via JBang

```shell
jbang app install isx@Sanne/incus-spawn
jbang app install isx-proxy@Sanne/incus-spawn
```

Both are needed, and `jbang` must stay on your PATH. To update, re-run both commands.

<!-- tabs:end -->

## Configuration

- `~/.config/incus-spawn/config.yaml` -- auth credentials and global settings (including the `mcp:` section, see [Delegating from an agent](#delegating-from-an-agent-on-your-host-mcp))
- `~/.config/incus-spawn/ssh/` -- managed SSH key pair, per-instance config, and known_hosts
- `~/.config/incus-spawn/images/*.yaml` -- user-level template definitions
- `~/.config/incus-spawn/tools/*.yaml` -- user-level tool definitions
- `.incus-spawn/images/*.yaml` -- project-local template definitions
- `.incus-spawn/tools/*.yaml` -- project-local tool definitions

### Credential accounts

You can configure a credential more than once under different names, for example a personal and a client Claude subscription, or two GitHub identities, and choose which one each instance uses. You can even switch a running instance to another account, with no restart.

```yaml
claude:
  accounts:
    personal:
      type: oauth
      oauthToken: "sk-ant-oat01-..."
    acme:
      type: vertex
      cloudMlRegion: europe-west1
      vertexProjectId: acme-prod
  default: personal
github:
  accounts:
    personal:
      token: "ghp_..."
    acme-bot:
      token: "ghp_..."
      email: "bot@acme.example"
  default: personal
```

You don't need to write this by hand: `isx init` adds, renames and removes accounts and sets the default. Re-run it any time to change them.

An instance uses, most specific first:

1. its own choice: `isx branch --account`, `isx account set`, or **a** in the TUI;
2. its template's `accounts:`;
3. the credential's `default`.

```yaml
name: tpl-acme
parent: tpl-java
accounts:
  claude: acme
  github: acme-bot
```

When you branch from a template, the new instance takes the template's account choices and keeps them. Editing `accounts:` later only affects instances branched after that.

#### Example: keeping a client's work on the client's accounts

```shell
isx build tpl-acme
isx branch acme-42 --from tpl-acme
isx account show acme-42
```

```
acme-42 (branched from tpl-acme):

  claude  acme -- Google Cloud Vertex AI (region: europe-west1, project: acme-prod)
          Pinned by template tpl-acme's accounts: setting, copied onto this instance when it was branched.

  github  acme-bot -- commits as bot@acme.example
          Pinned by template tpl-acme's accounts: setting, copied onto this instance when it was branched.
```

Everything in `acme-42` now runs as Acme's accounts. For a one-off, choose per branch: `isx branch review-1 --account github=acme-bot`.

#### Managing an instance's accounts

```shell
isx account list                        # accounts, and which instances use each
isx account show review-1               # what review-1 uses, and why
isx account set review-1 github=acme-bot
isx account unset review-1 github       # follow the default again
```

In the TUI, **F3** on an instance shows its accounts and **a** changes them. The branch dialog (**F4**) lets you pick accounts too.

Changes take effect on the instance's next request, with no restart, and switching GitHub accounts also updates its git identity. The exception is switching between Claude auth modes (Pro/Max, API key, Vertex), which needs a template built for that mode ([#866](https://github.com/Sanne/incus-spawn/issues/866)).

Changing a default moves every instance that follows it; `isx init` lists them first and lets you keep them where they are. Renaming an account in `isx init` updates the instances using it, and removing one tells you what still uses it. An instance left pointing at a missing account stops working rather than using another one; `isx doctor` and `isx account show` point these out.

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

The TUI updates live as instances are started, stopped, created or deleted elsewhere. To turn this off:

```yaml
tui-live-refresh: false
```

## Scripting isx

Query commands take `--format=table|plain|json`: `isx list`, `isx templates [list]`, `isx tools list`, `isx tools show`, `isx account list`, `isx account show`, `isx proxy status`, `isx doctor`, `isx vm status` (macOS), `isx update-base --list` and `isx branch`.

- `table` is for people. It may change in any release, so don't parse it.
- `plain` prints one record per line, its fields tab-separated, with `-` for an empty field. There is no header, padding, colour or glyph, and no results means no output.
- Neither format lets a value write a control character to your terminal. A value can hold one: an instance stamp set by hand with `incus config set`, say, escape sequence and all. `plain` keeps each record on one line and safe to print, so every control character in a value becomes a space: the C0 controls U+0000 to U+001F (tab, line feed, carriage return, backspace, form feed and ESC among them), DEL, the 8-bit controls U+0080 to U+009F, U+2028/U+2029, and the bidirectional embeddings, overrides and isolates U+202A to U+202E and U+2066 to U+2069, which would make your terminal show the rest of the line out of order. That loses them, so read `json` when you need a value exactly: it keeps every value as it is, writing each of those characters as a JSON escape that any JSON parser turns back into the original: `\t`, `\n`, `\r`, `\b` and `\f` for tab, line feed, carriage return, backspace and form feed, and `\uXXXX` for every other one.
- `json` prints an array of objects, or one object for a command about one thing (`tools show`, `proxy status`, `vm status`, `branch`). A list value is an array in `json` and its elements joined by `,` in `plain`. An absent value is `null`, and sizes are in bytes. Times are ISO-8601 with their offset: `2026-10-05T12:34:56+02:00`, or `2026-10-05T12:34:56Z` on a host whose zone is UTC, so match both forms. Instances stamped by older isx releases recorded only a date, which prints as an ISO-8601 date (`2026-09-01`). A time isx cannot read is `null` (`-` in `plain`).
- When isx cannot read what Incus answered, the command fails (exit 1, the reason on stderr). It never prints an empty result.
- Both `plain` and `json` are stable. Fields may be added at the end, but are never renamed, removed or reordered.
- Results go to stdout. Errors and diagnostics go to stderr, so stdout holds only results. Commands that act (`build`, `branch`, `destroy`, ...) report their steps on stdout and their warnings and notes on stderr. A status command's report is its result in every state: `isx proxy status` prints it on stdout whether or not the proxy is healthy, and the exit code says which.
- isx writes colour, clickable links and animated progress only when stdout is a terminal. When stdout is redirected or piped, or `NO_COLOR` is set to a non-empty value, neither stdout nor stderr holds escape sequences. isx cannot tell whether stderr alone is a terminal, so `isx build tpl-dev 2>build.log` run from a terminal still colours the warnings in `build.log`. For a clean log, redirect both streams or set `NO_COLOR=1`.

`isx list` prints `name`, `status` (`running`, `stopped`, ...), `ipv4`, `parent`, `runtime` (`container` or `virtual-machine`), `created`, `mcp_state` and `mcp_purpose`, in that order. The last two are `null` (`-`) unless an [`isx mcp`](#delegating-from-an-agent-on-your-host-mcp) session made the instance. `mcp_state` is then `held` (a session holds it), `orphaned` (its session ended and nobody has adopted it yet) or `kept` (an agent handed it to you with `keep_instance`), and `mcp_purpose` is what the agent said the instance is for, or `null`. For names alone, use `isx list -q`:

```shell
for name in $(isx list -q --status=stopped); do isx destroy "$name" --skip-confirmation; done
isx list --format=json | jq -r '.[] | select(.parent == "tpl-java") | .name'
```

The fields of the other commands, in order:

| Command | Fields |
|---------|--------|
| `isx templates` | `name`, `parent`, `source` (`built-in` or the file), `description`, `built`, `built_at`, `version_outdated` (built by another isx version), `definition_changed` (its definition or a tool it uses changed since), `parent_rebuilt` (its parent was built after it, and it was copied from the parent: never for a VM over a container parent, or the reverse, which is built from the definitions). The staleness flags are `null` for a template that is not built, and everything from `built` on is `null` when Incus could not be asked, with the reason on stderr. `isx build --out-of-sync` builds the templates that are not built, rebuilds those with `version_outdated`, `definition_changed` or `parent_rebuilt`, and rebuilds the descendants of every template it builds |
| `isx tools list` | `name`, `source`, `description` |
| `isx tools show <tool>` | `name`, `description`, `source`, `feature`, `requires`, `packages`, `parameters`, `actions` (labels), `downloads` (URLs), `proxy_domains` |
| `isx account list` | one record per account: `namespace`, `account`, `description`, `default`, `problem` (`incomplete`, or `not-configured` for an account only a pin names), `pinned_by`, `following` (the instances that follow the default). The last two are `null` when Incus could not be asked, with the reason on stderr; `plain` prints that as `-`, like an empty list, so read `json` to tell them apart |
| `isx account show <instance>` | one record per namespace: `namespace`, `account`, `description`, `chosen_by` (`default`, `template`, `explicit`, `copied` or `unknown`), `chosen_in` (the template or instance it came from), `template`, `template_account`, `problem`, `identity_pending` (the git identity the instance takes on its next start or shell), `template_problem` (why the template's own choice cannot be used) |
| `isx proxy status` | `status` (`running`, `waiting_for_dns`, `not_running`, `stale_dns`, `stale_gateway`, or `unknown` when the state could not be checked), `version`, `git_sha`, `runtime`, `dns_overrides`, `drift` (one string), `auth_error`, `health_endpoint`, `mitm_port`, `service_installed`, `managed_by` (`systemd`, `launchd` or `manual`), `restart_helps` (whether restarting clears the drift; `null` without drift), `check_error` (why the state is `unknown`). Printed in every state; the exit code is the table's, so `unknown` exits 1 like `not_running`: read `status` to tell them apart |
| `isx doctor` | one record per check: `level` (`ok`, `note`, `warn`, `fail`), `label`, `detail`, `remediation`. `detail` and `remediation` are free text for people: key a script on `level` and `label`. Nothing is offered or applied; `--bundle` cannot be combined with it |
| `isx vm status` | `running`, `pid`, `rest_api`, `log`, `appliance`, `appliance_pending` (the installed appliance a restart would apply), `vsock_connections`, `incus_reachable`, `incus_error`, `vsock_connections_high` (the in-VM forwarder may be leaking streams; `isx vm restart` clears them). Exits 1 when Incus is unreachable |
| `isx update-base --list` | newest first: `tag`, `date`, `latest`, `current`, `pinned` (the current release is pinned, so builds stay on it), `listed` (`false` for the current base image when it is not among the releases fetched, e.g. older than all of them, unpublished or deleted: it then gets a record of its own, last, with no `date`. That alone says nothing about its age) |
| `isx branch <name>` | `name`. Progress goes to stderr and no shell is opened, so `name=$(isx branch my-box --from tpl-java --format=plain)` captures it |

Exit codes:

| Code | Meaning |
|------|---------|
| 0 | Success, including a listing with no results |
| 1 | The command failed, with the reason on stderr: a value it rejects (`--format=yaml`, `--status=frozen`), Incus unreachable, ... `isx doctor` also exits 1 when a check fails, `isx vm status` when Incus is unreachable, and `isx proxy status` when the proxy is not running (that report is on stdout) |
| 2 | The command line could not be parsed (an unknown option, an option missing its value, a stray argument), with the usage on stderr. For `isx proxy status` only, 2 also means the proxy is down but its DNS overrides are still active |
| 3 | `isx proxy status` only: the proxy runs on an old bridge address |
| 78 | `isx proxy start` cannot run the proxy at all, e.g. `isx-proxy` is not installed |

## Compared with other sandboxes

Other projects also run coding agents away from the host. This section says where each one draws its boundary, and when to pick it over isx. See [Why full system containers?](#why-full-system-containers) for what isx's boundary gives an agent.

### NVIDIA OpenShell

[OpenShell](https://github.com/NVIDIA/OpenShell) agrees with isx on credentials: the sandbox never holds a real key, a trusted process outside it terminates TLS, injects the credential on approved requests, and identifies the caller in a way the sandbox cannot forge. The two differ in *what* is sandboxed.

OpenShell confines a workload: an application container with no network device, one non-root user, no Linux capabilities, and a Landlock filesystem policy fixed at startup that leaves `/usr` and `/etc` read-only. Seccomp hands every TCP connect and DNS lookup to a supervisor that checks it against a policy naming hosts, HTTP methods and the calling program. That is a finer-grained egress policy than isx's network modes.

isx confines a machine, because what we hand an agent is an investigation, and nobody knows at the start what tools it will need. A test that only fails under load: install `bpftrace` and write a probe. A slow native build: install async-profiler and compare flame graphs. A Testcontainers suite: run podman inside. A flaky remote integration: write a mock server, point `/etc/hosts` at it, capture the exchange with `tcpdump`. None of these is possible in OpenShell: there is no root to install anything, `bpf` and `perf_event_open` need capabilities the workload does not have, `/etc` is read-only, nested containers need namespaces and a NIC, and nothing about the sandbox can change once it runs, so each step means a person rebuilds the image and the agent's context is lost. In isx the agent has root on a system with systemd, nested containers, debuggers and a real NIC, and if it wrecks the box, the branch is destroyed and the next one costs a second. Pick OpenShell to run an agent as a governed workload in a fleet; pick isx to let one work the way a good engineer does.

## FAQ

### Why can't I mount a host directory read-write to follow agent work in my IDE?

A project directory is not just data — it is an implicit code execution channel. Build tools, package managers, and IDEs all trust its contents and execute them with your full host privileges. A read-write mount turns the agent's output into unreviewed host-side code execution, which is exactly the threat model isx exists to prevent.

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
| [`isx`](#isx) | Launch the interactive TUI |
| [`isx init`](#isx-init) | One-time host setup |
| [`isx build`](#isx-build) | Build or rebuild template images |
| [`isx branch`](#isx-branch) | Create a CoW clone from a template or instance |
| [`isx shell`](#isx-shell) | Open a shell in an instance |
| [`isx run`](#isx-run) | Run an action on an instance |
| [`isx destroy`](#isx-destroy) | Destroy an instance |
| [`isx list`](#isx-list) | List instances, for people or for scripts |
| [`isx update-base`](#isx-update-base) | Check for and install base image updates |
| [`isx update-all`](#isx-update-all) | Update packages and repos in all templates |
| [`isx templates`](#isx-templates) | Manage template definitions |
| [`isx tools`](#isx-tools) | List and inspect available tool definitions |
| [`isx account`](#isx-account) | Show or change the credential accounts an instance uses |
| [`isx project`](#isx-project) | Manage project templates |
| [`isx proxy`](#isx-proxy) | Manage the MITM authentication proxy |
| [`isx doctor`](#isx-doctor) | Diagnose host, proxy, VM, and tunnel health |
| [`isx clean`](#isx-clean) | Remove cached data, state, or configuration |
| [`isx reset`](#isx-reset) | Reset to a clean slate |
| [`isx vm`](#isx-vm) | Manage the VM appliance (macOS only) |
| [`isx ask`](#isx-ask) | AI-powered help |
| [`isx mcp`](#isx-mcp) | Serve isx to a local agent over MCP |
| [`isx completion`](#isx-completion) | Print shell completion script |

Use `isx <command> --help` for detailed options on any command.

---

### `isx`

Launch the interactive TUI. Falls back to plain-text listing when stdout is not a terminal.

    isx

### `isx init`

One-time host setup: install Incus, configure credentials and their [accounts](#credential-accounts), test connectivity. Safe to re-run, e.g. to add, rename or remove an account.

    isx init

### `isx build`

Build or rebuild a template image.

    isx build [<template>...] [options]

Several templates build in one invocation: plainly, each in turn, a named parent before its child (each with any parent that is missing or out of sync, so a parent they share is built once); with `--with-parents` or `--with-descendants`, as one batch with one confirmation, parents before children and every template once. An unknown name anywhere in the list builds nothing. A target that fails does not stop the others: one that inherits from it is skipped, the rest are built, and the build then exits 1 with one `Some templates failed to build:` line on stderr naming every template it left unbuilt (a parent a target's chain failed on included).

| Option | Description |
|--------|-------------|
| `--all` | Rebuild all defined templates |
| `--out-of-sync` | Rebuild templates whose definition or isx version changed, or whose parent was rebuilt after them, with everything inheriting from them |
| `--with-parents` | Rebuild the templates and all their parents unconditionally |
| `--with-descendants` | Rebuild the templates and all templates inheriting from them |
| `--missing` | Build only templates that don't exist yet; like several named templates, a failure skips what inherits from it and the rest are still built, then exits 1 naming what was not |
| `--type <type>` | Instance type: `container`, `vm`, or `kvm` (overrides image definition) |
| `--yes` | Skip interactive confirmations |
| `--skip-git-refresh` | Skip refreshing host-side git repositories before building |

### `isx branch`

Create a new instance as a copy-on-write clone from a template or existing instance.

    isx branch <name> [options]

| Option | Description |
|--------|-------------|
| `--from <source>` | Source instance to branch from (auto-detected from cwd if omitted) |
| `--gui` | Enable GUI passthrough (Wayland + GPU + audio); the default for a `gui: true` container template (not a project-local one) when isx runs in a Wayland session |
| `--no-gui` | Disable GUI passthrough even if the template has `gui: true` |
| `--kvm` | Expose /dev/kvm for nested virtualization |
| `--no-kvm` | Disable KVM even if the template was built with `type: kvm` |
| `--airgap` | Disable all network access |
| `--proxy-only` | Restrict network to the host proxy only |
| `--inbox <dir>` | Host directory to mount read-only at ~/inbox inside the instance |
| `--cpu <N>` | CPU core limit (default: adaptive) |
| `--memory <size>` | Memory limit, e.g. `8GB` (default: adaptive) |
| `--disk <size>` | Disk size limit (default: adaptive) |
| `--no-start` | Don't start the instance after creation |
| `--shell` | Open a plain shell instead of running the default action |
| `--account <ns>=<account>` | Use this [credential account](#credential-accounts) instead of the template's or the default; repeatable |
| `--mcp-client` | Let the instance drive isx over MCP, at `https://mcp.isx.internal/mcp`: a [coordinator in an instance](#a-coordinator-in-an-instance). Never inherited by its copies |

### `isx shell`

Open a shell in an existing instance.

    isx shell <instance>

When the `shell-status-bar` feature is enabled, a two-line status bar is pinned at the bottom of the terminal showing the instance name, template, IP address, and network mode. Press **F12** to open a quick-action menu with shortcuts from tool actions that set `shell_menu: true`. Enable with:

```yaml
# ~/.config/incus-spawn/config.yaml
features:
  - shell-status-bar
```

### `isx run`

Run the default action or a specific action on an instance.

    isx run <instance> [options]

| Option | Description |
|--------|-------------|
| `--action <ref>` | Action to run (`tool-name` or `tool-name:action-id`) |

### `isx destroy`

Destroy an instance, or bulk-destroy all templates or instances.

    isx destroy <instance>
    isx destroy --all-templates      # destroy all built templates (derived first)
    isx destroy --all-instances      # destroy all instances

| Option | Description |
|--------|-------------|
| `--all-templates` | Destroy all built templates (reverse order, derived first) |
| `--all-instances` | Destroy all instances |
| `--skip-confirmation` | Skip the confirmation prompt |

### `isx list`

List the instances isx created, without opening the TUI. Templates are not listed: `isx templates` lists those. See [Scripting isx](#scripting-isx) for the `plain` and `json` formats.

    isx list [options]

| Option | Description |
|--------|-------------|
| `--format=<format>` | `table` (the default, for people), `plain` or `json` (for scripts) |
| `--plain` | Same as `--format=plain`. Before this release it was a no-op that printed the table, so a script that skipped a header with it now gets records from the first line |
| `-q`, `--quiet` | Print instance names only, one per line |
| `--status=<status>` | Only instances that are `running` or `stopped` |

`isx instances`, which printed the names of every Incus instance not named `tpl-*`, is deprecated and will be removed in a later release; it now runs `isx list -q`. Shell completion scripts generated by this release no longer call it.

### `isx update-base`

Check for and install base image updates.

    isx update-base [<release-tag>] [options]

Pass a release tag (e.g. `fedora-44-v2`) to pin to that version.

| Option | Description |
|--------|-------------|
| `--list` | List available versions |
| `--latest` | Track the latest version (remove any pin) |

### `isx update-all`

Update system packages, npm globals, and git-fetch repos in all templates. Does not re-clone repos or reinstall tools; for that, use `isx build`.

    isx update-all [options]

| Option | Description |
|--------|-------------|
| `--prime` | Also re-run prime commands (e.g. `mvn install -DskipTests`) for repos that define one |

### `isx templates`

Manage template definitions. Running bare `isx templates` defaults to `list`.

    isx templates <subcommand>

| Subcommand | Description |
|------------|-------------|
| `list` | List available template names |
| `new` | Create a new template definition |
| `edit` | Edit a template definition in `$EDITOR` |

#### `isx templates list`

    isx templates list [options]

| Option | Description |
|--------|-------------|
| `-v`, `--verbose` | Show source and description |

#### `isx templates new`

    isx templates new [<name>]

| Option | Description |
|--------|-------------|
| `--project` | Create in project-local directory (`.incus-spawn/images/`) |

#### `isx templates edit`

    isx templates edit <name>

### `isx tools`

List and inspect available tool definitions. Running bare `isx tools` defaults to `list`.

    isx tools <subcommand>

| Subcommand | Description |
|------------|-------------|
| `list` | List available tools |
| `show` | Show details of a tool definition |

#### `isx tools list`

    isx tools list [options]

| Option | Description |
|--------|-------------|
| `-v`, `--verbose` | Show source and description |

#### `isx tools show`

    isx tools show <name>

### `isx account`

Show or change which [credential account](#credential-accounts) an instance uses. Accounts themselves are configured with `isx init`. Running bare `isx account` defaults to `list`.

    isx account <subcommand>

| Subcommand | Description |
|------------|-------------|
| `list` | Configured accounts per credential, the default, and the instances pinned to each |
| `show <instance>` | The account the instance uses for each credential, and where it comes from |
| `set <instance> <ns>=<account>...` | Pin the instance to these accounts; takes effect on its next request |
| `unset <instance> <ns>...` | Remove the pins, so the instance follows the default again |

### `isx project`

Manage project templates defined by an `incus-spawn.yaml` file.

    isx project <subcommand>

| Subcommand | Description |
|------------|-------------|
| `create` | Create a project template from a parent base image |
| `update` | Update an existing project template (packages, repos, deps) |

#### `isx project create`

    isx project create <name> [options]

| Option | Description |
|--------|-------------|
| `--config <path>` | Path to `incus-spawn.yaml` (default: auto-detect from cwd) |

#### `isx project update`

    isx project update <name> [options]

| Option | Description |
|--------|-------------|
| `--config <path>` | Path to `incus-spawn.yaml` |

### `isx proxy`

Manage the MITM authentication proxy.

    isx proxy <subcommand>

| Subcommand | Description |
|------------|-------------|
| `start` | Start the proxy |
| `stop` | Stop the proxy (handles both systemd and manual processes) |
| `restart` | Restart the proxy service |
| `status` | Check if the proxy is running |
| `install` | Install as a systemd user service (auto-starts on boot) |
| `uninstall` | Stop and remove the systemd proxy service |
| `logs` | Follow the proxy log in real time |
| `dump` | Run a pass-through proxy for host-side traffic capture |

#### `isx proxy start`

    isx proxy start [options]

| Option | Description |
|--------|-------------|
| `--port <port>` | MITM TLS proxy port (default: `18443`) |
| `--health-port <port>` | Health check HTTP port (default: `18080`) |
| `--gateway-ip <ip>` | Incus bridge gateway IP (skips auto-detection; on Linux it must be a private (RFC 1918) or loopback address or the bridge's own, on macOS the host's VM-facing bridge address) |
| `--debug` | Log full request/response details |

#### `isx proxy dump`

    isx proxy dump [options]

| Option | Description |
|--------|-------------|
| `--port <port>` | Local HTTP port (default: `19080`) |

### `isx doctor`

Diagnose host, proxy, VM, and tunnel health; offers to fix problems found.

    isx doctor [options]

| Option | Description |
|--------|-------------|
| `--deep` | Run per-instance checks (DNS, TLS, resolv.conf) |
| `--bundle` | Collect findings and logs into a support archive (.tar.gz); implies `--deep` |

Findings are marked `✓` healthy, `⚠` worth a look, `✗` broken, or `·` for a note. Notes don't affect the exit code.

`--bundle` creates an archive to attach to a GitHub issue. Credentials are redacted before anything is written, and `REDACTIONS.txt` in the archive lists what was removed.

### `isx clean`

Remove cached data, state, or configuration.

    isx clean <subcommand> [options]

| Subcommand | Description |
|------------|-------------|
| `cache` | Remove cached downloads, registry blobs, and build caches |
| `state` | Remove VM state, logs, and appliance artifacts |
| `config` | Remove configuration, SSH keys, and CA certificate |
| `pool` | Reclaim space from the storage pool (failed builds, unused images, DNF cache); `--base-images` also removes the downloaded base images, which the next build downloads again |
| `all` | Remove cache, state, and configuration (does not touch Incus templates or instances) |

All subcommands accept these options:

| Option | Description |
|--------|-------------|
| `--dry-run` | Show what would be deleted without deleting |
| `--skip-confirmation` | Skip the confirmation prompt |

### `isx reset`

Return isx to a freshly installed state. Removes all instances and templates, cached images and downloads, the proxy service, the VM (macOS) and all configuration, including SSH keys and the CA certificate. It shows what will be removed and asks first. Run `isx init` afterwards to set up again.

    isx reset

| Option | Description |
|--------|-------------|
| `--skip-confirmation` | Skip the confirmation prompt |

### `isx vm`

Manage the incus-spawn VM appliance. macOS only.

    isx vm <subcommand>

| Subcommand | Description |
|------------|-------------|
| `start` | Start the VM (creates disk image on first run) |
| `stop` | Stop the VM (graceful shutdown) |
| `restart` | Stop and restart the VM (applies pending appliance updates) |
| `status` | Show VM status and system diagnostics |
| `resize` | Grow the VM data disk that backs the storage pool |
| `reset` | Wipe the VM data disk: deletes every instance, template and cached image |
| `console` | Follow VM serial console output |
| `check-version` | Check whether the running appliance matches the installed version |

#### `isx vm restart`

    isx vm restart [options]

Stops and restarts the VM, applying any pending appliance updates. Running containers will be stopped.

| Option | Description |
|--------|-------------|
| `-y`, `--yes` | Skip the confirmation prompt |

#### `isx vm resize`

    isx vm resize <size>

Size must be larger than the current disk (grow-only), e.g. `100G`.

| Option | Description |
|--------|-------------|
| `-y`, `--yes` | Skip the confirmation prompt |

#### `isx vm reset`

    isx vm reset [options]

Wipes the VM's storage pool, for when it's beyond repair (for example, orphaned subvolumes reported by `isx doctor`). Every instance, template and cached image is deleted; isx lists them and asks first, or needs `--yes` without a terminal. Rebuild your templates afterwards with `isx build --all`.

| Option | Description |
|--------|-------------|
| `-y`, `--yes` | Skip the confirmation prompt |

### `isx ask`

AI-powered help. Ask any question about incus-spawn (uses AI tokens).

    isx ask <question...>

| Option | Description |
|--------|-------------|
| `--with-templates` | Include template and tool definitions in the AI context |

### `isx mcp`

Serve isx to an agent on this machine over the Model Context Protocol (stdio). Started by the agent, not by hand:

    claude mcp add isx -- isx mcp

See [Delegating from an agent on your host](#delegating-from-an-agent-on-your-host-mcp) for what it offers and how to approve templates.

`isx mcp --caller-instance <name>` serves an instance branched with `--mcp-client` instead; the proxy runs it for each connection that instance makes to `https://mcp.isx.internal/mcp` ([a coordinator in an instance](#a-coordinator-in-an-instance)).

### `isx completion`

Print a shell completion script.

    isx completion [<shell>]

Supported shells: `bash` (default), `zsh`, `fish`.

| Option | Description |
|--------|-------------|
| `--install` | Print installation instructions instead of the script |
