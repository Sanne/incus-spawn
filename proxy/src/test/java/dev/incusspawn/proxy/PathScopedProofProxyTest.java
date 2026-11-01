package dev.incusspawn.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tools sharing a domain under different path scopes (#1423): a request needs the proof of the
 * entry its path selects, not of every namespace on the domain, and an entry is never injected
 * for a request that did not prove its own namespace.
 */
class PathScopedProofProxyTest {

    private static final String SHARED = "shared.example.test";
    /** Only an account the default is not resolves the scoped entry here: routing falls back. */
    private static final String FALLBACK = "fallback.example.test";

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static HttpServer upstream;
    static HttpClient client;
    static MitmProxy proxy;
    static final Map<String, String> received = new ConcurrentHashMap<>();
    static final Map<String, CompletableFuture<String>> receivedWs = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        var tools = Files.createDirectories(tempHome.resolve(".config/incus-spawn/tools"));
        Files.writeString(tools.resolve("docs.yaml"), scopedTool("docs", SHARED, "/mcp", "${token}"));
        Files.writeString(tools.resolve("cache.yaml"), scopedTool("cache", SHARED, "/cache", "${token}"));
        Files.writeString(tools.resolve("partial.yaml"), scopedTool("partial", FALLBACK, "/mcp", "${token}/${org}"));
        Files.writeString(tools.resolve("broad.yaml"), """
                name: broad
                env:
                  - name: BROAD_TOKEN
                    value: broad-placeholder
                proxy:
                  config-namespace: broad
                  placeholders:
                    - env: BROAD_TOKEN
                      prefix: broad-
                  configuration:
                    token:
                      config-path: token
                      description: "broad token"
                      secret: true
                  auth:
                    - domains:
                        - %s
                        - %s
                      type: bearer
                      token: "${token}"
                """.formatted(SHARED, FALLBACK));
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), """
                docs:
                  token: docs-real
                cache:
                  token: cache-real
                broad:
                  token: broad-real
                partial:
                  token: partial-real
                """);

        vertx = Vertx.vertx(new io.vertx.core.VertxOptions().setAddressResolverOptions(
                new io.vertx.core.dns.AddressResolverOptions().setHostsValue(io.vertx.core.buffer.Buffer.buffer(
                        "127.0.0.1 " + SHARED + " " + FALLBACK + "\n"))));
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), SHARED)))
                .requestHandler(req -> {
                    var auth = req.getHeader("Authorization");
                    received.put(req.authority().host() + req.path(), auth == null ? "" : auth);
                    req.response().end("{}");
                })
                .webSocketHandler(ws -> {
                    var auth = ws.headers().get("Authorization");
                    receivedWs.computeIfAbsent(ws.path(), p -> new CompletableFuture<>()).complete(auth == null ? "" : auth);
                    ws.close();
                });
        int upstreamPort = NpmCacheProxyTest.listen(upstream);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        proxy.upstreamWsPort = upstreamPort;
        ContainerTls.startInBackground(proxy);
        proxy.overrideUpstream(SHARED, "127.0.0.1", upstreamPort);
        proxy.overrideUpstream(FALLBACK, "127.0.0.1", upstreamPort);
        ProofTestSupport.register(proxy);
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false));
    }

    private static String scopedTool(String name, String domain, String path, String token) {
        return """
                name: %1$s
                env:
                  - name: %2$s_TOKEN
                    value: %1$s-placeholder
                proxy:
                  config-namespace: %1$s
                  placeholders:
                    - env: %2$s_TOKEN
                      prefix: %1$s-
                  configuration:
                    token:
                      config-path: token
                      description: "%1$s token"
                      secret: true
                    org:
                      config-path: org
                      description: "%1$s organisation"
                  auth:
                    - domains:
                        - %3$s
                      paths:
                        - %4$s
                      type: bearer
                      token: "%5$s"
                """.formatted(name, name.toUpperCase(java.util.Locale.ROOT), domain, path, token);
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

    /** What the upstream received in Authorization for a GET of {@code path} sent with {@code authorization}. */
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
        return received.get(domain + path.replaceFirst("\\?.*", ""));
    }

    @Test
    void aScopedEntryNeedsOnlyItsOwnProof() throws Exception {
        assertEquals("Bearer docs-real", send(SHARED, "/mcp", "Bearer " + proof("docs")));
        assertEquals("Bearer cache-real", send(SHARED, "/cache/blob", "Bearer " + proof("cache")));
    }

    @Test
    void anotherNamespacesProofInjectsNothing() throws Exception {
        var docs = "Bearer " + proof("docs");
        assertEquals(docs, send(SHARED, "/cache/other", docs), "relayed unchanged");
        assertEquals(docs, send(SHARED, "/elsewhere", docs), "the unscoped entry needs its own proof");
    }

    @Test
    void theUnscopedFallbackNeedsItsOwnProof() throws Exception {
        assertEquals("Bearer broad-real", send(SHARED, "/api", "Bearer " + proof("broad")));
        var partial = "Bearer " + proof("partial");
        // The default account cannot resolve 'partial': /mcp falls back to 'broad', unproven here
        assertEquals(partial, send(FALLBACK, "/mcp", partial), "relayed unchanged");
    }

    @Test
    void aDotSegmentNeverCarriesAScopedCredentialOutOfItsScope() throws Exception {
        var docs = "Bearer " + proof("docs");
        assertEquals(docs, send(SHARED, "/mcp/../cache/blob", docs), "relayed unchanged");
        assertEquals("Bearer broad-real", send(SHARED, "/mcp/../api", "Bearer " + proof("broad")),
                "the unscoped entry serves every path, whatever the upstream resolves it to");
    }

    @Test
    void aWebSocketNeedsOnlyItsEntrysProof() throws Exception {
        assertEquals("Bearer docs-real", connect("/mcp/ws", "Bearer " + proof("docs")));
        var docs = "Bearer " + proof("docs");
        assertEquals(docs, connect("/cache/ws", docs), "relayed unchanged");
    }

    private static String connect(String path, String authorization) throws Exception {
        var options = new WebSocketConnectOptions();
        options.setHost(SHARED).setPort(proxy.mitmPort()).setSsl(true).setURI(path);
        options.addHeader("Authorization", authorization);
        var ws = vertx.createWebSocketClient(new io.vertx.core.http.WebSocketClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false));
        try {
            ws.connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            return receivedWs.computeIfAbsent(path, p -> new CompletableFuture<>()).get(10, TimeUnit.SECONDS);
        } finally {
            ws.close();
        }
    }
}
