package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSetupTest {

    @Test
    void detectPlatformReturnsLinuxX64OnAmd64() {
        assertEquals("linux-x64", ClaudeSetup.detectPlatform("amd64"));
        assertEquals("linux-x64", ClaudeSetup.detectPlatform("x86_64"));
    }

    @Test
    void detectPlatformReturnsLinuxArm64() {
        assertEquals("linux-arm64", ClaudeSetup.detectPlatform("aarch64"));
        assertEquals("linux-arm64", ClaudeSetup.detectPlatform("arm64"));
    }

    @Test
    void detectPlatformThrowsOnUnsupportedArch() {
        assertThrows(RuntimeException.class, () -> ClaudeSetup.detectPlatform("sparc"));
    }

    @Test
    void extractChecksumParsesValidManifest() throws IOException {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                    }
                  }
                }
                """;
        assertEquals(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                ClaudeSetup.extractChecksum(manifest, "linux-x64"));
    }

    @Test
    void extractChecksumAcceptsUppercaseHex() throws IOException {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
                    }
                  }
                }
                """;
        assertEquals(
                "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
                ClaudeSetup.extractChecksum(manifest, "linux-x64"));
    }

    @Test
    void extractChecksumThrowsOnMissingPlatform() {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                    }
                  }
                }
                """;
        var e = assertThrows(IOException.class,
                () -> ClaudeSetup.extractChecksum(manifest, "linux-arm64"));
        assertTrue(e.getMessage().contains("not found"));
        assertTrue(e.getMessage().contains("linux-arm64"));
    }

    @Test
    void extractChecksumThrowsOnInvalidChecksum() {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "not-a-valid-sha256"
                    }
                  }
                }
                """;
        var e = assertThrows(IOException.class,
                () -> ClaudeSetup.extractChecksum(manifest, "linux-x64"));
        assertTrue(e.getMessage().contains("Invalid checksum"));
        assertTrue(e.getMessage().contains("not-a-valid-sha256"));
    }

    @Test
    void extractChecksumThrowsOnEmptyManifest() {
        var manifest = "{}";
        assertThrows(IOException.class,
                () -> ClaudeSetup.extractChecksum(manifest, "linux-x64"));
    }

    @Test
    void downloadCacheIsInjectable(@TempDir Path tempDir) {
        var cache = new DownloadCache(tempDir);
        var setup = new ClaudeSetup(cache);
        assertEquals("claude", setup.name());
    }
}
