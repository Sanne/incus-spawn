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
                watchdog.interrupt();
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
                watchdog.interrupt();
                if (opened) connectionClosed(permit);
            }
        }
    }

    @Override
    public WsConnection openWebSocket(String wsPath) throws IOException {
        var addr = UnixDomainSocketAddress.of(socketPath);
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(addr);
            var out = Channels.newOutputStream(channel);
            var in  = Channels.newInputStream(channel);
            wsHandshake(out, in, wsPath);
            boolean permit = connectionOpened();
            return new UnixWsConnection(channel, out, in, permit);
        } catch (IOException e) {
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

    private RawResponse readResponse(InputStream in) throws IOException {
        var statusLine = readLine(in);
        var parts = statusLine.split(" ", 3);
        if (parts.length < 2) throw new IOException("Invalid HTTP status line: " + statusLine);
        int statusCode = Integer.parseInt(parts[1]);

        int contentLength = -1;
        boolean chunked = false;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            var lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(lower.substring(15).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }

        byte[] bodyBytes;
        if (chunked) {
            bodyBytes = readChunkedBody(in);
        } else if (contentLength >= 0) {
            bodyBytes = in.readNBytes(contentLength);
        } else {
            bodyBytes = in.readAllBytes();
        }

        return new RawResponse(statusCode, bodyBytes);
    }

    /** Send HTTP Upgrade request and consume the 101 response headers. */
    private void wsHandshake(OutputStream out, InputStream in, String wsPath) throws IOException {
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
        // Consume headers until blank line
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            if (line.startsWith("HTTP/") && !line.contains("101")) {
                throw new IOException("WebSocket upgrade failed: " + line);
            }
        }
    }

    // Package-private for testing
    String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            sb.append((char) c);
        }
        return sb.toString();
    }

    // Package-private for testing
    byte[] readChunkedBody(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        while (true) {
            var sizeLine = readLine(in).trim();
            if (sizeLine.isEmpty()) continue;
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
            int chunkSize = Integer.parseInt(sizeLine, 16);
            if (chunkSize == 0) break;
            out.write(in.readNBytes(chunkSize));
            readLine(in);
        }
        return out.toByteArray();
    }

    /**
     * Read one WebSocket frame from the server (unmasked).
     * Returns the payload bytes, or null on close frame or EOF.
     * Responds to PING frames with PONG automatically (skipped — Incus doesn't send pings).
     */
    private static byte[] wsReadPayload(InputStream in) throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 == -1) return null;
            int opcode = b0 & 0x0F;

            int b1 = in.read();
            if (b1 == -1) return null;
            long payloadLen = b1 & 0x7F;
            if (payloadLen == 126) {
                payloadLen = ((in.read() & 0xFFL) << 8) | (in.read() & 0xFFL);
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) payloadLen = (payloadLen << 8) | (in.read() & 0xFFL);
            }

            var payload = in.readNBytes((int) payloadLen);

            if (opcode == WS_CLOSE) return null;
            if (opcode == WS_PING) {
                // Skip — Incus doesn't send pings in practice
                continue;
            }
            if (opcode == WS_BINARY || opcode == 0x1 /* text */ || opcode == 0x0 /* continuation */) {
                return payload;
            }
            // Unknown opcode — skip frame
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

    private static Thread startWatchdog(SocketChannel channel, int timeoutSeconds) {
        return Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(timeoutSeconds * 1000L);
                channel.close();
            } catch (InterruptedException | IOException ignored) {}
        });
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

        UnixWsConnection(SocketChannel channel, OutputStream out, InputStream in, boolean permit) {
            this.channel = channel;
            this.out     = out;
            this.in      = in;
            this.permit  = permit;
        }

        @Override
        public byte[] readPayload() throws IOException {
            return wsReadPayload(in);
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
