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

    public static String defaultDiskLimit() {
        return "20GB";
    }

    private static long totalMemoryBytes() {
        try {
            var meminfo = Files.readString(Path.of("/proc/meminfo"));
            for (var line : meminfo.split("\n")) {
                if (line.startsWith("MemTotal:")) {
                    var parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024; // /proc/meminfo is in kB
                }
            }
        } catch (IOException | NumberFormatException e) {
            // fall through
        }
        return -1;
    }
}
