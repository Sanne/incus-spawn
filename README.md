# isx

**Give your AI coding agents their own machines — not your credentials.**

You're about to hand an AI agent a terminal.
On your laptop, that terminal can read your API keys, your GitHub token, your `~/.ssh`, and every repo you have checked out — and anything it writes, your IDE and build tools will happily execute.

isx onboards agents the way you'd onboard a new teammate:

- **A real machine of their own.**
  Each agent gets a full Linux workstation — its own filesystem, init system, networking, and process tree.
  It can `dnf install`, run Docker Compose, use `strace` and nested containers — everything works, because it *is* a real system, not an app container.
  Hardware-isolated KVM virtual machines are one flag away when you want the strongest isolation.
- **Zero credential exposure.**
  API keys and tokens never enter the environment in any form.
  A host-side TLS proxy injects real credentials upstream, so `claude`, `pi`, `gh`, `git`, and `curl` work unmodified inside — yet no credential is ever stored there.
  See [Credential Isolation](#credential-isolation).
- **Disposable in seconds.**
  Branch a prepared template like you'd branch a repo — instant copy-on-write clones.
  Use them, throw them away, branch again from a clean state.
- **Full autonomy, no babysitting.**
  Agents commit under their own identity and run without permission prompts — safe to let run, because the blast radius is the branch.

Runs on Linux and macOS, on your hardware.
Your code and credentials never leave the building.
Everything an agent does can be traced back to it — and nothing it does arrives uninvited.

Agents are the headline, not the limit: the same disposable machines are ideal for triaging untrusted patches, reproducing bug reports, and testing on a clean system — anything you'd rather not run on your host.

Built with [Quarkus](https://quarkus.io/) and [Tamboui](https://tamboui.dev/), powered by [Incus](https://linuxcontainers.org/incus/) system containers.
Written in Java, compiled to native binaries with [GraalVM](https://www.graalvm.org/).
*(isx was formerly known as incus-spawn.)*

## Quick Start

Requires **Linux, or macOS 15 (Sequoia) or later**; `isx init` sets up the rest.
[Installation](#installation) has the details.

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
```

A template is a machine image you build once: an OS, tools and repos.
This one adds Claude Code to the built-in `tpl-minimal`.
Save it as `~/.config/incus-spawn/images/agent.yaml`, or run `isx templates new tpl-agent` and paste it over the skeleton that opens in your editor:

```yaml
name: tpl-agent
description: Claude Code on the base OS
parent: tpl-minimal
tools:
  - claude
default-action: claude
```

```shell
# Build it (builds missing parent images first)
isx build tpl-agent

# Branch it: an instant copy-on-write machine of its own, with Claude Code open inside
isx branch my-first --from tpl-agent

# The interactive TUI: your templates and branches
isx
```

Claude Code signs in with the Claude credential set up in `isx init` (`isx account add claude` adds one later).
It never sees the credential: the proxy adds it on the way out (see [Credential Isolation](#credential-isolation)).
If you did not install the services `isx init` offers, start them before building: `isx proxy start` on Linux, `isx vm start && isx proxy start` on macOS.

Next, for another agent, replace `claude` with `codex`, `pi`, `gemini-cli`, `bob` or `copilot` (see [Agents](#agents)).
To give the agent a project, add `repos:` ([Declarative Repos](docs/TEMPLATES.md#declarative-repos)).
Its commits come back to your host with `git fetch` ([Git Remotes](#git-remotes)).

[Installation](#installation) says how to update, and what differs on macOS.

## Credential Isolation

**API keys and tokens never enter containers in any form.**
A host-side MITM TLS proxy (`isx proxy`) provides completely transparent authentication:

- The proxy uses bridge-level DNS overrides and a custom CA certificate so containers transparently route intercepted domains through the proxy
- The proxy terminates TLS, injects real authentication headers, and forwards to the real upstream over TLS — tools (`curl`, `git`, `gh`, `claude`, `pi`) work unmodified inside containers
- Containers hold only placeholder values (e.g. `sk-ant-placeholder`) that satisfy tools' local auth checks; placeholders cannot authenticate against any real service
- **Vertex AI support**: the proxy transparently translates requests to Vertex AI format — no GCP credentials enter the container
- **Claude Pro/Max support**: authenticate via `claude setup-token`; the proxy injects the OAuth Bearer token transparently
- **Several accounts per credential**: configure e.g. a personal and a client Claude subscription, or two GitHub identities, and choose per template or per instance which one is spent -- see [Credential accounts](docs/CONFIGURATION.md#credential-accounts)
- **HTTPS only**: Git operations must use HTTPS URLs (not SSH).
  `gh` defaults to HTTPS; for `git clone`, use `https://github.com/...`

There is no API, endpoint, environment variable, or file that code inside the container can access to obtain real credentials — the injection happens entirely outside the trust boundary.

Each instance also gets a random **instance secret** every time isx starts it, at the path in `$ISX_INSTANCE_SECRET_FILE` (`/run/isx/instance-secret`, readable by the instance user).
It is not a credential for anything upstream: it lets a service on the host tell instances apart by source address *and* secret together, so it is useless from any other instance.
A restart replaces it; a reboot isx did not perform leaves the instance without one until isx next starts it.

The proxy must be running for non-airgapped containers.
`isx init` can install it as a systemd user service, or run `isx proxy` in a separate terminal.
The CLI verifies proxy reachability and version compatibility before builds, branches, and shell access.

The proxy also caches container image layers and build artifacts on the host — the same dependency is never downloaded twice (see [Caching](docs/CONFIGURATION.md#caching)).

### Credentials reach the login environment

Each start replaces the placeholders with **proof tokens** unique to the instance and credential namespace (e.g. `gho_isx_…` in `GH_TOKEN`), exported by the login profile.
These tokens stay the same across stops, starts and reboots, so a nested service explicitly configured with one keeps working.
They reach exactly what runs in the login environment: a nested container gets none unless you pass one in.
[Credential proofs](docs/CREDENTIAL-PROOFS.md) says how to share or revoke them, and what to do after an upgrade.

### Git Configuration

With a GitHub PAT configured, containers get a ready-made `.gitconfig` with your GitHub name and email (the private `@users.noreply.github.com` address when there is one) and sensible defaults.

- **Dedicated agent account (recommended)**: give `isx init` the PAT of a separate GitHub account, so the agent's commits and PRs are clearly its own.
- **Your personal account**: use your own PAT.
  To bring your aliases and settings along, mount `~/.gitconfig` as a [host resource](docs/TEMPLATES.md#host-resources); it takes precedence over the generated config.

**Commit signing** isn't supported inside containers yet.
To sign, fetch the changes to your host through [git remotes](#git-remotes) and rebase there ([#271](https://github.com/Sanne/incus-spawn/issues/271)).

## Branching

Like `git branch`, branching creates an instant copy-on-write clone of any template.
Each branch has its own independent filesystem -- changes in one branch cannot affect the template or any other branch.
The storage backend (btrfs/zfs/lvm) deduplicates unchanged data automatically, so branches are instant to create and only consume disk space for their own modifications.
`isx init` automatically creates a btrfs storage pool if needed.

```
tpl-java  (stopped template, ~2GB)
  ├── fix-nasty-bug    (running, uses ~50MB extra)
  ├── review-pr-423    (running, uses ~30MB extra)
  └── experiment       (stopped, uses ~10MB extra)
```

You can install packages, break things, and destroy a branch when done.
The template and other branches are completely unaffected.
Sudo works without a password, and shell sessions set the terminal title to `isx:<containername>` so you always know which environment you're in.

Branches can optionally enable GUI/audio passthrough (Wayland + PipeWire with GPU acceleration, Linux only), restricted networking, an inbox mount to share files read-only from the host, or different [credential accounts](docs/CONFIGURATION.md#credential-accounts) than their template's.
Resource limits (CPU, memory, disk) are auto-detected from the host but can be overridden.
The interactive TUI (`isx` with no arguments) provides a Midnight Commander-style interface with modal dialogs for branching, renaming, and building, plus F3 detail views and F9 tool actions.

The TUI shows a storage gauge and per-row disk usage so you can see what's filling the pool.
Sizes are approximate (marked `~`): the base template carries the shared base image, while CoW branches show only the data unique to them.
Press **C** to reclaim space, or on macOS grow the disk with `isx vm resize <size>`.

Templates pre-install your baseline tools and repos, and integrations plug in through the same tool system: VS Code Remote, JetBrains Gateway, and agent skills.

### Network Modes

Each branch runs in one of three network modes:

| Mode | Flag | Description |
|------|------|-------------|
| **Full internet** | *(default)* | Unrestricted network access via NAT, auth via MITM proxy |
| **Proxy only** | `--proxy-only` | Outbound traffic restricted to the MITM proxy, enforced on the host where root in the instance cannot lift it |
| **Airgapped** | `--airgap` | No network device at all, and no `pci`/`usb` passthrough |

### Git Remotes

Containers created with `isx branch` are isolated environments, but you need a way to get your changes back. isx integrates with git's native remote helper protocol so you can use standard `git fetch`, `git push`, and `git pull` between host repos and container repos:

```shell
# Inside the container, the agent makes some commits...
# Back on the host:
git fetch fix-auth
git diff main..fix-auth/main    # review exactly what it did
git cherry-pick fix-auth/main   # take what you like
```

This is the intended review workflow: you always act on a specific, immutable commit — never on a live directory the agent can still modify (the [FAQ](docs/FAQ.md#why-cant-i-mount-a-host-directory-read-write-to-follow-agent-work-in-my-ide) says why there is deliberately no read-write project mount).

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

## Templates

A template is a machine image you prepare once, in YAML: an OS, packages, tools and repositories.
Templates inherit from one another, and building one builds any missing parent first:

```yaml
name: tpl-maven
description: JDK + Maven
parent: tpl-minimal
packages:
  - java-25-openjdk-devel
tools:
  - maven-3
```

`tpl-minimal`, on a Fedora base, is built in.
Your own templates go in `~/.config/incus-spawn/images/`, or a project's `.incus-spawn/images/`.
`isx templates new <name>` opens a skeleton listing every field, and `isx templates edit <name>` validates what you save.

```shell
isx build tpl-maven                  # builds missing parents first
isx build tpl-maven --with-parents   # rebuilds it and its parents from scratch
isx build --out-of-sync              # rebuilds what changed since it was built
```

[Templates](docs/TEMPLATES.md) lists every field: repositories cloned at build time, the working directory and default action, host resources, base image updates and VM templates.

## Agents

An agent is a tool in a template's `tools:` list, set up for unattended work.
It signs in through the proxy with an account you configured in `isx init`:

| Agent | Tool | Signs in with |
|-------|------|---------------|
| Claude Code | `claude` | an Anthropic API key, Claude Pro/Max, or Vertex AI |
| Codex CLI | `codex` | an OpenAI API key |
| Pi | `pi` | Claude's credentials, or the OpenAI key |
| Gemini CLI | `gemini-cli` | a Gemini API key |
| Bob Shell | `bob` | an IBM API key |
| GitHub Copilot CLI | `copilot` | the GitHub token `gh` uses |

[Agents](docs/AGENTS.md) has each one's parameters (model, reasoning effort, provider), agent skills, and the notes isx writes for an agent about its machine.

## Tools

Templates are made of tools.
`gh`, `maven-3`, `podman`, `sshd`, the VS Code and IntelliJ remote backends, `tmux` and `zmx` ship with isx; `isx tools list -v` lists them all.
Your own are YAML files in `~/.config/incus-spawn/tools/` or a project's `.incus-spawn/tools/`: packages, commands, environment variables, TUI actions, and credentials the proxy adds for a service of yours.
See [Tools](docs/TOOLS.md).

## Delegating over MCP

An agent on your machine, Claude Code say, can drive isx through `isx mcp`: branch instances from templates you approved, run builds and tests in them, and hand whole tasks to Claude Code or Codex inside one.
It is experimental and off by default; `isx init` offers to set it up.
[Delegating from an agent on your host](docs/MCP.md) says what such an agent can and cannot do, and how to run the coordinating agent in an instance of its own.

## Configuration

`isx init` writes your settings and credentials to `~/.config/incus-spawn/config.yaml`; your own templates and tools go beside it, in `images/` and `tools/`.
A credential can have several accounts, a personal and a client Claude subscription say, and each template or instance chooses which one it spends.
[Configuration](docs/CONFIGURATION.md) covers the files, credential accounts and the download caches.

## Installation

isx runs on Linux, and on macOS 15 (Sequoia) or later; Windows is not supported.
On Linux, `isx init` installs [Incus](https://linuxcontainers.org/incus/) with your package manager.
On macOS it provisions a lightweight Linux VM with [vfkit](https://github.com/crc-org/vfkit), which starts when needed and is managed with `isx vm start|stop|restart|status|resize|reset`.
Apple Silicon is supported.
Intel Macs get a binary built for the same macOS 15 minimum on a best-effort basis: it is released, but not verified on an Intel Mac.

On macOS isx runs containers only.
VM instances (`type: vm`, `--type vm`), KVM passthrough (`--kvm`, `type: kvm`), GUI and audio passthrough (Wayland + PipeWire) and the `overlay` mode for host resources are Linux-only: use `readonly` or `copy` host resources instead. isx refuses a VM or `--kvm` on macOS before it creates anything, and branches a `type: kvm` template without KVM.

Behind TLS inspection (a corporate proxy that re-signs HTTPS), isx trusts the host's CA store for its own downloads and for the proxy's upstream connections.
On Linux that is the system bundle (`/etc/pki/ca-trust`, `/etc/ssl/certs`).
On macOS it is `/etc/ssl/cert.pem` plus every certificate an administrator or MDM trusts for SSL in the System keychain, which is where inspection agents install their CA; a root CA an administrator denied there is not trusted from any source (a denied intermediate or server certificate, and a certificate denied only in a login keychain, are still accepted: #1552).
A CA trusted only in your login keychain is not used, because trusting a CA for isx should take an administrator: trust it system-wide with `sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ca.pem`, then `isx proxy restart`.
`isx doctor` lists the Keychain CAs isx trusts.

| Installed with ([Quick Start](#quick-start)) | Update with |
|----------------|-------------|
| Homebrew | `brew upgrade incus-spawn` |
| DNF: Fedora 43+, RHEL 9–10, CentOS Stream 9, AlmaLinux 9, Rocky Linux 9 (via EPEL) | `sudo dnf upgrade` |
| APT | `sudo apt upgrade` |
| `curl -fsSL https://isx.run \| sh` | the same command again |
| JBang | both `jbang app install` commands again |

The `curl` script installs a self-contained native binary to `~/.local/bin/isx` (set `INSTALL_DIR` to change it), with no JVM needed.
To uninstall it, run `uninstall.sh`: it keeps the caches in `~/.cache/incus-spawn/` unless you pass `--purge`, and on macOS the VM's data disk, with every instance and template, unless you pass `--delete-instances`.
To switch install channel, e.g. from a source build to Homebrew, `uninstall.sh --binaries-only` removes only the binaries and keeps everything else.
JBang installs need both commands, and `jbang` must stay on your PATH.

Shell completions are available for bash, zsh and fish via `isx completion <shell>`.

## CLI

| Command | Description |
|---------|-------------|
| [`isx`](docs/CLI.md#isx) | Launch the interactive TUI |
| [`isx init`](docs/CLI.md#isx-init) | One-time host setup |
| [`isx build`](docs/CLI.md#isx-build) | Build or rebuild template images |
| [`isx branch`](docs/CLI.md#isx-branch) | Create a CoW clone from a template or instance |
| [`isx shell`](docs/CLI.md#isx-shell) | Open a shell in an instance |
| [`isx run`](docs/CLI.md#isx-run) | Run an action on an instance |
| [`isx destroy`](docs/CLI.md#isx-destroy) | Destroy an instance |
| [`isx list`](docs/CLI.md#isx-list) | List instances, for people or for scripts |
| [`isx update-base`](docs/CLI.md#isx-update-base) | Check for and install base image updates |
| [`isx update-all`](docs/CLI.md#isx-update-all) | Update packages and repos in all templates |
| [`isx templates`](docs/CLI.md#isx-templates) | Manage template definitions |
| [`isx tools`](docs/CLI.md#isx-tools) | List and inspect available tool definitions |
| [`isx account`](docs/CLI.md#isx-account) | Add credentials, and show or change the accounts an instance uses |
| [`isx project`](docs/CLI.md#isx-project) | Manage project templates |
| [`isx proxy`](docs/CLI.md#isx-proxy) | Manage the MITM authentication proxy |
| [`isx doctor`](docs/CLI.md#isx-doctor) | Diagnose host, proxy, VM, and tunnel health |
| [`isx clean`](docs/CLI.md#isx-clean) | Remove cached data, state, or configuration |
| [`isx reset`](docs/CLI.md#isx-reset) | Reset to a clean slate |
| [`isx vm`](docs/CLI.md#isx-vm) | Manage the VM appliance (macOS only) |
| [`isx ask`](docs/CLI.md#isx-ask) | AI-powered help |
| [`isx mcp`](docs/CLI.md#isx-mcp) | Serve isx to a local agent over MCP |
| [`isx completion`](docs/CLI.md#isx-completion) | Print shell completion script |

Use `isx <command> --help` for detailed options on any command.
Scripts read `--format=plain` or `--format=json`, which stay stable: see [Scripting isx](docs/CLI.md#scripting-isx).

## Further reading

- [FAQ](docs/FAQ.md): URL clicks in Codex, the clipboard, and why there is no read-write project mount.
- [Compared with other sandboxes](docs/SANDBOXES.md): why an isx instance is a whole machine, and where nono, Claude Code's sandbox runtime, NVIDIA OpenShell, dev containers, Docker Sandboxes and a VM you manage sit beside it.
- [What differs from a stock Fedora install](docs/FEDORA.md): the few deliberate deviations, mostly so that every runtime trusts the proxy's CA.
- [Vision](docs/VISION.md): where isx is going, from a container manager to mission control for parallel coding agents.
- [DESIGN.md](DESIGN.md): the architecture and why each choice was made.
