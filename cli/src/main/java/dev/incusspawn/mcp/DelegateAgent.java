package dev.incusspawn.mcp;

/** The closed set of worker adapters. No discovery or initialization outside the MCP path. */
enum DelegateAgent {
    CLAUDE("claude", "claude", "Claude Code"), CODEX("codex", "openai", "Codex"),
    /**
     * Pi spends Claude's account or OpenAI's by the provider its template chose (PiSetup), and
     * the proxy injects whichever it is. Its namespace is only read for a model preflight, which
     * Pi has none of, so it names none.
     */
    PI("pi", "", "Pi"),
    /** opencode spends Claude's, OpenAI's or Gemini's account by its template's provider (OpencodeSetup), as Pi does. */
    OPENCODE("opencode", "", "opencode"),
    UNKNOWN("unknown", "", "an unknown agent");

    final String id;
    final String namespace;
    /** How a message to a coordinator names it. */
    final String label;

    DelegateAgent(String id, String namespace, String label) {
        this.id = id;
        this.namespace = namespace;
        this.label = label;
    }

    static DelegateAgent parse(String name) {
        return switch (name == null ? "" : name) {
            case "claude" -> CLAUDE;
            case "codex" -> CODEX;
            case "pi" -> PI;
            case "opencode" -> OPENCODE;
            default -> UNKNOWN;
        };
    }

    static DelegateAgent select(InstanceBackend.TemplateInfo template, String requested) {
        var agent = requested == null ? template.defaultAgent() : parse(requested);
        if (agent == UNKNOWN || !template.tools().contains(agent.id)) {
            throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "template '" + template.name()
                    + "' cannot delegate to " + (requested == null ? "an agent" : requested)
                    + "; choose a template with supports_delegate and an agent from list_templates.delegate_agents.");
        }
        return agent;
    }

    /** Whether a chosen model is tried before launch ({@link ModelCheck}); for the others an access error is the task's. */
    boolean supportsModelPreflight() {
        return this == CLAUDE;
    }

    /**
     * Whether its runs go through a guest-side runner ({@code codex-runner.cjs},
     * {@code pi-runner.cjs}, {@code opencode-runner.cjs}), which publishes {@code result-<run>.txt}
     * and {@code outcome-<run>.json}.
     */
    boolean usesRunner() {
        return this == CODEX || this == PI || this == OPENCODE;
    }

    /**
     * Whether the agent names its own session, which the runner saves from its first event, and
     * can recover from {@code events-1.jsonl} when a kill cut that short ({@code recover}).
     */
    boolean recoversSession() {
        return this == CODEX || this == OPENCODE;
    }

    /**
     * What it runs under: full access, with no turn limit, since the instance is the boundary
     * (#1857). Claude's {@code --permission-mode}, Codex's sandbox (always with
     * {@code approval_policy=never}), for Pi, which has neither, every tool, and for opencode
     * {@code --auto}, which answers every permission it would ask about. Refused only for
     * an agent this isx does not know.
     */
    String permissionMode() {
        return switch (this) {
            case CLAUDE -> "bypassPermissions";
            case CODEX -> "danger-full-access";
            case PI, OPENCODE -> "full-access";
            case UNKNOWN -> throw unknown();
        };
    }

    /** Refuses an agent this isx does not know, such as a task a newer isx recorded. */
    void requireKnown() {
        if (this == UNKNOWN) throw unknown();
    }

    private static ToolError unknown() {
        return new ToolError(ToolError.Code.REFUSED,
                "unsupported task agent or metadata version; upgrade isx before continuing it");
    }

    /**
     * A CLI check inside the worker, with no inference and no credential reads. Each help is read
     * from stdout and stderr alike: opencode's yargs prints it on stderr, and a CLI that moves it
     * there must not have every delegate refused.
     */
    String probe() {
        return switch (this) {
            case CODEX -> "command -v codex >/dev/null && command -v node >/dev/null"
                    + " && timeout 10 codex exec --help 2>&1 | grep -q -- --output-last-message"
                    + " && timeout 10 codex exec resume --help 2>&1 | grep -q -- --json";
            case PI -> "command -v pi >/dev/null && command -v node >/dev/null"
                    + " && timeout 10 pi --help 2>&1 | grep -q -- --session-id";
            case OPENCODE -> "command -v opencode >/dev/null && command -v node >/dev/null"
                    + " && timeout 10 opencode run --help 2>&1 | grep -q -- --auto";
            default -> "command -v claude >/dev/null";
        };
    }

    /** What the coordinator is told when {@link #probe} fails. */
    String probeRefusal(String instance) {
        return switch (this) {
            case CODEX -> "Codex and Node with exec/resume JSON output are required in " + instance
                    + "; rebuild its template or upgrade the CLI.";
            case PI -> "Pi with --session-id and Node are required in " + instance
                    + "; rebuild its template or upgrade the CLI.";
            case OPENCODE -> "opencode with run --auto and Node are required in " + instance
                    + "; rebuild its template or upgrade the CLI.";
            default -> "Claude Code is required in " + instance + "; rebuild its template.";
        };
    }
}
