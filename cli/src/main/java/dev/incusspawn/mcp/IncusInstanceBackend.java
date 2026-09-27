package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.tui.InstanceLockManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** {@link InstanceBackend} over the real Incus daemon, through the same code paths as the CLI. */
final class IncusInstanceBackend implements InstanceBackend {

    static final String AGENT_USER = "agentuser";
    static final String AGENT_HOME = "/home/agentuser";

    private final IncusClient incus;
    private final InstanceLockManager locks;

    IncusInstanceBackend(IncusClient incus, InstanceLockManager locks) {
        this.incus = incus;
        this.locks = locks;
    }

    @Override
    public List<TemplateInfo> templates() {
        var defs = ImageDef.loadAll();
        var instances = instanceConfigs();
        var version = BuildInfo.instance().version();
        var result = new ArrayList<TemplateInfo>();
        for (var def : defs.values()) {
            var config = instances.get(def.getName());
            var built = config != null && Metadata.TYPE_BASE.equals(config.get(Metadata.TYPE));
            var stale = built && !version.equals(config.getOrDefault(Metadata.BUILD_VERSION, ""));
            // The definition may be trusted now while the image was built from a project-local one
            var projectLocal = def.getProjectRoot() != null
                    || (config != null && builtFromProjectLocal(config));
            result.add(new TemplateInfo(def.getName(), def.getDescription(), built, stale,
                    chainTools(def, defs), projectLocal));
        }
        return result;
    }

    @Override
    public CreatedInstance create(String template, String name, Map<String, String> stamps) {
        // Exactly `isx branch <name> --from <template>`: the template's network mode, accounts,
        // KVM and resource defaults; no GUI, no inbox. The agent chooses none of it.
        var request = BranchFlow.Request.defaults(template, name).withExtraConfig(stamps);
        BranchFlow.Preflight preflight;
        try {
            preflight = BranchFlow.preflight(incus, request);
        } catch (BranchFlow.BranchException e) {
            throw new ToolError("cannot create an instance from " + template + ": " + e.getMessage());
        }
        try {
            BranchFlow.create(incus, preflight);
        } catch (RuntimeException e) {
            // A half-made branch is useless to the agent and invisible to the user; take it away.
            try {
                if (incus.exists(name)) destroy(name);
            } catch (RuntimeException cleanup) {
                System.err.println("isx mcp: could not remove the failed instance " + name
                        + ": " + cleanup.getMessage());
            }
            throw new ToolError("creating " + name + " from " + template + " failed: " + e.getMessage());
        }
        var config = metadata(name);
        var workdir = config == null ? "" : config.getOrDefault(Metadata.WORKDIR, "");
        return new CreatedInstance(name,
                config == null ? null : config.get(Metadata.STATIC_IP),
                workdir.isEmpty() ? AGENT_HOME : workdir);
    }

    @Override
    public boolean destroy(String name) {
        if (!incus.exists(name)) return false;
        var lock = locks.tryAcquire(name, Metadata.OP_DELETING);
        if (lock.isEmpty()) throw new ToolError("'" + name + "' is locked by another isx process; try again.");
        try (var held = lock.get()) {
            InstanceDestroyer.deleteHeld(incus, name);
        }
        InstanceDestroyer.refreshProxy();
        return true;
    }

    @Override
    public Map<String, String> metadata(String name) {
        var instance = incus.instanceMetadata(name);
        if (instance.isMissingNode() || instance.path("name").isMissingNode()) return null;
        return configOf(instance);
    }

    @Override
    public void stamp(String name, String key, String value) {
        incus.configSet(name, key, value);
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

    private static boolean builtFromProjectLocal(Map<String, String> config) {
        var source = BuildSource.fromJson(config.get(Metadata.BUILD_SOURCE));
        return source != null && source.getDefinitions().values().stream()
                .anyMatch(def -> def.getProjectRoot() != null);
    }

    /** Tool names across the template's parent chain. */
    private static List<String> chainTools(ImageDef def, Map<String, ImageDef> defs) {
        var tools = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        var current = def;
        while (current != null && seen.add(current.getName())) {
            current.getTools().forEach(ref -> tools.add(ref.getName()));
            current = current.getParent() == null ? null : defs.get(current.getParent());
        }
        return List.copyOf(tools);
    }
}
