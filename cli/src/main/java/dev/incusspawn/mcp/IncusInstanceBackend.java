package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.command.InstancePrep;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.ProxyActivity;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.tui.InstanceLockManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
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
    /** agentuser's uid (and gid) in every isx base image, as InstanceLifecycle's file pushes assume. */
    static final int AGENT_UID = 1000;

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
        return new TemplateInfo(def.getName(), def.getDescription(), built, stale, tools, delegateModel(def, defs),
                source != null && source.usedProjectLocal(), defs);
    }

    /**
     * The {@code model} the {@code claude} tool is configured with: the nearest layer listing
     * the tool decides alone, as the build reconfigures it with that layer's parameters only;
     * null when it sets none. What the definition says: a template built before a change to it
     * is listed as {@code stale}.
     */
    static String delegateModel(ImageDef def, Map<String, ImageDef> defs) {
        return ImageDef.chain(def, defs).reversed().stream()
                .flatMap(d -> d.getTools().stream())
                .filter(ref -> "claude".equals(ref.getName()))
                .findFirst().map(ref -> ref.getParams().get("model")).orElse(null);
    }

    @Override
    public CreatedInstance create(TemplateInfo info, String name, Map<String, String> stamps) {
        return branch(info, info.name(), name, stamps);
    }

    @Override
    public CreatedInstance fork(TemplateInfo lineage, String source, String name, Map<String, String> stamps) {
        // BranchFlow resolves a branch of a branch already: the leaf template from PROFILE, the
        // source's own pins on top of the template's.
        return branch(lineage, source, name, stamps);
    }

    private CreatedInstance branch(TemplateInfo info, String template, String name, Map<String, String> stamps) {
        // Exactly `isx branch <name> --from <template>`: the template's network mode, accounts,
        // KVM and resource defaults; no GUI, no inbox. The agent chooses none of it.
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
        return new CreatedInstance(name, ip.isEmpty() ? null : ip, workdir(runtime.workdir()), preflight.accounts());
    }

    @Override
    public String effectiveAccount(String namespace, String pinned) {
        try {
            return AccountResolver.effectiveAccount(SpawnConfig.load(), namespace, pinned);
        } catch (AccountResolver.UnknownAccountException e) {
            // A pin to an account that is gone or incomplete: the check through the proxy fails
            // closed on it, so the pin is all the cache key needs.
            return pinned == null ? "" : pinned;
        }
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
    public boolean destroyIfHeldBy(String name, String session, boolean onlyIfStopped) {
        var lock = locks.tryAcquire(name, Metadata.OP_DELETING);
        if (lock.isEmpty()) throw new ToolError("'" + name + "' is locked by another isx process.");
        try (var held = lock.get()) {
            return InstanceDestroyer.deleteHeldIf(incus, name,
                    instance -> session.equals(instance.path("config").path(Metadata.MCP_SESSION).asText(null))
                            // Started since the caller saw it stopped: someone may be in it now.
                            && (!onlyIfStopped || "Stopped".equals(instance.path("status").asText())));
        } catch (IncusException e) {
            throw new ToolError("cannot remove '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public void stop(String name) {
        var lock = locks.tryAcquire(name, Metadata.OP_STOPPING);
        if (lock.isEmpty()) throw new ToolError("'" + name + "' is locked by another isx process; try again.");
        try (var held = lock.get()) {
            incus.setPendingOperation(name, Metadata.OP_STOPPING);
            try {
                incus.stop(name);
            } finally {
                incus.clearPendingOperation(name);
            }
        } catch (IncusException e) {
            throw new ToolError("could not stop '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public void start(String name) {
        var lock = locks.tryAcquire(name, "starting");
        if (lock.isEmpty()) throw new ToolError("'" + name + "' is locked by another isx process; try again.");
        try (var held = lock.get()) {
            // Stdout is the protocol channel, but StdioGuard has sent System.out to stderr.
            InstancePrep.prepare(incus, name, System.err::println);
        } catch (InstancePrep.Refused | IncusException e) {
            throw new ToolError("could not start '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public void refreshProxy() {
        InstanceDestroyer.refreshProxy();
    }

    /** Where the proxy's health endpoint listens: on Linux a bridge read, so kept until it fails. */
    private volatile String proxyAddress;

    @Override
    public ProxyActivity proxyActivity() {
        try {
            var address = proxyAddress;
            if (address == null) proxyAddress = address = ProxyHealthCheck.healthAddress(incus);
            return ProxyActivity.fetch(address);
        } catch (RuntimeException e) {
            proxyAddress = null;
            throw new ToolError(e.getMessage() + "; ask the user to check it with: isx proxy status");
        }
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

    @Override
    public int probe(String name, String script, OutputStream stdout, Duration limit) {
        return incus.execProbe(name, AGENT_UID, AGENT_HOME, script, stdout, limit);
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
        var status = instance.path("status").asText("");
        if (!status.isEmpty()) config.put(STATUS, status);
        return config;
    }
}
