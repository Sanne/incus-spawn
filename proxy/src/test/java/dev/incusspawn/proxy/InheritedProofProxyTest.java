package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code <ns>=inherit} (#1110): an isx running inside an isx instance presents that instance's
 * proof for an instance of its own that inherits the namespace, in place of the instance's own
 * proof, so the outer proxy serves the parent's account. Any other instance is served as before.
 */
class InheritedProofProxyTest {

    private static final String API = "api.github.com";
    private static final String GIT = "github.com";
    /** The secret the outer isx gave the machine this proxy runs on. */
    private static final String PARENT_SECRET = "11".repeat(32);
    /** The secret this proxy's own isx gave the instance that asks. */
    private static final String INSTANCE_SECRET = "22".repeat(32);
    private static final String INSTANCE_PROOF = "gho_isx_" + ProofToken.derive(INSTANCE_SECRET, "github");
    private static final String PARENT_PROOF = "gho_isx_" + ProofToken.derive(PARENT_SECRET, "github");

    @TempDir
    static Path tempHome;
    static String origHome;
    static String origSecretFile;
    static Path secretFile;
    static Vertx vertx;
    static HttpServer apiUpstream;
    static HttpServer gitUpstream;
    static int apiPort;
    static int gitPort;
    static HttpClient client;
    /** The Authorization header each upstream path was sent with. */
    static final Map<String, String> received = new ConcurrentHashMap<>();
    /** What the registry lists: the instance at the test client's address. */
    static volatile String listing;

    MitmProxy proxy;

    @BeforeAll
    static void startUpstreams() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));
        origSecretFile = System.getProperty(InheritedProof.SECRET_FILE_PROPERTY);
        secretFile = tempHome.resolve("instance-secret");
        System.setProperty(InheritedProof.SECRET_FILE_PROPERTY, secretFile.toString());

        vertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(new AddressResolverOptions()
                .setHostsValue(Buffer.buffer("127.0.0.1 " + API + "\n"))));
        var ca = CertificateAuthority.loadOrCreate();
        apiUpstream = upstream(ca, API);
        apiUpstream.webSocketHandler(ws -> {
            var auth = ws.headers().get("Authorization");
            received.put("ws:" + ws.path(), auth == null ? "" : auth);
            ws.close();
        });
        apiPort = NpmCacheProxyTest.listen(apiUpstream);
        gitUpstream = upstream(ca, GIT);
        gitPort = NpmCacheProxyTest.listen(gitUpstream);
        client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true).setTrustAll(true).setVerifyHost(false).setKeepAlive(false));
    }

    private static HttpServer upstream(CertificateAuthority ca, String name) throws Exception {
        return vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, name)))
                .requestHandler(req -> {
                    var auth = req.getHeader("Authorization");
                    received.put(req.path(), auth == null ? "" : auth);
                    var other = req.getHeader("X-Other");
                    if (other != null) received.put(req.path() + "#X-Other", other);
                    req.response().end("{}");
                });
    }

    @AfterAll
    static void stopUpstreams() throws Exception {
        try {
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
            if (origSecretFile == null) System.clearProperty(InheritedProof.SECRET_FILE_PROPERTY);
            else System.setProperty(InheritedProof.SECRET_FILE_PROPERTY, origSecretFile);
        }
    }

    @BeforeEach
    void nested() throws Exception {
        Files.writeString(secretFile, PARENT_SECRET + "\n");
        received.clear();
    }

    @AfterEach
    void stopProxy() {
        if (proxy != null) proxy.stop();
    }

    /** A proxy serving {@code config}, with the test client's address listed as {@code accounts}. */
    private void serve(String config, String accounts) throws Exception {
        Files.writeString(tempHome.resolve(".config/incus-spawn/config.yaml"), config);
        listing = """
                [{"name":"inner-box","config":{"user.incus-spawn.static-ip":"127.0.0.1",
                "user.incus-spawn.instance-proof-sha256":%s%s}}]
                """.formatted(io.hyperfoil.tools.jjq.value.JqValues.toPrettyJsonString(io.hyperfoil.tools.jjq.value.JqString.of(ProofVerifier.stamp(INSTANCE_SECRET, ProofToken.declared()))), accounts);
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        proxy.upstreamWsPort = apiPort;
        ContainerTls.startInBackground(proxy);
        proxy.overrideUpstream(API, "127.0.0.1", apiPort);
        proxy.overrideUpstream(GIT, "127.0.0.1", gitPort);
        var registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return io.hyperfoil.tools.jjq.value.JqValues.parse(listing);
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);
    }

    private static final String INNER_ACCOUNT = """
            github:
              accounts:
                inner:
                  token: "ghp_inner"
              default: inner
            """;

    private static String pinned(String account) {
        return ",\"user.incus-spawn.account.github\":\"" + account + "\"";
    }

    /** Sends {@code authorization} to {@code host}{@code path} through the proxy: its status. */
    private int send(String host, String path, String authorization) throws Exception {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(host)
                .setPort(443)
                .setURI(path);
        return client.request(options)
                .compose(req -> req.putHeader("Authorization", authorization).send())
                .compose(resp -> resp.body().map(b -> resp.statusCode()))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anInstanceThatInheritsPresentsTheParentsProofInsteadOfItsOwn() throws Exception {
        serve(INNER_ACCOUNT, pinned("inherit"));

        assertEquals(200, send(API, "/user", "token " + INSTANCE_PROOF));
        assertEquals(200, send(GIT, "/org/repo.git/info/refs", basic("x-access-token", INSTANCE_PROOF)));

        // The same header and scheme, the parent's proof: what the parent's own gh would send
        assertEquals("token " + PARENT_PROOF, received.get("/user"));
        assertEquals(basic("x-access-token", PARENT_PROOF), received.get("/org/repo.git/info/refs"));
    }

    @Test
    void anInstanceThatInheritsByDefaultPresentsTheParentsProof() throws Exception {
        serve("""
                github:
                  default: inherit
                """, "");

        assertEquals(200, send(API, "/user", "Bearer " + INSTANCE_PROOF));
        // github.com is no domain of the defaults': this config holds no GitHub credential
        assertEquals(200, send(GIT, "/org/repo.git/info/refs", basic("x-access-token", INSTANCE_PROOF)));

        assertEquals("Bearer " + PARENT_PROOF, received.get("/user"));
        assertEquals(basic("x-access-token", PARENT_PROOF), received.get("/org/repo.git/info/refs"));
    }

    @Test
    void theParentsCurrentSecretIsTheOneProved() throws Exception {
        serve(INNER_ACCOUNT, pinned("inherit"));
        // The parent was started again: a new secret, with this proxy still running
        var rotated = "33".repeat(32);
        Files.writeString(secretFile, rotated + "\n");
        proxy.refreshNesting();  // as the config watcher does every second

        assertEquals(200, send(API, "/user", "token " + INSTANCE_PROOF));

        assertEquals("token gho_isx_" + ProofToken.derive(rotated, "github"), received.get("/user"));
    }

    @Test
    void aSecretOlderThanASecondIsReadAgainWithoutTheWatcher() throws Exception {
        serve(INNER_ACCOUNT, pinned("inherit"));
        var rotated = "44".repeat(32);
        Files.writeString(secretFile, rotated + "\n");
        Thread.sleep(1100);

        // The first request finds the secret old and has it read again in the background
        assertEquals(200, send(API, "/first", "token " + INSTANCE_PROOF));
        var expected = "token gho_isx_" + ProofToken.derive(rotated, "github");
        for (int i = 0; i < 50 && !expected.equals(received.get("/later")); i++) {
            Thread.sleep(20);
            assertEquals(200, send(API, "/later", "token " + INSTANCE_PROOF));
        }

        assertEquals(expected, received.get("/later"));
    }

    @Test
    void onlyAProofIsSwapped() throws Exception {
        serve(INNER_ACCOUNT, pinned("inherit"));

        // A static placeholder proves nothing, and a token of the caller's own stays theirs
        assertEquals(200, send(API, "/static", "token gho_placeholder"));
        assertEquals(200, send(API, "/own", "token gho_callersOwnToken"));

        assertEquals("token gho_placeholder", received.get("/static"));
        assertEquals("token gho_callersOwnToken", received.get("/own"));
    }

    /**
     * A namespace sharing the domain under another path scope, its proof in a header no carrier
     * reads: unproven, so it is never swapped for the parent's, which would hand this request the
     * parent's account for it (#1423).
     */
    @Test
    void anUnprovenNamespaceIsNotSwapped() throws Exception {
        var tool = Files.createDirectories(tempHome.resolve(".config/incus-spawn/tools")).resolve("extra.yaml");
        Files.writeString(tool, """
                name: extra
                env:
                  - name: EXTRA_TOKEN
                    value: extra-placeholder
                proxy:
                  config-namespace: extra
                  placeholders:
                    - env: EXTRA_TOKEN
                      prefix: extra-
                  configuration:
                    token:
                      config-path: token
                      description: "extra token"
                      secret: true
                  auth:
                    - domains: [%s]
                      paths: [/extra]
                      type: header
                      name: X-Extra-Key
                      value: "${token}"
                """.formatted(API));
        try {
            serve(INNER_ACCOUNT + "extra:\n  default: inherit\n", pinned("inherit"));
            var extraProof = "extra-isx_" + ProofToken.derive(INSTANCE_SECRET, "extra");
            var options = new RequestOptions()
                    .setMethod(HttpMethod.GET)
                    .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                    .setHost(API).setPort(443).setURI("/user");
            int status = client.request(options)
                    .compose(req -> req.putHeader("Authorization", "token " + INSTANCE_PROOF)
                            .putHeader("X-Other", extraProof).send())
                    .compose(resp -> resp.body().map(b -> resp.statusCode()))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(200, status);
            assertEquals("token " + PARENT_PROOF, received.get("/user"), "the proven namespace inherits");
            assertEquals(extraProof, received.get("/user#X-Other"), "the unproven one is left as it is");
        } finally {
            Files.delete(tool);
        }
    }

    @Test
    void aForgedProofCannotInheritEvenWhenTheParentSecretIsMissing() throws Exception {
        serve(INNER_ACCOUNT, pinned("inherit"));
        Files.delete(secretFile);
        proxy.refreshNesting();
        var forged = "token gho_isx_" + "00".repeat(16);
        assertEquals(200, send(API, "/forged", forged));
        assertEquals(forged, received.get("/forged"));
        var options = new WebSocketConnectOptions().setHost(API).setPort(proxy.mitmPort()).setSsl(true)
                .setURI("/forged").addHeader("Authorization", forged);
        var wsClient = vertx.createWebSocketClient(new io.vertx.core.http.WebSocketClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
        try {
            wsClient.connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 100 && !received.containsKey("ws:/forged"); i++) Thread.sleep(10);
            assertEquals(forged, received.get("ws:/forged"));
        } finally { wsClient.close(); }
    }

    @Test
    void anInstancePinnedToAnInnerAccountGetsThatAccountsToken() throws Exception {
        serve(INNER_ACCOUNT, pinned("inner"));

        assertEquals(200, send(API, "/user", "token " + INSTANCE_PROOF));

        assertEquals("Bearer ghp_inner", received.get("/user"));
    }

    @Test
    void anInstanceWithNoAccountKeepsItsOwnProof() throws Exception {
        serve("", "");

        assertEquals(200, send(API, "/user", "token " + INSTANCE_PROOF));

        assertEquals("token " + INSTANCE_PROOF, received.get("/user"));
    }

    @Test
    void outsideAnIsxInstanceInheritingIsRefused() throws Exception {
        serve(INNER_ACCOUNT, pinned("inherit"));
        Files.delete(secretFile);
        proxy.refreshNesting();

        // As for an account that is not configured: never the inner default instead
        assertEquals(502, send(API, "/user", "token " + INSTANCE_PROOF));
        assertEquals(null, received.get("/user"));
    }

    @Test
    void aWebSocketPresentsTheParentsProofToo() throws Exception {
        serve(INNER_ACCOUNT, pinned("inherit"));

        var options = new WebSocketConnectOptions()
                .setHost(API).setPort(proxy.mitmPort()).setSsl(true).setURI("/socket")
                .addHeader("Authorization", "token " + INSTANCE_PROOF);
        var wsClient = vertx.createWebSocketClient(new io.vertx.core.http.WebSocketClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
        try {
            wsClient.connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 100 && !received.containsKey("ws:/socket"); i++) Thread.sleep(50);
        } finally {
            wsClient.close();
        }

        assertEquals("token " + PARENT_PROOF, received.get("ws:/socket"));
    }

    @Test
    void becomingNestedIsAReasonToReload() throws Exception {
        Files.delete(secretFile);
        serve(INNER_ACCOUNT, "");
        assertEquals(false, proxy.refreshNesting());

        // The parent's isx delivered its secret after this proxy started, at boot
        Files.writeString(secretFile, PARENT_SECRET + "\n");

        assertEquals(true, proxy.refreshNesting());
        proxy.reload();
        assertEquals(false, proxy.refreshNesting());
    }
}
