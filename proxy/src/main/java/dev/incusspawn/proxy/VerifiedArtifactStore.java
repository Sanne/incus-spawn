package dev.incusspawn.proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * On-disk half of the Maven/Gradle artifact cache: an artifact plus the stored
 * copies of the sidecars upstream published for it, kept next to each other.
 * <p>
 * Invariants:
 * <ul>
 *   <li>An artifact is only committed after its bytes matched a checksum from
 *       upstream, and that checksum is committed with it as a sidecar.</li>
 *   <li>A stored checksum sidecar always describes the artifact beside it: it was
 *       either committed with it or checked against its hash before being stored.</li>
 *   <li>Commit, reconcile and evict for one artifact are serialized, so a store
 *       racing an eviction cannot leave a sidecar next to an artifact it does not
 *       describe. Hashing, which can take long for a large artifact, happens
 *       outside that lock.</li>
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
        /**
         * The answer contradicts the cached artifact, but the caller did not allow
         * this sidecar to evict it; nothing was changed.
         */
        DISAGREES,
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
     * Commit a downloaded file if it matches the expected checksum; otherwise
     * delete the download. With {@code storeSidecar}, the checksum is committed
     * beside it as that sidecar, replacing any sidecars stored for a previous copy.
     *
     * @param expected the checksum, as a sidecar body
     * @return whether the file was committed
     */
    static boolean verifyAndCommit(Path download, Path artifact, Sidecar checksum,
                                   byte[] expected, boolean storeSidecar) throws IOException {
        var hex = checksum.hex(expected);
        if (hex == null || !hex.equals(checksum.hashOf(download))) {
            Files.deleteIfExists(download);
            return false;
        }
        synchronized (lockFor(artifact)) {
            if (storeSidecar) {
                // A concurrent miss may have committed first: its other stored
                // sidecars describe that copy, not this one
                deleteSidecarsLocked(artifact);
                writeAtomically(checksum.storedFile(artifact), expected);
            }
            // Replaced in one step, so a concurrent reader never finds it missing
            Files.move(download, artifact,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * Bring a file from outside the cache (the host's {@code ~/.m2}) in as a
     * detached copy, and commit it if the copy matches upstream's checksum. The
     * copy is hashed, not the source, so a source changing mid-way cannot slip in.
     * <p>
     * {@code Files.copy} clones where the filesystem can ({@code copy_file_range},
     * which btrfs and XFS turn into a reflink; {@code clonefile} on APFS), so the
     * copy costs no space there and a plain copy elsewhere. A hardlink would share
     * the inode, so an in-place rewrite of the source would change the cache.
     */
    static boolean importCopy(Path source, Path artifact, Sidecar checksum,
                              byte[] expected) throws IOException {
        Files.createDirectories(artifact.getParent());
        var temp = Files.createTempFile(artifact.getParent(), "m2-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            return verifyAndCommit(temp, artifact, checksum, expected, true);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Reconcile a cached artifact with a fresh upstream answer for one of its sidecars.
     * <ul>
     *   <li>200 with a stored copy: equal confirms the artifact, different evicts it.</li>
     *   <li>200 without a stored copy: a checksum is checked against the artifact's
     *       hash (stored on a match, evicted otherwise); a signature is just stored.</li>
     *   <li>200 with a checksum we cannot parse says nothing: it is not evidence
     *       that the artifact changed.</li>
     *   <li>404/410 for a sidecar we have a stored copy of: upstream withdrew the
     *       release, so the artifact is evicted.</li>
     *   <li>Anything else says nothing about the artifact.</li>
     * </ul>
     *
     * @param mayEvict whether this sidecar may evict on its own; if not, a
     *                 contradiction is reported as {@link Outcome#DISAGREES}
     */
    static Outcome reconcile(Path artifact, Sidecar sidecar, int status, byte[] body,
                             boolean mayEvict) throws IOException {
        FileState before;
        synchronized (lockFor(artifact)) {
            if (!Files.isRegularFile(artifact)) return Outcome.UNCHANGED;
            var storedFile = sidecar.storedFile(artifact);
            var stored = Files.isRegularFile(storedFile) ? Files.readAllBytes(storedFile) : null;

            if (status == 404 || status == 410) {
                return stored == null ? Outcome.UNCHANGED : contradictedLocked(artifact, mayEvict);
            }
            if (status != 200) return Outcome.UNCHANGED;
            if (sidecar.isChecksum() && sidecar.hex(body) == null) return Outcome.UNCHANGED;

            if (stored != null) {
                return sidecar.sameContent(stored, body) ? Outcome.MATCHED : contradictedLocked(artifact, mayEvict);
            }
            if (!sidecar.isChecksum()) {
                writeAtomically(storedFile, body);
                return Outcome.UNCHANGED;
            }
            before = FileState.of(artifact);
        }

        String actual;
        try {
            actual = sidecar.hashOf(artifact);
        } catch (NoSuchFileException e) {
            return Outcome.UNCHANGED;
        }

        synchronized (lockFor(artifact)) {
            // Replaced or evicted while it was being hashed: the hash describes nothing cached
            if (!before.equals(FileState.of(artifact))) return Outcome.UNCHANGED;
            if (sidecar.hex(body).equals(actual)) {
                writeAtomically(sidecar.storedFile(artifact), body);
                return Outcome.MATCHED;
            }
            return contradictedLocked(artifact, mayEvict);
        }
    }

    /** Store a sidecar beside a cached artifact, if it is still cached. */
    static void storeSidecar(Path artifact, Sidecar sidecar, byte[] body) throws IOException {
        synchronized (lockFor(artifact)) {
            if (Files.isRegularFile(artifact)) writeAtomically(sidecar.storedFile(artifact), body);
        }
    }

    /**
     * The stored copy of a sidecar, or null unless both it and the artifact it
     * describes are cached. Served only when upstream cannot be reached.
     */
    static byte[] storedSidecar(Path artifact, Sidecar sidecar) throws IOException {
        synchronized (lockFor(artifact)) {
            var storedFile = sidecar.storedFile(artifact);
            if (!Files.isRegularFile(artifact) || !Files.isRegularFile(storedFile)) return null;
            return Files.readAllBytes(storedFile);
        }
    }

    private static Outcome contradictedLocked(Path artifact, boolean mayEvict) throws IOException {
        if (!mayEvict) return Outcome.DISAGREES;
        evictLocked(artifact);
        return Outcome.EVICTED;
    }

    // The artifact goes first: a sidecar left without its artifact is ignored,
    // but an artifact left without its sidecars would still be served.
    private static void evictLocked(Path artifact) throws IOException {
        Files.deleteIfExists(artifact);
        deleteSidecarsLocked(artifact);
    }

    private static void deleteSidecarsLocked(Path artifact) throws IOException {
        for (var s : Sidecar.ALL) {
            Files.deleteIfExists(s.storedFile(artifact));
        }
    }

    /** Enough to tell whether a file was replaced: a new inode or new contents change it. */
    private record FileState(Object fileKey, FileTime modified, long size) {
        static FileState of(Path file) {
            try {
                var attrs = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileState(attrs.fileKey(), attrs.lastModifiedTime(), attrs.size());
            } catch (IOException e) {
                return null;
            }
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
}
