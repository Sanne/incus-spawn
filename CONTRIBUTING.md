# Contributing

## Building from source

```shell
# Build
mvn package

# Run tests
mvn test                        # unit tests (no Incus needed)
mvn verify -DskipITs=false      # integration tests (requires Incus)

# Install locally
./install.sh            # JVM
./install.sh --native   # native (requires Docker, Podman, or GraalVM)
```

Snapshot builds display the nearest reachable Git tag matching `v[0-9]*` without its leading `v`, followed by `+` and the number of commits since that tag, for example `1.2.3+5`.
Builds exactly at a tag retain a `+0` snapshot marker, for example `1.2.3+0`; uncommitted changes do not affect the version.
The full commit hash is still shown in parentheses.
With no reachable tag, or with Git older than 2.35, the version falls back to the POM's snapshot version.
Release builds keep the POM version.
The Git description affects only the displayed version; template stamps and version comparisons keep the POM version.

To try a change against a real Incus on a disposable Linux machine (a scratch VM, an isx instance), install it with `./install.sh` and run `scripts/local-incus.sh`: it sets Incus up the way CI's integration job does.

## Website Development

The project website is hosted on Cloudflare Pages (project `isx`): `.github/workflows/pages.yml` deploys `main` to production and `preview.yml` deploys each PR to its own preview URL. See [docs/dev/WEBSITE.md](docs/dev/WEBSITE.md) for the Cloudflare setup. The build script requires Node.js (for `npx` and `node`). Install it from [nodejs.org](https://nodejs.org/) or via your package manager (`brew install node`, `dnf install nodejs`, `apt install nodejs npm`).

To preview changes locally:

```shell
# Build the site (generates _site/ directory from README.md and docs/*.md)
./site/build.sh

# Serve it the way Cloudflare Pages does (/docs serves docs.html)
npx wrangler pages dev _site
```

Then open the address it prints in your browser. The build script renders README.md as the docs page and every `docs/*.md` as a page beside it (`docs/CLI.md` is `/docs/cli`), each with its table of contents; `docs/dev/` holds maintainer notes and is not published.

## Releasing

### Branch Strategy

Development uses two long-lived branches:

- **`stable`** — production releases. This is what users get from the default Homebrew formula, COPR repo, and APT suite. Only merge to `stable` when the release is ready for general use.
- **`main`** — development branch. Releases from `main` go to separate dev channels on every package manager, so they never affect stable users.

The release workflow detects the channel automatically from the tag name — no configuration changes are needed to switch between stable and dev releases:

| Tag format | Channel | Example |
|---|---|---|
| Clean semver | Stable | `v0.3.0` |
| `-dev.N` suffix | Dev | `v0.3.0-dev.1`, `v0.3.0-dev.2` |

### Creating a Release

```shell
./release.sh           # auto-derives version from latest tag
./release.sh 0.3.0     # explicit version
```

The script enforces branch/tag consistency:
- On `stable`: only clean semver tags are allowed (`v0.3.0`)
- On `main`: only `-dev.N` tags are allowed (`v0.3.0-dev.1`)
- Releasing from any other branch is rejected

When no version is given, the script increments the patch from the latest tag and appends `-dev.1` on `main`.

### What the Workflow Does

Pushing a tag triggers a workflow that will:
1. Detect the release channel from the tag (see table above)
2. Set the project version from the tag
3. Build self-contained uber-jars for both `isx` and `isx-proxy` (for JBang users)
4. Build native binaries via GraalVM (Linux amd64/aarch64, macOS aarch64/x86_64) for both `isx` and `isx-proxy`
5. Create a GitHub Release (marked as pre-release for dev tags)
6. Update the [Homebrew tap](https://github.com/Sanne/homebrew-tap): `incus-spawn.rb` for stable, `incus-spawn-dev.rb` for dev
7. Publish RPM to [Fedora COPR](https://copr.fedorainfracloud.org/coprs/sanne/incus-spawn/): `incus-spawn` project for stable, `incus-spawn-dev` for dev
8. Publish `.deb` packages to the [APT repository](https://sanne.github.io/isx-apt-releases): `stable` suite for stable, `dev` suite for dev
9. Submit a [Nixpkgs](https://github.com/NixOS/nixpkgs) PR with updated hashes (stable only, currently disabled)
10. Bump the POM version to the next snapshot

### Channel Isolation

Dev releases are fully isolated from stable — a dev release cannot affect users on the stable channel:

- **GitHub Releases**: dev tags are marked as pre-releases and excluded from `latest`
- **Homebrew**: separate formulas (`incus-spawn` vs `incus-spawn-dev`) with `conflicts_with` to prevent co-installation
- **COPR**: separate projects (`incus-spawn` vs `isx-dev`)
- **APT**: separate pool directories (`pool/stable/` vs `pool/dev/`) and suites (`dists/stable/` vs `dists/dev/`), so `rm` during publish only touches that channel's debs

### Prerequisites for Dev Channel

Before the first dev release, these one-time steps are needed:

1. **COPR**: Create the `isx-dev` project at copr.fedorainfracloud.org (same settings as `incus-spawn`)
2. **Homebrew tap**: Ensure `Formula/incus-spawn-dev.rb` exists in `Sanne/homebrew-tap`

### Installing Dev Builds

See [docs/dev/HOMEBREW.md](docs/dev/HOMEBREW.md) and [docs/dev/APT.md](docs/dev/APT.md) for per-platform dev channel instructions. Summary:

```shell
# macOS (Homebrew)
brew install Sanne/tap/incus-spawn-dev

# Fedora (COPR)
sudo dnf copr enable sanne/isx-dev
sudo dnf install incus-spawn

# Ubuntu/Debian (APT)
# Use "dev" instead of "stable" in the sources list:
echo "deb [signed-by=...] https://sanne.github.io/isx-apt-releases dev main" | sudo tee /etc/apt/sources.list.d/incus-spawn.list
sudo apt update && sudo apt install incus-spawn
```

Users can then update via `brew upgrade` (macOS), `dnf upgrade` (Fedora), `apt upgrade` (Ubuntu/Debian), `curl -fsSL .../get-isx.sh | sh` (native), or `jbang app install isx@Sanne/incus-spawn` plus `jbang app install isx-proxy@Sanne/incus-spawn` (JVM — both aliases, since the CLI cannot serve the proxy in-process).

### Switching Between Dev and Stable Channels

The two channels use separate packages/repos, so switching means uninstalling one and installing the other:

```shell
# macOS (Homebrew) — the formulas conflict, so uninstall first
brew uninstall incus-spawn-dev
brew install Sanne/tap/incus-spawn
# (reverse to go back to dev)

# Fedora (COPR)
sudo dnf copr disable sanne/isx-dev
sudo dnf copr enable sanne/incus-spawn
sudo dnf reinstall incus-spawn
# (reverse the enable/disable to go back to dev)

# Ubuntu/Debian (APT) — change "dev" to "stable" in the sources list
sudo sed -i 's/ dev main/ stable main/' /etc/apt/sources.list.d/incus-spawn.list
sudo apt update && sudo apt install incus-spawn
# (change "stable" back to "dev" to go back to dev)
```
