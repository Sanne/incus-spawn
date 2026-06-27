# VM Appliance

Minimal Alpine Linux VM with Incus pre-installed. See [DESIGN.md](DESIGN.md) for architecture details.

## Prerequisites

**Build:** podman (the build runs inside a Linux container automatically)

**Test:** qemu-system-x86_64 (or aarch64), btrfs-progs

## Build

```bash
./appliance/build.sh
```

Produces `rootfs.tar.zst` (~42 MB), `disk.img.gz`, and `vmlinuz` (~5 MB). The kernel is cached — delete `vmlinuz` to force a rebuild.

On macOS the build runs in a podman container that cannot create loop devices, so `build.sh` assembles `disk.img.gz` on the host afterwards via the podman machine VM (this requires `podman machine` to be running: `podman machine start`).

## Testing with `isx` (macOS — the real client path)

This is how to test a freshly-built appliance the way macOS users actually run
it — `isx` boots the VM itself (wiring up the vsock forwarder, the virtio-fs
host mount, and the data/swap disks) and connects to the Incus daemon over
vsock.

> **macOS only.** On Linux, `isx` connects to a *natively installed* Incus over
> `/run/incus/unix.socket`; the appliance VM (QEMU) is not wired for vsock, so
> there's nothing for `isx` to connect to. On Linux, test the appliance image
> standalone with `test-boot.sh` or `vm.sh` (see below) — `build.sh` works the
> same and produces `disk.img.gz` directly (loop devices work in the build
> container there).

```bash
./appliance/build.sh
./appliance/test-with-isx.sh        # add --keep-data to reuse the data disk
isx                                 # launch the TUI against the new build
```

`test-with-isx.sh` stops any running VM, clears the previously extracted root
disk so the new build is used, recreates the data disk, points `isx` at
`appliance/build` via `ISX_APPLIANCE_DIR`, boots it with `isx vm start`, and
waits until the daemon is reachable.

The data disk holds Incus's state (`/var/lib/incus`). A data disk written by a
*different* appliance build can leave `incusd` unable to start on the fresh
rootfs (`ERROR: Incus daemon not ready after 30s` in the console), so the script
recreates it by default. Pass `--keep-data` when iterating on the same build and
you want your containers to survive.

> **Do not use `vm.sh` to test the `isx` connection.** `vm.sh` boots the
> appliance *standalone* for interactive/console testing only. It intentionally
> omits the vsock forwarder device, the host mount, the data/swap disks, and the
> `isx.*` kernel parameters, so a VM started by `vm.sh` can never be reached by
> `isx`.

## Run

### Quick boot test (non-interactive, 3 checks)

```bash
# Linux (requires root for disk image creation on first run)
sudo ./appliance/test-boot.sh

# macOS
./appliance/test-boot.sh
```

### Standalone VM (via vm.sh)

For booting the appliance on its own — without the `isx` integration (vsock,
host mount, data disk). Useful for inspecting boot behaviour or the serial
console. **Not reachable by `isx`** — use `test-with-isx.sh` for that.

```bash
sudo ./appliance/vm.sh start    # creates disk on first run, boots VM
sudo ./appliance/vm.sh console  # read-only log tail
sudo ./appliance/vm.sh status
sudo ./appliance/vm.sh stop
```

### Interactive serial console

```bash
sudo ./appliance/vm.sh shell
```

Boots the VM with an interactive serial console. Log in as `root` (no password). Exit with `Ctrl-A X`. Requires stopping a background VM first (`vm.sh stop`).

## Removing a previous disk image

The disk image is created once from the rootfs tarball and reused on subsequent boots. After rebuilding the appliance, **you must delete the old disk image** or the VM will boot the stale rootfs:

```bash
# macOS — vm.sh stores the disk under your home directory
rm -f ~/.local/state/incus-spawn/disk.img

# Linux — vm.sh is typically run as root, so the disk is under root's state directory
sudo rm -f /root/.local/state/incus-spawn/disk.img

# test-boot.sh stores it in the build directory (both platforms)
rm -f appliance/build/disk.img
```

## Troubleshooting

From the host (no login required):

```bash
sudo ./appliance/diag.sh
```

This boots the VM in diagnostic mode, prints process list, kernel messages, Incus status, network and DNS configuration, then exits. The disk is not modified (`snapshot=on`). Ask users to paste the output when reporting issues.

From inside an interactive session:

```bash
incus-spawn-diag
```

## Testing a container inside the VM

```bash
incus launch images:alpine/edge test
incus exec test -- cat /etc/alpine-release
incus delete test --force
```
