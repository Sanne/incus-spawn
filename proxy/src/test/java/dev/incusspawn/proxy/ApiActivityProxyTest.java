package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import dev.incusspawn.incus.IncusClient;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /activity} (#898): an instance's model calls through the proxy, counted and read out of
 * their responses' usage, for the host to ask about without touching the instance.
 */
class ApiActivityProxyTest {

    private static final String ANTHROPIC = "api.anthropic.com";
    private static final String WORKER = "mcp-isx-870-impl-k3x9q";

    @TempDir
    static Path tempHome;
    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static HttpServer upstream;
    static HttpClient client;
    static int mitmPort;
    static int healthPort;
    static InstanceRegistry registry;
    /** What the registry lists: one instance, at the test client's address. */
    static volatile String listing;
    /** Whether listing fails, as when the Incus socket is gone. */
    static volatile boolean listingFails;
    /** Holds every listing back until completed, as when Incus stalls. */
    static volatile java.util.concurrent.CountDownLatch listingHeld = new java.util.concurrent.CountDownLatch(0);
    /** The response upstream holds back for {@code /stall}, until a test ends it. */
    static volatile java.util.concurrent.CompletableFuture<io.vertx.core.http.HttpServerResponse> stalled =
            new java.util.concurrent.CompletableFuture<>();
    /** The Accept-Encoding each upstream path was asked with. */
    static final Map<String, String> acceptEncodings = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        vertx = Vertx.vertx();
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), ANTHROPIC)))
                .requestHandler(ApiActivityProxyTest::answer);
        int upstreamPort = NpmCacheProxyTest.listen(upstream);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                new ProxyCredentials("sk-ant-api03-real", "", false, "", "", List.of()));
        proxy.upstream.upstreamTrustAll = true;
        // The real clock, moving on every reading as a slow machine's does between two handlers (#1281)
        var last = new java.util.concurrent.atomic.AtomicLong();
        proxy.apiActivity = new ApiActivity(() -> last.updateAndGet(t -> Math.max(t + 1, System.currentTimeMillis())));
        ContainerTls.startInBackground(proxy);
        mitmPort = proxy.mitmPort();
        healthPort = proxy.healthPort();
        proxy.overrideUpstream(ANTHROPIC, "127.0.0.1", upstreamPort);

        // The test client connects from 127.0.0.1, which the registry says is the worker
        listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
        registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                if (listingFails) throw new IllegalStateException("Incus is not answering");
                try {
                    listingHeld.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return io.hyperfoil.tools.jjq.value.JqValues.parse(listing);
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);

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

    static void answer(HttpServerRequest req) {
        var accept = req.getHeader("Accept-Encoding");
        acceptEncodings.put(req.path(), accept == null ? "" : accept);
        var resp = req.response();
        switch (req.path()) {
            case "/v1/messages" -> {
                if ("stream".equals(req.getParam("mode"))) {
                    resp.setChunked(true).putHeader("Content-Type", "text/event-stream");
                    // Split mid-line, as a real stream is: the usage must be read across chunks
                    resp.write("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":"
                            + "{\"input_tokens\":100,\"cache_read_input_tokens\":4000,");
                    resp.write("\"cache_creation_input_tokens\":50,\"output_tokens\":1}}}\n\n"
                            + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":"
                            + "{\"text\":\"the word \\\"usage\\\" in text\"}}\n\n");
                    resp.write("event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":12}}\n");
                    resp.end("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":30}}\n\n");
                } else if ("fail".equals(req.getParam("mode"))) {
                    resp.setStatusCode(529).putHeader("Content-Type", "application/json")
                            .end("{\"type\":\"error\",\"usage\":{\"input_tokens\":999}}");
                } else {
                    resp.putHeader("Content-Type", "application/json")
                            .end("{\"type\":\"message\",\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}");
                }
            }
            case "/v1/messages/count_tokens" -> resp.putHeader("Content-Type", "application/json")
                    .end("{\"input_tokens\":500}");
            case "/stall" -> stalled.complete(resp);
            default -> resp.putHeader("Content-Type", "application/json").end("{}");
        }
    }

    static String listed(String name, String createdAt) {
        return """
                [{"name":"%s","created_at":"%s","config":{"user.incus-spawn.static-ip":"127.0.0.1"}}]
                """.formatted(name, createdAt);
    }

    static int post(String uri) throws Exception {
        return postAsync(uri).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    static io.vertx.core.Future<Integer> postAsync(String uri) {
        var options = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                .setHost(ANTHROPIC)
                .setPort(443)
                .setURI(uri);
        return client.request(options)
                .compose(req -> req.putHeader("Accept-Encoding", "gzip, br").send(Buffer.buffer("{}")))
                .compose(resp -> resp.body().map(b -> resp.statusCode()));
    }

    static JqValue activity() throws Exception {
        return activity(healthPort);
    }

    /** {@code /activity} as the host reads it from the proxy whose health endpoint is on {@code port}. */
    static JqValue activity(int port) throws Exception {
        var resp = health(port, "GET", "/activity");
        assertEquals(200, resp.statusCode(), resp.body());
        return JqValues.parseStrict(resp.body());
    }

    static HttpResponse<String> health(String method, String path) throws Exception {
        return health(healthPort, method, path);
    }

    private static HttpResponse<String> health(int port, String method, String path) throws Exception {
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void everyRequestCountsAsTrafficButOnlyModelCallsAsModelCalls() throws Exception {
        // #935: settings and telemetry to the same domain are traffic, never a model call
        var before = activity().path("instances").path(WORKER);
        assertEquals(200, post("/api/oauth/profile"));
        assertEquals(200, post("/v1/messages"));
        var after = activity().path("instances").path(WORKER);

        assertEquals(1, after.path("requests").asLong(0) - before.path("requests").asLong(0), after.toString());
        var traffic = after.path("traffic");
        var anthropic = traffic.path("domains").path(ANTHROPIC);
        assertEquals(2, traffic.path("requests").asLong(0) - before.path("traffic").path("requests").asLong(0), traffic.toString());
        assertEquals(2, anthropic.path("requests").asLong(0)
                - before.path("traffic").path("domains").path(ANTHROPIC).path("requests").asLong(0));
        assertEquals(0, traffic.path("in_flight").asLong(0));
        assertTrue(traffic.path("request_bytes").asLong(0) - before.path("traffic").path("request_bytes").asLong(0) >= 4,
                "the two bodies of {}: " + traffic);
        assertTrue(traffic.path("response_bytes").asLong(0) > before.path("traffic").path("response_bytes").asLong(0));
        assertTrue(anthropic.path("last_activity_at").asLong(0) >= after.path("last_response_at").asLong(0));
    }

    @Test
    void aRequestUpstreamHoldsIsInFlightUntilItIsAnswered() throws Exception {
        stalled = new java.util.concurrent.CompletableFuture<>();
        var answered = postAsync("/stall");
        var held = stalled.get(10, TimeUnit.SECONDS);

        assertEquals(1, activity().path("instances").path(WORKER).path("traffic").path("in_flight").asLong(0));
        held.end("{}");
        assertEquals(200, answered.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        // The client has its answer once the proxy wrote it, and the flow ends as it is written
        assertEquals(0, activity().path("instances").path(WORKER).path("traffic").path("in_flight").asLong(0));
    }

    @Test
    void aCallerTheRegistryDoesNotKnowCountsAsUnattributed() throws Exception {
        try {
            listing = """
                    [{"name":"elsewhere","created_at":"2026-10-01T09:00:00Z","config":{"user.incus-spawn.static-ip":"10.0.0.9"}}]
                    """;
            registry.refresh();
            var before = activity().path("unattributed");
            assertEquals(200, post("/v1/messages"));
            var after = activity();

            assertEquals(1, after.path("unattributed").path("requests").asLong(0) - before.path("requests").asLong(0),
                    after.toString());
            assertEquals(0, after.path("instances").path("elsewhere").path("traffic").path("requests").asLong(0));
            assertTrue(after.path("unattributed").path("counting_since").asLong(0) > 0);
        } finally {
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void aCallerOnlyItsProofIdentifiesCountsAsThatInstance() throws Exception {
        // macOS (#1582): the address is the VM's, the proof names the instance
        var secret = "42".repeat(32);
        try {
            listing = """
                    [{"name":"behind-nat","status":"Running","created_at":"2026-10-01T09:00:00Z","config":{
                       "user.incus-spawn.static-ip":"10.0.0.9","user.incus-spawn.instance-proof-sha256":"%s"}}]
                    """.formatted(ProofVerifier.stamp(secret, ProofToken.declared()).replace("\n", "\\n"));
            registry.refresh();
            proxy.proofIdentifies = (remote, local) -> true;
            var before = activity();
            var options = new RequestOptions().setMethod(HttpMethod.POST)
                    .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                    .setHost(ANTHROPIC).setPort(443).setURI("/v1/messages");
            assertEquals(200, client.request(options)
                    .compose(req -> req.putHeader("x-api-key", "sk-ant-isx_" + ProofToken.derive(secret, "claude"))
                            .send(Buffer.buffer("{}")))
                    .compose(resp -> resp.body().map(b -> resp.statusCode()))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
            var after = activity();

            var counted = after.path("instances").path("behind-nat");
            assertEquals(1, counted.path("requests").asLong(0), after.toString());
            assertTrue(counted.path("identified_by_proof").asBoolean(false), after.toString());
            assertEquals(1, counted.path("traffic").path("requests").asLong(0), after.toString());
            assertEquals(0, counted.path("traffic").path("in_flight").asLong(0));
            assertEquals(before.path("unattributed").path("requests").asLong(0),
                    after.path("unattributed").path("requests").asLong(0), after.toString());
        } finally {
            proxy.proofIdentifies = (remote, local) -> false;
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void aCallerOnlyItsSecretIdentifiesCountsAsThatInstance() throws Exception {
        // Identified, then sent back to initialize: no session process is started
        secretIdentifiedMcpCallIsCounted(true, 404);
    }

    @Test
    void anUnstampedCallerItsSecretIdentifiesCountsAsThatInstanceToo() throws Exception {
        // As on Linux, where the address counts it, though isx mcp refuses it
        secretIdentifiedMcpCallIsCounted(false, 403);
    }

    /** macOS (#1583): the address is the VM's, the per-start secret names the instance. */
    private void secretIdentifiedMcpCallIsCounted(boolean stamped, int status) throws Exception {
        var secret = InstanceSecret.generate();
        // Its own name: counters outlive a test, and the proof test counts a "behind-nat"
        var name = stamped ? "coordinator-behind-nat" : "worker-behind-nat";
        try {
            listing = """
                    [{"name":"%s","status":"Running","created_at":"2026-10-01T09:00:00Z","config":{
                       "user.incus-spawn.static-ip":"10.0.0.9","%s":"%s"%s}}]
                    """.formatted(name, dev.incusspawn.incus.Metadata.INSTANCE_SECRET_SHA256, InstanceSecret.sha256(secret),
                    stamped ? ",\"" + dev.incusspawn.incus.Metadata.MCP_CALLER + "\":\""
                            + dev.incusspawn.incus.Metadata.newMcpCallerGrant() + "\"" : "");
            registry.refresh();
            proxy.proofIdentifies = (remote, local) -> true;
            var before = activity();
            var options = new RequestOptions().setMethod(HttpMethod.GET)
                    .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                    .setHost(ProxyConfig.MCP_DOMAIN).setPort(443).setURI(McpBridge.PATH);
            assertEquals(status, client.request(options)
                    .compose(req -> req.putHeader(InstanceSecret.HEADER, secret).send())
                    .compose(resp -> resp.body().map(b -> resp.statusCode()))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
            var after = activity();

            var counted = after.path("instances").path(name).path("traffic");
            assertEquals(1, counted.path("requests").asLong(0), after.toString());
            assertTrue(after.path("instances").path(name).path("identified_by_proof").asBoolean(false), after.toString());
            assertEquals(0, counted.path("in_flight").asLong(0));
            assertEquals(before.path("unattributed").path("requests").asLong(0),
                    after.path("unattributed").path("requests").asLong(0), after.toString());
        } finally {
            proxy.proofIdentifies = (remote, local) -> false;
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void anInstanceItsAddressNamesIsNotIdentifiedByProof() throws Exception {
        assertEquals(200, post("/v1/messages"));
        var worker = activity().path("instances").path(WORKER);
        assertTrue(worker.has("identified_by_proof"), worker.toString());
        assertFalse(worker.path("identified_by_proof").asBoolean(false), worker.toString());
    }

    @Test
    void aProofFromAnUnknownAddressCountsAsAnAddressMissNotARefusedProof() throws Exception {
        // #1582 before its fix: the proof is never checked, since no instance has the address
        try {
            listing = """
                    [{"name":"elsewhere","created_at":"2026-10-01T09:00:00Z","config":{"user.incus-spawn.static-ip":"10.0.0.9"}}]
                    """;
            registry.refresh();
            var before = activity();
            assertEquals(200, postWithKey("sk-ant-isx_" + ProofToken.derive("42".repeat(32), "claude")));
            var after = activity();

            assertEquals(1, after.path("refused_address_misses").asLong(0) - before.path("refused_address_misses").asLong(0),
                    after.toString());
            assertEquals(before.path("refused_proofs").asLong(0), after.path("refused_proofs").asLong(0), after.toString());
        } finally {
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void aProofThatMatchesNoneOfAKnownInstancesCountsAsARefusedProof() throws Exception {
        var before = activity();
        assertEquals(200, postWithKey("sk-ant-isx_" + ProofToken.derive("42".repeat(32), "claude")));
        var after = activity();

        assertEquals(1, after.path("refused_proofs").asLong(0) - before.path("refused_proofs").asLong(0), after.toString());
        assertEquals(before.path("refused_address_misses").asLong(0), after.path("refused_address_misses").asLong(0),
                after.toString());
    }

    @Test
    void theHostsOwnCredentialIsNeitherARefusedProofNorAnAddressMiss() throws Exception {
        var before = activity();
        assertEquals(200, postWithKey("sk-ant-api03-the-hosts-own"));
        var after = activity();

        assertEquals(before.path("refused_proofs").asLong(0), after.path("refused_proofs").asLong(0), after.toString());
        assertEquals(before.path("refused_address_misses").asLong(0), after.path("refused_address_misses").asLong(0),
                after.toString());
    }

    @Test
    void unplacedRunningNamesOnlyRunningInstancesWithNoTraffic() throws Exception {
        try {
            listing = """
                    [{"name":"silent","status":"Running","created_at":"2026-10-01T09:00:00Z","config":{"user.incus-spawn.static-ip":"10.0.0.9"}},
                     {"name":"stopped","status":"Stopped","created_at":"2026-10-01T09:00:00Z","config":{"user.incus-spawn.static-ip":"10.0.0.10"}},
                     {"name":"%s","status":"Running","created_at":"2026-10-01T09:00:00.000000000Z","config":{"user.incus-spawn.static-ip":"127.0.0.1"}}]
                    """.formatted(WORKER);
            registry.refresh();
            assertEquals(200, post("/v1/messages"));

            assertEquals("[\"silent\"]", activity().path("unplaced_running").toString());
        } finally {
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void aFailedListingShowsAsAStaleRegistryUntilOneSucceeds() throws Exception {
        try {
            assertFalse(activity().path("registry_stale").asBoolean(false));
            listingFails = true;
            registry.refresh();
            assertTrue(activity().path("registry_stale").asBoolean(false));
        } finally {
            listingFails = false;
            registry.refresh();
        }
        assertFalse(activity().path("registry_stale").asBoolean(false));
    }

    @Test
    void aProofRefusedWhenTheRefreshTimedOutIsNeitherARefusedProofNorAnAddressMiss() throws Exception {
        // The snapshot may lack the instance's new proof: no instance's to renew, and no miss either
        var before = activity();
        listingHeld = new java.util.concurrent.CountDownLatch(1);
        try {
            assertEquals(200, postWithKey("sk-ant-isx_" + ProofToken.derive("42".repeat(32), "claude")));
        } finally {
            listingHeld.countDown();
        }
        var after = activity();

        assertEquals(before.path("refused_injections").asLong(0) + 1, after.path("refused_injections").asLong(0), after.toString());
        assertEquals(before.path("refused_proofs").asLong(0), after.path("refused_proofs").asLong(0), after.toString());
        assertEquals(before.path("refused_address_misses").asLong(0), after.path("refused_address_misses").asLong(0),
                after.toString());
    }

    @Test
    void aProofRefusedWhileAnotherListingStallsIsNeitherARefusedProofNorAnAddressMiss() throws Exception {
        // The request's refresh coalesces onto the stalled listing and gives up at its deadline, with the old snapshot
        var before = activity();
        listingHeld = new java.util.concurrent.CountDownLatch(1);
        var stalled = new Thread(registry::refresh);
        try {
            stalled.start();
            assertEquals(200, postWithKey("sk-ant-isx_" + ProofToken.derive("42".repeat(32), "claude")));
            // A hung listing throws nothing: how long it has run is what says the proxy cannot vouch for proofs
            assertTrue(activity().path("registry_stale").asBoolean(false), "while the listing hangs");
        } finally {
            listingHeld.countDown();
            stalled.join(10_000);
        }
        var after = activity();
        assertFalse(after.path("registry_stale").asBoolean(false), "once the hung listing lands: " + after);

        assertEquals(before.path("refused_injections").asLong(0) + 1, after.path("refused_injections").asLong(0), after.toString());
        assertEquals(before.path("refused_proofs").asLong(0), after.path("refused_proofs").asLong(0), after.toString());
        assertEquals(before.path("refused_address_misses").asLong(0), after.path("refused_address_misses").asLong(0),
                after.toString());
    }

    @Test
    void anAddressMissSaysWhenTheLastWas() throws Exception {
        try {
            listing = """
                    [{"name":"elsewhere","created_at":"2026-10-01T09:00:00Z","config":{"user.incus-spawn.static-ip":"10.0.0.9"}}]
                    """;
            registry.refresh();
            var sent = System.currentTimeMillis();
            assertEquals(200, postWithKey("sk-ant-isx_" + ProofToken.derive("42".repeat(32), "claude")));
            var last = activity().path("last_address_miss_at").asLong(0);
            assertTrue(last >= sent && last <= System.currentTimeMillis(), String.valueOf(last));
        } finally {
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void healthReportsBothVersionsWithoutIntroducingVersionDrift() throws Exception {
        var response = health("GET", "/health");
        assertEquals(200, response.statusCode());
        var body = JqValues.parseStrict(response.body());
        var build = BuildInfo.instance();
        assertEquals(build.version(), body.pathText("version", ""));
        assertEquals(build.displayVersion(), body.pathText("displayVersion", ""));
        assertEquals(build.gitSha(), body.pathText("gitSha", ""));
        var info = ProxyHealthCheck.parseProxyInfo(response.body());
        assertEquals(build.displayVersion(), info.displayVersion());
        assertEquals("", ProxyHealthCheck.checkVersionDrift(info));
    }

    @Test
    void healthCarriesNoneOfThePlacementFields() throws Exception {
        var body = health("GET", "/health").body();
        for (var field : List.of("refused_proofs", "refused_address_misses", "registry_stale", "identified_by_proof",
                "last_address_miss_at")) {
            assertFalse(body.contains(field), body);
        }
    }

    /** A model call carrying {@code key}, as an instance's Claude Code sends it. */
    static int postWithKey(String key) throws Exception {
        var options = new RequestOptions().setMethod(HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(mitmPort, "127.0.0.1"))
                .setHost(ANTHROPIC).setPort(443).setURI("/v1/messages");
        return client.request(options)
                .compose(req -> req.putHeader("x-api-key", key).send(Buffer.buffer("{}")))
                .compose(resp -> resp.body().map(b -> resp.statusCode()))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void theHealthServerOnlyReads() throws Exception {
        for (var path : List.of("/health", "/activity")) {
            for (var method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                assertEquals(405, health(method, path).statusCode(), method + " " + path);
            }
        }
        assertEquals(404, health("POST", "/routes").statusCode());
    }

    @Test
    void countsAnInstancesModelCallsAndTheTokensTheirResponsesReport() throws Exception {
        // A new incarnation, so its counts start here whichever tests called as the worker before
        listing = listed(WORKER, "2026-10-01T10:00:00.000000000Z");
        registry.refresh();
        try {
            var before = System.currentTimeMillis();
            assertEquals(200, post("/v1/messages?mode=stream"));
            assertEquals(200, post("/v1/messages"));
            assertEquals(529, post("/v1/messages?mode=fail"));
            // Not model calls: an idle agent's other traffic must not make it look busy
            assertEquals(200, post("/v1/messages/count_tokens"));
            assertEquals(200, post("/api/oauth/profile"));

            var worker = activity().path("instances").path(WORKER);
            assertEquals(3, worker.path("requests").asLong(0), worker.toString());
            assertTrue(worker.path("counting_since").asLong(0) >= before, "from its first call");
            assertEquals(0, worker.path("in_flight").asLong(0));
            assertTrue(worker.path("last_request_at").asLong(0) >= before);
            assertTrue(worker.path("last_response_at").asLong(0) >= worker.path("last_request_at").asLong(0));
            // The stream's message_delta counts are cumulative: 30, not 1 + 12 + 30; a failed call reports none
            assertEquals(107, worker.path("input_tokens").asLong(0));
            assertEquals(33, worker.path("output_tokens").asLong(0));
            assertEquals(4000, worker.path("cache_read_input_tokens").asLong(0));
            assertEquals(50, worker.path("cache_creation_input_tokens").asLong(0));

            // Asked uncompressed, or the usage could not be read; other requests are left as sent
            assertEquals("identity", acceptEncodings.get("/v1/messages"));
            assertEquals("gzip, br", acceptEncodings.get("/api/oauth/profile"));
        } finally {
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void aNewInstanceWithAReusedNameStartsItsOwnCounts() throws Exception {
        // Hand-named: destroyed and branched again under the same name, with no /activity read
        // in between to drop the first one's counters (#1063)
        try {
            listing = listed("box", "2026-10-07T10:00:00.000000000Z");
            registry.refresh();
            assertEquals(200, post("/v1/messages"));
            var first = activity().path("instances").path("box");

            listing = listed("box", "2026-10-07T11:00:00.000000000Z");
            registry.refresh();
            var reborn = System.currentTimeMillis();
            assertEquals(200, post("/v1/messages"));
            var second = activity().path("instances").path("box");

            assertEquals(1, second.path("requests").asLong(0), "the old instance's call is not its: " + second);
            assertEquals(3, second.path("output_tokens").asLong(0), second.toString());
            assertTrue(second.path("counting_since").asLong(0) >= reborn,
                    "a later start, so a client subtracting across the two does not: " + first + " / " + second);
        } finally {
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }

    @Test
    void anOlderInstanceRenamedOntoTheNameStartsItsOwnCounts() throws Exception {
        // A rename keeps the instance's own created_at, here older than the destroyed one's
        try {
            listing = listed("w", "2026-10-07T12:00:00.000000000Z");
            registry.refresh();
            assertEquals(200, post("/v1/messages"));

            listing = listed("w", "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
            var renamed = System.currentTimeMillis();
            assertEquals(200, post("/v1/messages"));
            var w = activity().path("instances").path("w");

            assertEquals(1, w.path("requests").asLong(0), "its own call, not the destroyed one's: " + w);
            assertTrue(w.path("counting_since").asLong(0) >= renamed, w.toString());
        } finally {
            listing = listed(WORKER, "2026-10-01T09:00:00.000000000Z");
            registry.refresh();
        }
    }
}
