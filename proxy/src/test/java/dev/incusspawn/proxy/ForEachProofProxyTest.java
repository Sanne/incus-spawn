package dev.incusspawn.proxy;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A tool whose servers each have their own host and key in {@code config.yaml} ({@code for-each},
 * #1419): the real proxy injects each server's key on its own host alone, for a request proving
 * the tool's one namespace, and never on a host another tool already serves.
 */
class ForEachProofProxyTest {

    private static final String DEV = "dev.example.test";
    private static final String PROD = "prod.example.test";
    /** A domain another tool declares statically, which the configured list also names. */
    private static final String TAKEN = "taken.example.test";

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static HttpServer upstream;
    static HttpClient client;
    static MitmProxy proxy;
    static final Map<String, String> received = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        var tools = Files.createDirectories(tempHome.resolve(".config/incus-spawn/tools"));
        Files.writeString(tools.resolve("develocity.yaml"), ForEachResolutionTest.DEVELOCITY);
        Files.writeString(tools.resolve("other.yaml"), """
                name: other
                env:
                  - name: OTHER_TOKEN
                    value: other-placeholder
                proxy:
                  config-namespace: other
                  placeholders:
                    - env: OTHER_TOKEN
                      prefix: other-
                  configuration:
                    token:
                      config-path: token
                      description: "other token"
                      secret: true
                  auth:
                    - domains:
                        - %s
                      type: bearer
                      token: "${token}"
                """.formatted(TAKEN));
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), """
                develocity:
                  servers:
                    quarkus:
                      host: %s
                      accessKey: key-quarkus
                    commonhaus:
                      host: %s
                      accessKey: key-commonhaus
                    grabby:
                      host: %s
                      accessKey: key-grabby
                other:
                  token: other-real
                """.formatted(DEV, PROD, TAKEN));

        vertx = Vertx.vertx(new io.vertx.core.VertxOptions().setAddressResolverOptions(
                new io.vertx.core.dns.AddressResolverOptions().setHostsValue(io.vertx.core.buffer.Buffer.buffer(
                        "127.0.0.1 " + DEV + " " + PROD + " " + TAKEN + "\n"))));
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), DEV)))
                .requestHandler(req -> {
                    var auth = req.getHeader("Authorization");
                    received.put(req.authority().host() + req.path(), auth == null ? "" : auth);
                    req.response().end("{}");
                });
        int upstreamPort = NpmCacheProxyTest.listen(upstream);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        ContainerTls.startInBackground(proxy);
        for (var domain : new String[] {DEV, PROD, TAKEN}) proxy.overrideUpstream(domain, "127.0.0.1", upstreamPort);
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

    private static String proof(String namespace) {
        return ProofTestSupport.token(namespace, namespace + "-");
    }

    /** What the upstream received in Authorization for a GET of {@code path} on {@code domain}. */
    private static String send(String domain, String path, String authorization) throws Exception {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(domain)
                .setPort(443)
                .setURI(path);
        int status = client.request(options)
                .compose(req -> req.putHeader("Authorization", authorization).send())
                .compose(resp -> resp.body().map(b -> resp.statusCode()))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(200, status);
        return received.get(domain + path);
    }

    @Test
    void eachServerGetsItsOwnKeyAndNoOther() throws Exception {
        var develocity = "Bearer " + proof("develocity");
        assertEquals("Bearer key-quarkus", send(DEV, "/mcp", develocity));
        assertEquals("Bearer key-commonhaus", send(PROD, "/mcp", develocity));
    }

    @Test
    void outsideItsPathsOrWithoutTheProofNothingIsInjected() throws Exception {
        var develocity = "Bearer " + proof("develocity");
        assertEquals(develocity, send(DEV, "/api", develocity));
        assertEquals("Bearer guest-own", send(PROD, "/mcp", "Bearer guest-own"));
    }

    @Test
    void aServerNeverTakesOverAnotherToolsHost() throws Exception {
        var develocity = "Bearer " + proof("develocity");
        assertEquals(develocity, send(TAKEN, "/mcp", develocity), "relayed unchanged");
        assertEquals("Bearer other-real", send(TAKEN, "/other", "Bearer " + proof("other")));
    }
}
