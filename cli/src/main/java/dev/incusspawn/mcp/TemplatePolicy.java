package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;

import java.util.List;
import java.util.function.Supplier;

/**
 * Which templates an agent may branch from. A template must be listed in {@code mcp.templates},
 * be defined by a trusted layer (never a project-local {@code .incus-spawn/} definition, nor an
 * image built from one), and be built. Unbuilt templates are refused, never built on demand.
 *
 * <p>Definitions come from trusted layers only (see {@code IncusInstanceBackend}), so a
 * repository's definitions cannot stand in for an approved template's.
 *
 * <p>The config is read on every call, so a person narrowing the list affects a running session
 * at once. Nothing here, or anywhere in {@code isx mcp}, can widen it.
 */
final class TemplatePolicy {

    static final String HOW_TO_APPROVE = "Only the user can approve templates, by listing them "
            + "under mcp.templates in ~/.config/incus-spawn/config.yaml.";

    private final InstanceBackend backend;
    private final Supplier<McpConfig> config;

    TemplatePolicy(InstanceBackend backend, Supplier<McpConfig> config) {
        this.backend = backend;
        this.config = config;
    }

    /** The approved templates that are defined and trusted, built or not. */
    List<InstanceBackend.TemplateInfo> approved() {
        var listed = config.get().templates();
        if (listed.isEmpty()) return List.of();
        return backend.templates().stream()
                .filter(t -> listed.contains(t.name()) && !t.projectLocal())
                .toList();
    }

    /** The template, if an agent may branch from it now; otherwise a {@link ToolError} saying why. */
    InstanceBackend.TemplateInfo require(String name) {
        var info = requireLineage(name);
        if (!info.built()) {
            throw new ToolError(ToolError.Code.WRONG_STATE, "template '" + name + "' is approved but not built. "
                    + "Ask the user to run: isx build " + name);
        }
        return info;
    }

    /**
     * The template an instance descends from, if an agent may still fork that instance: as
     * {@link #require}, except that the template need not be built -- a fork copies the
     * instance, not the template's image -- the same rule adoption applies.
     */
    InstanceBackend.TemplateInfo requireLineage(String name) {
        var listed = config.get().templates();
        if (!listed.contains(name)) {
            throw new ToolError(ToolError.Code.NOT_APPROVED, "template '" + name + "' is not approved for agents"
                    + (listed.isEmpty() ? " (none are)." : "; approved: " + String.join(", ", listed) + ".")
                    + " " + HOW_TO_APPROVE);
        }
        var info = backend.template(name)
                .orElseThrow(() -> new ToolError(ToolError.Code.NOT_APPROVED, "template '" + name + "' is approved but has no "
                        + "definition. Ask the user to check mcp.templates in their config."));
        if (info.projectLocal()) {
            throw new ToolError(ToolError.Code.NOT_APPROVED, "template '" + name + "' was built from a project-local definition "
                    + "(.incus-spawn/ in a repository), which agents may never use.");
        }
        return info;
    }
}
