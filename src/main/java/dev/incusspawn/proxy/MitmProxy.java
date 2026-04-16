package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;

import javax.net.ssl.*;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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
 */
public class MitmProxy {

    public static final int CONTAINER_FACING_PORT = 443;
    public static final int DEFAULT_MITM_PORT = 18443;
    public static final int DEFAULT_HEALTH_PORT = 18080;

    private static final Set<String> ANTHROPIC_DOMAINS = Set.of("api.anthropic.com");
    private static final Set<String> GITHUB_DOMAINS = Set.of(
            "github.com", "api.github.com",
            "raw.githubusercontent.com", "objects.githubusercontent.com",
            "codeload.github.com", "uploads.github.com"
    );
    private static final Set<String> REGISTRY_DOMAINS = Set.of(
            "registry-1.docker.io", "auth.docker.io",
            "ghcr.io", "quay.io"
    );

    private static final Set<String> INTERCEPTED_DOMAIN_SET;
    static {
        var all = new HashSet<String>();
        all.addAll(ANTHROPIC_DOMAINS);
        all.addAll(GITHUB_DOMAINS);
        all.addAll(REGISTRY_DOMAINS);
        INTERCEPTED_DOMAIN_SET = Set.copyOf(all);
    }

    // OCI blob URL pattern: /v2/<name>/blobs/sha256:<64-hex-chars>
    // Group 1 = image name (e.g. "library/postgres"), group 2 = digest
    private static final Pattern BLOB_DIGEST_PATTERN = Pattern.compile(
            "/v2/(.+)/blobs/(sha256:[a-f0-9]{64})");

    private static final Path REGISTRY_CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".cache", "incus-spawn", "registry");

    private final String bindAddress;
    private final int mitmPort;
    private final int healthPort;
    private final String anthropicApiKey;
    private final String ghToken;

    // Vertex AI configuration. When useVertex=true, the proxy transparently translates
    // standard Anthropic API requests (to api.anthropic.com) into Vertex AI rawPredict
    // requests. Containers always run Claude Code in standard mode — they have no
    // knowledge of Vertex AI.
    private final boolean useVertex;
    private final String vertexRegion;
    private final String vertexProjectId;

    private static final ObjectMapper JSON = new ObjectMapper();

    // Top-level fields accepted by Vertex AI rawPredict. Anything else (beta features
    // like context_management, etc.) is stripped to avoid "Extra inputs" rejections.
    private static final Set<String> VERTEX_ALLOWED_FIELDS = Set.of(
            "anthropic_version", "messages", "system", "max_tokens",
            "temperature", "top_p", "top_k", "stop_sequences", "stream",
            "metadata", "tools", "tool_choice"
    );

    // Track which stripped fields have already been logged (avoid spam)
    private final Set<String> loggedStrippedFields = ConcurrentHashMap.newKeySet();

    // Cached GCP access token for Vertex AI (tokens last ~60 min, refresh at ~50 min)
    private String cachedVertexToken;
    private long vertexTokenExpiryMs;

    private volatile boolean running;
    private HttpServer healthServer;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // SNI hostname captured during TLS handshake, keyed by thread
    private final ConcurrentHashMap<Long, String> sniByThread = new ConcurrentHashMap<>();

    // Pre-generated per-domain TLS material
    private SSLContext serverSslContext;

    public MitmProxy(String bindAddress, int mitmPort, int healthPort,
                     String anthropicApiKey, String ghToken,
                     boolean useVertex, String vertexRegion, String vertexProjectId) {
        this.bindAddress = bindAddress;
        this.mitmPort = mitmPort;
        this.healthPort = healthPort;
        this.anthropicApiKey = anthropicApiKey;
        this.ghToken = ghToken;
        this.useVertex = useVertex;
        this.vertexRegion = vertexRegion != null ? vertexRegion : "";
        this.vertexProjectId = vertexProjectId != null ? vertexProjectId : "";
    }

    /**
     * Create a MitmProxy using credentials from SpawnConfig and the Incus bridge gateway IP.
     */
    public static MitmProxy fromConfig(IncusClient incus) {
        var config = SpawnConfig.load();
        var gatewayIp = resolveGatewayIp(incus);
        var claude = config.getClaude();
        return new MitmProxy(
                gatewayIp,
                DEFAULT_MITM_PORT,
                DEFAULT_HEALTH_PORT,
                claude.getApiKey(),
                config.getGithub().getToken(),
                claude.isUseVertex(),
                claude.getCloudMlRegion(),
                claude.getVertexProjectId());
    }

    /**
     * Resolve the Incus bridge gateway IP (e.g. "10.166.11.1").
     */
    public static String resolveGatewayIp(IncusClient incus) {
        var result = incus.exec("network", "get", "incusbr0", "ipv4.address");
        var addr = result.assertSuccess("Failed to get bridge IP").stdout().strip();
        if (addr.contains("/")) {
            addr = addr.substring(0, addr.indexOf('/'));
        }
        return addr;
    }

    /**
     * The set of domains intercepted by this proxy.
     */
    public static Set<String> interceptedDomains() {
        return INTERCEPTED_DOMAIN_SET;
    }

    /**
     * Configure bridge-level DNS overrides via dnsmasq so all containers on
     * incusbr0 resolve intercepted domains to the gateway IP.
     */
    public static void configureBridgeDns(IncusClient incus) {
        var gatewayIp = resolveGatewayIp(incus);
        // Set A records to the gateway IP and block AAAA records (return ::)
        // to prevent IPv6 connections from bypassing the proxy.
        var dnsmasqConfig = interceptedDomains().stream()
                .sorted()
                .flatMap(d -> java.util.stream.Stream.of(
                        "address=/" + d + "/" + gatewayIp,
                        "address=/" + d + "/::"))
                .collect(java.util.stream.Collectors.joining("\n"));
        incus.exec("network", "set", "incusbr0", "raw.dnsmasq", dnsmasqConfig)
                .assertSuccess("Failed to configure bridge DNS overrides");
        System.out.println("  DNS overrides: " + interceptedDomains().size() +
                " domains -> " + gatewayIp + " (via bridge dnsmasq)");
    }

    /**
     * Clear bridge-level DNS overrides, restoring normal DNS resolution.
     */
    public static void clearBridgeDns(IncusClient incus) {
        incus.exec("network", "set", "incusbr0", "raw.dnsmasq", "");
    }

    /**
     * Start the MITM proxy and health server. Blocks until {@link #stop()} is called.
     */
    public void start() throws Exception {
        running = true;

        // Load or create CA, generate per-domain certs
        var ca = CertificateAuthority.loadOrCreate();
        serverSslContext = buildServerSslContext(ca);

        // Start health check HTTP server
        healthServer = HttpServer.create(new InetSocketAddress(bindAddress, healthPort), 0);
        healthServer.setExecutor(executor);
        healthServer.createContext("/health", this::handleHealthCheck);
        healthServer.start();

        System.out.println("MITM proxy listening on " + bindAddress + ":" + mitmPort);
        System.out.println("Health endpoint on " + bindAddress + ":" + healthPort + "/health");
        System.out.println("Intercepted domains: " + INTERCEPTED_DOMAIN_SET);
        System.out.println("Registry cache: " + REGISTRY_CACHE_DIR +
                " (domains: " + REGISTRY_DOMAINS + ")");
        if (useVertex) {
            System.out.println("Vertex AI mode: translating api.anthropic.com requests" +
                    " to " + vertexRegion + "-aiplatform.googleapis.com" +
                    " (project: " + vertexProjectId + ")");
        }
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        // Start TLS server
        executor.submit(this::runMitmServer);

        // Block until stopped
        try {
            while (running) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        running = false;
        if (healthServer != null) {
            healthServer.stop(1);
        }
        executor.shutdownNow();
    }

    // --- TLS Server ---

    private void runMitmServer() {
        try {
            var serverSocketFactory = serverSslContext.getServerSocketFactory();
            var serverSocket = (SSLServerSocket) serverSocketFactory.createServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindAddress, mitmPort));

            // Negotiate HTTP/1.1 only
            var params = serverSocket.getSSLParameters();
            params.setApplicationProtocols(new String[]{"http/1.1"});
            serverSocket.setSSLParameters(params);

            while (running) {
                try {
                    var clientSocket = (SSLSocket) serverSocket.accept();
                    executor.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("MITM accept error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Failed to start MITM server: " + e.getMessage());
                stop();
            }
        }
    }

    private void handleConnection(SSLSocket clientSocket) {
        try (clientSocket) {
            clientSocket.setSoTimeout(120_000);
            clientSocket.startHandshake();

            var in = clientSocket.getInputStream();
            var out = clientSocket.getOutputStream();

            // Read the HTTP request
            var request = HttpMessage.readRequest(in);
            if (request == null) return;

            // Determine domain from Host header or SNI
            var domain = request.host();
            if (domain == null) {
                var sniDomain = sniByThread.remove(Thread.currentThread().getId());
                domain = sniDomain;
            } else {
                sniByThread.remove(Thread.currentThread().getId());
            }

            if (domain == null || !INTERCEPTED_DOMAIN_SET.contains(domain)) {
                System.err.println("MITM: unknown domain: " + domain);
                out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".getBytes());
                return;
            }

            // Registry traffic — cache blobs, relay everything else
            if (REGISTRY_DOMAINS.contains(domain)) {
                handleRegistryRequest(request, in, out, domain);
                return;
            }

            // For Anthropic API requests when using Vertex AI, translate the request
            // to the Vertex rawPredict format. This requires buffering the body to
            // extract the model name and rewrite the JSON.
            String upstreamHost;
            byte[] rewrittenBody = null;

            if (useVertex && ANTHROPIC_DOMAINS.contains(domain)) {
                upstreamHost = vertexRegion + "-aiplatform.googleapis.com";
                var bodyBytes = request.readRequestBody(in);
                rewrittenBody = translateToVertex(request, bodyBytes, upstreamHost);
            } else {
                upstreamHost = domain;
                injectHeaders(request, domain);
            }

            var upstreamSocket = (SSLSocket) SSLSocketFactory.getDefault()
                    .createSocket(upstreamHost, 443);
            upstreamSocket.setSoTimeout(300_000);

            try (upstreamSocket) {
                upstreamSocket.startHandshake();
                var upstreamOut = upstreamSocket.getOutputStream();
                var upstreamIn = upstreamSocket.getInputStream();

                request.writeTo(upstreamOut);
                if (rewrittenBody != null) {
                    // Vertex: send the rewritten body we already buffered
                    upstreamOut.write(rewrittenBody);
                    upstreamOut.flush();
                } else {
                    // Non-Vertex: stream body directly from client
                    request.relayRequestBody(in, upstreamOut);
                }

                HttpMessage.relayResponse(upstreamIn, out);
            }
        } catch (IOException e) {
            // Connection closed or network error — expected during normal operation
        } catch (Exception e) {
            System.err.println("MITM connection error: " + e.getMessage());
        }
    }

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
     *   <li>Header: anthropic-beta removed (Vertex rejects unsupported beta flags)</li>
     *   <li>Streaming: :rawPredict → :streamRawPredict when stream=true</li>
     * </ul>
     *
     * @return the rewritten body bytes
     */
    private byte[] translateToVertex(HttpMessage request, byte[] bodyBytes, String upstreamHost) {
        try {
            var tree = bodyBytes.length > 0 ? JSON.readTree(bodyBytes) : null;

            // Non-JSON or non-object body (e.g. GET /v1/models): just rewrite auth and forward
            if (tree == null || !tree.isObject()) {
                request.setHeader("Host", upstreamHost);
                request.setHeader("Authorization", "Bearer " + getVertexAccessToken());
                request.removeHeader("x-api-key");
                request.removeHeader("anthropic-beta");
                return bodyBytes;
            }

            var root = (ObjectNode) tree;

            // Extract model (goes into URL, not body)
            var model = root.has("model") ? root.get("model").asText() : "claude-sonnet-4-6";
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

            // Add Vertex API version
            root.put("anthropic_version", "vertex-2023-10-16");

            // Strip "scope" from cache_control objects deep in the tree (beta feature)
            stripCacheControlScope(root);

            var rewrittenBytes = JSON.writeValueAsBytes(root);

            // Rewrite URL path
            var endpoint = streaming ? ":streamRawPredict" : ":rawPredict";
            var vertexPath = "/v1/projects/" + vertexProjectId + "/locations/" + vertexRegion +
                    "/publishers/anthropic/models/" + model + endpoint;
            request.setPath(vertexPath);

            // Rewrite headers
            request.setHeader("Host", upstreamHost);
            request.setHeader("Authorization", "Bearer " + getVertexAccessToken());
            request.removeHeader("x-api-key");
            request.removeHeader("anthropic-beta");
            request.setHeader("Content-Length", String.valueOf(rewrittenBytes.length));

            return rewrittenBytes;
        } catch (IOException e) {
            throw new RuntimeException("Failed to translate request body to Vertex format", e);
        }
    }

    /**
     * Recursively remove "scope" from any "cache_control" object in the JSON tree.
     */
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

    private void injectHeaders(HttpMessage request, String domain) {
        if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (anthropicApiKey != null && !anthropicApiKey.isBlank()) {
                request.setHeader("x-api-key", anthropicApiKey);
            }
        } else if (GITHUB_DOMAINS.contains(domain)) {
            if (ghToken != null && !ghToken.isBlank()) {
                if ("github.com".equals(domain)) {
                    // Git HTTP transport requires Basic auth (token as password)
                    var credentials = "x-access-token:" + ghToken;
                    var encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
                    request.setHeader("Authorization", "Basic " + encoded);
                } else {
                    // API and CDN domains accept Bearer tokens
                    request.setHeader("Authorization", "Bearer " + ghToken);
                }
            }
        }
    }

    // --- Registry blob caching ---

    /**
     * Handle a request to a container registry domain.
     * GET requests for blobs with a SHA256 digest are served from cache or
     * fetched, cached, and served. Everything else is relayed transparently.
     */
    private void handleRegistryRequest(HttpMessage request, InputStream clientIn,
                                       OutputStream clientOut, String domain) throws Exception {
        var path = request.path();
        var method = request.method();

        if ("GET".equals(method) && path != null) {
            var matcher = BLOB_DIGEST_PATTERN.matcher(path);
            if (matcher.matches()) {
                var imageName = matcher.group(1);
                var digest = matcher.group(2);
                var imageRef = domain + "/" + imageName;
                var cacheFile = REGISTRY_CACHE_DIR.resolve(digest.replace(":", "-"));

                if (Files.exists(cacheFile)) {
                    System.out.println("Registry cache hit: " + imageRef +
                            " " + digest.substring(0, 19) +
                            "... (" + formatSize(Files.size(cacheFile)) + ")");
                    serveCachedBlob(cacheFile, digest, clientOut);
                    return;
                }

                fetchCacheAndServe(request, clientIn, clientOut, domain,
                        digest, cacheFile, imageRef);
                return;
            }
        }

        // Non-cacheable (auth tokens, manifests, HEAD, tag lookups) — relay
        relayToUpstream(request, clientIn, clientOut, domain);
    }

    /**
     * Serve a cached blob with a synthetic HTTP 200 response.
     */
    private void serveCachedBlob(Path cacheFile, String digest,
                                 OutputStream clientOut) throws IOException {
        var size = Files.size(cacheFile);
        var header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + size + "\r\n" +
                "Docker-Content-Digest: " + digest + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        clientOut.write(header.getBytes());
        try (var fis = Files.newInputStream(cacheFile)) {
            fis.transferTo(clientOut);
        }
        clientOut.flush();
    }

    /**
     * Fetch a blob from upstream, tee-stream it to the client and a temp file,
     * verify the SHA256 digest, and atomically move into the cache.
     */
    private void fetchCacheAndServe(HttpMessage request, InputStream clientIn,
                                    OutputStream clientOut, String domain,
                                    String digest, Path cacheFile,
                                    String imageRef) throws Exception {
        var upstreamSocket = (SSLSocket) SSLSocketFactory.getDefault()
                .createSocket(domain, 443);
        upstreamSocket.setSoTimeout(300_000);

        try (upstreamSocket) {
            upstreamSocket.startHandshake();
            var upstreamOut = upstreamSocket.getOutputStream();
            var upstreamIn = upstreamSocket.getInputStream();

            request.writeTo(upstreamOut);
            request.relayRequestBody(clientIn, upstreamOut);

            var response = HttpMessage.readResponse(upstreamIn);
            if (response == null) return;

            var statusCode = response.statusCode();

            if (statusCode == 307 || statusCode == 302 || statusCode == 301) {
                var location = response.header("Location");
                if (location != null) {
                    fetchFromRedirect(location, clientOut, digest, cacheFile, imageRef);
                    return;
                }
            }

            if (statusCode == 200) {
                Files.createDirectories(REGISTRY_CACHE_DIR);
                var tempFile = Files.createTempFile(REGISTRY_CACHE_DIR, "blob-", ".tmp");
                try {
                    response.writeTo(clientOut);
                    var contentLength = response.header("Content-Length");

                    try (var fileOut = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                        if (contentLength != null) {
                            teeStream(upstreamIn, clientOut, fileOut,
                                    Long.parseLong(contentLength.trim()));
                        } else {
                            teeUntilEof(upstreamIn, clientOut, fileOut);
                        }
                    }

                    if (verifyDigest(tempFile, digest)) {
                        Files.move(tempFile, cacheFile,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("Registry cached: " + imageRef +
                                " " + digest.substring(0, 19) +
                                "... (" + formatSize(Files.size(cacheFile)) + ")");
                    } else {
                        System.err.println("Registry cache: SHA256 mismatch for " +
                                imageRef + " " + digest + ", not caching");
                        Files.deleteIfExists(tempFile);
                    }
                } catch (Exception e) {
                    Files.deleteIfExists(tempFile);
                    throw e;
                }
                return;
            }

            // Non-200, non-redirect — relay as-is
            response.writeTo(clientOut);
            response.relayResponseBody(upstreamIn, clientOut);
        }
    }

    /**
     * Follow a redirect (307/302/301) on the host side, tee-stream the
     * response to the client and cache file, then verify the digest.
     */
    private void fetchFromRedirect(String location, OutputStream clientOut,
                                   String digest, Path cacheFile,
                                   String imageRef) throws Exception {
        var conn = (HttpURLConnection) new URL(location).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setReadTimeout(300_000);
        conn.setConnectTimeout(30_000);

        try {
            var responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                var errorBody = conn.getErrorStream() != null
                        ? conn.getErrorStream().readAllBytes() : new byte[0];
                var header = "HTTP/1.1 " + responseCode + " " +
                        conn.getResponseMessage() + "\r\n" +
                        "Content-Length: " + errorBody.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                clientOut.write(header.getBytes());
                clientOut.write(errorBody);
                clientOut.flush();
                return;
            }

            var contentLength = conn.getContentLengthLong();
            Files.createDirectories(REGISTRY_CACHE_DIR);
            var tempFile = Files.createTempFile(REGISTRY_CACHE_DIR, "blob-", ".tmp");

            try (var cdnIn = conn.getInputStream()) {
                var header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        (contentLength >= 0 ? "Content-Length: " + contentLength + "\r\n" : "") +
                        "Docker-Content-Digest: " + digest + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
                clientOut.write(header.getBytes());

                try (var fileOut = new BufferedOutputStream(Files.newOutputStream(tempFile))) {
                    if (contentLength >= 0) {
                        teeStream(cdnIn, clientOut, fileOut, contentLength);
                    } else {
                        teeUntilEof(cdnIn, clientOut, fileOut);
                    }
                }

                if (verifyDigest(tempFile, digest)) {
                    Files.move(tempFile, cacheFile,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Registry cached: " + imageRef +
                            " " + digest.substring(0, 19) +
                            "... (" + formatSize(Files.size(cacheFile)) + ")");
                } else {
                    System.err.println("Registry cache: SHA256 mismatch for " +
                            imageRef + " " + digest + ", not caching");
                    Files.deleteIfExists(tempFile);
                }
            } catch (Exception e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Relay a non-cacheable registry request transparently to upstream.
     */
    private void relayToUpstream(HttpMessage request, InputStream clientIn,
                                 OutputStream clientOut, String domain) throws Exception {
        var upstreamSocket = (SSLSocket) SSLSocketFactory.getDefault()
                .createSocket(domain, 443);
        upstreamSocket.setSoTimeout(300_000);

        try (upstreamSocket) {
            upstreamSocket.startHandshake();
            var upstreamOut = upstreamSocket.getOutputStream();
            var upstreamIn = upstreamSocket.getInputStream();

            request.writeTo(upstreamOut);
            request.relayRequestBody(clientIn, upstreamOut);
            HttpMessage.relayResponse(upstreamIn, clientOut);
        }
    }

    // --- Tee streaming helpers ---

    private static void teeStream(InputStream in, OutputStream out1,
                                  OutputStream out2, long length) throws IOException {
        var buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n == -1) break;
            out1.write(buffer, 0, n);
            out1.flush();
            out2.write(buffer, 0, n);
            remaining -= n;
        }
        out2.flush();
    }

    private static void teeUntilEof(InputStream in, OutputStream out1,
                                    OutputStream out2) throws IOException {
        var buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out1.write(buffer, 0, n);
            out1.flush();
            out2.write(buffer, 0, n);
        }
        out2.flush();
    }

    private static boolean verifyDigest(Path file, String expectedDigest) throws Exception {
        var parts = expectedDigest.split(":", 2);
        if (parts.length != 2 || !"sha256".equals(parts[0])) return false;

        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[8192];
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

    // --- SSL Context ---

    /**
     * Build an SSLContext with a custom KeyManager that selects the right
     * certificate based on the SNI hostname from the TLS handshake.
     */
    private SSLContext buildServerSslContext(CertificateAuthority ca) throws Exception {
        // Generate certs for all intercepted domains
        var domainCerts = new ConcurrentHashMap<String, CertificateAuthority.CertEntry>();
        for (var domain : INTERCEPTED_DOMAIN_SET) {
            domainCerts.put(domain, ca.generateDomainCert(domain));
        }

        // Build a KeyStore with all domain certs
        var keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        for (var entry : domainCerts.entrySet()) {
            keyStore.setKeyEntry(
                    entry.getKey(),
                    entry.getValue().key(),
                    "changeit".toCharArray(),
                    new X509Certificate[]{entry.getValue().cert(), ca.caCert()});
        }

        // Custom KeyManager that picks the cert based on SNI
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());
        var baseKeyManager = (X509ExtendedKeyManager) kmf.getKeyManagers()[0];

        var sniKeyManager = new X509ExtendedKeyManager() {
            @Override
            public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                return chooseAlias(engine.getPeerHost(), engine.getHandshakeSession());
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                if (socket instanceof SSLSocket sslSocket) {
                    return chooseAlias(null, sslSocket.getHandshakeSession());
                }
                return domainCerts.keySet().iterator().next();
            }

            private String chooseAlias(String peerHost, SSLSession session) {
                // Try to get SNI from the handshake session
                if (session instanceof ExtendedSSLSession extSession) {
                    for (var sni : extSession.getRequestedServerNames()) {
                        if (sni.getType() == StandardConstants.SNI_HOST_NAME) {
                            var hostname = ((SNIHostName) sni).getAsciiName();
                            if (domainCerts.containsKey(hostname)) {
                                // Store SNI for later use in handleConnection
                                sniByThread.put(Thread.currentThread().getId(), hostname);
                                return hostname;
                            }
                        }
                    }
                }
                // Fallback to peerHost or first domain
                if (peerHost != null && domainCerts.containsKey(peerHost)) {
                    return peerHost;
                }
                return domainCerts.keySet().iterator().next();
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return baseKeyManager.chooseClientAlias(keyType, issuers, socket);
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return baseKeyManager.getCertificateChain(alias);
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return baseKeyManager.getPrivateKey(alias);
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return baseKeyManager.getClientAliases(keyType, issuers);
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return baseKeyManager.getServerAliases(keyType, issuers);
            }
        };

        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(new KeyManager[]{sniKeyManager}, null, null);
        return sslContext;
    }

    // --- Health check ---

    private void handleHealthCheck(HttpExchange exchange) throws IOException {
        var response = "{\"status\":\"ok\"}".getBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
