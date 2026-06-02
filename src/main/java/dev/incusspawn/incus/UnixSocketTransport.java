package dev.incusspawn.incus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
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

    private static final int WS_BINARY = 0x2;
    private static final int WS_CLOSE  = 0x8;
    private static final int WS_PING   = 0x9;
    // WebSocket masking is not a security mechanism (RFC 6455 §10.3 — it prevents
    // proxy confusion, not attacks). ThreadLocalRandom is sufficient and avoids
    // the GraalVM native-image issue with SecureRandom static fields captured at
    // build time.

    private final String socketPath;

    UnixSocketTransport(String socketPath) {
        this.socketPath = socketPath;
    }

    @Override
    public RawResponse request(String method, String path,
                               String contentType, Map<String, String> extraHeaders,
                               byte[] bodyBytes) throws IOException {
        var addr = UnixDomainSocketAddress.of(socketPath);
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(addr);
            var out = Channels.newOutputStream(channel);
            var in  = Channels.newInputStream(channel);
            writeRequest(out, method, path, contentType, extraHeaders, bodyBytes);
            // Do NOT shutdownOutput() — Incus cancels the request context on half-close.
            // Connection: close makes the server close its end after the response, which
            // causes readAllBytes() / readNBytes() to see EOF naturally.
            return readResponse(in);
        }
    }

    @Override
    public WsConnection openWebSocket(String wsPath) throws IOException {
        var addr = UnixDomainSocketAddress.of(socketPath);
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(addr);
        var out = Channels.newOutputStream(channel);
        var in  = Channels.newInputStream(channel);
        wsHandshake(out, in, wsPath);
        return new UnixWsConnection(channel, out, in);
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

    /**
     * WsConnection backed by a Unix domain SocketChannel.
     * Reads block without a timeout — bounded by the process lifecycle: Incus closes the
     * WebSocket when the container process exits, and callers join reader threads with
     * timeouts (e.g. execBidirectional's 2 s stdin join). The HTTPS transport has a 120 s
     * poll timeout via LinkedBlockingQueue; Unix sockets don't need one because the daemon
     * is local and connection failures surface as IOExceptions immediately.
     */
    private static class UnixWsConnection implements IncusTransport.WsConnection {
        private final SocketChannel channel;
        private final OutputStream out;
        private final InputStream in;

        UnixWsConnection(SocketChannel channel, OutputStream out, InputStream in) {
            this.channel = channel;
            this.out     = out;
            this.in      = in;
        }

        @Override
        public byte[] readPayload() throws IOException {
            return wsReadPayload(in);
        }

        @Override
        public void sendData(byte[] data, int offset, int length) throws IOException {
            wsSendMasked(out, WS_BINARY, data, offset, length);
        }

        @Override
        public void sendClose() throws IOException {
            wsSendMasked(out, WS_CLOSE, new byte[0], 0, 0);
            out.flush();
        }

        @Override
        public void close() {
            try { channel.close(); } catch (IOException ignored) {}
        }
    }
}
