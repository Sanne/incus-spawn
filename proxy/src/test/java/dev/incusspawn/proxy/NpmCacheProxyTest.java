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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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

        mitmPort = WebSocketProxyTest.findFreePort();
        proxy = new MitmProxy(vertx, "127.0.0.1", mitmPort, WebSocketProxyTest.findFreePort(), "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", List.of()));
        // Upstream is verified as in production, trusting the mock's CA besides the system's
        proxy.trustUpstreamCertificate(SpawnConfig.configDir().resolve("ca.crt").toString());
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
        stalledLookup = null;
        unavailable.clear();
        unavailableStatus = 503;
        unavailableDelayMillis = 0;
        // Answered now, so a stall cannot outlive its test on the shared probe connection
        stalled.forEach(req -> req.response().setStatusCode(404).end());
        stalled.clear();
        synchronized (gathered) {
            gathered.clear();
        }
        assertTrue(ProxyMain.applyBenchUpstream(proxy, REGISTRY + "=127.0.0.1:" + registryPort, ""));
    }

    /** Publish {@code name@1.0.0}: its tarball, and the version document carrying its shasum. */
    static String publish(String name) throws Exception {
        var tarball = ("tarball of " + name).getBytes(StandardCharsets.UTF_8);
        var shasum = ArtifactCacheProxyTest.hex("SHA-1", tarball);
        var tarballPath = "/" + name + "/-/" + name + "-1.0.0.tgz";
        routes.put(tarballPath, tarball);
        routes.put("/" + name + "/1.0.0", ("{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"dist\":{\"shasum\":\""
                + shasum + "\"}}").getBytes(StandardCharsets.UTF_8));
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

    static void awaitCached(String tarballPath) throws Exception {
        var file = cached(tarballPath);
        ArtifactCacheProxyTest.await(file + " to be cached", () -> Files.isRegularFile(file));
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

    @Test
    void slowServerErrorIsNotAskedAgainWhenTheClientHasWaitedTooLong() throws Exception {
        // Half of a 2s budget gone on the first answer: a second lookup could leave the tarball's head no time
        var path = publish("slow");
        unavailable.put("/slow/1.0.0", new AtomicInteger(1));
        unavailableDelayMillis = 1200;
        var budget = proxy.clientSilenceBudgetSeconds;
        proxy.clientSilenceBudgetSeconds = 2;
        try {
            assertEquals(new Response(200, "tarball of slow"), get(path, 10));
        } finally {
            proxy.clientSilenceBudgetSeconds = budget;
        }

        assertEquals(1, hits.get("/slow/1.0.0").get());
    }
}
