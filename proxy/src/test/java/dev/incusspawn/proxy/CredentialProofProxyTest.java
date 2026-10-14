package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.http.*;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The wire contract of #1100: possession of an address alone grants no credential. */
class CredentialProofProxyTest {
    static final String GITHUB = "api.github.com";
    static final String ANTHROPIC = "api.anthropic.com";
    static final String SECRET = ProofTestSupport.SECRET;
    static final String BODY = "{\"model\":\"claude-test\",\"messages\":[],\"max_tokens\":7}";
    static final String GITHUB_CONFIG = """
            github:
              accounts:
                outer:
                  token: ghp_outer
              default: outer
            """;
    @TempDir static Path home;
    static String previousHome;
    static Vertx vertx;
    static HttpServer upstream;
    static HttpClient client;
    static int upstreamPort;
    static volatile CompletableFuture<Received> received;
    record Received(String uri, String host, String authorization, String key, String body) {}
    MitmProxy proxy;
    InstanceRegistry registry;
    volatile String stamp;
    volatile String generation = "ab".repeat(32);
    volatile boolean failListings;
    volatile CompletableFuture<Void> holdListing;
    final CompletableFuture<Void> listingEntered = new CompletableFuture<>();
    volatile String address = "127.0.0.1";
    volatile String account;
    String bakedIdentity;
    final AtomicInteger tokenFetches = new AtomicInteger();
    final AtomicInteger listings = new AtomicInteger();

    @BeforeAll static void start() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".config/incus-spawn"));
        vertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(new AddressResolverOptions()
                .setHostsValue(Buffer.buffer("127.0.0.1 " + GITHUB + " " + ANTHROPIC + "\n"))));
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), ANTHROPIC)))
                .requestHandler(req -> req.body().onSuccess(body -> {
                    received.complete(new Received(req.uri(), req.getHeader("Host"), req.getHeader("Authorization"),
                            req.getHeader("x-api-key"), body.toString()));
                    req.response().end("{}");
                })).webSocketHandler(ws -> {
                    received.complete(new Received(ws.uri(), ws.headers().get("Host"), ws.headers().get("Authorization"),
                            ws.headers().get("x-api-key"), ""));
                    ws.close();
                });
        upstreamPort = NpmCacheProxyTest.listen(upstream);
        client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false).setMaxPoolSize(16));
    }

    @AfterAll static void stop() throws Exception {
        try { if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        finally { System.setProperty("user.home", previousHome); }
    }

    @AfterEach void stopProxy() { if (proxy != null) proxy.stop(); }

    @Test void unexpectedRefusalsDoNotPrintExceptionMessagesOrCauses() {
        var header = "Bearer " + ProofTestSupport.token("github", "gho_");
        var failure = new IllegalArgumentException("invalid header value: " + header,
                new RuntimeException("credential: " + header));
        assertEquals(": IllegalArgumentException", MitmProxy.refusal(failure));
    }

    void serve(String claude) throws Exception {
        Files.writeString(home.resolve(".config/incus-spawn/config.yaml"), GITHUB_CONFIG + claude);
        stamp = ProofVerifier.stamp(SECRET, ProofToken.declared());
        registry = new InstanceRegistry(new IncusClient() {
            @Override public JqValue listJsonConfig() {
                listings.incrementAndGet();
                var hold = holdListing;
                if (hold != null) {
                    listingEntered.complete(null);
                    hold.join();
                }
                if (failListings) throw new IllegalStateException("Incus unavailable");
                var config = JqObject.builder(3).put(Metadata.STATIC_IP, address)
                        .put(Metadata.INSTANCE_PROOF_ID, generation)
                        .put(Metadata.INSTANCE_PROOF_SHA256, stamp);
                if (account != null) config.put(Metadata.accountKey("claude"), account);
                if (bakedIdentity != null) config.put(Metadata.accountIdentityKey("claude"), bakedIdentity);
                return JqValues.parse("[" + JqValues.toPrettyJsonString(JqObject.builder(2)
                        .put("name", "outer-instance").put("config", config.build()).build()) + "]");
            }
        });
        registry.refresh();
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load()) {
            @Override String fetchGcloudToken() { tokenFetches.incrementAndGet(); return "ya29.outer"; }
        };
        proxy.upstream.upstreamTrustAll = true;
        proxy.upstreamWsPort = upstreamPort;
        ContainerTls.startInBackground(proxy);
        proxy.useInstanceRegistry(registry);
        for (var host : List.of(GITHUB, "github.com", ANTHROPIC, "us-east5-aiplatform.googleapis.com")) {
            proxy.overrideUpstream(host, "127.0.0.1", upstreamPort);
        }
    }

    static String claude(String type) {
        return "claude:\n  accounts:\n    outer:\n      type: " + type
                + "\n      apiKey: sk-ant-outer\n      oauthToken: sk-ant-oat01-outer\n"
                + "      vertexProjectId: outer-project\n      cloudMlRegion: us-east5\n  default: outer\n";
    }

    int http(String host, String header, String value) throws Exception {
        received = new CompletableFuture<>();
        var options = new RequestOptions().setMethod(HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(host).setPort(443).setURI("/v1/messages?keep=1");
        return client.request(options).compose(req -> {
            if (value != null) req.putHeader(header, value);
            return req.send(BODY);
        }).compose(resp -> resp.body().map(b -> resp.statusCode()))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    Received request(boolean websocket, String host, String header, String value) throws Exception {
        if (!websocket) {
            assertEquals(200, http(host, header, value));
        } else {
            received = new CompletableFuture<>();
            var options = new WebSocketConnectOptions().setHost(host).setPort(proxy.mitmPort()).setSsl(true)
                    .setURI("/v1/messages?keep=1");
            if (value != null) options.addHeader(header, value);
            var ws = client.webSocket(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            // Leave the connection alive until the upstream has received its headers.
            var result = received.get(10, TimeUnit.SECONDS);
            ws.close();
            return result;
        }
        return received.get(10, TimeUnit.SECONDS);
    }

    record Case(boolean websocket, String host, String header, String value, boolean proven) {}

    static Stream<Case> matrix() {
        var cases = new java.util.ArrayList<Case>();
        for (var ws : List.of(false, true)) {
            for (var ns : List.of("github", "claude")) {
                var prefix = ns.equals("github") ? "gho_" : "sk-ant-";
                var host = ns.equals("github") ? GITHUB : ANTHROPIC;
                var header = ns.equals("github") ? "Authorization" : "x-api-key";
                var scheme = ns.equals("github") ? "Bearer " : "";
                var token = ProofTestSupport.token(ns, prefix);
                cases.add(new Case(ws, host, header, null, false));
                for (var value : List.of(prefix + "placeholder", prefix + "isx_bad", token + "f",
                        prefix + "isx_" + ProofToken.derive("55".repeat(32), ns),
                        prefix + "isx_" + ProofToken.derive(SECRET, ns.equals("github") ? "claude" : "github"),
                        prefix + "real-inner-account")) {
                    cases.add(new Case(ws, host, header, scheme + value, false));
                }
                cases.add(new Case(ws, host, header, scheme + token, true));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest @MethodSource("matrix")
    void addressAndCurrentNamespaceProofAreBothRequired(Case c) throws Exception {
        serve(claude("api-key"));
        var got = request(c.websocket(), c.host(), c.header(), c.value());
        var actual = c.header().equals("Authorization") ? got.authorization() : got.key();
        assertEquals(c.proven() ? (c.host().equals(GITHUB) ? "Bearer ghp_outer" : "sk-ant-outer") : c.value(), actual);
        assertEquals("/v1/messages?keep=1", got.uri());
        assertTrue(got.host().startsWith(c.host()));
        if (!c.websocket()) assertEquals(BODY, got.body());
        assertEquals(0, tokenFetches.get());
    }

    static Stream<String> modes() { return Stream.of("api-key", "oauth", "vertex"); }

    @ParameterizedTest @MethodSource("modes")
    void independentInnerAuthenticationKeepsItsDestinationAndBodyRegardlessOfOuterMode(String mode) throws Exception {
        serve(claude(mode));
        for (var ws : List.of(false, true)) {
            var got = request(ws, ANTHROPIC, "Authorization", "Bearer inner-account-B");
            assertEquals("Bearer inner-account-B", got.authorization());
            assertNull(got.key());
            assertEquals("/v1/messages?keep=1", got.uri());
            assertTrue(got.host().startsWith(ANTHROPIC));
            if (!ws) assertEquals(BODY, got.body());
        }
        assertEquals(0, tokenFetches.get());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aMissingOuterAccountOnlyRefusesAProvenRequestAndLogsWhy(boolean websocket) throws Exception {
        account = "removed";
        serve(claude("vertex"));
        assertEquals("Bearer inner-B", request(false, ANTHROPIC, "Authorization", "Bearer inner-B").authorization());
        assertEquals("Bearer inner-B", request(true, ANTHROPIC, "Authorization", "Bearer inner-B").authorization());
        var log = refuseProvenRequest(websocket);
        assertTrue(log.contains("pinned to missing claude account 'removed'"), log);
        assertEquals(0, tokenFetches.get());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void anUnservableAccountLogsItsReasonForHttpAndWebSockets(boolean websocket) throws Exception {
        bakedIdentity = "api-key";
        serve(claude("oauth"));
        var log = refuseProvenRequest(websocket);
        assertTrue(log.contains("was built for claude 'api-key'"), log);
        assertTrue(log.contains("account of type 'oauth'"), log);
    }

    String refuseProvenRequest(boolean websocket) throws Exception {
        var logFile = dev.incusspawn.Environment.proxyLifecycleLogFile();
        var before = Files.exists(logFile) ? Files.readString(logFile).length() : 0;
        var token = ProofTestSupport.token("claude", "sk-ant-");
        if (websocket) {
            received = new CompletableFuture<>();
            var options = new WebSocketConnectOptions().setHost(ANTHROPIC).setPort(proxy.mitmPort()).setSsl(true)
                    .setURI("/v1/messages?keep=1").addHeader("x-api-key", token);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.webSocket(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("502"), error.toString());
        } else {
            assertEquals(502, http(ANTHROPIC, "x-api-key", token));
        }
        assertFalse(received.isDone(), "a refused account must never reach the upstream");
        var log = Files.readString(logFile).substring(before);
        assertTrue(log.contains((websocket ? "Refusing WebSocket to " : "Refusing ") + ANTHROPIC), log);
        assertTrue(log.contains("outer-instance"), log);
        assertFalse(log.contains(token), "diagnostics must never include the proof");
        return log;
    }

    @Test void customToolWithoutPlaceholdersWarnsOnLoadAndBlocksSharedDomainInjection() throws Exception {
        var tools = home.resolve(".config/incus-spawn/tools");
        Files.createDirectories(tools);
        var definition = tools.resolve("custom.yaml");
        Files.writeString(definition, """
                name: custom
                proxy:
                  config-namespace: custom
                  configuration:
                    key:
                      config-path: token
                      secret: true
                  auth:
                    - domains: [api.github.com]
                      type: header
                      name: X-Custom-Key
                      value: '${key}'
                """);
        try {
            var logFile = dev.incusspawn.Environment.proxyLifecycleLogFile();
            var before = Files.exists(logFile) ? Files.readString(logFile).length() : 0;
            serve(claude("api-key") + "custom:\n  token: custom-host-credential\n");
            var proof = "Bearer " + ProofTestSupport.token("github", "gho_");
            for (var ws : List.of(false, true)) {
                assertEquals(proof, request(ws, GITHUB, "Authorization", proof).authorization());
            }
            var warnings = Files.readString(logFile).substring(before).lines()
                    .filter(line -> line.contains("namespace 'custom' has no declared placeholders")).toList();
            assertEquals(1, warnings.size(), "warn on configuration load, never per request");
            assertTrue(warnings.getFirst().contains(GITHUB));
            assertTrue(warnings.getFirst().contains("proxy.placeholders"));
        } finally {
            Files.deleteIfExists(definition);
        }
    }

    @Test void anUnknownAddressNeverReceivesTheDefaultAccount() throws Exception {
        address = "127.0.0.2";
        serve(claude("api-key"));
        var proof = "Bearer " + ProofTestSupport.token("github", "gho_");
        assertEquals(proof, request(false, GITHUB, "Authorization", proof).authorization());
        assertEquals(proof, request(true, GITHUB, "Authorization", proof).authorization());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"api-key", "oauth"})
    void everyClaudePrefixAndCarrierWorksIndependentlyOfTheSelectedMode(String mode) throws Exception {
        serve(claude(mode));
        for (var prefix : List.of("sk-ant-", "sk-ant-oat01-", "ya29.")) {
            for (var ws : List.of(false, true)) {
                for (var header : List.of("Authorization", "x-api-key")) {
                    var got = request(ws, ANTHROPIC, header,
                            (header.equals("Authorization") ? "Bearer " : "") + ProofTestSupport.token("claude", prefix));
                    assertEquals(mode.equals("oauth") ? "Bearer sk-ant-oat01-outer" : null, got.authorization());
                    assertEquals(mode.equals("api-key") ? "sk-ant-outer" : null, got.key());
                }
            }
        }
    }

    @Test void theGitBasicPasswordCarriesTheSameProof() throws Exception {
        serve(claude("api-key"));
        var basic = "Basic " + Base64.getEncoder().encodeToString(("x-access-token:"
                + ProofTestSupport.token("github", "gho_")).getBytes(StandardCharsets.UTF_8));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("x-access-token:ghp_outer".getBytes(StandardCharsets.UTF_8)),
                request(false, "github.com", "Authorization", basic).authorization());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void theFirstNewProofWaitsForTheRefreshFloorAndOldProofsStayRefused(boolean websocket) throws Exception {
        serve(claude("api-key"));
        registry.refresh();
        var rotated = "66".repeat(32);
        stamp = ProofVerifier.stamp(rotated, ProofToken.declared());
        var fresh = "Bearer gho_isx_" + ProofToken.derive(rotated, "github");
        assertEquals("Bearer ghp_outer", request(websocket, GITHUB, "Authorization", fresh).authorization());
        var old = "Bearer " + ProofTestSupport.token("github", "gho_");
        int before = listings.get();
        var requests = java.util.stream.IntStream.range(0, 8).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try { return http(GITHUB, "Authorization", old); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        })).toList();
        for (var result : requests) assertEquals(200, result.get(10, TimeUnit.SECONDS));
        assertTrue(listings.get() <= before + 1, "a burst of invalid proofs must share a listing");
        assertEquals(old, received.get(10, TimeUnit.SECONDS).authorization());
    }

    @Test void aStalledListingBoundsProofWaitsWithoutQueuingMoreWorkersOrDelayingIndependentCredentials() throws Exception {
        serve(claude("api-key"));
        holdListing = new CompletableFuture<>();
        var invalid = "Bearer gho_isx_" + "9".repeat(ProofToken.DIGEST_HEX_CHARS);
        int before = listings.get();
        try {
            var waiting = CompletableFuture.supplyAsync(() -> {
                try { return http(GITHUB, "Authorization", invalid); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            });
            listingEntered.get(3, TimeUnit.SECONDS);
            var independent = CompletableFuture.supplyAsync(() -> {
                try { return request(false, GITHUB, "Authorization", "Bearer own-credential"); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            });
            assertEquals("Bearer own-credential", independent.get(2, TimeUnit.SECONDS).authorization());
            assertEquals(200, waiting.get(8, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) assertEquals(200, http(GITHUB, "Authorization", invalid));
            assertEquals(before + 1, listings.get(), "the stalled worker remains shared even after its waiters time out");
        } finally {
            holdListing.complete(null);
        }
    }

    @Test void resetAcknowledgementReportsOnlyAppliedStateAndFailureClearsIt() throws Exception {
        serve(claude("api-key"));
        try (var reader = java.net.http.HttpClient.newHttpClient()) {
            var query = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"
                    + proxy.healthPort() + "/credential-proofs?instance=outer-instance")).GET().build();
            var previous = reader.send(query, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            var resetSecret = "67".repeat(32);
            generation = "cd".repeat(32);
            stamp = ProofVerifier.stamp(resetSecret, ProofToken.declared());
            assertEquals(previous, reader.send(query, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
            proxy.refreshInstanceRegistry();
            var reply = reader.send(query, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reply.statusCode());
            var state = JqValues.parseStrict(reply.body());
            assertEquals(generation, state.pathText("generation", ""));
            assertEquals(dev.incusspawn.tool.ToolDef.sha256hex(stamp), state.pathText("verifierHash", ""));
            assertFalse(reply.body().contains(resetSecret));
            var old = "Bearer " + ProofTestSupport.token("github", "gho_");
            assertEquals(old, request(false, GITHUB, "Authorization", old).authorization());
            var fresh = "Bearer gho_isx_" + ProofToken.derive(resetSecret, "github");
            assertEquals("Bearer ghp_outer", request(true, GITHUB, "Authorization", fresh).authorization());
            failListings = true;
            registry.refresh();
            assertEquals(404, reader.send(query, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
