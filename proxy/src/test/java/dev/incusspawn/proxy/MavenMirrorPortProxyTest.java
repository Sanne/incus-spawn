package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
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
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A mirror on a port other than 443, under a host name of one label (#2681), as a stock
 * Reposilite on a home server is: the proxy connects to that port, names it in {@code Host}, and
 * keeps two mirrors on one host apart when one of them fails. The mirrors are reached through the
 * resolver, never an override, which would replace the port as well as the address.
 */
class MavenMirrorPortProxyTest {

    private static final String CENTRAL = "repo.maven.apache.org";
    private static final String REPO1 = "repo1.maven.org";
    private static final String MIRROR = "localhost";
    private static final String COORDINATES = "org/example/lib/1.0/lib-1.0.jar";
    private static final String JAR = "/maven2/" + COORDINATES;
    private static final String CONTENT = "central bytes";

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static int centralPort;
    /** One mirror for each of Central's two names, on one host and two ports. */
    static int firstPort;
    static int secondPort;
    static HttpClient client;
    static MitmProxy proxy;
    /** "Host METHOD path" of every request each mirror received. */
    static final List<String> atFirst = new CopyOnWriteArrayList<>();
    static final List<String> atSecond = new CopyOnWriteArrayList<>();
    static final List<String> atCentral = new CopyOnWriteArrayList<>();
    static volatile boolean firstFails;

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        vertx = Vertx.vertx();
        var ca = CertificateAuthority.loadOrCreate();
        HttpServer central = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, CENTRAL)))
                .requestHandler(req -> {
                    atCentral.add(req.method() + " " + req.path());
                    serve(req, JAR);
                });
        centralPort = NpmCacheProxyTest.listen(central);
        firstPort = NpmCacheProxyTest.listen(mirror(ca, atFirst, true));
        secondPort = NpmCacheProxyTest.listen(mirror(ca, atSecond, false));

        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), """
                maven-mirrors:
                  - repository: https://%s/maven2/
                    mirror: https://%s:%d/central/
                  - repository: https://%s/maven2/
                    mirror: https://%s:%d/repo1/
                """.formatted(CENTRAL, MIRROR, firstPort, REPO1, MIRROR, secondPort));

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        ContainerTls.startInBackground(proxy);
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false));
    }

    private static HttpServer mirror(CertificateAuthority ca, List<String> seen, boolean first) throws Exception {
        return vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, MIRROR)))
                .requestHandler(req -> {
                    seen.add(req.getHeader("Host") + " " + req.method() + " " + req.path());
                    if (first && firstFails) {
                        req.response().setStatusCode(503).end();
                    } else {
                        serve(req, (first ? "/central/" : "/repo1/") + COORDINATES);
                    }
                });
    }

    /** Serves CONTENT at {@code artifact}, and its {@code .sha1} beside it. */
    private static void serve(HttpServerRequest req, String artifact) {
        if (req.path().equals(artifact + ".sha1")) {
            req.response().end(sha1(CONTENT));
        } else if (req.path().equals(artifact)) {
            req.response().putHeader("X-Checksum-SHA1", sha1(CONTENT)).end(req.method() == HttpMethod.HEAD ? "" : CONTENT);
        } else {
            req.response().setStatusCode(404).end();
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
        atFirst.clear();
        atSecond.clear();
        atCentral.clear();
        firstFails = false;
        proxy.upstream.clearBackoffs();
        proxy.artifactCache.mirrorBackoffs.clear();
        proxy.overrideUpstream(CENTRAL, "127.0.0.1", centralPort);
        proxy.overrideUpstream(REPO1, "127.0.0.1", centralPort);
        var dir = Environment.mavenCacheDir();
        if (Files.isDirectory(dir)) {
            try (var files = Files.walk(dir)) {
                for (var file : files.filter(Files::isRegularFile).toList()) Files.delete(file);
            }
        }
    }

    private static int get(String repository) throws Exception {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(repository)
                .setPort(443)
                .setURI(JAR);
        return client.request(options)
                .compose(req -> req.send())
                .compose(resp -> resp.body().map(b -> {
                    assertEquals(CONTENT, b.toString(StandardCharsets.UTF_8));
                    return resp.statusCode();
                }))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    @Test
    void aMirrorOnAnotherPortIsAskedThereAndNamedWithItsPort() throws Exception {
        assertEquals(200, get(CENTRAL));
        var host = MIRROR + ":" + firstPort;
        assertEquals(List.of(host + " GET /central/" + COORDINATES + ".sha1", host + " GET /central/" + COORDINATES), atFirst);
        assertEquals(List.of(), atCentral, "the mirror served the miss");
    }

    @Test
    void twoMirrorsOnOneHostRestApart() throws Exception {
        firstFails = true;
        assertEquals(200, get(CENTRAL));
        assertEquals(List.of("GET " + JAR), atCentral, "the failing mirror leaves the miss to its repository");
        assertTrue(proxy.artifactCache.mirrorBackoffs.containsKey(MIRROR + ":" + firstPort),
                proxy.artifactCache.mirrorBackoffs.toString());

        // The other port of the same host did not fail, so it is still asked
        atCentral.clear();
        assertEquals(200, get(REPO1));
        assertEquals(2, atSecond.size(), atSecond.toString());
        assertEquals(List.of(), atCentral);
    }
}
