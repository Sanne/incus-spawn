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
    /** The per-connection read timeout a test runs with; production uses 10 seconds. */
    static volatile Duration testReadTimeout;
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
            // The grant is handed out once: an OAuth device grant answers the poll that earned it
            // and refuses a second ask for the same code, so this poll's own 200 body is the only
            // place the authorization code and verifier may be read from
            var response = post(issuer + "/api/accounts/deviceauth/token", body);
            switch (response.statusCode()) {
                // 403 and 404 are the server saying "not yet" (codex-rs poll_for_token)
                case 403, 404 -> {
                    if (note != null) note.accept("waiting for the sign-in to finish");
                    Thread.sleep(Duration.ofSeconds(code.intervalSeconds())
                            .toMillis());
                }
                case 200 -> {
                    var answer = parse(response.body(), "the auth server's answer");
                    authorizationCode = answer.pathText("authorization_code", "");
                    codeVerifier = answer.pathText("code_verifier", "");
                    if (authorizationCode.isEmpty() || codeVerifier == null || codeVerifier.isEmpty()) {
                        throw new IOException("OpenAI's auth server granted the code without one to exchange");
                    }
                }
                default -> throw new IOException("OpenAI's auth server answered " + response.statusCode()
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
        try (listener) { // closed on every path, the URL handler throwing included
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
     * browser sends on the way is answered and forgotten. A browser preconnect to the callback
     * port goes idle or closes without a request, so neither ends the wait. A mismatched state
     * is refused without ending the wait either, so a stale tab or a local page cannot finish,
     * or fail, a sign-in it did not start.
     */
    private String awaitCallback(ServerSocket listener, String state) throws IOException, InterruptedException {
        var wait = testBrowserWait == null ? BROWSER_WAIT : testBrowserWait;
        var deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            // Never 0, which would block past the deadline forever
            listener.setSoTimeout(Math.max(1, (int) Duration.ofNanos(deadline - System.nanoTime()).toMillis()));
            Socket socket;
            try {
                socket = listener.accept();
            } catch (java.net.SocketTimeoutException deadlineReached) {
                break;
            }
            try (socket) {
                var readLimit = testReadTimeout == null ? Duration.ofSeconds(10) : testReadTimeout;
                var untilDeadline = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
                // Never past the sign-in's own deadline
                var request = readRequest(socket, readLimit.compareTo(untilDeadline) < 0 ? readLimit : untilDeadline);
                if (request == null) continue;
                if (!CALLBACK_PATH.equals(request.get("path"))) {
                    answer(socket, 404, "Not isx's to answer");
                    continue;
                }
                Map<String, String> params;
                try {
                    params = parseQuery(request.getOrDefault("query", ""));
                } catch (IllegalArgumentException badEscape) {
                    // A query that does not decode carries no state of this sign-in, so like a
                    // mismatched one it is refused without ending the wait
                    answer(socket, 400, "The callback's query does not decode");
                    continue;
                }
                // This sign-in's state first: only its own callback may end it, with a code or
                // an error, whatever else the query carries
                if (!state.equals(params.get("state"))) {
                    answer(socket, 400, "State mismatch");
                    continue;
                }
                var error = params.get("error");
                if (error != null && !error.isEmpty()) {
                    var description = params.get("error_description");
                    answer(socket, 400, "The sign-in was refused: " + escapeHtml(error)
                            + (description == null ? "" : " (" + escapeHtml(description) + ")"));
                    throw new IOException("OpenAI's auth server refused the sign-in: " + error);
                }
                var code = params.get("code");
                if (code == null || code.isEmpty()) {
                    answer(socket, 400, "The sign-in carried no authorization code");
                    throw new IOException("the sign-in's callback carried no authorization code");
                }
                answer(socket, 200, "<html><body><h2>Signed in to isx.</h2>You can close this tab.</body></html>");
                return code;
            }
        }
        throw new IOException("the sign-in did not finish in " + wait.toMinutes() + " minutes");
    }

    /** The most of a request that is read, its line first: a browser's callback line is a fraction of it. */
    static final int MAX_REQUEST_BYTES = 16 * 1024;

    /**
     * A request's method, path, query and headers, read as text lines, or null when the
     * connection went idle or closed without sending one: a browser preconnect does both. The
     * whole request must arrive within {@code limit}, and its request line within
     * {@link #MAX_REQUEST_BYTES}, else it is null too: the listener serves one connection at a
     * time, so a client sending a byte now and then must not hold it until the sign-in's own
     * deadline. Headers past the cap are not read, since nothing here reads them.
     */
    private static Map<String, String> readRequest(Socket socket, Duration limit) throws IOException {
        var deadline = System.nanoTime() + limit.toNanos();
        var in = new java.io.BufferedInputStream(socket.getInputStream());
        var lines = new java.util.ArrayList<String>();
        var line = new java.io.ByteArrayOutputStream();
        var total = 0;
        try {
            while (true) {
                var left = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                if (left <= 0) return null;
                socket.setSoTimeout((int) Math.max(1, left));
                var b = in.read();
                if (b < 0) {
                    // Closed after its last line, as readLine took it: what it sent still counts
                    if (line.size() > 0) {
                        var text = line.toString(StandardCharsets.ISO_8859_1);
                        lines.add(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text);
                    }
                    break;
                }
                if (++total > MAX_REQUEST_BYTES) {
                    // Only the request line is read from it: headers past the cap, such as a
                    // large Cookie for 127.0.0.1, are dropped rather than the callback
                    if (lines.isEmpty()) return null;
                    break;
                }
                if (b == '\n') {
                    var text = line.toString(StandardCharsets.ISO_8859_1);
                    if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
                    line.reset();
                    if (text.isEmpty()) break;
                    lines.add(text);
                } else {
                    line.write(b);
                }
            }
        } catch (java.net.SocketTimeoutException idleConnection) {
            return null;
        } catch (IOException closedWithoutARequest) {
            return null;
        }
        if (lines.isEmpty()) return null;
        var out = new HashMap<String, String>();
        for (var header : lines.subList(1, lines.size())) {
            var colon = header.indexOf(':');
            if (colon > 0) out.putIfAbsent("header:" + header.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                    header.substring(colon + 1).trim());
        }
        var parts = lines.get(0).split(" ");
        if (parts.length >= 2) {
            var target = parts[1];
            var q = target.indexOf('?');
            out.put("path", q < 0 ? target : target.substring(0, q));
            out.put("query", q < 0 ? "" : target.substring(q + 1));
        }
        return out;
    }

    /**
     * Answers the request, or does nothing when its client has gone: what a client that hung up
     * is told changes nothing here, so a stray one cannot end the wait by leaving, and the real
     * callback's code is still exchanged when its tab closed first.
     */
    private static void answer(Socket socket, int status, String body) {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var head = "HTTP/1.1 " + status + (status == 200 ? " OK" : status == 404 ? " Not Found" : " Bad Request") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try {
            socket.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        } catch (IOException clientGone) {
            // Nobody is left to read it
        }
    }

    /** {@code text} for an HTML body: no character of it can open a tag, an attribute or an entity. */
    private static String escapeHtml(String text) {
        var out = new StringBuilder();
        text.codePoints().forEach(c -> {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.appendCodePoint(c);
            }
        });
        return out.toString();
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
        var response = post(url, json);
        if (response.statusCode() != 200) {
            throw new IOException("OpenAI's auth server answered HTTP " + response.statusCode());
        }
        return parse(response.body(), "the auth server's answer");
    }

    private HttpResponse<String> post(String url, String json) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JqValue parse(String body, String what) {
        return Jjq.parseForeign(body.getBytes(StandardCharsets.UTF_8), what);
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
