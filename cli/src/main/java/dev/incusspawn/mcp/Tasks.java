package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The session's background tasks: commands started with {@code exec(background)} and agents
 * started with {@code delegate}. The task's files and systemd unit in the instance are its state;
 * this registry only remembers which tasks are this session's and where they run.
 *
 * <p>Callers check ownership once at the tool boundary ({@link #require}, or
 * {@code McpSession.requireOwned} for an instance) and pass what they checked in.
 */
final class Tasks {

    static final String COMMAND = "command";
    static final String AGENT = "agent";
    static final int STATUS_TAIL = 64 * 1024;
    static final int RESULT_TAIL = 256 * 1024;

    record Task(String id, String instance, String kind, int runs, boolean running) {
        Task withRuns(int n) { return new Task(id, instance, kind, n, true); }
        Task withRunning(boolean r) { return new Task(id, instance, kind, runs, r); }
    }

    /** A task, with its instance's config as read when checking that this session owns it. */
    record Owned(Task task, Map<String, String> metadata) {}

    /**
     * One look at a task. {@code state} is {@code running}, {@code finished}, {@code lost} (its
     * unit is gone without recording an exit: the instance restarted, or it was killed), or
     * {@code unknown} when systemd could not be asked.
     */
    record Status(String state, int run, Integer exit, long outputBytes,
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

    /** This session's task, after checking the session still owns its instance. */
    Owned require(String id) {
        Task task;
        synchronized (this) {
            task = tasks.get(id);
        }
        if (task == null) {
            throw new ToolError("no task '" + id + "' in this session. Tasks are listed by list_instances.");
        }
        return new Owned(task, session.requireOwned(task.instance()));
    }

    /** Start a background command in {@code instance}, which the caller checked is owned. */
    Task startCommand(String instance, String cwd, Map<String, String> env, String command) {
        var id = reserve(instance, COMMAND);
        run(instance, TaskScripts.launch(id, 1, COMMAND, TaskScripts.commandRun(id, cwd, env, command)), "");
        return put(new Task(id, instance, COMMAND, 1, true));
    }

    /** Start a delegated agent in {@code instance}, which the caller checked is owned. */
    Task delegate(String instance, String cwd, String instruction) {
        var id = reserve(instance, AGENT);
        var script = TaskScripts.agentRun(id, 1, cwd, config.get().delegateMaxTurns());
        run(instance, TaskScripts.launch(id, 1, AGENT, script), instruction);
        return put(new Task(id, instance, AGENT, 1, true));
    }

    /** Continue a finished agent task's conversation with a new message. */
    Task sendMessage(Task task, String message, String cwd) {
        if (!AGENT.equals(task.kind())) throw new ToolError("task " + task.id() + " is a command, not a delegated agent.");
        checkCapacity(task.instance(), AGENT, task.id());
        var run = task.runs() + 1;
        var script = TaskScripts.agentRun(task.id(), run, cwd, config.get().delegateMaxTurns());
        run(task.instance(), TaskScripts.launch(task.id(), run, AGENT, script), message);
        return put(task.withRuns(run));
    }

    Status status(Task task) {
        var status = parse(run(task.instance(),
                TaskScripts.status(task.id(), AGENT.equals(task.kind()) ? RESULT_TAIL : STATUS_TAIL), null));
        markRunning(Map.of(task.id(), status.running()));
        return status;
    }

    /**
     * Wait until the task is no longer running or {@code seconds} have passed, polling only
     * whether it runs, then read its full status once.
     */
    Status await(Task task, int seconds, ToolContext ctx) throws InterruptedException {
        var deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (running(task.instance(), List.of(task.id())).getOrDefault(task.id(), false)
                && System.nanoTime() < deadline && !ctx.cancelled()) {
            ctx.progress("task " + task.id() + " still running");
            Thread.sleep(2000);
        }
        return status(task);
    }

    void cancel(Task task) {
        run(task.instance(), TaskScripts.cancel(task.id()), null);
        markRunning(Map.of(task.id(), false));
    }

    String diff(Task task, String path, int maxBytes) {
        return run(task.instance(), TaskScripts.diff(task.id(), path, maxBytes), null);
    }

    /**
     * Refuse a new task beyond the session's limit, or a second agent in one working tree.
     * {@code continuing} is a task being resumed, which does not count against itself.
     */
    private void checkCapacity(String instance, String kind, String continuing) {
        List<Task> believedRunning;
        synchronized (this) {
            believedRunning = tasks.values().stream().filter(Task::running).toList();
        }
        // Most will have finished since we last looked: ask each instance once.
        var byInstance = believedRunning.stream().collect(Collectors.groupingBy(Task::instance));
        long running = 0;
        for (var entry : byInstance.entrySet()) {
            var states = running(entry.getKey(), entry.getValue().stream().map(Task::id).toList());
            for (var t : entry.getValue()) {
                if (!states.getOrDefault(t.id(), false)) continue;
                if (t.id().equals(continuing)) {
                    throw new ToolError("task " + t.id() + " is still running; wait for it (task_status "
                            + "with wait_seconds) or cancel_task it first.");
                }
                running++;
                if (AGENT.equals(kind) && AGENT.equals(t.kind()) && t.instance().equals(instance)) {
                    throw new ToolError("task " + t.id() + " is already an agent working in " + instance
                            + "; two would clash in one working tree. Delegate with template instead of "
                            + "instance to get a fresh one.");
                }
            }
        }
        var max = config.get().maxConcurrentTasks();
        if (running >= max) {
            throw new ToolError(running + " task(s) are running, the most mcp.max-concurrent-tasks "
                    + "allows (" + max + "). Wait for one to finish or cancel_task it.");
        }
    }

    /** Which of these tasks in one instance are still running, from one cheap exec. */
    private Map<String, Boolean> running(String instance, List<String> ids) {
        var result = new HashMap<String, Boolean>();
        for (var line : run(instance, TaskScripts.states(ids), null).split("\n")) {
            var parts = line.strip().split(" ");
            if (parts.length == 2) result.put(parts[0], parts[1].equals("running"));
        }
        markRunning(result);
        return result;
    }

    private synchronized void markRunning(Map<String, Boolean> states) {
        states.forEach((id, r) -> tasks.computeIfPresent(id, (k, t) -> t.withRunning(r)));
    }

    private String reserve(String instance, String kind) {
        checkCapacity(instance, kind, null);
        return "t" + counter.incrementAndGet() + "-" + Long.toString(session.id.pid(), 36);
    }

    private synchronized Task put(Task task) {
        tasks.put(task.id(), task);
        return task;
    }

    /** Run a control script in the instance and return its stdout; stdin null for none. */
    private String run(String instance, String script, String stdin) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        InputStream in = stdin == null ? null : new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        var exit = backend.exec(instance, script, in, out, err);
        if (exit != 0) {
            throw new ToolError("isx could not run its task control script in " + instance + " (exit " + exit
                    + "): " + err.toString(StandardCharsets.UTF_8).strip());
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
            return new Status("lost", 0, null, 0, 0, "", "");
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
        // No answer at all is not evidence the task died: say so rather than call it lost.
        else if (unit.isBlank()) state = "unknown";
        else state = "lost";
        var agent = AGENT.equals(header.get("kind"));
        long outputBytes = parseLong(header.getOrDefault(agent ? "events_bytes" : "stdout_bytes", "0"));
        return new Status(state, (int) parseLong(header.getOrDefault("run", "0")), exit,
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
