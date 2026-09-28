package dev.incusspawn.proxy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

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
 *   <li>The stored checksum's modification time is when upstream last confirmed
 *       the artifact: set when both are committed, and again on every match by an
 *       answer that could have evicted it. Renewing it is best-effort: a file the
 *       proxy cannot re-stamp just ages, and gets confirmed before it is served.</li>
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
        // On disk before it is named: a power loss must not leave a truncated
        // artifact beside an intact checksum
        force(download);
        synchronized (lockFor(artifact)) {
            // In this order a crash at any point leaves no sidecar describing other
            // bytes: sidecars of a previous copy go first, the checksum comes last,
            // and an artifact without a stored checksum is hashed before one is trusted
            if (storeSidecar) deleteSidecarsLocked(artifact);
            // Replaced in one step, so a concurrent reader never finds it missing
            Files.move(download, artifact,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (storeSidecar) writeAtomically(checksum.storedFile(artifact), expected);
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
                if (!sidecar.sameContent(stored, body)) return contradictedLocked(artifact, mayEvict);
                // Only an answer that could have evicted the copy may vouch for it (a .sha1
                // on Central is outranked by the checksum header, and so confirms nothing)
                if (mayEvict) stampConfirmation(storedFile, Instant.now());
                return Outcome.MATCHED;
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
                if (!mayEvict) stampConfirmation(sidecar.storedFile(artifact), Instant.EPOCH);
                return Outcome.MATCHED;
            }
            return contradictedLocked(artifact, mayEvict);
        }
    }

    /**
     * What is cached for an artifact: the artifact file ({@code state}, and so its
     * size); its stored checksum and when upstream last confirmed it (both null
     * without one); and the stored copy of the sidecar asked for (null without one,
     * or when none was asked for).
     */
    record CachedCopy(FileState state, byte[] checksum, FileTime confirmedAt, byte[] sidecar) {
        long size() { return state.size(); }
    }

    // A read that saw the artifact replaced under it
    private static final CachedCopy TORN = new CachedCopy(null, null, null, null);

    /**
     * The cached copy of an artifact, or null when it is not cached. Read without the
     * lock, which commits hold across an fsync: a commit drops the old sidecars before
     * it replaces the artifact and writes the new checksum after, and an eviction
     * removes the artifact first, so while the artifact stays the same file across
     * the reads, the checksum read describes it. Otherwise it is read again under the
     * lock.
     */
    static CachedCopy cachedCopy(Path artifact, Sidecar checksum, Sidecar sidecar) throws IOException {
        var copy = readCopy(artifact, checksum, sidecar);
        if (copy != TORN) return copy;
        synchronized (lockFor(artifact)) {
            copy = readCopy(artifact, checksum, sidecar);
            return copy == TORN ? null : copy;
        }
    }

    private static CachedCopy readCopy(Path artifact, Sidecar checksum, Sidecar sidecar) throws IOException {
        var before = attributesOrNull(artifact);
        if (before == null || !before.isRegularFile()) return null;
        var storedFile = checksum.storedFile(artifact);
        var checksumAttrs = attributesOrNull(storedFile);
        var stored = checksumAttrs != null && checksumAttrs.isRegularFile() ? readOrNull(storedFile) : null;
        var sidecarBody = sidecar == null ? null
                : sidecar == checksum ? stored
                : readOrNull(sidecar.storedFile(artifact));
        var state = FileState.of(before);
        if (!state.equals(FileState.of(artifact))) return TORN;
        return new CachedCopy(state, stored, stored == null ? null : checksumAttrs.lastModifiedTime(), sidecarBody);
    }

    /** Whether the artifact is still the file a {@link CachedCopy} was read from. */
    static boolean unchanged(Path artifact, CachedCopy copy) {
        return copy.state().equals(FileState.of(artifact));
    }

    /**
     * Make the artifact's last confirmation untrusted, so its next hit confirms it
     * first: for a background confirmation that could neither renew nor evict.
     */
    static void expireConfirmation(Path artifact, Sidecar checksum) {
        synchronized (lockFor(artifact)) {
            var storedFile = checksum.storedFile(artifact);
            if (Files.isRegularFile(storedFile)) stampConfirmation(storedFile, Instant.EPOCH);
        }
    }

    // Best-effort (see the class invariants): a file owned by another user can be
    // readable, and even writable, and still refuse a new modification time
    private static void stampConfirmation(Path storedChecksum, Instant at) {
        try {
            Files.setLastModifiedTime(storedChecksum, FileTime.from(at));
        } catch (IOException e) {
            ProxyLog.warn("Cannot record the confirmation of " + storedChecksum + ": " + e.getMessage());
        }
    }

    private static BasicFileAttributes attributesOrNull(Path file) throws IOException {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static byte[] readOrNull(Path file) throws IOException {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Store a sidecar beside a cached artifact, if it is still cached. */
    static void storeSidecar(Path artifact, Sidecar sidecar, byte[] body) throws IOException {
        synchronized (lockFor(artifact)) {
            if (Files.isRegularFile(artifact)) writeAtomically(sidecar.storedFile(artifact), body);
        }
    }

    /** Drop the stored copy of a sidecar upstream no longer publishes. */
    static void dropSidecar(Path artifact, Sidecar sidecar) throws IOException {
        synchronized (lockFor(artifact)) {
            Files.deleteIfExists(sidecar.storedFile(artifact));
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
    record FileState(Object fileKey, FileTime modified, long size) {
        static FileState of(BasicFileAttributes attrs) {
            return new FileState(attrs.fileKey(), attrs.lastModifiedTime(), attrs.size());
        }

        static FileState of(Path file) {
            try {
                return of(Files.readAttributes(file, BasicFileAttributes.class));
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        var temp = Files.createTempFile(target.getParent(), "sc-", ".tmp");
        try {
            Files.write(temp, content);
            force(temp);
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
