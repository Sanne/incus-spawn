package dev.incusspawn.proxy;

import dev.incusspawn.proxy.VerifiedArtifactStore.Commit;
import dev.incusspawn.proxy.VerifiedArtifactStore.FileState;
import dev.incusspawn.proxy.VerifiedArtifactStore.Outcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedArtifactStoreTest {

    static final byte[] JAR = "jar-content".getBytes(StandardCharsets.UTF_8);
    static final byte[] OTHER = "other-content".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    static String hex(String algorithm, byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
    }

    static byte[] sha1Of(byte[] content) throws Exception {
        return hex("SHA-1", content).getBytes(StandardCharsets.US_ASCII);
    }

    Path artifact() {
        return dir.resolve("g/a/1.0/a-1.0.jar");
    }

    Path cached(byte[] content) throws Exception {
        assertEquals(Commit.COMMITTED, commit(content, content, Instant.now()));
        return artifact();
    }

    /** Commit a download of {@code content} verified on {@code verifiedOn}'s checksum, looked up at {@code lookedUp}. */
    Commit commit(byte[] content, byte[] verifiedOn, Instant lookedUp) throws Exception {
        var download = Files.createTempFile(dir, "dl-", ".tmp");
        Files.write(download, content);
        return VerifiedArtifactStore.verifyAndCommit(download, prepareParent(), Sidecar.SHA1, sha1Of(verifiedOn),
                true, lookedUp);
    }

    /** Reconcile with an answer whose probe was just made. */
    static Outcome reconcile(Path artifact, Sidecar sidecar, int status, byte[] body, boolean mayEvict) throws Exception {
        return VerifiedArtifactStore.reconcile(artifact, sidecar, status, body, mayEvict, Instant.now());
    }

    Path prepareParent() throws Exception {
        Files.createDirectories(artifact().getParent());
        return artifact();
    }

    // --- Sidecar parsing ---

    @Test
    void sidecarOfRecognisesEveryType() {
        assertEquals(Sidecar.SHA1, Sidecar.of("/x/a.jar.sha1"));
        assertEquals(Sidecar.MD5, Sidecar.of("/x/a.jar.md5"));
        assertEquals(Sidecar.SHA256, Sidecar.of("/x/a.jar.sha256"));
        assertEquals(Sidecar.SHA512, Sidecar.of("/x/a.jar.sha512"));
        assertEquals(Sidecar.ASC, Sidecar.of("/x/a.jar.asc"));
        assertNull(Sidecar.of("/x/a.jar"));
        assertEquals("/x/a.jar", Sidecar.SHA512.artifactPath("/x/a.jar.sha512"));
    }

    @Test
    void hexAcceptsBareAndSha1sumFormats() throws Exception {
        var h = hex("SHA-1", JAR);
        assertEquals(h, Sidecar.SHA1.hex((h + "\n").getBytes()));
        assertEquals(h, Sidecar.SHA1.hex((h.toUpperCase() + "  a-1.0.jar\n").getBytes()));
        assertEquals(h, Sidecar.SHA1.hex(("SHA1(a-1.0.jar)= " + h + "\n").getBytes()), "OpenSSL form");
        assertEquals(hex("MD5", JAR), Sidecar.MD5.hex(("MD5 (a-1.0.jar) = " + hex("MD5", JAR)).getBytes()), "BSD form");
        assertNull(Sidecar.SHA1.hex("not-a-checksum".getBytes()));
        assertNull(Sidecar.SHA1.hex(hex("MD5", JAR).getBytes()), "wrong length for the algorithm");
        assertNull(Sidecar.SHA1.hex(new byte[0]));
        assertNull(Sidecar.ASC.hex("-----BEGIN PGP SIGNATURE-----".getBytes()));
    }

    @Test
    void everyInterceptedMavenOrGradleDomainIsABuiltInRepository() {
        var intercepted = new java.util.HashSet<>(ProxyConfig.MAVEN_DOMAINS);
        intercepted.addAll(ProxyConfig.GRADLE_DOMAINS);
        assertEquals(intercepted, CachedRepository.builtInDomains(),
                "a cached domain must declare how a hit is confirmed with upstream, and an intercepted one is cached");
    }

    // --- commit ---

    @Test
    void commitStoresArtifactWithItsSidecar() throws Exception {
        var artifact = cached(JAR);
        assertArrayEquals(JAR, Files.readAllBytes(artifact));
        assertArrayEquals(sha1Of(JAR), Files.readAllBytes(Sidecar.SHA1.storedFile(artifact)));
    }

    @Test
    void commitRejectsMismatchingDownload() throws Exception {
        assertEquals(Commit.REJECTED, commit(OTHER, JAR, Instant.now()));
        assertFalse(Files.exists(artifact()));
        assertFalse(Files.exists(Sidecar.SHA1.storedFile(artifact())));
        assertTrue(ArtifactCacheProxyTest.noDownloadInFlight(dir));
    }

    @Test
    void commitOverAnotherCopyDropsSidecarsDescribingIt() throws Exception {
        var artifact = cached(OTHER);
        reconcile(artifact, Sidecar.MD5, 200, hex("MD5", OTHER).getBytes(), true);
        assertTrue(Files.exists(Sidecar.MD5.storedFile(artifact)));

        cached(JAR);
        assertFalse(Files.exists(Sidecar.MD5.storedFile(artifact)),
                "the stored .md5 described the replaced copy");
    }

    @Test
    void commitIsStampedWithItsLookup() throws Exception {
        var lookedUp = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
        assertEquals(Commit.COMMITTED, commit(JAR, JAR, lookedUp));
        assertEquals(lookedUp, Files.getLastModifiedTime(Sidecar.SHA1.storedFile(artifact())).toInstant(),
                "upstream confirmed these bytes at the lookup, not when their download finished");
    }

    @Test
    void commitFromAnOlderLookupKeepsTheCopyConfirmedAfterIt() throws Exception {
        // Two downloads racing a republish: the one whose lookup came second commits first
        var older = Instant.now().minus(Duration.ofMinutes(2));
        assertEquals(Commit.COMMITTED, commit(OTHER, OTHER, older.plusSeconds(60)));

        assertEquals(Commit.SUPERSEDED, commit(JAR, JAR, older));

        assertArrayEquals(OTHER, Files.readAllBytes(artifact()));
        assertArrayEquals(sha1Of(OTHER), Files.readAllBytes(Sidecar.SHA1.storedFile(artifact())));
        assertTrue(ArtifactCacheProxyTest.noDownloadInFlight(dir));
    }

    @Test
    void commitFromANewerLookupReplacesACopyCommittedAfterIt() throws Exception {
        // The one whose lookup came first commits first: committed later, it is still the older answer
        var older = Instant.now().minus(Duration.ofMinutes(2));
        assertEquals(Commit.COMMITTED, commit(OTHER, OTHER, older));

        assertEquals(Commit.COMMITTED, commit(JAR, JAR, older.plusSeconds(60)));

        assertArrayEquals(JAR, Files.readAllBytes(artifact()));
        assertArrayEquals(sha1Of(JAR), Files.readAllBytes(Sidecar.SHA1.storedFile(artifact())));
    }

    @Test
    void commitReplacesACopyWhoseConfirmationWasExpired() throws Exception {
        cached(OTHER);
        VerifiedArtifactStore.expireConfirmation(artifact(), Sidecar.SHA1);

        assertEquals(Commit.COMMITTED, commit(JAR, JAR, Instant.now().minus(Duration.ofHours(1))));
        assertArrayEquals(JAR, Files.readAllBytes(artifact()));
    }

    // --- reconcile ---

    @Test
    void matchingSidecarConfirms() throws Exception {
        var artifact = cached(JAR);
        var body = (hex("SHA-1", JAR) + "  a-1.0.jar").getBytes();
        assertEquals(Outcome.MATCHED, reconcile(artifact, Sidecar.SHA1, 200, body, true));
        assertTrue(Files.exists(artifact));
    }

    @Test
    void changedSidecarEvictsArtifactAndAllSidecars() throws Exception {
        var artifact = cached(JAR);
        reconcile(artifact, Sidecar.ASC, 200, "sig".getBytes(), true);
        assertEquals(Outcome.EVICTED, reconcile(artifact, Sidecar.SHA1, 200, sha1Of(OTHER), true));
        assertFalse(Files.exists(artifact));
        for (var s : Sidecar.values()) {
            assertFalse(Files.exists(s.storedFile(artifact)), s.name());
        }
    }

    @Test
    void unparseableChecksumSaysNothing() throws Exception {
        var artifact = cached(JAR);
        assertEquals(Outcome.UNCHANGED, reconcile(artifact, Sidecar.SHA1, 200, "<html>".getBytes(), true));
        assertEquals(Outcome.UNCHANGED, reconcile(artifact, Sidecar.MD5, 200, "garbage".getBytes(), true));
        assertTrue(Files.exists(artifact), "a checksum we cannot read is no evidence the artifact changed");
        assertFalse(Files.exists(Sidecar.MD5.storedFile(artifact)));
    }

    @Test
    void contradictionWithoutPermissionToEvictIsReported() throws Exception {
        var artifact = cached(JAR);
        assertEquals(Outcome.DISAGREES, reconcile(artifact, Sidecar.SHA1, 200, sha1Of(OTHER), false));
        assertEquals(Outcome.DISAGREES, reconcile(artifact, Sidecar.SHA1, 404, null, false));
        assertEquals(Outcome.DISAGREES, reconcile(artifact, Sidecar.MD5, 200, hex("MD5", OTHER).getBytes(), false));
        assertTrue(Files.exists(artifact));
        assertArrayEquals(sha1Of(JAR), Files.readAllBytes(Sidecar.SHA1.storedFile(artifact)));
        assertFalse(Files.exists(Sidecar.MD5.storedFile(artifact)), "a contradicting checksum is never stored");
    }

    @Test
    void dropSidecarRemovesOnlyThatSidecar() throws Exception {
        var artifact = cached(JAR);
        var copy = FileState.of(artifact);
        assertTrue(VerifiedArtifactStore.storeSidecar(artifact, copy, Sidecar.ASC, "sig".getBytes()));
        assertTrue(VerifiedArtifactStore.dropSidecar(artifact, copy, Sidecar.ASC));
        assertFalse(Files.exists(Sidecar.ASC.storedFile(artifact)));
        assertTrue(Files.exists(Sidecar.SHA1.storedFile(artifact)));
        assertTrue(Files.exists(artifact));
    }

    @Test
    void storeSidecarNeedsItsArtifact() throws Exception {
        var artifact = cached(JAR);
        var copy = FileState.of(artifact);
        assertTrue(VerifiedArtifactStore.storeSidecar(artifact, copy, Sidecar.ASC, "sig".getBytes()));
        assertArrayEquals("sig".getBytes(), Files.readAllBytes(Sidecar.ASC.storedFile(artifact)));
        Files.delete(artifact);
        assertFalse(VerifiedArtifactStore.storeSidecar(artifact, copy, Sidecar.MD5, "x".getBytes()));
        assertFalse(Files.exists(Sidecar.MD5.storedFile(artifact)));
    }

    @Test
    void sidecarAnsweredForAReplacedCopyChangesNothing() throws Exception {
        var artifact = cached(JAR);
        var reconciled = VerifiedArtifactStore.reconcileCopy(artifact, Sidecar.SHA1, Sidecar.ASC, 404, null,
                false, Instant.now());
        var answered = reconciled.copy();
        assertEquals(FileState.of(artifact), answered, "the copy the answer was judged against");
        assertEquals(Commit.COMMITTED, commit(OTHER, OTHER, Instant.now()));
        Files.writeString(Sidecar.ASC.storedFile(artifact), "sig-2");

        assertFalse(VerifiedArtifactStore.isCopy(artifact, answered));
        assertFalse(VerifiedArtifactStore.storeSidecar(artifact, answered, Sidecar.ASC, "sig-0".getBytes()));
        assertFalse(VerifiedArtifactStore.dropSidecar(artifact, answered, Sidecar.ASC));
        assertEquals("sig-2", Files.readString(Sidecar.ASC.storedFile(artifact)));
        assertFalse(VerifiedArtifactStore.isCopy(artifact, null), "nothing was cached when the answer was reconciled");
        Files.delete(artifact);
        assertNull(VerifiedArtifactStore.reconcileCopy(artifact, Sidecar.SHA1, Sidecar.ASC, 404, null, false,
                Instant.now()).copy());
    }

    @Test
    void artifactWhoseAttributesCannotBeReadIsNotCached() throws Exception {
        // A symlink to itself: reading the artifact's attributes fails with ELOOP, not NoSuchFileException
        var artifact = prepareParent();
        Files.createSymbolicLink(artifact, artifact.getFileName());
        var reconciled = VerifiedArtifactStore.reconcileCopy(artifact, Sidecar.SHA1, Sidecar.ASC, 404, null,
                true, Instant.now());
        assertEquals(Outcome.UNCHANGED, reconciled.outcome());
        assertNull(reconciled.copy());
    }

    @Test
    void newChecksumTypeIsCheckedAgainstTheArtifactOnce() throws Exception {
        var artifact = cached(JAR);
        var md5 = hex("MD5", JAR).getBytes();
        assertEquals(Outcome.MATCHED, reconcile(artifact, Sidecar.MD5, 200, md5, true));
        assertArrayEquals(md5, Files.readAllBytes(Sidecar.MD5.storedFile(artifact)));

        assertEquals(Outcome.EVICTED, reconcile(
                cached(JAR), Sidecar.SHA256, 200, hex("SHA-256", OTHER).getBytes(), true));
    }

    @Test
    void signatureIsStoredThenCompared() throws Exception {
        var artifact = cached(JAR);
        assertEquals(Outcome.UNCHANGED, reconcile(artifact, Sidecar.ASC, 200, "sig-1".getBytes(), true));
        assertEquals(Outcome.MATCHED, reconcile(artifact, Sidecar.ASC, 200, "sig-1".getBytes(), true));
        assertEquals(Outcome.EVICTED, reconcile(artifact, Sidecar.ASC, 200, "sig-2".getBytes(), true));
    }

    @Test
    void withdrawnSidecarWeHadEvicts() throws Exception {
        assertEquals(Outcome.EVICTED, reconcile(cached(JAR), Sidecar.SHA1, 404, null, true));
        assertEquals(Outcome.EVICTED, reconcile(cached(JAR), Sidecar.SHA1, 410, null, true));
    }

    @Test
    void missingSidecarWeNeverHadSaysNothing() throws Exception {
        var artifact = cached(JAR);
        assertEquals(Outcome.UNCHANGED, reconcile(artifact, Sidecar.SHA512, 404, null, true));
        assertTrue(Files.exists(artifact));
    }

    @Test
    void otherStatusesSayNothing() throws Exception {
        var artifact = cached(JAR);
        for (int status : new int[] {401, 403, 429}) {
            assertEquals(Outcome.UNCHANGED, reconcile(artifact, Sidecar.SHA1, status, null, true));
        }
        assertTrue(Files.exists(artifact));
    }

    @Test
    void nothingToReconcileWithoutAnArtifact() throws Exception {
        assertEquals(Outcome.UNCHANGED, reconcile(artifact(), Sidecar.SHA1, 200, sha1Of(JAR), true));
        assertFalse(Files.exists(Sidecar.SHA1.storedFile(artifact())));
    }

    @Test
    void renewalFromAnOlderProbeDoesNotSupersedeANewerLookup() throws Exception {
        // A probe made before the copy was committed answers after it: it must not pass for a
        // confirmation newer than the commit, or a download looked up in between is superseded
        var committed = Instant.now().minus(Duration.ofMinutes(3)).truncatedTo(ChronoUnit.SECONDS);
        assertEquals(Commit.COMMITTED, commit(OTHER, OTHER, committed));

        assertEquals(Outcome.SUPERSEDED, VerifiedArtifactStore.reconcile(
                artifact(), Sidecar.SHA1, 200, sha1Of(OTHER), true, committed.minusSeconds(60)));
        assertEquals(committed, Files.getLastModifiedTime(Sidecar.SHA1.storedFile(artifact())).toInstant());

        assertEquals(Commit.COMMITTED, commit(JAR, JAR, committed.plusSeconds(60)));
        assertArrayEquals(JAR, Files.readAllBytes(artifact()));
    }

    @Test
    void matchIsStampedWithItsProbe() throws Exception {
        cached(JAR);
        var stored = Sidecar.SHA1.storedFile(artifact());
        Files.setLastModifiedTime(stored, FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        var probed = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);

        assertEquals(Outcome.MATCHED, VerifiedArtifactStore.reconcile(artifact(), Sidecar.SHA1, 200, sha1Of(JAR), true, probed));
        assertEquals(probed, Files.getLastModifiedTime(stored).toInstant(),
                "upstream confirmed the copy when it was asked, not when its answer was applied");
    }

    @Test
    void newlyStoredChecksumIsStampedWithItsProbe() throws Exception {
        cached(JAR);
        var stored = Sidecar.SHA1.storedFile(artifact());
        Files.delete(stored);
        var probed = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);

        assertEquals(Outcome.MATCHED, VerifiedArtifactStore.reconcile(artifact(), Sidecar.SHA1, 200, sha1Of(JAR), true, probed));
        assertEquals(probed, Files.getLastModifiedTime(stored).toInstant());
    }

    @Test
    void answerFromAnOlderProbeKeepsTheCopyConfirmedAfterIt() throws Exception {
        var committed = Instant.now().minus(Duration.ofMinutes(3));
        assertEquals(Commit.COMMITTED, commit(JAR, JAR, committed));
        var older = committed.minusSeconds(60);

        assertEquals(Outcome.SUPERSEDED, VerifiedArtifactStore.reconcile(artifact(), Sidecar.SHA1, 200, sha1Of(OTHER), true, older));
        assertEquals(Outcome.SUPERSEDED, VerifiedArtifactStore.reconcile(artifact(), Sidecar.SHA1, 404, null, true, older));
        assertEquals(Outcome.SUPERSEDED, VerifiedArtifactStore.reconcile(
                artifact(), Sidecar.SHA1, Sidecar.MD5, 200, hex("MD5", OTHER).getBytes(), true, older),
                "another sidecar is ordered against the artifact's own checksum");

        assertArrayEquals(JAR, Files.readAllBytes(artifact()));
        assertFalse(Files.exists(Sidecar.MD5.storedFile(artifact())));
        assertEquals(Outcome.EVICTED, VerifiedArtifactStore.reconcile(
                artifact(), Sidecar.SHA1, 404, null, true, committed.plusSeconds(1)), "a later takedown still evicts");
    }

    @Test
    void cachedCopyNeedsItsArtifact() throws Exception {
        var artifact = cached(JAR);
        var copy = VerifiedArtifactStore.cachedCopy(artifact, Sidecar.SHA1, Sidecar.SHA1);
        assertArrayEquals(sha1Of(JAR), copy.checksum());
        assertArrayEquals(sha1Of(JAR), copy.sidecar());
        assertEquals(Files.size(artifact), copy.size());
        assertNotNull(copy.confirmedAt());
        assertNull(VerifiedArtifactStore.cachedCopy(artifact, Sidecar.SHA1, Sidecar.MD5).sidecar());
        Files.delete(artifact);
        assertNull(VerifiedArtifactStore.cachedCopy(artifact, Sidecar.SHA1, Sidecar.SHA1));
    }

    @Test
    void onlyAnAuthoritativeMatchRenewsTheConfirmation() throws Exception {
        var artifact = cached(JAR);
        var stored = Sidecar.SHA1.storedFile(artifact);
        var old = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(3)));
        Files.setLastModifiedTime(stored, old);

        assertEquals(Outcome.MATCHED, reconcile(artifact, Sidecar.SHA1, 200, sha1Of(JAR), false));
        assertEquals(old, Files.getLastModifiedTime(stored), "a sidecar that could not evict vouches for nothing");
        assertEquals(Outcome.MATCHED, reconcile(artifact, Sidecar.SHA1, 200, sha1Of(JAR), true));
        assertTrue(Files.getLastModifiedTime(stored).compareTo(old) > 0, "renewed");
    }

    @Test
    void aNonAuthoritativeHashMatchStoresTheChecksumUnconfirmed() throws Exception {
        var artifact = cached(JAR);
        var stored = Sidecar.SHA1.storedFile(artifact);
        Files.delete(stored);
        assertEquals(Outcome.MATCHED, reconcile(artifact, Sidecar.SHA1, 200, sha1Of(JAR), false));
        assertArrayEquals(sha1Of(JAR), Files.readAllBytes(stored));
        assertEquals(java.time.Instant.EPOCH, Files.getLastModifiedTime(stored).toInstant());
    }

    @Test
    void aReplacedArtifactIsNoLongerTheCachedCopy() throws Exception {
        var artifact = cached(JAR);
        var copy = VerifiedArtifactStore.cachedCopy(artifact, Sidecar.SHA1, null);
        assertTrue(VerifiedArtifactStore.unchanged(artifact, copy));
        var replacement = artifact.resolveSibling("replacement");
        Files.write(replacement, "other bytes".getBytes());
        Files.move(replacement, artifact, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertFalse(VerifiedArtifactStore.unchanged(artifact, copy));
    }

    @Test
    void expiringAConfirmation() throws Exception {
        var artifact = cached(JAR);
        VerifiedArtifactStore.expireConfirmation(artifact, Sidecar.SHA1);
        assertEquals(java.time.Instant.EPOCH,
                VerifiedArtifactStore.cachedCopy(artifact, Sidecar.SHA1, null).confirmedAt().toInstant());
    }

    // --- host copy import ---

    @Test
    void importedCopyIsDetachedFromItsSource() throws Exception {
        var source = dir.resolve("m2/a-1.0.jar");
        Files.createDirectories(source.getParent());
        Files.write(source, JAR);

        assertEquals(Commit.COMMITTED, VerifiedArtifactStore.importCopy(source, artifact(), Sidecar.SHA1, sha1Of(JAR), Instant.now()));
        assertFalse(Files.isSameFile(source, artifact()));

        // An in-place rewrite (O_TRUNC, as plain cp does) would show through a hardlink
        try (var out = Files.newOutputStream(source)) {
            out.write(OTHER);
        }
        assertArrayEquals(JAR, Files.readAllBytes(artifact()));
    }

    @Test
    void importRejectsHostCopyThatDiffersFromUpstream() throws Exception {
        var source = dir.resolve("m2/a-1.0.jar");
        Files.createDirectories(source.getParent());
        Files.write(source, OTHER);

        assertEquals(Commit.REJECTED, VerifiedArtifactStore.importCopy(source, artifact(), Sidecar.SHA1, sha1Of(JAR), Instant.now()));
        assertFalse(Files.exists(artifact()));
        try (var leftovers = Files.list(artifact().getParent())) {
            assertEquals(0, leftovers.count(), "no temp files left behind");
        }
    }

    @Test
    void renewOfAChecksumEvictedMeanwhileIsSilent() throws Exception {
        // A renew that lost the race to an eviction has nothing to stamp, and nothing to warn about
        var artifact = dir.resolve("pkg-1.0.0.tgz");
        var storedChecksum = Path.of(artifact + ".shasum");
        var originalErr = System.err;
        var originalHome = System.getProperty("user.home");
        var err = new ByteArrayOutputStream();
        // A warning would go to stderr, which another test may have suppressed, and to the home's log file
        var suppressed = ProxyLog.setSuppressStderr(false);
        System.setProperty("user.home", dir.toString());
        System.setErr(new PrintStream(err, true));
        try {
            VerifiedArtifactStore.renewConfirmation(artifact, storedChecksum, Instant.now());
        } finally {
            System.setErr(originalErr);
            System.setProperty("user.home", originalHome);
            ProxyLog.setSuppressStderr(suppressed);
        }

        assertFalse(Files.exists(storedChecksum));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }
}
