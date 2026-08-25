package dev.incusspawn.util;

import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Best-effort CPU topology inspection, used to size CPU/IO-bound parallelism
 * (e.g. concurrent git clones) to the machine's fast cores rather than every
 * logical thread.
 */
public final class CpuInfo {

    private CpuInfo() {}

    /**
     * Count of high-performance ("P") CPU cores. On hybrid CPUs this excludes
     * the slower efficiency cores; where the distinction cannot be determined it
     * falls back to the total logical processor count. Always at least 1.
     */
    public static int highPerfCores() {
        int detected = detect();
        return Math.max(1, detected > 0 ? detected : Runtime.getRuntime().availableProcessors());
    }

    private static int detect() {
        try {
            if (Platform.isMacOS()) return macPerformanceCores();
            if (Platform.isLinux()) return linuxBigCores();
        } catch (Exception ignored) {
            // fall through to the logical-processor fallback
        }
        return 0;
    }

    // On Apple Silicon perflevel0 is the performance cluster; on Intel Macs the
    // key is absent and sysctl fails, so this returns 0 (use all cores).
    private static int macPerformanceCores() {
        try {
            var pb = new ProcessBuilder("sysctl", "-n", "hw.perflevel0.physicalcpu");
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
