# incus-spawn Quickstart for macOS

## Who this is for

Developers on Apple Silicon Macs who want isolated Linux environments for running AI agents, reproducing bugs, or creating dev environments — without managing a VM manually.

## Prerequisites

- macOS with Apple Silicon (M1/M2/M3/M4)
- 12+ GB available RAM (recommended for building multiple projects in parallel)
- 80+ GB disk space (recommended for caching dependencies and multiple containers)

## 1. Install incus-spawn

```shell
brew install Sanne/tap/incus-spawn
```

Verify installation:

```shell
isx --version
```

## 2. Prepare a GitHub Token

incus-spawn provides GitHub authentication to agents running inside containers. You need a personal access token with appropriate scopes.

**Optional**: Consider generating a personal access token for a dedicated GitHub account, depending on how you want agents to be identified in commits and activity logs.

1. Go to [GitHub Settings > Developer settings > Personal access tokens > Tokens (classic)](https://github.com/settings/tokens)
2. Click **Generate new token (classic)**
3. Give it a descriptive name (e.g., "incus-spawn agents")
4. Select scopes based on what you want agents to be able to do:
   - `repo` — if you want agents to push commits to your repositories
   - `read:repo` — if you only want read access
   - `workflow` — if you want agents to trigger GitHub Actions
5. Click **Generate token** and copy it immediately

**Security note**: Do NOT use an unrestricted personal access token. Create a token with only the specific permissions your agents need. This token will be used by AI agents running in isolated containers.

Keep this token ready — you'll configure it during `isx init` in step 4.

## 3. (Optional) Clone Getting-Started Templates

For a smoother first experience, clone a repository with pre-made incus-spawn templates:

```shell
cd ~
git clone https://github.com/Sanne/incus-spawn-templates.git
```

This repository contains example image definitions and tools you can use as starting points. You can fork it and customize the metadata files to express your preferences about which projects to work on and which tools to have pre-installed into agent containers.

## 4. Initialize incus-spawn

Run the one-time setup command and follow the interactive prompts:

```shell
isx init
```

The initialization wizard will:
- Generate TLS certificates for the MITM proxy
- Prompt for your Claude Code API key or Vertex AI configuration
- Prompt for your GitHub token (paste the token from step 2)
- Download and start a lightweight Linux VM (via [vfkit](https://github.com/crc-org/vfkit))
- Install VM and proxy as macOS services (auto-start at login)

macOS will show permission dialogs for home folder access and local network connectivity. These are safe to approve:
- Your home directory is mounted read-only inside the VM (nothing is modified)
- Agents run in sandboxed containers that only see paths you explicitly configure
- Network access enables connectivity for the Linux containers

## 5. Build a Template

```shell
isx build tpl-dev
```

This builds a development template with common tools. Templates form an inheritance chain — `tpl-dev` extends `tpl-minimal`, and any missing parent is built automatically.

For Java development:

```shell
isx build tpl-java
```

## 6. Start Using incus-spawn

```shell
# Launch the interactive TUI
isx

# Or create an isolated environment from the command line
isx branch my-experiment tpl-dev
isx shell my-experiment
```

Inside the container, try:
- `claude` — launches Claude Code with injected auth
- `gh auth status` — verify GitHub authentication works
- `git clone https://github.com/your/repo.git` — clone repos (uses HTTPS with injected token)

When you're done, destroy the branch — the template is unaffected:

```shell
isx destroy my-experiment
```

## How It Works

incus-spawn runs a lightweight Linux VM on your Mac using Apple's Virtualization.framework. Inside the VM, Incus manages system containers — full Linux environments with their own init system, networking, and filesystem.

A host-side MITM TLS proxy intercepts HTTPS traffic to specific domains (Anthropic, GitHub, Maven Central, etc.) and injects real credentials. Containers only hold placeholder values — your API keys never enter any container.

Your home directory is mounted read-only in the VM, enabling host file sharing (build caches, project sources) without modifying anything on your Mac.

For more details, see the [main README](README.md) and [DESIGN.md](DESIGN.md).

---

## Troubleshooting

**VM not starting**: Check status with `isx vm status`. The VM log is at `~/.local/state/incus-spawn/vm.log`.

**Proxy not running**: Check status with `isx proxy status`. Start it with `isx proxy start`. The proxy is installed as a macOS service and should start automatically at login.

**Authentication not working**: Verify the proxy is running (`isx proxy status`) and check proxy logs with `isx proxy logs`.

**Network issues in containers**: Ensure the proxy is running. DNS overrides route intercepted domains through the proxy — without it, those domains won't resolve.

**Updating**: Run `brew upgrade incus-spawn` to get the latest version.

For anything else, [open an issue on GitHub](https://github.com/Sanne/incus-spawn/issues).
