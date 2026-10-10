package dev.incusspawn.incus;

import dev.incusspawn.util.CpuInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects host resources and computes adaptive container limits.
 */
public final class ResourceLimits {

    private ResourceLimits() {}

    /**
     * Reads the actual host CPU count from the OS, bypassing
     * Runtime.availableProcessors() which is capped by
     * -R:ActiveProcessorCount in the native image.
     */
    public static int hostProcessorCount() {
        return CpuInfo.logicalCores();
    }

    /** The most vCPUs a VM gets by default (#1238, decided by Sanne); {@code --cpu} sets more. */
    static final int VM_CPU_CAP = 8;

    /**
     * Default vCPUs for a VM (#1238, decided by Sanne): every host CPU but two, at most
     * {@value #VM_CPU_CAP}, and on a hybrid host at most its top tier's physical cores; at least
     * 1. A default, not a ceiling: {@code --cpu} sets any count.
     *
     * <p>Firmware and kernel boot time grow with the vCPU count, and a VM gains little from
     * efficiency cores next to its performance ones.
     */
    public static int defaultVmCpus() {
        return vmCpusFor(hostProcessorCount(), CpuInfo.hybridTopTierCores());
    }

    /**
     * {@link #defaultVmCpus()} for a host with {@code logical} CPUs; {@code hybridTopTier} is the
     * top tier's physical cores on a hybrid host, 0 on any other (or one whose tiers are unknown).
     */
    static int vmCpusFor(int logical, int hybridTopTier) {
        int vcpus = Math.min(VM_CPU_CAP, logical - 2);
        if (hybridTopTier > 0) vcpus = Math.min(vcpus, hybridTopTier);
        return Math.max(1, vcpus);
    }

    public static String adaptiveMemoryLimit() {
        long totalBytes = totalMemoryBytes();
        if (totalBytes <= 0) {
            return "4GB";
        }
        long limitBytes = (long) (totalBytes * 0.6);
        long limitGB = limitBytes / (1024 * 1024 * 1024);
        if (limitGB > 0) {
            return limitGB + "GB";
        }
        long limitMB = limitBytes / (1024 * 1024);
        return limitMB + "MB";
    }

    private static final long GIB = 1024L * 1024 * 1024;
    static final long VM_MEMORY_CEILING = 16 * GIB;
    static final long VM_MEMORY_FLOOR = 4 * GIB;

    /**
     * Default guest RAM for a VM: 25% of host RAM, capped at 16 GiB, at least 4 GiB (or 60% of
     * host RAM on hosts too small for that).
     *
     * <p>Unlike a container's {@code limits.memory}, which is a cgroup ceiling, a VM's is its RAM
     * size: the guest fills it with page cache, and QEMU keeps whatever the guest touched. The
     * container default ({@link #adaptiveMemoryLimit()}) given to every VM overcommits the host
     * as soon as two of them run.
     */
    public static String defaultVmMemoryLimit() {
        return vmMemoryLimitFor(totalMemoryBytes());
    }

    static String vmMemoryLimitFor(long totalBytes) {
        if (totalBytes <= 0) {
            return VM_MEMORY_FLOOR / GIB + "GiB";
        }
        // The floor never exceeds the container default, so a small host is no worse off than before
        long floor = Math.min(VM_MEMORY_FLOOR, (long) (totalBytes * 0.6));
        long bytes = Math.max(floor, Math.min(VM_MEMORY_CEILING, totalBytes / 4));
        long mib = bytes / (1024 * 1024);
        return mib % 1024 == 0 ? mib / 1024 + "GiB" : mib + "MiB";
    }

    /**
     * Default disk limit for containers. This is a ceiling, not an allocation —
     * with COW storage (btrfs/zfs) actual usage is thin-provisioned.
     */
    public static String defaultDiskLimit() {
        return "100GB";
    }

    public static long totalMemoryBytes() {
        try {
            var meminfo = Files.readString(Path.of("/proc/meminfo"));
            for (var line : meminfo.split("\n")) {
                if (line.startsWith("MemTotal:")) {
                    var parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024; // /proc/meminfo is in kB
                }
            }
        } catch (IOException | NumberFormatException e) {
            // fall through to macOS path
        }
        try {
            var pb = new ProcessBuilder("sysctl", "-n", "hw.memsize");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return Long.parseLong(output);
            }
        } catch (Exception e) {
            // fall through
        }
        return -1;
    }
}
