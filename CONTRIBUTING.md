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

## Website Development

The project website is hosted on GitHub Pages. To preview changes locally:

```shell
# Build the site (generates _site/ directory from README.md)
./site/build.sh

# Serve locally
cd _site && python3 -m http.server 8000
```

Then open http://localhost:8000 in your browser. The build script converts README.md to HTML and generates the table of contents for the docs page.

## Releasing

Releases are automated via GitHub Actions. To create a new release, run:

```shell
./release.sh
```

The script derives the version from the POM snapshot (e.g. `0.1.9-SNAPSHOT` → `v0.1.9`), validates the working tree, creates the tag, and pushes it. You can also pass an explicit version: `./release.sh 0.2.0`.

Pushing the tag triggers a workflow that will:
1. Set the project version from the tag
2. Build a self-contained uber-jar (for JBang users)
3. Build native binaries via GraalVM (Linux amd64/aarch64, macOS aarch64)
4. Create a GitHub Release with auto-generated release notes and all artifacts attached
5. Update the [Homebrew tap](https://github.com/Sanne/homebrew-tap) with new checksums
6. Publish the native binary as an RPM to [Fedora COPR](https://copr.fedorainfracloud.org/coprs/sanne/incus-spawn/)
7. Bump the POM version to the next snapshot

Users can then install or update via `brew upgrade` (macOS), `dnf upgrade` (Fedora), `curl -fsSL .../get-isx.sh | sh` (native), or `jbang app install isx@Sanne/incus-spawn` (JVM).
