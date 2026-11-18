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

    HttpServer stub;
    String issuer;
    ChatGptSignIn signIn;

    @BeforeEach
    void start() throws IOException {
        seen.clear();
        exchangeBody = null;
        pollsUntilGranted = 1;
        refuseExchange = false;
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
                    var answer = "{\"device_auth_id\":\"dev-123\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"1\"}";
                    exchange.sendResponseHeaders(200, answer.length());
                    out.write(answer.getBytes(StandardCharsets.UTF_8));
                }
                case "/api/accounts/deviceauth/token" -> {
                    if (seen.stream().filter(s -> s.startsWith("POST /api/accounts/deviceauth/token")).count()
                            <= pollsUntilGranted) {
                        exchange.sendResponseHeaders(403, -1);
                    } else {
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
        assertTrue(seen.stream().filter(s -> s.startsWith("POST /api/accounts/deviceauth/token")).count() >= 3,
                "polled until the person finished: " + seen);
    }

    @Test
    void aRefusedExchangeFailsTheSignIn() throws Exception {
        refuseExchange = true;

        var device = signIn.requestDeviceCode();
        assertThrows(IOException.class, () -> signIn.awaitAuthorization(device, null));
    }

    // ── the browser flow, for an organisation whose SSO the device code cannot reach ──

    /** Connects to the loopback listener as the browser would, and answers with the response's status. */
    private static int browseTo(String target) throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", callbackPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var line = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
            return line == null ? 0 : Integer.parseInt(line.substring(9, 12));
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
}
