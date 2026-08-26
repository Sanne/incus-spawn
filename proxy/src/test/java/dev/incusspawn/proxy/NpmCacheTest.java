package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NpmCacheTest {

    @Test
    void npmDomainsAreIntercepted() {
        for (var domain : ProxyConfig.NPM_DOMAINS) {
            assertTrue(ProxyConfig.isInterceptedDomain(domain, ProxyConfig.builtinInterceptedDomains(), java.util.List.of()),
                    domain + " should be intercepted");
            assertTrue(ProxyConfig.builtinInterceptedDomains().contains(domain),
                    domain + " should be in interceptedDomains()");
        }
    }

    // --- tarball pattern ---

    @Test
    void tarballPatternMatchesScopedPackage() {
        var m = MitmProxy.NPM_TARBALL_PATTERN.matcher("/@openai/codex/-/codex-0.1.0.tgz");
        assertTrue(m.matches());
        assertEquals("@openai/codex/-/codex-0.1.0.tgz", m.group(1));
    }

    @Test
    void tarballPatternMatchesUnscopedPackage() {
        var m = MitmProxy.NPM_TARBALL_PATTERN.matcher("/express/-/express-4.18.2.tgz");
        assertTrue(m.matches());
        assertEquals("express/-/express-4.18.2.tgz", m.group(1));
    }

    @Test
    void tarballPatternMatchesPreReleaseVersion() {
        var m = MitmProxy.NPM_TARBALL_PATTERN.matcher("/@types/node/-/node-20.0.0-beta.1.tgz");
        assertTrue(m.matches());
        assertEquals("@types/node/-/node-20.0.0-beta.1.tgz", m.group(1));
    }

    @Test
    void tarballPatternRejectsMetadataPath() {
        assertFalse(MitmProxy.NPM_TARBALL_PATTERN.matcher("/@openai/codex").matches());
        assertFalse(MitmProxy.NPM_TARBALL_PATTERN.matcher("/express").matches());
    }

    @Test
    void tarballPatternRejectsSearchPath() {
        assertFalse(MitmProxy.NPM_TARBALL_PATTERN.matcher("/-/v1/search?text=express").matches());
    }

    @Test
    void tarballPatternRejectsPathWithoutVersionDigit() {
        assertFalse(MitmProxy.NPM_TARBALL_PATTERN.matcher("/express/-/express-latest.tgz").matches());
    }

    @Test
    void tarballPatternAllowsDotDotButContainmentCheckBlocks() {
        var m = MitmProxy.NPM_TARBALL_PATTERN.matcher("/../-/..-1.0.tgz");
        assertTrue(m.matches(), "regex matches (containment check is elsewhere)");
        assertEquals("../-/..-1.0.tgz", m.group(1));
    }

    // --- path traversal ---

    @Test
    void storePackageEtagRejectsTraversal(@TempDir Path tmp) throws Exception {
        var origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            MitmProxy.storePackageEtag("../../etc", "\"evil\"");

            var escaped = tmp.resolve(".cache/incus-spawn/etc/.etag");
            assertFalse(Files.exists(escaped), "should not write outside npm cache dir");

            var legitimate = tmp.resolve(".cache/incus-spawn/npm/../../etc/.etag");
            assertFalse(Files.exists(legitimate.normalize()),
                    "should not write anywhere when path escapes");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    // --- packument pattern ---

    @Test
    void packumentPatternMatchesUnscopedPackage() {
        var m = MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/express");
        assertTrue(m.matches());
        assertEquals("express", m.group(1));
    }

    @Test
    void packumentPatternMatchesScopedPackage() {
        var m = MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/@openai/codex");
        assertTrue(m.matches());
        assertEquals("@openai/codex", m.group(1));
    }

    @Test
    void packumentPatternMatchesDottedPackageName() {
        var m = MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/socket.io");
        assertTrue(m.matches());
        assertEquals("socket.io", m.group(1));
    }

    @Test
    void packumentPatternRejectsTarballPath() {
        assertFalse(MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/@openai/codex/-/codex-0.1.0.tgz").matches());
        assertFalse(MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/express/-/express-4.18.2.tgz").matches());
    }

    @Test
    void packumentPatternRejectsPerVersionPath() {
        assertFalse(MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/express/4.18.2").matches());
        assertFalse(MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/@openai/codex/0.1.0").matches());
    }

    @Test
    void packumentPatternRejectsSpecialPaths() {
        assertFalse(MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/-/v1/search").matches());
        assertFalse(MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/-/npm/v1/security/advisories").matches());
    }

    @Test
    void packumentPatternRejectsRootPath() {
        assertFalse(MitmProxy.NPM_PACKUMENT_PATTERN.matcher("/").matches());
    }

    // --- package ref parsing ---

    @Test
    void parseScopedPackagePath() {
        var ref = MitmProxy.parseNpmTarballPath("@openai/codex/-/codex-0.1.0.tgz");
        assertNotNull(ref);
        assertEquals("@openai/codex", ref.packageName());
        assertEquals("0.1.0", ref.version());
    }

    @Test
    void parseUnscopedPackagePath() {
        var ref = MitmProxy.parseNpmTarballPath("express/-/express-4.18.2.tgz");
        assertNotNull(ref);
        assertEquals("express", ref.packageName());
        assertEquals("4.18.2", ref.version());
    }

    @Test
    void parsePreReleaseVersion() {
        var ref = MitmProxy.parseNpmTarballPath("@types/node/-/node-20.0.0-beta.1.tgz");
        assertNotNull(ref);
        assertEquals("@types/node", ref.packageName());
        assertEquals("20.0.0-beta.1", ref.version());
    }

    @Test
    void parseHyphenatedPackageName() {
        var ref = MitmProxy.parseNpmTarballPath("es5-ext/-/es5-ext-0.10.62.tgz");
        assertNotNull(ref);
        assertEquals("es5-ext", ref.packageName());
        assertEquals("0.10.62", ref.version());
    }

    @Test
    void parseReturnsNullForInvalidPath() {
        assertNull(MitmProxy.parseNpmTarballPath("express"));
        assertNull(MitmProxy.parseNpmTarballPath("express/-/e.tgz"));
    }

    // --- digest verification ---

    @Test
    void verifyDigestSha256(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.bin");
        Files.writeString(file, "hello");
        assertTrue(MitmProxy.verifyDigest(file,
                "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        assertFalse(MitmProxy.verifyDigest(file, "sha256:0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void verifyDigestSha1(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.bin");
        Files.writeString(file, "hello");
        assertTrue(MitmProxy.verifyDigest(file,
                "sha1:aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"));
        assertFalse(MitmProxy.verifyDigest(file, "sha1:0000000000000000000000000000000000000000"));
    }

    @Test
    void verifyDigestRejectsUnknownAlgorithm(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("test.bin");
        Files.writeString(file, "hello");
        assertFalse(MitmProxy.verifyDigest(file, "md5:abcd"));
    }

    // --- readFileOrNull ---

    @Test
    void readFileOrNullReturnsStrippedContent(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("data.txt");
        Files.writeString(f, "  hello world  \n");
        assertEquals("hello world", MitmProxy.readFileOrNull(f));
    }

    @Test
    void readFileOrNullReturnsNullForMissing(@TempDir Path tmp) {
        assertNull(MitmProxy.readFileOrNull(tmp.resolve("nope.txt")));
    }

    @Test
    void readFileOrNullReturnsEmptyStringForEmptyFile(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("empty.txt");
        Files.writeString(f, "");
        assertEquals("", MitmProxy.readFileOrNull(f));
    }

    // --- sidecar file writing ---

    @Test
    void writeNpmSidecarFilesCreatesShasumAndEtag(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("express/-/express-4.18.2.tgz");
        MitmProxy.writeNpmSidecarFiles(cacheFile, "abc123def456", "\"etag-value\"");

        assertEquals("abc123def456",
                Files.readString(Path.of(cacheFile + ".shasum")).strip());
        assertEquals("\"etag-value\"",
                Files.readString(Path.of(cacheFile + ".etag")).strip());
    }

    @Test
    void writeNpmSidecarFilesSkipsEtagWhenNull(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        MitmProxy.writeNpmSidecarFiles(cacheFile, "deadbeef", null);

        assertTrue(Files.isRegularFile(Path.of(cacheFile + ".shasum")));
        assertFalse(Files.exists(Path.of(cacheFile + ".etag")));
    }

    @Test
    void writeNpmSidecarFilesOverwritesExisting(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        MitmProxy.writeNpmSidecarFiles(cacheFile, "old-shasum", "\"old-etag\"");
        MitmProxy.writeNpmSidecarFiles(cacheFile, "new-shasum", "\"new-etag\"");

        assertEquals("new-shasum",
                Files.readString(Path.of(cacheFile + ".shasum")).strip());
        assertEquals("\"new-etag\"",
                Files.readString(Path.of(cacheFile + ".etag")).strip());
    }

    @Test
    void writeNpmSidecarFilesCreatesScopedPackageDirs(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("@scope/name/-/name-1.0.0.tgz");
        MitmProxy.writeNpmSidecarFiles(cacheFile, "abc", "\"etag\"");

        assertTrue(Files.isRegularFile(Path.of(cacheFile + ".shasum")));
    }

    // --- storePackageEtag ---

    @Test
    void storeAndReadPackageEtag(@TempDir Path tmp) throws Exception {
        var origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            MitmProxy.storePackageEtag("@openai/codex", "\"W/abc123\"");

            var etagFile = tmp.resolve(".cache/incus-spawn/npm/@openai/codex/.etag");
            assertTrue(Files.isRegularFile(etagFile));
            assertEquals("\"W/abc123\"", Files.readString(etagFile).strip());
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void storePackageEtagOverwritesPrevious(@TempDir Path tmp) throws Exception {
        var origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            MitmProxy.storePackageEtag("express", "\"first\"");
            MitmProxy.storePackageEtag("express", "\"second\"");

            var etagFile = tmp.resolve(".cache/incus-spawn/npm/express/.etag");
            assertEquals("\"second\"", Files.readString(etagFile).strip());
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    // --- checkNpmTarballCache: ETag fast path ---

    @Test
    void cacheHitEtagMatchServesDirectly(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"abc\"");
        Files.writeString(Path.of(cacheFile + ".shasum"), "someshasum");

        var shasumCalled = new AtomicBoolean(false);
        var result = MitmProxy.checkNpmTarballCache(cacheFile, "\"abc\"", "ref",
                () -> { shasumCalled.set(true); return "should-not-call"; });

        assertNotNull(result);
        assertTrue(result.cacheHit());
        assertEquals(Files.size(cacheFile), result.size());
        assertNull(result.digest());
        assertFalse(shasumCalled.get(), "shasum supplier should not be called when ETag matches");
    }

    @Test
    void cacheHitEmptyEtagFallsToShasumCheck(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "");
        Files.writeString(Path.of(cacheFile + ".shasum"), "abc123");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, "", "ref",
                () -> "abc123");

        assertNotNull(result);
        assertTrue(result.cacheHit());
    }

    // --- checkNpmTarballCache: ETag mismatch, shasum unchanged ---

    @Test
    void cacheHitEtagChangedShasumSameUpdatesEtag(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");
        Files.writeString(Path.of(cacheFile + ".shasum"), "abc123");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, "\"new\"", "ref",
                () -> "abc123");

        assertNotNull(result);
        assertTrue(result.cacheHit());
        assertEquals("\"new\"", Files.readString(Path.of(cacheFile + ".etag")).strip(),
                "tarball ETag should be updated to current package ETag");
    }

    @Test
    void cacheHitEtagChangedShasumSamePackageEtagNullSkipsEtagUpdate(@TempDir Path tmp)
            throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");
        Files.writeString(Path.of(cacheFile + ".shasum"), "abc123");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, null, "ref",
                () -> "abc123");

        assertNotNull(result);
        assertTrue(result.cacheHit());
        assertEquals("\"old\"", Files.readString(Path.of(cacheFile + ".etag")).strip(),
                "tarball ETag should NOT be updated when package ETag is null");
    }

    // --- checkNpmTarballCache: ETag mismatch, shasum changed (eviction) ---

    @Test
    void cacheHitShasumChangedEvictsAndRefetches(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "old-tarball");
        Files.writeString(Path.of(cacheFile + ".shasum"), "old-shasum");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old-etag\"");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, "\"new-etag\"", "ref",
                () -> "new-shasum");

        assertNotNull(result);
        assertFalse(result.cacheHit());
        assertEquals("sha1:new-shasum", result.digest());
        assertFalse(Files.exists(cacheFile), "stale tarball should be deleted");
        assertEquals("new-shasum",
                Files.readString(Path.of(cacheFile + ".shasum")).strip(),
                "sidecar should be written with new shasum");
    }

    @Test
    void cacheHitNoStoredShasumTreatsAsMismatch(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");
        // no .shasum file

        var result = MitmProxy.checkNpmTarballCache(cacheFile, "\"new\"", "ref",
                () -> "fresh-shasum");

        assertNotNull(result);
        assertFalse(result.cacheHit(), "missing stored shasum should trigger eviction");
        assertEquals("sha1:fresh-shasum", result.digest());
    }

    // --- checkNpmTarballCache: shasum fetch fails (graceful degradation) ---

    @Test
    void cacheHitShasumFetchFailsServesFromCache(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, "\"new\"", "ref",
                () -> null);

        assertNotNull(result);
        assertTrue(result.cacheHit(),
                "should serve cached tarball when registry is unreachable");
    }

    // --- checkNpmTarballCache: no package ETag ---

    @Test
    void cacheHitNoPackageEtagFallsToShasumVerification(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".shasum"), "abc123");
        // no .etag files at all

        var result = MitmProxy.checkNpmTarballCache(cacheFile, null, "ref",
                () -> "abc123");

        assertNotNull(result);
        assertTrue(result.cacheHit());
    }

    @Test
    void cacheHitBothEtagsNullDoesNotNpe(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".shasum"), "abc123");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, null, "ref",
                () -> "abc123");

        assertNotNull(result);
        assertTrue(result.cacheHit());
    }

    // --- checkNpmTarballCache: cache miss ---

    @Test
    void cacheMissShasumAvailableReturnsFetchResult(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, "\"etag\"", "ref",
                () -> "abc123");

        assertNotNull(result);
        assertFalse(result.cacheHit());
        assertEquals("sha1:abc123", result.digest());
        assertEquals("abc123",
                Files.readString(Path.of(cacheFile + ".shasum")).strip());
        assertEquals("\"etag\"",
                Files.readString(Path.of(cacheFile + ".etag")).strip());
    }

    @Test
    void cacheMissShasumUnavailableReturnsNull(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, "\"etag\"", "ref",
                () -> null);

        assertNull(result, "should return null to trigger relay when shasum unavailable");
    }

    @Test
    void cacheMissNoPackageEtagWritesShasumButNotEtag(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");

        var result = MitmProxy.checkNpmTarballCache(cacheFile, null, "ref",
                () -> "abc123");

        assertNotNull(result);
        assertFalse(result.cacheHit());
        assertTrue(Files.isRegularFile(Path.of(cacheFile + ".shasum")));
        assertFalse(Files.exists(Path.of(cacheFile + ".etag")),
                "should not write ETag sidecar when package ETag is null");
    }
}
