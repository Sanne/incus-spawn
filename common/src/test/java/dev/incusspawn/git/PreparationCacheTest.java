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

    @Test void snapshotsHaveOneGlobalBudgetAndOversizedEntriesAreNotRetained() throws Exception {
        var cache = new PreparationCache(root, new GitCacheConfig(20, Duration.ofDays(1)));
        try (var lease = cache.lease()) {
            cache.publishSnapshot("first", staged(lease.path, "first", 12));
            var old = PreparationGit.children(root.resolve("snapshots")).getFirst();
            Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 1000));
            cache.publishSnapshot("second", staged(lease.path, "second", 12));
            assertFalse(Files.exists(old));
            assertEquals(12, PreparationCache.size(root.resolve("snapshots")));
            cache.publishSnapshot("too-large", staged(lease.path, "large", 21));
            assertEquals(12, PreparationCache.size(root.resolve("snapshots")));
            assertEquals(1, PreparationGit.children(root.resolve("snapshots")).size());
        }
    }

    @Test void expiryAndAnAliasedCachePathCannotReapALiveLease() throws Exception {
        var cache = new PreparationCache(root, GitCacheConfig.DEFAULT);
        try (var lease = cache.lease()) {
            cache.publishSnapshot("old", staged(lease.path, "old", 4));
            var expired = PreparationGit.children(root.resolve("snapshots")).getFirst();
            Files.setLastModifiedTime(expired, FileTime.fromMillis(0));
            var alias = root.resolveSibling(root.getFileName() + "-alias");
            Files.createSymbolicLink(alias, root);
            try {
                try (var other = new PreparationCache(alias, GitCacheConfig.DEFAULT).lease()) {
                    assertFalse(Files.exists(expired));
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
