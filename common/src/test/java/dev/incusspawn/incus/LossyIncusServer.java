package dev.incusspawn.incus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Incus daemon on a real Unix socket that can misbehave the way the macOS vfkit vsock
 * tunnel did before isx-vm replaced it (#2031) -- no VM, no Mac.
 *
 * On macOS, Java only ever sees the VM's tunnel as a Unix socket; the transport workarounds in
 * {@link UnixSocketTransport}, {@link KeepAliveConnection}, {@link ConnectionPool} and
 * {@link IncusApi} exist because of how vfkit's tunnel <em>behaved</em> (close frames and EOF that
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
        /** Cut the response off partway through its headers. */
        TRUNCATE_HEADERS,
        /** Start a chunked body, then drop the connection before its last chunk. */
        TRUNCATE_CHUNKED,
        /** Read the request (plain or upgrade), then close without a single byte of response. */
        CLOSE_BEFORE_RESPONSE,
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
    /** false reproduces vfkit's tunnel: no close frame ever arrives, and the fds are never closed. */
    volatile boolean sendCloseFrames = true;
    volatile String stdout = "";
    volatile String stderr = "";
    volatile int exitCode = 0;
    /** How the operation ends: Success carries {@link #exitCode}; Failure or Cancelled carry none. */
    volatile String finalStatus = "Success";
    /** How long the "command" runs after all fds connect, before it writes output and exits. */
    volatile long runMillis = 0;
    /**
     * When set, the command runs until each named fd has drawn that many PINGs, after any
     * {@link #runMillis}. A test counting pings is then paced by the client's pings themselves,
     * never by how promptly a slow runner wakes the client's ping timer (#2012). The wait gives up
     * after {@link #PING_WAIT_MS}, so a client that stops pinging fails the test's own assertion
     * rather than its timeout, and the command's thread never outlives the test.
     */
    volatile Map<String, Integer> runsUntilPinged = Map.of();
    private static final long PING_WAIT_MS = 5_000;
    /** How many /wait calls answer "Running" before the operation reports its result. */
    volatile int runningWaits = 0;
    /** Output sent on stdout <em>after</em> the operation completes, one chunk per write. */
    volatile List<String> trailingStdout = List.of();
    /** The trailing chunks go out together, in one write: each but the first waits in the host's socket. */
    volatile boolean trailingInOneWrite = false;
    /** The command never ends by itself: a login profile that never returns. */
    volatile boolean runsForever = false;
    /** With {@link #runsForever}, a signal on the control fd ends it, as Incus's does a killable process. */
    volatile boolean diesOnSignal = false;
    /**
     * An interactive exec, as {@code isx shell} starts: only fds "0" and "control", started once
     * both connect. Its command ends after {@link #runMillis} or {@link #runsUntilPinged} by
     * closing both, unless it {@link #runsForever}.
     */
    volatile boolean interactive = false;
    /** When non-null on an interactive exec, control stays open until this latch opens (or 5 s). */
    volatile CountDownLatch interactiveCloseGate = null;
    /**
     * PINGs answered per fd before the link goes silent: later client PINGs still arrive and are
     * counted, but no PONG comes back. A test that wants a silent link pinged a number of times
     * sets that number here, so the pings it counts came before the silence by construction.
     */
    volatile int answersPings = Integer.MAX_VALUE;
    /** Each operation /wait is held this long before it is answered: a tunnel slower than its slack. */
    volatile long waitStallMillis = 0;
    /** The HTTP status every operation /wait answers; 404 is an operation lost with its instance. */
    volatile int waitHttpStatus = 200;
    /**
     * What {@code GET /1.0/instances/<name>} answers: the instance's status, or null for an
     * instance that is gone (404).
     */
    volatile String instanceStatus = "Running";
    /** The instance read is held this long before it is answered: a stalled daemon. */
    volatile long instanceStallMillis = 0;
    /** Data messages the client sent on the control fd, in order. */
    final List<String> controlMessages = new CopyOnWriteArrayList<>();
    /**
     * Runs on each fd's server thread after it registers, before deciding whether to start the
     * command. That fd's client frames (and PINGs) go unread while it blocks.
     */
    volatile Runnable afterFdRegistered = () -> { };
    /** Runs on an exec fd's server thread before its upgrade is answered: the client's handshake waits. */
    volatile Runnable beforeUpgrade = () -> { };
    /** false: the stderr fd gets no close frame and stays open, while stdout and control close. */
    volatile boolean closesStderr = true;
    /** The exec fd ("0", "1", "2" or "control") whose upgrade is refused with 403, or null. */
    volatile String rejectsFd = null;
    /** Operation-event subscriptions ({@code /1.0/events?type=operation}) opened, in all. */
    final AtomicInteger eventSubscriptions = new AtomicInteger();
    private final List<WsPeer> eventPeers = new CopyOnWriteArrayList<>();

    final AtomicInteger connectionsAccepted = new AtomicInteger();
    /** "METHOD path" of every plain request that reached the server, in arrival order. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** Operation /wait long-polls answered. */
    final AtomicInteger waits = new AtomicInteger();
    /** PING frames received per exec fd ("0", "1", "2", "control"). */
    final Map<String, AtomicInteger> pingsByFd = new ConcurrentHashMap<>();
    /** Counted down when the client hangs up the stdout fd (closes it, with or without a close frame). */
    final CountDownLatch stdoutHungUp = new CountDownLatch(1);

    private final Map<String, WsPeer> execFds = new ConcurrentHashMap<>();
    private volatile CountDownLatch operationDone = new CountDownLatch(1);
    /** Claimed by whichever fd thread first sees every fd connected, so the command runs once. */
    private final AtomicBoolean commandStarted = new AtomicBoolean();

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
            if (waitHttpStatus != 200) {
                respond(out, waitHttpStatus, ERROR_BODY.formatted(waitHttpStatus), Framing.LENGTH, req.close());
                return !req.close();
            }
            respond(out, 200, waitBody(req.path()), Framing.LENGTH, req.close());
            return !req.close();
        }
        requests.add(req.method() + " " + req.path());
        if (req.method().equals("POST") && req.path().endsWith("/exec")) {
            startOperation();
            respond(out, 202, interactive ? INTERACTIVE_EXEC_BODY : execBody(), Framing.LENGTH, req.close());
            return !req.close();
        }
        if (req.method().equals("GET") && req.path().startsWith(INSTANCES)
                && req.path().indexOf('/', INSTANCES.length()) < 0) {
            if (instanceStallMillis > 0) {
                try {
                    Thread.sleep(instanceStallMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var status = instanceStatus;
            if (status == null) {
                respond(out, 404, ERROR_BODY.formatted(404), Framing.LENGTH, req.close());
            } else {
                respond(out, 200, "{\"type\":\"sync\",\"status_code\":200,\"metadata\":{\"status\":\""
                        + status + "\"}}", Framing.LENGTH, req.close());
            }
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
            case TRUNCATE_HEADERS -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Ty".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return false;
            }
            case TRUNCATE_CHUNKED -> {
                out.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "b\r\n{\"partial\":\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return false;
            }
            case CLOSE_BEFORE_RESPONSE -> {
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

    private static final String INSTANCES = "/1.0/instances/";
    private static final String ERROR_BODY = "{\"type\":\"error\",\"error_code\":%d,\"error\":\"not found\"}";

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
        commandStarted.set(false);
        operationDone = new CountDownLatch(1);
    }

    private static String execBody() {
        return """
                {"type":"async","status_code":100,"operation":"/1.0/operations/op1",
                 "metadata":{"id":"op1","status":"Running","metadata":{"fds":
                   {"0":"%s","1":"%s","2":"%s","control":"%s"}}}}
                """.formatted(FD_STDIN, FD_STDOUT, FD_STDERR, FD_CONTROL);
    }

    private static final String INTERACTIVE_EXEC_BODY = """
            {"type":"async","status_code":100,"operation":"/1.0/operations/op1",
             "metadata":{"id":"op1","status":"Running","metadata":{"fds":
               {"0":"%s","control":"%s"}}}}
            """.formatted(FD_STDIN, FD_CONTROL);

    private String waitBody(String path) {
        var running = "{\"type\":\"sync\",\"status_code\":200,\"metadata\":{\"id\":\"op1\",\"status\":\"Running\"}}";
        if (waitStallMillis > 0) {
            try {
                Thread.sleep(waitStallMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (runningWaits > 0) {
                runningWaits--;
                return running;
            }
        }
        // The long-poll's own timeout, as Incus honours it: Running if the command is still going.
        var timeout = path.matches(".*[?&]timeout=\\d+.*")
                ? Long.parseLong(path.replaceAll(".*[?&]timeout=(\\d+).*", "$1")) : 30;
        try {
            if (!operationDone.await(Math.min(timeout, 30), TimeUnit.SECONDS)) return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!finalStatus.equals("Success")) {
            return "{\"type\":\"sync\",\"status_code\":200,\"metadata\":{\"id\":\"op1\",\"status\":\""
                    + finalStatus + "\",\"err\":\"forkexec failed\",\"metadata\":{}}}";
        }
        return "{\"type\":\"sync\",\"status_code\":200,\"metadata\":{\"id\":\"op1\",\"status\":\"Success\","
                + "\"metadata\":{\"return\":" + exitCode + "}}}";
    }

    private void serveWebSocket(SocketChannel conn, InputStream in, OutputStream out, Request req)
            throws IOException {
        if (fault == Fault.CLOSE_BEFORE_RESPONSE) {
            conn.close();
            return;
        }
        if (fault == Fault.REJECT_UPGRADE) {
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            conn.close();
            return;
        }
        if (req.path().startsWith("/1.0/events?type=operation")) {
            upgraded(out);
            var peer = new WsPeer(conn, out);
            eventPeers.add(peer);
            eventSubscriptions.incrementAndGet();
            readClientFrames(in, "events", peer);
            return;
        }
        var secret = req.path().substring(req.path().indexOf("secret=") + 7);
        var fd = switch (secret) {
            case FD_STDIN -> "0";
            case FD_STDOUT -> "1";
            case FD_STDERR -> "2";
            case FD_CONTROL -> "control";
            default -> "unknown";
        };
        beforeUpgrade.run();
        if (fd.equals(rejectsFd)) {
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            conn.close();
            return;
        }
        upgraded(out);
        var peer = new WsPeer(conn, out);
        execFds.put(fd, peer);
        afterFdRegistered.run();
        var fds = interactive ? List.of("0", "control") : List.of("0", "1", "2", "control");
        if (execFds.keySet().containsAll(fds)
                && commandStarted.compareAndSet(false, true)) {
            // wait-for-websocket: Incus starts the command once every fd is connected.
            Thread.ofVirtual().start(this::runCommand);
        }
        readClientFrames(in, fd, peer);
    }

    private static void upgraded(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Accept: fake\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** End every operation-event subscription, without a close frame: a link that dropped. */
    void dropEvents() {
        for (var peer : eventPeers) peer.drop();
        eventPeers.clear();
    }

    /** Tell every operation-event subscription that op1 ended, as Incus does. */
    private void announceEnd() {
        var event = finalStatus.equals("Success")
                ? "{\"type\":\"operation\",\"metadata\":{\"id\":\"op1\",\"status\":\"Success\",\"metadata\":{\"return\":" + exitCode + "}}}"
                : "{\"type\":\"operation\",\"metadata\":{\"id\":\"op1\",\"status\":\"" + finalStatus + "\",\"err\":\"forkexec failed\",\"metadata\":{}}}";
        for (var peer : eventPeers) {
            try {
                peer.send(0x1, event.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // a subscription the client closed
            }
        }
    }

    private void runCommand() {
        if (runsForever) return; // only a signal, if anything, ends it
        try {
            if (runMillis > 0) Thread.sleep(runMillis);
            if (interactive) { // the shell exited: Incus closes control, and the client hangs up fd "0"
                if (!stdout.isEmpty()) execFds.get("0").send(0x2, stdout.getBytes(StandardCharsets.UTF_8));
                awaitPings(runsUntilPinged);
                var gate = interactiveCloseGate;
                if (gate != null) gate.await(PING_WAIT_MS, TimeUnit.MILLISECONDS);
                execFds.get("control").closeWithFrame();
                return;
            }
            awaitPings(runsUntilPinged);
            execFds.get("1").send(0x2, stdout.getBytes(StandardCharsets.UTF_8));
            execFds.get("2").send(0x2, stderr.getBytes(StandardCharsets.UTF_8));
            if (sendCloseFrames) {
                for (var fd : List.of("1", "2", "control")) {
                    if (fd.equals("2") && !closesStderr) continue;
                    execFds.get(fd).closeWithFrame();
                }
            }
            operationDone.countDown();
            announceEnd();
            if (trailingInOneWrite) {
                execFds.get("1").sendAll(trailingStdout);
                return;
            }
            for (var chunk : trailingStdout) sendStdoutChunk(chunk);
        } catch (IOException | InterruptedException ignored) {
            // The client force-closed an fd, or the test is over: nothing more can be sent.
        } finally {
            operationDone.countDown();
        }
    }

    /** Block until each fd in {@code wanted} has drawn its number of PINGs, or {@link #PING_WAIT_MS} passed. */
    private void awaitPings(Map<String, Integer> wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PING_WAIT_MS);
        synchronized (pingsByFd) {
            while (wanted.entrySet().stream().anyMatch(e -> pings(e.getKey()) < e.getValue())) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) return;
                pingsByFd.wait(left);
            }
        }
    }

    /** Send a chunk of stdout now, from the caller's thread: output the test times itself. */
    void sendStdout(String chunk) {
        try {
            sendStdoutChunk(chunk);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void sendStdoutChunk(String chunk) throws IOException {
        execFds.get("1").send(0x2, chunk.getBytes(StandardCharsets.UTF_8));
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
                if ((opcode == 0x1 || opcode == 0x2) && fd.equals("control")) {
                    var message = new String(payload, StandardCharsets.UTF_8);
                    controlMessages.add(message);
                    if (diesOnSignal && message.contains("\"command\":\"signal\"")) {
                        exitCode = 137;
                        operationDone.countDown();
                    }
                }
                if (opcode == 0x9) {
                    try {
                        if (pings(fd) < answersPings) peer.send(0xA, payload);
                    } finally { // counted after its PONG went out, and even if the client is gone
                        synchronized (pingsByFd) {
                            pingsByFd.computeIfAbsent(fd, k -> new AtomicInteger()).incrementAndGet();
                            pingsByFd.notifyAll();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (fd.equals("1")) stdoutHungUp.countDown();
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
            out.write(frame(opcode, payload));
            out.flush();
        }

        /** One binary frame per chunk, all in a single write. */
        synchronized void sendAll(List<String> chunks) throws IOException {
            var frames = new ByteArrayOutputStream();
            for (var chunk : chunks) frames.writeBytes(frame(0x2, chunk.getBytes(StandardCharsets.UTF_8)));
            out.write(frames.toByteArray());
            out.flush();
        }

        private static byte[] frame(int opcode, byte[] payload) {
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
            return frame.toByteArray();
        }

        void drop() {
            try { conn.close(); } catch (IOException ignored) {}
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
