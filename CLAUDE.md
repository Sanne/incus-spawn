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

Both `cli` and `proxy` are independent Quarkus applications that produce separate native binaries. When `isx-proxy` is not installed, `isx proxy start` falls back to running the proxy inline within the CLI process.

Detailed architecture docs are in `.claude/rules/` and load automatically when you work on related files. Each rule file declares `paths:` globs that trigger it. When adding new source packages, renaming files, or restructuring modules, check whether `.claude/rules/` path globs need updating -- stale paths silently stop loading context. Prefer package-level globs (`incus/**`) over specific files; use specific files only for cross-cutting triggers (e.g. `BuildCommand.java` in `incus.md` to ensure pool-awareness context loads during build work).
