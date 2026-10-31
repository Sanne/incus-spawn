# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Full Git clone acceleration is specified in [Git preparation](docs/dev/GIT-PREPARATION.md): remote-authoritative selection, bare reference/dissociation, immutable bounded clean snapshots and reader leases.
Borrow host objects directly, without a preliminary pack copy; a failed reference attempt retries once from the remote before publication.
Refresh verified snapshots incrementally when their old history remains selected, sharing owned packs and validating new objects; removed history and pack compaction use a clean rebuild.
Never run build-time fetch or maintenance in a user checkout, or mount its full object store for a full clone.
Network-source preparation accepts only HTTPS, SSH and Git network URLs; file transport and remote helpers are blocked even after URL rewrites.
Check the guest's template-account access before preparing and match its complete advertised selection before mounting any snapshot, including warm hits.
Complete all access checks before priming; only raw shallow references gate other repos' primes once preparation begins.
The shallow transport/fixup path remains separate.
Explicit `repos[].host-source` uses a separate offline export of local branches/tags and their reachable objects, including unpushed history.
It never copies the source object store wholesale, fetches its remote, mounts its checkout or falls back to the network.
Only a leased independent prepared repository enters the guest; checksum verification and detachment precede prime.
Host sources require full history and trusted templates; project-local definitions remain unable to prepare host repositories.
Resolved source associations are stamped at build for automatic remotes, with host-owned cleanup records that survive instance or template deletion.


## Project Overview

incus-spawn (`isx`) is a CLI tool for managing isolated Incus-based development environments.
It creates full Linux system containers (not application containers) with copy-on-write branching, a MITM TLS proxy for credential isolation, and an interactive TUI.
See README.md and the pages in `docs/` (published on isx.run beside it; maintainer notes are in `docs/dev/`) for user-facing docs, DESIGN.md for architecture rationale, and [docs/dev/CHARACTER.md](docs/dev/CHARACTER.md) for the project's mission and design philosophy.

**Keep notes in the repository**: the machines this is developed on are wiped regularly, so anything worth remembering between sessions goes in a committed file (this one, `.claude/rules/`, DESIGN.md), never only in local agent memory.
Changes an existing host needs by hand, such as moving the CoW pool off a loop file, go in [docs/dev/MIGRATIONS.md](docs/dev/MIGRATIONS.md).

**List every divergence from stock Fedora in [docs/FEDORA.md](docs/FEDORA.md)** (published as isx.run/docs/fedora), in the same change that makes it.
That covers a masked unit, a changed default or a replaced file, whether it comes from isx, a built-in template or the base images.

**The project spans several repositories, and the technical work is delegated.**
`isx` is only part of it: the base images live in [`incus-spawn-images`](https://github.com/Sanne/incus-spawn-images), the template definitions in [`incus-spawn-templates`](https://github.com/Sanne/incus-spawn-templates), and the install channels in [`homebrew-tap`](https://github.com/Sanne/homebrew-tap) and [`isx-apt-releases`](https://github.com/Sanne/isx-apt-releases).
When a fix belongs in one of them, open the companion PR *there* and cross-link it with the issue here.
A change that only lands in this repo will not reach users whose breakage came from an image or a template (#842 / incus-spawn-images#15 is the worked example: the symptom was `isx shell` timing out, the fix was one line in a base image).
The investigation and the code belong to an agent running in an isx instance, not to the session holding the conversation.
Branch one (`isx branch --from tpl-isx <name>`) and drive it headless with `incus exec <name> -- su - agentuser -c 'claude -p "<brief>" --permission-mode bypassPermissions'`, then keep only its conclusion.
Probing instances, running package managers and reading build output by hand fills the conversation with detail that belongs inside the instance.

**Base-image checksums are verified against the release, never trusted.**
The `image_sha256` and `vm_image_sha256` values in `common/src/main/resources/images/minimal.yaml` are what makes a base-image download safe, so a wrong one is either a broken install or a silently unverified image.
They have been wrong on `main` more than once -- most recently `fedora-44-20261001`, merged wrong in `99d8d9dc` and repaired in `cf7be662`.
The cause was structural: the `repository_dispatch` path of `update-base-image.yml` copied the sums from the payload the images repo sent.
Both paths now derive them from the release's own sums files and stop on a payload that disagrees, and `verify-base-image-checksums.yml` checks all four on every PR that changes `minimal.yaml` (#1678).
**Never merge a base-image bump whose `verify-base-image-checksums` job did not run or did not pass.**
Mind that the pairing is as easy to get wrong as the digits (`-vm` is the VM disk, the bare name is the container rootfs, and each has an x86_64 and an aarch64); the job checks it, and its log and the bump's PR give the command to repeat the check:

```shell
gh release download <tag> -R Sanne/incus-spawn-images -p SHA256SUMS -O -
```

Four lines, four fields, and every one has to match the asset it names.
When the bump moves `vm_image_url` to `-vm-direct.tar.xz` (its PR says `VM image: direct`), the two VM sums come from `SHA256SUMS.v2` instead (`-p SHA256SUMS.v2`), which lists those as well as the four.
The same applies to a tool download added to `ToolDef` or a tool YAML, to the appliance kernel's `KERNEL_SHA256` in `appliance/kernel/build-kernel.sh` (from kernel.org's signed `sha256sums.asc`), and to `INCUS_SHA512` in `appliance/build.sh` (from Alpine's `incus-feature` APKBUILD): take the checksum from the publisher, never from a build log or another PR.

**Latency on the branch/start/shell path is never noise.**
A container starts in well under a second, so a few milliseconds or a handful of redundant Incus round trips are a real share of the wait.
A read costs well under a millisecond, on Linux and over the macOS vsock tunnel alike (DESIGN.md "CLI latency on macOS"); a settings write costs about 10 ms: 6-10 ms on macOS, 11-13 ms on Linux btrfs.
When a flow repeats a read, fix it rather than weighing whether it is worth it.
Pin the result in a request-budget test (such as `InstanceLifecycleRequestBudgetTest`), and trace it against a real Incus with `bench/trace-branch.sh`, since `FakeIncusDaemon` does not see what the proxy health check and refresh ask for.

**Keep docs in sync**: When making architectural changes (new proxy capabilities, new tool types, new init steps, module structure changes, CI job changes, new intercepted domains, etc.), update both this file (and its `.claude/rules/` topic files) and DESIGN.md in the same PR.
CLAUDE.md is the quick-reference for contributors; DESIGN.md is the full rationale.
Both must stay current.

**Markdown source: one sentence per line, and short sentences** (#1243).
This applies to README.md, `docs/*.md`, DESIGN.md, this file and `.claude/rules/`, so that a diff shows exactly which sentence changed.
A sentence never wraps, and a line never holds two sentences.
In a list item, each sentence after the first goes on its own line, indented to the item's text; in a block quote, each line starts with `> `.
Headings, tables (a row stays on one line), code, HTML and front matter are exempt.
`DocsOneSentencePerLineTest` enforces this in `mvn test`.
README.md and `docs/*.md` also keep to the budgets in `.claude/rules/writing.md` (a paragraph of at most four sentences and 60 words, a sentence of at most 40, no dashes, no issue numbers), enforced by `DocsShapeTest`.
Their lists mirror the code's: every top-level command in the README's CLI table and `docs/CLI.md`, every built-in tool in the Tools page's table, every tool with a proxy credential in the README's credential table (`DocsListsTest`).
A new command or tool fails that test until its page names it.
Split a sentence of more than about 40 words where it splits cleanly; `.claude/rules/writing.md` "Splitting a sentence" lists the only word changes a split may make.

The `gemini-cli` tool installs and configures Google Gemini CLI through `GeminiSetup`.
Its optional `model` parameter leaves the upstream default unpinned, and a user policy auto-approves tools within isx isolation.
The policy applies to direct launches as well as the action, excludes plan mode, and allows higher-priority user overrides.
Each build writes the resolved template environment instructions to `~/.gemini/GEMINI.md` when Gemini is installed, leaving `context.fileName` unset.
With `pi` they go to `/AGENTS.md`, which Pi reads from every working directory, so the user's `~/.pi/agent/AGENTS.md` stays theirs.
They are stored once in `/etc/isx/agent-instructions.md`; Claude's `/etc/claude-code/CLAUDE.md` (only with the `claude` tool) and Pi's `/AGENTS.md` are hardlinks to it, a location in the home is a copy (`ToolSetup.environmentContextPath`, `AgentInstructions`).
Codex gets them rendered as `additional_developer_instructions` in `/etc/codex/requirements.toml` (`ToolSetup.renderEnvironmentContext`), never `~/.codex/AGENTS.md`.
Gemini API keys stay in host-side `gemini` accounts; the guest's `GEMINI_API_KEY` carries that namespace's proof, verified before the proxy injects `x-goog-api-key` at `generativelanguage.googleapis.com`.
Google OAuth and Vertex are outside this integration; see `.claude/rules/tools-env.md`.

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

The JVM install runs straight from this checkout: its launchers `java -jar` `cli/target/quarkus-app/quarkus-run.jar` and `proxy/target/quarkus-app/quarkus-run.jar`, so rebuilding or `mvn clean` here changes or breaks the installed `isx` and a running proxy.
Install from the main checkout, never from a worktree you will delete; `--native` copies the binaries instead.

To check a change against a real Incus on a disposable Linux host, `scripts/local-incus.sh` sets one up the way CI's `isx-integration-tests-native` job does, and the `validate-on-real-incus` skill (`.claude/skills/`) covers what to exercise and inspect.

Git preparation tests require `git daemon` (`git-daemon` on Fedora) and serve disposable loopback remotes.
Tests that run `git credential` or log in over HTTPS configure Git with `HermeticGit` (common test-jar), so no developer credential helper, such as macOS's osxkeychain, receives a test proof (#1505).

## Tech Stack

- **Java 25**, **Quarkus 4.x** with aesh for CLI commands (the proxy runs on Vert.x 5 and Netty 4.2; see [docs/dev/QUARKUS-4.md](docs/dev/QUARKUS-4.md))
- **Tamboui** for the interactive TUI (terminal UI framework)
- **jjq** (`io.hyperfoil.tools:jjq-*`) for YAML and JSON: definitions and `config.yaml` bind through generated `@JqMapped` mappings (`Jjq`), and JSON is read with `JqValues.parseStrict` when isx or Incus wrote it, and with `Jjq.parseForeign` (a lone surrogate escape reads as U+FFFD, nothing else is let through) when another program did
- **Quarkus CDI** for dependency injection (tool discovery, command wiring)

## Module Structure

Three Maven modules under a parent POM:

- **`common`** (`incus-spawn-common`): shared code -- Incus client, proxy config, image/tool definitions, configuration loading.
  Not a Quarkus app; uses the Jandex Maven plugin to produce a `META-INF/jandex.idx` so Quarkus indexes its classes from dependent modules, including the `@RegisterForReflection` on `SpawnConfig.ClaudeAccountType`: without the index the native image binds every Claude account `type:` to null while every JVM test passes.
  Its test classes are also published as a test-jar, which `cli` and `proxy` depend on in test scope, so `cli` tests drive commands against `FakeIncusDaemon` and `proxy` tests run Git through `HermeticGit`.
- **`cli`** (`incus-spawn`): the main CLI/TUI binary (`isx`).
  Depends on common.
  Native image: serial GC, `-Os` (size-optimized), `-H:-AllowVMInternalThreads`, on x86_64 `-march=haswell` and on aarch64 `-march=armv8.1-a+aes` (arch-gated in `cli/pom.xml`, same as the proxy).
  Release builds take a PGO profile and are then `-O3` (see "PGO" below).
  It downloads tool tarballs and VM images over HTTPS.
  On x86_64 the default `x86-64-v3` omits AES/CLMUL: 79 MB/s vs ~3100 MB/s for AES-256-GCM, with byte-identical binary size and ~11% faster startup, so it costs nothing here (x86_64 measurements; the CLI was not measured on aarch64).
- **`proxy`** (`incus-spawn-proxy`): the standalone MITM proxy binary (`isx-proxy`).
  Depends on common.
  Native image: G1 GC on Linux and serial GC on macOS (measured: G1 buys nothing on Apple Silicon, see `docs/dev/PERFORMANCE-NOTES.md`), `-O3` (throughput-optimized), on x86_64 `-march=haswell`, on aarch64 `-march=armv8.1-a+aes`.
  Without a profile `-O3` uses ML-inferred profiles; release builds take a real one (see "PGO" below).
  The `-march` value is set by arch-gated Maven profiles in `proxy/pom.xml` (one per arch: an x86 `-march` fails an aarch64 build and vice versa).
  x86_64: GraalVM's default `-march=x86-64-v3` omits AES and CLMUL, so the image cannot use AES-NI/GHASH intrinsics and TLS falls back to software AES -- measured 73 MB/s vs 960 MB/s serving a cached Maven artifact (~13x).
  `haswell` costs no hardware support: AES-NI predates the v3 baseline by three years.
  `skylake` (+ADX) measures indistinguishably, so its narrowing is not worth taking.
  Do not "upgrade" this to `x86-64-v4`: the numbered levels never include AES, so v4 measures identically to v3 while dropping non-AVX-512 hardware.
  aarch64, measured separately: GraalVM's default `armv8.1-a` has no AES/PMULL either (proxy, cached Maven artifact on Apple Silicon: 74 vs 627 MB/s, #1144).
  `+aes` enables AES and PMULL, and GraalVM has no runtime CPU dispatch on aarch64, so it is a build-time choice.
  It drops no hardware the default ran on in practice: ARMv8.0 cores (Raspberry Pi 3/4) already fail `armv8.1-a`'s LSE check.

**PGO**: release binaries are built with profiles collected in the same release run (#1637, DESIGN.md "Profile-guided optimization").
`release.yml`'s `profiles` job builds both binaries instrumented (`-Dnative.pgo.instrument`), runs `bench/pgo-collect.sh` against a real Incus, and merges each binary's dumps into one profile (`scripts/pgo-merge.sh`).
The build jobs apply them with `-Dnative.pgo=<dir>`, and `.github/scripts/check-pgo-quality.sh` fails a build whose binaries did not both take their profile above a floor.
The Intel macOS build, on GraalVM 25.0, stays unprofiled.
The arguments join the one list through the `native.pgo.args` placeholder; without `-Dnative.pgo` a build is unprofiled, so Community Edition still builds.
A change to build flags, the GraalVM version or the collection flows is verified with a `workflow_dispatch` run of `release.yml` before merging; that run publishes nothing.

Both `cli` and `proxy` are independent Quarkus applications that produce separate native binaries.
The CLI has no Vert.x dependency, so it **cannot** run the proxy in-process.
Every install channel must ship both binaries.
`isx proxy start` exits `EXIT_CONFIG` (78) with install instructions when `isx-proxy` is missing -- before touching the service, since restarting a service whose binary is absent cannot help.
On macOS that exit code is not enough (launchd has no `RestartPreventExitStatus`), so `haltUnusableMacOsService()` boots the job out and removes the plist unless the proxy is still answering.
The unit and macOS plist exec `isx-proxy` directly; for units written by older builds, which exec `isx proxy start`, `ProxyService.isSupervisedInvocation()` matches systemd's `INVOCATION_ID` against the proxy unit's own so the command never manages the service it is itself running under.
Both exist because a unit exec'ing `isx proxy start` restarts itself forever (issue #701).
JBang installs each alias separately, so `jbang-catalog.json` publishes `isx` **and** `isx-proxy`; `JbangCatalogTest` fails if an alias names an asset `gh release create` does not upload.

**Credential accounts are per config namespace, not per tool.**
A namespace (`claude`, `github`, ... -- whatever a tool declares as `config-namespace`) holds `accounts:` and `default:`.
Which account an instance uses is decided in three layers, lowest first: that `default`, the template's `accounts:` map (merged per key down the chain), then `isx branch --account <ns>=<account>` / `isx account set`, stamped as `user.incus-spawn.account.<ns>`.
The generic seam is `ProxyDef.accountConfigPath()` + `ToolProxyResolver`, so adding accounts to a new credential needs **no new mechanism** -- only config and init UX.
Selection is always `<ns>=<account>`; never accept a bare account name, which would silently fan out across namespaces added later.
Resolution **fails closed**: a pin to a missing account errors rather than serving the default.
Every namespace, Claude included, follows one model (#773): a flat, pre-accounts credential *presents as the account `default`*, and writes always produce the accounts layout.
What differs between namespaces is declared as an `AccountShape` (`ToolSetup.accountShape()`), never branched on by name.
The proxy holds no configuration of its own (#935): what it routes and injects is derived from host config and these stamps, so a restart or reload rebuilds it, and a new per-instance capability is a no-inherit stamp `isx` writes, never proxy memory (DESIGN.md "The proxy holds no configuration of its own").
The proxy identifies the caller by source address (static IP + `security.ipv4_filtering`, which is load-bearing, not optional hardening).
A per-start secret (`InstanceSecret`, #934) adds a second check for host-side services: `InstanceRegistry.identify()` wants address *and* secret.
On macOS, behind the same gate as a proof, a non-host caller at an unknown address is the running instance whose secret it presents (#1583).
Its consumers are `isx mcp` for a coordinator in an instance and the host routes of `config.yaml` (`host-routes:`, #936), which forward a name to a loopback controller with the caller's identity stamped by the proxy and a per-route secret the controller checks, which no guest ever holds (`HostRouteSecret`, DESIGN.md "Host routes").
Only its hash is recorded on the host, and every isx start rotates it, for one PATCH on a stopped start and none on a branch (`InstanceLifecycle.startForUse`).
Rotating and delivering it are serialized per instance across isx processes by a `HostLock` (`InstanceLifecycle.secretLock`, #1065), and one that waited re-checks rather than rotating again.
A container start, a branch's included, then records its boot (`last_used_at`, read once the guest answers and handed on to the CA check, so the stamp's write is the one request it adds), so `ensureReady` replaces a secret lost to a reboot isx did not do at no cost on the shell path.
A VM's agent probe asks the guest instead (#1024).
Each start fills credential placeholders with stable per-instance namespace proofs (`CredentialProof`, `ProofToken`).
A protected host key and a public random generation derive the credential seed; ordinary starts keep it, while branches and `isx account reset-proofs` replace it.
The seed is separate from the rotating MCP secret and is delivered to `/run/isx/credential-secret`.
Which variables carry them is declared on the tool's `proxy:` entry (`placeholders:`).
Missing declarations warn on proxy configuration load and during tool validation, since they block injection for that namespace's entries.
Single-tool validation checks its own namespace; the proxy checks borrowed namespaces against all loaded tools.
The proxy checks address and namespace proof before account resolution, HTTP or WebSocket injection, Vertex translation or `inherit` substitution (#1107).
Missing or incorrect proofs preserve the incoming authentication and destination; unknown addresses get no defaults.
On macOS, where the VM NATs every container to its own address, a non-host caller at an unknown address is the instance its proofs name (#1582); Linux keeps the address check.
Verifier hashes change on creation, explicit reset or declaration changes; credentialed builds receive and revoke their own temporary proofs.
Project create and update also use temporary proofs, preserving inherited account pins, baked-identity checks and untrusted host-route lineage, with cleanup on success or failure.
`isx update-all` grants a template the same temporary proofs for its own accounts and revokes them however the update ends (#1743); host routes never trust a template itself as a caller.
Reset invalidates old prefetched generations before its metadata write, and guest proof delivery holds the same lock through its boot stamp.
Normal starts derive the seed once; TUI restarts reuse the listing without another pre-restart metadata read.
Every copy clears inherited proof state, and a symlinked config directory is accepted after checking its resolved target.
Unrecognized proofs wait asynchronously for a bounded shared registry refresh; reset waits for host-only acknowledgement before reporting revocation complete.
Existing instances refresh verifier delivery on their next isx shell or start; old API-key Claude images may require rebuilding.
They are written beside the secret in `/run/isx` by the same exec, and the raw secret never enters the environment.
Plain Git presents the GitHub proof through the URL-scoped system helper `GitHubGitCredential` (#1420), installed during proof delivery and GitHub tool setup.
The helper reads the current proof file per request, including outside login shells and after resets; it never persists a token.
`isx doctor` inside an instance checks the exported ones against the secret file (`DoctorCommand.instanceCredentialFindings`, #1109); the proofs file's format is read back only by `ProofToken.delivered`, beside `profile`, so the two change together.
A placeholder token in the guest is read when the tool runs, never fixed by a build (#1108): where a tool reads a file instead of its variable, a login script in `/etc/profile.d/isx-zz-*` rewrites the file from the variable.
An isx running inside an instance shares that instance's grant for a namespace only when its account there is the reserved `inherit` (#1110): its proxy swaps the instance's proofs for the parent's, derived from `/run/isx/credential-secret` (`InheritedProof`).
See `.claude/rules/proxy.md` and DESIGN.md.

**Host trust** (#1437): isx's own HTTPS, CLI and proxy upstream alike, trusts the host's system CA bundle through `SystemTrust`.
On macOS it adds what an administrator trusts for SSL in the Keychain's admin domain and drops what one denied, from every source, refusing a chain that holds it (`MacKeychainTrust`, #1467; `DeniedChainTrustManager`, #1552); the user's own domain counts for its denies alone.
See DESIGN.md "Host trust on macOS: the admin domain only".

**Host CA propagation** is opt-in via `host-ca: { propagate: true }` in `config.yaml`.
When enabled, `CertificateAuthority.collectTrustAnchors()` collects the MITM CA and host anchors from `/etc/pki/ca-trust/source/anchors/` into one trust set, each keyed by SHA-256 fingerprint (`isx-<fp>.crt`).
On macOS the set also holds the Keychain's admin-trusted CAs, less what it denies (`MacKeychainTrust`, #1530).
The stamp is the CA's fingerprint, plus `+<host anchors' fingerprint>` when there are any, so `CaTrust` still reads which CA built an image after the host anchors change.
`fixContainerCaIfNeeded` reconciles the whole set in one exec: stamps `<ca>+pending`, removes old anchors, writes the current set via heredoc, and re-stamps.
It holds a per-instance `HostLock` (`~/.cache/incus-spawn/locks/trust/<instance>.lock`) from re-reading the stamp to the final stamp, and collects the set under it, so two overlapping reconciles cannot leave a removed CA trusted under the newer stamp (#1381).
The pending stamp matches no set, so a failed install retries; an empty one would never be checked again.
Builds install the set through the same script (`installTrustAnchors`) and stamp the set they installed, not one read again at the end.
See `.claude/rules/proxy.md`.

**`isx init` prompts read from `Prompts`, never a `Console`.**
`java.io.Console` is final, so code taking one cannot be driven by a test, and CI's `isx init </dev/null` skips every credential prompt (#772).
Each credential step has a thin entry that gets `Prompts.console()` and a package-private `(SpawnConfig, Prompts)` overload holding the logic; network and host calls (token verification, `gh`, the browser, env vars) are overridable methods on `CredentialSetup`.
Flow tests drive them with `ScriptedPrompts` under `@ExtendWith(IsolatedHome.class)` and assert on the `config.yaml` written to disk.
Secrets go through `askSecret()` and are shown only via `maskSecret()`, which never reveals more than a third of one.
The question is shown by the same `Prompts` (`ask`, `say`; `BaseCommand.askYesNo` for yes or no), at the step indent, so `ScriptedPrompts.conversation()` sees what a flow asks; what it reports between questions goes through `BuildOutput` (#1703).
A new prompt follows the same shape and gets a flow test.
See `.claude/rules/commands.md` and DESIGN.md "Testing".

**Never print straight to stdout/stderr: all output goes through one seam (#1703).**
While the TUI renders it owns the terminal, so a raw `System.out`/`System.err` line is drawn over and lost, and scrambles the screen.
`BuildOutput`'s static methods hand every message to the bound `Output` (`common/util`), a `TerminalOutput` unless the owner of the terminal bound another (the TUI, `PreTuiOutput`, `isx mcp`, the proxy).
Say what kind a message is: a `step`, a `warn` with its advice as details (never a `Warning:`-prefixed line), why a command failed as an `error`, a query's answer on `BuildOutput.results()`.
Indent with `group`, never with leading spaces.
Under a header, work done is a live step, work skipped a `note`, the answer a `step`, and `success` closes the block; labels are bare (`"Launching x"`, never `"Launching x..."`), which `StepLabelGateTest` holds (#1791).
Shared code the TUI runs while it draws (`lifecycle/`, `incus/`) takes an `Output` from its caller, never a `Consumer<String>`; code with no caller to ask, such as a definition loader, reports through `Warnings.warn()`.
`RawOutputGateTest` (`cli`) enforces it: only its gate classes (`EXEMPT` and the build-time `graal/*Feature` classes) touch the raw streams, the console or `printStackTrace()`, and no print literal is indented by hand.
It counts a logger, `Thread.dumpStack()` and reflection on `System` as raw stream use, and lets only named files hand a child process the terminal or open `/dev/tty`.
Code that hands the terminal over or draws on it whole takes the streams from `TerminalStreams`, and only the files the test names may.
Tests assert on `RecordingOutput`'s typed events (common test-jar), never on a swapped stream; `StreamSwapGateTest` names the few that may swap, each with why.
The bindings and every kind of message are in `.claude/rules/terminal-output.md`; the rationale is DESIGN.md "One output seam".

**Output a script reads is a contract.**
`--format=plain|json` (on `isx list` and every other query command, and `isx branch`) goes through `OutputFormat` (`common/util`): one ordered map per record feeds both formats.
Every format, `table` included, is printed to `BuildOutput.results()`, the bound output's stream for a command's answer: stdout on a terminal, nothing while the TUI draws.
Fields are added, never renamed, removed or reordered.
Stdout holds only results, never colour, glyphs, progress or prose.
No value writes a control character raw (`plain` spaces them through `OutputFormat.oneLine`, `json` escapes them, #1118).
`table` is for people and may change.
`isx list` from the CLI costs one Incus request and loads no definitions.
Commands that act need only clean stdout: `BuildOutput.ansi()` is the one gate for colour and hyperlinks (nothing off an ANSI terminal or under `NO_COLOR`; it follows stdout, not stderr), and its warnings and notes go to stderr.
A status report goes to stdout in every state, its exit code saying which (#1037).
See `.claude/rules/commands.md` and DESIGN.md "Output for scripts".

**YAML tool downloads:** `ToolDef` download entries may set `extract`, `destination_file`, or both.
Downloads are cached on the host, then either extracted into the target, copied to the exact destination path, or processed both ways.
VM builds use mount-and-copy rather than slow incus-agent file pushes over vsock.

**Secrets are declared, not listed.**
`SecretRegistry` (`common/config/`) answers "where do secrets live in `config.yaml`" by collecting every tool's `ToolDef.ConfigEntry` that sets `secret: true` with a `config-path`.
So a new credential becomes known to every secret-aware consumer by being declared on the tool (`ToolSetup.proxy()` or tool YAML), never by editing a list.
A declared leaf also holds anywhere under its namespace, which is what covers `claude.accounts.<name>.apiKey`.
`SecretRedactor` owns the other half -- what a credential *looks like*: the whole-word key-name backstop for undeclared keys in `SpawnConfig.extras`, the token-shape patterns, and the `<isx:redacted:<path>>` marker.
`isx doctor --bundle` (`SupportBundle`) is the first consumer: the config is redacted structurally, every other file is scrubbed, and `SupportBundle.add()` is the only way into the archive so a new collector cannot leak by forgetting.
See DESIGN.md "Support bundles: redaction by construction" and `.claude/rules/config.md`.

**Project-local definitions are untrusted.**
`.incus-spawn/` is whatever a cloned repository ships, so project-local templates get only what stays inside their project (#765).
Their host-resources and `file://` base images are confined to the project on real paths (`HostResourceSetup.collectEffective()`, re-checked at build, branch and start).
Their `image_url` may only replace an image their own project imported (the `incus-spawn.project` image stamp, which fails closed).
Their repos never get host checkout mounts or host-side clones.
Their `gui: true` is never a branch default (GUI hands over the host's GPU and its Wayland and audio sockets; `--gui` still can).
For every definition, `DownloadCache.download()` is `http(s)`-only and refuses loopback and link-local hosts on every redirect hop, and no `prime` runs while any host checkout is mounted.
A new capability that reaches host state outside the project needs the same gate.
See DESIGN.md "Project-local templates are confined to their project" and `.claude/rules/config.md`.

**`isx mcp` gives a local agent a narrow slice of isx.**
It serves MCP over stdio (hand-rolled JSON-RPC in `cli/.../mcp/`: the Quarkus MCP extension cost every isx command ~1.7 ms at startup).
An agent may branch only templates listed in `~/.config/incus-spawn/mcp.yaml` (read per call, refused whole when invalid, never written by the package -- `McpNoWritePathTest`; an `mcp:` section in `config.yaml` is not read, #1331), never project-local ones, always with template defaults via `BranchFlow` -- or fork a stopped instance it holds (`create_instance(from_instance)`, #1013), which is `isx branch --from <instance>` with that instance's lineage re-checked.
An `idempotency_key` on `create_instance`, `delegate` and `exec(background)` (#1011) makes repeating a call safe.
It is stamped on the instance by the copy request (`mcp-idempotency-key`) and written to the task's directory, so it lives as long as what it made, with no host ledger.
Instances belong to the host user and are held by one session (`<pid>-<start>`), stamped by the copy request itself and checked on every tool call.
A session ending releases them as orphans, which a later session can `adopt_instance` (tasks included) and which are destroyed only after `orphan-grace-hours` in `mcp.yaml` with nobody in them (#898).
One kept only for a delegate that shows no activity is stopped instead, and destroyed after `dormant-grace-hours` in `mcp.yaml` unless adopted (#1028).
A person sees the same: `isx list` (`mcp_state`, `mcp_purpose`) and the TUI's instance details say which instances a session made, what for, and whether they are held, orphaned or kept (`McpStanding`, from the listing alone, #1053).
`exec` has no default time limit (cancel kills the process tree).
Background commands and `delegate` (Claude Code, Codex or Pi inside the instance) run as systemd units in the guest.
A client that lists `isx/task_changed` under `capabilities.experimental` is notified of every task state change (`TaskWatcher`, #1014), and only then are tasks polled.
`instance_activity` reads what the proxy counted of an instance's model calls and, by domain, of all its traffic (its host-only, read-only `/activity`, #935; `isx proxy activity` for a person), so a coordinator tells working from stuck without touching the instance.
Every tool declares an `outputSchema` and returns matching `structuredContent` for programs, while the text stays for the model; a refusal carries no `structuredContent`, only a code in `_meta["dev.incusspawn/error"]` (#1010).
Stdout is the protocol: `StdioGuard` redirects `System.out` to stderr and `Headless` disables prompts.
An agent may also run in an instance branched with `--mcp-client` (#915).
The proxy serves it `https://mcp.isx.internal/mcp` (`McpBridge`, Streamable HTTP) after checking address, per-start secret and the `mcp-caller` stamp, bridged to a host `isx mcp --caller-instance <name>` whose session is the instance itself.
No copy inherits the stamp and the MCP path refuses it.
Every start registers the server in that instance's Claude Code and Pi, or removes it from one without the stamp, inside the exec that delivers the secret (`McpClientRegistration`, #1182), so a copy loses it on its first start.
See `.claude/rules/mcp.md` and DESIGN.md "MCP server".
Managed delegation selects `claude`, `codex` or `pi`, exposes installed agents through `list_templates.delegate_agents`, and persists the selected agent with each task.
See [MCP delegation to Codex workers](docs/dev/MCP-CODEX-DELEGATION.md) for recovery, policy differences, and validation, and [MCP delegation to Pi workers](docs/dev/MCP-PI-DELEGATION.md) for what differs for Pi.
Pi has no sandbox, so a restrictive Claude permission policy refuses it rather than widening into a fully writable worker.

**External orchestrators drive isx through ordinary commands (#1720).**
`isx branch --owner <name> [--owner-id <id>]` stamps who controls an instance; like `isx mcp`'s stamps these are ownership keys (`Metadata.isOwnershipKey`) that no copy inherits.
`isx branch --format=json` adds the SSH target and `project_root`, while `plain` stays the new name alone for `name=$(...)`.
`isx start` and `isx stop` reuse `isx shell`'s preparation and the marked stop, and `isx ssh-proxy --start` lets an orchestrator's reconnect start a stopped instance; the Host block a plain `ssh` reads never starts one.
`isx destroy --owner <tool>` deletes only for a tool `drivers:` in `config.yaml` lets destroy, and only while the instance still carries that owner; a plain destroy of an owned instance asks.
`isx list`'s `OWNER` column folds in `isx mcp`'s standing, and its formats append `owner` and `owner_id`.
An orchestrator with hooks (Orca) needs no isx code: `integrations/orca/isx-orca-recipe` speaks its recipe contract over those commands, with `jq` (`OrcaRecipeScriptTest`, `docs/INTEGRATIONS.md`).
An orchestrator with no hooks (herdr) is served by a tool's host half instead: a built-in `HostIntegration` keyed on the tool being in the instance's build, which registers the instance on its first start and removes it on destroy, never for a project-local or untrusted build (`.claude/rules/tools-env.md`).
See `.claude/rules/commands.md` and DESIGN.md "External orchestrators: owners, start, stop and destroy".

**Native image: host paths belong in the run-time-initialized classes.**
Quarkus initializes application classes at image-build time unless they are listed in `--initialize-at-run-time`, and Linux native builds run as **root inside the GraalVM builder container**.
So a field holding an `Environment` path bakes the builder's `/root/...` into the binary (that regression shipped: see DESIGN.md "Build-time initialization must not capture host paths").
Eagerly resolved host state goes in `RuntimeConstants` (`common`) or `RuntimeServices` (`cli`), both on the flag; everywhere else call the `Environment` method instead of storing its result.
The flag is declared once per module, in its `resources-filtered/application.properties`.
Pom profiles add platform arguments through placeholders the list includes and never redefine the list itself (#489).
`macos.min.args` is how every macOS build gets its minimum macOS (DESIGN.md "The minimum macOS is set by the build").
`NativeImageInitializationTest` fails if a list stops deferring a class or registering a guard, or a pom redefines it, and `graal/BakedHostStateFeature` fails the native build if such a path reaches the image heap.

**The platform role is the OS's, and no released binary can be switched (#1800).**
On macOS the proxy identifies a caller by its credential proof without the Linux address check, so a Linux isx in macOS's role would drop that check.
`Platform` takes macOS's role from `os.name` (which a JVM's `-Dos.name` or `JAVA_TOOL_OPTIONS` sets) only on a host with macOS's `SystemVersion.plist` and no Linux kernel (elsewhere it refuses to start), and native images fold it at build time, where `graal/PlatformRoleFeature` fails a build whose role is not its target OS's.
CI tests the macOS roles on Linux with a test-only build instead: `scripts/macos-role-build.sh` swaps the uber-jars' `Platform.class` for one pinned to macOS, and `appliance/test-macos-role.sh` runs it against the appliance in the `macos-role` job.
Never add a runtime switch, flag or variable for the role.
`release.yml` and `uber-jar-smoke` run `scripts/check-platform-role.sh` on the uber-jars, and `PlatformRoleBuildGuardTest` keeps the test build out of every other job and every install channel.

Detailed architecture docs are in `.claude/rules/` and load automatically when you work on related files.
Each rule file declares `paths:` globs that trigger it.
When adding new source packages, renaming files, or restructuring modules, check whether `.claude/rules/` path globs need updating -- stale paths silently stop loading context.
Prefer package-level globs (`incus/**`) over specific files; use specific files only for cross-cutting triggers (e.g. `BuildCommand.java` in `incus.md` to ensure pool-awareness context loads during build work).
