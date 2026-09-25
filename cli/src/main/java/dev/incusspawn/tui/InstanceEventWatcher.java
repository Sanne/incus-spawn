package dev.incusspawn.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.IncusEventStream;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Follows the Incus lifecycle event feed so the TUI notices instances that were created,
 * started, stopped, renamed or deleted by another process, without polling.
 *
 * <p>A daemon thread holds one {@code /1.0/events} subscription and reports each relevant
 * {@link Change} to the listener. Every successful (re)connect also fires {@code onResync}: events
 * that happened while no subscription was open are lost, so the only safe reaction to gaining a
 * subscription is one full re-read. The very first connect counts too, which covers the window
 * between the TUI's initial load and the subscription going live. After a drop it reconnects with
 * exponential backoff; while disconnected {@link #isConnected()} is false, which is the TUI's cue
 * to fall back to slow polling.
 *
 * <p>Live updates are a convenience and must never cost the Incus channel anything, so the watcher
 * gives up rather than keep knocking: after {@link #MAX_CONSECUTIVE_FAILURES} failures in a row --
 * a connect that fails, or a subscription that drops within {@link #HEALTHY_SESSION_MS} -- it stops
 * for good and calls {@code onGiveUp}. Each attempt is a new connection, which on macOS is a vsock
 * stream through the appliance's forwarder, where closed streams can linger until reaped; a
 * reconnect loop left running for days would keep adding to them.
 *
 * <p>Listener callbacks run on the watcher thread, so they must only flip flags for the UI thread.
 */
public final class InstanceEventWatcher implements AutoCloseable {

    /** One instance lifecycle event: the Incus action (e.g. {@code instance-started}) and the instance name. */
    public record Change(String action, String name) {
        /**
         * Whether the instance has just come (back) up. Its IPv4 is typically assigned a few
         * seconds after this event, and no further event announces the address, so the
         * listener schedules a follow-up read.
         */
        public boolean isStart() {
            return STARTS.contains(action);
        }
    }

    // State changes the instance list shows. Deliberately excludes the chatty read-only and
    // interactive actions (instance-exec, instance-console*, instance-file-*, ...): the TUI's own
    // shells and the proxy's health probes generate those constantly and they change no row.
    static final Set<String> RELEVANT_ACTIONS = Set.of(
            "instance-created", "instance-deleted", "instance-renamed", "instance-updated",
            "instance-started", "instance-stopped", "instance-shutdown", "instance-restarted",
            "instance-paused", "instance-resumed", "instance-restored", "instance-migrated",
            "instance-ready", "instance-agent-started");
    private static final Set<String> STARTS = Set.of(
            "instance-started", "instance-restarted", "instance-resumed");

    private static final ObjectMapper JSON = new ObjectMapper();
    static final long INITIAL_BACKOFF_MS = 2_000;
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final long HEALTHY_SESSION_MS = 60_000;

    private final Supplier<IncusEventStream> opener;
    private final Consumer<Change> onChange;
    private final Runnable onResync;
    private final Runnable onGiveUp;
    private final long initialBackoffMs;
    private final long healthySessionMs;
    private final Thread thread;
    private final Object lock = new Object();
    private IncusEventStream current;       // guarded by lock
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile boolean gaveUp;

    /**
     * @param opener   opens a fresh lifecycle subscription; may throw when Incus is unreachable
     * @param onChange called for every relevant instance event
     * @param onResync called whenever a subscription (re)opens, so missed events are re-read
     * @param onGiveUp called once if the watcher stops retrying (not when {@link #close()}d)
     */
    public InstanceEventWatcher(Supplier<IncusEventStream> opener, Consumer<Change> onChange,
                                Runnable onResync, Runnable onGiveUp) {
        this(opener, onChange, onResync, onGiveUp, INITIAL_BACKOFF_MS, HEALTHY_SESSION_MS);
    }

    // Package-private for testing: the real thresholds would make give-up tests take minutes.
    InstanceEventWatcher(Supplier<IncusEventStream> opener, Consumer<Change> onChange,
                         Runnable onResync, Runnable onGiveUp, long initialBackoffMs, long healthySessionMs) {
        this.initialBackoffMs = initialBackoffMs;
        this.healthySessionMs = healthySessionMs;
        this.opener = opener;
        this.onChange = onChange;
        this.onResync = onResync;
        this.onGiveUp = onGiveUp;
        this.thread = new Thread(this::run, "incus-events");
        this.thread.setDaemon(true);
    }

    public InstanceEventWatcher start() {
        thread.start();
        return this;
    }

    /** Whether a subscription is currently open. False while (re)connecting, after close or giving up. */
    public boolean isConnected() {
        return connected;
    }

    /** Whether the watcher stopped retrying after repeated failures. */
    public boolean hasGivenUp() {
        return gaveUp;
    }

    private void run() {
        long backoff = initialBackoffMs;
        int failures = 0;
        while (!closed) {
            IncusEventStream stream = null;
            long connectedAt = -1;
            try {
                stream = opener.get();
                synchronized (lock) {
                    if (closed) break;
                    current = stream;
                }
                connected = true;
                connectedAt = System.nanoTime();
                onResync.run();
                String message;
                while ((message = stream.next()) != null) {
                    var change = parse(message);
                    if (change != null) onChange.accept(change);
                }
            } catch (Exception ignored) {
                // Unreachable daemon, dropped socket, or close() from another thread: all mean
                // "no subscription right now". Retried below unless we're closing.
            } finally {
                connected = false;
                synchronized (lock) {
                    current = null;
                }
                if (stream != null) stream.close();
            }
            if (closed) break;
            boolean healthy = connectedAt >= 0
                    && (System.nanoTime() - connectedAt) / 1_000_000 >= healthySessionMs;
            if (healthy) {
                // A long-lived subscription that finally dropped (daemon restart, sleep/resume)
                // starts a fresh budget.
                failures = 0;
                backoff = initialBackoffMs;
            } else if (++failures >= MAX_CONSECUTIVE_FAILURES) {
                gaveUp = true;
                onGiveUp.run();
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff *= 2;
        }
        connected = false;
    }

    /** Parse one event message; null unless it's a lifecycle event the instance list cares about. */
    static Change parse(String message) {
        JsonNode node;
        try {
            node = JSON.readTree(message);
        } catch (Exception e) {
            return null;
        }
        if (node == null || !"lifecycle".equals(node.path("type").asText())) return null;
        var metadata = node.path("metadata");
        var action = metadata.path("action").asText("");
        if (!RELEVANT_ACTIONS.contains(action)) return null;
        var name = metadata.path("name").asText("");
        if (name.isEmpty()) {
            // Older daemons carry the instance only in the source URL.
            var source = metadata.path("source").asText("");
            var prefix = "/1.0/instances/";
            if (source.startsWith(prefix)) {
                name = source.substring(prefix.length());
                int q = name.indexOf('?');
                if (q >= 0) name = name.substring(0, q);
            }
        }
        return new Change(action, name);
    }

    /** Stop following events. Unblocks a pending read; safe to call more than once. */
    @Override
    public void close() {
        closed = true;
        IncusEventStream stream;
        synchronized (lock) {
            stream = current;
        }
        if (stream != null) stream.close();
        thread.interrupt();
    }
}
