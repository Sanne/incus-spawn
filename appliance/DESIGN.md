# VM Appliance Design

A minimal openSUSE Tumbleweed VM image with Incus pre-installed, built from a declarative recipe. Serves two purposes:

1. **Integration testing on CI** -- boots in GitHub Actions via QEMU/KVM to validate the full system (Incus daemon, networking) rather than mocking
2. **macOS support** -- runs as an invisible Linux VM via Apple Virtualization.framework so macOS users get native Incus containers

## Build Pipeline

`build.sh` avoids tools that require block devices or KVM. The entire build runs inside a chroot, making it portable across CI runners, containers, and bare metal:

1. Pull the official openSUSE Tumbleweed container image via podman
2. Extract rootfs to a temporary directory
3. Copy overlay files (`root/`) into the rootfs
4. Install packages via chroot (systemd-network, systemd-resolved, Incus, btrfs-progs)
5. Run `config.sh` to enable services, strip bloat, and configure
6. Clean stale state (`/run/*`, `/var/lib/incus/*`) to prevent PID/lock file issues on first boot
7. Pack the rootfs into a zstd-compressed tarball (`rootfs.tar.zst`)
7. Build a custom minimal kernel from vanilla kernel.org source (`kernel/build-kernel.sh`)

Output artifacts: `vmlinuz` (~11 MB), `rootfs.tar.zst` (~99 MB). No initrd.

No disk images are created during build -- the tarball is unpacked into a btrfs disk image on first use (see Disk Lifecycle below).

## Custom Kernel

The appliance uses a custom kernel built from vanilla kernel.org source (`kernel/build-kernel.sh`). Every required driver and subsystem is compiled built-in -- there are no loadable modules and no initrd. The kernel boots directly to the root filesystem.

### Config Fragment (`kernel/isx.config`)

Applied on top of `allnoconfig` via `merge_config.sh`. Both x86_64 and aarch64 options are included in a single fragment; irrelevant options are silently ignored by kconfig.

**Enabled (built-in)**:
- **Virtio**: PCI, MMIO, block, network, console, balloon
- **Filesystems**: btrfs, overlayfs (Incus containers), fuse (lxcfs), tmpfs, procfs, sysfs, devtmpfs
- **Networking**: TCP/UDP/IPv4/IPv6, UNIX sockets, packet sockets, bridge (with VLAN filtering), veth, macvlan, 802.1Q VLANs, netfilter/iptables (NAT, REDIRECT, CHECKSUM, MASQUERADE, conntrack)
- **Container isolation**: all namespace types (including time), cgroups v2 (cpu with CFS bandwidth, io with iocost, memory with zswap, pids, cpuset, hugetlb), seccomp, AppArmor
- **Console**: serial 8250 (ttyS0), AMBA PL011 (ttyAMA0), HVC (hvc0)
- **Block**: loop devices (Incus btrfs storage pool)
- **System**: POSIX timers, file locking, BPF JIT, audit

**Stripped at compile time**:
- All hardware drivers except virtio (no SCSI, SATA, NVMe, USB, GPU, sound, wireless, bluetooth, input, I2C, SPI, GPIO, DMA, IOMMU, hwmon, media, InfiniBand, firewire, thunderbolt, NFC)
- CPU mitigations (`CONFIG_CPU_MITIGATIONS=n`) -- trusted appliance VM; untrusted workloads are isolated in Incus containers
- All filesystems except btrfs, overlayfs, fuse, tmpfs, proc, sysfs, devtmpfs
- All network protocols except TCP/UDP/IPv4/IPv6/UNIX/packet/netlink
- Module support, initrd support, kexec, hibernation, suspend, RAID/MD/DM, ftrace/kprobes

### Config Validation

`build-kernel.sh` validates the config fragment after applying it: every `CONFIG_*=y` option is checked against the generated `.config`. Options silently dropped by kconfig (unknown name or unmet dependency) are reported as warnings. Arch-specific options (e.g., ARM64 UART on x86) are expected to be absent on the other architecture and are harmless.

Key dependencies discovered during development:
- `CONFIG_64BIT=y` -- `allnoconfig` on x86 defaults to 32-bit
- `CONFIG_FILE_LOCKING=y` -- required by lxcfs PID file locking; without it `flock()` returns ENOSYS
- `CONFIG_POSIX_TIMERS=y` -- required by dbus-broker metrics; without it `clock_gettime()` fails with assertion crash
- `CONFIG_FAIR_GROUP_SCHED=y` -- dependency for `CONFIG_CFS_BANDWIDTH` (cgroup cpu.weight/cpu.max)
- `CONFIG_VLAN_8021Q=y` -- dependency for `CONFIG_BRIDGE_VLAN_FILTERING` (Incus bridge)
- `CONFIG_NETFILTER_XTABLES_LEGACY=y` -- dependency for iptables filter/nat/mangle in kernel 7.x
- `CONFIG_NETFILTER_XT_TARGET_CHECKSUM=y` -- required by Incus for DHCP checksum fixup on bridge

### Root Device

Without initrd, the kernel can't resolve `root=LABEL=...` (label resolution requires udev). The kernel cmdline uses `root=/dev/vda` instead -- the virtio-blk device is always `/dev/vda` since there is exactly one disk. `CONFIG_DEVTMPFS_MOUNT=y` ensures `/dev/vda` exists at boot.

## Image Stripping

`config.sh` aggressively reduces image size by removing components unnecessary for headless VM container hosting:

- **GPU/graphics**: LLVM, Mesa, Vulkan, SPIRV, DRI -- pulled in by Incus's QEMU dependency
- **QEMU tools**: Incus bundles its own; host copies removed
- **Container tools**: skopeo, umoci, lego, virtiofsd
- **Scripting runtimes**: Python, Perl
- **Kernel and boot**: entire `/boot` and `/usr/lib/modules` removed (custom kernel is built separately)
- **Hardware databases**: PCI/USB/OUI/Bluetooth hwdb files
- **Package manager**: zypper/rpm removed (appliance is not user-upgradeable)
- **Docs and locale**: man pages, /usr/share/doc, locale data

## Disk Lifecycle

Disk images are not created during build. Instead, the rootfs tarball is unpacked into a btrfs disk image on first use. This two-stage approach avoids the need for block devices during build and lets each environment choose its own disk size.

**On macOS** (via `podman machine ssh`):
```
truncate -s 60G disk.img
mkfs.btrfs -q -L isxroot disk.img        # formats file directly, no loop device
sudo mount -o loop disk.img /mnt          # kernel creates loop device via loop-control
sudo tar xf rootfs.tar.zst -C /mnt
sudo chmod 755 /mnt                       # container rootfs has 700 on /
sudo umount /mnt
```

**On Linux** (direct):
```
truncate -s $SIZE disk.img
LOOP=$(losetup --find --show disk.img)
mkfs.btrfs -q -L isxroot $LOOP
mount $LOOP /mnt && tar xf rootfs.tar.zst -C /mnt
chmod 755 /mnt
umount /mnt && losetup -d $LOOP
```

The `chmod 755` is required because the container rootfs root directory has `drwx------` (700) permissions, which prevents any non-root service (like systemd-networkd running as `systemd-network` user) from traversing the filesystem.

Disk images are sparse: a 60 GB image consumes ~540 MB actual disk space on APFS (macOS) or any CoW filesystem.

## Boot Backends

### vfkit (macOS)

Uses Apple Virtualization.framework via [vfkit](https://github.com/crc-org/vfkit). Direct kernel boot with virtio devices:

```
vfkit --cpus 2 --memory 2048 \
  --bootloader linux,kernel=vmlinuz,cmdline="root=/dev/vda rw rootflags=commit=300 console=hvc0 quiet" \
  --device virtio-blk,path=disk.img \
  --device virtio-net,nat \
  --device virtio-serial,logFilePath=vm.log \
  --restful-uri tcp://localhost:$PORT
```

- No initrd -- kernel has all drivers built-in
- Console on `hvc0` (virtio-serial), not `ttyS0`
- REST API for lifecycle management (stop via `POST /vm/state {"state":"Stop"}`)
- NAT networking with DHCP (interface appears as `enp0s1`)

### QEMU (Linux / CI)

Architecture-aware with KVM acceleration when available:

- **x86_64**: `-machine pc`, `-cpu host -enable-kvm` when `/dev/kvm` exists
- **aarch64**: `-machine virt`, `-cpu host -enable-kvm` when `/dev/kvm` exists
- Console: `ttyS0` (x86_64) or `ttyAMA0` (aarch64)

## VM Management (`vm.sh`)

Lifecycle script with subcommands: `start`, `stop`, `status`, `console`.

**State files** in `~/.local/state/incus-spawn/`:
- `disk.img` -- btrfs disk image (created on first start)
- `vm.pid` -- vfkit/QEMU process ID
- `vm.log` -- serial console output
- `vm.rest-uri` -- vfkit REST API endpoint (macOS only)

**Configuration** via environment variables (overrides adaptive defaults):
- `ISX_VM_DISK` -- disk size (default: `60G`)
- `ISX_VM_CPUS` -- vCPU count (default: host cores - 2, minimum 1)
- `ISX_VM_MEMORY` -- memory in MiB (default: 60% of host RAM, minimum 2048)
- `ISX_GATEWAY` -- Incus bridge gateway IP (default: `10.166.11.1`), passed via kernel cmdline
- `ISX_MITM_PORT` -- MITM proxy port (default: `18443`), passed via kernel cmdline
- `APPLIANCE_DIR` -- path to build artifacts (default: `appliance/build`)

**Stop sequence** (graceful shutdown):
1. Send stop request via REST API (vfkit only)
2. Wait up to 5 seconds for process to exit
3. `SIGTERM`, wait 1 second
4. `SIGKILL` as last resort

## First-Boot Initialization

`incus-spawn-vm-init` runs as a oneshot systemd service after Incus and systemd-resolved are ready. It reads configuration from kernel command line parameters (`isx.gateway`, `isx.mitm_port`, `isx.shared`) and:

1. Waits for the resolved stub resolv.conf (DNS readiness)
2. Waits for the Incus daemon to become ready (up to 30 seconds)
3. Creates the `incusbr0` bridge network with the configured gateway IP and NAT
4. Creates a btrfs storage pool (`cow`) backed by a loop file, adaptively sized (half of free disk, capped at 30 GB, minimum 1 GB)
5. Installs an iptables PREROUTING redirect rule (port 443 -> MITM proxy port) on the bridge interface
6. Symlinks the `isx` binary from the shared directory if available

On subsequent boots, the script detects existing configuration and skips creation steps.

## Testing

### Local (`test-boot.sh`)

Boots the appliance image and verifies four checks:

1. Btrfs root filesystem mounted
2. Incus daemon activated
3. Network online
4. systemd multi-user target reached

Backend selection: vfkit on macOS, QEMU on Linux (with KVM when available). Creates a 4 GB test disk from the rootfs tarball if one doesn't already exist.

### CI (`.github/workflows/`)

**Build** (`build-appliance.yml`): separate jobs for x86_64 (`ubuntu-latest`) and aarch64 (`ubuntu-24.04-arm`). Artifacts cached by content hash of `appliance/**` files. Includes kernel compilation (~3-5 minutes with minimal config).

**Integration** (`test-integration.yml`): restores cached build artifacts, creates a btrfs disk image from the tarball, boots via QEMU with KVM, verifies the VM reaches multi-user target, and runs the Incus smoke test.

### Smoke Test

`incus-spawn-smoke-test` runs as a oneshot service gated by the kernel cmdline parameter `isx.smoke_test=1`. It verifies:

1. Incus daemon is responsive (`incus info`)
2. Storage pool `cow` exists
3. Bridge `incusbr0` exists
4. Container creation works (if image server is reachable)

Output goes to the serial console as `=== SMOKE TEST PASSED ===` or `=== SMOKE TEST FAILED: <reason> ===`. CI checks for this marker.

## Boot Timeline

With the custom kernel (no initrd, no modules) and stripped systemd units:

```
0.019s  kernel exec /sbin/init
0.079s  systemd starts (library loading)
1.120s  systemd ready, begins unit startup
~1.2s   multi-user target reached
```

The 1.1s systemd initialization gap is the dynamic linker loading distro-compiled systemd shared libraries. Sub-second boot would require a custom systemd build.

## Services

The appliance runs a minimal set of services:

- **systemd-networkd** — DHCP on the virtio-net interface
- **systemd-resolved** — DNS stub resolver (receives DHCP nameservers from networkd)
- **dbus-broker** — D-Bus system bus (required by Incus, networkd, resolved)
- **lxcfs** — per-container /proc virtualization (required by Incus)
- **incus** — container daemon
- **incus-startup** — auto-starts containers from previous session
- **incus-spawn-vm** — first-boot setup (bridge, storage pool, iptables)
- **serial-getty@hvc0** — serial console for debugging via `vm.sh console`

No SSH, no logind, no polkit. The appliance is headless and accessed only through the Incus API.

## Boot Diagnostics

`incus-spawn-diag` runs as a oneshot service triggered by a systemd timer 30 seconds after boot. It dumps diagnostic information to the serial console (auto-detected: `ttyS0` on QEMU, `hvc0` on vfkit, `ttyAMA0` on aarch64 QEMU):

- Failed systemd services
- Full journal for lxcfs, incus, incus-spawn-vm, dbus-broker

Output is always available in `vm.log` (vfkit) or the QEMU serial console log. Zero overhead -- runs once, no persistent process.

## Debugging

### Serial console login

The appliance has passwordless root login enabled on serial consoles (`ttyS0` for QEMU, `hvc0` for vfkit). On QEMU, connect with `-serial stdio` and press Enter at the login prompt.

### Manual QEMU boot (bypassing test-boot.sh)

`test-boot.sh` filters output through `grep`, which can appear stuck due to buffering. For debugging, boot QEMU directly:

```
sudo timeout 120 qemu-system-x86_64 \
  -machine pc -enable-kvm -cpu host \
  -m 2048 -nographic -no-reboot -nodefaults -serial stdio \
  -kernel appliance/build/vmlinuz \
  -drive file=appliance/build/disk.img,format=raw,if=virtio \
  -netdev user,id=net0 -device virtio-net-pci,netdev=net0 \
  -append "root=/dev/vda rw console=ttyS0"
```

Note: the `-netdev` line is required for `incus-spawn-vm-init` to succeed -- without networking, dnsmasq cannot bind its DHCP socket to the bridge interface.

### Inspecting the disk image

```
sudo mount -o loop appliance/build/disk.img /mnt/isx-disk
# inspect files...
sudo umount /mnt/isx-disk
```

If mount fails with "failed to setup loop device", clean stale loop devices first: `sudo losetup -D`

### Creating a fresh disk from tarball

```
sudo bash -c '
truncate -s 4G appliance/build/disk.img
LOOP=$(losetup --find --show appliance/build/disk.img)
mkfs.btrfs -q -L isxroot "$LOOP"
mkdir -p /mnt/isx-disk
mount "$LOOP" /mnt/isx-disk
zstd -d appliance/build/rootfs.tar.zst --stdout | tar xf - -C /mnt/isx-disk
chmod 755 /mnt/isx-disk
umount /mnt/isx-disk
losetup -d "$LOOP"
'
```

### Kernel config validation

After modifying `kernel/isx.config`, validate for both architectures in a container:

```
podman run --rm -v $PWD/appliance:/appliance:ro fedora:44 bash -c '
dnf install -y -q make gcc flex bison bc findutils
tar xf /appliance/build/linux-*.tar.xz -C /tmp/ks --strip-components=1
cd /tmp/ks
for ARCH in x86 arm64; do
    echo "=== $ARCH ==="
    KCONFIG_ALLCONFIG=/appliance/kernel/isx.config make -s ARCH=$ARCH allnoconfig
    make -s ARCH=$ARCH olddefconfig
    grep "^CONFIG_" /appliance/kernel/isx.config | grep "=y" | while read line; do
        key=${line%%=*}
        grep -q "^${key}=y" .config || echo "  NOT APPLIED: $line"
    done
done
'
```

Arch-specific options (ARM64 UART on x86, ACPI on arm64, KERNEL_ZSTD on arm64) are expected to be absent on the other architecture.

### Enabling serial console login (`enable-console.sh`)

Patches a disk image for passwordless root auto-login on serial consoles:

```
sudo ./enable-console.sh appliance/build/disk.img
```

Creates auto-login getty units for ttyS0 and ttyAMA0, removes the root password, and configures PAM. After patching, boot with QEMU `-serial stdio` and press Enter to get a root shell.

### Zypper RPM caching

RPM downloads are cached in `/var/cache/incus-spawn-zypp` (configurable via `ZYPP_CACHE_DIR`). First build downloads ~1.5 GB; subsequent builds reuse cached RPMs. Clear the cache to force fresh downloads: `sudo rm -rf /var/cache/incus-spawn-zypp`
