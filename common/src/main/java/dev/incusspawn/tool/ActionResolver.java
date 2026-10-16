package dev.incusspawn.tool;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves tool actions for instances, including default actions and named action references.
 * The one copy of these rules: {@code isx run}, {@code isx branch}, the shell's F12 menu and the
 * TUI's F9 menu and Enter all resolve through it (#1158).
 */
public class ActionResolver {

    private final IncusClient incus;
    private final ToolDefLoader toolDefLoader;
    private final List<ToolSetup> cdiTools;
    private final Map<String, ImageDef> imageDefs;
    /** Loaded on the first feature check, once per resolver. */
    private SpawnConfig config;

    /**
     * @param incus only for the methods that read an instance; null for a caller that passes in
     *              everything it has already read, as the TUI does from its listing
     */
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

        // CDI tool actions (for tools not already handled by YAML). A YAML tool without actions
        // that shadows a gated-off CDI tool is installed, but the CDI tool's commands are not.
        for (var cdiTool : cdiTools) {
            if (installedTools.contains(cdiTool.name()) && !handledTools.contains(cdiTool.name())) {
                if (featureOff(cdiTool)) continue;
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
     * The instance's {@code default-action} reference, or null when its template names none.
     * Resolve it with {@link #findActionByRef}, and say why it failed with {@link #unresolvedReason}.
     *
     * @param instance the instance as {@link #readInstance} returned it, whose stamp stands in
     *                 for a template YAML that is gone
     */
    public String defaultActionRef(JqValue instance, String parentTemplate) {
        return defaultActionRef(parentTemplate, () -> stampedDefaultAction(instance));
    }

    private static String stampedDefaultAction(JqValue instance) {
        return instance.getField("config").pathText(Metadata.DEFAULT_ACTION, "");
    }

    private static String buildSource(JqValue instance) {
        return instance.getField("config").pathText(Metadata.BUILD_SOURCE, "");
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
    public String defaultCommandForBranch(String template, JqValue sourceInstance) {
        return findDefaultAction(template, () -> stampedDefaultAction(sourceInstance),
                        () -> collectInstalledTools(sourceInstance, template), collectRepos(sourceInstance, template))
                .flatMap(a -> a.shellCommand(null)).orElse(null);
    }

    /**
     * @param snapshot the {@link Metadata#DEFAULT_ACTION} stamp, read only when a layer's YAML is gone
     * @param installedTools collected only when there is a reference to match
     */
    private Optional<ToolAction> findDefaultAction(String parentTemplate, Supplier<String> snapshot,
                                                   Supplier<Set<String>> installedTools,
                                                   List<ActionContext.RepoInfo> repos) {
        var ref = defaultActionRef(parentTemplate, snapshot);
        if (ref == null) return Optional.empty();
        return findActionByRef(ref, resolveActionsForInstance(null, parentTemplate, installedTools.get(), repos));
    }

    /**
     * Find a specific action by reference (e.g., "tool-name" or "tool-name:action-id").
     */
    public static Optional<ToolAction> findActionByRef(String ref, List<ToolAction> actions) {
        var parsed = parseActionRef(ref);
        var matching = actionsOfTool(parsed, actions);

        if (matching.isEmpty()) {
            return Optional.empty();
        }

        return resolveActionByRef(parsed, matching);
    }

    /**
     * Why {@link #findActionByRef} finds no action for the default-action {@code ref}, worded as
     * the TUI's Enter says it, or null when it finds one: {@code isx run} names a broken
     * reference rather than passing it off as none (#1444).
     */
    public static String unresolvedReason(String ref, List<ToolAction> actions) {
        var parsed = parseActionRef(ref);
        var matching = actionsOfTool(parsed, actions);
        if (matching.isEmpty()) {
            return "default-action '" + ref + "': tool not found";
        }
        if (resolveActionByRef(parsed, matching).isPresent()) return null;
        return parsed.actionId() != null
                ? "default-action '" + ref + "': action id not found or ambiguous"
                : "default-action '" + ref + "': ambiguous, use tool:action-id";
    }

    /**
     * Collect installed tools for an instance. For instances with BUILD_SOURCE metadata
     * (branched from a built template), uses the build-time tools list so that action
     * resolution reflects what was actually installed — not what the current YAML says.
     * Falls back to the YAML chain for templates or when BUILD_SOURCE is unavailable; a
     * template whose YAML is gone still has its snapshot, so it uses that, and a layer of the
     * chain whose YAML is gone comes from it too.
     *
     * @param instance the instance as {@link #readInstance} returned it
     */
    public Set<String> collectInstalledTools(JqValue instance, String parentTemplate) {
        return collectInstalledTools(instance.getField("config").pathText(Metadata.TYPE, ""),
                buildSource(instance), parentTemplate);
    }

    /**
     * {@link #collectInstalledTools(JqValue, String)} from the instance's {@link Metadata#TYPE}
     * and {@link Metadata#BUILD_SOURCE}, for a caller that already holds them.
     */
    public Set<String> collectInstalledTools(String type, String buildSourceJson, String parentTemplate) {
        if (buildSourceJson != null && !buildSourceJson.isBlank()) {
            if (!Metadata.TYPE_BASE.equals(type) || getInheritanceChain(parentTemplate).isEmpty()) {
                var bs = dev.incusspawn.config.BuildSource.fromJson(buildSourceJson);
                if (bs != null) {
                    return extractBuildTimeTools(bs);
                }
            }
        }
        return collectInstalledToolsFromChain(parentTemplate, buildSourceJson);
    }

    /**
     * Collect all installed tools by walking the template inheritance chain.
     * Use for templates (whose YAML IS authoritative) or as a fallback.
     */
    private Set<String> collectInstalledToolsFromChain(String parentTemplate, String buildSourceJson) {
        var tools = new LinkedHashSet<String>();
        if (parentTemplate == null || parentTemplate.isEmpty() || "-".equals(parentTemplate)) {
            return tools;
        }

        var chain = getInheritanceChain(parentTemplate, buildSourceJson);
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

    /**
     * Drops the tools whose feature is off. A YAML definition shadows the same-named CDI tool, so
     * only its own gate counts, as the build decides ({@code BuildTools.findTool}).
     */
    private void removeFeatureGated(Set<String> toolNames) {
        toolNames.removeIf(name -> {
            var tool = toolDefLoader.find(name);
            if (tool == null) {
                tool = cdiTools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
            }
            return tool != null && featureOff(tool);
        });
    }

    /** Whether {@code tool} is behind a feature that is off. */
    private boolean featureOff(ToolSetup tool) {
        if (tool.feature() == null) return false;
        if (config == null) config = SpawnConfig.load();
        return !config.isFeatureEnabled(tool.feature());
    }

    /**
     * Collect repository information from the template inheritance chain. Like
     * {@link #collectInstalledTools}, a clone's come from what it was built with (#1445); a
     * template's from its YAML, the build record standing in for a layer whose YAML is gone.
     *
     * @param instance the instance as {@link #readInstance} returned it
     */
    public List<ActionContext.RepoInfo> collectRepos(JqValue instance, String parentTemplate) {
        return collectRepos(instance.getField("config").pathText(Metadata.TYPE, ""), buildSource(instance),
                parentTemplate);
    }

    /**
     * {@link #collectRepos(JqValue, String)} from the instance's {@link Metadata#TYPE} and
     * {@link Metadata#BUILD_SOURCE}, for a caller that already holds them.
     */
    public List<ActionContext.RepoInfo> collectRepos(String type, String buildSourceJson, String parentTemplate) {
        var repos = new ArrayList<ActionContext.RepoInfo>();
        if (parentTemplate == null || parentTemplate.isEmpty() || "-".equals(parentTemplate)) {
            return repos;
        }

        // A clone's repos are those it was built with, like its tools: one added to the YAML since
        // is a directory the clone does not have (#1445).
        var chain = getInheritanceChain(parentTemplate, buildSourceJson, !Metadata.TYPE_BASE.equals(type));
        // One per destination, as the build clones them (#1394).
        var seen = new java.util.HashSet<String>();
        for (var def : chain) {
            for (var repo : def.getRepos()) {
                var path = repo.getPath();
                if (path == null || !seen.add(ImageDef.repoDestination(repo))) continue;
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
    public ActionContext buildActionContext(String instanceName, JqValue instance, String parentTemplate) {
        var config = instance.getField("config");
        var status = instance.pathText("status", "");
        var ipv4 = "";
        // A frozen guest is paused, not stopped, and still holds its address.
        if ("Running".equalsIgnoreCase(status) || "Frozen".equalsIgnoreCase(status)) {
            var extracted = incus.getContainerIpv4(instanceName);
            if (extracted != null) {
                ipv4 = extracted;
            }
        }
        if (ipv4.isEmpty()) {
            ipv4 = config.pathText(Metadata.STATIC_IP, "");
        }
        var networkMode = config.pathText(Metadata.NETWORK_MODE, "");
        var installedTools = collectInstalledTools(instance, parentTemplate);
        var repos = collectRepos(instance, parentTemplate);

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
    public ShellMenu shellMenu(String instanceName, JqValue instance, String templateName, String workdir) {
        return shellMenu(instanceName, () -> instance, templateName, workdir);
    }

    private ShellMenu shellMenu(String instanceName, Supplier<JqValue> instance, String templateName,
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
    public JqValue readInstance(String instanceName) {
        var instance = incus.instanceMetadata(instanceName);
        if (Jjq.isMissing(instance)) {
            throw new IncusException("Failed to read instance " + instanceName);
        }
        return instance;
    }

    // --- Private helpers ---

    /**
     * The default-action reference, or null for none: the YAML chain's, child over parent, else
     * the snapshot's when a layer's YAML is gone, the leaf's or a parent's. With a layer gone the
     * inherited value is unknown, and the stamp, kept in step with the chain while it was whole
     * ({@code BuildCommand.syncDefaultActions}), is the fallback for exactly that. A blank
     * stamp means none: every build sets or unsets it, so the layers the instance was built with
     * are not consulted, since they may hold a ref removed since. With a layer gone, the sync
     * leaves the stamp alone, so it holds the answer of the last build or sync.
     *
     * @param snapshot the {@link Metadata#DEFAULT_ACTION} stamp, read only when a layer's YAML is gone
     */
    public String defaultActionRef(String parentTemplate, Supplier<String> snapshot) {
        String ref = null;
        var chain = getInheritanceChain(parentTemplate);
        for (int i = chain.size() - 1; i >= 0 && ref == null; i--) {
            ref = chain.get(i).getDefaultAction();
        }
        if (ref == null && layerGone(chain)) ref = snapshot.get();
        return (ref == null || ref.isBlank()) ? null : ref;
    }

    /** Whether the YAML chain stops at a layer whose YAML is gone, not at a root or a parent cycle. */
    private boolean layerGone(List<ImageDef> chain) {
        if (chain.isEmpty()) return true;
        var parent = chain.getFirst().getParent();
        return parent != null && !parent.isBlank() && !"-".equals(parent) && !imageDefs.containsKey(parent);
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

    private static List<ToolAction> actionsOfTool(ActionRef parsed, List<ToolAction> actions) {
        return actions.stream().filter(a -> parsed.toolName().equals(a.toolName())).toList();
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

    /** The template's YAML chain, root first; empty when the leaf's YAML is gone. */
    private List<ImageDef> getInheritanceChain(String templateName) {
        return getInheritanceChain(templateName, null);
    }

    /**
     * The template's chain, root first. A layer whose YAML is gone comes from the definitions the
     * instance was built with, as {@code isx build} and the TUI's refresh already take them; the
     * build record is parsed only then. From the first recorded layer up, the ancestors come from
     * the record too: a recorded layer's parent is the one it was built on, not whatever YAML has
     * that name now.
     */
    private List<ImageDef> getInheritanceChain(String templateName, String buildSourceJson) {
        return getInheritanceChain(templateName, buildSourceJson, false);
    }

    /**
     * {@link #getInheritanceChain(String, String)}, from the record from the leaf up when
     * {@code recordFirst}: what a clone was built with, not what the YAML says now.
     */
    private List<ImageDef> getInheritanceChain(String templateName, String buildSourceJson, boolean recordFirst) {
        var chain = new ArrayList<ImageDef>();
        var current = templateName;
        var visited = new java.util.HashSet<String>();
        Map<String, ImageDef> stored = recordFirst ? recordedDefinitions(buildSourceJson) : null;
        var fromRecord = recordFirst;

        while (current != null && !current.isEmpty() && !"-".equals(current)) {
            if (visited.contains(current)) break;
            visited.add(current);

            ImageDef def;
            if (fromRecord) {
                def = stored.get(current);
                // A record that lacks an ancestor (built while it was missing) leaves the rest to the YAML.
                if (def == null) {
                    fromRecord = false;
                    def = imageDefs.get(current);
                }
            } else {
                def = imageDefs.get(current);
                if (def == null && buildSourceJson != null && !buildSourceJson.isBlank()) {
                    if (stored == null) stored = recordedDefinitions(buildSourceJson);
                    def = stored.get(current);
                    fromRecord = def != null;
                }
            }
            if (def == null) break;

            chain.add(def);
            current = def.getParent();
        }

        // Reverse to root→child order so callers can iterate from end
        // to get "child wins over parent" semantics
        java.util.Collections.reverse(chain);
        return chain;
    }

    /** The definitions the record holds, empty when there is none or it does not parse. */
    private static Map<String, ImageDef> recordedDefinitions(String buildSourceJson) {
        var bs = buildSourceJson == null || buildSourceJson.isBlank() ? null
                : dev.incusspawn.config.BuildSource.fromJson(buildSourceJson);
        return bs == null ? Map.of() : bs.getDefinitions();
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
