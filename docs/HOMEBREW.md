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
   - macOS native binaries (`incus-spawn-macos-aarch64`, `incus-spawn-macos-x86_64`)
   - Linux binaries (amd64, aarch64)
   - VM appliance artifacts (kernel, disk images)
   - Git remote helper (`git-remote-isx`)

3. **Homebrew formula is updated automatically** by the release workflow.
   It computes SHA256 checksums from the build artifacts and pushes the
   updated formula to `Sanne/homebrew-tap`. Requires the `HOMEBREW_TAP_TOKEN`
   secret (a PAT with `contents: write` on the tap repo).

4. **Users auto-update** on their next `brew update && brew upgrade`

### Manual formula update (fallback)

If the automated step fails, update the formula manually:

```bash
VERSION=X.Y.Z
curl -sL https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/incus-spawn-macos-aarch64 | shasum -a 256
curl -sL https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/incus-spawn-macos-x86_64 | shasum -a 256
curl -sL https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/git-remote-isx | shasum -a 256
```

Update `version` and all `sha256` values in `Sanne/homebrew-tap/Formula/incus-spawn.rb`,
then commit and push.

## Dev Channel

A separate formula is available for development/pre-release builds:

```bash
brew install Sanne/tap/incus-spawn-dev
```

The dev formula installs the same `isx` and `isx-proxy` binaries but tracks the development release stream. It conflicts with the stable formula — Homebrew will prevent both from being installed at the same time.

To switch between channels:

```bash
# Switch from stable to dev
brew uninstall incus-spawn
brew install Sanne/tap/incus-spawn-dev

# Switch from dev to stable
brew uninstall incus-spawn-dev
brew install Sanne/tap/incus-spawn
```

The dev formula is updated automatically by the release workflow when a tag with a `-dev.N` suffix is pushed (e.g. `v0.3.0-dev.1`).

## Supported Platforms

The Homebrew formulas install on both Apple Silicon (arm64) and Intel (x86_64) Macs. Both binaries need **macOS 15 (Sequoia) or later**; macOS 14 and older are not supported. Apple Silicon is supported and verified on a Mac; the Intel binary is built and shipped on the same minimum as **best effort**: beyond the release workflow's smoke test on its Intel runner, it is not verified on an Intel Mac, as none is available. The build sets that minimum explicitly (`macos.deployment.target` in the root `pom.xml`), and the release workflow fails if any macOS binary it uploads records a different one, so a runner upgrade cannot move it (see DESIGN.md "The minimum macOS is set by the build"). Both formulas declare it as `depends_on macos: :sequoia`, so `brew install` refuses an older macOS instead of installing binaries that cannot load there. The release workflow writes that line on every release from its `MACOS_MIN`, and `WorkflowRunnerLabelsTest` fails when it no longer names the deployment target; a manual update keeps the line as it is.
