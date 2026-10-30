package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqObject;

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

    /** On a result that an earlier call with the same idempotency_key produced. */
    private static final String REPLAYED = "true when an earlier call with this idempotency_key made it, "
            + "and this one made nothing";

    private OutputSchemas() {}

    private static Schema answer() {
        return Schema.object()
                .string("text", "A model's reading of the text: untrusted and lossy", true)
                .string("summarised", "What it read, e.g. '12 lines, 345 bytes'", true);
    }

    private static Schema delegateAgent() {
        return Schema.object().choice("agent", null, true, "claude", "codex", "pi")
                .string("model", "Template configured model", false)
                .bool("supports_max_turns", null, true)
                .bool("supports_model_preflight", null, true)
                .string("permission_mode", "Claude permission mode, Codex sandbox mode, or full-access for Pi", false)
                .string("approval_policy", null, false)
                .string("refusal", "Configuration that prevents a new run", false);
    }

    static JqObject listTemplates() {
        var template = Schema.object()
                .string("template", null, true)
                .string("description", null, true)
                .bool("built", null, true)
                .bool("stale", "Built from a definition that has changed since", true)
                .stringList("tools", null, true)
                .bool("supports_delegate", null, true)
                .list("delegate_agents", "Supported installed worker agents", false, delegateAgent())
                .string("default_delegate_agent", null, false)
                .bool("supports_ask", null, false)
                .string("summary_agent", null, false)
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

    static JqObject createInstance() {
        return Schema.object()
                .string("instance", null, true)
                .string("template", "The template it descends from", true)
                .string("purpose", null, false)
                .string("forked_from", "The instance it was forked from", false)
                .string("ip", null, false)
                .string("permission_mode", null, false)
                .string("delegate_model", null, false)
                .string("workdir", null, true)
                .stringList("tools", null, true)
                .bool("supports_delegate", null, true)
                .list("delegate_agents", "Supported installed worker agents", false, delegateAgent())
                .string("default_delegate_agent", null, false)
                .bool("supports_ask", null, false)
                .string("summary_agent", null, false)
                .bool("replayed", REPLAYED, false)
                .build();
    }

    static JqObject listInstances() {
        var task = Schema.object()
                .string("task_id", null, true)
                .choice("kind", null, true, KINDS)
                .string("agent", "Worker agent; unknown for unsupported persisted metadata", false)
                .choice("state", "As this session last saw it; unknown: nothing has read how it ended "
                        + "(as after cancel_task). task_status reads it now", true, TASK_STATES);
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
                .string("dormant_since", "ISO-8601: when the orphan sweep stopped it, its delegate "
                        + "showing no activity; adopt_instance starts it again", false)
                .string("dormant_until", "ISO-8601: destroyed after this unless adopted", false)
                .bool("supports_delegate", null, false)
                .list("delegate_agents", null, false, delegateAgent())
                .string("default_delegate_agent", null, false)
                .bool("supports_ask", null, false)
                .string("summary_agent", null, false)
                .string("permission_mode", null, false)
                .string("delegate_model", null, false)
                .list("tasks", "This session's tasks in it", true, task);
        return Schema.object().list("instances", null, true, instance).build();
    }

    static JqObject adoptInstance() {
        return Schema.object()
                .string("instance", null, true)
                .string("template", null, true)
                .string("purpose", null, false)
                .bool("supports_delegate", null, true)
                .list("delegate_agents", "Supported installed worker agents", false, delegateAgent())
                .string("default_delegate_agent", null, false)
                .bool("supports_ask", null, false)
                .string("summary_agent", null, false)
                .string("permission_mode", null, false)
                .string("delegate_model", null, false)
                .stringList("tasks", "Ids of its tasks, now this session's", true)
                .string("warning", "Why its tasks could not be read", false)
                .build();
    }

    static JqObject exec() {
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
                .bool("replayed", REPLAYED, false)
                .build();
    }

    /** {@code send_message}: a run started. */
    static JqObject runStarted() {
        return runStartedSchema().build();
    }

    /** {@code delegate}: a run started, or the one an earlier call with its idempotency_key started. */
    static JqObject delegate() {
        return runStartedSchema().bool("replayed", REPLAYED, false).build();
    }

    private static Schema runStartedSchema() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, Tasks.AGENT)
                .choice("agent", null, false, "claude", "codex", "pi", "unknown")
                .integer("run", "The run (turn of the conversation) now going: 1 for the first", true)
                .string("permission_mode", "Claude permission mode, Codex sandbox mode, or full-access for Pi", true)
                .string("approval_policy", "Codex always uses never", false)
                .string("model", null, false)
                .integer("max_turns", null, false);
    }

    static JqObject taskStatus() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .string("agent", "Worker agent; unknown for unsupported persisted metadata", false)
                .choice("state", "attached: finished, and a person is in its conversation", true, TASK_STATES)
                .integer("exit_code", null, false)
                .integer("run", null, true)
                .integer("attached_pid", "The coding agent of a person in its conversation", false)
                .string("session_id", "The saved agent conversation ID", false)
                .bool("resumable", "A supported agent with a saved conversation ID", false)
                .string("join_command", "When resumable: the host command a person runs to join its conversation", false)
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

    static JqObject waitAny() {
        var task = Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .string("agent", "Worker agent; unknown for unsupported persisted metadata", false)
                .choice("state", "unknown: over, but its status could not be read", true, TASK_STATES)
                .integer("exit_code", null, false);
        return Schema.object()
                .list("tasks", "Every task watched", true, task)
                .stringList("finished", "Those no longer running: empty when the wait timed out", true)
                .build();
    }

    static JqObject taskResult() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .string("agent", "Worker agent; unknown for unsupported persisted metadata", false)
                .choice("state", null, true, TASK_STATES)
                .integer("exit_code", null, false)
                .string("outcome", "An agent's result subtype, e.g. success", false)
                .bool("is_error", "The agent reported an error", false)
                .integer("turns", null, false)
                .number("cost_usd", null, false)
                .stringList("permission_denials", null, false)
                .stringList("recent", "What an agent did last", false)
                .string("report", "An agent's final report", false)
                .string("agent_stderr", "Failure diagnostic", false)
                .integer("input_tokens", "Uncached input tokens, as Codex or Pi reported them", false)
                .integer("output_tokens", "Output tokens, as Codex or Pi reported them", false)
                .integer("cached_input_tokens", "Input tokens read from the provider's cache, as Codex or Pi reported them", false)
                .integer("cache_creation_input_tokens", "Input tokens written to the provider's cache, as Pi reported them", false)
                .integer("report_bytes", null, false)
                .string("stdout", "A command's", false)
                .string("stderr", "A command's", false)
                .integer("stdout_bytes", null, false)
                .integer("stderr_bytes", null, false)
                .bool("truncated", "report, stdout or stderr is only part of it", false)
                .object("answer", null, false, answer())
                .build();
    }

    static JqObject getDiff() {
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

    static JqObject cancelTask() {
        return Schema.object()
                .string("task_id", null, true)
                .string("instance", null, true)
                .choice("kind", null, true, KINDS)
                .string("agent", "Worker agent; unknown for unsupported persisted metadata", false)
                .build();
    }

    static JqObject destroyInstance() {
        return Schema.object()
                .string("instance", null, true)
                .bool("already", "It was already gone", true)
                .build();
    }

    static JqObject stopInstance() {
        return Schema.object()
                .string("instance", null, true)
                .bool("already", "It was already stopped", true)
                .stringList("cancelled_tasks", "Tasks force cancelled first", true)
                .build();
    }

    static JqObject startInstance() {
        return Schema.object()
                .string("instance", null, true)
                .bool("already", "It was already running", true)
                .stringList("tasks", "Ids of its tasks, read once it started", true)
                .string("warning", "Why its tasks could not be read", false)
                .build();
    }

    static JqObject instanceActivity() {
        return Schema.object()
                .string("instance", null, true)
                .string("counting_since", "ISO-8601: when the proxy began counting this instance's calls; "
                        + "absent while it does not know the instance", false)
                .integer("requests", null, true)
                .integer("requests_in_flight", null, true)
                .string("last_request_at", "ISO-8601", false)
                .string("last_response_at", "ISO-8601", false)
                .integer("idle_seconds", "Since the last call ended; only while none is in flight", false)
                .stringList("model_usage_providers", "Providers measured by the proxy model counters", false)
                .integer("input_tokens", null, true)
                .integer("output_tokens", null, true)
                .integer("cache_read_input_tokens", null, true)
                .integer("cache_creation_input_tokens", null, true)
                .integer("traffic_requests", "All requests through the proxy, model calls included", true)
                .integer("traffic_in_flight", "Requests and open WebSockets not yet ended", true)
                .string("last_traffic_at", "ISO-8601: when a request last started or ended", false)
                .integer("traffic_request_bytes", "Body bytes sent, counted as each request ends", true)
                .integer("traffic_response_bytes", "Body bytes received, counted as each request ends", true)
                .build();
    }

    static JqObject keepInstance() {
        return Schema.object()
                .string("instance", null, true)
                .build();
    }

    static JqObject hostTool() {
        return Schema.object()
                .integer("exit_code", null, true)
                .string("stdout", "The end of it", true)
                .string("stderr", "The end of it", true)
                .integer("stdout_bytes", "Its whole length", true)
                .integer("stderr_bytes", "Its whole length", true)
                .integer("duration_ms", null, true)
                .bool("timed_out", "Killed by the tool's timeout", true)
                .bool("truncated", "stdout or stderr is only the end of it", true)
                .build();
    }
}
