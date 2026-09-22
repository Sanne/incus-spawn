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

When adding a new end-to-end test, add an `assert` call in the appropriate script under a new numbered section. The test runs as root inside the container; use `su -l agentuser -c "..."` to test user-level behavior. The `tpl-minimal` base image is Fedora with only git, curl, which, procps-ng, and findutils -- install extra packages with `dnf install` inside the test if needed.

# Secret Scanning

`.github/workflows/gitleaks.yml` runs on every push/PR to `main` and scans full git history (not just the diff) with `gitleaks/gitleaks-action@v2`, in two independent jobs:

- **`scan-stock`**: reads the root `.gitleaks.toml`, which sets `[extend] useDefault = true` to pull in gitleaks' own built-in rules.
- **`scan-leaktk`**: reads `.gitleaks-leaktk.toml` via `GITLEAKS_CONFIG`, which extends the vendored `.gitleaks/leaktk-gitleaks-8.27.0.toml` (unmodified copy of [leaktk/patterns](https://github.com/leaktk/patterns)' generated gitleaks config -- see the header comment in that file for the source commit and refresh instructions). `GITLEAKS_VERSION` is pinned to `8.27.0` to match the version leaktk generated the ruleset for.

gitleaks' `[extend]` table is mutually exclusive between `path` and `useDefault` (one config file can only pick one base ruleset to extend), which is why these are two separate config files and two separate scan passes rather than one merged ruleset. They must be two **jobs**, not two steps in one job: `gitleaks-action` installs the gitleaks binary to a fixed `/tmp` path, so a second invocation in the same job fails with "Destination file path /tmp/gitleaks.tmp already exists" -- this shipped once and failed on the very first PR.

Both configs carry the same `[allowlist]` (kept in sync manually, not shared via a common file): known-fake credentials in config/proxy unit tests that legitimately need key-shaped strings to exercise parsing and redaction logic (`ClaudeLegacyConfigCompatTest`, `ClaudeAccountsTest`, `ProxyCredentialsAccountsTest`, `SecretRedactorTest`, `SupportBundleTest`, `HelpContextTest`), plus `^\.gitleaks/` -- the vendored leaktk ruleset's own rule definitions and example regex fragments look like credentials to a scanner. Path-based allowlist entries apply across all historical commits gitleaks scans, since matching is on file path, not commit content -- so this also covers commits that predate the allowlist. A new test fixture with a fake credential needs its path added to **both** `.toml` files, or it (rightly) fails the PR check the first time.

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
