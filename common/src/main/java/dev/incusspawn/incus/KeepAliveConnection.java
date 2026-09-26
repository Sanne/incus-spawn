package dev.incusspawn.incus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A persistent HTTP/1.1 keep-alive connection to the Incus Unix socket, reused across short
 * request-path calls (GET/POST) so tight poll loops don't reconnect each iteration.
 *
 * Responses are read by {@link HttpResponseReader} with strict Content-Length/chunked framing —
 * never read-to-EOF — so the connection stays usable afterwards. State lets the pool distinguish a reusable IDLE
 * connection from a DEAD one to discard.
 *
 * Not thread-safe: a connection is held by one caller at a time (checked out from the pool).
 * WebSocket exec fds are NOT handled here — they are per-operation and non-poolable.
 */
final class KeepAliveConnection {

    enum State { IDLE, IN_USE, DEAD }

    /** Signals the connection closed before any response byte — the request provably did not
     *  execute, so the caller may safely retry it on a fresh connection. */
    static final class StaleConnectionException extends IOException {
        StaleConnectionException(String message) { super(message); }
    }

    private final String socketPath;
    private final SocketChannel channel;
    private final InputStream in;
    private final OutputStream out;
    private final boolean permit;
    private final java.util.concurrent.atomic.AtomicBoolean accountingClosed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile State state = State.IN_USE;
    private long idleSinceNanos;
    private boolean serverWantsClose;

    private KeepAliveConnection(String socketPath, SocketChannel channel,
                                InputStream in, OutputStream out, boolean permit) {
        this.socketPath = socketPath;
        this.channel = channel;
        this.in = in;
        this.out = out;
        this.permit = permit;
    }

    static KeepAliveConnection open(String socketPath) throws IOException {
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            // Count and permit pooled connections through the same accounting as one-shot ones,
            // so the connection gauge and the fail-open concurrency semaphore still see them.
            boolean permit = UnixSocketTransport.connectionOpened();
            return new KeepAliveConnection(socketPath, channel,
                    Channels.newInputStream(channel), Channels.newOutputStream(channel), permit);
        } catch (IOException e) {
            try { channel.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    String socketPath() { return socketPath; }
    State state() { return state; }
    long idleSinceNanos() { return idleSinceNanos; }

    /** Whether this connection can be reused (not dead, and the server didn't ask to close). */
    boolean healthy() { return state != State.DEAD && !serverWantsClose; }

    void markIdle() {
        state = State.IDLE;
        idleSinceNanos = System.nanoTime();
    }

    /**
     * Send one request over this connection without closing it, and read the framed response.
     *
     * @throws StaleConnectionException if the connection was closed before any response byte
     *         (safe to retry on a fresh connection).
     * @throws IOException on any other failure (connection is marked DEAD).
     */
    IncusTransport.RawResponse execute(String method, String path, String contentType,
                        Map<String, String> extraHeaders, byte[] body, int timeoutSeconds) throws IOException {
        state = State.IN_USE;
        // Watchdog: close the channel on deadline so a stalled vsock can't block forever —
        // the same protection the one-shot request path has.
        var timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);
        var watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(timeoutSeconds * 1000L);
                timedOut.set(true);
                channel.close();
            } catch (InterruptedException | IOException ignored) {}
        });
        try {
            writeRequest(method, path, contentType, extraHeaders, body);
            return readResponse();
        } catch (StaleConnectionException e) {
            state = State.DEAD;
            throw e;
        } catch (IOException | RuntimeException e) {
            // Any failure — including a RuntimeException from parsing a corrupt/partial response
            // (e.g. NumberFormatException) — makes this connection unusable. Mark it DEAD so the
            // caller closes it rather than returning it to the pool or leaking its accounting.
            state = State.DEAD;
            if (timedOut.get()) {
                throw new IOException("request timed out after " + timeoutSeconds + "s (" + path + ")");
            }
            throw e;
        } finally {
            watchdog.interrupt();
        }
    }

    private void writeRequest(String method, String path, String contentType,
                             Map<String, String> extraHeaders, byte[] body) throws IOException {
        var header = new StringBuilder();
        header.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        header.append("Host: localhost\r\n");
        header.append("Accept: application/json\r\n");
        header.append("Connection: keep-alive\r\n");
        if (body.length > 0 || !extraHeaders.isEmpty()) {
            header.append("Content-Type: ").append(contentType).append("\r\n");
        }
        for (var e : extraHeaders.entrySet()) {
            header.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        header.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        try {
            out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
            if (body.length > 0) out.write(body);
            out.flush();
        } catch (IOException e) {
            // A write failure on a pooled connection almost always means the server closed the
            // idle connection before our request landed — i.e. it did not execute.
            throw new StaleConnectionException("write failed (connection likely closed while idle): " + e.getMessage());
        }
    }

    private IncusTransport.RawResponse readResponse() throws IOException {
        HttpResponseReader.Response resp;
        try {
            resp = HttpResponseReader.read(in);
        } catch (HttpResponseReader.NoResponseException e) {
            // Closed or reset before any response byte: the request provably did not execute,
            // so the caller may retry. EOF anywhere later is NOT retry-safe and propagates.
            throw new StaleConnectionException(e.getMessage());
        }
        // Connection: close, or a body framed by close -- either way this socket is done.
        if (!resp.reusable()) serverWantsClose = true;
        return new IncusTransport.RawResponse(resp.statusCode(), resp.body());
    }

    void close() {
        state = State.DEAD;
        try { channel.close(); } catch (IOException ignored) {}
        if (accountingClosed.compareAndSet(false, true)) {
            UnixSocketTransport.connectionClosed(permit);
        }
    }
}
