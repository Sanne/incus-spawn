package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The device-code sign-in against a stub of OpenAI's auth server (#1332): the endpoints Codex's
 * own login drives, the form the exchange must send, and what the session store is given. The
 * stub records every request, so the grant's shape is checked, not assumed.
 */
class ChatGptSignInTest {

    static final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    /** The exchange's body, form-encoded, recorded when the stub served one. */
    static volatile String exchangeBody;
    /** How many times the poll was asked before it granted the code. */
    static volatile int pollsUntilGranted = 1;
    /** Whether the token endpoint refuses the exchange, as a failed sign-in does. */
    static volatile boolean refuseExchange;
    /** The answer of a poll the stub already granted, as OpenAI's auth server answers a grant asked for twice. */
    static volatile boolean granted;
    /** The device-code answer, which codex-rs also reads with the code under {@code usercode}. */
    static volatile String userCodeAnswer;

    HttpServer stub;
    String issuer;
    ChatGptSignIn signIn;

    @BeforeEach
    void start() throws IOException {
        seen.clear();
        exchangeBody = null;
        pollsUntilGranted = 1;
        refuseExchange = false;
        granted = false;
        userCodeAnswer = "{\"device_auth_id\":\"dev-123\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"1\"}";
        // A dead "browser" thread must fail the test in seconds, not hang for the sign-in's
        // full fifteen-minute patience
        ChatGptSignIn.testBrowserWait = java.time.Duration.ofSeconds(20);
        browserFailure = null;
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
            var out = exchange.getResponseBody();
            switch (exchange.getRequestURI().getPath()) {
                case "/api/accounts/deviceauth/usercode" -> {
                    var answer = userCodeAnswer;
                    if (answer == null) {
                        exchange.sendResponseHeaders(404, -1);
                        out.close();
                        return;
                    }
                    exchange.sendResponseHeaders(200, answer.length());
                    out.write(answer.getBytes(StandardCharsets.UTF_8));
                }
                case "/api/accounts/deviceauth/token" -> {
                    // A device grant is handed out once: the poll that earns it is answered, and
                    // every later ask for the same code is refused, as OpenAI's auth server does
                    if (seen.stream().filter(s -> s.startsWith("POST /api/accounts/deviceauth/token")).count()
                            <= pollsUntilGranted || granted) {
                        exchange.sendResponseHeaders(403, -1);
                    } else {
                        granted = true;
                        exchangeBody = body;
                        var answer = "{\"authorization_code\":\"auth-code-1\",\"code_challenge\":\"chal\","
                                + "\"code_verifier\":\"verifier-1\"}";
                        exchange.sendResponseHeaders(200, answer.length());
                        out.write(answer.getBytes(StandardCharsets.UTF_8));
                    }
                }
                case "/oauth/token" -> {
                    exchangeBody = body;
                    if (refuseExchange) {
                        exchange.sendResponseHeaders(400, -1);
                        out.close();
                        return;
                    }
                    var idToken = jwt("{\"email\":\"user@example.invalid\","
                            + "\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct-ent\"}}");
                    var answer = "{\"id_token\":\"" + idToken + "\",\"access_token\":\"at-real\","
                            + "\"refresh_token\":\"rt-real\"}";
                    exchange.sendResponseHeaders(200, answer.length());
                    out.write(answer.getBytes(StandardCharsets.UTF_8));
                }
                default -> exchange.sendResponseHeaders(404, -1);
            }
            out.close();
        });
        stub.start();
        issuer = "http://127.0.0.1:" + stub.getAddress().getPort();
        signIn = new ChatGptSignIn(issuer);
    }

    @AfterEach
    void stop() {
        stub.stop(0);
    }

    private static String jwt(String payload) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "."
                + enc.encodeToString(payload.getBytes()) + ".signature";
    }

    @Test
    void aDeviceCodeComesFromTheUsercodeEndpointWithCodexsClient() throws Exception {
        var device = signIn.requestDeviceCode();

        assertEquals("dev-123", device.deviceAuthId());
        assertEquals("ABCD-EFGH", device.userCode());
        assertEquals(1, device.intervalSeconds(), "the server sends the interval as a string");
        assertEquals(issuer + "/codex/device", device.verificationUrl());
        assertEquals(List.of("POST /api/accounts/deviceauth/usercode {\"client_id\":\""
                + ChatGptAccounts.CODEX_CLIENT_ID + "\"}"), List.copyOf(seen));
    }

    @Test
    void theCodeIsReadUnderEitherNameCodexReads() throws Exception {
        userCodeAnswer = "{\"device_auth_id\":\"dev-123\",\"usercode\":\"WXYZ-1234\",\"interval\":\"1\"}";

        assertEquals("WXYZ-1234", signIn.requestDeviceCode().userCode());
    }

    @Test
    void anIntervalThatIsNotANumberIsTheServersAnswerNotAnUnreachableServer() {
        userCodeAnswer = "{\"device_auth_id\":\"dev-123\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"5s\"}";

        var e = assertThrows(ChatGptSignIn.DeviceCodeRefused.class, () -> signIn.requestDeviceCode());
        assertTrue(e.getMessage().contains("poll interval"), e.getMessage());
    }

    @Test
    void aServerWithoutTheDeviceFlowIsToldApart() {
        userCodeAnswer = null;

        assertThrows(ChatGptSignIn.DeviceCodeUnavailable.class, () -> signIn.requestDeviceCode());
    }

    @Test
    void thePollWaitsForThePersonAndExchangesTheCodeAsCodexDoes() throws Exception {
        pollsUntilGranted = 2;

        var tokens = signIn.awaitAuthorization(signIn.requestDeviceCode(), null);

        assertEquals("at-real", tokens.accessToken());
        assertEquals("rt-real", tokens.refreshToken());
        assertEquals("acct-ent", tokens.accountId(), "the workspace the id token names, not a secret");
        // The grant is the one Codex's OAuth client sends (codex-rs oauth/client.rs exchange_code),
        // form-encoded as that client's TokenEncoding::Form
        var decoded = java.net.URLDecoder.decode(exchangeBody, StandardCharsets.UTF_8);
        var form = Map.of("grant_type", "authorization_code", "client_id", ChatGptAccounts.CODEX_CLIENT_ID,
                "code", "auth-code-1", "redirect_uri", issuer + "/deviceauth/callback",
                "code_verifier", "verifier-1");
        for (var entry : form.entrySet()) {
            assertTrue(decoded.contains(entry.getKey() + "=" + entry.getValue()),
                    entry.getKey() + " missing from " + decoded);
        }
        assertTrue(seen.stream().filter(s -> s.startsWith("POST /api/accounts/deviceauth/token")).count() == 3,
                "the grant is read from the poll that earned it, never asked for again: " + seen);
    }

    @Test
    void aRefusedExchangeFailsTheSignIn() throws Exception {
        refuseExchange = true;

        var device = signIn.requestDeviceCode();
        assertThrows(IOException.class, () -> signIn.awaitAuthorization(device, null));
    }

    @Test
    void theGrantIsHandedOutOnceAndReadFromThePollThatEarnedIt() throws Exception {
        // pollsUntilGranted stays 1: the first poll is refused, the second grants, and a third
        // ask -- what reading the code from a second request would do -- is refused in turn
        var tokens = signIn.awaitAuthorization(signIn.requestDeviceCode(), null);

        assertEquals("at-real", tokens.accessToken());
        assertEquals("rt-real", tokens.refreshToken());
    }

    // ── the browser flow, for an organisation whose SSO the device code cannot reach ──

    /** Connects to the loopback listener as the browser would, and answers with the response's status. */
    private static int browseTo(String target) throws Exception {
        return browseToFully(target).status();
    }

    /** The response the listener answers a browser's request with, status and body. */
    private record Answered(int status, String body) {}

    private static Answered browseToFully(String target) throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", callbackPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            var line = reader.readLine();
            var body = new StringBuilder();
            for (var rest = reader.readLine(); rest != null; rest = reader.readLine()) body.append(rest);
            return line == null ? new Answered(0, "")
                    : new Answered(Integer.parseInt(line.substring(9, 12)), body.toString());
        }
    }

    /** The port the listener took: one of Codex's two, whichever was free. */
    private static int callbackPort() throws Exception {
        var authorizeUrl = lastAuthorizeUrl;
        var redirect = paramOf(authorizeUrl, "redirect_uri");
        return Integer.parseInt(redirect.substring("http://127.0.0.1:".length(),
                redirect.indexOf('/', "http://127.0.0.1:".length())));
    }

    private static volatile String lastAuthorizeUrl;
    /** What killed the "browser" thread, so a test fails on it rather than on a timeout. */
    static volatile Throwable browserFailure;

    private static String paramOf(String url, String name) {
        for (var pair : url.substring(url.indexOf('?') + 1).split("&")) {
            if (pair.startsWith(name + "=")) return java.net.URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static final String CALLBACK = "/auth/callback";

    @Test
    void theBrowserFlowSignsInWhereTheDeviceCodeCannot() throws Exception {
        var thread = new Thread(() -> {
            try {
                var state = paramOf(lastAuthorizeUrl, "state");
                var status = browseTo(CALLBACK + "?code=browser-code-1&state=" + state);
                if (status != 200) throw new IllegalStateException("callback answered " + status);
            } catch (Throwable e) {
                browserFailure = e;
            }
        });

        var tokens = signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        });

        thread.join(10_000);
        assertNull(browserFailure, "the browser thread died");
        assertEquals("at-real", tokens.accessToken());
        assertEquals("acct-ent", tokens.accountId());
        // The authorize URL is Codex's own (codex-rs build_authorize_url), on a port OpenAI allow-lists
        var authorizeUrl = lastAuthorizeUrl;
        assertTrue(authorizeUrl.startsWith(issuer + "/oauth/authorize?"), authorizeUrl);
        assertEquals(ChatGptAccounts.CODEX_CLIENT_ID, paramOf(authorizeUrl, "client_id"));
        assertEquals("S256", paramOf(authorizeUrl, "code_challenge_method"));
        var redirect = paramOf(authorizeUrl, "redirect_uri");
        assertTrue(redirect.equals("http://127.0.0.1:1455" + CALLBACK)
                || redirect.equals("http://127.0.0.1:1457" + CALLBACK), authorizeUrl);
        assertTrue(authorizeUrl.contains("originator=codex_cli_rs"), authorizeUrl);
        // The exchange spent the PKCE pair the authorize URL promised
        var challenge = paramOf(authorizeUrl, "code_challenge");
        var verifier = java.net.URLDecoder.decode(exchangeBody, StandardCharsets.UTF_8)
                .split("code_verifier=")[1].split("&")[0];
        var expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(expected, challenge, "the challenge is the verifier's SHA-256, as S256 promises");
        assertTrue(java.net.URLDecoder.decode(exchangeBody, StandardCharsets.UTF_8).contains("code=browser-code-1"));
    }

    @Test
    void aStateThatIsNotThisSignInsIsRefusedWithoutEndingIt() throws Exception {
        var finished = new java.util.concurrent.CompletableFuture<Void>();
        var thread = new Thread(() -> {
            try {
                // A stale tab from an earlier sign-in: the right path, the wrong state
                if (browseTo(CALLBACK + "?code=stale-code&state=someone-elses-state") != 400) {
                    finished.completeExceptionally(new IllegalStateException("a mismatched state was accepted"));
                }
                var state = paramOf(lastAuthorizeUrl, "state");
                if (browseTo(CALLBACK + "?code=browser-code-1&state=" + state) != 200) {
                    finished.completeExceptionally(new IllegalStateException("the real callback was refused"));
                }
                finished.complete(null);
            } catch (Exception e) {
                finished.completeExceptionally(e);
            }
        });

        var tokens = signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        });

        thread.join(10_000);
        finished.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("at-real", tokens.accessToken(), "the stale code never reached the exchange");
    }

    @Test
    void aQueryWithABadEscapeIsRefusedWithoutEndingIt() throws Exception {
        var finished = new java.util.concurrent.CompletableFuture<Void>();
        var thread = new Thread(() -> {
            try {
                // A local request whose query does not decode: it carries no state of this sign-in
                if (browseTo(CALLBACK + "?state=%zz") != 400) {
                    finished.completeExceptionally(new IllegalStateException("a query that does not decode was not refused"));
                }
                var state = paramOf(lastAuthorizeUrl, "state");
                if (browseTo(CALLBACK + "?code=browser-code-1&state=" + state) != 200) {
                    finished.completeExceptionally(new IllegalStateException("the real callback was refused"));
                }
                finished.complete(null);
            } catch (Exception e) {
                finished.completeExceptionally(e);
            }
        });

        var tokens = signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        });

        thread.join(10_000);
        finished.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("at-real", tokens.accessToken(), "only this sign-in's own callback ends the wait");
    }

    @Test
    void aCallbackWithHeadersPastTheCapStillEndsIt() throws Exception {
        var statusLine = new java.util.concurrent.CompletableFuture<String>();
        var thread = new Thread(() -> {
            // A browser with many cookies for 127.0.0.1: its headers alone pass the cap, many
            // times over, so the bytes left unread would not fit a socket buffer unnoticed
            var state = paramOf(lastAuthorizeUrl, "state");
            try (var socket = new java.net.Socket("127.0.0.1", callbackPort())) {
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(("GET " + CALLBACK + "?code=browser-code-1&state=" + state
                        + " HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: c=" + "x".repeat(8 * ChatGptSignIn.MAX_REQUEST_BYTES)
                        + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                statusLine.complete(new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), StandardCharsets.ISO_8859_1)).readLine());
            } catch (Exception e) {
                statusLine.completeExceptionally(e);
            }
        });

        var tokens = signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        });

        thread.join(10_000);
        assertEquals("at-real", tokens.accessToken(), "the callback's code was exchanged despite its headers");
        // The headers were read to their end, so the connection closes cleanly and the browser
        // shows the answer, never a reset
        assertEquals("HTTP/1.1 200 OK", statusLine.get(1, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void anErrorWhoseDescriptionDoesNotDecodeStillEndsIt() {
        var thread = new Thread(() -> {
            try {
                // This sign-in's own state, and a description with a bad percent escape: the
                // pair that does not decode is left out, the error still ends the wait
                assertEquals(400, browseTo(CALLBACK + "?error=access_denied&error_description=100%&state="
                        + paramOf(lastAuthorizeUrl, "state")));
            } catch (Throwable e) {
                browserFailure = e;
            }
        });

        var refused = assertThrows(IOException.class, () -> signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        }));
        assertTrue(refused.getMessage().contains("access_denied"), refused.getMessage());
    }

    @Test
    void aClientTricklingItsRequestDoesNotHoldTheListener() throws Exception {
        ChatGptSignIn.testReadTimeout = java.time.Duration.ofMillis(400);
        var trickling = new java.util.concurrent.atomic.AtomicBoolean(true);
        var trickleConnected = new java.util.concurrent.CountDownLatch(1);
        try {
            var finished = new java.util.concurrent.CompletableFuture<Long>();
            var trickle = new Thread(() -> {
                // One byte every 100 ms: each read is well within the read timeout, the whole
                // request never is
                try (var socket = new java.net.Socket("127.0.0.1", callbackPort())) {
                    // Connected first, so its connection is the first the listener accepts
                    trickleConnected.countDown();
                    var out = socket.getOutputStream();
                    for (var b : "GET /auth/callback?state=x HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII)) {
                        if (!trickling.get()) break;
                        out.write(b);
                        out.flush();
                        Thread.sleep(100); // not a wait: trickles the request a byte at a time
                    }
                } catch (Exception ignored) {
                    // the listener dropping it is the point
                }
            });
            var browser = new Thread(() -> {
                try {
                    trickle.start();
                    if (!trickleConnected.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the trickling client never connected");
                    }
                    Thread.sleep(200);
                    var started = System.nanoTime();
                    var state = paramOf(lastAuthorizeUrl, "state");
                    if (browseTo(CALLBACK + "?code=browser-code-1&state=" + state) != 200) {
                        finished.completeExceptionally(new IllegalStateException("the real callback was refused"));
                    }
                    finished.complete(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
                } catch (Exception e) {
                    finished.completeExceptionally(e);
                }
            });

            var tokens = signIn.signInWithBrowser(url -> {
                lastAuthorizeUrl = url;
                browser.start();
            });

            browser.join(10_000);
            var waited = finished.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("at-real", tokens.accessToken());
            assertTrue(waited < 2_000, "the trickle held the listener for " + waited + " ms; its request alone takes 3.8 s");
        } finally {
            trickling.set(false);
            ChatGptSignIn.testReadTimeout = null;
        }
    }

    @Test
    void aRefusedSignInTheBrowserReports() {
        var thread = new Thread(() -> {
            try {
                assertEquals(400, browseTo(CALLBACK + "?error=access_denied&state="
                        + paramOf(lastAuthorizeUrl, "state")));
            } catch (Throwable e) {
                browserFailure = e;
            }
        });

        var refused = assertThrows(IOException.class, () -> signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        }));
        assertTrue(refused.getMessage().contains("access_denied"), refused.getMessage());
    }

    @Test
    void anErrorFromAnotherSignInsCallbackDoesNotEndThisOne() throws Exception {
        var finished = new java.util.concurrent.CompletableFuture<Void>();
        var thread = new Thread(() -> {
            try {
                // A local page or stale tab carrying an error, for a state this sign-in never made
                if (browseTo(CALLBACK + "?error=access_denied&state=someone-elses-state") != 400) {
                    finished.completeExceptionally(new IllegalStateException("a foreign error was taken for this sign-in's"));
                }
                var state = paramOf(lastAuthorizeUrl, "state");
                if (browseTo(CALLBACK + "?code=browser-code-1&state=" + state) != 200) {
                    finished.completeExceptionally(new IllegalStateException("the real callback was refused"));
                }
                finished.complete(null);
            } catch (Exception e) {
                finished.completeExceptionally(e);
            }
        });

        var tokens = signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        });

        thread.join(10_000);
        finished.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("at-real", tokens.accessToken(), "the foreign error never reached the exchange");
    }

    @Test
    void aRefusedSignInsDescriptionIsEscapedInTheAnswer() {
        var answered = new java.util.concurrent.CompletableFuture<Answered>();
        var thread = new Thread(() -> {
            try {
                answered.complete(browseToFully(CALLBACK + "?error=access_denied"
                        + "&error_description=%3Cscript%3Ealert(1)%3C/script%3E"
                        + "&state=" + paramOf(lastAuthorizeUrl, "state")));
            } catch (Throwable e) {
                browserFailure = e;
            }
        });

        assertThrows(IOException.class, () -> signIn.signInWithBrowser(url -> {
            lastAuthorizeUrl = url;
            thread.start();
        }));
        var page = answered.join();
        assertEquals(400, page.status());
        assertTrue(page.body().contains("&lt;script&gt;"), page.body());
        assertFalse(page.body().contains("<script>"), "the description is text on the page, never markup: " + page.body());
    }

    @Test
    void aPreconnectThatClosesOrStaysIdleDoesNotEndTheWait() throws Exception {
        ChatGptSignIn.testReadTimeout = java.time.Duration.ofMillis(300);
        try {
            var finished = new java.util.concurrent.CompletableFuture<Void>();
            var thread = new Thread(() -> {
                try {
                    // A browser preconnect: closed without a request
                    new java.net.Socket("127.0.0.1", callbackPort()).close();
                    // And one that stays idle past the read timeout
                    try (var idle = new java.net.Socket("127.0.0.1", callbackPort())) {
                        Thread.sleep(900);
                    }
                    var state = paramOf(lastAuthorizeUrl, "state");
                    if (browseTo(CALLBACK + "?code=browser-code-1&state=" + state) != 200) {
                        finished.completeExceptionally(new IllegalStateException("the real callback was refused"));
                    }
                    finished.complete(null);
                } catch (Exception e) {
                    finished.completeExceptionally(e);
                }
            });

            var tokens = signIn.signInWithBrowser(url -> {
                lastAuthorizeUrl = url;
                thread.start();
            });

            thread.join(10_000);
            finished.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("at-real", tokens.accessToken(), "the preconnects never ended the wait");
        } finally {
            ChatGptSignIn.testReadTimeout = null;
        }
    }
}
