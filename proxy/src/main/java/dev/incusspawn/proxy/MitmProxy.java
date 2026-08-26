package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.SocketAddress;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * TLS-terminating MITM proxy for transparent credential injection.
 * <p>
 * Containers resolve intercepted domains (api.anthropic.com, github.com, etc.)
 * to the Incus bridge gateway IP via bridge-level dnsmasq. This proxy listens on port 443
 * on the gateway IP, terminates TLS using per-domain certificates signed by a
 * custom CA, injects authentication headers, and forwards to the real upstream.
 * <p>
 * Credentials never enter containers in any form. Tools (curl, git, gh, claude)
 * work completely unmodified inside containers.
 * <p>
 * Internally uses Vert.x for non-blocking I/O, connection pooling, and
 * zero-copy file serving.
 */
public class MitmProxy {

    private static final int BUFFER_SIZE = 64 * 1024;

    private static final Set<String> ANTHROPIC_DOMAINS = ProxyConfig.ANTHROPIC_DOMAINS;
    private static final Set<String> REGISTRY_DOMAINS = ProxyConfig.REGISTRY_DOMAINS;
    private static final Set<String> MAVEN_DOMAINS = ProxyConfig.MAVEN_DOMAINS;
    private static final Set<String> GRADLE_DOMAINS = ProxyConfig.GRADLE_DOMAINS;
    private static final Set<String> NPM_DOMAINS = ProxyConfig.NPM_DOMAINS;

    // OCI blob URL pattern: /v2/<name>/blobs/sha256:<64-hex-chars>
    // Group 1 = image name (e.g. "library/postgres"), group 2 = digest
    private static final Pattern BLOB_DIGEST_PATTERN = Pattern.compile(
            "/v2/(.+)/blobs/(sha256:[a-f0-9]{64})");

    // Gradle distribution archive: /distributions/gradle-<version>-<variant>.zip
    // Group 1 = filename (e.g. "gradle-9.2.1-bin.zip")
    private static final Pattern GRADLE_DIST_PATTERN = Pattern.compile(
            "/distributions/(gradle-[\\w.\\-]+-(?:bin|all)\\.zip)");

    // npm tarball: /<scope>/<name>/-/<name>-<version>.tgz or /<name>/-/<name>-<version>.tgz
    // Group 1 = full path after leading slash (used as cache key)
    static final Pattern NPM_TARBALL_PATTERN = Pattern.compile(
            "/((?:@[^/]+/)?[^/]+/-/[^/]+-\\d[^/]*\\.tgz)");

    // npm packument: /<name> or /@scope/name (no further path segments)
    // Group 1 = package name
    static final Pattern NPM_PACKUMENT_PATTERN = Pattern.compile(
            "/((?:@[^/]+/)?[^/]+)");

    private static Path registryCacheDir() {
        return Environment.registryCacheDir();
    }

    private static Path mavenCacheDir() {
        return Environment.mavenCacheDir();
    }

    private static Path gradleCacheDir() {
        return Environment.gradleCacheDir();
    }

    private static Path npmCacheDir() {
        return Environment.npmCacheDir();
    }

    private static Path m2Repository() {
        return Environment.m2Repository();
    }

    // URL path prefix preceding Maven coordinates on each domain
    private static final java.util.Map<String, String> MAVEN_PATH_PREFIX = java.util.Map.of(
            "repo.maven.apache.org", "/maven2/",
            "repo1.maven.org", "/maven2/",
            "plugins.gradle.org", "/m2/"
    );

    private final String bindAddress;
    private final int mitmPort;
    private final int healthPort;
    private final ProxyCredentials credentials;

    private static final ObjectMapper JSON = new ObjectMapper();

    // Top-level fields accepted by Vertex AI rawPredict. Anything else (beta features
    // like context_management, etc.) is stripped to avoid "Extra inputs" rejections.
    private static final Set<String> VERTEX_ALLOWED_FIELDS = Set.of(
            "anthropic_version", "messages", "system", "max_tokens",
            "temperature", "top_p", "top_k", "stop_sequences", "stream",
            "metadata", "tools", "tool_choice", "thinking", "output_config"
    );

    // Track which stripped fields have already been logged (avoid spam)
    private final Set<String> loggedStrippedFields = ConcurrentHashMap.newKeySet();

    // Cached GCP access token for Vertex AI (tokens last ~60 min, refresh at ~50 min)
    private String cachedVertexToken;
    private long vertexTokenExpiryMs;

    private final Vertx vertx;
    private HttpServer mitmServer;
    private HttpServer healthHttpServer;
    private HttpClient upstreamClient;
    private HttpClient wsUpstreamClient;
    private CountDownLatch stopLatch;

    private ApiTrafficLog debugLog;
    // CA fingerprint computed at startup for the health endpoint
    private String caFingerprint = "";
    private volatile boolean dnsConfigured;
    private static final long DNS_CACHE_TTL_MS = 60_000;
    private record DnsEntry(String ip, long expiresAt, Future<String> inflight) {
        static DnsEntry resolving(Future<String> f) { return new DnsEntry(null, 0, f); }
        static DnsEntry resolved(String ip) {
            return new DnsEntry(ip, System.currentTimeMillis() + DNS_CACHE_TTL_MS, null);
        }
        boolean isValid() { return ip != null && System.currentTimeMillis() < expiresAt; }
        boolean isResolving() { return inflight != null; }
    }
    private final ConcurrentHashMap<String, DnsEntry> dns = new ConcurrentHashMap<>();

    private final String healthBindAddress;

    private Map<String, ResolvedToolProxy> toolProxyByExactDomain = Map.of();
    private List<Map.Entry<String, ResolvedToolProxy>> toolProxyWildcardSuffixes = List.of();
    private Set<String> allInterceptedDomains = ProxyConfig.builtinInterceptedDomains();
    private List<String> wildcardSuffixes = List.of();
    private String toolProxyFingerprint = "";

    // Overridable for tests: upstream WebSocket connections default to port 443 + TLS
    int upstreamWsPort = 443;
    boolean upstreamWsSsl = true;
    boolean upstreamTrustAll = false;

    void overrideDns(String host, String ip) {
        dns.put(host, DnsEntry.resolved(ip));
    }

    public MitmProxy(Vertx vertx, String bindAddress, int mitmPort, int healthPort,
                     String healthBindAddress, ProxyCredentials credentials) {
        this.vertx = vertx;
        this.bindAddress = bindAddress;
        this.healthBindAddress = healthBindAddress;
        this.mitmPort = mitmPort;
        this.healthPort = healthPort;
        this.credentials = credentials;
    }

    public void setDnsConfigured(boolean configured) {
        this.dnsConfigured = configured;
    }

    private String vertexHost() {
        return ProxyConfig.vertexHost(credentials.vertexRegion());
    }

    public void setDebugLog(ApiTrafficLog debugLog) {
        this.debugLog = debugLog;
    }

    /**
     * Set resolved tool proxies for credential injection.
     * Call before start() so DNS and cert generation include tool proxy domains.
     * Entries with {@code type: anthropic} are included in the fingerprint (so credential
     * changes trigger a proxy restart) but excluded from the domain maps (Anthropic auth
     * is handled by hardcoded logic, not the generic tool proxy injection path).
     */
    public void setToolProxies(List<ResolvedToolProxy> proxies) {
        this.toolProxyFingerprint = ToolProxyResolver.fingerprint(proxies);

        var exact = new java.util.LinkedHashMap<String, ResolvedToolProxy>();
        var wildcards = new ArrayList<Map.Entry<String, ResolvedToolProxy>>();
        var extraDomains = new HashSet<String>();
        var suffixes = new ArrayList<String>();

        for (var tp : proxies) {
            if (tp.auth() != null && "anthropic".equals(tp.auth().getType())) continue;

            var domain = tp.domain();
            if (domain.startsWith("*.")) {
                var suffix = domain.substring(1); // ".example.com"
                wildcards.add(Map.entry(suffix, tp));
                if (!suffixes.contains(suffix)) suffixes.add(suffix);
                extraDomains.add(domain.substring(2)); // base domain for DNS
            } else {
                exact.put(domain, tp);
                extraDomains.add(domain);
            }
        }

        this.toolProxyByExactDomain = Map.copyOf(exact);
        this.toolProxyWildcardSuffixes = List.copyOf(wildcards);
        this.wildcardSuffixes = List.copyOf(suffixes);
        this.allInterceptedDomains = ProxyConfig.interceptedDomains(extraDomains);
    }

    /** @deprecated Use {@link ToolProxyResolver#resolve(dev.incusspawn.config.SpawnConfig)} directly. */
    public static List<ResolvedToolProxy> resolveToolProxies(dev.incusspawn.config.SpawnConfig config) {
        return ToolProxyResolver.resolve(config);
    }

    ResolvedToolProxy findToolProxy(String domain) {
        var exact = toolProxyByExactDomain.get(domain);
        if (exact != null) return exact;
        for (var entry : toolProxyWildcardSuffixes) {
            if (domain.endsWith(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    public Set<String> allInterceptedDomains() {
        return allInterceptedDomains;
    }

    private boolean isInterceptedDomain(String domain) {
        return ProxyConfig.isInterceptedDomain(domain,
                toolProxyByExactDomain.keySet(), wildcardSuffixes);
    }

    /** Create a MitmProxy using credentials from SpawnConfig and the Incus bridge gateway IP. */
    public static MitmProxy fromConfig(Vertx vertx, IncusClient incus) {
        var config = dev.incusspawn.config.SpawnConfig.load();
        var gatewayIp = ProxyConfig.resolveGatewayIp(incus);
        return new MitmProxy(
                vertx,
                gatewayIp,
                ProxyConfig.DEFAULT_MITM_PORT,
                ProxyConfig.DEFAULT_HEALTH_PORT,
                gatewayIp,
                ProxyCredentials.fromConfig(config));
    }

    // --- Lifecycle ---

    /**
     * Start the MITM proxy and health server. Blocks until {@link #stop()} is called.
     *
     * @param onReady called after both servers are listening, before blocking on the stop latch.
     *                Use this to enable DNS overrides so they are never visible without a healthy proxy.
     */
    public void start(Runnable onReady) throws Exception {
        stopLatch = new CountDownLatch(1);

        var ca = CertificateAuthority.loadOrCreate();
        caFingerprint = ca.caFingerprint();

        // Build JKS keystore with per-domain certs (alias = domain name for SNI).
        // Also generate wildcard certs (*.domain) so subdomains resolved via
        // dnsmasq address= overrides get a valid cert (e.g. cdn01.quay.io).
        var allDomains = allInterceptedDomains.stream()
                .sorted()
                .flatMap(d -> java.util.stream.Stream.of(d, "*." + d))
                .toList();
        // Reuse persisted leaf certs across restarts so their notBefore stays
        // stable (minted while clocks were in sync); only mint on miss/expiry/CA
        // rotation. See CertStore for why per-start re-minting broke validation
        // on hosts whose container clock lags (e.g. macOS VM after resume).
        var certStore = new CertStore(ca);
        var certs = allDomains.parallelStream()
                .map(domain -> java.util.Map.entry(domain, certStore.get(domain)))
                .toList();
        var keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        for (var entry : certs) {
            keyStore.setKeyEntry(
                    entry.getKey(),
                    entry.getValue().key(),
                    "changeit".toCharArray(),
                    new X509Certificate[]{entry.getValue().cert(), ca.caCert()});
        }
        var baos = new ByteArrayOutputStream();
        keyStore.store(baos, "changeit".toCharArray());
        var jksBuffer = Buffer.buffer(baos.toByteArray());

        // MITM TLS server with SNI
        var serverOptions = new HttpServerOptions()
                .setHost(bindAddress)
                .setPort(mitmPort)
                .setSsl(true)
                .setSni(true)
                .setKeyCertOptions(new JksOptions().setValue(jksBuffer).setPassword("changeit"))
                .setIdleTimeout(120)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setAlpnVersions(List.of(HttpVersion.HTTP_1_1))
                .setMaxWebSocketFrameSize(1024 * 1024)
                .setMaxWebSocketMessageSize(16 * 1024 * 1024);

        // Upstream HTTPS client with connection pooling.
        // GraalVM native images don't embed the build-time trust store reliably
        // when built via container, so point Vert.x at the system PEM CA bundle.
        var clientOptions = new HttpClientOptions()
                .setSsl(true)
                .setVerifyHost(!upstreamTrustAll)
                .setTrustAll(upstreamTrustAll)
                .setMaxPoolSize(20)
                .setKeepAliveTimeout(30)
                .setConnectTimeout(30_000)
                .setReadIdleTimeout(300);
        var systemCaBundle = findSystemCaBundle();
        if (systemCaBundle != null) {
            clientOptions.setTrustOptions(new io.vertx.core.net.PemTrustOptions().addCertPath(systemCaBundle));
        }
        upstreamClient = vertx.createHttpClient(clientOptions);

        // Separate client for WebSocket: no read-idle timeout (WebSocket
        // connections are long-lived and may be idle between prompts) and
        // no connection pooling (each WebSocket is its own connection).
        var wsClientOptions = new HttpClientOptions()
                .setSsl(true)
                .setVerifyHost(!upstreamTrustAll)
                .setTrustAll(upstreamTrustAll)
                .setConnectTimeout(30_000)
                .setReadIdleTimeout(0)
                .setMaxWebSocketFrameSize(1024 * 1024)
                .setMaxWebSocketMessageSize(16 * 1024 * 1024);
        if (systemCaBundle != null) {
            wsClientOptions.setTrustOptions(new io.vertx.core.net.PemTrustOptions().addCertPath(systemCaBundle));
        }
        wsUpstreamClient = vertx.createHttpClient(wsClientOptions);

        int maxRetries = 30;
        for (int attempt = 1; ; attempt++) {
            mitmServer = vertx.createHttpServer(serverOptions);
            mitmServer.exceptionHandler(err -> {
                if (isBenignConnectionError(err)) {
                    // Clients (containers) drop connections abruptly all the time —
                    // process exit, timeouts, TLS aborts. A full stack trace per RST
                    // is pure noise, so log concisely without one.
                    ProxyLog.info("MITM connection closed: " + err.getMessage());
                    return;
                }
                System.err.println("MITM server error: " + err.getMessage());
                err.printStackTrace(System.err);
            });
            mitmServer.requestHandler(this::routeRequest);
            mitmServer.webSocketHandler(this::routeWebSocket);
            try {
                mitmServer.listen()
                        .toCompletionStage().toCompletableFuture().get();
                break;
            } catch (Exception e) {
                if (attempt >= maxRetries || !isBindException(e)) throw e;
                if (!ProxyHealthCheck.isHealthy(healthBindAddress)) throw e;
                ProxyLog.warn("Port " + mitmPort + " in use, previous proxy still running (" + attempt + "/" + maxRetries + ")");
                Thread.sleep(200);
            }
        }

        // Health check HTTP server (plain, no TLS)
        healthHttpServer = vertx.createHttpServer()
                .requestHandler(this::handleHealthCheck);
        healthHttpServer.listen(healthPort, healthBindAddress)
                .toCompletionStage().toCompletableFuture().get();

        if (onReady != null) {
            onReady.run();
        }

        ProxyLog.info("Listening on " + bindAddress + ":" + mitmPort);
        ProxyLog.info("Health endpoint on " + healthBindAddress + ":" + healthPort);
        System.out.println("MITM proxy listening on " + bindAddress + ":" + mitmPort);
        System.out.println("Health endpoint on " + healthBindAddress + ":" + healthPort + "/health");
        System.out.println("Intercepted domains: " + allInterceptedDomains);
        System.out.println("Registry cache: " + registryCacheDir() +
                " (domains: " + REGISTRY_DOMAINS + ")");
        System.out.println("Maven cache: " + mavenCacheDir() +
                " (domains: " + MAVEN_DOMAINS + ")");
        System.out.println("Maven .m2 fallback: " +
                (Files.isDirectory(m2Repository()) ? m2Repository() : "not available"));
        System.out.println("Gradle cache: " + gradleCacheDir() +
                " (domains: " + GRADLE_DOMAINS + ")");
        System.out.println("npm cache: " + npmCacheDir() +
                " (domains: " + NPM_DOMAINS + ")");
        if (credentials.useVertex()) {
            System.out.println("Vertex AI mode: translating api.anthropic.com requests" +
                    " to " + vertexHost() +
                    " (region: " + credentials.vertexRegion() + ", project: " + credentials.vertexProjectId() + ")");
        } else if (!credentials.oauthToken().isBlank()) {
            System.out.println("OAuth mode: injecting Bearer token for api.anthropic.com");
        }
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        stopLatch.await();
    }

    public void stop() {
        ProxyLog.info("Stopping proxy");
        try {
            try {
                if (mitmServer != null) mitmServer.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            try {
                if (upstreamClient != null) upstreamClient.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            try {
                if (wsUpstreamClient != null) wsUpstreamClient.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            try {
                if (healthHttpServer != null) healthHttpServer.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        } finally {
            // Guarantee stopLatch is always counted down, even if an unexpected exception occurs
            if (stopLatch != null) stopLatch.countDown();
        }
    }

    private static boolean isBindException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.net.BindException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Whether a server-level exception is an expected connection teardown rather
     * than a real fault. Containers close connections abruptly (RST, half-close,
     * TLS abort) on process exit or timeout, which Netty surfaces here as
     * {@link java.net.SocketException} ("Connection reset", "Broken pipe") or a
     * closed-channel error. These carry no useful stack trace.
     */
    private static boolean isBenignConnectionError(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof java.nio.channels.ClosedChannelException) return true;
            var msg = cause.getMessage();
            if (msg != null) {
                var m = msg.toLowerCase(java.util.Locale.ROOT);
                if (cause instanceof java.io.IOException) {
                    if (m.contains("connection reset") || m.contains("broken pipe")) {
                        return true;
                    }
                }
                // Vert.x wraps transport errors in VertxException (not IOException)
                // when a WebSocket operation hits a closed connection.
                if (m.contains("connection was closed")
                        || m.contains("connection or outbound has closed")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    // --- Request routing ---

    private void routeRequest(HttpServerRequest clientReq) {
        try {
            var domain = extractDomain(clientReq);
            if (domain == null) {
                sendError(clientReq.response(), 502, "Unknown domain");
                return;
            }

            if (REGISTRY_DOMAINS.contains(domain)) {
                handleRegistryRequest(clientReq, domain);
            } else if (MAVEN_DOMAINS.contains(domain)) {
                handleMavenRequest(clientReq, domain);
            } else if (GRADLE_DOMAINS.contains(domain)) {
                handleGradleRequest(clientReq, domain);
            } else if (NPM_DOMAINS.contains(domain)) {
                handleNpmRequest(clientReq, domain);
            } else if (isInterceptedDomain(domain)) {
                handleApiRequest(clientReq, domain);
            } else {
                // Subdomain of an intercepted domain (e.g. cdn01.quay.io) reached us
                // via dnsmasq wildcard — relay transparently without auth injection.
                relayRequest(clientReq, domain);
            }
        } catch (Exception e) {
            var domain = extractDomain(clientReq);
            var path = clientReq.path();
            System.err.println("Unexpected error handling request to " + domain + path + ": " + e.getMessage());
            e.printStackTrace(System.err);
            sendError(clientReq.response(), 502, "Internal proxy error");
        }
    }

    private String extractDomain(HttpServerRequest req) {
        var host = req.getHeader("Host");
        if (host != null) {
            var colon = host.indexOf(':');
            return colon > 0 ? host.substring(0, colon) : host;
        }
        var sni = req.connection().indicatedServerName();
        return (sni != null && !sni.isEmpty()) ? sni : null;
    }

    // --- WebSocket passthrough ---

    private void routeWebSocket(ServerWebSocket clientWs) {
        var host = clientWs.headers().get("Host");
        if (host == null) {
            clientWs.reject(400);
            return;
        }
        var colon = host.indexOf(':');
        var domain = colon > 0 ? host.substring(0, colon) : host;
        handleWebSocketUpgrade(clientWs, domain);
    }

    private void handleWebSocketUpgrade(ServerWebSocket clientWs, String domain) {
        var wsOptions = new WebSocketConnectOptions()
                .setHost(domain)
                .setPort(upstreamWsPort)
                .setSsl(upstreamWsSsl)
                .setURI(clientWs.uri());

        for (var entry : clientWs.headers()) {
            var key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!key.startsWith("sec-websocket") && !key.equals("connection")
                    && !key.equals("upgrade") && !key.equals("host")) {
                wsOptions.addHeader(entry.getKey(), entry.getValue());
            }
        }

        var protocols = clientWs.headers().get("Sec-WebSocket-Protocol");
        if (protocols != null && !protocols.isBlank()) {
            for (var p : protocols.split(",")) {
                wsOptions.addSubProtocol(p.trim());
            }
        }

        injectWebSocketAuth(wsOptions, domain);

        // Pause the client socket so frames arriving before the upstream
        // connection is ready are buffered, not dropped.
        clientWs.pause();

        // Let Vert.x resolve DNS via its built-in resolver (configured on the
        // Vertx instance).  Unlike HTTP requests, WebSocket ignores setServer(),
        // so manual resolveHost() + setHost(ip) would break TLS SNI.
        // Uses wsUpstreamClient which has no read-idle timeout (WebSocket
        // sessions can be idle between prompts for minutes).
        wsUpstreamClient.webSocket(wsOptions).onSuccess(upstreamWs -> {
            if (clientWs.isClosed()) {
                upstreamWs.close();
                return;
            }

            if (ProxyLog.isDebugEnabled()) {
                ProxyLog.debug("WebSocket connected: " + domain + clientWs.uri());
            }

            // Periodic pings on both legs to prevent idle timeouts.
            // Upstream pings prevent NAT/firewall timeouts during long AI
            // thinking phases; client pings prevent the MITM server's own
            // idle timeout (120s) from killing the connection when no data
            // flows on the client leg (e.g. while the model is reasoning).
            var upstreamPingTimer = vertx.setPeriodic(30_000, id ->  {
                if (!upstreamWs.isClosed()) {
                    upstreamWs.writePing(Buffer.buffer("keepalive"));
                }
            });
            var clientPingTimer = vertx.setPeriodic(30_000, id -> {
                if (!clientWs.isClosed()) {
                    clientWs.writePing(Buffer.buffer("keepalive"));
                }
            });

            clientWs.frameHandler(frame -> {
                if ((frame.isText() || frame.isBinary() || frame.isContinuation())
                        && !upstreamWs.isClosed()) {
                    upstreamWs.writeFrame(frame);
                }
            });
            upstreamWs.frameHandler(frame -> {
                if ((frame.isText() || frame.isBinary() || frame.isContinuation())
                        && !clientWs.isClosed()) {
                    clientWs.writeFrame(frame);
                }
            });

            clientWs.closeHandler(v -> {
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!upstreamWs.isClosed()) {
                    var code = clientWs.closeStatusCode();
                    if (code != null) {
                        upstreamWs.close(code, clientWs.closeReason() != null ? clientWs.closeReason() : "");
                    } else {
                        upstreamWs.close();
                    }
                }
            });
            upstreamWs.closeHandler(v -> {
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!clientWs.isClosed()) {
                    var code = upstreamWs.closeStatusCode();
                    if (code != null) {
                        clientWs.close(code, upstreamWs.closeReason() != null ? upstreamWs.closeReason() : "");
                    } else {
                        clientWs.close();
                    }
                }
            });

            clientWs.exceptionHandler(err -> {
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!isBenignConnectionError(err)) {
                    System.err.println("WebSocket client error (" + domain + "): " + err.getMessage());
                }
                if (!upstreamWs.isClosed()) upstreamWs.close();
            });
            upstreamWs.exceptionHandler(err -> {
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!isBenignConnectionError(err)) {
                    System.err.println("WebSocket upstream error (" + domain + "): " + err.getMessage());
                }
                if (!clientWs.isClosed()) clientWs.close();
            });

            clientWs.resume();
        }).onFailure(err -> {
            System.err.println("WebSocket upstream connect failed (" + domain + "): " + err.getMessage());
            if (!clientWs.isClosed()) clientWs.close((short) 1011, "Upstream connection failed");
        });
    }

    private void injectWebSocketAuth(WebSocketConnectOptions options, String domain) {
        if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (!credentials.oauthToken().isBlank()) {
                options.putHeader("Authorization", "Bearer " + credentials.oauthToken());
                options.removeHeader("x-api-key");
            } else if (!credentials.anthropicApiKey().isBlank()) {
                options.putHeader("x-api-key", credentials.anthropicApiKey());
            }
        } else {
            var tp = findToolProxy(domain);
            if (tp != null) {
                var headerName = tp.headerName();
                var headerValue = tp.computeHeaderValue();
                if (headerName != null && headerValue != null) {
                    options.putHeader(headerName, headerValue);
                }
            }
        }
    }

    // --- API requests (Anthropic, GitHub) ---

    private void handleApiRequest(HttpServerRequest clientReq, String domain) {
        clientReq.body().onSuccess(bodyBuffer -> {
            try {
                handleApiRequestWithBody(clientReq, domain, bodyBuffer);
            } catch (Exception e) {
                System.err.println("API request error: " + e.getMessage());
                e.printStackTrace(System.err);
                sendError(clientReq.response(), 502, "Proxy error");
            }
        }).onFailure(err -> {
            System.err.println("Failed to read API request body: " + err.getMessage());
            sendError(clientReq.response(), 502, "Proxy error");
        });
    }

    private void handleApiRequestWithBody(HttpServerRequest clientReq, String domain,
                                           Buffer bodyBuffer) throws Exception {
        String upstreamHost;
        byte[] bodyBytes = bodyBuffer.getBytes();
        boolean isVertexRequest = false;
        boolean bodyRewritten = false;
        String originalDump = null;
        byte[] originalBody = null;

        if (debugLog != null) {
            originalDump = dumpRequest(clientReq);
            originalBody = bodyBytes;
        }

        var path = clientReq.path();
        var uri = clientReq.uri();
        var requestOptions = new RequestOptions()
                .setMethod(clientReq.method())
                .setPort(443);

        if (credentials.useVertex() && ANTHROPIC_DOMAINS.contains(domain) && path != null) {
            if (path.startsWith("/v1/projects/")) {
                // Already Vertex-formatted (container running in Vertex mode with
                // ANTHROPIC_VERTEX_BASE_URL pointing here): forward to real Vertex.
                // The Vertex SDK uses @date suffixes (e.g. claude-haiku-4-5@20251001)
                // which the global endpoint rejects — strip them.
                upstreamHost = vertexHost();
                isVertexRequest = true;
                uri = path.replaceFirst("@\\d{8}(?=:)", "");
            } else if (path.startsWith("/v1/messages")) {
                // Standard API format: translate to Vertex AI rawPredict
                upstreamHost = vertexHost();
                isVertexRequest = true;
                var translated = translateToVertex(path, bodyBytes, upstreamHost);
                uri = translated.path;
                bodyBytes = translated.body;
                bodyRewritten = true;
            } else {
                // Non-messages endpoints (settings, bootstrap, feature flags, etc.)
                upstreamHost = domain;
            }
        } else {
            upstreamHost = domain;
        }
        requestOptions.setHost(upstreamHost).setURI(uri);

        sendApiRequest(clientReq, requestOptions, upstreamHost, domain,
                bodyBytes, isVertexRequest, bodyRewritten, false,
                originalDump, originalBody);
    }

    private Future<HttpClientRequest> requestWithAsyncDns(RequestOptions options) {
        return resolveHost(options.getHost()).compose(ip -> {
            options.setServer(SocketAddress.inetSocketAddress(options.getPort(), ip));
            return upstreamClient.request(options);
        });
    }

    // JVM resolver is blocking (Quarkus use-async-dns=false); resolve on a worker thread.
    // Single map with compute() for atomic state transitions — no window between
    // removing an inflight entry and inserting the cached result.
    private Future<String> resolveHost(String host) {
        var entry = dns.compute(host, (h, existing) -> {
            if (existing != null && (existing.isValid() || existing.isResolving()))
                return existing;
            var future = vertx.<String>executeBlocking(() ->
                    InetAddress.getByName(h).getHostAddress(), false
            ).andThen(ar -> {
                if (ar.succeeded()) {
                    dns.put(h, DnsEntry.resolved(ar.result()));
                } else {
                    dns.remove(h);
                }
            });
            return DnsEntry.resolving(future);
        });
        return entry.isValid()
                ? Future.succeededFuture(entry.ip())
                : entry.inflight();
    }

    private void sendApiRequest(HttpServerRequest clientReq, RequestOptions requestOptions,
                                String upstreamHost, String domain,
                                byte[] bodyBytes, boolean isVertexRequest,
                                boolean bodyRewritten, boolean isRetry,
                                String originalDump, byte[] originalBody) {
        requestWithAsyncDns(requestOptions).onSuccess(upReq -> {
            copyRequestHeaders(clientReq, upReq, domain);
            if (!injectHeaders(upReq, domain, upstreamHost, isVertexRequest)) {
                sendError(clientReq.response(), 502, "Failed to obtain upstream credentials");
                return;
            }
            upReq.putHeader("Content-Length", String.valueOf(bodyBytes.length));

            upReq.send(Buffer.buffer(bodyBytes)).onSuccess(upResp -> {
                if (!isRetry && isVertexRequest && upResp.statusCode() == 401) {
                    System.err.println("Vertex 401: invalidating cached token and retrying");
                    invalidateVertexToken();
                    sendApiRequest(clientReq, requestOptions, upstreamHost, domain,
                            bodyBytes, isVertexRequest, bodyRewritten, true,
                            originalDump, originalBody);
                    return;
                }

                if (upResp.statusCode() == 401 && !credentials.oauthToken().isBlank()
                        && ANTHROPIC_DOMAINS.contains(domain)) {
                    System.err.println("Claude OAuth token rejected (HTTP 401). " +
                            "The token may have expired — run 'isx init' to refresh.");
                }

                relayApiResponse(clientReq, upResp, upstreamHost, domain,
                        bodyBytes, bodyRewritten, originalDump, originalBody);
            }).onFailure(err -> {
                System.err.println("Upstream send error (" + domain + "): " + err.getMessage());
                sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(err -> {
            System.err.println("Upstream connect error (" + domain + "): " + err.getMessage());
            sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    private void relayApiResponse(HttpServerRequest clientReq, HttpClientResponse upResp,
                                   String upstreamHost, String domain,
                                   byte[] sentBody, boolean bodyRewritten,
                                   String originalDump, byte[] originalBody) {
        var clientResp = clientReq.response();
        clientResp.setStatusCode(upResp.statusCode());
        clientResp.setStatusMessage(upResp.statusMessage());
        copyResponseHeaders(upResp, clientResp);

        if (debugLog != null) {
            upResp.body().onSuccess(respBody -> {
                var respBytes = respBody.getBytes();
                var responseDump = dumpResponse(upResp);
                debugLog.logExchange(
                        originalDump, originalBody,
                        null, bodyRewritten ? sentBody : null,
                        responseDump, respBytes.length > 0 ? respBytes : null);
                clientResp.putHeader("Content-Length", String.valueOf(respBytes.length));
                clientResp.end(Buffer.buffer(respBytes));
            }).onFailure(err -> {
                System.err.println("Failed to capture debug response: " + err.getMessage());
                sendError(clientResp, 502, "Debug capture error");
            });
        } else {
            pipeResponse(upResp, clientResp);
        }
    }

    // --- Registry blob caching ---

    /**
     * Handle a request to a container registry domain.
     * GET requests for blobs with a SHA256 digest are served from cache or
     * fetched, cached, and served. Everything else is relayed transparently.
     */
    private void handleRegistryRequest(HttpServerRequest clientReq, String domain) {
        var path = clientReq.path();

        if (clientReq.method() == HttpMethod.GET && path != null) {
            var matcher = BLOB_DIGEST_PATTERN.matcher(path);
            if (matcher.matches()) {
                var imageName = matcher.group(1);
                var digest = matcher.group(2);
                var imageRef = domain + "/" + imageName;
                var cacheFile = registryCacheDir().resolve(digest.replace(":", "-"));

                cachedFileSize(cacheFile).onSuccess(size -> {
                    if (size >= 0) {
                        System.out.println("Registry cache hit: " + imageRef +
                                " " + digest.substring(0, 19) +
                                "... (" + formatSize(size) + ")");
                        serveCachedFile(clientReq.response(), cacheFile, digest);
                    } else {
                        fetchCacheAndServe(clientReq, domain, digest, cacheFile, imageRef);
                    }
                }).onFailure(err -> {
                    System.err.println("Cache check error: " + err.getMessage());
                    relayRequest(clientReq, domain);
                });
                return;
            }
        }

        // Non-cacheable (auth tokens, manifests, HEAD, tag lookups) — relay
        relayRequest(clientReq, domain);
    }

    private Future<Long> cachedFileSize(Path cacheFile) {
        return vertx.executeBlocking(() -> Files.isRegularFile(cacheFile) ? Files.size(cacheFile) : -1L);
    }

    /**
     * Serve a cached file with a synthetic HTTP 200 response.
     * If {@code digest} is non-null, includes a Docker-Content-Digest header (OCI blobs).
     */
    private void serveCachedFile(HttpServerResponse clientResp, Path cacheFile, String digest) {
        clientResp.setStatusCode(200);
        clientResp.putHeader("Content-Type", "application/octet-stream");
        if (digest != null) {
            clientResp.putHeader("Docker-Content-Digest", digest);
        }
        clientResp.sendFile(cacheFile.toString()).onFailure(err -> {
            System.err.println("Failed to serve cached file: " + err.getMessage());
            if (!clientResp.ended() && !clientResp.closed()) {
                sendError(clientResp, 500, "Cache read error");
            }
        });
    }

    /**
     * Fetch a file from upstream, tee-stream it to the client and a temp file,
     * and atomically move into the cache. When {@code digest} is non-null,
     * the cached file is verified against the SHA256 digest (OCI blobs);
     * when null the file is cached unconditionally (immutable Maven artifacts).
     */
    private void fetchCacheAndServe(HttpServerRequest clientReq, String domain,
                                    String digest, Path cacheFile, String ref) {
        var options = new RequestOptions()
                .setMethod(clientReq.method())
                .setHost(domain)
                .setPort(443)
                .setURI(clientReq.uri());

        requestWithAsyncDns(options).onSuccess(upReq -> {
            copyRequestHeaders(clientReq, upReq, domain);
            upReq.putHeader("Connection", "close");
            // Don't let upstream gzip the response — we cache raw bytes
            // and serve them directly via sendFile on cache hits.
            upReq.headers().remove("Accept-Encoding");

            sendWithBody(clientReq, upReq).onSuccess(upResp -> {
                var statusCode = upResp.statusCode();

                if (statusCode == 200) {
                    teeStreamToCache(clientReq.response(), upResp, digest, cacheFile, ref);
                } else if (statusCode >= 300 && statusCode < 400) {
                    // Follow redirect manually — Vert.x setFollowRedirects carries
                    // the original Host header, which breaks cross-domain redirects
                    // (e.g. plugins.gradle.org -> plugins-artifacts.gradle.org).
                    fetchFromRedirect(clientReq, upResp, digest, cacheFile, ref);
                } else {
                    var clientResp = clientReq.response();
                    clientResp.setStatusCode(statusCode);
                    clientResp.setStatusMessage(upResp.statusMessage());
                    copyResponseHeaders(upResp, clientResp);
                    pipeResponse(upResp, clientResp);
                }
            }).onFailure(err -> {
                System.err.println("Upstream error fetching " + ref + ": " + err.getMessage());
                sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(err -> {
            System.err.println("Connect error fetching " + ref + ": " + err.getMessage());
            sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    private static final int MAX_REDIRECTS = 10;

    /**
     * Follow a 3xx redirect from upstream, making a new request to the Location URL.
     * Uses the redirect target's host for both the connection and Host header.
     * Handles multi-hop cross-domain redirects manually because Vert.x's built-in
     * setFollowRedirects carries the original Host header across domains.
     */
    private void fetchFromRedirect(HttpServerRequest clientReq, HttpClientResponse upResp,
                                   String digest, Path cacheFile, String ref) {
        followRedirect(clientReq, upResp, digest, cacheFile, ref, 0);
    }

    private void followRedirect(HttpServerRequest clientReq, HttpClientResponse upResp,
                                String digest, Path cacheFile, String ref, int depth) {
        if (depth >= MAX_REDIRECTS) {
            System.err.println("Too many redirects for " + ref);
            sendError(clientReq.response(), 502, "Too many redirects");
            return;
        }

        var location = upResp.getHeader("Location");
        if (location == null) {
            System.err.println("Redirect with no Location header for " + ref);
            sendError(clientReq.response(), 502, "Redirect with no Location");
            return;
        }

        URI redirectUri;
        try {
            redirectUri = new URI(location);
        } catch (Exception e) {
            System.err.println("Invalid redirect Location for " + ref + ": " + location);
            sendError(clientReq.response(), 502, "Invalid redirect Location");
            return;
        }

        var redirectHost = redirectUri.getHost();
        var redirectPort = redirectUri.getPort() > 0 ? redirectUri.getPort() : 443;
        var redirectPath = redirectUri.getRawPath();
        if (redirectUri.getRawQuery() != null) {
            redirectPath += "?" + redirectUri.getRawQuery();
        }

        var redirectOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(redirectHost)
                .setPort(redirectPort)
                .setURI(redirectPath);

        requestWithAsyncDns(redirectOptions).onSuccess(redReq -> {
            redReq.putHeader("Host", redirectHost);
            redReq.putHeader("Connection", "close");

            redReq.send().onSuccess(redResp -> {
                var statusCode = redResp.statusCode();
                if (statusCode == 200) {
                    teeStreamToCache(clientReq.response(), redResp, digest, cacheFile, ref);
                } else if (statusCode >= 300 && statusCode < 400) {
                    followRedirect(clientReq, redResp, digest, cacheFile, ref, depth + 1);
                } else {
                    System.err.println("Redirect target " + redirectHost + " returned " +
                            statusCode + " for " + ref + " (Location: " + location + ")");
                    var clientResp = clientReq.response();
                    clientResp.setStatusCode(statusCode);
                    clientResp.setStatusMessage(redResp.statusMessage());
                    copyResponseHeaders(redResp, clientResp);
                    pipeResponse(redResp, clientResp);
                }
            }).onFailure(err -> {
                System.err.println("Redirect fetch error for " + ref + ": " + err.getMessage());
                sendError(clientReq.response(), 502, "Redirect fetch failed");
            });
        }).onFailure(err -> {
            System.err.println("Redirect connect error for " + ref + ": " + err.getMessage());
            sendError(clientReq.response(), 502, "Redirect connection failed");
        });
    }

    private void teeStreamToCache(HttpServerResponse clientResp, HttpClientResponse upResp,
                                  String digest, Path cacheFile, String ref) {
        upResp.pause();

        clientResp.setStatusCode(200);
        clientResp.putHeader("Content-Type", "application/octet-stream");
        var clHeader = upResp.getHeader("Content-Length");
        if (clHeader != null) {
            clientResp.putHeader("Content-Length", clHeader);
        }
        if (digest != null) {
            clientResp.putHeader("Docker-Content-Digest", digest);
        }
        if (clHeader == null) {
            clientResp.setChunked(true);
        }

        var contentEncoding = upResp.getHeader("Content-Encoding");
        var isGzip = contentEncoding != null && contentEncoding.toLowerCase().contains("gzip");

        vertx.executeBlocking(() -> {
            Files.createDirectories(cacheFile.getParent());
            return Files.createTempFile(cacheFile.getParent(), "dl-", ".tmp");
        }).onSuccess(tempFile -> {
            vertx.fileSystem().open(tempFile.toString(),
                    new io.vertx.core.file.OpenOptions().setCreate(true).setWrite(true)
            ).onSuccess(asyncFile -> {
                upResp.handler(chunk -> {
                    clientResp.write(chunk);
                    asyncFile.write(chunk);
                    if (clientResp.writeQueueFull()) {
                        upResp.pause();
                        clientResp.drainHandler(v -> {
                            if (!asyncFile.writeQueueFull()) upResp.resume();
                        });
                    }
                    if (asyncFile.writeQueueFull()) {
                        upResp.pause();
                        asyncFile.drainHandler(v -> {
                            if (!clientResp.writeQueueFull()) upResp.resume();
                        });
                    }
                });

                upResp.endHandler(v -> {
                    asyncFile.close().onComplete(closeResult -> {
                        clientResp.end();
                        vertx.executeBlocking(() -> {
                            finalizeCacheFile(tempFile, cacheFile, digest, ref, isGzip);
                            return null;
                        });
                    });
                });

                upResp.exceptionHandler(err -> {
                    asyncFile.close();
                    clientResp.end();
                    vertx.executeBlocking(() -> {
                        Files.deleteIfExists(tempFile);
                        return null;
                    });
                    System.err.println("Stream error caching " + ref + ": " + err.getMessage());
                });

                asyncFile.exceptionHandler(err -> {
                    System.err.println("Disk write error caching " + ref + ": " + err.getMessage());
                    asyncFile.close();
                    vertx.executeBlocking(() -> {
                        Files.deleteIfExists(tempFile);
                        return null;
                    });
                });

                upResp.resume();
            }).onFailure(err -> {
                System.err.println("Failed to open temp file for caching: " + err.getMessage());
                upResp.resume();
                pipeResponse(upResp, clientResp);
            });
        }).onFailure(err -> {
            System.err.println("Failed to create temp file: " + err.getMessage());
            upResp.resume();
            pipeResponse(upResp, clientResp);
        });
    }

    private void finalizeCacheFile(Path tempFile, Path cacheFile, String digest,
                                   String ref, boolean isGzip) {
        try {
            if (isGzip) {
                var decompFile = Files.createTempFile(cacheFile.getParent(), "gz-", ".tmp");
                try (var gzIn = new GZIPInputStream(Files.newInputStream(tempFile));
                     var decompOut = Files.newOutputStream(decompFile)) {
                    gzIn.transferTo(decompOut);
                }
                Files.move(decompFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            if (digest != null && !verifyDigest(tempFile, digest)) {
                System.err.println("Cache: checksum mismatch for " +
                        ref + " " + digest + ", not caching");
                Files.deleteIfExists(tempFile);
            } else {
                Files.move(tempFile, cacheFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Cached: " + ref +
                        (digest != null ? " " + digest.substring(0, 19) + "..." : "") +
                        " (" + formatSize(Files.size(cacheFile)) + ")");
            }
        } catch (Exception e) {
            System.err.println("Failed to finalize cache for " + ref + ": " + e.getMessage());
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    // --- Gradle distribution caching ---

    /**
     * Handle a request to services.gradle.org.
     * GET requests for distribution archives (/distributions/gradle-X.Y.Z-bin.zip,
     * gradle-X.Y.Z-all.zip) are served from cache or fetched, cached, and served.
     * All other paths are relayed transparently since they may be mutable.
     */
    private void handleGradleRequest(HttpServerRequest clientReq, String domain) {
        var path = clientReq.path();

        if (clientReq.method() == HttpMethod.GET && path != null) {
            var matcher = GRADLE_DIST_PATTERN.matcher(path);
            if (matcher.matches()) {
                var filename = matcher.group(1);
                var cacheFile = gradleCacheDir().resolve(filename);
                var ref = domain + path;

                cachedFileSize(cacheFile).onSuccess(size -> {
                    if (size >= 0) {
                        System.out.println("Gradle cache hit: " + filename +
                                " (" + formatSize(size) + ")");
                        serveCachedFile(clientReq.response(), cacheFile, null);
                    } else {
                        fetchGradleDistAndServe(clientReq, domain, cacheFile, ref);
                    }
                }).onFailure(err -> {
                    System.err.println("Gradle cache check error: " + err.getMessage());
                    relayRequest(clientReq, domain);
                });
                return;
            }
        }

        relayRequest(clientReq, domain);
    }

    /**
     * Fetch the SHA256 checksum sidecar, then fetch and cache the Gradle distribution
     * with digest verification via the existing fetchCacheAndServe() infrastructure.
     */
    private void fetchGradleDistAndServe(HttpServerRequest clientReq, String domain,
                                          Path cacheFile, String ref) {
        var sha256Path = clientReq.path() + ".sha256";

        vertx.executeBlocking(() -> fetchChecksumFromUpstream(domain, sha256Path, 64))
            .onSuccess(sha256Hex -> {
                String digest = sha256Hex != null ? "sha256:" + sha256Hex : null;
                if (digest != null) {
                    System.out.println("Gradle: fetching " + ref + " (sha256:" +
                            sha256Hex.substring(0, 12) + "...)");
                } else {
                    System.out.println("Gradle: fetching " + ref + " (no sha256 sidecar)");
                }
                fetchCacheAndServe(clientReq, domain, digest, cacheFile, ref);
            })
            .onFailure(err -> {
                System.err.println("Gradle SHA256 fetch error for " + ref + ": " + err.getMessage());
                fetchCacheAndServe(clientReq, domain, null, cacheFile, ref);
            });
    }

    // --- npm tarball caching ---

    record NpmPackageRef(String packageName, String version) {}

    /**
     * Parse a matched npm tarball path into a package name and version.
     * Input format: {@code @scope/name/-/name-version.tgz} or {@code name/-/name-version.tgz}.
     */
    static NpmPackageRef parseNpmTarballPath(String tarballPath) {
        var sepIdx = tarballPath.indexOf("/-/");
        if (sepIdx < 0) return null;
        var packageName = tarballPath.substring(0, sepIdx);
        var filename = tarballPath.substring(sepIdx + 3);
        var basename = packageName.contains("/")
                ? packageName.substring(packageName.lastIndexOf('/') + 1)
                : packageName;
        if (filename.length() <= basename.length() + 5) return null;
        var version = filename.substring(basename.length() + 1, filename.length() - 4);
        return new NpmPackageRef(packageName, version);
    }

    /**
     * Handle a request to registry.npmjs.org.
     * <p>
     * Three request types:
     * <ul>
     *   <li><b>Tarball</b> ({@code GET /<pkg>/-/<name>-<ver>.tgz}): served from cache when
     *       the package's ETag hasn't changed since the tarball was verified. When the ETag
     *       has changed (a new version was published or a version was republished), the
     *       tarball's shasum is re-verified against per-version metadata. Cache misses
     *       are verified on store.</li>
     *   <li><b>Packument</b> ({@code GET /<pkg>}): relayed fresh to upstream. The response's
     *       ETag header is stored so tarball cache hits can be served without re-verification
     *       when the packument is unchanged.</li>
     *   <li><b>Everything else</b> (search, audit, publish, per-version metadata):
     *       relayed transparently.</li>
     * </ul>
     */
    private void handleNpmRequest(HttpServerRequest clientReq, String domain) {
        var path = clientReq.path();
        if (path == null) {
            relayRequest(clientReq, domain);
            return;
        }

        var cacheDir = npmCacheDir();

        if (clientReq.method() == HttpMethod.GET) {
            var matcher = NPM_TARBALL_PATTERN.matcher(path);
            if (matcher.matches()) {
                var tarballPath = matcher.group(1);
                var pkgRef = parseNpmTarballPath(tarballPath);
                if (pkgRef != null) {
                    var cacheFile = cacheDir.resolve(tarballPath).normalize();
                    if (!cacheFile.startsWith(cacheDir)) {
                        relayRequest(clientReq, domain);
                        return;
                    }
                    var ref = domain + path;
                    fetchNpmTarballAndServe(clientReq, domain, cacheFile, ref,
                            pkgRef.packageName(), pkgRef.version());
                    return;
                }
            }
        }

        var packumentMatcher = NPM_PACKUMENT_PATTERN.matcher(path);
        if (packumentMatcher.matches()) {
            relayNpmPackument(clientReq, domain, packumentMatcher.group(1));
            return;
        }

        relayRequest(clientReq, domain);
    }

    /**
     * Relay a packument request to upstream and store the response ETag.
     * The ETag is used by tarball cache hits to skip re-verification when
     * the packument hasn't changed.
     */
    private void relayNpmPackument(HttpServerRequest clientReq, String domain,
                                    String packageName) {
        relayRequest(clientReq, domain, upResp -> {
            var etag = upResp.getHeader("ETag");
            if (etag != null && !etag.isBlank()) {
                vertx.executeBlocking(() -> {
                    storePackageEtag(packageName, etag);
                    return null;
                });
            }
        });
    }

    static void storePackageEtag(String packageName, String etag) {
        try {
            var cacheDir = npmCacheDir();
            var etagFile = cacheDir.resolve(packageName).resolve(".etag").normalize();
            if (!etagFile.startsWith(cacheDir)) return;
            Files.createDirectories(etagFile.getParent());
            Files.writeString(etagFile, etag);
        } catch (IOException e) {
            System.err.println("npm: failed to store ETag for " + packageName +
                    ": " + e.getMessage());
        }
    }

    static String readFileOrNull(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }

    record NpmVerifyResult(boolean cacheHit, long size, String digest) {}

    /**
     * Serve an npm tarball from cache or fetch fresh.
     * <p>
     * <b>Cache hit + ETag unchanged</b>: serve directly (zero cost — no upstream,
     * no hash computation). The package's ETag hasn't changed since this tarball was
     * last verified, so the shasum is guaranteed unchanged.
     * <p>
     * <b>Cache hit + ETag changed/missing</b>: fetch per-version metadata, compare
     * the upstream shasum with the stored sidecar. Same shasum → update the tarball's
     * ETag marker and serve. Different shasum → evict and re-fetch.
     * <p>
     * <b>Cache miss</b>: fetch per-version shasum, download with digest verification,
     * write shasum + ETag sidecar files alongside the cached tarball.
     */
    private void fetchNpmTarballAndServe(HttpServerRequest clientReq, String domain,
                                          Path cacheFile, String ref,
                                          String packageName, String version) {
        vertx.<NpmVerifyResult>executeBlocking(() -> {
            var cacheDir = npmCacheDir();
            var etagFile = cacheDir.resolve(packageName).resolve(".etag").normalize();
            var packageEtag = etagFile.startsWith(cacheDir)
                    ? readFileOrNull(etagFile) : null;
            return checkNpmTarballCache(cacheFile, packageEtag, ref,
                    () -> fetchNpmShasum(domain, packageName, version));
        }).onSuccess(result -> {
            if (result == null) {
                relayRequest(clientReq, domain);
            } else if (result.cacheHit()) {
                System.out.println("npm cache hit: " + ref +
                        " (" + formatSize(result.size()) + ")");
                serveCachedFile(clientReq.response(), cacheFile, null);
            } else {
                fetchCacheAndServe(clientReq, domain, result.digest(), cacheFile, ref);
            }
        }).onFailure(err -> {
            System.err.println("npm integrity check error for " + ref +
                    ": " + err.getMessage());
            relayRequest(clientReq, domain);
        });
    }

    static NpmVerifyResult checkNpmTarballCache(Path cacheFile, String packageEtag,
                                                 String ref,
                                                 java.util.function.Supplier<String> shasumSupplier)
            throws IOException {
        var etagPath = Path.of(cacheFile + ".etag");
        var shasumPath = Path.of(cacheFile + ".shasum");

        if (Files.isRegularFile(cacheFile)) {
            var tarballEtag = readFileOrNull(etagPath);

            if (packageEtag != null && !packageEtag.isEmpty()
                    && packageEtag.equals(tarballEtag)) {
                return new NpmVerifyResult(true, Files.size(cacheFile), null);
            }

            var shasum = shasumSupplier.get();
            if (shasum == null) {
                return new NpmVerifyResult(true, Files.size(cacheFile), null);
            }

            var storedShasum = readFileOrNull(shasumPath);
            if (shasum.equals(storedShasum)) {
                if (packageEtag != null) {
                    Files.writeString(etagPath, packageEtag);
                }
                return new NpmVerifyResult(true, Files.size(cacheFile), null);
            }

            System.out.println("npm cache stale: " + ref +
                    " (shasum changed), evicting");
            Files.deleteIfExists(cacheFile);
            Files.deleteIfExists(shasumPath);
            Files.deleteIfExists(etagPath);
            var digest = "sha1:" + shasum;
            writeNpmSidecarFiles(cacheFile, shasum, packageEtag);
            return new NpmVerifyResult(false, 0, digest);
        }

        var shasum = shasumSupplier.get();
        if (shasum == null) return null;
        var digest = "sha1:" + shasum;
        writeNpmSidecarFiles(cacheFile, shasum, packageEtag);
        return new NpmVerifyResult(false, 0, digest);
    }

    static void writeNpmSidecarFiles(Path cacheFile, String shasum,
                                              String packageEtag) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(Path.of(cacheFile + ".shasum"), shasum);
            if (packageEtag != null) {
                Files.writeString(Path.of(cacheFile + ".etag"), packageEtag);
            }
        } catch (IOException e) {
            System.err.println("npm: failed to write sidecar files: " + e.getMessage());
        }
    }

    /**
     * Fetch the SHA-1 checksum for an npm package version from the registry's
     * per-version metadata endpoint ({@code /<package>/<version>}).
     * Returns the 40-char hex shasum, or null on any failure.
     */
    static String fetchNpmShasum(String domain, String packageName, String version) {
        var encodedName = packageName.replace("/", "%2F");
        var body = fetchUpstreamBody(domain, "/" + encodedName + "/" + version,
                "Accept: application/json");
        if (body == null) return null;
        try {
            var dist = JSON.readTree(body).path("dist").path("shasum");
            if (dist.isTextual()) {
                var hex = dist.asText().trim().toLowerCase();
                if (hex.matches("[a-f0-9]{40}")) return hex;
            }
        } catch (Exception e) { /* JSON parse error */ }
        return null;
    }

    // --- Maven/Gradle artifact caching ---

    /**
     * Handle a request to a Maven/Gradle repository.
     * GET requests for cacheable artifact paths are served from cache or
     * fetched, cached, and served. Metadata and SNAPSHOT paths are relayed
     * transparently since they can change between builds.
     */
    private void handleMavenRequest(HttpServerRequest clientReq, String domain) {
        var path = clientReq.path();

        if (clientReq.method() == HttpMethod.GET && path != null && isMavenCacheable(path)) {
            var cacheFile = mavenCacheDir().resolve(domain).resolve(path.substring(1));

            cachedFileSize(cacheFile).onSuccess(size -> {
                if (size >= 0) {
                    System.out.println("Maven cache hit: " + domain + path +
                            " (" + formatSize(size) + ")");
                    serveCachedFile(clientReq.response(), cacheFile, null);
                } else {
                    tryM2FallbackThenFetch(clientReq, domain, path, cacheFile);
                }
            }).onFailure(err -> {
                System.err.println("Maven cache check error: " + err.getMessage());
                relayRequest(clientReq, domain);
            });
            return;
        }

        relayRequest(clientReq, domain);
    }

    // Try host .m2 fallback for artifact files (not checksums/signatures),
    // then fall back to upstream fetch if .m2 doesn't have a SHA1-verified copy.
    private void tryM2FallbackThenFetch(HttpServerRequest clientReq, String domain,
                                        String path, Path cacheFile) {
        if (!isMavenArtifactFile(path)) {
            fetchCacheAndServe(clientReq, domain, null, cacheFile, domain + path);
            return;
        }

        vertx.executeBlocking(() -> {
            var m2File = resolveM2Path(domain, path);
            if (m2File == null || !Files.isRegularFile(m2File)) return null;

            var upstreamSha1 = fetchChecksumFromUpstream(domain, path + ".sha1", 40);
            if (upstreamSha1 == null) return null;

            var localSha1 = computeSha1(m2File);
            if (!upstreamSha1.equals(localSha1)) {
                System.out.println("Maven .m2 SHA1 mismatch: " + domain + path +
                        " (local=" + localSha1.substring(0, 8) + "..." +
                        " upstream=" + upstreamSha1.substring(0, 8) + "...)");
                return null;
            }

            Files.createDirectories(cacheFile.getParent());
            try {
                Files.createLink(cacheFile, m2File);
            } catch (IOException e) {
                // Cross-filesystem or unsupported — fall back to copy
                Files.copy(m2File, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("Maven .m2 hit: " + domain + path +
                    " (" + formatSize(Files.size(cacheFile)) + ", SHA1 verified)");
            return cacheFile;
        }).onSuccess(result -> {
            if (result != null) {
                serveCachedFile(clientReq.response(), cacheFile, null);
            } else {
                fetchCacheAndServe(clientReq, domain, null, cacheFile, domain + path);
            }
        }).onFailure(err -> {
            System.err.println("Maven .m2 fallback error: " + err.getMessage());
            fetchCacheAndServe(clientReq, domain, null, cacheFile, domain + path);
        });
    }

    // --- Generic relay (non-cacheable) ---

    /** Relay a non-cacheable request transparently to upstream. */
    private void relayRequest(HttpServerRequest clientReq, String domain) {
        relayRequest(clientReq, domain, null);
    }

    /**
     * Relay a request to upstream with an optional response callback.
     * When {@code responseCallback} is non-null it fires after the upstream
     * response headers arrive but before the body is piped to the client.
     */
    private void relayRequest(HttpServerRequest clientReq, String domain,
                               java.util.function.Consumer<HttpClientResponse> responseCallback) {
        var options = new RequestOptions()
                .setMethod(clientReq.method())
                .setHost(domain)
                .setPort(443)
                .setURI(clientReq.uri());

        requestWithAsyncDns(options).onSuccess(upReq -> {
            copyRequestHeaders(clientReq, upReq, domain);

            sendWithBody(clientReq, upReq).onSuccess(upResp -> {
                if (responseCallback != null) {
                    responseCallback.accept(upResp);
                }
                var clientResp = clientReq.response();
                clientResp.setStatusCode(upResp.statusCode());
                clientResp.setStatusMessage(upResp.statusMessage());
                copyResponseHeaders(upResp, clientResp);
                pipeResponse(upResp, clientResp);
            }).onFailure(err -> {
                System.err.println("Relay upstream error (" + domain + "): " + err.getMessage());
                sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(err -> {
            System.err.println("Relay connect error (" + domain + "): " + err.getMessage());
            sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    // --- Vertex AI translation ---

    private record VertexTranslation(String path, byte[] body) {}

    /**
     * Translate a standard Anthropic API request into a Vertex AI rawPredict request.
     * <p>
     * Differences between the two APIs:
     * <ul>
     *   <li>URL: /v1/messages → /v1/projects/{pid}/locations/{region}/publishers/anthropic/models/{model}:rawPredict</li>
     *   <li>Auth: x-api-key header → Authorization: Bearer (GCP token)</li>
     *   <li>Body: only {@link #VERTEX_ALLOWED_FIELDS} are kept; everything else is stripped</li>
     *   <li>Body: "model" replaced with "anthropic_version": "vertex-2023-10-16"</li>
     *   <li>Body: "scope" removed from nested cache_control objects (beta feature)</li>
     *   <li>Header: anthropic-beta removed (Vertex features are enabled via anthropic_version)</li>
     *   <li>Streaming: :rawPredict → :streamRawPredict when stream=true</li>
     * </ul>
     */
    private VertexTranslation translateToVertex(String originalPath, byte[] bodyBytes,
                                                 String upstreamHost) {
        try {
            var tree = bodyBytes.length > 0 ? JSON.readTree(bodyBytes) : null;

            // Non-JSON or non-object body (e.g. GET /v1/models): just forward as-is
            if (tree == null || !tree.isObject()) {
                return new VertexTranslation(originalPath, bodyBytes);
            }

            var root = (ObjectNode) tree;

            // Extract model (goes into URL, not body). The global Vertex endpoint
            // only accepts short aliases, so strip date suffixes like -20251001.
            var model = root.has("model") ? root.get("model").asText() : "claude-sonnet-4-6";
            model = model.replaceFirst("-\\d{8}$", "");
            var streaming = root.has("stream") && root.get("stream").asBoolean();

            // Strip all top-level fields Vertex doesn't support (beta features, etc.)
            root.remove("model");
            var fieldNames = new java.util.ArrayList<String>();
            root.fieldNames().forEachRemaining(fieldNames::add);
            var stripped = new java.util.ArrayList<String>();
            for (var field : fieldNames) {
                if (!VERTEX_ALLOWED_FIELDS.contains(field)) {
                    root.remove(field);
                    stripped.add(field);
                }
            }
            if (!stripped.isEmpty() && loggedStrippedFields.addAll(stripped)) {
                System.err.println("Vertex translation: stripped unsupported fields: " + stripped);
            }

            root.put("anthropic_version", "vertex-2023-10-16");
            // Strip "scope" from cache_control objects deep in the tree (beta feature)
            stripCacheControlScope(root);

            var rewrittenBytes = JSON.writeValueAsBytes(root);

            var endpoint = streaming ? ":streamRawPredict" : ":rawPredict";
            var vertexPath = "/v1/projects/" + credentials.vertexProjectId() + "/locations/" + credentials.vertexRegion() +
                    "/publishers/anthropic/models/" + model + endpoint;

            return new VertexTranslation(vertexPath, rewrittenBytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to translate request body to Vertex format", e);
        }
    }

    /** Recursively remove "scope" from any "cache_control" object in the JSON tree. */
    private void stripCacheControlScope(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var obj = (ObjectNode) node;
            if (obj.has("cache_control") && obj.get("cache_control").isObject()) {
                ((ObjectNode) obj.get("cache_control")).remove("scope");
            }
            for (var it = obj.elements(); it.hasNext(); ) {
                stripCacheControlScope(it.next());
            }
        } else if (node.isArray()) {
            for (var element : node) {
                stripCacheControlScope(element);
            }
        }
    }

    // --- Header injection ---

    /**
     * Inject real credentials into the upstream request.
     * Returns false if a required token could not be obtained (caller should 502).
     */
    private boolean injectHeaders(HttpClientRequest upReq, String domain,
                               String upstreamHost, boolean isVertexRequest) {
        upReq.putHeader("Host", upstreamHost);

        if (isVertexRequest) {
            String token;
            try {
                token = getVertexAccessToken();
            } catch (Exception e) {
                System.err.println("Failed to get Vertex token: " + e.getMessage());
                return false;
            }
            upReq.putHeader("Authorization", "Bearer " + token);
            // Strip Anthropic-specific headers that Vertex doesn't use.
            // The translated body already carries anthropic_version.
            upReq.headers().remove("x-api-key");
            upReq.headers().remove("anthropic-beta");
            upReq.headers().remove("anthropic-version");
            upReq.headers().remove("anthropic-dangerous-direct-browser-access");
        } else if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (!credentials.oauthToken().isBlank()) {
                // The container's tool (claude or pi) was configured with an OAuth-shaped
                // placeholder, so it already built the OAuth request itself — Bearer auth
                // plus whatever Claude Code identity/beta headers Anthropic currently
                // requires. We only swap the placeholder token for the real one and never
                // touch those headers, so we don't have to track Anthropic's auth quirks here.
                upReq.putHeader("Authorization", "Bearer " + credentials.oauthToken());
                upReq.headers().remove("x-api-key");
            } else if (!credentials.anthropicApiKey().isBlank()) {
                upReq.putHeader("x-api-key", credentials.anthropicApiKey());
            } else {
                upReq.headers().remove("x-api-key");
            }
        } else {
            var tp = findToolProxy(domain);
            if (tp != null) {
                var headerName = tp.headerName();
                var headerValue = tp.computeHeaderValue();
                if (headerName != null && headerValue != null) {
                    upReq.putHeader(headerName, headerValue);
                }
            }
        }
        return true;
    }

    // --- GCP access token ---

    /**
     * Get a GCP access token for Vertex AI, caching it for ~50 minutes.
     * Tokens are obtained via {@code gcloud auth print-access-token} on the host.
     */
    private synchronized String getVertexAccessToken() {
        if (cachedVertexToken != null && System.currentTimeMillis() < vertexTokenExpiryMs) {
            return cachedVertexToken;
        }
        try {
            var pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            var process = pb.start();
            // Read stdout and stderr separately — gcloud may print warnings to
            // stderr (e.g. credential refresh notices) which would corrupt the token
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            var stderr = new String(process.getErrorStream().readAllBytes()).strip();
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("gcloud auth print-access-token failed (exit " + exitCode + "): " + stderr);
            }
            cachedVertexToken = stdout;
            vertexTokenExpiryMs = System.currentTimeMillis() + 50 * 60 * 1000L; // refresh every 50 min
            return cachedVertexToken;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to obtain GCP access token: " + e.getMessage() +
                    ". Ensure 'gcloud' is installed and 'gcloud auth application-default login' has been run.", e);
        }
    }

    private synchronized void invalidateVertexToken() {
        cachedVertexToken = null;
        vertexTokenExpiryMs = 0;
    }

    // --- Maven helpers ---

    /**
     * Check whether a Maven repository path is safe to cache.
     * Release artifacts are immutable; metadata and snapshots are not.
     */
    private static boolean isMavenCacheable(String path) {
        if (path.contains("..")) return false;
        if (path.endsWith("/")) return false;
        if (path.contains("maven-metadata.xml")) return false;
        if (path.contains("-SNAPSHOT")) return false;
        return true;
    }

    /**
     * Check whether a Maven path refers to an actual artifact (jar, pom, etc.)
     * rather than a checksum or signature sidecar (.sha1, .sha256, .md5, .asc).
     */
    static boolean isMavenArtifactFile(String path) {
        return !path.endsWith(".sha1") && !path.endsWith(".sha256")
                && !path.endsWith(".md5") && !path.endsWith(".asc");
    }

    /**
     * Map a Maven repository URL path to the corresponding path in ~/.m2/repository.
     * Returns null if the domain is unknown or the path doesn't match.
     */
    static Path resolveM2Path(String domain, String urlPath) {
        var prefix = MAVEN_PATH_PREFIX.get(domain);
        if (prefix == null || !urlPath.startsWith(prefix)) return null;
        var relativePath = urlPath.substring(prefix.length());
        if (relativePath.contains("..")) return null;
        return m2Repository().resolve(relativePath);
    }

    /** Compute the SHA-1 digest of a local file, returning the lowercase hex string. */
    static String computeSha1(Path file) throws Exception {
        var md = MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    /**
     * Fetch a resource body from upstream via a raw SSL GET.
     * Returns the response body, or null on any failure (non-200, network error).
     */
    static byte[] fetchUpstreamBody(String domain, String path, String... extraHeaders) {
        try {
            var socket = (javax.net.ssl.SSLSocket) javax.net.ssl.SSLSocketFactory.getDefault()
                    .createSocket(domain, 443);
            socket.setSoTimeout(30_000);

            try (socket) {
                socket.startHandshake();
                var out = socket.getOutputStream();
                var in = socket.getInputStream();

                var sb = new StringBuilder();
                sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
                sb.append("Host: ").append(domain).append("\r\n");
                for (var header : extraHeaders) {
                    sb.append(header).append("\r\n");
                }
                sb.append("Connection: close\r\n\r\n");
                out.write(sb.toString().getBytes());
                out.flush();

                var response = HttpMessage.readResponse(in);
                if (response == null || response.statusCode() != 200) return null;

                var clHeader = response.header("Content-Length");
                if (clHeader != null) {
                    int len = Integer.parseInt(clHeader.trim());
                    var body = new byte[len];
                    int offset = 0;
                    while (offset < len) {
                        int n = in.read(body, offset, len - offset);
                        if (n == -1) break;
                        offset += n;
                    }
                    return body;
                }
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetch a checksum sidecar file from upstream (e.g. .sha1 or .sha256).
     * Returns the hex checksum string, or null if it could not be retrieved
     * or doesn't match the expected length.
     */
    private static String fetchChecksumFromUpstream(String domain, String checksumPath,
                                                     int hexLength) {
        var body = fetchUpstreamBody(domain, checksumPath);
        if (body == null) return null;
        var hex = new String(body).trim().split("\\s+")[0].toLowerCase();
        if (hex.matches("[a-f0-9]{" + hexLength + "}")) return hex;
        return null;
    }

    // --- Digest verification ---

    static boolean verifyDigest(Path file, String expectedDigest) throws Exception {
        var parts = expectedDigest.split(":", 2);
        if (parts.length != 2) return false;
        var algorithm = switch (parts[0]) {
            case "sha256" -> "SHA-256";
            case "sha1" -> "SHA-1";
            default -> null;
        };
        if (algorithm == null) return false;

        var md = MessageDigest.getInstance(algorithm);
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        var actual = java.util.HexFormat.of().formatHex(md.digest());
        return actual.equals(parts[1]);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // --- Health check ---

    private void handleHealthCheck(HttpServerRequest req) {
        if (!"/health".equals(req.path())) {
            req.response().setStatusCode(404).end();
            return;
        }
        var info = BuildInfo.instance();
        var body = "{\"status\":\"ok\""
                + ",\"version\":\"" + info.version() + "\""
                + ",\"gitSha\":\"" + info.gitSha() + "\""
                + ",\"runtime\":\"" + escapeJson(info.runtime()) + "\""
                + ",\"caFingerprint\":\"" + caFingerprint + "\""
                + ",\"toolProxyFingerprint\":\"" + toolProxyFingerprint + "\""
                + ",\"dnsConfigured\":" + dnsConfigured + "}";
        req.response()
                .putHeader("Content-Type", "application/json")
                .end(body);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --- SSL trust ---

    private static final String[] SYSTEM_CA_BUNDLES = {
            "/etc/ssl/cert.pem",                                    // Fedora (symlink), macOS, Alpine
            "/etc/ssl/certs/ca-certificates.crt",                   // Debian, Ubuntu
            "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",    // RHEL, CentOS
    };

    private static String findSystemCaBundle() {
        for (var path : SYSTEM_CA_BUNDLES) {
            if (Files.exists(Path.of(path))) return path;
        }
        return null;
    }

    // --- Vert.x helpers ---

    private void copyRequestHeaders(HttpServerRequest clientReq, HttpClientRequest upReq,
                                    String domain) {
        upReq.headers().setAll(clientReq.headers());
        upReq.headers().remove("Host");
        upReq.headers().remove("Connection");
        upReq.headers().remove("Transfer-Encoding");
        upReq.putHeader("Host", domain);
    }

    private void copyResponseHeaders(HttpClientResponse upResp, HttpServerResponse clientResp) {
        clientResp.headers().setAll(upResp.headers());
        clientResp.headers().remove("Connection");
        clientResp.headers().remove("Transfer-Encoding");
    }

    private io.vertx.core.Future<HttpClientResponse> sendWithBody(
            HttpServerRequest clientReq, HttpClientRequest upReq) {
        var cl = clientReq.getHeader("Content-Length");
        var te = clientReq.getHeader("Transfer-Encoding");
        var hasBody = (cl != null && !"0".equals(cl))
                || (te != null && te.toLowerCase().contains("chunked"));
        if (hasBody) {
            return clientReq.body().compose(body -> upReq.send(body));
        }
        return upReq.send();
    }

    private void pipeResponse(HttpClientResponse upResp, HttpServerResponse clientResp) {
        int status = clientResp.getStatusCode();
        if (upResp.getHeader("Content-Length") == null
                && status != 204 && status != 304 && (status < 100 || status >= 200)) {
            clientResp.setChunked(true);
        }
        upResp.handler(chunk -> {
            clientResp.write(chunk);
            if (clientResp.writeQueueFull()) {
                upResp.pause();
                clientResp.drainHandler(v -> upResp.resume());
            }
        });
        upResp.endHandler(v -> clientResp.end());
        upResp.exceptionHandler(err ->
                System.err.println("Relay stream error: " + err.getMessage()));
    }

    private void sendError(HttpServerResponse resp, int statusCode, String message) {
        try {
            if (!resp.ended() && !resp.closed()) {
                if (resp.headWritten()) {
                    resp.end();
                } else {
                    resp.setStatusCode(statusCode).end(message);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to send error response: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    // --- Debug logging helpers ---

    private String dumpRequest(HttpServerRequest req) {
        var sb = new StringBuilder();
        sb.append(req.method()).append(' ').append(req.uri())
                .append(' ').append(req.version() == HttpVersion.HTTP_1_1 ? "HTTP/1.1" : "HTTP/1.0")
                .append('\n');
        for (var entry : req.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private String dumpResponse(HttpClientResponse resp) {
        var sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.statusCode())
                .append(' ').append(resp.statusMessage()).append('\n');
        for (var entry : resp.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }
}
