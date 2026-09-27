package dev.incusspawn.proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/**
 * The on-disk state the proxy's configuration was loaded from: {@code config.yaml}, the
 * {@code tools/} directory and each {@code tools/*.yaml}, keyed by path to their mtime and size.
 * <p>
 * Drift is "any entry differs from the one recorded at load", never "an mtime is later than
 * when we loaded". Comparing against the wall clock reports drift forever for a file whose
 * mtime is in the future (copied with {@code cp -p} from a machine whose clock ran ahead, a
 * clock stepped back), and every restart that drift triggers leaves it standing (#818).
 * Comparing recorded values involves no clock, and also catches an edit landing in the same
 * timestamp tick as the load, which a strictly-later check misses.
 * <p>
 * Capture it <em>before</em> reading the config: an edit between the capture and the read then
 * shows as drift and is reloaded again, rather than being silently recorded as already seen.
 */
record ConfigFingerprint(Map<String, Stamp> entries) {

    record Stamp(long mtimeNanos, long size) {}

    static ConfigFingerprint capture(Path configDir) {
        var entries = new HashMap<String, Stamp>();
        record(entries, configDir.resolve("config.yaml"));
        var toolsDir = configDir.resolve("tools");
        if (Files.isDirectory(toolsDir)) {
            // The directory's own mtime catches additions and removals of any file,
            // including ones whose own mtime happens to match nothing we recorded.
            record(entries, toolsDir);
            try (var stream = Files.list(toolsDir)) {
                stream.filter(p -> {
                            var name = p.getFileName().toString();
                            return name.endsWith(".yaml") || name.endsWith(".yml");
                        })
                        .forEach(p -> record(entries, p));
            } catch (IOException ignored) {
                // Listing failed: the directory's own stamp still stands in for its contents.
            }
        }
        return new ConfigFingerprint(Map.copyOf(entries));
    }

    private static void record(Map<String, Stamp> entries, Path path) {
        try {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            entries.put(path.toString(), new Stamp(
                    attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),
                    attrs.isDirectory() ? -1 : attrs.size()));
        } catch (NoSuchFileException ignored) {
            // Absent: no entry, so its later appearance reads as drift.
        } catch (IOException ignored) {
            // Unreadable attributes: treat as absent rather than failing the health check.
        }
    }
}
