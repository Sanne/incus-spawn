package dev.incusspawn.proxy;

import dev.incusspawn.config.HostRoutes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Serves the names of {@code host-routes:} (#936): a request an instance sends to one is
 * forwarded over plain HTTP to the route's loopback target, a controller on this host, stamped
 * with who sent it. See DESIGN.md "Host routes".
 *
 * <p><b>Who may call.</b> An instance identified by its source address <em>and</em> the
 * per-start secret it presents ({@link InstanceSecret#HEADER}), as {@link InstanceRegistry#identify}
 * decides (on macOS, where the address names no instance, by the secret alone, #1583), one
 * throttled refresh on a refusal as for {@code isx mcp}; and whose leaf template
 * the route admits ({@link HostRoutes.Route#admits}), never a project-local one. Everything else
 * is refused before its body is read, and the controller sees nothing of it.
 *
 * <p><b>What the controller sees.</b> The request as sent, minus every {@code X-Isx-*} header the
 * client sent, the instance's secret included, plus {@link #INSTANCE_HEADER} and
 * {@link #TEMPLATE_HEADER} written here, and the route's own secret ({@link HostRouteSecret}),
 * which no guest ever holds. The controller trusts the identity headers only once that secret
 * matches, so a request that reached it some other way (another local process, a listener not on
 * loopback alone) cannot pass as an instance. The secret is stripped from the response's headers,
 * and never logged. No credential is ever injected on a route.
 *
 * <p><b>One name per connection.</b> A request is routed only when its SNI and its {@code Host}
 * are both the route's name (421 otherwise), so a connection opened for a credential domain
 * cannot carry a request for a route, nor the other way round. Names under a route resolve to the
 * proxy too (dnsmasq answers subdomains) and get a 404 rather than a relay.
 */
final class HostRouteRelay {

    static final String INSTANCE_HEADER = "X-Isx-Instance";
    static final String TEMPLATE_HEADER = "X-Isx-Template";
    private static final String IDENTITY_PREFIX = "x-isx-";
    private static final String ROUTE_SECRET_KEY = headerKey(HostRouteSecret.HEADER);
    /** Hop-by-hop headers (RFC 9110 7.6.1), and what this side of the relay sets itself. */
    private static final Set<String> NOT_FORWARDED = Set.of("connection", "keep-alive", "proxy-connection",
            "te", "trailer", "transfer-encoding", "upgrade", "expect", "host");
    static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    static final int MAX_CONNECTIONS_PER_ROUTE = 32;

    private final Vertx vertx;
    private final Supplier<InstanceRegistry> registry;
    private final Upstream upstream;
    /** Made on the first routed request, so a proxy with no routes holds none. */
    private volatile HttpClient client;

    /**
     * A route as served: the route and the secret its controller checks ({@link HostRouteSecret}),
     * null when that cannot be used, and then every request to it is refused (503). Still claimed,
     * so its name keeps its certificate and bridge DNS entry, which the CLI writes regardless, and a
     * request to it is never relayed out as ordinary traffic. The secret never appears in
     * {@link #toString}, which a log line or an exception may print.
     */
    record Served(HostRoutes.Route route, String secret) {
        @Override
        public String toString() {
            return "Served[" + route + ", secret=<redacted>]";
        }
    }

    /** {@code routes}, each with its secret from {@code secretOf} (null when it cannot be used). */
    static Map<String, Served> served(Map<String, HostRoutes.Route> routes,
                                      java.util.function.Function<String, String> secretOf) {
        var served = new java.util.LinkedHashMap<String, Served>();
        routes.forEach((name, route) -> served.put(name, new Served(route, secretOf.apply(name))));
        return java.util.Collections.unmodifiableMap(served);
    }

    HostRouteRelay(Vertx vertx, Supplier<InstanceRegistry> registry, Upstream upstream) {
        this.vertx = vertx;
        this.registry = registry;
        this.upstream = upstream;
    }

    /**
     * Whether this request is the relay's to answer: its {@code Host} or its SNI is a route's
     * name or a name under one. Such a request never reaches credential injection or a relay.
     */
    static boolean claims(Map<String, Served> routes, HttpServerRequest req) {
        return !routes.isEmpty() && (isRouted(routes, hostOf(req)) || isRouted(routes, sniOf(req)));
    }

    /**
     * As above, for a WebSocket, which a route refuses in this version: a controller streams
     * with long-poll or server-sent events, which the plain request path carries.
     */
    static boolean claims(Map<String, Served> routes, io.vertx.core.http.ServerWebSocketHandshake ws) {
        if (routes.isEmpty()) return false;
        String sni = null;
        if (ws.sslSession() instanceof javax.net.ssl.ExtendedSSLSession session) {
            for (var name : session.getRequestedServerNames()) {
                if (name instanceof javax.net.ssl.SNIHostName hostName) sni = hostName.getAsciiName();
            }
        }
        return isRouted(routes, lower(ws.authority() == null ? null : ws.authority().host()))
                || isRouted(routes, lower(sni));
    }

    private static boolean isRouted(Map<String, Served> routes, String name) {
        return name != null && routes.keySet().stream()
                .anyMatch(route -> name.equals(route) || HostRoutes.isUnder(name, route));
    }

    /**
     * Answer one request {@link #claims} says is the relay's; with {@code bySecretAlone}, a caller
     * whose address is no instance may be identified by its secret ({@link InstanceRegistry#identify(String, String, boolean)}).
     * {@code identified} is told who the caller is, null for nobody, once that is decided.
     */
    void handle(HttpServerRequest req, String address, boolean bySecretAlone, Map<String, Served> routes,
                java.util.function.Consumer<InstanceRegistry.Caller> identified) {
        // Nothing may read the body before the caller is known, and Vert.x drops a body nobody reads
        req.pause();
        var host = hostOf(req);
        var sni = sniOf(req);
        if (host == null || !host.equals(sni)) {
            CallerCheck.refuse(req, 421, "this connection is for " + sni + ", not " + host);
            return;
        }
        var route = routes.get(host);
        if (route == null) {
            CallerCheck.refuse(req, 404, "nothing is routed at " + host);
            return;
        }
        if (route.secret() == null) {
            // Fail closed: without its secret the controller could not tell the proxy from anyone
            CallerCheck.refuse(req, 503, host + " is not served: its route secret cannot be used (see the proxy log)");
            return;
        }
        var current = registry.get();
        if (current == null) {
            CallerCheck.refuse(req, 503, "the proxy cannot tell instances apart yet; try again shortly");
            return;
        }
        var secret = req.getHeader(InstanceSecret.HEADER);
        CallerCheck.identify(vertx, req, current, r -> r.identifyWithLineage(address, secret, bySecretAlone), caller -> {
            identified.accept(caller == null ? null : caller.caller());
            if (caller != null) admit(req, route, caller);
            else CallerCheck.refuse(req, 403, CallerCheck.unidentified(current, address, bySecretAlone, "a host route"));
        });
    }

    private void admit(HttpServerRequest req, Served served, InstanceRegistry.Identified caller) {
        var route = served.route();
        var lineage = caller.lineage();
        if (!route.admits(lineage.template(), lineage.untrusted())) {
            ProxyLog.warn("Refusing host route " + route.name() + " to instance '" + caller.instanceName() + "'"
                    + (lineage.untrusted() ? ", " + lineage.why()
                            : ", whose template '" + lineage.template() + "' the route does not list"));
            CallerCheck.refuse(req, 403, "instance '" + caller.instanceName() + "' may not call " + route.name());
            return;
        }
        forward(req, served, caller);
    }

    private void forward(HttpServerRequest req, Served served, InstanceRegistry.Identified caller) {
        var route = served.route();
        // Gone while its caller was being identified: nothing to forward
        if (req.response().closed()) return;
        // Origin form only: an absolute-form target (GET http://other/x) would name another host
        var query = req.query();
        var options = new RequestOptions().setMethod(req.method())
                .setHost(route.host()).setPort(route.port()).setURI(req.path() + (query == null ? "" : "?" + query));
        var named = connectionOptions(req.headers());
        req.headers().forEach((name, value) -> {
            var key = headerKey(name);
            if (!NOT_FORWARDED.contains(key) && !named.contains(key) && !key.startsWith(IDENTITY_PREFIX)) {
                options.addHeader(name, value);
            }
        });
        // The route secret last, over anything of that name: what tells the controller it is the proxy
        options.putHeader("Host", route.name())
                .putHeader(INSTANCE_HEADER, caller.instanceName())
                .putHeader(TEMPLATE_HEADER, caller.lineage().template())
                .putHeader(HostRouteSecret.HEADER, served.secret());
        // A client gone before the controller answers takes its request with it, even one still connecting
        var upstreamRequest = new java.util.concurrent.atomic.AtomicReference<io.vertx.core.http.HttpClientRequest>();
        var gone = new java.util.concurrent.atomic.AtomicBoolean();
        req.response().closeHandler(v -> {
            gone.set(true);
            var pending = upstreamRequest.get();
            if (pending != null) pending.reset();
        });
        client().request(options).onSuccess(upReq -> {
            upstreamRequest.set(upReq);
            if (gone.get()) {
                upReq.reset();
                return;
            }
            Future<HttpClientResponse> answered;
            if (Upstream.hasBody(req)) {
                // The client sends no body until it is told to; the controller never sees the Expect
                if (Upstream.expectsContinue(req)) req.response().writeContinue();
                answered = upReq.send(req);
            } else {
                req.resume();
                answered = upReq.send();
            }
            answered.onSuccess(upResp -> {
                var clientResp = req.response();
                upstream.copyResponseHead(upResp, clientResp);
                // A reason phrase is the controller's text too, which may quote the request
                var reason = upResp.statusMessage();
                if (reason != null && reason.contains(served.secret())) {
                    clientResp.setStatusMessage(io.netty.handler.codec.http.HttpResponseStatus.valueOf(upResp.statusCode())
                            .reasonPhrase());
                }
                // Hop-by-hop on the way back too: the controller's are for the loopback hop
                var hopOnly = connectionOptions(upResp.headers());
                var dropped = new java.util.HashSet<String>();
                for (var header : clientResp.headers()) {
                    var key = headerKey(header.getKey());
                    if (NOT_FORWARDED.contains(key) || hopOnly.contains(key) || key.equals(ROUTE_SECRET_KEY)
                            // A controller that echoes request headers must not hand the guest the secret
                            || header.getValue().contains(served.secret())) {
                        dropped.add(header.getKey());
                    }
                }
                dropped.forEach(clientResp.headers()::remove);
                upstream.pipeResponse(upResp, clientResp, null);
            }).onFailure(err -> {
                ProxyLog.warn("Host route " + route.name() + " failed: " + err.getMessage());
                upstream.sendError(req.response(), 502, "Host route error");
            });
        }).onFailure(err -> {
            ProxyLog.warn("Host route " + route.name() + " cannot reach " + route.host() + ":" + route.port()
                    + ": " + err.getMessage());
            CallerCheck.refuse(req, 502, "nothing answers at " + route.name() + " on this host");
        });
    }

    /**
     * A header name as a controller may read it: lower case, with every character but a letter or
     * digit as {@code -}, since CGI-style servers fold them all into one variable
     * ({@code X_Isx_Instance} and {@code X.Isx.Instance} are {@code HTTP_X_ISX_INSTANCE} as much
     * as {@code X-Isx-Instance} is).
     */
    static String headerKey(String name) {
        var key = new StringBuilder(name.length());
        for (var c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            key.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '-');
        }
        return key.toString();
    }

    /** The headers {@code headers} names in {@code Connection:}, hop-by-hop for that message alone. */
    private static Set<String> connectionOptions(io.vertx.core.MultiMap headers) {
        var named = new java.util.HashSet<String>();
        for (var value : headers.getAll("Connection")) {
            for (var token : value.split(",")) {
                if (!token.isBlank()) named.add(headerKey(token.strip()));
            }
        }
        return named;
    }

    private static String hostOf(HttpServerRequest req) {
        return lower(req.authority() == null ? null : req.authority().host());
    }

    private static String sniOf(HttpServerRequest req) {
        return lower(req.connection().indicatedServerName());
    }

    private static String lower(String name) {
        return name == null || name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
    }

    private synchronized HttpClient client() {
        if (client == null) {
            // Long-polls and event streams each hold a connection: room for several instances at once,
            // and nothing held past the MITM server's own idle timeout, which ends the client side anyway
            client = vertx.createHttpClient(new HttpClientOptions().setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                    .setIdleTimeout(MitmProxy.MITM_IDLE_TIMEOUT_SECONDS)
                    .setIdleTimeoutUnit(java.util.concurrent.TimeUnit.SECONDS),
                    new PoolOptions().setHttp1MaxSize(MAX_CONNECTIONS_PER_ROUTE));
        }
        return client;
    }

    void stop() {
        if (client != null) client.close();
    }
}
