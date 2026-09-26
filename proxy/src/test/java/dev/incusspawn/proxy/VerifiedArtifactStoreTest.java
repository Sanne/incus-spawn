package dev.incusspawn.proxy;

import dev.incusspawn.proxy.VerifiedArtifactStore.Outcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
        var download = Files.createTempFile(dir, "dl-", ".tmp");
        Files.write(download, content);
        assertTrue(VerifiedArtifactStore.verifyAndCommit(download, prepareParent(), Sidecar.SHA1, sha1Of(content)));
        return artifact();
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
        assertNull(Sidecar.SHA1.hex("not-a-checksum".getBytes()));
        assertNull(Sidecar.SHA1.hex(hex("MD5", JAR).getBytes()), "wrong length for the algorithm");
        assertNull(Sidecar.SHA1.hex(new byte[0]));
        assertNull(Sidecar.ASC.hex("-----BEGIN PGP SIGNATURE-----".getBytes()));
    }

    @Test
    void everyCachedDomainDeclaresItsRevalidation() {
        for (var domain : ProxyConfig.MAVEN_DOMAINS) {
            assertNotNull(Revalidation.forDomain(domain),
                    domain + " is cached, so it must declare how a hit is confirmed with upstream");
        }
        for (var domain : ProxyConfig.GRADLE_DOMAINS) {
            assertNotNull(Revalidation.forDomain(domain), domain);
        }
        assertNull(Revalidation.forDomain("example.com"));
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
        var download = Files.createTempFile(dir, "dl-", ".tmp");
        Files.write(download, OTHER);
        assertFalse(VerifiedArtifactStore.verifyAndCommit(download, prepareParent(), Sidecar.SHA1, sha1Of(JAR)));
        assertFalse(Files.exists(artifact()));
        assertFalse(Files.exists(Sidecar.SHA1.storedFile(artifact())));
        assertFalse(Files.exists(download));
    }

    @Test
    void commitOverAnotherCopyDropsSidecarsDescribingIt() throws Exception {
        var artifact = cached(OTHER);
        VerifiedArtifactStore.reconcile(artifact, Sidecar.MD5, 200, hex("MD5", OTHER).getBytes());
        assertTrue(Files.exists(Sidecar.MD5.storedFile(artifact)));

        cached(JAR);
        assertFalse(Files.exists(Sidecar.MD5.storedFile(artifact)),
                "the stored .md5 described the replaced copy");
    }

    // --- reconcile ---

    @Test
    void matchingSidecarConfirms() throws Exception {
        var artifact = cached(JAR);
        var body = (hex("SHA-1", JAR) + "  a-1.0.jar").getBytes();
        assertEquals(Outcome.MATCHED, VerifiedArtifactStore.reconcile(artifact, Sidecar.SHA1, 200, body));
        assertTrue(Files.exists(artifact));
    }

    @Test
    void changedSidecarEvictsArtifactAndAllSidecars() throws Exception {
        var artifact = cached(JAR);
        VerifiedArtifactStore.reconcile(artifact, Sidecar.ASC, 200, "sig".getBytes());
        assertEquals(Outcome.EVICTED, VerifiedArtifactStore.reconcile(artifact, Sidecar.SHA1, 200, sha1Of(OTHER)));
        assertFalse(Files.exists(artifact));
        for (var s : Sidecar.values()) {
            assertFalse(Files.exists(s.storedFile(artifact)), s.name());
        }
    }

    @Test
    void unparseableChecksumEvicts() throws Exception {
        var artifact = cached(JAR);
        assertEquals(Outcome.EVICTED, VerifiedArtifactStore.reconcile(artifact, Sidecar.SHA1, 200, "<html>".getBytes()));
    }

    @Test
    void newChecksumTypeIsCheckedAgainstTheArtifactOnce() throws Exception {
        var artifact = cached(JAR);
        var md5 = hex("MD5", JAR).getBytes();
        assertEquals(Outcome.MATCHED, VerifiedArtifactStore.reconcile(artifact, Sidecar.MD5, 200, md5));
        assertArrayEquals(md5, Files.readAllBytes(Sidecar.MD5.storedFile(artifact)));

        assertEquals(Outcome.EVICTED, VerifiedArtifactStore.reconcile(
                cached(JAR), Sidecar.SHA256, 200, hex("SHA-256", OTHER).getBytes()));
    }

    @Test
    void signatureIsStoredThenCompared() throws Exception {
        var artifact = cached(JAR);
        assertEquals(Outcome.UNCHANGED, VerifiedArtifactStore.reconcile(artifact, Sidecar.ASC, 200, "sig-1".getBytes()));
        assertEquals(Outcome.MATCHED, VerifiedArtifactStore.reconcile(artifact, Sidecar.ASC, 200, "sig-1".getBytes()));
        assertEquals(Outcome.EVICTED, VerifiedArtifactStore.reconcile(artifact, Sidecar.ASC, 200, "sig-2".getBytes()));
    }

    @Test
    void withdrawnSidecarWeHadEvicts() throws Exception {
        assertEquals(Outcome.EVICTED, VerifiedArtifactStore.reconcile(cached(JAR), Sidecar.SHA1, 404, null));
        assertEquals(Outcome.EVICTED, VerifiedArtifactStore.reconcile(cached(JAR), Sidecar.SHA1, 410, null));
    }

    @Test
    void missingSidecarWeNeverHadSaysNothing() throws Exception {
        var artifact = cached(JAR);
        assertEquals(Outcome.UNCHANGED, VerifiedArtifactStore.reconcile(artifact, Sidecar.SHA512, 404, null));
        assertTrue(Files.exists(artifact));
    }

    @Test
    void otherStatusesSayNothing() throws Exception {
        var artifact = cached(JAR);
        for (int status : new int[] {401, 403, 429}) {
            assertEquals(Outcome.UNCHANGED, VerifiedArtifactStore.reconcile(artifact, Sidecar.SHA1, status, null));
        }
        assertTrue(Files.exists(artifact));
    }

    @Test
    void nothingToReconcileWithoutAnArtifact() throws Exception {
        assertEquals(Outcome.UNCHANGED, VerifiedArtifactStore.reconcile(artifact(), Sidecar.SHA1, 200, sha1Of(JAR)));
        assertFalse(Files.exists(Sidecar.SHA1.storedFile(artifact())));
    }

    @Test
    void storedSidecarNeedsItsArtifact() throws Exception {
        var artifact = cached(JAR);
        assertArrayEquals(sha1Of(JAR), VerifiedArtifactStore.storedSidecar(artifact, Sidecar.SHA1));
        assertNull(VerifiedArtifactStore.storedSidecar(artifact, Sidecar.MD5));
        Files.delete(artifact);
        assertNull(VerifiedArtifactStore.storedSidecar(artifact, Sidecar.SHA1));
    }

    // --- host copy import ---

    @Test
    void importedCopyIsDetachedFromItsSource() throws Exception {
        var source = dir.resolve("m2/a-1.0.jar");
        Files.createDirectories(source.getParent());
        Files.write(source, JAR);

        assertTrue(VerifiedArtifactStore.importCopy(source, artifact(), Sidecar.SHA1, sha1Of(JAR)));
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

        assertFalse(VerifiedArtifactStore.importCopy(source, artifact(), Sidecar.SHA1, sha1Of(JAR)));
        assertFalse(Files.exists(artifact()));
        try (var leftovers = Files.list(artifact().getParent())) {
            assertEquals(0, leftovers.count(), "no temp files left behind");
        }
    }

    @Test
    void deleteTreeRemovesEverything() throws Exception {
        var root = dir.resolve("legacy");
        Files.createDirectories(root.resolve("a/b"));
        Files.write(root.resolve("a/b/c.jar"), JAR);
        VerifiedArtifactStore.deleteTree(root);
        assertFalse(Files.exists(root));
        VerifiedArtifactStore.deleteTree(root);
    }
}
