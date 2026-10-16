package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which tool actions the TUI offers for an instance (F9) and which one Enter runs: the tools the
 * instance was built with, their declared actions, and the template's {@code default-action}.
 * The rules are {@link ActionResolver}'s; this builds its inputs from the listing. Reads the
 * definitions the TUI currently holds, through suppliers, since a reload replaces them.
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

    /**
     * The resolver over the definitions the TUI holds now. It gets no Incus client: everything
     * it needs comes from the listing, so resolving adds no request.
     */
    private ActionResolver resolver() {
        return new ActionResolver(null, toolDefLoader.get(), cdiTools.get(), imageDefs.get());
    }

    /** The actions F9 offers for an instance, and Enter's default action is chosen from. */
    List<ToolAction> resolveActionsForInstance(InstanceInfo instance) {
        var inputs = inputs(instance);
        return inputs.resolver().resolveActionsForInstance(instance.name(), inputs.template(),
                inputs.tools(), inputs.repos());
    }

    /** The template's {@code default-action} reference, or null for a plain shell. */
    String resolveDefaultActionRef(InstanceInfo instance) {
        return resolver().defaultActionRef(resolveTemplateName(instance), instance::defaultAction);
    }

    ActionContext buildActionContext(InstanceInfo instance) {
        var inputs = inputs(instance);
        return new ActionContext(
                instance.name(), instance.machineType(), inputs.tools(), inputs.repos(),
                new ActionContext.InstanceState(instance.ipv4(), instance.status(),
                        instance.parent(), instance.networkMode()));
    }

    /** The resolver's inputs for an instance: its leaf template, the tools it was built with, and its repos. */
    private record Inputs(ActionResolver resolver, String template, Set<String> tools,
                          List<ActionContext.RepoInfo> repos) {}

    private Inputs inputs(InstanceInfo instance) {
        var resolver = resolver();
        var template = resolveTemplateName(instance);
        return new Inputs(resolver, template,
                resolver.collectInstalledTools(instance.type(), instance.buildSourceJson(), template),
                resolver.collectRepos(instance.type(), instance.buildSourceJson(), template));
    }
}
