package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What each tool's {@code structuredContent} holds: the contract with a program driving
 * {@code isx mcp}, so names are shared across tools ({@code instance}, {@code template},
 * {@code task_id}, {@code state}, {@code exit_code}, {@code run}) and every object is closed.
 * {@code McpOutputSchemaTest} keeps a golden copy; change one deliberately.
 *
 * <p>Methods rather than constants: nothing here is built until {@code isx mcp} lists its tools.
 */
final class OutputSchemas {

    /**
     * The one vocabulary of a task's {@code state}, in every tool that reports one; the
     * {@code isx/task_changed} notification uses the same words, adds {@code released} and never
     * says {@code unknown}. {@code McpOutputSchemaTest} pins both.
     */
    static final String[] TASK_STATES = {"running", "finished", "attached", "lost", "unknown"};
    static final String[] KINDS = {Tasks.AGENT, Tasks.COMMAND};

    private OutputSchemas() {}

    private static Schema answer() {
        return Schema.object()
                .string("text", "A model's reading of the text: untrusted and lossy", true)
                .string("summarised", "What it read, e.g. '12 lines, 345 bytes'", true);
    }

    static ObjectNode listTemplates() {
        var template = Schema.object()
                .string("template", null, true)
                .string("description", null, true)
                .bool("built", null, true)
                .bool("stale", "Built from a definition that has changed since", true)
                .stringList("tools", null, true)
                .bool("supports_delegate", null, true)
                .string("permission_mode", "The delegated agent's permission mode", false)
                .string("delegate_model", "The delegated agent's default model", false);
        return Schema.object()
                .list("templates", "Approved templates; empty when none are", true, template)
                .integer("max_instances", null, true)
                .integer("max_concurrent_tasks", null, true)
                .integer("orphan_grace_hours", null, true)
                .integer("delegate_max_turns", null, false)
                .build();
    }

    static ObjectNode createInstance() {
        return Schema.object()
                .string("instance", null, true)
                .string("template", "The template it descends from", true)
                .string("purpose", null, false)
                .string("forked_from", "The instance it was forked from", false)
                .string("ip", null, false)
                .string("workdir", null, true)
                .stringList("tools", null, true)
                .bool("supports_delegate", null, true)
                .build();
    }

    static ObjectNode listInstances() {
        var task = Schema.object()
                .string("task_id", null, true)
                .choice("kind", null, true, KINDS)
                .bool("running", "Running as this session last saw it. Not a state: the session does not know whether "
                        + "one not running finished or was lost; task_status says", true);
        var instance = Schema.object()
                .string("instance", null, true)
                .string("template", null, true)
                .string("purpose", null, false)
                .string("created", "ISO-8601", false)
                .choice("state", "creating or ready: held by this session; held: by another live session; "
                        + "orphaned: by none, adopt_instance takes it", true, "creating", "ready", "held", "orphaned")
                .bool("kept", null, true)
                .string("held_by", "The session holding it, as <pid>-<start>", false)
                .string("held_by_client", "The MCP client of that session", false)
                .string("orphaned_since", "ISO-8601", false)
                .string("orphan_until", "ISO-8601: destroyed after this unless adopted", false)
                .list("tasks", "This session's tasks in it", true, task);
        return Schema.object().list("instances", null, true, instance).build();
    }

    static ObjectNode adoptInstance() {
        return Schema.object()
                .string("instance", null, true)
                .string("template", null, true)
                .string("purpose", null, false)
                .bool("supports_delegate", null, true)
                .stringList("tasks", "Ids of its tasks, now this session's", true)
                .string("warning", "Why its tasks could not be read", false)
                .build();
    }

    static ObjectNode exec() {
        return Schema.object()
                .bool("background", "Started as a task: only task_id, instance and kind are set", true)
                .string("task_id", null, false)
                .string("instance", null, false)
                .choice("kind", null, false, Tasks.COMMAND)
                .integer("exit_code", "-1 when isx lost track of the command", false)
                .integer("duration_ms", null, false)
                .bool("timed_out", "Killed by timeout_seconds", false)
                .bool("cancelled", null, false)
                .string("stdout", "The end of it, when not asked about", false)
                .string("stderr", "The end of it, when not asked about", false)
                .integer("stdout_bytes", "Its whole length", false)
                .integer("stderr_bytes", "Its whole length", false)
                .bool("truncated", "stdout or stderr is only the end of it", false)
                .object("answer", null, false, answer())
                .build();
    }

    /** {@code delegate} and {@code send_message}: a run started. */
    static ObjectNode runStarted() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, Tasks.AGENT)
                .integer("run", "The run (turn of the conversation) now going: 1 for the first", true)
                .string("permission_mode", null, true)
                .string("model", null, false)
                .integer("max_turns", null, false)
                .build();
    }

    static ObjectNode taskStatus() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .choice("state", "attached: finished, and a person is in its conversation", true, TASK_STATES)
                .integer("exit_code", null, false)
                .integer("run", null, true)
                .integer("attached_pid", "The Claude Code of a person in its conversation", false)
                .string("started_at", "ISO-8601: when the current run started", false)
                .string("last_activity", "ISO-8601: when the current run last wrote output", false)
                .string("model", null, false)
                .integer("max_turns", "The budget the run gets", false)
                .integer("assistant_messages", null, false)
                .number("cost_usd", "Once an agent has finished", false)
                .stringList("permission_denials", "Tools the agent's permission mode refused", false)
                .stringList("recent", "What an agent did lately", false)
                .string("agent_stderr", "An agent that failed or was lost: the end of its stderr", false)
                .integer("stdout_bytes", null, false)
                .integer("stderr_bytes", null, false)
                .string("stdout_tail", "A command's last lines", false)
                .string("stderr_tail", "A command's last lines", false)
                .build();
    }

    static ObjectNode waitAny() {
        var task = Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .choice("state", "unknown: over, but its status could not be read", true, TASK_STATES)
                .integer("exit_code", null, false);
        return Schema.object()
                .list("tasks", "Every task watched", true, task)
                .stringList("finished", "Those no longer running: empty when the wait timed out", true)
                .build();
    }

    static ObjectNode taskResult() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .choice("state", null, true, TASK_STATES)
                .integer("exit_code", null, false)
                .string("outcome", "An agent's result subtype, e.g. success", false)
                .bool("is_error", "The agent reported an error", false)
                .integer("turns", null, false)
                .number("cost_usd", null, false)
                .stringList("permission_denials", null, false)
                .stringList("recent", "What an agent did last", false)
                .string("report", "An agent's final report", false)
                .integer("report_bytes", null, false)
                .string("stdout", "A command's", false)
                .string("stderr", "A command's", false)
                .integer("stdout_bytes", null, false)
                .integer("stderr_bytes", null, false)
                .bool("truncated", "report, stdout or stderr is only part of it", false)
                .object("answer", null, false, answer())
                .build();
    }

    static ObjectNode getDiff() {
        var file = Schema.object()
                .string("path", "Relative to its repository", true)
                .nullableInteger("added", "Lines; null for a binary file", true)
                .nullableInteger("deleted", "Lines; null for a binary file", true);
        var repo = Schema.object()
                .string("repo", "The repository's directory in the instance", true)
                .list("files", null, true, file);
        return Schema.object()
                .list("repos", "Every repository the task recorded, unless asked about", false, repo)
                .string("patch", "The unified diff, unless stat or too large", false)
                .integer("patch_bytes", null, false)
                .bool("too_large", "The patch exceeded max_bytes and was left out", false)
                .object("answer", null, false, answer())
                .build();
    }

    static ObjectNode cancelTask() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .build();
    }

    static ObjectNode destroyInstance() {
        return Schema.object()
                .string("instance", null, true)
                .bool("already", "It was already gone", true)
                .build();
    }

    static ObjectNode stopInstance() {
        return Schema.object()
                .string("instance", null, true)
                .bool("already", "It was already stopped", true)
                .stringList("cancelled_tasks", "Tasks force cancelled first", true)
                .build();
    }

    static ObjectNode startInstance() {
        return Schema.object()
                .string("instance", null, true)
                .bool("already", "It was already running", true)
                .stringList("tasks", "Ids of its tasks, read once it started", true)
                .string("warning", "Why its tasks could not be read", false)
                .build();
    }

    static ObjectNode keepInstance() {
        return Schema.object()
                .string("instance", null, true)
                .build();
    }
}
