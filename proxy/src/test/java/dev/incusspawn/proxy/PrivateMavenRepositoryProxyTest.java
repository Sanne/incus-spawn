package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
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
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A private Maven repository declared in config.yaml (#2312): served from a cache of its own per
 * injected credential, and only once upstream has confirmed, with that credential, the copy to
 * serve. The guest holds a proof, never the repository's credential.
 */
class PrivateMavenRepositoryProxyTest {

    private static final String NEXUS = "nexus.example.test";
    private static final String JAR = "/repository/releases/org/example/lib/1.0/lib-1.0.jar";
    private static final String CONTENT = "private bytes";
    private static final String REAL = "Bearer nexus-real";
    private static final String MOVED = "/repository/releases/org/example/moved/1.0/moved-1.0.jar";

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static HttpServer upstream;
    static HttpClient client;
    static MitmProxy proxy;
    static int upstreamPort;
    /** "METHOD path Authorization" of every request upstream received. */
    static final List<String> received = new CopyOnWriteArrayList<>();
    /** The credential upstream accepts: the repository revokes an account by changing it. */
    static volatile String accepted = REAL;

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        var tools = Files.createDirectories(tempHome.resolve(".config/incus-spawn/tools"));
        Files.writeString(tools.resolve("nexus.yaml"), """
                name: nexus
                env:
                  - name: NEXUS_TOKEN
                    value: nexus-placeholder
                proxy:
                  config-namespace: nexus
                  placeholders:
                    - env: NEXUS_TOKEN
                      prefix: nexus-
                  configuration:
                    token:
                      config-path: token
                      description: "nexus token"
                      secret: true
                  auth:
                    - domains:
                        - %s
                      type: bearer
                      token: "${token}"
                """.formatted(NEXUS));
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), """
                nexus:
                  token: nexus-real
                maven-repositories:
                  - url: https://%s/repository/releases/
                    access: private
                    credentials: nexus
                    confirm: checksum-header
                    replaces-releases: false
                """.formatted(NEXUS));

        vertx = Vertx.vertx();
        var sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(CONTENT.getBytes(StandardCharsets.UTF_8)));
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), NEXUS)))
                .requestHandler(req -> {
                    var auth = req.getHeader("Authorization");
                    received.add(req.method() + " " + req.path() + " " + auth);
                    if (!accepted.equals(auth)) {
                        req.response().setStatusCode(401).end();
                    } else if (req.path().equals(MOVED)) {
                        req.response().setStatusCode(302).putHeader("Location", JAR).end();
                    } else if (req.path().equals(JAR)) {
                        req.response().putHeader("X-Checksum-SHA1", sha1)
                                .end(req.method() == HttpMethod.HEAD ? "" : CONTENT);
                    } else {
                        req.response().setStatusCode(404).end();
                    }
                });
        upstreamPort = NpmCacheProxyTest.listen(upstream);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        ContainerTls.startInBackground(proxy);
        ProofTestSupport.register(proxy);
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
        received.clear();
        accepted = REAL;
        proxy.overrideUpstream(NEXUS, "127.0.0.1", upstreamPort);
        var dir = ArtifactCacheHandler.privateCacheDir();
        if (Files.isDirectory(dir)) {
            try (var files = Files.walk(dir)) {
                for (var file : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals(".key")).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private record Answer(int status, String body) {}

    private static Answer get(String authorization) throws Exception {
        return get(JAR, authorization);
    }

    private static Answer get(String path, String authorization) throws Exception {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(NEXUS)
                .setPort(443)
                .setURI(path);
        return client.request(options)
                .compose(req -> req.putHeader("Authorization", authorization).send())
                .compose(resp -> resp.body().map(b -> new Answer(resp.statusCode(), b.toString(StandardCharsets.UTF_8))))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static String proof() {
        return "Bearer " + ProofTestSupport.token("nexus", "nexus-");
    }

    /** The private cache's copies of the jar, in whichever partition. */
    private static List<Path> cachedCopies() throws Exception {
        var dir = ArtifactCacheHandler.privateCacheDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.endsWith(Path.of(NEXUS).resolve(JAR.substring(1)))).toList();
        }
    }

    private static void awaitCached() throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cachedCopies().isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the download was never cached");
            Thread.sleep(10);
        }
    }

    @Test
    void aHitIsServedOnlyOnceUpstreamConfirmedItWithTheInjectedCredential() throws Exception {
        assertEquals(new Answer(200, CONTENT), get(proof()));
        awaitCached();
        assertEquals(List.of("GET " + JAR + " " + REAL), received, "the miss goes out with the real credential");
        var copy = cachedCopies().getFirst();
        assertFalse(copy.startsWith(Environment.mavenCacheDir().resolve(NEXUS)), "never in the shared public cache");

        received.clear();
        assertEquals(new Answer(200, CONTENT), get(proof()));
        assertEquals(List.of("HEAD " + JAR + " " + REAL), received, "one HEAD with the credential, the bytes from the cache");
    }

    @Test
    void aRevokedCredentialIsRefusedAtOnceThoughACopyIsCached() throws Exception {
        get(proof());
        awaitCached();

        accepted = "Bearer someone-else";
        received.clear();
        var answer = get(proof());
        assertEquals(401, answer.status(), "upstream's refusal, not the cached copy");
        assertNotEquals(CONTENT, answer.body());
    }

    @Test
    void nothingIsServedWhileUpstreamCannotBeAsked() throws Exception {
        get(proof());
        awaitCached();

        // A port taken and closed again at once, so a connect to it is refused
        var closed = new java.net.ServerSocket(0);
        int refusedPort = closed.getLocalPort();
        closed.close();
        proxy.overrideUpstream(NEXUS, "127.0.0.1", refusedPort);
        var answer = get(proof());
        assertNotEquals(200, answer.status(), "a private copy is never served unconfirmed, offline included");
        assertNotEquals(CONTENT, answer.body());
    }

    @Test
    void aRequestWithoutTheNamespacesProofNeverReachesThePrivateCache() throws Exception {
        accepted = "Bearer guest-own";
        assertEquals(new Answer(200, CONTENT), get("Bearer guest-own"));
        assertEquals(List.of(), cachedCopies(), "relayed with its own credential, never cached");
        assertEquals(List.of("GET " + JAR + " Bearer guest-own"), received);
    }

    @Test
    void aRedirectGoesToTheClientNeverFollowedWithoutTheCredential() throws Exception {
        var answer = get(MOVED, proof());
        assertEquals(302, answer.status(), "followed here, the next hop would go out with no credential");
        assertEquals(List.of("GET " + MOVED + " " + REAL), received);
    }
}
