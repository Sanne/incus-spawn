package dev.incusspawn.tool;

import com.sun.net.httpserver.HttpServer;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** #2411: downloads fetched ahead of a build, which say nothing and leave the build a cache hit. */
class BackgroundDownloadsTest {

    private static final byte[] BODY = "base image ".repeat(100).getBytes(StandardCharsets.UTF_8);

    @Test
    void aFileFetchedAheadIsNotDownloadedAgainByTheBuild(@TempDir Path cacheDir) throws Exception {
        var requests = new AtomicInteger();
        var server = serve(requests, null);
        try {
            var url = url(server, "/image.tar.xz");
            var sha = sha256(BODY);
            var events = RecordingOutput.during(() -> {
                try (var ahead = BackgroundDownloads.start(cache(cacheDir), () -> List.of(new DownloadCache.Pinned(url, sha)))) {
                    assertTrue(ahead.await(Duration.ofSeconds(30)));
                    assertEquals(List.of(url), ahead.fetched());
                }
            }).events();
            assertEquals(List.of(), events, "a download ahead says nothing");
            assertEquals(1, requests.get());

            var built = cache(cacheDir).downloadAllowingLocalFile(url, sha, DownloadCache.Listener.NONE);
            assertArrayEquals(BODY, Files.readAllBytes(built));
            assertEquals(1, requests.get(), "the build's download is a cache hit");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFailureIsNotReportedAheadAndTheBuildFetchesAgainAndSaysWhy(@TempDir Path cacheDir) throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            var url = url(server, "/missing.tar.xz");
            var sha = sha256(BODY);
            var events = RecordingOutput.during(() -> {
                try (var ahead = BackgroundDownloads.start(cache(cacheDir), () -> List.of(new DownloadCache.Pinned(url, sha)))) {
                    assertTrue(ahead.await(Duration.ofSeconds(30)));
                    assertEquals(List.of(url), ahead.failed());
                }
            }).events();
            assertEquals(List.of(), events, "the failure waits for the build");

            var failure = assertThrows(IOException.class, () -> cache(cacheDir).download(url, sha));
            assertTrue(failure.getMessage().contains("404"), failure.getMessage());
            assertEquals(2, requests.get(), "the build tried again");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPlanThatFailsFetchesNothingAndSaysNothing(@TempDir Path cacheDir) throws Exception {
        var events = RecordingOutput.during(() -> {
            try (var ahead = BackgroundDownloads.start(cache(cacheDir), () -> {
                throw new IOException("the release list is unreachable");
            })) {
                assertTrue(ahead.await(Duration.ofSeconds(10)));
                assertEquals(List.of(), ahead.fetched());
            }
        }).events();
        assertEquals(List.of(), events);
    }

    @Test
    void aFileWithoutAChecksumIsNotFetchedAhead(@TempDir Path cacheDir) throws Exception {
        // The build would download it again whatever was fetched, so fetching it ahead only costs
        var requests = new AtomicInteger();
        var server = serve(requests, null);
        try {
            try (var ahead = BackgroundDownloads.start(cache(cacheDir),
                    () -> List.of(new DownloadCache.Pinned(url(server, "/latest"), null)))) {
                assertTrue(ahead.await(Duration.ofSeconds(10)));
            }
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aHostLocalUrlIsRefusedAheadAsByTheCache(@TempDir Path cacheDir) throws Exception {
        // The real check: a definition's URL may not reach the host's own services ahead either
        var requests = new AtomicInteger();
        var server = serve(requests, null);
        try {
            var url = url(server, "/image.tar.xz");
            try (var ahead = BackgroundDownloads.start(new DownloadCache(cacheDir),
                    () -> List.of(new DownloadCache.Pinned(url, sha256(BODY)), new DownloadCache.Pinned("file:///etc/passwd", sha256(BODY))))) {
                assertTrue(ahead.await(Duration.ofSeconds(10)));
                assertEquals(List.of(url, "file:///etc/passwd"), ahead.failed());
            }
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closingStopsADownloadAndKeepsWhatItFetchedForTheNextRun(@TempDir Path cacheDir) throws Exception {
        var requests = new AtomicInteger();
        var release = new CountDownLatch(1);
        var server = serve(requests, release);
        try {
            var url = url(server, "/image.tar.xz");
            var sha = sha256(BODY);
            var ahead = BackgroundDownloads.start(cache(cacheDir), () -> List.of(new DownloadCache.Pinned(url, sha)));
            var partial = cacheDir.resolve(DownloadCache.PARTIAL_DIR).resolve(DownloadCache.cacheFilename(url));
            for (var i = 0; i < 500 && !(Files.exists(partial) && Files.size(partial) > 0); i++) Thread.sleep(10);
            ahead.close();
            assertTrue(ahead.await(Duration.ofSeconds(10)), "the download stopped");
            assertEquals(List.of(), ahead.fetched());
            assertTrue(Files.size(partial) > 0, "the partial file is kept");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /** Serves {@link #BODY} with a strong validator; with {@code hold}, half of it, then waits for it. */
    private static HttpServer serve(AtomicInteger requests, CountDownLatch hold) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            ex.getResponseHeaders().add("ETag", "\"v1\"");
            ex.sendResponseHeaders(200, BODY.length);
            try (var body = ex.getResponseBody()) {
                if (hold == null) {
                    body.write(BODY);
                } else {
                    body.write(BODY, 0, BODY.length / 2);
                    body.flush();
                    hold.await(30, TimeUnit.SECONDS);
                    body.write(BODY, BODY.length / 2, BODY.length - BODY.length / 2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client went away
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static DownloadCache cache(Path cacheDir) {
        return new DownloadCache(cacheDir, uri -> false, List.of(), duration -> {});
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
