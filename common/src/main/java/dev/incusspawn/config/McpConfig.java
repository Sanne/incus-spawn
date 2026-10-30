package dev.incusspawn.config;

import dev.incusspawn.Environment;
import io.hyperfoil.tools.jjq.mapper.JqAnySetter;
import io.hyperfoil.tools.jjq.mapper.JqConverter;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code ~/.config/incus-spawn/mcp.yaml}: what {@code isx mcp} lets a local agent do. Only a
 * person edits it -- the MCP server reads it on every call and has no way to write it.
 *
 * <pre>
 * templates: [tpl-java, tpl-dev]   # the only templates an agent may branch from
 * max-instances: 8                 # per host user, across sessions, counting creates in flight
 * max-concurrent-tasks: 8          # background commands and delegated agents, per host user, across sessions
 * delegate-max-turns: 200          # passed to the inner agent as --max-turns; a delegate's
 *                                  # max_turns may narrow it, never exceed it
 * delegate-permission-mode: bypassPermissions   # passed to every delegate as --permission-mode
 * delegate-permission-modes:       # per-template overrides of it
 *   tpl-review: plan
 * orphan-grace-hours: 24           # how long an instance outlives the session that held it
 * dormant-after-hours: 24          # past that, an orphan kept only for a task that shows no
 *                                  # activity this long is stopped, not destroyed
 * dormant-grace-hours: 168         # and destroyed this long after it was stopped
 * summary-model: haiku             # the model that answers a tool's `ask`, inside the instance
 * host-tools:                      # user-defined MCP tools that run a command on the host
 *   instance-inventory:
 *     command: ~/incus-spawn/scripts/instance-inventory.sh
 *     description: Read-only inventory of stopped isx instances
 *     timeout: 300                 # seconds; default 60, 0 = no limit
 *     min-interval: 10             # seconds between invocations; default 10, 0 = no limit
 * </pre>
 *
 * A missing or empty file approves nothing. One that cannot be read, does not parse, or holds
 * a key or value isx does not know is {@link Invalid}: nothing that depends on the policy may
 * run until it is fixed, so a mistyped restriction is never dropped in silence (#1331). An
 * {@code mcp:} section left in {@code config.yaml} is never read.
 *
 * Unset limits are never written back, so a saved file does not pin today's defaults.
 */
@JqInclude(JqInclude.Include.NON_NULL)
@JqMapped
public class McpConfig {

    public static final String FILE_NAME = "mcp.yaml";

    /** Why the policy file cannot be used; its message names the file and says what is wrong. */
    public static final class Invalid extends IllegalStateException {
        Invalid(String message) {
            super(message);
        }
    }

    public static final int DEFAULT_MAX_INSTANCES = 8;
    public static final int DEFAULT_MAX_CONCURRENT_TASKS = 8;
    /** What every isx template's managed settings already default to; passed explicitly. */
    public static final String DEFAULT_PERMISSION_MODE = "bypassPermissions";
    public static final int DEFAULT_ORPHAN_GRACE_HOURS = 24;
    public static final int DEFAULT_DORMANT_AFTER_HOURS = 24;
    public static final int DEFAULT_DORMANT_GRACE_HOURS = 168;
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

    @JqName("templates")
    private List<String> templates;
    @JqName("max-instances")
    @JqConverter(IntegerConverter.class)
    private Integer maxInstances;
    @JqName("max-concurrent-tasks")
    @JqConverter(IntegerConverter.class)
    private Integer maxConcurrentTasks;
    @JqName("delegate-max-turns")
    @JqConverter(IntegerConverter.class)
    private Integer delegateMaxTurns;
    @JqName("delegate-permission-mode")
    private String delegatePermissionMode;
    @JqName("delegate-permission-modes")
    private Map<String, String> delegatePermissionModes;
    @JqName("orphan-grace-hours")
    @JqConverter(IntegerConverter.class)
    private Integer orphanGraceHours;
    @JqName("dormant-after-hours")
    @JqConverter(IntegerConverter.class)
    private Integer dormantAfterHours;
    @JqName("dormant-grace-hours")
    @JqConverter(IntegerConverter.class)
    private Integer dormantGraceHours;
    @JqName("summary-model")
    private String summaryModel;

    @JqName("codex")
    private Codex codex;

    @JqName("host-tools")
    @JqInclude(JqInclude.Include.NON_NULL)
    private Object hostToolsRaw;

    // Keys isx does not know, only to refuse them: never written back.
    private final List<String> unknown = new ArrayList<>();

    @JqAnySetter
    public void setUnknown(String key, Object value) { unknown.add(key); }

    @JqInclude(JqInclude.Include.NON_NULL)
    @JqMapped
    public static class Codex {
        @JqName("sandbox-mode")
        private String sandboxMode;
        @JqName("sandbox-modes")
        private Map<String, String> sandboxModes;
        private final List<String> unknown = new ArrayList<>();

        public void setSandboxMode(String mode) { sandboxMode = mode; }
        public void setSandboxModes(Map<String, String> modes) { sandboxModes = modes; }

        @JqAnySetter
        public void setUnknown(String key, Object value) { unknown.add(key); }
    }

    /**
     * A user-defined host tool: a command the user wrote, exposed as an MCP tool an agent can
     * trigger.
     * The agent has no influence on the command string (v1 has no arguments).
     *
     * @param name the YAML key (hyphens allowed); the MCP tool name replaces hyphens with underscores
     * @param command run via {@code bash -c}; tilde expansion works
     * @param description shown to the model
     * @param timeout seconds; 0 = no limit
     * @param minInterval seconds between invocations; 0 = no limit.
     *                    A positive interval also forbids concurrent invocations.
     */
    public record HostTool(String name, String command, String description,
                           int timeout, int minInterval) {
        public static final int DEFAULT_TIMEOUT = 60;
        public static final int MAX_TIMEOUT = 600;
        public static final int DEFAULT_MIN_INTERVAL = 10;
        public static final int MAX_MIN_INTERVAL = 3600;

        public String toolName() {
            return name.replace('-', '_');
        }
    }

    private static final Pattern HOST_TOOL_KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Set<String> HOST_TOOL_KEYS = Set.of(
            "command", "description", "timeout", "min-interval");

    /**
     * The host tools from {@code host-tools:}, each checked on its own; a wrong entry is dropped
     * with a warning naming it, so one typo never widens what a tool does.
     * Keyed by the YAML key (hyphens), not the MCP tool name (underscores).
     */
    public Map<String, HostTool> hostTools(Consumer<String> warn) {
        if (hostToolsRaw == null) return Map.of();
        if (!(hostToolsRaw instanceof Map<?, ?> map)) {
            warn.accept("Ignoring host-tools: in mcp.yaml (expected a name per tool, each with command: and description:)");
            return Map.of();
        }
        var result = new LinkedHashMap<String, HostTool>();
        map.forEach((key, value) -> {
            var tool = hostTool(String.valueOf(key), value, warn);
            if (tool != null) {
                if (result.containsKey(tool.name())) {
                    warn.accept(ignoringHostTool(tool.name()) + ": it is written more than once");
                } else {
                    result.put(tool.name(), tool);
                }
            }
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static HostTool hostTool(String key, Object value, Consumer<String> warn) {
        var name = key.strip().toLowerCase(Locale.ROOT);
        if (!HOST_TOOL_KEY.matcher(name).matches()) {
            warn.accept(ignoringHostTool(key) + ": a host-tool name is 1-40 lowercase letters, digits and hyphens, "
                    + "starting with a letter");
            return null;
        }
        if (!(value instanceof Map<?, ?> fields)) {
            warn.accept(ignoringHostTool(key) + ": expected command: and description: under it");
            return null;
        }
        for (var field : fields.keySet()) {
            if (!HOST_TOOL_KEYS.contains(String.valueOf(field))) {
                warn.accept(ignoringHostTool(key) + ": unknown key '" + field + "'");
                return null;
            }
        }
        var command = fields.get("command");
        if (!(command instanceof String cmd) || cmd.isBlank()) {
            warn.accept(ignoringHostTool(key) + ": command: is required and must be a non-empty string");
            return null;
        }
        var description = fields.get("description");
        if (!(description instanceof String desc) || desc.isBlank()) {
            warn.accept(ignoringHostTool(key) + ": description: is required and must be a non-empty string");
            return null;
        }
        int timeout = HostTool.DEFAULT_TIMEOUT;
        if (fields.containsKey("timeout")) {
            var t = asPositiveInt(fields.get("timeout"));
            if (t == null || (t > HostTool.MAX_TIMEOUT && t != 0)) {
                warn.accept(ignoringHostTool(key) + ": timeout must be 0 (no limit) or 1-" + HostTool.MAX_TIMEOUT);
                return null;
            }
            timeout = t;
        }
        int minInterval = HostTool.DEFAULT_MIN_INTERVAL;
        if (fields.containsKey("min-interval")) {
            var mi = asPositiveInt(fields.get("min-interval"));
            if (mi == null || (mi > HostTool.MAX_MIN_INTERVAL && mi != 0)) {
                warn.accept(ignoringHostTool(key) + ": min-interval must be 0 (no limit) or 1-" + HostTool.MAX_MIN_INTERVAL);
                return null;
            }
            minInterval = mi;
        }
        return new HostTool(name, cmd.strip(), desc.strip(), timeout, minInterval);
    }

    private static Integer asPositiveInt(Object value) {
        if (value instanceof Number n) {
            var i = n.intValue();
            return i >= 0 ? i : null;
        }
        return null;
    }

    private static String ignoringHostTool(String name) {
        return "Ignoring host tool '" + name + "' in mcp.yaml";
    }

    public static Path file() {
        return Environment.configDir().resolve(FILE_NAME);
    }

    /**
     * The policy in {@link #file()}, read now: defaults (nothing approved) when there is none.
     * While config.yaml still has the {@code mcp:} section this file replaced and there is no
     * file yet, it is {@link Invalid} instead: the defaults would widen what the user wrote there
     * (a longer orphan grace, a narrower permission mode), and an {@code isx init} saving its
     * approvals would make that permanent. Nothing is read from the section.
     */
    public static McpConfig load() {
        var file = file();
        var config = read(file);
        if (config != null) return config;
        if (hasLegacySection()) {
            throw invalid(file + " does not exist, and the mcp: section of config.yaml it replaced is no longer "
                    + "read; move that section's contents to " + file + ", without the enclosing mcp: key");
        }
        return new McpConfig();
    }

    /**
     * Whether config.yaml still has the old section. One that does not parse cannot say, and
     * {@link SpawnConfig#load} would read it as empty, so it is {@link Invalid} too.
     */
    private static boolean hasLegacySection() {
        try {
            return SpawnConfig.loadStrict().hasLegacyMcpSection();
        } catch (IOException e) {
            throw invalid(file() + " does not exist, and " + YamlErrors.friendly(SpawnConfig.file().toString(), e)
                    + ", so whether it still has the mcp: section that " + FILE_NAME + " replaced cannot be told; "
                    + "fix config.yaml or create " + FILE_NAME);
        }
    }

    static McpConfig load(Path file) {
        var config = read(file);
        return config != null ? config : new McpConfig();
    }

    /** Null when there is no file. */
    private static McpConfig read(Path file) {
        String yaml;
        try {
            yaml = Files.readString(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw invalid(file + ": cannot be read (" + e.getMessage() + ")");
        }
        return parse(yaml, file.toString());
    }

    /** {@code yaml} as the contents of {@code name}, validated: what {@link #load} returns. */
    static McpConfig parse(String yaml, String name) {
        McpConfig config;
        try {
            config = Jjq.mcpConfig(yaml);
        } catch (IOException e) {
            throw invalid(YamlErrors.friendly(name, e));
        }
        if (config == null) return new McpConfig();
        try {
            config.validate();
        } catch (IllegalArgumentException e) {
            throw invalid(name + ": " + e.getMessage());
        }
        return config;
    }

    private static Invalid invalid(String why) {
        return new Invalid(why + "; isx mcp refuses everything that depends on it until it is fixed");
    }

    /** Every value checked on use, checked at once, so no call runs on part of a policy. */
    private void validate() {
        if (templates != null && templates.stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new IllegalArgumentException("templates has an empty entry");
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(unknown.contains("mcp")
                    ? "the settings go at the top level of mcp.yaml, without an enclosing mcp: key"
                    : "unknown key " + String.join(", ", unknown));
        }
        if (delegatePermissionMode != null) requirePermissionMode("delegate-permission-mode", delegatePermissionMode);
        // An empty per-template entry falls back to the file's own setting, as on use.
        if (delegatePermissionModes != null) {
            delegatePermissionModes.forEach((template, mode) -> {
                if (mode != null) requirePermissionMode("delegate-permission-modes." + template, mode);
            });
        }
        summaryModel();
        if (codex != null) {
            if (!codex.unknown.isEmpty()) {
                throw new IllegalArgumentException("unknown key codex." + String.join(", codex.", codex.unknown));
            }
            if (codex.sandboxMode != null) requireSandboxMode("codex.sandbox-mode", codex.sandboxMode);
            if (codex.sandboxModes != null) {
                codex.sandboxModes.forEach((template, mode) -> {
                    if (mode != null) requireSandboxMode("codex.sandbox-modes." + template, mode);
                });
            }
        }
    }

    /**
     * What to tell the user while {@code config} still has the {@code mcp:} section this file
     * replaced, or null when it has none.
     */
    public static String legacySectionNotice(SpawnConfig config) {
        return config.hasLegacyMcpSection()
                ? "the mcp: section of config.yaml is no longer read; move its contents to " + file()
                        + ", without the enclosing mcp: key"
                : null;
    }

    /**
     * Write {@link #file()}, owner-only (also when it was not before). Only {@code isx init} calls this;
     * nothing in {@code isx mcp} may ({@code McpNoWritePathTest}).
     */
    public void save() {
        var file = file();
        try {
            // Through a symlink, as a dotfiles setup has it: the rename replaces the target.
            var target = file;
            // readSymbolicLink, not toRealPath: a dangling link (dotfiles not checked out yet) stays one.
            while (Files.isSymbolicLink(target)) target = target.resolveSibling(Files.readSymbolicLink(target));
            Files.createDirectories(target.getParent());
            // Owner-only from the first byte, and swapped in whole: a session reading it meanwhile
            // sees the old policy or the new one, never an empty or half-written file.
            var temp = Files.createTempFile(target.getParent(), ".mcp-", ".yaml",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Jjq.writeYaml(temp, this);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save " + file + ": " + e.getMessage(), e);
        }
    }

    public void setCodex(Codex codex) { this.codex = codex; }

    /**
     * Codex's sandbox for {@code template}: its {@code sandbox-modes} entry, else the section's
     * {@code sandbox-mode}, else {@code danger-full-access}. A restrictive Claude permission mode
     * is never widened by a default: one set for this template in {@code delegate-permission-modes}
     * needs this template's own {@code sandbox-modes} entry, a section-wide one at least the
     * section-wide {@code sandbox-mode}.
     */
    public String codexSandboxMode(String template) {
        var mode = codex == null || codex.sandboxModes == null ? null : codex.sandboxModes.get(template);
        if (mode == null) {
            var restricted = !DEFAULT_PERMISSION_MODE.equals(delegatePermissionMode(template));
            if (restricted && delegatePermissionModes != null && delegatePermissionModes.get(template) != null) {
                throw new IllegalArgumentException("template '" + template + "' has its own restrictive Claude "
                        + "permission mode; set codex.sandbox-modes for it in mcp.yaml before using Codex");
            }
            if (codex != null) mode = codex.sandboxMode;
            if (mode == null) {
                if (restricted) {
                    throw new IllegalArgumentException("template '" + template + "' has a restrictive Claude permission "
                            + "policy; set codex.sandbox-mode or sandbox-modes in mcp.yaml before using Codex");
                }
                return "danger-full-access";
            }
        }
        return requireSandboxMode("codex sandbox mode", mode);
    }

    /**
     * What Pi runs under for {@code template}: always every tool, as Pi has no sandbox or
     * permission modes. So a template whose Claude permission policy is restrictive is refused
     * rather than given a worker that can write anything.
     */
    public String piPermissionMode(String template) {
        if (!DEFAULT_PERMISSION_MODE.equals(delegatePermissionMode(template))) {
            throw new IllegalArgumentException("template '" + template + "' has a restrictive Claude permission policy, "
                    + "and Pi has no sandbox or permission modes to honour it; choose another agent or template");
        }
        return PI_PERMISSION_MODE;
    }

    /** What a Pi worker reports as its permission mode: every tool, bounded by the instance. */
    public static final String PI_PERMISSION_MODE = "full-access";

    private static String requireSandboxMode(String key, String mode) {
        if (!List.of("read-only", "workspace-write", "danger-full-access").contains(mode)) {
            throw new IllegalArgumentException(key + " '" + mode
                    + "' is not one of read-only, workspace-write or danger-full-access");
        }
        return mode;
    }

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
        return requirePermissionMode("delegate-permission-mode", mode);
    }

    private static String requirePermissionMode(String key, String mode) {
        if (!MODE.matcher(mode).matches()) {
            throw new IllegalArgumentException(key + " '" + mode + "' is not a permission mode");
        }
        return mode;
    }

    public int orphanGraceHours() {
        return orphanGraceHours == null ? DEFAULT_ORPHAN_GRACE_HOURS : Math.max(0, orphanGraceHours);
    }

    /**
     * How long an orphan past its grace period, kept only because a delegated agent has not
     * finished, must show no activity before the sweep stops it (#1028).
     */
    public int dormantAfterHours() {
        return dormantAfterHours == null ? DEFAULT_DORMANT_AFTER_HOURS : Math.max(0, dormantAfterHours);
    }

    /** How long an instance the sweep stopped as dormant waits, stopped, before it is destroyed. */
    public int dormantGraceHours() {
        return dormantGraceHours == null ? DEFAULT_DORMANT_GRACE_HOURS : Math.max(0, dormantGraceHours);
    }

    public String summaryModel() {
        if (summaryModel == null) return DEFAULT_SUMMARY_MODEL;
        if (!isModelName(summaryModel)) {
            throw new IllegalArgumentException("summary-model '" + summaryModel + "' is not a model name");
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
    public void setDormantAfterHours(Integer hours) { this.dormantAfterHours = hours; }
    public void setDormantGraceHours(Integer hours) { this.dormantGraceHours = hours; }
    public void setSummaryModel(String model) { this.summaryModel = model; }
}
