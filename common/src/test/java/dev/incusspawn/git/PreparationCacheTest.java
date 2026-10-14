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

    @Test void anOversizedReplacementRetiresTheOldSnapshotWithoutInvalidatingReaders() throws Exception {
        var cache = new PreparationCache(root, new GitCacheConfig(20, Duration.ofDays(1)));
        try (var lease = cache.lease()) {
            cache.publishSnapshot("repo", staged(lease.path, "old", 12));
            var pin = lease.path.resolve("pin");
            assertTrue(cache.snapshot("repo", pin));
            var oversized = staged(lease.path, "new", 21);
            cache.publishSnapshot("repo", oversized);
            assertFalse(cache.snapshot("repo", lease.path.resolve("stale")));
            assertTrue(PreparationGit.children(root.resolve("snapshots")).isEmpty());
            assertEquals(12, Files.size(pin.resolve("data")), "a live reader still owns the old bytes");
            assertTrue(Files.exists(oversized), "oversized work remains usable by its current caller");
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

    @Test void perUrlWritersWaitBeyondThirtySecondsAcrossProcessesWithoutBlockingOtherUrls() throws Exception {
        var key = "aa" + "1".repeat(62);
        var otherKey = "aa" + "2".repeat(62); // collided under the old stripe scheme
        var ready = root.resolve("ready");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), Writer.class.getName(), root.toString(), key,
                ready.toString()).redirectErrorStream(true).redirectOutput(root.resolve("writer.log").toFile()).start();
        try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(ready), Files.readString(root.resolve("writer.log")));
            var cache = new PreparationCache(root, GitCacheConfig.DEFAULT);
            var waiting = workers.submit(() -> { try (var lock = cache.writer(key)) { return true; } });
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { try (var lock = cache.writer(otherKey)) { } });
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> waiting.get(31, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                child.getOutputStream().close(); // EOF releases the child lock
            }
            assertTrue(waiting.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            child.destroyForcibly();
            child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    public static class Writer {
        public static void main(String[] args) throws Exception {
            var cache = new PreparationCache(Path.of(args[0]), GitCacheConfig.DEFAULT);
            try (var lock = cache.writer(args[1])) {
                Files.writeString(Path.of(args[2]), "ready");
                System.in.read();
            }
        }
    }

    private static Path staged(Path root, String name, int bytes) throws Exception {
        var dir = Files.createDirectory(root.resolve(name));
        Files.write(dir.resolve("data"), new byte[bytes]);
        return dir;
    }
}
