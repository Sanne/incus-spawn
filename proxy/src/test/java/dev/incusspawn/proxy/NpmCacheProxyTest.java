package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The npm tarball cache end to end: a client talking to the proxy, the proxy talking to
 * a mock registry over TLS that it verifies like the real one (#960).
 */
class NpmCacheProxyTest {

    static final String REGISTRY = "registry.npmjs.org";
    static final int BURST = 4;

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static HttpServer registry;
    /** Presents a certificate for another name, so a client that checks hostnames refuses it. */
    static HttpServer impostor;
    static HttpClient client;
    static io.vertx.core.Context clientContext;
    static int mitmPort;
    static int registryPort;
    static int impostorPort;
    /** Where {@link #offline} points the registry, picked once every listener is up. */
    static InetSocketAddress refused;

    /** Keyed by path; anything else is a 404. */
    static final Map<String, byte[]> routes = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** Version lookups held until this many are waiting, then all answered: only concurrent ones get through. */
    static volatile int lookupsToGather;
    static final List<HttpServerRequest> gathered = new ArrayList<>();
    /** Version lookups to this path are not answered within the test, like a connection that died silently. */
    static volatile String stalledLookup;
    static final List<HttpServerRequest> stalled = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Per path, how many more requests the registry answers with a 503, like an edge having a bad moment. */
    static final Map<String, AtomicInteger> unavailable = new ConcurrentHashMap<>();
    /** The status those answers carry, and how long each takes. */
    static volatile int unavailableStatus;
    static volatile long unavailableDelayMillis;

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        var ca = CertificateAuthority.loadOrCreate();
        vertx = Vertx.vertx();
        // Offers h2 like the real registry does
        registry = vertx.createHttpServer(new HttpServerOptions().setSsl(true).setUseAlpn(true)
                        .setKeyCertOptions(keyCert(ca, REGISTRY)))
                .requestHandler(NpmCacheProxyTest::answer);
        registryPort = listen(registry);
        impostor = vertx.createHttpServer(new HttpServerOptions().setSsl(true).setUseAlpn(true)
                        .setKeyCertOptions(keyCert(ca, "example.org")))
                .requestHandler(NpmCacheProxyTest::answer);
        impostorPort = listen(impostor);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", List.of()));
        // Upstream is verified as in production, trusting the mock's CA besides the system's
        proxy.trustUpstreamCertificate(SpawnConfig.configDir().resolve("ca.crt").toString());
        ContainerTls.startInBackground(proxy);
        mitmPort = proxy.mitmPort();
        refused = ArtifactCacheProxyTest.refusedLoopback(Set.of(registryPort, impostorPort, mitmPort, proxy.healthPort()));

        clientContext = vertx.getOrCreateContext();
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false),
                new io.vertx.core.http.PoolOptions().setHttp1MaxSize(16));
    }

    static PemKeyCertOptions keyCert(CertificateAuthority ca, String name) throws Exception {
        var leaf = ca.generateDomainCert(name);
        return new PemKeyCertOptions()
                .setCertValue(Buffer.buffer(DerEncoder.toPem("CERTIFICATE", leaf.cert().getEncoded())))
                .setKeyValue(Buffer.buffer(DerEncoder.toPem("PRIVATE KEY", leaf.key().getEncoded())));
    }

    static int listen(HttpServer server) throws Exception {
        return server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS).actualPort();
    }

    static void answer(HttpServerRequest req) {
        var path = req.path();
        hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        if (path.equals(stalledLookup)) {
            stalled.add(req);
            // The drain may have ended the stall between the check and the add, and missed this one
            if (!path.equals(stalledLookup)) release(req);
            return;
        }
        var failures = unavailable.get(path);
        if (failures != null && failures.getAndDecrement() > 0) {
            if (unavailableDelayMillis > 0) {
                vertx.setTimer(unavailableDelayMillis, t -> req.response().setStatusCode(unavailableStatus).end("unavailable"));
            } else {
                req.response().setStatusCode(unavailableStatus).end("unavailable");
            }
            return;
        }
        if (!path.contains("/-/") && lookupsToGather > 0) {
            synchronized (gathered) {
                gathered.add(req);
                if (gathered.size() < lookupsToGather) return;
                gathered.forEach(NpmCacheProxyTest::reply);
                gathered.clear();
            }
            return;
        }
        reply(req);
    }

    /** Answers a held lookup, once: whichever of the drain and {@link #answer} removes it. */
    static void release(HttpServerRequest req) {
        // A test may have closed or answered it already
        if (stalled.remove(req) && !req.response().closed() && !req.response().ended()) {
            req.response().setStatusCode(404).end();
        }
    }

    static void reply(HttpServerRequest req) {
        var body = routes.get(req.path());
        if (body == null) {
            req.response().setStatusCode(404).end("not found");
        } else {
            req.response().putHeader("Content-Type", "application/json").end(Buffer.buffer(body));
        }
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
    void reset() {
        routes.clear();
        hits.clear();
        lookupsToGather = 0;
        unavailable.clear();
        unavailableStatus = 503;
        unavailableDelayMillis = 0;
        synchronized (gathered) {
            gathered.clear();
        }
        online();
        proxy.upstream.clearUnreachable();
    }

    /**
     * A test's own await can end before its confirmation leaves the set, which the next test reads (#1496):
     * drained here, against this test's own fixture, and charged to it if it never ends. Held lookups are
     * answered before the wait, so a stall cannot outlive its test on the shared probe connection, and a test that
     * fails while one is held reports that failure, not this drain's timeout as well.
     */
    @AfterEach
    void drainBackgroundConfirmations() throws Exception {
        // Stopped before the answers: a lookup already past the check is answered by answer() itself
        stalledLookup = null;
        stalled.forEach(NpmCacheProxyTest::release);
        ArtifactCacheProxyTest.awaitBackgroundConfirmations(proxy);
    }

    static void online() {
        assertTrue(ProxyMain.applyBenchUpstream(proxy, REGISTRY + "=127.0.0.1:" + registryPort, ""));
    }

    /** Point the registry at a port nothing listens on, so every connect is refused. */
    static void offline() {
        proxy.overrideUpstream(REGISTRY, refused.getHostString(), refused.getPort());
    }

    /** Publish {@code name@1.0.0}: its tarball, and the version document carrying its shasum and integrity. */
    static String publish(String name) throws Exception {
        return publish(name, "tarball of " + name);
    }

    /** {@link #publish(String)} with the tarball's contents, so a test can republish it. */
    static String publish(String name, String contents) throws Exception {
        var tarball = contents.getBytes(StandardCharsets.UTF_8);
        var shasum = ArtifactCacheProxyTest.hex("SHA-1", tarball);
        var integrity = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tarball));
        var tarballPath = "/" + name + "/-/" + name + "-1.0.0.tgz";
        routes.put(tarballPath, tarball);
        routes.put("/" + name + "/1.0.0", ("{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"dist\":{\"shasum\":\""
                + shasum + "\",\"integrity\":\"" + integrity + "\"}}").getBytes(StandardCharsets.UTF_8));
        return tarballPath;
    }

    static Path cached(String tarballPath) {
        return Environment.npmCacheDir().resolve(tarballPath.substring(1));
    }

    record Response(int status, String text) {}

    static Future<Response> getAsync(String path) {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                .setHost(REGISTRY)
                .setPort(443)
                .setURI(path);
        var result = io.vertx.core.Promise.<Response>promise();
        clientContext.runOnContext(v -> client.request(options)
                .compose(req -> req.send())
                .compose(resp -> resp.body().map(b -> new Response(resp.statusCode(), b.toString(StandardCharsets.UTF_8))))
                .onComplete(result));
        return result.future();
    }

    static Response get(String path, long seconds) throws Exception {
        return getAsync(path).toCompletionStage().toCompletableFuture().get(seconds, TimeUnit.SECONDS);
    }

    /** Its digest is the last file a commit writes, after the tarball and its ETag. */
    static void awaitCached(String tarballPath) throws Exception {
        var file = cached(tarballPath);
        var shasum = Path.of(file + ".shasum");
        ArtifactCacheProxyTest.await(file + " to be cached", () -> Files.isRegularFile(shasum));
    }

    @Test
    void coldTarballsLookUpTheirShasumsConcurrently() throws Exception {
        // The registry answers no version lookup until all of them are waiting at once
        lookupsToGather = BURST;
        var paths = new ArrayList<String>();
        var responses = new ArrayList<Future<Response>>();
        for (int i = 0; i < BURST; i++) {
            var path = publish("burst" + i);
            paths.add(path);
            responses.add(getAsync(path));
        }
        Future.all(responses).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        for (int i = 0; i < BURST; i++) {
            var name = "burst" + i;
            assertEquals(new Response(200, "tarball of " + name), responses.get(i).result());
            assertEquals(1, hits.getOrDefault("/" + name + "/1.0.0", new AtomicInteger()).get(),
                    "the shasum of " + name + " is looked up at the registry");
            awaitCached(paths.get(i));
        }
    }

    @Test
    void stalledLookupHoldsUpNoOtherTarball() throws Exception {
        var stalled = publish("stalled");
        stalledLookup = "/stalled/1.0.0";
        getAsync(stalled);
        ArtifactCacheProxyTest.await("the stalled lookup to reach the registry",
                () -> hits.containsKey(stalledLookup));

        var other = publish("other");
        assertEquals(new Response(200, "tarball of other"), get(other, 5));
        awaitCached(other);
    }

    @Test
    void lookupRefusesAnUpstreamCertifiedForAnotherName() throws Exception {
        var path = publish("impostor");
        proxy.overrideUpstream(REGISTRY, "127.0.0.1", impostorPort);

        get(path, 20);

        assertEquals(0, hits.getOrDefault("/impostor/1.0.0", new AtomicInteger()).get(),
                "no request reaches a server whose certificate names another host");
        assertFalse(Files.exists(cached(path)));
    }

    @Test
    void lookupAnsweredWithAServerErrorIsAskedAgain() throws Exception {
        // One bad answer used to relay the tarball uncached, where a stalled body cannot be resumed (#925)
        var path = publish("flaky");
        unavailable.put("/flaky/1.0.0", new AtomicInteger(1));

        assertEquals(new Response(200, "tarball of flaky"), get(path, 10));

        assertEquals(2, hits.get("/flaky/1.0.0").get(), "the lookup is asked again after its 503");
        awaitCached(path);
    }

    @Test
    void lookupThatKeepsFailingIsAskedOnceMoreThenRelayed() throws Exception {
        var path = publish("down");
        unavailable.put("/down/1.0.0", new AtomicInteger(Integer.MAX_VALUE));

        assertEquals(new Response(200, "tarball of down"), get(path, 10));

        assertEquals(2, hits.get("/down/1.0.0").get(), "a registry that keeps failing is not hammered");
        assertFalse(Files.exists(cached(path)), "nothing unverified is cached");
    }

    @Test
    void lookupThatIsThrottledIsNotAskedAgainAtOnce() throws Exception {
        var path = publish("throttled");
        unavailable.put("/throttled/1.0.0", new AtomicInteger(1));
        unavailableStatus = 429;

        assertEquals(new Response(200, "tarball of throttled"), get(path, 10));

        assertEquals(1, hits.get("/throttled/1.0.0").get());
        assertFalse(Files.exists(cached(path)));
    }

    /** Fetch {@code name@1.0.0} through the proxy once, so its tarball is cached. */
    static String publishAndCache(String name) throws Exception {
        var path = publish(name);
        assertEquals(new Response(200, "tarball of " + name), get(path, 10));
        awaitCached(path);
        return path;
    }

    @Test
    void tarballIsVerifiedOnItsIntegrity() throws Exception {
        var path = publishAndCache("integrity");

        assertEquals("sha512:" + ArtifactCacheProxyTest.hex("SHA-512", "tarball of integrity".getBytes(StandardCharsets.UTF_8)),
                Files.readString(Path.of(cached(path) + ".shasum")), "the SHA-512 it was verified on, not the SHA-1");
    }

    @Test
    void unpublishedVersionIsNotServedFromCache() throws Exception {
        // A version removed upstream (unpublished, or taken down as malware) is gone for a direct fetch too
        var path = publishAndCache("unpublished");
        routes.clear();

        assertEquals(404, get(path, 10).status());

        assertFalse(Files.exists(cached(path)), "the removed version is evicted");
        assertFalse(Files.exists(Path.of(cached(path) + ".shasum")));
    }

    @Test
    void tarballFromALookupBeforeARepublishDoesNotReplaceTheRepublishedCopy() throws Exception {
        // Both downloads are held at the registry, each answered with the bytes its own lookup described.
        // The packument ETag stays the same throughout, as under npm ci (#1538)
        ArtifactCacheHandler.storePackageEtag("republished", "\"republished-etag\"");
        var path = publish("republished", "original");
        stalledLookup = path;
        var first = getAsync(path);
        ArtifactCacheProxyTest.await("the first download to be held", () -> stalled.size() == 1);
        var original = stalled.getFirst();

        // A second miss for the same tarball sees the republished digest, and is cached first
        publish("republished", "republished");
        var second = getAsync(path);
        ArtifactCacheProxyTest.await("the second download to be held", () -> stalled.size() == 2);
        answerHeld(stalled.getLast(), "republished");
        assertEquals(new Response(200, "republished"), second.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        awaitCached(path);

        // The first download then finishes, verified on the digest its own lookup found: its client
        // gets those bytes, but upstream confirmed the cached copy after that lookup, so it stays
        answerHeld(original, "original");
        assertEquals(new Response(200, "original"), first.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        ArtifactCacheProxyTest.await("the first download to be settled",
                () -> ArtifactCacheProxyTest.noDownloadInFlight(cached(path).getParent()));
        var digest = "sha512:" + ArtifactCacheProxyTest.hex("SHA-512", "republished".getBytes(StandardCharsets.UTF_8));
        assertEquals(digest, ArtifactCacheHandler.readFileOrNull(Path.of(cached(path) + ".shasum")));
        assertEquals("republished", Files.readString(cached(path)));

        // Within fresh and under the same ETag, the next request is served from the cache without a lookup
        stalledLookup = null;
        var lookups = hits.get("/republished/1.0.0").get();
        assertEquals(new Response(200, "republished"), get(path, 10), "what upstream publishes now");
        assertEquals(lookups, hits.get("/republished/1.0.0").get(), "served without a lookup");
    }

    /** Two cold misses for one tarball share its version lookup (#1567); the downloads are their own. */
    @Test
    void overlappingMissesForOneTarballShareOneLookup() throws Exception {
        var path = publish("joined");
        var lookup = "/joined/1.0.0";
        stalledLookup = lookup;
        var first = getAsync(path);
        ArtifactCacheProxyTest.await("the first lookup to be held", () -> stalled.size() == 1);
        var second = ArtifactCacheProxyTest.joinedOrAskedAgain(proxy.artifactCache.npmLookups,
                () -> getAsync(path), () -> stalled.size() == 2);
        stalledLookup = null;
        for (var held : stalled) answerHeld(held, new String(routes.get(lookup), StandardCharsets.UTF_8));

        assertEquals(new Response(200, "tarball of joined"), first.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(new Response(200, "tarball of joined"), second.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, hits.get(lookup).get(), "the second miss joined the first one's lookup");
        awaitCached(path);
        ArtifactCacheProxyTest.await("both downloads to be settled",
                () -> ArtifactCacheProxyTest.noDownloadInFlight(cached(path).getParent()));
    }

    /**
     * A 5xx retry is each caller's own, decided on its own silence budget: a caller whose budget is
     * spent does not retry, and one that joined its lookup but has budget left asks again (#1567).
     */
    @Test
    void aJoinedNpmLookupRetriesAFiveHundredOnItsOwnBudget() throws Exception {
        publish("retried");
        var lookup = "/retried/1.0.0";
        stalledLookup = lookup;
        var cache = proxy.artifactCache;
        var spent = System.nanoTime() - TimeUnit.SECONDS.toNanos(proxy.upstream.clientSilenceBudgetSeconds);
        var exhausted = cache.fetchNpmVersion(REGISTRY, "retried", "1.0.0", spent);
        ArtifactCacheProxyTest.await("the first lookup to be held", () -> stalled.size() == 1);
        var joined = cache.npmLookups.joined();
        var fresh = cache.fetchNpmVersion(REGISTRY, "retried", "1.0.0", System.nanoTime());
        assertEquals(joined + 1, cache.npmLookups.joined(), "the second caller joined the first one's ask");

        stalledLookup = null;
        var held = stalled.getFirst();
        assertTrue(stalled.remove(held));
        held.response().setStatusCode(503).end("unavailable");

        assertEquals(503, exhausted.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).answer().status(),
                "no budget left for a retry");
        assertEquals(200, fresh.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).answer().status(),
                "asked again on its own budget");
        assertEquals(2, hits.get(lookup).get());
    }

    /** Answer a held request with {@code body}, taking it from the drain. */
    static void answerHeld(HttpServerRequest req, String body) {
        assertTrue(stalled.remove(req));
        req.response().end(Buffer.buffer(body));
    }

    /**
     * Cache {@code name@1.0.0} under a stored packument ETag, as after an install that fetched
     * the packument, then age its last confirmation by {@code age}. The next install from a
     * lockfile (npm ci) fetches no packument, so that ETag stays the one the tarball was
     * verified under (#1466).
     */
    static String cacheUnderEtag(String name, Duration age) throws Exception {
        ArtifactCacheHandler.storePackageEtag(name, "\"" + name + "-etag\"");
        var path = publishAndCache(name);
        // Both sidecars are committed with the tarball, which publishAndCache waits for
        assertTrue(Files.isRegularFile(Path.of(cached(path) + ".etag")));
        ArtifactCacheProxyTest.confirmedAgo(Path.of(cached(path) + ".shasum"), age);
        return path;
    }

    @Test
    void freshlyConfirmedTarballIsServedWithoutALookup() throws Exception {
        var path = cacheUnderEtag("fresh", Duration.ofMinutes(5));
        routes.clear();

        assertEquals(new Response(200, "tarball of fresh"), get(path, 10));

        assertEquals(1, hits.get("/fresh/1.0.0").get(), "within fresh, the hit is not confirmed again");
    }

    @Test
    void lockfileInstallConfirmsAnExpiredTarballBeforeServingIt() throws Exception {
        // A version removed upstream while the packument ETag still vouches for its tarball
        var path = cacheUnderEtag("expired", Duration.ofDays(8));
        routes.clear();

        assertEquals(404, get(path, 10).status());

        assertFalse(Files.exists(cached(path)), "the removed version is evicted");
    }

    @Test
    void lockfileInstallConfirmsAStaleTarballInTheBackground() throws Exception {
        var path = cacheUnderEtag("stale", Duration.ofHours(3));
        routes.clear();

        assertEquals(new Response(200, "tarball of stale"), get(path, 10));

        ArtifactCacheProxyTest.await("the removed version to be evicted", () -> !Files.exists(cached(path)));
        assertEquals(404, get(path, 10).status(), "the next request gets upstream's answer");
    }

    @Test
    void backgroundConfirmationEvictsATarballChangedUpstream() throws Exception {
        var path = cacheUnderEtag("changed", Duration.ofHours(3));
        routes.put("/changed/1.0.0", "{\"dist\":{\"shasum\":\"%s\"}}".formatted("b".repeat(40))
                .getBytes(StandardCharsets.UTF_8));

        assertEquals(new Response(200, "tarball of changed"), get(path, 10));

        ArtifactCacheProxyTest.await("the changed version to be evicted", () -> !Files.exists(cached(path)));
        assertFalse(Files.exists(Path.of(cached(path) + ".shasum")), "no sidecar is left for a tarball not stored");
    }

    @Test
    void backgroundConfirmationRenewsAStaleTarball() throws Exception {
        var path = cacheUnderEtag("renewed", Duration.ofHours(3));
        var shasum = Path.of(cached(path) + ".shasum");

        assertEquals(new Response(200, "tarball of renewed"), get(path, 10));

        ArtifactCacheProxyTest.await("the confirmation to be renewed", () -> Files.getLastModifiedTime(shasum)
                .toInstant().isAfter(Instant.now().minus(Duration.ofMinutes(1))));
        assertEquals(new Response(200, "tarball of renewed"), get(path, 10));
        assertEquals(2, hits.get("/renewed/1.0.0").get(), "the renewed hit is fresh again");
        assertEquals(1, hits.get(path).get(), "the tarball is never fetched again");
    }

    @Test
    void registryInBackoffServesCachedTarballsWithoutALookup() throws Exception {
        var expired = cacheUnderEtag("backoff-expired", Duration.ofDays(8));
        var stale = cacheUnderEtag("backoff-stale", Duration.ofHours(3));

        offline();
        assertEquals(new Response(200, "tarball of backoff-expired"), get(expired, 10));
        assertTrue(proxy.artifactCache.inBackoff(REGISTRY), "a refused connect starts the backoff");

        // Reachable again, but the backoff is not over: neither hit waits for, or starts, a lookup
        online();
        assertEquals(new Response(200, "tarball of backoff-expired"), get(expired, 10));
        assertEquals(new Response(200, "tarball of backoff-stale"), get(stale, 10));
        // In a backoff the stale hit's confirmation is queued and done in one synchronous call, so its key is in
        // the set only while the serving callback runs: this await cannot see one that callback has yet to
        // queue, only one that asked the registry and is still waiting on it, which counts below (#1496)
        ArtifactCacheProxyTest.awaitBackgroundConfirmations(proxy);
        assertEquals(1, hits.get("/backoff-expired/1.0.0").get(), "the expired hit is served unconfirmed");
        assertEquals(1, hits.get("/backoff-stale/1.0.0").get(), "the stale hit is not confirmed in the background");
    }

    /** Ends with its confirmation's lookup still held: the drain answers it, rather than timing out on it. */
    @Test
    void aTestEndingWhileALookupIsHeldIsNotChargedTheDrain() throws Exception {
        var held = cacheUnderEtag("held-at-the-end", Duration.ofHours(3));
        stalledLookup = "/held-at-the-end/1.0.0";
        assertEquals(new Response(200, "tarball of held-at-the-end"), get(held, 10));
        ArtifactCacheProxyTest.await("the confirmation's lookup to be held", () -> stalled.size() == 1);
    }

    @Test
    void queuedConfirmationAsksNothingOnceABackoffBegan() throws Exception {
        var first = cacheUnderEtag("queued-first", Duration.ofHours(3));
        var second = cacheUnderEtag("queued-second", Duration.ofHours(3));
        var cap = proxy.artifactCache.maxBackgroundConfirmations;
        proxy.artifactCache.maxBackgroundConfirmations = 1;
        try {
            // The first confirmation's lookup is held, so the second waits past the cap
            stalledLookup = "/queued-first/1.0.0";
            assertEquals(new Response(200, "tarball of queued-first"), get(first, 10));
            ArtifactCacheProxyTest.await("the first confirmation's lookup", () -> stalled.size() == 1);
            assertEquals(new Response(200, "tarball of queued-second"), get(second, 10));
            ArtifactCacheProxyTest.await("the second to be queued", () -> proxy.artifactCache.backgroundConfirmations.size() == 2);

            // A backoff begins while it waits. The held lookup's connection then breaks (an answer would
            // end the backoff), and its retry's too, so the queue moves on with the backoff still on.
            proxy.upstream.unreachableSince.put(REGISTRY, System.nanoTime());
            ArtifactCacheProxyTest.await("the queue to drain", () -> {
                for (var req : stalled) {
                    stalled.remove(req);
                    req.connection().close();
                }
                return proxy.artifactCache.backgroundConfirmations.isEmpty();
            });
        } finally {
            proxy.artifactCache.maxBackgroundConfirmations = cap;
        }

        assertEquals(1, hits.get("/queued-second/1.0.0").get(), "the queued confirmation asks a registry in backoff nothing");
        assertTrue(proxy.artifactCache.inBackoff(REGISTRY), "and so gets no answer that would end it");
        assertTrue(Files.exists(cached(second)));
    }

    @Test
    void unreachableRegistryStillServesCache() throws Exception {
        var path = publishAndCache("offline");
        routes.remove(path);
        unavailable.put("/offline/1.0.0", new AtomicInteger(Integer.MAX_VALUE));

        assertEquals(new Response(200, "tarball of offline"), get(path, 10));

        assertEquals(1, hits.get(path).get(), "the cached copy is served, the tarball is not fetched again");
    }

    @Test
    void forbiddenLookupRelaysWithoutServingCache() throws Exception {
        var path = publishAndCache("forbidden");
        routes.put(path, "republished".getBytes(StandardCharsets.UTF_8));
        unavailable.put("/forbidden/1.0.0", new AtomicInteger(1));
        unavailableStatus = 403;

        assertEquals(new Response(200, "republished"), get(path, 10));

        assertEquals("tarball of forbidden", Files.readString(cached(path)),
                "an answer that confirms nothing evicts nothing");
    }

    @Test
    void unparseableVersionDocumentRelaysWithoutServingCache() throws Exception {
        var path = publishAndCache("garbled");
        routes.put(path, "republished".getBytes(StandardCharsets.UTF_8));
        routes.put("/garbled/1.0.0", "{not json".getBytes(StandardCharsets.UTF_8));

        assertEquals(new Response(200, "republished"), get(path, 10));

        assertEquals("tarball of garbled", Files.readString(cached(path)));
    }

    @Test
    void slowServerErrorIsNotAskedAgainWhenTheClientHasWaitedTooLong() throws Exception {
        // Half of a 2s budget gone on the first answer: a second lookup could leave the tarball's head no time
        var path = publish("slow");
        unavailable.put("/slow/1.0.0", new AtomicInteger(1));
        unavailableDelayMillis = 1200;
        var budget = proxy.upstream.clientSilenceBudgetSeconds;
        proxy.upstream.clientSilenceBudgetSeconds = 2;
        try {
            assertEquals(new Response(200, "tarball of slow"), get(path, 10));
        } finally {
            proxy.upstream.clientSilenceBudgetSeconds = budget;
        }

        assertEquals(1, hits.get("/slow/1.0.0").get());
    }

    /**
     * A tarball relayed uncached after its shasum lookup failed is cut at the client's silence budget
     * counted from its request, not from the relay's start (#1059): the lookup's wait was the
     * client's too, and the MITM server's idle timeout counts it.
     */
    @Test
    void relayAfterASlowLookupCountsTheLookupsWait() throws Exception {
        var path = publish("lookup-then-stall");
        unavailable.put("/lookup-then-stall/1.0.0", new AtomicInteger(1));
        unavailableStatus = 404;
        unavailableDelayMillis = 3000;
        stalledLookup = path;
        RelayRequestBodyTest.withBudget(proxy.upstream, 4, () -> {
            var start = System.nanoTime();
            var response = get(path, 10);
            var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(502, response.status(), response.text());
            // From the relay's start it would be 7s
            assertTrue(elapsed < 5500, "cut " + elapsed + "ms after the request, with a 4s budget");
        });
    }
}
