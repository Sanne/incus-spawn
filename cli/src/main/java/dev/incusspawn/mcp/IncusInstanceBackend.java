package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.tui.InstanceLockManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link InstanceBackend} over the real Incus daemon, through the same code paths as the CLI.
 * Definitions always come from {@link ImageDef#loadTrusted()}: {@code isx mcp} runs in whatever
 * repository the agent was started in, and its {@code .incus-spawn/} must not reach an agent's
 * instance -- not even by overriding a parent of an approved template.
 */
final class IncusInstanceBackend implements InstanceBackend {

    static final String AGENT_USER = "agentuser";
    static final String AGENT_HOME = "/home/agentuser";

    private final IncusClient incus;
    private final InstanceLockManager locks;

    IncusInstanceBackend(IncusClient incus, InstanceLockManager locks) {
        this.incus = incus;
        this.locks = locks;
    }

    /** Where commands run by default: the template's workdir, else agentuser's home. */
    static String workdir(Map<String, String> metadata) {
        return workdir(metadata == null ? "" : metadata.getOrDefault(Metadata.WORKDIR, ""));
    }

    private static String workdir(String workdir) {
        return workdir.isEmpty() ? AGENT_HOME : workdir;
    }

    @Override
    public List<TemplateInfo> templates() {
        var defs = ImageDef.loadTrusted();
        var instances = instanceConfigs();
        return defs.values().stream().map(def -> describe(def, defs, instances.get(def.getName()))).toList();
    }

    @Override
    public Optional<TemplateInfo> template(String name) {
        var defs = ImageDef.loadTrusted();
        var def = defs.get(name);
        return def == null ? Optional.empty() : Optional.of(describe(def, defs, metadata(name)));
    }

    private static TemplateInfo describe(ImageDef def, Map<String, ImageDef> defs, Map<String, String> config) {
        var built = config != null && Metadata.TYPE_BASE.equals(config.get(Metadata.TYPE));
        var stale = built && !BuildInfo.instance().version().equals(config.getOrDefault(Metadata.BUILD_VERSION, ""));
        var source = config == null ? null : BuildSource.fromJson(config.get(Metadata.BUILD_SOURCE));
        var tools = ImageDef.chain(def, defs).stream()
                .flatMap(d -> d.getTools().stream().map(ref -> ref.getName()))
                .distinct().toList();
        return new TemplateInfo(def.getName(), def.getDescription(), built, stale, tools,
                source != null && source.usedProjectLocal(), defs);
    }

    @Override
    public CreatedInstance create(TemplateInfo info, String name, Map<String, String> stamps) {
        // Exactly `isx branch <name> --from <template>`: the template's network mode, accounts,
        // KVM and resource defaults; no GUI, no inbox. The agent chooses none of it.
        var template = info.name();
        var request = BranchFlow.Request.defaults(template, name).withExtraConfig(stamps);
        BranchFlow.Preflight preflight;
        try {
            // The trusted definitions the template was just checked against, not a second load.
            preflight = BranchFlow.preflight(incus, request, info.definitions());
        } catch (BranchFlow.BranchException e) {
            throw new ToolError("cannot create an instance from " + template + ": " + e.getMessage());
        }
        InstanceLifecycle.RuntimeConfig runtime;
        try {
            runtime = BranchFlow.create(incus, preflight);
        } catch (RuntimeException e) {
            // A half-made branch is useless to the agent and invisible to the user; take it away.
            try {
                if (incus.exists(name)) {
                    destroy(name);
                    refreshProxy();
                }
            } catch (RuntimeException cleanup) {
                System.err.println("isx mcp: could not remove the failed instance " + name
                        + ": " + cleanup.getMessage());
            }
            throw new ToolError("creating " + name + " from " + template + " failed: " + e.getMessage());
        }
        // The request starts it, so the runtime config read before the start is always there.
        var ip = runtime.staticIp();
        return new CreatedInstance(name, ip.isEmpty() ? null : ip, workdir(runtime.workdir()));
    }

    @Override
    public void destroy(String name) {
        var lock = locks.tryAcquire(name, Metadata.OP_DELETING);
        if (lock.isEmpty()) throw new ToolError("'" + name + "' is locked by another isx process; try again.");
        try (var held = lock.get()) {
            InstanceDestroyer.deleteHeld(incus, name);
        }
    }

    @Override
    public void refreshProxy() {
        InstanceDestroyer.refreshProxy();
    }

    @Override
    public Map<String, String> metadata(String name) {
        // Only a 404 means gone: a refused or failed read would otherwise drop a live instance.
        JsonNode instance;
        try {
            instance = incus.instanceMetadataOrThrow(name);
        } catch (IncusException e) {
            throw new ToolError("cannot read '" + name + "' from Incus right now (" + e.getMessage()
                    + "); nothing was changed, try again.");
        }
        return instance == null ? null : configOf(instance);
    }

    @Override
    public void stamp(String name, Map<String, String> config) {
        incus.configUpdate(name, new java.util.HashMap<String, Object>(config));
    }

    @Override
    public Map<String, Map<String, String>> mcpInstances() {
        var result = new LinkedHashMap<String, Map<String, String>>();
        instanceConfigs().forEach((name, config) -> {
            if (config.containsKey(Metadata.MCP_SESSION)) result.put(name, config);
        });
        return result;
    }

    @Override
    public int exec(String name, String script, InputStream stdin, OutputStream stdout, OutputStream stderr) {
        return incus.execScriptAsUser(name, AGENT_USER, script, stdin, stdout, stderr);
    }

    /** Every instance's {@code user.incus-spawn.*} config, from one listing. */
    private Map<String, Map<String, String>> instanceConfigs() {
        var result = new LinkedHashMap<String, Map<String, String>>();
        JsonNode list;
        try {
            list = JsonRpc.JSON.readTree(incus.listJsonConfig());
        } catch (Exception e) {
            throw new ToolError("cannot list Incus instances: " + e.getMessage());
        }
        for (var instance : list) {
            result.put(instance.path("name").asText(), configOf(instance));
        }
        return result;
    }

    private static Map<String, String> configOf(JsonNode instance) {
        var config = new LinkedHashMap<String, String>();
        instance.path("config").properties().forEach(e -> {
            if (e.getKey().startsWith(Metadata.PREFIX)) config.put(e.getKey(), e.getValue().asText());
        });
        return config;
    }
}
