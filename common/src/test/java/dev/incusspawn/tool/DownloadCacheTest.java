package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void refusesFileUrls(@TempDir Path tempDir) throws IOException {
        var secret = Files.writeString(tempDir.resolve("id_rsa"), "secret");
        var cache = new DownloadCache(tempDir.resolve("cache"));
        var e = assertThrows(IOException.class, () -> cache.download(secret.toUri().toString(), null));
        assertTrue(e.getMessage().contains("only http:// and https://"), e.getMessage());
    }

    @Test
    void refusesOtherSchemes(@TempDir Path cacheDir) {
        var cache = new DownloadCache(cacheDir);
        for (var url : List.of("ftp://example.com/f", "jar:file:///tmp/x.jar!/a", "FILE:///etc/passwd")) {
            assertThrows(IOException.class, () -> cache.download(url, null), url);
        }
    }

    @Test
    void baseImagesMayUseLocalFiles(@TempDir Path tempDir) throws IOException {
        var image = Files.writeString(tempDir.resolve("image.tar.xz"), "image");
        var cache = new DownloadCache(tempDir.resolve("cache"));
        var listener = new RecordingListener();
        var result = cache.downloadAllowingLocalFile(image.toUri().toString(), DownloadCache.computeSha256(image),
                listener);
        assertEquals("image", Files.readString(result));
        // A fresh copy is reported like a download, so it is not announced as a cache hit.
        assertEquals(List.of("received", "verifying"), listener.events);
        assertEquals(5, listener.lastBytes);
    }

    @Test
    void localFileAllowanceDoesNotLiftTheLoopbackVeto(@TempDir Path cacheDir) {
        var cache = new DownloadCache(cacheDir);
        assertThrows(IOException.class, () -> cache.downloadAllowingLocalFile("http://127.0.0.1:1/x", null));
    }

    @Test
    void hostLocalAddressesAreRecognized() {
        for (var url : List.of("http://localhost/x", "http://127.0.0.1/x", "http://127.8.9.10:8080/x",
                "http://[::1]/x", "http://[::ffff:127.0.0.1]/x", "http://0.0.0.0/x", "http://[::]/x",
                "http://169.254.169.254/latest/meta-data/", "http://[fe80::1]/x")) {
            assertTrue(DownloadCache.resolvesToHostLocal(URI.create(url)), url);
        }
        for (var url : List.of("https://93.184.215.14/x", "https://10.0.0.1/x", "https://[2606:4700::1111]/x")) {
            assertFalse(DownloadCache.resolvesToHostLocal(URI.create(url)), url);
        }
    }

    @Test
    void refusesLoopbackEvenWhenCached(@TempDir Path cacheDir) throws IOException {
        var url = "http://127.0.0.1/tool.tar.gz";
        var cached = Files.writeString(cacheDir.resolve(DownloadCache.cacheFilename(url)), "x");
        var cache = new DownloadCache(cacheDir);
        var e = assertThrows(IOException.class, () -> cache.download(url, DownloadCache.computeSha256(cached)));
        assertTrue(e.getMessage().contains("loopback or link-local"), e.getMessage());
    }

    @Test
    void redirectsAreFollowedAndEachHopIsChecked(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A redirect body longer than the file it points to: must not survive into the download.
        var longBody = "x".repeat(1000).getBytes(StandardCharsets.UTF_8);
        server.createContext("/to-file", ex -> {
            ex.getResponseHeaders().add("Location", "/file");
            ex.sendResponseHeaders(302, longBody.length);
            ex.getResponseBody().write(longBody);
            ex.close();
        });
        server.createContext("/to-secret", ex -> {
            ex.getResponseHeaders().add("Location", "/secret");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(301, -1);
            ex.close();
        });
        server.createContext("/file", ex -> {
            var body = "content".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            // The test server is on loopback, so stand in "/secret" for a host-local address.
            var cache = new DownloadCache(cacheDir, uri -> uri.getPath().startsWith("/secret"));

            assertEquals("content", Files.readString(cache.download(base + "/to-file", null)));

            var e = assertThrows(IOException.class, () -> cache.download(base + "/to-secret", null));
            assertTrue(e.getMessage().contains("redirected from " + base + "/to-secret"), e.getMessage());

            e = assertThrows(IOException.class, () -> cache.download(base + "/loop", null));
            assertTrue(e.getMessage().contains("redirects"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transientFailuresAreRetriedFromTheOriginalUrl(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirects = new AtomicInteger();
        var fileRequests = new AtomicInteger();
        server.createContext("/release", ex -> {
            redirects.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/asset");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // Fails transiently, as GitHub release downloads have been seen to, then serves the file.
        server.createContext("/asset", ex -> {
            if (fileRequests.incrementAndGet() <= 2) {
                ex.sendResponseHeaders(500, -1);
            } else {
                var body = "content".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var cache = new DownloadCache(cacheDir, uri -> false, List.of(Duration.ZERO, Duration.ZERO));
            assertEquals("content", Files.readString(cache.download(base + "/release", null)));
            assertEquals(3, fileRequests.get());
            assertEquals(3, redirects.get(), "each attempt restarts at the original URL");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void persistentServerErrorsGiveUpNamingTheFailingHost(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/broken", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/broken";
            var cache = new DownloadCache(cacheDir, uri -> false, List.of(Duration.ZERO, Duration.ZERO));
            var e = assertThrows(IOException.class, () -> cache.download(url, null));
            assertEquals("Download failed: HTTP 503 from 127.0.0.1 for " + url + " (after 3 attempts)", e.getMessage());
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientErrorsAreNotRetried(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/missing", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
            var cache = new DownloadCache(cacheDir, uri -> false, List.of(Duration.ZERO, Duration.ZERO));
            var e = assertThrows(IOException.class, () -> cache.download(url, null));
            assertEquals("Download failed: HTTP 404 from 127.0.0.1 for " + url, e.getMessage());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusedConnectionsAreRetried(@TempDir Path cacheDir) throws IOException {
        int port;
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var url = "http://127.0.0.1:" + port + "/gone";
        var cache = new DownloadCache(cacheDir, uri -> false, List.of(Duration.ZERO));
        var e = assertThrows(IOException.class, () -> cache.download(url, null));
        assertTrue(e.getMessage().startsWith("Download failed: "), e.getMessage());
        assertTrue(e.getMessage().endsWith(" from 127.0.0.1 for " + url + " (after 2 attempts)"), e.getMessage());
    }

    /** Records what a download reported, in order. */
    private static final class RecordingListener implements DownloadCache.Listener {
        final List<String> events = new ArrayList<>();
        long lastBytes = -1;
        long lastTotal;

        @Override
        public void verifyingCached() {
            events.add("verifyingCached");
        }

        @Override
        public void received(long bytes, long total) {
            if (events.isEmpty() || !events.get(events.size() - 1).equals("received")) events.add("received");
            lastBytes = bytes;
            lastTotal = total;
        }

        @Override
        public void verifying() {
            events.add("verifying");
        }
    }

    @Test
    void progressReportsBytesAgainstContentLength(@TempDir Path cacheDir) throws IOException {
        var body = "y".repeat(200_000).getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sized", ex -> {
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0); // no Content-Length
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var cache = new DownloadCache(cacheDir, uri -> false);
            var sha256 = DownloadCache.computeSha256(Files.write(cacheDir.resolveSibling("expected"), body));

            var sized = new RecordingListener();
            cache.downloadAllowingLocalFile(base + "/sized", sha256, sized);
            assertEquals(List.of("received", "verifying"), sized.events);
            assertEquals(body.length, sized.lastBytes);
            assertEquals(body.length, sized.lastTotal);

            var chunked = new RecordingListener();
            cache.downloadAllowingLocalFile(base + "/chunked", null, chunked);
            assertEquals(List.of("received"), chunked.events, "no checksum, so nothing to verify");
            assertEquals(body.length, chunked.lastBytes);
            assertEquals(-1, chunked.lastTotal);

            // The checksum matches the cached copy now: verified, never fetched.
            var hit = new RecordingListener();
            cache.downloadAllowingLocalFile(base + "/sized", sha256, hit);
            assertEquals(List.of("verifyingCached"), hit.events);
        } finally {
            server.stop(0);
        }
    }
}
