package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fan-out of bounded execs, such as the overview's read of every running instance (#2527), runs
 * only as many at once as the transport's connection cap carries with every connection each holds.
 * Unadmitted, thirty of them took every permit with their sockets, and a finished command's
 * {@code /wait} waited for one past the five seconds Incus keeps a finished operation.
 */
class ExecAdmissionTest {

    private static final int INSTANCES = 30;

    /** Counts the execs posted and not yet seen finished, holding each {@code /wait} a while. */
    private static final class Counting implements IncusTransport {
        private final FakeIncusDaemon daemon;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        Counting(FakeIncusDaemon daemon) { this.daemon = daemon; }

        @Override
        public RawResponse request(String method, String path, String contentType,
                                   Map<String, String> headers, byte[] body) throws IOException {
            if (path.contains("/wait")) {
                try {
                    Thread.sleep(100); // the command runs; outside the daemon's lock
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                var answer = daemon.request(method, path, contentType, headers, body);
                inFlight.decrementAndGet();
                return answer;
            }
            var answer = daemon.request(method, path, contentType, headers, body);
            if (method.equals("POST") && path.endsWith("/exec")) {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            }
            return answer;
        }

        @Override
        public RawResponse request(String method, String path, String contentType,
                                   Map<String, String> headers, Path bodyFile) throws IOException {
            return daemon.request(method, path, contentType, headers, bodyFile);
        }

        @Override
        public WsConnection openWebSocket(String wsPath) throws IOException {
            return daemon.openWebSocket(wsPath);
        }
    }

    @Test
    @Timeout(30)
    void aFanOutOfProbesRunsWithinTheConnectionCap() throws Exception {
        var daemon = new FakeIncusDaemon().execOutput("probe", "ok\n");
        for (int i = 0; i < INSTANCES; i++) daemon.container("c" + i, Map.of());
        var transport = new Counting(daemon);
        var incus = new IncusClient(new IncusApi(transport));

        var exits = new ArrayList<Future<Integer>>();
        var outputs = new ArrayList<ByteArrayOutputStream>();
        var longestQueued = new java.util.concurrent.atomic.AtomicLong();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < INSTANCES; i++) {
                var out = new ByteArrayOutputStream();
                outputs.add(out);
                var name = "c" + i;
                exits.add(pool.submit(() -> {
                    long before = IncusClient.probeQueueNanos();
                    int exit = incus.execProbe(name, 1000, "/home/agentuser", "probe", out, Duration.ofSeconds(10));
                    longestQueued.accumulateAndGet(IncusClient.probeQueueNanos() - before, Math::max);
                    return exit;
                }));
            }
        }

        for (int i = 0; i < INSTANCES; i++) {
            assertEquals(0, exits.get(i).get(), "c" + i + " lost its exit code");
            assertEquals("ok\n", outputs.get(i).toString(), "c" + i + " lost its output");
        }
        int peak = transport.peak.get();
        assertTrue(peak <= UnixSocketTransport.CONCURRENT_EXECS,
                peak + " execs ran at once, more than the " + UnixSocketTransport.CONCURRENT_EXECS
                        + " whose connections fit in the cap of " + UnixSocketTransport.connectionCap());
        assertTrue(peak > 1, "the probes ran one at a time: " + peak);
        // A probe that waited for its turn says so, so a caller timing the guest can leave it out.
        assertTrue(longestQueued.get() >= Duration.ofMillis(50).toNanos(),
                "no probe reported its wait behind the others: " + longestQueued.get() + " ns");
    }
}
