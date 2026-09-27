package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDefLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

/**
 * The on-disk state the proxy's configuration was loaded from: {@code config.yaml} and each
 * tool definition in {@code tools/}, keyed by path to their mtime and size. Drift is "differs
 * from the capture taken at load" -- never "newer than the load", which a future mtime satisfies
 * forever (#818). A file added or removed changes the key set, so no directory stamp is needed.
 */
record ConfigFingerprint(Map<Path, Stamp> entries) {

    record Stamp(FileTime mtime, long size) {}

    /** The config, and the fingerprint of the files it was read from. */
    record Loaded(SpawnConfig config, ConfigFingerprint fingerprint) {}

    /**
     * Read the config, fingerprinting its files <em>before</em> the read so an edit racing it
     * shows as drift rather than being recorded as already seen.
     */
    static Loaded load() {
        var fingerprint = capture();
        return new Loaded(SpawnConfig.load(), fingerprint);
    }

    static ConfigFingerprint capture() {
        return capture(SpawnConfig.configDir());
    }

    static ConfigFingerprint capture(Path configDir) {
        var entries = new HashMap<Path, Stamp>();
        record(entries, configDir.resolve("config.yaml"));
        try (var stream = Files.list(configDir.resolve("tools"))) {
            stream.filter(ToolDefLoader::isToolFile).forEach(p -> record(entries, p));
        } catch (IOException ignored) {
            // No tools directory (or unlistable): no tool entries.
        }
        return new ConfigFingerprint(Map.copyOf(entries));
    }

    private static void record(Map<Path, Stamp> entries, Path path) {
        try {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            entries.put(path, new Stamp(attrs.lastModifiedTime(), attrs.size()));
        } catch (IOException ignored) {
            // Absent or unreadable: no entry, so its later appearance reads as drift.
        }
    }
}
