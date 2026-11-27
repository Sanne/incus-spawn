package dev.incusspawn.incus;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One {@code /1.0/events?type=operation} subscription that tells every exec of a fan-out when its
 * operation ended, in place of a {@code /wait} long-poll per exec (#2626). Each exec registers its
 * operation's id right after its post, before it opens its sockets: with {@code wait-for-websocket}
 * Incus starts the command only once they are connected, so the end cannot come first. An operation
 * that fails before that, or one nobody registered yet, is kept among the last few ends.
 *
 * <p>When the subscription ends, every waiter is told it is {@link #LOST} and goes back to
 * {@code /wait}, as does every exec registered after.
 */
final class OperationWatcher implements AutoCloseable {

    /** How an operation ended: its status, its own metadata (an exec's {@code return}) and Incus's reason when it failed. */
    record End(String status, JqValue metadata, String err) {}

    /** What {@link Waiter#await} answers once the subscription is gone: ask {@code /wait} instead. */
    static final End LOST = new End("", null, "");

    // Ends no waiter was registered for yet: bounded, since every client's operations are seen
    private static final int KEPT_ENDS = 256;

    private final IncusEventStream stream;
    private final Map<String, CompletableFuture<End>> waiters = new ConcurrentHashMap<>();
    private final Map<String, End> unclaimed = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, End> eldest) {
            return size() > KEPT_ENDS;
        }
    };
    private volatile boolean lost;

    OperationWatcher(IncusEventStream stream) {
        this.stream = stream;
        Thread.ofVirtual().name("incus-operation-events").start(this::read);
    }

    /** Whether the subscription has ended: a new batch opens another. */
    boolean lost() { return lost; }

    /** Watch for the end of operation {@code id}; {@link Waiter#close} when done with it. */
    Waiter register(String id) {
        var future = new CompletableFuture<End>();
        synchronized (unclaimed) {
            var end = unclaimed.remove(id);
            if (end != null) future.complete(end);
            else waiters.put(id, future);
        }
        if (lost) future.complete(LOST);
        return new Waiter(id, future);
    }

    final class Waiter implements AutoCloseable {
        private final String id;
        private final CompletableFuture<End> end;

        private Waiter(String id, CompletableFuture<End> end) {
            this.id = id;
            this.end = end;
        }

        /** The operation's end, {@link #LOST}, or null when it has not ended within {@code nanos}. */
        End await(long nanos) {
            try {
                return end.get(Math.max(nanos, 0), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IncusException("interrupted waiting for operation " + id);
            } catch (ExecutionException e) {
                return LOST; // never completed exceptionally; kept total
            }
        }

        @Override
        public void close() {
            waiters.remove(id, end);
        }
    }

    private void read() {
        try {
            String message;
            while ((message = stream.next()) != null) {
                End end;
                String id;
                try {
                    var event = JqValues.parseStrict(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (!"operation".equals(event.pathText("type", ""))) continue;
                    var op = event.getField("metadata");
                    id = op.pathText("id", "");
                    var status = op.pathText("status", "");
                    if (id.isEmpty() || IncusApi.isUnfinished(status) || "Cancelling".equals(status)) continue;
                    end = new End(status, op.getField("metadata"), op.pathText("err", ""));
                } catch (RuntimeException unreadable) {
                    continue; // an event we cannot read ends nothing; its exec's limit still holds
                }
                CompletableFuture<End> waiter;
                synchronized (unclaimed) {
                    waiter = waiters.remove(id);
                    if (waiter == null) unclaimed.put(id, end);
                }
                if (waiter != null) waiter.complete(end);
            }
        } catch (IOException ignored) {
            // the subscription is gone: every waiter asks /wait instead
        } finally {
            lost = true;
            stream.close();
            waiters.values().forEach(w -> w.complete(LOST));
        }
    }

    @Override
    public void close() {
        stream.close(); // ends read(), which tells every waiter left
    }
}
