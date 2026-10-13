package dev.incusspawn.git;

import dev.incusspawn.config.GitCacheConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PreparationCacheTest {
    @TempDir Path root;

    @Test void metadataAndSnapshotsHaveIndependentGlobalBudgets() throws Exception {
        var cache = new PreparationCache(root, new GitCacheConfig(10, 20, Duration.ofDays(1)));
        try (var lease = cache.lease()) {
            cache.publishMetadata("a", staged(lease.path, "a", 6));
            Files.setLastModifiedTime(root.resolve("metadata/a"), FileTime.fromMillis(System.currentTimeMillis() - 1000));
            cache.publishSnapshot("repo", staged(lease.path, "snapshot", 18));
            cache.publishMetadata("b", staged(lease.path, "b", 6));
            assertFalse(Files.exists(root.resolve("metadata/a")));
            assertEquals(6, PreparationCache.size(root.resolve("metadata")));
            assertEquals(18, PreparationCache.size(root.resolve("snapshots")));
            cache.publishMetadata("too-large", staged(lease.path, "large", 11));
            assertFalse(Files.exists(root.resolve("metadata/too-large")));
            assertEquals(6, PreparationCache.size(root.resolve("metadata")));
        }
    }

    @Test void expiryAndAnAliasedCachePathCannotReapALiveLease() throws Exception {
        var cache = new PreparationCache(root, GitCacheConfig.DEFAULT);
        try (var lease = cache.lease()) {
            cache.publishMetadata("old", staged(lease.path, "old", 4));
            Files.setLastModifiedTime(root.resolve("metadata/old"), FileTime.fromMillis(0));
            var alias = root.resolveSibling(root.getFileName() + "-alias");
            Files.createSymbolicLink(alias, root);
            try {
                try (var other = new PreparationCache(alias, GitCacheConfig.DEFAULT).lease()) {
                    assertFalse(Files.exists(root.resolve("metadata/old")));
                    assertTrue(Files.isDirectory(lease.path));
                    assertNotEquals(lease.path, other.path);
                }
            } finally { Files.delete(alias); }
        }
    }

    private static Path staged(Path root, String name, int bytes) throws Exception {
        var dir = Files.createDirectory(root.resolve(name));
        Files.write(dir.resolve("data"), new byte[bytes]);
        return dir;
    }
}
