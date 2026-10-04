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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
     * What a delegated agent runs under, as the coordinator chose it: a {@code --model} and a
     * {@code --max-turns}, each null when not chosen (the template's model, the configured
     * budget). Later runs keep it, each value until one replaces it.
     */
    record Profile(String model, Integer maxTurns) {
        static final Profile NONE = new Profile(null, null);

        Profile with(Profile override) {
            if (override == null) return this;
            return new Profile(override.model != null ? override.model : model,
                    override.maxTurns != null ? override.maxTurns : maxTurns);
        }
    }

    /**
     * A task. {@code cwd} is where every run of it works: Claude Code keeps its sessions per
     * directory, so a resumed agent must start where the first run did. {@code launching} marks
     * a run whose slot is reserved but whose launch has not returned: it counts as running, and
     * the state probe (which cannot see it yet) must not say otherwise.
     */
    record Task(String id, String instance, String kind, String cwd, Profile profile, int runs, boolean running,
                boolean launching) {
        Task launchingRun(int n) { return new Task(id, instance, kind, cwd, profile, n, true, true); }
        Task launched() { return new Task(id, instance, kind, cwd, profile, runs, true, false); }
        Task withRunning(boolean r) { return launching ? this : new Task(id, instance, kind, cwd, profile, runs, r, false); }
        Task withProfile(Profile p) { return new Task(id, instance, kind, cwd, p, runs, running, launching); }
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
    /** How long counting other sessions' tasks waits for an instance before counting it as none. */
    Duration elsewhereTimeout = Duration.ofSeconds(10);
    /** How long an instance that did not answer in time is not asked again (counting as none). */
    Duration elsewhereCooldown = Duration.ofMinutes(10);
    /**
     * Probes of other sessions' instances not yet over, by instance: never more than one each, so
     * the reservations that arrive while a silent instance is first waited for share its probe
     * rather than each starting another {@code su -} in it.
     */
    private final Map<String, CompletableFuture<Long>> probing = new ConcurrentHashMap<>();
    /** Instances that did not answer in time, with when ({@link System#nanoTime}) to ask again. */
    private final Map<String, Long> quietUntil = new ConcurrentHashMap<>();

    private final TaskWatcher watcher = new TaskWatcher(this::watchRound);
    /** Instances whose last watch exec failed: polled again only by the next slow round. */
    private final java.util.Set<String> unanswered = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Tasks(McpSession session, InstanceBackend backend, Supplier<McpConfig> config) {
        this.session = session;
        this.backend = backend;
        this.config = config;
    }

    /** What tells a client that opted in when a task changes state. */
    TaskWatcher watcher() {
        return watcher;
    }

    List<Task> all() {
        // Outside the lock: forgetting may ask Incus why, and tell a watching client.
        forgetUnheld();
        synchronized (this) {
            return List.copyOf(tasks.values());
        }
    }

    /**
     * Forget the tasks of instances this session no longer holds -- another session adopted
     * them, or they are gone -- so they neither count against the limit nor get watched.
     */
    private void forgetUnheld() {
        java.util.Set<String> instances;
        synchronized (this) {
            instances = tasks.values().stream().map(Task::instance).collect(Collectors.toSet());
        }
        instances.forEach(this::forgetIfUnheld);
    }

    /** This session's task, after checking the session still owns its instance and it is running. */
    Owned require(String id) {
        var task = lookup(id);
        return new Owned(task, session.requireRunning(task.instance()));
    }

    /** {@link #require}, for a caller that copes with a stopped instance itself (waiting reads it as unknown). */
    Task requireHeld(String id) {
        var task = lookup(id);
        session.requireOwned(task.instance());
        return task;
    }

    private Task lookup(String id) {
        Task task;
        synchronized (this) {
            task = tasks.get(id);
        }
        if (task == null) {
            throw new ToolError("no task '" + id + "' in this session. Tasks are listed by list_instances; "
                    + "a task of an instance another session held becomes yours with adopt_instance.");
        }
        return task;
    }

    /** Start a background command in {@code instance}, which the caller checked is owned. */
    Task startCommand(String instance, String cwd, Map<String, String> env, String command) {
        var task = reserve(null, null, new Task(nextId(), instance, COMMAND, cwd, Profile.NONE, 1, true, true), true);
        return launch(task, null, null, t -> TaskScripts.commandRun(t.id(), cwd, env, command), "");
    }

    /**
     * Start a delegated agent in {@code instance}, which the caller checked is owned, under
     * {@code profile}. Without {@code refresh}, the caller has just asked the instances
     * ({@link #checkCapacityForNewAgent}), so this session's are asked again only if the states as
     * last known would refuse: a refresh only ever frees slots. Other sessions' tasks are always
     * counted anew: they may have started more while the caller branched an instance.
     */
    Task delegate(String instance, String cwd, String instruction, Profile profile, String permissionMode,
                  boolean refresh, Runnable check) {
        var task = reserve(null, null, new Task(nextId(), instance, AGENT, cwd, profile, 1, true, true), refresh);
        return launch(task, null, check, t -> agentRun(t, permissionMode), instruction);
    }

    /**
     * Continue a finished agent task's conversation with a new message, where it started, under
     * its profile with {@code override} applied. {@code check} runs once the run's slot is
     * reserved, and refuses it by throwing.
     * Refused while a person is in that conversation: two writers would race on one session.
     * Checked when sent, so a person joining in the seconds after is not seen.
     */
    Task sendMessage(Task task, String message, Profile override, String permissionMode, Runnable check) {
        if (!AGENT.equals(task.kind())) throw new ToolError("task " + task.id() + " is a command, not a delegated agent.");
        var attached = status(task, 0).attachedPid();
        if (attached != null) {
            throw new ToolError("a person is in task " + task.id() + "'s conversation (Claude Code, pid " + attached
                    + ", in " + task.instance() + "). Messages would race with theirs: wait until task_status "
                    + "no longer says attached, or ask the user.");
        }
        var reserved = reserve(task.id(), override, null, true);
        return launch(reserved, task, check, t -> agentRun(t, permissionMode), message);
    }

    /**
     * The run script of the task's current run. Its turn budget is the task's own, else the
     * configured one -- and never more than the configured one: a budget chosen under a higher
     * ceiling is held to the one the user set since.
     */
    private String agentRun(Task t, String permissionMode) {
        return TaskScripts.agentRun(t.id(), t.runs(), t.cwd(), t.profile(),
                config.get().delegateMaxTurns(t.profile().maxTurns()), permissionMode);
    }

    /**
     * Take over the tasks recorded in an instance this session just adopted, so their ids work
     * here as they did in the session that started them. Returns their ids.
     */
    List<String> adopt(String instance) {
        var adopted = new java.util.ArrayList<String>();
        for (var line : run(instance, TaskScripts.list(), null).split("\n")) {
            var parts = line.strip().split(" ", 7);
            if (parts.length < 4 || !parts[0].matches("[a-z0-9-]+")) continue;
            var kind = parts[1];
            if (!AGENT.equals(kind) && !COMMAND.equals(kind)) continue;
            int runs;
            try {
                runs = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            var cwd = parts.length > 6 && !parts[6].isBlank() ? parts[6] : IncusInstanceBackend.AGENT_HOME;
            var profile = parts.length > 5 ? recordedProfile(parts[4], parts[5]) : Profile.NONE;
            var task = new Task(parts[0], instance, kind, cwd, profile, runs, "running".equals(parts[3]), false);
            synchronized (this) {
                // Once reported released, a task this session takes back is reported again.
                if (tasks.putIfAbsent(task.id(), task) == null) watcher.revive(task.id());
            }
            adopted.add(task.id());
        }
        // A run recorded as unfinished may have died with a restart: ask systemd.
        refreshStates(instance::equals);
        return adopted;
    }

    /**
     * The profile an instance recorded for a task ({@code -} for a value never chosen). Read
     * from inside the instance, so only what a coordinator could have chosen is taken: anything
     * else is dropped, never passed to the next run.
     */
    static Profile recordedProfile(String model, String maxTurns) {
        Integer turns = null;
        try {
            if (!maxTurns.equals("-")) turns = Integer.parseInt(maxTurns);
        } catch (NumberFormatException e) {
            // left unset
        }
        return new Profile(McpConfig.isModelName(model) ? model : null, turns != null && turns > 0 ? turns : null);
    }

    /**
     * Refuse now what {@link #reserve} would refuse, without reserving: for a caller about to
     * create an instance for a new agent task, which should not be made only to be turned away.
     */
    void checkCapacityForNewAgent() {
        var elsewhere = busyElsewhere();
        refreshStates();
        synchronized (this) {
            checkLimit(elsewhere);
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
        // A task whose directory is gone reads as run 0.
        watcher.observe(task.id(), task.instance(), status.run() > 0 ? status.run() : task.runs(),
                status.state(), status.exit());
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
                    var gone = isGone(instance);
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

    /** The tasks of {@code instance} still running, after asking the instance which have finished. */
    List<Task> busyIn(String instance) {
        refreshStates(instance::equals);
        synchronized (this) {
            return tasks.values().stream().filter(t -> t.busy() && t.instance().equals(instance)).toList();
        }
    }

    void cancel(Task task) {
        cancel(task.instance(), List.of(task));
    }

    /**
     * Cancel several tasks of one instance in one exec. For a watching client the same exec then
     * says how they ended: {@code stop_instance} stops the instance right after, and a stopped
     * instance cannot be asked.
     */
    void cancel(String instance, List<Task> list) {
        if (list.isEmpty()) return;
        var ids = list.stream().map(Task::id).toList();
        var watching = watcher.enabled();
        var out = run(instance, TaskScripts.cancelAll(ids, watching ? TaskScripts.watchBody(ids) : ""), null);
        markRunning(list.stream().collect(Collectors.toMap(Task::id, t -> false)));
        if (watching) observeWatch(instance, ids, out);
    }

    String diff(Task task, String path, int maxBytes, boolean statOnly) {
        return run(task.instance(), TaskScripts.diff(task.id(), path, maxBytes, statOnly), null);
    }

    /**
     * Reserve a task slot before launching: a new task ({@code fresh}), or the next run of task
     * {@code continuing}. Refuses beyond {@code mcp.max-concurrent-tasks}, a second agent in one
     * working tree, or a run of a task still running. The check and the reservation happen under
     * one lock, so concurrent calls see each other's reservations; asking the instances which
     * tasks have finished happens before it, outside the lock. Another session's runs are seen
     * only once launched, and this session's count of them is as old as its probe: between one
     * session's count and another's launch, both may take the last slot, as two may both take the
     * last instance. The cap is a net against runaway creation, not a budget.
     */
    private Task reserve(String continuing, Profile override, Task fresh, boolean refresh) {
        var elsewhere = busyElsewhere();
        if (!refresh) {
            try {
                return reserveNow(continuing, override, fresh, elsewhere);
            } catch (ToolError refused) {
                // Tasks may have finished since the caller asked: ask again before refusing.
            }
        }
        refreshStates();
        return reserveNow(continuing, override, fresh, elsewhere);
    }

    /**
     * {@link #reserve} against the states as last known, with what is busy in other sessions. A
     * continued task's next run takes on {@code override}, so whoever reads the task meanwhile
     * sees the profile it starts with.
     */
    private Task reserveNow(String continuing, Profile override, Task fresh, Map<String, Long> elsewhere) {
        synchronized (this) {
            if (continuing != null) {
                var task = tasks.get(continuing);
                // Forgotten by the refresh above: its instance went away or another session took it.
                if (task == null) {
                    throw new ToolError("task " + continuing + " is no longer this session's: its instance "
                            + "is gone or another session adopted it.");
                }
                if (task.busy()) {
                    throw new ToolError("task " + continuing + " is still running; wait for it (task_status "
                            + "with wait_seconds) or cancel_task it first.");
                }
                checkLimit(elsewhere);
                var next = task.launchingRun(task.runs() + 1).withProfile(task.profile().with(override));
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
            checkLimit(elsewhere);
            tasks.put(fresh.id(), fresh);
            return fresh;
        }
    }

    /**
     * Refuse a new run when as many as {@code mcp.max-concurrent-tasks} are busy, counting what
     * is busy in this user's other sessions and orphans. Holds the lock.
     */
    private void checkLimit(Map<String, Long> elsewhere) {
        var mine = tasks.values().stream().filter(Task::busy).count();
        var others = elsewhere.values().stream().mapToLong(Long::longValue).sum();
        var busy = mine + others;
        var max = config.get().maxConcurrentTasks();
        if (busy >= max) {
            throw new ToolError(busy + " task(s) are running (" + mine + " in this session, " + others
                    + " in other sessions or orphaned instances" + (others > 0 ? ": " + elsewhere.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")) : "")
                    + "), the most mcp.max-concurrent-tasks allows (" + max + "). Wait for one to finish, or "
                    + "cancel_task one of this session's" + (others > 0 ? "; list_instances shows the other "
                    + "sessions' instances and the orphans, which adopt_instance takes over" : "") + ".");
        }
    }

    /**
     * The tasks still running in this host user's instances that another session holds or that
     * are orphaned ({@link McpSession#taskInstancesElsewhere}): the cap is per user, like
     * {@code mcp.max-instances}, since the machine runs them all whichever process holds them.
     * One listing, then one exec per such instance, in parallel; none when there are none.
     * {@code unknown} counts as running, as this session keeps an unknown task's slot; each task
     * id once.
     *
     * <p>An instance that cannot be asked counts nothing, so one broken instance cannot block
     * every later task: {@code mcp.max-instances} still bounds it. Nor can one that never answers
     * (a login profile that hangs) hold up every task start, or pile up execs: its probe is bounded
     * ({@link InstanceBackend#probe}: no login shell, killed at {@link #elsewhereTimeout}), there is never more
     * than one per instance, and once it has not answered in time the instance counts as none
     * without being asked for {@link #elsewhereCooldown}, as does one that answered but took more
     * than half the timeout. A quiet instance counts none: what it said once may be long over, and
     * a stale count must not block the user's starts for nothing.
     */
    private Map<String, Long> busyElsewhere() {
        var now = System.nanoTime();
        // A cooldown over is forgotten, so the map holds only instances quiet now.
        quietUntil.values().removeIf(until -> until - now <= 0);
        var listed = session.taskInstancesElsewhere();
        // One that left the listing (stopped, gone) is asked afresh if it comes back.
        quietUntil.keySet().retainAll(listed);
        var probes = new LinkedHashMap<String, CompletableFuture<Long>>();
        for (var name : listed) {
            if (!quietUntil.containsKey(name)) probes.put(name, probeElsewhere(name));
        }
        // From now, not from before the listing: each probe gets all of its time.
        var deadline = System.nanoTime() + elsewhereTimeout.toNanos();
        var counts = new LinkedHashMap<String, Long>();
        for (var probe : probes.entrySet()) {
            try {
                var count = probe.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (count > 0) counts.put(probe.getKey(), count);
            } catch (TimeoutException e) {
                var answer = probe.getValue().isDone() && !probe.getValue().isCompletedExceptionally()
                        ? probe.getValue().join() : null;
                if (answer != null) {
                    // It answered just as the wait ran out: use the answer (the probe has marked it quiet).
                    if (answer > 0) counts.put(probe.getKey(), answer);
                } else {
                    quietUntil.put(probe.getKey(), System.nanoTime() + elsewhereCooldown.toNanos());
                }
            } catch (ExecutionException e) {
                // Could not be asked: counts as none.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolError("interrupted while counting this user's running tasks");
            }
        }
        return counts;
    }

    /** The probe of {@code name} under way, or a new one: never two at once for one instance. */
    private CompletableFuture<Long> probeElsewhere(String name) {
        var started = new CompletableFuture<Long>();
        var probe = probing.putIfAbsent(name, started);
        if (probe != null) return probe;
        Thread.ofVirtual().start(() -> {
            try {
                var out = new ByteArrayOutputStream();
                var began = System.nanoTime();
                var exit = backend.probe(name, TaskScripts.busy(), out, elsewhereTimeout);
                if (exit != 0) throw new ToolError("exit " + exit);
                var count = (long) TaskScripts.taskIds(out.toString(StandardCharsets.UTF_8).lines()).size();
                // An instance that answers, but slowly -- after the caller gave up, or close to it --
                // would cost every task start nearly the whole wait: it is treated as one that did
                // not answer, and not asked while it cools down.
                if (System.nanoTime() - began > elsewhereTimeout.toNanos() / 2) {
                    quietUntil.put(name, System.nanoTime() + elsewhereCooldown.toNanos());
                }
                started.complete(count);
            } catch (RuntimeException e) {
                started.completeExceptionally(e);
            } finally {
                probing.remove(name, started);
            }
        });
        return started;
    }

    /** Ask each instance, once, which of the tasks believed running still are. */
    private void refreshStates() {
        refreshStates(instance -> true);
    }

    /** {@link #refreshStates()}, asking only the instances {@code which} accepts. */
    private void refreshStates(java.util.function.Predicate<String> which) {
        forgetUnheld();
        List<Task> believedRunning;
        synchronized (this) {
            believedRunning = tasks.values().stream()
                    .filter(t -> t.running() && !t.launching() && which.test(t.instance())).toList();
        }
        believedRunning.stream().collect(Collectors.groupingBy(Task::instance)).forEach((instance, list) -> {
            // Adopted by another session (or gone): its tasks are no longer this session's.
            if (session.hold(instance) != McpSession.Hold.HELD) {
                forgetIfUnheld(instance);
                return;
            }
            try {
                probe(instance, list.stream().map(Task::id).toList());
            } catch (RuntimeException e) {
                // An instance that is gone takes its tasks with it; one we merely cannot reach
                // right now keeps them as they were, so their slots stay counted.
                if (isGone(instance)) forgetInstance(instance);
            }
        });
    }

    /** Whether Incus says the instance no longer exists; not when Incus cannot be asked. */
    private boolean isGone(String instance) {
        try {
            return backend.metadata(instance) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Forget the tasks of an instance that no longer exists. */
    synchronized void forgetInstance(String instance) {
        forgetLocked(instance, TaskWatcher.LOST);
    }

    /**
     * Forget the tasks of an instance the session has let go of, for the reason it recorded
     * then. Decided under the lock {@link #adopt} takes, so an adoption completing meanwhile
     * keeps its tasks rather than having them forgotten and tombstoned behind it.
     */
    private synchronized void forgetIfUnheld(String instance) {
        var why = session.whyNotHeld(instance);
        if (why == McpSession.Hold.HELD) return;
        forgetLocked(instance, why == McpSession.Hold.GONE ? TaskWatcher.LOST : TaskWatcher.RELEASED);
    }

    /** Holds the lock: the watcher hears of it before an adoption can bring the tasks back. */
    private void forgetLocked(String instance, String reason) {
        tasks.values().removeIf(t -> {
            if (!t.instance().equals(instance)) return false;
            watcher.forget(t.id(), instance, reason);
            return true;
        });
    }

    /**
     * One round of the watcher's polling: every task it last reported running, or never
     * reported (adopted, or cancelled since), one exec per instance. A slow round also looks at
     * finished agents, whose conversation a person may have joined or left, and first asks
     * whether the session still holds each instance with tasks: another session may have adopted it.
     * An instance that could not be asked (stopped, say) is left alone until the next slow round,
     * rather than costing a failed exec every two seconds.
     */
    void watchRound(boolean slow) {
        if (slow) unanswered.clear();
        forgetUnheld();
        List<Task> current;
        synchronized (this) {
            current = List.copyOf(tasks.values());
        }
        if (slow) {
            current.stream().map(Task::instance).distinct().forEach(instance -> {
                if (session.hold(instance) != McpSession.Hold.HELD) forgetIfUnheld(instance);
            });
        }
        current.stream()
                .filter(t -> !t.launching() && session.holds(t.instance()) && !unanswered.contains(t.instance()))
                .filter(t -> {
                    var state = watcher.lastState(t.id());
                    return state == null || state.equals(TaskWatcher.RUNNING)
                            || (slow && AGENT.equals(t.kind()) && TaskWatcher.ATTACHABLE.contains(state));
                })
                .collect(Collectors.groupingBy(Task::instance, LinkedHashMap::new, Collectors.toList()))
                .forEach((instance, watched) -> {
                    try {
                        watch(instance, watched);
                    } catch (RuntimeException e) {
                        // As for any probe: gone takes its tasks with it, unreachable changes nothing.
                        if (isGone(instance)) forgetInstance(instance);
                        else unanswered.add(instance);
                    }
                });
    }

    /** One task as {@link TaskScripts#watch} saw it; {@code session} and {@code cwd} for a finished agent. */
    private static final class Seen {
        int run;
        String state;
        Integer exit;
        String session;
        String cwd;
    }

    /**
     * Ask one instance how these tasks are, and report what changed. A reading of an earlier run
     * than the session now knows of (a run launched while the exec was out) is stale, and dropped.
     */
    private void watch(String instance, List<Task> watched) {
        var ids = watched.stream().map(Task::id).toList();
        observeWatch(instance, ids, run(instance, TaskScripts.watch(ids), null));
    }

    /** Report what {@link TaskScripts#watch} said about these tasks. */
    private void observeWatch(String instance, List<String> ids, String output) {
        var seen = new LinkedHashMap<String, Seen>();
        var presence = new java.util.ArrayList<String>();
        for (var line : output.split("\n")) {
            if (line.startsWith("presence ")) {
                presence.add(line.substring("presence ".length()));
                continue;
            }
            var parts = line.split(" ", 3);
            if (parts.length < 3 || !ids.contains(parts[1])) continue;
            var task = seen.computeIfAbsent(parts[1], k -> new Seen());
            switch (parts[0]) {
                case "task" -> {
                    var f = parts[2].split(" ");
                    task.run = (int) parseLong(f[0]);
                    task.state = f.length > 1 ? f[1] : "unknown";
                    if (f.length > 2) task.exit = (int) parseLong(f[2]);
                }
                case "sid" -> task.session = parts[2].strip();
                case "cwd" -> task.cwd = parts[2].strip();
                default -> { }
            }
        }
        var who = Presence.parse(presence);
        var known = new HashMap<String, Boolean>();
        seen.forEach((id, task) -> {
            int runs;
            synchronized (this) {
                var now = tasks.get(id);
                if (task.state == null || now == null || task.run > 0 && task.run < now.runs()) return;
                runs = now.runs();
            }
            // No run at all (its directory is gone): reported under the run the session knows.
            if (task.run == 0) task.run = runs;
            var state = task.state.equals("finished") && task.session != null
                    && who.holderOf(task.session, task.cwd) != null ? "attached" : task.state;
            if (!state.equals("unknown")) known.put(id, state.equals(TaskWatcher.RUNNING));
            watcher.observe(id, instance, task.run, state, task.exit);
        });
        markRunning(known);
    }

    /**
     * Launch a reserved run, once {@code check} (if any) passed. On any failure -- the check's
     * and building its run script included, which refuses what an agent sent (a bad
     * environment name) -- the reservation is undone: a fresh task is
     * forgotten, a continued one goes back to how it was. Otherwise the slot would stay counted
     * for good, as nothing ever re-probes a launching task.
     */
    private Task launch(Task reserved, Task previous, Runnable check,
                        java.util.function.Function<Task, String> runScript, String stdin) {
        try {
            if (check != null) check.run();
            run(reserved.instance(), TaskScripts.launch(reserved.id(), reserved.runs(), reserved.kind(),
                    runScript.apply(reserved)), stdin);
        } catch (RuntimeException e) {
            synchronized (this) {
                if (previous == null) tasks.remove(reserved.id());
                else tasks.put(previous.id(), previous);
            }
            throw e;
        }
        Task launched;
        synchronized (this) {
            launched = reserved.launched();
            tasks.put(launched.id(), launched);
        }
        watcher.observe(launched.id(), launched.instance(), launched.runs(), TaskWatcher.RUNNING, null);
        return launched;
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
        // Decided in the guest (TaskScripts.RUN_STATE); anything else says nothing about the task.
        var state = header.getOrDefault("state", "unknown");
        if (!List.of("running", "finished", "lost").contains(state)) state = "unknown";
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
