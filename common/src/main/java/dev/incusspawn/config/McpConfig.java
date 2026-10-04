package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code mcp:} section of {@code config.yaml}: what {@code isx mcp} lets a local agent do.
 * Only a person edits it -- the MCP server reads it on every call and has no way to write it.
 *
 * <pre>
 * mcp:
 *   templates: [tpl-java, tpl-dev]   # the only templates an agent may branch from
 *   max-instances: 8                 # per host user, across sessions, counting creates in flight
 *   max-concurrent-tasks: 8          # background commands and delegated agents, per host user, across sessions
 *   delegate-max-turns: 200          # passed to the inner agent as --max-turns; a delegate's
 *                                    # max_turns may narrow it, never exceed it
 *   delegate-permission-mode: bypassPermissions   # passed to every delegate as --permission-mode
 *   delegate-permission-modes:       # per-template overrides of it
 *     tpl-review: plan
 *   orphan-grace-hours: 24           # how long an instance outlives the session that held it
 *   summary-model: haiku             # the model that answers a tool's `ask`, inside the instance
 * </pre>
 *
 * Unset limits are never written back, so a saved config does not pin today's defaults.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpConfig {

    public static final int DEFAULT_MAX_INSTANCES = 8;
    public static final int DEFAULT_MAX_CONCURRENT_TASKS = 8;
    /** What every isx template's managed settings already default to; passed explicitly. */
    public static final String DEFAULT_PERMISSION_MODE = "bypassPermissions";
    public static final int DEFAULT_ORPHAN_GRACE_HOURS = 24;
    /**
     * A Claude Code model alias, so a template that maps its models elsewhere (Vertex, Bedrock)
     * through {@code ANTHROPIC_DEFAULT_HAIKU_MODEL} is followed.
     */
    public static final String DEFAULT_SUMMARY_MODEL = "haiku";
    /**
     * Permission modes and model names are passed to {@code claude}; nothing else is allowed.
     * A model starts with a letter or digit, so it cannot be read as an option.
     */
    private static final Pattern MODE = Pattern.compile("[A-Za-z]{1,40}");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/\\[\\]-]{0,119}");

    @JsonProperty("templates")
    private List<String> templates;
    @JsonProperty("max-instances")
    private Integer maxInstances;
    @JsonProperty("max-concurrent-tasks")
    private Integer maxConcurrentTasks;
    @JsonProperty("delegate-max-turns")
    private Integer delegateMaxTurns;
    @JsonProperty("delegate-permission-mode")
    private String delegatePermissionMode;
    @JsonProperty("delegate-permission-modes")
    private Map<String, String> delegatePermissionModes;
    @JsonProperty("orphan-grace-hours")
    private Integer orphanGraceHours;
    @JsonProperty("summary-model")
    private String summaryModel;

    public List<String> templates() {
        return templates == null ? List.of() : List.copyOf(templates);
    }

    public int maxInstances() {
        return maxInstances == null ? DEFAULT_MAX_INSTANCES : Math.max(0, maxInstances);
    }

    public int maxConcurrentTasks() {
        return maxConcurrentTasks == null ? DEFAULT_MAX_CONCURRENT_TASKS : Math.max(0, maxConcurrentTasks);
    }

    /**
     * Null when unset: the inner agent's own default applies. Also the ceiling of a delegate's
     * own {@code max_turns}: an agent may spend less of the user's budget, never more.
     */
    public Integer delegateMaxTurns() {
        return delegateMaxTurns;
    }

    /** The turn budget of a delegate that chose {@code chosen} (null for none): within the ceiling. */
    public Integer delegateMaxTurns(Integer chosen) {
        if (chosen == null) return delegateMaxTurns;
        return delegateMaxTurns == null ? chosen : Integer.valueOf(Math.min(chosen, delegateMaxTurns));
    }

    /**
     * The {@code --permission-mode} a delegate in an instance of {@code template} runs with:
     * the template's override, else the section's, else {@link #DEFAULT_PERMISSION_MODE}. Always
     * explicit, so a headless agent never meets a permission prompt nobody can answer. A value
     * that is not a plain word is an error for the person who wrote it, never passed on.
     */
    public String delegatePermissionMode(String template) {
        var mode = delegatePermissionModes == null ? null : delegatePermissionModes.get(template);
        if (mode == null) mode = delegatePermissionMode;
        if (mode == null) return DEFAULT_PERMISSION_MODE;
        if (!MODE.matcher(mode).matches()) {
            throw new IllegalArgumentException("mcp.delegate-permission-mode '" + mode + "' is not a permission mode");
        }
        return mode;
    }

    public int orphanGraceHours() {
        return orphanGraceHours == null ? DEFAULT_ORPHAN_GRACE_HOURS : Math.max(0, orphanGraceHours);
    }

    public String summaryModel() {
        if (summaryModel == null) return DEFAULT_SUMMARY_MODEL;
        if (!isModelName(summaryModel)) {
            throw new IllegalArgumentException("mcp.summary-model '" + summaryModel + "' is not a model name");
        }
        return summaryModel;
    }

    /**
     * Whether {@code model} can be passed to {@code claude --model}: a model id or alias, never
     * anything a shell or an option parser would read as more.
     */
    public static boolean isModelName(String model) {
        return model != null && MODEL.matcher(model).matches();
    }

    public void setTemplates(List<String> templates) { this.templates = templates; }
    public void setMaxInstances(Integer maxInstances) { this.maxInstances = maxInstances; }
    public void setMaxConcurrentTasks(Integer n) { this.maxConcurrentTasks = n; }
    public void setDelegateMaxTurns(Integer n) { this.delegateMaxTurns = n; }
    public void setDelegatePermissionMode(String mode) { this.delegatePermissionMode = mode; }
    public void setDelegatePermissionModes(Map<String, String> modes) { this.delegatePermissionModes = modes; }
    public void setOrphanGraceHours(Integer hours) { this.orphanGraceHours = hours; }
    public void setSummaryModel(String model) { this.summaryModel = model; }
}
