package dev.incusspawn.incus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IncusApi} exec and probing against a {@link LossyIncusServer} that behaves like the
 * macOS vfkit tunnel: close frames and EOF never arrive, output keeps flowing after the process
 * exits, and a wedged tunnel accepts connections without answering. Each test pins one
 * compensation that otherwise only a Mac would exercise.
 */
class IncusApiLossyTunnelTest {

    private LossyIncusServer server;
    private int openBefore;

    @BeforeEach
    void start() throws IOException {
        server = new LossyIncusServer();
        openBefore = UnixSocketTransport.openConnectionCount();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        assertEquals(openBefore, UnixSocketTransport.openConnectionCount(),
                "every connection opened by the test must be closed and its permit released");
    }

    private IncusApi api() {
        return new IncusApi(new UnixSocketTransport(server.socketPath()));
    }

    private IncusApi api(IncusApi.DrainClock clock) {
        return new IncusApi(new UnixSocketTransport(server.socketPath()), IncusApi.WS_PING_INTERVAL_MS, clock);
    }

    private IncusClient.ExecResult exec(IncusApi api) {
        return api.execCapture("c1", List.of("true"), null, null, null, null);
    }

    @Test
    @Timeout(10)
    void healthyTunnelCompletesOnCloseFrames() {
        server.stdout = "out";
        server.stderr = "err";
        server.exitCode = 3;
        var result = exec(api());
        assertEquals(new IncusClient.ExecResult(3, "out", "err"), result);
    }

    @Test
    @Timeout(10)
    void execCompletesFromWaitWhenNoCloseFrameEverArrives() {
        server.sendCloseFrames = false; // vfkit: the fds stay open and silent forever
        server.stdout = "hello";
        server.stderr = "warning";
        server.exitCode = 7;
        long start = System.nanoTime();
        var result = exec(api());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(new IncusClient.ExecResult(7, "hello", "warning"), result,
                "the exit code comes from the operation /wait, the output from the drain");
        assertTrue(elapsedMs < 3000,
                "an idle drain must close the fds promptly, not wait out the ceiling: " + elapsedMs + "ms");
    }

    @Test
    @Timeout(10)
    void anOperationThatFailedHasNoExitCodeToReport() {
        // The command may never have run: a probe must not read that as a clean, empty answer.
        server.finalStatus = "Failure";
        assertEquals(-1, exec(api()).exitCode());
    }

    @Test
    @Timeout(10)
    void waitIsReissuedWhileTheOperationIsStillRunning() {
        server.runningWaits = 3; // three long-poll windows elapse before the command exits
        server.exitCode = 5;
        assertEquals(5, exec(api()).exitCode());
        assertEquals(4, server.waits.get(), "a Running answer must be re-polled, not taken as the result");
    }

    private IncusException execPastItsLimit() {
        return assertThrows(IncusException.class, () -> api().execStreamWithin("c1", List.of("su", "-", "agentuser"),
                null, null, null, null, null, null, java.time.Duration.ofMillis(300)));
    }

    @Test
    @Timeout(10)
    void aCommandPastItsLimitIsKilledAndGivenUpOn() {
        server.runsForever = true;
        server.diesOnSignal = true;
        long start = System.nanoTime();
        var e = execPastItsLimit();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(List.of(IncusApi.signalMessage(9)), server.controlMessages, "killed through the control fd");
        assertTrue(e.getMessage().contains("killed"), e.getMessage());
        assertTrue(elapsedMs < 2500, "the limit, rounded up to Incus's whole-second wait, then the kill: " + elapsedMs + "ms");
    }

    @Test
    @Timeout(15)
    void aWaitThatOverrunsItsSlackStillKillsTheCommand() throws Exception {
        server.runsForever = true;
        server.waitStallMillis = 4000; // past the bounded /wait's one-second poll plus its two of slack
        long start = System.nanoTime();
        execPastItsLimit();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 6000, "the host is free once the /wait overruns: " + elapsedMs + "ms");
        // The frame is sent before the throw; give the server's reader a moment to see it.
        for (int i = 0; i < 100 && server.controlMessages.isEmpty(); i++) Thread.sleep(10);
        assertEquals(List.of(IncusApi.signalMessage(9)), server.controlMessages,
                "the command is killed on this way out too, not left running in the guest");
    }

    @Test
    @Timeout(15)
    void aCommandTheKillDoesNotEndIsStillGivenUpOn() {
        server.runsForever = true; // a child it left holds its output: the operation never ends
        long start = System.nanoTime();
        var e = execPastItsLimit();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(List.of(IncusApi.signalMessage(9)), server.controlMessages, "killed once, not again and again");
        assertTrue(e.getMessage().contains("given up on"), e.getMessage());
        assertTrue(elapsedMs < (1 + IncusApi.KILL_GRACE_SECONDS + 2) * 1000,
                "bounded by the limit and the grace, never by the command: " + elapsedMs + "ms");
    }

    // The trailing chunks are timed by the drain's own polls, never by a sleep in the fake: a
    // sender woken late (#1208, a slow macOS runner) made a gap longer than the drain's window,
    // which the drain rightly read as the output having stopped.
    @Test
    @Timeout(10)
    void drainCapturesOutputThatArrivesAfterCompletion() {
        server.sendCloseFrames = false;
        server.stdout = "a";
        // Each gap is half the drain's idle window, so the drain must keep extending.
        var clock = new PacedClock(OutputDrain.IDLE_MS / 2, List.of("b", "c", "d", "e", "f").iterator(), true);
        assertEquals("abcdef", exec(api(clock)).stdout(),
                "output still in flight when /wait returns must not be truncated");
        // Bounded by the ceiling, not by the idle window: a chunk is stamped when a reader takes
        // it, so a late reader moves the drain's end out in drain time too.
        long drainMillis = clock.nanoTime() / 1_000_000;
        assertTrue(drainMillis < OutputDrain.MAX_MS / 2,
                "the drain must end once the output is idle, well before its ceiling: " + drainMillis + "ms of drain time");
    }

    /**
     * The drain's time, advanced only by its own pauses; every {@code gapMillis} of it, the pause
     * that reaches it sends the next chunk. However late any thread wakes, the chunks go out at
     * the drain times they were given, and what was sent is either read or still in the socket by
     * its next poll. With {@code realPauses}, every pause also sleeps for real so a reader can
     * take what was sent, and when the drain ends still depends on when a reader takes the last
     * chunk. Without them the drain takes no wall time to speak of, but a reader may never catch up.
     */
    private final class PacedClock implements IncusApi.DrainClock {
        private final long pausesPerChunk;
        private final Iterator<String> chunks;
        private final boolean realPauses;
        private long now;
        private long pauses;

        PacedClock(long gapMillis, Iterator<String> chunks, boolean realPauses) {
            if (gapMillis <= 0 || gapMillis % OutputDrain.POLL_MS != 0) {
                throw new IllegalArgumentException("a gap is a whole number of drain polls: " + gapMillis);
            }
            this.pausesPerChunk = gapMillis / OutputDrain.POLL_MS;
            this.chunks = chunks;
            this.realPauses = realPauses;
        }

        @Override
        public synchronized long nanoTime() {
            return now;
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            String due;
            synchronized (this) {
                now += millis * 1_000_000L;
                due = ++pauses % pausesPerChunk == 0 && chunks.hasNext() ? chunks.next() : null;
            }
            if (due != null) server.sendStdout(due);
            if (realPauses) Thread.sleep(millis);
        }
    }

    // #1208: bytes that reached the host are output, even while no reader has taken them yet. The
    // drain closed the socket once nothing had been read for its idle window, and closing a
    // socket discards what is waiting in it: a reader held up -- here by a slow sink, on the
    // macOS runner by a late wakeup -- lost the end of the output.
    @Test
    @Timeout(10)
    void drainKeepsOutputWaitingUnreadInTheSocket() {
        server.sendCloseFrames = false;
        server.stdout = "a";
        server.trailingStdout = List.of("b", "c");
        server.trailingInOneWrite = true; // "c" is in the host's socket before "b" is read
        var out = new ByteArrayOutputStream() {
            @Override
            public void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                if (new String(b, off, len, StandardCharsets.UTF_8).equals("b")) {
                    // Hold the reader until the drain has given up on the socket, or long past
                    // the point where an idle drain would have.
                    try {
                        server.stdoutHungUp.await(3 * OutputDrain.IDLE_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        assertEquals(0, api().execStream("c1", List.of("true"), null, null, null, null, out, null));
        assertEquals("abc", out.toString(StandardCharsets.UTF_8),
                "output waiting in the socket must be read before it is closed");
    }

    @Test
    @Timeout(10)
    void bytesNoReaderWillTakeDoNotHoldTheDrain() {
        server.sendCloseFrames = false;
        server.stdout = "a";
        server.trailingStdout = List.of("b", "c");
        server.trailingInOneWrite = true;
        var out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                if (b == 'b') throw new IOException("the sink went away"); // its reader ends, "c" stays in the socket
            }
        };
        long start = System.nanoTime();
        api().execStream("c1", List.of("true"), null, null, null, null, out, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < OutputDrain.MAX_MS / 2,
                "only a live reader's unread bytes extend the drain, not its ceiling: " + elapsedMs + "ms");
    }

    // Regression for #987: the fake decided "every fd is connected" per fd thread, so when all
    // four registered before any of them checked, each started the command and every byte was
    // sent four times. Real Incus starts the command once; so must the fake.
    @Test
    @Timeout(10)
    void commandStartsOnceWhenEveryFdRegistersBeforeAnyChecks() {
        server.sendCloseFrames = false;
        server.stdout = "a";
        server.stderr = "e";
        server.trailingStdout = List.of("b");
        var allRegistered = new CountDownLatch(4);
        var raceForced = new AtomicBoolean(true);
        server.afterFdRegistered = () -> {
            allRegistered.countDown();
            try {
                if (!allRegistered.await(5, TimeUnit.SECONDS)) raceForced.set(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var result = exec(api());
        assertTrue(raceForced.get(), "every fd must have registered before any of them checked");
        assertEquals(new IncusClient.ExecResult(0, "ab", "e"), result);
    }

    // #1084: the trickle is paced by the drain's polls too. A trickle on the fake's own sleep
    // could be woken late past the idle window, which the drain rightly read as the output
    // having stopped, and gave up seconds before its ceiling. Nor do the polls sleep for real:
    // a slow macOS runner overslept each one, and every poll up to the ceiling took the test
    // past its timeout. Bytes left unread extend the drain as much as bytes read.
    @Test
    @Timeout(10)
    void drainIsBoundedWhenOutputNeverStops() {
        server.sendCloseFrames = false;
        // Never idle, never closed: a chunk every half idle window, for as long as the drain polls.
        var clock = new PacedClock(OutputDrain.IDLE_MS / 2, Stream.generate(() -> ".").iterator(), false);
        assertEquals(0, exec(api(clock)).exitCode());
        long drainMillis = clock.nanoTime() / 1_000_000;
        assertTrue(drainMillis >= OutputDrain.MAX_MS && drainMillis < OutputDrain.MAX_MS + OutputDrain.POLL_MS,
                "the drain must extend while output flows and give up at its ceiling: " + drainMillis + "ms of drain time");
    }

    @Test
    @Timeout(10)
    void keepalivePingsReachEveryExecFd() {
        server.sendCloseFrames = false;
        server.runMillis = 600; // a quiet command: no output while it runs
        exec(new IncusApi(new UnixSocketTransport(server.socketPath()), 50));
        // Each fd is its own vsock stream and its own forwarder child in the VM, so an inactivity
        // reaper would collect whichever one went quiet -- they all have to be pinged.
        for (var fd : List.of("control", "1", "2")) {
            assertTrue(server.pings(fd) >= 2, "fd " + fd + " got " + server.pings(fd) + " pings");
        }
    }

    @Test
    @Timeout(10)
    void keepalivePingsReachAQuietStdin() throws IOException {
        server.sendCloseFrames = false;
        server.runMillis = 600;
        // An ssh-proxy session nobody types into: stdin stays open and sends nothing, and only
        // a ping keeps its forwarder child from reaping the connection.
        try (var quiet = new java.io.PipedInputStream(); var typist = new java.io.PipedOutputStream(quiet)) {
            // Hang up only near the end, so the exec need not wait out an abandoned stdin read.
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(500); typist.close(); } catch (Exception ignored) {}
            });
            new IncusApi(new UnixSocketTransport(server.socketPath()), 50).execBidirectional(
                    "c1", List.of("cat"), null, null, null, null,
                    quiet, OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
        }
        assertTrue(server.pings("0") >= 2, "stdin got " + server.pings("0") + " pings");
    }

    @Test
    @Timeout(10)
    void anAbandonedStdinReadDoesNotKeepItsSocket() throws IOException {
        server.sendCloseFrames = false;
        // Nobody ever closes this stdin, as with System.in under ssh-proxy: the exec abandons the
        // read after its grace. The stdin socket must close then, not when the read returns,
        // which stop() checks once the server is gone and the idle HTTP connections with it.
        try (var quiet = new java.io.PipedInputStream(); var typist = new java.io.PipedOutputStream(quiet)) {
            new IncusApi(new UnixSocketTransport(server.socketPath()), 50).execBidirectional(
                    "c1", List.of("cat"), null, null, null, null,
                    quiet, OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
            server.close();
            assertEquals(openBefore, UnixSocketTransport.openConnectionCount(),
                    "the stdin socket outlived the exec");
        }
    }

    /** An {@code isx shell} session against the server, pinging every 50 ms and cut after 300 ms of silence. */
    private boolean shell() throws IOException {
        var api = new IncusApi(new UnixSocketTransport(server.socketPath()), 50);
        api.shellSilenceLimit(300);
        server.interactive = true;
        // Nobody types: stdin ends at once, as a session left alone sends nothing.
        try (var stdin = java.nio.channels.FileChannel.open(Path.of("/dev/null"))) {
            return api.execPty("c1", List.of("bash"), null, null, null, null, 80, 24, null, stdin);
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hung session ignores interrupts
    void aShellOnASilentLinkIsCutAndReadAsALostConnection() throws IOException {
        // The client is alive, but nothing comes back: no output, and no PONG to its pings (#1996).
        // Without a silence limit the session, and the guest's shell with it, lasts forever.
        server.runsForever = true;
        server.answersPings = false;
        assertTrue(shell(), "a silent link must end the session as a lost connection, so the shell reconnects");
        assertTrue(server.pings("control") >= 2, "the control fd was pinged: " + server.pings("control"));
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hung session ignores interrupts
    void aQuietShellWhoseLinkAnswersIsNotCut() throws IOException {
        // Idle for well past the silence limit, but every ping draws a PONG: the link is there.
        // Ten pings 50 ms apart outlast the 300 ms limit. The shell ends on the tenth, not after
        // a fixed time: a slow runner's timer oversleeps each interval (#2012).
        server.runsUntilPinged = Map.of("control", 10);
        long start = System.nanoTime();
        assertFalse(shell(), "the shell exited by itself; that is no lost connection");
        long lastedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(lastedMillis > 300, "the session must outlast the silence limit: " + lastedMillis + "ms");
        assertTrue(server.pings("control") >= 10, "pinged throughout: " + server.pings("control"));
    }

    @Test
    @Timeout(10)
    void execFailsLoudlyWhenAnFdHandshakeIsCutOff() {
        // The tunnel hangs up on the fd WebSocket before answering the upgrade. This used to
        // "connect" a dead socket, so that stream's output came back empty with no error.
        server.fault = LossyIncusServer.Fault.CLOSE_BEFORE_RESPONSE;
        server.stdout = "must not vanish silently";
        var e = assertThrows(IncusException.class, () -> exec(api()));
        assertTrue(e.getMessage().contains("exec WebSocket"), e.getMessage());
    }

    @Test
    @Timeout(10)
    void tryConnectFindsAResponsiveSocket() {
        assertNotNull(IncusApi.tryConnect(List.of("/nonexistent/incus.sock", server.socketPath())));
    }

    @Test
    @Timeout(15)
    void tryConnectFailsFastAgainstASocketThatNeverAnswers() {
        server.fault = LossyIncusServer.Fault.SILENT_ON_ACCEPT;
        long start = System.nanoTime();
        assertNull(IncusApi.tryConnect(List.of(server.socketPath())));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 6000,
                "the probe must use its short timeout (3s), not the 30s request watchdog: " + elapsedMs + "ms");
    }

    // incusd stalls every new connection behind one that has sent nothing (its local listener
    // peeks 8 bytes inside Accept(), with no deadline), and vfkit may never deliver the close
    // that would release it. So the vsock diagnosis must speak, not merely connect.
    @Test
    @Timeout(10)
    void vsockDiagnosisSendsARequestRatherThanABareConnect() {
        var msg = IncusApi.diagnoseVsock(Path.of(server.socketPath()));
        assertTrue(msg.contains("is accessible"), msg);
        assertEquals(1, server.requestCount("GET /1.0"),
                "a connection that never sends a request can wedge incusd behind the tunnel");
    }

    @Test
    @Timeout(15)
    void vsockDiagnosisIsBoundedAgainstASocketThatNeverAnswers() {
        server.fault = LossyIncusServer.Fault.SILENT_ON_ACCEPT;
        long start = System.nanoTime();
        var msg = IncusApi.diagnoseVsock(Path.of(server.socketPath()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(msg.contains("did not answer"), msg);
        assertTrue(elapsedMs < 6000, "the diagnosis must use the probe timeout: " + elapsedMs + "ms");
    }

    @Test
    @Timeout(10)
    void vsockDiagnosisReportsASocketNobodyListensOn() throws IOException {
        var dir = Files.createTempDirectory("vsock");
        var sock = dir.resolve("v.sock");
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(sock)); // leaves the file behind once closed
        }
        try {
            assertTrue(IncusApi.diagnoseVsock(sock).contains("not accepting connections"));
        } finally {
            Files.deleteIfExists(sock);
            Files.deleteIfExists(dir);
        }
    }
}
