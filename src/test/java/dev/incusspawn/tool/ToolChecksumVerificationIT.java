package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test that verifies SHA256 checksums in built-in tool definitions
 * match the actual downloads. This catches mistakes like corrupted downloads
 * or incorrectly copied checksums.
 * <p>
 * Disabled by default (requires network access). Enable with:
 * {@code mvn verify -DskipITs=false -Dverify.tool.checksums=true}
 */
@EnabledIfSystemProperty(named = "verify.tool.checksums", matches = "true")
class ToolChecksumVerificationIT {

    private static final List<String> BUILTIN_TOOLS = List.of(
            "tools/maven-3.yaml",
            "tools/idea-backend.yaml",
            "tools/starship.yaml"
    );

    @Test
    void verifyAllBuiltInToolChecksums() throws Exception {
        var failures = new ArrayList<String>();

        for (var resourcePath : BUILTIN_TOOLS) {
            var tool = loadBuiltinTool(resourcePath);
            if (tool == null) {
                failures.add("Failed to load built-in tool: " + resourcePath);
                continue;
            }

            for (var download : tool.getDownloads()) {
                if (download.getSha256() == null) {
                    continue; // Skip downloads without checksums
                }

                try {
                    var actualHash = downloadAndHash(download.getUrl());
                    var expectedHash = download.getSha256().toLowerCase();

                    if (!actualHash.equals(expectedHash)) {
                        failures.add(String.format(
                                "Tool '%s': SHA256 mismatch for %s\n  Expected: %s\n  Actual:   %s",
                                tool.getName(), download.getUrl(), expectedHash, actualHash));
                    } else {
                        System.out.printf("✓ %s: %s%n", tool.getName(), download.getUrl());
                    }
                } catch (Exception e) {
                    failures.add(String.format(
                            "Tool '%s': Failed to verify %s: %s",
                            tool.getName(), download.getUrl(), e.getMessage()));
                }
            }
        }

        if (!failures.isEmpty()) {
            fail("Checksum verification failures:\n" + String.join("\n", failures));
        }
    }

    private ToolDef loadBuiltinTool(String resourcePath) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return ToolDef.loadFromStream(is);
        }
    }

    private String downloadAndHash(String url) throws IOException, NoSuchAlgorithmException {
        var tempFile = Files.createTempFile("tool-checksum-", ".tmp");
        try {
            // Download to temp file
            try (var in = URI.create(url).toURL().openStream()) {
                Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Compute SHA256
            return computeSha256(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private String computeSha256(Path file) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
