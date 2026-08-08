package dev.incusspawn.tool;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
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
     * Collect all installed tools for an instance by walking its template inheritance chain.
     */
    public Set<String> collectInstalledTools(String parentTemplate) {
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
        return tools;
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
        var status = incus.getInstanceStatus(instanceName);
        var ipv4 = "";
        if (incus.configGet(instanceName, "volatile.eth0.hwaddr") != null) {
            var extracted = incus.getContainerIpv4(instanceName);
            if (extracted != null) {
                ipv4 = extracted;
            }
        }
        if (ipv4.isEmpty()) {
            ipv4 = incus.configGet(instanceName, Metadata.STATIC_IP);
            if (ipv4 == null) ipv4 = "";
        }
        var networkMode = incus.configGet(instanceName, Metadata.NETWORK_MODE);
        var installedTools = collectInstalledTools(parentTemplate);
        var repos = collectRepos(parentTemplate);

        return new ActionContext(
                instanceName,
                ipv4,
                status,
                parentTemplate,
                installedTools,
                networkMode,
                repos
        );
    }

    // --- Private helpers ---

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
