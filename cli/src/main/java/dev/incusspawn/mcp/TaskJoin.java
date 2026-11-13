package dev.incusspawn.mcp;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.OutputFormat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Joining a delegated agent's conversation from the host: {@code isx shell <instance> --task
 * [<id>]}, the TUI's Shift+F2, and {@code task_status.join_command}. Resuming is a {@code cd} to
 * the task's recorded {@code cwd} plus the agent's own resume command, which is exactly what
 * {@link Presence} recognises as a person in the conversation: so {@code task_status} says
 * {@code attached} and {@code send_message} is refused while they are in it. Everything is read
 * from the task directories in one exec, with no {@code isx mcp} session: an orphan's task can be
 * joined too.
 */
public final class TaskJoin {
    private static final Pattern EPOCH_SECONDS = Pattern.compile("[0-9]{1,12}");

    private static final Pattern SESSION_ID = Tasks.SESSION_ID;
    private static final int PROMPT_WIDTH = 60;

    private TaskJoin() {}

    /**
     * A delegated agent task in an instance. {@code state} is {@link TaskScripts#joinable}'s
     * ({@code running}, {@code finished}, {@code lost} or {@code unknown}); {@code startedAt} is
     * when its current run started, null if unknown; {@code instruction} the first line of its
     * first prompt, as one line safe to print; {@code attachedPid} the coding agent of a person
     * already in its conversation, or null.
     */
    public record Entry(String id, String agent, String state, Instant startedAt, String instruction,
                        String sessionId, String cwd, Long attachedPid) {

        /** Whether its conversation can be resumed at all: a known agent, a saved session and a directory. */
        boolean resumable() {
            return DelegateAgent.parse(agent) != DelegateAgent.UNKNOWN && sessionId != null && !cwd.isEmpty();
        }

        /**
         * Whether something may be writing to the conversation now: a run that is going (or that
         * systemd could not say is over), or a person already in it.
         */
        public boolean busy() {
            return !"finished".equals(state) && !"lost".equals(state) || attachedPid != null;
        }

        /** Whether it is a task a person could mean: resumable, or a known agent's run still going. */
        boolean joinable() {
            return resumable() || DelegateAgent.parse(agent) != DelegateAgent.UNKNOWN && busy();
        }

        /** Whether its current run is going, as systemd says. */
        public boolean running() {
            return "running".equals(state);
        }

        /**
         * The command, run as agentuser in the instance, that resumes this conversation; with a
         * {@code nonce} ({@link #nonce}), a refusal in the guest is left for {@link #refusedInGuest}.
         */
        public String guestCommand(String nonce) {
            return TaskJoin.guestCommand(id, DelegateAgent.parse(agent), cwd, sessionId, nonce);
        }

        /** {@link #guestCommand(String)} for a caller that does not ask afterwards whether it was refused. */
        public String guestCommand() {
            return guestCommand(null);
        }
    }

    /** What joining comes to: one conversation to enter, or why not. */
    public sealed interface Outcome {
        /** Resume {@code entry}'s conversation. */
        record Join(Entry entry) implements Outcome {}
        /** {@code entry} is mid-turn (or its state unknown): two writers on one session would race. */
        record Busy(Entry entry) implements Outcome {}
        /** No resumable agent task in the instance. */
        record None() implements Outcome {}
        /** No resumable agent task with the id asked for. */
        record NotFound(String id) implements Outcome {}
        /** Several, and none asked for: never guessed. */
        record Several(List<Entry> entries) implements Outcome {}
    }

    /** How long reading the tasks may take: the TUI waits on it at a keypress. */
    private static final Duration READ_LIMIT = Duration.ofSeconds(10);

    /**
     * The agent tasks recorded in {@code instance}, from one exec as agentuser, bounded by
     * {@link #READ_LIMIT} so a guest that never answers cannot hold the caller.
     */
    public static List<Entry> read(IncusClient incus, String instance) {
        var out = new ByteArrayOutputStream();
        int exit;
        try {
            exit = probe(incus, instance, TaskScripts.joinable(), out);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not read the tasks in " + instance + ": "
                    + OutputFormat.oneLine(String.valueOf(e.getMessage())), e);
        }
        if (exit != 0) throw new IllegalStateException("Could not read the tasks in " + instance + " (exit " + exit + ")");
        return parse(out.toString(StandardCharsets.UTF_8));
    }

    /** {@link TaskScripts#joinable}'s output, in the order the guest listed the tasks. */
    static List<Entry> parse(String output) {
        var fields = new LinkedHashMap<String, LinkedHashMap<String, String>>();
        var presence = new ArrayList<String>();
        for (var line : output.split("\n")) {
            if (line.startsWith("presence ")) {
                presence.add(line.substring("presence ".length()));
                continue;
            }
            var parts = line.split(" ", 3);
            if (parts.length < 2) continue;
            fields.computeIfAbsent(parts[1], k -> new LinkedHashMap<>()).put(parts[0], parts.length > 2 ? parts[2] : "");
        }
        var who = Presence.parse(presence);
        var entries = new ArrayList<Entry>();
        fields.forEach((id, f) -> {
            if (!f.containsKey("task")) return;
            var task = f.get("task").split(" ");
            var state = List.of("running", "finished", "lost").contains(task[0]) ? task[0] : "unknown";
            Instant started = null;
            if (task.length > 1 && EPOCH_SECONDS.matcher(task[1]).matches()) started = Instant.ofEpochSecond(Long.parseLong(task[1]));
            var sid = f.getOrDefault("sid", "").strip();
            var agent = Tasks.recordedAgent(f.get("identity"));
            var session = SESSION_ID.matcher(sid).matches() ? sid : null;
            var cwd = f.getOrDefault("cwd", "").strip();
            // As task_status decides attached: an agent resuming the session, or working where it did.
            entries.add(new Entry(id, agent.id, state, started,
                    shorten(OutputFormat.oneLine(f.getOrDefault("prompt", "").strip())),
                    session, cwd, session == null ? null : who.holderOf(agent, session, cwd)));
        });
        return entries;
    }

    /**
     * What to join among {@code entries}: the task {@code id} names, or with none (null or blank)
     * the only one a person could mean ({@link Entry#joinable}). A busy task is refused, never
     * waited for.
     */
    public static Outcome resolve(List<Entry> entries, String id) {
        var resumable = entries.stream().filter(Entry::joinable).toList();
        Entry chosen;
        if (id != null && !id.isBlank()) {
            chosen = resumable.stream().filter(e -> e.id().equals(id.strip())).findFirst().orElse(null);
            if (chosen == null) return new Outcome.NotFound(id.strip());
        } else if (resumable.isEmpty()) {
            return new Outcome.None();
        } else if (resumable.size() > 1) {
            return new Outcome.Several(resumable);
        } else {
            chosen = resumable.getFirst();
        }
        return chosen.busy() ? new Outcome.Busy(chosen) : new Outcome.Join(chosen);
    }

    /**
     * The command that resumes task {@code taskId}'s conversation in the guest: its recorded
     * directory, {@link TaskScripts#joinGuard} again in the same shell, then the agent's resume, as
     * {@link Presence} recognises it (argv and physical cwd). A failed {@code cd} or guard exits
     * the shell rather than falling back to a login shell. {@code sessionId} must be
     * {@link #SESSION_ID}-shaped, as {@link Entry} and {@link Tasks.Status} only carry such.
     */
    static String guestCommand(String taskId, DelegateAgent agent, String cwd, String sessionId, String nonce) {
        if (!SESSION_ID.matcher(sessionId).matches()) throw new IllegalArgumentException("not a session id: " + sessionId);
        var resume = switch (agent) {
            case CLAUDE -> "claude --resume ";
            case CODEX -> "codex resume ";
            case PI -> "pi --session-id ";
            case OPENCODE -> "opencode --session ";
            case UNKNOWN -> throw new IllegalArgumentException("unsupported task agent");
        };
        // Under the conversation's lock, so a run that starts in the meantime and the person's
        // agent cannot both run; the shell stays, to say so if the lock was taken first.
        return TaskScripts.joinGuard(taskId, agent, sessionId, cwd, nonce)
                + "cd " + ExecScript.quote(cwd) + " || isx_refuse 'its directory is gone'; "
                + TaskScripts.requireFlock("isx_refuse 'flock (util-linux) is missing in this instance'") + "; "
                + TaskScripts.CONVERSATION_LOCK + resume + sessionId + "; rc=$?; [ $rc != " + TaskScripts.CONVERSATION_TAKEN
                + " ] || isx_refuse 'a run of it, or another person, took its conversation first'; exit $rc";
    }

    /** A fresh token naming one join, for {@link Entry#guestCommand} and {@link #refusedInGuest}. */
    public static String nonce() {
        return McpSession.randomSuffix(12);
    }

    /**
     * Whether the guest refused the join {@code nonce} named, asked after its shell ended (false
     * when it cannot be asked): one more exec, after the session, never before it.
     */
    public static boolean refusedInGuest(IncusClient incus, String instance, String taskId, String nonce) {
        try {
            var out = new ByteArrayOutputStream();
            // Briefly: it runs after every join, and the person is waiting for their prompt.
            return probe(incus, instance, TaskScripts.joinRefusal(taskId, nonce), out, Duration.ofSeconds(3)) == 0
                    && !out.toString(StandardCharsets.UTF_8).isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code script} as agentuser, bounded by {@link #READ_LIMIT}; its exit, its stdout in {@code out}. */
    private static int probe(IncusClient incus, String instance, String script, ByteArrayOutputStream out) {
        return probe(incus, instance, script, out, READ_LIMIT);
    }

    private static int probe(IncusClient incus, String instance, String script, ByteArrayOutputStream out, Duration limit) {
        return incus.execProbe(instance, IncusInstanceBackend.AGENT_UID, IncusInstanceBackend.AGENT_HOME, script, out, limit);
    }

    /** The host command a person runs to join {@code taskId} in {@code instance}. */
    public static String hostCommand(String instance, String taskId) {
        return "isx shell " + instance + " --task " + taskId;
    }

    /** One row of the table {@code isx shell --task} prints when several tasks could be meant. */
    public static String describe(Entry entry, Instant now) {
        return entry.id() + "  " + entry.agent() + "  " + entry.state()
                + (entry.startedAt() != null ? " " + age(entry.startedAt(), now) : "")
                + (entry.instruction().isEmpty() ? "" : "  " + entry.instruction());
    }

    /**
     * Why {@code outcome} joins nothing, as one line for {@code isx shell --task} and the TUI's
     * status line alike; null for a join, and for several, which each caller lists its own way.
     */
    public static String refusal(Outcome outcome, String instance) {
        return switch (outcome) {
            case Outcome.None none -> "No delegated task to join in " + instance + ".";
            case Outcome.NotFound missing -> "No delegated task " + missing.id() + " to join in " + instance
                    + "; to list them: isx shell " + instance + " --task";
            case Outcome.Busy busy -> busy.entry().attachedPid() != null
                    ? "A person is already in " + busy.entry().id() + "'s conversation (agent pid " + busy.entry().attachedPid()
                            + "); a second would write to it at the same time."
                    : busy.entry().running()
                    ? busy.entry().id() + " is mid-turn, and joining now would race its run on one conversation; join it once the turn ends."
                    : "Cannot tell whether " + busy.entry().id() + " is still running (systemd did not answer), so not joining it.";
            case Outcome.Join join -> null;
            case Outcome.Several several -> null;
        };
    }

    /** The hint plain {@code isx shell} prints before the prompt, or null when there is nothing to join. */
    public static String hint(String instance, List<Entry> entries) {
        var resumable = entries.stream().filter(Entry::joinable).toList();
        if (resumable.isEmpty()) return null;
        if (resumable.size() == 1) {
            return "A delegated task's conversation is here; to join it: " + hostCommand(instance, resumable.getFirst().id());
        }
        return resumable.size() + " delegated tasks' conversations are here; to pick one: isx shell " + instance + " --task";
    }

    /**
     * The last few things a busy task's agent did, each one line safe to print: what
     * {@code task_status} shows as {@code recent}, from one more exec.
     */
    public static List<String> recent(IncusClient incus, String instance, Entry entry, int keep) {
        var result = incus.execInContainer(instance, IncusInstanceBackend.AGENT_USER, TaskScripts.status(entry.id(), 4096));
        if (result.exitCode() != 0) return List.of();
        var summary = StreamJsonEvents.summarize(DelegateAgent.parse(entry.agent()), Tasks.parse(result.stdout()).output(), keep);
        return summary.recent().stream().map(line -> OutputFormat.oneLine(line).strip()).toList();
    }

    /** How long ago {@code then} was, coarsely ("12m", "3h", "2d"). */
    static String age(Instant then, Instant now) {
        var d = Duration.between(then, now);
        if (d.isNegative() || d.toMinutes() < 1) return "just now";
        if (d.toHours() < 1) return d.toMinutes() + "m ago";
        if (d.toDays() < 1) return d.toHours() + "h ago";
        return d.toDays() + "d ago";
    }

    private static String shorten(String text) {
        return text.codePointCount(0, text.length()) <= PROMPT_WIDTH ? text
                : text.substring(0, text.offsetByCodePoints(0, PROMPT_WIDTH - 1)) + "…";
    }
}
