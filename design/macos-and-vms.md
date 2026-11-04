# macOS, the appliance and VMs

How isx reaches Incus, on Linux and through the macOS appliance, and how VM instances boot.

## Incus Daemon Connection

The CLI communicates with the Incus daemon via its REST API.
The transport depends on the platform:

**Linux** (direct): The Incus daemon exposes a Unix domain socket at `/run/incus/unix.socket`.
The CLI speaks plain HTTP/1.1 over this socket — no TLS, no authentication (access is governed by Unix socket permissions and the `incus-admin` group).
WebSocket-based exec sessions (for `isx shell`, file push, etc.) use the same socket.

**macOS** (via VM): Incus runs inside a VM managed by vfkit.
The CLI connects via a **vsock tunnel** — a direct host↔VM communication channel that bypasses the IP network entirely:

```
UnixSocketTransport (plain HTTP/1.1)
  → ~/.local/state/incus-spawn/vm.incus.sock  (Unix socket on host)
    → vfkit virtio-vsock device (port 8443)
      → socat VSOCK-LISTEN:8443 inside VM
        → /run/incus/unix.socket (Incus daemon)
```

vfkit exposes the VM's vsock port 8443 as a Unix domain socket on the host.
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

The vfkit vsock tunnel (`AF_VSOCK` across `host unix socket → vfkit → in-VM socat → Incus`) does **not reliably propagate connection close/EOF** — particularly after macOS sleep/resume, when in-flight streams are left half-open.
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
  So a gap between two polls counts as idle for at most half the window, and a late poll ends the drain only if the poll before it already found the output idle, which gives the readers the sleep in between to catch up.
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
  They pile up as vfkit-held host fds and degrade every new connection (observed: hundreds of leaked streams, `list` latency from sub-second to ~30s).
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
  The WebSocket connect + handshake is bounded by the transport timeout like any request, since a wedged tunnel accepts and then says nothing.
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
  Through vfkit it may never arrive, and then the forwarder keeps the silent connection open until `socat -T` reaps it -- up to 180s with no API at all, which reads exactly like the field "wedged tunnel" that `forwarder-restart` clears instantly by killing the child.
  isx therefore never opens a tunnel connection without writing a request straight away.
  The one place that used to (the vsock branch of `diagnoseConnectionFailure`, a connect-and-close reachability check that ran precisely when the tunnel was struggling) now sends a real `GET /1.0` under the probe timeout.
  The server-side fix belongs upstream.
  LXD fixed the identical code (canonical/lxd#18705, peek moved off the accept loop with a read deadline).
  Incus followed after 7.5.1 (lxc/incus@2cb2549, for lxc/incus#4082): each connection is classified in its own goroutine, with a 3s deadline on the first read.
  No release had it when #1313 needed it, so the appliance build carries it as a backport onto Alpine's 6.21.0 (`appliance/incus/`, see `appliance/DESIGN.md`).
  On an appliance built since then, a silent or partial connection stalls nothing, and incusd closes it after 3s, well before the forwarder's backstop.
  An older appliance is still affected, so the rule above stays.
  incusd also closes idle keep-alive connections after 30s (`IdleTimeout`), a close vfkit may not deliver either.
  So `ConnectionPool`'s TTL (5s) must stay well under it.

- **The forwarder's accept queue must absorb one exec.** socat's default listen backlog is 5, and a vsock listener with a full accept queue refuses the connection rather than letting it wait.
  One exec opens four fd WebSockets plus `/wait` at once, so any overlap with other traffic was refused; CI measured 3 of 16 and 12 of 32 concurrent connects refused.
  Both guest listeners (forwarder and agent) set `backlog=128`.

- **Diagnosis and layer-aware recovery.**
  The tunnel has two independent failure points: the host-side vfkit forwarding (link 2) and the guest-side socat forwarder (link 3).
  `isx doctor` and the automatic recovery in `VmManager.ensureRunning()` both compare the host-side fd count (`lsof`) against the in-VM socat child count (agent `socat-count` verb) to localize the wedge via `leakLayer()`: if the guest count is low while the host count is high, vfkit is not reaping (VFKIT); if both are high, the forwarder is lingering children (FORWARDER); if the guest count is zero, the forwarder is not running (always FORWARDER — restarting it is the correct fix regardless of host count).
  `isx doctor` spends an agent round trip only where it changes the finding: it asks for the guest count only when the host count reports a leak or cannot be measured, and pings the agent before offering `forwarder-restart` only when that count did not come back, since a count that did already proves the agent lane (#1214).
  The forwarder finding is made before the "Incus not reachable" (or "slow") one: when it offers `forwarder-restart`, the Incus finding offers no VM restart and does not point at the VM, so a user answering the prompts in order does not reboot for a fault the no-reboot restart clears (#1277).
  The recovery decision tree in `recoverReachability()`: 10s grace probe → `detectLeakLayer()` → for a VFKIT wedge, fail fast with "run `isx vm restart`" (a forwarder restart cannot fix a host-side problem); for FORWARDER or unknown layer, restart the forwarder via the agent → 15s post-restart probe → 30s backstop.
  `probeTunnelHealth()` provides the same detection as a public API for proactive TUI/build-time checks, returning `HEALTHY`/`VFKIT_WEDGED`/`FORWARDER_ISSUE`/`UNKNOWN`.
  Recovery is provided by a small **allowlisted in-VM control agent** (`isx-agent`) on its own vsock port — verbs `ping`, `version`, `socat-count`, `sshd-status`, `forwarder-restart`, `btrfs-usage`, `btrfs-status`, `btrfs-rescan`, no arbitrary exec — reached over an independent channel so it works even when the Incus tunnel is wedged.
  `forwarder-restart` drops and relaunches the forwarder **without rebooting the VM or stopping containers**, and now verifies the new process started (polls `pgrep` 4×0.5s) before confirming; stderr goes to `/dev/console` (the virtio-serial feeding `vm.log`) so startup errors are visible.
  See appliance/DESIGN.md for the in-VM side.

## The macOS VM outlives whatever started it

vfkit is started as the leader of a new session, not as a plain child of `isx`.
A plain child stays in the starting command's process group and on its terminal, and both take it down.
Closing the terminal hangs up every process on it.
Ctrl+C reaches the whole foreground group, so interrupting the command that happened to start the VM stopped the VM.
And launchd kills whatever is left in a job's process group once the job exits, so the login agent, whose `isx vm start` exits as soon as the VM is up, killed its own VM a few seconds later (#971).

Java has no `setsid`, and macOS ships no `setsid` command, so the VM is started through `/usr/bin/perl -MPOSIX=setsid -e 'setsid(); exec …'`: perl replaces itself with vfkit, so the pid `isx` records is the VM's.
Linux has util-linux's `setsid`, which execs the command in its own place too when, as a child of the JVM, it is not a process group leader; `OwnSession` prefers it and falls back to perl.
Ignoring SIGHUP (`nohup`) was rejected because it covers one of the three cases; `AbandonProcessGroup` in the agent's plist covers another one only.
Launching the app bundle through LaunchServices (`open`) would detach it too, but returns no pid.
If perl is ever gone from macOS the command is run as before, and this has to be solved again.
The perl wrapper starts without the user's `PERL5OPT`, `PERL5LIB` and `PERLLIB`, which could stop it from loading, and puts them back before the exec: whatever it starts keeps them, a Git credential helper in perl among them.

A build's host Git is started the same way (#1425), but unlike the VM it must not outlive isx: see design/templates.md "A running build takes two Ctrl+C".

vfkit's own stdout/stderr (perl's diagnostics if the exec above fails; otherwise whatever vfkit itself prints) go to `Environment.vfkitLogFile()`, never to `vm.log`: vfkit opens `vm.log` itself, non-append, for the VM's virtio-serial console.
A second writer appending to the same file corrupts both — vfkit's own periodic lines ("machine awake" on host wake, timesync setup) land at EOF over console bytes `isx vm console` has already read past, and the console's next write lands over those in turn.
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
It needs four layers, listed in [`appliance/DESIGN.md`](../appliance/DESIGN.md) "No nested virtualization": the appliance kernel, vfkit's `--nested`, QEMU and its firmware in the appliance, and isx's own `/dev/kvm` check.
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
  The stock-based image got Fedora's kernel updates with every rebuild, so this would be a step back if nothing moved the pin; `update-kernel.yml` does, daily (#1355, "Kernel updates" below).
  A kernel security fix reaches VMs when that bump merges, then `ISX_REF` in the images repo follows, and an images release ships it.
  The same pin builds the macOS appliance's kernel, which already works this way.
  Kernel development happens in a nested VM inside a `--kvm` branch, never by replacing the box's own kernel.
  What a template needs from the kernel goes into `vm.config` when a template asks for it, and the `vm` build fails when an option there stops applying.
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
`update-kernel.yml` moves it, daily, as a proposal: `appliance/kernel/update-kernel-pin.sh bump` takes the newest point release of the pinned series and its sum from kernel.org's `sha256sums.asc`, and the job keeps one PR, `bot/kernel-bump`, on it.
A newer release replaces that PR's commit and title rather than opening a second PR, so a stale bump never waits beside a current one; a release whose PR was closed unmerged is not proposed again; a PR that `main` has overtaken is closed, and one ahead of the newest release the signed sums list stays as it is, since that file can lag.
The job reads what the open PR proposes from its branch, and a declined release from a marker in the closed PR's body, so editing a title changes nothing; the branch is pushed again only for a newer release or when `main`'s `build-kernel.sh` has changed under it, since each push discards the PR's Mac boot; the PR is marked pending before the push and names the release only after it, so a failure in between never leaves a PR naming one release over another's commit.
A new series is never proposed: when kernel.org ends the pinned one, the job opens `[kernel] <series> is EOL`, since its fragments, both boot paths and the Mac boot need a person, and closes it once the pin is on a live series.
A failed run opens `[kernel] update check failing`, which the next good run closes, so a failure is not only in an Actions log.
Nothing merges by itself: the bump goes through CI and a Mac boot (ci.md, #791), and Sanne merges it.

**Where the sum comes from.**
`update-kernel-pin.sh` verifies `sha256sums.asc` with `gpgv` against `appliance/kernel/kernel.org-autosigner.asc`, the kernel.org checksum autosigner's key, and also requires the one valid signature to be by `AUTOSIGNER_FPR`, the same key's fingerprint pinned in the script.
It reads only the text gpgv reports as signed, so a line added after the signature never counts.
The pin moves only forward, within its series.
`check` re-derives the pinned version's sum and fails on any other, and `kernel-pin-signed.yml` runs `main`'s copy, with `main`'s key, on the pin of every PR that changes `appliance/kernel/`, so a bump by hand meets the same rule as the bot's, and a PR that swapped the key and the script along with the pin cannot vouch for itself.
**Alternatives:** `releases.json` or git tags with the downloaded tarball's own hash, which is trust on download, what #1128 removed; the key fetched from WKD or a keyserver on every run, which would accept a rotated or spoofed key without anyone seeing it; automerge on green, which would leave no person between kernel.org and the kernel every macOS container runs on.

**The key is committed, and changed only by hand.**
It was taken from the signed `pgpkeys.git` on git.kernel.org, and the same fingerprint, `B8868C80BA62A1FFFAF5FDA9632D3A06589DA6B1`, is served by kernel.org's WKD (`gpg --locate-keys autosigner@kernel.org`) and keys.openpgp.org; kernel.org's signature page describes the autosigner without printing it.
A change is a two-place edit, the key file and `AUTOSIGNER_FPR`, made only after the new fingerprint has been checked against those three sources; `KernelPinUpdateTest` fails when the two places disagree or the file holds another key.
The key has no expiry today.
When kernel.org rotates it, the daily job fails closed and opens its issue.

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
Turning `CPU_MITIGATIONS` back on measured no worse either, so the vm role keeps the appliance's `n`.
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

## Considered and deferred

### Address-based caller identity on macOS

On macOS every container reaches the proxy from the VM's one address, so the proxy identifies a caller there by the proof or per-start secret it presents, not by where it comes from (#1582, #1583).
Restoring per-container addresses was considered: the appliance would REDIRECT `:443` to an in-VM relay that prefixes a PROXY protocol v2 header, the proxy would trust that header only from the VM, and a FORWARD rule would stop containers forging it.
It is deferred because proof-only identity is simpler and sufficient for now.
What it would close is replay of one instance's tokens from another, which needs that instance to leak them first, and it would cost a long-lived relay on the data path, PROXY protocol in the native proxy, the anti-forgery rule and validation on a real Mac.
It is worth revisiting for full per-instance traffic accounting on macOS (cached Maven or npm traffic carries no proof), or for a threat model where one user's instances are mutually adversarial.
The full reasoning is in #1584.

### A Linux appliance mode

On Linux isx drives the host's own Incus, and the appliance VM runs only under vfkit on macOS.
A Linux appliance mode, with isx booting and managing the same VM as on a Mac, is a possible future: for a host whose Incus cannot be installed or upgraded, or to develop the appliance on Linux with isx in the loop.
`VmManager` once had a QEMU backend for it, but no released isx could reach it: `isx vm` is registered only on macOS, and every other caller of `VmManager` is behind a macOS check.
It was removed so that fixes stop landing on a path no user runs (#1695).
PR #1754 removed it, so its diff shows the code: `startQemu` and `qemuDriveArgs` in `VmManager`, with `Backend.QEMU` and the Linux branch of `detectBackend`.
`0434e17e` on `main` still has it, and `git log -S startQemu` finds the commit that removed it.
That is the starting point, not the design: the mode would also need vsock wiring to the forwarder, a reachable entry point such as `isx vm` on Linux, and a CI job that boots it through isx.
