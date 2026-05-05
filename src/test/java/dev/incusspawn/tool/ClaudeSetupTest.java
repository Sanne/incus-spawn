package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSetupTest {

    @Test
    void detectPlatformReturnsLinuxX64OnAmd64() {
        var original = System.getProperty("os.arch");
        try {
            System.setProperty("os.arch", "amd64");
            assertEquals("linux-x64", ClaudeSetup.detectPlatform());

            System.setProperty("os.arch", "x86_64");
            assertEquals("linux-x64", ClaudeSetup.detectPlatform());
        } finally {
            System.setProperty("os.arch", original);
        }
    }

    @Test
    void detectPlatformReturnsLinuxArm64() {
        var original = System.getProperty("os.arch");
        try {
            System.setProperty("os.arch", "aarch64");
            assertEquals("linux-arm64", ClaudeSetup.detectPlatform());
        } finally {
            System.setProperty("os.arch", original);
        }
    }

    @Test
    void detectPlatformThrowsOnUnsupportedArch() {
        var original = System.getProperty("os.arch");
        try {
            System.setProperty("os.arch", "sparc");
            assertThrows(RuntimeException.class, ClaudeSetup::detectPlatform);
        } finally {
            System.setProperty("os.arch", original);
        }
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
