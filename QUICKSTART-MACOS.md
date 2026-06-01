# incus-spawn Quickstart for macOS

## Who this is for

This guide is for developers who want to experiment with a preview of incus-spawn on a MacBook. 

**Important**: This guide requires setting up your own Linux VM. incus-spawn intends to provide a managed VM solution in the future, so manual VM setup is a temporary requirement during the preview phase.

## Prerequisites

- macOS with Apple Silicon (M1/M2/M3/M4)
- 12+ GB available RAM (recommended for building multiple projects in parallel and leveraging build/dependency caches)
- 80+ GB disk space (recommended for caching dependencies and multiple project containers)

## 1. Install UTM

[UTM](https://mac.getutm.app/) is a free virtual machine application for macOS. Install it using Homebrew:

```shell
brew install --cask utm
```

Then launch UTM from your Applications folder or via Spotlight.

## 2. Create a Fedora VM

### Download Fedora Workstation

Download the **Fedora Workstation 44 aarch64** ISO from the [official Fedora download page](https://fedoraproject.org/workstation/download/).

**Important**: Choose the **aarch64 (ARM)** version for Apple Silicon.

### Create the VM in UTM

1. Open UTM and click **Create a New Virtual Machine**
2. Select **Virtualize** (for better performance on Apple Silicon)
3. Choose **Linux**
4. Browse and select the Fedora ISO you downloaded
5. **Configure resources** (optional but recommended for working on multiple projects/branches concurrently with several agents):
   - **Memory**: 12 GB (12288 MB)
   - **CPU cores**: 8
   - **Storage**: 100 GB (sparse allocation — won't consume the full amount immediately, but allows room for aggressive caching of downloads, dependencies, and tools)
6. Complete the wizard and start the VM
7. Follow the Fedora installation process (choose Fedora Workstation installation)
8. Create a user account and complete setup
9. Configure the user for passwordless login and disable automatic standby/sleep
10. Reboot the VM when installation finishes

### Post-installation

After logging into Fedora:

```shell
# Update the system
sudo dnf upgrade -y

# Install basic development tools
sudo dnf install -y git curl gh
```

## 3. Configure Claude Code in the VM

incus-spawn will automatically detect and use Claude Code credentials configured in the VM. Set it up now even though you won't use it directly.

### Install Claude Code CLI

```shell
curl -fsSL https://claude.ai/install.sh | sh
```

### Authenticate

```shell
claude
```

On first launch, Claude Code will open a browser for OAuth authentication:
- If the browser opens automatically, log in with your Anthropic/Claude account
- If not, press `c` to copy the login URL, then paste it into a browser

Once authenticated, test it works:

```shell
claude --status
```

You can exit Claude Code after verifying authentication. The credentials are now stored and tested and will be picked up by incus-spawn.

**What's happening**: incus-spawn uses a MITM proxy to inject these credentials into containers transparently, so your API keys never enter the isolated environments.

## 4. Prepare a GitHub Token

incus-spawn will provide GitHub authentication to agents running inside containers. You need a personal access token with appropriate scopes.

### Create a token

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

Keep this token ready — you'll configure it during `isx init` in step 7.

## 5. (Optional) Clone Getting-Started Templates

For a smoother first experience, clone a repository with pre-made incus-spawn templates:

```shell
cd ~
git clone https://github.com/Sanne/incus-spawn-templates.git
```

This repository contains example image definitions and tools you can use as starting points. You can fork this repository and customize the metadata files to express your preferences about which projects to work on and which tools to have pre-installed into agent containers.

## 6. Install incus-spawn

Add the Fedora COPR repository and install incus-spawn:

```shell
# Enable the COPR repository
sudo dnf copr enable sanne/incus-spawn

# Import the GPG key
sudo rpm --import https://download.copr.fedorainfracloud.org/results/sanne/incus-spawn/pubkey.gpg

# Install incus-spawn
sudo dnf install incus-spawn
```

Verify installation:

```shell
isx --version
```

## 7. (Optional) Clone Your Projects

Clone the repositories you intend to work on into the VM. The VM will serve as a coordination point for patches between the host (your Mac) and isolated containers.

```shell
# Example: clone a project you're working on
cd ~/projects  # or wherever you organize your code
git clone https://github.com/your-org/your-project.git
```

**Why this helps**: incus-spawn can automatically set up git remotes between the VM and containers. When agents make changes inside containers, you can easily fetch and review those commits on the VM side using standard git commands like `git fetch` and `git cherry-pick`. The VM acts as a shared cache and coordination layer.

You can configure automatic remote management later in `~/.config/incus-spawn/config.yaml` by setting `host-paths` to point to your project directories. See [Git Remotes](README.md#git-remotes) in the main README for details.

## 8. Initialize incus-spawn

Run the one-time setup command and follow the interactive prompts:

```shell
isx init
```

The initialization wizard will:
- Install and configure Incus (the container system)
- Set up the MITM TLS proxy for credential injection
- Configure firewall rules
- Prompt for your GitHub token (paste the token from step 4)
- Prompt for Claude Code authentication setup
- Ask where you stored your local git projects (the ones you cloned in step 7)
- Create storage pools for copy-on-write branching

Follow all prompts carefully. When asked about the proxy, choose to install it as a systemd service so it starts automatically.

## 9. Next Steps

You're ready to use incus-spawn! Try these commands:

```shell
# Launch the interactive TUI to manage templates and instances
isx

# Or create an isolated environment from the command line
isx branch my-experiment tpl-dev
isx shell my-experiment
```

Inside the container, try:
- `claude` — launches Claude Code with injected auth
- `gh auth status` — verify GitHub authentication works
- `git clone https://github.com/your/repo.git` — clone repos (uses HTTPS with injected token)

For more details, see the [main README](README.md).

---

## Troubleshooting

**Proxy not running**: Check status with `isx proxy status`. Start it with `isx proxy start`, or install it as a systemd service with `isx proxy install` to start automatically on boot.

**Authentication not working**: Verify Claude Code auth with `claude --status` and check proxy logs with `isx proxy logs`.

**Network issues in containers**: Ensure the proxy is running and the VM has internet access.

For anything else, [open an issue on GitHub](https://github.com/Sanne/incus-spawn/issues) or ping me — happy to help!

## Sources

- [UTM Virtual Machines](https://mac.getutm.app/)
- [UTM Homebrew Formula](https://formulae.brew.sh/cask/utm)
- [Fedora Workstation Download](https://fedoraproject.org/workstation/download/)
- [Claude Code Authentication Docs](https://code.claude.com/docs/en/authentication)
- [GitHub Personal Access Tokens](https://github.com/settings/tokens)
