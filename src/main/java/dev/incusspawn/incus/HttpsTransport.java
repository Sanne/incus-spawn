package dev.incusspawn.incus;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.incusspawn.Environment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import javax.net.ssl.SSLContext;

/**
 * HTTPS transport for the Incus REST API.
 * Used on macOS (or any platform) when connecting to a remote Incus daemon over HTTPS.
 * Uses {@link java.net.http.HttpClient} for HTTP connection pooling and WebSocket support.
 * No extra Maven dependencies — all APIs are in the JDK.
 */
class HttpsTransport implements IncusTransport {

    private final HttpClient httpClient;
    private final String baseUrl;   // e.g. "https://192.168.64.10:8443"
    private final String wsBaseUrl; // e.g. "wss://192.168.64.10:8443"

    HttpsTransport(String baseUrl, SSLContext sslContext) {
        this.baseUrl   = baseUrl.replaceAll("/$", "");
        this.wsBaseUrl = this.baseUrl.replace("https://", "wss://");
        this.httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Create from the Incus client config. Reads the default remote if it uses HTTPS.
     * Returns null if the default remote is not HTTPS or the config cannot be loaded.
     */
    static HttpsTransport fromClientConfig() {
        for (var configPath : Environment.incusConfigCandidates()) {
            if (!Files.exists(configPath)) continue;
            try {
                var yaml = new YAMLMapper();
                var root = yaml.readTree(configPath.toFile());
                var defaultRemote = root.path("default-remote").asText("local");
                var remote = root.path("remotes").path(defaultRemote);
                if (remote.isMissingNode()) continue;
                var addr = remote.path("addr").asText("");
                if (!addr.startsWith("https://")) continue;
                // Load client certs from same directory as config
                var certDir = configPath.getParent();
                var sslContext = buildSslContext(certDir, addr);
                if (sslContext == null) continue;
                return new HttpsTransport(addr, sslContext);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static SSLContext buildSslContext(Path certDir, String serverAddr) {
        try {
            var clientCertPath = certDir.resolve("client.crt");
            var clientKeyPath  = certDir.resolve("client.key");
            if (!Files.exists(clientCertPath) || !Files.exists(clientKeyPath)) return null;

            // Parse client cert
            var certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.Certificate clientCert;
            try (var is = Files.newInputStream(clientCertPath)) {
                clientCert = certFactory.generateCertificate(is);
            }

            // Parse private key (PKCS8 DER, strip PEM headers)
            var keyPem = Files.readString(clientKeyPath);
            var keyB64 = keyPem
                    .replaceAll("-----BEGIN[^-]+-----", "")
                    .replaceAll("-----END[^-]+-----", "")
                    .replaceAll("\\s+", "");
            var keyBytes = java.util.Base64.getDecoder().decode(keyB64);
            var keySpec  = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            java.security.PrivateKey privateKey;
            try {
                privateKey = java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
            } catch (Exception e) {
                privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            }

            // Build KeyStore with client identity
            var ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("incus-client", privateKey, new char[0],
                    new java.security.cert.Certificate[]{clientCert});
            var kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);

            // Trust manager: trust server certs in certDir/servercerts/
            var tm = buildTrustManager(certDir);

            var ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tm != null ? new javax.net.ssl.TrustManager[]{tm} : null, null);
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }

    private static javax.net.ssl.TrustManager buildTrustManager(Path certDir) {
        var serverCertsDir = certDir.resolve("servercerts");
        if (!Files.exists(serverCertsDir)) {
            return buildPermissiveTrustManager();
        }
        try {
            var certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
            var ts = java.security.KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            try (var stream = Files.list(serverCertsDir)) {
                var certs = stream.filter(p -> p.toString().endsWith(".crt")).toList();
                if (certs.isEmpty()) return buildPermissiveTrustManager();
                int i = 0;
                for (var certFile : certs) {
                    try (var is = Files.newInputStream(certFile)) {
                        ts.setCertificateEntry("server-" + i++, certFactory.generateCertificate(is));
                    }
                }
            }
            var tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            var tms = tmf.getTrustManagers();
            if (tms.length == 0 || !(tms[0] instanceof javax.net.ssl.X509TrustManager x509tm)) {
                throw new IllegalStateException("TrustManagerFactory did not produce an X509TrustManager");
            }
            return skipHostnameVerification(x509tm);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Incus server certificates from " + serverCertsDir, e);
        }
    }

    /** Permissive TrustManager — accepts any server cert. Logs a warning on first use. */
    private static javax.net.ssl.TrustManager buildPermissiveTrustManager() {
        System.err.println("Warning: no Incus server certificates found — TLS verification disabled for remote connection");
        return skipHostnameVerification(new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        });
    }

    /**
     * Wraps a trust manager in an X509ExtendedTrustManager that validates
     * the certificate chain but skips hostname verification. Incus self-signed
     * certs don't include the VM's DHCP IP in their SANs, so hostname
     * verification would always fail.
     *
     * When the SSLEngine sees an X509ExtendedTrustManager, it delegates
     * hostname checking entirely to it rather than doing its own check.
     * By calling the 2-arg checkServerTrusted (without engine/socket),
     * we get chain validation without hostname matching — scoped to this
     * SSLContext only, no global system properties needed.
     */
    private static javax.net.ssl.X509ExtendedTrustManager skipHostnameVerification(
            javax.net.ssl.X509TrustManager delegate) {
        return new javax.net.ssl.X509ExtendedTrustManager() {
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                    String authType, javax.net.ssl.SSLEngine engine)
                    throws java.security.cert.CertificateException {
                delegate.checkServerTrusted(chain, authType);
            }
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                    String authType, java.net.Socket socket)
                    throws java.security.cert.CertificateException {
                delegate.checkServerTrusted(chain, authType);
            }
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                    String authType) throws java.security.cert.CertificateException {
                delegate.checkServerTrusted(chain, authType);
            }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                    String authType, javax.net.ssl.SSLEngine engine)
                    throws java.security.cert.CertificateException {
                delegate.checkClientTrusted(chain, authType);
            }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                    String authType, java.net.Socket socket)
                    throws java.security.cert.CertificateException {
                delegate.checkClientTrusted(chain, authType);
            }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                    String authType) throws java.security.cert.CertificateException {
                delegate.checkClientTrusted(chain, authType);
            }
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return delegate.getAcceptedIssuers();
            }
        };
    }

    @Override
    public RawResponse request(String method, String path,
                               String contentType, Map<String, String> extraHeaders,
                               byte[] body) throws IOException {
        var bodyPublisher = body.length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, bodyPublisher)
                .header("Accept", "application/json");
        if (body.length > 0 && contentType != null)
            builder.header("Content-Type", contentType);
        for (var e : extraHeaders.entrySet())
            builder.header(e.getKey(), e.getValue());
        try {
            var resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new RawResponse(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    @Override
    public WsConnection openWebSocket(String wsPath) throws IOException {
        var wsUri = URI.create(wsBaseUrl + wsPath);
        var conn  = new HttpsWsConnection();
        try {
            var ws = httpClient.newWebSocketBuilder()
                    .buildAsync(wsUri, conn.listener())
                    .join();
            conn.setWebSocket(ws);
        } catch (Exception e) {
            throw new IOException("WebSocket connection failed: " + wsUri, e);
        }
        return conn;
    }

    /**
     * Bridges Java's async {@link WebSocket.Listener} to our synchronous {@link WsConnection}
     * interface. Payloads are queued and consumed by {@link #readPayload()}.
     */
    static class HttpsWsConnection implements WsConnection {
        // Sentinel checked by reference identity (==) to signal connection close.
        private static final byte[] CLOSE_SENTINEL = new byte[0];

        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream partial    = new ByteArrayOutputStream();
        private volatile WebSocket ws;

        void setWebSocket(WebSocket ws) {
            this.ws = ws;
            ws.request(1);
        }

        WebSocket.Listener listener() {
            return new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                    var bytes = new byte[data.remaining()];
                    data.get(bytes);
                    partial.write(bytes, 0, bytes.length);
                    if (last) { queue.offer(partial.toByteArray()); partial.reset(); }
                    ws.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    var bytes = data.toString().getBytes(StandardCharsets.UTF_8);
                    partial.write(bytes, 0, bytes.length);
                    if (last) { queue.offer(partial.toByteArray()); partial.reset(); }
                    ws.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    queue.offer(CLOSE_SENTINEL);
                    return null;
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    queue.offer(CLOSE_SENTINEL);
                }
            };
        }

        @Override
        public byte[] readPayload() throws IOException {
            try {
                var payload = queue.take();
                return payload == CLOSE_SENTINEL ? null : payload;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void sendData(byte[] data, int offset, int length) throws IOException {
            try {
                ws.sendBinary(ByteBuffer.wrap(data, offset, length), true).join();
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw new IOException(e);
            }
        }

        @Override
        public void sendClose() throws IOException {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw new IOException(e);
            }
        }

        @Override
        public void close() {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join(); } catch (Exception ignored) {}
            ws.abort();
            queue.offer(CLOSE_SENTINEL);
        }
    }
}
