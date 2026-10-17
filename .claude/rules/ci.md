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
  Test failures are counted from the surefire reports after the run, so a failure in `common` does not skip `cli` and `proxy` (`-fae` would: they depend on it), and the reports are uploaded when it fails, since few contributors have a Mac to reproduce on
- **Test JVM flags**: the root pom's `argLine` property keeps `com.sun.crypto.provider.AESCrypt::makeSessionKey` out of the JIT, a GraalVM 25 JIT bug that made the proxy tests flake with `bad_record_mac` (#940, upstream: [oracle/graal#14599](https://github.com/oracle/graal/issues/14599), DESIGN.md "Testing").
  Add to that property and never set surefire's `<argLine>`, which would drop both the flag and the arguments the Quarkus plugin adds; `-DargLine=` on the command line replaces it too.
  The JVM launchers carry the same options (`install.sh`'s `jvm_options()` on a Graal-JIT JDK, `java-options` on the JBang aliases); `LauncherJitWorkaroundTest` keeps them equal to the `argLine`'s `-XX:CompileCommand` options, so change those in all three together.
  `JitWorkaroundTest` checks it in the proxy module, where the TLS tests are.
  That the excluded method still exists is checked only on a Graal-JIT JVM (`UseJVMCICompiler`), since other JDKs need no exclude and JDK 27 has no `AESCrypt` at all (#1050)
- **`uber-jar-smoke`**: builds `-Prelease` and runs both uber-jars on a JVM -- what JBang users get.
  Nothing else in CI executes them, which is how the JBang channel broke unnoticed (issue #701); it also asserts that `proxy start` without an `isx-proxy` sibling exits 78 with install instructions rather than looping
- **`build-native-cli`**: builds the CLI native image, uploads artifact
- **`build-native-proxy`**: builds the proxy native image, uploads artifact
- **`integration-tests (x86_64)`, `integration-tests (aarch64)`**: a matrix.
  Each boots its arch's appliance VM image under QEMU with a `vhost-vsock-pci` device, checks it reaches `ISX READY` and passes the in-guest Incus smoke test, then bridges the guest's vsock ports to Unix sockets with host `socat` (standing in for vfkit) and runs `appliance/test-tunnel.sh` and the real native `isx` through them -- see below
- **`isx-integration-tests-native`**: installs Incus on Ubuntu 24.04, uses native binaries from the build-native jobs, runs `isx init`, starts the MITM proxy, builds templates (`tpl-minimal`, `tpl-test-podman`, `tpl-test-podman-rootless`, `tpl-test-vm`, `tpl-test-codex`, `tpl-test-mcp`), then runs test scripts inside branched instances
- **`fresh-daemon-init`**: verifies `isx init` on a daemon that has never been initialized

The **`update-base-image-steps`** job in `test-integration.yml` runs `.github/scripts/test-update-base-image.sh`: `update-base-image.yml`'s three steps, as its `workflow_dispatch` backstop runs them, against a fake images release with `-vm-direct` and `SHA256SUMS.v2`, `gh`, `curl` and the remote faked (#1255).
It checks the switch to `-vm-direct` with both arches' checksums, also after a stock-image bump of the same release was merged or while its branch is open; a routine bump of a built-in already on `-vm-direct` (plain title, `-vm-direct` branch); the refusal to move such a built-in back to stock when a release lacks `-vm-direct`; and that the PR title names the switch and a release published again under its tag.
It starts from a stock built-in written in the script, whose keys, nested ones included, must match `minimal.yaml`'s, and finds the workflow's steps by name, so renaming a key or a step fails it too.
`.github/workflows/test-vm-kernel.yml` (**`boot (x86_64)`**, **`boot (aarch64)`**) runs when `appliance/kernel/**`, `appliance/test-vm-kernel.sh` or `minimal.yaml` change (#1255).
It builds the VM base image's kernel with `build-kernel.sh vm` and boots it with `appliance/test-vm-kernel.sh` on the VM image `minimal.yaml` pins, downloaded and checked against `vm_image_sha256`.
x86_64 boots under KVM, aarch64 under TCG; the console logs and `systemd-analyze plot`s are uploaded.
It shares the kernel-source cache key with `build-appliance.yml`, and depends on no image release.

**`signed kernel pin`** (`.github/workflows/kernel-pin-signed.yml`, #1355) runs on a change under `appliance/kernel/`: `main`'s `appliance/kernel/update-kernel-pin.sh check <file>`, with `main`'s key and `AUTOSIGNER_FPR`, on the PR head's `build-kernel.sh`, and fails unless its `KERNEL_SHA256` is the sum in kernel.org's `sha256sums.asc`, and unless the file sets `KERNEL_VERSION` and `KERNEL_SHA256` once each, as `NAME="value"` lines (a second assignment would be the one the build uses).
That catches the plain forms only: a PR that changes how `build-kernel.sh` uses its pin still needs a person to read it.
It runs on `pull_request_target` and checks out the default branch whatever the PR's base, because a PR that changed the key, the fingerprint or the script along with the pin would pass its own copy; the PR's file is read as data, and nothing of the PR runs.
The head, not the merge: a clean merge carries the head's pin when the PR changed it and `main`'s otherwise, and a conflicting one cannot merge until it is rebased and checked again.
Every edit re-runs it, a change of base included: a run whose job was skipped would show as passing over a failed one on the same head.
A PR that rotates the key therefore fails it until merged, which is the deliberate review the three-source check asks for.

`.github/workflows/update-kernel.yml` runs daily and on `workflow_dispatch`, in `Sanne/incus-spawn` and from `main` only, since it runs with `BOT_TOKEN` and builds the bump from the ref it runs on; the checkout keeps no credentials, and the PR step pushes through `gh auth setup-git`.
`update-kernel-pin.sh bump` moves the pin to the newest point release of its series, and the job keeps one PR on `bot/kernel-bump`, opened with `BOT_TOKEN` so CI runs on it and labelled `env:macos` for the Mac boot.
A newer release, or a `main` whose `build-kernel.sh` moved under the branch, rebuilds it in three steps: the PR is renamed and marked pending (its body says not to boot or review it, carries a pending marker instead of the release's, and loses `env:macos`), the fresh commit on `main` is pushed with a lease on the branch as the run read it, and only then is the PR told the old Mac boot no longer applies and given the release's marker and `env:macos` again; a failure at any step leaves a PR that claims nothing its branch lacks, and the next run finishes the refresh.
A branch that already carries the release, on a base (GitHub's merge base with `main`) whose pin is `main`'s, keeps its commit, whatever else was merged into it; its PR is finished if a refresh left it pending, and otherwise left alone, a body edited by hand included.
A release whose PR was closed unmerged is skipped (by the marker in the PR body, pending or not, not its title, stripped when the job closes a PR itself); a PR `main` has caught up with is closed, but not one proposing a newer release than the signed sums now list; a failed read of the PR's body, or a branch whose pin cannot be read, fails the run before anything is edited.
A commit on the branch the job did not make stops any push or close (the run fails instead), as does one pushed while the run is going (the lease), and only this repository's own `bot/kernel-bump` counts, never a fork's branch of that name.
It opens `[kernel] <series> is EOL` when kernel.org ends the series (closed once the pin is on a live one; checked when the PR step failed too), and `[kernel] update check failing` when a run fails (a signature that does not verify fails closed), closed by the next good run.
The **`update-kernel-steps`** job in `test-integration.yml` runs `.github/scripts/test-update-kernel.sh`: the PR step against a local remote, with a fake `gh` that applies each `--jq` filter to fixture JSON.
`KernelPinUpdateTest` covers the script itself offline, with a throwaway signing key.

`scripts/local-incus.sh` reproduces the `isx-integration-tests-native` setup (packages, `sg incus-admin -c "isx init </dev/null"`, the default profile on the `cow` pool) on a local host, for the `validate-on-real-incus` skill.
A change to how that job prepares Incus belongs in the script too.
The script also installs `virtiofsd`, which Fedora's `incus` package does not pull in: without it Incus shares VM disks over 9p, which cannot be hot-plugged (#853).
Nested inside an isx instance it also works around what a user namespace refuses (Incus's bridge firewall rules, the unmapped subordinate-ID range, the iptables redirect); the skill says what it cannot fix there.

Each job runs on its own freshly-provisioned runner, so jobs never inherit each other's Incus state.

**No CI job boots the appliance under vfkit.**
Both arches boot under QEMU; this is worth stating precisely, because the runner list makes it look like more:

| where | what it does with the appliance |
|---|---|
| `test-integration.yml` → `integration-tests (x86_64)` (`ubuntu-26.04`) | boots it under `qemu-system-x86_64` with KVM and vsock; full tunnel checks, backstop, native `isx` |
| `test-integration.yml` → `integration-tests (aarch64)` (`ubuntu-24.04-arm`) | boots it under `qemu-system-aarch64` with **TCG** (the arm64 runners expose no `/dev/kvm`) and vsock; smoke test and tunnel checks, but not the backstop (a fixed 3-minute wait that is arch-independent) or native `isx` (CI builds no arm64 CLI). vhost-vsock works under TCG, and the appliance reaches `ISX READY` in about 20s |
| `build-appliance.yml` → `build_x86_64`, `build_aarch64` (`ubuntu-26.04`, `ubuntu-24.04-arm`) | builds, checks size and embedded version, caches, uploads. Never boots it |
| `test-integration.yml` → `unit-tests-macos` (`macos-15`) | nothing: runs `mvn test` on the host, the macOS half of the Java code only |
| `release.yml` native matrix (`macos-15`, `macos-15-intel`) | builds the native CLI/proxy for macOS 15 (Intel is best effort: shipped, but this smoke test is all it gets; no Intel Mac verifies it, #1086) and smoke-tests the *binaries* -- `--version`, `--help`, completions, `templates list`; the release job then checks each one's recorded minimum macOS. Never starts a VM, and runs on `v*` tags only |

**macOS runner labels are pinned, and retired ones are caught by a test.**
Workflows name a versioned label (`macos-15`, `macos-15-intel`), never `macos-latest`.
The binaries' minimum macOS no longer follows the runner.
It is the root pom's `macos.deployment.target` (15.0, Sequoia, #1086), linked in by a macOS-activated profile.
The release job's "Check macOS binaries' minimum macOS" step (`scripts/check-macos-minos.py`) fails unless every macOS artifact the release uploads records exactly that `minos` (see DESIGN.md "The minimum macOS is set by the build").
The Homebrew formulas declare it as `depends_on macos: :sequoia`, which `release.yml`'s tap step writes from `MACOS_MIN` on every release; README and `docs/HOMEBREW.md` state it.
GitHub retires old images (`macos-14` with its `-large`/`-xlarge` variants, #1000), and a job on a removed label waits for a runner that never comes.
Because `release.yml` runs only on `v*` tags, nothing but the next release would notice, so `WorkflowRunnerLabelsTest` fails on `macos-14` or older and on `macos-latest` in any workflow.
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
For the appliance kernel this is a check, not a habit: **`kernel-mac-verified`** (`.github/workflows/kernel-mac-verified.yml`, #1355) fails a PR whose merge changes the kernel's inputs until a Mac boot of a build with exactly those inputs is recorded.
The inputs are what `appliance/kernel/kernel-fingerprint.sh` hashes, by their git blobs: `appliance/build.sh`, and in `appliance/kernel/` `build-kernel.sh` and `isx*.config`.
Every other file in `appliance/kernel/` is listed there as not an input, and an unlisted one fails the fingerprint, so a new file is classified before it can change the kernel unseen.
The compiler is not covered: `build.sh` takes it from `fedora:44` when it runs, and the pre-release Mac run still sees a change there.
`build-appliance.yml` puts the fingerprint in the appliance's cache key, so the image always covers its kernel's inputs, and writes `kernel-inputs` beside it: the commit it built (on a PR, the merge `actions/checkout` made), the fingerprint and `KERNEL_VERSION`.
On the bare-metal Mac, `appliance/test-kernel-on-mac.sh <pr>`, run from a checkout of `main`, takes the `appliance-aarch64` artifact of the newest successful `build-appliance.yml` run of the PR's head, refuses it unless its `kernel-inputs` names the PR's merge as GitHub has it now (a build from before the base moved is not what would be merged; a push rebuilds), fingerprints that merge with its own `main` copy of the script, as the check does, checks the kernel's `Linux version` banner against the merge's `KERNEL_VERSION` (`X.Y` reports `X.Y.0`), runs `test-boot.sh` and `test-with-isx.sh` on it, posts a `### Mac kernel verification` record (head, build, fingerprint, run, artifact sums, machine, each result) and sets the status `isx/mac-kernel-boot` on the head (`success` only if all passed, description `fingerprint <fp>`), then re-runs the check.
The check passes on a `success` for the fingerprint of the PR merged into its base, set by a login in its `VERIFIERS` (Sanne, Sanne-ai-bot), on the head or on a commit one of their records names, so a rebase that leaves the kernel alone keeps the boot, and a base that changed the kernel under the PR does not; the newest status a verifier set on a commit is the one that counts.
A PR that touches neither `appliance/build.sh` nor `appliance/kernel/` passes from its file list alone; for one that does, it waits up to two minutes for GitHub to merge the new head and fails if `refs/pull/<n>/merge` still merges another, and an API error fails it rather than reading as "no boot".
Nothing re-runs it when the base branch moves, so a base that changes the kernel under a green PR is caught only if branch protection requires branches to be up to date before merging.
Both the check and the Mac fingerprint with `main`'s `kernel-fingerprint.sh`, so a change to what it counts as an input, or a new file in `appliance/kernel/`, goes in a PR of its own first.
It runs on `pull_request_target` and checks out the default branch, so it is `main`'s copy that runs, whatever the PR's base: a PR cannot change its own gate, and the check reads the PR only as git trees and through the API, with a read-only token; never add a step there that runs anything from the PR.
It takes the PR's head from the API rather than `github.event.pull_request.head.sha`: a `pull_request_target` workflow that names it matches leaktk's "vulnerable workflow" rule and fails `scan-leaktk`, and that rule is worth keeping for the day a step does check the head out.
The **`kernel-mac-gate-steps`** job in `test-integration.yml` runs both scripts on a fixture repository with `gh` and the Mac faked (`.github/scripts/test-kernel-mac-gate.sh`).
A green CI is not evidence that vfkit behaves.

The appliance cache key hashes `appliance/**` except `appliance/test-*.sh`, `appliance/*.md` and `appliance/kernel/vm*.config`, which never enter the image, and appends the kernel's fingerprint, so editing a test script or a VM kernel fragment does not force an appliance rebuild.
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
It asserts the profile has a root disk and a NIC, then launches a real instance -- a profile that merely looks right can still name a bad pool.

Note that `isx init` is not run against the QEMU appliance VM: on Linux it provisions a *native* Incus, and the appliance has its own provisioning.
The vsock-bridged boot in `integration-tests` exercises the client path to the appliance instead (`isx` over `vm.incus.sock`), which needs no `init`.
`appliance/test-with-isx.sh` stays macOS-only because it has isx *boot* the VM, and isx's QEMU backend deliberately wires no vsock (Linux users run native Incus).
The appliance also provisions Incus with its own shell script and never calls `isx init` -- the "ensure default profile has a root disk and NIC" invariant is implemented twice, in `incus-spawn-vm-init` (shell, in-VM) and `IncusClient.ensureDefaultProfileDevices` (Java, host).
Keep the two in sync.

The `isx-integration-tests` job exercises five environments: a container (from `tpl-minimal`), a podman container with the default rootful socket (from `tpl-test-podman`), one with every non-default `podman` parameter (from `tpl-test-podman-rootless`), a codex container (from `tpl-test-codex`), and a VM (from `tpl-test-vm`).
The accounts scripts below branch two further instances of their own.
Test scripts live in `.github/scripts/`; a script pushed into a guest cannot use awk (the base image has none; grep, sed and bash do the job):

- **`test-instance.sh`**: pushed into containers and VMs, tests proxy interception (Maven/GitHub HTTPS), git clone, passwordless sudo, systemd lifecycle, DNS interception (A to the gateway, no `::` or real AAAA answer, #814), login shell env vars, TLS certificate quality, and tool-contributed proxy credential injection (section 9 verifies bearer token injection via a test fixture tool `.github/test-fixtures/tools/test-proxy-tool.yaml` and a local HTTPS echo server at `echo.incus-spawn.test`).
  Uses `assert()` / `assert_eq()` shell helpers.
- **`test-airgap.sh`**: runs on the host.
  Branches `tpl-minimal` with `--airgap` and asserts on the *expanded* devices (no `nic` at all), the `network-mode` stamp, and from inside that the started branch has no interface but loopback and cannot reach the internet.
  Then branches it again without `--airgap` and asserts the NIC and an address come back.
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
- **`test-previous-release-config.sh <release>`**: runs on the host after the VM step, once per release users are on (currently v0.3.7 and v0.3.8; v0.3.7 differs only in also writing the old `host-path` key).
  It swaps in the committed `common/src/test/resources/config-compat/<release>-full.yaml` -- generated by that release's own serializer, so every namespace is flat, with `""` values and a stray `oauthMode` -- substituting only CI's gateway and echo-reportable placeholder credentials (line edits, not a YAML round trip, which would normalise away the shape under test).
  It checks that `isx account list` shows each configured namespace as `default  (default)` (on the release before #773 only `claude` was listed), that an unpinned instance and one pinned `<ns>=default` in every namespace are both served each flat credential, and that pinning a name the flat credential does not have is still refused.
  It restores the original config and SIGHUPs the proxy on exit, whatever happens.
  The write side -- the first save moving credentials into `accounts.default` -- cannot be driven here (CI's `isx init` never saves a credential) and is covered by `PreviousReleaseConfigCompatTest`, which runs in `unit-tests` with no Incus.
  It is deliberately not an `*IT`, which would only run under `-DskipITs=false`.

When adding a new end-to-end test, add an `assert` call in the appropriate script under a new numbered section.
The test runs as root inside the container; use `su -l agentuser -c "..."` to test user-level behavior.
The `tpl-minimal` base image is Fedora with only git, curl, which, procps-ng, and findutils -- install extra packages with `dnf install` inside the test if needed.

# Secret Scanning

`.github/workflows/gitleaks.yml` runs on every push and on PRs targeting `main` with `gitleaks/gitleaks-action@v3`, in two independent jobs.
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
  `GITLEAKS_VERSION` is pinned to `8.27.0` to match the version leaktk generated the ruleset for.

gitleaks' `[extend]` table is mutually exclusive between `path` and `useDefault` (one config file can only pick one base ruleset to extend), which is why these are two separate config files and two separate scan passes rather than one merged ruleset.
They must be two **jobs**, not two steps in one job.
`gitleaks-action` installs the gitleaks binary to a fixed `/tmp` path, so a second invocation in the same job fails with "Destination file path /tmp/gitleaks.tmp already exists".
This shipped once and failed on the very first PR.

Each config carries its own `[allowlist]`, not shared via a common file.
The path lists differ on purpose: each lists the fixtures whose strings that config's rules actually flag.
They cover unit tests that legitimately need key-shaped strings to exercise parsing and redaction logic (`ClaudeLegacyConfigCompatTest`, `ClaudeAccountsTest`, `ProxyCredentialsAccountsTest`, `SecretRedactorTest`, `ExistingConfigCompatTest`, `PerInstanceCredentialsTest`, `SupportBundleTest`, `ClaudeAuthFlowTest`, `HelpContextTest`, the leaktk-only `PinnedAccountStateTest`, and the previous-release fixtures under `common/src/test/resources/config-compat/`).
Both also list `^\.gitleaks/`: the vendored leaktk ruleset's own rule definitions and example regex fragments look like credentials to a scanner.
Path-based allowlist entries apply across all historical commits gitleaks scans, since matching is on file path, not commit content -- so this also covers commits that predate the allowlist.
A new test fixture with a fake credential needs its path added to whichever `.toml` file's scan flags it (run both scans locally; do not add a path a scan does not need, since a path entry also hides a real key pasted into that file), or it (rightly) fails the PR check the first time.
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

`bench/cli.sh` times `isx` commands against live Incus, JVM and native (`--runtime=both|jvm|native`), on a throwaway instance it branches and destroys itself.
It also covers the preparation `isx shell` does before attaching a terminal, via `isx run <instance> --action=<unknown>`, and time to a usable prompt for `isx shell` and a full `isx branch --shell`, run on a pseudo-terminal that types a marker command.
Results go to `bench/results/cli/` so `run.sh` never compares against them.
`bench/trace-branch.sh` records the daemon's events (`incus monitor`) during one `isx branch --shell` and prints a timeline with the longest gaps and operations, to find where a branch spends its time without adding any instrumentation to isx.
`bench/request-cost.sh` times plain requests on the daemon's socket, with no isx involved: what one read or one settings write costs on a host, the number to multiply a request count by.
`bench/vm-boot.sh` times a VM copy's boot phase by phase (firmware, GRUB, kernel, `incus-agent`, Incus seeing it, exec) with `incus` alone, Linux only, and `--set` tries an instance setting on the copies (DESIGN.md "VM boot: where the wait goes").
`cli.sh`, `trace-branch.sh` and `request-cost.sh` run on macOS against the appliance too: `cli.sh` needs no `incus` client there, and `trace-branch.sh` points the client at the appliance's socket by itself.
All of them are developer-run and too noisy for CI; the PR-time guard for CLI latency is the request-count budget in `InstanceLifecycleRequestBudgetTest` (see `incus.md`).
