package dev.incusspawn.mcp;

import dev.incusspawn.Environment;
import dev.incusspawn.proxy.EventLogAppender;
import io.hyperfoil.tools.jjq.value.JqObject;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes when a task's run starts and ends to the event log (#2428), so a report can book each
 * model call to the run that made it: {@code kind: task}, {@code op: start} or {@code end}.
 *
 * <p>A start is written once the launch returned, stamped with the moment its exec was sent, with the {@code activity} the caller gave that
 * run alone. An end is written the first time this session sees a run it knows of no longer
 * running, by whichever read saw it: a status, a watch, a state probe, or a destroy. It carries
 * the time isx mcp saw it, so a call made between the run's real end and that moment is booked to
 * the run. No other run of the task starts before then, and nor does another agent in the
 * instance for this session. A run nobody sees end has no end line; the reader closes it.
 *
 * <p>The lines go into the proxy's own file through {@link EventLogAppender}, and a failed write
 * loses the line, never the task. {@code isx mcp} runs on the host, so no guest can write one.
 */
final class TaskRunLog {

    /** The states a run ends in. */
    private static final Set<String> ENDED = Set.of("finished", TaskWatcher.LOST, "attached");

    /** A run is its instance's: a task id read back from a guest never stands for another instance's run. */
    private record Key(String instance, String taskId, int run) {}

    /** What the end line repeats of its start. */
    private record Open(String instance, String incarnation, String kind, String activity) {}

    /** An end seen; written when the run's start is, if that has not been yet. */
    private record End(Instant at, String state, Integer exit) {}

    private final Map<Key, Open> open = new ConcurrentHashMap<>();
    /** A run whose launch exec is out: when it was sent, and an end a read saw meanwhile, which waits for the start line. */
    private record Launching(Instant at, End end) {
        Launching ended(End e) {
            return end != null ? this : new Launching(at, e);
        }
    }

    private final Map<Key, Launching> launching = new ConcurrentHashMap<>();
    private final Set<Key> ended = ConcurrentHashMap.newKeySet();

    private static Key key(Tasks.Task task, int run) {
        return new Key(task.instance(), task.id(), run);
    }

    /**
     * A run's launch exec is about to be sent: its start line carries this moment, so a model call
     * the run makes before the exec returns is inside it. A read that sees the run end before
     * {@link #started} holds the end back.
     */
    void launching(Tasks.Task task) {
        launching.put(key(task, task.runs()), new Launching(Instant.now(), null));
    }

    /** The launch failed: there was no run. */
    void notLaunched(Tasks.Task task) {
        var k = key(task, task.runs());
        launching.remove(k);
        open.remove(k);
    }

    /** A run was launched. {@code activity} and {@code incarnation} may be null. */
    void started(Tasks.Task task, String activity, String incarnation) {
        var k = key(task, task.runs());
        var run = new Open(task.instance(), incarnation == null ? "" : incarnation, task.kind(), activity == null ? "" : activity);
        // A read may have seen the run before its launch returned: its placeholder gives way
        open.put(k, run);
        var sent = launching.get(k);
        write(JqObject.builder(9)
                .put("t", (sent != null ? sent.at() : Instant.now()).toString())
                .put("kind", "task")
                .put("op", "start")
                .put("instance", run.instance())
                .put("incarnation", run.incarnation())
                .put("task_id", task.id())
                .put("run", (long) task.runs())
                .put("task_kind", run.kind())
                .put("activity", run.activity())
                .build().toJsonString());
        // Removed after the start is written, so an end seen meanwhile is held, never written first
        var pending = launching.remove(k);
        if (pending != null && pending.end() != null) end(task, task.runs(), pending.end());
    }

    /**
     * A run was seen in {@code state}. One seen running is remembered, as a run adopted from
     * another session is, so its end is written here; one seen ended is written once.
     */
    void seen(Tasks.Task task, int run, String state, Integer exit) {
        if (run < 1) return;
        var k = key(task, run);
        if (TaskWatcher.RUNNING.equals(state)) {
            if (!ended.contains(k)) open.putIfAbsent(k, new Open(task.instance(), "", task.kind(), ""));
            return;
        }
        if (!ENDED.contains(state)) return;
        var seenEnd = new End(Instant.now(), state, exit);
        // Ended before its launch returned: written after the start, with the time it was seen
        if (launching.computeIfPresent(k, (x, pending) -> pending.ended(seenEnd)) != null) return;
        end(task, run, seenEnd);
    }

    private void end(Tasks.Task task, int run, End e) {
        var k = key(task, run);
        var was = open.remove(k);
        if (was == null || !ended.add(k)) return;
        var line = JqObject.builder(11)
                .put("t", e.at().toString())
                .put("kind", "task")
                .put("op", "end")
                .put("instance", was.instance())
                .put("incarnation", was.incarnation())
                .put("task_id", task.id())
                .put("run", (long) run)
                .put("task_kind", was.kind())
                .put("activity", was.activity())
                .put("state", e.state());
        if (e.exit() != null) line.put("exit_code", (long) e.exit());
        write(line.build().toJsonString());
    }

    private static void write(String line) {
        // Resolved per call: a path held in a static would be baked into the native image.
        if (!EventLogAppender.append(Environment.proxyEventLogFile(), line)) {
            McpLog.log("could not write a task line to the event log");
        }
    }
}
