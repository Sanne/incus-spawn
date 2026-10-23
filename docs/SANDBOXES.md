# Compared with other sandboxes

"Sandbox" names three different things, and the right one depends on what you need.
This page says what each kind is, where isx sits, and for each of the other tools when it is the better fit.

- **A sandboxed process.**
  The agent runs as a regular process in your own operating system, as your user.
  It is confined to a list of allowed files, hosts and operations that you configure.
- **A sandboxed workload.**
  The agent runs in an application container with a fixed set of tools.
  It cannot reshape its environment the way it could on a real system.
- **A sandboxed workstation.**
  The agent gets a complete Linux system of its own, to investigate on without constraints: it installs tools, runs services and builds what is missing, as the research requires.
  Once the work is done the system is considered tainted and discarded.

| Sandbox | Kind | The agent can reshape | Where it runs | Isolation |
|---|---|---|---|---|
| nono | process | nothing: the profile is fixed at launch | your OS, as your user | Landlock, bubblewrap or Seatbelt, plus a proxy |
| Claude Code's sandbox runtime | process | nothing: paths and domains are listed in advance | your OS, as your user | Landlock, bubblewrap or Seatbelt, plus a proxy |
| NVIDIA OpenShell | workload | nothing once it runs | an application container | no NIC, no capabilities, Landlock, seccomp |
| Dev containers | workload | packages, if the image allows | a Docker container | an iptables firewall inside the container |
| Docker Sandboxes | workstation | everything, with sudo | its own microVM | its own kernel |
| A VM you manage | workstation | everything | a VM | its own kernel |
| isx | workstation | everything, as root | a branch of a template: a system container or a VM | a kernel namespace, or its own kernel with `--vm` |

isx is in the third category.
An instance is a branch of a template, in the way a git worktree is a branch of a repository: a second working copy that shares everything unchanged and costs only what it changes.
Here the working copy is the whole system, toolchain, dependencies and services included.

That is what makes it cheap to create one per task and throw it away afterwards.
A template declares the tools and the repositories, and is built once: the projects are cloned and primed, dependencies fetched and builds warmed.
Every branch of it starts ready to work, and every branch is the same.

Day to day a workstation is one command away.
`isx branch` creates one instantly, `isx shell` or the TUI gets you in, your editor attaches over SSH, an `isx://` git remote fetches the agent's work, and `isx destroy` ends it.

## Why full system containers?

An isx instance is a complete system, because it is built for a different job than an application container.

An application container, the kind Docker and Podman run by default, is built for shipping applications: a minimal filesystem, one process, fast startup.

An isx instance is a workstation: a full [Incus](https://linuxcontainers.org/incus/) system container, or a KVM virtual machine with `--vm`.
It has its own init system, real networking (`ping`, `strace`, nested Podman or Docker), and GUI and audio passthrough on Linux.

The template pre-installs your baseline tools and repositories, but what the agent gets is a real Linux system.
It can `dnf install`, `pip install`, build from source or run Docker Compose, just as on a workstation.

This matters for agents in particular: what you hand an agent is an investigation, and nobody knows at the start what tools it will need.
An agent on an isx branch installs a tracer, starts a service or writes a tool of its own as it goes.

It works exactly as it would on a developer workstation, because that is what it has.
If it wrecks the instance, you destroy the branch and the next one is instant.

For the strongest isolation, a KVM virtual machine (`--vm`) adds a separate kernel and hardware-level separation.
The sections below show how this choice plays out against the other sandboxes for agents.

## A sandboxed process

The agent is an ordinary process in your operating system, running as your user, confined to the files, hosts and operations you allow.
The confinement is Landlock or bubblewrap on Linux and Seatbelt on macOS, plus a local proxy for the allowed hosts.

There is nothing to install and nothing to boot, and the agent works with the tools and files that are already on your host.
Codex's built-in sandbox works this way too.

### nono

[nono](https://github.com/nolabs-ai/nono) is a sandboxed process with a profile per agent and a broker that keeps credentials out of the agent's process.

- **Design choice.**
  The agent has read and write access to the current directory and no access to anything else on disk or on the network unless its profile allows it.
  Profiles are published per agent and can be extended.
- **What follows.**
  A broker can run the commands the agent calls (`git`, `gh`, `kubectl`) in separate sandboxes with their own credentials, so a token is available to one command and never to the agent process.
- **Written in** Rust, under Apache-2.0.

### Claude Code's sandbox runtime

[sandbox-runtime](https://github.com/anthropics/sandbox-runtime) is the implementation behind Claude Code's `/sandbox`.

- **Design choice.**
  The runtime denies writes except to listed paths, denies network except to listed domains via a proxy, and allows reads everywhere except listed paths.
  It always denies writes to `.git/hooks`, `.mcp.json` and the other files a session could use to run code in a later session.
- **Scope.**
  `/sandbox` applies this to Bash commands only.
  Running `claude` itself under the runtime applies it to the file tools, hooks and MCP servers as well.

**Where isx differs.**
A sandboxed process has its world fixed before it starts: the paths and hosts in the profile, the tools already on your host.
Landlock rules can be tightened after launch, never loosened.

So an agent that needs one more path, a package or a running service has to be stopped, given a wider profile and started again.
The package still has to be installed on your host.

In an isx branch the world is the agent's to change.
It installs tools, writes its own, starts services and runs containers as the task turns out to need them, and your host is untouched.

isx asks for more setup once, `isx init` and a template build.
After that a branch is one command away and instant, primed with the project and its dependencies, with a shell, an editor and a git remote already attached.

The two also answer "what if a tool is compromised" differently.

- **nono** is fine-grained: each command runs in its own sandbox and a credential works only for the endpoints its profile lists.
  This needs the profile to anticipate each command and endpoint.
- **isx** is coarser and simpler: assume the whole instance is compromised, give it only credentials whose misuse you can live with, and make the instance disposable.
  A read-only GitHub account on a review template is such a credential.

Use a sandboxed process when the agent edits your working tree with the tools you already have.
Use isx when the agent has to work out what it needs as it goes.

## A sandboxed workload

The agent runs in an application container with a fixed set of tools.
It may be allowed to add a package, but it cannot reshape the environment the way it could on a real system: there is no init system, so no services, and privileges are reduced.

### NVIDIA OpenShell

[OpenShell](https://github.com/NVIDIA/OpenShell) is a governed workload with the same credential model as isx and a finer egress policy.

- **Where it agrees with isx.**
  On credentials: the sandbox never holds a real key, a trusted process outside it terminates TLS, injects the credential on approved requests, and identifies the caller in a way the sandbox cannot forge.
  The two differ in what is sandboxed.
- **Design choice.**
  OpenShell confines a workload: an application container with no network device, one non-root user, no Linux capabilities, and a Landlock filesystem policy fixed at startup that leaves `/usr` and `/etc` read-only.
  Seccomp hands every TCP connect and DNS lookup to a supervisor that checks it against a policy naming hosts, HTTP methods and the calling program.
- **What follows.**
  That is a finer-grained egress policy than isx's network modes.

**Where isx differs.**
isx hands the agent a workstation, because the tools an investigation needs are unknown at the start.
What that looks like in practice:

- A test that only fails under load: install `bpftrace` and write a probe.
- A slow native build: install async-profiler and compare flame graphs.
- A Testcontainers suite: run podman inside.
- A flaky remote integration: write a mock server, point `/etc/hosts` at it, capture the exchange with `tcpdump`.

None of these is possible in OpenShell:

- There is no root to install anything.
- `bpf` and `perf_event_open` need capabilities the workload does not have.
- `/etc` is read-only.
- Nested containers need namespaces and a NIC.
- Nothing about the sandbox can change once it runs, so each step means a person rebuilds the image and the agent's context is lost.

In isx the agent has root on a system with systemd, nested containers, debuggers and a real NIC.
If it wrecks the instance, you destroy the branch and the next one is instant.
Pick OpenShell to run an agent as a governed workload in a fleet; pick isx to let one work the way a good engineer does.

### Dev containers

A [dev container](https://code.claude.com/docs/en/devcontainer) is a Docker container managed by an editor, with the project directory bind-mounted read-write and the agent installed in the image.

- **Design choice.**
  Claude Code's reference configuration adds an iptables firewall inside the container that denies egress except to listed domains, so the agent can run without permission prompts.
  It is a good way to give a team one identical environment.
- **What follows.**
  Whether the agent can add packages, or undo that firewall, depends on the image.
  The common base images give the user full sudo, which also lets it flush the rules; the reference limits sudo to re-running the firewall script and so allows neither.
- **The trade-off.**
  The filter lives inside the boundary, so the agent's freedom and the firewall's integrity trade off against each other.

**Where isx differs.**
A dev container is an application container: no init system, so no services; capabilities dropped, so nested containers and tracing need privileged flags; and the workspace the agent writes is your host's directory.

The container is also yours to keep, rebuilt from the Dockerfile rather than discarded after the task.
Claude Code's own docs note that the credentials live inside it, in `~/.claude`, where an unattended session can read them.

Pick a dev container to give a team one environment with the editor attached; pick isx when the agent should reshape its environment freely and its edits should not land on your working tree.

## A sandboxed workstation

The agent has root on a complete Linux system, with its own init, network stack, and either its own kernel or a kernel namespace.
The tools differ in how a system is created, what it costs while it runs, and how cheaply it is discarded and replaced once the work has tainted it.

### Docker Sandboxes

[Docker Sandboxes](https://docs.docker.com/ai/sandboxes/) (`sbx`) is a sandboxed workstation in a microVM of its own, built from an image.

- **Where it agrees with isx.**
  On credentials, and on what the agent should get: a full Linux system with sudo, package managers, nested containers, and state that persists.
  Both can put that system in a VM.
- **Design choice.**
  What a sandbox is: in Docker it is always its own microVM, built from an image.
  In isx it is a branch of a template, as a system container by default, or a VM (`--vm`) when you want the strongest isolation.
- **What follows.**
  A Docker sandbox runs on Docker's own VMM with its own kernel, disk image and Docker store, and holds the memory it is given whether or not the agent is using it.
  A saved template is an image the next sandbox is built from.
- **Where isx differs.**
  An isx template is built once on a copy-on-write pool, and a branch of it is instant and costs the disk of what it changes.
  So trying five approaches in five branches and keeping one is the normal way to work.
- **A wrecked branch.**
  An agent that wrecks its environment costs a `destroy` and a new branch rather than a rebuild.
- **Many at once.**
  A chain of templates (base, tools, project) shares everything below it, and a container branch uses only what its processes use, so a laptop holds many of them at once.
- **Licence and platforms.**
  Docker's runtime is proprietary and free to use, on macOS, Windows and Linux; isx is open source on Incus, on Linux and macOS.

The workspace is the other divergence.

- **Docker Sandboxes.**
  `sbx run` mounts the current directory read-write by default, so the agent edits the host's files in place.
  Clone mode (`--clone`) is the opt-in that keeps work inside the sandbox until fetched.
- **isx** is more cautious here: it never mounts a host directory read-write, since files the agent writes can end up executed on the host by a build, a commit or an IDE.
  The [FAQ](FAQ.md#why-cant-i-mount-a-host-directory-read-write-to-follow-agent-work-in-my-ide) explains why.
  Work comes out through `isx://` git remotes instead, so clone mode is the only mode isx has.

Pick Docker Sandboxes for one governed VM per developer, especially on Windows; pick isx when you want many cheap environments from one template, as containers or VMs as the work demands.

### A VM you manage

A VM from Lima, Multipass, Tart or Vagrant, or a cloud instance, gives an agent a complete system with its own kernel.
Claude Code's docs recommend one for untrusted code.

- **Design choice.**
  The VM provides nothing beyond the system itself.
- **What follows.**
  Credentials have to be copied into it and stay there, and tools are installed by hand or by a provisioning script.
  SSH and editor access are yours to set up.
  The VM holds its full memory allocation while it runs.
- **Where isx differs.**
  `isx branch --vm` is such a VM with the rest of isx around it: templates that declare the tools, a branch per task and the credential proxy.
  It also offers a container instead of a VM, the pragmatic choice when a separate kernel is not worth its overhead.
- **Wired in.**
  Each branch comes wired into your tools: an `isx://` git remote to fetch its work, and SSH keys and VS Code Remote or JetBrains Gateway access from the first start.
  `isx mcp` lets an agent on the host delegate to branches of its own.
