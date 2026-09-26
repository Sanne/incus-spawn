package dev.incusspawn.incus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void waitIsReissuedWhileTheOperationIsStillRunning() {
        server.runningWaits = 3; // three long-poll windows elapse before the command exits
        server.exitCode = 5;
        assertEquals(5, exec(api()).exitCode());
        assertEquals(4, server.waits.get(), "a Running answer must be re-polled, not taken as the result");
    }

    @Test
    @Timeout(10)
    void drainCapturesOutputThatArrivesAfterCompletion() {
        server.sendCloseFrames = false;
        server.stdout = "a";
        // Each gap is shorter than the drain's idle window, so the drain must keep extending.
        server.trailingStdout = List.of("b", "c", "d", "e", "f");
        server.trailingGapMillis = 60;
        assertEquals("abcdef", exec(api()).stdout(),
                "output still in flight when /wait returns must not be truncated");
    }

    @Test
    @Timeout(20)
    void drainIsBoundedWhenOutputNeverStops() {
        server.sendCloseFrames = false;
        server.trickleForever = true; // never idle, never closed
        long start = System.nanoTime();
        var result = exec(api());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(0, result.exitCode());
        assertTrue(elapsedMs >= 4000 && elapsedMs < 10_000,
                "the drain must extend while output flows and give up at its ceiling (5s): " + elapsedMs + "ms");
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
