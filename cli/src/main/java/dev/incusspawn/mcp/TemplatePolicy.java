package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.McpScope;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Which templates an agent may branch from. A template must be listed in {@code templates} in {@code mcp.yaml},
 * be defined by a trusted layer (never a project-local {@code .incus-spawn/} definition, nor an
 * image built from one), and be built. Unbuilt templates are refused, never built on demand.
 *
 * <p>Definitions come from trusted layers only (see {@code IncusInstanceBackend}), so a
 * repository's definitions cannot stand in for an approved template's.
 *
 * <p>A coordinator in an instance gets only what its template's {@code coordinator.control-over} also
 * lists ({@link McpScope}, #1812), and nothing when it lists none.
 *
 * <p>The config is read on every call, so a person narrowing the list affects a running session
 * at once. Nothing here, or anywhere in {@code isx mcp}, can widen it.
 */
final class TemplatePolicy {

    private final InstanceBackend backend;
    private final Supplier<McpConfig> config;
    private final Function<McpConfig, McpScope> scope;

    /**
     * For the host's own session only: {@code mcp.yaml}'s list alone. A coordinator's policy is
     * always built with its scope ({@code McpMain.scopeFor}); {@code McpSession} refuses an
     * instance session without one.
     */
    TemplatePolicy(InstanceBackend backend, Supplier<McpConfig> config) {
        this(backend, config, c -> McpScope.host(c.templates()));
    }

    /** {@code scope} narrows the config to what this session's caller may use. */
    TemplatePolicy(InstanceBackend backend, Supplier<McpConfig> config, Function<McpConfig, McpScope> scope) {
        this.backend = backend;
        this.config = config;
        this.scope = scope;
    }

    /** What this session may use now, from the config as it is now. */
    McpScope scope() {
        return scope.apply(config.get());
    }

    /** The approved templates that are defined and trusted, built or not. */
    List<InstanceBackend.TemplateInfo> approved() {
        var listed = scope().templates();
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
     * Refuses a template outside this session's {@link McpScope}, from the config and definitions
     * alone, saying which list lacks it.
     */
    void requireListed(String name) {
        var why = scope().explain(name);
        if (why != null) throw new ToolError(ToolError.Code.NOT_APPROVED, why);
    }

    /**
     * The template an instance descends from, if an agent may still fork that instance: as
     * {@link #require}, except that the template need not be built -- a fork copies the
     * instance, not the template's image -- the same rule adoption applies.
     */
    InstanceBackend.TemplateInfo requireLineage(String name) {
        requireListed(name);
        var info = backend.template(name)
                .orElseThrow(() -> new ToolError(ToolError.Code.NOT_APPROVED, "template '" + name + "' is approved but has no "
                        + "definition. Ask the user to check templates in their mcp.yaml."));
        if (info.projectLocal()) {
            throw new ToolError(ToolError.Code.NOT_APPROVED, "template '" + name + "' was built from a project-local definition "
                    + "(.incus-spawn/ in a repository), which agents may never use.");
        }
        return info;
    }
}
