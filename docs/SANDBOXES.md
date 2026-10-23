# Compared with other sandboxes

The term "sandbox" is a bit generic.
There are different kinds of sandboxes, and it is useful to be aware of the differences to pick the right one for your needs.
Broadly speaking, there are three categories:

- **A sandboxed process.**
  The agent runs as a regular process in your own operating system, as your user, and is confined to a list of allowed files, hosts and operations that you configure.
- **A sandboxed workload.**
  The agent runs in an application container with a fixed set of tools, and cannot reshape its environment the way it could on a real system.
- **A sandboxed workstation.**
  The agent gets a complete Linux system of its own, to investigate on without constraints: it installs tools, runs services and builds what is missing, as the research requires.
  Once the work is done the system is considered tainted and discarded.

isx is in the third category.
The system is an Incus system container or a VM, and it is a branch of a template, in the way a git worktree is a branch of a repository: a second working copy that shares everything unchanged and costs only what it changes, except that here the working copy is the whole machine, toolchain, dependencies and services included.
That is what makes it cheap to create one per task and throw it away afterwards.
Day to day a workstation is one command away.
A template declares the tools and the repositories, and is built once: the projects are cloned and primed, dependencies fetched and builds warmed, so every branch of it starts ready to work, and every branch is the same.
`isx branch` creates one in about a second, `isx shell` or the TUI gets you in, your editor attaches over SSH, an `isx://` git remote fetches the agent's work, and `isx destroy` ends it.

Below, the other tools are grouped by category, with a note on when each is the better fit.

## Why full system containers?

**Application containers**, the kind Docker and Podman run by default, are built for shipping applications: a minimal filesystem, one process, fast startup. isx is built for a different job, and the choice that follows from it is what a sandbox *is*.
An isx sandbox is a workstation, a complete machine: a full [Incus](https://linuxcontainers.org/incus/) **system container**, or a KVM virtual machine (`--vm`), with its own init system, real networking (`ping`, `strace`, nested Podman/Docker), and GUI and audio passthrough (Linux only).
Templates pre-install your baseline tools and repos, but what the agent gets is a real Linux system, where it can `dnf install`, `pip install`, build from source, or run Docker Compose just like on a workstation.
This matters for agents in particular, because what you hand an agent is an investigation, and nobody knows at the start what tools it will need.
An agent on an isx branch installs a tracer, starts a service, or writes a tool of its own as it goes, and if it wrecks the machine, the branch is destroyed and the next one costs a second.
It works exactly as it would on a developer workstation, because that is what it has.
The sections below show how this choice plays out against the other sandboxes for agents.
For the strongest isolation, KVM virtual machines (`--vm`) add a separate kernel and hardware-level separation.

## A sandboxed process

In this category the agent is an ordinary process in your operating system, running as your user, confined to the files, hosts and operations you allow: Landlock or bubblewrap on Linux, Seatbelt on macOS, plus a local proxy for the allowed hosts.
There is nothing to install and nothing to boot, and the agent works with the tools and files that are already on your machine.
Codex's built-in sandbox works this way too.

### nono

[nono](https://github.com/nolabs-ai/nono) runs an agent with read and write access to the current directory and no access to anything else on disk or on the network unless its profile allows it.
Profiles are published per agent and can be extended.
A broker can run the commands the agent calls (`git`, `gh`, `kubectl`) in separate sandboxes with their own credentials, so a token is available to one command and never to the agent process.
Rust, Apache-2.0.

### Claude Code's sandbox runtime

[sandbox-runtime](https://github.com/anthropics/sandbox-runtime) is the implementation behind Claude Code's `/sandbox`.
Writes are denied except to listed paths, network is denied except to listed domains via a proxy, and reads are allowed everywhere except listed paths.
It always denies writes to `.git/hooks`, `.mcp.json` and the other files a session could use to run code in a later session.
`/sandbox` applies this to Bash commands only.
Running `claude` itself under the runtime applies it to the file tools, hooks and MCP servers as well.

**Where isx differs.**
A sandboxed process has its world fixed before it starts: the paths and hosts in the profile, the tools already on your machine.
Landlock rules can be tightened after launch, never loosened, so an agent that needs one more path, a package or a running service has to be stopped, given a wider profile and started again, and the package still has to be installed on your host.
In an isx branch the world is the agent's to change.
It installs tools, writes its own, starts services and runs containers as the task turns out to need them, and your host is untouched. isx asks for more setup once, `isx init` and a template build; after that a branch costs one command and a second, primed with the project and its dependencies, with a shell, an editor and a git remote already attached.
The two also answer "what if a tool is compromised" differently. nono's answer is fine-grained: each command runs in its own sandbox and a credential works only for the endpoints its profile lists, which needs the profile to anticipate each command and endpoint. isx's is coarser and simpler: assume the whole instance is compromised, give it only credentials whose misuse you can live with (a read-only GitHub account on a review template), and make the instance disposable.
Use a sandboxed process when the agent edits your working tree with the tools you already have.
Use isx when the agent has to work out what it needs as it goes.

## A sandboxed workload

In this category the agent runs in an application container with a fixed set of tools.
It may be allowed to add a package, but it cannot reshape the environment the way it could on a real system: there is no init system, so no services, and privileges are reduced.

### NVIDIA OpenShell

[OpenShell](https://github.com/NVIDIA/OpenShell) agrees with isx on credentials: the sandbox never holds a real key, a trusted process outside it terminates TLS, injects the credential on approved requests, and identifies the caller in a way the sandbox cannot forge.
The two differ in *what* is sandboxed.

OpenShell confines a workload: an application container with no network device, one non-root user, no Linux capabilities, and a Landlock filesystem policy fixed at startup that leaves `/usr` and `/etc` read-only.
Seccomp hands every TCP connect and DNS lookup to a supervisor that checks it against a policy naming hosts, HTTP methods and the calling program.
That is a finer-grained egress policy than isx's network modes.

isx hands the agent a workstation, because what we hand an agent is an investigation, and nobody knows at the start what tools it will need.
A test that only fails under load: install `bpftrace` and write a probe.
A slow native build: install async-profiler and compare flame graphs.
A Testcontainers suite: run podman inside.
A flaky remote integration: write a mock server, point `/etc/hosts` at it, capture the exchange with `tcpdump`.
None of these is possible in OpenShell: there is no root to install anything, `bpf` and `perf_event_open` need capabilities the workload does not have, `/etc` is read-only, nested containers need namespaces and a NIC, and nothing about the sandbox can change once it runs, so each step means a person rebuilds the image and the agent's context is lost.
In isx the agent has root on a system with systemd, nested containers, debuggers and a real NIC, and if it wrecks the box, the branch is destroyed and the next one costs a second.
Pick OpenShell to run an agent as a governed workload in a fleet; pick isx to let one work the way a good engineer does.

### Dev containers

A [dev container](https://code.claude.com/docs/en/devcontainer) is a Docker container managed by an editor, with the project directory bind-mounted read-write and the agent installed in the image.
Claude Code's reference configuration adds an iptables firewall inside the container that denies egress except to listed domains, so the agent can run without permission prompts.
It is a good way to give a team one identical environment.
Whether the agent can add packages, or undo that firewall, depends on the image: the common base images give the user full sudo, which also lets it flush the rules, while the reference limits sudo to re-running the firewall script and so allows neither.
The filter lives inside the boundary, so the agent's freedom and the firewall's integrity trade off against each other.
Either way it is an application container: no init system, so no services; capabilities dropped, so nested containers and tracing need privileged flags; and the workspace the agent writes is your host's directory.
The container is also yours to keep, rebuilt from the Dockerfile rather than discarded after the task, and Claude Code's own docs note that the credentials live inside it, in `~/.claude`, where an unattended session can read them.
Pick a dev container to give a team one environment with the editor attached; pick isx when the agent should reshape its environment freely and its edits should not land on your working tree.

## A sandboxed workstation

In this category the agent has root on a complete Linux system, with its own init, network stack, and either its own kernel or a kernel namespace.
The tools differ in how a system is created, what it costs while it runs, and how cheaply it is discarded and replaced once the work has tainted it.

### Docker Sandboxes

[Docker Sandboxes](https://docs.docker.com/ai/sandboxes/) (`sbx`) and isx agree on credentials, and on what the agent should get: a full Linux machine with sudo, package managers, nested containers, and state that persists.
Both can put that machine in a VM.
The design choice that separates them is what a sandbox *is*.
In Docker it is always its own microVM, built from an image; in isx it is a branch of a template, as a system container by default, or a VM (`--vm`) when you want the strongest isolation.

A Docker sandbox runs on Docker's own VMM with its own kernel, disk image and Docker store, and holds the memory it is given whether or not the agent is using it; a saved template is an image the next sandbox is built from.
An isx template is built once on a copy-on-write pool, and a branch of it costs a second and the disk of what it changes, so trying five approaches in five branches and keeping one is the normal way to work, and an agent that wrecks its environment costs a `destroy` and a new branch rather than a rebuild.
A chain of templates (base, tools, project) shares everything below it, and a container branch uses only what its processes use, so a laptop holds many of them at once.
Docker's runtime is proprietary and free to use, on macOS, Windows and Linux; isx is open source on Incus, on Linux and macOS.

The workspace is the other divergence.
`sbx run` mounts the current directory read-write by default, so the agent edits the host's files in place, and clone mode (`--clone`) is the opt-in that keeps work inside the sandbox until fetched. isx is more cautious here: it never mounts a host directory read-write, since files the agent writes can end up executed on the host by a build, a commit or an IDE (the FAQ explains why), and work comes out through `isx://` git remotes instead, so clone mode is the only mode isx has.
Pick Docker Sandboxes for one governed VM per developer, especially on Windows; pick isx when you want many cheap environments from one template, as containers or VMs as the work demands.

### A VM you manage

A VM from Lima, Multipass, Tart or Vagrant, or a cloud instance, gives an agent a complete machine with its own kernel.
Claude Code's docs recommend one for untrusted code.
The VM provides nothing beyond the machine: credentials have to be copied into it and stay there, tools are installed by hand or by a provisioning script, SSH and editor access are yours to set up, and the VM holds its full memory allocation while it runs.
`isx branch --vm` is such a VM with the rest of isx around it: templates that declare the tools, a branch per task, the credential proxy, and the option of a container instead of a VM as the pragmatic choice when a separate kernel is not worth its overhead.
Each branch also comes wired into the developer's tools: an `isx://` git remote to fetch its work, SSH keys and VS Code Remote or JetBrains Gateway access from the first start, and `isx mcp` so an agent on the host can delegate to branches of its own.
