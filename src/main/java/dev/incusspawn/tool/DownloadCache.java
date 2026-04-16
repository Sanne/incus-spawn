package dev.incusspawn.tool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Host-side download manager with a persistent file cache.
 * <p>
 * Files are cached in {@code ~/.cache/incus-spawn/downloads/} keyed by
 * a hash of the URL. When a sha256 checksum is provided, cached files
 * are validated before reuse and downloads are verified before caching.
 */
public class DownloadCache {

    private static final Path CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".cache", "incus-spawn", "downloads");

    private final Path cacheDir;

    public DownloadCache() {
        this(CACHE_DIR);
    }

    /** Constructor for testing with a custom cache directory. */
    DownloadCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Download a URL and return the path to the cached file.
     * If sha256 is provided and a cached file matches, the download is skipped.
     * If sha256 is null, the file is always re-downloaded.
     */
    public Path download(String url, String sha256) throws IOException {
        Files.createDirectories(cacheDir);

        var filename = cacheFilename(url);
        var cached = cacheDir.resolve(filename);

        // Cache hit: file exists and sha256 matches
        if (sha256 != null && Files.exists(cached)) {
            if (sha256.equalsIgnoreCase(computeSha256(cached))) {
                return cached;
            }
        }

        System.out.println("  Downloading " + url + "...");
        var tmp = Files.createTempFile(cacheDir, "download-", ".tmp");
        try {
            var client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));

            if (response.statusCode() != 200) {
                throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + url);
            }

            if (sha256 != null) {
                var actual = computeSha256(tmp);
                if (!sha256.equalsIgnoreCase(actual)) {
                    throw new IOException("SHA-256 mismatch for " + url
                            + ": expected " + sha256 + " but got " + actual);
                }
            }

            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Generate a stable cache filename from the URL.
     * Uses the last path segment (e.g. "apache-maven-3.9.14-bin.tar.gz")
     * prefixed with a short hash of the full URL for uniqueness.
     */
    static String cacheFilename(String url) {
        var lastSlash = url.lastIndexOf('/');
        var basename = lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
        // Short hash prefix to avoid collisions between different URLs with same filename
        var hash = Integer.toHexString(url.hashCode() & 0x7fffffff);
        return hash + "-" + basename;
    }

    static String computeSha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var is = Files.newInputStream(file)) {
                var buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
