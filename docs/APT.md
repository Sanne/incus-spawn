# APT Distribution

incus-spawn is distributed via an APT repository for Ubuntu and Debian users, hosted on GitHub Pages from a dedicated repository:
**https://github.com/Sanne/isx-apt-releases**

## User Installation

```bash
curl -fsSL https://sanne.github.io/isx-apt-releases/public.gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/incus-spawn.gpg
echo "deb [signed-by=/usr/share/keyrings/incus-spawn.gpg] https://sanne.github.io/isx-apt-releases stable main" | sudo tee /etc/apt/sources.list.d/incus-spawn.list
sudo apt update && sudo apt install incus-spawn
```

Updates with `sudo apt upgrade`.

## Repository Structure

The APT repo uses a single `stable` suite (not per-Ubuntu-release) because the GraalVM native binary is self-contained. Both `amd64` and `arm64` architectures are published.

```
isx-apt-releases repo:
  public.gpg                              # GPG public key for apt verification
  pool/
    incus-spawn_VERSION_amd64.deb
    incus-spawn_VERSION_arm64.deb
  dists/stable/
    Release / Release.gpg / InRelease     # Signed repository metadata
    main/binary-amd64/Packages{,.gz}
    main/binary-arm64/Packages{,.gz}
```

Each `.deb` contains:
- `/usr/bin/isx` -- the native binary
- `/usr/bin/git-remote-isx` -- git remote helper
- Shell completions for bash, zsh, and fish

## Release Process

When cutting a new release:

1. **Tag and push** the release in this repository (triggers GitHub Actions)

2. **GitHub Actions builds** `.deb` packages for both architectures from
   the pre-built GraalVM native binaries (no source compilation in the
   packaging step). Shell completions are generated from the amd64 binary.

3. **The APT repository is updated automatically** by the release workflow.
   It clones `Sanne/isx-apt-releases`, replaces the pool with new debs,
   regenerates `Packages` indices via `dpkg-scanpackages`, signs the
   `Release` file with GPG, and pushes. Requires two secrets:
   - `APT_GPG_PRIVATE_KEY` -- ASCII-armored GPG private key (without a passphrase)
   - `APT_REPO_TOKEN` -- a PAT with `contents: write` on `Sanne/isx-apt-releases`

4. **Users auto-update** on their next `sudo apt update && sudo apt upgrade`

### Manual update (fallback)

If the automated step fails, build and publish manually:

```bash
VERSION=X.Y.Z

# Download binaries from the GitHub Release
curl -sLO https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/incus-spawn-linux-amd64
curl -sLO https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/incus-spawn-linux-aarch64
curl -sLO https://github.com/Sanne/incus-spawn/releases/download/v${VERSION}/git-remote-isx

# Build .deb (repeat for arm64 with the aarch64 binary)
PKG_DIR="incus-spawn_${VERSION}_amd64"
mkdir -p "$PKG_DIR"/{DEBIAN,usr/bin,usr/share/bash-completion/completions,usr/share/zsh/vendor-completions,usr/share/fish/vendor_completions.d}
sed -e "s/@VERSION@/$VERSION/" -e "s/@ARCH@/amd64/" packaging/debian/control > "$PKG_DIR/DEBIAN/control"
install -m 755 incus-spawn-linux-amd64 "$PKG_DIR/usr/bin/isx"
install -m 755 git-remote-isx "$PKG_DIR/usr/bin/git-remote-isx"
"$PKG_DIR/usr/bin/isx" completion bash > "$PKG_DIR/usr/share/bash-completion/completions/isx"
"$PKG_DIR/usr/bin/isx" completion zsh  > "$PKG_DIR/usr/share/zsh/vendor-completions/_isx"
"$PKG_DIR/usr/bin/isx" completion fish > "$PKG_DIR/usr/share/fish/vendor_completions.d/isx.fish"
dpkg-deb --root-owner-group --build "$PKG_DIR"

# Then clone isx-apt-releases, copy debs into pool/, and regenerate indices
```

## GPG Key Setup (one-time)

The APT repository is signed with a dedicated GPG key. To set it up:

```bash
# Generate a key without a passphrase (required for non-interactive CI use)
gpg --batch --gen-key <<EOF
  Key-Type: RSA
  Key-Length: 4096
  Name-Real: incus-spawn APT signing
  Name-Email: your-email@example.com
  Expire-Date: 0
  %no-protection
EOF

# Find the key ID (the hex string after "rsa4096/")
gpg --list-secret-keys --keyid-format long

# Export the private key (add as APT_GPG_PRIVATE_KEY GitHub Actions secret)
gpg --export-secret-keys --armor KEY_ID

# The public key is exported automatically by the workflow to public.gpg
```

After generating, add the armored private key as the `APT_GPG_PRIVATE_KEY` secret in the repository's GitHub Actions settings.

## Repository Setup (one-time)

1. Create the `Sanne/isx-apt-releases` repository on GitHub (can be empty)
2. Enable GitHub Pages in its settings: Source **Deploy from a branch**, branch **main** / root (`/`)
3. Add an `APT_REPO_TOKEN` secret to `Sanne/incus-spawn` — a PAT with `contents: write` permission on `Sanne/isx-apt-releases` (same pattern as `HOMEBREW_TAP_TOKEN` for the Homebrew tap)

## Supported Platforms

The APT repository supports `amd64` (x86_64) and `arm64` (aarch64) on any Debian-based distribution. A single `stable` suite is used because the GraalVM native binary has no distribution-specific dependencies.
