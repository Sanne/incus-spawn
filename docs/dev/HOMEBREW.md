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
   - macOS native binaries (`incus-spawn-macos-aarch64`, `isx-proxy-macos-aarch64`; Apple Silicon only)
   - Linux binaries (amd64, aarch64)
   - VM appliance artifacts (kernel, disk images)
   - Git remote helper (`git-remote-isx`)

3. **Homebrew formula is updated automatically** by the release workflow.
   It computes SHA256 checksums from the build artifacts and pushes the
   updated formula to `Sanne/homebrew-tap`. Requires the `HOMEBREW_TAP_TOKEN`
   secret (a PAT with `contents: write` on the tap repo).

4. **Users auto-update** on their next `brew update && brew upgrade`

### Manual formula update (fallback)

If the automated step fails, update the formula manually.
It is `continue-on-error`, so a failed tap update leaves the release green with only an `::error::` annotation: check for one after every release.
Its guard refuses a formula that still names an Intel asset or lacks `depends_on arch: :arm64` (design/native-image.md "Intel Macs are not shipped"); after fixing the formula, bump it as below, since nothing re-runs the step.

```bash
VERSION=X.Y.Z
curl -sL https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/incus-spawn-macos-aarch64 | shasum -a 256
curl -sL https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/isx-proxy-macos-aarch64 | shasum -a 256
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

The Homebrew formulas install on Apple Silicon (arm64) Macs only: from the first release on Quarkus 4 they declare `depends_on arch: :arm64` and name no Intel asset, so `brew install` tells an Intel Mac so before downloading anything. That edit to both formulas is made by hand before that release, since the release workflow's tap step will not rewrite an Intel formula. Intel Macs are not shipped since the first release on Quarkus 4; v0.4.2 and v0.5.0-dev.2 were the last with an Intel binary (design/native-image.md "Intel Macs are not shipped"). The release workflow's tap step refuses to update a formula that still names an Intel asset or lacks that line. The binaries need **macOS 15 (Sequoia) or later**; macOS 14 and older are not supported, and Apple Silicon is verified on a Mac. The build sets that minimum explicitly (`macos.deployment.target` in the root `pom.xml`), and the release workflow fails if any macOS binary it uploads records a different one, so a runner upgrade cannot move it (see design/native-image.md "The minimum macOS is set by the build"). Both formulas declare it as `depends_on macos: :sequoia`, so `brew install` refuses an older macOS instead of installing binaries that cannot load there. The release workflow writes that line on every release from its `MACOS_MIN`, and `WorkflowRunnerLabelsTest` fails when it no longer names the deployment target; a manual update keeps the line as it is.
