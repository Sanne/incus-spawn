# Storage and copy-on-write

How the storage pool is backed, and how isx keeps it consistent.

## Storage and COW

Copy-on-write storage is essential for efficient branching.
`isx init` automatically creates a btrfs storage pool (`cow`) if no CoW-capable pool exists.
On a CoW-capable pool (btrfs/zfs/lvm), Incus implements a same-pool `type: copy` as a native snapshot (e.g. `btrfs subvolume snapshot`), so copies are instant CoW clones with no data transfer — there is no need to use the Incus snapshot API explicitly.
Full copies happen only in two cases: (1) there is no CoW pool at all (the `dir` driver uses rsync), or (2) the source instance's root disk is on a different pool than the copy target, forcing a cross-pool migration path.

A CoW pool is **required** for instance creation (`create`) and copying (`copy`/`planCopy`).
`requireCowPool()` throws with a specific diagnostic (pool listing failed vs. no CoW pool found) instead of silently falling back to the default profile's pool, which on a pre-configured Ubuntu system is typically a `dir` pool.
`findCowPool()` (nullable) remains for callers that handle absence gracefully (cleanup, diagnostics, TUI display, image import).
`importImage()` uses `findCowPool()` and sends an `X-Incus-Pool` header when a CoW pool exists, so base images land on the CoW pool when possible — but does not throw when absent, since images are still usable on any pool.

`IncusClient.copy()` follows the source instance's pool: if the source's root disk is on a CoW pool, the copy targets that same pool (guaranteeing same-pool CoW); otherwise it targets the first CoW pool (cross-pool full copy).
`planCopy()` also throws if no CoW pool exists.
`isx branch` and `BuildCommand.buildFromParent()` compute a `CopyPlan` before copying and warn the user when the copy will be a full rsync (with a pointer to `isx doctor` for remediation).
`isx doctor` surfaces three failure modes: no CoW pool at all (FAIL with a Linux remediation to create one), the default profile's root disk pointing to a non-CoW pool (WARN with auto-fix remediation), and instances whose root disk landed on a non-CoW pool (WARN naming the affected instances, with a remediation that moves the stopped ones through `IncusClient.moveToPool`; running ones are only listed).

A common trigger for stale profile state is setting up Incus before `isx init` (e.g. `incus admin init --minimal` on Ubuntu), which creates a `default` dir pool and sets the profile's root disk to it.
`ensureDefaultProfile()` now upgrades the root disk to the CoW pool when it finds this mismatch during init.

Supported CoW drivers: **btrfs**, **zfs**, **lvm**.
If btrfs pool creation fails during init (e.g. unsupported filesystem), the user is warned and can continue with the `dir` driver, but clones will be full copies.

### How the pool is backed: a subvolume on btrfs, a loop file elsewhere (#1656)

`isx init` used to create the pool as `size=100GiB` with no `source`, which Incus backs with a sparse loop file, `/var/lib/incus/disks/cow.img`.
On a btrfs host (Fedora's default, with `compress=zstd:1`) that is btrfs inside a file on btrfs: every instance write is a CoW extent plus metadata in the pool, then a second extent plus extent-tree, csum-tree and commit work on the host, and the file fragments over time.
A compressed file cannot be `nodatacow`, so the outer copy is not avoidable.
Measured on a laptop with 26 instances: about 540 GB/day written while mostly idle, seven eighths of it filesystem work rather than any process's writes.

So when `/var/lib/incus/storage-pools` is on btrfs (`CowPoolBacking.filesystemOf`, from `Files.getFileStore` on its nearest existing ancestor), init creates the pool with `source=/var/lib/incus/storage-pools/cow`.
Incus accepts exactly one source path under its own directory, the pool's mount path, and turns it into a subvolume itself (`btrfs.Create`, "New btrfs subvolume on existing btrfs filesystem"), so isx runs no `btrfs` command and needs no new privilege.
It is what nested Incus in a dev container and the macOS appliance (`incus-spawn-vm-init`) already do.
One filesystem, one set of metadata, and the host's compression applies once.
If the subvolume create fails (a leftover non-empty `storage-pools/cow`, an Incus with another directory), init falls back to the loop file and says doctor will explain the move: a loop on btrfs writes twice, but no CoW pool makes every branch a full copy.
Doctor's "no CoW pool" remediation, where `noCowPoolMsg` sends every user, takes the same path (`CowPoolBacking.create`), and `CowPoolBacking.failureExplanation` names the leftover directory when that is what Incus refused.
Note that `incus admin init --minimal` itself makes such a subvolume pool on a btrfs host; isx's path runs when Incus came up with a `dir` pool, or when the pool predates that.

Elsewhere (ext4, xfs) the loop file stays, as the only option, and is mounted with `btrfs.mount_options=user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1`, the appliance's options and those `docs/dev/MIGRATIONS.md` gives for a loop that stays.
Incus replaces its own default (`user_subvol_rm_allowed`) with whatever is set, so it is repeated.
The 300 s commit is the appliance's trade (`appliance/config.sh`): instances are scratch space, and fsync is still honoured.
Compression happens once, inside the pool: the loop file init makes on a host filesystem other than btrfs compresses, and the fallback loop on a btrfs host does not, since the host may compress the file again.
Both init and doctor judge that by the filesystem holding the file (`/var/lib/incus/disks`), and treat one that cannot be read as possibly compressing (`compressesLoop`), so the two never disagree about a pool init made.
`IncusClient.listPoolInfo` throws when Incus cannot list its pools, so doctor says it could not check rather than offering to create a pool that may exist; `listPools` keeps answering empty for its other callers, from the same parser.
`DoctorPoolBackingTest` drives the whole path through doctor's checks on a `FakeIncusDaemon` whose pools carry config.
The merge also puts back `user_subvol_rm_allowed` where a value set without it dropped it, as an earlier version of `docs/dev/MIGRATIONS.md` had people do.
Doctor's merge adds `compress=zstd:1` only where the user chose no `compress` option of their own and no `nodatacow` or `nodatasum`, which compression turns off, and never for a loop file on btrfs.
Incus itself sets no no-compress attribute on the file (`ensureSparseFile` only truncates), so on a btrfs host the outer layer may compress again; that host is told to migrate anyway.
A subvolume pool gets no `btrfs.mount_options` from isx.
Incus bind-mounts it and then remounts the bind with the pool's options (its default `user_subvol_rm_allowed` included), and a btrfs remount without `MS_BIND` changes the options of the whole host filesystem until the next mount.
So options set on a subvolume pool are host tuning in disguise; `docs/dev/MIGRATIONS.md` "Host write tuning" is where that choice is made, deliberately, through `/etc/fstab`.

**Doctor**: `CowPoolBacking.doctorFindings` reads the pool config from the listing doctor already makes (`IncusClient.listPoolInfo`) and says two things about a loop pool: a WARN without remediation when the file is on btrfs, pointing at the migration in `docs/dev/MIGRATIONS.md` (`CowPoolBacking.MIGRATION_NOTES`, a GitHub link because the maintainer notes are not published; moving instances to a new pool is not something to do behind a yes/no prompt), and a WARN that offers the mount options, merged into what the user set (`withLoopMountOptions`), applied through `IncusClient.setPoolConfig`; Incus remounts the pool live.
The issue also asked for a warning when the loop file lacks `chattr +C`.
It is not checked: reading the flag needs root, and `+C` takes effect only on an empty file, so for an existing pool the only remedy is the migration the first warning already gives.
Doctor offers a resize only for a loop file, a pool whose source is a `.img` (`CowPoolBacking.isLoopFile`): a subvolume pool's total is its filesystem's, and Incus has no size to grow.

**The cap and #414**: the 100 GiB `size` came from #414, where creating the pool inside a VM consumed over 32 GB of RAM; the suspect was `mkfs.btrfs` on a large loop device.
A subvolume pool runs no `mkfs` and allocates no device, so that concern does not apply to it, and the loop path keeps the cap.
What a subvolume pool loses is the ceiling: instances can fill the host filesystem.
Incus reports a subvolume pool's usage as its filesystem's, so doctor's existing "nearly full" warning covers the host disk, and each instance's root `size` (`--disk`, default 100GB) still bounds one runaway instance.
No pool-wide quota is set: a qgroup limit on the pool subvolume would not bound the host's own writes, and it is the full disk that hurts.

**Quotas on the host filesystem**: Incus enables btrfs quotas lazily, the first time it applies a volume size (`btrfs quota enable` on the volume's path), and on a subvolume pool that is the host filesystem: qgroup accounting then covers the host's own subvolumes too, and `BtrfsUsage`'s `quota rescan` walks the whole filesystem.
Incus also mounts the host filesystem with `user_subvol_rm_allowed` each time it starts with such a pool: `btrfs.Mount` bind-mounts a path source and then remounts it with the pool's options, `MS_REMOUNT` without `MS_BIND`, which sets them on the whole filesystem.
It skips that remount inside a user namespace (nested Incus in a container) and when the pool path is already mounted, so it is a change only a host install makes.
That lets an unprivileged user delete any subvolume they can write to on it, not only in the pool.
Sanne accepted both host-wide changes as the price of one filesystem (2026-10-25, on #1663), documented in `docs/CONFIGURATION.md` rather than avoided.
`InstanceSubvolumes.refFromPath` already reads the deeper `.../storage-pools/<pool>/containers/<name>` paths a subvolume pool's `subvolume list` prints.

**Disk-space visibility in the TUI**: All instances (and templates — templates are just stopped instances) share this one pool, so the pool's total is the real disk budget.
On the macOS appliance the `cow` pool is a fixed-size VM disk, so its `space.total` *is* the VM's disk.
The TUI surfaces this with a full-width **storage gauge** above the panels (green/amber/red) plus a per-row **DISK** column on both panels.
The gauge reads whole-pool usage (`getPoolUsageBytes`); the per-row figures come from `state.disk.<dev>.usage` in the recursion=2 listing the TUI already fetches, so no extra API cost.
Two deliberate honesty choices follow from the CoW model: (1) per-row usage is shown as **absolute used bytes**, never as a percentage of the instance's `root.size` — that limit is a thin-provisioned ceiling (defaults to 100 GB), not an allocation, so a percentage would be meaningless; (2) per-row figures are marked approximate (`~`).

**When a pool counts as low or critical** (#2204): one rule in `IncusClient.PoolUsage` serves the gauge, the TUI's status warning, the clean dialog's resize tip, the build failure diagnostics and `isx doctor`.
A pool is low (amber) at 75% used with under 10 GiB free, and critical (red, the status warning, the build's "likely cause" and doctor's warning) at 90% used with under 5 GiB free.
A percentage alone called a 1 TiB pool critical with 100 GiB still free, so users learned to ignore the warning and a failed build pointed at the pool.
A floor alone would call a mostly empty small pool low, so both must hold.
The percentage decides only on a pool under 40 GiB for low and under 50 GiB for critical; on a larger one the floor does.
So a 100 GiB pool, the loop file's size, turns amber at 90% and red at 95%, where it used to at 75% and 90%, and doctor offers to enlarge it from 95%.
The floors are small because work needs little: a branch writes tens of MiB, and a build that fetches dependencies its template did not seed writes a few GiB.
They are not smaller because btrfs can refuse writes with a few GiB still reported free, since a metadata chunk needs unallocated space, and because deleting subvolumes to reclaim space needs some too.
The 5 GiB keeps that margin and the 10 GiB gives notice before it; neither was measured against a filling pool.
The floors are not configurable, which a config key could change if anyone needs it.

The per-row number is hard because the only figure Incus exposes (`state.disk.<dev>.usage`) is btrfs *exclusive* bytes — blocks unique to that one subvolume — and exclusive **collapses to ~0 for any subvolume that has a CoW descendant**.
So a template's real weight is invisible: the moment a child template or a branch exists, the parent's own layer stops being exclusive to it.
The base image and every shared block belong to no row and appear only in the pool total.
The figure that *does* answer "how big is this template" is btrfs **referenced** (rfer) — a subvolume's full logical size including blocks it shares with ancestors — but Incus exposes no API for it (its btrfs driver reads the qgroup and returns only the exclusive column).

So `isx` reads rfer itself, and caches it.
Because a built template is immutable and its rfer is stable (referenced bytes don't change when a parent is later deleted — the child still references those extents), `BuildCommand` measures rfer **once at build time** and stamps it as `user.incus-spawn.disk-referenced` metadata (excluded from the content fingerprint, so it never triggers a rebuild).
The TUI then reads that stamp at no extra cost on every reload and shows each template as a **delta from its parent**.
The root template's delta is its own rfer — i.e. the base-image weight — and each derived template shows only what its layer added (packages, a tools install), which is exactly the intuitive "cost of this template".
Instances keep their exclusive usage: a branch with no descendants has `exclusive ≈ rfer − rfer(parent)`, so exclusive already *is* its delta, and it comes free from the API.
This replaces the older `foldBaseWeightIntoRootTemplate`, which — lacking rfer — could only dump the *entire* pool remainder onto the root template.
So on a real inheritance chain the base template ballooned to include every layer's shared blocks while the intermediate templates read ~0.
The fold survives only as a fallback: when a template predates the stamp (cache-only, no backfill — rebuild to populate it) or the pool isn't btrfs, the display falls back to exclusive-plus-fold.

Instances (unlike templates) are mutable, so they are never stamped — each instance row always shows its **live exclusive** usage from the Incus API, recomputed every reload.
The reading is "space reclaimed by deleting just this row".
An instance branched from another instance shares its inherited blocks with the source, so those blocks are exclusive to neither and float in the pool total (visible in the gauge, attributed to no row) exactly like template-shared blocks.
This makes instance→instance branching safe by construction.
Because nothing is cached, deleting the branched-from instance needs no invalidation.
On the next reload the surviving branch's exclusive usage simply grows to absorb the blocks that were shared, since they are now unique to it.
The delete-confirmation note closes the loop in the other direction.
Before the shared-with-parent caveat it checks whether anything was branched or derived from the target (`hasDescendant`, using the parent each row already records) and, if so, warns that deleting it reclaims little while its descendants remain — the instance analog of the base-template note.

Deleting a template is safe: rfer is a property of the *extents a subvolume references*, so a child's stamped rfer stays accurate after its parent is deleted (the child still references — and keeps alive — those blocks).
Only the delta arithmetic has to cope with a missing parent, and it does.
The row's parent is resolved by climbing the *definitional* chain (the on-disk YAML, which outlives the deleted instance) to the nearest ancestor that still exists and is stamped (`nearestStampedAncestorRfer`).
So deleting an intermediate template just makes the next survivor down the chain subtract the next survivor up — no phantom subtraction, no crash — and if the entire chain above a row is gone, its parent resolves to `null` and it shows full rfer, correctly re-absorbing the base weight that no other row now claims.
The rows keep reconciling with the gauge; the only residual imprecision is that blocks shared *between siblings* of a deleted fork point get counted in each sibling — bounded, and already flagged by the `~` marker.
The delete-confirmation dialog restates the caveat at the moment it matters — for a clone, "blocks shared with `<parent>` stay until the parent is removed"; for the base template, that most of its size is the shared base image and won't be reclaimed while derivatives exist.

Reading rfer needs root (the qgroup ioctls need `CAP_SYS_ADMIN` and the pool directory is `0700 root`), and `isx` normally runs as an unprivileged user.
Rather than prompt for sudo on every TUI refresh, the privileged read is split by platform (`BtrfsUsage`).
On **Linux** the pool is on the host, so `isx init` installs a tightly-scoped NOPASSWD sudoers rule (`/etc/sudoers.d/incus-spawn-btrfs`, validated with `visudo -cf`) permitting *only* read-only `btrfs qgroup show -re --raw [--sync]`/`subvolume list` against the pool mount (both the plain and `--sync` command forms are listed, since sudo matches argv exactly).
It also permits `quota rescan`, which rebuilds the counters, the two waits of a settle, `subvolume sync` and `quota rescan -W`, and `tee` to the pool filesystem's `drop_subtree_threshold` (#2061).
That path names the filesystem by its UUID, written out, because sudo's `*` also matches spaces and would let `tee` write any file; sudo matches the arguments exactly, and the kernel accepts only 0 to 8 there.
A pool re-created under another UUID loses the entry until `isx init` rewrites the rule.
On **macOS** the pool lives inside the appliance VM, where the in-VM control agent already runs as root, so it gains a `btrfs-usage` verb (still an allowlisted one-verb dispatcher, not a general guest-exec channel).
Both paths feed one unit-tested parser (`BtrfsUsage.parse`, joining qgroup rfer to subvolume paths).
Since rfer is read only at build time (plus the fallback), the privileged surface is exercised rarely, not on every refresh.

Two btrfs quirks make the stamp-time read fragile, both handled in `BuildCommand.probeReferencedSize` (which measures) / `stampReferencedSize` (which records) and `BtrfsUsage`: (1) qgroup accounting only reflects *committed* transactions, so a read right after a build can miss its final writes; (2) deleting a subvolume can mark the pool's qgroup accounting inconsistent, and a rebuild does exactly that (`deleteIfExists` of the previous template) immediately before stamping.
For (2), `probeReferencedSize` measures rfer against the freshly-built *temp* subvolume **before** the delete/rename, not after — rfer is per-subvolume, so the temp name reports the same value the canonical name will.
For (1), the read comes in two flavours (`BtrfsUsage.probe(pool, sync)`).
The stamp-time read passes `--sync` to force a commit first, while the plain read (the default, and the intended path for periodic sampling) skips it, because forcing a whole-filesystem commit on every sampling tick would be wasteful.
The `--sync` flavour must stay in step across three layers — the Java command, the `isx-agent`'s `btrfs-usage <pool> [sync]` verb (an allowlisted second token), and the sudoers rule (which lists both command forms) — or the privileged read is denied.

Getting a stamp wrong no longer collapses the whole display.
`canUseReferencedModel` gates the delta model on every built **root** template being stamped (the root carries the base-image weight, so without its rfer the shared-base fold is the better model).
A built *derived* template missing its stamp is tolerated — `applyReferencedTemplateDeltas` leaves that one row on its exclusive usage instead of dropping every template to ~0.
So a transient read failure at build time degrades a single row, not the entire panel.

A missing stamp also self-heals on reload without a rebuild: `fillMissingReferencedSizes` backfills it with a single live probe, and *only* when there's an actual gap (an unstamped built template).
A template's first backfill is also saved as its stamp, since templates are immutable, and it is held to a build's conditions.
The template is held in place (`TemplateLock.tryReading`), so no swap puts another instance under its name between the read and the stamp.
That hold never waits, since the backfill runs on the TUI's UI thread and a swap can hold the lock for seconds.
A template a rebuild is swapping right now keeps its gap until a later reload.
The accounting is read consistent just before and after the read, never taken from the reload's status, which may predate the swap that flagged it.
And the read forces a commit (the sync flavour), so the stamp counts every write.
A gap that read could not fill, or whose stamp could not be written, is read again only with the light flavour, in memory, so it costs one commit, not one per reload.
`ReferencedBackfill` holds that decision, behind a seam for the probe, the status reads, the locks and the stamp write, and `ReferencedBackfillTest` pins it.
This keeps the privileged read out of the healthy per-refresh path (a fully-stamped install never probes on reload) while recovering pre-feature templates and every build that left its template unstamped.
The backfill never overwrites an existing stamp, which is authoritative and free.

**The accounting itself can be silently wrong, so it is checked and repaired.**
Ordering the read before the delete addresses a *transient* effect; the real failure is persistent and filesystem-wide.
The kernel flags a pool's qgroup accounting `inconsistent` — and from then on freezes every counter at its last value — when quotas are enabled on a pool that already holds data, or when dropping a subvolume would require walking a shared subtree deeper than `drop_subtree_threshold`.
Both are routine for isx.
Incus enables quotas lazily, when the first instance size limit is applied (by which time the image and every template already exist), and the appliance kernel runs with the threshold at 3, so template rebuilds keep re-tripping it.
Nothing clears the flag except a completed `btrfs quota rescan`.
The frozen values are not zeros but plausible numbers (each snapshot inherits its source's rfer, so every subvolume reports the base image's ~113 MiB with 16 KiB exclusive), which is why this went unnoticed: they pass the `<= 0` stamp guard, get recorded, and the delta model subtracts identical stamps to exactly `~0B` per layer.
The exclusive fallback and Incus's own `state.disk` usage read the same frozen qgroups, so there is no untainted fallback — only the statfs-based gauge stays right.
The fix is a trust gate plus an automatic repair, both in `BtrfsUsage`.
The kernel exposes the flags world-readable in sysfs (`/sys/fs/btrfs/<fsid>/qgroups/{enabled,inconsistent,mode,drop_subtree_threshold}`), so *detection* needs no privilege at all.
`BtrfsSysfs` resolves the pool's fsid on Linux (via the containing btrfs mount's *source device* in `/proc/self/mountinfo` — btrfs `st_dev` numbers are per-subvolume, so neither `stat` nor mountinfo's `major:minor` identify the block device), and the agent's `btrfs-status` verb does the same lookup inside the VM.
One `parseStatus` serves both.
`repairIfInconsistent` reads the status and, if the accounting is inconsistent, starts the rescan asynchronously — the kernel rebuilds the counters in the background and clears the flag when done, seconds on a developer-sized pool — throttled to one trigger per minute and five per process (on Linux via a `btrfs quota rescan <pool>` entry added to the sudoers rule at `INIT_VERSION` 6; on macOS via the agent's `btrfs-rescan` verb).
Consumers act on `untrusted()` only — a status that *can't be read* (older agent, pre-6.1 kernel, quotas off) keeps the pre-check behaviour rather than blanking the display.
`IncusClient.delete()` — the single method every subvolume delete funnels through, `deleteIfExists` included — calls it right after the delete succeeds (best-effort, never failing a delete that already worked).
A delete is exactly the operation that can cause the inconsistency, so this catches it immediately instead of waiting for the next reload or build.
Deletes arrive in bursts (`isx clean` sweeping failed builds, destroying a set of branches).
So that path uses `repairIfInconsistentThrottled`, which collapses a burst to one check (5s minimum spacing) and defers the caller's pool lookup behind the same gate.
Otherwise N deletes would mean N status reads, each an agent round trip on macOS.
Nothing is missed by collapsing: only the deletes in the burst could have set the flag, the first check already caught that and started the rescan, and the TUI cadence and next build re-check regardless.
The rescan-trigger budget (`MAX_RESCAN_TRIGGERS`) counts *consecutive* attempts and resets the moment accounting reads consistent, so it bounds a stuck repair rather than capping how many times a long-lived session may legitimately self-heal.
The TUI also calls it every reload (cadence-limited: 30s normally, 2s while a repair is pending, so an agent round trip on macOS isn't on the hot path).
While untrusted it keeps showing existing stamps (a stamp taken from consistent accounting stays valid, templates being immutable), does not read anything live (no backfill, no shared-base fold) and shows a one-shot "repairing" hint.
The reload that first sees the flag clear re-validates the stamps with one live probe and re-stamps whatever differs — the one path that overwrites a stamp, which also fires once per session on the poisoned-stamp signature (a derived template stamped with exactly its parent's value) so pools broken before the check existed heal without a rebuild.
`BuildCommand.probeReferencedSize` runs the same detect-and-repair before stamping, and never waits for it.
A rescan walks the whole pool, which took over 20 s on a nested host, and every rebuild's swap deletes a subvolume and can flag the pool again, so a batch of builds would wait once per template.
The stamp serves only the TUI's disk panel, so a build whose accounting is under repair leaves its template unstamped, prints no message, and the TUI's backfill measures and saves it once the flag clears.
`isx doctor` reports the state and offers the rescan for when the automatic one couldn't run.

**Full qgroups made the next branches wait for deleted instances (#2061).**
A subvolume delete returns at once, and `btrfs-cleaner` frees the tree in the background.
Under full qgroup accounting it accounts every extent the subvolume shared with its source, and a new snapshot waits for the transaction that work holds.
After 20 copies of a 2.7 GiB instance were deleted, each `incus copy` took about 5 s instead of 0.15 s, for over two minutes; after 70, up to 24 s, for 16 minutes (docs/dev/PERFORMANCE-NOTES.md "Branching after a bulk delete").
isx turns full qgroups on itself: the root `size` every branch gets makes Incus run `btrfs quota enable` (see "Quotas on the host filesystem" above).

**isx has btrfs skip that accounting, and rebuilds it after the delete.**
The kernel's `drop_subtree_threshold` (sysfs, `qgroups/drop_subtree_threshold`, 3 by default) decides it: a delete whose shared tree is that deep or deeper is not traced, and the accounting is marked inconsistent instead.
Before a delete, isx sets it to 0 on a pool under full qgroups (`BtrfsUsage.skipDropAccounting`), so the cleaner traces nothing and frees 70 deleted copies at once.
The kernel resets it to 3 at every mount and every `quota enable`, and Incus enables quotas lazily, so isx sets it before its deletes rather than once at boot.
After the delete, isx settles the pool (`BtrfsUsage.settleDeletes`): it forces a commit, waits for the cleaner, puts the old threshold back, waits for any rescan already running, commits again, and starts a `btrfs quota rescan`.
Putting it back means a delete isx does not make, a plain `incus delete` or Incus pruning an image, is traced as before, slowly, and never leaves the accounting inconsistent with nobody to settle it.
The rescan rebuilds the counters in the background without slowing a snapshot; branches during it measured 0.12–0.16 s.
What this gives up: while the accounting is inconsistent, btrfs enforces no qgroup limit, so no instance's root `size` holds, from the delete until the rescan completes.
Settling at once is what keeps that short, a second or two after a destroy.
Left alone, the cleaner frees a delete only at the next commit, up to `commit=300` s later, when no isx process is there to notice the flag it sets.
A rescan that overlapped the cleaner clears the flag on completion, and the commit that then removes a freed subvolume's qgroup sets it again, which is why the settle waits for one and commits before starting its own.
A drop at threshold 0 marks the accounting inconsistent, and marking it cancels a rescan still running, so the prepare step waits for one before it sets the threshold.
Without that wait, 20 `isx destroy` in a row cancelled each previous one's rescan 14 times, and once left the pool reading "rescan running" with no rescan, refusing every new one, until it was mounted again.
The wait costs back-to-back destroys one rescan each: 20 in a row took 58 s instead of 32 s on a 2.3 GiB source, and a destroy with no rescan running waits for nothing.
A rescan the wait does not see end (60 s on Linux, 4 s through the macOS agent, inside its 5 s reply timeout) leaves the threshold as it is, so that delete is traced as before.
A wait that times out kills `sudo`, not the `btrfs` it forked, which keeps both pipes open.
So the reads of its output give up after a second (`BtrfsUsage.runProcess`), and the destroy goes on while that `btrfs` ends on its own.
isx puts a threshold it replaced back from a shutdown hook too (`IncusClient.restoreReplacedThresholds`), for Ctrl-C or SIGTERM during a batch, whose settle then never runs.
Left at 0, every delete isx does not make would go untraced until the next mount, and isx's own next delete, reading 0, would put nothing back.
The hook writes the threshold alone, and the next delete, TUI refresh, build or doctor run rescans; a SIGKILL runs no hook.
It is registered before the first threshold write, and marks isx as exiting before it reads what is held.
A delete whose write was still in flight when the hook read puts its threshold back itself, unless the JVM halts first.
A delete made once isx is exiting writes no threshold and is traced as before.
Prepare and settle are one critical section per pool, across processes (#2435).
A host lock on `btrfs-pool/<pool>.lock` (`PoolQgroupLock`) is taken before a delete sets the threshold.
It is in a directory of its own, because the TUI's per-instance locks share the lock directory as `<instance>.lock`.
The pool's state is read again under it every time, a pool that reads 0 included, and the lock is released once the settle has started its rescan, or when the threshold is put back at exit.
The lock is per user, since it lives under `~/.cache`.
Without it, one process's drop at threshold 0 could cancel the rescan another had just started, and a series of such cancellations is what left a pool unable to rescan until a remount.
A delete that finds the lock held waits for it, then reads the pool's state again, since the other process changed it.
A rescan trigger from a TUI refresh or a build takes the lock only if it is free, and schedules none otherwise: the holder's settle starts one.
`isx doctor`'s rescan waits three seconds for it first, and is told a rescan is under way only if the holder is still settling.
A delete that cannot get the lock within five minutes sets no threshold, so btrfs traces its drops as it did before isx set any, and it is not settled.
The lock is held by a small thread of its own, since a batch is prepared on one thread and settled on another, and the kernel drops it if isx is killed.
On macOS it holds off host processes until the appliance agent has the settle, which then runs detached (#2436).
The settle runs once per batch of deletes (`IncusClient.deleteBatch`: `isx destroy --all-*`, the TUI's batch delete, `isx clean`), so a destroy of one instance pays it once, under 0.1 s of its 0.7 s here, and a batch of 70 about 2 s.
The forced commit writes whatever the pool has not committed yet, so on a busy pool it can take longer.
On macOS the appliance's `isx-agent` does the same through its `btrfs-skip-drop-accounting` and `btrfs-settle` verbs, the second detached so the host does not wait.

Rejected: simple quotas (`btrfs quota enable --simple`) also avoid the wait and never leave the counters inconsistent.
On a pool enabled in simple mode from the start, each subvolume counts what it wrote, which is the per-template delta the TUI shows.
But an existing pool needs its quotas disabled and re-enabled, after which everything already written counts for nobody until it is rebuilt, and the base image's bytes are left to a nameless qgroup once the image is deleted.
Rejected too: dropping the root `size`, which turns quotas off and with them every template size and every instance's disk cap.

`isx doctor` names a backlog as a note (`DoctorCommand.pendingCleanupFinding`) when the pool still traces drops: full accounting, consistent, at a threshold other than 0.
It is read from the `qgroup show` that `BtrfsUsage` already reads (`BtrfsUsage.pendingCleanup`), counting the qgroups btrfs-progs paths as `<under deletion>`.
A missing subvolume is not enough: once the cleaner has freed one, its qgroup stays until a later commit with its referenced bytes unchanged, pathed `<stale>`.
An inconsistent pool is skipped, since its frozen counters could show a backlog long gone.
On Linux it also notes when the sudoers rule does not let isx set the threshold (`DoctorCommand.thresholdNotSettable`), since a threshold of 3 is what every pool reads between deletes.
It asks by writing back the value the pool has, which changes nothing; `sudo -n -l` would also say yes for a rule that wants a password, such as an administrator's `%wheel` one.

The `C` key reclaims space via `isx clean pool` (failed builds, unused images, build caches).
Downloaded base images, the container one and the `-vm` disk image, are a category of their own and opt-in, like the DNF cache.
They are not unused, only re-downloadable, and on a slow connection a VM base image is the most expensive thing to lose.
Labelled "Storage" rather than "pool" to avoid leaking the Incus term, and the gauge/column degrade gracefully to hidden/`-` on `dir` pools that report no per-volume usage.

Reclaiming isn't always enough: on the macOS appliance the pool is capped by the VM's data disk, so once real usage approaches the ceiling the fix is to *grow the disk*, not delete work.
`isx vm resize <size>` does this (macOS only — on native Linux the pool grows with the host filesystem, so the command isn't registered there at all).
The data disk is a sparse raw image on the host.
Resizing extends the file (`RandomAccessFile.setLength`, grow-only) while the VM is stopped, then the guest expands the btrfs filesystem to fill the larger device on the next boot — a one-line `btrfs filesystem resize max /var/lib/incus` in the appliance init that mirrors the root disk's existing auto-resize.
The data disk is deliberately separate from the root disk and survives root-disk upgrades, so growth persists across appliance version bumps.
The resize verifies the pool total actually grew after reboot and warns if the running appliance predates the auto-resize step (the image grew but the filesystem didn't).
The critical-fill TUI warning names both remedies — `C` to reclaim, `isx vm resize` to expand.

### Orphaned subvolumes and dangling records

A pool's instance subvolumes and Incus's records can drift apart (#717).
A rename that moves the subvolume while the record (and its `backup.yaml`) keeps the old name, followed by a delete of that record, leaves data on disk with nothing pointing at it: an *orphan*.
Incus cannot see it, so neither can isx, until something creates an instance of that name and fails with "file exists".
For a template rebuild, which creates `<name>-rebuilding` and renames it to `<name>`, that failure used to come after the whole build.
The stage before is a *dangling record*: an instance whose subvolume is not at its path.
Whether Incus then deletes such a record may depend on its version: 6.23 refuses ("Not a Btrfs subvolume"), while the orphans found on the #717 appliance (Incus 6.21) fit a delete that went through.

`InstanceSubvolumes` compares the two sides.
The disk side is a names-only parse of `btrfs subvolume list` (`BtrfsUsage.subvolumeList`, the same sudoers entry and agent verb as the rfer read).
The rfer join in `BtrfsUsage.parse` cannot serve, because it drops every subvolume without a qgroup row, which would hide orphans and invent dangling records; it places subvolumes with the same parser, though, so sizes and the scan agree on names.
Only top-level `containers/<name>` and `virtual-machines/<name>` count, anchored at the pool (the filesystem root, or `…/storage-pools/<pool>` with no instance or pool directory above it): a guest running its own Incus nests a pool of the same name inside its rootfs.
The records side is the pool's volumes across all projects, which live on disk as `<project>_<name>` outside `default`, plus the default-project instances whose root disk is on the pool.
An empty listing while Incus has instances there is treated as "unknown", not as every instance being dangling, because the agent answers an empty section when `btrfs` fails.

Four consumers use it, and each **fails open** when the pool cannot be listed (not btrfs, no sudoers rule, an unprivileged host): refusing every delete there would be worse than the rare orphan.

- `IncusClient.delete` throws `DanglingRecordException` before sending the DELETE when the instance's subvolume is missing, because that delete is what strands the data.
- `IncusClient.rename` checks afterwards that Incus lists the new name and that the subvolume moved with it.
  That turns a half-completed rename (the build's swap, promotion to `-failed-build`, a TUI rename) into an error at the rename rather than a latent orphan.
- `BuildCommand.buildSingleImage` refuses up front when either `<name>` or `<name>-rebuilding` is an orphan or a dangling record, since its swap would otherwise fail only after the whole build.
- `isx doctor` reports orphans (FAIL when the name is a template's or its `-rebuilding` name, since that build cannot succeed; WARN otherwise, with referenced sizes when qgroups have them) and dangling records (WARN).
  On Linux it prints the `sudo btrfs subvolume delete -R` command instead of widening the read-only sudoers rule — removal stays manual there.
  On macOS it offers a real remediation, one orphan at a time, through the `btrfs-orphan-delete` agent verb (#874).
  Wiping the whole pool with `isx vm reset` was the only option before, which is disproportionate for a handful of stray subvolumes and loses every instance, not just the orphaned ones.
  The agent re-checks, from inside the VM and across every Incus project, that nothing still references the name before it deletes anything — a separate trust boundary from `isx doctor`'s own scan, which runs on the host and can be stale by the time a user confirms the remediation (another branch could finish, or fail and get cleaned up, between the scan and the confirmation).
  It also confirms the path is still a *subvolume*, not "exists": Incus 6.23 recreates a plain directory with `backup.yaml` at a dangling record's path whenever it rewrites that record, and that directory is not the orphan's data.
  `subvolumeFindings` stays pure (no agent call) so it is unit-testable without a VM.
  `DoctorCommand.checkSubvolumes` is where the live action is wired in, replacing the finding's description and remediation only on macOS and only when there are orphans and `VmAgentClient.supportsOrphanDelete` confirms the appliance has the verb.
  That probe sends a name no real subvolume has, so a supported appliance answers `error: not a subvolume` — never reaching the Incus re-check or a delete — while one built before the verb existed answers `error: unknown verb`.
  Either way nothing is touched.
  Probing before offering, rather than discovering it after the user confirms, is what lets the remediation keep pointing at `isx vm reset` on an older appliance instead of a destructive action reporting, too late, that it cannot do what it just offered.
  `DoctorCommand.deleteOrphans` draws the same distinction on the delete calls themselves.
  A timeout (`VmAgentClient.send`'s own 5s watchdog, same as an unreachable agent) answers `Optional.empty()`, which is not the literal `error: unknown verb` string — the only reply that actually means "unsupported" — so a slow-but-working verb on a later orphan is not told its appliance is too old while its delete finishes inside the VM regardless.

`isx vm reset` (`VmManager.resetDataDisk`) is the macOS last resort for a pool with nothing worth keeping.
It lists what will be lost, booting a stopped VM to ask Incus, because the host keeps per-instance state (SSH config, git remotes) that only instance names can find.
Then, holding the VM lifecycle lock, it stops the VM and waits for the hypervisor process to exit.
It replaces the data disk with a blank sparse one **of the same size**, so a disk grown with `isx vm resize` stays grown, and boots.
The appliance's `rcS` formats the blank disk and `incus-spawn-vm-init` recreates the bridge, the `cow` pool and the default profile.
The command then removes the host integration of every instance it listed, signals the proxy, and checks that Incus answers with an empty CoW pool.
Like every command that deletes data, it refuses to run without a terminal to confirm on unless given its skip flag (`BaseCommand.confirmDestructive`).
The older `confirm()` proceeds there, which is how `echo n | isx vm reset` once wiped a pool during testing.
`resetDataDisk` returns a `ResetResult` rather than a boolean because the cleanup hinges on it.
`NOT_RESET` (lock held, the VM would not exit, the disk could not be deleted) leaves every instance in place and must not touch their host state, while `VM_DOWN` means the disk is gone even though the VM did not come back.

**Tidying "state" keeps the data disk (#1155).**
The data disk lives in `~/.local/state/incus-spawn/` beside pid files, sockets, logs and the root disk, so anything that tidies that directory took every instance with it.
`isx clean state` and `isx clean all` deleted it as "state" (and `clean all` claimed the pool was untouched), and `uninstall.sh` listed it as "remove disk image".
`uninstall.sh` is also the natural step when moving from a source build to Homebrew, which is how a whole pool was lost to a confirmed prompt.
All three now delete everything in the directory *except* `data.img` unless given `--delete-instances`, and say what they keep and what it holds (the script counts instances and built templates with the isx being uninstalled when the VM is up; with it down nothing can tell, and it says so).
Keeping only that file is enough: it is mounted at `/var/lib/incus`, so it carries the Incus database as well as the pool, and the appliance boots on it as it does after a root-disk upgrade.
That makes how the VM stops matter: before, a disk about to be deleted could be killed mid-write, but one that is kept is stopped through the isx being uninstalled (`isx vm stop`) first.
That asks the guest to shut down through its agent (`shutdown`: `poweroff`, so init runs `rcK`, which stops Incus and its instances and syncs) and waits up to 30 s for the hypervisor to exit by itself.
Only then does `VmManager.stopLocked` fall back to the launcher's stop (its control socket, then `SIGTERM`), which cuts the disks off.
Before #881 there was no such verb, and vfkit's stop request was a power button nothing in the guest listens for.
So every `isx vm stop` on a Mac ended in the signal: the next boot replayed the data disk's btrfs tree log, and with the disks mounted `commit=300` an instance deleted seconds before the stop came back as an orphaned subvolume (see appliance/DESIGN.md, "Stop sequence").
An appliance from before the verb is still stopped that way, until a restart applies a newer one.
Because the VM is gone by the time the script looks, it cannot see a cut-off itself, so `stopLocked` reports how the VM stopped (`VmManager.StopResult`: `SHUT_DOWN` or `SIGNALLED`).
`stopLocked` warns on `SIGNALLED` whenever the disks are kept (`isx vm stop`, `restart`, `resize`, doctor's restart); `isx reset` and `isx vm reset`, which delete them next, stop quietly (`VmManager.stopToDelete`).
`isx vm stop --require-clean` exits 4 on `SIGNALLED` and 1 on a stop that failed.
The script passes that flag and, on 4 or when the VM outlives isx (then it signals the VM itself), warns that the kept disk may need recovery, then and in its closing lines.
With an appliance from before #881 that warning appears on every Mac uninstall with a running VM: "kept" means "as intact as after `isx vm stop`", which there is a tree-log replay.
The exit status is opt-in so that `isx vm stop && ...` keeps working where every stop is still a signalled one.
An isx from before the flag rejects it with 2; the script then runs a plain `isx vm stop` and, since that isx cannot say how it went, warns that it cannot tell.
`isx` is asked for the counts through a temporary file rather than a pipe, so `$(...)` does not wait for a child isx left holding its output.
`bounded` runs the command in a process group of its own under a watchdog that SIGKILLs the group, since a bare `alarm` does nothing to a command that ignores SIGALRM (an ignored disposition survives `exec`) and does not reach anything the command started.
The 20 s is a hard bound.
`uninstall.sh --binaries-only` removes just the binaries and install.sh's Homebrew override, for switching install channel.
On Linux the proxy service needs no change there, since the next isx command rewrites a start script that names another `isx-proxy` (`ProxyService.reinstallIfChanged`).
On macOS that is not enough, as a Mac showed.
The launch agents name the binaries by path, `reinstallIfChanged` rewrites only the proxy's, and only once it sees a proxy of another version answering, so with the same version in both channels nothing was repaired and the proxy lived on only as the old process.
The VM agent was left naming a removed `isx` in every case, to fail at the next login.
So when a plist names the directory the binaries were removed from, the script runs `isx proxy stop` and then `isx proxy install` with the isx that remains on `PATH`.
The stop is needed: `isx proxy install` writes both agents only for a service that is not loaded (`installMacOs`), and on a loaded one rewrites the proxy's alone.
The VM keeps running throughout; the proxy is down for the second or two between the two commands, longer only when it is slow to stop, since `isx proxy stop` returns once launchd has removed the job.
The script then looks at the plists again and warns, naming the two commands, when they still name a removed binary, or when no isx remains to run them.
Agents written by another install are left alone.
The script refuses an unknown option rather than ignoring it, so a mistyped `--binaries-only` cannot run a full uninstall.
The root fix, moving the data disk out of the state directory, needs a migration and is a separate change.
`isx clean state` and `isx clean all` remove the appliance (`~/.local/share/incus-spawn`) along with the rest, so the VM boots on the kept disk only after `isx init` downloads it again, after either command.
The VM's launch agent has no `KeepAlive` (checked on a Mac in the plists 0.3.9 and this change write; 0.3.10's was not available there), so stopping the VM before booting out the agent cannot have launchd start it again.

`install.sh` never writes a Homebrew prefix (#1614).
A prefix is machine state: `/opt/homebrew` serves every account on a Mac, and `/home/linuxbrew/.linuxbrew` every account on Linux.
It used to relink the prefix's `isx`, `isx-proxy` and `git-remote-isx` to the installer's `$INSTALL_DIR`, so the brew isx would not shadow the new build.
Then another user's KeepAlive proxy, CLI and Git helper ran the installer's build out of the installer's home.
Nothing a script can see says that nobody else uses a prefix: its owner is whoever installed Homebrew, and other users' PATH and launch agents are private.
So a rule such as "relink only when the prefix is yours alone" would fail open, and copying or asking first would cross the same boundary.
`install.sh` now only removes links of its own, and points each one back into the formula's keg (`opt/incus-spawn`, or `opt/incus-spawn-dev` for the tap's conflicting dev formula) as Homebrew links it.
It restores only into a keg Homebrew records as linked (`var/homebrew/linked`).
An installed but unlinked keg may be unlinked on purpose, or by a `brew upgrade` whose link step the old override made fail, so the script does not link it by hand.
The two formulae conflict but may both be installed, one of them unlinked, so the linked one is chosen rather than the first installed.
When nothing replaces a removed link, the script warns, since no account on the prefix has that binary there any more, and names `brew link <formula>` when a formula is installed but not linked.
A link is its own when it resolves to the binary just installed (`-ef`, so any spelling of `INSTALL_DIR` counts), or names a file under the installing user's home, which covers an earlier install to another `INSTALL_DIR`.
An empty or `/` HOME names nobody's home, so under `env -i`, cron or launchd it makes no link its own.
Any other link out of the prefix came from another user's install, so it is named in a warning and not removed.
The restore is not `brew link`, for two reasons.
The old override replaced only the links, so Homebrew still counts the keg as linked and `brew link` answers "Already linked" without restoring anything.
`--overwrite` would also replace every foreign link in the formula's way.
`uninstall.sh` removes and restores its overrides by the same rule.
Shadowing is fixed in the installer's own account: when `isx` on PATH is not the new build, the script says so and names the PATH change.
The post-install `isx proxy install` follows the same `which isx`, so the CLI and the service stay on one build.
Forcing the service onto the new build would only last until the next command's drift repair moved it back.

`install.sh` refuses an `INSTALL_DIR` inside a Homebrew prefix, before it builds (#1666).
Installing there renames the build over the formula's links in the prefix's `bin`, and every account on the machine then runs it.
That is the cross-account write #1614 removes from the relink, so a warning would not do: it would still write the prefix.
Each ancestor of the directory's physical path is compared with each prefix by inode (`-ef`).
So a trailing slash, a symlink, a `..` or another letter case on a case-insensitive Mac volume cannot spell around it.
A directory that does not exist yet is judged by its nearest existing parent, before `mkdir -p` creates anything.
Such a directory may not contain `..`, since `mkdir -p` would create the part before it and then climb out of it, perhaps into a prefix.
A directory whose physical path cannot be read is refused, since the check would otherwise pass it unseen.
The prefixes are the one `brew --prefix` names, `$HOMEBREW_PREFIX`, and the standard `/opt/homebrew`, `/usr/local` and `/home/linuxbrew/.linuxbrew` wherever they hold `bin/brew`, so an account without `brew` on PATH is refused too.
The marker keeps a plain `/usr/local/bin` usable where Homebrew is not installed there.
