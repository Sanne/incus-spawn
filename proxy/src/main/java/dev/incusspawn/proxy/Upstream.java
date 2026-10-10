package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The proxy's side towards upstream, shared by {@link MitmProxy} and its caches: the upstream
 * clients, async DNS, the per-domain unreachable backoff, the client's silence budget, the plain
 * relay, and the helpers that copy headers, pipe a response and answer with an error.
 * <p>
 * It holds no credentials and never injects any: whatever needs them stays in {@link MitmProxy}.
 */
class Upstream {

    /** The blocking lookup behind {@link #resolveHost}. */
    interface HostLookup {
        String lookupHost(String host) throws Exception;
    }

    private final Vertx vertx;
    private final HostLookup hostLookup;

    HttpClient upstreamClient;
    // Cache confirmations (HEADs, sidecars) get their own pool, so a cache hit never
    // queues behind large downloads on upstreamClient's
    HttpClient probeClient;
    HttpClient wsUpstreamClient;

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

    // Overridable for tests
    boolean upstreamTrustAll = false;
    // Overridable for tests: see probeClient's options
    int probeReadIdleSeconds = 15;
    // How long a client fetching into the cache may go without a byte from us. Below the MITM
    // server's idle timeout, which drops the client silently, with the upstream read still
    // pending: the upstream client's read-idle timeout (300s) outlasts it. Waiting for a
    // response head and every resume get only what is left of it.
    int clientSilenceBudgetSeconds = MitmProxy.MITM_IDLE_TIMEOUT_SECONDS - 10;
    // For the benchmark (bench/run.sh --load=maven, via ISX_BENCH_UPSTREAM) and tests:
    // send a host's upstream connections to a local stub. Host header and SNI still
    // name the real host. Set before start().
    private final Map<String, SocketAddress> upstreamOverrides = new ConcurrentHashMap<>();
    private volatile String extraUpstreamTrustPem;

    void overrideUpstream(String host, String ip, int port) {
        upstreamOverrides.put(host, SocketAddress.inetSocketAddress(port, ip));
    }

    SocketAddress upstreamOverride(String host) {
        return upstreamOverrides.get(host);
    }

    /** Trust a stub's certificate for upstream connections, alongside the system CAs. Set before start(). */
    void trustUpstreamCertificate(String pemPath) {
        extraUpstreamTrustPem = pemPath;
    }

    void overrideDns(String host, String ip) {
        dns.put(host, DnsEntry.resolved(ip));
    }

    void clearUnreachable() {
        unreachableSince.clear();
    }

    Upstream(Vertx vertx, HostLookup hostLookup) {
        this.vertx = vertx;
        this.hostLookup = hostLookup;
    }

    /** Creates the upstream clients; {@link MitmProxy#start} calls it before it listens. */
    void createClients() {
        // Upstream HTTPS client with connection pooling.
        // GraalVM native images don't embed the build-time trust store reliably
        // when built via container, so point Vert.x at the system PEM CA bundle.
        var clientOptions = new HttpClientOptions()
                .setSsl(true)
                .setVerifyHost(!upstreamTrustAll)
                .setTrustAll(upstreamTrustAll)
                .setMaxPoolSize(20)
                .setKeepAliveTimeout(30)
                .setConnectTimeout(UPSTREAM_CONNECT_TIMEOUT_MILLIS)
                // Outlasts the MITM server's idle timeout: a stalled relay or wait for the cache is
                // ended by clientSilenceBudgetSeconds instead (RelayWatchdog, CachingDownload). Not
                // lowered for everything: an upload gets no bytes back for as long as it sends.
                .setReadIdleTimeout(300);
        var upstreamTrust = upstreamTrust();
        if (upstreamTrust != null) clientOptions.setTrustOptions(upstreamTrust);
        upstreamClient = vertx.createHttpClient(clientOptions);

        // Every cache hit waits on one of these: h2 (via ALPN) shares a connection.
        // The read-idle timeout only fires while an exchange is waiting, and being
        // shorter than probeOptions()' it closes a silently dead connection (whose
        // exchange is then retried on a new one) before any request times out on it.
        // A few h2 connections per host, so a burst to a host that falls back to
        // HTTP/1.1, or does not answer at all, is not handled one connect at a time.
        var probeOptions = new HttpClientOptions()
                .setSsl(true)
                .setVerifyHost(!upstreamTrustAll)
                .setTrustAll(upstreamTrustAll)
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setUseAlpn(true)
                .setHttp2KeepAliveTimeout(PROBE_KEEP_ALIVE_SECONDS)
                .setHttp2MaxPoolSize(4)
                .setMaxPoolSize(32)
                .setKeepAliveTimeout(PROBE_KEEP_ALIVE_SECONDS)
                .setConnectTimeout(10_000)
                .setReadIdleTimeout(probeReadIdleSeconds);
        if (upstreamTrust != null) probeOptions.setTrustOptions(upstreamTrust);
        probeClient = vertx.createHttpClient(probeOptions);

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
        if (upstreamTrust != null) wsClientOptions.setTrustOptions(upstreamTrust);
        wsUpstreamClient = vertx.createHttpClient(wsClientOptions);
    }

    /** Closes the upstream clients, as {@link MitmProxy#stop} does. */
    void closeClients() {
        try {
            if (upstreamClient != null) upstreamClient.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        try {
            if (probeClient != null) probeClient.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        try {
            if (wsUpstreamClient != null) wsUpstreamClient.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    Future<HttpClientRequest> requestWithAsyncDns(RequestOptions options) {
        return requestWithAsyncDns(upstreamClient, options);
    }

    Future<HttpClientRequest> requestWithAsyncDns(HttpClient client, RequestOptions options) {
        var host = options.getHost();
        // Cut to fit what a waiting client has left, a connect timeout says nothing about the domain
        var cutShort = options.getConnectTimeout() > 0 && options.getConnectTimeout() < UPSTREAM_CONNECT_TIMEOUT_MILLIS;
        var override = upstreamOverrides.get(host);
        Future<HttpClientRequest> connected;
        if (override != null) {
            // Host header and SNI still name the real host; only the connection moves
            options.setServer(override);
            connected = client.request(options);
        } else {
            connected = resolveHost(host).compose(ip -> {
                options.setServer(SocketAddress.inetSocketAddress(options.getPort(), ip));
                return client.request(options);
            });
        }
        if (Revalidation.forDomain(host) == null) return connected;
        // Every request to a caching domain, whatever its purpose, keeps its backoff current: a
        // failed connect starts it, and only a response ends it, since a request on a pooled
        // connection is handed out without any network I/O.
        return connected.andThen(ar -> {
            if (ar.succeeded()) {
                ar.result().response().onSuccess(resp -> unreachableSince.remove(host));
            } else if (cutShort && (ar.cause() instanceof java.util.concurrent.TimeoutException
                    || ar.cause() instanceof io.netty.channel.ConnectTimeoutException)) {
                return;
            } else if (unreachableSince.put(host, System.nanoTime()) == null) {
                ProxyLog.warn("Cannot reach " + host + " (" + ar.cause().getMessage() +
                        "); serving cached copies unconfirmed for " + UNREACHABLE_BACKOFF_SECONDS + "s");
            }
        });
    }

    // JVM resolver is blocking (Quarkus use-async-dns=false); resolve on a worker thread.
    // compute() only claims the host with an inflight entry, so concurrent callers share one
    // lookup. The lookup itself starts after compute() returns: one that finishes before its
    // callback is attached runs that callback synchronously, and the callback's write to
    // this map from inside compute() throws "Recursive update".
    Future<String> resolveHost(String host) {
        var claimed = new AtomicReference<Promise<String>>();
        var entry = dns.compute(host, (h, existing) -> {
            if (existing != null && (existing.isValid() || existing.isResolving()))
                return existing;
            var promise = Promise.<String>promise();
            claimed.set(promise);
            return DnsEntry.resolving(promise.future());
        });
        var promise = claimed.get();
        if (promise != null) {
            vertx.<String>executeBlocking(() -> hostLookup.lookupHost(host), false)
                    .onComplete(ar -> {
                        // Update the cache before waking waiters, so none of them re-resolves.
                        // Only replace our own inflight entry: one written meanwhile (overrideDns)
                        // is newer than this lookup and must not be overwritten or dropped.
                        if (ar.succeeded()) {
                            dns.replace(host, entry, DnsEntry.resolved(ar.result()));
                        } else {
                            dns.remove(host, entry);
                        }
                        promise.handle(ar);
                    });
        }
        return entry.isValid()
                ? Future.succeededFuture(entry.ip())
                : entry.inflight();
    }

    /** What is left of the client's silence budget, counted from {@code since}. */
    long silenceLeftMillis(long since) {
        return TimeUnit.SECONDS.toMillis(clientSilenceBudgetSeconds)
                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    /** {@link #silenceLeftMillis} as a timeout, which must be positive. */
    long timeoutLeftMillis(long since) {
        return Math.max(1, silenceLeftMillis(since));
    }

    static final int UPSTREAM_CONNECT_TIMEOUT_MILLIS = 30_000;

    static final long UNREACHABLE_BACKOFF_SECONDS = 30;

    // How long probeClient keeps an idle connection; DESIGN.md says why not longer
    private static final int PROBE_KEEP_ALIVE_SECONDS = 60;

    // Per caching domain, when a connection to it last failed (requestWithAsyncDns
    // keeps this). While recent, a request with a cached copy to fall back on uses it
    // at once instead of each waiting out a connect timeout.
    final Map<String, Long> unreachableSince = new ConcurrentHashMap<>();

    // --- Generic relay (non-cacheable) ---

    /** Relay a non-cacheable request transparently to upstream. */
    void relayRequest(HttpServerRequest clientReq, String domain) {
        relayRequest(clientReq, domain, null);
    }

    /**
     * Relay a request to upstream with an optional response callback.
     * When {@code responseCallback} is non-null it fires after the upstream
     * response headers arrive but before the body is piped to the client.
     * Its body is claimed here ({@link #claimBody}), so a request with one is relayed before anything
     * waits; the caching handlers, which wait, only ever get requests without one.
     */
    void relayRequest(HttpServerRequest clientReq, String domain,
                               java.util.function.Consumer<HttpClientResponse> responseCallback) {
        var body = claimBody(clientReq);
        var options = new RequestOptions()
                .setMethod(clientReq.method())
                .setHost(domain)
                .setPort(443)
                .setURI(clientReq.uri());
        var watchdog = new RelayWatchdog(clientReq, domain, body != null);
        // From here on, waiting is upstream's time: a lookup or connect that stalls is cut (#929)
        if (body != null) body.onSuccess(read -> watchdog.requestRead());

        requestWithAsyncDns(options).onSuccess(upReq -> {
            if (watchdog.cut) {
                upReq.reset();
                return;
            }
            watchdog.upReq = upReq;
            copyRequestHeaders(clientReq, upReq, domain);

            sendWithBody(body, upReq).onSuccess(upResp -> {
                if (watchdog.cut) return;
                if (responseCallback != null) {
                    responseCallback.accept(upResp);
                }
                var clientResp = clientReq.response();
                clientResp.setStatusCode(upResp.statusCode());
                clientResp.setStatusMessage(upResp.statusMessage());
                copyResponseHeaders(upResp, clientResp);
                watchdog.upResp = upResp;
                pipeResponse(upResp, clientResp, watchdog);
            }).onFailure(err -> {
                if (watchdog.cut) return;
                if (body != null && body.failed()) {
                    // Never sent: give its connection back to the pool rather than hold it for good
                    upReq.exceptionHandler(ignored -> {}).reset();
                    System.err.println("Relay (" + domain + "): request body not received from the client: "
                            + err.getMessage());
                    sendError(clientReq.response(), 502, "Request body not received");
                    return;
                }
                System.err.println("Relay upstream error (" + domain + "): " + err.getMessage());
                sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(err -> {
            if (watchdog.cut) return;
            System.err.println("Relay connect error (" + domain + "): " + err.getMessage());
            sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    /**
     * Ends a relay whose client has gone {@link #clientSilenceBudgetSeconds} without a byte either
     * way, with a line and a 502, or a reset once the head was sent (#929). Just short of the MITM
     * server's idle timeout, which would drop the client silently with the upstream read still
     * pending (the upstream client's read-idle timeout outlasts it), so it cuts nothing that
     * would have lived: an upload counts as the client's bytes until its body is read. Not while
     * upstream is paused because the client holds the response back.
     */
    final class RelayWatchdog {
        private final HttpServerRequest clientReq;
        private final String domain;
        HttpClientRequest upReq;
        HttpClientResponse upResp;
        boolean cut;
        // Until the request body is read, and while the client holds the response back
        private boolean waitingOnClient = true;
        private boolean over;
        private long clientActive = System.nanoTime();
        // Vert.x numbers timers from 0: cancelling 0 before one is set would cancel another's
        private long timer = -1;

        RelayWatchdog(HttpServerRequest clientReq, String domain, boolean hasBody) {
            this.clientReq = clientReq;
            this.domain = domain;
            // With no body to wait for, the DNS lookup and connect are already upstream's time
            waitingOnClient = hasBody;
            var clientResp = clientReq.response();
            clientResp.endHandler(v -> stop());
            clientResp.closeHandler(v -> stop());
            // It may have left during the lookups before the relay, with no handler yet to tell
            if (clientResp.closed()) stop();
            check();
        }

        void requestRead() {
            if (waitingOnClient) waitingOnClient(false);
        }

        void touch() {
            clientActive = System.nanoTime();
        }

        void waitingOnClient(boolean waiting) {
            waitingOnClient = waiting;
            touch();
        }

        private void stop() {
            over = true;
            vertx.cancelTimer(timer);
        }

        private void check() {
            if (over) return;
            // Not a stall
            if (waitingOnClient) touch();
            var left = silenceLeftMillis(clientActive);
            if (left > 0) {
                timer = vertx.setTimer(left, id -> check());
                return;
            }
            stop();
            cut = true;
            var clientResp = clientReq.response();
            ProxyLog.warn("Relay cut (" + domain + clientReq.path() + "): "
                    + (clientResp.headWritten() ? "no data from upstream" : "no answer from upstream")
                    + " for the client in " + clientSilenceBudgetSeconds + "s");
            if (upResp != null) upResp.handler(null).endHandler(null).exceptionHandler(ignored -> {});
            sendError(clientResp, 502, "Upstream timed out");
            if (upReq != null) upReq.reset();
        }
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

    /** The system CA bundle, plus a benchmark stub's certificate when one is configured. */
    private io.vertx.core.net.PemTrustOptions upstreamTrust() {
        var systemCaBundle = findSystemCaBundle();
        if (systemCaBundle == null && extraUpstreamTrustPem == null) return null;
        var trust = new io.vertx.core.net.PemTrustOptions();
        if (systemCaBundle != null) trust.addCertPath(systemCaBundle);
        if (extraUpstreamTrustPem != null) trust.addCertPath(extraUpstreamTrustPem);
        return trust;
    }

    // --- Vert.x helpers ---

    void copyRequestHeaders(HttpServerRequest clientReq, HttpClientRequest upReq,
                                    String domain) {
        upReq.headers().setAll(clientReq.headers());
        upReq.headers().remove("Host");
        upReq.headers().remove("Connection");
        upReq.headers().remove("Transfer-Encoding");
        upReq.putHeader("Host", domain);
    }

    void copyResponseHeaders(HttpClientResponse upResp, HttpServerResponse clientResp) {
        clientResp.headers().setAll(upResp.headers());
        clientResp.headers().remove("Connection");
        clientResp.headers().remove("Transfer-Encoding");
    }

    /** Sends the client's request body, if any, once it has all arrived. */
    private static Future<HttpClientResponse> sendWithBody(Future<Buffer> body, HttpClientRequest upReq) {
        return body != null ? body.compose(upReq::send) : upReq.send();
    }

    /**
     * Starts reading the request's body, if it has one, for a relay to send on. Called as the request
     * is routed, before anything waits: Vert.x drops a body that arrives with nothing reading it, and
     * then refuses to read the request at all, so a body claimed only once the upstream connection was
     * ready was gone whenever it beat the connect, and the relay hung (#1164). One claimed too late
     * anyway fails here, for the relay to answer, rather than throwing where nothing would.
     */
    private static Future<Buffer> claimBody(HttpServerRequest clientReq) {
        if (!hasBody(clientReq)) return null;
        try {
            return clientReq.body();
        } catch (IllegalStateException e) {
            return Future.failedFuture(e);
        }
    }

    static boolean hasBody(HttpServerRequest clientReq) {
        var cl = clientReq.getHeader("Content-Length");
        var te = clientReq.getHeader("Transfer-Encoding");
        return (cl != null && !"0".equals(cl))
                || (te != null && te.toLowerCase().contains("chunked"));
    }

    void pipeResponse(HttpClientResponse upResp, HttpServerResponse clientResp) {
        pipeResponse(upResp, clientResp, null, null);
    }

    void pipeResponse(HttpClientResponse upResp, HttpServerResponse clientResp, RelayWatchdog watchdog) {
        pipeResponse(upResp, clientResp, watchdog, null);
    }

    /** As above, showing each chunk to {@code tap} too, when there is one. */
    void pipeResponse(HttpClientResponse upResp, HttpServerResponse clientResp, RelayWatchdog watchdog,
                              java.util.function.Consumer<Buffer> tap) {
        int status = clientResp.getStatusCode();
        if (upResp.getHeader("Content-Length") == null
                && status != 204 && status != 304 && (status < 100 || status >= 200)) {
            clientResp.setChunked(true);
        }
        upResp.handler(chunk -> {
            if (tap != null) tap.accept(chunk);
            clientResp.write(chunk);
            if (watchdog != null) watchdog.touch();
            if (clientResp.writeQueueFull()) {
                upResp.pause();
                if (watchdog != null) watchdog.waitingOnClient(true);
                clientResp.drainHandler(v -> {
                    if (watchdog != null) watchdog.waitingOnClient(false);
                    upResp.resume();
                });
            }
        });
        upResp.endHandler(v -> clientResp.end());
        upResp.exceptionHandler(err -> {
            ProxyLog.warn("Relay stream error: " + err.getMessage());
            sendError(clientResp, 502, "Upstream stream error");
        });
    }

    void sendError(HttpServerResponse resp, int statusCode, String message) {
        try {
            if (!resp.ended() && !resp.closed()) {
                if (resp.headWritten()) {
                    resp.reset();
                } else {
                    // Whatever was set for the answer that failed (copied from upstream, or an
                    // artifact's type and checksum) would describe the error as that answer
                    resp.headers().clear();
                    resp.setStatusCode(statusCode).end(message);
                }
            }
        } catch (Exception e) {
            ProxyLog.warn("Failed to send error response: " + e.getMessage());
        }
    }
}
