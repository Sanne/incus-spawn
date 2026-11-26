# macOS, the appliance and VMs

How isx reaches Incus, on Linux and through the macOS appliance, and how VM instances boot.

## Incus Daemon Connection

The CLI communicates with the Incus daemon via its REST API.
The transport depends on the platform:

**Linux** (direct): The Incus daemon exposes a Unix domain socket at `/run/incus/unix.socket`.
The CLI speaks plain HTTP/1.1 over this socket — no TLS, no authentication (access is governed by Unix socket permissions and the `incus-admin` group).
WebSocket-based exec sessions (for `isx shell`, file push, etc.) use the same socket.

**macOS** (via VM): Incus runs inside a VM that isx's own launcher, `isx-vm`, runs on Virtualization.framework.
The CLI connects via a **vsock tunnel** — a direct host↔VM communication channel that bypasses the IP network entirely:

```
UnixSocketTransport (plain HTTP/1.1)
  → ~/.local/state/incus-spawn/vm.incus.sock  (Unix socket on host)
    → isx-vm's relay to the virtio-vsock device (port 8443)
      → socat VSOCK-LISTEN:8443 inside VM
        → /run/incus/unix.socket (Incus daemon)
```

`isx-vm` exposes the VM's vsock port 8443 as a Unix domain socket on the host.
Inside the VM, socat bridges the vsock listener to the Incus daemon's local Unix socket.
The result is that the macOS path reuses the same `UnixSocketTransport` as Linux — plain HTTP, no TLS, no certificates.

**Why vsock instead of HTTPS:** The original macOS transport used HTTPS over TCP to the VM's DHCP-assigned IP (192.168.64.0/24 subnet).
This required client certificate generation, server certificate capture, hostname verification bypass (the self-signed cert doesn't include the DHCP IP), and IP rediscovery on VM restart.
More critically, corporate VPN software (notably Cisco AnyConnect) installs a macOS socket filter that blocks non-Apple-signed binaries from TCP connections to the VM subnet — even when the VPN is disconnected.
Since `isx` is an ad-hoc-signed GraalVM native binary, AnyConnect blocks it from reaching the VM over TCP.
vsock bypasses this entirely because it operates outside the IP network stack (`AF_VSOCK`, not `AF_INET`), so socket filters that target TCP connections cannot intercept it.
The MITM proxy is unaffected because its traffic flows in the opposite direction — containers inside the VM connect outward to the host, which arrives as inbound traffic to the proxy process, not as an outbound `connect()` from `isx`.

**No HTTPS fallback:** `IncusApi.tryConnect()` selects a transport in order: Linux Unix sockets → vsock Unix socket.
The earlier HTTPS-over-TCP path (mutual TLS to the VM's DHCP IP) has been removed.
It reintroduced exactly the problems vsock exists to avoid (macOS Local Network permission prompts and VPN socket-filter blocking, described above).
Maintaining two transports made field issues hard to diagnose because it was unknowable which path a given user was actually on.
(`HttpsTransport` still exists in the tree but is no longer wired into connection selection.)

**An operation is done when it says so, not when its wait returns.**
State changes (start, copy, create, image import) answer 202 with an operation, and `IncusApi.waitForOperation` long-polls its `/wait?timeout=120`.
A poll that times out answers HTTP 200 with the operation still `Running` -- a full copy onto a non-CoW pool, an image Incus is still downloading, a large import over the macOS tunnel all outlast it -- so the wait asks again while the status is `Running`, `Pending` or `Cancelling`, up to a ceiling as long as the exec wait's (4 hours), and past that throws rather than return (#1089).
A `Cancelled` operation throws like a `Failure`.
Taking the timed-out poll (or a cancellation) for success, as it once did, sent callers on against a missing or half-written instance.
A fast operation still costs one wait, which the request budgets pin; `OperationWaitTest` pins the re-poll and the ceiling.

## macOS vsock robustness

The vsock tunnel as vfkit ran it (`AF_VSOCK` across `host unix socket → vfkit → in-VM socat → Incus`) did **not reliably propagate connection close/EOF** — particularly after macOS sleep/resume, when in-flight streams were left half-open.
Since #2031 isx runs the VM with its own launcher, `isx-vm`, whose relay closes both sides of a connection on the first EOF; the compensations below stay until each is retired with Mac evidence.
Where the host-to-guest half of it comes from is in "Where the vsock leak comes from" below, and the relay that replaced vfkit's is in "The launcher's relay".
That single fault surfaced in two directions and shaped several design choices:

- **Exec completion is derived from the operation, not the socket.**
  WebSocket exec (`isx shell`, package installs, git) originally read stdout/stderr until the server closed the fds.
  When close frames are dropped, `readPayload()` blocks forever.
  The fix (`IncusApi.execWebSocket`) unifies capture/stream/bidirectional exec and takes the operation `/wait` endpoint — the daemon's operation state, over a normal HTTP request — as the authoritative completion + exit-code signal, then drains and force-closes the data sockets.
  Completion no longer depends on close-frame delivery.
  Every exec fd is keepalive-pinged (each is a separate socat child that an inactivity reaper would otherwise collect on a quiet command).
  That includes stdin, which carries no output back, so an `isx ssh-proxy` session nobody types into for 180 s would otherwise lose its input (#1647).
  Its pongs are drained, and however the exec ends, its stdin socket closes with it (`StdinSocket`), even one still being opened.
  An exec or shell keepalive stops on a flag, never an interrupt (`IncusApi.Keepalive`): an interrupt that lands during a channel write closes the socket and discards output still unread in it.
  Stopping does not wait for a ping in flight either, since one can be stuck behind a stdin write the guest stopped reading.
  So the socket drops a ping that would follow its close frame (`UnixWsConnection.sendPing`).
  The post-exit drain is **adaptive**: it waits a short minimum for bytes in flight, extends while output is still arriving (so trailing output isn't truncated), and closes shortly after it goes idle (`OutputDrain`).
  Idle is counted only while the drain was watching.
  When the host process pauses (a VM's vCPU descheduled, a GC), the first poll after the pause used to read the pause as quiet output and close the sockets before the reader threads, paused with it, had taken what arrived meanwhile -- truncating the output (#1062, #1084, seen on macOS CI runners).
  So a gap between two polls counts as idle for at most half the window, and a late poll ends the drain only if the poll before it already found the output idle, which gives the readers the sleep between the two polls to catch up.
  (Restarting the whole window on each late poll, the first form of this, held every drain to its ceiling on the macOS runners, where polls come late again and again: #1122.)
  The ceiling stays wall time, so pauses never hold the caller past it.
  The drain also waits for bytes that reached the host but that no reader has taken yet.
  Closing a socket discards what is queued in it, so a reader held up by a slow sink or woken late lost the end of output that had already arrived (#1208).
  Any byte waiting unread in a data socket counts as output arriving now (`WsConnection.hasUnread`: the bytes read ahead plus the socket's `available()`); the ceiling still bounds a sink that never takes it.
  A caller that asks a guest it cannot trust to answer (`isx mcp` counting another session's tasks, or sweeping orphans) passes a limit (`execStreamWithin`, `IncusClient.execProbe`, which also skips the login shell).
  The wait's long-polls shrink to it, the process is then sent SIGKILL over the control fd, and after a short grace the caller is released either way, since a killed process whose child still holds its output keeps the operation open.
  The kill goes out on every way the bounded wait can end before the command was reported finished -- a `/wait` that overran its slack (likely over the vsock tunnel), an operation lost, an interrupt -- so giving up on a bounded exec never leaves its command running unowned.
  (An unbounded one keeps its old behaviour: it waits, up to its four-hour ceiling.)
  An interactive `isx shell` has no operation to wait on, and Incus never times one out, so a link that went silent with isx still running held the session, and the guest's shell, forever (#1996).
  Its control fd's keepalive therefore also watches for silence: Incus answers each ping with a pong, so once `EXEC_SILENCE_LIMIT_MS` (50 s) passes with nothing received, the link is gone.
  The check runs at each ping, so the cut comes up to one ping interval (15 s) later.
  The keepalive then closes control, which ends the session as a lost connection, and the shell's reconnect loop starts a fresh one.
  Incus kills the command once that close reaches it; across a link that stays half-open it may not, and the old shell lingers until it does.
  Only control is watched, because fd "0"'s reader also waits on the terminal, and a stalled terminal is no dead link.
  So byte-stream reads count what arrives, pongs included, in `millisSinceLastReceived()`, while still never writing.

- **The forwarder leaks, so it needs a backstop and a recovery path.**
  The same close-propagation gap means the in-VM `socat` forwarder never reaps connections whose close didn't cross the boundary.
  They piled up as vfkit-held host fds and degraded every new connection (observed: hundreds of leaked streams, `list` latency from sub-second to ~30s).
  Mitigations: a `socat -T` **inactivity timeout** reaps orphaned children (sized above the 120s `/wait` long-poll, with keepalives so live connections are never reaped); a **keep-alive connection cache** (`ConnectionPool`/`KeepAliveConnection`, via `requestPooled`) reuses a warm connection for short request-path calls (`get`/`post`/`/wait`) instead of reconnecting each time, cutting the churn that feeds the leak (exec WebSocket fds are per-operation and not poolable); and a per-process connection gauge + high-water mark in `UnixSocketTransport` makes accumulation visible.

- **A cut-off exchange is an error, never a smaller success.**
  A tunnel that drops connections mid-exchange turns any parser that reads EOF as "end of line" or "end of headers" into a source of silent data loss.
  The one-shot request path used to: a response cut off in its headers came back as a 200 with an empty body, a cut-off chunked body spun until the watchdog fired, and a WebSocket upgrade the peer hung up on "succeeded" -- handing exec a dead fd whose output then came back empty while the exit code (from `/wait`) looked fine.
  So there is one strict parser, `HttpResponseReader`, shared by the one-shot path, `KeepAliveConnection` and the WebSocket handshake.
  It reads a head a byte at a time, so every path hands it the socket through `UnixSocketTransport.socketInput`, the read-ahead WebSocket frames use (#1650).
  A bare channel stream cost a read call per head byte: `isx list -q` made 331 one-byte reads for its one request.
  A pooled connection keeps the read-ahead it opened with for its whole life, since what it read past one response belongs to the next.
  Every early EOF is an `EOFException` (EOF before the first byte is `NoResponseException`, which the keep-alive path maps to its retry-safe stale case), and malformed numbers are `IOException`s rather than runtime exceptions that escape callers' handlers.
  WebSocket frames follow the same rule (EOF inside a frame is an error; EOF between frames is end-of-stream).
  The WebSocket connect + handshake is bounded by the transport timeout like any request, since a wedged tunnel accepts the connection and then never answers.
  Only the handshake is bounded -- an open exec or shell socket legitimately stays quiet, and its liveness comes from the keepalive pings.

- **A bulk exec stream is framed large and read ahead (#1643).**
  `isx ssh-proxy` relays scp and every other ssh byte through an exec's fds, and git's pack exchange does the same.
  Each frame and each socket read costs the same however small it is.
  So stdin goes in frames of up to 64 KiB (`IncusApi.STDIN_CHUNK`; a read returns what is there, so a typed key still goes at once).
  Each client frame is masked a word at a time and written in one write (`UnixSocketTransport.wsSendMasked`).
  Received frames are parsed from a read-ahead (`SocketInput`), not a read per header byte.
  It starts at 4 KiB and grows to 64 KiB once a read fills it or a frame wants more, so a socket that reads only its handshake and control messages never zeroes 64 KiB.
  At 4 KiB frames in three writes each, an upload kept the relay near a full core and incusd at twice its work, for 240 to 275 MiB/s; it now relays 375 to 420 MiB/s at under half the CPU (native `-Os` builds, before PGO; docs/dev/PERFORMANCE-NOTES.md).
  The read-ahead is not a `BufferedInputStream`: its lock would hold the drain's `hasUnread()` behind a reader parked in `read()`, the very wait the drain must see past.

- **One silent connection can wedge the whole API.**
  Before lxc/incus@2cb2549, incusd wraps its local Unix listener in a `StarttlsListener` whose `Accept()` peeks 8 bytes to detect a STARTTLS upgrade, synchronously and with no deadline.
  So a connection that has sent nothing blocks every connection behind it until it speaks or closes (found by the vsock-bridged CI boot: a new request completed exactly when a silent one finally sent its first bytes).
  On Linux a client's close reaches incusd at once and unblocks the peek.
  Through vfkit it often never arrived, and then the forwarder kept the silent connection open until `socat -T` reaped it -- up to 180s with no API at all, which read exactly like the field "wedged tunnel" that `forwarder-restart` cleared instantly by killing the child.
  isx therefore never opens a tunnel connection without writing a request straight away.
  The one place that used to (the vsock branch of `diagnoseConnectionFailure`, a connect-and-close reachability check that ran precisely when the tunnel was struggling) now sends a real `GET /1.0` under the probe timeout.
  The server-side fix belongs upstream.
  LXD fixed the identical code (canonical/lxd#18705, peek moved off the accept loop with a read deadline).
  Incus followed after 7.5.1 (lxc/incus@2cb2549, for lxc/incus#4082): each connection is classified in its own goroutine, with a 3s deadline on the first read.
  No release had it when #1313 needed it, so the appliance build carried it as a backport onto Alpine's Incus, 7.0.0 on Alpine 3.24.
  The 7.0.2 LTS release has it, and the appliance's incusd is now built from that release (#2104, `appliance/incus/`, see `appliance/DESIGN.md`).
  On an appliance built since then, a silent or partial connection stalls nothing, and incusd closes it after 3s, well before the forwarder's backstop.
  An older appliance is still affected, so the rule above stays.
  incusd also closes idle keep-alive connections after 30s (`IdleTimeout`), a close vfkit did not always deliver either.
  So `ConnectionPool`'s TTL (5s) must stay well under it.

- **The forwarder's accept queue must absorb one exec.** socat's default listen backlog is 5, and a vsock listener with a full accept queue refuses the connection rather than letting it wait.
  One exec opens four fd WebSockets plus `/wait` at once, so any overlap with other traffic was refused; CI measured 3 of 16 and 12 of 32 concurrent connects refused.
  Both guest listeners (forwarder and agent) set `backlog=128`.

- **Diagnosis and layer-aware recovery.**
  The tunnel has two independent failure points: the host-side relay of the launcher (link 2) and the guest-side socat forwarder (link 3).
  `isx doctor` and the automatic recovery in `VmManager.ensureRunning()` both compare the connections the launcher's relay holds on the Incus socket (its control socket's `counts`, `VmControl`) against the in-VM socat child count (agent `socat-count` verb) to localize the wedge via `leakLayer()`: if the guest count is low while the host count is high, the launcher is holding closed connections (LAUNCHER); if both are high, the forwarder is lingering children (FORWARDER); if the guest count is zero, the forwarder is not running (always FORWARDER — restarting it is the correct fix regardless of host count).
  Until #2031 the host count was `lsof`'s count of vfkit's descriptors on the socket, a guess at what its relay held.
  `isx doctor` spends an agent round trip only where it changes the finding: it asks for the guest count only when the host count reports a leak or cannot be measured, and pings the agent before offering `forwarder-restart` only when that count did not come back, since a count that did already proves the agent lane (#1214).
  The forwarder finding is made before the "Incus not reachable" (or "slow") one: when it offers `forwarder-restart`, the Incus finding offers no VM restart and does not point at the VM, so a user answering the prompts in order does not reboot for a fault the no-reboot restart clears (#1277).
  The recovery decision tree in `recoverReachability()`: 10s grace probe → `detectLeakLayer()` → for a LAUNCHER wedge, fail fast with "run `isx vm restart`" (a forwarder restart cannot fix a host-side problem); for FORWARDER or unknown layer, restart the forwarder via the agent → 15s post-restart probe → 30s backstop.
  `probeTunnelHealth()` provides the same detection as a public API for proactive TUI/build-time checks, returning `HEALTHY`/`LAUNCHER_WEDGED`/`FORWARDER_ISSUE`/`UNKNOWN`.
  Recovery is provided by a small **allowlisted in-VM control agent** (`isx-agent`) on its own vsock port — verbs `ping`, `version`, `socat-count`, `sshd-status`, `forwarder-restart`, `btrfs-usage`, `btrfs-status`, `btrfs-rescan`, `btrfs-skip-drop-accounting` and `btrfs-settle` (#2061), no arbitrary exec — reached over an independent channel so it works even when the Incus tunnel is wedged.
  `forwarder-restart` drops and relaunches the forwarder **without rebooting the VM or stopping containers**, and now verifies the new process started (polls `pgrep` 4×0.5s) before confirming; stderr goes to `/dev/console` (the virtio-serial feeding `vm.log`) so startup errors are visible.
  See appliance/DESIGN.md for the in-VM side.

## Where the vsock leak comes from

The mitigations above treat the tunnel's lost closes as a given.
Most of the bridge that loses them is open source, and reading it points to a concrete cause for the host-to-guest direction.
That cause is confirmed: a relay with vfkit's logic in front of a real vsock on Linux fails the same two checks, with the same counts, as vfkit does on a Mac.
This section is the record of every tunnel fault isx worked around under vfkit, who owns the code at fault, and what follows (#785, #2030, #2031).
It does not list the bugs fixed under #774: those were isx's own parsers, which read an EOF from a lossy tunnel as a normal end.

### Who owns which part of the tunnel

```
isx  -- unix socket -->  vfkit  -->  Virtualization.framework  -- virtio-vsock -->  guest kernel  -->  socat forwarder  -->  Incus
```

| layer | project | where to engage |
|---|---|---|
| host client | isx (`UnixSocketTransport`, `KeepAliveConnection`, `IncusApi`) | here |
| host Unix socket to vsock bridge | crc-org/vfkit `pkg/vf/vsock.go` (v0.6.4, Apache-2.0) | GitHub |
| the proxy loop inside that bridge | inetaf/tcpproxy, which vfkit pins at `c4b9df0` (Apache-2.0) | GitHub |
| Go bindings to Apple's framework | Code-Hex/vz v3.7.1, which vfkit pins (MIT) | GitHub |
| the hypervisor's vsock device | Apple Virtualization.framework (`VZVirtioSocketDevice`), closed source | Feedback Assistant only |
| guest vsock and forwarder | the appliance kernel and `socat -T 180` (`isx-start-vsock-forwarder`) | here |

So "Apple's vsock is brittle" is not established.
Three of the four layers between isx and the guest are open source, and the one fault that has been pinned down is in them.

### The faults, and what isx does about each

| # | symptom | seen in | what isx did about it under vfkit |
|---|---|---|---|
| P1 | A connection the host closes does not close in the guest. The forwarder's `socat` children and vfkit's fds on `vm.incus.sock` pile up, and new connections slow down (a sub-second `isx list` took about 30 s). | #253, #676, #791, #2031 | the `socat -T 180` backstop; `ConnectionPool` to cut churn; `isx doctor`'s leak localization; the agent's `forwarder-restart` |
| P2 | vfkit stopped serving `vm.incus.sock` while the agent socket on the same vsock device worked. `connect()` succeeded, then no byte arrived, and the guest never saw the connection. Only a VM restart recovered. | #253, #676, #791, #1874 | leak-layer detection (`LeakLayer.LAUNCHER` today); fail fast with "run `isx vm restart`" |
| P3 | Exec WebSocket close frames do not arrive, and an interactive shell's teardown stalls about 8 s. | #254 | completion from the operation's `/wait`; the adaptive drain and force-close; keepalive pings on every fd |
| P4 | Streams go half-open across macOS sleep and resume. Reads block forever and shells die. | #248, #253 | keepalive pings; the event stream's silence limit (50 s); request watchdogs; reconnect in `InstanceEventWatcher` |
| P5 | The guest clock ran 32 h behind after a wake. #204 and #290 blamed vfkit's `--timesync`, but the guest agent never listened (it bound to the host's CID), so vfkit had nothing to reach (#2282). | #204, #290, #2282 | chrony in the guest; the `isx.time` seed at boot; the agent on any CID, with `/dev/rtc0` in the kernel |

### A close from isx never reaches the guest (P1)

vfkit relays each connection on `vm.incus.sock` to the guest with `inetaf/tcpproxy` (`pkg/vf/vsock.go`, `connectVsock`).
Two behaviours combine:

1. **tcpproxy waits for both directions.**
   Since its half-close change (inetaf/tcpproxy#38, 2023), `DialProxy.HandleConn` closes neither socket until both copy directions have ended.
   When one direction reaches EOF, it only half-closes the other socket, through `CloseWrite()`, and only when the connection implements it.
   vfkit has used this version since v0.5.1 (its go.mod bump `a371dff`, January 2024), so every vfkit isx has shipped with behaves this way.
2. **The vsock side cannot half-close.**
   Code-Hex/vz's `VirtioSocketConnection` keeps the real `*net.UnixConn` in a private field and exposes no `CloseWrite()`.
   tcpproxy's type assertion fails, so its half-close of the vsock side does nothing.

So when isx closes a tunnel connection, the unix-to-vsock copy ends and nothing is sent to the guest.
The vsock-to-unix copy keeps blocking on its read, and vfkit holds both fds.
The guest's socat does not learn of it until incusd closes its side (30s `IdleTimeout` on a keep-alive connection) or `socat -T` reaps it (180s).
Connections opened faster than those timeouts reap them pile up as vfkit-held host fds, which is the leak the section above describes.

isx never half-closes on purpose: `UnixSocketTransport` must not call `shutdownOutput()`, because Incus cancels the request on a half-close.
So the tunnel has no use for half-close, and closing both sides on the first EOF is correct for it.

**On a Mac the loss is one-directional.**
A close the host makes is lost, and a close the guest makes crosses (Apple M2 Pro, vfkit v0.6.4, 2026-09-27, #791).
A keep-alive `GET /1.0` that the host then closed left its forwarder child until about 30 s later, incusd's idle close, which is a guest-side close.
A connection the host opened and closed without sending a byte held the whole Incus API for about 179 s, until `socat -T 180` reaped it.
That was an appliance from before #1313, whose incusd waited on a silent connection.
Since 2026-10-23 the Mac runner fails two checks of `appliance/test-tunnel.sh` through vfkit on every run, with every other check passing:

```
forwarder children exit with their connections (count 52, baseline 4)
their forwarder children exited with them (count 58, baseline 4)
```

**Those two failures were allowed while a Mac ran vfkit** (#2031, decided 2026-11-13).
Without that, every Mac boot of a kernel change failed on a leak that isx fixed by leaving vfkit, so `kernel-mac-verified` could not pass.
`test-boot.sh` told `test-tunnel.sh` that the bridge was vfkit (`TUNNEL_KNOWN_LEAK=vfkit`), and each of the two checks printed a `KNOWN` line with its counts in place of `FAIL`.
The allowance was bounded: at most one child could be left for each request made since the baseline, which is what vfkit's leak gives.
The exception went with vfkit when the launcher shipped (stage 3), so both checks are strict on a Mac as in CI.

**The same counts come out on Linux, from the relay's logic alone** (2026-11-11, #2031).
The v0.5.0 appliance was booted under QEMU with `vhost-vsock-pci`, as `integration-tests` boots it, and the unmodified `test-tunnel.sh` was run through different bridges:

| bridge between the host sockets and the guest's vsock ports | the two forwarder checks |
|---|---|
| `socat UNIX-LISTEN:…,fork VSOCK-CONNECT:3:<port>`, CI's bridge until #2031 | pass, count 1, baseline 1 |
| tcpproxy's `DialProxy` at vfkit v0.6.4's commit, the vsock side wrapped as vz v3.7.1 wraps it | fail, 52/4 and 58/4 |
| the same `socat` with `ignoreeof` on the listening side | fail, 52/4 and 58/4 |
| the same tcpproxy, the vsock side given `CloseWrite()` and `CloseRead()` | pass, 1/1 |
| a relay that closes both sides on the first EOF, in Go and in Swift | pass, 1/1 |

The numbers are arithmetic.
The baseline is read after three API requests, so 4 is the listener and three leaked children.
52 adds the 48 requests of the two bursts (16 and 32), and 58 adds the six requests of the next section.
Through the vfkit-like relay a leaked child lived about 32 s, until incusd's idle close, as on the Mac.
So P1 needs no fault in Virtualization.framework, and either change to the relay clears it.

### vfkit can stop serving one socket (P2)

This one is read from source and seen on a Mac, and it is not reproduced on Linux.

tcpproxy's `serveListener` returns on the first `Accept()` error and never accepts again.
The error is reported only through `Proxy.Wait()`.
vfkit starts one proxy per vsock socket, keeps only its `Close()` and never calls `Wait()`, so the error is neither logged nor acted on (`cmd/vfkit/main.go`, `pkg/vf/vsock.go`).
The listening socket stays open, so a client's `connect()` still succeeds into the backlog and nothing serves it.
The agent socket has a proxy of its own and keeps working, which is the picture of #676.
Which `Accept()` error would start it is unknown: Go already retries `ECONNABORTED` and raises `RLIMIT_NOFILE`, and vfkit logs nothing.

On the Mac, about 70 concurrent `GET /1.0` wedged vfkit's side of the tunnel (2026-09-27, #791).
`forwarder-restart` reset the guest's count and the API stayed unreachable, with vfkit holding 6 fds on `vm.incus.sock` against 2 forwarder processes in the guest.
Only `isx vm restart` cleared it.
A later run wedged at bursts of 32, 3 times in 24 runs of `test-tunnel.sh`, and in 4 more runs a burst lost one request without a wedge (2026-10-31, #1874).
About one request in a burst of 32 came back as `Empty reply from server` within 0.1 ms (#1313).

None of this happens on Linux through the vfkit-like relay.
Bursts of 32, 70, 100 and 120 concurrent requests, repeated, never lost one, and with 3,992 leaked children in the guest a `GET /1.0` still took 1.7 ms.
So the wedge, the lost requests and the slow `isx list` are not the relay's close logic.
They are in vfkit's or vz's process, or in the framework, and only a Mac shows them.

### A second suspect: who owns the vsock fd

vz duplicates the framework's fd (`net.FileConn`) and then closes the original itself (`socket.go`, `newVirtioSocketConnection`).
Apple's API closes a connection with `VZVirtioSocketConnection.close()`, after which `fileDescriptor` reads `-1`.
The documentation does not say whether the framework also closes the fd when the connection object is deallocated.
If it does, it may close a reused fd number that now belongs to another connection, which would show up as unexplained EOFs or hangs.
This is unconfirmed.

### What may still be Virtualization.framework's

Whether the framework carries a close the host makes on its descriptor to the guest could not be read from source, and no relay could repair it if it did not.
vfkit never made that close, so its Mac data could not answer it.
The same held for sleep and resume: the half-open streams of P4 might have been a separate fault of the framework, or the leak above made visible by a backlog built up while the Mac slept.
Both were measured on a Mac with `isx-vm`, whose relay does close (#2031, stages 2 and 3).
A host-side close reached the guest, and every tunnel check passed.
Across a sleep, the launcher ended all 20 connections held before it, and the API answered 40 ms after the wake, where vfkit had kept all 20 open (docs/dev/PERFORMANCE-NOTES.md "The launcher's machine after a sleep, with the agent fixed, on a Mac").

### What follows

- **isx replaces vfkit with a launcher of its own** (#2031, done).
  A small Swift program on Virtualization.framework, `isx-vm`, boots the VM and relays its two vsock ports with the relay of the next section.
  It removes vfkit, vz and tcpproxy from the path, so P1, P2 and P5 are no longer another project's code.
  It landed in three stages: the relay, tested on Linux and used as CI's bridge; a prototype measured against vfkit on the Mac runner, which was the go or no-go; then the shipped launcher.
  The cost is a third macOS binary to build, sign with the `com.apple.security.virtualization` entitlement and ship, and the session handling `VmManager` does for it.
- **No report is filed with vfkit** (decided 2026-11-13).
  isx is moving away from vfkit, so a fix there would not reach an isx user.
  The fix is known all the same: adding `CloseWrite()` and `CloseRead()` to vz's `VirtioSocketConnection` fixes P1 for every vfkit user, if the framework turns `shutdown(SHUT_WR)` into a vsock shutdown.
  Closing both sides on the first EOF does not depend on that.
  The Linux reproduction above is the record of the fault; no report existed in vfkit, vz or tcpproxy when searched (2026-09-26).
  A fix would also have to pass a vz release, a vfkit release and Homebrew, and isx pins no minimum vfkit version.
- **The test exception went with vfkit.**
  `TUNNEL_KNOWN_LEAK` and the `KNOWN` outcome of `appliance/test-tunnel.sh` let vfkit's leak pass on a Mac until the launcher became the Mac's relay; stage 3 removed them, so both forwarder checks are strict on a Mac as in CI.
- **No compensation is retired because a fix shipped.**
  `/wait`-based completion, the request and handshake watchdogs and strict parsing are correct on any transport and stay.
  `socat -T 180`, the leak-layer diagnosis, the pool's TTL and `forwarder-restart` exist only because of P1 and P2.
  Each is retired by itself, with Mac evidence, once the launcher is the only relay a supported isx runs.

vfkit itself is maintained but slow: in the year to November 2026 it had 19 commits by people, all from its two maintainers, and outside pull requests waited months.
No fork is more active.
The alternatives fit less well: krunkit is a different engine that hardcodes a writable virtio-fs share as well, and Lima and Tart are whole VM managers.

## The launcher's relay

A connection isx closes is closed in the guest at once, so nothing piles up for a timeout to reap.
That is what the relay in `launcher/` is for (#2031).
A Mac runs it in `isx-vm` ("The launcher's machine"), and CI runs it as the bridge to the appliance's vsock ports.

`launcher/` is a Swift package, outside the Maven build.
Its module `IsxRelay` holds the relay and imports no framework, only the C library and `Synchronization` for one mutex.
A connection to the guest is whatever file descriptor a `dial` function returns, and `hangUp` gives it back to whoever owns it.
On a Mac those two will be Virtualization.framework's `connect(toPort:)` and `VZVirtioSocketConnection.close()`.
On Linux they are an `AF_VSOCK` socket to a QEMU guest and `close()`, in `isx-vsock-bridge`, which CI runs.
So the code that decides when a connection ends is the same in both places.

- **The first side to end ends the connection.**
  When a read from either side returns EOF or an error, the relay stops copying, calls `shutdown(SHUT_RDWR)` on the guest's descriptor and closes both descriptors, each once.
  The shutdown tells the guest at once, whatever `hangUp` then does with the descriptor.
  What was read from a side is written whole before that side is read again, so a reply the guest sent just before closing arrives complete.
- **A write that fails does not end it.**
  The side that refused the write has stopped reading, and it may have answered first: Incus does that when it turns an upload down while isx is still sending.
  The relay's write to the guest then fails before the other direction has carried the answer to the host.
  So that direction reads on and drops what it reads, and the connection ends when either side's read ends.
  A relay that closed both sides at the failed write lost the answer in about one of five such connections (`testAnAnswerArrivesFromAGuestThatClosesWhileTheHostStillSends`).
  What this gives up: the sender is not told at once that its bytes go nowhere, only when the side that refused them ends.
- **It gives up half-close.**
  A client that shut down its write side and waited for a reply would lose the reply.
  No client of the tunnel does that: `UnixSocketTransport` never half-closes, since Incus cancels a request on one, and `VmAgentClient` keeps its write side open until the agent hangs up.
  A new tunnel client must do the same.
  The first run of `isx-vm` on a Mac met one that did not: the appliance's tunnel checks made their agent calls through `nc`, and Apple's `nc` shuts its write side down at the end of its input.
  Every agent call under `isx-vm` came back empty, while under vfkit the same calls had always passed, since vfkit's relay did not carry that half-close to the guest.
  The checks now call the agent with `appliance/agent-call.pl`, which keeps its write side open on every host, and `appliance/test-agent-call.sh` holds it to that in CI on Linux and macOS.
  The relay was left as it is: one that passed the half-close on would still lose the answer of `forwarder-restart`, since the guest's `socat` waits only half a second after an EOF, and one that held the EOF back would be vfkit's relay again.
  In return the relay does not depend on the framework carrying `SHUT_WR`, which nobody has measured.
- **The accept loop does not end on an error.**
  It retries `EINTR` and `ECONNABORTED` at once.
  When the process is out of descriptors or memory it reports the error once, waits 100 ms and accepts again, so the connection waiting in the backlog is served when there is room.
  It returns only when the listener is no longer a listening socket, and the caller treats that as fatal.
  A loop that ends on its first error leaves a socket that accepts connections and serves none, which is the suspected cause of P2.
- **Each connection has one thread of its own, and it waits only in `poll()`.**
  The thread carries both directions, and its reads and writes never wait (`MSG_DONTWAIT`), so no call of one thread has to end a call of another.
  The first version did depend on that: a thread per direction, each in a blocking `read()`, and a `shutdown()` to end the other's read.
  On macOS it kept a connection the guest had closed, the host still sending into it, in 3 of 600 rounds, until the host closed too.
  That is the fault this relay exists to remove, and Linux never showed it.
  The cause is in XNU, read in the published source (xnu-11417): `soreceive()` on a Unix socket can release the socket's lock in `uipc_rcvd()` just before it sleeps, and then sleeps without looking at the socket's state again.
  A `shutdown()` from another thread in that window wakes nobody, and what the peer sends afterwards is dropped without a wakeup.
  What this gives up: an extra `poll()` call for each read.
- **It asks `poll()` only about a descriptor a direction waits for.**
  A direction waits for its source to be readable or, while bytes it read are still unwritten, for its destination to be writable.
  A side that closed is reported on every `poll()` call, asked for or not.
  Its end must wait while bytes read from it are unwritten, so a relay that kept it in the call would spin until the other side took them (`testWhatAHostSentBeforeClosingWaitsForAGuestThatReadsLate`).
- **The thread is never a Dispatch queue's.**
  It waits for as long as its connection lives, and a Dispatch queue's pool is bounded, so a few dozen held connections would stop every later one.
  When no thread can be made, the connection is closed and counted as refused.
- **It counts what it holds.**
  `waiting`, `held`, `total` and `refused` per socket are what the launcher's control socket will report in place of the `lsof` guess in `leakLayer()` (stage 3).
  `waiting` is the host connections whose `dial` has not returned: a guest that stopped answering shows there, where `held` stays at zero.
  The bridge prints them on `SIGUSR1`.
- **The socket is the user's alone.**
  `Relay.listen` sets mode `0600` before it listens, replaces a socket a killed process left behind, and refuses to remove anything that is not a socket.
  It does not check whether a live process still listens there, so running one launcher per VM is the caller's job (stage 3).
  A path too long for a socket address is an error, never truncated to another path.

The package's tests drive the relay over socket pairs, with no VM (`swift test` in `launcher-linux`, the bundle under `xcrun xctest` in `launcher-macos`).
A test of the relay that has not finished after 60 s ends the run and is named, since a test blocked in a read, a write or a close cannot be failed from another thread (#2265).
The regression test is `testAHostCloseReachesTheGuest`: the guest's end must see the end within a second of the host closing.
With the relay changed to vfkit's logic that test fails, and so do the guest-close test and the burst test, which finds 128 connections still held.
`testAnAnswerArrivesFromAGuestThatClosesWhileTheHostStillSends` runs 400 rounds, which is what showed the macOS fault above in `launcher-macos`.
Over a real vsock, `integration-tests` runs the unmodified `appliance/test-tunnel.sh` through the bridge, then requires that the bridge holds no connection and none is waiting.
The `macos-role` job bridges with it too, so isx's own requests cross the relay: `isx init`, two template builds, branches and an MCP session.
Linux differs from macOS in one place the tests allow for: Linux reserves a descriptor for `accept()` before it blocks, so at the limit an accept already waiting still takes one connection.
They also differ in how a listener closes under a blocked `accept()`.
On Linux `shutdown()` ends that `accept()`; on macOS it does not, and `close()` waits for the `accept()` to return.
A stack sample on a macOS runner caught that wait, which at times lasted over a minute: it was the hang of #2265.
So the tests close a listener on a thread of their own and connect to it every 50 ms until the close returns, since a connection makes `accept()` return.
The test's guest refuses every dial from then on, so a connection the relay accepts there leaves no guest end open and no dial waiting.
The relay itself never closes its listener, but a launcher that stops serving a socket must not close it under `serve()` either.

What CI cannot show is the framework's half: that a close on its descriptor reaches the guest, and how the relay behaves under bursts, held connections and sleep on a Mac.
Those were measured against vfkit on the Mac runner before the launcher shipped, with its prototype ("The measurement and the rule").

## The launcher's machine

`isx-vm` starts isx's VM on Virtualization.framework with the relay above in front of its vsock ports (#2031).
Every macOS release ships it as `isx-vm-macos-aarch64`, and the Homebrew formula installs it beside `isx`.
It replaced vfkit after its prototype was measured against vfkit on one Mac (docs/dev/PERFORMANCE-NOTES.md, the launcher's sections).

isx is its only caller, so it takes arguments of its own, each with one value (`VmManager.launcherCommand`, read by `VmSpec`): `--disk` for each disk in the guest's order, `--nat <mac>`, `--console`, `--share <tag>=<dir>`, `--vsock <port>=<path>`, `--clock-port` and `--control`.
An option it does not know is refused by name with exit code 64, so a device isx asked for is never missing from the machine without a word.
The arguments and what a wake needs are in the module `IsxVmCore`, which imports no framework and is tested on Linux as well (`VmSpecTests`, `WakeTests`).
The machine itself is `launcher/Sources/isx-vm`, which only a Mac compiles.

**isx finds it beside itself, and starts it from the app bundle.**
`VmManager.findLauncher` takes the `isx-vm` beside the `isx` on the `PATH` (Homebrew and `install.sh` install both in one directory), then `~/.local/bin`, then the `PATH`.
The VM's app bundle (`incus-spawn-vm.app` in the VM's state directory) links `Contents/MacOS/isx-vm` to it, so macOS takes the permission dialogs' text from the bundle's `Info.plist`.
A bundle an older isx made for vfkit is rewritten: its link goes and its plist names `isx-vm`.
Its own output goes to `isx-vm.log`, never `vm.log`, which it writes the console to.
A JBang install has no `isx-vm`: on a Mac, a JBang user downloads `isx-vm-macos-aarch64` from the release, as they installed vfkit before, or uses Homebrew.

**The control socket replaces vfkit's REST endpoint and the `lsof` guess.**
`vm.control.sock` in the VM's state directory, for this user alone, takes one verb a connection, as the guest's agent does (`Control`, `VmControl`).
`state` answers `running` or `stopping` with the launcher's pid, `counts` gives each relay's `waiting`, `held`, `total` and `refused`, and `stop` stops the machine as `SIGTERM` does.
isx reads the Incus relay's `held` as the forwarder connection count (`isx vm status`, `isx doctor`, `leakLayer()`), where it once counted vfkit's descriptors with `lsof`.
A stop asks the guest's agent first; a guest that did not go down by itself is stopped on the control socket, with `SIGTERM` as the fallback, and either cuts the disks off as a power cut would.
It can do no more than a signal from the same user could, and it interprets no other bytes.

**A VM an older isx started under vfkit keeps running after the upgrade, and isx still recognises it.**
Both run on the same disks, so isx must never start `isx-vm` beside it: `isRunning()` takes a `vfkit` pid as the running VM, `isx vm status` says `Launcher: vfkit`, and `isx doctor` offers the restart that moves it to `isx-vm`.
isx stops it with the agent's shutdown and then signals, since it has no control socket.
This recognition is the one vfkit name left in the code (`VmManager.LEGACY_HYPERVISOR`), with the files only vfkit's isx wrote (`vm.rest-uri`, `vm.rest.sock`, `empty-initrd`, `vfkit.log`), which isx removes with the VM's stale files.
It goes once no supported upgrade starts from a release that ran vfkit.
`isRunning()` reads `vm.pid` alone, so a pid file lost while vfkit runs (an older isx still open, a crash, a cleaned state directory) would let a start put `isx-vm` on the disks vfkit holds.
So the last step before a launch looks for a hypervisor of this user, `isx-vm` or vfkit, whose arguments name this state directory's `disk.img`, adopts it as the VM, and launches nothing (`adoptHypervisorOnDisks`).
`isRunning()` does the same when `vm.pid` names no VM, before it removes the VM's sockets as stale, so a restart can still ask that guest to shut down.
It lists processes, so it runs only before a launch and when `vm.pid` names no VM, never on the branch, start or shell path of a running VM.
The other way round holds too: an older isx still running after the upgrade, an open TUI or an `isx mcp` session, takes an `isx-vm` pid for a stale one and deletes `vm.pid` and the vsock sockets.
The control socket survives, since that isx never knew it, and its `state` names the launcher's pid, so `isRunning()` records it again (`adoptRunningLauncher`) rather than let the next start put a second VM on the same disks.
Incus stays unreachable until `isx vm restart` brings the sockets back; the upgrade notes say to quit such processes first.
So every launch of the VM, the automatic one included, first restarts a drifted proxy service, one of another version or an older config (`ProxyHealthCheck.restartDriftedBeforeVmStart`, #2552): after `brew upgrade` the service still runs the old proxy, which reaches the same cleanup on its registry reads.
It costs one health request on the loopback, against a launch of seconds.
A restart asks it once, only after nothing else can stop the restart, since it cuts the instances' connections, and its start does not ask again.
The TUI and `isx mcp` cannot be restarted for the user; the upgrade notes, the Homebrew caveats included, name them.

**The first restart after the upgrade does everything that can fail before it stops the VM** (#2552).
It changes three things at once: the launcher, the kernel and the root disk of the new release.
So it looks for `isx-vm` first, and a missing one (a JBang install) fails the restart with the VM still running and the install advice for how this isx was installed (`VmManager.launcherMissing`): the formula's `brew reinstall`, `install.sh`, or the release's asset checked against its `SHA256SUMS`.
It then downloads the new kernel and disk image while the old VM runs, which boots from memory, so replacing its files is safe.
A download that fails stops nothing, and a start whose download fails warns and boots the old root disk with the kernel it came with, so an offline dev build still starts, and an old image is never extracted under the new version's stamp.
Both files download beside the current ones and move into place only once both arrived, and the appliance's `version` file is written last, so a partial download is retried in full.
macOS may ask again for the home folder and the local network, since the program asking is new, and the notes that say why are shown on that restart too, though vfkit's run left `vm.log` behind.
`isx-vm` is signed ad hoc, so its designated requirement is the binary's hash; whether each release asks again is for the Mac to show.
While it is, and while a VM still runs under vfkit, an unreachable Incus is answered with `isx vm restart` (`VmManager.unreachableAdvice`), never the Linux remedies of the generic diagnosis.
For a VM under vfkit the recovery first tries the guest forwarder's restart, which takes a second and stops no instance, and gives that advice when it did not help; a removed socket goes straight to the advice, since no forwarder restart brings a host socket back.
A control socket that exists and does not answer is not taken for a dead launcher: the launcher started with that socket is looked for among the user's processes before anything is cleaned up as stale.
A launcher that answers `stopping` still holds the disks, so it is adopted too, and a stop waits for it rather than signalling it at once; only one whose pid is `vm.pid`'s is asked to stop, never another machine's.
The Incus socket counts as removed only while the control socket answers: the launcher makes that one last, so a booting VM, which has neither yet, is not told to restart.

vfkit's defaults are not the specification.
Each setting is chosen for isx, and where it is vfkit's own that is a decision too:

| Setting | `isx-vm` | vfkit, as isx runs it | Why, and what it gives up |
|---|---|---|---|
| Disks | host page cache, a guest flush is an `fsync()` | the same | Kept as vfkit had them, so the comparison measures the launcher and not a disk policy. A policy per disk (the swap disk needs no durability) is left to the profiling that follows the comparison. |
| Initial ramdisk | none | required, so isx wrote an empty one | The appliance kernel is built without support for one (`CONFIG_BLK_DEV_INITRD=n`) and the framework does not ask for one. Nothing is given up. |
| Network | the host's NAT, with isx's fixed MAC address | the same | isx finds the VM's address in the host's DHCP leases by that MAC address (`VmNetwork`). |
| Console | written to a file that is not appended to | the same | isx reads a boot's console from the start of the file. |
| Home share | read-only at the hypervisor | writable; only the guest's `-o ro` mount keeps it read-only | Root in the guest can remount a share, and cannot change what the hypervisor refuses. It gives up nothing isx does: the guest never writes there. |
| Entropy device | present | absent (isx passes no `virtio-rng`) | The guest's random numbers come from the host from the first moment of a boot; the kernel has the driver (`CONFIG_HW_RANDOM_VIRTIO`). One more device in the machine; the measurement records when the guest's pool is ready under each. |
| Memory balloon | absent | absent (isx passes no `virtio-balloon`) | Nothing in isx would ask the guest for memory back, and a device nobody drives is only surface. |
| Open descriptors | the limit is raised at start as far as the process may go (`Relay.raiseDescriptorLimit`) | the same, by Go's runtime | A connection holds two descriptors in the relay, and macOS starts a program with room for 256, so about 120 connections. Above that the accept loop only waits and retries, and later connections sit in the backlog. Nothing is given up. |
| Control endpoint | a control socket: state, the relays' counts, stop | a REST socket | isx asks the guest's own agent to shut down first, and the appliance has nothing behind the power button vfkit's endpoint presses. What isx needs of the launcher is its counts and a stop. |
| Stop | `stop` on the control socket, `SIGTERM` or `SIGINT` stops the machine at once | presses the power button, waits five seconds, then stops it | isx asks for it only after the guest was asked and did not end. |
| Guest clock | set after each wake, on a new connection to the guest agent | set after each wake, on one connection kept for the process's life | See below. |
| Guest connections | each `VZVirtioSocketConnection` is kept while its descriptor is in use, and closed through the framework once | the descriptor is copied, the framework's own closed and the object let go | The framework closes a connection's descriptor when its object is closed or released, so the relay hands back exactly what it was given (`GuestConnections`). |
| Guest connections at once | at most 192, the three relays together; the next waits for one to end (`GuestRoom`) | no limit | The framework's own process holds a descriptor for each and has room for 256; the one that does not fit stops the machine. See below. |

The signature is ad hoc and carries one entitlement, `com.apple.security.virtualization`, which the framework requires before it starts a machine (`launcher/build-isx-vm.sh`, `launcher/isx-vm.entitlements`).
It needs no certificate, key or Apple account, and CI makes it.
vfkit's entitlements file also lists the two network entitlements of the App Sandbox, which mean nothing to a program outside that sandbox.
The build script reads the entitlements back from the signature and fails unless they are exactly that one.

**On a wake the launcher ends every connection it accepted before the sleep and sets the guest's clock.**
A connection may have died on either side while the host slept, and neither side is told, so each one is ended on both sides (`Relay.dropAll(stampedBefore:)`) and isx makes new ones.
What this gives up: a connection that would have survived a short sleep, an open `isx shell` for one, is cut on every wake.
A connection isx makes after the wake, before the launcher has noticed it, is kept.
The relay stamps each connection when it accepts it with how far the clock that counts the time asleep is ahead of the other.
That lead grows only in a sleep, so a stamp more than half a second below the lead read at the wake was taken before the sleep, or before the last one of several.
The stamp is taken before the guest is dialled, so a connection whose dial the sleep interrupted counts as made before it, and is ended when that dial returns.
The guest does not run while its host sleeps, so its clock is behind by the time asleep.
The launcher sends the host's time to the guest agent (`guest-set-time`), on a connection of its own for each wake, tried up to five times a second apart since the guest is waking too.
vfkit kept one connection for all wakes and stopped setting the clock for good when that one could not be made.
The clock is set for one wake at a time, on a serial queue, since the guest agent serves one client at a time.
The appliance's agent listens since #2282, and on a Mac it answered `guest-set-time` under vfkit as well.
With an appliance from before that fix, after a sleep of 62 s the guest was 59 s behind 10 s after the wake, with vfkit as with `isx-vm` (docs/dev/PERFORMANCE-NOTES.md "The launcher's machine with 192 connections held, and after a sleep, on a Mac").
With the fixed appliance, the guest's clock was set after a 62 s sleep under `isx-vm`; vfkit's VM came back 61 s behind in the same round, but its tunnel had wedged earlier in it, and outside a bench round vfkit set the clock within a second (docs/dev/PERFORMANCE-NOTES.md "The launcher's machine after a sleep, with the agent fixed, on a Mac").
A second wake while the first is still being served sets the clock again afterwards, since the guest stood still once more.
The launcher learns of a wake from two clocks, one that counts the time asleep and one that does not (`SleepWatch`), read every two seconds.
That needs no system notification, so no AppKit and no IOKit in a program with no other use for them.
What it gives up: the wake is noticed up to two and a half seconds late, and the process wakes every two seconds to ask.

**The relays have at most 192 connections to the guest open at once, all three together.**
A host connection beyond that is accepted and waits, and is dialled when another one ends.
The limit is not the launcher's own.
Virtualization.framework runs the machine in a process of its own, `com.apple.Virtualization.VirtualMachine`, which holds one descriptor for every vsock connection.
That process has room for 256 descriptors, and the connection that does not fit stops the machine.
On the Mac `isx-vm` accepted 248 of 400 connections asked for, and the framework then stopped the machine with "Internal Virtualization error" (docs/dev/PERFORMANCE-NOTES.md "The launcher's machine stops at 248 held connections, on a Mac").
`isx-vm`'s own limit was raised and had room, and it cannot raise another process's.
vfkit runs the machine on the same framework and limits nothing, so the same load would stop its machine too; that was not measured, since vfkit's tunnel fails earlier.
192 is three quarters of the 256.
The rest is for what that process has open anyway (8, if the 248 filled it), for the files of the home's share, which it opens as well, for the clock's own connection after a wake, and for connections whose close is still on its way to the main queue.
How many descriptors a busy share takes is not known: the measurement records that process's count, with connections held and after the share was read under isx.
With the limit the machine stayed up on the Mac with 192 held, and between 47 and 56 of the 256 were left, since the count read that day is an upper bound (the same section of the notes).
The room is one object for the process (`GuestRoom`), since the three sockets' connections are counted together in that other process.
A connection that waits is counted as `waiting`, as one inside a dial is, and the relay says once that the room is full.
One whose client left while it waited is closed without a dial and counted as `refused`: its request would otherwise reach the guest after its sender gave up on the answer.
The relay notices within a second, while the room is still full, so callers that give up and try again do not each leave a thread and a descriptor behind.
What the limit gives up: with 192 connections held, the next one waits for as long as they stay, whoever asks, the next `isx` command and `isx doctor` included.
Refusing it at once was the alternative, and lost: a burst of short requests beyond the room would then fail where it now only takes longer, and isx holds nothing near 192 connections.

**One isx process also caps its own connections, at 48 on macOS** (`UnixSocketTransport`).
The cap predates the launcher: it kept one process from piling streams onto vfkit's forwarder, and it fails open after 10 s rather than wedge a burst.
It was set well above one operation's need, which stopped being true once the overview ran an exec in every running instance (#2527).
Each exec holds five connections, so with 30 instances running the execs took every permit, and a finished command's `/wait` waited past the 5 s Incus keeps a finished operation.
So a bounded exec is now admitted before it is posted, and only as many run at once as fit in the cap with all five connections each (`IncusApi.BOUNDED_EXECS`).
What this gives up: the reads run in a rolling window of ten on macOS (eight before #2626), so N reads take about N/10 times one read, not the time of the slowest one.
For 30 instances whose reads all time out, that is three waves of 13 s (the 10 s limit and the 3 s kill grace), about 40 s.
Only probes wait in the window: the overview's reads, and `isx mcp`'s probes of instances other sessions hold (`probeElsewhere`) and of the task a person joins (`TaskJoin`).
So with more instances to ask than there are slots (ten on macOS, 62 on Linux), a task start waits for the waves before its own.
`isx mcp`'s task status poll runs an unbounded exec, which is not admitted and does not wait.
On Linux there is no forwarder for the cap to protect: the socket is incusd's own, so its cap is 256, a brake on a runaway that leaves most of the 1024 descriptors a process's soft limit commonly allows.
That was 50 execs at once, so the overview read 50 instances in one wave: with 40 instances whose reads took 1.5 s each, 2.5 s instead of 8.7 s (docs/dev/PERFORMANCE-NOTES.md "The overview's reads against the connection cap, on Linux").
On a Mac the launcher's 192 is the real limit, so the cap's own value there is still to be derived from the launcher's room and measured on a Mac.

**A batch of probes shares one operation subscription, and an exec opens its sockets at once** (#2626).
An exec in a probe batch (`IncusClient.probeBatch`, opened by the overview's `GuestRead.readEach`) learns when its command ended from one `/1.0/events?type=operation` subscription the batch holds (`OperationWatcher`), not from a `/wait` of its own.
It registers its operation's id right after the post, before its sockets open, and Incus starts the command only once they are connected, so the end cannot come before the registration.
An operation that ends before anyone registered it is kept among the last 256 ends.
The exec then opens control, stdout and stderr at once, and connects and closes stdin beside them (`IncusApi.openExecSockets`), so over the tunnel the four vsock connects overlap instead of following one another.
If one handshake fails, the others are closed once theirs end, so no connection outlives the failure.
An exec therefore holds at most four connections: control, stdout, stderr, and either stdin's handshake or a `/wait`.
So `CONCURRENT_EXECS` is the cap less the pool's idle connections and the batch's subscription, divided by four: 10 on macOS and 62 on Linux, three waves instead of four for 30 instances on a Mac.
A probe discards its stderr, so its drain waits for stdout alone and closes stderr once stdout is done, rather than waiting up to `OutputDrain.IDLE_MS` for a close frame that never comes.

What the subscription gives up: when it ends while execs wait, each of them asks `/wait` from then on, and one whose command ended more than 5 s earlier gets a 404 and no exit code.
A subscription that goes silent without ending is not noticed before the probe's limit: the probe is then killed, and its end asked of `/wait`, so a command that did end in the last 5 s is still reported, as killed.
The batch is short, one read of the overview, and its subscription is pinged like the TUI's, so both are rare.
A `/wait` for each exec was the alternative, and costs one connection and one request per exec, as well as the 404 when a finished command's `/wait` waits for a connection.
Unbatched execs, the task status poll and builds, keep their `/wait`.
On Linux the batch measured no faster, since incusd's own socket makes a handshake cheap and 30 probes fit in one wave (docs/dev/PERFORMANCE-NOTES.md "Probe batches on one operation subscription, on Linux").
The gain is the Mac's: with 31 guests whose reads take about 1.5 s each, `isx overview` went from 6.7 s to 5.1 s (docs/dev/PERFORMANCE-NOTES.md "Probe batches on one operation subscription, on a Mac").

The framework's objects are used on the main queue alone, the queue the machine is made on.
A relay's thread reaches the guest through `dial`, which asks the main queue and waits for it; nothing on the main queue waits for a relay.
A connection that ends is closed there too: `hangUp` hands the close to the main queue and returns before it, since the relay has already shut the descriptor down.
Apple's page for `VZVirtioSocketConnection.close()` names no queue, so the launcher keeps to its rule rather than lean on that silence.
What it gives up: a guest descriptor stays open for a moment after the relay has counted its connection as ended, until the main queue reaches the close.

### The measurement and the rule

Stage 2 measured the prototype against vfkit on the Mac runner, through the stage's own script, which ran `isx-vm` in vfkit's place on vfkit's command line.
The rule was the design's: go when a host-side close reaches the guest, and failures and latency are no worse than vfkit's.
A host-side close reached the guest, `isx-vm` passed every tunnel check where vfkit failed them, and its one slower row, a branch to its prompt at 455 against 387 ms, was accepted (docs/dev/PERFORMANCE-NOTES.md, the launcher's sections; the records are on PR #2197).
That script went with vfkit's command line.

### Comparing the shipped launcher with vfkit

`bench/vm-launcher-compare.sh --old=<isx> --new=<isx>` compares the Mac's own VM under two isx builds: one that runs vfkit, such as the previous release, and one that runs `isx-vm`.
It needs no vfkit code in this repository, since the old build brings its own.
Each round restarts the VM under each build in turn, in alternating order, and measures the start and the stop, idle memory and CPU of the launcher and of the framework's machine process, the machine's descriptors, a branch to its prompt, exec, disk, share and network throughput in an instance, and the tunnel under load (`appliance/measure-tunnel.sh` on the VM's sockets).
With `--sleep` it also puts the Mac to sleep for a minute under each, which needs `sudo -n pmset`.
It prints each metric's median, range and count, and flags the new build where its median is worse by more than a tenth.
Every exec in its probe instance is bounded (`PROBE_TIMEOUT`, 150 s, above the download's own limit), since one stalled vfkit exec once held a side's later samples for 16 minutes; a cut exec is counted as `probe_timeouts` and leaves its sample out.
The bound sends `SIGTERM`, then `SIGKILL`: a bare alarm does not end `incus`, whose Go runtime takes `SIGALRM`, and a stalled exec ran 1666 s under one on the Mac.
The download is the first 100 MiB of a kernel tarball from kernel.org's CDN, since Cloudflare's speed test answered 403 from the VM under both launchers.
A download that is not one (a refusal or a challenge page) counts as `net_failed` and never as 0 MB/s.
`measure-tunnel.sh` takes no latency at a count held at the relay's room (`ROOM`, 192), where every new connection waits for a held one to end.
It leaves the VM under the new build.

## The macOS VM outlives whatever started it

The launcher is started as the leader of a new session, not as a plain child of `isx`.
A plain child stays in the starting command's process group and on its terminal, and both take it down.
Closing the terminal hangs up every process on it.
Ctrl+C reaches the whole foreground group, so interrupting the command that happened to start the VM stopped the VM.
And launchd kills whatever is left in a job's process group once the job exits, so the login agent, whose `isx vm start` exits as soon as the VM is up, killed its own VM a few seconds later (#971).

Java has no `setsid`, and macOS ships no `setsid` command, so the VM is started through `/usr/bin/perl -MPOSIX=setsid -e 'setsid(); exec …'`: perl replaces itself with the launcher, so the pid `isx` records is the VM's.
Linux has util-linux's `setsid`, which execs the command in its own place too when, as a child of the JVM, it is not a process group leader; `OwnSession` prefers it and falls back to perl.
Ignoring SIGHUP (`nohup`) was rejected because it covers one of the three cases; `AbandonProcessGroup` in the agent's plist covers another one only.
Launching the app bundle through LaunchServices (`open`) would detach it too, but returns no pid.
If perl is ever gone from macOS the command is run as before, and this has to be solved again.
The perl wrapper starts without the user's `PERL5OPT`, `PERL5LIB` and `PERLLIB`, which could stop it from loading, and puts them back before the exec: whatever it starts keeps them, a Git credential helper in perl among them.

A build's host Git is started the same way (#1425), but unlike the VM it must not outlive isx: see design/templates.md "A running build takes two Ctrl+C".

The launcher's own stdout/stderr (perl's diagnostics if the exec above fails; otherwise whatever the launcher itself prints) go to `Environment.launcherLogFile()`, never to `vm.log`: the launcher opens `vm.log` itself, non-append, for the VM's console.
A second writer appending to the same file corrupts both — the launcher's own lines (a wake, the guest's clock) land at EOF over console bytes `isx vm console` has already read past, and the console's next write lands over those in turn.
A separate file also keeps `vm.log`'s absence a reliable signal of a first launch, which gates the one-time TCC permissions note.
Before the split, a perl failure still created `vm.log` (the append-mode redirect creates the file as soon as the process starts), so a retry after a perl failure saw `vm.log` already there and silently dropped the note.

## VM Appliance

A minimal Alpine Linux VM image with Incus pre-installed, providing CI integration testing and macOS support.
Uses BusyBox init (not systemd or OpenRC) for fastest possible boot.
Custom kernel from kernel.org source (zero modules, no initrd) with musl libc for fast dynamic linking.
The build produces a rootfs tarball (~30-40 MB) and kernel (~10.5 MB on aarch64, ~5 MB on x86_64, both gzipped to ~5 MB for release download); a writable btrfs disk image is created on first boot.
See [`appliance/DESIGN.md`](../appliance/DESIGN.md) for full architecture details.

### No VM instances or KVM passthrough on macOS, yet

On macOS isx runs containers only, and says so before anything is created (#1254).
`isx build` refuses a VM template when the appliance's Incus lists no `virtual-machine` instance type (#1391).
`BranchFlow.vmUnsupportedOnMacOs` and `kvmUnsupportedOnMacOs` refuse a branch of a VM and an explicit `--kvm`, for `isx branch`, `isx mcp` and the TUI's branch dialog alike.
A template's own `type: kvm` is how it was built, not the branch's request, so on macOS its branches go without KVM and say so, as GUI does outside a Wayland session.
`isx mcp` has no `--no-kvm` to ask for that itself, so refusing would leave such a template unusable there.
`isx doctor` states the limit as a note.
Before this, each path failed in a lower layer: the build in QEMU's absence inside the appliance, and `--kvm` with a `/dev/kvm` message that described a Linux host.

It is not ruled out.
It has not been done because nobody has needed it yet.
It needs four layers, listed in [`appliance/DESIGN.md`](../appliance/DESIGN.md) "No nested virtualization": the appliance kernel, nested virtualization in `isx-vm`, QEMU and its firmware in the appliance, and isx's own `/dev/kvm` check.
It would also raise the hardware floor to an M3 or later on macOS 15, and grow the appliance by roughly 150 MB.
A VM in the appliance sits two hypervisor levels down, where #1240 measured a 49 s boot under one level of nesting (docs/dev/PERFORMANCE-NOTES.md "VM boot: where the wait goes").
So the VM image boot work on Sanne/incus-spawn-images#12 is a prerequisite for it being usable at all.

## The VM image's boot chain (#1255)

The VM base image isx uses (`-vm-direct.tar.xz`, from the first incus-spawn-images release that has it and the `minimal.yaml` bump that follows) boots in three steps: OVMF (AAVMF on aarch64), the kernel, systemd.
- **No boot loader.**
  The disk is a GPT with an ESP holding one file, the kernel, as `EFI/BOOT/BOOTX64.EFI` (`BOOTAA64.EFI`), which the firmware starts by the removable-media path with no NVRAM entry.
  Its command line is built in, so nothing has to pass one: `root=PARTLABEL=isx-root rootfstype=ext4 rootwait ro console=ttyS0 loglevel=5 TERM=dumb` on x86_64 (`CMDLINE_OVERRIDE`), the same with `console=ttyAMA0` on aarch64 (`CMDLINE_FORCE`).
  GRUB and shim exist to choose among kernels and to carry a Secure Boot chain; this image has one kernel, and Secure Boot is off for every VM (#1240, docs/dev/PERFORMANCE-NOTES.md "VM boot: where the wait goes"), so they would only add their time.
  That kernel is not signed: with Secure Boot on, OVMF would refuse it.
  isx turns Secure Boot off on every VM build (`prepareVmBuild`), every branch (`configureBranch`) and every start for use (`startForUse`); a template built before #1240 that keeps it on through a template update or an agent recovery still has its stock-based disk, built before this image existed.
  isx releases that turn none of it off are never given this image (below).
- **No initrd.**
  An initrd is needed when the root's drivers are modules: Fedora's kernel has virtio-scsi, Incus's default root bus, as one.
  This kernel has no modules at all; virtio-blk and ext4 are built in, and the kernel finds the root by its GPT partition name.
  isx puts every VM build's root disk on virtio-blk (`InstanceLifecycle.prepareVmBuild`), and branches copy that device.
  Dropping the initrd removes systemd running twice and `switch-root`: 2.1 s of the 6.0 s to `basic.target` measured at one level of virtualization on #1255.
- **One pinned kernel.**
  The kernel is built from kernel.org source, checksummed, by `appliance/kernel/build-kernel.sh vm` at the incus-spawn commit the images repo pins (`fedora/build-vm-kernel.sh`), so its config has one home and reaches the image only through a bump of that pin.
  The image carries no kernel package, no `/boot`, and no module files: `/usr/lib/modules/<release>/` holds only the index of built-in drivers, so `modprobe` of one succeeds (a nested Incus needs `modprobe vhost_vsock`).
  dnf in the image refuses kernel packages (`/etc/dnf/libdnf5.conf.d/isx-kernel.conf`), since a Fedora kernel would land in a `/boot` nothing boots.
  So a template cannot upgrade or replace the kernel of this image, by design: `dnf upgrade` leaves it alone, and `dnf install kernel-core` answers "matches only excluded packages" (seen on #1217); a new kernel comes with a bump of the pin.
  The stock-based image (`-vm.tar.xz`, for older isx, last released for Fedora 44) still takes kernel upgrades, and from incus-spawn-images#27 on boots the newest one: its later kernels' boot entries share the image's own entry id (`KERNEL_INSTALL_MACHINE_ID` in `/etc/machine-info`), so GRUB's order no longer depends on the template build's random machine-id (#1217).
  **Kernel fixes do not arrive with the ten-day rebuild.**
  The image is rebuilt every ten days, which brings Fedora's userspace updates, but its kernel is the version `KERNEL_VERSION` and `KERNEL_SHA256` in `appliance/kernel/build-kernel.sh` name, at the incus-spawn commit `ISX_REF` pins in the images repo, and the images CI caches the built kernel by that pin: every rebuild ships the same kernel until both are bumped.
  The stock-based image got Fedora's kernel updates with every rebuild, so this would be a step back if nothing moved the pin; the upstream watch does (#1355, #2036, "Kernel updates" below).
  A kernel security fix reaches VMs when that bump merges, then `ISX_REF` in the images repo follows, and an images release ships it.
  The same pin builds the macOS appliance's kernel, which already works this way.
  Kernel development happens in a nested VM inside a `--kvm` branch, never by replacing the box's own kernel.
  What a template needs from the kernel goes into `vm.config` when a template asks for it.
  The `vm` build fails when any option of its fragments stops applying, `isx.config` included, since the VM kernel relies on it too (#2005).
  So each fragment holds only options its arches can take, and the build expects none to skip.
- **How it ships.**
  Every isx release before the reader of `SHA256SUMS.v2` tracks the newest release's `-vm.tar.xz` and starts it with Secure Boot on and the root on virtio-scsi, which this image cannot boot.
  So a Fedora 44 release keeps that stock-based image and `SHA256SUMS` exactly as they were and adds this one as `-vm-direct.tar.xz`, listed only in `SHA256SUMS.v2` (see design/templates.md "Base image").
  Fedora 45 releases carry this one alone, with `SHA256SUMS.v2` as their only manifest (#1862).
  The images repo's release job publishes it only when the boot test (`appliance/test-vm-kernel.sh`) passed on it, and checks every line of `SHA256SUMS.v2` against the file published under its name.

Measured under nested KVM in an isx VM (the same host as docs/dev/PERFORMANCE-NOTES.md "VM boot: where the wait goes", Incus 6.23, 8 vCPUs, 4 GiB), three runs each, from the start request until an exec answers: 21.8 s on the stock-based image, 11.9 s on this one; through `isx branch` (`bench/vm-boot.sh --isx`), 22 s and 12.6 s.
In plain QEMU with the boot test, firmware to `basic.target` took 4.2 s under the same nesting (median of three, on the image incus-spawn-images#22 built, reported there); at one level of virtualization #1255 measured 2.4 s, against 6.0 s for the stock-based image with Secure Boot off.
The firmware, about 1 s at one level of virtualization, is what remains; a trimmed OVMF measured 0.26 s less under nested KVM, which suggests about 0.1 s at one level, and is not shipped (below).

### Kernel updates (#1355)

The VM base image and the macOS appliance build one kernel, the version `appliance/kernel/build-kernel.sh` pins, and a stable fix reaches neither until that pin moves.
The upstream watch moves it, as a proposal (#2036, design/upstream.md "The model"): its `kernel` item takes the newest point release of the pinned series and its sum from kernel.org's `sha256sums.asc`, through `appliance/kernel/update-kernel-pin.sh releases` and `sum`, waits a day after the release, and keeps one PR, `upstream/kernel`, on it.
A newer release replaces that PR's commit and title rather than opening a second PR, so a stale bump never waits beside a current one; a release whose PR was closed unmerged is not proposed again; a PR that `main` has overtaken is closed, and one ahead of the newest release the signed sums list stays as it is, since that file can lag.
The engine reads what the open PR proposes from its branch, and a declined release from a marker in the closed PR's body, so editing a title has no effect; the branch is pushed again only for a newer release or when `main`'s `build-kernel.sh` has changed under it, since each push discards the PR's Mac boot; the PR is marked pending before the push and names the release only after it, so a failure between the two never leaves a PR naming one release over another's commit.
A new series is never proposed: when kernel.org lists one, the watch opens `[upstream] kernel: series <s> available`, and when it ends the pinned one, `[upstream] kernel: series <s> is EOL`, since its fragments, both boot paths and the Mac boot need a person.
A failed run opens `[upstream] watch failing`, which the next good run closes, so a failure is not only in an Actions log.
The bump goes through CI and a Mac boot (ci.md, #791), and merges only as the engine's proven regeneration: `upstream-verify` and `kernel-mac-verified` green, then the bot's merge script, never a person's reading of the diff (design/upstream.md "The model").
These are the rules `update-kernel.yml` and its `bot/kernel-bump` PR had (#1355), kept in `upstream/engine/pr.sh` for every item.

**The Mac boot is recorded against what it booted.**
The `kernel-mac-verified` check fails any PR, by anyone, whose merge changes the appliance kernel's inputs, until a Mac boot of a build with those inputs is recorded (`.claude/rules/ci.md`).
The inputs are `appliance/build.sh`, `build-kernel.sh` and the appliance fragments, hashed by their git blobs into a fingerprint.
The build says what it built: `build-appliance.yml` writes the commit into `kernel-inputs` beside the image, and on a PR that commit is the merge, not the head.
The Mac boots it only if that is the PR's merge as GitHub has it now, fingerprints that merge, checks the kernel's own `Linux version` banner against its `KERNEL_VERSION`, and sets a status on the PR's head with the fingerprint.
The check compares it with the fingerprint of the PR merged into its base as it is when the check runs, so a rebase that leaves the kernel alone keeps the boot, while a change to any input, on the PR or under it on the base, needs another; the check and the Mac both fingerprint with `main`'s script, never the PR's.
**Alternatives:** a status on the commit alone, which a rebase or an unrelated push would discard and send every bump back to the Mac queue; the head's fingerprint, which misses a kernel change the base brought in and is not what the PR build compiled; a comment a reviewer compares by eye, the habit this replaces; the PR's own copy of the check, which a PR could edit to pass, so it runs on `pull_request_target` from the base branch.
**What it does not cover:** the compiler, which `build.sh` takes from `fedora:44` at build time; a base that moves after the check passed, which only branch protection's "require branches to be up to date" catches; and the artifact is the PR's own build, so the check proves a boot happened, not that a PR's workflow was honest, which review still does.
The verifiers are Sanne and the bot account the Mac runner uses, which the worker boxes use too.

**The Mac boots only what Sanne or the bot pushed (#1385).**
A PR's appliance image is built by that PR's own `build-appliance.yml`, so an external PR's image is its author's.
The boot runs it under isx's VM manager, which shares the Mac user's whole home with the guest, and that home holds the `gh` token that records a boot.
So `test-kernel-on-mac.sh` refuses a PR whose author or head repository is anyone else, with no flag to override it, and Sanne re-pushes an external kernel change as her own branch to have it booted.
It also runs only from a clean checkout of `origin/main`: run from a PR's branch, it would run that PR's scripts with the verifier's credentials and fingerprint with the PR's input lists.
**Alternatives:** an override flag for a reviewed external PR, which a misled verifier could pass as easily as Sanne; booting external images with no credentials in the shared home, which is still open for trusted PRs as well (#1385).

**A failed Mac boot keeps what decides it (#2033).**
The Mac runner boots PRs unattended, so the person reading a failure has only the record and what the Mac kept.
The record names each failed check with its counts, since a tail of the log does not, which is what tells a known tunnel fault (#785) from a regression.
The full logs, the guest's serial console included, stay on the Mac, in the run's work directory and per PR under `~/.cache/incus-spawn/kernel-mac/`, but of the artifact only `kernel-inputs` does, or an unattended Mac would fill up with copies of the artifact.
The record is public, so the work directory, home, host name and user name are scrubbed from what it quotes.
**Alternatives:** posting the whole log, which the guest writes and which buries the failed checks; keeping the whole work directory, a few gigabytes per failure.

**Where the sum comes from.**
`update-kernel-pin.sh` verifies `sha256sums.asc` with `gpgv` against `appliance/kernel/kernel.org-autosigner.asc`, the kernel.org checksum autosigner's key, and also requires the one valid signature to be by `AUTOSIGNER_FPR`, the same key's fingerprint pinned in the script.
It reads only the text gpgv reports as signed, so a line added after the signature never counts.
The pin moves only forward, within its series.
`check` re-derives the pinned version's sum and fails on any other, and `kernel-pin-signed.yml` runs `main`'s copy, with `main`'s key, on the pin of every PR that changes `appliance/kernel/`, so a bump by hand meets the same rule as the bot's, and a PR that swapped the key and the script along with the pin cannot vouch for itself.
**Alternatives:** `releases.json` or git tags with the downloaded tarball's own hash, which is trust on download, what #1128 removed; the key fetched from WKD or a keyserver on every run, which would accept a rotated or spoofed key without anyone seeing it; automerge on plain green, which would leave no proof between kernel.org and the kernel every macOS container runs on; the bot merges only a PR that `upstream-verify` proved to be the engine's regeneration of the signed sum, after a Mac boot (design/upstream.md "What is never done").

**The key is committed, and changed only by hand.**
It was taken from the signed `pgpkeys.git` on git.kernel.org, and the same fingerprint, `B8868C80BA62A1FFFAF5FDA9632D3A06589DA6B1`, is served by kernel.org's WKD (`gpg --locate-keys autosigner@kernel.org`) and keys.openpgp.org; kernel.org's signature page describes the autosigner without printing it.
A change is a two-place edit, the key file and `AUTOSIGNER_FPR`, made only after the new fingerprint has been checked against those three sources; `KernelPinUpdateTest` fails when the two places disagree or the file holds another key.
The key has no expiry today.
When kernel.org rotates it, the watch fails closed and opens `[upstream] watch failing`.

## The VM base image's own kernel (#1255)

How the image's kernel came about, in the order #1255 built it; "The VM image's boot chain" above is what ships.
The kernel is built from the appliance's source pin, and OVMF starts it straight from the ESP.
`appliance/kernel/build-kernel.sh vm` builds that kernel, and `appliance/test-vm-kernel.sh` boots it.
The `vm` role layers `vm.config` and `vm-<arch>.config` over the appliance fragments.
They add an ext4 root on virtio-blk, the EFI stub, autofs, MSI, nested KVM with vhost-net and vhost-vsock, and 9p for the agent's config share.
The command line is built in, since nothing passes one: the root is found by its GPT partition name, `isx-root`, which the kernel resolves itself, with no udev.
A feature goes in when a template asks for it, and what is deliberately absent is listed in the fragment's header.

`test-vm-kernel.sh` boots a copy of a VM image's disk under QEMU with the kernel as the ESP's only file, OVMF or AAVMF with Secure Boot off, 8 vCPUs and 4 GiB.
It asserts the guest reaches `basic.target` with no virtio device on a legacy interrupt line, and prints firmware, kernel, userspace and ready times, `critical-chain`, `blame` and on request the plot.
It prepares the copy with `sgdisk`, `mtools` and `debugfs`, so it needs neither root nor a loop device, and the firmware is a parameter, so a trimmed OVMF build is measured by the same harness.
`test-vm-kernel.yml` runs it in CI, x86_64 under KVM and aarch64 under TCG, on the VM image `minimal.yaml` pins: the stock-based one until the base-image bump moves the built-in to `-vm-direct`, then the image built from scratch, which the images repo's own CI also boots before every release.

Measured on 2026-10-11 under nested KVM (the same host as docs/dev/PERFORMANCE-NOTES.md "VM boot: where the wait goes", inside an isx VM), on `fedora-44-20261011`, median of five boots: firmware 2.6 s, kernel 0.76 s, userspace to `basic.target` 1.24 s, ready 4.6 s.
Firmware here includes QEMU's own start.
Two options the plan asked to measure did not show at this level of nesting, where the kernel time alone varied by ±0.15 s between runs (on the stock image of 2026-10-09).
`DEFERRED_STRUCT_PAGE_INIT` averaged 0.58 s of kernel time against 0.66 s over eight interleaved pairs, so it is left out.
Turning `CPU_MITIGATIONS` back on measured no worse either, so the vm role keeps the appliance's `n` on x86_64, the only arch that can switch it off (`isx-x86_64.config`).
Both are for step 6 to measure again on bare metal.
aarch64 boots to `basic.target` under TCG with this kernel and AAVMF, which no one had tried before.

The host side is step 4.
VM builds put the root disk on virtio-blk (see design/host-resources.md "VM behavior"), and grow the root wherever it is attached.
Before any image release, it is exercised with the from-scratch image built locally (incus-spawn-images `fedora/build-vm-scratch.sh`) and named by a project-local template, `image_url` and `vm_image_url` pointing at the tarballs with `file://`.
A template built and branched that way found the three kernel options `appliance/DESIGN.md` lists for it: online `resize2fs`, PCI hotplug, the power button.
The CI integration step for VMs (`test-instance.sh`, `test-accounts.sh`) then passed on the image of incus-spawn-images `4bc90f5`, and three more gaps showed, in the kernel and in what the image carries for it.
Without `PCI_MSI` all virtio devices share one legacy interrupt line, and under nested KVM the agent's setup took 5.5 s, its 9p copy alone 5.1 s against 0.9 s on Fedora's kernel: `isx branch` took 18 s on that image, 12.6 s once its kernel had MSI, and 22 s on the stock image.
A nested Incus refused VMs twice: `modprobe vhost_vsock` failed, since the image had no index of built-in drivers, and QEMU could not create an irqchip without `KVM_IOAPIC`; `KVM_SMM` came in with it, for Secure Boot guests.
With those, a nested Incus ran a container, a VM with Secure Boot off and a Fedora VM with it on.
`bench/vm-boot.sh --isx` times `isx branch` itself: under nested KVM, about 2 s pass between the agent going active and `isx branch` returning, on the stock image and the new one alike.
`InstanceLifecycleRequestBudgetTest` counts that part of a VM branch: 15 requests, two of them execs (the secret's guest script and the `resolv.conf` check), with the `.network` push, one instance read and one bridge read.
Counted first, it was 17: the CA and `resolv.conf` checks each read again what the push had just read.

A trimmed OVMF was measured with the same harness before anything is built for it (#1255, step 8).
EDK2 `edk2-stable202608`, the release Fedora's `edk2-20260812` packages, was built for `OvmfPkgX64` twice: with its defaults, and with `NETWORK_ENABLE`, `SECURE_BOOT_ENABLE`, `TPM2_ENABLE`, `TPM1_ENABLE`, `BUILD_SHELL` and `SMM_REQUIRE` all `FALSE`.
Nine interleaved boots each, same kernel and root, mean firmware time: Fedora's `OVMF_CODE.fd` 2.51 s, upstream defaults 2.43 s, trimmed 2.25 s.
So trimming saves about a quarter of a second under nested KVM, where the firmware takes about 2.5 times the 1.0 s it took at one level of virtualization, which suggests about 0.1 s there.
That is short of the several hundred milliseconds the plan asked for before shipping a firmware build, so nothing is shipped.
A boot on Sanne's host with `FIRMWARE_CODE` pointed at the trimmed build would settle it.
USB has no build flag and was left in.

## A VM's hostname is set at each start (#2334)

Every name the guest shows reads its hostname: `ISX_CONTAINER` expands `${HOSTNAME}` at login, and the terminal title, the agents' status lines and starship's module read that.
Incus sets a container's hostname to the instance name, and sets nothing in a VM.
A VM kept the base image's `fedora`, unless a DHCP answer from the bridge happened to name it on a first boot, and a VM that boots its static `.network` file asks DHCP nothing.
So two VMs open at once could not be told apart from inside, and a tab running `isx shell` on one was titled `isx:fedora`.

isx writes the instance name to `/proc/sys/kernel/hostname` in the exec that gives each boot its secret (`GuestHostname`): a start or restart for use (`awaitReady`), a branch's setup script, the agent recovery's restart, and the repair of a reboot isx did not do (`giveSecretHeld`).
It is never written at build: the name has to be the branch's own, which is why `ISX_CONTAINER` is expanded at login.
It is never written to `/etc/hostname` either, which a copy would carry under its source's name until its own first start.

What this gives up: the name lasts one boot.
A VM that reboots by itself shows `fedora` until isx next reaches it, which the first `isx shell`, plain `ssh` or `isx mcp` call does, since each repairs a boot without its secret.
A boot unit in the base image that read the name from `/dev/incus` would cover that gap too, but it would also need a base image release and would set the name a second way beside the one isx already owns.

The alternative was a file in `/run/isx` that every consumer reads instead of the hostname.
It would leave `hostname` and every `gethostname()` saying `fedora`, and every consumer would have to change.

## Considered and deferred

### Address-based caller identity on macOS

On macOS every container reaches the proxy from the VM's one address, so the proxy identifies a caller there by the proof or per-start secret it presents, not by where it comes from (#1582, #1583).
Restoring per-container addresses was considered: the appliance would REDIRECT `:443` to an in-VM relay that prefixes a PROXY protocol v2 header, the proxy would trust that header only from the VM, and a FORWARD rule would stop containers forging it.
It is deferred because proof-only identity is simpler and sufficient for now.
What it would close is replay of one instance's tokens from another, which needs that instance to leak them first, and it would cost a long-lived relay on the data path, PROXY protocol in the native proxy, the anti-forgery rule and validation on a real Mac.
It is worth revisiting for full per-instance traffic accounting on macOS (cached Maven or npm traffic carries no proof), or for a threat model where one user's instances are mutually adversarial.
The full reasoning is in #1584.

### A Linux appliance mode

On Linux isx drives the host's own Incus, and the appliance VM runs only under `isx-vm` on macOS.
A Linux appliance mode, with isx booting and managing the same VM as on a Mac, is a possible future: for a host whose Incus cannot be installed or upgraded, or to develop the appliance on Linux with isx in the loop.
`VmManager` once had a QEMU backend for it, but no released isx could reach it: `isx vm` is registered only on macOS, and every other caller of `VmManager` is behind a macOS check.
It was removed so that fixes stop landing on a path no user runs (#1695).
PR #1754 removed it, so its diff shows the code: `startQemu` and `qemuDriveArgs` in `VmManager`, with `Backend.QEMU` and the Linux branch of `detectBackend`.
`0434e17e` on `main` still has it, and `git log -S startQemu` finds the commit that removed it.
That is the starting point, not the design: the mode would also need vsock wiring to the forwarder, a reachable entry point such as `isx vm` on Linux, and a CI job that boots it through isx.

### An NVMe or host-cached root disk (#810)

A VM's root disk stays on virtio-blk, and isx sets no `io.cache` on it, so Incus picks the mode by the pool.
On btrfs and ZFS that is `writeback`: Incus avoids `O_DIRECT` on a disk file there, citing btrfs checksum errors and ZFS lockups (docs/dev/PERFORMANCE-NOTES.md "VM disk cache mode, per context").
On `dir` it is `none`, which reaches the host's disk on every flush the guest issues.
So on the btrfs pool `isx init` makes, every VM already reads and writes through the host's page cache, and the guest's flushes still reach the disk.
Measured under nested KVM, NVMe was no faster on a git clone, unpacked a tree 30-40% slower and does not change boot time (docs/dev/PERFORMANCE-NOTES.md "VM root disk: bus and host cache mode").
It would also need NVMe built into the VM base image's own kernel, which has virtio-blk alone.
isx does not override Incus's choice in either direction.
Setting `none` on btrfs would take the `O_DIRECT` path Incus avoids for checksum errors.
Setting `writeback` on `dir` gave direct reads more speed but no clone or unpack any faster, and costs host memory.
`unsafe` also ignores the guest's flushes, so a host crash can lose writes the guest was told were on disk and corrupt its filesystem.

Three places where `writeback` or `unsafe` might still pay were measured, and none is taken (#2198):
- **A `/tmp` of its own on `unsafe`** halves an rpm install and a tar unpack written there against today's `/tmp`, and saves nothing on a Maven build.
  It would add a volume, its attachment and a format to every VM branch, and it speeds up only work in `/tmp` that issues many flushes.
- **Branches do not share their template's blocks in the host's cache.**
  A copy's image is a separate file on btrfs, and the page cache is per file, so each branch caches its own copy of what it reads, and keeps it after it stops.
  Reading all of `/usr` and a Maven build left 2.5 GiB of each of four branches' images cached, and the fourth read no faster than the first.
  This is the cost of the `writeback` mode VMs already run on btrfs; `none` would avoid it but takes the path Incus avoids.
- **A template build on `unsafe`, flushed after a clean stop and switched back before any branch,** saves about 2.4 s per 700 MiB of packages installed, and the flush costs 0.37 s of it.
  A failed build is discarded anyway, but a host crash between the build's end and the flush would leave a corrupt template that looks built, so the flush would have to come before the build counts as done.
  Whether that gain is worth the extra step is Sanne's decision on #2198.

NVMe is worth measuring again on bare metal or on ZFS, which #810 did not cover.
