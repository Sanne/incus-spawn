package dev.incusspawn.proxy;

import dev.incusspawn.util.Eventually;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "confirm every serve" cache of {@code confirmed-cache:} domains (#2518), against a stub
 * that stands in for Azure blob storage behind GitHub's cache service: every object is reached by
 * a signed link whose {@code sig} the stub checks before anything else, and it answers
 * {@code If-None-Match}, {@code Range} and {@code x-ms-range} as Azure does.
 */
class ConfirmedCacheProxyTest {

    static final String HOST = "productionresultssa1.blob.core.windows.net";
    static final String BLOB = "/actions-results/cache/maven-linux.tzst";
    static final String GOOD = "?sv=2025&se=later&sig=good";
    static final String BAD = "?sv=2025&se=later&sig=forged";
    // What the stub's Range matching reads
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static HttpServer upstream;
    static int upstreamPort;
    static HttpClient client;

    /** The blob's bytes now, and the ETag they are served at. */
    static volatile String content;
    static volatile String etag;
    /** Every request upstream received: "status range If-None-Match". */
    static final List<String> received = new CopyOnWriteArrayList<>();
    /** Whole bodies upstream sent: a fill or a full download. */
    static final AtomicInteger wholeBodies = new AtomicInteger();
    static final AtomicInteger versions = new AtomicInteger();
    /** Paths a whole body was asked for, in order. */
    static final List<String> wholePaths = new CopyOnWriteArrayList<>();
    /** Whole bodies are held after their first byte until {@link #releaseStalled}. */
    static volatile boolean stallWhole;
    static final List<Runnable> stalled = new CopyOnWriteArrayList<>();
    /** The stored copy is removed just before upstream answers 304. */
    static volatile boolean evictBefore304;

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), CONFIG);
        vertx = Vertx.vertx();
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), HOST)))
                .requestHandler(ConfirmedCacheProxyTest::answer);
        upstreamPort = NpmCacheProxyTest.listen(upstream);
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        ContainerTls.startInBackground(proxy);
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @BeforeEach
    void reset() throws Exception {
        proxy.overrideUpstream(HOST, "127.0.0.1", upstreamPort);
        // An empty store: the last one is moved aside, since a fill a previous test started may still write to it
        var dir = ConfirmedCacheStore.dir();
        if (Files.exists(dir)) Files.move(dir, dir.resolveSibling("confirmed-before-" + versions.incrementAndGet()));
        publish("blob " + versions.incrementAndGet() + " " + "0123456789".repeat(100));
        received.clear();
        wholeBodies.set(0);
        evictBefore304 = false;
        wholePaths.clear();
        releaseStalled();
        stallWhole = false;
    }

    static void releaseStalled() {
        var waiting = List.copyOf(stalled);
        stalled.clear();
        waiting.forEach(release -> vertx.runOnContext(v -> release.run()));
    }

    static final String CONFIG = """
            confirmed-cache:
              domains:
                - "*.blob.core.windows.net"
            """;

    static void configure(String extra) throws Exception {
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), CONFIG + extra);
        proxy.reload();
    }

    static void publish(String bytes) {
        content = bytes;
        etag = "\"0x8DD" + Integer.toHexString(bytes.hashCode()) + "\"";
    }

    static void answer(HttpServerRequest req) {
        var resp = req.response();
        var inm = req.getHeader("If-None-Match");
        var range = req.getHeader("x-ms-range") != null ? req.getHeader("x-ms-range") : req.getHeader("Range");
        // Azure checks the signature before any condition: a forged link never learns the ETag
        if (!"good".equals(req.getParam("sig")) || !req.path().startsWith("/actions-results/")) {
            received.add("403 " + range + " " + inm);
            resp.setStatusCode(403).putHeader("Content-Type", "application/xml")
                    .end("<Error><Code>AuthenticationFailed</Code></Error>");
            return;
        }
        if ("metadata".equals(req.getParam("comp"))) {
            // Azure answers a metadata read with the blob's own ETag and no body of the blob's
            received.add("200 comp=metadata " + inm);
            resp.putHeader("ETag", etag).putHeader("x-ms-meta-owner", "ci").end();
            return;
        }
        resp.putHeader("ETag", etag).putHeader("x-ms-blob-type", "BlockBlob")
                .putHeader("x-ms-request-id", "req-" + received.size())
                .putHeader("Content-Type", "application/octet-stream").putHeader("Accept-Ranges", "bytes");
        if (etag.equals(inm)) {
            if (evictBefore304) {
                // The copy goes between the proxy's read of it and its serve: evicted, or superseded
                try {
                    Files.delete(ConfirmedCacheStore.current(ConfirmedCacheStore.objectDir(HOST, BLOB, null)).body());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            received.add("304 " + range + " " + inm);
            resp.setStatusCode(304).end();
            return;
        }
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        if (range != null) {
            var m = RANGE.matcher(range);
            assertTrue(m.matches(), range);
            var first = Integer.parseInt(m.group(1));
            var last = Math.min(Integer.parseInt(m.group(2)), bytes.length - 1);
            received.add("206 " + range + " " + inm);
            resp.setStatusCode(206).putHeader("Content-Range", "bytes " + first + "-" + last + "/" + bytes.length)
                    .end(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOfRange(bytes, first, last + 1)));
            return;
        }
        received.add("200 " + range + " " + inm);
        wholeBodies.incrementAndGet();
        wholePaths.add(req.path());
        resp.putHeader("Content-MD5", java.util.Base64.getEncoder().encodeToString(md5(bytes)));
        if (stallWhole) {
            // The head and one byte now, the rest when the test lets it go
            resp.putHeader("Content-Length", String.valueOf(bytes.length));
            resp.write(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOfRange(bytes, 0, 1)));
            stalled.add(() -> resp.end(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOfRange(bytes, 1, bytes.length))));
            return;
        }
        resp.end(io.vertx.core.buffer.Buffer.buffer(bytes));
    }

    static byte[] md5(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Answer(int status, String body, Map<String, String> headers) {}

    static Answer get(String query, Map<String, String> headers) throws Exception {
        return getAt(BLOB, query, headers);
    }

    static Answer getAt(String path, String query, Map<String, String> headers) throws Exception {
        var options = new RequestOptions().setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(HOST).setPort(443).setURI(path + query);
        return client.request(options).compose(req -> {
            headers.forEach(req::putHeader);
            return req.send();
        }).compose(resp -> resp.body().map(b -> {
            var seen = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            resp.headers().forEach(h -> seen.put(h.getKey(), h.getValue()));
            return new Answer(resp.statusCode(), b.toString(StandardCharsets.UTF_8), seen);
        })).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    static Answer get(String query) throws Exception {
        return get(query, Map.of());
    }

    static void awaitCopy(String bytes) throws Exception {
        awaitCopy(BLOB, bytes);
    }

    static ConfirmedCacheStore.Copy copyAt(String path) {
        return ConfirmedCacheStore.current(ConfirmedCacheStore.objectDir(HOST, path, null));
    }

    static void awaitCopy(String path, String bytes) throws Exception {
        Eventually.await("the blob was never stored", Duration.ofSeconds(5), () -> {
            var copy = copyAt(path);
            return copy != null && Files.readString(copy.body()).equals(bytes);
        });
    }

    /** The regression: a stored blob is served on upstream's 304 for the client's own signed request. */
    @Test
    void aCopyIsServedOnlyOnUpstreams304() throws Exception {
        var first = get(GOOD);
        assertEquals(200, first.status());
        assertEquals(content, first.body());
        awaitCopy(content);

        var second = get(GOOD);

        assertEquals(200, second.status());
        assertEquals(content, second.body());
        assertEquals(etag, second.headers().get("ETag"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(md5(content.getBytes(StandardCharsets.UTF_8))),
                second.headers().get("Content-MD5"));
        assertEquals("BlockBlob", second.headers().get("x-ms-blob-type"));
        assertEquals(1, wholeBodies.get(), "the blob was downloaded again");
        assertEquals(List.of("200 null null", "304 null " + etag), received);
    }

    /**
     * Another resource at the blob's path, which Azure answers with the blob's own ETag, never
     * shares the blob's copy: a download after it is still the blob, and it is never the blob.
     */
    @Test
    void aQueryForSomethingElseAtThePathIsAnotherObject() throws Exception {
        var metadata = get(GOOD + "&comp=metadata");
        assertEquals(200, metadata.status());
        assertEquals("", metadata.body());

        var blob = get(GOOD);
        assertEquals(content, blob.body());
        awaitCopy(content);

        var again = get(GOOD + "&comp=metadata");
        assertEquals("", again.body(), "the metadata read was served the blob");
        assertEquals("ci", again.headers().get("x-ms-meta-owner"));
    }

    /**
     * The fills under way are reserved against max-size before any is written, so a guest that
     * asks one byte of many large blobs cannot make the proxy write past it: the fill that does not
     * fit beside the one under way is not started, and nothing of it is stored.
     */
    @Test
    void theFillsUnderWayStayWithinMaxSize() throws Exception {
        try {
            configure("  max-size: \"" + (content.length() * 3 / 2) + "\"\n");
            stallWhole = true;
            assertEquals(206, getAt("/actions-results/a", GOOD, Map.of("Range", "bytes=0-0")).status());
            Eventually.await("the first fill never started", Duration.ofSeconds(5), () -> wholePaths.size() == 1);

            assertEquals(206, getAt("/actions-results/b", GOOD, Map.of("Range", "bytes=0-0")).status());
            Eventually.await("the second fill was never asked for", Duration.ofSeconds(5), () -> wholePaths.size() == 2);
            stallWhole = false;
            releaseStalled();

            awaitCopy("/actions-results/a", content);
            Thread.sleep(300);
            assertNull(copyAt("/actions-results/b"), "a fill past max-size was stored");
        } finally {
            configure("");
        }
    }

    /** At most a few background fills run at once, however many objects a guest asks a byte of. */
    @Test
    void backgroundFillsAreCapped() throws Exception {
        stallWhole = true;
        for (int i = 0; i <= ConfirmedCacheHandler.MAX_BACKGROUND_FILLS; i++) {
            assertEquals(206, getAt("/actions-results/many-" + i, GOOD, Map.of("Range", "bytes=0-0")).status());
        }
        Eventually.await("the fills never started", Duration.ofSeconds(5),
                () -> wholePaths.size() == ConfirmedCacheHandler.MAX_BACKGROUND_FILLS);
        Thread.sleep(300);

        assertEquals(ConfirmedCacheHandler.MAX_BACKGROUND_FILLS, wholePaths.size(), wholePaths.toString());
    }

    /** A background fill that trickles is given up at its deadline, and its slot goes to the next one. */
    @Test
    void aTricklingFillGivesUpItsSlot() throws Exception {
        proxy.confirmedCache.fillDeadlineBaseMillis = 300;
        try {
            stallWhole = true;
            for (int i = 0; i < ConfirmedCacheHandler.MAX_BACKGROUND_FILLS; i++) {
                getAt("/actions-results/slow-" + i, GOOD, Map.of("Range", "bytes=0-0"));
            }
            Eventually.await("the fills never started", Duration.ofSeconds(5),
                    () -> wholePaths.size() == ConfirmedCacheHandler.MAX_BACKGROUND_FILLS);
            // A ranged miss while every slot is held is only relayed: the next one asks again
            Eventually.await("the next fill never got a slot", Duration.ofSeconds(10), () -> {
                getAt("/actions-results/next", GOOD, Map.of("Range", "bytes=0-0"));
                return wholePaths.contains("/actions-results/next");
            });
        } finally {
            proxy.confirmedCache.fillDeadlineBaseMillis = 120_000;
        }
    }

    /** A Host header in another case is the same host: it finds the copy. */
    @Test
    void aHostInAnotherCaseFindsTheCopy() throws Exception {
        get(GOOD);
        awaitCopy(content);
        received.clear();

        var options = new RequestOptions().setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(HOST.toUpperCase(java.util.Locale.ROOT)).setPort(443).setURI(BLOB + GOOD);
        var status = client.request(options).compose(req -> req.send()).compose(resp -> resp.body().map(b -> resp.statusCode()))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(200, status);
        assertEquals(List.of("304 null " + etag), received);
    }

    /** A copy removed between its read and upstream's 304 is fetched again, never a 500. */
    @Test
    void aCopyGoneBeforeItsServeIsFetchedAgain() throws Exception {
        get(GOOD);
        awaitCopy(content);
        evictBefore304 = true;

        var answer = get(GOOD);

        assertEquals(200, answer.status());
        assertEquals(content, answer.body());
        assertEquals(2, wholeBodies.get());
    }

    /** A link upstream refuses gets upstream's refusal, never the copy another link stored. */
    @Test
    void aRefusedLinkGetsTheRefusalNotTheCopy() throws Exception {
        get(GOOD);
        awaitCopy(content);

        var refused = get(BAD);

        assertEquals(403, refused.status());
        assertTrue(refused.body().contains("AuthenticationFailed"), refused.body());
        assertFalse(refused.body().contains("blob "), refused.body());
    }

    /** A blob overwritten upstream is served as it is now, and its new bytes replace the copy. */
    @Test
    void aChangedEtagReplacesTheCopy() throws Exception {
        get(GOOD);
        awaitCopy(content);
        var old = content;
        publish("overwritten " + old);

        var changed = get(GOOD);

        assertEquals(200, changed.status());
        assertEquals(content, changed.body());
        awaitCopy(content);
        assertEquals(content, get(GOOD).body());
        assertEquals(2, wholeBodies.get());
        assertNotEquals(old, content);
    }

    /**
     * GitHub's cache action reads a blob in ranges, in parallel: a miss relays them and fetches the
     * whole blob once beside them, and then every range is served from that one copy.
     */
    @Test
    void rangesAreServedFromOneStoredCopy() throws Exception {
        var bytes = content;
        var miss = get(GOOD, Map.of("Range", "bytes=0-99"));
        assertEquals(206, miss.status());
        assertEquals(bytes.substring(0, 100), miss.body());
        awaitCopy(bytes);
        received.clear();

        var plain = get(GOOD, Map.of("Range", "bytes=100-199"));
        var azure = get(GOOD, Map.of("x-ms-range", "bytes=1000-1999"));
        var suffix = get(GOOD, Map.of("Range", "bytes=-10"));
        var beyond = get(GOOD, Map.of("Range", "bytes=50000-50010"));

        assertEquals(new Answer(206, bytes.substring(100, 200), plain.headers()), plain);
        assertEquals("bytes 100-199/" + bytes.length(), plain.headers().get("Content-Range"));
        assertNull(plain.headers().get("Content-MD5"), "the whole blob's MD5 does not describe a range of it");
        assertEquals(206, azure.status());
        assertEquals(bytes.substring(1000), azure.body());
        assertEquals("bytes 1000-" + (bytes.length() - 1) + "/" + bytes.length(), azure.headers().get("Content-Range"));
        assertEquals(bytes.substring(bytes.length() - 10), suffix.body());
        assertEquals(416, beyond.status());
        assertEquals(1, wholeBodies.get(), "the blob was fetched whole more than once");
        assertTrue(received.stream().allMatch(r -> r.startsWith("304 ")), received.toString());
    }

    /** No stored bytes while upstream cannot be asked: the copy is only ever served on its 304. */
    @Test
    void nothingIsServedWhileUpstreamCannotBeAsked() throws Exception {
        get(GOOD);
        awaitCopy(content);
        var closed = new java.net.ServerSocket(0);
        int refusedPort = closed.getLocalPort();
        closed.close();
        proxy.overrideUpstream(HOST, "127.0.0.1", refusedPort);

        var answer = get(GOOD);

        assertEquals(502, answer.status());
        assertNotEquals(content, answer.body());
    }

    /** A request with a question of its own is relayed as it came, never answered from the copy. */
    @Test
    void aClientsOwnConditionIsRelayed() throws Exception {
        get(GOOD);
        awaitCopy(content);
        received.clear();

        var answer = get(GOOD, Map.of("If-None-Match", "\"something-else\""));

        assertEquals(200, answer.status());
        assertEquals(List.of("200 null \"something-else\""), received);
    }
}
