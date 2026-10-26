# Host migrations

Maintainer notes on changes an existing host needs by hand, because `isx init` only sets up new hosts.
Each section says how to tell whether a host is affected, what to change, and how to verify it.
This page is not published; once a migration is common enough for users, it moves to `docs/`.

## The CoW pool is a loop file on a btrfs host

### Why it matters

`isx init` used to create the pool with `incus storage create cow btrfs size=100GiB`, whatever the host filesystem.
Without a `source`, Incus backs it with a sparse file, `/var/lib/incus/disks/cow.img`, attached as a loop device.
On Fedora the host filesystem is btrfs, so the layout is btrfs inside a loop file inside btrfs.
Every write in an instance is copy-on-write in the pool, then the loop's write into `cow.img` is copy-on-write again in the host filesystem.
The file is not nodatacow (`C` in `lsattr`), so it fragments without bound: one host counted 2,974,635 extents for about 187 GB of data.
That host's file also showed `m` (no-compress), but Incus sets neither flag: its `ensureSparseFile` only truncates, so another host's file may be compressed by the host as well.
On that host three quarters of all NVMe writes were filesystem metadata and rewrites rather than data, about 540 GB a day with the machine mostly idle.
Since issue 1656, `isx init` creates the pool as a subvolume on a btrfs host (`CowPoolBacking`), and `isx doctor` flags a loop file on btrfs and links here.
This section is the migration for hosts set up before that, and for a host where the subvolume create failed and init fell back to a loop file.

### How to tell

```shell
incus storage show cow | grep source          # /var/lib/incus/disks/cow.img means a loop file
findmnt -no FSTYPE /var/lib/incus             # btrfs means the host could hold the pool directly
sudo lsattr -d /var/lib/incus/disks/cow.img   # no 'C' means every loop write is copy-on-write
sudo filefrag /var/lib/incus/disks/cow.img    # the extent count is the fragmentation
```

To see the amplification itself, compare what processes write with what the device receives.
Container writes are attributed to their `lxc.payload.<name>` cgroups, on the loop device (`7:0`) and on the NVMe (`259:0`).

```shell
awk '{printf "device: %.1f GB written since boot\n", $7*512/1e9}' /sys/block/nvme0n1/stat
for c in /sys/fs/cgroup/*/; do [ -r "$c/io.stat" ] && cat "$c/io.stat"; done \
  | awk '$1=="259:0"{for(i=1;i<=NF;i++) if($i ~ /^wbytes=/){split($i,kv,"="); w+=kv[2]}} END{printf "attributed: %.1f GB\n", w/1e9}'
```

The gap between the two is kernel filesystem work: btrfs metadata, commits and copy-on-write rewrites.
Measured over two minutes at idle on the host above, the device took 4.9 MB/s while every process together wrote 0.6 MB/s.

### Migration

Instances must be stopped to move.
Templates can be rebuilt instead of moved, since `isx build` reproduces them; moving keeps instance state.
Incus re-caches images in the new pool on its own.

```shell
sudo incus storage create cow2 btrfs source=/var/lib/incus/storage-pools/cow2 </dev/null

for i in $(incus list -c n --format csv); do incus stop "$i" 2>/dev/null; incus move "$i" --storage cow2; done
incus storage volume move cow/dnf-cache cow2/dnf-cache
sudo incus profile device set default root pool=cow2

incus storage show cow | grep -c '/1.0/'       # 0 once nothing uses it
sudo incus storage delete cow                  # removes cow.img
isx init                                       # points disk accounting at cow2
```

The source is the new pool's own mount path, the only path Incus accepts under its own directory, and what `isx init` uses.
Incus creates the subvolume there itself.
Keep it there: isx reads per-instance disk usage from `btrfs subvolume list` and only recognises `storage-pools/<pool>/containers/<name>`.
A subvolume elsewhere, such as `/var/lib/incus-pool`, leaves the TUI without per-template sizes and the orphan check without a listing it trusts.
`incus storage create` reads a config from stdin when stdin is not a terminal, hence the `</dev/null` in a script.

Set no `btrfs.mount_options` on the new pool.
For a path source, Incus bind-mounts it and then remounts with the pool's options, `MS_REMOUNT` without `MS_BIND`, which changes the options of the whole host filesystem until its next mount.
That makes them host tuning: choose it deliberately in [Host write tuning](#host-write-tuning), through `/etc/fstab`.
Even with none set, Incus remounts the host filesystem with its default, `user_subvol_rm_allowed`, when it mounts the pool on the host; it skips that inside a container and when the pool is already mounted.
A host migrated with an earlier version of this page, which set them, undoes it with `sudo incus storage unset cow2 btrfs.mount_options`.

Nothing in isx depends on the pool's name: `CowPoolBacking` only uses `cow` when it creates one, and the pool probe accepts any btrfs pool.
The final `isx init` rewrites the disk-accounting sudoers rule, which names the pool's mount path.
It takes the first btrfs, zfs or lvm pool Incus lists, so with another CoW pool still present, check that `/etc/sudoers.d/incus-spawn-btrfs` names `storage-pools/cow2`.
If SELinux denies Incus the new path, `sudo restorecon -R /var/lib/incus/storage-pools`.
Do not defragment `cow.img` in place instead: it would write the whole file once and fragment again immediately.
A member of `incus-admin` needs no `sudo` for the `incus` commands above.

Two leftovers can stop the move loop or the pool deletion; both were met on a host migrated on 2026-10-26.

An instance last started before a reboot or logout can refuse to move with `Missing source path "/run/user/<uid>/zmx/containers/<name>" for disk "zmx-sockets"`.
Its `zmx-sockets` device points at a directory on a tmpfs that is gone.
isx recreates it before a start (`ZmxSocketForward.ensureHostDirForStart`), but a move never reaches that repair.
Recreate it by hand and move again:

```shell
mkdir -p /run/user/$(id -u)/zmx/containers/<name> && incus move <name> --storage cow2
```

A failed `isx build` can leave a container volume with no instance behind it, such as `tpl-quarkus-failed-build` beside an instance record in state ERROR.
`isx doctor` reports the record as an instance without storage, and `incus delete` removes it.
The volume stays in the pool's `used_by` and blocks `incus storage delete`.
The storage API refuses to delete a container volume (`Storage volumes of type "container" cannot be deleted with the storage API`), so the row goes out of the database directly, after checking that no instance of that name exists:

```shell
incus admin sql global "SELECT id, name FROM instances WHERE name = '<volume>'"          # must be empty
incus admin sql global "SELECT id FROM storage_volumes WHERE name = '<volume>'"
incus admin sql global "DELETE FROM storage_volumes WHERE id = <id> AND name = '<volume>'"
```

Its data lives in the old pool and goes with it.

### Pool mount options where a loop stays

A host whose filesystem is not btrfs keeps the loop file.
`isx init` creates new loop pools with these options, and `isx doctor` offers them for an existing one, both leaving out compression where the file's filesystem is btrfs or cannot be read; by hand:

```shell
sudo incus storage set cow btrfs.mount_options user_subvol_rm_allowed,noatime,commit=300,compress=zstd:1
```

Keep `user_subvol_rm_allowed`: it is Incus's default, and a value set here replaces it.
A pool set with an earlier version of this page lacks it; `isx doctor` offers to put it back.
Incus applies a changed value at once, by remounting the pool, except when it runs inside a container.
Compression in the pool is safe where the host filesystem does not compress.
On a btrfs host leave it out: Incus marks nothing no-compress, so the host may compress the same bytes again, and such a host should move to a subvolume anyway.
The appliance already mounts its btrfs with `noatime,commit=300,compress=zstd:1` (`appliance/config.sh`).

## Host write tuning

These change the whole operating system, so isx never writes them; they are advice for a host that runs many instances.
Instances are throw-away scratch pads, so the trade is a few minutes of unsynced scratch lost on a crash in exchange for most short-lived files never reaching the disk.
Data that was fsynced is safe either way.

A small file costs disk twice under Fedora's defaults.
Its data pages are flushed once older than 30 seconds (`vm.dirty_expire_centisecs`), and its inode goes out with the next btrfs transaction commit, also every 30 seconds (`commit=`).
Anything that lives longer than half a minute is written, and its deletion is written too.
Neither knob costs memory: dirty pages occupy page cache either way, bounded by `vm.dirty_bytes`.

```shell
sudo tee /etc/sysctl.d/91-isx-writeback.conf >/dev/null <<'SYSCTL'
# isx: let short-lived build and agent scratch die in the page cache
vm.dirty_expire_centisecs = 60000
vm.dirty_writeback_centisecs = 3000
SYSCTL
sudo sysctl --system
```

Add `noatime,commit=300` to the btrfs lines of `/etc/fstab`, then apply without a reboot:

```shell
sudo mount -o remount,noatime,commit=300 /
sudo mount -o remount,noatime,commit=300 /home
```

Leave `vm.dirty_background_bytes` alone unless builds still show heavy writes: once that much is dirty, flushing starts regardless of age, which is what bounds a writeback stall.

Verify with `/sys/fs/btrfs/<uuid>/commit_stats` (one commit per five minutes at idle) and the device and cgroup counters above over the same window.

## Instances built from base images before fedora-44-20261009

Those images' network watchdog read the address of `eth0` with `awk`, so it restarted systemd-networkd every 30 seconds for the instance's whole life (issue 1657).
The container image has no `awk`, and a VM's NIC is not named `eth0`, so containers and VMs alike are affected.
`fedora-44-20261009` is the first release with the fix (incus-spawn-images `2f77305`, from `git tag --contains`).
`isx doctor` warns about the templates and instances still on such an image.
An affected instance shows a steady `isx-watchdog: IP mismatch: expected=<ip> current=, restarting networkd` in its journal.

```shell
incus exec <name> -- journalctl -t isx-watchdog --since -1h | wc -l    # about 120 means every run
incus exec <name> -- systemctl disable --now isx-network-watchdog.timer  # stop the loop now
```

Then rebuild the template from a current base image and branch again.
