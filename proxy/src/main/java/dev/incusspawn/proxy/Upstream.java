package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

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
    // Overridable for tests: see sendProbe()
    int probeReadIdleSeconds = 15;
    // Per probeClient connection, when an exchange on it last read something (sendProbe())
    private final Map<HttpConnection, Long> probeLastRead = Collections.synchronizedMap(new WeakHashMap<>());
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
        // No read-idle timeout: Netty's ticks from the connection's last read, so it would
        // cut a pooled connection just after a request went out on it; sendProbe() times
        // the dead-connection check from the request instead.
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
                .setConnectTimeout(10_000);
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

    /**
     * Sends a request made on {@link #probeClient} and hands its response to {@code exchange}, which
     * calls the {@code Runnable} it is given on every read of the response's body.
     * <p>
     * Closes the connection once nothing has been read on it for {@link #probeReadIdleSeconds},
     * counted from the request (#1007): shorter than the exchange's own timeout, this evicts a
     * silently dead connection, and fails what waits on it as broken rather than timed out, before
     * any exchange times out on it. A timed-out exchange would reset only its own stream.
     */
    <T> Future<T> sendProbe(HttpClientRequest req,
                            BiFunction<HttpClientResponse, Runnable, Future<T>> exchange) {
        var connection = req.connection();
        long sent = System.nanoTime();
        Runnable read = () -> probeLastRead.put(connection, System.nanoTime());
        // Over when the response has ended, not when the exchange has: it may go on to follow a redirect
        var over = Promise.<Void>promise();
        var result = req.send().compose(resp -> {
            read.run();
            resp.end().onComplete(r -> over.tryComplete());
            return exchange.apply(resp, read);
        });
        result.onComplete(r -> over.tryComplete());
        long idleNanos = TimeUnit.SECONDS.toNanos(probeReadIdleSeconds);
        closeWhenReadIdle(connection, sent, over.future(), idleNanos, idleNanos);
        return result;
    }

    private void closeWhenReadIdle(HttpConnection connection, long sent, Future<Void> over, long idleNanos, long delayNanos) {
        vertx.setTimer(Math.max(1, TimeUnit.NANOSECONDS.toMillis(delayNanos)), t -> {
            if (over.isComplete()) return;
            long idle = System.nanoTime() - Math.max(sent, probeLastRead.getOrDefault(connection, sent));
            if (idle >= idleNanos) connection.close();
            else closeWhenReadIdle(connection, sent, over, idleNanos, idleNanos - idle);
        });
    }

    /**
     * Opens an upstream WebSocket, to its override when its host has one. Vert.x 4 dials a
     * WebSocket to its host and port and ignores setServer(), so the override is dialled by
     * address: the Host header still names the real host, but there is no SNI, and the stub's
     * certificate must cover the address. Without this, the socket went to the real host.
     */
    Future<io.vertx.core.http.WebSocket> connectWebSocket(io.vertx.core.http.WebSocketConnectOptions options) {
        var host = options.getHost();
        var override = upstreamOverrides.get(host);
        if (override != null) {
            options.putHeader("Host", host).setHost(override.host()).setPort(override.port());
        }
        return wsUpstreamClient.webSocket(options);
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
        relayRequest(clientReq, domain, plainHooks(clientReq, domain, null));
    }

    /**
     * Relay a request to upstream with an optional response callback.
     * When {@code responseCallback} is non-null it fires after the upstream
     * response headers arrive but before the body is piped to the client.
     * A body is paused here and streamed upstream once the connection is ready ({@link #pipeRequest}),
     * so an upload is never held whole in memory (#1175); a request with one is relayed before
     * anything waits, and the caching handlers, which wait, only ever get requests without one.
     */
    void relayRequest(HttpServerRequest clientReq, String domain,
                               java.util.function.Consumer<HttpClientResponse> responseCallback) {
        relayRequest(clientReq, domain, plainHooks(clientReq, domain, responseCallback));
    }

    /** A plain relay's: the client's head as it is, and no taps. */
    private RelayHooks plainHooks(HttpServerRequest clientReq, String domain,
                                  java.util.function.Consumer<HttpClientResponse> responseCallback) {
        return new RelayHooks(upReq -> {
            copyRequestHeaders(clientReq, upReq, domain);
            return Future.succeededFuture(true);
        }, responseCallback, null, null);
    }

    /**
     * What a relay leaves to its caller. {@code prepare} sets the upstream request's head, before any
     * of it leaves, and completes false when it has answered the client itself. The taps see each
     * chunk of the request and of the response as it passes; any of the last three may be null.
     */
    record RelayHooks(java.util.function.Function<HttpClientRequest, Future<Boolean>> prepare,
                      java.util.function.Consumer<HttpClientResponse> responseCallback,
                      java.util.function.Consumer<Buffer> requestTap,
                      java.util.function.Consumer<Buffer> responseTap) {}

    /** As above, with the head and the taps left to {@code hooks}: the credential-injecting relay (#1315). */
    void relayRequest(HttpServerRequest clientReq, String domain, RelayHooks hooks) {
        var streaming = hasBody(clientReq);
        // At once, before anything waits: Vert.x queues a paused body, but drops one that arrives
        // with nothing reading it, and then refuses to read the request at all (#1164)
        if (streaming) clientReq.pause();
        var options = new RequestOptions()
                .setMethod(clientReq.method())
                .setHost(domain)
                .setPort(443)
                .setURI(clientReq.uri());
        var watchdog = new RelayWatchdog(clientReq, domain, streaming, System.nanoTime(), null);

        requestWithAsyncDns(options).onSuccess(upReq -> {
            // A client that left while its body was paused has no handler yet to say so
            if (watchdog.gone()) {
                upReq.exceptionHandler(ignored -> {}).reset();
                return;
            }
            watchdog.upReq = upReq;
            Future<Boolean> head;
            try {
                head = hooks.prepare().apply(upReq);
            } catch (RuntimeException e) {
                // A header Vert.x refuses (a credential with a line break): fail now, not at the budget
                head = Future.failedFuture(e);
            }
            head.onComplete(prepared -> {
                if (prepared.succeeded() && prepared.result() && !watchdog.gone()) {
                    send(clientReq, domain, upReq, streaming, watchdog, hooks);
                    return;
                }
                // Nothing of the request has left: let the connection go back to the pool
                upReq.exceptionHandler(ignored -> {}).reset();
                if (prepared.failed() && !watchdog.gone()) {
                    System.err.println("Relay error (" + domain + "): " + prepared.cause().getMessage());
                    sendError(clientReq.response(), 502, "Proxy error");
                }
            });
        }).onFailure(err -> {
            if (watchdog.cut) return;
            System.err.println("Relay connect error (" + domain + "): " + err.getMessage());
            sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    /** Sends the request whose head {@code hooks} has set, and relays its answer. */
    private void send(HttpServerRequest clientReq, String domain, HttpClientRequest upReq, boolean streaming,
                      RelayWatchdog watchdog, RelayHooks hooks) {
        upReq.response().onSuccess(upResp -> {
            if (watchdog.cut) return;
            if (hooks.responseCallback() != null) {
                hooks.responseCallback().accept(upResp);
            }
            var clientResp = clientReq.response();
            copyResponseHead(upResp, clientResp);
            // Upstream answered before the upload ended: the rest of it is never read (RelayWatchdog)
            if (watchdog.uploadUnread()) clientResp.putHeader("Connection", "close");
            watchdog.upResp = upResp;
            pipeResponse(upResp, clientResp, watchdog, hooks.responseTap());
        }).onFailure(err -> {
            // A client that left mid-upload resets the request itself
            if (watchdog.gone()) return;
            System.err.println("Relay upstream error (" + domain + "): " + err.getMessage());
            sendError(clientReq.response(), 502, "Upstream error");
        });
        if (streaming) pipeRequest(clientReq, upReq, watchdog, hooks.requestTap());
        else upReq.end();
    }

    /**
     * Streams the client's paused request body to upstream as it arrives, pausing the client while
     * upstream's write queue is full. While the client sends, the relay is never cut; while upstream
     * holds the body back, that is upstream's time. A client that leaves mid-upload resets the
     * request, which gives its connection back to the pool instead of holding it for good.
     */
    private static void pipeRequest(HttpServerRequest clientReq, HttpClientRequest upReq, RelayWatchdog watchdog,
                                    java.util.function.Consumer<Buffer> tap) {
        if (clientReq.getHeader("Content-Length") == null) upReq.setChunked(true);
        // An upstream that drops mid-upload fails the response, which logs it and answers: without a
        // handler of its own, Vert.x would log the same failure again, unprefixed
        upReq.exceptionHandler(ignored -> {});
        io.vertx.core.Handler<Buffer> pump = chunk -> {
            if (tap != null) tap.accept(chunk);
            upReq.write(chunk);
            watchdog.touch();
            if (upReq.writeQueueFull()) {
                clientReq.pause();
                watchdog.uploading(false);
                upReq.drainHandler(v -> {
                    upReq.drainHandler(null);
                    watchdog.uploading(true);
                    clientReq.resume();
                });
            }
        };
        if ("100-continue".equalsIgnoreCase(clientReq.getHeader("Expect"))) {
            // The client sends no body until it is told to: send upstream the head now, and relay its
            // 100. Waiting for that is upstream's time; the client's starts with its first byte
            upReq.continueHandler(v -> clientReq.response().writeContinue());
            upReq.sendHead();
            clientReq.handler(first -> {
                watchdog.uploading(true);
                clientReq.handler(pump);
                pump.handle(first);
            });
        } else {
            watchdog.uploading(true);
            clientReq.handler(pump);
        }
        clientReq.endHandler(v -> {
            upReq.end();
            watchdog.uploading(false);
        });
        clientReq.exceptionHandler(err -> upReq.exceptionHandler(ignored -> {}).reset());
        clientReq.resume();
    }

    /**
     * Ends a relay whose client has gone {@link #clientSilenceBudgetSeconds} without a byte either
     * way, with a line and a 502, or a reset once the head was sent (#929). Just short of the MITM
     * server's idle timeout, which would drop the client silently with the upstream read still
     * pending (the upstream client's read-idle timeout outlasts it), so it cuts nothing that
     * would have lived: an upload counts as the client's bytes while it streams. Not while
     * upstream is paused because the client holds the response back. An early answer streams while
     * the upload still does, so each direction keeps its own wait: neither ends the other's.
     * <p>
     * A response that ends, an error included, before the upload it answers was read closes the
     * client's connection: reading the rest only to discard it could be gigabytes (#1175), and the
     * unread body would otherwise sit on a keep-alive connection, in front of its next request.
     */
    final class RelayWatchdog {
        private final HttpServerRequest clientReq;
        private final String domain;
        private final boolean streaming;
        HttpClientRequest upReq;
        HttpClientResponse upResp;
        boolean cut;
        // The request body streams; not while the DNS lookup and connect wait, nor while a paused
        // body does, nor while upstream holds it back: that is upstream's time
        private boolean uploading;
        // The client holds the response back
        private boolean holdingResponse;
        private boolean over;
        private long clientActive;
        // Vert.x numbers timers from 0: cancelling 0 before one is set would cancel another's
        private long timer = -1;

        /**
         * Counting the client's silence from {@code since}: its request, or the last byte it got.
         * {@code upResp} is the response already in hand, if any, for the budget may be spent by now.
         */
        RelayWatchdog(HttpServerRequest clientReq, String domain, boolean streaming, long since,
                      HttpClientResponse upResp) {
            this.clientReq = clientReq;
            this.domain = domain;
            this.streaming = streaming;
            this.clientActive = since;
            this.upResp = upResp;
            if (upResp != null) upReq = upResp.request();
            var clientResp = clientReq.response();
            clientResp.endHandler(v -> {
                stop();
                if (uploadUnread()) {
                    clientResp.close();
                    // An upstream that answered early may read no more: a half-sent request would
                    // hold its pooled connection until the read-idle timeout
                    if (upReq != null) upReq.exceptionHandler(ignored -> {}).reset();
                }
            });
            clientResp.closeHandler(v -> stop());
            // It may have left during the lookups before the relay, with no handler yet to tell
            if (clientResp.closed()) stop();
            check();
        }

        /** Cut, or the client has left: nothing is to be sent to it any more. */
        boolean gone() {
            return cut || clientReq.response().closed();
        }

        /** The request has a body that has not all been read. */
        boolean uploadUnread() {
            return streaming && !clientReq.isEnded();
        }

        void touch() {
            clientActive = System.nanoTime();
        }

        void uploading(boolean uploading) {
            this.uploading = uploading;
            touch();
        }

        void holdingResponse(boolean holding) {
            holdingResponse = holding;
            touch();
        }

        private void stop() {
            over = true;
            vertx.cancelTimer(timer);
        }

        private void check() {
            if (over) return;
            // Not a stall
            if (uploading || holdingResponse) touch();
            var left = silenceLeftMillis(clientActive);
            if (left > 0) {
                timer = vertx.setTimer(left, id -> check());
                return;
            }
            stop();
            cut = true;
            var clientResp = clientReq.response();
            ProxyLog.warn("Relay cut (" + domain + clientReq.path() + "): "
                    // A head in hand is an answer, though Vert.x only writes it with the first chunk
                    + (clientResp.headWritten() || upResp != null ? "no data from upstream" : "no answer from upstream")
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

    /** Relays a request whose body has already been read, as it came. */
    void relayBuffered(HttpServerRequest clientReq, String domain, Buffer body) {
        var options = new RequestOptions().setMethod(clientReq.method()).setHost(domain).setPort(443)
                .setURI(clientReq.uri());
        requestWithAsyncDns(options).onSuccess(upReq -> {
            copyBufferedRequestHeaders(clientReq, upReq, domain);
            upReq.putHeader("Content-Length", String.valueOf(body.length()));
            upReq.send(body).onSuccess(upResp -> {
                copyResponseHead(upResp, clientReq.response());
                pipeResponse(upResp, clientReq.response(), null);
            }).onFailure(e -> {
                ProxyLog.warn("Upstream send error (" + domain + "): " + e.getMessage());
                sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(e -> {
            ProxyLog.warn("Upstream connect error (" + domain + "): " + e.getMessage());
            sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    /**
     * {@link #copyRequestHeaders}, for a request whose body was read here and goes upstream at
     * once: its {@code Expect} was answered here, and upstream must not wait to send a 100.
     */
    void copyBufferedRequestHeaders(HttpServerRequest clientReq, HttpClientRequest upReq, String domain) {
        copyRequestHeaders(clientReq, upReq, domain);
        upReq.headers().remove("Expect");
    }

    /** Upstream's status and headers, for a response relayed as it is. */
    void copyResponseHead(HttpClientResponse upResp, HttpServerResponse clientResp) {
        clientResp.setStatusCode(upResp.statusCode());
        clientResp.setStatusMessage(upResp.statusMessage());
        clientResp.headers().setAll(upResp.headers());
        clientResp.headers().remove("Connection");
        clientResp.headers().remove("Transfer-Encoding");
    }

    static boolean hasBody(HttpServerRequest clientReq) {
        var cl = clientReq.getHeader("Content-Length");
        var te = clientReq.getHeader("Transfer-Encoding");
        return (cl != null && !"0".equals(cl))
                || (te != null && te.toLowerCase().contains("chunked"));
    }

    /** Relays upstream's response, head and body, as {@link #relayBody} does the body. */
    void relayResponse(HttpServerRequest clientReq, String domain, HttpClientResponse upResp, long since) {
        copyResponseHead(upResp, clientReq.response());
        relayBody(clientReq, domain, upResp, since);
    }

    /**
     * Pipes a response's body under the head already set on {@code clientReq}'s response, with a
     * {@link RelayWatchdog} counting from {@code since}, when the client last heard from us (#1192).
     */
    void relayBody(HttpServerRequest clientReq, String domain, HttpClientResponse upResp, long since) {
        var watchdog = new RelayWatchdog(clientReq, domain, false, since, upResp);
        // The budget was spent before the response came (check() has let it go and sent the 502), or
        // the client left. Without a handler, the reset's error would be logged by Vert.x, unprefixed
        if (watchdog.gone()) {
            upResp.exceptionHandler(ignored -> {}).request().reset();
            return;
        }
        pipeResponse(upResp, clientReq.response(), watchdog);
        // A response held paused, as CachingDownload's is
        upResp.resume();
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
                if (watchdog != null) watchdog.holdingResponse(true);
                clientResp.drainHandler(v -> {
                    if (watchdog != null) watchdog.holdingResponse(false);
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
