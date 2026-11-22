package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.config.MavenMirrors;
import dev.incusspawn.config.MavenRepositories;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A mirror config.yaml names for Maven Central (#2304): asked first for an artifact the cache
 * does not hold, with Central asked when the mirror does not have it, and its copy confirmed with
 * Central at its next use.
 */
class MavenMirrorProxyTest {

    private static final String CENTRAL = "repo.maven.apache.org";
    private static final String MIRROR = "mirror.example.test";
    private static final String COORDINATES = "org/example/lib/1.0/lib-1.0.jar";
    private static final String JAR = "/maven2/" + COORDINATES;
    private static final String MIRRORED = "/central/maven2/" + COORDINATES;
    private static final String CONTENT = "central bytes";

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static HttpServer central;
    static HttpServer mirror;
    static int centralPort;
    static int mirrorPort;
    static HttpClient client;
    static MitmProxy proxy;
    /** "METHOD path" of every request each server received, and the mirror's Authorization headers. */
    static final List<String> atCentral = new CopyOnWriteArrayList<>();
    static final List<String> atMirror = new CopyOnWriteArrayList<>();
    static final List<String> mirrorAuthorization = new CopyOnWriteArrayList<>();
    /** What each server holds, by path: an artifact's own path, its sidecar's derived. */
    static final Map<String, String> centralHolds = new ConcurrentHashMap<>();
    static final Map<String, String> mirrorHolds = new ConcurrentHashMap<>();
    static volatile boolean centralThrottles;
    static volatile boolean mirrorFails;
    /** The mirror answers as Maven Central's edge does a throttled host: 404 with a Retry-After (#2422). */
    static volatile boolean mirrorThrottlesAsNotFound;
    /** The X-Checksum-SHA1 the proxy sent with its last answer. */
    static volatile String lastChecksumHeader;

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), """
                maven-mirrors:
                  - repository: https://%s/maven2/
                    mirror: https://%s/central/maven2/
                """.formatted(CENTRAL, MIRROR));

        vertx = Vertx.vertx();
        var ca = CertificateAuthority.loadOrCreate();
        central = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, CENTRAL)))
                .requestHandler(req -> {
                    atCentral.add(req.method() + " " + req.path());
                    if (centralThrottles) {
                        req.response().setStatusCode(429).putHeader("Retry-After", "3600").end();
                    } else {
                        // Central sends the artifact's SHA-1 with it
                        serve(req, centralHolds, true);
                    }
                });
        centralPort = NpmCacheProxyTest.listen(central);
        mirror = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, MIRROR)))
                .requestHandler(req -> {
                    atMirror.add(req.method() + " " + req.path());
                    var auth = req.getHeader("Authorization");
                    if (auth != null) mirrorAuthorization.add(auth);
                    if (mirrorFails) {
                        req.response().setStatusCode(503).end();
                        return;
                    }
                    if (mirrorThrottlesAsNotFound) {
                        req.response().setStatusCode(404).putHeader("Retry-After", "3600").end();
                        return;
                    }
                    // A storage bucket's mirror sends no checksum header: its .sha1 vouches for the transfer
                    serve(req, mirrorHolds, false);
                });
        mirrorPort = NpmCacheProxyTest.listen(mirror);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        ContainerTls.startInBackground(proxy);
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false));
    }

    /** Serves {@code holds}: an artifact, or the {@code .sha1} of one. */
    private static void serve(HttpServerRequest req, Map<String, String> holds, boolean checksumHeader) {
        var path = req.path();
        var sidecar = path.endsWith(".sha1");
        var content = holds.get(sidecar ? path.substring(0, path.length() - 5) : path);
        if (content == null) {
            req.response().setStatusCode(404).end();
        } else if (sidecar) {
            req.response().end(sha1(content));
        } else {
            if (checksumHeader) req.response().putHeader("X-Checksum-SHA1", sha1(content));
            req.response().end(req.method() == HttpMethod.HEAD ? "" : content);
        }
    }

    private static String sha1(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
    void reset() throws Exception {
        atCentral.clear();
        atMirror.clear();
        mirrorAuthorization.clear();
        centralHolds.clear();
        mirrorHolds.clear();
        centralThrottles = false;
        mirrorFails = false;
        mirrorThrottlesAsNotFound = false;
        proxy.upstream.clearBackoffs();
        proxy.artifactCache.mirrorBackoffs.clear();
        proxy.overrideUpstream(CENTRAL, "127.0.0.1", centralPort);
        proxy.overrideUpstream(MIRROR, "127.0.0.1", mirrorPort);
        var dir = Environment.mavenCacheDir();
        if (Files.isDirectory(dir)) {
            try (var files = Files.walk(dir)) {
                for (var file : files.filter(Files::isRegularFile).toList()) Files.delete(file);
            }
        }
    }

    private record Answer(int status, String body) {}

    private static Answer get() throws Exception {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(CENTRAL)
                .setPort(443)
                .setURI(JAR);
        return client.request(options)
                .compose(req -> req.putHeader("Authorization", "Bearer guest-own").send())
                .compose(resp -> {
                    lastChecksumHeader = resp.getHeader("X-Checksum-SHA1");
                    return resp.body().map(b -> new Answer(resp.statusCode(), b.toString(StandardCharsets.UTF_8)));
                })
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static Path copy() {
        return Environment.mavenCacheDir().resolve(CENTRAL).resolve(JAR.substring(1));
    }

    /** Waits for the copy and its stored checksum to be committed with {@code content}. */
    private static void awaitCopy(String content) throws Exception {
        var checksum = copy().resolveSibling(copy().getFileName() + ".sha1");
        dev.incusspawn.util.Eventually.await("the download to be cached", java.time.Duration.ofSeconds(5),
                () -> Files.isRegularFile(checksum) && Files.isRegularFile(copy())
                        && content.equals(Files.readString(copy())));
    }

    @Test
    void aMissIsFetchedFromTheMirrorAndConfirmedWithTheRepositoryAtItsNextUse() throws Exception {
        centralHolds.put(JAR, CONTENT);
        mirrorHolds.put(MIRRORED, CONTENT);

        assertEquals(new Answer(200, CONTENT), get());
        awaitCopy(CONTENT);
        assertEquals(List.of(), atCentral, "the miss never reaches Central");
        assertEquals(List.of("GET " + MIRRORED + ".sha1", "GET " + MIRRORED), atMirror);
        assertEquals(sha1(CONTENT), lastChecksumHeader,
                "the mirror's checksum goes out with the artifact, so Maven does not ask Central for the .sha1");
        assertEquals(List.of(), mirrorAuthorization, "nothing of the client's goes to the mirror");

        atMirror.clear();
        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of("HEAD " + JAR), atCentral, "the mirror's copy is confirmed by Central first");
        assertEquals(List.of(), atMirror);

        atCentral.clear();
        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of(), atCentral, "confirmed, the copy is trusted for Central's tiers");
    }

    @Test
    void aRequestRightAfterADownloadWaitsForItsCommitInsteadOfDownloadingAgain() throws Exception {
        centralHolds.put(JAR, CONTENT);
        mirrorHolds.put(MIRRORED, CONTENT);

        // The client is answered before its copy is committed; hold the commit back
        var second = new java.util.concurrent.CompletableFuture<Answer>();
        synchronized (VerifiedArtifactStore.lockFor(copy())) {
            assertEquals(new Answer(200, CONTENT), get());
            var asking = new Thread(() -> {
                try {
                    second.complete(get());
                } catch (Exception e) {
                    second.completeExceptionally(e);
                }
            });
            asking.setDaemon(true);
            asking.start();
            Thread.sleep(500);
            assertFalse(second.isDone(), "the second request waits for the first one's commit");
        }
        assertEquals(new Answer(200, CONTENT), second.get(20, TimeUnit.SECONDS));
        assertEquals(List.of("GET " + MIRRORED + ".sha1", "GET " + MIRRORED), atMirror,
                "the mirror is asked once, never again for a copy being committed");
        assertEquals(List.of("HEAD " + JAR), atCentral, "the second request confirms the copy with Central");
    }

    @Test
    void anArtifactTheMirrorDoesNotHaveIsFetchedFromTheRepository() throws Exception {
        centralHolds.put(JAR, CONTENT);

        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of("GET " + MIRRORED + ".sha1"), atMirror, "without its checksum, the artifact is not asked");
        assertEquals(List.of("GET " + JAR), atCentral);
        awaitCopy(CONTENT);
    }

    @Test
    void aMirrorsCopyTheRepositoryDisagreesWithIsEvictedAndFetchedFromTheRepository() throws Exception {
        centralHolds.put(JAR, CONTENT);
        mirrorHolds.put(MIRRORED, "other bytes");

        assertEquals(new Answer(200, "other bytes"), get());
        awaitCopy("other bytes");

        atMirror.clear();
        assertEquals(new Answer(200, CONTENT), get(), "Central's bytes, never the mirror's again");
        assertEquals(List.of("HEAD " + JAR, "GET " + JAR), atCentral);
        assertEquals(List.of(), atMirror, "after Central answered for the artifact, its mirror is not asked");
        awaitCopy(CONTENT);
    }

    @Test
    void aColdBuildWorksWhileTheRepositoryThrottles() throws Exception {
        centralThrottles = true;
        mirrorHolds.put(MIRRORED, CONTENT);

        assertEquals(new Answer(200, CONTENT), get());
        awaitCopy(CONTENT);
        assertEquals(List.of(), atCentral);

        // Central's 429 to the confirmation: the copy is served unconfirmed, as any copy is in a throttle
        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of("HEAD " + JAR), atCentral);
    }

    @Test
    void aFailingMirrorIsNotAskedAgainForAWhile() throws Exception {
        mirrorFails = true;
        centralHolds.put(JAR, CONTENT);

        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of("GET " + MIRRORED + ".sha1"), atMirror);
        assertEquals(List.of("GET " + JAR), atCentral);
        awaitCopy(CONTENT);

        // Another miss goes straight to Central while the mirror rests
        Files.delete(copy());
        Files.delete(copy().resolveSibling(copy().getFileName() + ".sha1"));
        atMirror.clear();
        atCentral.clear();
        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of(), atMirror, "a mirror that just failed is not asked again");
        assertEquals(List.of("GET " + JAR), atCentral);
        awaitCopy(CONTENT);
    }

    @Test
    void aMirrorAnsweringNotFoundWithRetryAfterRestsAsAThrottledOneDoes() throws Exception {
        mirrorThrottlesAsNotFound = true;
        centralHolds.put(JAR, CONTENT);

        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of("GET " + MIRRORED + ".sha1"), atMirror);
        awaitCopy(CONTENT);

        // A throttle, not a file the mirror lacks: the next miss does not ask it again
        Files.delete(copy());
        Files.delete(copy().resolveSibling(copy().getFileName() + ".sha1"));
        atMirror.clear();
        atCentral.clear();
        assertEquals(new Answer(200, CONTENT), get());
        assertEquals(List.of(), atMirror, "a mirror that just throttled is not asked again");
        assertEquals(List.of("GET " + JAR), atCentral);
        awaitCopy(CONTENT);
    }

    /** {@code repositories} with one mirror for the repository at {@code host} and {@code prefix}. */
    private static List<CachedRepository> withMirrors(String host, String prefix, List<String> warnings,
                                                      List<CachedRepository> repositories) {
        var entry = new MavenMirrors.Entry(new MavenRepositories.Url(host, prefix), new MavenRepositories.Url(MIRROR, "/m/"));
        return CachedRepository.withMirrors(repositories, List.of(entry), warnings::add);
    }

    @Test
    void aMirrorIsRefusedForARepositoryItCannotStandInFor() {
        var nexus = new CachedRepository("nexus.example.test", "/releases/", CachedRepository.Layout.MAVEN,
                Revalidation.HEAD_CHECKSUM.onlyConfirmed(), "nexus");
        var replacing = new CachedRepository("replacing.example.test", "/", CachedRepository.Layout.MAVEN,
                Revalidation.HEAD_CHECKSUM.onlyConfirmed(), null);
        var all = new ArrayList<>(CachedRepository.BUILT_IN);
        all.add(nexus);
        all.add(replacing);
        for (var refused : List.of(
                List.of("unknown.example.test", "/", "caches no repository"),
                List.of(CENTRAL, "/other/", "caches no repository"),
                List.of("nexus.example.test", "/releases/", "private"),
                List.of("replacing.example.test", "/", "replaces releases"),
                List.of("services.gradle.org", "/distributions/", "only a Maven repository"))) {
            var warnings = new ArrayList<String>();
            assertEquals(all, withMirrors(refused.get(0), refused.get(1), warnings, all), refused.toString());
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains(refused.get(2)), warnings.getFirst());
        }
    }

    @Test
    void aMirrorStandsInForTheOneRepositoryItNames() {
        var warnings = new ArrayList<String>();
        var all = withMirrors("repo1.maven.org", "/maven2/", warnings, CachedRepository.BUILT_IN);
        assertEquals(List.of(), warnings);
        var repo1 = all.stream().filter(r -> r.domain().equals("repo1.maven.org")).findFirst().orElseThrow();
        assertEquals(new MavenRepositories.Url(MIRROR, "/m/"), repo1.mirror());
        assertEquals(new CachedRepository.MirrorSource(MIRROR, "/m/org/a/1/a-1.jar"), repo1.mirrorOf("/maven2/org/a/1/a-1.jar"));
        assertNull(all.stream().filter(r -> r.domain().equals(CENTRAL)).findFirst().orElseThrow().mirror(),
                "each name of Central is a repository of its own");
    }

    @Test
    void theRulesSeeTheBuiltInRepositoriesAsTheProxyCachesThem() {
        // isx validate checks mirrors against CachedRepositoryRules.BUILT_IN, so it must be the proxy's own list
        assertEquals(CachedRepository.BUILT_IN.stream().map(CachedRepository::asCached).toList(),
                CachedRepositoryRules.BUILT_IN);
    }
}
