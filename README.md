# isx

**Give your AI coding agents their own machines, not your credentials.**

An AI agent in your terminal can read your API keys, your GitHub token, your `~/.ssh` and every repo you have checked out.
Anything it writes, your IDE and build tools will happily execute.

isx onboards agents the way you'd onboard a new teammate:

- **A real machine of their own.**
  Each agent gets a full Linux workstation: its own filesystem, init system, networking and process tree.
  It can `dnf install`, run Docker Compose, use `strace` and nested containers, because it is a real system, not an app container.
  A hardware-isolated KVM virtual machine is one flag away when you want the strongest isolation.
- **Zero credential exposure.**
  API keys and tokens never enter the machine in any form.
  A proxy on your host adds them to the agent's requests as they leave, so `claude`, `pi`, `gh`, `git` and `curl` work unmodified inside.
  See [Credential Isolation](#credential-isolation).
- **Instant branches.**
  Branch a prepared template like you'd branch a repo: an instant copy-on-write clone.
  Use it, throw it away, branch again from a clean state.
- **Full autonomy, no babysitting.**
  Agents commit under their own identity and run without permission prompts.
  That is safe because the machine holds nothing worth stealing, and nothing the agent does reaches your host uninvited.

Runs on Linux and macOS, on your hardware.
Your code and credentials never leave the building, and everything an agent does can be traced back to it.

Agents are the headline, not the limit.
The same disposable machines are ideal for triaging untrusted patches, reproducing bug reports and testing on a clean system.

Built with [Quarkus](https://quarkus.io/) and [Tamboui](https://tamboui.dev/), powered by [Incus](https://linuxcontainers.org/incus/) system containers.
Written in Java, compiled to native binaries with [GraalVM](https://www.graalvm.org/).
isx was formerly known as incus-spawn.

## Quick Start

Requires Linux, or macOS 15 (Sequoia) or later; `isx init` sets up the rest.
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

Claude Code signs in with the Claude credential you set up in `isx init`; `isx account add claude` adds one later.
It never sees the credential: the proxy adds it on the way out.
If you did not install the services `isx init` offers, start them before building: `isx proxy start` on Linux, `isx vm start && isx proxy start` on macOS.

For another agent, replace `claude` with `codex`, `pi`, `gemini-cli`, `bob` or `copilot`; see [Agents](#agents).
Pi uses your Claude credential too, unless its `provider` names another API ([Pi](docs/AGENTS.md#pi-coding-agent)).
To give the agent a project, add `repos:` ([Declarative Repos](docs/TEMPLATES.md#declarative-repos)).
Its commits come back to your host with `git fetch` ([Git Remotes](#git-remotes)).

## Credential Isolation

The agent must be free to act, so its instance cannot hold anything worth stealing.
API keys and tokens never enter an instance in any form.
Instead, a proxy on your host (`isx proxy`) adds the real credentials to the agent's requests as they leave, and `curl`, `git`, `gh`, `claude` and `pi` work unmodified inside.

How it works:

- The instance's DNS resolves the intercepted domains to the proxy, and it trusts the proxy's CA, so the traffic takes that route on its own.
- The proxy replaces the placeholder with the real credential and forwards the request upstream over TLS.
- Inside, a tool sees only a placeholder such as `sk-ant-placeholder`.
  It satisfies the tool's local checks and authenticates against nothing.
- Nothing inside the instance yields a real credential: no API, no endpoint, no variable, no file.

What the proxy can sign in as:

| Credential | Who uses it in the instance | What you give `isx init` |
|------------|-----------------------------|--------------------------|
| Anthropic API key | Claude Code (`claude`), Pi (`pi`) | the key |
| Claude Pro or Max | Claude Code (`claude`), Pi (`pi`) | the token from `claude setup-token`; the proxy adds the OAuth bearer token |
| Vertex AI | Claude Code (`claude`), Pi (`pi`) | your project and region; the proxy translates each request and signs it with the host's GCP credentials, which never enter the instance |
| OpenAI API key | Codex CLI (`codex`), Pi (`pi`) | the key |
| Gemini API key | Gemini CLI (`gemini-cli`) | the key |
| IBM API key | Bob Shell (`bob`) | the key |
| GitHub token | `gh`, `git`, GitHub Copilot CLI (`copilot`) | the token |
| Atlassian API token | the `atlassian-mcp` tool, for Jira and Confluence | your account email and the token |
| A service of your own | whatever your tool YAML installs | its credential, declared in the YAML's `proxy:` block ([Proxy Credentials](docs/TOOLS.md#proxy-credentials)) |

A credential can have several accounts, for example a personal and a client Claude subscription, or two GitHub identities.
Each template or instance chooses which one it spends: see [Credential accounts](docs/CONFIGURATION.md#credential-accounts).
Git must use HTTPS URLs, not SSH; `gh` defaults to HTTPS.

The proxy must be running for every instance that has a network.
`isx init` installs it as a service, systemd on Linux and launchd on macOS, or you run `isx proxy start` in a terminal of its own.
isx checks that the proxy is reachable and compatible before a build, a branch or a shell.

The proxy also caches container image layers and build artifacts on the host, so the same dependency is never downloaded twice ([Caching](docs/CONFIGURATION.md#caching)).

Each start also gives the instance a random instance secret, at the path in `$ISX_INSTANCE_SECRET_FILE` (`/run/isx/instance-secret`, readable by the instance user).
It is not a credential for anything upstream.
It lets a service on the host tell instances apart by source address and secret together, so it is useless from any other instance.

A restart replaces the secret.
After a reboot isx did not perform, the instance has none until isx next starts it.

### Credentials reach the login environment

Each start replaces the placeholders with proof tokens, unique to the instance and the credential, such as `gho_isx_…` in `GH_TOKEN`.
The login profile exports them.
They stay the same across stops, starts and reboots, so a nested service you configured with one keeps working.

They reach exactly what runs in the login environment: a nested container gets none unless you pass one in.

[Credential proofs](docs/CREDENTIAL-PROOFS.md) says how to share or revoke them, and what to do after an upgrade.

### Git Configuration

With a GitHub token configured, every instance gets a ready-made `.gitconfig`: your GitHub name and email, the private `@users.noreply.github.com` address when there is one, and sensible defaults.

- **A dedicated agent account** (recommended): give `isx init` the token of a separate GitHub account, so the agent's commits and PRs are clearly its own.
- **Your personal account**: use your own token.
  To bring your aliases and settings along, mount `~/.gitconfig` as a [host resource](docs/TEMPLATES.md#host-resources); it takes precedence over the generated config.

Commit signing is not supported inside an instance yet ([#271](https://github.com/Sanne/incus-spawn/issues/271)).
To sign, fetch the changes to your host through [git remotes](#git-remotes) and rebase there.

## Branching

A branch is an instance made from a template, the way `git branch` makes a branch from a commit: an instant copy-on-write clone.
It has its own filesystem, and nothing it changes reaches the template or another branch.

The storage backend (btrfs, zfs or lvm) shares the unchanged data, so a branch costs only what it changes.
`isx init` creates a btrfs storage pool if there is none.

```
tpl-java  (stopped template, ~2GB)
  ├── fix-nasty-bug    (running, uses ~50MB extra)
  ├── review-pr-423    (running, uses ~30MB extra)
  └── experiment       (stopped, uses ~10MB extra)
```

Install packages, break things, and destroy the branch when done.
Sudo works without a password, and the shell sets the terminal title to `isx:<name>`, so you always know which instance you are in.

What a branch can have, each on request:

- GUI and audio passthrough: Wayland and PipeWire with GPU acceleration, Linux only.
- Restricted networking: see [Network Modes](#network-modes).
- An inbox: a read-only mount of a host directory, to hand files in.
- Other [credential accounts](docs/CONFIGURATION.md#credential-accounts) than its template's.
- Resource limits for CPU, memory and disk, detected from the host and overridable.

The TUI (`isx` with no arguments) is a Midnight Commander-style view of your templates and branches.
It has dialogs for branching, renaming and building, F3 for an instance's details and F9 for tool actions.

Without function keys, press the digit instead: 1 to 0 stand for F1 to F10, and Alt with a digit for Shift with that key.
On macOS, Alt is Option once the terminal sends Option as Meta.

A storage gauge and per-row disk usage show what is filling the pool.
Sizes are approximate (`~`): the base template carries the shared base image, and each branch only its own data.
Press C to reclaim space, or on macOS grow the disk with `isx vm resize <size>`.

Templates pre-install your baseline tools and repos.
Integrations plug in through the same tool system: VS Code Remote, JetBrains Gateway and agent skills.

### Network Modes

Each branch runs in one of three network modes:

| Mode | Flag | What it means |
|------|------|---------------|
| Full internet | *(default)* | Unrestricted access through NAT, signed in through the proxy |
| Proxy only | `--proxy-only` | Outbound traffic reaches only the proxy, enforced on the host, where root in the instance cannot lift it |
| Airgapped | `--airgap` | No network device at all, and no `pci` or `usb` passthrough |

### Git Remotes

An instance is isolated, and the work has to come back.
isx speaks git's remote helper protocol, so plain `git fetch`, `git push` and `git pull` work between a host repo and a repo in an instance:

```shell
# Inside the instance, the agent makes some commits...
# Back on the host:
git fetch fix-auth
git diff main..fix-auth/main    # review exactly what it did
git cherry-pick fix-auth/main   # take what you like
```

This is the intended review workflow.
You act on a specific, immutable commit, never on a live directory the agent can still change.
The [FAQ](docs/FAQ.md#why-cant-i-mount-a-host-directory-read-write-to-follow-agent-work-in-my-ide) says why there is deliberately no read-write project mount.

#### isx:// URLs

The remote uses the `isx://` URL scheme, where `~` expands to `/home/agentuser`:

```shell
git remote add fix-auth isx://fix-auth/~/quarkus
git fetch fix-auth
git diff main..fix-auth/main
```

An absolute path inside the instance follows a double slash, as in `isx://fix-auth//home/agentuser/quarkus`.
The instance must be running for git operations to work.

#### Automatic remotes

With `host-paths` in `~/.config/incus-spawn/config.yaml`, isx manages the remotes for you:

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

`isx branch` adds a remote named after the instance to each host repo that matches one in the instance.
SSH and HTTPS URLs of the same repo count as the same.
`isx destroy` removes the remote again.

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

[Agents](docs/AGENTS.md) has each one's parameters (model, reasoning effort, provider), agent skills, and the notes isx writes for an agent about its instance.

## Tools

Templates are made of tools.
`gh`, `herdr`, `maven-3`, `orca`, `podman`, `sshd`, the VS Code and IntelliJ remote backends, `tmux` and `zmx` ship with isx; `isx tools list -v` lists them all.
Your own are YAML files in `~/.config/incus-spawn/tools/` or a project's `.incus-spawn/tools/`: packages, commands, environment variables, TUI actions, and credentials the proxy adds for a service of yours.
See [Tools](docs/TOOLS.md).

## Delegating over MCP

An agent on your host, Claude Code say, can drive isx through `isx mcp`: branch instances from templates you approved, run builds and tests in them, and hand whole tasks to Claude Code or Codex inside one.
It is off by default, since it hands an agent that power; `isx init` offers to set it up.

[Delegating from an agent on your host](docs/MCP.md) says what such an agent can and cannot do, and how to run the coordinating agent in an instance of its own, branched with `isx branch --mcp-client`.
[Driving `isx mcp` from a program](docs/MCP-PROTOCOL.md) is the reference for a controller of your own.

## Configuration

`isx init` writes your settings and credentials to `~/.config/incus-spawn/config.yaml`; your own templates and tools go beside it, in `images/` and `tools/`.
A credential can have several accounts, a personal and a client Claude subscription say, and each template or instance chooses which one it spends.
[Configuration](docs/CONFIGURATION.md) covers the files, credential accounts and the download caches.

## Installation

isx runs on Linux, and on macOS 15 (Sequoia) or later.
Windows is not supported.

**Linux**: `isx init` installs [Incus](https://linuxcontainers.org/incus/) with your package manager.

**macOS**: `isx init` provisions a lightweight Linux VM with [vfkit](https://github.com/crc-org/vfkit), which starts when needed.
`isx vm start|stop|restart|status|resize|reset|console|check-version` manages it, and [its reference](docs/CLI.md#when-the-vm-stops-answering) says what to do when it stops answering.
Apple Silicon is supported.
Intel Macs get a binary built for the same macOS 15 minimum on a best-effort basis: it is released, but not verified on an Intel Mac.

On macOS isx runs containers only.
These are Linux-only:

- VM instances (`type: vm`, `--type vm`) and KVM passthrough (`--kvm`, `type: kvm`).
  isx refuses them on macOS before it creates anything, and branches a `type: kvm` template without KVM.
- GUI and audio passthrough (Wayland and PipeWire).
- The `overlay` mode for host resources: use `readonly` or `copy` instead.

**Behind TLS inspection** (a corporate proxy that re-signs HTTPS), isx trusts the host's CA store for its own downloads and for the proxy's upstream connections.

- On Linux that is the system bundle: `/etc/pki/ca-trust`, `/etc/ssl/certs`.
- On macOS it is `/etc/ssl/cert.pem` plus every certificate an administrator or MDM trusts for SSL in the System keychain, which is where inspection agents install their CA.
  A certificate an administrator denied there, or you marked "Never Trust" in your login keychain, is not trusted from any source, and neither is a chain holding it or a cross-signed copy.
- A CA trusted only in your login keychain is not used: trusting a CA for isx should take an administrator.
  Trust it system-wide, then restart the proxy:

```shell
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ca.pem
isx proxy restart
```

`isx doctor` lists the Keychain CAs isx trusts.

| Installed with ([Quick Start](#quick-start)) | Update with |
|----------------|-------------|
| Homebrew | `brew upgrade incus-spawn` |
| DNF: Fedora 43 and later, RHEL 9 and 10, CentOS Stream 9, AlmaLinux 9, Rocky Linux 9 (via EPEL) | `sudo dnf upgrade` |
| APT | `sudo apt upgrade` |
| `curl -fsSL https://isx.run \| sh` | the same command again |
| JBang | both `jbang app install` commands again |

The `curl` script installs the `isx` and `isx-proxy` native binaries to `~/.local/bin` (set `INSTALL_DIR` to change it), with no JVM needed.
JBang installs need both commands, and `jbang` must stay on your PATH.

To uninstall, run `uninstall.sh`:

- It keeps your configuration in `~/.config/incus-spawn/` and the caches in `~/.cache/incus-spawn/` unless you pass `--purge`.
- On macOS it keeps the VM's data disk, with every instance and template, unless you pass `--delete-instances`.
- `--binaries-only` removes only the binaries, to switch install channel, for example from a source build to Homebrew.

Shell completions are available for bash, zsh, fish and PowerShell via `isx completion <shell>`.

## CLI

| Command | Description |
|---------|-------------|
| [`isx`](docs/CLI.md#isx) | Launch the interactive TUI |
| [`isx init`](docs/CLI.md#isx-init) | One-time host setup |
| [`isx build`](docs/CLI.md#isx-build) | Build or rebuild template images |
| [`isx branch`](docs/CLI.md#isx-branch) | Create a CoW clone from a template or instance |
| [`isx shell`](docs/CLI.md#isx-shell) | Open a shell in an instance |
| [`isx start`](docs/CLI.md#isx-start) | Start an instance without opening a shell |
| [`isx stop`](docs/CLI.md#isx-stop) | Stop a running instance |
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
| [`isx doctor`](docs/CLI.md#isx-doctor) | Run health checks and offer to fix problems |
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
