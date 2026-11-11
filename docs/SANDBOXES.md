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

isx is the third kind, a sandboxed workstation.

An instance is a branch of a template, in the way a git worktree is a branch of a repository.
It is a second working copy that shares the files it has not changed and uses disk space only for the files it changes.
Here the working copy is the whole system, toolchain, dependencies and services included.

This sharing is what makes it cheap to create one instance per task and throw it away afterwards.
A template declares the tools and the repositories, and is built once: the projects are cloned, their dependencies fetched and their build caches filled.
Every branch of it starts ready to work, and every branch is the same.

In daily use, each step of a task is one command.
`isx branch` creates an instance instantly, `isx shell` or the TUI opens a shell in it, your editor attaches over SSH, an `isx://` git remote fetches the agent's work, and `isx destroy` deletes it.

## Why full system containers?

An isx instance is a complete system, because it is built for a different job than an application container.

An application container, the kind Docker and Podman run by default, is built for shipping applications: a minimal filesystem, one process, fast startup.

An isx instance is a workstation: a full [Incus](https://linuxcontainers.org/incus/) system container, or a KVM virtual machine with `--vm`.
It has its own init system and real networking.
Tools such as `ping`, `strace` and nested Podman or Docker work in it as on a workstation.
On Linux it can also pass through GUI and audio.

The template pre-installs your baseline tools and repositories, but what the agent gets is a real Linux system.
The agent can `dnf install`, `pip install`, build from source or run Docker Compose, just as on a workstation.

This matters for agents in particular: what you hand an agent is an investigation, and nobody knows at the start what tools it will need.
An agent on an isx branch installs a tracer, starts a service or writes a tool of its own when the investigation needs it.

The agent works as it would on a developer workstation, because the instance is one.
If it wrecks the instance, you destroy it and branch again: the new branch is instant and starts from the clean template.

For the strongest isolation, a KVM virtual machine (`--vm`) adds a separate kernel and hardware-level separation.
The sections below compare this choice with the other sandboxes for agents.

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
  `/sandbox` applies these rules to Bash commands only.
  Running `claude` itself under the runtime applies them to the file tools, hooks and MCP servers as well.

**Where isx differs.**
What a sandboxed process can reach is fixed before it starts: the paths and hosts in the profile, the tools already on your host.
Landlock rules can be tightened after launch, never loosened.

So an agent that needs one more path, a package or a running service has to be stopped, given a wider profile and started again.
The package still has to be installed on your host.

In an isx branch the agent can change its whole system.
It installs tools, writes its own, starts services and runs containers when the task needs them, and none of this changes your host.

isx asks for more setup once, `isx init` and a template build.
After that one command creates a branch instantly, with the project and its dependencies already in it, and with a shell, an editor and a git remote already attached.

nono and isx also answer "what if a tool is compromised" differently.

- **nono** is fine-grained: each command runs in its own sandbox and a credential works only for the endpoints its profile lists.
  This needs the profile to anticipate each command and endpoint.
- **isx** is coarser and simpler: assume the whole instance is compromised, give it only credentials whose misuse you can accept, and make the instance disposable.
  A read-only GitHub account on a review template is such a credential.

Use a sandboxed process when the agent edits your working tree with the tools you already have.
Use isx when the agent has to find out what it needs while it works.

## A sandboxed workload

The agent runs in an application container with a fixed set of tools.
It may be allowed to add a package, but it cannot reshape the environment the way it could on a real system: there is no init system, so no services, and privileges are reduced.

### NVIDIA OpenShell

[OpenShell](https://github.com/NVIDIA/OpenShell) is a governed workload with the same credential model as isx and a finer policy for outgoing connections.

- **Where it agrees with isx.**
  On credentials: the sandbox never holds a real key, a trusted process outside it decrypts the requests, adds the credential to approved requests, and identifies the caller in a way the sandbox cannot forge.
  The two differ in what is sandboxed.
- **Design choice.**
  OpenShell confines a workload: an application container with no network device, one non-root user, no Linux capabilities, and a Landlock filesystem policy fixed at startup that leaves `/usr` and `/etc` read-only.
  Seccomp hands every TCP connect and DNS lookup to a supervisor that checks it against a policy naming hosts, HTTP methods and the calling program.
- **What follows.**
  This policy for outgoing connections is finer-grained than isx's network modes.

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

Pick OpenShell to run an agent as a governed workload in a fleet; pick isx when the agent has to install, trace and run whatever the investigation needs.

### Dev containers

A [dev container](https://code.claude.com/docs/en/devcontainer) is a Docker container managed by an editor, with the project directory bind-mounted read-write and the agent installed in the image.

- **Design choice.**
  Claude Code's reference configuration adds an iptables firewall inside the container that denies outgoing connections except to listed domains, so the agent can run without permission prompts.
  A dev container is a good way to give a team one identical environment.
- **What follows.**
  Whether the agent can add packages, or undo that firewall, depends on the image.
  The common base images give the user full sudo, which also lets the agent delete the firewall rules.
  The reference configuration limits sudo to re-running the firewall script, so the agent can neither add packages nor delete the rules.
- **The trade-off.**
  The firewall runs inside the container it confines, so an agent with enough freedom to install packages can also remove the firewall.

**Where isx differs.**
A dev container is an application container: no init system, so no services; capabilities dropped, so nested containers and tracing need privileged flags; and the workspace the agent writes is your host's directory.

You also keep the container and rebuild it from the Dockerfile, rather than discarding it after the task.

Claude Code's own docs note that the credentials live inside it, in `~/.claude`, where an unattended session can read them.
In isx the real credentials stay on your host, and a proxy there adds them to the agent's requests as the requests leave the instance.

Pick a dev container to give a team one environment with the editor attached; pick isx when the agent should reshape its environment freely and its edits should not change your working tree.

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
  An isx template is built once on a copy-on-write pool, and a branch of it is instant and uses disk space only for what it changes.
  So trying five approaches in five branches and keeping one is the normal way to work.
- **A wrecked branch.**
  An agent that wrecks its environment costs a `destroy` and a new branch rather than a rebuild.
- **Many at once.**
  In a chain of templates (base, tools, project) each template shares the disk of the templates it builds on, and a container branch uses only the memory its processes use, so a laptop holds many branches at once.
- **Licence and platforms.**
  Docker's runtime is proprietary and free to use, on macOS, Windows and Linux; isx is open source on Incus, on Linux and macOS.

The workspace is the other divergence.

- **Docker Sandboxes.**
  `sbx run` mounts the current directory read-write by default, so the agent edits the host's files in place.
  Clone mode (`--clone`) is the opt-in that keeps work inside the sandbox until fetched.
- **isx** does not mount your project directory read-write, because a file the agent writes there can run on your host through a build, a commit or your IDE ([FAQ](FAQ.md#why-cant-i-mount-a-host-directory-read-write-to-follow-agent-work-in-my-ide)).
  You give up having the agent's files as a local folder.
  You fetch them through `isx://` git remotes instead, so clone mode is the only mode isx has.

Pick Docker Sandboxes for one governed VM per developer, especially on Windows; pick isx when you want many cheap environments from one template, as containers or VMs as the work demands.

### A VM you manage

A VM from Lima, Multipass, Tart or Vagrant, or a cloud instance, gives an agent a complete system with its own kernel.
Claude Code's docs recommend one for untrusted code.

- **Design choice.**
  The VM does not provide anything beyond the system itself.
- **What follows.**
  Credentials have to be copied into it and stay there, and tools are installed by hand or by a provisioning script.
  SSH and editor access are yours to set up.
  The VM holds its full memory allocation while it runs.
- **Where isx differs.**
  `isx branch --vm` creates such a VM and adds the rest of isx: templates that declare the tools, a branch per task and the credential proxy.
  isx also offers a container instead of a VM, the pragmatic choice when a separate kernel is not worth its overhead.
- **Connected to your tools.**
  Each branch is already connected to your tools: an `isx://` git remote to fetch its work, and SSH keys and VS Code Remote or JetBrains Gateway access from the first start.
  `isx mcp` lets an agent on the host delegate work to branches of its own.
