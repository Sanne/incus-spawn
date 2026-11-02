package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.WebSocketConnectOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy side of host-owned ChatGPT sessions (#1332), against a stub of OpenAI's token
 * endpoint and the Codex backend: what a claimed request must prove, what it may do, and what a
 * refresh may write. The stub records every request, so "never relayed" is checked, not assumed.
 */
class ChatGptRouteTest {

    private static final String ACCOUNT = "sso";
    private static final String INSTANCE = "worker";
    private static final String SECRET = "11".repeat(32);
    private static final String OTHER_SECRET = "22".repeat(32);
    private static final String ACCOUNT_ID = "acct-test";

    @TempDir
    static Path home;
    static String originalHome;
    static Vertx vertx;
    static HttpServer stub;
    static MitmProxy proxy;
    static int mitmPort;
    static io.vertx.core.http.HttpClient wsClient;
    static ChatGptSessionStore store;
    static volatile String sessionId;

    /** Every request the stub saw: "METHOD host path | authorization | account". */
    static final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> refreshBodies = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> revokeBodies = new ConcurrentLinkedQueue<>();
    static final AtomicInteger refreshes = new AtomicInteger();
    static volatile String refreshError;
    static volatile int refreshErrorStatus = 400;
    static volatile boolean refreshAnswersEmpty;
    /** The last backend request the stub answered: its body's length and its headers. */
    record Received(int bodyLength, io.vertx.core.MultiMap headers) {}
    static volatile Received lastBackend;
    static volatile CompletableFuture<Void> holdBackend = CompletableFuture.completedFuture(null);
    /** Each relayed refresh's Accept-Encoding and Expect, as "encoding|expect". */
    static final ConcurrentLinkedQueue<String> refreshHeads = new ConcurrentLinkedQueue<>();
    static volatile CompletableFuture<Void> holdRefresh = CompletableFuture.completedFuture(null);
    static volatile boolean backendRefuses;
    static volatile long mintedTtlSeconds = 3600;

    @BeforeAll
    static void start() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        var configDir = Files.createDirectories(home.resolve(".config/incus-spawn"));
        Files.writeString(configDir.resolve("config.yaml"), """
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                    work:
                      type: chatgpt
                    keyed:
                      apiKey: "sk-real-api-key"
                  default: sso
                """);
        store = new ChatGptSessionStore();

        // Both names resolve to loopback, where nothing listens on 443: a dial that ignored the
        // override would fail here rather than leave the machine
        vertx = Vertx.vertx(new io.vertx.core.VertxOptions().setAddressResolverOptions(
                new io.vertx.core.dns.AddressResolverOptions().setHostsValue(
                        Buffer.buffer("127.0.0.1 chatgpt.com auth.openai.com\n"))));
        var ca = CertificateAuthority.loadOrCreate();
        stub = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, ChatGptRoute.CHATGPT)))
                .requestHandler(ChatGptRouteTest::answer)
                .webSocketHandler(ws -> {
                    // Decided before it is recorded: a test waits for the record, then clears the flag
                    var refuse = backendRefuses;
                    seen.add("WS " + ws.headers().get("Host") + " " + ws.path() + " | "
                            + ws.headers().get("Authorization") + " | " + ws.headers().get("ChatGPT-Account-Id"));
                    if (refuse) {
                        ws.reject(401);
                        return;
                    }
                    ws.textMessageHandler(msg -> ws.writeTextMessage("echo:" + msg));
                });
        int stubPort = NpmCacheProxyTest.listen(stub);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        proxy.overrideUpstream(ChatGptRoute.CHATGPT, "127.0.0.1", stubPort);
        proxy.overrideUpstream(ChatGptRoute.AUTH, "127.0.0.1", stubPort);
        ContainerTls.startInBackground(proxy);
        mitmPort = proxy.mitmPort();
        wsClient = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    /** A fresh session for every test, and the worker instance bound to it with SECRET's proofs. */
    @BeforeEach
    void freshSession() {
        seen.clear();
        refreshBodies.clear();
        revokeBodies.clear();
        refreshes.set(0);
        refreshError = null;
        refreshErrorStatus = 400;
        refreshAnswersEmpty = false;
        holdBackend = CompletableFuture.completedFuture(null);
        refreshHeads.clear();
        holdRefresh = CompletableFuture.completedFuture(null);
        backendRefuses = false;
        mintedTtlSeconds = 3600;
        proxy.clearAuthError();
        lastBackend = null;
        sessionId = store.create(ACCOUNT, mint(3600)).sessionId();
        bind(SECRET, sessionId);
    }

    // --- Fixtures ---

    static ChatGptSessionStore.Tokens mint(long ttlSeconds) {
        var now = System.currentTimeMillis() / 1000;
        var auth = "{\"chatgpt_plan_type\":\"pro\",\"chatgpt_account_id\":\"" + ACCOUNT_ID
                + "\",\"organizations\":[\"org-secret-detail\"]}";
        var access = jwt("{\"exp\":" + (now + ttlSeconds) + ",\"jti\":\"" + java.util.UUID.randomUUID()
                + "\",\"https://api.openai.com/auth\":" + auth + "}");
        var id = jwt("{\"exp\":" + (now + 3600) + ",\"email\":\"user@example.invalid\",\"https://api.openai.com/auth\":" + auth + "}");
        return new ChatGptSessionStore.Tokens(id, access, "rt.real-" + java.util.UUID.randomUUID(), ACCOUNT_ID);
    }

    static String jwt(String payload) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "."
                + enc.encodeToString(payload.getBytes()) + "." + enc.encodeToString(("real-signature-" + payload.hashCode()).getBytes());
    }

    /** Lists the worker at the test client's address, bound as a start with {@code secret} would. */
    static void bind(String secret, String session) {
        bind(secret, session, secret);
    }

    /**
     * The same, but with {@code currentSecret} as the instance's secret now: another one than the
     * proofs were made with is what a copy, or a start that did not renew the binding, looks like.
     */
    static void bind(String secret, String session, String currentSecret) {
        bind(secret, session, currentSecret, ACCOUNT);
    }

    static void bind(String secret, String session, String currentSecret, String account) {
        useListing(listing(secret, session, currentSecret, account));
    }

    /** Serves {@code listed} as what Incus lists, read once now. */
    static void useListing(String listed) {
        listing = listed;
        var registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return JqValues.parse(listing);
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);
    }

    /** What Incus lists for the worker; a test may change it without the registry knowing. */
    static volatile String listing;

    static String listing(String secret, String session, String currentSecret, String account) {
        var proofs = ChatGptProof.derive(secret, account, session);
        return """
                [{"name":"%s","created_at":"2026-10-01T00:00:00Z","config":{
                  "user.incus-spawn.static-ip":"127.0.0.1",
                  "user.incus-spawn.account.openai":"%s",
                  "user.incus-spawn.chatgpt-session":"%s:%s",
                  "user.incus-spawn.chatgpt-access-proof-sha256":"%s",
                  "user.incus-spawn.chatgpt-refresh-proof-sha256":"%s",
                  "user.incus-spawn.chatgpt-proof-secret-sha256":"%s",
                  "user.incus-spawn.instance-secret-sha256":"%s"}}]
                """.formatted(INSTANCE, account, account, session, ChatGptProof.verifier(proofs.access()),
                ChatGptProof.verifier(proofs.refresh()), InstanceSecret.sha256(secret), InstanceSecret.sha256(currentSecret));
    }

    static ChatGptProof.Proofs proofs() {
        return ChatGptProof.derive(SECRET, ACCOUNT, sessionId);
    }

    static String accessPlaceholder(String digest) {
        return ChatGptPlaceholders.accessToken(mint(3600).accessToken(), digest);
    }

    static String refreshBody(String refreshToken) {
        return "{\"client_id\":\"" + ChatGptRoute.CODEX_CLIENT_ID + "\",\"grant_type\":\"refresh_token\",\"refresh_token\":\""
                + refreshToken + "\"}";
    }

    // --- The stub ---

    static void answer(HttpServerRequest req) {
        req.body().onSuccess(body -> {
            var host = req.getHeader("Host");
            seen.add(req.method() + " " + host + " " + req.uri() + " | " + req.getHeader("Authorization")
                    + " | " + req.getHeader("ChatGPT-Account-Id"));
            var resp = req.response().putHeader("Content-Type", "application/json");
            switch (req.path()) {
                case "/oauth/token" -> {
                    refreshes.incrementAndGet();
                    refreshBodies.add(body.toString());
                    refreshHeads.add(req.getHeader("Accept-Encoding") + "|" + req.getHeader("Expect"));
                    holdRefresh.whenComplete((v, e) -> vertx.runOnContext(x -> {
                        if (refreshAnswersEmpty) {
                            resp.end("{}");
                        } else if (refreshError != null) {
                            resp.setStatusCode(refreshErrorStatus).end("{\"error\":{\"code\":\"" + refreshError + "\"}}");
                        } else {
                            var fresh = mint(mintedTtlSeconds);
                            resp.end("{\"id_token\":\"" + fresh.idToken() + "\",\"access_token\":\"" + fresh.accessToken()
                                    + "\",\"refresh_token\":\"" + fresh.refreshToken() + "\"}");
                        }
                    }));
                }
                case "/oauth/revoke" -> {
                    revokeBodies.add(body.toString());
                    resp.end("{}");
                }
                case "/backend-api/codex/models" -> {
                    if ("client_version=redirect".equals(req.query())) {
                        resp.setStatusCode(302).putHeader("Location", "https://chatgpt.com/backend-api/conversations").end();
                    } else {
                        resp.setStatusCode(backendRefuses ? 401 : 200).end("{\"models\":[]}");
                    }
                }
                default -> {
                    lastBackend = new Received(body.length(), io.vertx.core.MultiMap.caseInsensitiveMultiMap().addAll(req.headers()));
                    var status = backendRefuses ? 401 : 200;
                    holdBackend.whenComplete((v, e) -> vertx.runOnContext(x -> resp.setStatusCode(status).end("{\"ok\":true}")));
                }
            }
        });
    }

    // --- A container's client: raw HTTP/1.1 over TLS, so every byte of the request is the test's ---

    record Reply(int status, String body) {}

    static Reply send(String host, String sni, String rawHead, String body) throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (var socket = (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", mitmPort)) {
            socket.setSoTimeout(15_000);
            var params = socket.getSSLParameters();
            // None for a null sni: connecting to an address, the client sends no TLS name
            params.setServerNames(sni == null ? List.of() : List.of(new SNIHostName(sni)));
            socket.setSSLParameters(params);
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            var request = rawHead + (rawHead.contains("Content-Length") || rawHead.contains("Transfer-Encoding") || bytes.length == 0 ? "" : "Content-Length: " + bytes.length + "\r\n")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            return readResponse(new BufferedInputStream(socket.getInputStream()));
        }
    }

    /**
     * One response, read to its end by its own framing rather than to EOF: a request refused before
     * its body was read is answered while the client could still be sending, and the connection
     * then stays open until it stops.
     */
    static Reply readResponse(InputStream in) throws IOException {
        int status;
        // An interim answer (100 Continue) comes first and says nothing of the request's fate
        // Not 101: a test that upgrades reads the socket, not another response
        while ((status = Integer.parseInt(line(in).substring(9, 12))) < 200 && status != 101) {
            while (!line(in).isEmpty()) { /* its headers */ }
        }
        long length = -1;
        var chunked = false;
        for (var header = line(in); !header.isEmpty(); header = line(in)) {
            var lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) length = Long.parseLong(lower.substring(15).strip());
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
        }
        var body = new ByteArrayOutputStream();
        if (chunked) {
            for (int size; (size = Integer.parseInt(line(in).split(";", 2)[0].strip(), 16)) > 0; line(in)) body.write(in.readNBytes(size));
            line(in);
        } else {
            body.write(length >= 0 ? in.readNBytes((int) length) : in.readAllBytes());
        }
        return new Reply(status, body.toString(StandardCharsets.UTF_8));
    }

    /** One CRLF-terminated line, without its terminator. */
    static String line(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        for (int b; (b = in.read()) >= 0 && b != '\n'; ) if (b != '\r') out.write(b);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    static Reply backend(String method, String target, String bearer) throws Exception {
        return send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, method + " " + target + " HTTP/1.1\r\nHost: chatgpt.com\r\n"
                + (bearer == null ? "" : "Authorization: Bearer " + bearer + "\r\n") + "ChatGPT-Account-Id: guest-says\r\n",
                method.equals("POST") ? "{}" : "");
    }

    static Reply refresh(String refreshToken) throws Exception {
        return refreshWithBody(refreshBody(refreshToken));
    }

    static Reply refreshWithBody(String body) throws Exception {
        return send(ChatGptRoute.AUTH, ChatGptRoute.AUTH, "POST /oauth/token HTTP/1.1\r\nHost: auth.openai.com\r\n"
                + "Content-Type: application/json\r\nOriginator: codex_cli_rs\r\nAccept-Encoding: gzip, br\r\n"
                + "Expect: 100-continue\r\n", body);
    }

    static List<String> seenOn(String path) {
        return seen.stream().filter(s -> s.contains(" " + path)).toList();
    }

    // --- Injection ---

    @ParameterizedTest
    @ValueSource(strings = {"POST /backend-api/codex/responses", "GET /backend-api/wham/accounts/check"})
    void anAllowedRequestWithTheCurrentProofCarriesTheRealTokenAndNothingOfTheGuests(String request) throws Exception {
        var parts = request.split(" ");
        var reply = backend(parts[0], parts[1], accessPlaceholder(proofs().access()));

        assertEquals(200, reply.status(), reply.body());
        var upstream = seenOn(parts[1]);
        assertEquals(1, upstream.size(), seen.toString());
        assertTrue(upstream.getFirst().contains("Bearer " + store.read(ACCOUNT).tokens().accessToken()), upstream.toString());
        assertTrue(upstream.getFirst().endsWith("| " + ACCOUNT_ID), "the account id is the session's, not the guest's: " + upstream);
        assertFalse(upstream.getFirst().contains(ChatGptProof.MARKER), "no placeholder goes upstream: " + upstream);
    }

    @Test
    void theGuestsOwnHeadersReachUpstreamBesideTheRealToken() throws Exception {
        var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                + "Host: chatgpt.com\r\nAuthorization: Bearer " + accessPlaceholder(proofs().access()) + "\r\n"
                + "Content-Type: application/json\r\nOpenAI-Beta: responses=experimental\r\nOriginator: codex_cli_rs\r\n", "{}");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("application/json", lastBackend.headers().get("Content-Type"));
        assertEquals("responses=experimental", lastBackend.headers().get("OpenAI-Beta"));
        assertEquals("codex_cli_rs", lastBackend.headers().get("Originator"));
    }

    @Test
    void aCallerRefusedOnAListingFromBeforeItsStartIsCheckedAgainOnAFreshOne() throws Exception {
        // The instance restarted: a new secret, new proofs, which Incus lists but the registry
        // has not read yet (a start does not signal the proxy)
        listing = listing(OTHER_SECRET, sessionId, OTHER_SECRET, ACCOUNT);
        Thread.sleep(InstanceRegistry.MISS_REFRESH_INTERVAL_MS + 100);
        var proofs = ChatGptProof.derive(OTHER_SECRET, ACCOUNT, sessionId);

        var reply = backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs.access()));
        assertEquals(200, reply.status(), "refused only until the registry listed again: " + reply.body());
        assertEquals(200, refresh(proofs.refreshToken()).status());
    }

    @Test
    void theModelsListTakesOnlyTheClientVersionQuery() throws Exception {
        var bearer = accessPlaceholder(proofs().access());
        assertEquals(200, backend("GET", "/backend-api/codex/models?client_version=0.162.1", bearer).status());
        assertEquals(403, backend("GET", "/backend-api/codex/models?client_version=0.162.1&x=1", bearer).status());
        assertEquals(1, seenOn("/backend-api/codex/models").size(), seen.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?account_id=other", "?"})
    void workspaceDiscoveryTakesNoQuery(String query) throws Exception {
        var reply = backend("GET", "/backend-api/wham/accounts/check" + query, accessPlaceholder(proofs().access()));

        assertEquals(403, reply.status(), reply.body());
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /backend-api/wham/accounts/check", "GET /backend-api/wham/accounts",
            "POST /backend-api/codex/analytics-events/events",
            "GET /backend-api/wham/usage", "GET /backend-api/conversations", "POST /backend-api/ps/mcp",
            "GET /backend-api/codex/responses", "DELETE /backend-api/codex/responses"})
    void anythingOffTheAllowlistIsRefusedHereWithAValidProof(String request) throws Exception {
        var parts = request.split(" ");
        var reply = backend(parts[0], parts[1], accessPlaceholder(proofs().access()));

        assertEquals(403, reply.status(), reply.body());
        assertTrue(reply.body().contains("not allowed for ChatGPT sessions"), reply.body());
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void aRequestThatClaimsNothingIsRelayedAsItCame() throws Exception {
        var reply = backend("GET", "/backend-api/conversations", "a-users-own-token");

        assertEquals(200, reply.status());
        assertEquals(List.of("GET chatgpt.com /backend-api/conversations | Bearer a-users-own-token | guest-says"),
                List.copyOf(seen));
    }

    @Test
    void anUpstreamRedirectIsHandedBackNotFollowed() throws Exception {
        var reply = backend("GET", "/backend-api/codex/models?client_version=redirect", accessPlaceholder(proofs().access()));

        assertEquals(302, reply.status());
        assertEquals(1, seen.size(), "the proxy followed nothing: " + seen);
    }

    // --- What a claimed request must prove ---

    /** Every way to hold an access placeholder that is not the worker's current one. */
    static List<String> wrongAccessProofs() {
        var current = proofs();
        var forgedFromHash = ChatGptProof.derive(InstanceSecret.sha256(SECRET).substring(0, 64), ACCOUNT, sessionId);
        return List.of(
                "x.y." + ChatGptProof.MARKER + "placeholder",
                "x.y." + ChatGptProof.MARKER,
                accessPlaceholder(ChatGptProof.derive(OTHER_SECRET, ACCOUNT, sessionId).access()),
                accessPlaceholder(ChatGptProof.derive(SECRET, ACCOUNT, "previous-session").access()),
                accessPlaceholder(ChatGptProof.derive(SECRET, "keyed", sessionId).access()),
                accessPlaceholder(current.refresh()),
                accessPlaceholder(forgedFromHash.access()),
                accessPlaceholder(ChatGptProof.verifier(current.access())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /backend-api/codex/responses", "GET /backend-api/wham/accounts/check"})
    void noAccessPlaceholderButTheCurrentOneIsServed(String request) throws Exception {
        var parts = request.split(" ");
        for (var bearer : wrongAccessProofs()) {
            var reply = backend(parts[0], parts[1], bearer);
            assertEquals(401, reply.status(), bearer + ": " + reply.body());
        }
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void noRefreshPlaceholderButTheCurrentOneIsServed() throws Exception {
        var current = proofs();
        var forgedFromHash = ChatGptProof.derive(InstanceSecret.sha256(SECRET).substring(0, 64), ACCOUNT, sessionId);
        for (var token : List.of(
                ChatGptProof.REFRESH_PREFIX + "placeholder",
                ChatGptProof.derive(OTHER_SECRET, ACCOUNT, sessionId).refreshToken(),
                ChatGptProof.derive(SECRET, ACCOUNT, "previous-session").refreshToken(),
                ChatGptProof.REFRESH_PREFIX + current.access(),
                forgedFromHash.refreshToken(),
                ChatGptProof.REFRESH_PREFIX + ChatGptProof.verifier(current.refresh()))) {
            var reply = refresh(token);
            assertEquals(401, reply.status(), token + ": " + reply.body());
            assertTrue(reply.body().contains("invalid_grant"), reply.body());
            assertFalse(reply.body().contains("access_token"), "no placeholder handed out: " + reply.body());
        }
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void aCopyWithItsSourcesVerifiersButASecretOfItsOwnGetsNothing() throws Exception {
        // The copy holds its source's guest files, placeholders included, and its metadata
        bind(SECRET, sessionId, OTHER_SECRET);

        assertEquals(401, backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs().access())).status());
        assertEquals(401, refresh(proofs().refreshToken()).status());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void anInstanceWithoutABindingGetsNothing() throws Exception {
        var proofs = proofs();
        useListing("[{\"name\":\"other\",\"config\":{\"user.incus-spawn.static-ip\":\"127.0.0.1\"}}]");

        assertEquals(401, backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs.access())).status());
        assertEquals(401, refresh(proofs.refreshToken()).status());
        assertEquals(401, send(ChatGptRoute.AUTH, ChatGptRoute.AUTH, "POST /oauth/revoke HTTP/1.1\r\nHost: auth.openai.com\r\n",
                "{\"token\":\"" + proofs.refreshToken() + "\"}").status());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    // --- Shape: what upstream sees is what the allowlist matched ---

    @ParameterizedTest
    @ValueSource(strings = {
            "/backend-api/codex/responses%2f..%2fwham%2faccounts%2fcheck",
            "/backend-api/codex/%72esponses",
            "/backend-api/codex/../wham/accounts/check",
            "/backend-api/codex/./responses",
            "/backend-api//codex/responses",
            "/backend-api/codex/responses;x=1",
            "/backend-api/codex/responses/",
            "https://chatgpt.com/backend-api/codex/responses",
            "/backend-api/codex/responses#frag"})
    void aTargetThatCouldReadAsAnotherPathIsRefused(String target) throws Exception {
        var reply = backend("POST", target, accessPlaceholder(proofs().access()));

        assertTrue(reply.status() == 400 || reply.status() == 403, target + " -> " + reply.status() + " " + reply.body());
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void duplicateCredentialHeadersAreRefused() throws Exception {
        var bearer = accessPlaceholder(proofs().access());
        var head = "POST /backend-api/codex/responses HTTP/1.1\r\nHost: chatgpt.com\r\n";
        for (var extra : List.of(
                "Authorization: Bearer " + bearer + "\r\nAuthorization: Bearer other\r\n",
                "Authorization: Bearer " + bearer + "\r\nChatGPT-Account-Id: a\r\nChatGPT-Account-Id: b\r\n",
                "Authorization: Bearer " + bearer + "\r\nChatGPT-Account-Id: a, b\r\n")) {
            var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, head + extra, "{}");
            assertEquals(400, reply.status(), extra + reply.body());
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aHostThatIsNotTheTlsNameIsRefused() throws Exception {
        var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.AUTH, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                + "Host: chatgpt.com\r\nAuthorization: Bearer " + accessPlaceholder(proofs().access()) + "\r\n", "{}");

        assertEquals(400, reply.status(), reply.body());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    // --- Refresh ---

    @Test
    void aRefreshWhileTheRealTokenIsFreshIsAnsweredHereWithTheCallersOwnProofs() throws Exception {
        var proofs = proofs();
        var real = store.read(ACCOUNT).tokens();
        var reply = refresh(proofs.refreshToken());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(0, refreshes.get(), "nothing sent upstream");
        var tokens = JqValues.parseStrict(reply.body());
        assertEquals(proofs.refreshToken(), tokens.pathText("refresh_token", ""));
        assertEquals(proofs.access(), ChatGptProof.accessDigestOf(tokens.pathText("access_token", "")));
        assertEquals(ChatGptPlaceholders.expiry(real.accessToken()), ChatGptPlaceholders.expiry(tokens.pathText("access_token", "")));
        assertNoRealToken(reply.body(), real);
        assertFalse(reply.body().contains("org-secret-detail"), "only the claims Codex reads: " + reply.body());
    }

    @Test
    void aRefreshNearExpiryIsRelayedOnceWithTheRealTokenAndCommitted() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        var before = store.read(ACCOUNT);

        var reply = refresh(proofs().refreshToken());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(1, refreshes.get());
        var sent = JqValues.parseStrict(refreshBodies.peek());
        assertEquals(before.tokens().refreshToken(), sent.pathText("refresh_token", ""));
        assertEquals(ChatGptRoute.CODEX_CLIENT_ID, sent.pathText("client_id", ""));
        var after = store.read(ACCOUNT);
        assertEquals(before.generation() + 1, after.generation());
        assertEquals(sessionId, after.sessionId());
        assertFalse(after.tokens().refreshToken().equals(before.tokens().refreshToken()), "rotated");
        assertNull(after.refreshing(), "the journal entry is cleared on commit");
        assertNoRealToken(reply.body(), after.tokens());
        assertTrue(seenOn("/oauth/token").getFirst().contains("| null |"), "no Authorization upstream: " + seen);
        // An answer the proxy can parse, and no Expect for a body sent at once
        assertEquals(List.of("identity|null"), List.copyOf(refreshHeads));
    }

    @Test
    void refreshesInARowGoUpstreamOnlyOncePerInterval() throws Exception {
        // A real token that stays inside the refresh window would otherwise make every guest
        // request a refresh at OpenAI: found when the stub's tokens lived 4 minutes, and real
        // Codex refreshed nearly 6000 times in 5 minutes
        mintedTtlSeconds = 60;
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);

        for (int i = 0; i < 5; i++) assertEquals(200, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get());
    }

    @Test
    void aThrottledRefreshDoesNotHandBackATokenTheBackendRefused() throws Exception {
        backendRefuses = true;
        var bearer = accessPlaceholder(proofs().access());
        assertEquals(401, backend("POST", "/backend-api/codex/responses", bearer).status());
        assertEquals(200, refresh(proofs().refreshToken()).status(), "the first one goes upstream");

        // Refused again within the interval: the refreshed token is no better, so no token at all
        assertEquals(401, backend("POST", "/backend-api/codex/responses", bearer).status());
        var reply = refresh(proofs().refreshToken());
        assertEquals(503, reply.status(), reply.body());
        assertFalse(reply.body().contains("access_token"), reply.body());
        assertEquals(1, refreshes.get());
    }

    /**
     * A throttled caller that read the session before a refresh committed: it is handed the newer
     * token only if that one works. The interleaving cannot be forced over HTTP, so the caller's
     * stale read is passed in directly.
     */
    @ParameterizedTest
    @CsvSource({"works, 200", "refused, 503", "expired, 503"})
    void aThrottledCallerGetsANewerTokenOnlyIfItWorks(String newer, int status) throws Exception {
        // Expired, as a token is when a throttled refresh gets this far
        sessionId = store.create(ACCOUNT, mint(-10)).sessionId();
        bind(SECRET, sessionId);
        var stale = store.read(ACCOUNT);
        if (newer.equals("expired")) mintedTtlSeconds = -10;
        assertEquals(200, refresh(proofs().refreshToken()).status(), "commits the next generation");
        if (newer.equals("refused")) {
            refuseTheCurrentToken();
        }

        var outcome = proxy.chatGpt.newerOrLater(stale);
        assertEquals(status, outcome.status());
        assertEquals(status == 200 ? store.read(ACCOUNT).tokens() : null, outcome.tokens());
        assertEquals(1, refreshes.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aThrottledCallerOfASessionThatIsGoneIsRefusedAndItsRecordsDropped(boolean signedInAgain) throws Exception {
        var stale = store.read(ACCOUNT);
        refuseTheCurrentToken();
        assertTrue(proxy.chatGpt.refuses(ACCOUNT, sessionId));
        if (signedInAgain) store.create(ACCOUNT, mint(3600));
        else store.remove(ACCOUNT);

        var outcome = proxy.chatGpt.newerOrLater(stale);
        assertEquals(401, outcome.status(), "not a retryable 503: the proofs will never work again");
        assertNull(outcome.tokens());
        assertFalse(proxy.chatGpt.refuses(ACCOUNT, sessionId), "nothing kept about a session that is gone");
    }

    @Test
    void aLate401ForAReplacedTokenDoesNotMarkTheNewOne() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        // A request with the old token, answered 401 only after a refresh has replaced it
        backendRefuses = true;
        holdBackend = new CompletableFuture<>();
        var late = CompletableFuture.supplyAsync(() -> uncheck(() ->
                backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs().access()))));
        for (int i = 0; i < 200 && seenOn("/backend-api/codex/responses").isEmpty(); i++) Thread.sleep(25);
        assertEquals(200, refresh(proofs().refreshToken()).status(), "near expiry: refreshed upstream");
        holdBackend.complete(null);
        assertEquals(401, late.get(15, TimeUnit.SECONDS).status());
        backendRefuses = false;

        // The new token is fine: a refresh is answered with it, not throttled away
        var reply = refresh(proofs().refreshToken());
        assertEquals(200, reply.status(), reply.body());
        assertEquals(1, refreshes.get());
    }

    @Test
    void aWebSocketTheBackendRefusesLetsTheNextRefreshThrough() throws Exception {
        backendRefuses = true;
        upgrade(codexWebSocket());
        // The client's handshake completes before the proxy dials: the stub refuses the dial itself
        for (int i = 0; i < 200 && seen.stream().noneMatch(e -> e.startsWith("WS ")); i++) Thread.sleep(25);
        backendRefuses = false;

        // The refusal is recorded once upstream's answer is in: until then a refresh is answered here
        for (int i = 0; i < 200 && refreshes.get() == 0; i++) {
            assertEquals(200, refresh(proofs().refreshToken()).status());
            if (refreshes.get() == 0) Thread.sleep(25);
        }
        assertEquals(1, refreshes.get(), "refreshed upstream although the token was not near expiry");
    }

    /**
     * When several Codex processes refresh together: one upstream call serves them all. With an
     * expired token, the ones arriving while the first is upstream wait for it rather than get the
     * throttle's 503; with one still working, they are answered with it and wait for nothing.
     */
    @ParameterizedTest
    @CsvSource({"-10, 19", "60, 0"})
    void concurrentRefreshesOfOneSessionShareOneUpstreamCall(long ttlSeconds, int joins) throws Exception {
        sessionId = store.create(ACCOUNT, mint(ttlSeconds)).sessionId();
        bind(SECRET, sessionId);
        holdRefresh = new CompletableFuture<>();
        var token = proofs().refreshToken();
        var joinedBefore = proxy.chatGpt.joined.get();
        try (var pool = Executors.newFixedThreadPool(20)) {
            var replies = new ArrayList<java.util.concurrent.Future<Reply>>();
            replies.add(pool.submit(() -> refresh(token)));
            awaitRefreshes(1);
            for (int i = 0; i < 19; i++) replies.add(pool.submit(() -> refresh(token)));
            if (joins > 0) {
                for (int i = 0; i < 400 && proxy.chatGpt.joined.get() - joinedBefore < joins; i++) Thread.sleep(25);
            } else {
                // Answered at once, while the first still waits upstream
                for (var reply : replies.subList(1, replies.size())) {
                    assertEquals(200, reply.get(20, TimeUnit.SECONDS).status());
                }
            }
            assertEquals(joins, proxy.chatGpt.joined.get() - joinedBefore);
            holdRefresh.complete(null);
            for (var reply : replies) {
                var answered = reply.get(20, TimeUnit.SECONDS);
                assertEquals(200, answered.status(), answered.body());
            }
        }
        assertEquals(1, refreshes.get());
    }

    /**
     * A token the backend stops accepting before it expires is refreshed whichever allowed call
     * finds out first: workspace discovery is Codex's first, so a turn never reaches the others.
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST /backend-api/codex/responses", "GET /backend-api/wham/accounts/check"})
    void aBackend401LetsTheNextRefreshThrough(String request) throws Exception {
        var parts = request.split(" ");
        refuseTheCurrentToken(parts[0], parts[1]);
        backendRefuses = false;

        assertTrue(proxy.chatGpt.refuses(ACCOUNT, sessionId));
        assertEquals(200, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get(), "the real token was refreshed although it was not near expiry");
        assertFalse(proxy.chatGpt.refuses(ACCOUNT, sessionId), "a committed refresh clears the session's refusals");
        assertEquals(200, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get(), "and only once");

        // Refused again within 30 s: the limit holds, whichever call it was
        refuseTheCurrentToken(parts[0], parts[1]);
        assertEquals(503, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get(), "no second rotation inside the interval");
    }

    @Test
    void aSignInAgainDuringARefreshFailsItAndLeavesTheNewSessionAlone() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        var oldToken = proofs().refreshToken();
        holdRefresh = new CompletableFuture<>();
        var pending = CompletableFuture.supplyAsync(() -> uncheck(() -> refresh(oldToken)));
        awaitRefreshes(1);

        var newSession = store.create(ACCOUNT, mint(3600));
        holdRefresh.complete(null);
        var reply = pending.get(20, TimeUnit.SECONDS);

        assertEquals(401, reply.status(), reply.body());
        assertFalse(reply.body().contains("access_token"), "no new-session proof or placeholder: " + reply.body());
        var now = store.read(ACCOUNT);
        assertEquals(newSession, now, "the new session is untouched");
        awaitRevokes(1);
        assertTrue(revokeBodies.peek().contains("rt.real-"), "the discarded chain's token is revoked: " + revokeBodies);
        // The old session's proofs never become the new session's
        assertEquals(401, refresh(oldToken).status());
    }

    @Test
    void aSignInAgainVoidsTheOldProofOnTheBackendAndTheWebSocket() throws Exception {
        // The instance keeps its binding to the old session; the account signs in again
        var oldProof = accessPlaceholder(proofs().access());
        var renewed = store.create(ACCOUNT, mint(3600));

        var reply = backend("POST", "/backend-api/codex/responses", oldProof);
        assertEquals(401, reply.status(), reply.body());
        assertFalse(reply.body().contains(renewed.tokens().accessToken()), reply.body());
        // The proxy answers the handshake before it reads the session, then closes the socket when
        // the session is not the binding's: wait for that close, then nothing may have been dialled
        var closed = new CompletableFuture<Short>();
        wsClient.webSocket(webSocket("/backend-api/codex/responses", "Bearer " + oldProof))
                .onSuccess(ws -> ws.closeHandler(v -> closed.complete(ws.closeStatusCode())))
                .onFailure(closed::completeExceptionally);
        assertEquals((short) 1011, closed.get(15, TimeUnit.SECONDS), "closed by the proxy, not dialled");
        assertTrue(seen.isEmpty(), "the new session's token went nowhere: " + seen);
    }

    @Test
    void aRemovalDuringARefreshIsNotUndone() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        holdRefresh = new CompletableFuture<>();
        var pending = CompletableFuture.supplyAsync(() -> uncheck(() -> refresh(proofs().refreshToken())));
        awaitRefreshes(1);

        store.remove(ACCOUNT);
        holdRefresh.complete(null);

        assertEquals(401, pending.get(20, TimeUnit.SECONDS).status());
        assertNull(store.read(ACCOUNT), "the proxy never recreates a session");
        awaitRevokes(1);
    }

    @Test
    void aRefusedRefreshIsReportedAndClassifiedAsOpenAiWouldHave() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        refreshError = "refresh_token_reused";

        var reply = refresh(proofs().refreshToken());

        assertEquals(400, reply.status());
        assertTrue(reply.body().contains("\"code\":\"refresh_token_reused\""), reply.body());
        assertNotNull(proxy.authError, "the host is told to sign in again");
        assertNull(store.read(ACCOUNT).refreshing(), "the journal entry is cleared");
    }

    @Test
    void aRefreshOvertakenWithinTheSameSessionIsAnsweredFromIt() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        holdRefresh = new CompletableFuture<>();
        var pending = CompletableFuture.supplyAsync(() -> uncheck(() -> refresh(proofs().refreshToken())));
        awaitRefreshes(1);
        // Another writer commits the same session's next generation first
        var current = store.read(ACCOUNT);
        assertEquals(ChatGptSessionStore.Commit.COMMITTED, store.commit(current, mint(3600)));
        holdRefresh.complete(null);

        var reply = pending.get(20, TimeUnit.SECONDS);
        assertEquals(200, reply.status(), "the session is healthy: " + reply.body());
        assertEquals(proofs().refreshToken(), JqValues.parseStrict(reply.body()).pathText("refresh_token", ""));
        assertEquals(store.read(ACCOUNT).generation(), current.generation() + 1, "the other write stands");
        awaitRevokes(1);
    }

    @Test
    void aServerErrorOnRefreshIsTransientNotASignOut() throws Exception {
        sessionId = store.create(ACCOUNT, mint(-10)).sessionId();
        bind(SECRET, sessionId);
        refreshError = "upstream_down";
        refreshErrorStatus = 503;

        var reply = refresh(proofs().refreshToken());
        assertEquals(502, reply.status(), reply.body());
        assertTrue(reply.body().contains("server_error"), reply.body());
        assertNull(proxy.authError, "nothing tells the user to sign in again");
        // Within the interval, an expired token is not handed out as if refreshed: try again later
        assertEquals(503, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get());
    }

    @Test
    void aTokenRequestTooLargeToBeIsxsIsRelayedUninspected() throws Exception {
        var padding = "x".repeat(70 * 1024);
        var body = "{\"refresh_token\":\"" + proofs().refreshToken() + "\",\"pad\":\"" + padding + "\"}";
        assertEquals(200, refreshWithBody(body).status());
        assertEquals(List.of(body), List.copyOf(refreshBodies), "untouched, and nothing injected");
    }

    @Test
    void aPinnedAccountsRefreshNeitherRaisesNorClearsTheHostsAuthError() throws Exception {
        // 'work' is pinned, 'sso' is the default: its broken session is no host-wide sign-in
        var work = store.create("work", mint(60)).sessionId();
        bind(SECRET, work, SECRET, "work");
        refreshError = "refresh_token_reused";
        assertEquals(400, refresh(ChatGptProof.derive(SECRET, "work", work).refreshToken()).status());
        assertNull(proxy.authError);

        // And its success does not clear an error something else raised
        proxy.setAuthError("Claude OAuth token rejected (HTTP 401).", "isx init");
        refreshError = null;
        work = store.create("work", mint(60)).sessionId();
        bind(SECRET, work, SECRET, "work");
        assertEquals(200, refresh(ChatGptProof.derive(SECRET, "work", work).refreshToken()).status());
        assertEquals("Claude OAuth token rejected (HTTP 401).", proxy.authError);
    }

    @Test
    void aTokenRequestIsxDoesNotRecogniseFailsClosed() throws Exception {
        var token = proofs().refreshToken();
        for (var body : List.of(
                "{\"client_id\":\"another-client\",\"grant_type\":\"refresh_token\",\"refresh_token\":\"" + token + "\"}",
                "{\"client_id\":\"" + ChatGptRoute.CODEX_CLIENT_ID + "\",\"grant_type\":\"refresh_token\",\"refresh_token\":\""
                        + token + "\",\"scope\":\"openid\"}",
                "{\"client_id\":\"" + ChatGptRoute.CODEX_CLIENT_ID + "\",\"grant_type\":\"urn:ietf:params:oauth:grant-type:token-exchange\",\"refresh_token\":\"" + token + "\"}",
                "grant_type=refresh_token&refresh_token=" + token)) {
            assertEquals(400, refreshWithBody(body).status(), body);
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aGuestLogoutNeverReachesOpenAi() throws Exception {
        var reply = send(ChatGptRoute.AUTH, ChatGptRoute.AUTH, "POST /oauth/revoke HTTP/1.1\r\nHost: auth.openai.com\r\n",
                "{\"token\":\"" + proofs().refreshToken() + "\",\"token_type_hint\":\"refresh_token\"}");

        assertEquals(200, reply.status());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aTokenRequestWithoutAPlaceholderIsRelayedAsItCame() throws Exception {
        var body = refreshBody("rt.a-users-own");
        assertEquals(200, refreshWithBody(body).status());
        assertEquals(List.of(body), List.copyOf(refreshBodies));
    }

    // --- WebSocket ---

    @Test
    void aWebSocketWithTheCurrentProofIsDialledToTheOverrideWithTheRealToken() throws Exception {
        var client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
        var options = new WebSocketConnectOptions().setHost(ChatGptRoute.CHATGPT).setPort(mitmPort).setSsl(true)
                .setURI("/backend-api/codex/responses")
                // As a container sends it: it connects to 443, which the bridge redirects here
                .putHeader("Host", ChatGptRoute.CHATGPT)
                .addHeader("Authorization", "Bearer " + accessPlaceholder(proofs().access()));
        var echoed = new CompletableFuture<String>();
        client.webSocket(options).onSuccess(ws -> {
            ws.textMessageHandler(echoed::complete);
            ws.writeTextMessage("{\"type\":\"response.create\"}");
        }).onFailure(echoed::completeExceptionally);

        assertEquals("echo:{\"type\":\"response.create\"}", echoed.get(15, TimeUnit.SECONDS));
        var dialled = seen.stream().filter(s -> s.startsWith("WS ")).toList();
        assertEquals(1, dialled.size(), "dialled the stub through the override: " + seen);
        assertTrue(dialled.getFirst().contains("Bearer " + store.read(ACCOUNT).tokens().accessToken()), dialled.toString());
        assertTrue(dialled.getFirst().endsWith("| " + ACCOUNT_ID), dialled.toString());
    }

    @Test
    void aWebSocketWithAWrongProofIsRejectedBeforeAnythingIsDialled() throws Exception {
        for (var bearer : wrongAccessProofs()) {
            assertEquals(401, upgrade(webSocket("/backend-api/codex/responses", "Bearer " + bearer)), bearer);
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    /** The upgrade Codex sends, with the worker's current access proof. */
    static WebSocketConnectOptions codexWebSocket() {
        return webSocket("/backend-api/codex/responses", "Bearer " + accessPlaceholder(proofs().access()));
    }

    static WebSocketConnectOptions webSocket(String path, String... authorizations) {
        var options = new WebSocketConnectOptions().setHost(ChatGptRoute.CHATGPT).setPort(mitmPort).setSsl(true)
                .setURI(path).putHeader("Host", ChatGptRoute.CHATGPT);
        for (var authorization : authorizations) options.addHeader("Authorization", authorization);
        return options;
    }

    /** The status a WebSocket upgrade was refused with, or 101 if it was accepted. */
    static int upgrade(WebSocketConnectOptions options) throws Exception {
        var status = new CompletableFuture<Integer>();
        wsClient.webSocket(options).onSuccess(ws -> { status.complete(101); ws.close(); })
                .onFailure(e -> status.complete(e instanceof io.vertx.core.http.UpgradeRejectedException rejected
                        ? rejected.getStatus() : -1));
        return status.get(15, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/backend-api/conversations", "/backend-api/wham/accounts/check",
            "/backend-api/codex/models", "/backend-api/codex/responses?x=1", "/backend-api/codex/../conversations",
            "/backend-api/codex/responses%2f"})
    void aWebSocketOffTheAllowlistIsRefusedWithAValidProof(String path) throws Exception {
        var status = upgrade(webSocket(path, "Bearer " + accessPlaceholder(proofs().access())));

        assertTrue(status == 400 || status == 403, path + " -> " + status);
        assertTrue(seen.isEmpty(), "nothing dialled: " + seen);
    }

    @Test
    void aWebSocketWithTwoAuthorizationsIsRefused() throws Exception {
        var bearer = "Bearer " + accessPlaceholder(proofs().access());

        assertEquals(400, upgrade(webSocket("/backend-api/codex/responses", bearer, "Bearer other")));
        assertEquals(400, upgrade(webSocket("/backend-api/codex/responses", bearer, bearer)));
        assertTrue(seen.isEmpty(), "nothing dialled: " + seen);
    }

    // --- The TLS name ---

    @Test
    void aClaimedRequestWithNoTlsNameIsRefused() throws Exception {
        // The server may refuse the handshake itself; either way nothing is relayed
        try {
            var reply = send(ChatGptRoute.CHATGPT, null, "POST /backend-api/codex/responses HTTP/1.1\r\nHost: chatgpt.com\r\n"
                    + "Authorization: Bearer " + accessPlaceholder(proofs().access()) + "\r\n", "{}");
            assertEquals(400, reply.status(), reply.body());
        } catch (java.io.IOException refusedAtHandshake) {
            // also a refusal
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void theShapeCheckFailsClosedWithoutATlsName() {
        // The MITM server already refuses a handshake with no TLS name, so no request reaches this
        // case today; the check must not depend on that
        var headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap().add("Host", ChatGptRoute.CHATGPT);
        assertNull(ChatGptRoute.shapeProblem(headers, ChatGptRoute.CHATGPT, "/backend-api/codex/responses", ChatGptRoute.CHATGPT));
        assertNotNull(ChatGptRoute.shapeProblem(headers, ChatGptRoute.CHATGPT, "/backend-api/codex/responses", null));
        assertNotNull(ChatGptRoute.shapeProblem(headers, ChatGptRoute.CHATGPT, "/backend-api/codex/responses", ""));
        assertNotNull(ChatGptRoute.shapeProblem(headers, ChatGptRoute.CHATGPT, "/backend-api/codex/responses", ChatGptRoute.AUTH));
    }

    @Test
    void aWebSocketWhoseTlsNameIsNotItsHostIsRefused() throws Exception {
        var options = codexWebSocket()
                .setHost(ChatGptRoute.AUTH);
        assertEquals(400, upgrade(options));
        assertTrue(seen.isEmpty(), "nothing dialled: " + seen);
    }

    @Test
    void aWebSocketWithNoTlsNameIsRefused() throws Exception {
        var options = codexWebSocket()
                .setHost("127.0.0.1");
        assertNotEquals(101, upgrade(options));
        assertTrue(seen.isEmpty(), "nothing dialled: " + seen);
    }

    // --- What a refresh answers when something is off ---

    @Test
    void aRefreshAnswerWithoutTokensKeepsTheRecordOfTheAttempt() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        refreshAnswersEmpty = true;

        assertEquals(502, refresh(proofs().refreshToken()).status());
        assertNotNull(store.read(ACCOUNT).refreshing(), "OpenAI said yes, so it may have rotated the token");
    }

    @Test
    void aSessionTokenThatIsNoJwtAnswers502RatherThanHang() throws Exception {
        var real = mint(3600);
        sessionId = store.create(ACCOUNT, new ChatGptSessionStore.Tokens("not-a-jwt", real.accessToken(),
                real.refreshToken(), real.accountId())).sessionId();
        bind(SECRET, sessionId);

        assertEquals(502, refresh(proofs().refreshToken()).status());
    }

    // --- Bodies: read only after the proof, and then streamed ---

    @Test
    void aBodyIsNeverReadForACallerWithoutTheProof() throws Exception {
        // Declares far more than the bound: refused on its head, before a byte of body is waited for
        for (var bearer : List.of(accessPlaceholder(ChatGptProof.derive(OTHER_SECRET, ACCOUNT, sessionId).access()),
                "a.b." + ChatGptProof.MARKER + "x")) {
            var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                    + "Host: chatgpt.com\r\nAuthorization: Bearer " + bearer + "\r\nContent-Length: 999999999\r\n", "");
            assertEquals(401, reply.status(), bearer);
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aProvenRequestsBodyStreamsThroughWhole() throws Exception {
        // Never held whole in the shared proxy (#1315): it streams once the head is proven
        var chunk = "x".repeat(64 * 1024);
        var body = new StringBuilder();
        for (int i = 0; i < 64; i++) body.append(Integer.toHexString(chunk.length())).append("\r\n").append(chunk).append("\r\n");
        body.append("0\r\n\r\n");
        var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                + "Host: chatgpt.com\r\nAuthorization: Bearer " + accessPlaceholder(proofs().access())
                + "\r\nTransfer-Encoding: chunked\r\n", body.toString());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(64 * chunk.length(), lastBackend.bodyLength());
        assertTrue(seenOn("/backend-api/codex/responses").getFirst().contains("Bearer " + store.read(ACCOUNT).tokens().accessToken()));
    }

    // --- Helpers ---

    static void assertNoRealToken(String text, ChatGptSessionStore.Tokens real) {
        assertFalse(text.contains(real.accessToken()), "real access token handed out");
        assertFalse(text.contains(real.refreshToken()), "real refresh token handed out");
        assertFalse(text.contains(real.idToken()), "real id token handed out");
        for (var part : List.of(real.accessToken(), real.idToken())) {
            assertFalse(text.contains(part.substring(part.lastIndexOf('.') + 1)), "a real signature handed out");
        }
    }

    /** The backend refuses from now on, and has just refused the current access proof's token. */
    static void refuseTheCurrentToken() throws Exception {
        refuseTheCurrentToken("POST", "/backend-api/codex/responses");
    }

    static void refuseTheCurrentToken(String method, String path) throws Exception {
        backendRefuses = true;
        assertEquals(401, backend(method, path, accessPlaceholder(proofs().access())).status());
    }

    static void awaitRefreshes(int n) throws InterruptedException {
        for (int i = 0; i < 200 && refreshes.get() < n; i++) Thread.sleep(25);
        assertEquals(n, refreshes.get());
    }

    static void awaitRevokes(int n) throws InterruptedException {
        for (int i = 0; i < 200 && revokeBodies.size() < n; i++) Thread.sleep(25);
        assertEquals(n, revokeBodies.size(), "revokes: " + revokeBodies);
    }

    interface ThrowingSupplier<T> { T get() throws Exception; }

    static <T> T uncheck(ThrowingSupplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
