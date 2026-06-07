# Homebrew Distribution

incus-spawn is distributed via Homebrew for macOS users.

## Tap Repository

The Homebrew formula lives in a separate repository:
**https://github.com/Sanne/homebrew-tap**

This follows Homebrew's naming convention for taps:
- Tap name: `Sanne/tap`
- Repository: `Sanne/homebrew-tap`

## User Installation

Users install via:

```bash
brew tap Sanne/tap
brew install incus-spawn
```

Or in one command:

```bash
brew install Sanne/tap/incus-spawn
```

## Release Process

When cutting a new release:

1. **Tag and push** the release in this repository (triggers GitHub Actions)

2. **GitHub Actions builds**:
   - macOS aarch64 native binary (`incus-spawn-macos-aarch64`)
   - Linux binaries (amd64, aarch64)
   - VM appliance artifacts (kernel, disk images)
   - Git remote helper (`git-remote-isx`)

3. **Update the Homebrew formula** in `Sanne/homebrew-tap`:
   - Update `version` field in `Formula/incus-spawn.rb`
   - Compute and update SHA256 hashes:
     ```bash
     VERSION=0.1.28
     curl -sL https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/incus-spawn-macos-aarch64 | shasum -a 256
     curl -sL https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/git-remote-isx | shasum -a 256
     ```
   - Replace `PLACEHOLDER_ARM64_SHA256` and `PLACEHOLDER_GIT_REMOTE_SHA256` with the computed values
   - Test locally: `brew install --build-from-source Formula/incus-spawn.rb`
   - Commit and push to the tap repo

4. **Users auto-update** on their next `brew update && brew upgrade`

## Supported Platforms

The Homebrew formula **only supports Apple Silicon (arm64)** Macs.

Intel Macs are not supported due to:
- Deprecated GitHub Actions runners (macos-13 x86_64)
- Focus on modern Apple Silicon hardware
- Native performance requirements for the VM workload

Users on Intel Macs can build from source using `./install.sh --native`.
