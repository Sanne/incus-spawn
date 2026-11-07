package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code host-routes:} (#936): a name an instance reaches on 443 that the proxy forwards to a
 * loopback controller on the host, stamping who called. The controller is a stand-in that
 * records every request it gets, so these tests see exactly what crossed the route.
 */
class HostRouteProxyTest {

    private static final String SECRET = "b".repeat(64);
    private static final String INSTANCE = "agent-1";
    private static final String TEMPLATE = "tpl-agent";
    /** Admits {@link #TEMPLATE}. */
    private static final String WORK = "work.isx.internal";
    /** Admits only another template. */
    private static final String OTHER = "other.isx.internal";
    /** Admits {@link #TEMPLATE} among others. */
    private static final String OPEN = "open.isx.internal";
    /** Lists no templates: refused when the config is read, so never served. */
    private static final String UNFILTERED = "unfiltered.isx.internal";
    /** Its secret file is readable by others: claimed, and every request refused (503). */
    private static final String LEAKY = "leaky.isx.internal";

    @TempDir
    static Path home;
    static String origHome;
    static Vertx vertx;
    static Vertx clientVertx;
    static Context clientContext;
    static HttpClient client;
    static HttpServer controller;
    static MitmProxy proxy;
    static int port;
    static volatile String listing;
    static InstanceRegistry registry;

    record Seen(HttpMethod method, String uri, MultiMap headers, String body) {}

    static final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        var configDir = Files.createDirectories(home.resolve(".config/incus-spawn"));
        CertificateAuthority.loadOrCreate();

        vertx = Vertx.vertx();
        controller = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(body -> {
            seen.add(new Seen(req.method(), req.uri(), MultiMap.caseInsensitiveMultiMap().addAll(req.headers()),
                    body.toString()));
            // A careless controller that echoes the request's headers back, in its reason phrase too
            if (req.path().equals("/reason")) req.response().setStatusMessage("Bad " + req.getHeader(HostRouteSecret.HEADER));
            req.response().putHeader("X-Echo", "route secret " + req.getHeader(HostRouteSecret.HEADER))
                    .putHeader(HostRouteSecret.HEADER, String.valueOf(req.getHeader(HostRouteSecret.HEADER)))
                    .putHeader("X-Controller", "yes").putHeader("Proxy-Connection", "x")
                    .putHeader("Connection", "X-Loopback").putHeader("X-Loopback", "for the loopback hop").end("hello " + req.getHeader("X-Isx-Instance"));
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var target = "127.0.0.1:" + controller.actualPort();

        var leaky = Files.createDirectories(configDir.resolve("host-routes")).resolve(LEAKY + ".secret");
        Files.writeString(leaky, "l".repeat(64) + "\n");
        Files.setPosixFilePermissions(leaky, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        Files.writeString(configDir.resolve("config.yaml"), """
                host-routes:
                  %s:
                    target: %s
                    templates: [%s]
                  %s:
                    target: %s
                    templates: [tpl-elsewhere]
                  %s:
                    target: %s
                    templates: [tpl-elsewhere, %s]
                  %s:
                    target: %s
                  %s:
                    target: %s
                    templates: [%s]
                """.formatted(WORK, target, TEMPLATE, OTHER, target, OPEN, target, TEMPLATE,
                UNFILTERED, target, LEAKY, target, TEMPLATE));
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return io.hyperfoil.tools.jjq.value.JqValues.parse(listing);
            }
        });
        proxy.useInstanceRegistry(registry);
        ContainerTls.startInBackground(proxy);
        port = proxy.mitmPort();

        clientVertx = Vertx.vertx();
        clientContext = clientVertx.getOrCreateContext();
        client = clientVertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true)
                .setVerifyHost(false).setForceSni(true));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (clientVertx != null) clientVertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @BeforeEach
    void anAgentInstance() {
        instance(TEMPLATE, "");
        seen.clear();
    }

    /** The instance at 127.0.0.1, built from {@code template}, with this start's secret recorded. */
    private static void instance(String template, String extraConfig) {
        instance("127.0.0.1", template, extraConfig);
    }

    private static void instance(String address, String template, String extraConfig) {
        instance(INSTANCE, address, template, extraConfig);
    }

    private static void instance(String name, String address, String template, String extraConfig) {
        listing = """
                [{"name":"%s","status":"Running","config":{
                   "%s":"%s", "%s":"%s", "%s":"%s"%s}}]
                """.formatted(name, Metadata.STATIC_IP, address, Metadata.INSTANCE_SECRET_SHA256,
                InstanceSecret.sha256(SECRET), Metadata.PROFILE, template, extraConfig);
        registry.refresh();
    }

    record Reply(int status, MultiMap headers, String body) {}

    record FullReply(int status, String reason) {}

    /** A GET to {@code name} with this start's secret, keeping the reason phrase. */
    private static FullReply sendFull(String name, String path) throws Exception {
        var options = new RequestOptions().setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1"))
                .setHost(name).setPort(443).setURI(path).addHeader(InstanceSecret.HEADER, SECRET);
        var result = Promise.<FullReply>promise();
        clientContext.runOnContext(v -> client.request(options)
                .compose(req -> req.send())
                .compose(resp -> resp.body().map(b -> new FullReply(resp.statusCode(), resp.statusMessage())))
                .onComplete(result));
        return result.future().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /** A request whose SNI is {@code sni} and whose Host header is {@code host}. */
    private static Reply send(String sni, String host, String path, MultiMap headers, String body) throws Exception {
        var options = new RequestOptions().setMethod(body == null ? HttpMethod.GET : HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1"))
                .setHost(sni).setPort(443).setURI(path);
        headers.forEach(options::addHeader);
        if (!sni.equals(host)) options.putHeader("Host", host);
        var result = Promise.<Reply>promise();
        clientContext.runOnContext(v -> client.request(options)
                .compose(req -> body == null ? req.send() : req.send(Buffer.buffer(body)))
                .compose(resp -> resp.body().map(b -> new Reply(resp.statusCode(), resp.headers(), b.toString())))
                .onComplete(result));
        return result.future().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static Reply send(String name, MultiMap headers) throws Exception {
        return send(name, name, "/report?x=1", headers, null);
    }

    private static MultiMap withSecret() {
        return MultiMap.caseInsensitiveMultiMap().add(InstanceSecret.HEADER, SECRET);
    }

    @Test
    void theRightSecretReachesTheControllerWithTheProxysIdentityOnly() throws Exception {
        var headers = withSecret()
                .add("X-Isx-Instance", "someone-else")
                .add("x-isx-template", "tpl-forged")
                .add("X-Isx-Anything", "forged")
                // A CGI-style controller reads these as HTTP_X_ISX_INSTANCE, the proxy's own header
                .add("X_Isx_Instance", "someone-else")
                .add("x_isx-template", "tpl-forged")
                .add("X.Isx.Instance", "someone-else")
                .add("Connection", "keep-alive, X-Hop")
                .add("X-Hop", "for this hop only")
                .add("X-Other", "kept");
        var reply = send(WORK, WORK, "/report?x=1", headers, "{\"done\":true}");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("hello " + INSTANCE, reply.body());
        assertEquals("yes", reply.headers().get("X-Controller"));
        assertNull(reply.headers().get("Proxy-Connection"), "hop-by-hop on the way back too");
        assertNull(reply.headers().get("X-Loopback"), "a header the controller named in Connection: stays on its hop");
        assertEquals(1, seen.size());
        var got = seen.getFirst();
        assertEquals(HttpMethod.POST, got.method());
        assertEquals("/report?x=1", got.uri());
        assertEquals("{\"done\":true}", got.body());
        assertEquals(List.of(INSTANCE), got.headers().getAll("X-Isx-Instance"), "the proxy's identity replaces the client's");
        assertEquals(List.of(TEMPLATE), got.headers().getAll("X-Isx-Template"));
        assertNull(got.headers().get("X-Isx-Anything"), "no client-sent X-Isx-* header crosses");
        // As a CGI-style controller reads a name: every non-alphanumeric character as one
        got.headers().names().forEach(name -> assertFalse(name.toUpperCase().replaceAll("[^A-Z0-9]", "_").startsWith("X_ISX_")
                && !List.of("X-Isx-Instance", "X-Isx-Template", HostRouteSecret.HEADER).contains(name),
                "a variant of an identity header crossed: " + name));
        assertNull(got.headers().get("X-Hop"), "a header the client named in Connection: is hop-by-hop");
        assertNull(got.headers().get(InstanceSecret.HEADER), "the controller never sees the instance's secret");
        assertEquals(List.of(routeSecret(WORK)), got.headers().getAll(HostRouteSecret.HEADER),
                "the route's own secret, which the controller checks before trusting the rest");
        assertNull(reply.headers().get(HostRouteSecret.HEADER), "never handed back to the guest");
        assertNull(reply.headers().get("X-Echo"), "not in any header the controller echoes either");
        assertEquals("kept", got.headers().get("X-Other"));
    }

    /** What the controller reads to check the proxy: the route's secret file. */
    private static String routeSecret(String route) throws Exception {
        return Files.readString(HostRouteSecret.file(route)).strip();
    }

    @Test
    void eachRouteHasItsOwnOwnerOnlySecretAndAForgedOneIsReplaced() throws Exception {
        var file = HostRouteSecret.file(WORK);
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file));
        assertEquals(64, routeSecret(WORK).length());
        assertNotEquals(routeSecret(WORK), routeSecret(OPEN), "a controller of one route cannot pass as another's");

        var reply = send(WORK, withSecret().add(HostRouteSecret.HEADER, "forged-by-the-guest"));
        assertEquals(200, reply.status(), reply.body());
        assertEquals(List.of(routeSecret(WORK)), seen.getFirst().headers().getAll(HostRouteSecret.HEADER));
    }

    @Test
    void aRouteWithoutTemplatesIsNeverServed() throws Exception {
        // Not intercepted at all: no certificate, so not even a handshake
        assertFalse(proxy.allInterceptedDomains().contains(UNFILTERED));
        assertThrows(Exception.class, () -> send(UNFILTERED, withSecret()));
        assertEquals(List.of(), seen);
        var log = Files.readString(dev.incusspawn.Environment.proxyLifecycleLogFile());
        assertTrue(log.contains("Ignoring host route '" + UNFILTERED + "'") && log.contains("templates: is required"), log);
    }

    @Test
    void aRouteWhoseSecretIsUnsafeIsRefusedNotRelayedOut() throws Exception {
        // Still claimed, as the CLI's bridge DNS still names it: never relayed out as ordinary traffic
        assertTrue(proxy.allInterceptedDomains().contains(LEAKY));
        var reply = send(LEAKY, withSecret());
        assertEquals(503, reply.status(), reply.body());
        assertEquals(List.of(), seen);
        var log = Files.readString(dev.incusspawn.Environment.proxyLifecycleLogFile());
        assertTrue(log.contains("Refusing every request to host route '" + LEAKY + "' (503)") && log.contains("owner only"), log);
    }

    @Test
    void aReasonPhraseQuotingTheSecretIsNotHandedOn() throws Exception {
        var reply = sendFull(WORK, "/reason");
        assertEquals(200, reply.status());
        assertFalse(reply.reason().contains(routeSecret(WORK)), reply.reason());
    }

    @Test
    void noRouteSecretIsEverLogged() throws Exception {
        send(WORK, withSecret());
        send(OTHER, withSecret());
        send(WORK, MultiMap.caseInsensitiveMultiMap());
        var log = Files.readString(dev.incusspawn.Environment.proxyLifecycleLogFile());
        for (var route : List.of(WORK, OTHER, OPEN)) {
            assertFalse(log.contains(routeSecret(route)), "the secret of " + route + " is in the proxy log");
        }
        assertFalse(log.contains("l".repeat(64)), "nor the unusable one's");
    }

    @Test
    void anAbsoluteFormTargetReachesTheControllerAsItsPathOnly() throws Exception {
        var reply = send(WORK, WORK, "http://elsewhere.internal/report?x=1", withSecret(), null);
        assertEquals(200, reply.status(), reply.body());
        assertEquals("/report?x=1", seen.getFirst().uri());
    }

    @Test
    void aMissingOrWrongSecretIsRefusedBeforeTheController() throws Exception {
        assertEquals(403, send(WORK, MultiMap.caseInsensitiveMultiMap()).status());
        assertEquals(403, send(WORK, MultiMap.caseInsensitiveMultiMap().add(InstanceSecret.HEADER, "c".repeat(64))).status());
        assertEquals(List.of(), seen);
    }

    @Test
    void onMacOSTheSecretIdentifiesACallerFromAnAddressThatIsNoInstance() throws Exception {
        // #1583: the VM NATs every container to its own address, which names no instance
        instance("10.0.0.5", TEMPLATE, "");
        try {
            var refused = send(WORK, withSecret());
            assertEquals(403, refused.status(), "Linux keeps the address check");
            assertTrue(refused.body().contains("only an isx instance"), refused.body());

            proxy.proofIdentifies = (remote, local) -> true;
            var reply = send(WORK, withSecret());
            assertEquals(200, reply.status(), reply.body());
            assertEquals(List.of(INSTANCE), seen.getFirst().headers().getAll("X-Isx-Instance"));
            assertEquals(List.of(TEMPLATE), seen.getFirst().headers().getAll("X-Isx-Template"));

            var wrong = send(WORK, MultiMap.caseInsensitiveMultiMap().add(InstanceSecret.HEADER, "c".repeat(64)));
            assertEquals(403, wrong.status());
            assertTrue(wrong.body().contains(InstanceSecret.HEADER), "the secret is what to fix: " + wrong.body());
            assertEquals(403, send(OTHER, withSecret()).status(), "the route's templates still decide");
            assertEquals(1, seen.size(), "nothing refused reaches the controller");
        } finally {
            proxy.proofIdentifies = (remote, local) -> false;
        }
    }

    @Test
    void aCallerItsSecretAloneIdentifiesCountsAsThatInstance() throws Exception {
        // #1593: else isx proxy activity shows macOS host-route traffic as (unattributed).
        // Its own name: counters outlive a test, and identified_by_proof is never unset
        var name = "agent-behind-nat";
        instance(name, "10.0.0.5", TEMPLATE, "");
        try {
            proxy.proofIdentifies = (remote, local) -> true;
            var before = ApiActivityProxyTest.activity(proxy.healthPort());
            assertEquals(200, send(WORK, withSecret()).status());
            var after = ApiActivityProxyTest.activity(proxy.healthPort());

            var counted = after.path("instances").path(name);
            assertEquals(1, counted.path("traffic").path("requests").asLong(0), after.toString());
            assertTrue(counted.path("identified_by_proof").asBoolean(false), after.toString());
            assertEquals(before.path("unattributed").path("requests").asLong(0),
                    after.path("unattributed").path("requests").asLong(0), after.toString());
        } finally {
            proxy.proofIdentifies = (remote, local) -> false;
        }
    }

    @Test
    void anSniAndHostThatDisagreeAreRefused() throws Exception {
        // A route's name on the connection cannot carry a request for another domain, nor the other way round
        assertEquals(421, send(WORK, OPEN, "/", withSecret(), null).status());
        assertEquals(421, send(WORK, ProxyConfig.MCP_DOMAIN, "/", withSecret(), null).status());
        assertEquals(421, send("api.anthropic.com", WORK, "/", withSecret(), null).status());
        assertEquals(List.of(), seen);
    }

    @Test
    void aNameUnderARouteIsNotFound() throws Exception {
        assertEquals(404, send("x." + WORK, withSecret()).status());
        assertEquals(List.of(), seen);
    }

    @Test
    void aNameNotConfiguredIsNotRouted() {
        // Not intercepted at all: the proxy has no certificate for it
        assertThrows(Exception.class, () -> send("nope.isx.internal", withSecret()));
        assertEquals(List.of(), seen);
    }

    @Test
    void aWebSocketIsRefused() throws Exception {
        // WebSocket clients dial the name, so this one resolves it as an instance's bridge DNS would
        var wsVertx = Vertx.vertx(new io.vertx.core.VertxOptions().setAddressResolverOptions(
                new io.vertx.core.dns.AddressResolverOptions().setHostsValue(Buffer.buffer("127.0.0.1 " + WORK + "\n"))));
        // Held until the end: Vert.x 5 closes a client it finds unreachable, so one only the
        // pending connect referred to was closed by a GC mid-handshake ("Connection closed", #2006)
        var wsClient = wsVertx.createWebSocketClient(
                new io.vertx.core.http.WebSocketClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
        try {
            var connect = new io.vertx.core.http.WebSocketConnectOptions()
                    .setHost(WORK).setPort(port).setSsl(true).setURI("/ws")
                    .addHeader(InstanceSecret.HEADER, SECRET);
            var pending = wsClient.connect(connect);
            // As a loaded run may: a client left unreachable failed here in 5 of 5 runs (#2006)
            System.gc();
            var failure = assertThrows(Exception.class,
                    () -> pending.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS));
            assertTrue(String.valueOf(failure.getCause()).contains("404"), String.valueOf(failure.getCause()));
            assertEquals(List.of(), seen);
        } finally {
            try {
                wsClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } finally {
                wsVertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void theRouteNamesAreInterceptedForCertificatesAndBridgeDns() {
        assertTrue(proxy.allInterceptedDomains().containsAll(List.of(WORK, OTHER, OPEN)),
                proxy.allInterceptedDomains().toString());
    }

    @Test
    void aTemplateTheRouteDoesNotListIsRefused() throws Exception {
        assertEquals(403, send(OTHER, withSecret()).status());
        assertEquals(200, send(OPEN, withSecret()).status(), "a route listing the template among others admits it");
        assertEquals(1, seen.size());
    }

    @Test
    void aProjectLocalInstanceIsNeverAdmitted() throws Exception {
        instance(TEMPLATE, ", \"" + Metadata.PROJECT + "\":\"some-repo\"");
        assertEquals(403, send(WORK, withSecret()).status());
        assertEquals(403, send(OPEN, withSecret()).status());
        assertEquals(List.of(), seen);
    }
}
