package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code mcp:} section of {@code config.yaml}: what {@code isx mcp} lets a local agent do.
 * Only a person edits it -- the MCP server reads it on every call and has no way to write it.
 *
 * <pre>
 * mcp:
 *   templates: [tpl-java, tpl-dev]   # the only templates an agent may branch from
 *   max-instances: 3                 # per session, counting creates in flight
 *   max-concurrent-tasks: 2          # background commands and delegated agents, per session
 *   delegate-max-turns: 200          # passed to the inner agent as --max-turns
 * </pre>
 *
 * Unset limits are never written back, so a saved config does not pin today's defaults.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpConfig {

    public static final int DEFAULT_MAX_INSTANCES = 3;
    public static final int DEFAULT_MAX_CONCURRENT_TASKS = 2;

    @JsonProperty("templates")
    private List<String> templates;
    @JsonProperty("max-instances")
    private Integer maxInstances;
    @JsonProperty("max-concurrent-tasks")
    private Integer maxConcurrentTasks;
    @JsonProperty("delegate-max-turns")
    private Integer delegateMaxTurns;

    public List<String> templates() {
        return templates == null ? List.of() : List.copyOf(templates);
    }

    public int maxInstances() {
        return maxInstances == null ? DEFAULT_MAX_INSTANCES : Math.max(0, maxInstances);
    }

    public int maxConcurrentTasks() {
        return maxConcurrentTasks == null ? DEFAULT_MAX_CONCURRENT_TASKS : Math.max(0, maxConcurrentTasks);
    }

    /** Null when unset: the inner agent's own default applies. */
    public Integer delegateMaxTurns() {
        return delegateMaxTurns;
    }

    public void setTemplates(List<String> templates) { this.templates = templates; }
    public void setMaxInstances(Integer maxInstances) { this.maxInstances = maxInstances; }
    public void setMaxConcurrentTasks(Integer n) { this.maxConcurrentTasks = n; }
    public void setDelegateMaxTurns(Integer n) { this.delegateMaxTurns = n; }
}
