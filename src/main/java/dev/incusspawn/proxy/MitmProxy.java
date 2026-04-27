package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import io.vertx.core.net.JksOptions;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
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

    public static final int CONTAINER_FACING_PORT = 443;
    public static final int DEFAULT_MITM_PORT = 18443;
    public static final int DEFAULT_HEALTH_PORT = 18080;

    private static final int BUFFER_SIZE = 64 * 1024;

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
    private static final Set<String> MAVEN_DOMAINS = Set.of(
            "repo.maven.apache.org", "repo1.maven.org",
            "plugins.gradle.org"
    );

    private static final Set<String> INTERCEPTED_DOMAIN_SET;
    static {
        var all = new HashSet<String>();
        all.addAll(ANTHROPIC_DOMAINS);
        all.addAll(GITHUB_DOMAINS);
        all.addAll(REGISTRY_DOMAINS);
        all.addAll(MAVEN_DOMAINS);
        INTERCEPTED_DOMAIN_SET = Set.copyOf(all);
    }

    // OCI blob URL pattern: /v2/<name>/blobs/sha256:<64-hex-chars>
    // Group 1 = image name (e.g. "library/postgres"), group 2 = digest
    private static final Pattern BLOB_DIGEST_PATTERN = Pattern.compile(
            "/v2/(.+)/blobs/(sha256:[a-f0-9]{64})");

    private static Path registryCacheDir() {
        return RuntimeConstants.REGISTRY_CACHE_DIR;
    }

    private static Path mavenCacheDir() {
        return RuntimeConstants.MAVEN_CACHE_DIR;
    }

    private static Path m2Repository() {
        return RuntimeConstants.M2_REPOSITORY;
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
            "metadata", "tools", "tool_choice", "thinking", "output_config"
    );

    // Track which stripped fields have already been logged (avoid spam)
    private final Set<String> loggedStrippedFields = ConcurrentHashMap.newKeySet();

    // Cached GCP access token for Vertex AI (tokens last ~60 min, refresh at ~50 min)
    private String cachedVertexToken;
    private long vertexTokenExpiryMs;

    private Vertx vertx;
    private HttpServer mitmServer;
    private HttpServer healthHttpServer;
    private HttpClient upstreamClient;
    private CountDownLatch stopLatch;

    private ApiTrafficLog debugLog;
    // CA fingerprint computed at startup for the health endpoint
    private String caFingerprint = "";

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
     * Resolve the Vertex AI hostname for a region. Some regions use special hostnames
     * instead of the standard {@code {region}-aiplatform.googleapis.com} pattern.
     */
    public static String vertexHost(String region) {
        return switch (region) {
            case "global" -> "aiplatform.googleapis.com";
            case "us" -> "aiplatform.us.rep.googleapis.com";
            case "eu" -> "aiplatform.eu.rep.googleapis.com";
            default -> region + "-aiplatform.googleapis.com";
        };
    }

    private String vertexHost() {
        return vertexHost(vertexRegion);
    }

    public void setDebugLog(ApiTrafficLog debugLog) {
        this.debugLog = debugLog;
    }

    /** Create a MitmProxy using credentials from SpawnConfig and the Incus bridge gateway IP. */
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

    /** Resolve the Incus bridge gateway IP (e.g. "10.166.11.1"). */
    public static String resolveGatewayIp(IncusClient incus) {
        var result = incus.exec("network", "get", "incusbr0", "ipv4.address");
        var addr = result.assertSuccess("Failed to get bridge IP").stdout().strip();
        if (addr.contains("/")) {
            addr = addr.substring(0, addr.indexOf('/'));
        }
        return addr;
    }

    /** The set of domains intercepted by this proxy. */
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

    /** Clear bridge-level DNS overrides, restoring normal DNS resolution. */
    public static void clearBridgeDns(IncusClient incus) {
        incus.exec("network", "set", "incusbr0", "raw.dnsmasq", "");
    }

    public static String getDnsOverrides(IncusClient incus) {
        var result = incus.exec("network", "get", "incusbr0", "raw.dnsmasq");
        return result.success() ? result.stdout().strip() : "";
    }

    // --- Lifecycle ---

    /** Start the MITM proxy and health server. Blocks until {@link #stop()} is called. */
    public void start() throws Exception {
        stopLatch = new CountDownLatch(1);
        vertx = Vertx.vertx();

        var ca = CertificateAuthority.loadOrCreate();
        caFingerprint = ca.caFingerprint();

        // Build JKS keystore with per-domain certs (alias = domain name for SNI).
        // Also generate wildcard certs (*.domain) so subdomains resolved via
        // dnsmasq address= overrides get a valid cert (e.g. cdn01.quay.io).
        var keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        for (var domain : INTERCEPTED_DOMAIN_SET) {
            var certEntry = ca.generateDomainCert(domain);
            keyStore.setKeyEntry(
                    domain,
                    certEntry.key(),
                    "changeit".toCharArray(),
                    new X509Certificate[]{certEntry.cert(), ca.caCert()});
            var wildcardEntry = ca.generateDomainCert("*." + domain);
            keyStore.setKeyEntry(
                    "*." + domain,
                    wildcardEntry.key(),
                    "changeit".toCharArray(),
                    new X509Certificate[]{wildcardEntry.cert(), ca.caCert()});
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
                .setAlpnVersions(java.util.List.of(HttpVersion.HTTP_1_1));

        // Upstream HTTPS client with connection pooling.
        // GraalVM native images don't embed the build-time trust store reliably
        // when built via container, so point Vert.x at the system PEM CA bundle.
        var clientOptions = new HttpClientOptions()
                .setSsl(true)
                .setVerifyHost(true)
                .setTrustAll(false)
                .setMaxPoolSize(20)
                .setKeepAliveTimeout(30)
                .setConnectTimeout(30_000)
                .setReadIdleTimeout(300);
        var systemCaBundle = findSystemCaBundle();
        if (systemCaBundle != null) {
            clientOptions.setTrustOptions(new io.vertx.core.net.PemTrustOptions().addCertPath(systemCaBundle));
        }
        upstreamClient = vertx.createHttpClient(clientOptions);

        mitmServer = vertx.createHttpServer(serverOptions);
        mitmServer.requestHandler(this::routeRequest);
        mitmServer.listen()
                .toCompletionStage().toCompletableFuture().get();

        // Health check HTTP server (plain, no TLS)
        healthHttpServer = vertx.createHttpServer()
                .requestHandler(this::handleHealthCheck);
        healthHttpServer.listen(healthPort, bindAddress)
                .toCompletionStage().toCompletableFuture().get();

        System.out.println("MITM proxy listening on " + bindAddress + ":" + mitmPort);
        System.out.println("Health endpoint on " + bindAddress + ":" + healthPort + "/health");
        System.out.println("Intercepted domains: " + INTERCEPTED_DOMAIN_SET);
        System.out.println("Registry cache: " + registryCacheDir() +
                " (domains: " + REGISTRY_DOMAINS + ")");
        System.out.println("Maven cache: " + mavenCacheDir() +
                " (domains: " + MAVEN_DOMAINS + ")");
        System.out.println("Maven .m2 fallback: " +
                (Files.isDirectory(m2Repository()) ? m2Repository() : "not available"));
        if (useVertex) {
            System.out.println("Vertex AI mode: translating api.anthropic.com requests" +
                    " to " + vertexHost() +
                    " (region: " + vertexRegion + ", project: " + vertexProjectId + ")");
        }
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        stopLatch.await();
    }

    public void stop() {
        try {
            if (mitmServer != null) mitmServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (healthHttpServer != null) healthHttpServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (upstreamClient != null) upstreamClient.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Error during proxy shutdown: " + e.getMessage());
        } finally {
            if (stopLatch != null) stopLatch.countDown();
        }
    }

    // --- Request routing ---

    private void routeRequest(HttpServerRequest clientReq) {
        var domain = extractDomain(clientReq);
        if (domain == null) {
            sendError(clientReq.response(), 502, "Unknown domain");
            return;
        }

        if (REGISTRY_DOMAINS.contains(domain)) {
            handleRegistryRequest(clientReq, domain);
        } else if (MAVEN_DOMAINS.contains(domain)) {
            handleMavenRequest(clientReq, domain);
        } else if (INTERCEPTED_DOMAIN_SET.contains(domain)) {
            handleApiRequest(clientReq, domain);
        } else {
            // Subdomain of an intercepted domain (e.g. cdn01.quay.io) reached us
            // via dnsmasq wildcard — relay transparently without auth injection.
            relayRequest(clientReq, domain);
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

    // --- API requests (Anthropic, GitHub) ---

    private void handleApiRequest(HttpServerRequest clientReq, String domain) {
        clientReq.body().onSuccess(bodyBuffer -> {
            try {
                handleApiRequestWithBody(clientReq, domain, bodyBuffer);
            } catch (Exception e) {
                System.err.println("API request error: " + e.getMessage());
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

        if (useVertex && ANTHROPIC_DOMAINS.contains(domain) && path != null) {
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

    private void sendApiRequest(HttpServerRequest clientReq, RequestOptions requestOptions,
                                String upstreamHost, String domain,
                                byte[] bodyBytes, boolean isVertexRequest,
                                boolean bodyRewritten, boolean isRetry,
                                String originalDump, byte[] originalBody) {
        upstreamClient.request(requestOptions).onSuccess(upReq -> {
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

                vertx.executeBlocking(() -> {
                    if (Files.exists(cacheFile)) {
                        return Files.size(cacheFile);
                    }
                    return -1L;
                }).onSuccess(size -> {
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

        upstreamClient.request(options).onSuccess(upReq -> {
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

    /**
     * Follow a 3xx redirect from upstream, making a new request to the Location URL.
     * Uses the redirect target's host for both the connection and Host header.
     */
    private void fetchFromRedirect(HttpServerRequest clientReq, HttpClientResponse upResp,
                                   String digest, Path cacheFile, String ref) {
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
                .setURI(redirectPath)
                .setFollowRedirects(true);

        upstreamClient.request(redirectOptions).onSuccess(redReq -> {
            redReq.putHeader("Host", redirectHost);
            redReq.putHeader("Connection", "close");

            redReq.send().onSuccess(redResp -> {
                var statusCode = redResp.statusCode();
                if (statusCode == 200) {
                    teeStreamToCache(clientReq.response(), redResp, digest, cacheFile, ref);
                } else {
                    System.err.println("Redirect target returned " + statusCode + " for " + ref +
                            " (Location: " + location + ")");
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
        var te = upResp.getHeader("Transfer-Encoding");
        if (te != null && te.toLowerCase().contains("chunked") && clHeader == null) {
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
                System.err.println("Cache: SHA256 mismatch for " +
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

            vertx.executeBlocking(() -> {
                if (Files.exists(cacheFile)) {
                    return Files.size(cacheFile);
                }
                return -1L;
            }).onSuccess(size -> {
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

            var upstreamSha1 = fetchSha1FromUpstream(domain, path + ".sha1");
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
        var options = new RequestOptions()
                .setMethod(clientReq.method())
                .setHost(domain)
                .setPort(443)
                .setURI(clientReq.uri());

        upstreamClient.request(options).onSuccess(upReq -> {
            copyRequestHeaders(clientReq, upReq, domain);

            sendWithBody(clientReq, upReq).onSuccess(upResp -> {
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
     *   <li>Body: thinking.type "adaptive" mapped to "enabled" (Vertex doesn't support adaptive)</li>
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
            // Vertex only accepts thinking.type "enabled"/"disabled", not "adaptive"
            if (root.has("thinking") && root.get("thinking").isObject()) {
                var thinking = (ObjectNode) root.get("thinking");
                if ("adaptive".equals(thinking.path("type").asText())) {
                    thinking.put("type", "enabled");
                }
            }

            var rewrittenBytes = JSON.writeValueAsBytes(root);

            var endpoint = streaming ? ":streamRawPredict" : ":rawPredict";
            var vertexPath = "/v1/projects/" + vertexProjectId + "/locations/" + vertexRegion +
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
            // Vertex rawPredict doesn't use the anthropic-beta header — features are
            // available via anthropic_version directly
            upReq.headers().remove("x-api-key");
            upReq.headers().remove("anthropic-beta");
        } else if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (anthropicApiKey != null && !anthropicApiKey.isBlank()) {
                upReq.putHeader("x-api-key", anthropicApiKey);
            } else {
                // No real key — strip the container's placeholder so the request
                // looks the same as a no-auth request from the host
                upReq.headers().remove("x-api-key");
            }
        } else if (GITHUB_DOMAINS.contains(domain)) {
            if (ghToken != null && !ghToken.isBlank()) {
                if ("github.com".equals(domain)) {
                    // Git HTTP transport requires Basic auth (token as password)
                    var credentials = "x-access-token:" + ghToken;
                    var encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
                    upReq.putHeader("Authorization", "Basic " + encoded);
                } else {
                    // API and CDN domains accept Bearer tokens
                    upReq.putHeader("Authorization", "Bearer " + ghToken);
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
     * Fetch the .sha1 checksum for a Maven artifact from the upstream repository.
     * Returns the hex SHA1 string, or null if the checksum could not be retrieved.
     */
    private static String fetchSha1FromUpstream(String domain, String sha1Path) {
        try {
            var socket = (javax.net.ssl.SSLSocket) javax.net.ssl.SSLSocketFactory.getDefault()
                    .createSocket(domain, 443);
            socket.setSoTimeout(30_000);

            try (socket) {
                socket.startHandshake();
                var out = socket.getOutputStream();
                var in = socket.getInputStream();

                var request = "GET " + sha1Path + " HTTP/1.1\r\n"
                        + "Host: " + domain + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
                out.write(request.getBytes());
                out.flush();

                var response = HttpMessage.readResponse(in);
                if (response == null || response.statusCode() != 200) return null;

                var clHeader = response.header("Content-Length");
                byte[] body;
                if (clHeader != null) {
                    int len = Integer.parseInt(clHeader.trim());
                    body = new byte[len];
                    int offset = 0;
                    while (offset < len) {
                        int n = in.read(body, offset, len - offset);
                        if (n == -1) break;
                        offset += n;
                    }
                } else {
                    body = in.readAllBytes();
                }

                // Handle "hash" or "hash  filename" formats
                var hex = new String(body).trim().split("\\s+")[0].toLowerCase();
                if (hex.matches("[a-f0-9]{40}")) return hex;
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // --- Digest verification ---

    private static boolean verifyDigest(Path file, String expectedDigest) throws Exception {
        var parts = expectedDigest.split(":", 2);
        if (parts.length != 2 || !"sha256".equals(parts[0])) return false;

        var md = MessageDigest.getInstance("SHA-256");
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
                + ",\"caFingerprint\":\"" + caFingerprint + "\"}";
        req.response()
                .putHeader("Content-Type", "application/json")
                .end(body);
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
        var te = upResp.getHeader("Transfer-Encoding");
        if (te != null && te.toLowerCase().contains("chunked")) {
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
        if (!resp.ended() && !resp.closed()) {
            resp.setStatusCode(statusCode).end(message);
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
