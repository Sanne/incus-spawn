package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.Platform;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Maven/Gradle artifact cache end to end: a client talking to the proxy,
 * the proxy talking to a mock upstream standing in for every repository domain.
 */
class ArtifactCacheProxyTest {

    static final String CENTRAL = "repo.maven.apache.org";
    static final String PORTAL = "plugins.gradle.org";
    static final String GRADLE = "services.gradle.org";
    static final String GRADLE_DOWNLOADS = "downloads.gradle.org";
    static final String JAR = "/maven2/org/example/lib/1.0/lib-1.0.jar";
    static final String PLUGIN_JAR = "/m2/org/example/plugin/1.0/plugin-1.0.jar";
    static final String DIST = "/distributions/gradle-9.0-bin.zip";

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static HttpServer upstream;
    /** The same routes on a listener without ALPN, like an upstream that only speaks HTTP/1.1. */
    static HttpServer h1Upstream;
    static HttpClient client;
    // Every request of the test client goes out from this one context. Issued from the
    // test thread instead, each got a context of its own, and under load responses lost
    // their bodies and the client's TLS stream was corrupted (bad_record_mac, records of
    // zeros): seen in CI, and reproduced by running this class repeatedly in one JVM on
    // two CPUs, where it no longer happens this way.
    static io.vertx.core.Context clientContext;
    static int mitmPort;
    static int upstreamPort;
    static int h1UpstreamPort;

    /** A reply status that closes the connection instead of answering. */
    static final int DROP = -2;

    record Reply(int status, byte[] body, String location, String checksumHeader) {
        Reply(int status, byte[] body, String location) {
            this(status, body, location, null);
        }
    }

    /** Keyed by "host path"; anything else is a 404. Hits are keyed by "METHOD host path". */
    static final Map<String, Reply> routes = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** The protocols and connections HEADs arrived on. */
    static final Set<HttpVersion> headVersions = ConcurrentHashMap.newKeySet();
    static final Set<HttpConnection> headConnections = ConcurrentHashMap.newKeySet();
    /** The connection the last HEAD arrived on, and the one the first stalled HEAD was left waiting on. */
    static volatile HttpConnection lastHeadConnection;
    static volatile HttpConnection stalledConnection;
    /** HEADs left to receive but never answer, like a connection that died silently. */
    static final AtomicInteger headsToStall = new AtomicInteger();
    static volatile long headDelayMs;
    static final AtomicInteger headsAnswered = new AtomicInteger();
    /** GETs left to cut off halfway through their body: stalled, or closed when {@link #cutByClosing}. */
    static final AtomicInteger getsToCut = new AtomicInteger();
    static volatile boolean cutByClosing;
    /** Whether a Range request gets its 206, as from the real repositories and registries. */
    static volatile boolean honourRange;
    static final List<String> rangesAsked = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final List<String> ifRangesAsked = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The ETag GETs answer with, if any; with {@link #changeOnCut} a cut changes it, as a republish would. */
    static volatile String etag;
    static volatile String lastModified;
    /** The Date GETs answer with, if any: what makes Last-Modified a strong validator or not. */
    static volatile String date;
    /** Range answers left to end short, as if the connection closed early without an error. */
    static final AtomicInteger rangesToShorten = new AtomicInteger();
    static volatile boolean changeOnCut;
    /** GETs left to receive but never answer. */
    static final AtomicInteger getsToIgnore = new AtomicInteger();
    /** How long the next GET's head takes; a cut then comes before any of its body when {@link #cutBeforeBody}. */
    static volatile long nextGetDelayMs;
    static volatile boolean cutBeforeBody;
    /** When set, a cut GET is not left stalled: the rest follows after this pause. */
    static volatile long cutPauseMs;
    /** Range requests left to answer 503, like an overloaded CDN. */
    static final AtomicInteger rangesToRefuse = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        for (var legacy : Environment.unverifiedLegacyCacheDirs()) {
            Files.createDirectories(legacy.resolve("repo.maven.apache.org/stale"));
            Files.writeString(legacy.resolve("repo.maven.apache.org/stale/x.jar"), "unverified");
        }

        var ca = CertificateAuthority.loadOrCreate();
        var leaf = ca.generateDomainCert(CENTRAL);
        vertx = Vertx.vertx();
        var keyCert = new PemKeyCertOptions()
                .setCertValue(Buffer.buffer(DerEncoder.toPem("CERTIFICATE", leaf.cert().getEncoded())))
                .setKeyValue(Buffer.buffer(DerEncoder.toPem("PRIVATE KEY", leaf.key().getEncoded())));
        // Offers h2 like the real repositories do, so confirmations run over HTTP/2
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true).setUseAlpn(true).setKeyCertOptions(keyCert))
                .requestHandler(ArtifactCacheProxyTest::answer);
        upstreamPort = upstream.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();
        h1Upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true).setKeyCertOptions(keyCert))
                .requestHandler(ArtifactCacheProxyTest::answer);
        h1UpstreamPort = h1Upstream.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();

        mitmPort = WebSocketProxyTest.findFreePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, WebSocketProxyTest.findFreePort(), "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", java.util.List.of()));
        proxy.upstreamTrustAll = true;
        proxy.probeReadIdleSeconds = 1;
        // Tests read the hit counts themselves; a summary logged meanwhile would drain them
        proxy.cacheStatsIntervalMs = TimeUnit.HOURS.toMillis(1);
        var ready = new CountDownLatch(1);
        var thread = new Thread(() -> {
            try {
                proxy.start(ready::countDown);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "test-proxy");
        thread.setDaemon(true);
        thread.start();
        assertTrue(ready.await(15, TimeUnit.SECONDS), "Proxy did not start in time");

        clientContext = vertx.getOrCreateContext();
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false).setMaxPoolSize(16));
    }

    static void answer(HttpServerRequest req) {
        var host = req.authority().host();
        var key = host + " " + req.path();
        hits.computeIfAbsent(req.method() + " " + key, k -> new AtomicInteger()).incrementAndGet();
        if (req.method() == HttpMethod.HEAD) {
            headVersions.add(req.version());
            headConnections.add(req.connection());
            lastHeadConnection = req.connection();
            if (headsToStall.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                if (stalledConnection == null) stalledConnection = req.connection();
                return;
            }
            if (headDelayMs > 0) {
                vertx.setTimer(headDelayMs, t -> reply(req, key));
                return;
            }
        }
        if (req.method() == HttpMethod.GET && getsToIgnore.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            return;
        }
        var delay = req.method() == HttpMethod.GET ? nextGetDelayMs : 0;
        if (delay > 0) {
            nextGetDelayMs = 0;
            vertx.setTimer(delay, t -> reply(req, key));
            return;
        }
        reply(req, key);
    }

    static void reply(HttpServerRequest req, String key) {
        if (req.method() == HttpMethod.HEAD) headsAnswered.incrementAndGet();
        var reply = routes.get(key);
        var resp = req.response();
        if (reply == null) {
            resp.setStatusCode(404).end("not found");
            return;
        }
        if (reply.status() == DROP) {
            req.connection().close();
            return;
        }
        resp.setStatusCode(reply.status());
        if (reply.location() != null) resp.putHeader("Location", reply.location());
        if (reply.checksumHeader() != null) resp.putHeader("X-Checksum-SHA1", reply.checksumHeader());
        var body = reply.body() == null ? new byte[0] : reply.body();
        var currentEtag = etag;
        if (currentEtag != null && reply.status() == 200) resp.putHeader("ETag", currentEtag);
        var currentLastModified = lastModified;
        if (currentLastModified != null && reply.status() == 200) resp.putHeader("Last-Modified", currentLastModified);
        var currentDate = date;
        if (currentDate != null) resp.putHeader("Date", currentDate);
        var range = req.getHeader("Range");
        if (range != null) rangesAsked.add(range);
        var ifRange = req.getHeader("If-Range");
        if (ifRange != null) ifRangesAsked.add(ifRange);
        if (range != null && rangesToRefuse.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            resp.setStatusCode(503).end();
            return;
        }
        if (range != null && honourRange && reply.status() == 200
                && (ifRange == null || ifRange.equals(currentEtag) || ifRange.equals(currentLastModified))) {
            var from = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
            var rest = java.util.Arrays.copyOfRange(body, from, body.length);
            resp.setStatusCode(206)
                    .putHeader("Content-Range", "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
            if (getsToCut.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                // Stalls again, halfway through the rest
                resp.putHeader("Content-Length", String.valueOf(rest.length))
                        .write(Buffer.buffer(java.util.Arrays.copyOf(rest, rest.length / 2)));
                return;
            }
            if (rangesToShorten.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                rest = java.util.Arrays.copyOf(rest, rest.length / 2);
            }
            resp.end(Buffer.buffer(rest));
            return;
        }
        if (req.method() == HttpMethod.GET && reply.status() == 200
                && getsToCut.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            resp.putHeader("Content-Length", String.valueOf(body.length));
            if (changeOnCut) etag = "\"changed\"";
            var cutAt = cutBeforeBody ? 0 : body.length / 2;
            var pause = cutPauseMs;
            resp.write(Buffer.buffer(java.util.Arrays.copyOf(body, cutAt)))
                    .onComplete(written -> {
                        if (cutByClosing) req.connection().close();
                    });
            if (pause > 0) {
                vertx.setTimer(pause, t -> resp.end(Buffer.buffer(java.util.Arrays.copyOfRange(body, cutAt, body.length))));
            }
            return;
        }
        resp.end(Buffer.buffer(body));
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
        routes.clear();
        hits.clear();
        headVersions.clear();
        headConnections.clear();
        lastHeadConnection = null;
        stalledConnection = null;
        headsToStall.set(0);
        headDelayMs = 0;
        headsAnswered.set(0);
        getsToCut.set(0);
        cutByClosing = false;
        honourRange = true;
        rangesAsked.clear();
        ifRangesAsked.clear();
        etag = null;
        lastModified = null;
        date = null;
        rangesToShorten.set(0);
        changeOnCut = false;
        getsToIgnore.set(0);
        nextGetDelayMs = 0;
        cutBeforeBody = false;
        cutPauseMs = 0;
        rangesToRefuse.set(0);
        proxy.downloadIdleSeconds = 20;
        proxy.clientSilenceBudgetSeconds = 110;
        proxy.maxBackgroundConfirmations = 16;
        // Most tests are about confirming a hit; the tiers that skip it have tests of their own
        proxy.artifactCacheTiers = ArtifactCacheTiers.CONFIRM_EVERY_HIT;
        proxy.logCacheStats();
        online();
        for (var dir : new Path[] {Environment.mavenCacheDir(), Environment.gradleCacheDir(),
                Environment.m2Repository()}) {
            // user.home is JVM-global: never let a stray value point this at a real ~/.m2
            assertTrue(dir.startsWith(tempHome), dir + " is outside the test home");
            await(dir + " to be deleted", () -> deleteOnceIdle(dir));
        }
    }

    /**
     * A download is committed after its response ends, so the previous test can leave one
     * running; deleting under it fails the walk (DirectoryNotEmpty) or lets it write into
     * the next test. Each in-flight store holds a {@code .tmp} beside its target until it
     * commits, so wait for none to be left, and retry if one still races the walk.
     */
    static boolean deleteOnceIdle(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var files = Files.walk(dir)) {
                    if (files.anyMatch(f -> f.getFileName().toString().endsWith(".tmp"))) return false;
                }
            }
            FileTrees.delete(dir);
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
        return !Files.exists(dir);
    }

    // --- helpers ---

    /** Every repository host goes to the mock, through the same hook the benchmark uses. */
    static void online() {
        proxy.clearUnreachable();
        for (var host : new String[] {CENTRAL, PORTAL, GRADLE, GRADLE_DOWNLOADS}) {
            online(host);
        }
    }

    static void online(String host) {
        assertTrue(ProxyMain.applyBenchUpstream(proxy, host + "=127.0.0.1:" + upstreamPort, ""));
    }

    /** Nothing listens on 127.0.0.2's port, so every connection is refused. */
    static void offline() {
        for (var host : new String[] {CENTRAL, PORTAL, GRADLE, GRADLE_DOWNLOADS}) {
            proxy.overrideUpstream(host, "127.0.0.2", upstreamPort);
        }
    }

    record Response(int status, byte[] body, String checksumHeader) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static Response get(String host, String path) throws Exception {
        return getAsync(host, path).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    static Future<Response> getAsync(String host, String path) {
        return sendAsync(host, path, resp -> resp.body().map(b -> new Response(resp.statusCode(), b.getBytes(),
                resp.getHeader("X-Checksum-SHA1"))));
    }

    /** A GET through the proxy, its response handled by {@code read}, all on the client's context. */
    static <T> Future<T> sendAsync(String host, String path,
                                   Function<io.vertx.core.http.HttpClientResponse, Future<T>> read) {
        return requestAsync(host, path, req -> req.send().compose(read));
    }

    /** A GET through the proxy, sent (or not) by {@code send}, on the client's context. */
    static <T> Future<T> requestAsync(String host, String path, Function<HttpClientRequest, Future<T>> send) {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                .setHost(host)
                .setPort(443)
                .setURI(path);
        return onClientContext(() -> client.request(options).compose(send));
    }

    static void publish(String host, String path, String content, String algorithm, String extension)
            throws Exception {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        routes.put(host + " " + path, new Reply(200, bytes, null));
        routes.put(host + " " + path + extension, new Reply(200, hex(algorithm, bytes).getBytes(), null));
    }

    /** Like the real repositories: Central also sends X-Checksum-SHA1, the plugin portal doesn't. */
    static void publishJar(String host, String path, String content) throws Exception {
        publish(host, path, content, "SHA-1", ".sha1");
        if (host.equals(CENTRAL)) {
            var bytes = content.getBytes(StandardCharsets.UTF_8);
            routes.put(host + " " + path, new Reply(200, bytes, null, hex("SHA-1", bytes)));
        }
    }

    static String hex(String algorithm, byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
    }

    static int hitsOn(String host, String path) {
        return count("GET", host, path);
    }

    static int headsOn(String host, String path) {
        return count("HEAD", host, path);
    }

    static int count(String method, String host, String path) {
        var n = hits.get(method + " " + host + " " + path);
        return n == null ? 0 : n.get();
    }

    static Path cached(String host, String path) {
        return Environment.mavenCacheDir().resolve(host).resolve(path.substring(1));
    }

    @FunctionalInterface
    interface Condition {
        boolean holds() throws Exception;
    }

    /** Polls every 10 ms, so a wait lasts barely longer than the condition takes. */
    static void await(String what, Condition condition) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.holds()) {
            if (System.nanoTime() - deadline > 0) fail("Timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    /** Commits happen after the response ends, on a worker thread. */
    static void awaitFile(Path file, String content) throws Exception {
        await(file + " to hold " + content,
                () -> Files.isRegularFile(file) && Files.readString(file).equals(content));
    }

    /**
     * A checksum sidecar lands just after its artifact (VerifiedArtifactStore commits it last,
     * for crash safety), so seeing the artifact does not mean the sidecar is there yet.
     */
    static void awaitSidecar(Path file) throws Exception {
        await(file + " to exist", () -> Files.isRegularFile(file));
    }

    static void assertStaysPresent(Path file, String content) throws Exception {
        Thread.sleep(300);
        assertEquals(content, Files.readString(file), file + " should have stayed cached");
    }

    static void assertStaysAbsent(Path file) throws Exception {
        Thread.sleep(300);
        assertFalse(Files.exists(file), file + " should not have been cached");
    }

    // --- tests ---

    @Test
    void centralMissIsOneGetVerifiedAgainstItsOwnChecksumHeader() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        var miss = get(CENTRAL, JAR);
        assertEquals("v1", miss.text());
        assertEquals(hex("SHA-1", "v1".getBytes()), miss.checksumHeader(), "upstream's header is passed on");
        awaitFile(cached(CENTRAL, JAR), "v1");
        awaitSidecar(Sidecar.SHA1.storedFile(cached(CENTRAL, JAR)));
        assertEquals(1, hitsOn(CENTRAL, JAR));
        assertEquals(0, headsOn(CENTRAL, JAR), "no HEAD when nothing is cached");
        assertEquals(0, hitsOn(CENTRAL, JAR + ".sha1"));
    }

    @Test
    void centralHitIsConfirmedWithOneHead() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        hits.clear();
        var hit = get(CENTRAL, JAR);
        assertEquals("v1", hit.text());
        assertEquals(hex("SHA-1", "v1".getBytes()), hit.checksumHeader(),
                "the fresh upstream checksum lets Maven skip its .sha1 request");
        assertEquals(1, headsOn(CENTRAL, JAR));
        assertEquals(0, hitsOn(CENTRAL, JAR));
        assertEquals(0, hitsOn(CENTRAL, JAR + ".sha1"));
    }

    /** Cache the jars, then confirm them all at once, each HEAD answered after {@code delayMs}. */
    static void confirmConcurrently(List<String> jars, long delayMs) throws Exception {
        for (var jar : jars) {
            publishJar(CENTRAL, jar, jar);
            get(CENTRAL, jar);
            awaitFile(cached(CENTRAL, jar), jar);
        }
        headDelayMs = delayMs;
        var responses = Future.all(jars.stream().map(jar -> getAsync(CENTRAL, jar)).toList())
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        for (int i = 0; i < jars.size(); i++) {
            assertEquals(jars.get(i), responses.<Response>resultAt(i).text());
            assertNotNull(responses.<Response>resultAt(i).checksumHeader(), "confirmed by upstream");
        }
    }

    static List<String> jars(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "/maven2/org/example/lib" + i + "/1.0/lib" + i + "-1.0.jar").toList();
    }

    @Test
    void concurrentConfirmationsShareHttp2Connections() throws Exception {
        // One confirmation first, so the burst finds the connection already open
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        get(CENTRAL, JAR);

        var jars = jars(6);
        confirmConcurrently(jars, 300);
        assertEquals(Set.of(HttpVersion.HTTP_2), headVersions);
        // Over HTTP/1.1 each concurrent HEAD needs a connection of its own. The pool may still
        // hold connections from earlier tests, so this asserts sharing, not an exact count.
        assertTrue(headConnections.size() < jars.size(),
                jars.size() + " concurrent HEADs on " + headConnections.size() + " connections");
    }

    @Test
    void confirmationsToAnHttp1UpstreamRunInParallel() throws Exception {
        assertTrue(ProxyMain.applyBenchUpstream(proxy, CENTRAL + "=127.0.0.1:" + h1UpstreamPort, ""));
        long start = System.nanoTime();
        confirmConcurrently(jars(8), 500);
        long confirmMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(Set.of(HttpVersion.HTTP_1_1), headVersions);
        assertTrue(headConnections.size() > 1, "HEADs spread over " + headConnections.size() + " connection(s)");
        // One at a time would take 8 x 500ms on top of the downloads
        assertTrue(confirmMs < 4000, "took " + confirmMs + "ms");
    }

    @Test
    void silentlyDeadConnectionIsReplacedBeforeTheHitTimesOut() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        get(CENTRAL, JAR);

        headsToStall.set(1);
        long start = System.nanoTime();
        var hit = get(CENTRAL, JAR);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals("v1", hit.text());
        assertNotNull(hit.checksumHeader(), "confirmed on a new connection, not served unconfirmed");
        assertNotNull(stalledConnection, "a HEAD was stalled");
        assertNotSame(stalledConnection, lastHeadConnection, "the retry went to another connection");
        assertTrue(ms < 10_000, "took " + ms + "ms");
    }

    // --- confirmation tiers (artifact-cache: fresh / max-stale) ---

    /**
     * Switch to the default tiers and cache {@code content} at {@code path}, as a first
     * request would. Returns the stored checksum, whose mtime is the confirmation time.
     */
    static Path cacheJar(String host, String path, String content) throws Exception {
        proxy.artifactCacheTiers = ArtifactCacheTiers.DEFAULT;
        publishJar(host, path, content);
        get(host, path);
        awaitFile(cached(host, path), content);
        var stored = Sidecar.SHA1.storedFile(cached(host, path));
        awaitSidecar(stored);
        return stored;
    }

    /** Make an artifact look last confirmed {@code age} ago. */
    static void confirmedAgo(Path storedChecksum, Duration age) throws Exception {
        Files.setLastModifiedTime(storedChecksum, FileTime.from(Instant.now().minus(age)));
    }

    @Test
    void freshHitIsServedWithoutAskingUpstream() throws Exception {
        cacheJar(CENTRAL, JAR, "v1");

        // Even a republish goes unseen while the confirmation is fresh: that is the trade
        publishJar(CENTRAL, JAR, "v2");
        hits.clear();
        var hit = get(CENTRAL, JAR);
        assertEquals("v1", hit.text());
        assertEquals(hex("SHA-1", "v1".getBytes()), hit.checksumHeader(),
                "the stored checksum goes out, so Maven skips its .sha1 request");
        assertEquals(0, headsOn(CENTRAL, JAR));
        assertEquals(0, hitsOn(CENTRAL, JAR));
        assertTrue(proxy.cacheStats.drain().contains("(1 served on a fresh confirmation)"));
    }

    @Test
    void hitSummaryIsLoggedOnceTheIntervalPasses() throws Exception {
        cacheJar(CENTRAL, JAR, "v1");
        var interval = proxy.cacheStatsIntervalMs;
        proxy.cacheStatsIntervalMs = 200;
        try {
            // Nothing is pending after reset(), so this hit schedules the summary
            get(CENTRAL, JAR);
            assertEquals(1, proxy.cacheStats.pending());
            await("the summary to drain the counts", () -> proxy.cacheStats.pending() == 0);
            // And the next hit schedules another one
            get(CENTRAL, JAR);
            await("the next summary", () -> proxy.cacheStats.pending() == 0);
        } finally {
            proxy.cacheStatsIntervalMs = interval;
        }
    }

    @Test
    void freshSidecarIsServedFromTheStore() throws Exception {
        cacheJar(CENTRAL, JAR, "v1");

        hits.clear();
        var sidecar = get(CENTRAL, JAR + ".sha1");
        assertEquals(200, sidecar.status());
        assertEquals(hex("SHA-1", "v1".getBytes()), sidecar.text());
        assertEquals(0, hitsOn(CENTRAL, JAR + ".sha1"));
        assertEquals(0, headsOn(CENTRAL, JAR));
    }

    @Test
    void staleHitIsServedAtOnceAndConfirmedInTheBackground() throws Exception {
        var stored = cacheJar(CENTRAL, JAR, "v1");
        confirmedAgo(stored, Duration.ofHours(3));

        hits.clear();
        // Answered later than a serve from disk takes. (The 1s read-idle timeout counts from the
        // connection's last read, so a delayed HEAD may be retried: count "at least", not "exactly".)
        headDelayMs = 300;
        var hit = get(CENTRAL, JAR);
        assertEquals("v1", hit.text());
        assertEquals(0, headsAnswered.get(), "served before its HEAD was answered");
        assertTrue(proxy.cacheStats.drain().contains("(1 served while confirming again)"));

        await("the confirmation to be renewed", () -> Duration.between(
                Files.getLastModifiedTime(stored).toInstant(), Instant.now()).toMinutes() < 1);
        var heads = headsOn(CENTRAL, JAR);
        assertTrue(heads >= 1);
        get(CENTRAL, JAR);
        assertEquals(heads, headsOn(CENTRAL, JAR), "renewed, so fresh again");
    }

    @Test
    void staleHitThatChangedIsEvictedForTheNextRequest() throws Exception {
        var stored = cacheJar(CENTRAL, JAR, "v1");
        confirmedAgo(stored, Duration.ofDays(2));

        publishJar(CENTRAL, JAR, "v2");
        assertEquals("v1", get(CENTRAL, JAR).text(), "the stale tier serves before it checks");
        await("the eviction", () -> !Files.exists(cached(CENTRAL, JAR)));
        assertEquals("v2", get(CENTRAL, JAR).text());
        awaitFile(cached(CENTRAL, JAR), "v2");
    }

    @Test
    void expiredHitIsConfirmedBeforeServing() throws Exception {
        var stored = cacheJar(CENTRAL, JAR, "v1");
        confirmedAgo(stored, Duration.ofDays(8));

        publishJar(CENTRAL, JAR, "v2");
        hits.clear();
        assertEquals("v2", get(CENTRAL, JAR).text(), "past max-stale the change is seen first");
        assertEquals(1, headsOn(CENTRAL, JAR));
        assertNull(proxy.cacheStats.drain(), "an evicted hit is a download, not a cache hit");
    }

    @Test
    void pluginPortalHitsAreAlwaysConfirmedFirst() throws Exception {
        // The Portal can delete a version, so a past confirmation never stands in for one
        cacheJar(PORTAL, PLUGIN_JAR, "p1");

        hits.clear();
        assertEquals("p1", get(PORTAL, PLUGIN_JAR).text());
        assertEquals(1, hitsOn(PORTAL, PLUGIN_JAR + ".sha1"));
        assertTrue(proxy.cacheStats.drain().contains("(1 confirmed first)"));
    }

    /** Cache the Gradle distribution under the default tiers; returns its stored checksum. */
    static Path cacheDist() throws Exception {
        proxy.artifactCacheTiers = ArtifactCacheTiers.DEFAULT;
        var zip = "gradle-zip".getBytes();
        routes.put(GRADLE + " " + DIST, new Reply(200, zip, null));
        var downloads = "https://" + GRADLE_DOWNLOADS + DIST + ".sha256";
        routes.put(GRADLE + " " + DIST + ".sha256", new Reply(301, null, downloads));
        routes.put(GRADLE_DOWNLOADS + " " + DIST + ".sha256", new Reply(200, hex("SHA-256", zip).getBytes(), null));
        get(GRADLE, DIST);
        var cached = Environment.gradleCacheDir().resolve("gradle-9.0-bin.zip");
        awaitFile(cached, "gradle-zip");
        var stored = Sidecar.SHA256.storedFile(cached);
        awaitSidecar(stored);
        return stored;
    }

    @Test
    void freshHitOnSidecarOnlyDomainAsksNothing() throws Exception {
        cacheDist();

        hits.clear();
        var hit = get(GRADLE, DIST);
        assertEquals("gradle-zip", hit.text());
        assertNull(hit.checksumHeader(), "Gradle sends no checksum header, so none is made up");
        assertEquals(0, hitsOn(GRADLE, DIST + ".sha256"));
        assertEquals(0, hitsOn(GRADLE_DOWNLOADS, DIST + ".sha256"));
    }

    @Test
    void staleSidecarOnlyDomainIsConfirmedInTheBackground() throws Exception {
        confirmedAgo(cacheDist(), Duration.ofHours(3));

        hits.clear();
        assertEquals("gradle-zip", get(GRADLE, DIST).text());
        await("the background .sha256 fetch", () -> hitsOn(GRADLE_DOWNLOADS, DIST + ".sha256") == 1);
    }

    @Test
    void inconclusiveBackgroundConfirmationExpiresTheCopy() throws Exception {
        var stored = cacheJar(CENTRAL, JAR, "v1");
        confirmedAgo(stored, Duration.ofHours(3));

        // A 403 says nothing about the artifact: neither renew nor evict, but stop trusting it
        routes.put(CENTRAL + " " + JAR, new Reply(403, null, null));
        assertEquals("v1", get(CENTRAL, JAR).text());
        await("the confirmation to expire",
                () -> Files.getLastModifiedTime(stored).toInstant().equals(Instant.EPOCH));
        assertTrue(Files.exists(cached(CENTRAL, JAR)), "not evicted");
    }

    @Test
    void backgroundConfirmationsPastTheLimitWaitTheirTurn() throws Exception {
        proxy.maxBackgroundConfirmations = 1;
        var jars = List.of(JAR, "/maven2/org/example/b/1.0/b-1.0.jar", "/maven2/org/example/c/1.0/c-1.0.jar");
        for (var jar : jars) confirmedAgo(cacheJar(CENTRAL, jar, jar), Duration.ofHours(3));

        hits.clear();
        headDelayMs = 200;
        for (var jar : jars) assertEquals(jar, get(CENTRAL, jar).text());
        for (var jar : jars) {
            await("the background HEAD of " + jar, () -> headsOn(CENTRAL, jar) >= 1);
        }
    }

    @Test
    void aClientsOwnSha1RequestDoesNotRenewTrustOnCentral() throws Exception {
        // On Central the checksum header outranks a separately uploaded .sha1, so a
        // matching .sha1 must not restart the window in which hits skip the HEAD
        var stored = cacheJar(CENTRAL, JAR, "v1");
        confirmedAgo(stored, Duration.ofDays(8));
        var before = Files.getLastModifiedTime(stored);

        assertEquals(200, get(CENTRAL, JAR + ".sha1").status());
        assertEquals(before, Files.getLastModifiedTime(stored));
    }

    @Test
    void staleHitWhileUpstreamIsUnreachableIsStillServed() throws Exception {
        var stored = cacheJar(CENTRAL, JAR, "v1");
        confirmedAgo(stored, Duration.ofHours(3));
        var before = Files.getLastModifiedTime(stored);

        offline();
        assertEquals("v1", get(CENTRAL, JAR).text());
        assertStaysPresent(cached(CENTRAL, JAR), "v1");
        assertEquals(before, Files.getLastModifiedTime(stored), "an unreachable upstream confirms nothing");
    }

    @Test
    void aPooledConnectionDoesNotEndTheBackoffUntilItAnswers() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        confirm();
        var pooled = lastHeadConnection;

        offline();
        assertEquals(MitmProxy.SidecarAnswer.UNREACHABLE, confirm());
        assertTrue(proxy.inBackoff(CENTRAL), "a refused connect starts the backoff");

        // Back to the address the pooled connection was made to: acquiring it needs no network I/O.
        // The read-idle timeout closes the stalled connection and the HEAD is retried on a new one,
        // possibly at once: the idle check ticks from the last read, not from the HEAD. So the
        // retry stalls too, or its answer would end the backoff before it is checked (#1005).
        online(CENTRAL);
        headsToStall.set(2);
        var stalled = confirmAsync();
        await("the HEAD to arrive", () -> stalledConnection != null);
        assertSame(pooled, stalledConnection, "the HEAD went out on the pooled connection");
        assertTrue(proxy.inBackoff(CENTRAL), "a pooled connection proves nothing until it answers");
        assertEquals(MitmProxy.SidecarAnswer.UNREACHABLE,
                stalled.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS));
        assertEquals(0, headsToStall.get(), "the HEAD was retried");
        assertNotSame(pooled, lastHeadConnection, "on another connection");
        assertTrue(proxy.inBackoff(CENTRAL), "nor does the retry's connection, until it answers");

        assertNotEquals(MitmProxy.SidecarAnswer.UNREACHABLE, confirm());
        assertFalse(proxy.inBackoff(CENTRAL), "an answer ends the backoff");
    }

    /** Confirm the cached JAR with Central's checksum header, as a hit does. */
    static MitmProxy.SidecarAnswer confirm() throws Exception {
        return confirmAsync().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    static Future<MitmProxy.SidecarAnswer> confirmAsync() {
        return onClientContext(() -> proxy.fetchChecksumHeader(CENTRAL, JAR, Revalidation.forDomain(CENTRAL)));
    }

    static <T> Future<T> onClientContext(java.util.function.Supplier<Future<T>> action) {
        var result = io.vertx.core.Promise.<T>promise();
        clientContext.runOnContext(v -> action.get().onComplete(result));
        return result.future();
    }

    @Test
    void centralChangeIsSeenBeforeServing() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        publishJar(CENTRAL, JAR, "v2");
        assertEquals("v2", get(CENTRAL, JAR).text(), "the stale copy is never served");
        awaitFile(cached(CENTRAL, JAR), "v2");
    }

    @Test
    void centralDeletionIsSeenBeforeServing() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        routes.clear();
        assertEquals(404, get(CENTRAL, JAR).status());
        assertFalse(Files.exists(cached(CENTRAL, JAR)));
    }

    @Test
    void centralWithoutChecksumHeaderFallsBackToSidecar() throws Exception {
        publish(CENTRAL, JAR, "v1", "SHA-1", ".sha1");
        var miss = get(CENTRAL, JAR);
        assertEquals("v1", miss.text());
        assertNull(miss.checksumHeader());
        awaitFile(cached(CENTRAL, JAR), "v1");
        assertEquals(1, hitsOn(CENTRAL, JAR + ".sha1"), "fetched once the download is done");

        hits.clear();
        var hit = get(CENTRAL, JAR);
        assertEquals("v1", hit.text());
        assertEquals(1, headsOn(CENTRAL, JAR));
        assertEquals(1, hitsOn(CENTRAL, JAR + ".sha1"));
        assertEquals(hex("SHA-1", "v1".getBytes()), hit.checksumHeader());
    }

    @Test
    void downloadNotMatchingItsChecksumHeaderIsServedButNotCached() throws Exception {
        routes.put(CENTRAL + " " + JAR, new Reply(200, "corrupt".getBytes(), null, hex("SHA-1", "v1".getBytes())));
        assertEquals("corrupt", get(CENTRAL, JAR).text(), "the client sees what upstream sent");
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void downloadNotMatchingItsChecksumIsServedButNotCached() throws Exception {
        routes.put(CENTRAL + " " + JAR, new Reply(200, "corrupt".getBytes(), null));
        routes.put(CENTRAL + " " + JAR + ".sha1", new Reply(200, hex("SHA-1", "v1".getBytes()).getBytes(), null));
        assertEquals("corrupt", get(CENTRAL, JAR).text(), "the client sees what upstream sent");
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    /** Text that does not repeat, so a resume from the wrong offset cannot look right. */
    static String randomText(int length) {
        var random = new java.util.Random(length);
        var text = new StringBuilder(length);
        for (int i = 0; i < length; i++) text.append((char) ('a' + random.nextInt(26)));
        return text.toString();
    }

    static final String ETAG = "\"v1\"";

    /** Publishes a jar, with a strong ETag, whose first download is cut off halfway. */
    static String publishCutJar(boolean byClosing) throws Exception {
        var content = randomText(128 * 1024);
        publishJar(CENTRAL, JAR, content);
        etag = ETAG;
        getsToCut.set(1);
        cutByClosing = byClosing;
        proxy.downloadIdleSeconds = 1;
        return content;
    }

    @Test
    void downloadThatStallsMidwayIsResumedWhereItStopped() throws Exception {
        // npm drops an optional platform package whose download breaks (#925)
        var content = publishCutJar(false);

        assertEquals(content, get(CENTRAL, JAR).text(), "the client sees one unbroken download");
        assertEquals(List.of("bytes=" + content.length() / 2 + "-"), rangesAsked);
        assertEquals(List.of(ETAG), ifRangesAsked, "only the same file may continue");
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void downloadThatKeepsStallingIsResumedAsLongAsItGetsFurther() throws Exception {
        var content = publishCutJar(false);
        // The first GET and the next four resumes each stall halfway through
        getsToCut.set(5);

        assertEquals(content, get(CENTRAL, JAR).text(), "the client sees one unbroken download");
        assertEquals(5, rangesAsked.size(), rangesAsked.toString());
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void downloadWhoseConnectionDropsMidwayIsResumedWhereItStopped() throws Exception {
        var content = publishCutJar(true);

        // From half, or from 0 when the close overtakes the bytes before the proxy writes them
        assertEquals(content, get(CENTRAL, JAR).text(), "the client sees one unbroken download");
        assertEquals(1, rangesAsked.size(), rangesAsked.toString());
        awaitFile(cached(CENTRAL, JAR), content);
    }

    /** A reset connection, or a 502 if nothing had been sent yet: never a body that looks whole. */
    static void assertDownloadFails() throws Exception {
        assertDownloadFails(20);
    }

    static void assertDownloadFails(int timeoutSeconds) throws Exception {
        try {
            var response = getAsync(CENTRAL, JAR).toCompletionStage().toCompletableFuture()
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            assertEquals(502, response.status(), "got " + response.body().length + " bytes");
        } catch (java.util.concurrent.ExecutionException e) {
            // reset mid-body
        }
    }

    @Test
    void downloadIsNotResumedFromAnUpstreamThatIgnoresRange() throws Exception {
        publishCutJar(true);
        honourRange = false;

        assertDownloadFails();
        assertEquals(1, rangesAsked.size(), rangesAsked.toString());
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void downloadOfAFileThatChangedIsNotSplicedOntoTheOldOne() throws Exception {
        publishCutJar(true);
        changeOnCut = true;

        assertDownloadFails();
        assertEquals(List.of(ETAG), ifRangesAsked);
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void downloadWithOnlyLastModifiedResumesAgainstIt() throws Exception {
        var content = publishCutJar(false);
        etag = null;
        lastModified = "Tue, 29 Sep 2026 08:10:59 GMT";
        date = "Wed, 30 Sep 2026 09:00:00 GMT";

        assertEquals(content, get(CENTRAL, JAR).text());
        assertEquals(List.of(lastModified), ifRangesAsked);
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void downloadWhoseLastModifiedIsNotAStrongValidatorIsNotResumed() throws Exception {
        // Modified in the second it was sent: a change later that second keeps the same date
        publishCutJar(true);
        etag = null;
        lastModified = "Wed, 30 Sep 2026 09:00:00 GMT";
        date = lastModified;

        assertDownloadFails();
        assertEquals(List.of(), rangesAsked, "not even tried");
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void downloadWithOnlyAWeakEtagIsNotResumed() throws Exception {
        // A server ignores a weak If-Range and answers the whole file, and with an ETag
        // present, Last-Modified may not stand in for it (RFC 9110 13.1.5)
        publishCutJar(true);
        etag = "W/\"v1\"";
        lastModified = "Tue, 29 Sep 2026 08:10:59 GMT";

        assertDownloadFails();
        assertEquals(List.of(), rangesAsked, "not even tried");
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void resumeAnsweredWithAServerErrorIsTriedAgain() throws Exception {
        // A stall is often an overloaded CDN, which is as likely to answer the Range with a 503
        var content = publishCutJar(false);
        rangesToRefuse.set(1);

        assertEquals(content, get(CENTRAL, JAR).text(), "the client sees one unbroken download");
        assertEquals(2, rangesAsked.size(), rangesAsked.toString());
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void rangeThatEndsShortIsResumedAgain() throws Exception {
        var content = publishCutJar(false);
        rangesToShorten.set(1);

        assertEquals(content, get(CENTRAL, JAR).text());
        assertEquals(2, rangesAsked.size(), rangesAsked.toString());
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void redirectedDownloadIsResumedAtItsTarget() throws Exception {
        var content = randomText(128 * 1024);
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var cdnPath = "/cdn/lib-1.0.jar";
        routes.put(CENTRAL + " " + JAR, new Reply(302, null, "https://" + CENTRAL + cdnPath));
        routes.put(CENTRAL + " " + cdnPath, new Reply(200, bytes, null, hex("SHA-1", bytes)));
        etag = ETAG;
        getsToCut.set(1);
        proxy.downloadIdleSeconds = 1;

        assertEquals(content, get(CENTRAL, JAR).text());
        assertEquals(1, hitsOn(CENTRAL, JAR), "the redirect is not followed again");
        assertEquals(2, hitsOn(CENTRAL, cdnPath));
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void relativeRedirectIsFollowedAndResumedOnTheHostItCameFrom() throws Exception {
        // A relative Location resolves against the request's authority: the domain and port the
        // request named, never the address it was sent to (requestWithAsyncDns pins an IP)
        var content = randomText(128 * 1024);
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var cdnPath = "/cdn/lib-1.0.jar";
        routes.put(CENTRAL + " " + JAR, new Reply(302, null, cdnPath));
        routes.put(CENTRAL + " " + cdnPath, new Reply(200, bytes, null, hex("SHA-1", bytes)));
        etag = ETAG;
        getsToCut.set(1);
        proxy.downloadIdleSeconds = 1;

        assertEquals(content, get(CENTRAL, JAR).text());
        assertEquals(1, hitsOn(CENTRAL, JAR));
        assertEquals(2, hitsOn(CENTRAL, cdnPath), "the resume goes to the redirect target's host");
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void downloadGoesOnIntoTheCacheWhenTheClientLeaves() throws Exception {
        var content = randomText(64 * 1024).repeat(256);
        publishJar(CENTRAL, JAR, content);

        sendAsync(CENTRAL, JAR, resp -> {
            var got = new java.util.concurrent.atomic.AtomicLong();
            resp.handler(b -> {
                if (got.addAndGet(b.length()) > 1_000_000) resp.request().connection().close();
            });
            return Future.succeededFuture();
        });

        // The next client, maybe the same one retrying, gets a hit
        await(JAR + " to be cached", () -> Files.isRegularFile(cached(CENTRAL, JAR)));
    }

    @Test
    void downloadWithoutAValidatorIsNotResumed() throws Exception {
        // Without If-Range, a file changed meanwhile would continue the old one's bytes
        publishCutJar(true);
        etag = null;

        assertDownloadFails();
        assertEquals(List.of(), rangesAsked, "not even tried");
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void downloadThatCannotBeResumedIsNotCutWhenItPauses() throws Exception {
        // Cutting it could only fail it; before resumes, it carried on after the pause
        var content = publishCutJar(false);
        etag = null;
        cutPauseMs = 1_500;

        assertEquals(content, get(CENTRAL, JAR).text());
        assertEquals(List.of(), rangesAsked);
        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void downloadThatCannotBeResumedIsCutAtTheClientsBudget() throws Exception {
        // A stall it never recovers from ends with a line, not a silent drop at 120s
        publishCutJar(false);
        etag = null;
        proxy.downloadIdleSeconds = 20;
        proxy.clientSilenceBudgetSeconds = 2;

        // A reset (bytes were sent) long before a stall check would come
        assertDownloadFails(8);
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void downloadGoesOnIntoTheCacheWhenTheClientLeftBeforeItsHead() throws Exception {
        // Left while the head was awaited: no budget for anyone, so the stall is resumed
        var content = publishCutJar(false);
        cutBeforeBody = true;
        // The head gets 1.5s of slack; after it, less of the budget is left than a resume needs
        nextGetDelayMs = 2_500;
        proxy.clientSilenceBudgetSeconds = 4;

        requestAsync(CENTRAL, JAR, req -> {
            req.send();
            vertx.setTimer(300, t -> req.connection().close());
            return Future.succeededFuture();
        });

        awaitFile(cached(CENTRAL, JAR), content);
    }

    @Test
    void connectCutShortByTheClientsBudgetDoesNotStartTheBackoff() throws Exception {
        // A listener whose accept queue is full drops further SYNs: the connect times out.
        // That is how Linux behaves; macOS completes or resets connections past the backlog, so
        // there the connect does not time out and this would be testing something else.
        assumeTrue(Platform.isLinux(),
                "needs Linux's full-accept-queue behaviour to make a connect time out");
        publishJar(CENTRAL, JAR, "v1");
        try (var blackHole = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var first = new java.net.Socket();
             var second = new java.net.Socket()) {
            first.connect(blackHole.getLocalSocketAddress());
            second.connect(blackHole.getLocalSocketAddress());
            proxy.overrideUpstream(CENTRAL, "127.0.0.1", blackHole.getLocalPort());
            proxy.clientSilenceBudgetSeconds = 2;

            assertEquals(502, get(CENTRAL, JAR).status());
            assertFalse(proxy.inBackoff(CENTRAL), "our own short timeout says nothing about the domain");
        }
    }

    @Test
    void clientThatStopsReadingIsNotTakenForAStalledUpstream() throws Exception {
        // Enough to fill every socket buffer between here and upstream, so upstream is paused
        var content = randomText(64 * 1024).repeat(256);
        publishJar(CENTRAL, JAR, content);
        etag = ETAG;
        // Not 1s: a pause of the whole JVM that long on a loaded runner would look like a stall
        proxy.downloadIdleSeconds = 2;

        var body = sendAsync(CENTRAL, JAR, resp -> {
            var received = io.vertx.core.Promise.<Buffer>promise();
            var buffer = Buffer.buffer(content.length());
            resp.pause();
            // Longer than two stall checks
            vertx.setTimer(4_500, t -> resp.resume());
            resp.handler(buffer::appendBuffer);
            resp.endHandler(end -> received.complete(buffer));
            resp.exceptionHandler(received::tryFail);
            return received.future();
        }).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(content.length(), body.length());
        assertEquals(hex("SHA-1", content.getBytes()), hex("SHA-1", body.getBytes()));
        assertEquals(List.of(), rangesAsked, "a healthy upstream was not reset");
    }

    @Test
    void headThatNeverComesIsAnErrorBeforeTheClientIsDropped() throws Exception {
        // Otherwise the MITM server's idle timeout drops the client, silently (#925)
        publishJar(CENTRAL, JAR, "v1");
        getsToIgnore.set(1);
        proxy.clientSilenceBudgetSeconds = 1;

        assertEquals(502, get(CENTRAL, JAR).status());
    }

    @Test
    void lateHeadWhoseBodyStallsIsAnsweredWithinTheClientsBudget() throws Exception {
        // The head takes most of the budget (an npm shasum lookup before it counts too), then
        // no body follows. A stall check a whole downloadIdleSeconds away came after the MITM
        // server had dropped the client, silently (#925)
        publishCutJar(false);
        cutBeforeBody = true;
        // The check lands where the budget leaves too little to resume, or a resume is refused:
        // a 502 either way, not a timing race between the two
        honourRange = false;
        proxy.downloadIdleSeconds = 20;
        nextGetDelayMs = 2_000;
        proxy.clientSilenceBudgetSeconds = 4;

        // What is under test is the timeout: the proxy answers before the MITM server would
        // drop the client (10s past the budget in production), not a stall check 20s away
        var response = getAsync(CENTRAL, JAR).toCompletionStage().toCompletableFuture().get(8, TimeUnit.SECONDS);
        assertEquals(502, response.status(), "an error the client retries");
        assertNull(response.checksumHeader(), "the error does not describe itself as the artifact");
    }

    @Test
    void artifactWithoutChecksumIsRelayedUncached() throws Exception {
        routes.put(CENTRAL + " " + JAR, new Reply(200, "v1".getBytes(), null));
        assertEquals("v1", get(CENTRAL, JAR).text());
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void changedChecksumEvictsAndNextRequestRefetches() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        publishJar(CENTRAL, JAR, "v2");
        assertEquals(hex("SHA-1", "v2".getBytes()), get(CENTRAL, JAR + ".sha1").text());
        assertFalse(Files.exists(cached(CENTRAL, JAR)));

        assertEquals("v2", get(CENTRAL, JAR).text());
        awaitFile(cached(CENTRAL, JAR), "v2");
    }

    @Test
    void withdrawnReleaseIsEvicted() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        routes.clear();
        assertEquals(404, get(CENTRAL, JAR + ".sha1").status());
        assertFalse(Files.exists(cached(CENTRAL, JAR)));
        assertEquals(404, get(CENTRAL, JAR).status());
    }

    @Test
    void sidecarsAreAlwaysFetchedFresh() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        hits.clear();

        get(CENTRAL, JAR + ".sha1");
        get(CENTRAL, JAR + ".sha1");
        assertEquals(2, hitsOn(CENTRAL, JAR + ".sha1"));
    }

    @Test
    void offlineServesCachedArtifactAndStoredSidecars() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        routes.put(CENTRAL + " " + JAR + ".md5", new Reply(200, hex("MD5", "v1".getBytes()).getBytes(), null));
        routes.put(CENTRAL + " " + JAR + ".asc", new Reply(200, "signature".getBytes(), null));
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        get(CENTRAL, JAR + ".md5");
        get(CENTRAL, JAR + ".asc");

        offline();
        assertEquals("v1", get(CENTRAL, JAR).text());
        var sha1 = get(CENTRAL, JAR + ".sha1");
        assertEquals(200, sha1.status());
        assertEquals(hex("SHA-1", "v1".getBytes()), sha1.text());
        assertEquals(hex("MD5", "v1".getBytes()), get(CENTRAL, JAR + ".md5").text());
        assertEquals("signature", get(CENTRAL, JAR + ".asc").text());
        assertEquals(502, get(CENTRAL, JAR + ".sha512").status(), "never stored, so nothing to serve");
    }

    @Test
    void backoffAnswersFromStoredCopiesWithoutRetrying() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        offline();
        get(CENTRAL, JAR + ".sha1");
        online(CENTRAL);
        hits.clear();
        publishJar(CENTRAL, JAR, "v2");

        assertEquals(hex("SHA-1", "v1".getBytes()), get(CENTRAL, JAR + ".sha1").text(),
                "within the backoff the stored copy answers");
        assertEquals("v1", get(CENTRAL, JAR).text());
        assertEquals(0, hitsOn(CENTRAL, JAR + ".sha1"));
        assertEquals(0, headsOn(CENTRAL, JAR));
    }

    @Test
    void backoffNeverTurnsAnAnswerableRequestIntoAnError() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        offline();
        get(CENTRAL, JAR + ".sha1");
        online(CENTRAL);

        // Nothing stored to fall back on, so upstream is asked despite the backoff
        var other = "/maven2/org/example/other/1.0/other-1.0.jar";
        publishJar(CENTRAL, other, "o1");
        assertEquals(hex("SHA-1", "o1".getBytes()), get(CENTRAL, other + ".sha1").text());

        // ...and that answer ended the backoff: the cached jar is confirmed again
        publishJar(CENTRAL, JAR, "v2");
        assertEquals("v2", get(CENTRAL, JAR).text());
    }

    @Test
    void unusableAnswerConfirmsNothing() throws Exception {
        publishJar(PORTAL, PLUGIN_JAR, "p1");
        get(PORTAL, PLUGIN_JAR);
        awaitFile(cached(PORTAL, PLUGIN_JAR), "p1");

        // Upstream is reachable but its answer cannot be used: not a reason to serve unconfirmed
        routes.put(PORTAL + " " + PLUGIN_JAR + ".sha1", new Reply(302, null, "ftp://" + PORTAL + "/x.sha1"));
        routes.put(PORTAL + " " + PLUGIN_JAR, new Reply(200, "p2".getBytes(), null));
        hits.clear();
        assertEquals("p2", get(PORTAL, PLUGIN_JAR).text());
        assertEquals(1, hitsOn(PORTAL, PLUGIN_JAR), "upstream answered, not the cache");

        // ...and it is not mistaken for an outage either
        routes.put(PORTAL + " " + PLUGIN_JAR + ".sha1", new Reply(200, hex("SHA-1", "p2".getBytes()).getBytes(), null));
        assertEquals(hex("SHA-1", "p2".getBytes()), get(PORTAL, PLUGIN_JAR + ".sha1").text());
    }

    @Test
    void centralSidecarThatContradictsTheHeaderDoesNotEvict() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        routes.put(CENTRAL + " " + JAR + ".asc", new Reply(200, "sig-1".getBytes(), null));
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        get(CENTRAL, JAR + ".asc");

        // An old artifact whose .sha1 file is wrong, while the server-computed header is right
        var wrong = hex("SHA-1", "something else".getBytes());
        routes.put(CENTRAL + " " + JAR + ".sha1", new Reply(200, wrong.getBytes(), null));
        assertEquals(wrong, get(CENTRAL, JAR + ".sha1").text(), "the client still sees upstream's file");
        assertTrue(Files.exists(cached(CENTRAL, JAR)), "the HEAD confirmed the artifact");

        // A re-signed artifact: the header still confirms it, so the new signature is kept
        routes.put(CENTRAL + " " + JAR + ".asc", new Reply(200, "sig-2".getBytes(), null));
        get(CENTRAL, JAR + ".asc");
        assertTrue(Files.exists(cached(CENTRAL, JAR)));
        assertEquals("sig-2", Files.readString(Sidecar.ASC.storedFile(cached(CENTRAL, JAR))));
    }

    @Test
    void requestWithQueryIsRelayedUncached() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        assertEquals("v1", get(CENTRAL, JAR + "?x=1").text());
        assertStaysAbsent(cached(CENTRAL, JAR));
    }

    @Test
    void backoffStillDownloadsWhatIsOnlyInM2() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        offline();
        get(CENTRAL, JAR + ".sha1");
        online(CENTRAL);

        var other = "/maven2/org/example/other/1.0/other-1.0.jar";
        publishJar(CENTRAL, other, "o1");
        var m2 = Environment.m2Repository().resolve("org/example/other/1.0/other-1.0.jar");
        Files.createDirectories(m2.getParent());
        Files.writeString(m2, "o1");
        assertEquals("o1", get(CENTRAL, other).text(), "a ~/.m2 copy is no fallback, so upstream is asked");
    }

    @Test
    void droppedConnectionServesTheCachedCopy() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        var jar = routes.get(CENTRAL + " " + JAR);
        routes.put(CENTRAL + " " + JAR, new Reply(DROP, null, null));
        hits.clear();
        assertEquals("v1", get(CENTRAL, JAR).text());
        assertEquals(2, headsOn(CENTRAL, JAR), "retried once before treating it as an outage");
        routes.put(CENTRAL + " " + JAR, jar);
    }

    @Test
    void throttledProbeServesTheCachedCopy() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        routes.put(CENTRAL + " " + JAR, new Reply(429, null, null));
        hits.clear();
        assertEquals("v1", get(CENTRAL, JAR).text());
        assertEquals(1, headsOn(CENTRAL, JAR));
        assertEquals(0, hitsOn(CENTRAL, JAR + ".sha1"), "a 429 is not answered by asking again");
        assertEquals(0, hitsOn(CENTRAL, JAR));
    }

    @Test
    void headerlessHeadNeverLetsTheSidecarEvictOnCentral() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");

        // An edge that omits the header, beside an old artifact's wrong .sha1 file
        routes.put(CENTRAL + " " + JAR, new Reply(200, "v1".getBytes(), null));
        routes.put(CENTRAL + " " + JAR + ".sha1", new Reply(200, hex("SHA-1", "wrong".getBytes()).getBytes(), null));
        assertEquals("v1", get(CENTRAL, JAR).text());
        assertStaysPresent(cached(CENTRAL, JAR), "v1");
    }

    @Test
    void withdrawnSignatureIsDroppedNotReplacedByTheErrorPage() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        routes.put(CENTRAL + " " + JAR + ".asc", new Reply(200, "sig".getBytes(), null));
        get(CENTRAL, JAR);
        awaitFile(cached(CENTRAL, JAR), "v1");
        get(CENTRAL, JAR + ".asc");
        var storedAsc = Sidecar.ASC.storedFile(cached(CENTRAL, JAR));
        assertTrue(Files.exists(storedAsc));

        routes.remove(CENTRAL + " " + JAR + ".asc");
        assertEquals(404, get(CENTRAL, JAR + ".asc").status());
        assertTrue(Files.exists(cached(CENTRAL, JAR)), "the HEAD still confirms the artifact");
        assertFalse(Files.exists(storedAsc), "offline, nothing upstream no longer has is served");
    }

    @Test
    void largeErrorPageIsStillThatError() throws Exception {
        routes.put(CENTRAL + " " + JAR + ".asc", new Reply(404, new byte[200 * 1024], null));
        assertEquals(404, get(CENTRAL, JAR + ".asc").status());
    }

    @Test
    void malformedBenchUpstreamIsRefused() {
        assertTrue(ProxyMain.applyBenchUpstream(proxy, "", ""), "unset means no override");
        assertFalse(ProxyMain.applyBenchUpstream(proxy, "repo1.maven.org", ""));
        assertFalse(ProxyMain.applyBenchUpstream(proxy, "repo1.maven.org=127.0.0.1", ""));
        assertFalse(ProxyMain.applyBenchUpstream(proxy, "repo1.maven.org=127.0.0.1:1,oops", ""));
    }

    @Test
    void redirectTargetsKeepTheRawUri() {
        assertEquals("https://h.example/a%20b/x.sha1?q=1",
                MitmProxy.redirectTarget("h.example", 443, "/a%20b/x", "x.sha1?q=1").toString());
        assertEquals("https://other.example/y", MitmProxy.redirectTarget("h.example", 443, "/a", "https://other.example/y").toString());
        assertEquals("https://h.example:8443/p/z", MitmProxy.redirectTarget("h.example", 8443, "/p/q", "z").toString());
        assertEquals("https://h.example/a", MitmProxy.redirectTarget("h.example", 443, "/a", "http://h.example/a").toString(),
                "upstream connections are TLS, so http is followed over https");
        assertNull(MitmProxy.redirectTarget("h.example", 443, "/a", "ftp://h.example/a"));
        assertNull(MitmProxy.redirectTarget("h.example", 443, "/a", "ht tp://bad"));
        assertEquals("https://cdn.example/b", MitmProxy.redirectTarget("h.example", 443, "/a|b{c}", "https://cdn.example/b").toString(),
                "an absolute Location does not depend on parsing the request URI");
    }

    @Test
    void pluginPortalHitsAreRevalidated() throws Exception {
        publishJar(PORTAL, PLUGIN_JAR, "p1");
        get(PORTAL, PLUGIN_JAR);
        awaitFile(cached(PORTAL, PLUGIN_JAR), "p1");

        hits.clear();
        assertEquals("p1", get(PORTAL, PLUGIN_JAR).text());
        assertEquals(1, hitsOn(PORTAL, PLUGIN_JAR + ".sha1"));
        assertEquals(0, hitsOn(PORTAL, PLUGIN_JAR));

        // Deleted and republished under the same number
        publishJar(PORTAL, PLUGIN_JAR, "p2");
        assertEquals("p2", get(PORTAL, PLUGIN_JAR).text());
        awaitFile(cached(PORTAL, PLUGIN_JAR), "p2");
    }

    @Test
    void pluginPortalDeletionIsSeenOnTheNextHit() throws Exception {
        publishJar(PORTAL, PLUGIN_JAR, "p1");
        get(PORTAL, PLUGIN_JAR);
        awaitFile(cached(PORTAL, PLUGIN_JAR), "p1");

        routes.clear();
        assertEquals(404, get(PORTAL, PLUGIN_JAR).status());
        assertFalse(Files.exists(cached(PORTAL, PLUGIN_JAR)));
    }

    @Test
    void pluginPortalHitIsServedWhenOffline() throws Exception {
        publishJar(PORTAL, PLUGIN_JAR, "p1");
        get(PORTAL, PLUGIN_JAR);
        awaitFile(cached(PORTAL, PLUGIN_JAR), "p1");

        offline();
        assertEquals("p1", get(PORTAL, PLUGIN_JAR).text());
    }

    @Test
    void gradleDistributionIsVerifiedThroughRedirectedChecksum() throws Exception {
        var zip = "gradle-zip".getBytes();
        routes.put(GRADLE + " " + DIST, new Reply(200, zip, null));
        var downloads = "https://" + GRADLE_DOWNLOADS + DIST + ".sha256";
        routes.put(GRADLE + " " + DIST + ".sha256", new Reply(301, null, downloads));
        routes.put(GRADLE_DOWNLOADS + " " + DIST + ".sha256", new Reply(200, hex("SHA-256", zip).getBytes(), null));

        assertEquals("gradle-zip", get(GRADLE, DIST).text());
        var cached = Environment.gradleCacheDir().resolve("gradle-9.0-bin.zip");
        awaitFile(cached, "gradle-zip");
        awaitSidecar(Sidecar.SHA256.storedFile(cached));

        hits.clear();
        assertEquals("gradle-zip", get(GRADLE, DIST).text());
        assertEquals(0, hitsOn(GRADLE, DIST));
        assertEquals(1, hitsOn(GRADLE_DOWNLOADS, DIST + ".sha256"), "the hit was confirmed first");
    }

    @Test
    void hostM2CopyIsImportedWhenItMatchesUpstream() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        var m2 = Environment.m2Repository().resolve("org/example/lib/1.0/lib-1.0.jar");
        Files.createDirectories(m2.getParent());
        Files.writeString(m2, "v1");

        var response = get(CENTRAL, JAR);
        assertEquals("v1", response.text());
        assertEquals(0, hitsOn(CENTRAL, JAR), "served from ~/.m2, not downloaded");
        assertEquals(1, headsOn(CENTRAL, JAR), "its checksum is needed before deciding");
        assertEquals(hex("SHA-1", "v1".getBytes()), response.checksumHeader());
        assertFalse(Files.isSameFile(m2, cached(CENTRAL, JAR)));
    }

    @Test
    void hostM2CopyDifferingFromUpstreamIsIgnored() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        var m2 = Environment.m2Repository().resolve("org/example/lib/1.0/lib-1.0.jar");
        Files.createDirectories(m2.getParent());
        Files.writeString(m2, "locally built");

        assertEquals("v1", get(CENTRAL, JAR).text());
        assertEquals(1, hitsOn(CENTRAL, JAR));
        awaitFile(cached(CENTRAL, JAR), "v1");
    }

    @Test
    void snapshotsAndMetadataAreNeverCached() throws Exception {
        var snapshot = "/maven2/org/example/lib/1.1-SNAPSHOT/lib-1.1-SNAPSHOT.jar";
        publishJar(CENTRAL, snapshot, "s");
        get(CENTRAL, snapshot);
        var metadata = "/maven2/org/example/lib/maven-metadata.xml";
        routes.put(CENTRAL + " " + metadata, new Reply(200, "<metadata/>".getBytes(), null));
        get(CENTRAL, metadata);

        assertStaysAbsent(cached(CENTRAL, snapshot));
        assertFalse(Files.exists(cached(CENTRAL, metadata)));
    }

    @Test
    void unverifiedLegacyCachesAreDeleted() throws Exception {
        for (var legacy : Environment.unverifiedLegacyCacheDirs()) {
            await(legacy + " to be deleted on start", () -> !Files.exists(legacy));
        }
    }
}
