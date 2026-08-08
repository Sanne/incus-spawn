package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DownloadCacheTest {

    @Test
    void cacheFilenamePreservesOriginalName() {
        var filename = DownloadCache.cacheFilename(
                "https://example.com/releases/tool-1.2.3-bin.tar.gz");
        assertTrue(filename.endsWith("-tool-1.2.3-bin.tar.gz"));
        // Should have a hex hash prefix
        assertTrue(filename.matches("^[0-9a-f]+-tool-1\\.2\\.3-bin\\.tar\\.gz$"));
    }

    @Test
    void cacheFilenameDifferentUrlsSameBasename() {
        var a = DownloadCache.cacheFilename("https://a.com/tool.tar.gz");
        var b = DownloadCache.cacheFilename("https://b.com/tool.tar.gz");
        assertNotEquals(a, b, "Different URLs with same basename should produce different cache filenames");
    }

    @Test
    void computeSha256(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello\n");
        var sha256 = DownloadCache.computeSha256(file);
        // sha256 of "hello\n"
        assertEquals("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03", sha256);
    }

    @Test
    void cacheHitSkipsDownload(@TempDir Path cacheDir) throws IOException {
        var cache = new DownloadCache(cacheDir);

        // Pre-populate the cache with a fake file
        var content = "cached content";
        var fakeFile = cacheDir.resolve(
                DownloadCache.cacheFilename("https://example.com/tool.tar.gz"));
        Files.writeString(fakeFile, content);
        var sha256 = DownloadCache.computeSha256(fakeFile);

        // Should return the cached file without downloading
        var result = cache.download("https://example.com/tool.tar.gz", sha256);
        assertEquals(fakeFile, result);
        assertEquals(content, Files.readString(result));
    }

    @Test
    void sha256MismatchInCacheRedownloads(@TempDir Path cacheDir) throws IOException {
        var cache = new DownloadCache(cacheDir);

        // Pre-populate with wrong content
        var fakeFile = cacheDir.resolve(
                DownloadCache.cacheFilename("https://example.com/tool.tar.gz"));
        Files.writeString(fakeFile, "wrong content");

        // Should attempt to re-download (and fail since there's no real server)
        assertThrows(Exception.class,
                () -> cache.download("https://example.com/tool.tar.gz", "0000000000000000"));
    }
}
