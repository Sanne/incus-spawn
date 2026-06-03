#!/bin/sh
# Post-install script — runs inside the chroot during image build.
# Configures the minimal Alpine appliance for running Incus containers.

set -eu

#-- Incus: ensure subuid/subgid exist (Alpine's incus package may set these) --#
grep -q "^root:" /etc/subuid 2>/dev/null || echo "root:100000:65536" >> /etc/subuid
grep -q "^root:" /etc/subgid 2>/dev/null || echo "root:100000:65536" >> /etc/subgid

#-- LXC: newuidmap/newgidmap need suid for container user namespace mapping --#
chmod u+s /usr/bin/newuidmap /usr/bin/newgidmap 2>/dev/null || true

#-- Service directories --#
mkdir -p /var/lib/lxcfs
mkdir -p /run/dbus

#-- Init scripts --#
chmod +x /etc/init.d/rcS /etc/init.d/rcK

#-- Strip binaries and shared libraries --#
find /usr/lib /usr/sbin /usr/bin -type f \( -name '*.so*' -o -executable \) \
    -exec strip --strip-unneeded {} \; 2>/dev/null || true

#-- Remove boot files and kernel modules (custom kernel is built separately) --#
rm -rf /boot/* 2>/dev/null || true
rm -rf /usr/lib/modules 2>/dev/null || true

#-- Strip locale data, docs, caches --#
rm -rf /usr/share/man /usr/share/doc /usr/share/info
rm -rf /usr/share/locale/*

#-- Remove package manager (appliance is not user-upgradeable) --#
rm -f /sbin/apk 2>/dev/null || true
rm -rf /etc/apk /var/cache/apk 2>/dev/null || true

#-- Fstab: remount root with noatime --#
cat > /etc/fstab << 'EOF'
/dev/vda / btrfs noatime,commit=300 0 0
EOF

#-- Set default locale and hostname --#
echo 'isx' > /etc/hostname

#-- Root: passwordless login on serial console (appliance is headless) --#
passwd -d root 2>/dev/null || sed -i 's/^root:[^:]*:/root::/' /etc/shadow

exit 0
