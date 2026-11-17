package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Container image manifests through the proxy (#2306): a pull of a cached image asks the registry
 * only a HEAD, which Docker Hub does not count as a pull, and a public image's tag survives a
 * throttle. A stub stands in for a registry that, like ghcr.io and Docker Hub, wants a Bearer token
 * for every manifest, one an anonymous client gets for a public repository only.
 */
class ManifestCacheProxyTest {

    static final String REGISTRY = "ghcr.io";
    static final String TYPE = "application/vnd.oci.image.index.v1+json";
    /** The caller's own token: the registry lets it pull every repository. */
    static final String CALLER = "Bearer caller";
    static final String ANONYMOUS_TOKEN = "anonymous";

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static NetClient client;
    static int mitmPort;

    /** What each repository's tag {@code latest} is now. */
    static final Map<String, String> latest = new ConcurrentHashMap<>();
    /** Repositories an anonymous token may pull. */
    static final Set<String> publicRepositories = ConcurrentHashMap.newKeySet();
    /** Requests by "METHOD path Authorization". */
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** Every manifest request answers 429. */
    static volatile boolean throttled;
    /** A manifest by digest is answered with other bytes than the digest names. */
    static volatile boolean corrupt;

    static final AtomicInteger repositories = new AtomicInteger();
    String repository;

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        var ca = CertificateAuthority.loadOrCreate();
        vertx = Vertx.vertx();
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                new ProxyCredentials("", "", false, "", "", List.of()));
        proxy.trustUpstreamCertificate(SpawnConfig.configDir().resolve("ca.crt").toString());
        var registry = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, REGISTRY)))
                .requestHandler(ManifestCacheProxyTest::answer);
        ContainerTls.startInBackground(proxy);
        proxy.overrideUpstream(REGISTRY, "127.0.0.1", NpmCacheProxyTest.listen(registry));
        mitmPort = proxy.mitmPort();
        client = vertx.createNetClient(new NetClientOptions()
                .setSsl(true).setTrustAll(true).setHostnameVerificationAlgorithm(""));
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
        var dir = ManifestCache.dir();
        if (Files.exists(dir)) {
            try (var tree = Files.walk(dir)) {
                for (var file : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        // A repository of its own, so the proxy's memory of which ones it checked starts empty
        repository = "acme/app" + repositories.incrementAndGet();
        latest.put(repository, "{\"manifests\":[],\"n\":1}");
        hits.clear();
        throttled = false;
        corrupt = false;
    }

    static void answer(HttpServerRequest req) {
        var auth = req.getHeader("Authorization");
        hits.computeIfAbsent(req.method() + " " + req.path() + " " + auth, k -> new AtomicInteger()).incrementAndGet();
        var resp = req.response();
        if (req.path().equals("/token")) {
            var scope = req.getParam("scope");
            resp.putHeader("Content-Type", "application/json")
                    .end(scope != null && scope.endsWith(":pull") ? "{\"token\":\"" + ANONYMOUS_TOKEN + "\"}" : "{}");
            return;
        }
        var matcher = ManifestCache.MANIFEST_PATTERN.matcher(req.path());
        if (!matcher.matches()) {
            resp.setStatusCode(404).end();
            return;
        }
        var name = matcher.group(1);
        if (throttled) {
            resp.setStatusCode(429).putHeader("Retry-After", "3600").end("toomanyrequests");
            return;
        }
        var allowed = CALLER.equals(auth)
                || (("Bearer " + ANONYMOUS_TOKEN).equals(auth) && publicRepositories.contains(name));
        if (!allowed) {
            resp.setStatusCode(401).putHeader("WWW-Authenticate",
                    "Bearer realm=\"https://" + REGISTRY + "/token\",service=\"" + REGISTRY
                            + "\",scope=\"repository:" + name + ":pull\"").end("unauthorized");
            return;
        }
        var reference = matcher.group(2);
        var body = latest.get(name);
        if (reference.startsWith("sha256:") && !reference.equals(digestOf(body))) {
            resp.setStatusCode(404).end();
            return;
        }
        resp.putHeader("Content-Type", TYPE).putHeader("Docker-Content-Digest", digestOf(body));
        if (req.method() == HttpMethod.HEAD) {
            resp.putHeader("Content-Length", String.valueOf(body.length())).end();
        } else {
            resp.end(corrupt ? body + " " : body);
        }
    }

    /** The regression: a cached tag is confirmed with a HEAD, never fetched again, so no pull is counted. */
    @Test
    void cachedTagIsConfirmedWithAHeadAndServedFromTheCopy() throws Exception {
        var first = pull("latest", CALLER);
        assertOk(first, latest.get(repository));
        awaitCopy(latest.get(repository));

        var second = pull("latest", CALLER);

        assertOk(second, latest.get(repository));
        assertTrue(second.contains("Docker-Content-Digest: " + digestOf(latest.get(repository))), second);
        assertEquals(1, hits("GET", "latest", CALLER), "The cached tag was fetched again: a counted pull");
        assertEquals(1, hits("HEAD", "latest", CALLER), "The cached tag was not confirmed");
    }

    /** A digest is confirmed with a HEAD too, never fetched again. */
    @Test
    void cachedDigestIsConfirmedWithAHeadAndServedFromTheCopy() throws Exception {
        var digest = digestOf(latest.get(repository));
        assertOk(pull(digest, CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));

        assertOk(pull(digest, CALLER), latest.get(repository));

        assertEquals(1, hits("GET", digest, CALLER), "The cached digest was fetched again: a counted pull");
        assertEquals(1, hits("HEAD", digest, CALLER), "The cached digest was not confirmed");
    }

    /**
     * A digest is no proof of access: one is often published (an image pinned in a chart), so a
     * caller the registry refuses gets the refusal, not another caller's copy.
     */
    @Test
    void callerTheRegistryRefusesGetsItsRefusalNotTheCopyOfADigest() throws Exception {
        var digest = digestOf(latest.get(repository));
        assertOk(pull(digest, CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));

        var refused = pull(digest, null);

        assertTrue(refused.startsWith("HTTP/1.1 401"), refused);
    }

    /** While the registry throttles, a public repository's digest is served from its copy. */
    @Test
    void throttledPublicDigestIsServedUnconfirmed() throws Exception {
        publicRepositories.add(repository);
        var digest = digestOf(latest.get(repository));
        assertOk(pull(digest, CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));
        await(() -> ManifestCache.isPublic(REGISTRY, repository), "The repository was never found public");
        throttled = true;

        assertOk(pull(digest, CALLER), latest.get(repository));
    }

    /**
     * Copies are shared by digest, but a throttled public repository's name does not serve a digest
     * it never served: that one could be a private image's, cached by another caller's pull.
     */
    @Test
    void throttledPublicRepositoryDoesNotServeAnotherRepositorysDigest() throws Exception {
        var privateDigest = digestOf(latest.get(repository));
        assertOk(pull(privateDigest, CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));
        var privateRepository = repository;
        repository = "acme/public" + repositories.incrementAndGet();
        latest.put(repository, "{\"manifests\":[],\"public\":true}");
        publicRepositories.add(repository);
        assertOk(pull("latest", CALLER), latest.get(repository));
        await(() -> ManifestCache.isPublic(REGISTRY, repository), "The repository was never found public");
        throttled = true;

        var answer = pull(privateDigest, CALLER);

        assertTrue(answer.startsWith("HTTP/1.1 429"), answer);
        assertNotEquals(privateRepository, repository);
    }

    /** A tag that moved upstream is fetched again: the HEAD names a digest the cache does not have. */
    @Test
    void movedTagIsFetchedAgain() throws Exception {
        assertOk(pull("latest", CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));
        latest.put(repository, "{\"manifests\":[],\"n\":2}");

        assertOk(pull("latest", CALLER), latest.get(repository));

        assertEquals(2, hits("GET", "latest", CALLER));
        // Committed before the next test empties the cache under it
        awaitCopy(latest.get(repository));
    }

    /** A caller the registry refuses gets the registry's refusal, never the copy another caller's pull left. */
    @Test
    void callerTheRegistryRefusesGetsItsRefusalNotTheCopy() throws Exception {
        assertOk(pull("latest", CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));

        var refused = pull("latest", null);

        assertTrue(refused.startsWith("HTTP/1.1 401"), refused);
        assertTrue(refused.contains("WWW-Authenticate: Bearer realm="), refused);
    }

    /** While the registry throttles, a public repository's tag is served at the digest it last had. */
    @Test
    void throttledPublicTagIsServedUnconfirmed() throws Exception {
        publicRepositories.add(repository);
        assertOk(pull("latest", CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));
        await(() -> ManifestCache.isPublic(REGISTRY, repository), "The repository was never found public");
        throttled = true;

        assertOk(pull("latest", CALLER), latest.get(repository));
    }

    /** A private repository's tag is not: the caller might not be allowed to pull it, and nobody can ask. */
    @Test
    void throttledPrivateTagIsNotServed() throws Exception {
        assertOk(pull("latest", CALLER), latest.get(repository));
        awaitCopy(latest.get(repository));
        // The anonymous check's last step: a HEAD with the anonymous token, refused
        var digest = digestOf(latest.get(repository));
        await(() -> hits("HEAD", digest, "Bearer " + ANONYMOUS_TOKEN) > 0, "The repository was never checked");
        throttled = true;

        var answer = pull("latest", CALLER);

        assertTrue(answer.startsWith("HTTP/1.1 429"), answer);
        assertFalse(ManifestCache.isPublic(REGISTRY, repository));
    }

    /** Bytes that do not match their digest reach the client, which checks them, but never the cache. */
    @Test
    void manifestNotMatchingItsDigestIsNotCached() throws Exception {
        corrupt = true;
        var digest = digestOf(latest.get(repository));

        assertTrue(pull(digest, CALLER).startsWith("HTTP/1.1 200"));
        corrupt = false;
        assertOk(pull(digest, CALLER), latest.get(repository));

        assertEquals(2, hits("GET", digest, CALLER));
        // Committed before the next test empties the cache under it
        awaitCopy(latest.get(repository));
    }

    @Test
    void tokenRealmMustBeAnHttpsRegistry() {
        var name = "library/alpine";
        assertEquals("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull",
                String.valueOf(ManifestCache.tokenUri("Bearer realm=\"https://auth.docker.io/token\",service=\"registry.docker.io\","
                        + "scope=\"repository:library/alpine:push,pull\"", name, ProxyConfig.REGISTRY_DOMAINS)));
        assertNull(ManifestCache.tokenUri("Bearer realm=\"https://example.com/token\"", name, ProxyConfig.REGISTRY_DOMAINS));
        assertNull(ManifestCache.tokenUri("Bearer realm=\"http://auth.docker.io/token\"", name, ProxyConfig.REGISTRY_DOMAINS));
        assertNull(ManifestCache.tokenUri("Bearer realm=\"https://auth.docker.io:8443/token\"", name, ProxyConfig.REGISTRY_DOMAINS));
        assertNull(ManifestCache.tokenUri("Basic realm=\"https://auth.docker.io/token\"", name, ProxyConfig.REGISTRY_DOMAINS));
    }

    String pull(String reference, String authorization) throws Exception {
        var head = new StringBuilder("GET /v2/" + repository + "/manifests/" + reference + " HTTP/1.1\r\nHost: " + REGISTRY + "\r\n");
        head.append("Accept: ").append(TYPE).append("\r\n");
        if (authorization != null) head.append("Authorization: ").append(authorization).append("\r\n");
        return exchange(Buffer.buffer(head.append("Connection: close\r\n\r\n").toString()));
    }

    int hits(String method, String reference, String authorization) {
        var path = reference.startsWith("/") ? reference : "/v2/" + repository + "/manifests/" + reference;
        var counter = hits.get(method + " " + path + " " + authorization);
        return counter == null ? 0 : counter.get();
    }

    static void assertOk(String answer, String body) {
        assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
        assertTrue(answer.contains("Content-Type: " + TYPE), answer);
        assertTrue(answer.endsWith("\r\n\r\n" + body), answer);
    }

    static void awaitCopy(String body) throws InterruptedException {
        var digest = digestOf(body);
        await(() -> ManifestCache.storedType(digest) != null, "The manifest was never cached");
    }

    static void await(BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(10);
        }
    }

    static String digestOf(String body) {
        return "sha256:" + StaleUpstreamConnectionTest.sha256Hex(body);
    }

    static String exchange(Buffer request) throws Exception {
        var socket = client.connect(mitmPort, "127.0.0.1", REGISTRY)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var answer = new CompletableFuture<String>();
        var bytes = Buffer.buffer();
        socket.handler(bytes::appendBuffer);
        socket.closeHandler(v -> answer.complete(bytes.toString(StandardCharsets.UTF_8)));
        socket.write(request);
        try {
            return answer.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            return fail("The proxy did not close the connection within 10s");
        }
    }
}
