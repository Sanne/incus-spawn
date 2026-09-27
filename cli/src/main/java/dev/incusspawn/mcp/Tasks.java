package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The session's background tasks: commands started with {@code exec(background)} and agents
 * started with {@code delegate}. The task's files and systemd unit in the instance are its state;
 * this registry only remembers which tasks are this session's and where they run.
 */
final class Tasks {

    static final String COMMAND = "command";
    static final String AGENT = "agent";
    static final int STATUS_TAIL = 64 * 1024;
    static final int RESULT_TAIL = 256 * 1024;

    record Task(String id, String instance, String kind, Instant created, int runs, boolean running) {
        Task withRuns(int n) { return new Task(id, instance, kind, created, n, true); }
        Task withRunning(boolean r) { return new Task(id, instance, kind, created, runs, r); }
    }

    /**
     * One look at a task. {@code state} is {@code running}, {@code finished}, or {@code lost}
     * (its unit is gone without recording an exit: the instance restarted, or it was killed).
     */
    record Status(String state, String kind, int run, Integer exit, long outputBytes,
                  long stderrBytes, String output, String stderr) {
        boolean running() {
            return "running".equals(state);
        }
    }

    private final McpSession session;
    private final InstanceBackend backend;
    private final Supplier<McpConfig> config;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    Tasks(McpSession session, InstanceBackend backend, Supplier<McpConfig> config) {
        this.session = session;
        this.backend = backend;
        this.config = config;
    }

    synchronized List<Task> all() {
        return List.copyOf(tasks.values());
    }

    Task require(String id) {
        Task task;
        synchronized (this) {
            task = tasks.get(id);
        }
        if (task == null) {
            throw new ToolError("no task '" + id + "' in this session. Tasks are listed by list_instances.");
        }
        session.requireOwned(task.instance());
        return task;
    }

    Task startCommand(String instance, String cwd, Map<String, String> env, String command) {
        var id = reserve(instance, COMMAND);
        var script = TaskScripts.launch(id, 1, COMMAND, TaskScripts.commandRun(id, cwd, env, command), false);
        launch(id, instance, script, "");
        return put(new Task(id, instance, COMMAND, Instant.now(), 1, true));
    }

    Task delegate(String instance, String cwd, String instruction) {
        var id = reserve(instance, AGENT);
        var run = TaskScripts.agentRun(id, 1, cwd, config.get().delegateMaxTurns());
        launch(id, instance, TaskScripts.launch(id, 1, AGENT, run, true), instruction);
        return put(new Task(id, instance, AGENT, Instant.now(), 1, true));
    }

    /** Continue a finished agent task's conversation with a new message. */
    Task sendMessage(String id, String message, String cwd) {
        var task = require(id);
        if (!AGENT.equals(task.kind())) throw new ToolError("task " + id + " is a command, not a delegated agent.");
        if (status(task).running()) {
            throw new ToolError("task " + id + " is still running; wait for it (task_status with "
                    + "wait_seconds) or cancel_task it first.");
        }
        checkCapacity(task.instance(), AGENT);
        var run = task.runs() + 1;
        var script = TaskScripts.agentRun(id, run, cwd, config.get().delegateMaxTurns());
        launch(id, task.instance(), TaskScripts.launch(id, run, AGENT, script, false), message);
        return put(task.withRuns(run));
    }

    Status status(Task task) {
        var out = run(task.instance(), TaskScripts.status(task.id(), task.kind().equals(AGENT) ? RESULT_TAIL : STATUS_TAIL));
        var status = parse(out);
        synchronized (this) {
            tasks.computeIfPresent(task.id(), (k, t) -> t.withRunning(status.running()));
        }
        return status;
    }

    /** Poll until the task is no longer running or {@code seconds} have passed. */
    Status await(Task task, int seconds, ToolContext ctx) throws InterruptedException {
        var deadline = System.nanoTime() + seconds * 1_000_000_000L;
        var status = status(task);
        while (status.running() && System.nanoTime() < deadline && !ctx.cancelled()) {
            Thread.sleep(2000);
            status = status(task);
            ctx.progress("task " + task.id() + " still running");
        }
        return status;
    }

    void cancel(Task task) {
        run(task.instance(), TaskScripts.cancel(task.id()));
        synchronized (this) {
            tasks.computeIfPresent(task.id(), (k, t) -> t.withRunning(false));
        }
    }

    String diff(Task task, String path, int maxBytes) {
        return run(task.instance(), TaskScripts.diff(task.id(), path, maxBytes));
    }

    /** Refuse a new task beyond the session's limit, or a second agent in one working tree. */
    private void checkCapacity(String instance, String kind) {
        List<Task> believedRunning;
        synchronized (this) {
            believedRunning = tasks.values().stream().filter(Task::running).toList();
        }
        // Refresh what we believe is running: most will have finished since we last looked.
        long running = 0;
        for (var t : believedRunning) {
            if (!status(t).running()) continue;
            running++;
            if (AGENT.equals(kind) && AGENT.equals(t.kind()) && t.instance().equals(instance)) {
                throw new ToolError("task " + t.id() + " is already an agent working in " + instance
                        + "; two would clash in one working tree. Delegate with template instead of "
                        + "instance to get a fresh one.");
            }
        }
        var max = config.get().maxConcurrentTasks();
        if (running >= max) {
            throw new ToolError(running + " task(s) are running, the most mcp.max-concurrent-tasks "
                    + "allows (" + max + "). Wait for one to finish or cancel_task it.");
        }
    }

    private String reserve(String instance, String kind) {
        session.requireOwned(instance);
        checkCapacity(instance, kind);
        return "t" + counter.incrementAndGet() + "-" + Long.toString(session.id.pid(), 36);
    }

    private synchronized Task put(Task task) {
        tasks.put(task.id(), task);
        return task;
    }

    private void launch(String id, String instance, String script, String stdin) {
        var err = new ByteArrayOutputStream();
        var exit = backend.exec(instance, script,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), java.io.OutputStream.nullOutputStream(), err);
        if (exit != 0) {
            throw new ToolError("could not start task " + id + " in " + instance + " (exit " + exit + "): "
                    + err.toString(StandardCharsets.UTF_8).strip());
        }
    }

    private String run(String instance, String script) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var exit = backend.exec(instance, script, null, out, err);
        if (exit != 0) {
            throw new ToolError("isx could not read the task's state in " + instance + " (exit " + exit + "): "
                    + err.toString(StandardCharsets.UTF_8).strip());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static Status parse(String out) {
        var header = new LinkedHashMap<String, String>();
        var lines = out.split("\n", -1);
        int i = 0;
        for (; i < lines.length && !lines[i].equals("---"); i++) {
            var eq = lines[i].indexOf('=');
            if (eq > 0) header.put(lines[i].substring(0, eq), lines[i].substring(eq + 1));
        }
        if ("missing".equals(header.get("state"))) {
            return new Status("lost", "", 0, null, 0, 0, "", "");
        }
        var rest = i < lines.length ? String.join("\n", List.of(lines).subList(i + 1, lines.length)) : "";
        var split = rest.indexOf("\n---stderr\n");
        var output = split >= 0 ? rest.substring(0, split) : rest;
        var stderr = split >= 0 ? rest.substring(split + "\n---stderr\n".length()) : "";
        Integer exit = header.containsKey("exit") ? Integer.valueOf(header.get("exit").strip()) : null;
        var unit = header.getOrDefault("unit", "");
        String state;
        if (exit != null) state = "finished";
        else if (unit.equals("active") || unit.equals("activating")) state = "running";
        else state = "lost";
        var kind = header.getOrDefault("kind", "");
        long outputBytes = parseLong(header.getOrDefault(AGENT.equals(kind) ? "events_bytes" : "stdout_bytes", "0"));
        return new Status(state, kind, (int) parseLong(header.getOrDefault("run", "0")), exit,
                outputBytes, parseLong(header.getOrDefault("stderr_bytes", "0")),
                output.endsWith("\n") ? output.substring(0, output.length() - 1) : output, stderr);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
