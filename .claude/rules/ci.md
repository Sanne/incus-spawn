---
paths:
  - ".github/**"
  - "bench/**"
  # Cross-cutting trigger: VmManager holds the CLI half of the release-asset
  # name contract below, so editing it must load this file too.
  - "common/src/main/java/dev/incusspawn/vm/VmManager.java"
---

# CI Integration Tests

`.github/workflows/test-integration.yml` runs on every push/PR to `main`. Key jobs:

- **`unit-tests`**: `mvn package` (no Incus required)
- **`uber-jar-smoke`**: builds `-Prelease` and runs both uber-jars on a JVM -- what JBang users get. Nothing else in CI executes them, which is how the JBang channel broke unnoticed (issue #701); it also asserts that `proxy start` without an `isx-proxy` sibling exits 78 with install instructions rather than looping
- **`build-native-cli`**: builds the CLI native image, uploads artifact
- **`build-native-proxy`**: builds the proxy native image, uploads artifact
- **`integration-tests`**: boots the appliance VM image under QEMU, checks it reaches `ISX READY` and passes an Incus smoke test
- **`isx-integration-tests-native`**: installs Incus on Ubuntu 24.04, uses native binaries from the build-native jobs, runs `isx init`, starts the MITM proxy, builds templates (`tpl-minimal`, `tpl-test-podman`, `tpl-test-vm`), then runs test scripts inside branched instances
- **`fresh-daemon-init`**: verifies `isx init` on a daemon that has never been initialized

Each job runs on its own freshly-provisioned runner, so jobs never inherit each other's Incus state.

**No CI job boots the appliance on aarch64 or under vfkit.** This is worth stating precisely, because the runner list makes it look otherwise:

| where | what it does with the appliance |
|---|---|
| `test-integration.yml` → `integration-tests` (`ubuntu-latest`) | boots it under `qemu-system-x86` -- **x86_64 only** |
| `build-appliance.yml` → `build_x86_64`, `build_aarch64` (`ubuntu-24.04-arm`) | builds, checks size and embedded version, caches, uploads. Never boots it |
| `release.yml` native matrix (`macos-14`, `macos-15-intel`) | builds the native CLI/proxy and smoke-tests the *binaries* -- `--version`, `--help`, completions, `templates list`. Never starts a VM, and runs on `v*` tags only |

So macOS runners do exist, but nothing on them exercises the appliance, and no workflow invokes `vfkit` at all. The `isx init` calls in `test-integration.yml` are Linux-path only, where isx talks to host Incus over a Unix socket and the VM is not involved.

The practical consequence: the vfkit path -- the one macOS users actually run -- has **zero automated coverage**, and aarch64 boots are covered only by `appliance/test-boot.sh`, which is developer-run. A green CI is not evidence that an appliance change works on macOS. Anything touching the kernel config, the console, or the boot path needs a manual boot on both arches before it ships.

**Release asset names are a contract with the CLI.** `release.yml` publishes the appliance kernel as `vmlinuz-<arch>.gz` (gzipped there, not by `build-kernel.sh`, which still emits a plain `vmlinuz` for local QEMU/vfkit runs) and `VmManager.downloadKernel` gunzips it on the way into `~/.isx`. Renaming an asset on one side breaks the other, with the twist that a dev build resolves its appliance version to the *latest* release rather than its own -- which is why the download falls back to the pre-`.gz` name instead of failing. Change both sides together, and keep the fallback until no reachable release predates the rename.

`fresh-daemon-init` exists because `isx-integration-tests` runs `incus admin init --minimal` *before*
`isx init`, which populates the default profile -- so it cannot catch `isx init` failing to populate it
itself. It installs Incus and creates only the storage pool, with no `admin init`, reproducing the
state where a pool exists but the default profile is empty (every instance creation then fails with
"Failed getting root disk: No root device could be found"). It asserts the profile has a root disk and
a NIC, then launches a real instance -- a profile that merely looks right can still name a bad pool.

Note that `isx init` cannot be tested against the QEMU appliance VM on Linux: isx connects to the
*natively installed* Incus over `/run/incus/unix.socket` and the QEMU boot path exposes no vsock
socket, so it would hit the runner's own daemon and report a misleading success (see the guard in
`appliance/test-with-isx.sh`). The appliance also provisions Incus with its own shell script and never
calls `isx init` -- the "ensure default profile has a root disk and NIC" invariant is implemented twice,
in `incus-spawn-vm-init` (shell, in-VM) and `IncusClient.ensureDefaultProfileDevices` (Java, host).
Keep the two in sync.

The `isx-integration-tests` job exercises three environments: a container (from `tpl-minimal`), a rootless-podman container (from `tpl-test-podman`), and a VM (from `tpl-test-vm`). Test scripts live in `.github/scripts/`:

- **`test-instance.sh`**: pushed into containers and VMs, tests proxy interception (Maven/GitHub HTTPS), git clone, passwordless sudo, systemd lifecycle, DNS interception, login shell env vars, TLS certificate quality, and tool-contributed proxy credential injection (section 10 verifies bearer token injection via a test fixture tool `.github/test-fixtures/tools/test-proxy-tool.yaml` and a local HTTPS echo server at `echo.incus-spawn.test`). Uses `assert()` / `assert_eq()` shell helpers.
- **`test-podman.sh`**: pushed into the podman container, tests rootless podman (pull, run, build).
- **`test-accounts.sh`** + **`test-account-switching.sh`**: per-instance credential accounts. Two instances are branched from one template differing only in `--account`, so the assertion is the claim: one shared proxy, different real credentials, decided by who asked. The first runs inside each instance (its token names its own account, and no real token is anywhere in the container); the second runs on the host and drives `isx account set` against running instances -- a re-point takes effect on the next request with nothing restarted, and an instance pinned to a deleted account is refused rather than served the default. Both lean on the fixture tool `.github/test-fixtures/tools/test-account-tool.yaml` (namespace `testAccountTool`, domain `echo-accounts.incus-spawn.test`), kept separate from `test-proxy-tool.yaml` because that one pins the flat pre-accounts layout and both shapes must keep working. **No real credential is involved**: `echo-server.py` answers GitHub's `/user` and `/user/emails` as whichever account a `ghp-acct-<name>` token names, so it can play two identities at once -- which one real token never could. **`test-git-identity.sh`** uses that: an instance pinned to `github=agent` must *commit* as that account, not merely push with its token. It runs twice against `acct-gh` -- once before `isx account set ... github=agent` (asserting the template's baked `ci-user` identity) and once after -- so a pass means the re-point actually changed the identity rather than finding one that was already right. Its argument is the *login* the echo server answers as, not the account name, which is why the pre-swap call passes `ci-user` rather than `ci`; the script's title-casing mirrors Python's `str.title()` so a hyphenated login matches. The step drives `isx account set` rather than `isx branch --account` because a branch that starts also attaches a shell, which CI cannot do -- the branch-time reconcile is the same call, from `BranchCommand`. The real CI token stays the `ci` account and remains the default, so the authenticated `git clone` and the existing github assertions are untouched. `test-vm` is branched with an account too, so the VM path -- metadata and file pushes over the incus-agent -- is covered rather than only containers. The `isx init` account menu is **not** exercised here: CI runs `isx init </dev/null`, so every credential prompt hits EOF and is skipped; its decisions are unit-tested in `AccountMenuTest`, and the credential flows around it in `GitHubAuthFlowTest` / `ClaudeAuthFlowTest` / `CredentialPromptsTest`, which is possible because they read from `Prompts` rather than a `Console` (see `commands.md`).
- **`test-previous-release-config.sh <release>`**: runs on the host after the VM step, once per release users are on (currently v0.3.7 and v0.3.8; v0.3.7 differs only in also writing the old `host-path` key). It swaps in the committed `common/src/test/resources/config-compat/<release>-full.yaml` -- generated by that release's own serializer, so every namespace is flat, with `""` values and a stray `oauthMode` -- substituting only CI's gateway and echo-reportable placeholder credentials (line edits, not a YAML round trip, which would normalise away the shape under test). It checks that `isx account list` shows each configured namespace as `default  (default)` (on the release before #773 only `claude` was listed), that an unpinned instance and one pinned `<ns>=default` in every namespace are both served each flat credential, and that pinning a name the flat credential does not have is still refused. It restores the original config and SIGHUPs the proxy on exit, whatever happens. The write side -- the first save moving credentials into `accounts.default` -- cannot be driven here (CI's `isx init` never saves a credential) and is covered by `PreviousReleaseConfigCompatTest`, which runs in `unit-tests` with no Incus: it is deliberately not an `*IT`, which would only run under `-DskipITs=false`.

When adding a new end-to-end test, add an `assert` call in the appropriate script under a new numbered section. The test runs as root inside the container; use `su -l agentuser -c "..."` to test user-level behavior. The `tpl-minimal` base image is Fedora with only git, curl, which, procps-ng, and findutils -- install extra packages with `dnf install` inside the test if needed.

# Secret Scanning

`.github/workflows/gitleaks.yml` runs on every push/PR to `main` with `gitleaks/gitleaks-action@v2`, in two independent jobs. It scans **the commits in that push or PR**, not the repository's full history -- the `fetch-depth: 0` checkout is there so the action can resolve the range, not so it re-scans everything. The difference is load-bearing and easy to get wrong: a full-history scan of this repo reports four `generic-api-key` hits in `CertificateAuthority.java`, from commits in April and July 2026, on both gitleaks 8.27.0 and 8.30.1. All four are false positives -- Java method and record signatures declaring a private-key parameter next to a certificate one, where the rule treats the type name following the `key` identifier as its value. CI is green because it never looks at those commits. (Do not paste one of those signatures into a file to illustrate the point: quoting it verbatim here failed `scan-stock` on the very commit that added this paragraph.) So a credential committed before this workflow existed, or in any range it did not scan, is not caught by it, and nothing re-scans history on a schedule.

- **`scan-stock`**: reads the root `.gitleaks.toml`, which sets `[extend] useDefault = true` to pull in gitleaks' own built-in rules.
- **`scan-leaktk`**: reads `.gitleaks-leaktk.toml` via `GITLEAKS_CONFIG`, which extends the vendored `.gitleaks/leaktk-gitleaks-8.27.0.toml` (unmodified copy of [leaktk/patterns](https://github.com/leaktk/patterns)' generated gitleaks config -- see the header comment in that file for the source commit and refresh instructions). `GITLEAKS_VERSION` is pinned to `8.27.0` to match the version leaktk generated the ruleset for.

gitleaks' `[extend]` table is mutually exclusive between `path` and `useDefault` (one config file can only pick one base ruleset to extend), which is why these are two separate config files and two separate scan passes rather than one merged ruleset. They must be two **jobs**, not two steps in one job: `gitleaks-action` installs the gitleaks binary to a fixed `/tmp` path, so a second invocation in the same job fails with "Destination file path /tmp/gitleaks.tmp already exists" -- this shipped once and failed on the very first PR.

Both configs carry the same `[allowlist]` (kept in sync manually, not shared via a common file): known-fake credentials in config/proxy unit tests that legitimately need key-shaped strings to exercise parsing and redaction logic (`ClaudeLegacyConfigCompatTest`, `ClaudeAccountsTest`, `ProxyCredentialsAccountsTest`, `SecretRedactorTest`, `SupportBundleTest`, `HelpContextTest`, and the previous-release fixtures under `common/src/test/resources/config-compat/`), plus `^\.gitleaks/` -- the vendored leaktk ruleset's own rule definitions and example regex fragments look like credentials to a scanner. Path-based allowlist entries apply across all historical commits gitleaks scans, since matching is on file path, not commit content -- so this also covers commits that predate the allowlist. A new test fixture with a fake credential needs its path added to **both** `.toml` files, or it (rightly) fails the PR check the first time.

Both configs also carry two custom `[[rules]]` (`anthropic-api-key`, `anthropic-oauth-token`) for `sk-ant-api03-`/`sk-ant-oat01-` keys -- neither gitleaks' defaults nor leaktk/patterns have an Anthropic-specific rule. They're gated on a 40+ char suffix rather than matching the prefix alone: real keys run 90+ chars past the prefix, while every fake fixture in this repo tops out at 28, so the length gate catches a real leak without re-flagging the test placeholders above -- no allowlist entry needed for these two rules specifically.

This is a separate GitHub Action from a Red Hat-internal tool called rh-gitleaks (a wrapper around gitleaks with patterns from an internal Pattern Server) that may also flag this repo out-of-band -- rh-gitleaks can't run from a public-repo GitHub Actions runner, so it isn't wired into CI here. leaktk/patterns is the closest public equivalent: its README states it backs an (unreleased) internal pattern server, so its ruleset is the nearest available proxy for what that tool would flag.

# Benchmarking

`bench/run.sh` measures native image performance: binary size, startup time, memory (idle and peak RSS), throughput, and latency. See `bench/README.md` for full documentation.

```shell
bench/run.sh                              # Build native image + benchmark
bench/run.sh --skip-build                 # Reuse existing binary
bench/run.sh --label "before-my-change"   # Tag results for comparison
```

Requires Oracle GraalVM with `native-image`, a running Incus daemon, and a working `isx init` setup. Results are saved as JSON to `bench/results/` and automatically compared with the previous run. Use this before and after changes to the proxy, Vert.x configuration, or native image settings to catch regressions.

`bench/cli.sh` times `isx` commands against live Incus, JVM and native (`--runtime=both|jvm|native`), on a throwaway instance it branches and destroys itself. It also covers the preparation `isx shell` does before attaching a terminal, via `isx run <instance> --action=<unknown>`. Results go to `bench/results/cli/` so `run.sh` never compares against them. It is developer-run and too noisy for CI; the PR-time guard for CLI latency is the request-count budget in `InstanceLifecycleRequestBudgetTest` (see `incus.md`).
