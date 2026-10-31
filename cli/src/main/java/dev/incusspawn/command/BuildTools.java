package dev.incusspawn.command;

import dev.incusspawn.Warnings;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tools a template installs: each one found by name, its parameters checked, its
 * {@code requires} resolved, and what its ancestors already installed taken away.
 */
public final class BuildTools {

    private BuildTools() {}

    /**
     * Resolve all tools referenced by the image definition, including
     * transitive dependencies declared via {@code requires}.
     */
    record ResolvedTool(
        String name,
        ToolSetup setup,
        Map<String, String> parameters,
        boolean reconfigureOnly
    ) {
        ResolvedTool(String name, ToolSetup setup, Map<String, String> parameters) {
            this(name, setup, parameters, false);
        }
    }

    record ToolResolution(
        List<ResolvedTool> effective,
        List<ResolvedTool> ancestors
    ) {}

    static List<ResolvedTool> resolveTools(ImageDef imageDef, ToolDefLoader toolDefLoader, boolean quiet) {
        return resolveTools(imageDef, toolDefLoader, List.of(), quiet);
    }

    /**
     * {@code quiet} silences the warnings. Auto-added dependencies are never announced here:
     * only {@link #collectEffectiveTools} does, once per build.
     */
    static List<ResolvedTool> resolveTools(ImageDef imageDef, ToolDefLoader toolDefLoader,
                                                      Iterable<ToolSetup> cdiTools, boolean quiet) {
        return resolveTools(imageDef, toolDefLoader, cdiTools, quiet, new LinkedHashMap<>());
    }

    /** Resolve, collecting into {@code autoAdded} each auto-added dependency with the tools that required it. */
    private static List<ResolvedTool> resolveTools(ImageDef imageDef, ToolDefLoader toolDefLoader,
                                                   Iterable<ToolSetup> cdiTools, boolean quiet,
                                                   Map<String, LinkedHashSet<String>> autoAdded) {
        var explicit = new LinkedHashSet<String>();
        for (var toolRef : imageDef.getTools()) {
            explicit.add(toolRef.getName());
        }
        var resolved = new LinkedHashMap<String, ResolvedTool>();
        var explicitlyResolved = new HashSet<String>();

        for (var toolRef : imageDef.getTools()) {
            resolveWithDeps(toolRef.getName(), toolRef.getParams(), resolved,
                new LinkedHashSet<>(), explicit, explicitlyResolved, true,
                toolDefLoader, cdiTools, quiet ? null : java.util.Objects.toString(imageDef.getName(), ""),
                autoAdded);
        }
        return new ArrayList<>(resolved.values());
    }

    /**
     * One note per dependency added implicitly, naming every tool that required it, so a
     * dependency shared by several tools is reported once. Only a dependency this build sets up
     * ({@code effective}, a reconfigure of an ancestor's included) is announced: not one that did
     * not resolve (unknown or feature-gated: already warned about) or that an ancestor template
     * installed as is. Packages the build installs anyway do not matter: the tool is still added
     * and its setup still runs. The note names only the dependents this build sets up, unless
     * none is (then all of them, so it still says why).
     */
    private static void noteAutoAdded(Map<String, LinkedHashSet<String>> autoAdded,
                                      List<ResolvedTool> effective) {
        var names = new HashSet<String>();
        for (var tool : effective) names.add(tool.name());
        autoAdded.forEach((dep, dependents) -> {
            if (!names.contains(dep)) return;
            var setUp = dependents.stream().filter(names::contains).toList();
            BuildOutput.note("Auto-adding dependency: " + dep
                + " (required by " + String.join(", ", setUp.isEmpty() ? dependents : setUp) + ")");
        });
    }

    /**
     * {@code template} names the template being resolved in each warning (empty: unnamed), or is
     * null for a quiet resolution: {@link Warnings#warn} says a message once, so without the name a second
     * template's unknown tool would go unreported in a build of several.
     * {@code autoAdded} collects each implicitly added dependency with the tools that required it.
     */
    private static void resolveWithDeps(String name, Map<String, String> params,
                                  LinkedHashMap<String, ResolvedTool> resolved,
                                  LinkedHashSet<String> visiting, Set<String> explicit,
                                  Set<String> explicitlyResolved, boolean isExplicit,
                                  ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools, String template,
                                  Map<String, LinkedHashSet<String>> autoAdded) {
        var quiet = template == null;
        var in = quiet || template.isEmpty() ? "" : " in template '" + OutputFormat.oneLine(template) + "'";
        if (!visiting.add(name)) {
            if (!quiet) {
                Warnings.warn("Dependency cycle detected: " +
                        String.join(" -> ", visiting) + " -> " + name + in + ", skipping.");
            }
            return;
        }
        var tool = findTool(name, toolDefLoader, cdiTools);
        if (tool == null) {
            if (!quiet) {
                var ungated = findToolUngated(name, toolDefLoader, cdiTools);
                if (ungated != null) {
                    Warnings.warn("Tool '" + name + "'" + in + " requires feature '"
                            + ungated.feature() + "' — add it to the features list in config.yaml to enable.");
                } else {
                    Warnings.warn("Unknown tool '" + name + "'" + in + ", skipping.");
                }
            }
            visiting.remove(name);
            return;
        }

        // Resolve parameters and validate
        Map<String, String> resolvedParams = params != null ? params : Map.of();
        var parameterDefs = tool.parameters();
        if (!parameterDefs.isEmpty()) {
            var validation = dev.incusspawn.tool.ParameterResolver.resolve(
                parameterDefs, resolvedParams);
            if (validation.hasErrors()) {
                throw new IllegalArgumentException(
                    "Error in tool '" + name + "' parameters:\n" +
                    String.join("\n", validation.errors().stream().map(e -> "  " + e).toList())
                );
            }
            resolvedParams = validation.resolvedValues();
        } else if (!resolvedParams.isEmpty()) {
            throw new IllegalArgumentException(
                "Tool '" + name + "' does not accept parameters, but received: " + resolvedParams.keySet()
            );
        }

        // Check if tool already resolved - if parameters differ, explicit config wins over transitive deps
        if (resolved.containsKey(name)) {
            var existing = resolved.get(name);
            if (!existing.parameters().equals(resolvedParams)) {
                if (!isExplicit && explicit.contains(name)) {
                    // Transitive dep for a tool the user explicitly configured — skip
                    if (!quiet && params != null && !params.isEmpty()) {
                        Warnings.warn("Tool '" + name + "'" + in +
                            " is explicitly configured, overriding parameters from a transitive dependency.");
                    }
                } else if (isExplicit && !explicitlyResolved.contains(name)) {
                    // Explicit config replaces a prior transitive-dep resolution
                    if (!quiet) {
                        var defaultOnly = dev.incusspawn.tool.ParameterResolver.resolve(
                            tool.parameters(), Map.of());
                        if (defaultOnly.hasErrors() ||
                                !defaultOnly.resolvedValues().equals(existing.parameters())) {
                            Warnings.warn("Tool '" + name + "'" + in +
                                " is explicitly configured, overriding parameters from a transitive dependency.");
                        }
                    }
                    resolved.put(name, new ResolvedTool(name, tool, resolvedParams));
                    explicitlyResolved.add(name);
                } else {
                    throw new IllegalArgumentException(
                        "Tool '" + name + "' specified multiple times with different parameters:\n" +
                        "  First:  " + existing.parameters() + "\n" +
                        "  Second: " + resolvedParams
                    );
                }
            }
            visiting.remove(name);
            return;
        }

        // Recursively resolve dependencies with their parameters
        if (tool instanceof dev.incusspawn.tool.YamlToolSetup yts) {
            for (var depRef : yts.toolDef().getRequires()) {
                if (!explicit.contains(depRef.getName()) && !visiting.contains(depRef.getName())) {
                    autoAdded.computeIfAbsent(depRef.getName(), k -> new LinkedHashSet<>()).add(name);
                }
                resolveWithDeps(depRef.getName(), depRef.getParams(), resolved, visiting, explicit, explicitlyResolved, false, toolDefLoader, cdiTools, template, autoAdded);
            }
        } else {
            for (var dep : tool.requires()) {
                if (!explicit.contains(dep) && !visiting.contains(dep)) {
                    autoAdded.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(name);
                }
                resolveWithDeps(dep, Map.of(), resolved, visiting, explicit, explicitlyResolved, false, toolDefLoader, cdiTools, template, autoAdded);
            }
        }

        resolved.put(name, new ResolvedTool(name, tool, resolvedParams));
        if (isExplicit) {
            explicitlyResolved.add(name);
        }
        visiting.remove(name);
    }

    private static ToolSetup findTool(String name, ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools) {
        var tool = toolDefLoader.find(name);
        if (tool != null) return isFeatureGated(tool) ? null : tool;
        for (var t : cdiTools) {
            if (t.name().equals(name)) return isFeatureGated(t) ? null : t;
        }
        return null;
    }

    static boolean isFeatureGated(ToolSetup tool) {
        var feature = tool.feature();
        return feature != null && !SpawnConfig.load().isFeatureEnabled(feature);
    }

    public static boolean isFeatureGated(ToolSetup tool, SpawnConfig config) {
        var feature = tool.feature();
        return feature != null && !config.isFeatureEnabled(feature);
    }

    private static ToolSetup findToolUngated(String name, ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools) {
        var tool = toolDefLoader.find(name);
        if (tool != null && tool.feature() != null) return tool;
        for (var t : cdiTools) {
            if (t.name().equals(name) && t.feature() != null) return t;
        }
        return null;
    }

    /**
     * The tool fingerprints {@code imageDef}'s stamp folds in: only its own tools, not its
     * ancestors', computed as the TUI and {@code isx templates} compute them (#1142). Tool
     * parameters that no longer validate are thrown, as the build would.
     */
    static Map<String, String> computeToolFingerprints(ImageDef imageDef, ToolDefLoader toolDefLoader) {
        resolveTools(imageDef, toolDefLoader, true);
        return TemplateStaleness.toolFingerprints(List.of(imageDef), toolDefLoader);
    }

    /**
     * Resolve tools for this image, removing any already installed by ancestor images.
     * If an ancestor declares the same tool with different parameters, that's an error
     * (the parent's setup already ran and can't be undone).
     * Returns both the effective tools to install and the resolved ancestor tools.
     */
    static ToolResolution collectEffectiveTools(ImageDef imageDef, Map<String, ImageDef> defs,
                                                 ToolDefLoader toolDefLoader,
                                                 Iterable<ToolSetup> cdiTools) {
        var autoAdded = new LinkedHashMap<String, LinkedHashSet<String>>();
        var tools = resolveTools(imageDef, toolDefLoader, cdiTools, false, autoAdded);

        var ancestorToolsMap = new LinkedHashMap<String, ResolvedTool>();
        var ancestorTemplateNames = new LinkedHashMap<String, String>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            for (var resolved : resolveTools(ancestor, toolDefLoader, cdiTools, true)) {
                if (ancestorToolsMap.putIfAbsent(resolved.name(), resolved) == null) {
                    ancestorTemplateNames.put(resolved.name(), ancestor.getName());
                }
            }
        }

        var ancestorTools = new ArrayList<>(ancestorToolsMap.values());
        if (tools.isEmpty()) {
            return new ToolResolution(tools, ancestorTools);
        }

        var effective = new ArrayList<ResolvedTool>();
        for (var tool : tools) {
            var ancestorTool = ancestorToolsMap.get(tool.name());
            if (ancestorTool == null) {
                effective.add(tool);
            } else if (!ancestorTool.parameters().equals(tool.parameters())) {
                var paramDefs = tool.setup().parameters();
                var allReconfigurable = true;
                for (var key : tool.parameters().keySet()) {
                    var ancestorValue = ancestorTool.parameters().get(key);
                    var childValue = tool.parameters().get(key);
                    if (!java.util.Objects.equals(ancestorValue, childValue)) {
                        var def = paramDefs.get(key);
                        if (def == null || !def.isReconfigurable()) {
                            allReconfigurable = false;
                            break;
                        }
                    }
                }
                if (allReconfigurable) {
                    for (var key : ancestorTool.parameters().keySet()) {
                        if (!tool.parameters().containsKey(key)) {
                            var def = paramDefs.get(key);
                            if (def == null || !def.isReconfigurable()) {
                                allReconfigurable = false;
                                break;
                            }
                        }
                    }
                }
                if (allReconfigurable) {
                    effective.add(new ResolvedTool(tool.name(), tool.setup(), tool.parameters(), true));
                } else {
                    var ancestorTemplateName = ancestorTemplateNames.get(tool.name());
                    throw new IllegalArgumentException(
                        "Tool '" + tool.name() + "' is already installed by ancestor template '" +
                        ancestorTemplateName + "' with different parameters:\n" +
                        "  Ancestor: " + ancestorTool.parameters() + "\n" +
                        "  Current:  " + tool.parameters()
                    );
                }
            }
        }
        noteAutoAdded(autoAdded, effective);
        return new ToolResolution(effective, ancestorTools);
    }
}
