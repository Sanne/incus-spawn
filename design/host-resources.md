# Host resources

How host files and directories reach an instance, and what a project-local template may reach.

## Host Resources

Template images can declare host files and directories to share with containers via the `host-resources` YAML key.
Three modes control how the resource is made available:

**Readonly** (default): a read-only Incus disk device bind mount.
The container can read the host file/directory but cannot modify it.
Simple and safe for config files like `~/.gitconfig`.

**Overlay**: the host directory is attached as a read-only lower layer, with an ephemeral writable upper layer inside the container, combined via Linux overlayfs.
The container sees a normal read-write directory, but writes go to the container-local upper layer — the host is fully protected.
This is the right mode for caches (Maven, OCI) where tools expect to write but you don't need writes to persist back to the host.

**Copy**: the file or directory is copied into the container at build time and becomes part of the template.
Supports local paths and URLs.
No runtime dependency on the host.

An entry takes `source`, `path` and `mode`; any other key is ignored with a warning when the template's file is loaded (#1543, as for any key isx does not read, #1546), and so is a `confined-to` the file sets, which isx sets itself (#765).
`isx templates edit` also reports them in its check of the edited file (`TemplateValidator`), so a file with one is never called valid.
The edit flow loads every definition again after the edit, so it leaves that load's warnings about the edited file to the report, and a key added in the edit is named once.
It is a warning and not a refusal, so a template that built before still builds.
It is a warning at all because an ignored key changes the build: a misnamed `path:` (`target:`, `dest:`) puts the resource at the source's path, and when `path` is unset the warning suggests it.

### Overlay internals

For a host-resource with `mode: overlay` targeting `/home/agentuser/.m2/repository`:

1. **Build time**: the host directory is attached as a read-only Incus disk device at `/var/lib/incus-spawn/overlays/home/agentuser/.m2/repository/lower`, before the build instance starts (see "Devices are attached before start" below).
   Once it is up, the container creates `upper` and `work` siblings, then runs `mount -t overlay` to present the merged view at the target path.
   A systemd service (`incus-spawn-overlays.service`) is installed and enabled to re-apply the overlay mount on boot.

2. **After build**: the overlay is unmounted and the disk device is removed from the stopped template.
   The upper and work directories remain as container-local files.
   The full host-resource configuration is stored as JSON in `user.incus-spawn.host-resources` metadata.

3. **At branch time**: `BranchCommand` reads the stored metadata and re-attaches the disk device to the stopped instance before starting it.
   On boot, the systemd service re-mounts the overlay.
   The upper layer — now containing build artifacts — was copied via CoW when the instance was branched, so each instance has its own independent writable layer.

4. **On reboot**: the systemd service fires on every boot and re-mounts overlays.
   This works even if the container is started directly via `incus start` rather than through `isx`.
   The unit is ordered `After=incus-agent.service`: in a VM the lower layers are virtiofs shares, which incus-agent mounts before it signals ready (`Type=notify`).
   Without that ordering the service could run first and overlay an empty lower directory, hiding the host content until the next reboot.
   Containers have no such unit, so the ordering is a no-op there.
   Templates built before this ordering existed pick it up on rebuild.

5. **Derived builds**: a template built from a parent with overlays boots with the parent's service, so the overlays are already mounted when `applyForBuild` gets to them.
   The build-time mount checks the target with `findmnt` and leaves an existing overlay in place.
   Stacking a second one would leave one behind when `removeBuildDevices` unmounts once.

The overlay directory structure mirrors the container path directly under `/var/lib/incus-spawn/overlays/`, so the layout is self-documenting:

```
/var/lib/incus-spawn/overlays/home/agentuser/.m2/repository/
  ├── lower/    ← read-only disk device mount (host directory)
  ├── upper/    ← container-local writable layer (follows CoW branching)
  └── work/     ← overlayfs internal bookkeeping
```

Incus disk device names are derived from the container path for readability (e.g. `hr-home-agentuser--m2-repository`).
They are removed from stopped templates to avoid host-path dependencies and re-attached at branch time.

### Devices are attached before start

Every disk device a build or branch attaches from the host is added while the instance is still **stopped**: the `readonly` mounts, the `overlay` lower layers and the DNF cache volume (`HostResourceSetup.attachBuildDevices` and `GuestProvisioning.attachDnfCache`, both in the create→start window of `buildFromScratch` and `buildFromParent`), the host resources re-attached at branch time (`integrateWithHost`, already before `prefetchAndStart`), and the `--inbox` directory (`InstanceLifecycle.attachInbox`).
Only the in-guest work waits for start: `copy` pushes, the `mount -t overlay` over the attached lower layer, and `installOverlayService`, all in `HostResourceSetup.applyForBuild` (#828).
The reasons:

- **VMs can hot-plug only 8 devices.**
  Incus's QEMU driver gives each device present at start its own PCIe root port, then allocates exactly 8 spare ports for hot-plugging.
  Measured on Incus 6.23: a VM created with 40 directory disks boots with all 40 mounted and all 8 hotplug slots still free.
  So a boot-time device costs nothing from that pool, while each hot-plug into a running VM takes a slot.
  Repo references (#826) are hot-plugged mid-build, because each one is detached as soon as its clone finishes.
  Each worker attaches its own just before its clone, under a slot budget (see design/git.md "Parallel cloning"), so more references than slots take turns rather than lose.
  Host resources and the DNF cache used to be hot-plugged too, which pushed a template with 8 repo references past the limit.
  Attached before start, they leave all 8 slots to repo references.
  The inbox moved for the same reason: it used to leave a running instance with 7.
- **Nothing has to wait for a VM mount.**
  A hot-plugged virtiofs share is mounted by incus-agent asynchronously, so the build used to poll `mountpoint -q` for up to 15 seconds per overlay and for the DNF cache, with a degraded fallback for each timeout.
  Boot-time shares are different: incus-agent mounts everything in its `agent-mounts.json` synchronously, *before* it starts the server every `exec` goes through (`cmd/incus-agent/main_agent.go`).
  Once a build can run anything in the guest, the mounts are there.
  Both waits, and their timeout branches, are gone.
  The agent still only *logs* a share it fails to mount, so the overlay mount checks `mountpoint -q` on its lower layer in the same exec, without waiting, and warns instead of silently overlaying an empty directory.
  The DNF cache needs no such check: `cleanCaches` already refuses to clean through a live mount, and an unmounted cache only makes the build slower.
- **A missing source is skipped, not attached.**
  Incus refuses to start an instance whose disk `source` is missing (`Missing source path`), so `attachBuildDevices` attaches only sources that exist.
  `applyForBuild` then warns about the rest once the instance is up.
  That is the same warn-and-skip as "Missing sources" below, and the reason `prepareForStart` exists for later starts.
  That pre-start repair reads the instance once and fixes every device isx added: host resources whose source is gone (`removeStaleDevices`), the zmx socket directory, and the `--inbox` device (`removeStaleInbox`, #854).
  The inbox has no entry in `user.incus-spawn.host-resources`, so it needs its own check.
  A deleted inbox directory is removed from the instance with a warning, since the user asked for that mount.
  Its source is kept in `user.incus-spawn.inbox-source`, written in the same update that removes the device, and the first start that finds the directory back mounts it again, says so, and clears the key (#1139).
  The warning used to name an `incus config device add`, which the macOS host has no CLI to run; now it says to recreate the directory, which the next start mounts.
  The check maps the device source back to a host path (`hostPathOfDeviceSource`, the inverse of the macOS `/host` translation), and skips a macOS source outside the shared home, which the host cannot check.
  The TUI starts an instance while it still owns the terminal, where a stderr warning would be drawn over and lost, so it passes `InstancePrep.repairBeforeUse` its warning log as the sink for the start and the static-IP repair alike.
  The log announces them on the status line when the shell returns to the TUI, and keeps them for the `w` dialog.
  A plain `incus start` still fails on such a device; isx cannot intercept it.
  The zmx device is the exception: it is added with `required=false`.
  A start that bypasses isx (`incus start`, `boot.autostart`) skips a missing source, and the guest has no host-visible zmx sockets until isx itself starts the stopped instance and repairs the device first (#1044).
  An instance already running, such as one `boot.autostart` brought up after a reboot, keeps running without the mount until it is stopped and started through isx.
  The device's source is the instance's own directory, `~/.local/state/incus-spawn/zmx/<name>`, not the host zmx dir (#1821).
  zmx's own dir falls back to `/tmp/zmx-<uid>` without `ZMX_DIR` and `XDG_RUNTIME_DIR`, and another local user can make that first.
  So the guest's sockets and logs stay where only the user can reach them, and the source no longer follows the environment of whichever isx started the instance last.
  isx makes `zmx/` and each `<name>` mode `0700` and requires both to be the user's alone (`OwnDirectory`, shared with host-route secrets) when it adds the device and on every start, at a few stats and no request.
  A `zmx/` that grants group or others any access is set back to `0700` at the same points and on every shell: the guest owns `<name>` through the shifted mount and can open it to anyone, so `zmx/` is what keeps other users out of its sessions and logs.
  When they are not, the instance gets no device, says why, and is stamped `user.incus-spawn.zmx-withheld`; the first start that finds the directory right adds the device back and clears the stamp.
  The host zmx dir holds only the absolute `isx-<name>` links, so host `zmx list` and `zmx attach` need no configuration.
  Every other session socket in the instance's directory is linked as `isx-<name>.<session>` (#2059), each time `isx shell` or the TUI opens a shell and again when it returns, so a session the guest named itself shows up too.
  An instance name has no dot, so `isx-<name>.` is that instance's alone, and destroy never removes another instance's links.
  The guest chooses these names, so only an entry that is itself a socket (`lstat`, never a link it made) with a name `ShellSession.NAME` accepts is linked, at most `MAX_SESSION_LINKS` (64) of them, the first by name, and a link whose socket is gone is dropped.
  The check holds only when the link is made: the guest can later swap the socket for a link of its own, as it can the `isx` socket, so the targets carry the same trust the `isx-<name>` link always had.
  isx makes that dir `0700` when it is missing, and links only from one that is the user's alone; otherwise the shell warns and the device stays, since its directory is private.
  Destroy and rename remove the instance's directory with everything zmx wrote in it, its `logs/` included, and follow no symlink (#1772).
  They walk it only through a `zmx/` that is the user's alone; otherwise the directory stays and they warn.
  The pre-start repair also re-adds a device from before that change, which costs no request when the device is already optional.
- **The attach is quiet.**
  It runs inside the "Launching…"/"Deriving…" progress line, so the per-resource "Mounted …" notes and warnings come from `applyForBuild` after start, and a DNF cache failure is returned by `attachDnfCache` and printed once the step is done.
- **The agent's home may already exist.**
  A mount under `/home/agentuser` makes Incus (or incus-agent) create the directory, as root, before a from-scratch build on an image without `agentuser` gets to `useradd -m`.
  `useradd` then succeeds but silently skips `/etc/skel`, and a `chown -R` of the home fails on the read-only mount.
  So user creation runs `BuildCommand.AGENT_HOME_OWNERSHIP`.
  It copies skel with `cp -an` (no clobbering) and ignores the copy's exit status, because GNU coreutils 9.2 to 9.4 exit 1 whenever `-n` leaves a file alone, which after a normal `useradd -m` is every file (#981).
  Only the copy is best-effort: the ownership and mode steps after it still fail the build.
  It then chowns everything except mount points (which belong to the host), and sets the home to the `0700` that `useradd -m` would have given it (a directory created by a mount is `0755`).
  On a fresh home this does exactly what `useradd -m` plus `chown -R` did.
- **Host resources are not best-effort; repo references are.**
  A repo reference has a correct fallback, a network clone that is slower but gives the same result, so #826 degrades to it when slots run out, saying so on the repo's progress line.
  A host-resource mount *is* what the template declared.
  Skipping one would hand the user an instance missing a directory, and they would find out later in confusing ways.
  So a failed host-resource attach fails loudly (`deviceAdd` throws).
  The design keeps it off the hotplug pool so that this does not happen.
  Any future attach against a *running* instance must keep failing loudly on slot exhaustion rather than warn and skip.

### Mounts may not target system directories

Attaching before start means a from-scratch build has its host resources mounted from first boot, before `dnf upgrade`, user creation and every boot-time service.
A read-only mount over a path the system writes to would break those steps, where it used to be added only after them.
So `HostResourceSetup.requireAllowedMountTarget` rejects a `readonly` or `overlay` target of `/` or anything under `/etc`, `/usr`, `/bin`, `/sbin`, `/lib`, `/lib64`, `/boot`, `/var`, `/run`, `/tmp`, `/proc`, `/sys` or `/dev` (`FORBIDDEN_MOUNT_ROOTS`).
The package-managed trees are there because dnf writes them.
`/var`, `/run` and `/tmp` are there because services write them at boot, and the virtual filesystems because a mount there never makes sense.
The target is checked after normalizing, so `/opt/../etc` does not slip through.
The rules:

- **`copy` is not limited.**
  It writes files rather than mounting, so a template may still place `/etc/containers/storage.conf` that way.
- **Checked on the effective list.**
  `collectEffective` enforces it after merging the chain, so a child that overrides a parent's `/etc` mount with `copy` builds fine.
  The build preflight reports it before anything is created, and `TemplateValidator` shows the same error while the file is being edited.
- **Branching from an older template fails.**
  A template built before the rule can still carry such a mount in `user.incus-spawn.host-resources`.
  `requireBranchableTemplate` refuses to branch from it before `incus copy`, so no half-made branch is left behind, and the error says to fix and rebuild the template.
  `applyForInstance` checks again before attaching anything, as a backstop.
- **Existing instances still start.**
  The start path (`prepareForStart`) does not check: such an instance has been working with that mount, and failing its start would be a surprise with no rebuild on that path.
- **An absolute source without `path:` is also its target.**
  A resource that relies on that default and lives under one of these roots (a project checked out under `/tmp`, say) needs an explicit `path:`.

### VM behavior

File-level host resources (individual files rather than directories) automatically fall back to `copy` mode on VMs, since Incus disk devices only support directory mounts for virtual machines.
The fallback is logged: "VM: falling back to copy mode for file ...".

### Missing sources

If a host path doesn't exist at build or branch time, the entry is skipped with a warning.
The build/branch proceeds without it.

### Inheritance

Host resources compose additively across the parent chain, with override-by-container-path.
If a parent declares `~/.gitconfig` as `readonly` and a child declares `~/.gitconfig` as `copy`, the child's mode wins.
This follows the same last-write-wins pattern as package deduplication.

### Project-local templates are confined to their project

A project-local template (`.incus-spawn/images/`) is whatever the cloned repository shipped, and the same repository controls the template's `prime` commands and tools, which have network access.
If it could name any host path, running `isx build` in a cloned directory would be enough to copy `~/.ssh` into a template that every branch inherits, bypassing the credential isolation isx exists for (#765).
So `ImageDef.loadFromDirectory` stamps project-local definitions with their `projectRoot` (never read from YAML), and `HostResourceSetup.collectEffective()` confines each of their non-URL sources to that directory.
Trusted layers (user, search paths, built-in) are unaffected, and so are a project-local template's trusted ancestors.

- **Checked on real paths.**
  The source is resolved against the project root, then through its symlinks.
  For a path that does not exist yet, that means its longest existing prefix, and dangling links are followed to their targets.
  So `~`, absolute paths, `..`, and symlinks all get the same treatment.
  For `copy` of a directory the tree is walked too, because the host-side push follows file symlinks.
  Mounts need no walk: symlinks inside a mount resolve inside the container.
- **Rewritten to the checked path.**
  The effective resource carries the absolute real source, an explicit container path, and `confined-to: <root>`.
  A relative source would otherwise be re-resolved against the CWD of a later `isx branch`, so `source: .ssh` checked as `<project>/.ssh` would become `~/.ssh` when branching from home.
  `collectEffective` rebuilds every entry, so a `confined-to` written in YAML is discarded.
- **Re-checked at every use.**
  The project is a working tree, and a later `git pull` can turn a checked directory into a symlink.
  `verifyConfined()` runs again in `attachBuildDevices` and `applyForBuild`, `applyForInstance` (branch), and `removeStaleDevices` (every start).
  There a violation removes the device and warns instead of failing the start.
- **Enforced before the build starts.**
  `BuildCommand.buildSingleImage` calls `collectEffective` before creating anything, so a rejected template leaves no half-built container.
  The `isx templates new/edit --project` validator reports the same error while the file is still open.
- **Covers `file://` base images too.**
  `image_url`/`vm_image_url` accept `file://` (the incus-spawn-images local-testing workflow), so the build preflight applies the same check to a project-local template's resolved base image URL (`HostResourceSetup.requireConfinedBaseImages`).
  The template check reports it on `image_url` or `vm_image_url`, so `isx templates validate` and `isx templates new --from` never pass what the build refuses (#2095).
- **No GUI by default.**
  A branch's GUI passthrough mounts the host's Wayland and audio sockets and hands over its GPU, so a project-local `gui: true` -- the definition's own, or any the source was built from (`BuildSource.usedProjectLocal()`) -- does not turn it on by default.
  `BranchFlow.defaultsFor()` leaves it off with a note, and `--gui` still turns it on (#869).
- **Survives rebuilds from metadata.**
  `BuildSource` records `projectRoots` alongside `sources`, so a template rebuilt out of scope from `build-source` stays confined.
  For metadata written before the field existed, the root is inferred from a `…/.incus-spawn/images/*.yaml` source.

### Project-local templates never touch host checkouts

A project-local template's `repos:` get no host reference mount, and `HostRepoRefresh.collectAllRepos()` leaves them out of the host-side fetch.
Otherwise a cloned repository could list the URL of a repo you have checked out and get your working tree mounted into a build it controls.
Their repos are cloned from the network inside the container, which is slower but reaches nothing of the host's.
Trusted ancestors of a project-local template keep their references.

### Project-local templates cannot swap shared base images

Local image aliases (`fedora-44-base`) are shared by every template on the host.
Before this, a project-local template could declare `image: fedora-44-base` with its own `image_url` and the genuine `image_tag`.
The build replaced your image with the attacker's rootfs, and every later trusted build reported it "up to date" and ran on it.
So every import is stamped with the `incus-spawn.project` image property: the importing project's root, or empty for a trusted definition.
The stamp is written after import, so a value shipped in the tarball's own metadata never survives, and it is read back before the alias is created, because `setImageProperty` fails silently and an unstamped project import would pass as trusted.
The rules:

- **A project-local template may only replace an image its own project imported.**
  Anything else fails the build with instructions to use its own image name.
- **A trusted template with an `image_url` re-imports a project-owned image even when the tag matches.**
- **`requireImageTrustedFor()` runs just before launch.**
  A build may only start from a local image stamped empty or with its own project.
  This covers trusted templates that name an alias without an `image_url` and so never reach the import path.
  Images imported before the stamp existed read as trusted; the check cannot tell whether one of them was already swapped.
- **Every provenance lookup fails closed.**
  They use `imageAliasTargetOrThrow()`/`imagePropertyOrThrow()`, where only an explicit 404 or a missing property means "absent".
  The lenient `imageAliasTarget()`/`getImageProperty()` return null on any failure, and a transient Incus error would otherwise read as "imported by a trusted definition".

### Host-side downloads reach only remote hosts

Every `DownloadCache.download()` runs on the host on behalf of a definition, and the result goes into a container.
This covers YAML tool downloads, Java tool setups, `copy` host-resources with a URL source, and appliance images.
So it applies to all definitions, trusted or not:

- **Only `http(s)`.**
  `file://` would copy host files for any tool, and no tool needs it: a local artifact belongs in a host-resource.
  Base images are the one legitimate `file://` user, and they go through `downloadAllowingLocalFile()` instead.
- **No host-local addresses.**
  Hosts resolving to loopback, the wildcard address, or link-local (which includes the `169.254.169.254` cloud metadata service) are refused.
  Those reach services only the host can see, such as the isx proxy, local dev servers, and instance credentials.
  Private LAN ranges stay allowed, since internal mirrors are legitimate.
- **Redirects checked hop by hop.**
  `HttpClient` runs with `Redirect.NEVER`, and `fetch()` follows up to 10 hops itself.
  Each hop goes through the same check and the same no-https-downgrade rule as `Redirect.NORMAL`, because a public URL could otherwise redirect to `127.0.0.1`.
  The check comes before the cache lookup.
- **Retries start over, and are checked again.**
  A 429, 500, 502, 503 or 504, or a connection that fails or breaks mid-body, is retried four times (after 2s, 8s, 30s, then 60s; a longer `Retry-After` is honoured up to 60s).
  That is because GitHub release downloads have been seen to answer 500 transiently, around when a new base image had just been published and was first pulled, and a CI runner to be unable to connect to github.com for 100s (#1572).
  With those 30s connect timeouts the attempts span about four minutes, while a connection refused at once gets the 100s of pauses; a name that does not resolve is not retried, so a machine without DNS still fails at once.
  Each attempt starts again at the original URL and runs the host-local check on it again just before connecting.
  A retry comes seconds to minutes after the first check, by when the JDK's address cache may have let a rebinding domain move to `127.0.0.1`.
  Starting over also means a short-lived signed redirect target is never reused.
  What another attempt cannot fix fails at once: other statuses, these rules' refusals, an unresolvable name, a TLS handshake or certificate failure, a malformed response, and anything on this side of the connection (a failed write to the cache, a throwing `Listener`).
  `CountingSubscriber` tells the two apart once the body has started, since only the connection signals `onError`.
  The error names each distinct failure with its host, since behind a redirect the failing host is not the one in the URL.
- **A broken body is resumed, checked by isx rather than by the server.**
  When a `200`'s body breaks off, the next attempt keeps the partial file and asks for the rest with `Range: bytes=<size>-` and `If-Range`.
  It still starts at the original URL, so the host-local check and a fresh signed redirect target come with it; the `Range` goes on every hop, since only the answer says which hop serves the file, and a redirect ignores it.
  `If-Range` is not relied on: GitHub's asset store answers `206` to a mismatched one (measured for #900).
  So only a `206` that carries the first response's validator, a `Content-Range` from the byte on disk to the end of the same length, and the matching `Content-Length` is appended.
  A `200` replaces the partial file; a `416` or any other `206` starts over from byte 0 in the same attempt, without using up a retry.
  A download is resumable only when its `200` had a validator a `206` can be checked against (a strong `ETag`, or a `Last-Modified` at least a second older than its `Date` when there is no `ETag`, the same rule as the proxy's `CachingDownload`), a length, and no `Content-Encoding`; otherwise it restarts from byte 0.
  That matters most for downloads without a `sha256` (appliance artifacts, some tools), which have only this check.
  Every large download (base images, the macOS appliance VM artifacts, tool tarballs, the Claude binary) goes through `DownloadCache`, and one that moves off it should keep its retries and resume rather than grow its own.
- **DNS rebinding cannot split check from connect.**
  A rebinding domain answers with a public address for the check and `127.0.0.1` for the connection.
  But both resolve through the JDK's in-process address cache, which keeps a successful lookup for 30 seconds whatever TTL the DNS answer carries, so the connection a few milliseconds after the check sees the same address.
  In the native image that policy is fixed at image build time (`sun.net.InetAddressCachePolicy` is build-time initialized).
  Measured on GraalVM 25: `-Dsun.net.inetaddr.ttl=0`, `-Dnetworkaddress.cache.ttl=0` and `JAVA_TOOL_OPTIONS` at runtime all leave it at 30s, and only the same flag passed to `native-image` changes it.
  So never pass `sun.net.inetaddr.ttl` or `networkaddress.cache.ttl` to the native build.
  On the JVM build, only whoever launches isx can lower it.

This follows `ToolProxyResolver.rejectProjectLocalProxy()`: project-local definitions get the capabilities that stay within the project, and anything that reaches the rest of the host belongs in a location the user owns.
