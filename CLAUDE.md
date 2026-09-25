# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

incus-spawn (`isx`) is a CLI tool for managing isolated Incus-based development environments. It creates full Linux system containers (not Docker-style app containers) with copy-on-write branching, a MITM TLS proxy for credential isolation, and an interactive TUI. See README.md for user-facing docs, DESIGN.md for architecture rationale, and [docs/CHARACTER.md](docs/CHARACTER.md) for the project's mission and design philosophy.

**Keep docs in sync**: When making architectural changes (new proxy capabilities, new tool types, new init steps, module structure changes, CI job changes, new intercepted domains, etc.), update both this file (and its `.claude/rules/` topic files) and DESIGN.md in the same PR. CLAUDE.md is the quick-reference for contributors; DESIGN.md is the full rationale. Both must stay current.

## Build and Test Commands

```shell
mvn package                    # Build both modules (CLI: cli/target/, proxy: proxy/target/)
mvn test                       # Unit tests only (no Incus required)
mvn verify -DskipITs=false     # Unit + integration tests (requires running Incus)
mvn test -Dtest=ToolDefTest    # Run a single test class
mvn test -Dtest=ToolDefTest#testAllFields  # Run a single test method

mvn package -Dnative -DskipTests           # GraalVM native binaries (isx + isx-proxy)

./install.sh                   # Build and install JVM version to ~/.local/bin/isx
./install.sh --native          # Build and install native binaries
```

## Tech Stack

- **Java 25**, **Quarkus 3.x** with aesh for CLI commands
- **Tamboui** for the interactive TUI (terminal UI framework)
- **Jackson YAML** for configuration/definition parsing
- **Quarkus CDI** for dependency injection (tool discovery, command wiring)

## Module Structure

Three Maven modules under a parent POM:

- **`common`** (`incus-spawn-common`): shared code -- Incus client, proxy config, image/tool definitions, configuration loading. Not a Quarkus app; uses the Jandex Maven plugin to produce a `META-INF/jandex.idx` so Quarkus discovers its CDI beans and `@RegisterForReflection` annotations from dependent modules.
- **`cli`** (`incus-spawn`): the main CLI/TUI binary (`isx`). Depends on common. Native image: serial GC, `-Os` (size-optimized), `-H:-AllowVMInternalThreads`,
  and on x86_64 `-march=haswell` (arch-gated in `cli/pom.xml`, same as the proxy). It downloads tool tarballs and VM images over HTTPS, and the default
  `x86-64-v3` omits AES/CLMUL: 79 MB/s vs ~3100 MB/s for AES-256-GCM. Binary size is byte-identical and startup ~11% faster, so it costs nothing here.
- **`proxy`** (`incus-spawn-proxy`): the standalone MITM proxy binary (`isx-proxy`). Depends on common. Native image: G1 GC, `-O3` (throughput-optimized, enables ML-inferred PGO), and on x86_64 `-march=haswell`.
  The `-march` value is set by arch-gated Maven profiles in `proxy/pom.xml` (empty on aarch64, where an x86 `-march` would fail the build).
  GraalVM's default `-march=x86-64-v3` omits AES and CLMUL, so the image cannot use AES-NI/GHASH intrinsics and TLS falls back to software AES --
  measured 73 MB/s vs 960 MB/s serving a cached Maven artifact (~13x). `haswell` costs no hardware support: AES-NI predates the v3 baseline by three
  years. `skylake` (+ADX) measures indistinguishably, so its narrowing is not worth taking. Do not "upgrade" this to `x86-64-v4`: the numbered levels
  never include AES, so v4 measures identically to v3 while dropping non-AVX-512 hardware.

Both `cli` and `proxy` are independent Quarkus applications that produce separate native binaries. The CLI has no Vert.x dependency, so it **cannot** run the proxy in-process: every install channel must ship both binaries, and `isx proxy start` exits `EXIT_CONFIG` (78) with install instructions when `isx-proxy` is missing -- before touching the service, since restarting a service whose binary is absent cannot help. On macOS that exit code is not enough (launchd has no `RestartPreventExitStatus`), so `haltUnusableMacOsService()` boots the job out and removes the plist unless the proxy is still answering. The unit and macOS plist exec `isx-proxy` directly; for units written by older builds, which exec `isx proxy start`, `ProxyService.isSupervisedInvocation()` matches systemd's `INVOCATION_ID` against the proxy unit's own so the command never manages the service it is itself running under. Both exist because a unit exec'ing `isx proxy start` restarts itself forever (issue #701). JBang installs each alias separately, so `jbang-catalog.json` publishes `isx` **and** `isx-proxy`; `JbangCatalogTest` fails if an alias names an asset `gh release create` does not upload.

**YAML tool downloads:** `ToolDef` download entries may set `extract`, `destination_file`, or both. Downloads are cached on the host, then either extracted into the target, copied to the exact destination path, or processed both ways. VM builds use mount-and-copy rather than slow incus-agent file pushes over vsock.

**Secrets are declared, not listed.** `SecretRegistry` (`common/config/`) answers "where do secrets live in `config.yaml`" by collecting every tool's `ToolDef.ConfigEntry` that sets `secret: true` with a `config-path` -- so a new credential becomes known to every secret-aware consumer by being declared on the tool (`ToolSetup.proxy()` or tool YAML), never by editing a list. A declared leaf also holds anywhere under its namespace, which is what covers `claude.accounts.<name>.apiKey`. `SecretRedactor` owns the other half -- what a credential *looks like*: the whole-word key-name backstop for undeclared keys in `SpawnConfig.extras`, the token-shape patterns, and the `<isx:redacted:<path>>` marker. `isx doctor --bundle` (`SupportBundle`) is the first consumer: the config is redacted structurally, every other file is scrubbed, and `SupportBundle.add()` is the only way into the archive so a new collector cannot leak by forgetting. See DESIGN.md "Support bundles: redaction by construction" and `.claude/rules/config.md`.

**Project-local definitions are untrusted.** `.incus-spawn/` is whatever a cloned repository ships, so project-local templates get only what stays inside their project (#765). Their host-resources and `file://` base images are confined to the project on real paths (`HostResourceSetup.collectEffective()`, re-checked at build, branch and start). Their `image_url` may only replace an image their own project imported (the `incus-spawn.project` image stamp, which fails closed). Their repos never get host checkout mounts or host-side clones. For every definition, `DownloadCache.download()` is `http(s)`-only and refuses loopback and link-local hosts on every redirect hop, and no `prime` runs while any host checkout is mounted. A new capability that reaches host state outside the project needs the same gate. See DESIGN.md "Project-local templates are confined to their project" and `.claude/rules/config.md`.

**Native image: host paths belong in the run-time-initialized classes.** Quarkus initializes
application classes at image-build time unless they are listed in `--initialize-at-run-time`, and
Linux native builds run as **root inside the GraalVM builder container** — so a field holding an
`Environment` path bakes the builder's `/root/...` into the binary (that regression shipped: see
DESIGN.md "Build-time initialization must not capture host paths"). Eagerly resolved host state goes
in `RuntimeConstants` (`common`) or `RuntimeServices` (`cli`), both on the flag; everywhere else call
the `Environment` method instead of storing its result. The flag is declared three times (each
module's `resources-filtered/application.properties` plus `cli/pom.xml`'s `macos-native` profile) --
`NativeImageInitializationTest` fails if one drifts, and `graal/BakedHostStateFeature` fails the
native build if such a path reaches the image heap.

Detailed architecture docs are in `.claude/rules/` and load automatically when you work on related files. Each rule file declares `paths:` globs that trigger it. When adding new source packages, renaming files, or restructuring modules, check whether `.claude/rules/` path globs need updating -- stale paths silently stop loading context. Prefer package-level globs (`incus/**`) over specific files; use specific files only for cross-cutting triggers (e.g. `BuildCommand.java` in `incus.md` to ensure pool-awareness context loads during build work).
