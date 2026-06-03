# VM Appliance

Minimal Alpine Linux VM with Incus pre-installed. See [DESIGN.md](DESIGN.md) for architecture details.

## Prerequisites

**Build:** podman, zstd, make, gcc, flex, bison, bc, libelf-dev, libssl-dev

**Test:** qemu-system-x86_64 (or aarch64), btrfs-progs

## Build

```bash
sudo ./appliance/build.sh appliance/build
```

Produces `rootfs.tar.zst` (~42 MB) and `vmlinuz` (~5 MB). The kernel is cached — delete `vmlinuz` to force a rebuild.

## Run

### Quick boot test (non-interactive, 3 checks)

```bash
sudo ./appliance/test-boot.sh appliance/build
```

### Persistent VM (via vm.sh)

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
# vm.sh stores the disk under root's state directory
sudo rm -f /root/.local/state/incus-spawn/disk.img

# test-boot.sh stores it in the build directory
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
