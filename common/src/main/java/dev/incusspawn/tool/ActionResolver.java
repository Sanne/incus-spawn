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

/**
 * Resolves tool actions for instances, including default actions and named action references.
 * Shared logic between ListCommand (TUI) and RunCommand (CLI).
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
        this.cdiTools = cdiTools;
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
        if (cdiTools != null) {
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
        }

        return actions;
    }

    /**
     * Find the default action for an instance based on its template's default-action field.
     */
    public Optional<ToolAction> findDefaultAction(String instanceName, String parentTemplate,
                                                   Set<String> installedTools,
                                                   List<ActionContext.RepoInfo> repos) {
        var ref = resolveDefaultActionRef(instanceName, parentTemplate);
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }

        var actions = resolveActionsForInstance(instanceName, parentTemplate, installedTools, repos);
        return findActionByRef(ref, actions);
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
     * Falls back to the YAML chain for templates or when BUILD_SOURCE is unavailable.
     */
    public Set<String> collectInstalledTools(String instanceName, String parentTemplate) {
        return collectInstalledTools(readInstance(instanceName).path("config"), parentTemplate);
    }

    private Set<String> collectInstalledTools(JsonNode config, String parentTemplate) {
        var buildSourceJson = config.path(Metadata.BUILD_SOURCE).asText("");
        if (!buildSourceJson.isBlank()) {
            var type = config.path(Metadata.TYPE).asText("");
            if (!Metadata.TYPE_BASE.equals(type)) {
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

        var config = SpawnConfig.load();
        var chain = getInheritanceChain(parentTemplate);
        for (var def : chain) {
            for (var repo : def.getRepos()) {
                var path = repo.getPath();
                if (path == null) continue;
                var repoPath = path.startsWith("~/")
                        ? "/home/agentuser" + path.substring(1)
                        : path;
                var name = repoPath.substring(repoPath.lastIndexOf('/') + 1);
                var hostPath = resolveHostPath(name, config);
                repos.add(new ActionContext.RepoInfo(name, repoPath, repo.getUrl(), hostPath));
            }
        }
        return repos;
    }

    private static String resolveHostPath(String repoName, SpawnConfig config) {
        try {
            var path = dev.incusspawn.git.GitRemoteUtils.resolveHostRepoPath(repoName, config);
            return path != null ? path.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build an ActionContext for executing an action on an instance.
     */
    public ActionContext buildActionContext(String instanceName, String parentTemplate) {
        // One instance read for status, type and every config key (configGet is a full instance
        // GET per key), plus /state only where a guest can hold an address (#979).
        var instance = readInstance(instanceName);
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
        var installedTools = collectInstalledTools(config, parentTemplate);
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
     * Resolve shell menu actions for an instance: actions with shell_menu=true,
     * filtered to host-side types (url/command), with expand:repos collapsed
     * to a single entry matching the effective workdir.
     */
    public List<ToolAction> resolveShellMenuActions(String instanceName, String parentTemplate) {
        var installedTools = collectInstalledTools(instanceName, parentTemplate);
        var repos = collectRepos(parentTemplate);
        var allActions = resolveActionsForInstance(instanceName, parentTemplate, installedTools, repos);

        var workdir = incus.configGet(instanceName, Metadata.WORKDIR);
        var effectiveWorkdir = (workdir == null || workdir.isBlank()) ? "/home/agentuser" : workdir;

        var candidates = allActions.stream()
                .filter(ToolAction::isShellMenu)
                .filter(a -> a.type().map(t -> "url".equals(t) || "command".equals(t)).orElse(false))
                .toList();

        var menuActions = new ArrayList<ToolAction>();
        var seen = new java.util.HashSet<String>();
        for (var action : candidates) {
            if (action.repoPath().isPresent()) {
                var groupKey = action.toolName();
                if (!seen.add(groupKey)) continue;
                var match = candidates.stream()
                        .filter(a -> a.toolName().equals(groupKey))
                        .filter(a -> a.repoPath().map(effectiveWorkdir::equals).orElse(false))
                        .findFirst()
                        .orElse(action);
                menuActions.add(match);
            } else {
                var key = action.toolName() + ":" + action.id().orElse("");
                if (seen.add(key)) {
                    menuActions.add(action);
                }
            }
        }
        return menuActions;
    }

    // --- Private helpers ---

    /** The instance as one GET returns it, failing like {@code configGet} when Incus refuses it. */
    private JsonNode readInstance(String instanceName) {
        var instance = incus.instanceMetadata(instanceName);
        if (instance.isMissingNode() || instance.isNull()) {
            throw new IncusException("Failed to read instance " + instanceName);
        }
        return instance;
    }

    private String resolveDefaultActionRef(String instanceName, String parentTemplate) {
        // Walk the template YAML chain (child wins over parent).
        var chain = getInheritanceChain(parentTemplate);
        if (!chain.isEmpty()) {
            for (int i = chain.size() - 1; i >= 0; i--) {
                var def = chain.get(i);
                if (def.getDefaultAction() != null) {
                    return def.getDefaultAction();
                }
            }
            return null;
        }
        // YAML definitions not on disk (e.g. user deleted them after building the template):
        // fall back to the snapshot stored in Incus metadata at build time.
        var refValue = incus.configGet(instanceName, Metadata.DEFAULT_ACTION);
        return (refValue == null || refValue.isBlank()) ? null : refValue;
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
