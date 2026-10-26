---
paths:
  - ".github/**"
  - "bench/**"
  # Mirrors the isx-integration-tests-native setup for local runs; keep the two in sync.
  - "scripts/local-incus.sh"
  # Run by release.yml on every shipped macOS binary.
  - "scripts/check-macos-minos.py"
  # Cross-cutting trigger: VmManager holds the CLI half of the release-asset
  # name contract below, so editing it must load this file too.
  - "common/src/main/java/dev/incusspawn/vm/VmManager.java"
---

# CI Integration Tests

`.github/workflows/test-integration.yml` runs on every push and PR targeting `main`.
On a push, root jobs are gated so feature-branch pushes only run on forks (not upstream, where the PR trigger covers them).
Key jobs:

- **`unit-tests`**: `mvn package` (no Incus required)
- **`unit-tests-macos`**: `mvn test` on `macos-15` (a standard arm64 runner), so a test that assumes Linux tools or kernel behaviour fails the PR instead of every Mac (#970). arm64 only, by decision (#974); the reasoning is in DESIGN.md "Testing".
  It sets `ISX_TEST_MUTATE_SYSTEM_KEYCHAIN=1`, which enables `KeychainTrustMacTest`: it adds CAs to the runner's System keychain, which only a discarded runner may do (#1467).
  Test failures are counted from the surefire reports after the run, so a failure in `common` does not skip `cli` and `proxy` (`-fae` would: they depend on it), and the reports are uploaded when it fails, since few contributors have a Mac to reproduce on
- **Test JVM flags**: the root pom's `argLine` property keeps `com.sun.crypto.provider.AESCrypt::makeSessionKey` out of the JIT, a GraalVM 25 JIT bug that made the proxy tests flake with `bad_record_mac` (#940, upstream: [oracle/graal#14599](https://github.com/oracle/graal/issues/14599), DESIGN.md "Testing").
  Add to that property and never set surefire's `<argLine>`, which would drop both the flag and the arguments the Quarkus plugin adds; `-DargLine=` on the command line replaces it too.
  The JVM launchers carry the same options (`install.sh`'s `jvm_options()` on a Graal-JIT JDK, `java-options` on the JBang aliases); `LauncherJitWorkaroundTest` keeps them equal to the `argLine`'s `-XX:CompileCommand` options, so change those in all three together.
  `JitWorkaroundTest` checks it in the proxy module, where the TLS tests are.
  That the excluded method still exists is checked only on a Graal-JIT JVM (`UseJVMCICompiler`), since other JDKs need no exclude and JDK 27 has no `AESCrypt` at all (#1050)
- **`uber-jar-smoke`**: builds `-Prelease` and runs both uber-jars on a JVM -- what JBang users get.
  Nothing else in CI executes them, which is how the JBang channel broke unnoticed (issue #701); it also asserts that `proxy start` without an `isx-proxy` sibling exits 78 with install instructions rather than looping
- **`macos-keychain-trust.yml`** (`native-keychain-smoke`, `macos-15`): builds the native CLI on macOS weekly, by hand and on a PR touching `MacKeychainTrust`/`SystemTrust`, and runs `.github/scripts/macos-keychain-smoke.sh`, which `release.yml` also runs on each macOS binary: a CA trusted in the System keychain must appear in `isx doctor --format=json`, and one denied there must not (#1467)
- **`build-native-cli`**: builds the CLI native image, uploads artifact
- **`build-native-proxy`**: builds the proxy native image, uploads artifact
- **`integration-tests (x86_64)`, `integration-tests (aarch64)`**: a matrix.
  Each boots its arch's appliance VM image under QEMU with a `vhost-vsock-pci` device, checks it reaches `ISX READY` and passes the in-guest Incus smoke test, then bridges the guest's vsock ports to Unix sockets with host `socat` (standing in for vfkit) and runs `appliance/test-tunnel.sh` and the real native `isx` through them -- see below
- **`isx-integration-tests-native`**: installs Incus on Ubuntu 24.04, uses native binaries from the build-native jobs, runs `isx init`, starts the MITM proxy, builds templates (`tpl-minimal`, `tpl-test-podman`, `tpl-test-podman-rootless`, `tpl-test-vm`, `tpl-test-codex`, `tpl-test-mcp`), then runs test scripts inside branched instances.
  `test-proxy-only.sh` is the only real check of the proxy-only boundary (#1137): branches of `tpl-minimal` (no iptables) and `tpl-test-vm` must reach the proxy and nothing else after `isx branch`, after `incus restart`, and on a `--no-start` branch first started by `incus start`.
  It needs Incus's nftables firewall driver on the runner
- **`fresh-daemon-init`**: verifies `isx init` on a daemon that has never been initialized

The **`update-base-image-steps`** job in `test-integration.yml` runs `.github/scripts/test-update-base-image.sh`: `update-base-image.yml`'s three steps, as its `workflow_dispatch` backstop and its `repository_dispatch` path run them, against a fake images release with `-vm-direct` and `SHA256SUMS.v2`, `gh` and the remote faked (#1255).
It checks the switch to `-vm-direct` with both arches' checksums, also after a stock-image bump of the same release was merged or while its branch is open; a routine bump of a built-in already on `-vm-direct` (plain title, `-vm-direct` branch); the refusal to move such a built-in back to stock when a release lacks `-vm-direct`; and that the PR title names the switch and a release published again under its tag.
It checks that both events take every checksum from the release for the names the URLs download, that a payload disagreeing with the release or a missing, malformed or contradicted sum stops the run, and that the PR pairs each checksum with its asset (#1678).
The same script runs `verify-base-image-sums.sh` against the fake release: a swapped arch, a stock sum under a `-vm-direct` URL and a rootfs sum paired with a VM disk must each fail it.
It starts from a stock built-in written in the script, whose keys, nested ones included, must match `minimal.yaml`'s, and finds the workflow's steps by name, so renaming a key or a step fails it too.
`.github/workflows/verify-base-image-checksums.yml` (**`verify-base-image-checksums`**) runs `.github/scripts/verify-base-image-sums.sh` on a PR or push that changes `minimal.yaml` or the scripts: the four checksums must be the release's own for the assets `image_url` and `vm_image_url` download (#1678).
It reads the real release, so it needs no secret and runs on a fork's PR too.
`.github/workflows/test-vm-kernel.yml` (**`boot (x86_64)`**, **`boot (aarch64)`**) runs when `appliance/kernel/**`, `appliance/test-vm-kernel.sh` or `minimal.yaml` change (#1255).
It builds the VM base image's kernel with `build-kernel.sh vm` and boots it with `appliance/test-vm-kernel.sh` on the VM image `minimal.yaml` pins, downloaded and checked against `vm_image_sha256`.
x86_64 boots under KVM, aarch64 under TCG; the console logs and `systemd-analyze plot`s are uploaded.
It shares the kernel-source cache key with `build-appliance.yml`, and depends on no image release.

`scripts/local-incus.sh` reproduces the `isx-integration-tests-native` setup (packages, `sg incus-admin -c "isx init </dev/null"`, the default profile on the `cow` pool) on a local host, for the `validate-on-real-incus` skill.
A change to how that job prepares Incus belongs in the script too.
The script also installs `virtiofsd`, which Fedora's `incus` package does not pull in: without it Incus shares VM disks over 9p, which cannot be hot-plugged (#853).
Nested inside an isx instance it also works around what a user namespace refuses (Incus's bridge firewall rules, the unmapped subordinate-ID range, the iptables redirect); the skill says what it cannot fix there.

Each job runs on its own freshly-provisioned runner, so jobs never inherit each other's Incus state.

**An artifact a later job or the release needs is uploaded through `.github/actions/upload-artifact-retry`**, which tries once more when the first attempt fails (#1672, DESIGN.md "Testing").
Diagnostic uploads (`if: always()` or `failure()`) call `actions/upload-artifact` directly.
`WorkflowArtifactUploadRetryTest` fails when a deliverable upload bypasses the action, or the action stops retrying.

**No CI job boots the appliance under vfkit.**
Both arches boot under QEMU; this is worth stating precisely, because the runner list makes it look like more:

| where | what it does with the appliance |
|---|---|
| `test-integration.yml` → `integration-tests (x86_64)` (`ubuntu-26.04`) | boots it under `qemu-system-x86_64` with KVM and vsock; full tunnel checks, backstop, native `isx` |
| `test-integration.yml` → `integration-tests (aarch64)` (`ubuntu-24.04-arm`) | boots it under `qemu-system-aarch64` with **TCG** (the arm64 runners expose no `/dev/kvm`) and vsock; smoke test and tunnel checks, but not the backstop (a fixed 3-minute wait that is arch-independent) or native `isx` (CI builds no arm64 CLI). vhost-vsock works under TCG, and the appliance reaches `ISX READY` in about 20s |
| `build-appliance.yml` → `build_x86_64`, `build_aarch64` (`ubuntu-26.04`, `ubuntu-24.04-arm`) | builds, checks size and embedded version, caches, uploads. Never boots it |
| `test-integration.yml` → `unit-tests-macos` (`macos-15`) | nothing: runs `mvn test` on the host, the macOS half of the Java code only |
| `release.yml` native matrix (`macos-15`, `macos-15-intel`) | builds the native CLI/proxy for macOS 15 (Intel is best effort: shipped, but this smoke test is all it gets; no Intel Mac verifies it, #1086) and smoke-tests the *binaries* -- `--version`, `--help`, completions, `templates list`; the release job then checks each one's recorded minimum macOS. Never starts a VM, and runs on `v*` tags and `workflow_dispatch` |

**Runner labels are pinned, and retired ones are caught by a test.**
Workflows name a versioned label (`ubuntu-26.04`, `ubuntu-24.04-arm`, `macos-15`, `macos-15-intel`), never `ubuntu-latest` or `macos-latest`.
That holds for API-only jobs too: GitHub annotates every run of a job on a floating label before it migrates, and a migration should be a change someone makes (#1679).
The binaries' minimum macOS no longer follows the runner.
It is the root pom's `macos.deployment.target` (15.0, Sequoia, #1086), linked in by a macOS-activated profile.
The release job's "Check macOS binaries' minimum macOS" step (`scripts/check-macos-minos.py`) fails unless every macOS artifact the release uploads records exactly that `minos` (see DESIGN.md "The minimum macOS is set by the build").
The Homebrew formulas declare it as `depends_on macos: :sequoia`, which `release.yml`'s tap step writes from `MACOS_MIN` on every release; README and `docs/dev/HOMEBREW.md` state it.
GitHub retires old images (`macos-14` with its `-large`/`-xlarge` variants, #1000), and a job on a removed label waits for a runner that never comes.
Because `release.yml` runs only on `v*` tags, nothing but the next release would notice, so `WorkflowRunnerLabelsTest` fails on `macos-14` or older and on `ubuntu-latest` or `macos-latest` in any workflow.
It also fails when `MACOS_MIN` is not Homebrew's name for the deployment target, when a release runner is older than the target, and when the release job no longer runs the check over `artifacts/native-macos-*/*`.
When GitHub announces the next retirement, raise its bound and move the labels together; a newer runner does not move the minimum.

**vfkit cannot run on GitHub-hosted macOS runners, and this is structural.**
Every hosted macOS runner, the paid `-large`/`-xlarge` ones included, is itself a macOS VM, and Virtualization.framework offers nested virtualization only to *Linux* guests.
A probe on `macos-14`, `macos-15`, `macos-26` and `macos-15-intel` failed with `VZErrorDomain` "Virtualization is not available on this hardware" (Intel: "failed to start") before the guest ran any code, and third-party per-minute macOS runners are VMs too (#774).
Testing vfkit needs a **bare-metal** Mac, as a self-hosted runner that gates releases (tags / `workflow_dispatch` behind a protected environment, never PRs).
Until one exists, the manual pre-release run of `appliance/test-boot.sh` and `appliance/test-with-isx.sh` on a Mac is the gate. #791 is the Mac verification plan and the brief for that runner.

**What the vsock-bridged boot covers.**
The topology is `Unix socket → [tunnel] → AF_VSOCK → in-guest socat forwarder → incusd`, and only `[tunnel]` is vfkit, so `integration-tests` reproduces everything either side of it with `socat UNIX-LISTEN:…,fork,backlog=128 VSOCK-CONNECT:3:<port>` on the paths isx looks for (`vm.incus.sock`, `vm.agent.sock`).
`appliance/test-tunnel.sh` (shared with `test-boot.sh`'s vfkit path) then checks: the Incus API through the forwarder; every `isx-agent` verb and its argument validation; that 16 and 32 concurrent connections are all accepted and every forwarder child exits afterwards (the baseline `isx doctor`'s leak count assumes); that requests are served past connections that sent nothing or 7 bytes, which incusd then closes near its 3s initial-read deadline (#1313); that the `socat -T 180` backstop reaps a silent connection no earlier than the floor; and no-reboot `forwarder-restart`.
The backstop is timed against a test-only silent endpoint, since incusd no longer holds a silent connection: `isx.test_silent_vsock=8444` on the kernel cmdline makes `incus-spawn-vm-init` put the production forwarder in front of a guest socket that never answers, and the job bridges it like the other two.
The smoke test checks the same STARTTLS behaviour in the guest, on the local socket, on both arches.
The native `isx` then connects through the same socket: the runner has no native Incus, so `IncusApi.tryConnect` falls through to the vsock candidate exactly as on macOS.
It does **not** prove vfkit itself: host socat propagates close faithfully, which is the property vfkit lacks.
The job runs in parallel with `isx-integration-tests-native`, which is longer, so the ~3 minutes of backstop wait adds no wall-clock to the workflow.

Findings this surfaced, each fixed or documented where it lives: the forwarder's socat listen backlog (default 5) refused concurrent connects (3/16, 12/32), and one exec opens about five; incusd's local listener stalled every new connection behind one that had sent nothing (see `incus.md`; fixed by a backport in #1313); incusd closes idle keep-alive connections after 30s.

The host half is covered in `unit-tests`.
The Java compensations for vfkit's behaviour (exec completion via `/wait`, the adaptive drain, per-fd keepalive pings, the watchdogs, stale-connection recycling, the fast `tryConnect` probe) run against `LossyIncusServer`, a fake daemon on a Unix socket that misbehaves the way the tunnel does -- see `incus.md`.
Still uncovered: vfkit itself, and boots under vfkit's hvc0 console and devices.
So anything touching the kernel config, the console, or the boot path still needs a boot on a Mac before it ships (#791).
A green CI is not evidence that vfkit behaves.

The appliance cache key hashes `appliance/**` except `appliance/test-*.sh`, `appliance/*.md` and `appliance/kernel/vm*.config`, which never enter the image, so editing a test script or a VM kernel fragment does not force an appliance rebuild.
Anything `build.sh` copies into the image (`root/`, `config.sh`, the kernel) must stay inside the hash.

The kernel-source cache (`/tmp/kernel-cache/`) is keyed `kernel-source-<version>-<sha256>`, both read from `appliance/kernel/build-kernel.sh`.
That script downloads only from kernel.org and checks `KERNEL_SHA256` on every run, a cache hit included (#1128).
The save step runs only after a verified build, but `actions/cache` never overwrites a key.
With a version-only key, an entry saved before the check existed (possibly the GitHub `.tar.gz`) or under a since-corrected pin would be restored, and fail or be re-downloaded, on every build at that version.

**Release asset names are a contract with the CLI.**
`release.yml` publishes the appliance kernel as `vmlinuz-<arch>.gz` (gzipped there, not by `build-kernel.sh`, which still emits a plain `vmlinuz` for local QEMU/vfkit runs) and `VmManager.downloadKernel` gunzips it on the way into `~/.isx`.
Renaming an asset on one side breaks the other, with the twist that a dev build resolves its appliance version to the *latest* release rather than its own.
This is why the download falls back to the pre-`.gz` name instead of failing.
Change both sides together, and keep the fallback until no reachable release predates the rename.

`fresh-daemon-init` exists because `isx-integration-tests` runs `incus admin init --minimal` *before* `isx init`, which populates the default profile -- so it cannot catch `isx init` failing to populate it itself.
It installs Incus and creates only the storage pool, with no `admin init`, reproducing the state where a pool exists but the default profile is empty (every instance creation then fails with "Failed getting root disk: No root device could be found").
It asserts the profile has a root disk and a NIC, then launches a real instance and runs a command in it -- a profile that merely looks right can still name a bad pool or a missing network.
The instance's image is a static busybox the job builds and imports itself, not one from images.linuxcontainers.org, whose outages otherwise read as isx regressions (#1558).

Note that `isx init` is not run against the QEMU appliance VM: on Linux it provisions a *native* Incus, and the appliance has its own provisioning.
The vsock-bridged boot in `integration-tests` exercises the client path to the appliance instead (`isx` over `vm.incus.sock`), which needs no `init`.
`appliance/test-with-isx.sh` stays macOS-only because it has isx *boot* the VM, and isx's QEMU backend deliberately wires no vsock (Linux users run native Incus).
The appliance also provisions Incus with its own shell script and never calls `isx init` -- the "ensure default profile has a root disk and NIC" invariant is implemented twice, in `incus-spawn-vm-init` (shell, in-VM) and `IncusClient.ensureDefaultProfileDevices` (Java, host).
Keep the two in sync.

The `isx-integration-tests` job exercises five environments: a container (from `tpl-minimal`), a podman container with the default rootful socket (from `tpl-test-podman`), one with every non-default `podman` parameter (from `tpl-test-podman-rootless`), a codex container (from `tpl-test-codex`), and a VM (from `tpl-test-vm`).
The accounts scripts below branch two further instances of their own.
Test scripts live in `.github/scripts/`; a script pushed into a guest cannot use awk (the base image has none; grep, sed and bash do the job):

- **`test-instance.sh`**: pushed into containers and VMs, tests proxy interception (Maven/GitHub HTTPS), git clone, passwordless sudo, systemd lifecycle, `/tmp` on disk (#1561), DNS interception (A to the gateway, no `::` or real AAAA answer, #814), login shell env vars, TLS certificate quality, and tool-contributed proxy credential injection (section 9 verifies bearer token injection via a test fixture tool `.github/test-fixtures/tools/test-proxy-tool.yaml` and a local HTTPS echo server at `echo.incus-spawn.test`).
  Uses `assert()` / `assert_eq()` shell helpers.
  After it, the "Test container" step writes a file to `/tmp`, runs `incus restart` and checks the file is still there, waiting at most 60 s for `local-fs.target`.
- **`test-airgap.sh`**: runs on the host.
  Branches `tpl-minimal` with `--airgap` and asserts on the *expanded* devices (no `nic` at all), the `network-mode` stamp, and from inside that the started branch has no interface but loopback and cannot reach the internet.
  A `proxy` device bound in the instance, from a profile of the test's own on a copy of `tpl-minimal` (not the default profile: Incus refuses a non-NAT proxy for the VMs using it), must be masked too and unreachable from inside; a second one added after the branch must be masked by the next `isx run` (#1518).
  Then branches it again without `--airgap` and asserts the NIC, the proxy and an address come back, and that `curl` through the proxy now succeeds: the positive control for the checks that it cannot.
  It needs a real Incus because the bug it guards (#813) was Incus semantics.
  Removing an instance device that overrides a profile device only drops the override, so the NIC must be masked with `type: none` instead.
  A fake that drops removed devices from `expanded_devices` passes either way.
- **`test-podman.sh`**: pushed into the podman container, tests rootless podman (pull, run, build).
- **`test-project-proofs.sh`**: creates a project from an authenticated Git fixture served by `echo-server.py`, checks its selected account during pre-build and update, and checks revocation, shutdown and address release after success and failure.
  The fixture rejects clones without the synthetic selected credential, so a public repository cannot hide missing proof delivery.
- **`test-podman-rootless.sh`**: pushed into the `tpl-test-podman-rootless` container (`mode: rootless`, `short-names: permissive`, `compose: true`), tests that `/run/docker.sock` and `/var/run/docker.sock` point at agentuser's socket with the system `podman.socket` left disabled, that an unqualified name pulls from docker.io without a prompt, that `podman-compose` is installed, and a PostgreSQL container run as agentuser.
  The short-name check pulls `testcontainers/ryuk`, which has no alias in Fedora's `shortnames.conf`; `alpine` and `postgres` do, so pulling them would pass under `enforcing` too.
- **`test-codex.sh`**: pushed into the codex container, tests the built-in `codex` tool end to end -- that `CodexSetup` installed the CLI and wrote the template's `model`/`effort` parameters and the unattended settings into `~/.codex/config.toml` (including the selected permission profile's full filesystem and network tables).
  It checks that `codex exec` reports `danger-full-access`, that the login environment and `auth.json` hold the current proof, and that a request presenting that proof receives the real key for `api.openai.com`.
  An anonymous request must not expect injection under proof enforcement.
  Asserting against what the tool actually wrote is the point: the hand-rolled `config.toml` fixture this replaced would have kept passing after `CodexSetup` changed underneath it.
  `tpl-test-codex` deliberately sets non-default `model`/`effort` so the test can tell template parameters from built-in defaults.
- **`test-mcp-client.sh`** (host-side): `isx branch --mcp-client` registers `isx mcp` in the instance's Claude Code and a copy loses it on its first start (#1182).
  `tpl-test-mcp` carries the fixture tool `test-claude-stub.yaml`, a `claude` on the system PATH that records its calls and mirrors its entry into `~/.claude.json` (where the start's reconcile and the branch check look), so no Claude Code install is needed.
  Asserts the branch reached the endpoint through the real proxy bridge and host `isx mcp` (`McpClientCheck`), that `claude mcp remove`/`add-json` ran once each as the agent, root's marker, that the next start (`isx run --action=no-such-action`, no terminal) runs no Claude Code, and that `isx branch --from` the stopped coordinator removes entry and marker while an ordinary branch calls nothing.
- **`test-accounts.sh`** + **`test-account-switching.sh`**: per-instance credential accounts.
  Two instances are branched from one template differing only in `--account`, so the assertion is the claim: one shared proxy, different real credentials, decided by who asked.
  The first runs inside each instance (its token names its own account, and no real token is anywhere in the container).
  The second runs on the host and drives `isx account set` against running instances.
  A re-point takes effect on the next request with nothing restarted, and an instance pinned to a deleted account is refused rather than served the default, and `isx account unset` returns an instance to the default live.
  Step [5] moves the Claude default to a Pro/Max account and asserts the proxy refuses the API-key-built instances' Claude requests (with the `isx account set` fix in the body) while their other credentials keep working, then restores the config.
  Both lean on the fixture tool `.github/test-fixtures/tools/test-account-tool.yaml` (namespace `testAccountTool`, domain `echo-accounts.incus-spawn.test`), kept separate from `test-proxy-tool.yaml` because that one pins the flat pre-accounts layout and both shapes must keep working.
  **No real credential is involved**: `echo-server.py` answers GitHub's `/user` and `/user/emails` as whichever account a `ghp-acct-<name>` token names, so it can play two identities at once -- which one real token never could.
  **`test-git-identity.sh`** uses that: an instance pinned to `github=agent` must *commit* as that account, not merely push with its token.
  It runs twice against `acct-gh` -- once before `isx account set ... github=agent` (asserting the template's baked `ci-user` identity) and once after -- so a pass means the re-point actually changed the identity rather than finding one that was already right.
  Its argument is the *login* the echo server answers as, not the account name, which is why the pre-swap call passes `ci-user` rather than `ci`; the script's title-casing mirrors Python's `str.title()` so a hyphenated login matches.
  The step drives `isx account set` rather than `isx branch --account` because a branch that starts also attaches a shell, which CI has no terminal for (without one, `isx branch` skips the shell and exits 0, #1027).
  The branch-time reconcile is the same call, from `BranchCommand`.
  **`test-token-rotation.sh`** (host-side, after it) covers #281: with `agent` made the default -- build containers are served the default -- it builds `tpl-test-rotation-base` (gh) while `agent` holds `ghp-acct-agent`, gives `agent` `ghp-acct-rotated`, builds the child `tpl-test-rotation-leaf`, and asserts a `--no-start` branch of each commits as `agent` and `rotated` respectively (no branch-time reconcile runs, so the identity is the template's own); then that the base's branch, still `agent`, commits as `rotated` after `isx run <it> --action=no-such-action` drives the next-use reconcile without a terminal; and that a child of `tpl-minimal` adding gh, whose inherited stamp is by then out of date, still gets gh's git defaults (`push.default`).
  The identity refresh runs after tool setup so it cannot create the `.gitconfig` gh's setup checks for.
  It backs up config.yaml and restores it on exit.
  The real CI token stays the `ci` account and remains the default, so the authenticated `git clone` and the existing github assertions are untouched.
  `test-vm` is branched with an account too, so the VM path -- metadata and file pushes over the incus-agent -- is covered rather than only containers.
  The `isx init` account menu is **not** exercised here.
  CI runs `isx init </dev/null`, so every credential prompt hits EOF and is skipped.
  Its decisions are unit-tested in `AccountMenuTest`, and the credential flows around it in `GitHubAuthFlowTest` / `ClaudeAuthFlowTest` / `CredentialPromptsTest`, which is possible because they read from `Prompts` rather than a `Console` (see `commands.md`).
- **`test-previous-release-config.sh <release>`**: runs on the host after the VM step, once per release users are on (currently v0.3.7, v0.3.8, v0.3.12 and v0.4.0; v0.3.7 differs only in also writing the old `host-path` key, v0.3.12 and v0.4.0 only in formatting, without the empty strings and stray keys).
  It swaps in the committed `common/src/test/resources/config-compat/<release>-full.yaml` -- generated by that release's own serializer, so every namespace is flat, with `""` values and a stray `oauthMode` up to v0.3.8 -- substituting only CI's gateway and echo-reportable placeholder credentials (line edits, not a YAML round trip, which would normalise away the shape under test).
  It checks that `isx account list` shows each configured namespace as `default  (default)` (on the release before #773 only `claude` was listed), that an unpinned instance and one pinned `<ns>=default` in every namespace are both served each flat credential, and that pinning a name the flat credential does not have is still refused.
  It restores the original config and SIGHUPs the proxy on exit, whatever happens.
  The write side -- the first save moving credentials into `accounts.default` -- cannot be driven here (CI's `isx init` never saves a credential) and is covered by `PreviousReleaseConfigCompatTest`, which runs in `unit-tests` with no Incus.
  It is deliberately not an `*IT`, which would only run under `-DskipITs=false`.

When adding a new end-to-end test, add an `assert` call in the appropriate script under a new numbered section.
The test runs as root inside the container; use `su -l agentuser -c "..."` to test user-level behavior.
The `tpl-minimal` base image is Fedora with only git, curl, which, procps-ng, and findutils -- install extra packages with `dnf install` inside the test if needed.

# Secret Scanning

`.github/workflows/gitleaks.yml` runs on every push and on PRs targeting `main` in three independent jobs: two scans with `gitleaks/gitleaks-action@v3`, and a test of the allowlists themselves.
It scans **the commits in that push or PR**, not the repository's full history -- the `fetch-depth: 0` checkout is there so the action can resolve the range, not so it re-scans everything.
The difference matters: a credential committed before this workflow existed, or in any range it did not scan, is not caught by it.
The stock ruleset's `generic-api-key` captures the type name after a Java `PrivateKey` parameter (`PrivateKey key, X509Certificate cert` in `CertificateAuthority.java`) as if it were a secret, so the root `.gitleaks.toml` allowlists the exact captured value `^X509Certificate$`.
It is anchored to the whole captured secret, so a value that merely starts with it (`apiKey = "X509Certificate…"`) is still flagged.
It applies to every rule in that config, not only `generic-api-key`; a secret exactly equal to `X509Certificate` is not a credential, so nothing real is hidden.
Without it a full-history scan reports three hits in that file.
(Do not paste such a signature into a file to illustrate the point: quoting one verbatim failed `scan-stock` on the commit that first documented it.)
Nothing re-scans history on a schedule.

- **`scan-stock`**: reads the root `.gitleaks.toml`, which sets `[extend] useDefault = true` to pull in gitleaks' own built-in rules.
- **`scan-leaktk`**: reads `.gitleaks-leaktk.toml` via `GITLEAKS_CONFIG`, which extends the vendored `.gitleaks/leaktk-gitleaks-8.27.0.toml` (unmodified copy of [leaktk/patterns](https://github.com/leaktk/patterns)' generated gitleaks config -- see the header comment in that file for the source commit and refresh instructions).
- **`allowlist-test`**: runs `.github/scripts/test-gitleaks-allowlist.sh` with the pinned `GITLEAKS_VERSION`, checksum-checked, and only `contents: read`, since it runs a script from the PR itself.

All three run one gitleaks version, `8.27.0` (the one leaktk generated its ruleset for), set once as `GITLEAKS_VERSION` in the workflow's top-level `env` beside its tarball's `GITLEAKS_SHA256`.
The scans read it and `allowlist-test` downloads it; a bump changes both lines.
It is not left to the action's default (8.24.3 in `gitleaks-action@v3`), because versions flag different fixture values (#1495).
8.24.3 reports the alphabet-run `ghp_` token of `SecretRedactorTest` and `SupportBundleTest` as `github-pat`, 8.27.0 does not, so the allowlist test vouched for nothing `scan-stock` ran, and a default bump in the action would have changed the scan with no change here.
The `github-pat` rule is the same in both: 8.27.0 hides the token through the default config's global stopword `abcdefghijklmnopqrstuvwxyz`, matched in any case, and 8.24.3 drops that default allowlist once a config extends the defaults with `useDefault`.
The test fails unless the workflow sets the version there and nowhere else, to the version the test runs.
Since it also scans the whole tracked tree, a bump to a version that flags another fixture fails it at once, in the PR making the bump.

gitleaks' `[extend]` table is mutually exclusive between `path` and `useDefault` (one config file can only pick one base ruleset to extend), which is why these are two separate config files and two separate scan passes rather than one merged ruleset.
They must be two **jobs**, not two steps in one job.
`gitleaks-action` installs the gitleaks binary to a fixed `/tmp` path, so a second invocation in the same job fails with "Destination file path /tmp/gitleaks.tmp already exists".
This shipped once and failed on the very first PR.

Each config carries its own `[allowlist]`, not shared via a common file.
The root `.gitleaks.toml` also repeats the leaktk config's fixture values, which the stock rules never flag.
The internal `rh-gitleaks` scanner runs a leaktk-style ruleset but reads only that default-named config, so without them it reported the config-compat fixtures' `sk-ant-oat01-fixture` as a Generic Secret.
A fixture value added to `.gitleaks-leaktk.toml` goes in both, and `test-gitleaks-allowlist.sh` fails when the root config lacks one.
Test fixtures are allowlisted by **value**, never by path (#1484): a path entry hides every secret in that file from every rule, a real key pasted into a fixture included.
The only path entry is `^\.gitleaks/leaktk-gitleaks-[0-9.]+\.toml$` in both, the vendored ruleset file alone, so a new file under `.gitleaks/` is still scanned: the vendored leaktk ruleset's own rule definitions and example regex fragments look like credentials to a scanner.
The root config holds the union of both value lists, and the leaktk config holds only the fixture values its own rules flag (leaktk's Generic Secret rule captures the short `sk-ant-api03-xyz`-style keys of the credential tests and the `config-compat/` YAMLs; the stock rules flag a fake PEM block and a short `ghp_` token in the redaction tests).
Entries are exact values, or a shape no service issues (the `isx_` proof tokens); never a prefix net such as `sk-ant-api03-` plus a few characters, which would hide a short real secret in any file.
A new test fixture with a fake credential needs its exact captured value (the report's `Secret`) added to the root config, and to the leaktk config too when its scan flags it (run both scans locally; the leaktk config takes no value its scan does not need), or it (rightly) fails the PR check the first time.
`.github/scripts/test-gitleaks-allowlist.sh` (job **`allowlist-test`**) holds both configs to this, on a copy of the tracked tree.
It plants one real-shaped key per credential shape the allowlists name exact values under: `sk-ant-api03-` and `sk-ant-oat01-` keys, 36-character `ghp_` and `gho_` tokens, an RSA private key block, and a Basic auth header carrying a `ghp_` token, each at real length with a mixed-case body.
The header is required of leaktk only: its Authorization Header rule is what captures the value, and gitleaks' defaults have no such rule.
They go at the top of every tracked `*/src/test/*` text file, and each config must report each key its rules capture, in each file, and nothing else.
The one allowance is a file the vendored ruleset itself skips for the rule that reports the key (leaktk's Private Key rule skips `tests?/.*\.txt`, so the TUI snapshots); the script prints how many, and a key reported in no file gets none.
It also fails on anything that suppresses outside the exact-value list: a path other than the vendored ruleset file, `stopwords`, `commits` or `regexTarget` in an allowlist, an inline allow comment in a tracked file, or a `.gitleaksignore`.
So does anything that hides a whole rule: an `[extend]` other than `useDefault = true` or the vendored `.gitleaks/` file alone (no `disabledRules`, no wrapper file), or a `[[rules]]` id other than the two Anthropic rules below, which would replace a base rule.
Keys are compared in any case, since gitleaks ignores the case of config keys.
And every allowlist regex (global, `[[allowlists]]` or per rule) must be anchored as a whole: no top-level `|`, no escaped final `\$`, no `(?m)` or `(?s)` flag.
Nor may it repeat without bound (`*`, `+` or `{n,}` outside a character class): such a regex is a net for real lengths whatever its anchors, as an indented PEM header followed by `(?:.*\n)*` would be.
Run it locally with the pinned gitleaks binary (8.27.0) as its argument; another version fails the pin check.
It reads configs and fixtures from the index, so stage your change first.
A net that widens a value entry to real lengths fails it; a bounded one that stays below them (`sk-ant-api03-[a-z]{1,30}`) does not, so keeping short prefix nets out is left to review.
A fixture value under a new credential prefix needs a planted key of that shape added to the script.
The vendored `.gitleaks/` ruleset's own allowlists are upstream data and not checked; review a refresh of it like any other allowlist change.
Allowlist `regexes` carry both anchors, `^…$`, around the whole captured secret.
Neither config sets `regexTarget`, so gitleaks tests them against the secret, not the line: a regex with surrounding context (a trailing quote) matches nothing.
A regex missing either anchor suppresses every longer secret that contains its match, a real key included.

Both configs also carry two custom `[[rules]]` (`anthropic-api-key`, `anthropic-oauth-token`) for `sk-ant-api03-`/`sk-ant-oat01-` keys -- neither gitleaks' defaults nor leaktk/patterns have an Anthropic-specific rule.
They're gated on a 40+ char suffix rather than matching the prefix alone.
Real keys run 90+ chars past the prefix, while every fake fixture in this repo tops out at 28, so the length gate catches a real leak without re-flagging the test placeholders above -- no allowlist entry needed for these two rules specifically.

This is a separate GitHub Action from a Red Hat-internal tool called rh-gitleaks (a wrapper around gitleaks with patterns from an internal Pattern Server) that may also flag this repo out-of-band -- rh-gitleaks can't run from a public-repo GitHub Actions runner, so it isn't wired into CI here.
leaktk/patterns is the closest public equivalent.
Its README states it backs an (unreleased) internal pattern server, so its ruleset is the nearest available proxy for what that tool would flag.

# Benchmarking

`bench/run.sh` measures native image performance: binary size, startup time, memory (idle and peak RSS), throughput, and latency.
See `bench/README.md` for full documentation.

```shell
bench/run.sh                              # Build native image + benchmark
bench/run.sh --skip-build                 # Reuse existing binary
bench/run.sh --label "before-my-change"   # Tag results for comparison
```

Requires Oracle GraalVM with `native-image`, a running Incus daemon, and a working `isx init` setup.
Results are saved as JSON to `bench/results/` and automatically compared with the previous run.
Use this before and after changes to the proxy, Vert.x configuration, or native image settings to catch regressions.
`--load=anthropic` and `--load=vertex` drive streamed model calls from Hyperfoil inside an instance branched from `tpl-isx-bench` (`bench/.incus-spawn/images/`), since the proxy injects only for a caller it knows by address and proof.
They answer from `UpstreamStub.java`, on a config of the run's own (`ISX_BENCH_CONFIG`), never the user's.
`--load=npm` drives the npm path (packument relay, cached tarball) from the host, and `--load=websocket` Codex-shaped WebSockets from `bench/WsLoad.java` inside the instance, Hyperfoil having no WebSocket support.
`--proxy=BIN`/`--cli=BIN` measure binaries built elsewhere, for an A/B of build flags.
`bench/pgo-collect.sh` collects the PGO profiles of docs/dev/PGO.md from instrumented builds: `run.sh --collect` once per load, then `cli.sh --collect`'s weighted flows, one gzipped `.iprof` per process.
`release.yml`'s `profiles` job runs it on every release, on Linux amd64 with Incus prepared as `isx-integration-tests-native` does, merges the dumps (`scripts/pgo-merge.sh`) and uploads `pgo-profiles`.
It runs the collection under `sudo -u "$USER"`, not `sg incus-admin`: rootless podman's `newuidmap` refuses a process whose primary group `sg` changed, so Hyperfoil cannot be extracted.
The four `build` jobs need it, and the three on GraalVM 25.4 build with `-Dnative.pgo=$GITHUB_WORKSPACE/pgo` (an absolute path) and check `.github/scripts/check-pgo-quality.sh`; the Intel macOS build stays unprofiled.
A `workflow_dispatch` of `release.yml` runs `profiles` and `build` alone, on a branch; `appliance`, `channel` and `release` need a pushed tag (`ReleasePgoWorkflowTest`).
`bench/ab.py` runs an interleaved A/B of builds of one commit over `run.sh` or `cli.sh`.

`bench/cli.sh` times `isx` commands against live Incus, JVM and native (`--runtime=both|jvm|native`), on a throwaway instance it branches and destroys itself.
It also covers the preparation `isx shell` does before attaching a terminal, via `isx run <instance> --action=<unknown>`, and time to a usable prompt for `isx shell` and a full `isx branch --shell`, run on a pseudo-terminal that types a marker command.
Results go to `bench/results/cli/` so `run.sh` never compares against them.
`bench/trace-branch.sh` records the daemon's events (`incus monitor`) during one `isx branch --shell` and prints a timeline with the longest gaps and operations, to find where a branch spends its time without adding any instrumentation to isx.
`bench/claude-start.sh` times a Claude Code template to Claude's first screen after `isx branch` and `isx run`, beside the shell prompt, the action's pre-launch history scan and `claude --version` timed in the instance (DESIGN.md "Claude Code's first screen: where the wait goes").
`bench/request-cost.sh` times plain requests on the daemon's socket, with no isx involved: what one read or one settings write costs on a host, the number to multiply a request count by.
`bench/vm-boot.sh` times a VM copy's boot phase by phase (firmware, GRUB, kernel, `incus-agent`, Incus seeing it, exec) with `incus` alone, Linux only, and `--set` tries an instance setting on the copies (DESIGN.md "VM boot: where the wait goes").
`cli.sh`, `trace-branch.sh` and `request-cost.sh` run on macOS against the appliance too: `cli.sh` needs no `incus` client there, and `trace-branch.sh` points the client at the appliance's socket by itself.
All of them are developer-run and too noisy for CI; the PR-time guard for CLI latency is the request-count budget in `InstanceLifecycleRequestBudgetTest` (see `incus.md`).
