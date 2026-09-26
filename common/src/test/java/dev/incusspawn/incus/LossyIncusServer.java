package dev.incusspawn.incus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Incus daemon on a real Unix socket that can misbehave the way the macOS vfkit vsock
 * tunnel does -- no VM, no Mac.
 *
 * On macOS, Java only ever sees vfkit's tunnel as a Unix socket; the transport workarounds in
 * {@link UnixSocketTransport}, {@link KeepAliveConnection}, {@link ConnectionPool} and
 * {@link IncusApi} exist because of how that tunnel <em>behaves</em> (close frames and EOF that
 * never arrive, connections that stall or vanish while idle), not because the wire is vsock. So
 * the behaviour is faked here and the compensations are asserted against it.
 *
 * It speaks just enough of the REST API for those paths: {@code GET /1.0}, exec
 * ({@code POST .../exec} returning an operation with four fd secrets), the fd WebSockets, and the
 * operation {@code /wait} long-poll. Any other request gets a small JSON body.
 */
final class LossyIncusServer implements AutoCloseable {

    /** How plain HTTP requests misbehave. Exec traffic (upgrades, /wait) is unaffected. */
    enum Fault {
        NONE,
        /** Accept the connection, then never read or write -- a wedged tunnel. */
        SILENT_ON_ACCEPT,
        /** Read the request, then never answer -- a tunnel that stalls mid-exchange. */
        STALL_AFTER_REQUEST,
        /** Answer, then drop the connection without announcing it -- an idle connection reaped. */
        DROP_AFTER_RESPONSE,
        /** Announce a body, send part of it, then drop the connection. */
        TRUNCATE_BODY,
        /** Refuse WebSocket upgrades with 403. */
        REJECT_UPGRADE
    }

    /** How a plain response is framed. EOF framing closes the connection after the body. */
    enum Framing { LENGTH, CHUNKED, EOF }

    private static final String FD_STDIN = "s0", FD_STDOUT = "s1", FD_STDERR = "s2", FD_CONTROL = "sc";

    private final Path dir;
    private final Path sock;
    private final ServerSocketChannel server;
    private final List<SocketChannel> accepted = new CopyOnWriteArrayList<>();

    volatile Fault fault = Fault.NONE;
    volatile Framing framing = Framing.LENGTH;

    // --- exec behaviour ---
    /** false reproduces vfkit: no close frame ever arrives, and the fds are never closed. */
    volatile boolean sendCloseFrames = true;
    volatile String stdout = "";
    volatile String stderr = "";
    volatile int exitCode = 0;
    /** How long the "command" runs after all fds connect, before it writes output and exits. */
    volatile long runMillis = 0;
    /** How many /wait calls answer "Running" before the operation reports its result. */
    volatile int runningWaits = 0;
    /** Output sent on stdout <em>after</em> the operation completes, one chunk per gap. */
    volatile List<String> trailingStdout = List.of();
    volatile long trailingGapMillis = 0;
    /** Keep writing stdout after completion until the client hangs up (never idle). */
    volatile boolean trickleForever = false;

    final AtomicInteger connectionsAccepted = new AtomicInteger();
    /** "METHOD path" of every plain request that reached the server, in arrival order. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** Operation /wait long-polls answered. */
    final AtomicInteger waits = new AtomicInteger();
    /** PING frames received per exec fd ("0", "1", "2", "control"). */
    final Map<String, AtomicInteger> pingsByFd = new ConcurrentHashMap<>();

    private final Map<String, WsPeer> execFds = new ConcurrentHashMap<>();
    private volatile CountDownLatch operationDone = new CountDownLatch(1);

    LossyIncusServer() throws IOException {
        dir = Files.createTempDirectory("lossy");
        sock = dir.resolve("i.sock"); // short: macOS caps sun_path at 104 bytes
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock.toString()));
        Thread.ofVirtual().name("lossy-accept").start(this::acceptLoop);
    }

    String socketPath() { return sock.toString(); }

    int requestCount(String methodAndPathPrefix) {
        return (int) requests.stream().filter(r -> r.startsWith(methodAndPathPrefix)).count();
    }

    int pings(String fd) {
        var n = pingsByFd.get(fd);
        return n == null ? 0 : n.get();
    }

    @Override
    public void close() throws IOException {
        // Close the parked pooled connections for this socket, so they neither leak into the
        // next test's connection accounting nor linger against a deleted path.
        var pool = ConnectionPool.global();
        for (KeepAliveConnection c; (c = pool.borrow(socketPath())) != null; ) c.close();
        try { server.close(); } catch (IOException ignored) {}
        for (var c : accepted) {
            try { c.close(); } catch (IOException ignored) {}
        }
        Files.deleteIfExists(sock);
        Files.deleteIfExists(dir);
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                var conn = server.accept();
                accepted.add(conn);
                connectionsAccepted.incrementAndGet();
                Thread.ofVirtual().start(() -> serve(conn));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(SocketChannel conn) {
        try {
            if (fault == Fault.SILENT_ON_ACCEPT) {
                parkUntilClosed(conn);
                return;
            }
            var in = Channels.newInputStream(conn);
            var out = Channels.newOutputStream(conn);
            while (true) {
                var req = Request.read(in);
                if (req == null) break; // client closed
                if (req.upgrade()) {
                    serveWebSocket(conn, in, out, req);
                    return; // the fd owns the connection from here on
                }
                if (!servePlain(conn, in, out, req)) break;
            }
            conn.close();
        } catch (IOException ignored) {
            try { conn.close(); } catch (IOException e) { /* already gone */ }
        }
    }

    /** @return whether the connection stays open for another request. */
    private boolean servePlain(SocketChannel conn, InputStream in, OutputStream out, Request req)
            throws IOException {
        if (req.path().contains("/wait")) {
            waits.incrementAndGet();
            respond(out, 200, waitBody(), Framing.LENGTH, req.close());
            return !req.close();
        }
        requests.add(req.method() + " " + req.path());
        if (req.method().equals("POST") && req.path().endsWith("/exec")) {
            startOperation();
            respond(out, 202, execBody(), Framing.LENGTH, req.close());
            return !req.close();
        }
        switch (fault) {
            case STALL_AFTER_REQUEST -> {
                parkUntilClosed(conn);
                return false;
            }
            case TRUNCATE_BODY -> {
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n{\"partial\":").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return false;
            }
            default -> { }
        }
        var body = req.path().equals("/1.0")
                ? "{\"type\":\"sync\",\"status_code\":200,\"metadata\":{\"api_version\":\"1.0\"}}"
                : "{\"n\":" + requests.size() + "}";
        var effectiveFraming = framing;
        respond(out, 200, body, effectiveFraming, req.close());
        if (fault == Fault.DROP_AFTER_RESPONSE) return false;
        return !req.close() && effectiveFraming != Framing.EOF;
    }

    private static void respond(OutputStream out, int status, String body, Framing framing, boolean close)
            throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var head = new StringBuilder("HTTP/1.1 ").append(status).append(status == 202 ? " Accepted" : " OK").append("\r\n");
        head.append("Content-Type: application/json\r\n");
        if (close || framing == Framing.EOF) head.append("Connection: close\r\n");
        var buf = new ByteArrayOutputStream();
        switch (framing) {
            case LENGTH -> {
                head.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
                buf.writeBytes(head.toString().getBytes(StandardCharsets.US_ASCII));
                buf.writeBytes(bytes);
            }
            case CHUNKED -> {
                head.append("Transfer-Encoding: chunked\r\n\r\n");
                buf.writeBytes(head.toString().getBytes(StandardCharsets.US_ASCII));
                // Two chunks, to prove reassembly rather than a lucky single read.
                int half = bytes.length / 2;
                writeChunk(buf, bytes, 0, half);
                writeChunk(buf, bytes, half, bytes.length - half);
                buf.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            case EOF -> {
                head.append("\r\n");
                buf.writeBytes(head.toString().getBytes(StandardCharsets.US_ASCII));
                buf.writeBytes(bytes);
            }
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    private static void writeChunk(ByteArrayOutputStream buf, byte[] b, int off, int len) {
        buf.writeBytes((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        buf.write(b, off, len);
        buf.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------------- exec operation

    private void startOperation() {
        execFds.clear();
        operationDone = new CountDownLatch(1);
    }

    private static String execBody() {
        return """
                {"type":"async","status_code":100,"operation":"/1.0/operations/op1",
                 "metadata":{"id":"op1","status":"Running","metadata":{"fds":
                   {"0":"%s","1":"%s","2":"%s","control":"%s"}}}}
                """.formatted(FD_STDIN, FD_STDOUT, FD_STDERR, FD_CONTROL);
    }

    private String waitBody() {
        synchronized (this) {
            if (runningWaits > 0) {
                runningWaits--;
                return "{\"type\":\"sync\",\"status_code\":200,\"metadata\":{\"id\":\"op1\",\"status\":\"Running\"}}";
            }
        }
        try {
            operationDone.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "{\"type\":\"sync\",\"status_code\":200,\"metadata\":{\"id\":\"op1\",\"status\":\"Success\","
                + "\"metadata\":{\"return\":" + exitCode + "}}}";
    }

    private void serveWebSocket(SocketChannel conn, InputStream in, OutputStream out, Request req)
            throws IOException {
        if (fault == Fault.REJECT_UPGRADE) {
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            conn.close();
            return;
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: fake\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        var secret = req.path().substring(req.path().indexOf("secret=") + 7);
        var fd = switch (secret) {
            case FD_STDIN -> "0";
            case FD_STDOUT -> "1";
            case FD_STDERR -> "2";
            case FD_CONTROL -> "control";
            default -> "unknown";
        };
        var peer = new WsPeer(conn, out);
        if (execFds.put(fd, peer) == null && execFds.keySet().containsAll(List.of("0", "1", "2", "control"))) {
            // wait-for-websocket: Incus starts the command once every fd is connected.
            Thread.ofVirtual().start(this::runCommand);
        }
        readClientFrames(in, fd, peer);
    }

    private void runCommand() {
        try {
            if (runMillis > 0) Thread.sleep(runMillis);
            execFds.get("1").send(0x2, stdout.getBytes(StandardCharsets.UTF_8));
            execFds.get("2").send(0x2, stderr.getBytes(StandardCharsets.UTF_8));
            if (sendCloseFrames) {
                for (var fd : List.of("1", "2", "control")) execFds.get(fd).closeWithFrame();
            }
            operationDone.countDown();
            for (var chunk : trailingStdout) {
                Thread.sleep(trailingGapMillis);
                execFds.get("1").send(0x2, chunk.getBytes(StandardCharsets.UTF_8));
            }
            while (trickleForever) {
                Thread.sleep(50);
                execFds.get("1").send(0x2, ".".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | InterruptedException ignored) {
            // The client force-closed the fd: exactly how a trickle is meant to end.
        } finally {
            operationDone.countDown();
        }
    }

    /** Consume masked client frames, counting PINGs and answering them as Incus does. */
    private void readClientFrames(InputStream in, String fd, WsPeer peer) {
        try {
            while (true) {
                int b0 = in.read();
                if (b0 == -1) return;
                int opcode = b0 & 0x0F;
                int b1 = in.read();
                if (b1 == -1) return;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((in.read() & 0xFFL) << 8) | (in.read() & 0xFFL);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFFL);
                }
                var mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : null;
                var payload = in.readNBytes((int) len);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 0x8) return;
                if (opcode == 0x9) {
                    pingsByFd.computeIfAbsent(fd, k -> new AtomicInteger()).incrementAndGet();
                    peer.send(0xA, payload);
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** The server end of one exec fd; writes are serialized (output thread vs PONG replies). */
    private static final class WsPeer {
        private final SocketChannel conn;
        private final OutputStream out;

        WsPeer(SocketChannel conn, OutputStream out) {
            this.conn = conn;
            this.out = out;
        }

        synchronized void send(int opcode, byte[] payload) throws IOException {
            var frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else {
                frame.write(126);
                frame.write(payload.length >> 8);
                frame.write(payload.length & 0xFF);
            }
            frame.writeBytes(payload);
            out.write(frame.toByteArray());
            out.flush();
        }

        synchronized void closeWithFrame() throws IOException {
            send(0x8, new byte[0]);
            conn.close();
        }
    }

    private static void parkUntilClosed(SocketChannel conn) {
        // Hold the connection open and silent; a blocking read returns once either side closes.
        try {
            Channels.newInputStream(conn).transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }

    private record Request(String method, String path, boolean close, boolean upgrade) {
        static Request read(InputStream in) throws IOException {
            var line = readLine(in);
            if (line == null) return null;
            var parts = line.split(" ");
            boolean close = false, upgrade = false;
            int contentLength = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                var lower = h.toLowerCase();
                if (lower.startsWith("content-length:")) contentLength = Integer.parseInt(lower.substring(15).trim());
                if (lower.startsWith("connection:") && lower.contains("close")) close = true;
                if (lower.startsWith("upgrade:") && lower.contains("websocket")) upgrade = true;
            }
            if (contentLength > 0) in.readNBytes(contentLength);
            return new Request(parts[0], parts[1], close, upgrade);
        }

        private static String readLine(InputStream in) throws IOException {
            var sb = new StringBuilder();
            int c = in.read();
            if (c == -1) return null;
            while (c != -1 && c != '\n') {
                if (c != '\r') sb.append((char) c);
                c = in.read();
            }
            return sb.toString();
        }
    }
}
