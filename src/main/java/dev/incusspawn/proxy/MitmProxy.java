package dev.incusspawn.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * TLS-terminating MITM proxy for transparent credential injection.
 * <p>
 * Containers resolve intercepted domains (api.anthropic.com, github.com, etc.)
 * to the Incus bridge gateway IP via /etc/hosts. This proxy listens on port 443
 * on the gateway IP, terminates TLS using per-domain certificates signed by a
 * custom CA, injects authentication headers, and forwards to the real upstream.
 * <p>
 * Credentials never enter containers in any form. Tools (curl, git, gh, claude)
 * work completely unmodified inside containers.
 */
public class MitmProxy {

    public static final int DEFAULT_MITM_PORT = 443;
    public static final int DEFAULT_HEALTH_PORT = 18080;

    private static final Set<String> INTERCEPTED_DOMAIN_SET = Set.of(
            "api.anthropic.com",
            "github.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "codeload.github.com",
            "uploads.github.com"
    );

    private static final Set<String> ANTHROPIC_DOMAINS = Set.of("api.anthropic.com");
    private static final Set<String> GITHUB_DOMAINS = Set.of(
            "github.com", "api.github.com",
            "raw.githubusercontent.com", "objects.githubusercontent.com",
            "codeload.github.com", "uploads.github.com"
    );

    // Pattern to extract "model" field from JSON body without a full parser
    private static final Pattern MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");
    // Pattern to detect streaming requests
    private static final Pattern STREAM_PATTERN = Pattern.compile("\"stream\"\\s*:\\s*true");

    private final String bindAddress;
    private final int mitmPort;
    private final int healthPort;
    private final String anthropicApiKey;
    private final String ghToken;

    // Vertex AI configuration (when useVertex=true, proxy translates standard API
    // requests to Vertex AI format — containers run in standard mode regardless)
    private final boolean useVertex;
    private final String vertexRegion;
    private final String vertexProjectId;

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
     * Used by BuildCommand to write /etc/hosts entries in golden images.
     */
    public static Set<String> interceptedDomains() {
        return INTERCEPTED_DOMAIN_SET;
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
        if (useVertex) {
            System.out.println("Vertex AI mode: translating requests to " +
                    vertexRegion + "-aiplatform.googleapis.com" +
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

            // Vertex AI translation: rewrite Anthropic API requests to Vertex format
            if (useVertex && ANTHROPIC_DOMAINS.contains(domain)) {
                handleVertexTranslation(request, in, out);
                return;
            }

            // Standard path: inject auth headers and forward to the original domain
            injectHeaders(request, domain);

            var upstreamSocket = (SSLSocket) SSLSocketFactory.getDefault()
                    .createSocket(domain, 443);
            upstreamSocket.setSoTimeout(300_000);

            try (upstreamSocket) {
                upstreamSocket.startHandshake();
                var upstreamOut = upstreamSocket.getOutputStream();
                var upstreamIn = upstreamSocket.getInputStream();

                request.writeTo(upstreamOut);
                request.relayRequestBody(in, upstreamOut);

                HttpMessage.relayResponse(upstreamIn, out);
            }
        } catch (IOException e) {
            // Connection closed or network error — expected during normal operation
        } catch (Exception e) {
            System.err.println("MITM connection error: " + e.getMessage());
        }
    }

    /**
     * Translate a standard Anthropic API request to Vertex AI format.
     * The container sends a normal request to api.anthropic.com; this method
     * rewrites it for the Vertex AI rawPredict/streamRawPredict endpoint.
     */
    private void handleVertexTranslation(HttpMessage request, java.io.InputStream clientIn,
                                          java.io.OutputStream clientOut) throws IOException {
        // Read the full request body to extract the model name and detect streaming
        var body = request.readRequestBody(clientIn);
        var bodyStr = new String(body);

        // Extract model name (e.g. "claude-sonnet-4-20250514")
        var modelMatcher = MODEL_PATTERN.matcher(bodyStr);
        var model = modelMatcher.find() ? modelMatcher.group(1) : "claude-sonnet-4-20250514";

        // Detect streaming
        var isStream = STREAM_PATTERN.matcher(bodyStr).find();

        // Build the Vertex AI path
        var vertexAction = isStream ? "streamRawPredict" : "rawPredict";
        var vertexPath = "/v1/projects/" + vertexProjectId +
                "/locations/" + vertexRegion +
                "/publishers/anthropic/models/" + model +
                ":" + vertexAction;
        request.setPath(vertexPath);

        // Replace auth: remove x-api-key, add GCP Bearer token
        request.removeHeader("x-api-key");
        var token = getVertexAccessToken();
        request.setHeader("Authorization", "Bearer " + token);

        // Point to the Vertex AI host
        var vertexHost = vertexRegion + "-aiplatform.googleapis.com";
        request.setHeader("Host", vertexHost);

        // Connect to the Vertex AI endpoint
        var upstreamSocket = (SSLSocket) SSLSocketFactory.getDefault()
                .createSocket(vertexHost, 443);
        upstreamSocket.setSoTimeout(300_000);

        try (upstreamSocket) {
            upstreamSocket.startHandshake();
            var upstreamOut = upstreamSocket.getOutputStream();
            var upstreamIn = upstreamSocket.getInputStream();

            // Write modified request headers
            request.writeTo(upstreamOut);
            // Write the buffered body directly (already read in full)
            upstreamOut.write(body);
            upstreamOut.flush();

            // Relay the response back to the client
            HttpMessage.relayResponse(upstreamIn, clientOut);
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
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("gcloud auth print-access-token failed (exit " + exitCode + "): " + output);
            }
            cachedVertexToken = output;
            vertexTokenExpiryMs = System.currentTimeMillis() + 50 * 60 * 1000L; // refresh every 50 min
            return cachedVertexToken;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to obtain GCP access token: " + e.getMessage() +
                    ". Ensure 'gcloud' is installed and 'gcloud auth application-default login' has been run.", e);
        }
    }

    private void injectHeaders(HttpMessage request, String domain) {
        if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (anthropicApiKey != null && !anthropicApiKey.isBlank()) {
                request.setHeader("x-api-key", anthropicApiKey);
            }
        } else if (GITHUB_DOMAINS.contains(domain)) {
            if (ghToken != null && !ghToken.isBlank()) {
                request.setHeader("Authorization", "Bearer " + ghToken);
            }
        }
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
