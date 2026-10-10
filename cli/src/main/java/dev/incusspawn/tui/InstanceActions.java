package dev.incusspawn.tui;

import dev.incusspawn.command.BuildTools;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolAction;
import dev.incusspawn.tool.YamlToolSetup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Which tool actions the TUI offers for an instance (F9) and which one Enter runs: the tools the
 * instance was built with, their declared actions, and the template's {@code default-action}.
 * Reads the definitions the TUI currently holds, through suppliers, since a reload replaces them.
 */
final class InstanceActions {

    private final Supplier<Map<String, dev.incusspawn.config.ImageDef>> imageDefs;
    private final Supplier<ToolDefLoader> toolDefLoader;
    private final Supplier<List<ToolSetup>> cdiTools;

    InstanceActions(Supplier<Map<String, dev.incusspawn.config.ImageDef>> imageDefs,
                    Supplier<ToolDefLoader> toolDefLoader, Supplier<List<ToolSetup>> cdiTools) {
        this.imageDefs = imageDefs;
        this.toolDefLoader = toolDefLoader;
        this.cdiTools = cdiTools;
    }

    /**
     * Resolve the template name for chain resolution: prefer PROFILE (always the leaf
     * template name, even when the instance was branched from another clone) over PARENT.
     */
    static String resolveTemplateName(InstanceInfo instance) {
        var profile = instance.profile();
        if (profile != null && !profile.isEmpty() && !"-".equals(profile)) return profile;
        var parent = instance.parent();
        if (parent != null && !parent.isEmpty() && !"-".equals(parent)) return parent;
        return null;
    }

    List<dev.incusspawn.config.ImageDef> getInheritanceChain(String templateName) {
        var chain = new ArrayList<dev.incusspawn.config.ImageDef>();
        var current = imageDefs.get().get(templateName);
        while (current != null) {
            chain.add(0, current); // prepend so root is first
            if (current.isRoot()) break;
            current = imageDefs.get().get(current.getParent());
        }
        return chain;
    }

    List<String> collectAutoDeps(List<String> explicitTools) {
        var explicit = new java.util.LinkedHashSet<>(explicitTools);
        var allDeps = new java.util.LinkedHashSet<String>();
        for (var toolName : explicitTools) {
            collectTransitiveDeps(toolName, allDeps, new java.util.HashSet<>());
        }
        allDeps.removeAll(explicit);
        return new ArrayList<>(allDeps);
    }

    private void collectTransitiveDeps(String name, java.util.Set<String> collected, java.util.Set<String> visiting) {
        if (collected.contains(name) || !visiting.add(name)) return;
        var tool = toolDefLoader.get().find(name);
        if (tool == null) return;
        for (var dep : tool.requires()) {
            collectTransitiveDeps(dep, collected, visiting);
            collected.add(dep);
        }
        visiting.remove(name);
    }

    /** The actions F9 offers for an instance, and Enter's default action is chosen from. */
    java.util.List<ToolAction> resolveActionsForInstance(InstanceInfo instance) {
        var actions = new ArrayList<ToolAction>();
        var tools = collectInstalledTools(instance);
        java.util.List<ActionContext.RepoInfo> repos = null;
        var handledTools = new java.util.HashSet<String>();

        // YAML-declared actions
        for (var toolName : tools) {
            var setup = toolDefLoader.get().find(toolName);
            if (setup instanceof YamlToolSetup yts) {
                var toolDef = yts.toolDef();
                if (!toolDef.getActions().isEmpty()) {
                    handledTools.add(toolName);
                }
                for (var entry : toolDef.getActions()) {
                    if (YamlToolAction.EXPAND_REPOS.equals(entry.getExpand())) {
                        if (repos == null) repos = collectRepos(instance);
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
        if (cdiTools.get() != null) {
            for (var cdiTool : cdiTools.get()) {
                if (tools.contains(cdiTool.name()) && !handledTools.contains(cdiTool.name())) {
                    for (var entry : cdiTool.actions()) {
                        if (YamlToolAction.EXPAND_REPOS.equals(entry.getExpand())) {
                            if (repos == null) repos = collectRepos(instance);
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

    String resolveDefaultActionRef(InstanceInfo instance) {
        // Walk the template YAML chain (child wins over parent).
        var templateName = resolveTemplateName(instance);
        var chain = templateName != null ? getInheritanceChain(templateName) : List.<dev.incusspawn.config.ImageDef>of();
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
        if (instance.defaultAction() != null && !instance.defaultAction().isEmpty()) {
            return instance.defaultAction();
        }
        return null;
    }

    record ActionRef(String toolName, String actionId) {}

    static ActionRef parseActionRef(String ref) {
        int colon = ref.indexOf(':');
        if (colon >= 0) {
            var id = ref.substring(colon + 1);
            return new ActionRef(ref.substring(0, colon), id.isEmpty() ? null : id);
        }
        return new ActionRef(ref, null);
    }

    static java.util.Optional<ToolAction> resolveActionByRef(ActionRef parsed,
                                                                      java.util.List<ToolAction> matching) {
        if (parsed.actionId() != null) {
            var withId = matching.stream()
                    .filter(a -> a.id().map(id -> matchesActionId(id, parsed.actionId())).orElse(false))
                    .toList();
            if (withId.size() == 1) return java.util.Optional.of(withId.get(0));
            return java.util.Optional.empty();
        }
        if (matching.size() == 1) return java.util.Optional.of(matching.get(0));
        return java.util.Optional.empty();
    }

    private static boolean matchesActionId(String actualId, String requestedId) {
        if (requestedId.equals(actualId)) return true;
        // Repo-expanded actions have IDs like "base-id/repo-name"; match on the base part
        int slash = actualId.indexOf('/');
        return slash >= 0 && requestedId.equals(actualId.substring(0, slash));
    }

    private java.util.Set<String> collectInstalledTools(InstanceInfo instance) {
        // For instances (clones), prefer the build-time tools list from BUILD_SOURCE
        // metadata. The current YAML chain may reference tools that were added after
        // the instance was branched and aren't actually installed in the container.
        // A template uses its YAML, unless that is gone (ActionResolver.collectInstalledTools' rule).
        var templateName = resolveTemplateName(instance);
        var chain = templateName == null ? List.<dev.incusspawn.config.ImageDef>of() : getInheritanceChain(templateName);
        if ((!Metadata.TYPE_BASE.equals(instance.type()) || chain.isEmpty())
                && instance.buildSourceJson() != null && !instance.buildSourceJson().isEmpty()) {
            var bs = dev.incusspawn.config.BuildSource.fromJson(instance.buildSourceJson());
            if (bs != null) {
                return extractBuildTimeTools(bs);
            }
        }
        var tools = new java.util.LinkedHashSet<String>();
        for (var def : chain) {
            for (var toolRef : def.getTools()) {
                tools.add(toolRef.getName());
            }
        }
        // Add transitive deps
        var allDeps = new java.util.LinkedHashSet<String>();
        for (var toolName : new ArrayList<>(tools)) {
            collectTransitiveDeps(toolName, allDeps, new java.util.HashSet<>());
        }
        tools.addAll(allDeps);
        var config = SpawnConfig.load();
        tools.removeIf(name -> {
            var setup = toolDefLoader.get().find(name);
            if (setup != null) return BuildTools.isFeatureGated(setup, config);
            if (cdiTools.get() != null) {
                for (var t : cdiTools.get()) {
                    if (t.name().equals(name)) return BuildTools.isFeatureGated(t, config);
                }
            }
            return false;
        });
        return tools;
    }

    private java.util.Set<String> extractBuildTimeTools(dev.incusspawn.config.BuildSource bs) {
        var tools = new java.util.LinkedHashSet<String>();
        for (var def : bs.getDefinitions().values()) {
            for (var toolRef : def.getTools()) {
                tools.add(toolRef.getName());
            }
        }
        // Add transitive deps from BUILD_SOURCE tool definitions
        for (var entry : bs.getTools().entrySet()) {
            var toolDef = entry.getValue();
            if (toolDef.getRequires() != null) {
                for (var dep : toolDef.getRequires()) {
                    tools.add(dep.getName());
                }
            }
        }
        var config = SpawnConfig.load();
        tools.removeIf(name -> {
            var setup = toolDefLoader.get().find(name);
            if (setup != null) return BuildTools.isFeatureGated(setup, config);
            if (cdiTools.get() != null) {
                for (var t : cdiTools.get()) {
                    if (t.name().equals(name)) return BuildTools.isFeatureGated(t, config);
                }
            }
            return false;
        });
        return tools;
    }

    private java.util.List<ActionContext.RepoInfo> collectRepos(InstanceInfo instance) {
        var repos = new ArrayList<ActionContext.RepoInfo>();
        var templateName = resolveTemplateName(instance);
        if (templateName == null) return repos;
        var chain = getInheritanceChain(templateName);
        for (var def : chain) {
            for (var repo : def.getRepos()) {
                var path = repo.getPath();
                if (path == null) continue;
                var repoPath = path.startsWith("~/")
                        ? "/home/agentuser" + path.substring(1) : path;
                var name = repoPath.substring(repoPath.lastIndexOf('/') + 1);
                repos.add(new ActionContext.RepoInfo(name, repoPath, repo.getUrl()));
            }
        }
        return repos;
    }

    ActionContext buildActionContext(InstanceInfo instance) {
        var tools = collectInstalledTools(instance);
        var repos = collectRepos(instance);
        return new ActionContext(
                instance.name(), instance.machineType(),
                tools, repos,
                new ActionContext.InstanceState(instance.ipv4(), instance.status(),
                        instance.parent(), instance.networkMode()));
    }
}
