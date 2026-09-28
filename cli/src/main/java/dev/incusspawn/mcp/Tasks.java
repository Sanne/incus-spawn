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

    /**
     * A task. {@code cwd} is where every run of it works: Claude Code keeps its sessions per
     * directory, so a resumed agent must start where the first run did. {@code launching} marks
     * a run whose slot is reserved but whose launch has not returned: it counts as running, and
     * the state probe (which cannot see it yet) must not say otherwise.
     */
    record Task(String id, String instance, String kind, String cwd, int runs, boolean running,
                boolean launching) {
        Task launchingRun(int n) { return new Task(id, instance, kind, cwd, n, true, true); }
        Task launched() { return new Task(id, instance, kind, cwd, runs, true, false); }
        Task withRunning(boolean r) { return launching ? this : new Task(id, instance, kind, cwd, runs, r, false); }
        boolean busy() { return running || launching; }
    }

    /** A task, with its instance's config as read when checking that this session owns it. */
    record Owned(Task task, Map<String, String> metadata) {}

    /**
     * One look at a task. {@code state} is {@code running}, {@code finished}, {@code attached}
     * (finished, and a person is now in its conversation: {@code attachedPid} is their Claude
     * Code), {@code lost} (its unit is gone without recording an exit: the instance restarted,
     * or it was killed), or {@code unknown} when systemd could not be asked. A person can also
     * join while a run is still going; the state stays {@code running}, with the pid set.
     */
    record Status(String state, int run, Integer exit, long outputBytes,
                  long stderrBytes, String output, String stderr, Long attachedPid) {
        boolean running() {
            return "running".equals(state);
        }
    }

    private final McpSession session;
    private final InstanceBackend backend;
    private final Supplier<McpConfig> config;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final AtomicLong counter = new AtomicLong();
    // Task ids outlive the session that made them (an adopting session takes the tasks over),
    // so they must not collide with a later session's: a counter alone, or a pid, would.
    private final String tag = McpSession.randomSuffix(4);

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
            throw new ToolError("no task '" + id + "' in this session. Tasks are listed by list_instances; "
                    + "a task of an instance another session held becomes yours with adopt_instance.");
        }
        return new Owned(task, session.requireOwned(task.instance()));
    }

    /** Start a background command in {@code instance}, which the caller checked is owned. */
    Task startCommand(String instance, String cwd, Map<String, String> env, String command) {
        var task = reserve(null, new Task(nextId(), instance, COMMAND, cwd, 1, true, true));
        return launch(task, null, TaskScripts.commandRun(task.id(), cwd, env, command), "");
    }

    /** Start a delegated agent in {@code instance}, which the caller checked is owned. */
    Task delegate(String instance, String cwd, String instruction, String permissionMode) {
        var task = reserve(null, new Task(nextId(), instance, AGENT, cwd, 1, true, true));
        return launch(task, null,
                TaskScripts.agentRun(task.id(), 1, cwd, config.get().delegateMaxTurns(), permissionMode), instruction);
    }

    /**
     * Continue a finished agent task's conversation with a new message, where it started.
     * Refused while a person is in that conversation: two writers would race on one session.
     * Checked when sent, so a person joining in the seconds after is not seen.
     */
    Task sendMessage(Task task, String message, String permissionMode) {
        if (!AGENT.equals(task.kind())) throw new ToolError("task " + task.id() + " is a command, not a delegated agent.");
        var attached = status(task, 0).attachedPid();
        if (attached != null) {
            throw new ToolError("a person is in task " + task.id() + "'s conversation (Claude Code, pid " + attached
                    + ", in " + task.instance() + "). Messages would race with theirs: wait until task_status "
                    + "no longer says attached, or ask the user.");
        }
        var reserved = reserve(task.id(), null);
        return launch(reserved, task, TaskScripts.agentRun(task.id(), reserved.runs(), task.cwd(),
                config.get().delegateMaxTurns(), permissionMode), message);
    }

    /**
     * Take over the tasks recorded in an instance this session just adopted, so their ids work
     * here as they did in the session that started them. Returns their ids.
     */
    List<String> adopt(String instance) {
        var adopted = new java.util.ArrayList<String>();
        for (var line : run(instance, TaskScripts.list(), null).split("\n")) {
            var parts = line.strip().split(" ", 5);
            if (parts.length < 4 || !parts[0].matches("[a-z0-9-]+")) continue;
            var kind = parts[1];
            if (!AGENT.equals(kind) && !COMMAND.equals(kind)) continue;
            int runs;
            try {
                runs = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            var cwd = parts.length > 4 && !parts[4].isBlank() ? parts[4] : IncusInstanceBackend.AGENT_HOME;
            var task = new Task(parts[0], instance, kind, cwd, runs, "running".equals(parts[3]), false);
            synchronized (this) {
                tasks.putIfAbsent(task.id(), task);
            }
            adopted.add(task.id());
        }
        // A run recorded as unfinished may have died with a restart: ask systemd.
        refreshStates();
        return adopted;
    }

    /**
     * Refuse now what {@link #reserve} would refuse, without reserving: for a caller about to
     * create an instance for a new agent task, which should not be made only to be turned away.
     */
    void checkCapacityForNewAgent() {
        refreshStates();
        synchronized (this) {
            checkLimit();
        }
    }

    Status status(Task task) {
        return status(task, AGENT.equals(task.kind()) ? RESULT_TAIL : STATUS_TAIL);
    }

    /** {@link #status(Task)} with this much of the end of the output. */
    Status status(Task task, int tailBytes) {
        var status = parse(run(task.instance(), TaskScripts.status(task.id(), tailBytes), null));
        // "unknown" says nothing about the task: keep what we last knew rather than free its slot.
        if (!"unknown".equals(status.state())) markRunning(Map.of(task.id(), status.running()));
        return status;
    }

    /**
     * Wait until the task is no longer running or {@code seconds} have passed, polling only
     * whether it runs, then read its full status once.
     */
    Status await(Task task, int seconds, ToolContext ctx) throws InterruptedException {
        var deadline = System.nanoTime() + seconds * 1_000_000_000L;
        // An unknown state may still be running: keep waiting rather than return early.
        while (!"done".equals(probe(task.instance(), List.of(task.id())).getOrDefault(task.id(), "done"))
                && System.nanoTime() < deadline && !ctx.cancelled()) {
            ctx.progress("task " + task.id() + " still running");
            Thread.sleep(2000);
        }
        return status(task);
    }

    /**
     * Wait until at least one of {@code watched} is no longer running, or {@code seconds} have
     * passed: one cheap probe per instance every two seconds. Returns each task's last state
     * ({@code running}, {@code done} or {@code unknown}), in the order given.
     */
    Map<String, String> awaitAny(List<Task> watched, int seconds, ToolContext ctx) throws InterruptedException {
        var deadline = System.nanoTime() + seconds * 1_000_000_000L;
        var byInstance = watched.stream().collect(Collectors.groupingBy(Task::instance,
                LinkedHashMap::new, Collectors.mapping(Task::id, Collectors.toList())));
        while (true) {
            var states = new HashMap<String, String>();
            byInstance.forEach((instance, ids) -> {
                try {
                    states.putAll(probe(instance, ids));
                } catch (RuntimeException e) {
                    // Gone: its tasks are over. Merely unreachable: nothing is known.
                    var gone = backend.metadata(instance) == null;
                    ids.forEach(id -> states.put(id, gone ? "done" : "unknown"));
                    if (gone) forgetInstance(instance);
                }
            });
            var ordered = new LinkedHashMap<String, String>();
            watched.forEach(t -> ordered.put(t.id(), states.getOrDefault(t.id(), "done")));
            if (ordered.containsValue("done") || System.nanoTime() >= deadline || ctx.cancelled()) return ordered;
            ctx.progress(watched.size() + " task(s) still running");
            Thread.sleep(2000);
        }
    }

    void cancel(Task task) {
        run(task.instance(), TaskScripts.cancel(task.id()), null);
        markRunning(Map.of(task.id(), false));
    }

    String diff(Task task, String path, int maxBytes, boolean statOnly) {
        return run(task.instance(), TaskScripts.diff(task.id(), path, maxBytes, statOnly), null);
    }

    /**
     * Reserve a task slot before launching: a new task ({@code fresh}), or the next run of task
     * {@code continuing}. Refuses beyond {@code mcp.max-concurrent-tasks}, a second agent in one
     * working tree, or a run of a task still running. The check and the reservation happen under
     * one lock, so concurrent calls see each other's reservations; asking the instances which
     * tasks have finished happens before it, outside the lock.
     */
    private Task reserve(String continuing, Task fresh) {
        refreshStates();
        synchronized (this) {
            if (continuing != null) {
                var task = tasks.get(continuing);
                if (task.busy()) {
                    throw new ToolError("task " + continuing + " is still running; wait for it (task_status "
                            + "with wait_seconds) or cancel_task it first.");
                }
                checkLimit();
                var next = task.launchingRun(task.runs() + 1);
                tasks.put(continuing, next);
                return next;
            }
            if (AGENT.equals(fresh.kind())) {
                for (var t : tasks.values()) {
                    if (t.busy() && AGENT.equals(t.kind()) && t.instance().equals(fresh.instance())) {
                        throw new ToolError("task " + t.id() + " is already an agent working in "
                                + fresh.instance() + "; two would clash in one working tree. Delegate "
                                + "with template instead of instance to get a fresh one.");
                    }
                }
            }
            checkLimit();
            tasks.put(fresh.id(), fresh);
            return fresh;
        }
    }

    /** Refuse a new run when as many as {@code mcp.max-concurrent-tasks} are busy. Holds the lock. */
    private void checkLimit() {
        var busy = tasks.values().stream().filter(Task::busy).count();
        var max = config.get().maxConcurrentTasks();
        if (busy >= max) {
            throw new ToolError(busy + " task(s) are running, the most mcp.max-concurrent-tasks "
                    + "allows (" + max + "). Wait for one to finish or cancel_task it.");
        }
    }

    /** Ask each instance, once, which of the tasks believed running still are. */
    private void refreshStates() {
        List<Task> believedRunning;
        synchronized (this) {
            believedRunning = tasks.values().stream().filter(t -> t.running() && !t.launching()).toList();
        }
        believedRunning.stream().collect(Collectors.groupingBy(Task::instance)).forEach((instance, list) -> {
            try {
                probe(instance, list.stream().map(Task::id).toList());
            } catch (RuntimeException e) {
                // An instance that is gone takes its tasks with it; one we merely cannot reach
                // right now keeps them as they were, so their slots stay counted.
                if (backend.metadata(instance) == null) forgetInstance(instance);
            }
        });
    }

    /** Forget the tasks of an instance that no longer exists. */
    synchronized void forgetInstance(String instance) {
        tasks.values().removeIf(t -> t.instance().equals(instance));
    }

    /**
     * Launch a reserved run. On failure the reservation is undone: a fresh task is forgotten, a
     * continued one goes back to how it was.
     */
    private Task launch(Task reserved, Task previous, String runScript, String stdin) {
        try {
            run(reserved.instance(), TaskScripts.launch(reserved.id(), reserved.runs(), reserved.kind(), runScript), stdin);
        } catch (RuntimeException e) {
            synchronized (this) {
                if (previous == null) tasks.remove(reserved.id());
                else tasks.put(previous.id(), previous);
            }
            throw e;
        }
        synchronized (this) {
            var launched = reserved.launched();
            tasks.put(launched.id(), launched);
            return launched;
        }
    }

    /**
     * {@code running}, {@code done} or {@code unknown} for each of these tasks in one instance,
     * from one cheap exec. Records what it learned, except for {@code unknown}.
     */
    private Map<String, String> probe(String instance, List<String> ids) {
        var result = new HashMap<String, String>();
        var known = new HashMap<String, Boolean>();
        for (var line : run(instance, TaskScripts.states(ids), null).split("\n")) {
            var parts = line.strip().split(" ");
            if (parts.length != 2) continue;
            result.put(parts[0], parts[1]);
            if (!parts[1].equals("unknown")) known.put(parts[0], parts[1].equals("running"));
        }
        markRunning(known);
        return result;
    }

    private synchronized void markRunning(Map<String, Boolean> states) {
        states.forEach((id, r) -> tasks.computeIfPresent(id, (k, t) -> t.withRunning(r)));
    }

    private String nextId() {
        return "t" + counter.incrementAndGet() + "-" + tag;
    }

    /** Run a control script in the instance and return its stdout; stdin null for none. */
    String run(String instance, String script, String stdin) {
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
        var presence = new java.util.ArrayList<String>();
        var lines = out.split("\n", -1);
        int i = 0;
        for (; i < lines.length && !lines[i].equals("---"); i++) {
            var eq = lines[i].indexOf('=');
            if (eq <= 0) continue;
            var key = lines[i].substring(0, eq);
            if (key.equals("presence")) presence.add(lines[i].substring(eq + 1));
            else header.put(key, lines[i].substring(eq + 1));
        }
        if ("missing".equals(header.get("state"))) {
            return new Status("lost", 0, null, 0, 0, "", "", null);
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
        var attached = agent ? Presence.parse(presence).holderOf(header.get("session_id"), header.get("cwd")) : null;
        if (attached != null && state.equals("finished")) state = "attached";
        long outputBytes = parseLong(header.getOrDefault(agent ? "events_bytes" : "stdout_bytes", "0"));
        return new Status(state, (int) parseLong(header.getOrDefault("run", "0")), exit,
                outputBytes, parseLong(header.getOrDefault("stderr_bytes", "0")),
                output.endsWith("\n") ? output.substring(0, output.length() - 1) : output, stderr, attached);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
