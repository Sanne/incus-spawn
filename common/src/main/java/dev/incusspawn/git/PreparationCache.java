package dev.incusspawn.git;

import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.util.HostLock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static dev.incusspawn.git.PreparationGit.*;

/**
 * Atomic immutable generations, with separate metadata/payload budgets. Readers pin owned files
 * into a leased work directory before releasing the catalogue lock; retiring a generation cannot
 * remove their inodes. User checkout files are never hardlinked into this cache.
 */
final class PreparationCache {
    // Keep the lease itself alive if a failed detach leaves the source mounted after its caller returns.
    private static final Map<Path, Lease> ACTIVE = new HashMap<>();
    private final Path root;
    private final GitCacheConfig policy;

    PreparationCache(Path root, GitCacheConfig policy) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
        Files.setPosixFilePermissions(this.root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        this.policy = policy;
    }

    HostLock writer(String key) {
        // A bounded set of stable lock inodes: never unlink a lock another process may have open.
        return HostLock.acquire(root.resolve("locks/" + key.substring(0, 2) + ".lock"), "preparing this Git repository", s -> {});
    }

    private HostLock catalogue() {
        return HostLock.acquire(root.resolve("catalogue.lock"), "updating the Git cache", s -> {});
    }

    Lease lease() throws IOException {
        try (var ignored = catalogue()) {
            Files.createDirectories(root.resolve("work"));
            prune();
            reap();
            var path = Files.createDirectory(root.resolve("work/" + UUID.randomUUID()));
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            var channel = FileChannel.open(path.resolve("lease"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                var lock = channel.tryLock();
                if (lock == null) throw new IOException("Cannot lease Git preparation directory");
                var lease = new Lease(path, channel, lock);
                synchronized (ACTIVE) { ACTIVE.put(path, lease); }
                return lease;
            } catch (IOException | RuntimeException e) {
                channel.close();
                delete(path);
                throw e;
            }
        }
    }

    final class Lease implements AutoCloseable {
        final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Lease(Path path, FileChannel channel, FileLock lock) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            try (var ignored = catalogue()) {
                closed = true;
                try {
                    lock.release();
                    channel.close();
                } finally {
                    synchronized (ACTIVE) { ACTIVE.remove(path); }
                    delete(path);
                }
            }
        }
    }

    /** Pin a snapshot while catalogue eviction is excluded. Its repository contains only selected objects. */
    boolean snapshot(String key, Path destination) throws IOException {
        try (var ignored = catalogue()) {
            for (var path : children(root.resolve("snapshots"))) {
                if (path.getFileName().toString().startsWith(key + "-")) {
                    copyTree(path, destination, true);
                    touch(path);
                    return true;
                }
            }
            return false;
        }
    }

    /** Read immutable metadata under the catalogue lock; corruption is a cache miss at the caller. */
    boolean metadata(String key, Path destination) throws IOException {
        try (var ignored = catalogue()) {
            var path = root.resolve("metadata/" + key);
            if (!Files.isDirectory(path)) return false;
            copyTree(path, destination, false);
            touch(path);
            return true;
        }
    }

    void publishMetadata(String key, Path staged) throws IOException {
        publish("metadata", key, staged, policy.metadataBytes(), false);
    }

    void publishSnapshot(String key, Path staged) throws IOException {
        publish("snapshots", key, staged, policy.snapshotBytes(), true);
    }

    private void publish(String kind, String key, Path staged, long budget, boolean snapshot) throws IOException {
        if (budget == 0 || policy.maxAge().isZero() || size(staged) > budget) return;
        try (var ignored = catalogue()) {
            var parent = Files.createDirectories(root.resolve(kind));
            var target = parent.resolve(snapshot ? key + "-" + UUID.randomUUID() : key);
            // Only complete directories appear in the catalogue. The staging and cache roots share a filesystem.
            if (Files.exists(target)) delete(target);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            touch(target);
            if (snapshot) {
                for (var old : children(parent)) {
                    if (!old.equals(target) && old.getFileName().toString().startsWith(key + "-")) delete(old);
                }
            }
            prune();
        }
    }

    private void prune() throws IOException {
        // Size counts logical file bytes; entry ceilings also bound tiny files and directory overhead.
        trim(root.resolve("metadata"), policy.metadataBytes(), 1024);
        trim(root.resolve("snapshots"), policy.snapshotBytes(), 128);
    }

    private void trim(Path directory, long budget, int maxEntries) throws IOException {
        var entries = new java.util.ArrayList<>(children(directory));
        entries.sort(Comparator.comparingLong(PreparationCache::modified));
        long total = 0;
        for (var path : entries) total += size(path);
        long cutoff = Instant.now().minus(policy.maxAge()).toEpochMilli();
        int count = entries.size();
        for (var path : entries) {
            if (total <= budget && count <= maxEntries && modified(path) > cutoff) continue;
            long bytes = size(path);
            delete(path);
            total -= bytes;
            count--;
        }
    }

    /** Reclaim work left by a dead process. Never open another channel on a live in-process lease. */
    private void reap() throws IOException {
        for (var path : children(root.resolve("work"))) {
            synchronized (ACTIVE) { if (ACTIVE.containsKey(path)) continue; }
            var lease = path.resolve("lease");
            if (!Files.isRegularFile(lease)) { delete(path); continue; }
            try (var channel = FileChannel.open(lease, StandardOpenOption.WRITE)) {
                try (var lock = channel.tryLock()) {
                    if (lock != null) delete(path);
                }
            } catch (OverlappingFileLockException e) {
                // An active lease through another spelling of the cache root: leave it alone.
            }
        }
    }

    static void copyTree(Path source, Path destination, boolean link) throws IOException {
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Symbolic link in owned Git cache");
                var target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else if (link) Files.createLink(target, file);
                else Files.copy(file, target);
            }
        }
    }

    static long size(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var files = Files.walk(path)) {
            long total = 0;
            for (var file : files.filter(Files::isRegularFile).toList()) total += Files.size(file);
            return total;
        }
    }

    private static void touch(Path path) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException e) { return 0; }
    }
}
