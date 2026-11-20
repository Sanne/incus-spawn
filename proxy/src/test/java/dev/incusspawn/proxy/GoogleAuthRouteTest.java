package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy side of Antigravity CLI's Google sign-in on the host (#2443), against a stub of
 * Google's token endpoint, userinfo and the Code Assist backend: a sign-in that becomes the
 * account's session, what a claimed request must prove, what it may do, and what a refresh may
 * write. The stub records every request, so "never relayed" and "no real token in the guest" are
 * checked, not assumed. The requests are those agy 1.5.12 sent in a probe against this stub.
 */
class GoogleAuthRouteTest {

    private static final String ACCOUNT = "home";
    private static final String INSTANCE = "worker";
    private static final String SECRET = "11".repeat(32);
    private static final String OTHER_SECRET = "22".repeat(32);
    private static final String CLIENT_SECRET = "GOCSPX-stub-secret";

    @TempDir
    static Path home;
    static String originalHome;
    static Vertx vertx;
    static HttpServer stub;
    static MitmProxy proxy;
    static int mitmPort;
    static GoogleSessionStore store;

    /** Every request the stub saw: "METHOD host path | authorization". */
    static final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> tokenBodies = new ConcurrentLinkedQueue<>();
    static final AtomicInteger tokenRequests = new AtomicInteger();
    static volatile String tokenError;
    static volatile int tokenErrorStatus = 400;
    static volatile boolean backendRefuses;
    static volatile String mintedEmail = "user@example.invalid";
    static volatile long mintedTtlSeconds = 3599;
    static volatile boolean mintRefreshToken = true;

    @BeforeAll
    static void start() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        var configDir = Files.createDirectories(home.resolve(".config/incus-spawn"));
        Files.writeString(configDir.resolve("config.yaml"), """
                gemini:
                  accounts:
                    home:
                      type: google
                    work:
                      apiKey: "gemini-real-key"
                  default: home
                """);
        store = new GoogleSessionStore(() -> configDir.resolve("gemini"));

        vertx = Vertx.vertx(new io.vertx.core.VertxOptions().setAddressResolverOptions(
                new io.vertx.core.dns.AddressResolverOptions().setHostsValue(Buffer.buffer(
                        "127.0.0.1 oauth2.googleapis.com www.googleapis.com daily-cloudcode-pa.googleapis.com cloudcode-pa.googleapis.com\n"))));
        var ca = CertificateAuthority.loadOrCreate();
        stub = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, GoogleAccounts.BACKEND)))
                .requestHandler(GoogleAuthRouteTest::answer);
        int stubPort = NpmCacheProxyTest.listen(stub);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        for (var host : GoogleAccounts.DOMAINS) proxy.overrideUpstream(host, "127.0.0.1", stubPort);
        ContainerTls.startInBackground(proxy);
        mitmPort = proxy.mitmPort();
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

    @BeforeEach
    void fresh() {
        seen.clear();
        tokenBodies.clear();
        tokenRequests.set(0);
        tokenError = null;
        tokenErrorStatus = 400;
        backendRefuses = false;
        mintedEmail = "user@example.invalid";
        mintedTtlSeconds = 3599;
        mintRefreshToken = true;
        proxy.clearAuthError();
        proxy.googleAuth.forgetMinted();
        proxy.proofIdentifies = (remote, local) -> false;
        store.remove(ACCOUNT);
        bind(SECRET, SECRET);
    }

    // --- Fixtures ---

    static GoogleSessionStore.Tokens real(long ttlSeconds) {
        var now = System.currentTimeMillis() / 1000;
        return new GoogleSessionStore.Tokens("ya29.real-" + java.util.UUID.randomUUID(), "1//real-" + java.util.UUID.randomUUID(),
                now + ttlSeconds, "user@example.invalid");
    }

    static String digest() {
        return ProofToken.derive(SECRET, GoogleAccounts.NAMESPACE);
    }

    static String otherDigest() {
        return ProofToken.derive(OTHER_SECRET, GoogleAccounts.NAMESPACE);
    }

    /** Lists the worker at the test client's address, its gemini proof derived from {@code secret}. */
    static void bind(String secret, String currentSecret) {
        bind(secret, currentSecret, null);
    }

    /** @param pin the worker's gemini account pin, or null for the default */
    static void bind(String secret, String currentSecret, String pin) {
        useListing("[" + listed(INSTANCE, "127.0.0.1", secret, currentSecret, pin) + "]");
    }

    /** One instance as Incus lists it: {@code secret}'s gemini proof stamped, {@code pin} its gemini account or null. */
    static String listed(String name, String address, String secret, String currentSecret, String pin) {
        var proofs = ProofVerifier.stamp(secret, java.util.List.of(
                new ProofToken.Placeholder("GEMINI_API_KEY", "gemini-", GoogleAccounts.NAMESPACE)));
        return """
                {"name":"%s","created_at":"2026-10-01T00:00:00Z","status":"Running","config":{
                  "user.incus-spawn.static-ip":"%s",
                  %s
                  "user.incus-spawn.instance-proof-id":"%s",
                  "user.incus-spawn.instance-proof-sha256":"%s",
                  "user.incus-spawn.instance-secret-sha256":"%s"}}
                """.formatted(name, address, pin == null ? "" : "\"user.incus-spawn.account.gemini\":\"" + pin + "\",",
                "33".repeat(32), proofs.replace("\n", "\\n"), InstanceSecret.sha256(currentSecret));
    }

    /** macOS (#1582): the caller's address names no instance, and a proof alone identifies it. */
    static void macOs(String... instances) {
        proxy.proofIdentifies = (remote, local) -> true;
        useListing("[" + String.join(",", instances) + "]");
    }

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

    static volatile String listing;

    static String idToken(String email) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\",\"kid\":\"stub\"}".getBytes()) + "."
                + enc.encodeToString(("{\"iss\":\"https://accounts.google.com\",\"sub\":\"1234567890\",\"email\":\"" + email
                + "\",\"email_verified\":true}").getBytes()) + ".c2lnbmF0dXJl";
    }

    // --- The stub ---

    static void answer(HttpServerRequest req) {
        req.body().onSuccess(body -> {
            var host = req.getHeader("Host");
            seen.add(req.method() + " " + host + " " + req.uri() + " | " + req.getHeader("Authorization"));
            var resp = req.response().putHeader("Content-Type", "application/json");
            if (GoogleAccounts.TOKEN.equals(host) && req.path().equals("/token")) {
                tokenRequests.incrementAndGet();
                tokenBodies.add(body.toString());
                if (tokenError != null) {
                    resp.setStatusCode(tokenErrorStatus).end("{\"error\":\"" + tokenError + "\",\"error_description\":\"stub\"}");
                    return;
                }
                var minted = real(mintedTtlSeconds);
                resp.end("{\"access_token\":\"" + minted.accessToken() + "\",\"expires_in\":" + mintedTtlSeconds
                        + (mintRefreshToken ? ",\"refresh_token\":\"" + minted.refreshToken() + "\"" : "")
                        + ",\"scope\":\"openid https://www.googleapis.com/auth/cloud-platform\",\"token_type\":\"Bearer\""
                        + ",\"id_token\":\"" + idToken(mintedEmail) + "\"}");
                return;
            }
            if (GoogleAccounts.TOKEN.equals(host) && req.path().equals("/revoke")) {
                resp.end("{}");
                return;
            }
            if (GoogleAccounts.USERINFO.equals(host)) {
                resp.setStatusCode(backendRefuses ? 401 : 200).end("{\"email\":\"" + mintedEmail + "\"}");
                return;
            }
            resp.setStatusCode(backendRefuses ? 401 : 200).end("{\"ok\":true}");
        });
    }

    // --- A container's client ---

    static ChatGptRouteTest.Reply send(String host, String rawHead, String body) throws Exception {
        return ChatGptRouteTest.sendTo(mitmPort, host, host, rawHead, body);
    }

    static ChatGptRouteTest.Reply backend(String host, String method, String target, String bearer) throws Exception {
        return send(host, method + " " + target + " HTTP/1.1\r\nHost: " + host + "\r\n"
                + (bearer == null ? "" : "Authorization: Bearer " + bearer + "\r\n")
                + "User-Agent: antigravity/cli/1.5.12 (aidev_client)\r\nContent-Type: application/json\r\n",
                method.equals("POST") ? "{\"project\":\"stub-project\"}" : "");
    }

    static String form(Map<String, String> pairs) {
        var out = new StringBuilder();
        pairs.forEach((k, v) -> out.append(out.isEmpty() ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return out.toString();
    }

    static Map<String, String> exchangeForm(String code) {
        var form = new java.util.LinkedHashMap<String, String>();
        form.put("client_id", GoogleAuthRoute.AGY_CLIENT_ID);
        form.put("client_secret", CLIENT_SECRET);
        form.put("code", code);
        form.put("code_verifier", "verifier-" + code);
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", GoogleAuthRoute.AGY_REDIRECT_URI);
        return form;
    }

    static Map<String, String> refreshForm(String refreshToken) {
        var form = new java.util.LinkedHashMap<String, String>();
        form.put("client_id", GoogleAuthRoute.AGY_CLIENT_ID);
        form.put("client_secret", CLIENT_SECRET);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return form;
    }

    static ChatGptRouteTest.Reply token(String body) throws Exception {
        return send(GoogleAccounts.TOKEN, "POST /token HTTP/1.1\r\nHost: oauth2.googleapis.com\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nUser-Agent: Go-http-client/1.1\r\n", body);
    }

    static java.util.List<String> seenOn(String host) {
        return seen.stream().filter(s -> s.contains(" " + host + " ")).toList();
    }

    static io.hyperfoil.tools.jjq.value.JqValue json(ChatGptRouteTest.Reply reply) {
        return JqValues.parseStrict(reply.body());
    }

    // --- Sign-in ---

    @Test
    void aSignInFromAnInstanceOnAGoogleAccountBecomesItsSessionAndTheGuestGetsPlaceholders() throws Exception {
        var reply = token(form(exchangeForm("4/0Acode")));

        assertEquals(200, reply.status(), reply.body());
        assertEquals(1, tokenRequests.get());
        assertEquals(form(exchangeForm("4/0Acode")), tokenBodies.peek(), "agy's exchange goes to Google as it came");
        var session = store.read(ACCOUNT);
        assertNotNull(session, "the account signed in");
        assertEquals("user@example.invalid", session.tokens().email());
        assertTrue(session.tokens().accessToken().startsWith("ya29.real-"));
        assertTrue(session.tokens().refreshToken().startsWith("1//real-"));
        var answer = json(reply);
        var access = answer.pathText("access_token", "");
        var refresh = answer.pathText("refresh_token", "");
        assertTrue(access.startsWith("ya29.isx_"), access);
        assertTrue(refresh.startsWith("1//isx_"), refresh);
        var minted = GooglePlaceholders.digestOf(access);
        assertEquals(minted, GooglePlaceholders.digestOf(refresh));
        assertNotEquals(digest(), minted, "the proxy cannot mint the instance's own proof");
        assertTrue(proxy.googleAuth.mintedDigests().contains(minted));
        assertEquals("user@example.invalid", GooglePlaceholders.emailOf(answer.pathText("id_token", "")));
        assertTrue(answer.path("expires_in").asLong(0) > 3500);
        assertFalse(reply.body().contains(session.tokens().accessToken()) || reply.body().contains(session.tokens().refreshToken()),
                "nothing real reaches the guest: " + reply.body());

        // The run that signed in works on the minted digest, and so does the instance's own proof
        assertEquals(200, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", access).status());
        assertEquals(200, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist",
                GooglePlaceholders.accessToken(digest())).status());
        var upstream = seenOn(GoogleAccounts.BACKEND);
        assertEquals(2, upstream.size(), seen.toString());
        upstream.forEach(line -> assertTrue(line.endsWith("| Bearer " + session.tokens().accessToken()), line));
    }

    @Test
    void aSignInAgainReplacesTheSessionAndAnEarlierMintedDigestStopsWorking() throws Exception {
        var first = json(token(form(exchangeForm("4/0Afirst"))));
        var firstDigest = GooglePlaceholders.digestOf(first.pathText("access_token", ""));
        var before = store.read(ACCOUNT);
        mintedEmail = "second@example.invalid";

        var second = json(token(form(exchangeForm("4/0Asecond"))));

        var after = store.read(ACCOUNT);
        assertNotEquals(before.sessionId(), after.sessionId());
        assertEquals("second@example.invalid", after.tokens().email());
        // The earlier digest still names the account: the session is the account's, not a run's
        assertEquals(200, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist",
                GooglePlaceholders.accessToken(firstDigest)).status());
        assertTrue(seenOn(GoogleAccounts.BACKEND).getFirst().endsWith("| Bearer " + after.tokens().accessToken()));
        assertNotEquals(firstDigest, GooglePlaceholders.digestOf(second.pathText("access_token", "")));
    }

    @Test
    void aMintedDigestIsForgottenByARestartAndTheInstancesProofStillWorks() throws Exception {
        var minted = json(token(form(exchangeForm("4/0Acode")))).pathText("access_token", "");
        proxy.googleAuth.forgetMinted();
        assertEquals(401, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", minted).status());
        assertEquals(200, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist",
                GooglePlaceholders.accessToken(digest())).status(), "what the next login's script writes");
    }

    @Test
    void aSignInFromAnInstanceOnAKeyAccountIsItsOwnAndRelayedUntouched() throws Exception {
        bind(SECRET, SECRET, "work");
        var reply = token(form(exchangeForm("4/0Amine")));

        assertEquals(200, reply.status(), reply.body());
        assertTrue(json(reply).pathText("access_token", "").startsWith("ya29.real-"), "Google's own answer, as it came");
        assertNull(store.read(ACCOUNT), "no session was kept for anyone");
        assertTrue(proxy.googleAuth.mintedDigests().isEmpty());
    }

    @Test
    void anOversizedTokenRequestIsRefusedNeverRelayed() throws Exception {
        var padded = form(exchangeForm("4/0Apadded")) + "&pad=" + "x".repeat(70_000);
        var declared = token(padded);
        assertEquals(413, declared.status());
        var chunked = send(GoogleAccounts.TOKEN, "POST /token HTTP/1.1\r\nHost: oauth2.googleapis.com\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nTransfer-Encoding: chunked\r\n",
                Integer.toHexString(padded.length()) + "\r\n" + padded + "\r\n0\r\n\r\n");
        assertEquals(413, chunked.status());
        assertEquals(0, tokenRequests.get(), "nothing reaches Google: " + tokenBodies);
        assertNull(store.read(ACCOUNT));
    }

    /** macOS: the address names no instance, so the sign-in is held to the instance that proved itself and was told to sign in. */
    @Test
    void onMacOsASignInIsKeptOnlyForTheInstanceThatWasToldToSignIn() throws Exception {
        var worker = listed(INSTANCE, "10.166.11.2", SECRET, SECRET, null);
        var keyed = listed("keyed", "10.166.11.3", OTHER_SECRET, OTHER_SECRET, "work");
        macOs(worker, keyed);

        // Nobody asked yet: refused, never relayed, so an API-key instance's sign-in cannot become the Google account's
        var unasked = token(form(exchangeForm("4/0Aunasked")));
        assertEquals(403, unasked.status(), unasked.body());
        assertTrue(unasked.body().contains("asked to sign in"), unasked.body());
        assertEquals(0, tokenRequests.get());
        assertNull(store.read(ACCOUNT));

        // agy's first request on the seeded file: a refresh with the worker's proof, told to sign in
        var refresh = token(form(refreshForm(GooglePlaceholders.refreshToken(digest()))));
        assertEquals(401, refresh.status());
        assertTrue(refresh.body().contains("no Google session yet"), refresh.body());
        var kept = token(form(exchangeForm("4/0Aasked")));
        assertEquals(200, kept.status(), kept.body());
        var session = store.read(ACCOUNT);
        assertNotNull(session);
        var minted = GooglePlaceholders.digestOf(json(kept).pathText("access_token", ""));
        assertTrue(proxy.googleAuth.mintedDigests().contains(minted));
        assertFalse(kept.body().contains(session.tokens().accessToken()));

        // The minted digest serves the run that signed in while its instance runs, and no longer once it is gone
        assertEquals(200, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", GooglePlaceholders.accessToken(minted)).status());
        assertTrue(seenOn(GoogleAccounts.BACKEND).getFirst().endsWith("| Bearer " + session.tokens().accessToken()));
        macOs(keyed);
        assertEquals(401, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", GooglePlaceholders.accessToken(minted)).status());

        // A second sign-in with nobody asked again is refused: the window closed with the exchange
        macOs(worker, keyed);
        assertEquals(403, token(form(exchangeForm("4/0Aagain"))).status());
    }

    @Test
    void onMacOsTwoInstancesToldToSignInAtOnceAreRefusedUntilOneSignsIn() throws Exception {
        var worker = listed(INSTANCE, "10.166.11.2", SECRET, SECRET, null);
        var other = listed("other", "10.166.11.3", OTHER_SECRET, OTHER_SECRET, null);
        macOs(worker, other);
        assertEquals(401, token(form(refreshForm(GooglePlaceholders.refreshToken(digest())))).status());
        assertEquals(401, token(form(refreshForm(GooglePlaceholders.refreshToken(otherDigest())))).status());
        var ambiguous = token(form(exchangeForm("4/0Awhich")));
        assertEquals(403, ambiguous.status(), ambiguous.body());
        assertTrue(ambiguous.body().contains("several instances"), ambiguous.body());
        assertEquals(0, tokenRequests.get());
    }

    @Test
    void aUserinfoRefusalIsNoBackendRefusal() throws Exception {
        var session = store.create(ACCOUNT, real(3600));
        backendRefuses = true;
        assertEquals(401, backend(GoogleAccounts.USERINFO, "GET", "/oauth2/v2/userinfo", GooglePlaceholders.accessToken(digest())).status());
        assertFalse(proxy.googleAuth.refuses(ACCOUNT, session.sessionId()), "only the Code Assist hosts judge the token");
        backendRefuses = false;
        assertEquals(200, token(form(refreshForm(GooglePlaceholders.refreshToken(digest())))).status());
        assertEquals(0, tokenRequests.get(), "the token still had time left and nothing refused it");
    }

    @Test
    void aSignInFromNobodyIsRelayedUntouchedOnLinux() throws Exception {
        useListing("[]");
        var reply = token(form(exchangeForm("4/0Astranger")));

        assertEquals(200, reply.status(), reply.body());
        assertTrue(json(reply).pathText("access_token", "").startsWith("ya29.real-"));
        assertNull(store.read(ACCOUNT));
    }

    /** agy's exchange from an instance on a Google account is never relayed: a shape isx does not know is refused, so no real token lands in the guest. */
    @Test
    void anAgyExchangeOfAnUnknownShapeFromAGoogleAccountInstanceIsRefusedNotRelayed() throws Exception {
        var form = exchangeForm("4/0Anew");
        form.put("scope", "openid");
        var reply = token(form(form));

        assertEquals(400, reply.status(), reply.body());
        assertEquals(0, tokenRequests.get(), "never relayed");
        assertNull(store.read(ACCOUNT));
        assertTrue(proxy.googleAuth.mintedDigests().isEmpty());
    }

    @Test
    void anExchangeThatIsNotAgysIsRelayedUntouched() throws Exception {
        var form = exchangeForm("4/0Aother");
        form.put("client_id", "another-client.apps.googleusercontent.com");
        var reply = token(form(form));

        assertEquals(200, reply.status());
        assertTrue(json(reply).pathText("access_token", "").startsWith("ya29.real-"));
        assertNull(store.read(ACCOUNT));
    }

    @Test
    void googlesRefusalOfASignInReachesTheGuestAndKeepsNoSession() throws Exception {
        tokenError = "invalid_grant";
        var reply = token(form(exchangeForm("4/0Abad")));

        assertEquals(400, reply.status());
        assertEquals("invalid_grant", json(reply).pathText("error", ""));
        assertNull(store.read(ACCOUNT));
        assertTrue(proxy.googleAuth.mintedDigests().isEmpty());
    }

    // --- Injection ---

    @Test
    void anAllowedRequestWithTheInstancesProofCarriesTheSessionsTokenAndNothingOfTheGuests() throws Exception {
        var session = store.create(ACCOUNT, real(3600));
        for (var target : new String[]{"POST /v1internal:streamGenerateContent?alt=sse", "POST /v1internal:loadCodeAssist",
                "GET /v1internal/cascadeNuxes?page_size=1000"}) {
            seen.clear();
            var parts = target.split(" ");
            var reply = backend(GoogleAccounts.BACKEND, parts[0], parts[1], GooglePlaceholders.accessToken(digest()));
            assertEquals(200, reply.status(), target + ": " + reply.body());
            var upstream = seenOn(GoogleAccounts.BACKEND);
            assertEquals(1, upstream.size(), seen.toString());
            assertTrue(upstream.getFirst().contains(" " + parts[1] + " | Bearer " + session.tokens().accessToken()), upstream.toString());
            assertFalse(upstream.getFirst().contains(ProofToken.MARKER));
        }
        assertEquals(200, backend(GoogleAccounts.BACKEND_PROD, "POST", "/v1internal:loadCodeAssist",
                GooglePlaceholders.accessToken(digest())).status(), "the production host too");
        assertEquals(200, backend(GoogleAccounts.USERINFO, "GET", "/oauth2/v2/userinfo",
                GooglePlaceholders.accessToken(digest())).status());
        assertTrue(seenOn(GoogleAccounts.USERINFO).getFirst().endsWith("| Bearer " + session.tokens().accessToken()));
    }

    @Test
    void aRequestOffTheCodeAssistApiOrOffUserinfoIsRefusedWithACredential() throws Exception {
        store.create(ACCOUNT, real(3600));
        for (var refused : new String[][]{
                {GoogleAccounts.BACKEND, "POST", "/v1/projects/mine/locations/us/datasets"},
                {GoogleAccounts.BACKEND, "POST", "/v1internal"},
                {GoogleAccounts.BACKEND, "POST", "/v1internalx:loadCodeAssist"},
                {GoogleAccounts.BACKEND, "POST", "/v1internal:load%41ssist"},
                {GoogleAccounts.BACKEND, "POST", "/v1internal/../v1/other"},
                {GoogleAccounts.USERINFO, "GET", "/drive/v3/files"},
                {GoogleAccounts.USERINFO, "POST", "/oauth2/v2/userinfo"}}) {
            seen.clear();
            var reply = backend(refused[0], refused[1], refused[2], GooglePlaceholders.accessToken(digest()));
            assertTrue(reply.status() == 403 || reply.status() == 400, String.join(" ", refused) + ": " + reply.status());
            assertTrue(seen.isEmpty(), "never relayed: " + seen);
        }
    }

    @Test
    void aRequestWithoutAClaimIsRelayedUntouched() throws Exception {
        store.create(ACCOUNT, real(3600));
        var reply = backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", "ya29.my-own-token");
        assertEquals(200, reply.status());
        assertTrue(seenOn(GoogleAccounts.BACKEND).getFirst().endsWith("| Bearer ya29.my-own-token"));
        var other = backend(GoogleAccounts.BACKEND, "GET", "/v1/anything", null);
        assertEquals(200, other.status());
    }

    @Test
    void aWrongProofAnotherInstancesOrAnInstanceOnAKeyAccountGetsNothing() throws Exception {
        var session = store.create(ACCOUNT, real(3600));
        for (var bearer : new String[]{GooglePlaceholders.accessToken(otherDigest()), "ya29.isx_short",
                GooglePlaceholders.accessToken("ab".repeat(16))}) {
            seen.clear();
            var reply = backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", bearer);
            assertEquals(401, reply.status(), bearer + ": " + reply.body());
            assertTrue(seen.isEmpty(), seen.toString());
        }
        // A copy, which lists the proofs with a secret that is no longer its own, still proves by the seed
        bind(SECRET, OTHER_SECRET);
        assertEquals(200, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist",
                GooglePlaceholders.accessToken(digest())).status(), "the proof is the seed's, not the secret's");
        // The instance pinned to the key account: its proof verifies, but its account is no Google one
        bind(SECRET, SECRET, "work");
        seen.clear();
        var keyed = backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", GooglePlaceholders.accessToken(digest()));
        assertEquals(401, keyed.status(), keyed.body());
        assertTrue(seen.isEmpty());
        assertFalse(seen.toString().contains(session.tokens().accessToken()));
    }

    @Test
    void aProofOfAnAccountThatHasNotSignedInIsToldSo() throws Exception {
        var reply = backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist", GooglePlaceholders.accessToken(digest()));
        assertEquals(401, reply.status());
        assertTrue(reply.body().contains("no Google session yet"), reply.body());
        assertTrue(seen.isEmpty());
    }

    @Test
    void aMalformedClaimedRequestIsRefusedBeforeAnythingLeaves() throws Exception {
        store.create(ACCOUNT, real(3600));
        var twoAuth = send(GoogleAccounts.BACKEND, "POST /v1internal:loadCodeAssist HTTP/1.1\r\nHost: daily-cloudcode-pa.googleapis.com\r\n"
                + "Authorization: Bearer " + GooglePlaceholders.accessToken(digest()) + "\r\nAuthorization: Bearer other\r\n", "{}");
        assertEquals(400, twoAuth.status());
        var wrongHost = send(GoogleAccounts.BACKEND, "POST /v1internal:loadCodeAssist HTTP/1.1\r\nHost: cloudcode-pa.googleapis.com\r\n"
                + "Authorization: Bearer " + GooglePlaceholders.accessToken(digest()) + "\r\n", "{}");
        assertEquals(400, wrongHost.status());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    // --- Refresh ---

    @Test
    void aRefreshOfATokenWithTimeLeftIsAnsweredFromTheSessionWithoutAskingGoogle() throws Exception {
        var session = store.create(ACCOUNT, real(3600));
        var reply = token(form(refreshForm(GooglePlaceholders.refreshToken(digest()))));

        assertEquals(200, reply.status(), reply.body());
        assertEquals(0, tokenRequests.get(), "Google was not asked");
        var answer = json(reply);
        assertEquals(GooglePlaceholders.accessToken(digest()), answer.pathText("access_token", ""));
        assertEquals(GooglePlaceholders.refreshToken(digest()), answer.pathText("refresh_token", ""));
        assertEquals("user@example.invalid", GooglePlaceholders.emailOf(answer.pathText("id_token", "")));
        assertTrue(answer.path("expires_in").asLong(0) > 3500);
        assertFalse(reply.body().contains(session.tokens().refreshToken()));
    }

    @Test
    void aRefreshOfAnExpiredTokenGoesToGoogleWithTheRealRefreshTokenAndIsCommitted() throws Exception {
        var session = store.create(ACCOUNT, real(-10));
        mintedEmail = "renamed@example.invalid";
        var reply = token(form(refreshForm(GooglePlaceholders.refreshToken(digest()))));

        assertEquals(200, reply.status(), reply.body());
        assertEquals(1, tokenRequests.get());
        var sent = tokenBodies.peek();
        assertTrue(sent.contains("refresh_token=" + URLEncoder.encode(session.tokens().refreshToken(), StandardCharsets.UTF_8)), sent);
        assertTrue(sent.contains("client_secret=" + CLIENT_SECRET) && sent.contains("client_id=" + GoogleAuthRoute.AGY_CLIENT_ID), sent);
        assertFalse(sent.contains(ProofToken.MARKER), "no placeholder goes to Google: " + sent);
        var after = store.read(ACCOUNT);
        assertEquals(session.generation() + 1, after.generation());
        assertNotEquals(session.tokens().accessToken(), after.tokens().accessToken());
        assertEquals("renamed@example.invalid", after.tokens().email(), "the id token's email is kept current");
        assertTrue(after.tokens().expiry() > System.currentTimeMillis() / 1000 + 3500);
        var answer = json(reply);
        assertEquals(GooglePlaceholders.accessToken(digest()), answer.pathText("access_token", ""));
        assertEquals("renamed@example.invalid", GooglePlaceholders.emailOf(answer.pathText("id_token", "")));
        assertFalse(reply.body().contains(after.tokens().accessToken()));
    }

    @Test
    void aRefreshAnswerWithoutARefreshTokenKeepsTheSessions() throws Exception {
        var session = store.create(ACCOUNT, real(-10));
        mintRefreshToken = false;
        assertEquals(200, token(form(refreshForm(GooglePlaceholders.refreshToken(digest())))).status());
        assertEquals(session.tokens().refreshToken(), store.read(ACCOUNT).tokens().refreshToken());
    }

    @Test
    void aRefreshGoogleRefusesEndsTheSessionsUseAndSaysToSignInAgain() throws Exception {
        store.create(ACCOUNT, real(-10));
        tokenError = "invalid_grant";
        var reply = token(form(refreshForm(GooglePlaceholders.refreshToken(digest()))));

        assertEquals(401, reply.status(), reply.body());
        assertEquals("invalid_grant", json(reply).pathText("error", ""));
        assertTrue(reply.body().contains("sign in again"), reply.body());
        assertNotNull(proxy.authError, "the default account's standing error is reported");
        assertTrue(proxy.authError.contains("gemini account 'home'"), proxy.authError);
        assertNull(store.read(ACCOUNT).refreshing(), "the abandoned attempt leaves no record");
    }

    @Test
    void aRefusedBackendTokenMakesTheNextRefreshGoUpstream() throws Exception {
        var session = store.create(ACCOUNT, real(3600));
        backendRefuses = true;
        assertEquals(401, backend(GoogleAccounts.BACKEND, "POST", "/v1internal:loadCodeAssist",
                GooglePlaceholders.accessToken(digest())).status());
        assertTrue(proxy.googleAuth.refuses(ACCOUNT, session.sessionId()));
        backendRefuses = false;

        assertEquals(200, token(form(refreshForm(GooglePlaceholders.refreshToken(digest())))).status());
        assertEquals(1, tokenRequests.get(), "the token still had time left, but the backend refused it");
        assertFalse(proxy.googleAuth.refuses(ACCOUNT, session.sessionId()));
    }

    @Test
    void aRefreshRequestThatIsNotAgysOrCarriesAnotherProofIsRefusedAndNeverRelayed() throws Exception {
        store.create(ACCOUNT, real(-10));
        var wrongProof = token(form(refreshForm(GooglePlaceholders.refreshToken(otherDigest()))));
        assertEquals(401, wrongProof.status());
        var form = refreshForm(GooglePlaceholders.refreshToken(digest()));
        form.put("client_id", "another-client");
        assertEquals(400, token(form(form)).status());
        var extra = refreshForm(GooglePlaceholders.refreshToken(digest()));
        extra.put("scope", "https://www.googleapis.com/auth/drive");
        assertEquals(400, token(form(extra)).status());
        assertEquals(0, tokenRequests.get(), tokenBodies.toString());
    }

    @Test
    void aRefreshWithARealTokenIsSomebodyElsesAndRelayedUntouched() throws Exception {
        var reply = token(form(refreshForm("1//somebody-elses")));
        assertEquals(200, reply.status());
        assertEquals(1, tokenRequests.get());
        assertTrue(tokenBodies.peek().contains("somebody-elses"));
    }

    @Test
    void aGuestsSignOutNeverEndsTheHostsSession() throws Exception {
        var session = store.create(ACCOUNT, real(3600));
        var reply = send(GoogleAccounts.TOKEN, "POST /revoke HTTP/1.1\r\nHost: oauth2.googleapis.com\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n", "token=" + URLEncoder.encode(
                GooglePlaceholders.refreshToken(digest()), StandardCharsets.UTF_8));
        assertEquals(200, reply.status());
        assertTrue(seen.isEmpty(), "answered here: " + seen);
        assertEquals(session, store.read(ACCOUNT));
        var theirs = send(GoogleAccounts.TOKEN, "POST /revoke HTTP/1.1\r\nHost: oauth2.googleapis.com\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n", "token=1%2F%2Ftheirs");
        assertEquals(200, theirs.status());
        assertEquals(1, seenOn(GoogleAccounts.TOKEN).size(), "somebody else's revocation is relayed");
    }

    @Test
    void theFormIsParsedAndEncodedRoundTrip() {
        var form = GoogleAuthRoute.parseForm(Buffer.buffer("a=1%2F%2Fx&b=y+z&c"));
        assertEquals(Map.of("a", "1//x", "b", "y z", "c", ""), form);
        assertEquals("a=1%2F%2Fx&b=y+z&c=", GoogleAuthRoute.encodeForm(form));
        assertTrue(GoogleAuthRoute.parseForm(Buffer.buffer("a=%zz")).isEmpty(), "a malformed body is nobody's request");
    }
}
