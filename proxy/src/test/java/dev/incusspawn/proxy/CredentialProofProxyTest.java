package dev.incusspawn.proxy;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.GitHubGitCredential;
import dev.incusspawn.tool.HermeticGit;
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
    static final String GEMINI = "generativelanguage.googleapis.com";
    static final String VERTEX = "aiplatform.googleapis.com";
    /** What Pi's google-vertex provider sends with an API key: Vertex's express form, no project. */
    static final String EXPRESS_CALL = "/v1/publishers/google/models/gemini-3.7-flash:streamGenerateContent?alt=sse";
    /** Where Claude Code sends a Vertex model call for the {@link #claude} Vertex account. */
    static final String CLAUDE_ON_VERTEX = "/v1/projects/outer-project/locations/us-east5/publishers/anthropic/models/";
    static final String SECRET = ProofTestSupport.SECRET;
    static final String BODY = "{\"model\":\"claude-test\",\"messages\":[],\"max_tokens\":7}";
    /** What Pi's google-vertex provider sends: every top-level field it uses, a function call and its answer. */
    static final String GEMINI_BODY = """
            {"contents":[{"role":"user","parts":[{"text":"hi"},{"inlineData":{"mimeType":"image/png","data":"iVBO"}}]},
            {"role":"model","parts":[{"functionCall":{"name":"read","args":{"fileData":"gs://x"}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"read","response":{"output":"ok"},
            "parts":[{"inlineData":{"mimeType":"image/png","data":"iVBO"}}]}}]}],
            "systemInstruction":{"parts":[{"text":"be brief"}]},
            "generationConfig":{"maxOutputTokens":7,"thinkingConfig":{"includeThoughts":true}},
            "tools":[{"functionDeclarations":[{"name":"read","parametersJsonSchema":{"type":"object"}}]}],
            "toolConfig":{"functionCallingConfig":{"mode":"AUTO"}}}""";
    static final String GITHUB_CONFIG = """
            github:
              accounts:
                outer:
                  token: ghp_outer
              default: outer
            """;
    static final String GEMINI_CONFIG = """
            gemini:
              accounts:
                outer:
                  apiKey: gemini-test-outer
                work:
                  apiKey: gemini-test-work
              default: outer
            """;
    @TempDir static Path home;
    static String previousHome;
    static Vertx vertx;
    static HttpServer upstream;
    static HttpClient client;
    static WebSocketClient wsClient;
    static int upstreamPort;
    static volatile CompletableFuture<Received> received;
    /** Upstream answers the next plain HTTP request with a 401, as Vertex does a stale token. */
    static volatile boolean unauthorizedOnce;
    static final AtomicInteger upstreamRequests = new AtomicInteger();
    static final AtomicInteger gitChallenges = new AtomicInteger();
    static final String GIT_HEAD = "a".repeat(40);
    record Received(String uri, String host, String authorization, String key, String googleKey, String body) {}
    /** The head of the last plain HTTP request upstream received, for headers {@link Received} does not name. */
    static volatile io.vertx.core.MultiMap receivedHeaders;
    /** {@link #http} sends no Content-Type of its own. */
    volatile boolean noContentType;
    /** Sent with every {@link #http} request beside its own header. */
    volatile Map<String, String> extraHeaders = Map.of();
    MitmProxy proxy;
    InstanceRegistry registry;
    volatile String stamp;
    volatile String generation = "ab".repeat(32);
    volatile boolean failListings;
    volatile CompletableFuture<Void> holdListing;
    final CompletableFuture<Void> listingEntered = new CompletableFuture<>();
    volatile String address = "127.0.0.1";
    volatile String account;
    volatile String geminiAccount;
    String bakedIdentity;
    /** The {@code gemini} section {@link #serve} writes. */
    String geminiConfig = GEMINI_CONFIG;
    final AtomicInteger tokenFetches = new AtomicInteger();
    final AtomicInteger listings = new AtomicInteger();

    @BeforeAll static void start() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".config/incus-spawn"));
        vertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(new AddressResolverOptions()
                .setHostsValue(Buffer.buffer("127.0.0.1 " + GITHUB + " " + ANTHROPIC + " " + GEMINI + " " + VERTEX + "\n"))));
        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(CertificateAuthority.loadOrCreate(), ANTHROPIC)))
                .requestHandler(req -> req.body().onSuccess(body -> {
                    if (req.path().startsWith("/git-proof-private.git/")) {
                        var expected = "Basic " + Base64.getEncoder().encodeToString(
                                "x-access-token:ghp_outer".getBytes(StandardCharsets.UTF_8));
                        if (!expected.equals(req.getHeader("Authorization"))) {
                            gitChallenges.incrementAndGet();
                            req.response().setStatusCode(401).putHeader("WWW-Authenticate", "Basic realm=GitHub").end();
                            return;
                        }
                        received.complete(new Received(req.uri(), req.getHeader("Host"), req.getHeader("Authorization"),
                                null, null, body.toString()));
                        req.response().putHeader("Content-Type", "text/plain").end(
                                req.path().endsWith("/HEAD") ? "ref: refs/heads/main\n" : GIT_HEAD + "\trefs/heads/main\n");
                        return;
                    }
                    upstreamRequests.incrementAndGet();
                    if (unauthorizedOnce) {
                        unauthorizedOnce = false;
                        req.response().setStatusCode(401).end("stale token");
                        return;
                    }
                    receivedHeaders = io.vertx.core.MultiMap.caseInsensitiveMultiMap().addAll(req.headers());
                    received.complete(new Received(req.uri(), req.getHeader("Host"), req.getHeader("Authorization"),
                            req.getHeader("x-api-key"), req.getHeader("x-goog-api-key"), body.toString()));
                    req.response().end("{}");
                })).webSocketHandler(ws -> {
                    received.complete(new Received(ws.uri(), ws.headers().get("Host"), ws.headers().get("Authorization"),
                            ws.headers().get("x-api-key"), ws.headers().get("x-goog-api-key"), ""));
                    ws.close();
                });
        upstreamPort = NpmCacheProxyTest.listen(upstream);
        client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false),
                new PoolOptions().setHttp1MaxSize(16));
        wsClient = vertx.createWebSocketClient(new WebSocketClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
    }

    @AfterAll static void stop() throws Exception {
        try { if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        finally { System.setProperty("user.home", previousHome); }
    }

    @BeforeEach void resetUpstream() {
        unauthorizedOnce = false;
        upstreamRequests.set(0);
    }

    @AfterEach void stopProxy() { if (proxy != null) proxy.stop(); }

    @Test
    void plainGitAnswersTheChallengeWithTheDeliveredProof(@TempDir Path guest) throws Exception {
        serve(claude("api-key"));
        received = new CompletableFuture<>();
        gitChallenges.set(0);
        var port = proxy.mitmPort();
        var config = guest.resolve("gitconfig");
        var proofs = guest.resolve("proof-tokens");
        Files.writeString(proofs, InstanceSecret.guestEnv(new InstanceSecret.Delivery(SECRET, SECRET), false,
                List.of(new ProofToken.Placeholder("GH_TOKEN", "gho_", "github")))
                .get(InstanceSecret.PROOFS_DELIVERY_ENV));
        var ca = guest.resolve("ca.crt");
        Files.writeString(ca, CertificateAuthority.loadOrCreate().caCertPem());
        var env = Map.of("GIT_SSL_CAINFO", ca.toString());
        var command = List.of("git", "-c", "http.proxy=", "-c",
                "http.curloptResolve=github.com:" + port + ":127.0.0.1", "ls-remote",
                "https://github.com:" + port + "/git-proof-private.git", "refs/heads/main");
        var unauthenticated = gitProcess(command, config, env);
        assertNotEquals(0, unauthenticated.exit());
        assertFalse(received.isDone(), "an anonymous Git request receives no host credential");

        // The fixture listens on an ephemeral port instead of 443; keep its helper scope identical.
        var install = GitHubGitCredential.INSTALL_SCRIPT.replace("github.com", "github.com:" + port)
                .replace("git config --system", "git config --file " + Container.shellQuote(config.toString()))
                .replace(InstanceSecret.PROOFS_PATH, proofs.toString());
        assertEquals(0, gitProcess(List.of("sh", "-c", install), config, env).exit());
        var authenticated = gitProcess(command, config, env);
        assertEquals(0, authenticated.exit(), authenticated.output());
        assertEquals(GIT_HEAD + "\trefs/heads/main\n", authenticated.output());
        assertTrue(gitChallenges.get() >= 2, "both invocations begin anonymously");
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "x-access-token:ghp_outer".getBytes(StandardCharsets.UTF_8)),
                received.get(10, TimeUnit.SECONDS).authorization());
    }

    private record GitResult(int exit, String output) {}

    private static GitResult gitProcess(List<String> command, Path config, Map<String, String> env) throws Exception {
        var pb = HermeticGit.configure(new ProcessBuilder(command).directory(config.getParent().toFile())
                .redirectErrorStream(true), config);
        pb.environment().putAll(env);
        var process = pb.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Git must not wait for input");
            return new GitResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test void unexpectedRefusalsDoNotPrintExceptionMessagesOrCauses() {
        var header = "Bearer " + ProofTestSupport.token("github", "gho_");
        var failure = new IllegalArgumentException("invalid header value: " + header,
                new RuntimeException("credential: " + header));
        assertEquals(": IllegalArgumentException", MitmProxy.refusal(failure));
    }

    void serve(String claude) throws Exception {
        Files.writeString(home.resolve(".config/incus-spawn/config.yaml"), GITHUB_CONFIG + geminiConfig + claude);
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
                if (geminiAccount != null) config.put(Metadata.accountKey("gemini"), geminiAccount);
                if (bakedIdentity != null) config.put(Metadata.accountIdentityKey("claude"), bakedIdentity);
                return JqValues.parse("[" + JqValues.toPrettyJsonString(JqObject.builder(3)
                        .put("name", "outer-instance").put("status", "Running").put("config", config.build()).build()) + "]");
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
        for (var host : List.of(GITHUB, "github.com", ANTHROPIC, GEMINI, VERTEX, "us-east5-aiplatform.googleapis.com")) {
            proxy.overrideUpstream(host, "127.0.0.1", upstreamPort);
        }
    }

    static String claude(String type) {
        return "claude:\n  accounts:\n    outer:\n      type: " + type
                + "\n      apiKey: sk-ant-outer\n      oauthToken: sk-ant-oat01-outer\n"
                + "      vertexProjectId: outer-project\n      cloudMlRegion: us-east5\n  default: outer\n";
    }

    int http(String host, String header, String value) throws Exception {
        return http(HttpMethod.POST, host, "/v1/messages?keep=1", header, value);
    }

    int http(HttpMethod method, String host, String uri, String header, String value) throws Exception {
        return http(method, host, uri, header, value, BODY);
    }

    /** As {@link #http(HttpMethod, String, String, String, String)}, with {@code body} (null: none). */
    int http(HttpMethod method, String host, String uri, String header, String value, String body) throws Exception {
        received = new CompletableFuture<>();
        var options = new RequestOptions().setMethod(method)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(host).setPort(443).setURI(uri);
        return client.request(options).compose(req -> {
            if (value != null) req.putHeader(header, value);
            // As every SDK sends a JSON body; a test's own extraHeaders replace it
            if (body != null && !noContentType) req.putHeader("Content-Type", "application/json");
            extraHeaders.forEach(req::putHeader);
            return body == null ? req.send() : req.send(body);
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
            var ws = wsClient.connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
            for (var ns : List.of("github", "claude", "gemini")) {
                var prefix = switch (ns) { case "github" -> "gho_"; case "gemini" -> "gemini-"; default -> "sk-ant-"; };
                var host = switch (ns) { case "github" -> GITHUB; case "gemini" -> GEMINI; default -> ANTHROPIC; };
                var header = switch (ns) { case "github" -> "Authorization"; case "gemini" -> "x-goog-api-key"; default -> "x-api-key"; };
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
        var actual = switch (c.header()) {
            case "Authorization" -> got.authorization();
            case "x-goog-api-key" -> got.googleKey();
            default -> got.key();
        };
        var credential = switch (c.host()) {
            case GITHUB -> "Bearer ghp_outer";
            case GEMINI -> "gemini-test-outer";
            default -> "sk-ant-outer";
        };
        assertEquals(c.proven() ? credential : c.value(), actual);
        assertEquals("/v1/messages?keep=1", got.uri());
        assertTrue(got.host().startsWith(c.host()));
        if (!c.websocket()) assertEquals(BODY, got.body());
        assertEquals(0, tokenFetches.get());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void geminiUsesThePinnedAccountAndPreservesIndependentBearerAuth(boolean websocket) throws Exception {
        geminiAccount = "work";
        serve(claude("vertex"));
        var got = request(websocket, GEMINI, "x-goog-api-key", ProofTestSupport.token("gemini", "gemini-"));
        assertEquals("gemini-test-work", got.googleKey());
        assertNull(got.authorization());
        assertNull(got.key());
        var independent = request(websocket, GEMINI, "Authorization", "Bearer google-inner-account");
        assertEquals("Bearer google-inner-account", independent.authorization());
        assertNull(independent.googleKey());
        assertEquals(0, tokenFetches.get());
    }

    /**
     * An instance on a Google account (#2443) sending an API key request, as agy does while it is
     * still set up for a key: the account has no key to inject, so the request is answered here,
     * with why in the error shape agy shows, and the guest's proof never reaches Google.
     */
    @Test void aGoogleAccountsKeyRequestIsAnsweredWithWhyAndNeverSent() throws Exception {
        geminiConfig = GEMINI_CONFIG.replace("  default: outer", "    home:\n      type: google\n  default: outer");
        geminiAccount = "home";
        serve("");
        var proof = ProofTestSupport.token("gemini", "gemini-");
        received = new CompletableFuture<>();
        var options = new RequestOptions().setMethod(HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(proxy.mitmPort(), "127.0.0.1"))
                .setHost(GEMINI).setPort(443).setURI("/v1beta/models/m:generateContent");
        var answer = client.request(options).compose(req -> req.putHeader("x-goog-api-key", proof)
                        .putHeader("Content-Type", "application/json").send(GEMINI_BODY))
                .compose(resp -> resp.body().map(b -> resp.statusCode() + " " + b))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(answer.startsWith("403 "), answer);
        var error = JqValues.parseStrict(answer.substring(4)).path("error");
        assertEquals("PERMISSION_DENIED", error.path("status").asText());
        var message = error.path("message").asText();
        assertTrue(message.contains("gemini account 'home', a Google account"), message);
        assertTrue(message.contains("isx account set outer-instance gemini="), message);
        assertTrue(message.contains("isx shell outer-instance"), message);
        assertFalse(received.isDone(), "nothing may be sent to Google, the proof least of all");

        // A key of the guest's own is not isx's to refuse
        assertEquals("gemini-inner-key", request(false, GEMINI, "x-goog-api-key", "gemini-inner-key").googleKey());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aMissingGeminiPinNeverFallsBackAndDoesNotBlockIndependentKeys(boolean websocket) throws Exception {
        geminiAccount = "removed";
        serve("");
        assertEquals("gemini-inner-key", request(websocket, GEMINI, "x-goog-api-key", "gemini-inner-key").googleKey());
        var proof = ProofTestSupport.token("gemini", "gemini-");
        received = new CompletableFuture<>();
        if (websocket) {
            var options = new WebSocketConnectOptions().setHost(GEMINI).setPort(proxy.mitmPort()).setSsl(true)
                    .setURI("/v1beta/models").addHeader("x-goog-api-key", proof);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> wsClient.connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("502"), error.toString());
        } else {
            assertEquals(502, http(GEMINI, "x-goog-api-key", proof));
        }
        assertFalse(received.isDone(), "no default credential may be used for a missing pin");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void geminiProofsRequireTheRegisteredAddressAndCurrentVerifier(boolean websocket) throws Exception {
        serve("");
        var old = ProofTestSupport.token("gemini", "gemini-");
        assertEquals("gemini-test-outer", request(websocket, GEMINI, "x-goog-api-key", old).googleKey());
        var reset = "77".repeat(32);
        stamp = ProofVerifier.stamp(reset, ProofToken.declared());
        registry.refresh();
        assertEquals(old, request(websocket, GEMINI, "x-goog-api-key", old).googleKey());
        var fresh = "gemini-isx_" + ProofToken.derive(reset, "gemini");
        assertEquals("gemini-test-outer", request(websocket, GEMINI, "x-goog-api-key", fresh).googleKey());
        address = "127.0.0.2";
        registry.refresh();
        assertEquals(fresh, request(websocket, GEMINI, "x-goog-api-key", fresh).googleKey());
    }

    /** Pi's google-vertex provider: its proof buys a Gemini call in the Vertex account's project, signed on the host. */
    @Test void geminiOnVertexSpendsTheVertexAccountWithTheHostsGcloudToken() throws Exception {
        serve(claude("vertex"));
        assertEquals(200, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", ProofTestSupport.token("claude", "AIza"),
                GEMINI_BODY));
        var got = received.get(10, TimeUnit.SECONDS);
        assertEquals("/v1/projects/outer-project/locations/us-east5/publishers/google/models/gemini-3.7-flash"
                + ":streamGenerateContent?alt=sse", got.uri());
        assertEquals("us-east5-aiplatform.googleapis.com", got.host());
        assertEquals("Bearer ya29.outer", got.authorization());
        assertNull(got.googleKey(), "the proof never leaves the proxy");
        assertEquals(GEMINI_BODY, got.body());
        assertEquals(1, tokenFetches.get());
    }

    /** Only the route names the project the token is charged to: no quota project, query or method override of the guest's. */
    @Test void geminiOnVertexKeepsOnlyTheResponseFormatFromTheGuestsQueryAndNoBillingHeaders() throws Exception {
        serve(claude("vertex"));
        extraHeaders = Map.of("x-goog-user-project", "someone-else", "X-HTTP-Method-Override", "DELETE");
        assertEquals(200, http(HttpMethod.POST, VERTEX, "/v1/publishers/google/models/gemini-3.7-flash:streamGenerateContent"
                + "?userProject=someone-else&alt=sse&key=AIza-other&%24alt=json&alt=sse",
                "x-goog-api-key", ProofTestSupport.token("claude", "AIza"), GEMINI_BODY));
        var got = received.get(10, TimeUnit.SECONDS);
        assertEquals("/v1/projects/outer-project/locations/us-east5/publishers/google/models/gemini-3.7-flash"
                + ":streamGenerateContent?alt=sse&alt=sse", got.uri());
        assertNull(receivedHeaders.get("x-goog-user-project"));
        assertNull(receivedHeaders.get("X-HTTP-Method-Override"));
        assertEquals("Bearer ya29.outer", got.authorization());
    }

    /**
     * The host's gcloud token reaches every project its user can, and a body can name such a project's
     * data: a proven call is refused, before anything leaves, unless its body is what Pi sends (#1925).
     */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"contents\":[],\"cachedContent\":\"projects/other-project/locations/us-east5/cachedContents/x\"}",
            "{\"contents\":[],\"cached_content\":\"projects/other-project/locations/us-east5/cachedContents/x\"}",
            "{\"contents\":[],\"tools\":[{\"retrieval\":{\"vertexRagStore\":{\"ragResources\":"
                    + "[{\"ragCorpus\":\"projects/other-project/locations/us-east5/ragCorpora/1\"}]}}}]}",
            "{\"tools\":[{\"functionDeclarations\":[]},{\"retrieval\":{\"vertexAiSearch\":"
                    + "{\"datastore\":\"projects/other-project/locations/global/collections/c/dataStores/d\"}}}]}",
            "{\"contents\":[{\"parts\":[{\"fileData\":{\"fileUri\":\"gs://other-bucket/secret.pdf\"}}]}]}",
            "{\"contents\":[{\"parts\":[{\"fil\\u0065Data\":{\"fileUri\":\"gs://other-bucket/secret.pdf\"}}]}]}",
            "{\"contents\":[], \"labels\":{}}", "[]", "", "{\"contents\":["})
    void geminiOnVertexRefusesABodyThatCouldNameAnotherProjectsData(String body) throws Exception {
        serve(claude("vertex"));
        var status = http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", ProofTestSupport.token("claude", "AIza"), body);
        assertTrue(status == 403 || status == 400, status + " for " + body);
        assertFalse(received.isDone(), "nothing reaches Vertex");
        assertEquals(0, upstreamRequests.get());
        assertEquals(0, tokenFetches.get());
    }

    /** A body the proxy cannot read is never signed. */
    @Test void geminiOnVertexRefusesAnEncodedBody() throws Exception {
        serve(claude("vertex"));
        extraHeaders = Map.of("Content-Encoding", "gzip");
        assertEquals(403, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", ProofTestSupport.token("claude", "AIza"),
                GEMINI_BODY));
        assertFalse(received.isDone());
        assertEquals(0, tokenFetches.get());
    }

    /** Vertex would read a protobuf or non-UTF-8 body as another format or text than the check read: refused. */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "application/x-protobuf", "application/json+protobuf", "text/plain", "application/jsonx", "",
            "application/json; charset=utf-7", "application/json; charset=shift_jis", "application/json; charset=utf-16",
            "application/json; charset=utf-8; charset=utf-7", "application/json; charset=utf-8; foo=bar"})
    void geminiOnVertexRefusesABodyThatIsNotJson(String contentType) throws Exception {
        serve(claude("vertex"));
        noContentType = contentType.isEmpty();
        if (!noContentType) extraHeaders = Map.of("Content-Type", contentType);
        assertEquals(403, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", ProofTestSupport.token("claude", "AIza"),
                GEMINI_BODY));
        assertFalse(received.isDone());
        assertEquals(0, tokenFetches.get());
    }

    /** JSON in UTF-8, which the check reads, however the type spells it. */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "application/json", "Application/JSON; charset=utf-8", "application/json;charset=\"UTF-8\""})
    void geminiOnVertexSendsAJsonTypedBody(String contentType) throws Exception {
        serve(claude("vertex"));
        extraHeaders = Map.of("Content-Type", contentType);
        assertEquals(200, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", ProofTestSupport.token("claude", "AIza"),
                GEMINI_BODY));
        assertEquals(GEMINI_BODY, received.get(10, TimeUnit.SECONDS).body());
    }

    /** The body is held, so a 401 from a stale token sends it again with a fresh one, as Claude's Vertex calls are. */
    @Test void geminiOnVertexSendsTheBodyAgainAfterA401() throws Exception {
        serve(claude("vertex"));
        unauthorizedOnce = true;
        assertEquals(200, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", ProofTestSupport.token("claude", "AIza"),
                GEMINI_BODY));
        assertEquals(GEMINI_BODY, received.get(10, TimeUnit.SECONDS).body());
        assertEquals(2, upstreamRequests.get());
        assertEquals(2, tokenFetches.get());
    }

    /** Without the instance's own claude proof, the request keeps its authentication and its destination. */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "", "AIza-a-real-inner-key", "AIzaplaceholder", "AIzaisx_bad"})
    void geminiOnVertexWithoutAClaudeProofIsRelayedUnchanged(String value) throws Exception {
        serve(claude("vertex"));
        var key = value.isEmpty() ? null : value;
        for (var presented : java.util.Arrays.asList(key, ProofTestSupport.token("gemini", "AIza"),
                "AIzaisx_" + ProofToken.derive("55".repeat(32), "claude"))) {
            assertEquals(200, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", presented));
            var got = received.get(10, TimeUnit.SECONDS);
            assertEquals(EXPRESS_CALL, got.uri());
            assertTrue(got.host().startsWith(VERTEX), got.host());
            assertEquals(presented, got.googleKey());
            assertNull(got.authorization());
        }
        assertEquals(0, tokenFetches.get());
    }

    /** A proven call on an account that is not Vertex AI, or that is no model call, never reaches Google. */
    @Test void geminiOnVertexRefusesWhatTheAccountOrTheRouteDoesNotServe() throws Exception {
        var proof = ProofTestSupport.token("claude", "AIza");
        serve(claude("api-key"));
        assertEquals(502, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key", proof));
        assertFalse(received.isDone());
        proxy.stop();
        serve(claude("vertex"));
        for (var call : List.of(
                Map.entry(HttpMethod.POST, "/v1/projects/someone-else/locations/us-east5/publishers/google/models/g:generateContent"),
                Map.entry(HttpMethod.POST, "/v1/projects/outer-project/locations/us-east5/endpoints"),
                Map.entry(HttpMethod.DELETE, "/v1/publishers/google/models/gemini-3.7-flash:generateContent"),
                Map.entry(HttpMethod.POST, "/v1/publishers/google/models/gemini-3.7-flash:predict"),
                Map.entry(HttpMethod.POST, "/v1/publishers/anthropic/models/claude:streamRawPredict"),
                Map.entry(HttpMethod.POST, "/v1/publishers/google/models/../../projects/x:generateContent"),
                Map.entry(HttpMethod.GET, "/v1/publishers/google/models"))) {
            assertEquals(403, http(call.getKey(), VERTEX, call.getValue(), "x-goog-api-key", proof), call.toString());
            assertFalse(received.isDone(), call.toString());
        }
        assertEquals(0, tokenFetches.get());
    }

    /** A Claude call already in Vertex's form, in the account's project and region, is signed with the host's gcloud token. */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "claude-opus-5-5:streamRawPredict", "claude-haiku-4-5@20251001:rawPredict", "count-tokens:rawPredict"})
    void claudeOnVertexServesModelCallsInTheAccountsProjectAndRegion(String call) throws Exception {
        serve(claude("vertex"));
        assertEquals(200, http(HttpMethod.POST, ANTHROPIC, CLAUDE_ON_VERTEX + call,
                "Authorization", "Bearer " + ProofTestSupport.token("claude", "ya29.")));
        var got = received.get(10, TimeUnit.SECONDS);
        assertEquals(CLAUDE_ON_VERTEX + call.replace("@20251001", ""), got.uri());
        assertEquals("us-east5-aiplatform.googleapis.com", got.host());
        assertEquals("Bearer ya29.outer", got.authorization());
    }

    /**
     * The host's gcloud token reaches every project its user can: a proven model call naming another
     * project or region is spent in the account's own (#1921), as a guest built for another account sends it.
     */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/v1/projects/other-project/locations/us-east5/", "/v1/projects/outer-project/locations/europe-west1/",
            "/v1/projects/../locations/../"})
    void claudeOnVertexSpendsOnlyTheAccountsProjectAndRegion(String elsewhere) throws Exception {
        serve(claude("vertex"));
        assertEquals(200, http(HttpMethod.POST, ANTHROPIC, elsewhere + "publishers/anthropic/models/claude-opus-5-5:rawPredict",
                "Authorization", "Bearer " + ProofTestSupport.token("claude", "ya29.")));
        var got = received.get(10, TimeUnit.SECONDS);
        assertEquals(CLAUDE_ON_VERTEX + "claude-opus-5-5:rawPredict", got.uri());
        assertEquals("us-east5-aiplatform.googleapis.com", got.host());
        assertEquals("Bearer ya29.outer", got.authorization());
    }

    /** A proven call in Vertex's form buys Claude model calls alone, refused before anything is sent (#1921). */
    @Test void claudeOnVertexRefusesAnyOtherApiOrMethodBeforeAnythingIsSent() throws Exception {
        serve(claude("vertex"));
        var proof = "Bearer " + ProofTestSupport.token("claude", "ya29.");
        var bodies = new java.util.HashMap<String, String>();
        // On the translation route: the guest's path and method must never be the ones signed
        var dataset = "/v1/messages/../projects/other-project/locations/us-east5/datasets/d";
        var endpoints = "/v1/messages/../projects/other-project/locations/us-east5/endpoints";
        var encoded = "/v1/messages%2F..%2Fprojects/other-project/locations/us-east5/datasets/d";
        bodies.put(dataset, null);
        bodies.put(endpoints, "[]");
        bodies.put(encoded, null);
        for (var call : List.of(
                Map.entry(HttpMethod.DELETE, dataset),
                Map.entry(HttpMethod.POST, endpoints),
                Map.entry(HttpMethod.GET, encoded),
                Map.entry(HttpMethod.POST, "/v1/messages/batches"),
                Map.entry(HttpMethod.POST, "/v1/messages/"),
                Map.entry(HttpMethod.GET, "/v1/messages"),
                Map.entry(HttpMethod.PUT, "/v1/messages/count_tokens"),
                Map.entry(HttpMethod.POST, "/v1/projects/other-project/locations/us-east5/endpoints"),
                Map.entry(HttpMethod.POST, "/v1/projects/outer-project/locations/us-east5/endpoints"),
                Map.entry(HttpMethod.POST, "/v1/projects/outer-project/locations/us-east5/publishers/google/models/gemini:generateContent"),
                Map.entry(HttpMethod.POST, "/v1/projects/outer-project/locations/us-east5/publishers/anthropic/models/claude:predict"),
                Map.entry(HttpMethod.POST, "/v1/projects/outer-project/locations/us-east5/endpoints/e:rawPredict"),
                Map.entry(HttpMethod.POST, CLAUDE_ON_VERTEX + "../../../../../../other-project/locations/us-east5/publishers/anthropic/models/c:rawPredict"),
                Map.entry(HttpMethod.POST, CLAUDE_ON_VERTEX + "claude%2F..%2F..%2Fx:rawPredict"),
                Map.entry(HttpMethod.GET, CLAUDE_ON_VERTEX + "claude:rawPredict"),
                Map.entry(HttpMethod.DELETE, "/v1/projects/outer-project/locations/us-east5/datasets/d"))) {
            var body = bodies.containsKey(call.getValue()) ? bodies.get(call.getValue()) : BODY;
            assertEquals(403, http(call.getKey(), ANTHROPIC, call.getValue(), "Authorization", proof, body), call.toString());
            assertFalse(received.isDone(), call.toString());
        }
        assertEquals(0, tokenFetches.get());
    }

    /** A proven message call is signed only once translated: a body that is no JSON object is refused, unsent (#1921). */
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource(nullValues = "none", value = {
            "/v1/messages, none", "/v1/messages, []", "/v1/messages, '\"x\"'", "/v1/messages, '  '",
            "/v1/messages/count_tokens, none", "/v1/messages/count_tokens, 7"})
    void claudeOnVertexSignsOnlyATranslatedMessageCall(String path, String body) throws Exception {
        serve(claude("vertex"));
        var proof = "Bearer " + ProofTestSupport.token("claude", "ya29.");
        assertEquals(403, http(HttpMethod.POST, ANTHROPIC, path, "Authorization", proof, body));
        assertFalse(received.isDone());
        assertEquals(0, tokenFetches.get());
    }

    /**
     * A standard count_tokens call goes to Vertex's token count in the account's project, never to the
     * model's rawPredict, a generation (#1938): the model stays in the body, without its date suffix,
     * and neither stream nor a generation's own fields reach it.
     */
    @Test void claudeOnVertexTranslatesCountTokensToVertexsTokenCount() throws Exception {
        serve(claude("vertex"));
        assertEquals(200, http(HttpMethod.POST, ANTHROPIC, "/v1/messages/count_tokens", "Authorization",
                "Bearer " + ProofTestSupport.token("claude", "ya29."),
                "{\"model\":\"claude-test-20250101\",\"messages\":[],\"max_tokens\":7,\"stream\":true}"));
        var got = received.get(10, TimeUnit.SECONDS);
        assertEquals(CLAUDE_ON_VERTEX + "count-tokens:rawPredict", got.uri());
        assertEquals("{\"messages\":[],\"model\":\"claude-test\",\"anthropic_version\":\"vertex-2023-10-16\"}", got.body());
        assertEquals("Bearer ya29.outer", got.authorization());
    }

    /** No quota project, query parameter or method override of the guest's reaches a signed Claude call on Vertex. */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/v1/messages", CLAUDE_ON_VERTEX + "claude-opus-5-5:streamRawPredict"})
    void claudeOnVertexKeepsNoBillingOverrideOfTheGuests(String path) throws Exception {
        serve(claude("vertex"));
        extraHeaders = Map.of("x-goog-user-project", "someone-else", "X-HTTP-Method-Override", "DELETE");
        assertEquals(200, http(HttpMethod.POST, ANTHROPIC, path + "?userProject=someone-else&alt=sse&%24alt=json",
                "Authorization", "Bearer " + ProofTestSupport.token("claude", "ya29.")));
        var got = received.get(10, TimeUnit.SECONDS);
        assertFalse(got.uri().contains("someone-else") || got.uri().contains("json"), got.uri());
        assertNull(receivedHeaders.get("x-goog-user-project"));
        assertNull(receivedHeaders.get("X-HTTP-Method-Override"));
        assertEquals("Bearer ya29.outer", got.authorization());
    }

    /** Without the instance's claude proof, a call in Vertex's form keeps its own authentication, destination and query. */
    @Test void claudeOnVertexWithoutAProofIsForwardedAsBefore() throws Exception {
        serve(claude("vertex"));
        var uri = "/v1/projects/other-project/locations/us-east5/endpoints?keep=1";
        assertEquals(200, http(HttpMethod.POST, ANTHROPIC, uri, "Authorization", "Bearer ya29.inner-own"));
        var got = received.get(10, TimeUnit.SECONDS);
        assertEquals(uri, got.uri());
        assertEquals("Bearer ya29.inner-own", got.authorization());
        assertEquals(0, tokenFetches.get());
    }

    /** Another namespace's proof at the Vertex domain buys that tool's own entry, never the Claude Vertex account. */
    @Test void geminiOnVertexNeedsTheClaudeProofItselfNotAnotherNamespaces() throws Exception {
        var tools = home.resolve(".config/incus-spawn/tools");
        Files.createDirectories(tools);
        var definition = tools.resolve("vertex-key.yaml");
        Files.writeString(definition, """
                name: vertex-key
                env:
                  - name: GCP_API_KEY
                    value: gcp-placeholder
                proxy:
                  config-namespace: gcp
                  placeholders:
                    - env: GCP_API_KEY
                      prefix: gcp-
                  configuration:
                    key:
                      config-path: apiKey
                      secret: true
                  auth:
                    - domains: [aiplatform.googleapis.com]
                      type: header
                      name: x-goog-api-key
                      value: '${key}'
                """);
        try {
            serve(claude("vertex") + "gcp:\n  apiKey: gcp-host-key\n");
            assertEquals(200, http(HttpMethod.POST, VERTEX, EXPRESS_CALL, "x-goog-api-key",
                    ProofTestSupport.token("gcp", "gcp-")));
            var got = received.get(10, TimeUnit.SECONDS);
            assertEquals(EXPRESS_CALL, got.uri());
            assertEquals("gcp-host-key", got.googleKey());
            assertNull(got.authorization());
            assertEquals(0, tokenFetches.get());
        } finally {
            Files.deleteIfExists(definition);
        }
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
                    () -> wsClient.connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
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

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void onMacOSAValidProofFromAnAddressThatIsNoInstanceIdentifiesTheCaller(boolean websocket) throws Exception {
        // #1582: the VM NATs every container to its own address, which names no instance
        address = "10.166.11.2";
        serve(claude("api-key"));
        proxy.proofIdentifies = (remote, local) -> true;
        var proof = "Bearer " + ProofTestSupport.token("github", "gho_");
        assertEquals("Bearer ghp_outer", request(websocket, GITHUB, "Authorization", proof).authorization());
        var foreign = "Bearer gho_isx_" + ProofToken.derive("55".repeat(32), "github");
        assertEquals(foreign, request(websocket, GITHUB, "Authorization", foreign).authorization());
        var otherNamespace = "Bearer gho_isx_" + ProofToken.derive(SECRET, "claude");
        assertEquals(otherNamespace, request(websocket, GITHUB, "Authorization", otherNamespace).authorization());
    }

    @Test void onlyMacOSCallersOtherThanTheHostMayBeIdentifiedByTheirProofAlone() {
        assertTrue(MitmProxy.proofMayIdentify(true, "192.168.64.21", "192.168.64.1"));
        assertFalse(MitmProxy.proofMayIdentify(false, "192.168.64.21", "192.168.64.1"), "Linux keeps the address check");
        assertFalse(MitmProxy.proofMayIdentify(true, "192.168.64.1", "192.168.64.1"), "the host, from the bridge address");
        assertFalse(MitmProxy.proofMayIdentify(true, "127.0.0.1", "127.0.0.1"));
        assertFalse(MitmProxy.proofMayIdentify(true, "::ffff:127.0.0.1", "192.168.64.1"));
        assertFalse(MitmProxy.proofMayIdentify(true, "::1", "192.168.64.1"));
        assertFalse(MitmProxy.proofMayIdentify(true, null, "192.168.64.1"));
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
