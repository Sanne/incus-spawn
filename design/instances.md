# Instances: branching, configuration and lifecycle

What an instance is, how a branch is made and configured, and how its lifecycle is kept consistent.

## Container Model

- **System containers** by default (lightweight, full init system), with `--vm` flag for KVM VMs (stronger isolation, separate kernel)
- Containers don't drop capabilities (`lxc.cap.drop =`) and the host kernel relaxes `perf_event_paranoid` to `-1` so profilers work without restrictions; `ptrace_scope` is unrestricted (Yama is not compiled in) and `ping_group_range` is wide by default
- No GUI unless the template asks for it (`gui: true`); Wayland + GPU passthrough available at branch time
- Three network modes at branch time: full internet (default), proxy-only, or airgapped
- Container user: `agentuser` (UID 1000, passwordless sudo)

## System containers vs application containers
System containers run a full init system and present as a complete machine.
This means higher base image size (~200MB vs ~5MB Alpine) and longer first-build time (system upgrade, user creation, tool installation).
However, clones are instant and near-zero cost with CoW storage, which is the common operation — you build once, branch many times.

## Container vs VM Trade-off
- **Containers** (default): share host kernel.
  A kernel exploit could escape.
  Suitable for semi-trusted code (AI agents with scoped permissions, community bug reproducers).
- **VMs** (`--vm` flag): hardware-level isolation via KVM.
  Recommended for actively malicious code.
  Separate kernel eliminates kernel exploit as an escape vector. ~10% performance overhead.

## No capability dropping (`lxc.cap.drop =`)
Standard Incus containers drop many Linux capabilities for defense-in-depth.
We don't, because the container *is* the security boundary and developers expect `ping`, `strace`, `perf`, raw sockets, and `dmesg` to work.
The risk is that a container escape exploit has more host capabilities to abuse.
For the strongest isolation, use `--vm`: a separate kernel under KVM, so a kernel exploit inside the guest does not reach the host kernel.

## Branching

Like `git branch`, branching creates an instant copy-on-write clone of any template image.
Each branch has its own independent filesystem -- changes in one branch cannot affect the template image or any other branch.
The CoW storage backend (btrfs/zfs/lvm) deduplicates unchanged data transparently at the block level, so branches are instant to create and only consume disk space for their own modifications.

**Static IP assignment**: Each branch receives a deterministic static IP on the bridge subnet at creation time.
`StaticIpAllocator` scans all existing instances for claimed `ipv4.address` values on NIC devices and picks the lowest free host address (`.2`–`.254`).
The IP is set on the Incus NIC device (so Incus is authoritative) and a `systemd-networkd` `.network` file is pushed into the stopped container before start, so the interface comes up statically at boot with no DHCP lease to expire.
A container does not wait for networkd to apply it (#801).
networkd is ordered after udev and configures eth0 about 200 ms into boot, while exec answers at about 80 ms, and every branch used to wait out the difference.
So the first exec after a start, the branch's setup script or the readiness probe of a stopped container's start, applies the file with `ip` (`InstanceNetwork.APPLY_STATIC_ADDRESS`).
That also gives a cold start what a branch has: its address before the shell is handed over, where it used to win a race against networkd by about 45 ms.
networkd adopts both when it comes up, since they are what it would have configured: the address is set with `brd +` and the route with `proto static`, without which systemd 262 deletes and re-adds each (docs/dev/PERFORMANCE-NOTES.md "Branch setup: the static address").
The file stays the one source of truth, so subnet migration, copies and existing instances need nothing.
Only a file for `eth0` with one address is applied: a VM's matches its NIC by MAC, and DHCP gives it the address before its agent answers.
`raw.lxc` `lxc.net.0.ipv4.*` keys would be as fast but were rejected: LXC numbers NICs in device order, so an added NIC puts the address on the wrong one, and naming `lxc.net.0` on a masked NIC (airgap) fails the start.
It is pushed root-owned `0644` explicitly: pushed with the local temp file's `0600`, `systemd-networkd` (which runs as `systemd-network`) could not read it, and only templates that already carried a `10-eth0.network` hid that, since overwriting keeps a file's mode.
Nothing reserves an address between the listing that finds it free and the write that sets it, so `StaticIpAllocator.claim()` holds a `HostLock` (`~/.cache/incus-spawn/locks/.static-ip.lock`; see "Lifecycle locking") from the listing until the caller's write returns (#815).
The name is dot-prefixed because the TUI's per-instance locks share that directory as `<instance>.lock`: an instance called `static-ip` would otherwise open, close and delete the claim's file.
The listing fails closed: one that cannot be read must not make every address look free.
Everything that does not depend on the address is read before the claim, so concurrent branches wait on each other only for one listing, the `.network` push and the write.
That includes the bridge, read once as a `BridgeAddress` (gateway, subnet, prefix length): branching used to read it three times, for the gateway, the allocation and the prefix.
The lock is per user, since it lives under `$HOME`.
Where it cannot be taken at all -- a home on NFS without lockd -- the claim warns once and goes on with only the in-process lock (`HostLock.acquireOrDegrade`) rather than failing every branch.
Incus's check below turns a collision into a failed branch, not a shared address, which is what `main` did before the lock existed.
A holder that never lets go still times out; only an unusable file degrades.
The claim and the stale-subnet repair report through the `Output` their caller hands them, so the TUI -- which runs that repair on its own screen before a shell -- captures them into its warning log instead of printing over itself (see design/terminal.md "Warnings while the TUI owns the terminal").
Writers it cannot see -- `sudo isx`, another host user's isx on the same daemon, a manual `incus config device set` -- are mostly caught by Incus itself, which refuses a second NIC with the same `ipv4.address` on the bridge (409) and so fails the branch rather than sharing an address.
Mostly, because Incus validates before it commits and two concurrent writes can both pass; only the lock closes that window.
Nor does that check cover copies.
Incus deliberately creates a copy whose NIC conflicts with another and only logs it.
So a branch of a branch used to start with its source's NIC address and `static-ip` metadata.
The proxy then mapped the address to whichever of the two it listed last, and a copy left behind by an interrupted branch kept it for good.
`IncusClient.copy()` now drops both in the copy request itself (a device in the request replaces the source's whole; an empty config value unsets), from the source read `planCopy` already makes.
An address only ever enters an instance through `claim()`.
Duplicates made before this, or by a manual `incus copy`, can still exist.
So the proxy's `InstanceRegistry` no longer lets listing order pick the owner of a shared `static-ip`.
Incus will not start an instance whose NIC address another NIC holds, so the one running claimant owns it, and with none or several running the address maps to nobody (with a warning) rather than to whoever was listed last.
The lock is on the host rather than in the proxy: the address must be claimed in the branch's one write (#804), full-internet branches need no proxy, and the proxy only rebuilds its view from Incus anyway.
Templates do not have baked-in addresses — all CoW branches share the template filesystem, so a static address in the template would collide.
Every build container holds a claimed address only while it builds, so the proxy can serve it its template's accounts, and keeps DHCP in the guest.
Its claim also skips every address the bridge's DHCP server has leased (see design/accounts.md "Builds are served their template's accounts").
The base image (from `Sanne/incus-spawn-images`) provides `systemd-networkd` and bakes in a connectivity watchdog (30s systemd timer) that detects IP loss after host sleep/wake and restarts `systemd-networkd` to recover; `isx` only supplies the per-branch address.

**Machine-id**: Every instance has an `/etc/machine-id` of its own (#1305).
A copy carries its source's, and without this every branch of a template would be one host to the journal, to a DHCP server's DUID and to anything derived with `systemd-id128`.
`BranchFlow` pushes a stopped container branch a fresh ID (`MachineId.giveOwn`) right after the copy, a random v4 UUID without dashes, as systemd writes it.
A failed push only warns: the branch works, and shares its source's ID unless that was cleared.
A VM takes its ID from its SMBIOS UUID instead, at every boot (#1483).
systemd does that in a VM (KVM, and QEMU under TCG) whenever `/etc/machine-id` is empty, and the VM image keeps it empty: it ships the file empty and masks `systemd-machine-id-commit`, so the ID systemd mounts over the file is never written to disk.
That also rests on the root being read-only when PID 1 sets the ID up (the kernel's built-in `ro`): on a writable root PID 1 writes the ID straight into the file, and every copy would share it again.
The VM kernel has DMI for it (`appliance/kernel/vm.config`); without DMI systemd falls back to a random ID.
Incus makes a new `volatile.uuid` for every copy and keeps it across restarts, so each VM's ID is its own and stable, whatever it was copied from (a branch that has booted too) and however.
An instance that keeps its `volatile.uuid` (restored from a backup or an export beside the original) keeps the original's ID too, as it would any disk image of the same machine.
`appliance/test-vm-kernel.sh` boots the kernel with a new SMBIOS UUID each time and checks the guest's ID is that UUID, still mounted over the file rather than written to it.
**Why not a fresh ID and a reboot at a VM branch's first start**: a stopped VM takes no file push, and the guest runs nothing before PID 1 reads the file, so isx could only write a new ID once the agent answers and then reboot.
That adds a second boot (about 12 s under nested KVM, where a VM branch takes about 15 s), needs a stamp saying which sources were cleared and a pending mark for `--no-start` and for VMs started outside isx, and the first boot still sends DHCP and writes the journal under the source's ID.
A per-branch `systemd.machine_id=` would need no reboot, but this kernel's command line is built in; `systemd.machine_id=firmware` and the `system.machine_id` credential apply only while the file is empty.
**Why not the same for containers**: a container's systemd sees no per-copy UUID (PID 1 gets only `container=lxc` and its credentials directory), so isx would still have to make the ID up, and the push into the stopped copy costs about 15 ms and no boot.
Every template build and project build still empties the file in the guest before its last stop (`BuildAccounts.clearGuestProofs`), for a container branch whose push fails, and for VM images that commit the ID: those from before this, and the stock-based `-vm.tar.xz` for older isx.
Their branches of a template get an ID of their own (random without DMI), but a VM branched from another branch or a kept failed build still shares its source's until its template is rebuilt on the current image.
It unmounts the file first, since a transient ID systemd never committed is mounted over the one on disk, and is best effort like the push: a template that keeps its ID still works.
An empty file boots as any other boot.
`uninitialized`, the other value machine-id(5) offers for images, makes the boot a first boot.
On Fedora a first boot runs a full `preset-all`, which disables every unit the image and the template build enabled against Fedora's presets: `systemd-networkd`, its sockets, `systemd-networkd-wait-online` and the image's network watchdog timer (checked by booting the base image's rootfs with each value).
So `ConditionFirstBoot` units do not run again in a branch; on the base image those are only `systemd-firstboot` and `systemd-homed-firstboot`, which it masks, and `first-boot-complete.target`.

**VM deferred file pushes**: File push to a stopped VM is not possible (it requires the running `incus-agent` inside the VM).
For VMs, `BranchFlow` (behind both `isx branch` and the TUI) skips pre-start file pushes (network config, SSH keys, terminfo) and instead call `InstanceNetwork.pushDeferredVmFiles()` after starting the VM and waiting for the agent to become ready.
The network config (IP, gateway and the NIC's MAC address) is read from one instance GET at push time.
A VM's file matches its NIC by `PermanentMACAddress=` (the device's `hwaddr`, else `volatile.<nic>.hwaddr`), never by name -- the permanent address, so a VLAN or bridge built on the NIC, which takes over its MAC, does not match too.
Incus renames a container's NIC to the device's `name` (`eth0`).
But a VM guest keeps its kernel's predictable name (`enp5s0` on Incus's PCIe layout).
So the `Name=eth0` file a VM used to get matched nothing and the VM ran on the base image's own DHCP config (#997).
That DHCP config still covers the first boot -- Incus's DHCP server hands out the NIC's `ipv4.address`, so the address is the same -- and the pushed file takes over from the next one.
A VM whose MAC cannot be read keeps DHCP rather than get a file matching no link or every link (a nested `docker0` included).
Because the file now applies, a stale-subnet repair must reach the guest too, or the VM would come up on its old address, which IP filtering drops.
The repair marks the VM `network-push-pending` in the same config write.
`isx init` and `isx doctor` (`migrateAllInstancesToNewSubnet`) cannot repair a running instance in place.
Incus validates a running instance's NIC update against the address it holds now, which the subnet change left off the bridge's subnet.
So it refuses the very update that would move it (#1009).
They stop it, warning that they do, move it, and start it again through `startForUse` (new secret, host devices); a container boots its new file, and a VM gets its file once its agent answers.
A guest that ignores the shutdown is never forced, and a frozen instance is not stopped (a stop waits on a guest that cannot answer).
Both are left as they were, with a warning to repair them again once stopped or resumed.
Any other VM gets it at its next `isx shell` or TUI shell.
`ensureReady` reads the mark from the same instance GET that gives it the status, and pushes whatever the status, since a VM started outside isx after the repair (`incus start`, autostart after a host reboot) is already running and has booted its old file.
A VM branched with `--no-start` owes its file the same way, since the push after start never ran.
`configureBranch` marks it (`BranchSettings.startsNow` false) in its one write, the one that claims its address.
So the mark costs no request.
Its first start through `ensureReady` -- `isx shell`, `isx run`, the TUI's shell, or MCP `start_instance` (`InstancePrep.prepare`) -- delivers the file, as for a repair (a start that does not go through it, such as `isx project update`'s bare `incus.start` or a plain `incus start`, boots it on DHCP with the same address until the next one that does).
Without it such a VM stayed on DHCP for good, with the lease-expiry-over-sleep/wake problem static addresses exist to avoid (#1004).
A started branch is not marked: it gets its file right after the start, and marking it would cost a write to clear.
The mark is not a reassignment, so it does not bring back the "Static IP mismatch" banner, which only `fixStaticIpIfNeeded`'s actual reassignment shows.
The mark clears once the file is pushed and `networkctl reload` succeeded -- a failed reload leaves the guest on its dropped address, so it stays owed and the user is told -- or when there is no file to push (no readable MAC: DHCP covers the VM).
A copy drops it with `static-ip`, since it is owed to the source's guest.
The reload needs no interface name either: it re-reads the files and reconfigures every link whose file changed.
For the same reason the post-start setup script waits for the instance's assigned address on any link (`RuntimeSetup.addressUpCheck`) rather than for an address on `eth0` (with no recorded address, for a default route, which a nested `docker0` does not add).
On a VM that wait never succeeded.
Every VM branch spent both of `pollUntilReady`'s runs (~35 s) on it before warning that setup may not be complete.
Waiting for the assigned address rather than any address also keeps a nested bridge that comes up first from passing for the instance's network.

The TUI branch modal supports:
- Custom name
- GUI and audio passthrough (Wayland + PipeWire + GPU)
- Network mode selection (full internet / proxy-only / airgapped) via three-state radio
- Inbox mount (read-only host directory for sharing files into the container)
- VM resource limits (CPU, memory, disk)

The modal only collects inputs.
The branch itself is made by `BranchFlow`, the same `preflight()`/`create()` as `isx branch`.
So a TUI branch gets the template's and source's account selection, the proxy refresh before start, and the CA, `resolv.conf` and identity repairs after it.
It used to be a hand-kept copy of the flow that skipped all of those, so a branch reusing a destroyed instance's static IP could be served that instance's credential account (#800).
Its initial values come from `BranchFlow.defaultsFor()` too -- the one rule `create()` applies to whatever a request leaves null: GUI and KVM on when the source's definition sets `gui: true` / `type: kvm` or the source itself has GUI or KVM (GUI only for a container, since passthrough's GPU device would keep a VM from starting; only from a Wayland session, by the checks `configureGui` makes; and never from a project-local definition or a source built with one, since GUI hands over the host's GPU and its Wayland and audio sockets.
Where the source asks for GUI but does not get it by default, a note says why -- printed by `create()` with the branch's progress, for the dialog too, which leaves an untouched GUI box to the default -- rather than errors; `Request.defaults()`, `isx mcp`'s request, pins GUI off whatever the template says, which `BranchDefaultsTest` holds), and the adaptive CPU, memory and disk limits for its machine type, worked out only when asked for (on macOS the memory default forks `sysctl`).
MCP control follows the same rule from the definition's `coordinator:` alone (#1812), since no copy inherits the grant, and `Request.defaults()` pins it off too.
The dialog used to compute its own, and its KVM rule already differed from the CLI's, which ignored the definition (#869).
For KVM, "the source itself" means a template's (built or project) `instance-mode: kvm`, but any other source's own `kvm-enabled` stamp: a branch inherits its template's `instance-mode` even when made with `--no-kvm`, which would otherwise hand `/dev/kvm` back to that branch's own branches (#1034).
`create()` takes those defaults, its machine type and the copy plan from the source read `preflight()` already made (`Preflight.sourceInstance`), and the dialog reads the source once for its defaults and account rows.
The action the new branch's shell opens with is shared the same way, `ActionResolver.defaultCommandForBranch` from that same read (#868).
The dialog used to resolve it by hand, without feature gates, transitive dependencies or `expand: repos`, and with three reads of its own.
Its reference comes from the current YAML, so changing it needs no rebuild.
But it is matched against the tools the source was built with (`BUILD_SOURCE`), which fails safe.
A tool added to the YAML and not yet built gives a plain shell rather than a launch of a binary that is not there.

## Why a branch is configured in one write

Between the copy and the start, a branch used to change the instance's settings in nine separate writes: resource limits and the root disk size, the proxy-only keys, the NIC address, IP spoofing protection, the static IP metadata, type/parent/created, the account pins, and the removal of inherited KVM passthrough (a full PUT even when there was nothing to remove).
Each step did its own read-modify-write, and each write costs Incus ~11-13 ms on btrfs, mostly rewriting the instance's backup file (`UpdateInstanceBackupFile`) -- ~110 ms of the ~250 ms before start (#804).
The same write costs 6-10 ms inside the macOS appliance; the vsock tunnel adds about a tenth of a millisecond to it (see docs/dev/PERFORMANCE-NOTES.md "CLI latency on macOS").

`InstanceLifecycle.configureBranch()`, shared by `isx branch` and the TUI, reads the instance once and collects every change into an `InstanceUpdate` (config sets and unsets, device properties, device removals), which `IncusClient.update()` sends as **one PATCH**.
PATCH cannot remove a device, so when the copy inherited KVM devices the write is **one PUT** of the whole instance carrying all the other changes as well.
A removal of a device the instance does not declare is dropped rather than forcing that PUT (which also makes `devicesRemoveAll` of absent devices a read only).
Devices are sent complete -- their expanded config with the new properties merged in -- so a profile device such as `root` or `eth0` is overridden, not replaced by a fragment Incus rejects.
`InstanceLifecycleRequestBudgetTest` pins the one write.

One write cannot say which of its settings Incus refused, and the old separate writes existed partly to keep two apart: failing to pin the address is fatal, failing to enable `security.ipv4_filtering` only warns.
Incus rolls a refused write back whole, so on failure the write is retried once without filtering: failure surfaces the real error.
Success does not prove filtering was the problem -- the first failure may have been transient -- so filtering is then enabled on its own with `applyIpFiltering()`, which warns as before if Incus refuses it.
The source address is what identifies an instance to the proxy, so a first failure must not quietly leave the branch without it.
Both extra writes happen only on the failure path.

Airgap rides in the same write: it masks each network device with `type: none` (`InstanceUpdate.replaceDevice`), which a PATCH can carry since it replaces a device whole.
Branching with network from an airgapped instance reads the profiles and writes their network devices over the masks airgap stamped, in that same write.
Enabling GUI or KVM passthrough and host resources still add their own devices afterwards.

## Why nothing is pushed into an instance just before it starts

`bench/trace-branch.sh` showed a full second of every 2.2 s branch spent idle inside Incus's start operation, right after it logged "Stopping forkfile".
Forkfile is the helper Incus runs to serve file pushes to a stopped container.
On start, Incus sends it SIGINT and waits for it to exit; forkfile exits once no transfer is open, but it re-checks only **once a second** (`cmd/incusd/main_forkfile.go`, Incus 6.23).
A transfer still counts as open until forkfile has finished `syncfs` on the root filesystem, which can outlast the HTTP response to the push.
So a start issued immediately after a push races that flush, and losing costs exactly one second (measured: 1149 ms against 142 ms with a 200 ms pause, same host).
isx pushed terminfo, and for SSH-capable templates the authorized keys, 1 ms before every branch start.

Both now travel inside the post-start setup script as heredocs (`buildSetupScript`), which costs no extra request, and `RuntimeSetup.prefetchAndStart()` -- shared by `isx branch` and the TUI -- pushes nothing between reading the config and starting.
Two related savings on the same path: `GuiPassthrough.removeGui()` returns after one read when the instance has no GUI state (it used to rewrite devices and config and push two empty files on every branch), and the setup script polls for the network address every 50 ms rather than every 0.5 s, which was ~400 ms of idle time per branch.

Three kinds of file still go in before the start, because they must be in place at boot: a container's own `/etc/machine-id`, the static `.network` file, and the Wayland profile.d/tmpfiles.d files of a `--gui` branch (or the empty files that clear them, when a branch drops GUI state it inherited).
All are pushed well before the start.
The machine-id goes in first, right after the copy.
GUI setup follows it, and `configureBranch()` pushes the `.network` file just before its combined settings write, leaving that write, host integration and the runtime prefetch between it and the start.
That has been enough to finish the flush; if a trace ever shows the one-second gap again, the `.network` push is the next candidate.

## Resource Limits (Adaptive)

Detected at branch time from host resources (`ResourceLimits`):

- **CPU**: VMs get `available_cores - 2` (host keeps 2 cores), no more than 8, and on a hybrid host no more than its top tier's physical cores, minimum 1 (#1238, see docs/dev/PERFORMANCE-NOTES.md "VM boot: where the wait goes"); containers are not pinned
- **Memory**: containers 60% of total RAM; VMs 25% of total RAM, capped at 16 GiB, at least 4 GiB (but never more than the container default, on hosts too small for that floor)
- **Disk**: 100GB root disk (a ceiling: CoW storage is thin-provisioned)

Overridable with `isx branch --cpu/--memory/--disk` and via the TUI branch modal (for VMs, all three fields are shown).

**Why VMs get a separate, smaller memory default.**
`limits.memory` means two different things.
On a container it is a cgroup ceiling: nothing is reserved, and 60% per container just stops one runaway from taking the host down.
On a VM it is the guest's RAM size.
QEMU allocates lazily, but the guest kernel treats all of it as its own and grows its page cache into it, so after one large build a VM holds most of its `limits.memory` on the host.
Two VMs at 60% each overcommit the host.
Idle vCPUs cost almost nothing, so the CPU default is not held to the same standard.

**Free page reporting.**
VMs also get virtio-balloon free page reporting, set through `raw.qemu.conf` (`[device "qemu_balloon"] free-page-reporting = "on"`, which Incus merges into its own balloon device).
Without it QEMU keeps every page the guest ever touched until the VM stops.
With it, pages the guest frees are returned to the host, so memory freed after a build is given back instead of lingering until shutdown.
Page cache still counts as used, so this complements the smaller default rather than replacing it.
`InstanceLifecycle.enableFreePageReporting()` applies it at VM build and at derive (whose parent may predate it).
At branch (whose template may predate it) `configureBranch()` adds it to its one write, at no extra request.
A `raw.qemu.conf` someone else set is left alone, because a merge that breaks the QEMU config leaves a VM that won't start.
QEMU rejects unknown device properties, so this needs QEMU 5.1 or later.

## Container Configuration

**Capabilities**: `lxc.cap.drop =` (don't drop any — the container is the security boundary).

**Host sysctl relaxation** (`/etc/sysctl.d/99-incus-spawn.conf`, applied by `isx init`):
- `kernel.perf_event_paranoid = -1` — unrestricted perf profiling (kernel-inclusive sampling, hardware counters where available)

**Kernel same-page merging** (`/etc/tmpfiles.d/incus-spawn-ksm.conf`, applied by `isx init` on Linux): sets `/sys/kernel/mm/ksm/run` to 1.
QEMU marks guest RAM `MADV_MERGEABLE` by default, and VMs branched from one template hold many identical pages (kernel, JDK, libraries, page cache of the same files), so KSM lets them share one copy.
It costs some host CPU for scanning, bounded by the kernel defaults (`pages_to_scan`, `sleep_millisecs`, and `smart_scan` on 6.7+), which isx does not tune.
Containers are unaffected, because their processes do not opt in.
KSM breaks transparent huge pages it merges into 4K pages, trading some TLB efficiency for memory on the shared pages only.
Init leaves KSM alone when `ksmtuned` is active, since that daemon owns the knob.

These are host-wide because they are not namespace-aware.
`kernel.dmesg_restrict = 0` and `kernel.yama.ptrace_scope = 0` hold by default (`dmesg_restrict`'s default is 0; Yama is not compiled into the appliance kernel, so ptrace is unrestricted).
`ping_group_range` is wide in modern kernels and further ensured by not dropping `CAP_NET_RAW`.

**DNS**: systemd-resolved disabled, `/etc/resolv.conf` points at Incus bridge gateway (`incusbr0`), immutable via `chattr +i`.

**Terminal title**: The host terminal title is set to `isx:<containername>` during `isx shell` sessions (via OSC escape sequences) and restored on exit.
Inside containers, `PROMPT_COMMAND` in `.bashrc` overrides Fedora's default title-setting to maintain the `isx:<hostname>` title.
Claude Code's built-in terminal title override is also suppressed so the container name stays visible.

**Interactive shells need a terminal, and only a lost session is retried.**
The PTY session reads keystrokes from `/dev/tty` and writes to stdout, so with no terminal there is no session to have.
`IncusClient.interactiveShell()` refuses up front (`hasTerminal()`: stdin and stdout a terminal, `Console.isTerminal()`) with a `NoTerminalForShellException`.
`execPty` opens `/dev/tty` before it posts the exec, so a missing controlling terminal is refused the same way rather than after a process was started in the guest.
The reconnect loop retries only an I/O failure of a session that could run; it never retries `NoTerminalForShellException`.
Before #1027 a script or agent running `isx branch` hit the failed `/dev/tty` open after the exec, which read as a lost connection: ten reconnects and ~76 s, each starting another exec, then `PTY exec failed`.
Since the branch itself had succeeded, `isx branch` now reports it, says how to connect, and exits 0 without a shell; `isx shell` refuses before starting the instance, with exit status 1.
Requiring stdout too means `isx shell <name> | tee log` is refused, where it used to work: it is the convention `confirmDestructive` already uses, and a shell whose output is not a terminal cannot be drawn correctly anyway.

**A shell names the session it enters** (#2059).
With zmx or tmux auto-attaching, every shell entered the session `isx`, so a second terminal mirrored the first.
`ShellPrep` now carries a `ShellSession`: the default `isx`, a named one (`isx shell --session <name>`), a new one (the TUI's `n`), or none (`--no-attach`); `InteractiveShell.innerScript` turns it into the login's script.
A new session is named in the guest, by the first free number from 2 that `zmx list --short` or `tmux has-session` does not hold, so naming it costs no round trip.
The shell keeps the number it chose in the guest's runtime directory under a key made once per shell, so a reconnect, which runs the same script, re-enters that session rather than taking the next number and leaving it behind.
Two `n` presses at the same moment can still pick the same number; that race was judged too rare to lock against.
A session asked for is entered even when the host terminal is in tmux, where the default one is skipped, since the user asked for it.
Its multiplexer is the one that auto-attaches, else zmx, else tmux; with neither, the plain login shell is independent already.
A template's `shell-command` runs in the session asked for, in zmx as before, and outside any session with `--no-attach`.
A name is held to `ShellSession.NAME` (1 to 32 of `[A-Za-z0-9_-]`, never a leading `-`, which zmx and tmux would read as an option) and checked before anything reaches Incus, so the script needs no quoting and the host link (`isx-<name>.<session>`, design/host-resources.md) stays unambiguous.
Alternatives: only `--no-attach` loses persistence, the reason zmx is installed; a host-chosen name needs a second path where the host cannot see the sockets (macOS, tmux); Ctrl or Alt+F2 are unreliable across terminals, while `n` already means "new" on a template row.

**Shell status bar** (feature-flagged as `shell-status-bar`): `ShellStatusBar` pins a two-line bar at the bottom of the terminal during `isx shell` sessions, showing instance name, template, IP and network mode.
F12 opens a quick-action menu populated from tool actions with `shell_menu: true` and a `shortcut` -- only `url` actions, because the menu runs them while the session owns the terminal in raw mode, and a `command` action's process would share that tty with the shell (fighting it for keystrokes and writing past the bar).
Actions run on a virtual thread, so a slow URL launcher never holds up keystrokes, and through `ToolAction.executeWithoutPrompting`, which returns an error where `execute` would ask the user something (VS Code's missing Remote-SSH extension) and by default refuses, so a new action is safe until it says it never prompts.
A prompt would be drawn over the session and its Enter would go to the shell.
The TUI's F9 menu runs its in-process actions (url, clipboard) through it too, since the TUI still owns the terminal there (#982); only `isx run` and a `command` action deferred until the TUI has quit call `execute`, and may prompt.
A bar with an empty menu does not intercept F12 at all, so programs that bind it keep it.
The menu is a `ShellMenu` (actions plus the `ActionContext` they run against, whose `parent` is also the bar's template label), resolved by `ActionResolver.shellMenu` from a single context, which `buildActionContext` builds from one instance read, plus `/state` for the address of a running or frozen guest (#979, pinned in `ActionResolverRequestBudgetTest`).
An `expand: repos` action is offered once, for the repo the session's workdir is in (or below).
Each entry needs its own one-key shortcut (printable ASCII, case-insensitively unique).
`ShellMenu.of` drops, with a `Warnings.warn`, an action the menu could not dispatch or one whose key an earlier action already takes.
So what the menu shows, what a key runs and whether F12 is intercepted all agree.
With the flag off every caller passes `ShellMenu.NONE` and makes no extra Incus request -- the bar is opt-in, so its cost must be too (pinned in `ShellMenuTest`).
The bar uses a scroll region (`\e[1;{h-2}r`) to confine child output above it, and reports `effectiveHeight()` (real height minus bar lines) as the PTY height so the child never draws into the bar area.
Every paint -- after a child write, and for the menu, a flash or a resize alike -- goes through one method that draws only where the child's output stands between escape sequences and characters.
A frame can end inside a CSI, an OSC or a UTF-8 character, and writing there would cut it short.
So `OutputBoundary` tracks just enough of the stream to know, and a paint asked for mid-sequence happens after the next frame that ends on a boundary -- except that a paint the user is waiting for (the menu, a hint, an action's result) is drawn anyway after 150 ms, since a child gone quiet inside a sequence sends no next frame and the parser is already swallowing keys for the menu.
A resize arriving mid-sequence drops its clear, which would land after the child's SIGWINCH redraw and blank it.
`OutputBoundary` follows the terminal on CAN/SUB (abort) and on an ESC that restarts a sequence, and bar text is cut to display columns (wide characters take two), so a row never wraps.
All output goes through a `BufferedOutputStream` so child data, scroll region fixup and bar repaint reach the terminal in one `write()` syscall (one rendering frame), avoiding visible intermediate states.
Cursor position is preserved across the fixup with a single DECSC/DECRC pair wrapping the scroll region and bar rendering.
`emitLine` does not save/restore the cursor itself, since DECSC is a single slot and a nested save would clobber the outer one.
`cleanup()` (on exit and in the SIGTERM shutdown hook) closes the bar.
It erases both bar rows and resets the scroll region between a cursor save and restore (after a CAN if the child left a sequence open), so the host prompt follows the session's last output.
From then on nothing draws until the next `setup()` -- not a frame still in flight when SIGTERM's hook runs, not a flash timer, not an action that finishes after the session, which in the TUI path would draw over the TUI.
Known limits of the scroll-region approach: the repaint restores the bar's own scroll region over any a child program set (vim splits, less, tmux), its DECSC overwrites the child's one saved cursor, and setup and resize clear the screen.
The bar stays on the main screen rather than the alternate screen, which would cost the session its scrollback and erase its output on exit (measured in #888).
`EscapeSequenceParser` handles F12 key detection (`\e[24~`) on the input side.
Normal mode passes non-F12 bytes through -- a bare Esc at the end of a read immediately, since terminals write a key sequence whole and holding it back would delay vim's Esc by a keystroke, and a CSI too long to be F12 (a mouse report) untouched.
Menu mode returns shortcut keys and swallows CSI and SS3 (`\eOA`) sequences whole.
An Esc in menu mode consumes only itself, so a key typed right after it (or Alt+key) reaches the shell once the menu closes.
`OutputBoundary` lets BEL end only an OSC: DCS, APC, PM and SOS end at ST.
Each result reports how many bytes it consumed, and the caller feeds the rest of the read again.

## Filesystem Isolation
- Inbox mount is strictly read-only
- Host resources default to read-only; overlay mode provides an ephemeral writable layer but the host directory is never modified
- Clone filesystems are independent CoW copies — changes in one clone don't affect others or the template image

## SSH Key Management

incus-spawn manages a dedicated SSH key pair, per-instance SSH configuration and each instance's pinned host keys, so tools like JetBrains Gateway, `ssh` and herdr work without passphrase prompts or host key warnings:

```
~/.config/incus-spawn/ssh/
    id_ed25519          # managed private key (mode 600, no passphrase)
    id_ed25519.pub      # managed public key
    config              # per-instance Host blocks (managed by isx)
    known_hosts         # each instance's pinned host keys (managed by isx)
```

**Lifecycle:**

1. **`isx init`** generates the ed25519 key pair (via `ssh-keygen`) and prepends an `Include ~/.config/incus-spawn/ssh/config` directive to `~/.ssh/config` (idempotent, resolves symlinks for dotfile managers).
   The key pair is also created lazily at first branch for users upgrading from older versions.
2. **`isx branch`** (via `RuntimeSetup.injectSshKeyIfAvailable`): injects the managed public key and the user's first default key (`~/.ssh/id_ed25519.pub`, `id_ecdsa.pub` or `id_rsa.pub`) into the container's `authorized_keys`.
   The setup script writes that file whole as agentuser through `runuser` (`RuntimeSetup.WRITE_KEYS`), never as root (#1698).
   A branch of an instance copies the `~/.ssh` agentuser owns, so a symlink planted there must not steer a root write in the copy.
   It runs after the home's chown and under `env -i`, since agentuser can read the environment of its own processes.
   The secret delivery before it already unsets what it delivered, so `env -i` keeps out whatever else the exec carries.
   A file agentuser does not own, which the root write used to repair, is removed first, as agentuser may in its own `~/.ssh`; a root-owned `~/.ssh` itself is not repaired.
   Then, once the instance is up (`RuntimeSetup.configureSshHostEntry`), it writes a `Host <instance-name>` block to the managed config with `Hostname <address>`, `ProxyCommand isx ssh-proxy <instance-name>`, `User agentuser`, `IdentityFile`, `IdentitiesOnly yes`, `HostKeyAlias <instance-name>`, `HashKnownHosts no`, `StrictHostKeyChecking no` and `UserKnownHostsFile` naming the managed `known_hosts` by its absolute path.
   Before the block, it pins the host keys the setup script printed (`HostKeys.GUEST_SCRIPT`, on the answer `pollUntilReadyOutput` returns), so the setup exec carries them (#1863).
   The address in both is the static one `configureBranch` stamped, as on a start, so this costs no Incus request.
   It is never one the guest reports: the guest owns its interfaces, and pinning an address takes it off every other instance's line.
   An airgapped branch has no address, so its block has no `Hostname` and its keys are pinned under its name alone.
   After this, `ssh <instance-name>` just works, and so does a client that forces strict host key checking.
   An instance added to herdr also gets a `Host <instance-name>.start.isx` block (`SshKeyManager.addStartEntry`), the same but for `ProxyCommand isx ssh-proxy --start` and no `Hostname`, so herdr's connection starts it when it is stopped (#2038).
3. **Every later start for use** (`InstanceLifecycle.startForUse`, behind `isx shell`, `isx run` and the TUI) does the same for an instance with SSH, so a `--no-start` branch is reachable after its first start (#1640).
   The keys ride in the readiness probe's guest script (`RuntimeSetup.sshStartScript`), which adds only the ones `authorized_keys` lacks, so a key added inside the box survives.
   A TUI restart (`restartForUse`) adds them to its probe the same way, from its listing.
   A key counts as present by its base64 blob, so a copy the user restricted with `from="..."` is not added again unrestricted.
   That script writes as agentuser through `runuser`, never as root, since agentuser owns `~/.ssh` and a symlink there must not steer a root write.
   Root first only reads the file, so the usual start, with every key in place, skips the user switch.
   That read is bounded to two seconds, and a timeout counts as a key missing, since agentuser controls the file and the probe exec has no time limit of its own.
   It is skipped when `~/.ssh` or the file is a symlink, or the file is not a regular one.
   agentuser can still swap one in between that test and the read, which then only tells root whether another file holds a public key, within the same bound.
   A commented-out line does not count as the key.
   The probe also prints sshd's host keys, which `waitForReady` returns as its answer, and the start pins them before it checks the block (#1863).
   The Host block is written only when the managed config has none, one with another `Hostname`, or one from before host keys were pinned, from the stamped static address.
   The pin is rewritten only when it differs from what the probe reported.
   So a start pays no Incus request and no write for either.
   A TUI restart pins from its probe the same way.
4. **`isx destroy`** (and TUI delete): removes the Host block, and the start alias's, from the managed config, and the instance's lines from the managed `known_hosts`.
   A rename removes them under the old name; the stopped instance is pinned under the new one on its next start.

**Design decisions:**

- **`ProxyCommand` through the exec API** (a1086a1e, #128): `isx ssh-proxy` pipes the connection to `nc localhost 22` in the instance, so a client needs no route to the instance's address.
  That is what makes it work on macOS, where the address is inside the appliance VM.
  Its throughput is the exec relay's: see design/macos-and-vms.md "A bulk exec stream is framed large and read ahead".
  ssh shows a ProxyCommand's stderr on the terminal untouched, and the guest decides it, so it goes through `GuestStderr`, as `isx git-remote-helper`'s does.
- **The ProxyCommand waits for sshd, the start does not** (#1699): a start returns about a second before sshd listens, so `ssh` straight after `isx run` was refused.
  The guest side first polls `/proc/net/tcp` for a socket listening on port 22, and only then runs `nc`.
  It polls only while `systemctl is-system-running` says the guest is still starting, and for at most 20 s by the wall clock.
  After boot, a stopped or failed sshd will not come, so the refusal is reported at once, as before.
  Waiting on the sshd tool's `ready:` check at every start was the alternative, but it would charge that second to starts that never use SSH.
  Retrying the exec after a refusal cannot work, since ssh has already written its banner into the first one's stdin.
  The probe reads the socket table rather than connecting, so a ready instance pays one `grep` and sshd logs nothing.
- **`Hostname` as a label** (4e271acb, #211): JetBrains Gateway resolves the host before it runs the `ProxyCommand`, and without the line it reported the instance name as not resolvable.
  It never dials that address: Gateway 2026.2 runs the `ProxyCommand` on macOS too, and logs the connection as `<Hostname>:<Port>` (#1600).
- **Host keys pinned by the instance's name** (#1863): `StrictHostKeyChecking no` alone leaves a client that forces `yes` on its command line with nothing to check.
  herdr 0.9.5 does, and its `-o` beats the config file, so with `UserKnownHostsFile /dev/null` it could never connect.
  The block keeps `no`, so plain `ssh` and Gateway behave as before and isx never prompts, and points at a managed `known_hosts` that holds the key.
  `HostKeyAlias <instance-name>` looks the key up by the name routing goes by.
  An address alone is no identity: `StaticIpAllocator` recycles addresses, and every branch of a template shares the host keys its build generated (`ssh-keygen -A` in the `sshd` tool), so an address-keyed pin would eventually report `REMOTE HOST IDENTIFICATION HAS CHANGED` (#1630 proposed that and was declined).
  Each line also names the address, `<name>,<address> <type> <key> isx:<name>`, for a client that ignores `HostKeyAlias` and dials `Hostname`; pinning an address takes it off any other instance's line, which keeps its name.
  Every key type sshd has is pinned, so no `HostKeyAlgorithms` restriction is needed.
  `test-ssh-host-keys.sh` in `isx-integration-tests-native` connects with herdr's options against a real Incus and sshd, by name and by address, after a key change and a start, and checks destroy removes the pins.
  The keys come from the guest on the readiness exec a start already makes, never from a build: a template rebuild changes nothing for existing branches, and a key regenerated inside an instance is pinned again on its next start.
  The guest is not trusted with the host's files: `HostKeys.parse` takes only a checked type and a base64 blob that decodes to a key of that type, at most eight of them, and isx writes the host field itself.
  A pin that has gone stale shows ssh's warning to plain `ssh`, which still logs in, and is replaced on the next start.
  Until then that `ssh` also turns off port, agent and X11 forwarding, which `/dev/null` never did, so an IDE's forwards stop working.
  A sibling branch of the same template passes another's pin, since they share keys; the `ProxyCommand` still reaches the instance by name through Incus, which authenticates the channel.
  An earlier design kept a managed `known_hosts` and copied entries into `~/.ssh/known_hosts`, on the belief that Gateway honoured neither `Include` nor `UserKnownHostsFile`; a1086a1e removed it, and Gateway 2026.2 honours both.
- **Both keys injected**: the managed key (passphraseless) ensures tools always work, while the user's personal key is also injected so interactive SSH sessions can use their preferred key.
- **Atomic writes**: config updates use temp-file-then-rename with restrictive permissions to avoid partial writes, and a config that would not change is not rewritten.
  A rename does not stop two isx processes from each rewriting the managed config from the same old copy, so a Host block is added or removed under a `HostLock` (`ssh-config.lock`), held from the read to the rename (#1632).
- **Non-fatal**: SSH setup failures never block init or branching.
  `SshKeyManager` reports them through `Warnings`; the fallback is a manual `ssh agentuser@<ip>`.

## Remote IDE Access

The built-in `idea-backend` tool installs the JetBrains IntelliJ IDEA remote development backend and registers the container for JetBrains Gateway discovery.
It uses the `requires` field to automatically pull in the `sshd` tool, which configures an OpenSSH server with pubkey-only authentication.
SSH key injection is handled automatically by the SSH key management subsystem (see above), so Gateway connections work without any manual key setup.
This enables IDE-based development inside containers: Gateway connects over SSH and runs the IntelliJ backend process inside the container, with the full project available.

The Gateway action's URL names the instance (`host=${name}`), never its address.
Gateway matches that host against the `Host` lines of `~/.ssh/config`, which name instances, so it takes `User`, `IdentityFile` and the `ProxyCommand` from the instance's block.
A host that names no block, such as the instance's IP typed into Gateway's connection dialog, gets none of them: Gateway dials it directly, which on macOS times out inside the appliance VM (#1600).

Its `index-mcp` parameter (default `false`) installs the third-party IDE Index MCP Server plugin and registers it in Claude Code at user scope as `intellij-index` (`http://127.0.0.1:29170/index-mcp/streamable-http`).
It is opt-in because it is a third-party download every build would otherwise depend on: the Marketplace's build-matched URL it first used began answering 404, which failed every `idea-backend` build.
The plugin comes from the author's GitHub release, pinned to the sha256 GitHub publishes for that asset; the Marketplace publishes no checksum, and its copy is re-signed, so it differs.
`mcp-plugin.xml` pins `serverPort` to the port the registration names, so the two agree by construction rather than through the plugin's per-IDE default.
The registration is a build step, not start-time reconciliation like `McpClientRegistration`: `claude mcp add` needs Claude Code already installed (a parent template or earlier in the tool list), and the step fails the build rather than skip it, since `run_as_user` output is discarded on success and a skip would go unseen.
`ClaudeSetup` lays its own keys of `~/.claude.json` over the file a parent left rather than rewriting it, so a child template that reconfigures `claude` (a different `model`, say) keeps the registration (#1638).
`IdeaBackendIndexMcpTest` runs the substituted step against stub commands, for both values of the parameter.

## GUI and Audio Passthrough

Enables GUI applications and audio inside containers.
A branch gets it with `--gui`, or by default when its template sets `gui: true` (or its source has it) and it is a container branched from a Wayland session; `--no-gui` opts out.
`isx mcp`'s branches never get it.
- GPU device passed through for hardware-accelerated rendering
- The host's Wayland socket and, where present, its PipeWire and PulseAudio sockets are each bind-mounted on their own under `/mnt/host-xdg`, at the path they hold in the host's `XDG_RUNTIME_DIR`
- The whole `XDG_RUNTIME_DIR` is not mounted: it also holds the host user's session D-Bus, podman, ssh-agent and keyring sockets, which the guest, sharing that user's uid, would otherwise reach
- A GUI instance made by an isx before #1521 keeps its whole-directory `xdg-runtime` mount on every start, so its guest still reaches those sockets.
  An affected instance lists an `xdg-runtime` device in `incus config device list <name>`.
  A start does not rewrite it (#1523: a release note, not a start-time repair).
  A branch of it, GUI or not, drops the device (`GUI_DEVICES`), so the remedy is to branch it again and destroy the old instance
- Environment variables set on the instance and written to `/etc/profile.d/wayland.sh` (`WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, toolkit backends, and `PIPEWIRE_REMOTE` / `PULSE_SERVER` for each audio socket mounted, since clients look for theirs in the guest's own `XDG_RUNTIME_DIR`)

## Metadata Tracking

Containers tagged via Incus `user.*` config keys:

```
user.incus-spawn.type=base
user.incus-spawn.profile=tpl-java
user.incus-spawn.parent=tpl-dev
user.incus-spawn.created=2026-04-07
user.incus-spawn.build-version=0.1.11        # isx version that built the template
user.incus-spawn.build-sha=c434ef9           # git commit SHA of isx at build time
user.incus-spawn.definition-sha=a1b2c3d4     # fingerprint of image def + tool defs
user.incus-spawn.ca-fingerprint=AB:CD:EF:... # CA certificate fingerprint
user.incus-spawn.build-source={...}          # (JSON, full image + tool defs for out-of-scope visibility)
user.incus-spawn.network-mode=PROXY_ONLY     # (proxy-only branches only)
user.incus-spawn.proxy-gateway=10.166.11.1   # (proxy-only branches only)
user.incus-spawn.airgap-masked=eth0,ib0      # (airgapped branches only, the devices airgap masked; "," for none)
user.incus-spawn.static-ip=10.166.11.2       # (branches only, assigned at creation)
user.incus-spawn.host-resources=[...]        # (JSON, when host-resources declared)
```

**Metadata outside the fingerprint**: `default-action` changes nothing in the image, so it is left out of `definition-sha` and editing it does not make a template outdated.
Its stamp (`user.incus-spawn.default-action`, which `ActionResolver` falls back to when a layer of the YAML chain is gone and no layer on disk sets one) was then only ever written by a rebuild, and diverged silently from the YAML (#284).
So every `isx build` that returns, successfully or with a failed template build, ends with `syncDefaultActions()`: one `GET /1.0/instances?recursion=1` covers every existing template, and a stamp is written only where it differs from the definition chain.
Because the value is inherited, the guard covers the whole chain, not just the template's own definition: the chain must reach a root in the loaded definitions (with a parent's YAML gone the inherited value is unknown, and the stamp is exactly the fallback for that case), the template's own `definition-sha` must be current, and its `build-source` must record every ancestor with the content and project root it has now.
So an outdated template, or a child built before its parent added a tool, keeps the stamp matching what it has installed until it is rebuilt.
Ancestors are compared by their own definitions, not their tools' YAML: a child built before a parent's tool gained an action can take a `default-action` naming that action, the same ref the YAML-first path already resolves for it.
A project-local definition, parent overrides included, never reaches a template built from elsewhere (see design/host-resources.md "Project-local templates are confined to their project").
The build-source snapshots a build borrows for a template whose YAML is gone are not used, since they can be older than the template they describe.
Hooking each skip branch instead (a parent `buildChain` leaves alone, the untouched templates of `--out-of-sync`) was tried first: it missed `--missing`, the type-change path and templates outside the chain built, and cost a round trip per template.
Folding `default-action` into the fingerprint was rejected: a whole rebuild to change one metadata key.
A layer's `default-action: -` (#2058) is none, nearer than any parent's: the walk stops there, and the build and the sync unset the stamp, so the layer-gone fallback also opens a shell.
It is a `-` rather than a blank value, since a blank value is a mistake the loader warns about rather than a choice.

**Staleness detection**: The TUI uses `build-version`, `definition-sha` and `created` to display staleness indicators next to template names, and `isx templates --format=plain|json` reports the same three as fields.
Both come from `TemplateStaleness.assess` (`cli/.../command/`), which is also what `isx build --out-of-sync` rebuilds on.
It reads the same one instance listing (`InstanceListing.builtTemplates`), so judging staleness costs one request whatever the number of templates (#1142).
A defined template not in the listing is rebuilt.
A listing Incus cannot give is thrown, never read as "nothing is built".
The parents of a named build are checked by `BuildCommand.isImageOutdated` with the same rules, one instance read each.
There, a parent that is not built is not rebuilt, but Incus failing to answer is thrown, never read as current.
Every caller fingerprints a definition's tools with `TemplateStaleness.toolFingerprints`, the build's stamp included.
A tool whose feature is off is left out, since it is not installed.
A tool in a `requires` cycle is resolved from itself, so fingerprinting one template's tools or all of them gives it the same value.
Any one of the three makes a template out of sync (`Staleness.outOfSync()`), which is also the TUI's count for "Rebuild out of sync templates".
`↑` counts since #1130: a child copied from its parent's previous build does not have what the parent has now, and before, nothing short of rebuilding the parent again repaired it.
It applies only to a template copied from its parent, which is one of the same machine type as Incus reports it (`TemplateStaleness.Built.machineType`, so a `--type` override counts).
A VM over a container parent (`tpl-isx-vm` over `tpl-isx`) is built from the definitions alone (`buildChain` skips its parent for the same reason), so its parent's builds are nothing to it.
A change to the parent's definition already reaches it through the descendant cascade.
Counting it would rebuild the VM, for minutes, after every rebuild of its parent.
It stays its own mark so the detail view can say why.
Only `build` acts on it; branch, start and the proxy do not:
- `!` — template was built with a different isx version than the running CLI
- `△` — the image definition or its tool definitions have changed since the last build (fingerprint mismatch)
- `↑` — a parent template was rebuilt more recently than this template

**Build source storage**: `build-source` stores the full image definition hierarchy and tool definitions as JSON.
This allows templates built from definitions that are no longer in scope (e.g. project-local definitions from a different working directory) to still be displayed and rebuilt in the TUI.

## Lifecycle locking

Multiple `isx` processes can modify VM or proxy state concurrently (e.g. `isx vm restart` in one terminal while `ensureRunning()` auto-starts in another).
`VmManager`, `ProxyService` and static IP allocation all guard their critical sections with one `HostLock`: an `fcntl` advisory file lock (`FileChannel.tryLock()`), auto-released on process death, behind a per-path in-process `ReentrantLock`.
The in-process half is what makes it safe for a multi-threaded TUI: a second `tryLock` from the same JVM throws instead of waiting, and closing any channel on the file drops every lock the process holds on it.
Each lock used to be open-coded, and only the newest had that half.
`HostLock` is `AutoCloseable` and not reentrant, so public methods (`start`, `stop`, `restart`, `ensureRunning`, `install`, etc.) acquire it then delegate to private `*Locked()` variants.
A method that calls another mutating method (e.g. `restart` → `stopLocked` + `startLocked`) uses the locked variant.
Lock files live at `~/.local/state/incus-spawn/vm.lock` (VM), `~/.config/incus-spawn/proxy.lock` (proxy) and `~/.cache/incus-spawn/locks/.static-ip.lock`.
A waiter prints one wait message and polls with backoff from 10 ms to 50 ms, and times out after 30 seconds.
The cap is low for fairness, not only speed.
`fcntl` keeps no queue, so whoever polls first after a release wins, and a waiter backed off to half a second kept losing to newcomers polling every 10 ms until it timed out while the lock changed hands all along.
The in-process lock is keyed by the lock file's real path, since `fcntl` locks a file rather than a path spelling.

**Replacing a template that others copy from (#1212).**
A rebuild (`isx build`, `isx project create`) builds under `<name>-rebuilding` and swaps it in only on success, so a failed rebuild keeps the previous template.
Incus cannot rename onto an existing name, and has no atomic swap: the swap is a delete and a rename, and in between the template does not exist.
Reordering the requests doesn't close that gap.
Renaming the old template aside first still leaves a moment with nothing under the name.
A lock that only the swap took would not help either, because readers don't ask for the template once.
`isx branch` checks that it exists, `BranchFlow.preflight` reads it, and the copy request names it, and a gap that falls anywhere among them fails the branch ("does not exist", or a copy whose source vanished mid-request).
So the lock is a read-write one.
`TemplateLock.replace` holds the template's `HostLock` exclusively for the delete and the rename.
Every path that copies from a template holds it shared, from the lookup until the copy is made: `isx branch`, the TUI's branch, `isx mcp`'s `create_instance(template)` and `delegate(template)`, and a build or project create copying its parent.
`isx build`'s check of whether a parent is missing or outdated (`parentNeedsBuild`) holds it and its own parent too, or a parent caught mid-swap would read as missing and be rebuilt a second time, racing the first (and a grandparent caught mid-swap would make the parent look current); so does the chain's lookup before the rebuild confirmation, or a template in the gap would be replaced without asking.
A reader that arrives during the gap waits the two requests out and then finds the new template.
Readers never hold each other off, so concurrent branches of one template, such as a coordinator's fan-out, cost each other nothing; the lock adds a few file syscalls and no Incus request to the branch path.
The lock file is `~/.cache/incus-spawn/locks/templates/<name>.lock`, one per name and never deleted, since deleting it would let two processes lock different files.
Names come from untrusted places (a project-local `name:`, an agent's `template` argument), so only a name Incus would accept for an instance gets a file (any other cannot be a template, and is held by nothing), and `isx mcp` holds only templates the user listed under `templates` in `mcp.yaml`.
`isx branch` cannot do the same.
Whether its `--from` names a template is only known from a lookup, which has to be made under the hold, so a mistyped `--from`, or a branch of a plain instance, leaves a lock file behind.
That is an empty file per name the user typed, against a branch that never has to wait out a swap.
A template's name may be at most 52 characters, since `isx build` and `isx project create` both build it as `<name>-rebuilding` first and Incus allows 63; both refuse a longer one before doing anything (`BuildCommand.reportNameTooLong`).
A process holds one shared `fcntl` lock for all its readers, released with the last of them (`HostLock.acquireShared`), because closing any channel on the file drops every lock the process holds on it.

What it costs.
A branch's readers hold the lock through the whole branch, start included (`BranchFlow.create`), so a swap waits for branches under way, a VM's start among them; its timeout is ten minutes rather than the usual thirty seconds, since timing out fails a build whose work is done.
`fcntl` has no queue, so a steady overlap of readers can delay a swap until they stop.
The lock covers only isx processes of this user on this host: a raw `incus copy`, `sudo isx`, or isx on another host against the same Incus can still meet the gap.
An unusable lock file (a home without working `fcntl` locks) degrades both sides to the in-process lock with one warning, as the static IP claim does, rather than failing builds or branches.

**Post-launch readiness.**
`startLocked()` only spawns the hypervisor process — it cannot detect an immediate crash or a guest that never reaches Incus.
Every code path that launches the VM verifies readiness within the lock scope: `start()` and `restart()` call `awaitReady()` (polling `waitUntilReady(60)` with `BuildOutput` step output); `ensureRunningLocked()` does its own inline wait with `System.err` output and recovery logic.
`startLocked()` returns a `StartResult` enum (`LAUNCHED`/`ALREADY_RUNNING`/`FAILED`) so callers can distinguish a fresh launch (needs readiness wait) from a no-op (VM was already up).
