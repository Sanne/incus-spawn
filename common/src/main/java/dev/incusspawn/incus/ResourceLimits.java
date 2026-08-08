package dev.incusspawn.incus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects host resources and computes adaptive container limits.
 */
public final class ResourceLimits {

    private ResourceLimits() {}

    public static int adaptiveCpuLimit() {
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, available - 2);
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
