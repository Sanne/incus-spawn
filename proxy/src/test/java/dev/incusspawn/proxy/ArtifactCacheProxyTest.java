package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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
    static HttpClient client;
    static int mitmPort;
    static int upstreamPort;

    record Reply(int status, byte[] body, String location, String checksumHeader) {
        Reply(int status, byte[] body, String location) {
            this(status, body, location, null);
        }
    }

    /** Keyed by "host path"; anything else is a 404. Hits are keyed by "METHOD host path". */
    static final Map<String, Reply> routes = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

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
        upstream = vertx.createHttpServer(new HttpServerOptions()
                .setSsl(true)
                .setKeyCertOptions(new PemKeyCertOptions()
                        .setCertValue(Buffer.buffer(DerEncoder.toPem("CERTIFICATE", leaf.cert().getEncoded())))
                        .setKeyValue(Buffer.buffer(DerEncoder.toPem("PRIVATE KEY", leaf.key().getEncoded())))));
        upstream.requestHandler(req -> {
            var host = req.authority().host();
            var key = host + " " + req.path();
            hits.computeIfAbsent(req.method() + " " + key, k -> new AtomicInteger()).incrementAndGet();
            var reply = routes.get(key);
            var resp = req.response();
            if (reply == null) {
                resp.setStatusCode(404).end("not found");
                return;
            }
            resp.setStatusCode(reply.status());
            if (reply.location() != null) resp.putHeader("Location", reply.location());
            if (reply.checksumHeader() != null) resp.putHeader("X-Checksum-SHA1", reply.checksumHeader());
            resp.end(Buffer.buffer(reply.body() == null ? new byte[0] : reply.body()));
        });
        upstreamPort = upstream.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();

        mitmPort = freePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, freePort(), "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", java.util.List.of()));
        proxy.upstreamTrustAll = true;
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
        routes.clear();
        hits.clear();
        online();
        for (var dir : new Path[] {Environment.mavenCacheDir(), Environment.gradleCacheDir(),
                Environment.m2Repository()}) {
            // user.home is JVM-global: never let a stray value point this at a real ~/.m2
            assertTrue(dir.startsWith(tempHome), dir + " is outside the test home");
            FileTrees.delete(dir);
        }
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
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                .setHost(host)
                .setPort(443)
                .setURI(path);
        return client.request(options)
                .compose(HttpClientRequest::send)
                .compose(resp -> resp.body().map(b -> new Response(resp.statusCode(), b.getBytes(),
                        resp.getHeader("X-Checksum-SHA1"))))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
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

    /** Commits happen after the response ends, on a worker thread. */
    static void awaitFile(Path file, String content) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (Files.isRegularFile(file) && Files.readString(file).equals(content)) return;
            Thread.sleep(25);
        }
        fail("Expected " + file + " to hold " + content);
    }

    static void assertStaysAbsent(Path file) throws Exception {
        Thread.sleep(300);
        assertFalse(Files.exists(file), file + " should not have been cached");
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // --- tests ---

    @Test
    void centralMissIsOneGetVerifiedAgainstItsOwnChecksumHeader() throws Exception {
        publishJar(CENTRAL, JAR, "v1");
        var miss = get(CENTRAL, JAR);
        assertEquals("v1", miss.text());
        assertEquals(hex("SHA-1", "v1".getBytes()), miss.checksumHeader(), "upstream's header is passed on");
        awaitFile(cached(CENTRAL, JAR), "v1");
        assertTrue(Files.exists(Sidecar.SHA1.storedFile(cached(CENTRAL, JAR))));
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
        routes.put(PORTAL + " " + PLUGIN_JAR + ".sha1", new Reply(302, null, "http://" + PORTAL + "/x.sha1"));
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
        assertNull(MitmProxy.redirectTarget("h.example", 443, "/a", "http://h.example/a"), "never downgrade to http");
        assertNull(MitmProxy.redirectTarget("h.example", 443, "/a", "ht tp://bad"));
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
        assertTrue(Files.exists(Sidecar.SHA256.storedFile(cached)));

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
            for (int i = 0; i < 200 && Files.exists(legacy); i++) Thread.sleep(25);
            assertFalse(Files.exists(legacy), legacy + " should have been deleted on start");
        }
    }
}
