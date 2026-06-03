#!/bin/bash
# Post-install script — runs inside the chroot during image build.
# Configures the minimal appliance for running Incus containers.

set -euo pipefail

#-- Create system users from sysusers.d (systemd-network, etc.) --#
# Must run before we strip systemd-sysusers.service from the image.
systemd-sysusers

#-- Systemd: enable only essential services --#
# SYSTEMD_OFFLINE=1 so systemctl works inside a chroot without a running systemd
export SYSTEMD_OFFLINE=1

systemctl set-default multi-user.target
systemctl enable systemd-networkd
systemctl enable systemd-resolved 2>/dev/null || true
systemctl enable incus.service
systemctl enable incus-spawn-vm.service
systemctl enable incus-spawn-diag.timer
systemctl enable incus-spawn-smoke-test.service

#-- Systemd: mask units that are part of systemd itself (can't remove) --#
systemctl mask systemd-homed
systemctl mask systemd-userdbd
systemctl mask systemd-pstore.service
systemctl mask systemd-networkd-wait-online.service
systemctl mask systemd-fsck-root.service
systemctl mask systemd-fsck@.service
systemctl mask getty@tty1
systemctl mask remote-fs.target
systemctl mask sys-kernel-debug.mount
systemctl mask sys-kernel-tracing.mount
systemctl mask dm-event.socket
systemctl mask dm-event.service
systemctl mask systemd-logind.service

#-- Network: systemd-networkd DHCP on all en* and eth* interfaces --#
mkdir -p /etc/systemd/network
cat > /etc/systemd/network/20-wired.network << 'EOF'
[Match]
Name=en* eth*

[Network]
DHCP=yes

[DHCPv4]
UseDNS=yes
EOF

#-- DNS: use systemd-resolved if available, static fallback otherwise --#
if [ -f /usr/lib/systemd/system/systemd-resolved.service ]; then
    ln -sf /run/systemd/resolve/stub-resolv.conf /etc/resolv.conf
else
    cat > /etc/resolv.conf << 'DNSEOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
DNSEOF
fi

#-- UsrEtc: openSUSE ships defaults in /usr/etc/, services expect /etc/ --#
ln -sf /usr/etc/nsswitch.conf /etc/nsswitch.conf
ln -sf /usr/etc/login.defs /etc/login.defs

#-- FUSE: lxcfs needs fusermount (only fusermount3 is installed) --#
ln -sf fusermount3 /usr/bin/fusermount

#-- Incus: pre-configure subuid/subgid --#
grep -q "^root:100000:65536$" /etc/subuid 2>/dev/null || echo "root:100000:65536" >> /etc/subuid
grep -q "^root:100000:65536$" /etc/subgid 2>/dev/null || echo "root:100000:65536" >> /etc/subgid


#-- Remove packages not needed in a headless VM appliance --#
rpm -e --nodeps --noscripts 2>/dev/null \
    dracut \
    plymouth \
    wicked wicked-service \
    ModemManager \
    rdma-core rdma-ndd \
    rsync \
    e2fsprogs \
    kbdsettings \
    rpmconfigcheck \
    || true

#-- Strip binaries and shared libraries --#
find /usr/lib64 /usr/lib /usr/sbin /usr/bin -type f \( -name '*.so*' -o -executable \) \
    -exec strip --strip-unneeded {} \; 2>/dev/null || true

#-- Remove Incus deps pulled in but not needed for headless container hosting --#
rm -f /usr/lib64/libLLVM*.so* 2>/dev/null || true
rm -f /usr/lib64/libgallium*.so* 2>/dev/null || true
rm -f /usr/lib64/libvulkan*.so* 2>/dev/null || true
rm -f /usr/lib64/libMesa*.so* 2>/dev/null || true
rm -f /usr/lib64/libSPIRV*.so* 2>/dev/null || true
rm -f /usr/lib64/libcapstone*.so* 2>/dev/null || true
rm -f /usr/lib64/libpython*.so* 2>/dev/null || true
rm -rf /usr/lib64/dri 2>/dev/null || true
rm -rf /usr/lib64/python* 2>/dev/null || true
rm -f /usr/bin/qemu-system-* /usr/bin/qemu-img /usr/bin/qemu-io 2>/dev/null || true
rm -f /usr/bin/qemu-nbd /usr/bin/qemu-storage-daemon 2>/dev/null || true
rm -rf /usr/share/qemu /usr/share/seabios /usr/share/ipxe 2>/dev/null || true
rm -f /usr/bin/skopeo /usr/bin/lego /usr/bin/umoci 2>/dev/null || true
rm -f /usr/libexec/virtiofsd 2>/dev/null || true
rm -rf /usr/lib64/libibverbs* /usr/lib64/librdmacm* 2>/dev/null || true
rm -f /usr/bin/perl /usr/bin/perl5.* 2>/dev/null || true
rm -rf /usr/lib/perl5 2>/dev/null || true

#-- Remove boot files and kernel modules (custom kernel is built separately) --#
rm -rf /boot/* 2>/dev/null || true
rm -rf /usr/lib/modules 2>/dev/null || true

#-- Remove systemd generators that don't apply to this appliance --#
rm -f /usr/lib/systemd/system-generators/systemd-cryptsetup-generator 2>/dev/null || true
rm -f /usr/lib/systemd/system-generators/systemd-gpt-auto-generator 2>/dev/null || true
rm -f /usr/lib/systemd/system-generators/systemd-hibernate-resume-generator 2>/dev/null || true
rm -f /usr/lib/systemd/system-generators/systemd-integritysetup-generator 2>/dev/null || true
rm -f /usr/lib/systemd/system-generators/systemd-veritysetup-generator 2>/dev/null || true
rm -f /usr/lib/systemd/system-generators/systemd-bless-boot-generator 2>/dev/null || true
rm -f /usr/lib/systemd/system-generators/systemd-system-update-generator 2>/dev/null || true

#-- Remove unit files that can never trigger in this appliance --#
# Reduces systemd's unit parse time during early init
rm -f /usr/lib/systemd/system/initrd-*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-hibernate*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-suspend*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-hybrid-sleep.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-kexec.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-bless-boot.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-boot-check-no-failures.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-rfkill.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-backlight@.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-battery-check.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-firstboot.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-volatile-root.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-confext*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-sysext*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-quotacheck*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-repart*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-growfs*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-time-wait-sync.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-bootctl@.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/quotaon*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/rsyncd*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/ebtables.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/debug-shell.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/breakpoint-*.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/pam_namespace.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/system-update-cleanup.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/remount-tmpfs.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/boot-sysctl.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/kernel-sysctl.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/kmod-static-nodes.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/capsule@.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/fstrim.timer /usr/lib/systemd/system/fstrim.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/openSUSE-build-key-import.timer /usr/lib/systemd/system/openSUSE-build-key-import.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/shadow.timer /usr/lib/systemd/system/shadow.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-binfmt.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-hwdb-update.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-journal-catalog-update.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-modules-load.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-sysusers.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-update-done.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-udev-settle.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-udev-load-credentials.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-userdb-load-credentials.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-vconsole-setup.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-soft-reboot.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/soft-reboot-cleanup.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-coredump@.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-timesyncd.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-timedated.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-localed.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/ca-certificates-setup.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/console-getty.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/container-getty@.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/dbus-org.freedesktop.locale1.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/dbus-org.freedesktop.timedate1.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/dbus-org.freedesktop.login1.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-logind.service 2>/dev/null || true
rm -rf /usr/lib/systemd/system/systemd-logind.service.d 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-logind-varlink.socket 2>/dev/null || true
rm -f /usr/lib/systemd/system/sshd.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/dnsmasq.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/autovt@.service 2>/dev/null || true
rm -f /usr/lib/systemd/system/proc-sys-fs-binfmt_misc.automount 2>/dev/null || true
rm -f /usr/lib/systemd/system/proc-sys-fs-binfmt_misc.mount 2>/dev/null || true

#-- Remove unreachable targets and stale sockets --#
rm -f /usr/lib/systemd/system/bluetooth.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/boot-complete.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/cryptsetup*.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/factory-reset.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/first-boot-complete.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/graphical.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/hibernate.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/hybrid-sleep.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/imports*.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/initrd*.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/integritysetup*.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/kexec.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/printer.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/remote-cryptsetup.target /usr/lib/systemd/system/remote-integritysetup.target /usr/lib/systemd/system/remote-veritysetup.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/rpcbind.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/sleep.target /usr/lib/systemd/system/soft-reboot.target /usr/lib/systemd/system/sound.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/suspend*.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/system-update*.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/tpm2.target /usr/lib/systemd/system/usb-gadget.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/veritysetup*.target 2>/dev/null || true
rm -f /usr/lib/systemd/system/rsyncd.socket /usr/lib/systemd/system/systemd-rfkill.socket 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-sysext.socket /usr/lib/systemd/system/systemd-pcrlock.socket 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-bootctl.socket /usr/lib/systemd/system/systemd-repart.socket 2>/dev/null || true
rm -f /usr/lib/systemd/system/systemd-coredump.socket /usr/lib/systemd/system/sshd.socket 2>/dev/null || true
rm -rf /usr/lib/systemd/system/graphical.target.wants 2>/dev/null || true
rm -rf /usr/lib/systemd/system/initrd-root-device.target.wants 2>/dev/null || true
rm -rf /usr/lib/systemd/system/initrd-root-fs.target.wants 2>/dev/null || true
rm -rf /usr/lib/systemd/system/kexec.target.wants 2>/dev/null || true
rm -rf /usr/lib/systemd/system/remote-fs.target.wants 2>/dev/null || true
rm -rf /usr/lib/systemd/system/system-update-pre.target.wants 2>/dev/null || true

#-- Remove udev hardware databases (VM doesn't need hardware detection) --#
rm -f /usr/lib/udev/hwdb.d/20-pci-*.hwdb 2>/dev/null || true
rm -f /usr/lib/udev/hwdb.d/20-OUI.hwdb 2>/dev/null || true
rm -f /usr/lib/udev/hwdb.d/20-usb-*.hwdb 2>/dev/null || true
rm -f /usr/lib/udev/hwdb.d/20-bluetooth*.hwdb 2>/dev/null || true
rm -f /usr/share/file/magic.mgc 2>/dev/null || true

#-- Strip locale data, docs, caches --#
rm -rf /usr/share/man /usr/share/doc /usr/share/info
rm -rf /usr/share/locale/*
rm -rf /var/cache/zypp/* /var/log/zypp
rm -rf /tmp/* /var/tmp/*

#-- Remove package manager (appliance is not user-upgradeable) --#
rm -f /usr/bin/zypper /usr/bin/rpm 2>/dev/null || true
rm -rf /usr/lib*/libzypp* /usr/lib*/librpm* 2>/dev/null || true
rm -rf /usr/share/zypper 2>/dev/null || true
rm -rf /usr/lib/sysimage/rpm 2>/dev/null || true

#-- D-Bus: disable audit logging (headless VM, no audit consumers) --#
mkdir -p /etc/systemd/system/dbus-broker.service.d
cat > /etc/systemd/system/dbus-broker.service.d/no-audit.conf << 'EOF'
[Service]
ExecStart=
ExecStart=/usr/bin/dbus-broker-launch --scope system
EOF

#-- Journal: volatile (tmpfs) to skip corruption check and disk I/O --#
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/volatile.conf << 'EOF'
[Journal]
Storage=volatile
RuntimeMaxUse=16M
EOF

#-- Fstab: remount root with noatime (VFS flag can't go in rootflags=) --#
cat > /etc/fstab << 'EOF'
/dev/vda / btrfs noatime,commit=300 0 0
EOF

#-- Set default locale and hostname --#
echo 'LANG=C.UTF-8' > /etc/locale.conf
echo 'isx' > /etc/hostname

#-- Root: passwordless login on serial console (appliance is headless) --#
passwd -d root
sed -i 's/^auth.*pam_unix.so.*/& nullok/' /etc/pam.d/login 2>/dev/null || true

exit 0
