package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.util.SystemTrust;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Signs a ChatGPT session in on the host (#1332), by the device-code flow Codex itself uses
 * (codex-rs {@code login/src/device_code_auth.rs}): OpenAI's auth server issues a one-time code
 * the person enters at {@code auth.openai.com/codex/device}, isx polls until they did, and
 * exchanges the authorization code at the same token endpoint the proxy later refreshes at.
 *
 * <p>What this writes is the session only isx keeps: {@link ChatGptSessionStore#create} under
 * {@code ~/.config/incus-spawn/openai/<account>/}, never a file in an instance. The tokens pass
 * through this process's memory alone; the person sees the code and the verification URL, never
 * a credential.
 *
 * <p>Every request names Codex's OAuth client id, so what this signs in is the session Codex
 * would have made itself: the proxy can answer Codex's refreshes for it, and OpenAI rotates the
 * refresh token on every use exactly as that client expects.
 *
 * <p>Not final: {@code CredentialSetup} hands tests a subclass of it, as it does for every
 * network call a credential step makes.
 */
public class ChatGptSignIn {

    /** OpenAI's auth server, the issuer Codex's login uses (codex-rs {@code DEFAULT_ISSUER}). */
    public static final String DEFAULT_ISSUER = "https://auth.openai.com";
    /** How long the person may take to enter the code, as Codex's login waits (15 minutes). */
    private static final Duration MAX_WAIT = Duration.ofMinutes(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** How long the person may take to finish in the browser, as Codex's login waits. */
    private static final Duration BROWSER_WAIT = Duration.ofMinutes(15);
    /** The wait a test runs with, so a failure surfaces in seconds; production uses {@link #BROWSER_WAIT}. */
    static volatile Duration testBrowserWait;
    /**
     * The ports Codex's login itself listens on, tried in this order: OpenAI's auth server
     * allow-lists exactly these redirect URIs (codex-rs: "Keep in sync with the Codex CLI Hydra
     * redirect URI allow-list"), so a port of isx's own would be refused there.
     */
    private static final int[] CALLBACK_PORTS = {1455, 1457};
    private static final String CALLBACK_PATH = "/auth/callback";

    private final HttpClient client;
    private final String issuer;

    /** Against OpenAI's auth server, trusting the host's system CA store. */
    public ChatGptSignIn() {
        this(DEFAULT_ISSUER);
    }

    /** Against {@code issuer}, for tests: production passes {@link #DEFAULT_ISSUER}. */
    public ChatGptSignIn(String issuer) {
        this.client = SystemTrust.httpClient().connectTimeout(REQUEST_TIMEOUT).build();
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    /** A one-time code the person enters at {@link DeviceCode#verificationUrl()}. */
    public record DeviceCode(String deviceAuthId, String userCode, long intervalSeconds,
                             String verificationUrl) {}

    /** Where the person enters the code (the page Codex's login prints). */
    public String verificationUrl() {
        return issuer + "/codex/device";
    }

    /**
     * Asks OpenAI's auth server for a device code. The server decides how often isx may poll;
     * a client that ignored it would be refused.
     */
    public DeviceCode requestDeviceCode() throws IOException, InterruptedException {
        var body = JqObject.builder(1)
                .put("client_id", JqString.of(ChatGptAccounts.CODEX_CLIENT_ID))
                .build().toJsonString();
        var answer = postJson(issuer + "/api/accounts/deviceauth/usercode", body);
        var deviceAuthId = answer.pathText("device_auth_id", "");
        var userCode = answer.pathText("user_code", "");
        if (deviceAuthId.isEmpty() || userCode.isEmpty()) {
            throw new IOException("OpenAI's auth server answered without a device code");
        }
        var interval = answer.path("interval");
        // The server sends the interval as a string (codex-rs deserialises it so); a number is read too
        long seconds = interval instanceof JqString s && !s.stringValue().isBlank()
                ? Long.parseLong(s.stringValue().strip()) : interval.asLong(5);
        return new DeviceCode(deviceAuthId, userCode, Math.max(1, seconds), verificationUrl());
    }

    /**
     * Waits for the person to enter the code, then exchanges the authorization code for the
     * session's tokens. {@code note} hears each poll's outcome, so the caller can say the flow
     * is waiting rather than hang silently.
     *
     * @return the tokens to hand {@link ChatGptSessionStore#create}
     */
    public ChatGptSessionStore.Tokens awaitAuthorization(DeviceCode code, Consumer<String> note)
            throws IOException, InterruptedException {
        var deadline = System.nanoTime() + MAX_WAIT.toNanos();
        String authorizationCode = null;
        String codeVerifier = null;
        while (authorizationCode == null) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("the device code expired: OpenAI's auth server kept refusing it for "
                        + MAX_WAIT.toMinutes() + " minutes");
            }
            var body = JqObject.builder(2)
                    .put("device_auth_id", JqString.of(code.deviceAuthId()))
                    .put("user_code", JqString.of(code.userCode()))
                    .build().toJsonString();
            var status = postForStatus(issuer + "/api/accounts/deviceauth/token", body);
            switch (status) {
                // 403 and 404 are the server saying "not yet" (codex-rs poll_for_token)
                case 403, 404 -> {
                    if (note != null) note.accept("waiting for the sign-in to finish");
                    Thread.sleep(Duration.ofSeconds(code.intervalSeconds())
                            .toMillis());
                }
                case 200 -> {
                    var answer = postJson(issuer + "/api/accounts/deviceauth/token", body);
                    authorizationCode = answer.pathText("authorization_code", "");
                    codeVerifier = answer.pathText("code_verifier", "");
                    if (authorizationCode.isEmpty() || codeVerifier == null || codeVerifier.isEmpty()) {
                        throw new IOException("OpenAI's auth server granted the code without one to exchange");
                    }
                }
                default -> throw new IOException("OpenAI's auth server answered " + status
                        + " while waiting for the sign-in");
            }
        }
        return exchange(authorizationCode, codeVerifier, issuer + "/deviceauth/callback");
    }

    /**
     * Signs in by the browser flow Codex's own login uses by default (codex-rs
     * {@code server.rs}): isx listens on loopback, {@code openUrl} opens the authorize URL in
     * the person's browser, and the sign-in happens there -- SSO included, since the
     * organisation's IdP is reached by the browser, not by isx. OpenAI answers to the loopback
     * redirect URI with an authorization code, which isx exchanges as the device flow does.
     *
     * <p>Blocks until the person finished, canceled, or {@link #BROWSER_WAIT} passed.
     *
     * @param openUrl handed the authorize URL to open; it must open on this host, for the
     *                redirect answers to the loopback listener
     */
    public ChatGptSessionStore.Tokens signInWithBrowser(Consumer<String> openUrl)
            throws IOException, InterruptedException {
        var random = new SecureRandom();
        var verifierBytes = new byte[64];
        random.nextBytes(verifierBytes);
        var codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(codeVerifier));
        var stateBytes = new byte[32];
        random.nextBytes(stateBytes);
        var state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

        var listener = callbackListener();
        var port = listener.getLocalPort();
        var redirectUri = "http://127.0.0.1:" + port + CALLBACK_PATH;
        openUrl.accept(issuer + "/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + url(ChatGptAccounts.CODEX_CLIENT_ID)
                + "&redirect_uri=" + url(redirectUri)
                + "&code_challenge=" + url(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + url(state)
                + "&scope=" + url("openid profile email offline_access api.connectors.read api.connectors.invoke")
                // What Codex's own authorize URL carries (codex-rs build_authorize_url)
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true"
                + "&originator=codex_cli_rs");
        try (listener) {
            var code = awaitCallback(listener, state);
            return exchange(code, codeVerifier, redirectUri);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            // Every JVM must provide SHA-256 (JCA spec, "SHA-2 family")
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The loopback listener, on the first of Codex's callback ports that is free. */
    private ServerSocket callbackListener() throws IOException {
        IOException last = null;
        for (var port : CALLBACK_PORTS) {
            try {
                return new ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress());
            } catch (IOException busy) {
                last = busy;
            }
        }
        throw new IOException("neither of Codex's sign-in callback ports (1455, 1457) is free on this host;"
                + " isx cannot listen for the browser's answer" + (last == null ? "" : ": " + last.getMessage()));
    }

    /**
     * One connection at a time, until the browser lands on the callback: anything else the
     * browser sends on the way is answered and forgotten. A mismatched state is refused without
     * ending the wait, so a stale tab cannot finish a sign-in it did not start.
     */
    private String awaitCallback(ServerSocket listener, String state) throws IOException, InterruptedException {
        var wait = testBrowserWait == null ? BROWSER_WAIT : testBrowserWait;
        var deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            // Never 0, which would block past the deadline forever
            listener.setSoTimeout(Math.max(1, (int) Duration.ofNanos(deadline - System.nanoTime()).toMillis()));
            try (var socket = listener.accept()) {
                socket.setSoTimeout(10_000);
                var request = readRequest(socket);
                var params = parseQuery(request.getOrDefault("query", ""));
                if (!CALLBACK_PATH.equals(request.get("path"))) {
                    answer(socket, 404, "Not isx's to answer");
                    continue;
                }
                var error = params.get("error");
                if (error != null && !error.isEmpty()) {
                    answer(socket, 400, "The sign-in was refused: " + error
                            + (params.get("error_description") == null ? "" : " (" + params.get("error_description") + ")"));
                    throw new IOException("OpenAI's auth server refused the sign-in: " + error);
                }
                var code = params.get("code");
                if (code == null || code.isEmpty()) {
                    answer(socket, 400, "The sign-in carried no authorization code");
                    throw new IOException("the sign-in's callback carried no authorization code");
                }
                if (!state.equals(params.get("state"))) {
                    answer(socket, 400, "State mismatch");
                    continue;
                }
                answer(socket, 200, "<html><body><h2>Signed in to isx.</h2>You can close this tab.</body></html>");
                return code;
            } catch (java.net.SocketTimeoutException deadlineReached) {
                break;
            }
        }
        throw new IOException("the sign-in did not finish in " + wait.toMinutes() + " minutes");
    }

    /** A request's method, path, query and headers, read as text lines. */
    private static Map<String, String> readRequest(Socket socket) throws IOException {
        var out = new HashMap<String, String>();
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        var line = reader.readLine();
        if (line == null) throw new IOException("the browser closed the connection without a request");
        var parts = line.split(" ");
        if (parts.length >= 2) {
            var target = parts[1];
            var q = target.indexOf('?');
            out.put("path", q < 0 ? target : target.substring(0, q));
            out.put("query", q < 0 ? "" : target.substring(q + 1));
        }
        for (var header = reader.readLine(); header != null && !header.isEmpty(); header = reader.readLine()) {
            var colon = header.indexOf(':');
            if (colon > 0) out.putIfAbsent("header:" + header.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                    header.substring(colon + 1).trim());
        }
        return out;
    }

    private static void answer(Socket socket, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static Map<String, String> parseQuery(String query) {
        var params = new HashMap<String, String>();
        for (var pair : query.split("&")) {
            var eq = pair.indexOf('=');
            if (eq <= 0) continue;
            params.putIfAbsent(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /**
     * Exchanges the authorization code for tokens, form-encoded as Codex's OAuth client sends it
     * (codex-rs {@code oauth/client.rs} {@code exchange_code}), with the flow's redirect URI.
     */
    private ChatGptSessionStore.Tokens exchange(String authorizationCode, String codeVerifier, String redirectUri)
            throws IOException, InterruptedException {
        var form = "grant_type=authorization_code"
                + "&client_id=" + url(ChatGptAccounts.CODEX_CLIENT_ID)
                + "&code=" + url(authorizationCode)
                + "&redirect_uri=" + url(redirectUri)
                + "&code_verifier=" + url(codeVerifier);
        var request = HttpRequest.newBuilder(URI.create(issuer + "/oauth/token"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI's auth server refused the sign-in's code exchange: HTTP "
                    + response.statusCode());
        }
        var answer = parse(response.body(), "the token exchange's answer");
        var idToken = answer.pathText("id_token", "");
        var accessToken = answer.pathText("access_token", "");
        var refreshToken = answer.pathText("refresh_token", "");
        if (idToken.isEmpty() || accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new IOException("OpenAI's auth server answered the code exchange without tokens");
        }
        return new ChatGptSessionStore.Tokens(idToken, accessToken, refreshToken, accountIdOf(idToken));
    }

    /**
     * The workspace the id token's auth claims name, which is what Codex records as the
     * session's account id (codex-rs {@code persist_tokens_async}). Metadata, not a credential.
     */
    static String accountIdOf(String idToken) {
        var parts = idToken.split("\\.", -1);
        if (parts.length != 3) return "";
        try {
            var claims = Jjq.parseForeign(Base64.getUrlDecoder().decode(parts[1]), "the id token's claims");
            return claims.path("https://api.openai.com/auth").pathText("chatgpt_account_id", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private JqValue postJson(String url, String json) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI's auth server answered HTTP " + response.statusCode());
        }
        return parse(response.body(), "the auth server's answer");
    }

    private int postForStatus(String url, String json) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static JqValue parse(String body, String what) {
        return Jjq.parseForeign(body.getBytes(StandardCharsets.UTF_8), what);
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
