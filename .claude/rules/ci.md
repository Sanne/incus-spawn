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

# Benchmarking

`bench/run.sh` measures native image performance: binary size, startup time, memory (idle and peak RSS), throughput, and latency. See `bench/README.md` for full documentation.

```shell
bench/run.sh                              # Build native image + benchmark
bench/run.sh --skip-build                 # Reuse existing binary
bench/run.sh --label "before-my-change"   # Tag results for comparison
```

Requires Oracle GraalVM with `native-image`, a running Incus daemon, and a working `isx init` setup. Results are saved as JSON to `bench/results/` and automatically compared with the previous run. Use this before and after changes to the proxy, Vert.x configuration, or native image settings to catch regressions.
