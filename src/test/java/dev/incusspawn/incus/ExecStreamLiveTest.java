package dev.incusspawn.incus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live validation of the unified /wait-driven exec ({@link IncusApi#execWebSocket}) against
 * a real container over the macOS vsock tunnel. Exercises the streaming paths the unit tests
 * can't: high-throughput output, a long idle stream (keepalive must hold the data fds open),
 * and bidirectional stdin/stdout.
 *
 * Skipped automatically when Incus is unreachable. Target a specific container with
 *   -Disx.test.container=isx-validate-exec
 * otherwise the first Running container is used.
 *
 *   mvn test -Dtest=ExecStreamLiveTest -Disx.test.container=isx-validate-exec
 */
class ExecStreamLiveTest {

    private static IncusApi http;
    private static String container;

    @BeforeAll
    static void connect() {
        http = IncusApi.tryConnect();
        if (http == null) {
            System.out.println("Skipping exec stream live tests: Incus not reachable.");
            return;
        }
        container = System.getProperty("isx.test.container");
        if (container == null || container.isBlank()) {
            container = firstRunningContainer();
        }
        System.out.println("ExecStreamLiveTest target container: " + container);
    }

    private static boolean skip() {
        return http == null || container == null;
    }

    private static String firstRunningContainer() {
        var resp = http.get("/1.0/instances?recursion=2");
        if (!resp.isSuccess()) return null;
        for (var inst : resp.body().path("metadata")) {
            if ("Running".equalsIgnoreCase(inst.path("status").asText())
                    && "container".equalsIgnoreCase(inst.path("type").asText())) {
                return inst.path("name").asText();
            }
        }
        return null;
    }

    @Test
    @Timeout(30)
    void captureReturnsOutputAndExitCode() {
        if (skip()) return;
        var r = http.execCapture(container, List.of("sh", "-c", "echo hello-capture; exit 0"),
                0, 0, null, Map.of());
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("hello-capture"), "stdout was: " + r.stdout());

        var fail = http.execCapture(container, List.of("sh", "-c", "exit 7"), 0, 0, null, Map.of());
        assertEquals(7, fail.exitCode(), "exit code must propagate through /wait");
    }

    /** High-activity stream: many lines must all arrive, in order, with no truncation. */
    @Test
    @Timeout(120)
    void streamHighActivityIsCompleteAndOrdered() {
        if (skip()) return;
        int n = 200_000;
        var counter = new CountingStream();
        int exit = http.execStream(container,
                List.of("sh", "-c", "i=0; while [ $i -lt " + n + " ]; do echo line$i; i=$((i+1)); done"),
                0, 0, null, Map.of(), counter, OutputStream.nullOutputStream());
        assertEquals(0, exit);
        assertEquals(n, counter.lines.get(),
                "expected " + n + " lines, got " + counter.lines.get() + " (truncation?)");
        // Spot-check ordering/integrity: first and last lines present and correctly framed.
        assertTrue(counter.text().startsWith("line0\n"), "first line wrong");
        assertTrue(counter.text().endsWith("line" + (n - 1) + "\n"), "last line wrong/truncated");
    }

    /**
     * Long idle stream: the command produces nothing for well over the keepalive interval,
     * then emits a marker. The data fd must stay open (keepalive) and the final output must
     * not be lost or the exit code misread.
     */
    @Test
    @Timeout(150)
    void streamLongIdleThenOutputSurvives() {
        if (skip()) return;
        var out = new ByteArrayOutputStream();
        long t0 = System.currentTimeMillis();
        int exit = http.execStream(container,
                List.of("sh", "-c", "sleep 90; echo AFTER_IDLE_MARKER"),
                0, 0, null, Map.of(), out, OutputStream.nullOutputStream());
        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        assertEquals(0, exit, "exit code after long idle");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("AFTER_IDLE_MARKER"),
                "marker lost after " + elapsed + "s idle; got: " + out);
        assertTrue(elapsed >= 88, "command returned too early (" + elapsed + "s) — premature close?");
    }

    /** Bidirectional: bytes written to stdin must echo back through stdout. */
    @Test
    @Timeout(30)
    void bidirectionalEchoesStdin() {
        if (skip()) return;
        var stdin = new ByteArrayInputStream("ping-bidir\n".getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        int exit = http.execBidirectional(container, List.of("cat"),
                0, 0, null, Map.of(), stdin, out, OutputStream.nullOutputStream());
        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("ping-bidir"),
                "stdin not echoed through bidirectional exec; got: " + out);
    }

    /** OutputStream that counts newlines and keeps the bytes for spot-checks. */
    private static final class CountingStream extends OutputStream {
        final AtomicLong lines = new AtomicLong();
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override public void write(int b) {
            if (b == '\n') lines.incrementAndGet();
            buf.write(b);
        }

        @Override public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) if (b[i] == '\n') lines.incrementAndGet();
            buf.write(b, off, len);
        }

        String text() { return buf.toString(StandardCharsets.UTF_8); }
    }
}
