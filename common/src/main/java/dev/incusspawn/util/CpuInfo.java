package dev.incusspawn.util;

import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Best-effort CPU topology inspection. Single source of truth for CPU counts
 * used to size resources (appliance VM vCPUs, container limits) and CPU/IO-bound
 * parallelism (e.g. concurrent git clones).
 */
public final class CpuInfo {

    private CpuInfo() {}

    /**
     * Total logical processors on the host. Reads {@code /proc/cpuinfo} (Linux)
     * or {@code sysctl hw.logicalcpu} (macOS) rather than
     * {@link Runtime#availableProcessors()}, which the native image pins via
     * {@code -R:ActiveProcessorCount} and so under-reports the real machine.
     * Always at least 1.
     */
    public static int logicalCores() {
        try {
            var cpuinfo = Files.readString(Path.of("/proc/cpuinfo"));
            int count = 0;
            for (var line : cpuinfo.split("\n")) {
                if (line.startsWith("processor")) count++;
            }
            if (count > 0) return count;
        } catch (IOException ignored) {}
        int sysctl = sysctlInt("hw.logicalcpu");
        if (sysctl > 0) return sysctl;
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Count of high-performance ("P"/big) logical cores, or 0 where the platform
     * does not expose the distinction (homogeneous CPU, Intel Mac, etc.). On
     * hybrid CPUs this excludes the slower efficiency cores.
     */
    public static int performanceCores() {
        try {
            if (Platform.isMacOS()) return macPerformanceCores();
            if (Platform.isLinux()) return linuxBigCores();
        } catch (Exception ignored) {
            // fall through
        }
        return 0;
    }

    /**
     * Parallelism bound for CPU/IO-bound fan-out: the performance-core count when
     * it can be determined, otherwise all logical cores. Always at least 1.
     */
    public static int highPerfCores() {
        int p = performanceCores();
        return Math.max(1, p > 0 ? p : logicalCores());
    }

    // On Apple Silicon perflevel0 is the performance cluster; on Intel Macs the
    // key is absent and sysctl fails, so this returns 0 (use all cores). P-cores
    // have a single thread each, so logicalcpu == physicalcpu here.
    private static int macPerformanceCores() {
        return sysctlInt("hw.perflevel0.logicalcpu");
    }

    private static int sysctlInt(String key) {
        try {
            var pb = new ProcessBuilder("sysctl", "-n", key);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var process = pb.start();
            var out = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0 || out.isEmpty()) return 0;
            return Integer.parseInt(out);
        } catch (IOException | NumberFormatException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    // Linux exposes per-CPU max capacity on hybrid/big.LITTLE systems; the big
    // cores are those at the maximum capacity. Homogeneous systems expose no
    // cpu_capacity files, so this returns 0 (use all cores).
    private static int linuxBigCores() {
        var base = Path.of("/sys/devices/system/cpu");
        if (!Files.isDirectory(base)) return 0;
        long maxCapacity = 0;
        int maxCount = 0;
        try (DirectoryStream<Path> cpus = Files.newDirectoryStream(base, "cpu[0-9]*")) {
            for (var cpu : cpus) {
                var capFile = cpu.resolve("cpu_capacity");
                if (!Files.isRegularFile(capFile)) continue;
                long cap;
                try {
                    cap = Long.parseLong(Files.readString(capFile).strip());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (cap > maxCapacity) {
                    maxCapacity = cap;
                    maxCount = 1;
                } else if (cap == maxCapacity) {
                    maxCount++;
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return maxCount;
    }
}
