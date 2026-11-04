package dev.incusspawn.baseimage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests how a release's checksum manifest is read: by the exact file names a template downloads,
 * so a release carrying a second VM image (#1255) can never pin one image's digest onto another.
 */
class BaseImageReleasesTest {

    private static final String TAG = "fedora-44-20261020";
    private static final String URL = "https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/";
    private static final String CONTAINER = URL + "fedora-44-{arch}.tar.xz";
    private static final String STOCK_VM = URL + "fedora-44-{arch}-vm.tar.xz";
    private static final String DIRECT_VM = URL + "fedora-44-{arch}-vm-direct.tar.xz";

    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    /** SHA256SUMS: what every isx release has read, unchanged by #1255. */
    private static final String SUMS = sha('1') + "  fedora-44-x86_64.tar.xz\n"
            + sha('2') + "  fedora-44-aarch64.tar.xz\n"
            + sha('3') + "  fedora-44-x86_64-vm.tar.xz\n"
            + sha('4') + "  fedora-44-aarch64-vm.tar.xz\n";

    /** SHA256SUMS.v2: the same four, and the VM image built from scratch. */
    private static final String SUMS_V2 = SUMS
            + sha('5') + "  fedora-44-x86_64-vm-direct.tar.xz\n"
            + sha('6') + "  fedora-44-aarch64-vm-direct.tar.xz\n";

    @Test
    void aTemplateGetsTheChecksumsOfExactlyTheFilesItDownloads() {
        var direct = BaseImageReleases.checksumsFor(BaseImageReleases.parseManifest(SUMS_V2), TAG, CONTAINER, DIRECT_VM);
        assertEquals(Map.of("x86_64", sha('1'), "aarch64", sha('2')), direct.container());
        assertEquals(Map.of("x86_64", sha('5'), "aarch64", sha('6')), direct.vm());

        var stock = BaseImageReleases.checksumsFor(BaseImageReleases.parseManifest(SUMS_V2), TAG, CONTAINER, STOCK_VM);
        assertEquals(Map.of("x86_64", sha('3'), "aarch64", sha('4')), stock.vm(),
                "-vm-direct must not stand in for -vm, though its name contains it");
    }

    @Test
    void aReleaseWithoutTheVmImageGivesNoVmChecksum() {
        var sums = BaseImageReleases.checksumsFor(BaseImageReleases.parseManifest(SUMS), TAG, CONTAINER, DIRECT_VM);
        assertEquals(Map.of("x86_64", sha('1'), "aarch64", sha('2')), sums.container());
        assertTrue(sums.vm().isEmpty(), "the stock image's checksum must not be taken for the direct one");
    }

    @Test
    void aTemplateWithoutAVmImageGetsNoVmChecksum() {
        var sums = BaseImageReleases.checksumsFor(BaseImageReleases.parseManifest(SUMS_V2), TAG, CONTAINER, null);
        assertTrue(sums.vm().isEmpty());
    }

    @Test
    void anUnreadableManifestIsRejectedWhole() {
        assertThrows(IllegalArgumentException.class,
                () -> BaseImageReleases.parseManifest(SUMS + "not-a-checksum  fedora-44-x86_64-vm-direct.tar.xz\n"));
        assertThrows(IllegalArgumentException.class,
                () -> BaseImageReleases.parseManifest(SUMS + sha('9') + "  fedora-44-x86_64.tar.xz\n"),
                "a file listed twice has no one checksum to trust");
        assertThrows(IllegalArgumentException.class, () -> BaseImageReleases.parseManifest(sha('1') + "\n"));
    }

    @Test
    void blankLinesAndBinaryMarkersAreTolerated() {
        var sums = BaseImageReleases.parseManifest("\n" + sha('A') + " *fedora-44-x86_64.tar.xz\n\n");
        assertEquals(Map.of("fedora-44-x86_64.tar.xz", sha('a')), sums);
    }

    @Test
    void assetNameFillsInTagAndArch() {
        assertEquals("fedora-44-aarch64-vm-direct.tar.xz", BaseImageReleases.assetName(DIRECT_VM, TAG, "aarch64"));
        assertEquals("img-fedora-44-20261020-x86_64.tar.xz",
                BaseImageReleases.assetName("file:///images/img-{tag}-{arch}.tar.xz", TAG, "x86_64"));
    }

    /** A v2 manifest whose request fails is read around through SHA256SUMS, which still lists the older assets. */
    @Test
    void aV2ThatCannotBeFetchedFallsBackToSha256Sums() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/SHA256SUMS", exchange -> {
            var body = SUMS.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        // A port nothing listens on: the v2 request fails outright, not with a status.
        int closedPort;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var release = new BaseImageReleases.Release(TAG, "2026-10-20", base + "/SHA256SUMS",
                    "http://127.0.0.1:" + closedPort + "/SHA256SUMS.v2");
            var sums = BaseImageReleases.fromImageUrl(CONTAINER).fetchChecksums(release, CONTAINER, STOCK_VM);
            assertEquals(Map.of("x86_64", sha('1'), "aarch64", sha('2')), sums.container());
            assertEquals(Map.of("x86_64", sha('3'), "aarch64", sha('4')), sums.vm());
        } finally {
            server.stop(0);
        }
    }

    /** A malformed asset URL from the release list is a failed request, not an exception that escapes the fallback. */
    @Test
    void aMalformedManifestUrlIsAFailedRequest() throws Exception {
        var release = new BaseImageReleases.Release(TAG, "2026-10-20", null, "https://example.com/bad url");
        assertNull(BaseImageReleases.fromImageUrl(CONTAINER).fetchChecksums(release, CONTAINER, STOCK_VM));
    }

    @Test
    void fromImageUrlOnlyMatchesGitHubReleaseUrls() {
        assertNotNull(BaseImageReleases.fromImageUrl(
                "https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-44-{arch}.tar.xz"));
        assertNull(BaseImageReleases.fromImageUrl(
                "https://example.com/images/{tag}/fedora-44-{arch}.tar.xz"),
                "a non-github host is not a trackable base image");
        assertNull(BaseImageReleases.fromImageUrl(
                "https://github.com/Sanne/incus-spawn-images/raw/main/foo.tar.xz"),
                "a github URL that is not a /releases/ URL is not trackable");
        assertNull(BaseImageReleases.fromImageUrl(null));
    }

    /**
     * GitHub's bytes are parsed as they came, so invalid UTF-8 is refused rather than read as U+FFFD.
     * As an IOException, which BuildCommand and UpdateBaseCommand answer by keeping the built-in base image.
     */
    @Test
    void aReleaseListWithInvalidUtf8IsRefusedSayingWhere() throws Exception {
        var json = "[{\"tag_name\":\"fedora-44-\u00ff\",\"assets\":[]}]"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        var e = assertThrows(java.io.IOException.class, () -> BaseImageReleases.parseReleases(json));
        assertEquals("GitHub's release list is not valid JSON: Invalid UTF-8 at line 1, column 25", e.getMessage());
        var notJson = assertThrows(java.io.IOException.class,
                () -> BaseImageReleases.parseReleases("<html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(notJson.getMessage().startsWith("GitHub's release list is not valid JSON: "), notJson.getMessage());
        var clean = "[{\"tag_name\":\"fedora-44-1\",\"published_at\":\"2026-10-20T00:00:00Z\",\"assets\":[]}]";
        assertEquals(java.util.List.of(new BaseImageReleases.Release("fedora-44-1", "2026-10-20", null, null)),
                BaseImageReleases.parseReleases(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Another line's release moves the alias and the file names, never the path before them (#1967). */
    @Test
    void namesMoveToTheReleasesLine() {
        var current = new BaseImageReleases.Names("fedora-45-base",
                "https://github.com/o/fedora-45-images/releases/download/{tag}/fedora-45-{arch}.tar.xz",
                "https://github.com/o/fedora-45-images/releases/download/{tag}/fedora-45-{arch}-vm.tar.xz");
        assertEquals(new BaseImageReleases.Names("fedora-44-base",
                        "https://github.com/o/fedora-45-images/releases/download/{tag}/fedora-44-{arch}.tar.xz",
                        "https://github.com/o/fedora-45-images/releases/download/{tag}/fedora-44-{arch}-vm.tar.xz"),
                BaseImageReleases.namesFor(current, "fedora-45-20261201", "fedora-44-20261101"));
        assertSame(current, BaseImageReleases.namesFor(current, "fedora-45-20261201", "fedora-45-20261215"));
        assertSame(current, BaseImageReleases.namesFor(current, null, "fedora-44-20261101"));
        var noVm = new BaseImageReleases.Names(null, current.imageUrl(), null);
        assertEquals(new BaseImageReleases.Names(null, "https://github.com/o/fedora-45-images/releases/download/{tag}/fedora-44-{arch}.tar.xz", null),
                BaseImageReleases.namesFor(noVm, "fedora-45-20261201", "fedora-44-20261101"));
    }

    /** A name that does not hold the line once is not guessed at. */
    @Test
    void aNameWithoutTheLineOnceIsNotMoved() {
        var twice = new BaseImageReleases.Names("fedora-44-base", "https://x/{tag}/fedora-44-fedora-44-{arch}.tar.xz", null);
        assertThrows(IllegalArgumentException.class,
                () -> BaseImageReleases.namesFor(twice, "fedora-44-20261101", "fedora-45-20261201"));
        var none = new BaseImageReleases.Names("own", "https://x/{tag}/fedora-44-{arch}.tar.xz", null);
        assertThrows(IllegalArgumentException.class,
                () -> BaseImageReleases.namesFor(none, "fedora-44-20261101", "fedora-45-20261201"));
    }
}
