package dev.incusspawn.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves tool actions for instances, including default actions and named action references.
 * Shared logic between Tui and RunCommand (CLI).
 */
public class ActionResolver {

    private final IncusClient incus;
    private final ToolDefLoader toolDefLoader;
    private final List<ToolSetup> cdiTools;
    private final Map<String, ImageDef> imageDefs;

    public ActionResolver(IncusClient incus, ToolDefLoader toolDefLoader,
                          List<ToolSetup> cdiTools, Map<String, ImageDef> imageDefs) {
        this.incus = incus;
        this.toolDefLoader = toolDefLoader;
        this.cdiTools = cdiTools == null ? List.of() : cdiTools;
        this.imageDefs = imageDefs;
    }

    /**
     * Resolve all actions available for a given instance.
     */
    public List<ToolAction> resolveActionsForInstance(String instanceName, String parentTemplate,
                                                       Set<String> installedTools,
                                                       List<ActionContext.RepoInfo> repos) {
        var actions = new ArrayList<ToolAction>();
        var handledTools = new java.util.HashSet<String>();

        // YAML-declared actions
        for (var toolName : installedTools) {
            var setup = toolDefLoader.find(toolName);
            if (setup instanceof YamlToolSetup yts) {
                var toolDef = yts.toolDef();
                if (!toolDef.getActions().isEmpty()) {
                    handledTools.add(toolName);
                }
                for (var entry : toolDef.getActions()) {
                    if (YamlToolAction.EXPAND_REPOS.equals(entry.getExpand())) {
                        for (var repo : repos) {
                            actions.add(new YamlToolAction(toolName, entry, repo));
                        }
                    } else {
                        actions.add(new YamlToolAction(toolName, entry));
                    }
                }
            }
        }

        // CDI tool actions (for tools not already handled by YAML)
        for (var cdiTool : cdiTools) {
            if (installedTools.contains(cdiTool.name()) && !handledTools.contains(cdiTool.name())) {
                for (var entry : cdiTool.actions()) {
                    if (YamlToolAction.EXPAND_REPOS.equals(entry.getExpand())) {
                        for (var repo : repos) {
                            actions.add(new YamlToolAction(cdiTool.name(), entry, repo));
                        }
                    } else {
                        actions.add(new YamlToolAction(cdiTool.name(), entry));
                    }
                }
            }
        }

        return actions;
    }

    /**
     * Find the default action for an instance based on its template's default-action field.
     *
     * @param instance the instance as {@link #readInstance} returned it, whose stamp stands in
     *                 for a template YAML that is gone
     */
    public Optional<ToolAction> findDefaultAction(JsonNode instance, String parentTemplate,
                                                   Set<String> installedTools,
                                                   List<ActionContext.RepoInfo> repos) {
        return findDefaultAction(parentTemplate, () -> stampedDefaultAction(instance), () -> installedTools, repos);
    }

    private static String stampedDefaultAction(JsonNode instance) {
        return instance.path("config").path(Metadata.DEFAULT_ACTION).asText("");
    }

    /**
     * The shell command a new branch opens with: its template's default action, or null for a
     * plain shell. The one rule for {@code isx branch} and the TUI's branch dialog (#868), from
     * the source as {@code BranchFlow.preflight} read it, so it costs no request.
     *
     * <p>The reference comes from the current YAML (no rebuild needed to change it), but the tools
     * it is matched against are those the source was built with ({@link #collectInstalledTools}):
     * a tool added to the YAML without a rebuild yields a plain shell, not a binary that is not
     * installed.
     *
     * @param template the leaf template the source was built from ({@code Preflight.template()})
     */
    public String defaultCommandForBranch(String template, JsonNode sourceInstance) {
        return findDefaultAction(template, () -> stampedDefaultAction(sourceInstance),
                        () -> collectInstalledTools(sourceInstance, template), collectRepos(template))
                .flatMap(a -> a.shellCommand(null)).orElse(null);
    }

    /**
     * @param snapshot the {@link Metadata#DEFAULT_ACTION} stamp, read only when the YAML is gone
     * @param installedTools collected only when there is a reference to match
     */
    private Optional<ToolAction> findDefaultAction(String parentTemplate, Supplier<String> snapshot,
                                                   Supplier<Set<String>> installedTools,
                                                   List<ActionContext.RepoInfo> repos) {
        var ref = resolveDefaultActionRef(parentTemplate, snapshot);
        if (ref == null) return Optional.empty();
        return findActionByRef(ref, resolveActionsForInstance(null, parentTemplate, installedTools.get(), repos));
    }

    /**
     * Find a specific action by reference (e.g., "tool-name" or "tool-name:action-id").
     */
    public Optional<ToolAction> findActionByRef(String ref, List<ToolAction> actions) {
        var parsed = parseActionRef(ref);
        var matching = actions.stream()
                .filter(a -> parsed.toolName().equals(a.toolName()))
                .toList();

        if (matching.isEmpty()) {
            return Optional.empty();
        }

        return resolveActionByRef(parsed, matching);
    }

    /**
     * Collect installed tools for an instance. For instances with BUILD_SOURCE metadata
     * (branched from a built template), uses the build-time tools list so that action
     * resolution reflects what was actually installed — not what the current YAML says.
     * Falls back to the YAML chain for templates or when BUILD_SOURCE is unavailable; a
     * template whose YAML is gone still has its snapshot, so it uses that.
     *
     * @param instance the instance as {@link #readInstance} returned it
     */
    public Set<String> collectInstalledTools(JsonNode instance, String parentTemplate) {
        var config = instance.path("config");
        var buildSourceJson = config.path(Metadata.BUILD_SOURCE).asText("");
        if (!buildSourceJson.isBlank()) {
            var type = config.path(Metadata.TYPE).asText("");
            if (!Metadata.TYPE_BASE.equals(type) || getInheritanceChain(parentTemplate).isEmpty()) {
                var bs = dev.incusspawn.config.BuildSource.fromJson(buildSourceJson);
                if (bs != null) {
                    return extractBuildTimeTools(bs);
                }
            }
        }
        return collectInstalledToolsFromYaml(parentTemplate);
    }

    /**
     * Collect all installed tools by walking the YAML template inheritance chain.
     * Use for templates (whose YAML IS authoritative) or as a fallback.
     */
    public Set<String> collectInstalledToolsFromYaml(String parentTemplate) {
        var tools = new LinkedHashSet<String>();
        if (parentTemplate == null || parentTemplate.isEmpty() || "-".equals(parentTemplate)) {
            return tools;
        }

        var chain = getInheritanceChain(parentTemplate);
        for (var def : chain) {
            for (var toolRef : def.getTools()) {
                tools.add(toolRef.getName());
            }
        }

        // Add transitive deps
        var allDeps = new LinkedHashSet<String>();
        for (var toolName : new ArrayList<>(tools)) {
            collectTransitiveDeps(toolName, allDeps, new java.util.HashSet<>());
        }
        tools.addAll(allDeps);
        removeFeatureGated(tools);
        return tools;
    }

    private Set<String> extractBuildTimeTools(dev.incusspawn.config.BuildSource bs) {
        var tools = new LinkedHashSet<String>();
        for (var def : bs.getDefinitions().values()) {
            for (var toolRef : def.getTools()) {
                tools.add(toolRef.getName());
            }
        }
        for (var entry : bs.getTools().entrySet()) {
            var toolDef = entry.getValue();
            if (toolDef.getRequires() != null) {
                for (var dep : toolDef.getRequires()) {
                    tools.add(dep.getName());
                }
            }
        }
        removeFeatureGated(tools);
        return tools;
    }

    private void removeFeatureGated(Set<String> toolNames) {
        var config = SpawnConfig.load();
        toolNames.removeIf(name -> {
            var tool = toolDefLoader.find(name);
            if (tool != null && tool.feature() != null) return !config.isFeatureEnabled(tool.feature());
            for (var t : cdiTools) {
                if (t.name().equals(name) && t.feature() != null) return !config.isFeatureEnabled(t.feature());
            }
            return false;
        });
    }

    /**
     * Collect repository information from the template inheritance chain.
     */
    public List<ActionContext.RepoInfo> collectRepos(String parentTemplate) {
        var repos = new ArrayList<ActionContext.RepoInfo>();
        if (parentTemplate == null || parentTemplate.isEmpty() || "-".equals(parentTemplate)) {
            return repos;
        }

        var chain = getInheritanceChain(parentTemplate);
        for (var def : chain) {
            for (var repo : def.getRepos()) {
                var path = repo.getPath();
                if (path == null) continue;
                var repoPath = path.startsWith("~/")
                        ? "/home/agentuser" + path.substring(1)
                        : path;
                var name = repoPath.substring(repoPath.lastIndexOf('/') + 1);
                repos.add(new ActionContext.RepoInfo(name, repoPath, repo.getUrl()));
            }
        }
        return repos;
    }

    /**
     * Build an ActionContext for executing an action on an instance.
     */
    public ActionContext buildActionContext(String instanceName, String parentTemplate) {
        // One instance read for status, type and every config key (configGet is a full instance
        // GET per key), plus /state only where a guest can hold an address (#979).
        return buildActionContext(instanceName, readInstance(instanceName), parentTemplate);
    }

    /** {@link #buildActionContext(String, String)} from an instance its caller already read. */
    public ActionContext buildActionContext(String instanceName, JsonNode instance, String parentTemplate) {
        var config = instance.path("config");
        var status = instance.path("status").asText("");
        var ipv4 = "";
        // A frozen guest is paused, not stopped, and still holds its address.
        if ("Running".equalsIgnoreCase(status) || "Frozen".equalsIgnoreCase(status)) {
            var extracted = incus.getContainerIpv4(instanceName);
            if (extracted != null) {
                ipv4 = extracted;
            }
        }
        if (ipv4.isEmpty()) {
            ipv4 = config.path(Metadata.STATIC_IP).asText("");
        }
        var networkMode = config.path(Metadata.NETWORK_MODE).asText("");
        var installedTools = collectInstalledTools(instance, parentTemplate);
        var repos = collectRepos(parentTemplate);

        return new ActionContext(
                instanceName,
                IncusClient.machineType(instance),
                installedTools,
                repos,
                new ActionContext.InstanceState(ipv4, status, parentTemplate, networkMode)
        );
    }

    /**
     * The shell status bar's F12 menu for an instance, or {@link ShellMenu#NONE} without a single
     * Incus request when the feature is off or the instance has no template. The menu's actions
     * come from the context it builds, so tools and repos are collected once.
     */
    public ShellMenu shellMenu(String instanceName, String templateName, String workdir) {
        return shellMenu(instanceName, () -> readInstance(instanceName), templateName, workdir);
    }

    /** {@link #shellMenu(String, String, String)} from an instance its caller already read. */
    public ShellMenu shellMenu(String instanceName, JsonNode instance, String templateName, String workdir) {
        return shellMenu(instanceName, () -> instance, templateName, workdir);
    }

    private ShellMenu shellMenu(String instanceName, Supplier<JsonNode> instance, String templateName,
                                String workdir) {
        if (templateName == null || templateName.isBlank() || !ShellMenu.enabled()) {
            return ShellMenu.NONE;
        }
        var context = buildActionContext(instanceName, instance.get(), templateName);
        var actions = resolveActionsForInstance(instanceName, templateName,
                context.installedTools(), context.repos());
        return ShellMenu.of(actions, workdir, context);
    }

    /**
     * The instance as one GET returns it, failing like {@code configGet} when Incus refuses it:
     * what a caller resolving several things about one instance reads once and passes on.
     */
    public JsonNode readInstance(String instanceName) {
        var instance = incus.instanceMetadata(instanceName);
        if (instance.isMissingNode() || instance.isNull()) {
            throw new IncusException("Failed to read instance " + instanceName);
        }
        return instance;
    }

    // --- Private helpers ---

    /** The default-action reference, or null for none: the YAML chain's, else the snapshot's when the YAML is gone. */
    private String resolveDefaultActionRef(String parentTemplate, Supplier<String> snapshot) {
        // Walk the template YAML chain (child wins over parent).
        String ref = null;
        var chain = getInheritanceChain(parentTemplate);
        for (int i = chain.size() - 1; i >= 0 && ref == null; i--) {
            ref = chain.get(i).getDefaultAction();
        }
        // YAML definitions not on disk (e.g. user deleted them after building the template):
        // fall back to the snapshot stored in Incus metadata at build time.
        if (chain.isEmpty()) ref = snapshot.get();
        return (ref == null || ref.isBlank()) ? null : ref;
    }

    private record ActionRef(String toolName, String actionId) {}

    private static ActionRef parseActionRef(String ref) {
        int colon = ref.indexOf(':');
        if (colon >= 0) {
            var id = ref.substring(colon + 1);
            return new ActionRef(ref.substring(0, colon), id.isEmpty() ? null : id);
        }
        return new ActionRef(ref, null);
    }

    private static Optional<ToolAction> resolveActionByRef(ActionRef parsed,
                                                            List<ToolAction> matching) {
        if (parsed.actionId() != null) {
            var withId = matching.stream()
                    .filter(a -> a.id().map(id -> matchesActionId(id, parsed.actionId())).orElse(false))
                    .toList();
            if (withId.size() == 1) return Optional.of(withId.get(0));
            return Optional.empty();
        }
        if (matching.size() == 1) return Optional.of(matching.get(0));
        return Optional.empty();
    }

    private static boolean matchesActionId(String actualId, String requestedId) {
        if (requestedId.equals(actualId)) return true;
        // Repo-expanded actions have IDs like "base-id/repo-name"; match on the base part
        int slash = actualId.indexOf('/');
        return slash >= 0 && requestedId.equals(actualId.substring(0, slash));
    }

    private List<ImageDef> getInheritanceChain(String templateName) {
        var chain = new ArrayList<ImageDef>();
        var current = templateName;
        var visited = new java.util.HashSet<String>();

        while (current != null && !current.isEmpty() && !"-".equals(current)) {
            if (visited.contains(current)) break;
            visited.add(current);

            var def = imageDefs.get(current);
            if (def == null) break;

            chain.add(def);
            current = def.getParent();
        }

        // Reverse to root→child order so callers can iterate from end
        // to get "child wins over parent" semantics
        java.util.Collections.reverse(chain);
        return chain;
    }

    private void collectTransitiveDeps(String toolName, Set<String> result, Set<String> visited) {
        if (visited.contains(toolName)) return;
        visited.add(toolName);

        var setup = toolDefLoader.find(toolName);
        if (setup instanceof YamlToolSetup yts) {
            var toolDef = yts.toolDef();
            for (var dep : toolDef.getRequires()) {
                result.add(dep.getName());
                collectTransitiveDeps(dep.getName(), result, visited);
            }
        }
    }
}
