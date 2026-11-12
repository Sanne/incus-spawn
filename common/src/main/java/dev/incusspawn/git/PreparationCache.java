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
 * Atomic immutable snapshots with bounded retention. Readers pin owned files
 * into a leased work directory before releasing the catalogue lock; retiring a generation cannot
 * remove their inodes. User checkout files are never hardlinked into this cache.
 */
final class PreparationCache {
    // Keep the lease itself alive if a failed detach leaves the source mounted after its caller returns.
    private static final Map<Path, Lease> ACTIVE = new HashMap<>();
    private final Path root;
    private final GitCacheConfig policy;
    /** Allows for Git's 15-minute commands and retry/fsck; far beyond {@link HostLock#TIMEOUT}. */
    static final java.time.Duration WRITER_TIMEOUT = java.time.Duration.ofHours(2);
    private final java.time.Duration writerTimeout;

    PreparationCache(Path root, GitCacheConfig policy) throws IOException {
        this(root, policy, WRITER_TIMEOUT);
    }

    /** The writer wait is a parameter so a test need not wait out the real one. */
    PreparationCache(Path root, GitCacheConfig policy, java.time.Duration writerTimeout) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
        Files.setPosixFilePermissions(this.root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        this.policy = policy;
        this.writerTimeout = writerTimeout;
    }

    java.time.Duration writerTimeout() { return writerTimeout; }

    HostLock writer(String key) {
        // A stable inode per URL: unrelated preparations never compete for a stripe. Do not unlink
        // a lock another process may have open. Allow for Git's 15-minute commands and retry/fsck.
        return HostLock.acquire(root.resolve("locks/" + key + ".lock"), "preparing this Git repository",
                s -> {}, writerTimeout);
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
            // Cancellation must still release its lease; restore the interrupt after cleanup.
            boolean interrupted = Thread.interrupted();
            try (var ignored = catalogue()) {
                closed = true;
                try {
                    lock.release();
                    channel.close();
                } finally {
                    synchronized (ACTIVE) { ACTIVE.remove(path); }
                    delete(path);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
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

    /**
     * Pin the most recently used snapshot of another pin of the same URL, {@code family}, and return its key, or
     * null when there is none. It only supplies objects to a new pin's fetch, which copies its own ref's closure.
     */
    String sibling(String family, String key, Path destination) throws IOException {
        try (var ignored = catalogue()) {
            Path newest = null;
            for (var path : children(root.resolve("snapshots"))) {
                // Snapshots published before #2050 name no family, and none is ever borrowed.
                var name = path.getFileName().toString();
                int at = name.indexOf("-" + family + "-");
                if (at <= 0 || name.substring(0, at).equals(key)) continue;
                if (newest == null || modified(path) > modified(newest)) newest = path;
            }
            if (newest == null) return null;
            copyTree(newest, destination, true);
            var name = newest.getFileName().toString();
            return name.substring(0, name.indexOf("-" + family + "-"));
        }
    }

    /**
     * Null when the snapshot was kept for later builds without evicting another recently used one.
     * Otherwise the result says whether this snapshot or others were not kept, why, and which setting
     * to change: every later build of those repositories clones and verifies them in full again. A
     * snapshot that is not kept stays usable in {@code staged}.
     */
    String publishSnapshot(String key, String family, Path staged) throws IOException {
        String notRetained = null;
        long bytes;
        if (policy.snapshotBytes() == 0 || policy.maxAge().isZero()) {
            notRetained = "Git snapshot retention is disabled (git-cache." + (policy.snapshotBytes() == 0
                    ? "snapshot-max-mib" : "max-age-days") + ": 0); it was used for this build but not kept";
        } else if ((bytes = size(staged)) > policy.snapshotBytes()) {
            notRetained = "Git snapshot (" + mib(bytes) + ") exceeds " + limit()
                    + "; it was used for this build but not kept";
        }
        boolean retain = notRetained == null;
        try (var ignored = catalogue()) {
            var parent = Files.createDirectories(root.resolve("snapshots"));
            Path target = null;
            if (retain) {
                // The family names the URL alone, so another pin's first preparation can borrow from it.
                target = parent.resolve(key + "-" + family + "-" + UUID.randomUUID());
                // Only complete directories appear in the catalogue. Staging shares the cache filesystem.
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
                touch(target);
            }
            // An oversized replacement must retire the old generation too; readers keep their own pins.
            for (var old : children(parent)) {
                if (!old.equals(target) && old.getFileName().toString().startsWith(key + "-")) delete(old);
            }
            var evicted = prune();
            // Snapshots that each fit but not together would otherwise evict each other on every build, silently.
            if (retain && !evicted.isEmpty()) {
                long lost = evicted.stream().mapToLong(Long::longValue).sum();
                notRetained = "Git snapshot kept, but it evicted " + evicted.size() + " other recently used snapshot"
                        + (evicted.size() == 1 ? "" : "s") + " (" + mib(lost) + ") to fit " + limit()
                        + "; raise it, or repositories built in turn are cloned and verified in full every time";
            }
        }
        return notRetained;
    }

    private String limit() {
        return "git-cache.snapshot-max-mib (" + (policy.snapshotBytes() >> 20) + " MiB)";
    }

    /** In the setting's unit, rounded up to a tenth: an oversized snapshot never reads as equal to, or under, the limit. */
    private static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", Math.ceil(bytes * 10.0 / (1L << 20)) / 10);
    }

    /** The sizes of the snapshots evicted for space while still within their maximum age. */
    private java.util.List<Long> prune() throws IOException {
        // Size counts logical file bytes; entry ceilings also bound tiny files and directory overhead.
        return trim(root.resolve("snapshots"), policy.snapshotBytes(), 128);
    }

    private java.util.List<Long> trim(Path directory, long budget, int maxEntries) throws IOException {
        var evicted = new java.util.ArrayList<Long>();
        var entries = new java.util.ArrayList<>(children(directory));
        entries.sort(Comparator.comparingLong(PreparationCache::modified));
        long total = 0;
        for (var path : entries) total += size(path);
        long cutoff = Instant.now().minus(policy.maxAge()).toEpochMilli();
        int count = entries.size();
        for (var path : entries) {
            boolean fresh = modified(path) > cutoff;
            if (total <= budget && count <= maxEntries && fresh) continue;
            long bytes = size(path);
            if (fresh && total > budget) evicted.add(bytes);
            delete(path);
            total -= bytes;
            count--;
        }
        return evicted;
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
