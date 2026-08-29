package dev.incusspawn.baseimage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code SHA256SUMS} parser, in particular that it keeps container
 * ({@code .tar.xz}) and VM ({@code -vm.tar.xz}) checksums separate per arch —
 * conflating them silently pins the wrong digest onto the wrong image.
 */
class BaseImageReleasesTest {

    private static final String SUMS = """
            490ece8b08b886f867bc6a956dd748dc39e5ce6e24b608a33fa3d7db89a1b063  fedora-44-x86_64.tar.xz
            619dbd62a1e2f4f3c841e42880a009eaf7ac09e02ee6bf9632eb192b73da51e1  fedora-44-aarch64.tar.xz
            77ada3a6b79026cf00a18b7dc2a15f38da8f871271b344cecfd384b7b0bfb028  fedora-44-x86_64-vm.tar.xz
            8fcc43261fd591399e4f3205515592fdbff43de357c23cd963ca20739c65306d  fedora-44-aarch64-vm.tar.xz
            """;

    @Test
    void separatesContainerAndVmChecksumsPerArch() {
        var sums = BaseImageReleases.parseSha256Sums(SUMS);

        assertEquals("490ece8b08b886f867bc6a956dd748dc39e5ce6e24b608a33fa3d7db89a1b063",
                sums.container().get("x86_64"));
        assertEquals("619dbd62a1e2f4f3c841e42880a009eaf7ac09e02ee6bf9632eb192b73da51e1",
                sums.container().get("aarch64"));
        assertEquals("77ada3a6b79026cf00a18b7dc2a15f38da8f871271b344cecfd384b7b0bfb028",
                sums.vm().get("x86_64"));
        assertEquals("8fcc43261fd591399e4f3205515592fdbff43de357c23cd963ca20739c65306d",
                sums.vm().get("aarch64"));
    }

    @Test
    void containerDigestsAreNotOverwrittenByVmLines() {
        var sums = BaseImageReleases.parseSha256Sums(SUMS);
        assertNotEquals(sums.container().get("x86_64"), sums.vm().get("x86_64"));
        assertNotEquals(sums.container().get("aarch64"), sums.vm().get("aarch64"));
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

    @Test
    void toleratesContainerOnlyReleaseAndBlankLines() {
        var body = """
                aaaa  fedora-44-x86_64.tar.xz

                bbbb  fedora-44-aarch64.tar.xz
                cccc  SHA256SUMS.sig
                """;
        var sums = BaseImageReleases.parseSha256Sums(body);
        assertEquals("aaaa", sums.container().get("x86_64"));
        assertEquals("bbbb", sums.container().get("aarch64"));
        assertTrue(sums.vm().isEmpty(), "no VM assets should yield an empty VM map");
    }
}
