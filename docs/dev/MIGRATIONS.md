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

## zmx sockets shared under `$TMPDIR/zmx`

On a host with `TMPDIR` set and neither `ZMX_DIR` nor `XDG_RUNTIME_DIR`, isx used to share instance zmx sockets under `$TMPDIR/zmx` (issue 1771).
Host zmx looks in `$TMPDIR/zmx-<uid>`, so `zmx attach isx-<name>` found nothing there.
isx now uses `$TMPDIR/zmx-<uid>`, and each instance's `zmx-sockets` device is moved there the next time isx starts it.
A running instance keeps the old source until then, so stop it and open a shell on it again.
The old directory is no longer read or cleaned up by isx.

```shell
ls "${TMPDIR:?}/zmx/containers"  # instances shared under the old directory
incus stop <name> && isx shell <name>
incus config device get <name> zmx-sockets source   # must no longer name $TMPDIR/zmx
rm -rf "${TMPDIR:?}/zmx"        # once no instance's device names it
```

## zmx sockets moved out of the host zmx dir

isx used to share each instance's zmx sockets and logs under `<zmx dir>/containers/<name>`, in the host zmx dir (issue 1821).
On a host without `ZMX_DIR` and `XDG_RUNTIME_DIR` that is `/tmp/zmx-<uid>`, which another local user can make first.
The source is now `~/.local/state/incus-spawn/zmx/<name>`, and each instance's `zmx-sockets` device moves there the next time isx starts it.
A running instance keeps the old source until then, so stop it and open a shell on it again.
The old `containers/` tree is no longer read or cleaned up by isx; on a tmpfs it goes at the next reboot.

```shell
zmxdir=${ZMX_DIR:-${XDG_RUNTIME_DIR:+$XDG_RUNTIME_DIR/zmx}}
zmxdir=${zmxdir:-${TMPDIR:-/tmp}/zmx-$(id -u)}
ls "$zmxdir/containers"         # instances shared under the old directory
incus stop <name> && isx shell <name>
incus config device get <name> zmx-sockets source   # must name ~/.local/state/incus-spawn/zmx/<name>
rm -r "$zmxdir/containers"      # once no instance's device names it
```

## A user tool whose name is not an identifier

Since issue 1778, isx refuses a tool whose `name:` is not letters, digits, `.`, `-` and `_`, starting with a letter or digit.
That covers user tools in `~/.config/incus-spawn/tools/` and a search path's `tools/`, as well as project-local ones.
Such a tool used to load, so a host may hold one that worked: `c++`, `_internal`, `node@20` or a name with a space.
Every built-in tool and every template in incus-spawn-templates already has such a name.

### How to tell

Any isx command that loads definitions warns once per refused file, and `isx build` refuses until it is fixed.
This section is for a warning that names the `tool name`; one that names `config-namespace` is the namespace rule, which predates it.

```text
⚠ cpp.yaml: tool name 'c++' is not an identifier (letters, digits, '.', '-' and '_', starting with a letter or digit); it is skipped, and isx build refuses until it is fixed
```

```shell
isx tools list >/dev/null      # the warnings go to stderr
```

### Migration

Rename the tool's `name:`, for example `c++` to `cpp`, and the same name in every template's `tools:` entry and every tool's `requires:` entry that names it.
If the tool declares a proxy credential, its accounts live under its `config-namespace`, which the rename does not change.
Then rebuild the templates that use it with `isx build <template>`.
A template built before the rename stored the tool under its old name, and isx skips that stored definition too, so rebuild it rather than wait.

### How to verify

`isx tools list` prints the new name and no warning, and `isx build <template>` runs.

## Shell completion installed before `isx instances` was removed

Completion scripts generated by releases before issue 1036 list instance names with `isx instances`.
That command was deprecated then and is now removed (issue 1802).
Such a script fails quietly: it sends the error to `/dev/null`, so `isx shell <Tab>` and the like offer no instance names.
Only a script saved to a file is affected; `eval "$(isx completion <shell>)"` in a shell's startup file regenerates it each time.

```shell
grep -l 'isx instances' ~/.zsh/completions/_isx ~/.local/share/bash-completion/completions/isx ~/.config/fish/completions/isx.fish 2>/dev/null
```

That covers the paths `isx completion --install` suggests; grep a script saved anywhere else, such as another `$fpath` entry, the same way.
Regenerate each file it lists with the command `isx completion --install` gives for that shell, for example `isx completion zsh > ~/.zsh/completions/_isx`.
Then open a new shell and check that `isx shell <Tab>` offers your instances.

## Shell completion saved before it asked isx

Since issue 1842, the bash, zsh and fish scripts answer every TAB with `isx --aesh-complete`, as the PowerShell one already did.
A script saved to a file by an earlier release still works: it calls `isx account list --completion` and `isx account add --completion`, which stay for it.
But it keeps its own lists of commands and options, so it misses what later releases add, and its zsh version completes nothing after `isx init`, `isx update-all` or `isx mcp`.

```shell
grep -L 'aesh-complete' ~/.zsh/completions/_isx ~/.local/share/bash-completion/completions/isx ~/.config/fish/completions/isx.fish 2>/dev/null
```

Each file it lists is an old script; regenerate it as in the section above, then check that `isx mcp --<Tab>` offers `--caller-instance`.
Once no saved script calls them, the two `--completion` flags can go.

## Every config.yaml holds auto-clone-repos

The `auto-clone-repos` setting is retired (issue 1789), because no command cloned a missing host checkout any more.
Every isx release before then, through v0.5.0-dev.1, wrote the key on each save, so nearly every `config.yaml` holds it, usually as `auto-clone-repos: ""`.
Finding it does not mean anyone set it.
isx ignores its value, whatever it is, and the file still loads.
A save keeps the line as written.

Nothing has to change: the line may stay or be deleted.
Builds behave as before, since the clone path it governed had no caller left.
A host checkout of a template's repository is made by hand, such as `git clone <url>` in a directory of `host-paths`.
`isx update-all` fetches the checkouts that exist, and existing shallow checkouts an earlier isx cloned keep their managed depth.

## A coordinator controls nothing until its template lists what it may

Before issue 1812, an instance branched with `--mcp-client` could use every template `templates` in `mcp.yaml` approves.
It now uses only those that its own template's `coordinator.control-over` lists as well, and none when that lists none.
There is no compatibility path: the feature had almost no users.
When such a coordinator next connects, the workers it held are released as orphans, and destroyed after `orphan-grace-hours` unless adopted.
List the templates before upgrading, or adopt the workers within that time.

### How to tell

`isx doctor` warns `Coordinator <instance>` for each coordinator that controls no template, including one branched with `--mcp-client` from a template with no `coordinator:` block.
It also warns `Coordinator template <name>` for a template that declares `coordinator:` without a usable list.
A coordinator's own `list_templates` comes back empty with a `scope_note` naming the file to edit, and `create_instance` refuses with the same.
The TUI's instance details show `MCP control: nothing` for it.

### What to change

In the definition of the template the coordinator was branched from, list what it may control:

```yaml
coordinator:
  control-over: [tpl-java, tpl-dev]
```

Only the templates also under `templates` in `~/.config/incus-spawn/mcp.yaml` count.
A non-empty list also makes every branch of that template a coordinator by default; `isx-control-capability: false` beside it keeps them plain unless chosen.
Nothing needs a rebuild or a restart: `isx mcp` reads the definition on every call.

### How to verify

`isx doctor` no longer warns for that template, and the coordinator's `list_templates` lists the templates.
