# Host migrations

Maintainer notes on changes an existing host needs by hand, because `isx init` only sets up new hosts.
Each section says how to tell whether a host is affected, what to change, and how to verify it.
This page is not published; once a migration is common enough for users, it moves to `docs/`.

## The CoW pool is a loop file on a btrfs host

### Why it matters

`isx init` creates the pool with `incus storage create cow btrfs size=100GiB` (`HostSetup.cowPoolCreateCommand`).
Without a `source`, Incus backs it with a sparse file, `/var/lib/incus/disks/cow.img`, attached as a loop device.
On Fedora the host filesystem is btrfs, so the layout is btrfs inside a loop file inside btrfs.
Every write in an instance is copy-on-write in the pool, then the loop's write into `cow.img` is copy-on-write again in the host filesystem.
Incus marks the file no-compress (`m` in `lsattr`) but not nodatacow (`C`), so it fragments without bound: one host counted 2,974,635 extents for about 187 GB of data.
On that host three quarters of all NVMe writes were filesystem metadata and rewrites rather than data, about 540 GB a day with the machine mostly idle.
Issue 1656 tracks making `isx init` do the right thing; this section is the migration for hosts set up before that.

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
sudo btrfs subvolume create /var/lib/incus-pool
sudo incus storage create cow2 btrfs source=/var/lib/incus-pool
sudo incus storage set cow2 btrfs.mount_options noatime,commit=300,compress=zstd:1

for i in $(incus list -c n --format csv); do incus stop "$i" 2>/dev/null; incus move "$i" --storage cow2; done
incus storage volume move cow/dnf-cache cow2/dnf-cache
sudo incus profile device set default root pool=cow2

incus storage show cow | grep -c '/1.0/'       # 0 once nothing uses it
sudo incus storage delete cow                  # removes cow.img
```

Nothing in isx depends on the pool's name: `HostSetup` and `DoctorCommand` only use `cow` when they create one, and the pool probe accepts any btrfs pool.
If SELinux denies Incus the new path, `sudo restorecon -R /var/lib/incus-pool`.
Do not defragment `cow.img` in place instead: it would write the whole file once and fragment again immediately.

### Pool mount options where a loop stays

A host whose filesystem is not btrfs keeps the loop file.
Set the pool's mount options so the pool itself does less work:

```shell
sudo incus storage set cow btrfs.mount_options noatime,commit=300,compress=zstd:1
```

The options apply when the pool is next mounted, so after a reboot.
Compression in the pool is safe because Incus marks `cow.img` no-compress, so the host does not compress the same bytes again.
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

## Instances built from base images before fedora-44-20261010

Those images' network watchdog read the address with `awk`, which the container image lacks, so it restarted systemd-networkd every 30 seconds for the instance's whole life (issue 1657).
An affected instance shows a steady `isx-watchdog: IP mismatch: expected=<ip> current=, restarting networkd` in its journal.

```shell
incus exec <name> -- journalctl -t isx-watchdog --since -1h | wc -l    # about 120 means every run
incus exec <name> -- systemctl disable --now isx-network-watchdog.timer  # stop the loop now
```

Then rebuild the template from a current base image and branch again.
