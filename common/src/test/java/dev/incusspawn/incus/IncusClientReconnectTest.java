package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A client whose first connection fails connects on a later call (#2386). The proxy keeps one
 * client for its whole life; one that kept the failure never read Incus again, and refused every
 * credential proof until it was restarted.
 */
class IncusClientReconnectTest {

    private final FakeIncusDaemon daemon = new FakeIncusDaemon()
            .instance("box", "container", "Running", java.util.Map.of());
    private final AtomicBoolean up = new AtomicBoolean();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger connects = new AtomicInteger();
    private final AtomicInteger diagnoses = new AtomicInteger();

    private IncusClient client() {
        var api = new IncusApi(daemon);
        java.util.function.Supplier<IncusApi> connect = () -> {
            connects.incrementAndGet();
            return up.get() ? api : null;
        };
        return new IncusClient(connect, connect, () -> "diagnosis " + diagnoses.incrementAndGet(), now::get);
    }

    @Test
    void aFailedFirstConnectionIsTriedAgainOnceTheIntervalHasPassed() {
        var client = client();
        var first = assertThrows(IncusException.class, client::listJsonConfig);
        assertEquals("diagnosis 1", first.getMessage(), "a failure this call met is its own answer");
        assertEquals(1, connects.get());

        up.set(true);
        now.addAndGet(IncusClient.RECONNECT_INTERVAL_MS - 1);
        var meanwhile = assertThrows(IncusException.class, client::listJsonConfig,
                "within the interval a call does not connect, so a stopped VM costs no connect per call");
        assertEquals(1, connects.get());
        assertEquals(1, diagnoses.get(), "nor does it probe the socket again");
        assertEquals("This process's last connection to Incus failed; it tries again on a request 2 s or more after it."
                + " diagnosis 1", meanwhile.getMessage(), "it says this process's connection failed, not that the socket answers");

        now.incrementAndGet();
        assertEquals(1, client.listJsonConfig().asList().size(), "past the interval it connects");
        assertEquals(2, connects.get());
    }

    @Test
    void aConnectionIsKeptOnceMade() {
        up.set(true);
        var client = client();
        client.listJsonConfig();
        up.set(false);
        now.addAndGet(10 * IncusClient.RECONNECT_INTERVAL_MS);
        client.listJsonConfig();
        assertEquals(1, connects.get(), "the branch/start/shell path connects once per process, as before");
    }

    @Test
    void aRetryThatFailsAgainWaitsAnotherInterval() {
        var client = client();
        assertThrows(IncusException.class, client::listJsonConfig);
        now.addAndGet(IncusClient.RECONNECT_INTERVAL_MS);
        var again = assertThrows(IncusException.class, client::listJsonConfig);
        assertEquals("diagnosis 2", again.getMessage());
        assertEquals(2, connects.get());
        assertThrows(IncusException.class, client::listJsonConfig);
        assertEquals(2, connects.get(), "the interval counts from the last attempt");
        assertEquals("diagnosis 2", client.checkConnectivity(), "the reason of the last attempt, probed once");
    }
}
