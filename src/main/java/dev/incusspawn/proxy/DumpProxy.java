package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;

import java.util.concurrent.CountDownLatch;

/**
 * Plain HTTP pass-through proxy for host-side API traffic capture.
 * <p>
 * Listens on localhost (no TLS), forwards to api.anthropic.com over TLS,
 * and logs every exchange via {@link ApiTrafficLog}. Use with
 * {@code ANTHROPIC_BASE_URL=http://localhost:PORT} to intercept Claude Code
 * traffic on the host for debugging.
 */
public class DumpProxy {

    private static final String UPSTREAM_HOST = "api.anthropic.com";

    private final int port;
    private final ApiTrafficLog log;

    public DumpProxy(int port, ApiTrafficLog log) {
        this.port = port;
        this.log = log;
    }

    public void start() throws Exception {
        var vertx = Vertx.vertx();

        var clientOptions = new HttpClientOptions()
                .setSsl(true)
                .setVerifyHost(true)
                .setTrustAll(false)
                .setDefaultHost(UPSTREAM_HOST)
                .setDefaultPort(443)
                .setMaxPoolSize(10)
                .setKeepAliveTimeout(30)
                .setConnectTimeout(30_000)
                .setReadIdleTimeout(300);
        var systemCaBundle = MitmProxy.findSystemCaBundle();
        if (systemCaBundle != null) {
            clientOptions.setTrustOptions(
                    new io.vertx.core.net.PemTrustOptions().addCertPath(systemCaBundle));
        }
        var upstreamClient = vertx.createHttpClient(clientOptions);

        var serverOptions = new HttpServerOptions()
                .setHost("127.0.0.1")
                .setPort(port)
                .setIdleTimeout(120)
                .setIdleTimeoutUnit(java.util.concurrent.TimeUnit.SECONDS);
        var server = vertx.createHttpServer(serverOptions);
        server.requestHandler(req -> handleRequest(req, upstreamClient));
        server.listen().toCompletionStage().toCompletableFuture().get();

        System.out.println("API dump proxy listening on http://localhost:" + port);
        System.out.println("Logs: " + log.logDir());
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  ANTHROPIC_BASE_URL=http://localhost:" + port + " claude");
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            upstreamClient.close();
            vertx.close();
        }));

        new CountDownLatch(1).await();
    }

    private void handleRequest(HttpServerRequest clientReq, HttpClient upstreamClient) {
        var originalDump = dumpRequest(clientReq);

        clientReq.body()
                .compose(body -> forwardToUpstream(clientReq, upstreamClient, body)
                        .compose(upResp -> upResp.body().map(rb -> new Exchange(upResp, body, rb))))
                .onSuccess(ex -> {
                    log.logExchange(originalDump, ex.reqBytes,
                            null, null,
                            dumpResponse(ex.response), ex.respBytes);
                    relay(ex.response, ex.respBytes, clientReq.response());
                })
                .onFailure(err -> {
                    System.err.println("Dump proxy error: " + err.getMessage());
                    sendError(clientReq.response(), 502);
                });
    }

    private Future<HttpClientResponse> forwardToUpstream(HttpServerRequest clientReq,
                                                         HttpClient upstreamClient,
                                                         Buffer bodyBuffer) {
        var options = new RequestOptions()
                .setMethod(clientReq.method())
                .setHost(UPSTREAM_HOST)
                .setPort(443)
                .setURI(clientReq.uri());

        return upstreamClient.request(options).compose(upReq -> {
            for (var entry : clientReq.headers()) {
                var key = entry.getKey();
                if ("Host".equalsIgnoreCase(key) || "Connection".equalsIgnoreCase(key)
                        || "Transfer-Encoding".equalsIgnoreCase(key)) continue;
                upReq.putHeader(key, entry.getValue());
            }
            upReq.putHeader("Host", UPSTREAM_HOST);
            return upReq.send(bodyBuffer);
        });
    }

    private record Exchange(HttpClientResponse response, byte[] reqBytes, byte[] respBytes) {
        Exchange(HttpClientResponse response, Buffer reqBody, Buffer respBody) {
            this(response,
                    reqBody != null && reqBody.length() > 0 ? reqBody.getBytes() : null,
                    respBody != null && respBody.length() > 0 ? respBody.getBytes() : null);
        }
    }

    private static void relay(HttpClientResponse upResp, byte[] body, HttpServerResponse clientResp) {
        clientResp.setStatusCode(upResp.statusCode());
        clientResp.setStatusMessage(upResp.statusMessage());
        for (var entry : upResp.headers()) {
            var key = entry.getKey();
            if ("Connection".equalsIgnoreCase(key)
                    || "Transfer-Encoding".equalsIgnoreCase(key)) continue;
            clientResp.putHeader(key, entry.getValue());
        }
        if (body != null) {
            clientResp.putHeader("Content-Length", String.valueOf(body.length));
            clientResp.end(Buffer.buffer(body));
        } else {
            clientResp.end();
        }
    }

    private static String dumpRequest(HttpServerRequest req) {
        var sb = new StringBuilder();
        sb.append(req.method()).append(' ').append(req.uri()).append(' ')
                .append(req.version().alpnName()).append('\n');
        for (var entry : req.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String dumpResponse(HttpClientResponse resp) {
        var sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.statusCode()).append(' ')
                .append(resp.statusMessage()).append('\n');
        for (var entry : resp.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static void sendError(HttpServerResponse resp, int statusCode) {
        if (!resp.ended() && !resp.closed()) {
            resp.setStatusCode(statusCode).end();
        }
    }
}
