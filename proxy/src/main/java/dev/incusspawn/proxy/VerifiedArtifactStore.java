package dev.incusspawn.proxy;

import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/**
 * On-disk half of the Maven/Gradle artifact cache: an artifact plus the stored
 * copies of the sidecars upstream published for it, kept next to each other.
 * <p>
 * Invariants:
 * <ul>
 *   <li>An artifact is only committed after its bytes matched a checksum sidecar
 *       fetched from upstream, and that sidecar is committed with it.</li>
 *   <li>A stored checksum sidecar always describes the artifact beside it: it was
 *       either committed with it or checked against its hash before being stored.</li>
 *   <li>Commit, reconcile and evict for one artifact are serialized, so a store
 *       racing an eviction cannot leave a sidecar next to an artifact it does not
 *       describe.</li>
 * </ul>
 * All methods block; call them from a worker thread.
 */
final class VerifiedArtifactStore {

    private VerifiedArtifactStore() {}

    /** What a fresh sidecar answer did to the cached artifact. */
    enum Outcome {
        /** The cached artifact is confirmed to be what upstream describes. */
        MATCHED,
        /** The cached artifact no longer matches upstream (or was withdrawn) and was removed. */
        EVICTED,
        /** Nothing was decided: no cached artifact, or an answer that says nothing about it. */
        UNCHANGED
    }

    private static final Object[] LOCKS = new Object[64];
    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private static Object lockFor(Path artifact) {
        return LOCKS[Math.floorMod(artifact.hashCode(), LOCKS.length)];
    }

    /**
     * Commit a downloaded artifact if it matches the checksum sidecar upstream
     * published for it; otherwise delete the download.
     *
     * @return whether the artifact was committed
     */
    static boolean verifyAndCommit(Path download, Path artifact, Sidecar checksum,
                                   byte[] sidecarBody) throws IOException {
        var expected = checksum.hex(sidecarBody);
        if (expected == null || !expected.equals(checksum.hashOf(download))) {
            Files.deleteIfExists(download);
            return false;
        }
        synchronized (lockFor(artifact)) {
            // A concurrent miss may have committed first: its other stored
            // sidecars describe that copy, not this one.
            evictLocked(artifact);
            writeAtomically(checksum.storedFile(artifact), sidecarBody);
            Files.move(download, artifact,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * Bring a file from outside the cache (the host's {@code ~/.m2}) in as a
     * detached copy, and commit it if the copy matches upstream's checksum. The
     * copy is hashed, not the source, so a source changing mid-way cannot slip in.
     * A reflink costs no space where the filesystem supports one; a hardlink would
     * share the inode, so an in-place rewrite of the source would change the cache.
     */
    static boolean importCopy(Path source, Path artifact, Sidecar checksum,
                              byte[] sidecarBody) throws IOException {
        Files.createDirectories(artifact.getParent());
        var temp = Files.createTempFile(artifact.getParent(), "m2-", ".tmp");
        try {
            // cp -c (macOS clonefile) refuses an existing destination
            Files.delete(temp);
            cloneOrCopy(source, temp);
            return verifyAndCommit(temp, artifact, checksum, sidecarBody);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void cloneOrCopy(Path source, Path target) throws IOException {
        String[] clone = Platform.isMacOS()
                ? new String[] {"cp", "-c", source.toString(), target.toString()}
                : new String[] {"cp", "--reflink=auto", source.toString(), target.toString()};
        try {
            var process = new ProcessBuilder(clone)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) return;
            process.destroyForcibly();
        } catch (IOException e) {
            // No usable cp: fall through to a plain copy
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted cloning " + source, e);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Reconcile a cached artifact with a fresh upstream answer for one of its sidecars.
     * <ul>
     *   <li>200 with a stored copy: equal confirms the artifact, different evicts it.</li>
     *   <li>200 without a stored copy: a checksum is checked against the artifact's
     *       hash (stored on a match, evicted otherwise); a signature is just stored.</li>
     *   <li>404/410 for a sidecar we have a stored copy of: upstream withdrew the
     *       release, so the artifact is evicted.</li>
     *   <li>Anything else says nothing about the artifact.</li>
     * </ul>
     */
    static Outcome reconcile(Path artifact, Sidecar sidecar, int status, byte[] body)
            throws IOException {
        synchronized (lockFor(artifact)) {
            if (!Files.isRegularFile(artifact)) return Outcome.UNCHANGED;
            var storedFile = sidecar.storedFile(artifact);
            var stored = Files.isRegularFile(storedFile) ? Files.readAllBytes(storedFile) : null;

            if (status == 404 || status == 410) {
                if (stored == null) return Outcome.UNCHANGED;
                evictLocked(artifact);
                return Outcome.EVICTED;
            }
            if (status != 200) return Outcome.UNCHANGED;

            if (stored != null) {
                if (sidecar.sameContent(stored, body)) return Outcome.MATCHED;
                evictLocked(artifact);
                return Outcome.EVICTED;
            }
            if (!sidecar.isChecksum()) {
                writeAtomically(storedFile, body);
                return Outcome.UNCHANGED;
            }
            var fresh = sidecar.hex(body);
            if (fresh != null && fresh.equals(sidecar.hashOf(artifact))) {
                writeAtomically(storedFile, body);
                return Outcome.MATCHED;
            }
            evictLocked(artifact);
            return Outcome.EVICTED;
        }
    }

    /**
     * The stored copy of a sidecar, or null unless both it and the artifact it
     * describes are cached. Used only when upstream cannot be reached.
     */
    static byte[] storedSidecar(Path artifact, Sidecar sidecar) throws IOException {
        synchronized (lockFor(artifact)) {
            var storedFile = sidecar.storedFile(artifact);
            if (!Files.isRegularFile(artifact) || !Files.isRegularFile(storedFile)) return null;
            return Files.readAllBytes(storedFile);
        }
    }

    // The artifact goes first: a sidecar left without its artifact is ignored,
    // but an artifact left without its sidecars would still be served.
    private static void evictLocked(Path artifact) throws IOException {
        Files.deleteIfExists(artifact);
        for (var s : Sidecar.values()) {
            Files.deleteIfExists(s.storedFile(artifact));
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        var temp = Files.createTempFile(target.getParent(), "sc-", ".tmp");
        try {
            Files.write(temp, content);
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Delete a directory tree; used to drop caches written before verification existed. */
    static void deleteTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
