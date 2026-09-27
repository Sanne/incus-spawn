package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The on-disk state the proxy's configuration was loaded from: {@code config.yaml} and each
 * {@code tools/*.yaml}, keyed by path to their mtime and size. Drift is "differs from the capture
 * taken at load" -- never "newer than the load", which a future mtime satisfies forever (#818).
 * A file added or removed changes the key set, so no directory stamp is needed.
 * <p>
 * Capture it <em>before</em> reading the config, so an edit racing the read shows as drift.
 */
record ConfigFingerprint(Map<String, Stamp> entries) {

    record Stamp(long mtimeNanos, long size) {}

    static ConfigFingerprint capture() {
        return capture(SpawnConfig.configDir());
    }

    static ConfigFingerprint capture(Path configDir) {
        var entries = new HashMap<String, Stamp>();
        record(entries, configDir.resolve("config.yaml"));
        var toolsDir = configDir.resolve("tools");
        if (Files.isDirectory(toolsDir)) {
            try (var stream = Files.list(toolsDir)) {
                stream.filter(p -> {
                            var name = p.getFileName().toString();
                            return name.endsWith(".yaml") || name.endsWith(".yml");
                        })
                        .forEach(p -> record(entries, p));
            } catch (IOException ignored) {
                // Unlistable: its tools read as absent, which differs from any successful capture.
            }
        }
        return new ConfigFingerprint(Map.copyOf(entries));
    }

    private static void record(Map<String, Stamp> entries, Path path) {
        try {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            entries.put(path.toString(),
                    new Stamp(attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS), attrs.size()));
        } catch (IOException ignored) {
            // Absent or unreadable: no entry, so its later appearance reads as drift.
        }
    }
}
