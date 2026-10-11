package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;

/** The closed set of worker adapters. No discovery or initialization outside the MCP path. */
enum DelegateAgent {
    CLAUDE("claude", "claude"), CODEX("codex", "openai"), UNKNOWN("unknown", "");

    final String id;
    final String namespace;

    DelegateAgent(String id, String namespace) {
        this.id = id;
        this.namespace = namespace;
    }

    static DelegateAgent parse(String name) {
        return switch (name == null ? "" : name) {
            case "claude" -> CLAUDE;
            case "codex" -> CODEX;
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

    String permissionMode(McpConfig config, String template) {
        try {
            return switch (this) {
                case CLAUDE -> config.delegatePermissionMode(template);
                case CODEX -> config.codexSandboxMode(template);
                case UNKNOWN -> throw new IllegalArgumentException("unsupported task agent or metadata version");
            };
        } catch (IllegalArgumentException e) {
            throw new ToolError(ToolError.Code.REFUSED, e.getMessage());
        }
    }

    void requireBudget(McpConfig config, Tasks.Profile profile) {
        if (this == UNKNOWN) throw new ToolError(ToolError.Code.REFUSED, "unsupported task agent or metadata version; upgrade isx before continuing it");
        if (this != CODEX) return;
        if (profile.maxTurns() != null) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "Codex does not support max_turns; omit it.");
        if (config.delegateMaxTurns() != null) throw new ToolError(ToolError.Code.REFUSED,
                "Codex cannot enforce mcp.delegate-max-turns; the user must revise that policy before delegating to Codex.");
    }

    /** A CLI check inside the worker, with no inference and no credential reads. */
    String probe() {
        if (this != CODEX) return "command -v claude >/dev/null";
        return "command -v codex >/dev/null && command -v node >/dev/null"
                + " && timeout 10 codex exec --help | grep -q -- --output-last-message"
                + " && timeout 10 codex exec resume --help | grep -q -- --json";
    }
}
