---
paths:
  - "common/src/main/java/dev/incusspawn/incus/**"
  - "common/src/main/java/dev/incusspawn/vm/**"
  - "common/src/main/java/dev/incusspawn/ClientLog.java"
  - "appliance/**"
  - "cli/src/main/java/dev/incusspawn/command/BuildCommand.java"
  - "cli/src/main/java/dev/incusspawn/command/BranchCommand.java"
---

# Incus Interaction

`IncusClient` communicates with the Incus daemon via its REST API. On Linux, requests go over a Unix domain socket (`UnixSocketTransport`); on macOS, over a vsock tunnel exposed as a Unix socket (same `UnixSocketTransport`). `IncusApi.tryConnect()` selects Linux Unix sockets -> vsock Unix socket; there is no HTTPS fallback (the old HTTPS-over-TCP path was removed -- it hit macOS Local Network prompts and VPN socket filters, and two transports made field issues undiagnosable; `HttpsTransport` remains in the tree but is unwired). `IncusApi` handles request serialization, async operation waiting, and WebSocket-based exec (capture, stream, PTY). `Container` is a helper for running commands inside a specific container (`exec`, `runAsUser`, `runInteractive`). The `incus` CLI binary is not required at runtime.

**macOS vsock robustness**: the vfkit vsock tunnel does not reliably propagate connection close/EOF, which drives several design choices (see DESIGN.md "Transport" and appliance/DESIGN.md):
- **Exec completion via `/wait`, not close frames.** `IncusApi.execWebSocket` unifies capture/stream/bidirectional exec and takes the operation `/wait` endpoint (daemon operation state over HTTP) as the authoritative completion + exit-code signal, then drains and force-closes the data sockets -- so a lost close frame can't hang exec. Every exec fd is keepalive-pinged; the drain is adaptive.
- **Keep-alive connection cache.** Short request-path calls (`get`/`post`/`/wait`) reuse a warm connection via `requestPooled` -> `ConnectionPool`/`KeepAliveConnection` instead of reconnecting per call; the exec WebSocket fds are per-operation and not pooled.
- **Forwarder leak + recovery.** The same close-propagation gap makes the in-VM `socat` forwarder leak connections. A `socat -T` inactivity backstop reaps them; `isx doctor` diagnoses it (host-side connection gauge in `UnixSocketTransport` / `vm status` vs the in-VM `isx-agent`'s socat count, localizing vfkit vs forwarder) and can restart the forwarder via the agent **without rebooting the VM**. `ClientLog` is a file-only (TUI-safe) diagnostic log for expected-but-noisy events like stale-connection recycling. The agent (`appliance/root/usr/local/sbin/isx-agent`, host side `VmAgentClient`) is an allowlisted one-verb-per-connection dispatcher -- `ping`, `socat-count`, `sshd-status`, `forwarder-restart`, and `btrfs-usage <pool> [sync]` (returns the pool's `btrfs qgroup show`/`subvolume list` output for disk accounting, since only the in-VM root agent can read the pool; the allowlisted `sync` second token forces a commit first) -- intentionally NOT a general guest-exec channel.

**Storage pool awareness**: On a CoW-capable pool (btrfs/zfs/lvm), Incus implements a same-pool `type: copy` as a native snapshot (e.g. `btrfs subvolume snapshot`) -- no explicit snapshot API call is needed. Full copies only happen when (1) there is no CoW pool (the `dir` driver rsyncs), or (2) the source's root disk is on a different pool than the copy target (cross-pool migration). `IncusClient.copy()` follows the source's pool via `planCopy()`: if the source's root disk is on a CoW pool, the copy targets the same pool; otherwise it falls back to the first CoW pool or the profile default. `isx branch` and `BuildCommand.buildFromParent()` warn when a copy will be full (non-CoW), and `isx doctor` surfaces both cases -- no CoW pool (FAIL with Linux remediation) and instances on non-CoW pools (WARN with rebuild/move guidance). `isCowDriver()` and `rootDiskPoolFromDevices()` are the unit-testable helpers.
