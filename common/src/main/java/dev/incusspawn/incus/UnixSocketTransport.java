package dev.incusspawn.incus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unix-socket-based transport for the Incus REST API (Linux).
 * Opens a fresh Unix domain socket connection per request/WebSocket.
 */
class UnixSocketTransport implements IncusTransport {

    static final List<String> SOCKET_CANDIDATES = List.of(
            "/run/incus/unix.socket",
            "/var/lib/incus/unix.socket"
    );

    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static final int WS_BINARY = 0x2;
    private static final int WS_CLOSE  = 0x8;
    private static final int WS_PING   = 0x9;
    private static final int WS_PONG   = 0xA;
    // WebSocket masking is not a security mechanism (RFC 6455 §10.3 — it prevents
    // proxy confusion, not attacks). ThreadLocalRandom is sufficient and avoids
    // the GraalVM native-image issue with SecureRandom static fields captured at
    // build time.

    private final String socketPath;
    private final int timeoutSeconds;

    // Diagnostics: track how many transport connections are open at once. Every
    // request and every WebSocket opens a fresh connection (we intentionally do not
    // pool), and on macOS each one is a vsock stream held by vfkit until the guest
    // forwarder closes it. A climbing high-water mark is the signature of the socat
    // forwarder leaking streams, so this is the cheapest way to see it accumulate.
    private static final java.util.concurrent.atomic.AtomicInteger OPEN_CONNECTIONS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile int peakConnections = 0;

    // Per-process safety valve: cap how many connections one isx invocation holds open
    // at once, so a runaway fan-out can't pile streams onto the forwarder. This bounds a
    // single process only — it cannot limit aggregate consumption across the many
    // concurrent/short-lived isx processes a script spawns; that requires forwarder-side
    // reaping. The cap is set well above any single operation's need (an exec holds ~5
    // fds), so it never engages in normal use, and acquisition is fail-open after a
    // timeout so it can never deadlock or wedge a legitimate burst.
    private static final int MAX_CONCURRENT_CONNECTIONS = 48;
    private static final long PERMIT_TIMEOUT_MS = 10_000;
    private static final java.util.concurrent.Semaphore CONNECTION_PERMITS =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_CONNECTIONS);

    // Monotonic count of connections ever opened this process — the churn metric. With the
    // keep-alive pool, many sequential request-path calls should open only a handful of
    // connections instead of one each.
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_OPENED =
            new java.util.concurrent.atomic.AtomicLong();

    /** Number of transport connections currently open. */
    static int openConnectionCount() { return OPEN_CONNECTIONS.get(); }

    /** Highest number of simultaneously-open connections seen this process. */
    static int peakConnectionCount() { return peakConnections; }

    /** Total connections opened this process (monotonic) — reuse keeps this low. */
    static long openedConnectionCount() { return TOTAL_OPENED.get(); }

    /**
     * Record a newly-opened connection and try to claim a concurrency permit.
     * @return true if a permit was acquired (must be passed to {@link #connectionClosed}).
     *         A false return means we proceeded without throttling (fail-open).
     */
    static boolean connectionOpened() {
        boolean permit;
        try {
            permit = CONNECTION_PERMITS.tryAcquire(PERMIT_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            permit = false;
        }
        TOTAL_OPENED.incrementAndGet();
        int now = OPEN_CONNECTIONS.incrementAndGet();
        // Benign race on the compare/store — this is a diagnostic counter, not a lock.
        if (now > peakConnections) peakConnections = now;
        return permit;
    }

    static void connectionClosed(boolean permit) {
        OPEN_CONNECTIONS.decrementAndGet();
        if (permit) CONNECTION_PERMITS.release();
    }

    UnixSocketTransport(String socketPath) {
        this(socketPath, DEFAULT_TIMEOUT_SECONDS);
    }

    UnixSocketTransport(String socketPath, int timeoutSeconds) {
        this.socketPath = socketPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public RawResponse request(String method, String path,
                               String contentType, Map<String, String> extraHeaders,
                               byte[] bodyBytes) throws IOException {
        return request(method, path, contentType, extraHeaders, bodyBytes, timeoutSeconds);
    }

    @Override
    public RawResponse requestPooled(String method, String path,
                                     String contentType, Map<String, String> extraHeaders,
                                     byte[] body) throws IOException {
        return requestPooled(method, path, contentType, extraHeaders, body, timeoutSeconds);
    }

    @Override
    public RawResponse requestPooled(String method, String path,
                                     String contentType, Map<String, String> extraHeaders,
                                     byte[] body, int requestTimeout) throws IOException {
        var pool = ConnectionPool.global();
        var conn = pool.borrow(socketPath);
        boolean reused = conn != null;
        if (conn == null) conn = KeepAliveConnection.open(socketPath);
        try {
            var resp = conn.execute(method, path, contentType, extraHeaders, body, requestTimeout);
            pool.release(conn); // park warm, or close if full/unhealthy
            return resp;
        } catch (KeepAliveConnection.StaleConnectionException stale) {
            // The request provably did not execute — recycle and retry once on a fresh
            // connection. Expected keep-alive hygiene: diagnoseable in the file log, never
            // surfaced to the user or the TUI.
            conn.close();
            dev.incusspawn.ClientLog.debug("recycled stale pooled connection ("
                    + (reused ? "reused" : "fresh") + ") for " + method + " " + path
                    + ": " + stale.getMessage());
            var fresh = KeepAliveConnection.open(socketPath);
            try {
                var resp = fresh.execute(method, path, contentType, extraHeaders, body, requestTimeout);
                pool.release(fresh);
                return resp;
            } catch (IOException | RuntimeException e) {
                fresh.close();
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            // Anything else may have executed (or is a real failure) — do not retry; surface it.
            // Include RuntimeException (e.g. a parse error) so the connection is always closed
            // and its accounting/permit released rather than leaked.
            conn.close();
            throw e;
        }
    }

    @Override
    public RawResponse request(String method, String path,
                               String contentType, Map<String, String> extraHeaders,
                               byte[] bodyBytes, int requestTimeout) throws IOException {
        var addr = UnixDomainSocketAddress.of(socketPath);
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            var watchdog = startWatchdog(channel, requestTimeout);
            boolean opened = false;
            boolean permit = false;
            try {
                channel.connect(addr);
                opened = true;
                permit = connectionOpened();
                var out = Channels.newOutputStream(channel);
                var in  = Channels.newInputStream(channel);
                writeRequest(out, method, path, contentType, extraHeaders, bodyBytes);
                // Do NOT shutdownOutput() — Incus cancels the request context on half-close.
                // Connection: close makes the server close its end after the response, which
                // causes readAllBytes() / readNBytes() to see EOF naturally.
                return readResponse(in);
            } catch (ClosedChannelException e) {
                throw timeoutException(path, requestTimeout);
            } finally {
                watchdog.cancel();
                if (opened) connectionClosed(permit);
            }
        }
    }

    @Override
    public RawResponse request(String method, String path,
                               String contentType, Map<String, String> extraHeaders,
                               Path bodyFile) throws IOException {
        var addr = UnixDomainSocketAddress.of(socketPath);
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            var watchdog = startWatchdog(channel, timeoutSeconds);
            boolean opened = false;
            boolean permit = false;
            try {
                channel.connect(addr);
                opened = true;
                permit = connectionOpened();
                var out = Channels.newOutputStream(channel);
                var in  = Channels.newInputStream(channel);
                writeRequestFromFile(out, method, path, contentType, extraHeaders, bodyFile);
                return readResponse(in);
            } catch (ClosedChannelException e) {
                throw timeoutException(path, timeoutSeconds);
            } finally {
                watchdog.cancel();
                if (opened) connectionClosed(permit);
            }
        }
    }

    @Override
    public WsConnection openWebSocket(String wsPath) throws IOException {
        var addr = UnixDomainSocketAddress.of(socketPath);
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        // Bound the connect + handshake like any request: a wedged tunnel accepts and then says
        // nothing. Only the setup is bounded -- the open socket's reads are not (see UnixWsConnection).
        var watchdog = startWatchdog(channel, timeoutSeconds);
        // The fd secret is a credential for this operation: keep it out of error messages.
        var loggablePath = wsPath.replaceFirst("\\?.*", "");
        try {
            channel.connect(addr);
            var out = Channels.newOutputStream(channel);
            var in  = Channels.newInputStream(channel);
            wsHandshake(out, in, wsPath);
            if (!watchdog.cancel()) throw timeoutException(loggablePath, timeoutSeconds);
            boolean permit = connectionOpened();
            return new UnixWsConnection(channel, out, in, permit);
        } catch (ClosedChannelException e) {
            watchdog.cancel();
            try { channel.close(); } catch (IOException ignored) {}
            throw timeoutException(loggablePath, timeoutSeconds);
        } catch (IOException | RuntimeException e) {
            watchdog.cancel();
            try { channel.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private void writeRequest(OutputStream out, String method, String path,
                              String contentType, Map<String, String> extraHeaders,
                              byte[] bodyBytes) throws IOException {
        var header = new StringBuilder();
        header.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        header.append("Host: localhost\r\n");
        header.append("Accept: application/json\r\n");
        header.append("Connection: close\r\n");
        if (bodyBytes.length > 0 || !extraHeaders.isEmpty()) {
            header.append("Content-Type: ").append(contentType).append("\r\n");
        }
        for (var entry : extraHeaders.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        header.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        if (bodyBytes.length > 0) {
            out.write(bodyBytes);
        }
        out.flush();
    }

    private void writeRequestFromFile(OutputStream out, String method, String path,
                                      String contentType, Map<String, String> extraHeaders,
                                      Path bodyFile) throws IOException {
        long fileSize = Files.size(bodyFile);
        var header = new StringBuilder();
        header.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        header.append("Host: localhost\r\n");
        header.append("Accept: application/json\r\n");
        header.append("Connection: close\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        for (var entry : extraHeaders.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        header.append("Content-Length: ").append(fileSize).append("\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        try (var fileIn = Files.newInputStream(bodyFile)) {
            fileIn.transferTo(out);
        }
        out.flush();
    }

    private static RawResponse readResponse(InputStream in) throws IOException {
        var resp = HttpResponseReader.read(in);
        return new RawResponse(resp.statusCode(), resp.body());
    }

    /** Send HTTP Upgrade request and require a 101 response. */
    private static void wsHandshake(OutputStream out, InputStream in, String wsPath) throws IOException {
        var keyBytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(keyBytes);
        var key = Base64.getEncoder().encodeToString(keyBytes);
        var req = "GET " + wsPath + " HTTP/1.1\r\n" +
                  "Host: localhost\r\n" +
                  "Upgrade: websocket\r\n" +
                  "Connection: Upgrade\r\n" +
                  "Sec-WebSocket-Key: " + key + "\r\n" +
                  "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        HttpResponseReader.Head head;
        try {
            head = HttpResponseReader.readHead(in);
        } catch (java.io.EOFException e) {
            // Without this, a peer that hung up mid-handshake yielded a "connected" socket that
            // read as closed at once -- an exec fd whose output silently came back empty.
            throw new IOException("WebSocket handshake failed: " + e.getMessage(), e);
        }
        if (head.statusCode() != 101) {
            throw new IOException("WebSocket upgrade failed: HTTP " + head.statusCode());
        }
    }

    /** One data frame as received: payload plus the FIN bit that ends a fragmented message. */
    record WsFrame(boolean fin, byte[] payload) {}

    /** Control frames seen while reading data frames. */
    @FunctionalInterface
    interface ControlFrames {
        /** Answer a server PING with a PONG echoing its payload (RFC 6455 §5.5.3). */
        void ping(byte[] payload) throws IOException;
        /** A PONG arrived, answering one of our pings: the peer is still there. */
        default void pong() {}
    }

    /**
     * Read the next data frame from the server (unmasked), passing any PING or PONG met along
     * the way to {@code control}. Returns null on close frame or EOF.
     */
    // Package-private for testing
    static WsFrame wsReadFrame(InputStream in, ControlFrames control) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 == -1) return null; // EOF between frames: the stream ended
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;

            // From here on EOF means the frame was cut off: an error, never a shorter frame.
            int b1 = frameByte(in);
            long payloadLen = b1 & 0x7F;
            if (payloadLen == 126) {
                payloadLen = ((long) frameByte(in) << 8) | frameByte(in);
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) payloadLen = (payloadLen << 8) | frameByte(in);
            }
            if (payloadLen < 0 || payloadLen > MAX_FRAME_BYTES) {
                throw new IOException("WebSocket frame length out of range: " + payloadLen);
            }

            var payload = in.readNBytes((int) payloadLen);
            if (payload.length < payloadLen) {
                throw new java.io.EOFException("WebSocket frame cut off (" + payload.length + "/" + payloadLen + " bytes)");
            }

            if (opcode == WS_CLOSE) return null;
            if (opcode == WS_PING) {
                control.ping(payload);
                continue;
            }
            if (opcode == WS_PONG) {
                control.pong();
                continue;
            }
            if (opcode == WS_BINARY || opcode == 0x1 /* text */ || opcode == 0x0 /* continuation */) {
                return new WsFrame(fin, payload);
            }
            // Unknown opcode — skip frame
        }
    }

    // Far above anything Incus sends (exec output and event frames are kilobytes), so only a
    // corrupt length -- read from a stream that went wrong -- can exceed it.
    private static final long MAX_FRAME_BYTES = 64L * 1024 * 1024;

    private static int frameByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new java.io.EOFException("WebSocket frame cut off in its header");
        return b;
    }

    /** Read frames until one carries FIN, concatenating a fragmented message. Null on close/EOF. */
    // Package-private for testing
    static byte[] wsReadMessage(InputStream in, ControlFrames control) throws IOException {
        var first = wsReadFrame(in, control);
        if (first == null || first.fin()) return first == null ? null : first.payload();
        var message = new ByteArrayOutputStream();
        message.write(first.payload());
        while (true) {
            var next = wsReadFrame(in, control);
            if (next == null) return null;
            message.write(next.payload());
            if (next.fin()) return message.toByteArray();
        }
    }

    /** Send a masked WebSocket frame (client frames MUST be masked per RFC 6455). */
    private static void wsSendMasked(OutputStream out, int opcode,
                                     byte[] data, int offset, int length)
            throws IOException {
        var mask = new byte[4];
        ThreadLocalRandom.current().nextBytes(mask);

        // Frame header: FIN + opcode, MASK + payload length (max 14 bytes)
        var header = new byte[14];
        int pos = 0;
        header[pos++] = (byte) (0x80 | opcode);
        if (length < 126) {
            header[pos++] = (byte) (0x80 | length);
        } else if (length < 65536) {
            header[pos++] = (byte) (0x80 | 126);
            header[pos++] = (byte) (length >> 8);
            header[pos++] = (byte) (length & 0xFF);
        } else {
            header[pos++] = (byte) (0x80 | 127);
            for (int i = 7; i >= 0; i--) header[pos++] = (byte) ((length >> (8 * i)) & 0xFF);
        }
        out.write(header, 0, pos);
        out.write(mask);

        // Masked payload
        var masked = new byte[length];
        for (int i = 0; i < length; i++) masked[i] = (byte) (data[offset + i] ^ mask[i % 4]);
        out.write(masked);
        out.flush();
    }

    private static Watchdog startWatchdog(SocketChannel channel, int timeoutSeconds) {
        return new Watchdog(channel, timeoutSeconds);
    }

    /**
     * Closes a channel if it is still in use at the deadline. {@link #cancel} settles the race
     * with the deadline: it returns false if the channel was (or is being) closed, so a caller
     * about to hand the channel on -- a WebSocket that outlives this call -- never hands on a
     * closed one.
     */
    private static final class Watchdog {
        private final Thread thread;
        private boolean cancelled;
        private boolean fired;

        Watchdog(SocketChannel channel, int timeoutSeconds) {
            thread = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(timeoutSeconds * 1000L);
                    synchronized (this) {
                        if (cancelled) return;
                        fired = true;
                    }
                    channel.close();
                } catch (InterruptedException | IOException ignored) {}
            });
        }

        /** @return true if cancelled before the deadline fired. */
        synchronized boolean cancel() {
            cancelled = true;
            thread.interrupt();
            return !fired;
        }
    }

    private static IOException timeoutException(String path, int timeout) {
        return new IOException(
                "Request timed out after " + timeout + "s (" + path + ") — "
                + "the Incus daemon may be unreachable. On macOS, try: isx vm restart");
    }

    /**
     * WsConnection backed by a Unix domain SocketChannel.
     * WebSocket reads block without a request-level timeout — bounded by the 15 s keepalive
     * pings (IncusApi.execPty) and reconnect logic (IncusClient.startShell). HTTP
     * request/response cycles use a watchdog timeout (see {@link #startWatchdog}).
     */
    private static class UnixWsConnection implements IncusTransport.WsConnection {
        private final SocketChannel channel;
        private final OutputStream out;
        private final InputStream in;
        private final Object writeLock = new Object();
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private final boolean permit;
        // Byte-stream reads (exec, shells) skip control frames exactly as they always have: Incus
        // doesn't ping exec sockets, and a PONG sent from the reader thread would queue behind a
        // stdin write holding writeLock -- a stall risk on the one channel that must never stall.
        private static final ControlFrames IGNORE_CONTROL = payload -> {};
        // Message reads (the long-lived event subscription) answer PINGs, which Incus requires of
        // event listeners, and count every control frame as a sign the peer is alive.
        private final ControlFrames answeringControl = new ControlFrames() {
            @Override public void ping(byte[] payload) throws IOException {
                received();
                sendPong(payload);
            }
            @Override public void pong() {
                received();
            }
        };
        private volatile long lastReceivedNanos = System.nanoTime();

        UnixWsConnection(SocketChannel channel, OutputStream out, InputStream in, boolean permit) {
            this.channel = channel;
            this.out     = out;
            this.in      = in;
            this.permit  = permit;
        }

        @Override
        public byte[] readPayload() throws IOException {
            var frame = wsReadFrame(in, IGNORE_CONTROL);
            return frame == null ? null : frame.payload();
        }

        @Override
        public byte[] readMessage() throws IOException {
            var message = wsReadMessage(in, answeringControl);
            received();
            return message;
        }

        @Override
        public long millisSinceLastReceived() {
            return (System.nanoTime() - lastReceivedNanos) / 1_000_000;
        }

        private void received() {
            lastReceivedNanos = System.nanoTime();
        }

        private void sendPong(byte[] payload) throws IOException {
            synchronized (writeLock) {
                wsSendMasked(out, WS_PONG, payload, 0, payload.length);
            }
        }

        @Override
        public void sendData(byte[] data, int offset, int length) throws IOException {
            synchronized (writeLock) {
                wsSendMasked(out, WS_BINARY, data, offset, length);
            }
        }

        @Override
        public void sendText(String text) throws IOException {
            var bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            synchronized (writeLock) {
                wsSendMasked(out, 0x1 /* WS_TEXT */, bytes, 0, bytes.length);
            }
        }

        @Override
        public void sendPing() throws IOException {
            synchronized (writeLock) {
                wsSendMasked(out, WS_PING, new byte[0], 0, 0);
            }
        }

        @Override
        public void sendClose() throws IOException {
            synchronized (writeLock) {
                wsSendMasked(out, WS_CLOSE, new byte[0], 0, 0);
                out.flush();
            }
        }

        @Override
        public void close() {
            // close() is called more than once (try-with-resources + the watcher
            // force-close in IncusApi), so only count the first.
            if (closed.compareAndSet(false, true)) {
                connectionClosed(permit);
            }
            try { channel.close(); } catch (IOException ignored) {}
        }
    }
}
