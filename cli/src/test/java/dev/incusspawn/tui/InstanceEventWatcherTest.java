package dev.incusspawn.tui;

import dev.incusspawn.incus.IncusEventStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InstanceEventWatcherTest {

    private static String event(String action, String name) {
        return "{\"type\":\"lifecycle\",\"metadata\":{\"action\":\"" + action + "\",\"name\":\"" + name
                + "\",\"source\":\"/1.0/instances/" + name + "\",\"project\":\"default\"}}";
    }

    @Test
    void parsesRelevantLifecycleEvents() {
        assertEquals(new InstanceEventWatcher.Change("instance-deleted", "dev-1"),
                InstanceEventWatcher.parse(event("instance-deleted", "dev-1")));
        assertTrue(InstanceEventWatcher.parse(event("instance-started", "dev-1")).isStart());
        assertTrue(InstanceEventWatcher.parse(event("instance-restarted", "dev-1")).isStart());
        assertFalse(InstanceEventWatcher.parse(event("instance-stopped", "dev-1")).isStart());
    }

    @Test
    void ignoresChattyAndForeignEvents() {
        // A shell or the proxy's probes produce these constantly; none of them changes a row.
        assertNull(InstanceEventWatcher.parse(event("instance-exec", "dev-1")));
        assertNull(InstanceEventWatcher.parse(event("instance-file-pushed", "dev-1")));
        assertNull(InstanceEventWatcher.parse(event("instance-console", "dev-1")));
        assertNull(InstanceEventWatcher.parse(event("storage-pool-updated", "default")));
        assertNull(InstanceEventWatcher.parse("{\"type\":\"logging\",\"metadata\":{\"message\":\"x\"}}"));
        assertNull(InstanceEventWatcher.parse("not json"));
    }

    @Test
    void fallsBackToSourceUrlForTheName() {
        var change = InstanceEventWatcher.parse(
                "{\"type\":\"lifecycle\",\"metadata\":{\"action\":\"instance-renamed\","
                        + "\"source\":\"/1.0/instances/new-name?project=default\"}}");
        assertEquals("new-name", change.name());
    }

    /** A scripted subscription: messages are fed through a queue, and close() ends it. */
    private static final class FakeStream implements IncusEventStream {
        private static final String EOF = "\u0000eof";
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        volatile boolean closed;

        @Override
        public String next() throws java.io.IOException {
            try {
                var m = messages.take();
                if (m == EOF) return null;
                return m;
            } catch (InterruptedException e) {
                throw new java.io.IOException("interrupted");
            }
        }

        void drop() {
            messages.add(EOF);
        }

        @Override
        public void close() {
            closed = true;
            messages.add(EOF);
        }
    }

    @Test
    @Timeout(10)
    void deliversChangesAndResyncsOnEveryConnect() throws Exception {
        var streams = new LinkedBlockingQueue<FakeStream>();
        var changes = new LinkedBlockingQueue<InstanceEventWatcher.Change>();
        var resyncs = new AtomicInteger();
        try (var watcher = new InstanceEventWatcher(() -> {
                    var s = new FakeStream();
                    streams.add(s);
                    return s;
                }, changes::add, resyncs::incrementAndGet, () -> {}, 50, 60_000).start()) {
            var first = streams.poll(5, TimeUnit.SECONDS);
            first.messages.add(event("instance-exec", "a"));
            first.messages.add(event("instance-stopped", "a"));
            assertEquals("instance-stopped", changes.poll(5, TimeUnit.SECONDS).action());
            assertTrue(watcher.isConnected());
            // The first connect resyncs too: events between the initial load and the subscription
            // going live would otherwise be lost.
            assertEquals(1, resyncs.get());

            // Dropped connection: reconnects after the backoff and resyncs again.
            first.drop();
            var second = streams.poll(5, TimeUnit.SECONDS);
            assertNotNull(second, "should reconnect");
            second.messages.add(event("instance-deleted", "a"));
            assertEquals("instance-deleted", changes.poll(5, TimeUnit.SECONDS).action());
            assertEquals(2, resyncs.get());
            assertTrue(changes.isEmpty(), "the exec event must have been filtered");
        }
    }

    @Test
    @Timeout(10)
    void givesUpAfterRepeatedConnectFailures() throws Exception {
        var attempts = new AtomicInteger();
        var resyncs = new AtomicInteger();
        var gaveUp = new java.util.concurrent.CountDownLatch(1);
        try (var watcher = new InstanceEventWatcher(() -> {
                    attempts.incrementAndGet();
                    throw new dev.incusspawn.incus.IncusException("unreachable");
                }, c -> {}, resyncs::incrementAndGet, gaveUp::countDown, 10, 60_000).start()) {
            assertTrue(gaveUp.await(5, TimeUnit.SECONDS), "should give up");
            assertTrue(watcher.hasGivenUp());
            assertFalse(watcher.isConnected());
            Thread.sleep(300);
            assertEquals(InstanceEventWatcher.MAX_CONSECUTIVE_FAILURES, attempts.get(),
                    "no further connection attempts after giving up");
            assertEquals(0, resyncs.get());
        }
    }

    @Test
    @Timeout(10)
    void subscriptionsThatDropEarlyCountAsFailures() throws Exception {
        // Connects fine but the stream ends at once, e.g. a forwarder closing it: a reconnect
        // loop in disguise, so it must hit the same limit.
        var attempts = new AtomicInteger();
        var gaveUp = new java.util.concurrent.CountDownLatch(1);
        try (var watcher = new InstanceEventWatcher(() -> {
                    attempts.incrementAndGet();
                    var s = new FakeStream();
                    s.drop();
                    return s;
                }, c -> {}, () -> {}, gaveUp::countDown, 10, 60_000).start()) {
            assertTrue(gaveUp.await(5, TimeUnit.SECONDS), "should give up");
            assertEquals(InstanceEventWatcher.MAX_CONSECUTIVE_FAILURES, attempts.get());
        }
    }

    @Test
    @Timeout(10)
    void aHealthySessionResetsTheFailureBudget() throws Exception {
        // Every session lasts past the (shortened) healthy threshold before dropping, so however
        // many times it drops, the watcher keeps reconnecting.
        var attempts = new AtomicInteger();
        var gaveUp = new java.util.concurrent.atomic.AtomicBoolean();
        try (var watcher = new InstanceEventWatcher(() -> {
                    attempts.incrementAndGet();
                    var s = new FakeStream();
                    Thread.ofVirtual().start(() -> {
                        try { Thread.sleep(60); } catch (InterruptedException ignored) {}
                        s.drop();
                    });
                    return s;
                }, c -> {}, () -> {}, () -> gaveUp.set(true), 5, 30).start()) {
            while (attempts.get() < InstanceEventWatcher.MAX_CONSECUTIVE_FAILURES + 3) Thread.sleep(10);
            assertFalse(gaveUp.get());
            assertFalse(watcher.hasGivenUp());
        }
    }

    @Test
    @Timeout(10)
    void closeDoesNotCountAsGivingUp() throws Exception {
        var gaveUp = new java.util.concurrent.atomic.AtomicBoolean();
        var streams = new LinkedBlockingQueue<FakeStream>();
        var watcher = new InstanceEventWatcher(() -> {
            var s = new FakeStream();
            streams.add(s);
            return s;
        }, c -> {}, () -> {}, () -> gaveUp.set(true), 10, 60_000).start();
        streams.poll(5, TimeUnit.SECONDS);
        watcher.close();
        Thread.sleep(200);
        assertFalse(gaveUp.get());
        assertFalse(watcher.hasGivenUp());
    }

    @Test
    @Timeout(10)
    void closeEndsTheSubscription() throws Exception {
        var streams = new LinkedBlockingQueue<FakeStream>();
        var watcher = new InstanceEventWatcher(() -> {
            var s = new FakeStream();
            streams.add(s);
            return s;
        }, c -> {}, () -> {}, () -> {}, 50, 60_000).start();
        var stream = streams.poll(5, TimeUnit.SECONDS);
        watcher.close();
        assertTrue(stream.closed);
        Thread.sleep(300);
        assertTrue(streams.isEmpty(), "must not reconnect after close");
        assertFalse(watcher.isConnected());
    }
}
