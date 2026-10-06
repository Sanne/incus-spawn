# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

incus-spawn (`isx`) is a CLI tool for managing isolated Incus-based development environments. It creates full Linux system containers (not Docker-style app containers) with copy-on-write branching, a MITM TLS proxy for credential isolation, and an interactive TUI. See README.md for user-facing docs, DESIGN.md for architecture rationale, and [docs/CHARACTER.md](docs/CHARACTER.md) for the project's mission and design philosophy.

**Keep notes in the repository**: the machines this is developed on are wiped regularly, so anything worth remembering between sessions goes in a committed file (this one, `.claude/rules/`, DESIGN.md), never only in local agent memory.

**The project spans several repositories, and the technical work is delegated.** `isx` is only part of it: the base images live in [`incus-spawn-images`](https://github.com/Sanne/incus-spawn-images), the template definitions in [`incus-spawn-templates`](https://github.com/Sanne/incus-spawn-templates), and the install channels in [`homebrew-tap`](https://github.com/Sanne/homebrew-tap) and [`isx-apt-releases`](https://github.com/Sanne/isx-apt-releases). When a fix belongs in one of them, open the companion PR *there* and cross-link it with the issue here -- a change that only lands in this repo will not reach users whose breakage came from an image or a template (#842 / incus-spawn-images#15 is the worked example: the symptom was `isx shell` timing out, the fix was one line in a base image). The investigation and the code belong to an agent running in an isx instance, not to the session holding the conversation: branch one (`isx branch --from tpl-isx <name>`) and drive it headless with `incus exec <name> -- su - agentuser -c 'claude -p "<brief>" --permission-mode bypassPermissions'`, then keep only its conclusion. Probing instances, running package managers and reading build output by hand fills the conversation with detail that belongs inside the instance.

**Base-image checksums are verified against the release, never trusted.** The `image_sha256` and `vm_image_sha256` values in `common/src/main/resources/images/minimal.yaml` are what makes a base-image download safe, so a wrong one is either a broken install or a silently unverified image. They have been wrong on `main` more than once -- most recently `fedora-44-20261001`, merged wrong in `99d8d9dc` and repaired in `cf7be662`. The cause is structural: `update-base-image.yml` has two paths, and only the manual backstop derives the sums from the release's own `SHA256SUMS`. The `repository_dispatch` path -- the one that actually runs -- takes the payload the images repo sent and comments "trust the payload". **Never merge a base-image bump on the payload alone.** Check all four against the authoritative asset, and mind that the pairing is as easy to get wrong as the digits (`-vm` is the VM disk, the bare name is the container rootfs, and each has an x86_64 and an aarch64):

```shell
gh release download <tag> -R Sanne/incus-spawn-images -p SHA256SUMS -O -
```

Four lines, four fields, and every one has to match the asset it names. The same applies to a tool download added to `ToolDef` or a tool YAML: take the checksum from the publisher, never from a build log or another PR.

**Latency on the branch/start/shell path is never noise.** A container starts in well under a second, so a few milliseconds or a handful of redundant Incus round trips are a real share of the wait, and each round trip costs far more over the macOS vsock tunnel. When a flow repeats a read, fix it rather than weighing whether it is worth it. Pin the result in a request-budget test (such as `InstanceLifecycleRequestBudgetTest`), and trace it against a real Incus with `bench/trace-branch.sh`, since `FakeIncusDaemon` does not see what the proxy health check and refresh ask for.

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

The JVM install runs straight from this checkout: its launchers `java -jar` `cli/target/quarkus-app/quarkus-run.jar` and `proxy/target/quarkus-app/quarkus-run.jar`, so rebuilding or `mvn clean` here changes or breaks the installed `isx` and a running proxy. Install from the main checkout, never from a worktree you will delete; `--native` copies the binaries instead.

To check a change against a real Incus on a disposable Linux host, `scripts/local-incus.sh` sets one up the way CI's `isx-integration-tests-native` job does, and the `validate-on-real-incus` skill (`.claude/skills/`) covers what to exercise and inspect.

## Tech Stack

- **Java 25**, **Quarkus 3.x** with aesh for CLI commands
- **Tamboui** for the interactive TUI (terminal UI framework)
- **Jackson YAML** for configuration/definition parsing
- **Quarkus CDI** for dependency injection (tool discovery, command wiring)

## Module Structure

Three Maven modules under a parent POM:

- **`common`** (`incus-spawn-common`): shared code -- Incus client, proxy config, image/tool definitions, configuration loading. Not a Quarkus app; uses the Jandex Maven plugin to produce a `META-INF/jandex.idx` so Quarkus discovers its CDI beans and `@RegisterForReflection` annotations from dependent modules. Its test classes are also published as a test-jar, which `cli` depends on in test scope, so `cli` tests drive commands against `FakeIncusDaemon`.
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

**Credential accounts are per config namespace, not per tool.** A namespace (`claude`, `github`, ... -- whatever a tool declares as `config-namespace`) holds `accounts:` and `default:`. Which account an instance uses is decided in three layers, lowest first: that `default`, the template's `accounts:` map (merged per key down the chain), then `isx branch --account <ns>=<account>` / `isx account set`, stamped as `user.incus-spawn.account.<ns>`. The generic seam is `ProxyDef.accountConfigPath()` + `ToolProxyResolver`, so adding accounts to a new credential needs **no new mechanism** -- only config and init UX. Selection is always `<ns>=<account>`; never accept a bare account name, which would silently fan out across namespaces added later. Resolution **fails closed**: a pin to a missing account errors rather than serving the default. Every namespace, Claude included, follows one model (#773): a flat, pre-accounts credential *presents as the account `default`*, and writes always produce the accounts layout. What differs between namespaces is declared as an `AccountShape` (`ToolSetup.accountShape()`), never branched on by name. The proxy identifies the caller by source address (static IP + `security.ipv4_filtering`, which is load-bearing, not optional hardening). A per-start secret (`InstanceSecret`, #934) adds a second check for host-side services: `InstanceRegistry.identify()` wants address *and* secret; only its hash is recorded on the host, and every isx start rotates it, for one PATCH on a stopped start and none on a branch (`InstanceLifecycle.startForUse`). A container start, a branch's included, then records its boot (`last_used_at`, read once the guest answers and handed on to the CA check, so the stamp's write is the one request it adds), so `ensureReady` replaces a secret lost to a reboot isx did not do at no cost on the shell path; a VM's agent probe asks the guest instead (#1024). See `.claude/rules/proxy.md` and DESIGN.md.

**`isx init` prompts read from `Prompts`, never a `Console`.** `java.io.Console` is final, so code taking one cannot be driven by a test, and CI's `isx init </dev/null` skips every credential prompt (#772). Each credential step has a thin entry that gets `Prompts.console()` and a package-private `(SpawnConfig, Prompts)` overload holding the logic; network and host calls (token verification, `gh`, the browser, env vars) are overridable methods on `InitCommand`. Flow tests drive them with `ScriptedPrompts` under `@ExtendWith(IsolatedHome.class)` and assert on the `config.yaml` written to disk. Secrets go through `askSecret()` and are shown only via `maskSecret()`, which never reveals more than a third of one. A new prompt follows the same shape and gets a flow test. See `.claude/rules/commands.md` and DESIGN.md "Testing".

**Never print straight to stdout/stderr from code the TUI can reach.** While the TUI renders it owns the terminal: a `System.out`/`System.err` line is drawn over and lost, and scrambles the screen. Shared code (`common`, especially `lifecycle/` and `incus/`) that reports progress or warnings takes a sink instead -- a `Consumer<String>` (`InstanceLifecycle.prepareHostDevicesForStart(..., warn)`, `ensureReady(..., say)`) or `StaticIpAllocator.Output` -- and each caller routes it: the CLI to `System.out::println`, the TUI to its warning log. Pass plain stdout from the TUI only where it has provably released the terminal (e.g. `shellInto`, after the runner closes), and say so in a comment. Code with no caller to take a sink from -- e.g. a definition loader built several layers down, as `ToolProxyResolver` builds `ToolDefLoader`s -- reports through `Warnings.warn()` (`common`): each distinct message once per channel (a long-running process that re-reads definitions calls `Warnings.forgetReported()`, as the proxy does on each reload), to stderr by default, and into the TUI's `WarningLog` while the TUI draws or reloads (#872). In the TUI, a warning sink is `warningLog::add`, never the status line directly: the status line holds one message until the next key, so warnings from several operations would overwrite each other.

**Output a script reads is a contract.** `--format=plain|json` (so far on `isx list`) goes through `OutputFormat` (`common/util`): one ordered map per record feeds both formats; fields are added, never renamed, removed or reordered; stdout holds only results, never colour, glyphs, progress or prose. `table` is for people and may change. `isx list` from the CLI costs one Incus request and loads no definitions; `isx instances` is a deprecated alias for `isx list -q`. See `.claude/rules/commands.md` and DESIGN.md "Output for scripts".

**YAML tool downloads:** `ToolDef` download entries may set `extract`, `destination_file`, or both. Downloads are cached on the host, then either extracted into the target, copied to the exact destination path, or processed both ways. VM builds use mount-and-copy rather than slow incus-agent file pushes over vsock.

**Secrets are declared, not listed.** `SecretRegistry` (`common/config/`) answers "where do secrets live in `config.yaml`" by collecting every tool's `ToolDef.ConfigEntry` that sets `secret: true` with a `config-path` -- so a new credential becomes known to every secret-aware consumer by being declared on the tool (`ToolSetup.proxy()` or tool YAML), never by editing a list. A declared leaf also holds anywhere under its namespace, which is what covers `claude.accounts.<name>.apiKey`. `SecretRedactor` owns the other half -- what a credential *looks like*: the whole-word key-name backstop for undeclared keys in `SpawnConfig.extras`, the token-shape patterns, and the `<isx:redacted:<path>>` marker. `isx doctor --bundle` (`SupportBundle`) is the first consumer: the config is redacted structurally, every other file is scrubbed, and `SupportBundle.add()` is the only way into the archive so a new collector cannot leak by forgetting. See DESIGN.md "Support bundles: redaction by construction" and `.claude/rules/config.md`.

**Project-local definitions are untrusted.** `.incus-spawn/` is whatever a cloned repository ships, so project-local templates get only what stays inside their project (#765). Their host-resources and `file://` base images are confined to the project on real paths (`HostResourceSetup.collectEffective()`, re-checked at build, branch and start). Their `image_url` may only replace an image their own project imported (the `incus-spawn.project` image stamp, which fails closed). Their repos never get host checkout mounts or host-side clones. Their `gui: true` is never a branch default (GUI hands over the host's GPU and `XDG_RUNTIME_DIR`; `--gui` still can). For every definition, `DownloadCache.download()` is `http(s)`-only and refuses loopback and link-local hosts on every redirect hop, and no `prime` runs while any host checkout is mounted. A new capability that reaches host state outside the project needs the same gate. See DESIGN.md "Project-local templates are confined to their project" and `.claude/rules/config.md`.

**`isx mcp` gives a local agent a narrow slice of isx.** It serves MCP over stdio (hand-rolled JSON-RPC in `cli/.../mcp/`: the Quarkus MCP extension cost every isx command ~1.7 ms at startup). An agent may branch only templates listed in `config.yaml`'s `mcp:` section (read per call, never written by the package -- `McpNoWritePathTest`), never project-local ones, always with template defaults via `BranchFlow` -- or fork a stopped instance it holds (`create_instance(from_instance)`, #1013), which is `isx branch --from <instance>` with that instance's lineage re-checked. Instances belong to the host user and are held by one session (`<pid>-<start>`), stamped by the copy request itself and checked on every tool call; a session ending releases them as orphans, which a later session can `adopt_instance` (tasks included) and which are destroyed only after `mcp.orphan-grace-hours` with nobody in them (#898). `exec` has no default time limit (cancel kills the process tree); background commands and `delegate` (the Claude Code inside the instance) run as systemd units in the guest; a client that lists `isx/task_changed` under `capabilities.experimental` is notified of every task state change (`TaskWatcher`, #1014), and only then are tasks polled. Stdout is the protocol: `StdioGuard` redirects `System.out` to stderr and `Headless` disables prompts. See `.claude/rules/mcp.md` and DESIGN.md "MCP server".

**Native image: host paths belong in the run-time-initialized classes.** Quarkus initializes
application classes at image-build time unless they are listed in `--initialize-at-run-time`, and
Linux native builds run as **root inside the GraalVM builder container** — so a field holding an
`Environment` path bakes the builder's `/root/...` into the binary (that regression shipped: see
DESIGN.md "Build-time initialization must not capture host paths"). Eagerly resolved host state goes
in `RuntimeConstants` (`common`) or `RuntimeServices` (`cli`), both on the flag; everywhere else call
the `Environment` method instead of storing its result. The flag is declared once per module, in its
`resources-filtered/application.properties`; pom profiles add platform arguments through placeholders
the list includes and never redefine the list itself (#489) -- `NativeImageInitializationTest` fails
if a list stops deferring a class or registering a guard, or a pom redefines it, and
`graal/BakedHostStateFeature` fails the native build if such a path reaches the image heap.

Detailed architecture docs are in `.claude/rules/` and load automatically when you work on related files. Each rule file declares `paths:` globs that trigger it. When adding new source packages, renaming files, or restructuring modules, check whether `.claude/rules/` path globs need updating -- stale paths silently stop loading context. Prefer package-level globs (`incus/**`) over specific files; use specific files only for cross-cutting triggers (e.g. `BuildCommand.java` in `incus.md` to ensure pool-awareness context loads during build work).
