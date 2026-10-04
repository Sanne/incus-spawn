package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Tells a client that opted in ({@link #CAPABILITY}) whenever one of the session's tasks changes
 * state: {@code running}, {@code finished}, {@code lost}, {@code attached}, or {@code released}
 * (another session adopted its instance). It remembers what it last reported per task, and the
 * paths that learn a task's state at no extra cost feed it, so a transition is reported once
 * however it was seen. A poller fills in the rest: tasks last reported running, or never, every
 * {@link #INTERVAL_MS}, and every {@link #SLOW_EVERY}th round also finished agents (a person
 * may join their conversation) and whether the session still holds each instance.
 *
 * <p>Nothing is polled or sent for a client that did not opt in: Claude Code would pay an exec
 * per instance every two seconds for messages it drops.
 */
final class TaskWatcher {

    static final String CAPABILITY = "isx/task_changed";
    static final String METHOD = "notifications/isx/task_changed";
    static final String RUNNING = "running";
    static final String LOST = "lost";
    static final String RELEASED = "released";
    /** The states a person joining or leaving an agent's conversation moves between. */
    static final java.util.Set<String> ATTACHABLE = java.util.Set.of("finished", "attached");
    static final long INTERVAL_MS = 2000;
    static final int SLOW_EVERY = 5;

    /** {@code forgotten}: the tombstone {@link #forget} leaves, which no later reading replaces. */
    private record Reported(String state, int run, Integer exit, boolean forgotten) {
        Reported(String state, int run, Integer exit) {
            this(state, run, exit, false);
        }
    }

    private final Consumer<Boolean> poll;
    /**
     * What was last reported per task. A forgotten task keeps a tombstone (marked, since
     * {@code lost} is also an ordinary state a later run moves on from): a reading taken before
     * it, arriving after, would tell the client the session still has the task. Until
     * {@link #revive}d.
     */
    private final Map<String, Reported> last = new HashMap<>();
    private volatile BiConsumer<String, JsonNode> sink;
    private volatile boolean enabled;
    private Thread poller;
    /** False in tests, which run each round themselves. */
    volatile boolean autoPoll = true;

    /** {@code poll} runs one round; its argument says whether it is a slow one. */
    TaskWatcher(Consumer<Boolean> poll) {
        this.poll = poll;
    }

    /** Where notifications go: the method and its params. */
    void sendTo(BiConsumer<String, JsonNode> sink) {
        this.sink = sink;
    }

    boolean enabled() {
        return enabled;
    }

    /** The client opted in: report from now on, and start polling. */
    synchronized void start() {
        if (enabled) return;
        enabled = true;
        if (autoPoll) poller = Thread.ofVirtual().name("isx-mcp-task-watch").start(this::loop);
    }

    synchronized void stop() {
        enabled = false;
        if (poller != null) poller.interrupt();
        poller = null;
    }

    /** Poll until stopped; the first round and every {@link #SLOW_EVERY}th is a slow one. */
    private void loop() {
        for (int round = 0; enabled; round++) {
            try {
                Thread.sleep(INTERVAL_MS);
                if (enabled) poll.accept(round % SLOW_EVERY == 0);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                System.err.println("isx mcp: watching tasks: " + e.getMessage());
            }
        }
    }

    /** What was last reported for the task, or null if nothing has been. */
    synchronized String lastState(String taskId) {
        var r = last.get(taskId);
        return r == null ? null : r.state();
    }

    /**
     * The task was seen in {@code state}; reported if that differs from what was last reported.
     * {@code unknown} says nothing about the task and is never reported.
     */
    synchronized void observe(String taskId, String instance, int run, String state, Integer exit) {
        if (!enabled || "unknown".equals(state)) return;
        var was = last.get(taskId);
        if (was != null && was.forgotten()) return;
        // A run never starts again once it ended: a reading saying so came from before it did.
        if (was != null && (run < was.run()
                || run == was.run() && RUNNING.equals(state) && was.state() != null && !RUNNING.equals(was.state()))) {
            return;
        }
        var now = new Reported(state, run, exit);
        if (!now.equals(last.put(taskId, now))) send(taskId, instance, now);
    }

    /**
     * The session no longer has the task: its instance is gone ({@link #LOST}, reported only if
     * it was running: a finished task lost nothing) or another session took it ({@link #RELEASED}).
     */
    synchronized void forget(String taskId, String instance, String reason) {
        if (!enabled) return;
        var was = last.get(taskId);
        var tombstone = new Reported(reason, was == null ? 0 : was.run(), null, true);
        last.put(taskId, tombstone);
        if (was == null || was.forgotten()) return;
        if (RELEASED.equals(reason) || RUNNING.equals(was.state())) send(taskId, instance, tombstone);
    }

    /** The task is this session's again (its instance was adopted back): report it from now on. */
    synchronized void revive(String taskId) {
        // Keep only the run, as a floor: a reading of an earlier one, taken before the forget, is
        // still stale, but whatever the task does now is news (and with no state it is polled).
        last.computeIfPresent(taskId, (k, r) -> new Reported(null, r.run(), null));
    }

    private void send(String taskId, String instance, Reported r) {
        var s = sink;
        if (s == null) return;
        var params = JsonRpc.JSON.createObjectNode();
        params.put("task_id", taskId);
        params.put("instance", instance);
        params.put("run", r.run());
        params.put("state", r.state());
        if (r.exit() != null) params.put("exit_code", r.exit());
        s.accept(METHOD, params);
    }
}
