# isx design

isx (incus-spawn) manages isolated Incus-based development environments.
They are system containers that behave like bare-metal Linux machines, designed for safely running untrusted AI agents and external reproducers in OSS projects.
This page is the index of the maintainer documentation: why isx is built the way it is.
Its mission and design philosophy are in [docs/dev/CHARACTER.md](docs/dev/CHARACTER.md).

## Why not application containers?

**Application containers**, the kind Docker and Podman run by default, isolate a single process with a minimal filesystem, no init system, and restricted networking.
This is ideal for deploying microservices but poor for development environments where you need:

- A real init system (systemd) for services like podman socket, sshd, or dbus
- Full networking: `ping`, `traceroute`, `tcpdump`, DNS resolution that works like a real machine
- Nested containers: running Podman/Docker inside the environment (Testcontainers, CI pipelines)
- Debugging tools: `strace`, `perf`, `gdb` — all require capabilities or sysctls that application containers strip
- GUI applications via Wayland passthrough with GPU acceleration, and audio via PipeWire

Incus **system containers** run a full Linux userspace with their own init, networking stack, and process tree.
They share the host kernel (like application containers) but present as a complete machine rather than a single process.
For stronger isolation, Incus also supports KVM virtual machines with a separate kernel, at the cost of a modest performance overhead.

The tradeoff: system containers are heavier than application containers (~200MB base vs ~5MB Alpine).
This is acceptable for development environments that persist for hours or days, and copy-on-write storage means clones are cheap regardless of base image size.

The choice is what a sandbox *is*: in isx, a branch of a template, as a container by default or a VM.
Other sandboxes for coding agents make it differently, and `docs/SANDBOXES.md`, "Compared with other sandboxes", says how.

## Goals

- **Secure by default**: isolated environments that prevent untrusted code from accessing host credentials or resources
- **Bare-metal experience**: containers with full init, real networking, working developer tools — developers shouldn't notice they're inside a container
- **Extensible without Java**: image definitions and tool installations defined in YAML; Java only needed for tools requiring programmatic logic
- **Ephemeral and cheap**: copy-on-write clones mean spinning up a new environment costs seconds and minimal disk space
- **Familiar**: CLI patterns inspired by git workflows (branch-name-style naming, auto-detection from cwd)
- **Idempotent setup**: `isx init` can be re-run safely at any time — each step checks whether its work is already done and skips without making changes, never disrupting running containers or reloading services unnecessarily.
  A re-run should not walk through every answer again.
  So a configured credential or MCP setup (#1292) asks one question, and Enter keeps it.
  Init completion is tracked by a versioned sentinel (`~/.config/incus-spawn/.init-complete` containing `INIT_VERSION`).
  When `INIT_VERSION` is bumped (new infrastructure step added), existing installations automatically re-run init on the next command.
  On Linux, init writes sysctl overrides (`/etc/sysctl.d/99-incus-spawn.conf`) to raise per-UID inotify limits (`max_user_instances=8192`, `max_user_watches=524288`), preventing inotify exhaustion when running many containers.
  Files init places under `/etc` are installed with `sudo install -m 0644`, not `sudo cp` from a `0600` temp file, so a re-run can read them back to check whether they changed.
  A file it cannot read counts as needing a rewrite, which repairs the `0600` files older releases wrote (#821).
  Init finishes in one run on a fresh Linux host, though its first step adds the user to `incus-admin` and a process gets its groups only at login (#1436).
  When the socket then refuses this process and the group database lists the user, `InitCommand.groupContinuation` runs the same command line again under `sg incus-admin`, marked by `ISX_INIT_CONTINUED`, and exits with its code.
  The probe connects afresh rather than through `IncusClient`, which keeps a failed connect for the rest of the process, so a daemon still starting does not decide Storage & Network's own check.
  The continuation skips the banner and the first step, so the counter reads on, and its closing box says the group needs a new login or `newgrp` elsewhere.
  An inner `sg <primary group>` gives back the primary group the outer one took, so the files init writes stay in the user's group, with `incus-admin` kept as a supplementary one.
  Without `sg`, which Arch-family distros removed, Storage & Network still stops and asks for a new login.
  Talking to the socket through `sudo` was rejected: it needs a relay such as `socat`, which a fresh host may not have.
  `isx init --plan` (#2073) says what a run would change and who must do each part (nobody, sudo, a secret typed by a person, or a person's approval) without changing anything.
  Each step is described once, its header, work and planner together (`InitCommand.InitStep`), and the plan lists the run's own step lists, so it cannot name a step the run does not take.
  A planner reads what the run's checks read and names the commands the run would run, from the same constants; what only root can read, such as firewall rules where polkit refuses or the btrfs sudoers rule, is reported as not checked rather than guessed, with why and the sudo commands that check it, and the plan never runs sudo (Sanne on #2091).
  The plan serves both a cautious person and an agent driving the install, which must know which steps are its own (#2072).
  `isx init --no-sudo` (#2074) turns the plan's sudo changes into one root script for the person and runs nothing as root itself; a process-wide guard (`Privilege`) refuses any command that elevates.
  The script is the plan's `sudo_script`, so what the person reads is what the plan reported, and it records what it ran in `/var/lib/isx/init-applied` for the checks only root could make.
  One script serves a fresh host: the redirect reads the bridge's gateway as the script runs, rather than waiting for a second round trip after Incus exists.
  A step that finds root work as it runs, such as the redirect of a bridge the run moved off a VPN's subnet, hands it over as a new script (`InitCommand.WaitingOnPerson`) rather than finishing on a proxy no container reaches; the bridge's old gateway is kept on disk until the script's record shows the redirect moved after it, so a rerun or a newer script cannot lose the move.
  The Template Search Paths step uses `gh` to auto-detect the user's GitHub identity and offer to fork/clone `incus-spawn-templates` — every failure path (no gh, no auth, API error) degrades gracefully to the existing manual flow

## Where the design lives

Each area has its own page under [`design/`](design/), listed below with its sections.
A citation names the page and a heading or a bold lead-in, such as design/proxy.md "Host routes".
Measurements, with the conditions they were taken under, are in [docs/dev/PERFORMANCE-NOTES.md](docs/dev/PERFORMANCE-NOTES.md).

- [Modules and native images](design/native-image.md)
  - Module Structure
  - Every install channel ships both binaries
  - Native image CPU baseline
  - aarch64: `+aes`
  - Why the CLI takes the same flag
  - Profile-guided optimization
  - Proxy garbage collector per platform
  - The minimum macOS is set by the build
  - Intel Macs are not shipped
  - The install script verifies its downloads
  - Build-time initialization must not capture host paths
  - Regular expressions are compiled once
  - jjq rather than Jackson
  - Considered and deferred
- [Templates, tools and builds](design/templates.md)
  - Template Image Hierarchy
  - Tool System
  - YAML tools vs a full plugin system (Packer, Ansible, etc.)
  - Hardcoded built-in tool list vs classpath scanning
  - Host Repo Refresh
  - Build Flow
  - A template with no definition here is never rebuilt
  - Agent Context File
  - Which base image an instance runs
  - Fedora-specific
- [Instances: branching, configuration and lifecycle](design/instances.md)
  - Container Model
  - System containers vs application containers
  - Container vs VM Trade-off
  - No capability dropping (`lxc.cap.drop =`)
  - Branching
  - Why a branch is configured in one write
  - Why nothing is pushed into an instance just before it starts
  - Resource Limits (Adaptive)
  - Container Configuration
  - Filesystem Isolation
  - SSH Key Management
  - Remote IDE Access
  - GUI and Audio Passthrough
  - Metadata Tracking
  - Lifecycle locking
- [Terminal output and the TUI](design/terminal.md)
  - Terminal Output Visual Language
  - Output for scripts: `--format=table|plain|json`
  - Shell completion asks the running binary
  - Keeping a long-lived TUI current
  - Warnings while the TUI owns the terminal
  - Terminals the TUI cannot assume
- [Network](design/network.md)
  - Network Modes
  - DNS: static resolv.conf + bridge dnsmasq
- [The MITM TLS proxy](design/proxy.md)
  - Credential isolation via MITM TLS proxy
  - Auth & Security: MITM TLS Proxy
  - Credential Isolation
  - The proxy holds no configuration of its own
  - Host routes
  - Vertex AI: container in Vertex mode vs standard mode
- [Credential accounts and proofs](design/accounts.md)
  - Credential accounts
  - The help assistant (`isx ask`)
- [Host resources](design/host-resources.md)
  - Host Resources
- [Storage and copy-on-write](design/storage.md)
  - Storage and COW
- [Git: clones, remotes and the remote helper](design/git.md)
  - Repo Cloning and Reference Optimization
  - Clone selection and future fetches
  - Git Remote Helper
  - Git remote helper: bash + Java split
  - Auto-remote: stateless cleanup vs state tracking
  - Auto-remote: opt-in via configuration
- [MCP and external orchestrators](design/mcp.md)
  - MCP server: delegating work to isx instances
  - Agents driving isx over MCP
  - External orchestrators: owners, start, stop and destroy
  - Considered and deferred
- [macOS, the appliance and VMs](design/macos-and-vms.md)
  - Incus Daemon Connection
  - macOS vsock robustness
  - Where the vsock leak comes from
  - The macOS VM outlives whatever started it
  - VM Appliance
  - The VM image's boot chain (#1255)
  - The VM base image's own kernel (#1255)
  - Considered and deferred
- [Testing](design/testing.md)
- [Doctor, support bundles and secret scanning](design/doctor.md)
  - Doctor: a finding that is neither healthy nor broken
  - Doctor and build remediations act through isx, never name an `incus` command
  - Support bundles: redaction by construction
  - Secret Scanning in CI
- [The upstream watch](design/upstream.md)
  - The model: a manifest, an engine, two workflows and a merge gate
  - The items
  - Threat model
  - Why not Sanne's own token
  - Why not approving from Actions
  - Why not GitHub auto-merge
  - Why not Dependabot
  - Why not one engine copy per repo
  - What is never done
