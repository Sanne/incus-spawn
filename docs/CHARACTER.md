# incus-spawn — Project Character

## Mission

incus-spawn solves a specific, underserved problem: **safe, fast, full-fidelity dev environments for running untrusted code** — particularly AI coding agents. Docker/Podman optimize for shipping apps (minimal filesystem, single process). incus-spawn optimizes for *working inside* a container as if it were a real machine: systemd, real networking, strace, nested containers, GUI/audio. The target user is a developer who wants to hand an AI agent a complete Linux workstation and not worry about credential theft or host damage.

## Core Strengths

**1. The credential isolation model is the standout feature.** The MITM TLS proxy is an elegant solution to a genuinely hard problem. API keys *never* enter containers in any form — not as env vars, not in files, not in process memory. The proxy intercepts at the TLS layer and injects credentials server-side. This is architecturally superior to every alternative (wrappers, credential helpers, `BASE_URL` overrides) because there is no attack surface inside the container. The three-auth-mode support (direct key, OAuth/Pro, Vertex AI) with automatic translation is impressively thorough.

**2. Copy-on-write branching is the right abstraction.** The git metaphor (template = commit, branch = cheap clone) maps perfectly to the use case. Build a template once (minutes), branch in seconds, destroy when done. The storage efficiency (btrfs/zfs/lvm dedup) means running 10 environments costs barely more than 1.

**3. The tool/template YAML system hits a sweet spot.** YAML tools with a clear execution order (packages -> downloads -> run -> files -> env -> verify) give 90% of Ansible's capability with zero dependencies. Package deduplication across the parent chain, host-side download caching, and transitive dependency resolution (`requires`) show careful engineering. Java CDI tools as an escape hatch for programmatic logic is the right layering.

**4. Deep integration rather than shallow glue.** Git remote helpers (`isx://` URLs with auto-remote management), SSH key lifecycle, IDE integration (Gateway/VS Code actions via F9), Claude Code skill baking, host-resource overlays, `PROMPT_COMMAND` terminal titles — these aren't features bolted on after the fact, they're woven into the lifecycle (branch injects keys + adds remotes, destroy cleans both up).

**5. macOS support via vsock is well-engineered.** The discovery that corporate VPN socket filters block TCP to the VM subnet but can't touch `AF_VSOCK` is a real-world insight that makes the tool actually usable in enterprise environments. The fallback chain (Linux Unix socket -> vsock -> HTTPS) is robust.

## Design Character

The project is opinionated in the right ways (Fedora, Incus, YAML-first) and flexible where it matters (tool resolution layers, search paths, project-local overrides). It doesn't try to be a general-purpose container orchestrator — it's laser-focused on "give an AI agent a disposable Linux workstation with zero credential exposure."

Every non-obvious decision in DESIGN.md has a "why not the alternative" section. When evaluating feature requests and changes, preserve this character: strong opinions on the core (security, fidelity, speed), flexibility at the edges (tools, templates, integrations).
