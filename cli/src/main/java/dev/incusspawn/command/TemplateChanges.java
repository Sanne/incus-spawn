package dev.incusspawn.command;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ToolDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What differs between a built template, as its {@code build-source} stamp recorded it, and its
 * definition now (#1201): the reasons behind {@link TemplateStaleness#definitionChanged}, and the
 * tools a template got from a parent when it was built that its chain no longer provides.
 */
public final class TemplateChanges {

    private TemplateChanges() {}

    /**
     * The hash inputs people edit, as built versus now: packages, tools and their parameters, and
     * repos, one line each ({@code + package htop}, {@code − tool claude},
     * {@code ~ tool jdk (version: 21)}). Empty when none of them differ, which leaves the settings
     * and tool definitions that {@link ImageDef#contentFingerprint} also covers.
     */
    public static List<String> definitionChanges(ImageDef built, ImageDef now, Map<String, ImageDef> defs) {
        var changes = new ArrayList<String>();
        addChanges(changes, "package", list(built.getPackages()), list(now.getPackages()),
                p -> p, p -> p, (x, y) -> null, k -> "");
        addChanges(changes, "tool", list(built.getTools()), list(now.getTools()), ToolDef.ToolRef::getName,
                TemplateChanges::toolLabel, (x, y) -> x.getParams().equals(y.getParams()) ? null
                        : toolLabel(x) + " → " + toolLabel(y),
                tool -> inheritedFrom(tool, now, defs));
        addChanges(changes, "repo", list(built.getRepos()), list(now.getRepos()),
                r -> r.sourceDescription() + " → " + r.getPath(), r -> r.sourceDescription() + " → " + r.getPath(),
                TemplateChanges::repoSettingsChange, k -> "");
        return changes;
    }

    /**
     * Tools the build of {@code def} included through its definition chain that the chain no
     * longer names, each with the template that provided it then (the nearest one). Empty when
     * there is no stamp to compare.
     */
    public static Map<String, String> droppedTools(BuildSource built, ImageDef def, Map<String, ImageDef> defs) {
        var dropped = new LinkedHashMap<String, String>();
        if (built == null) return dropped;
        var builtDefs = built.getDefinitions();
        var builtLeaf = builtDefs.get(def.getName());
        // Leaf first, so the first template naming a tool is the one that provided it
        var builtChain = builtLeaf != null ? ImageDef.chain(builtLeaf, builtDefs).reversed() : builtDefs.values();
        for (var layer : builtChain) {
            for (var tool : list(layer.getTools())) dropped.putIfAbsent(tool.getName(), layer.getName());
        }
        for (var layer : ImageDef.chain(def, defs)) {
            for (var tool : list(layer.getTools())) dropped.remove(tool.getName());
        }
        return dropped;
    }

    /** {@link #droppedTools} less those {@code def} named itself, which {@link #definitionChanges} lists. */
    public static Map<String, String> droppedInheritedTools(BuildSource built, ImageDef def,
                                                            Map<String, ImageDef> defs) {
        var dropped = droppedTools(built, def, defs);
        dropped.values().removeIf(def.getName()::equals);
        return dropped;
    }

    /** A tool reference with its parameters, {@code jdk (version: 21)}. */
    public static String toolLabel(ToolDef.ToolRef toolRef) {
        if (toolRef.getParams().isEmpty()) return toolRef.getName();
        var params = toolRef.getParams().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        return toolRef.getName() + " (" + params + ")";
    }

    /**
     * One line per item removed ({@code −}, by key), added ({@code +}), or kept under its key with
     * other settings ({@code ~}, as {@code changed} describes them; null when they are the same).
     */
    private static <T> void addChanges(List<String> changes, String kind, List<T> built, List<T> now,
                                       Function<T, String> key, Function<T, String> added,
                                       BiFunction<T, T, String> changed,
                                       Function<String, String> removedNote) {
        var before = byKey(built, key);
        var after = byKey(now, key);
        for (var k : before.keySet()) {
            if (!after.containsKey(k)) changes.add("− " + kind + " " + k + removedNote.apply(k));
        }
        after.forEach((k, item) -> {
            var old = before.get(k);
            var change = old != null ? changed.apply(old, item) : null;
            if (old == null) changes.add("+ " + kind + " " + added.apply(item));
            else if (change != null) changes.add("~ " + kind + " " + change);
        });
    }

    /** {@code url → path (branch main → next, depth, prime)}, or null when branch, depth and prime are unchanged. */
    private static String repoSettingsChange(ImageDef.RepoEntry built, ImageDef.RepoEntry now) {
        var what = new ArrayList<String>();
        if (!Objects.equals(built.getBranch(), now.getBranch())) {
            what.add("branch " + orElse(built.getBranch(), "(default)") + " → " + orElse(now.getBranch(), "(default)"));
        }
        if (!Objects.equals(built.getDepth(), now.getDepth())) {
            what.add("depth " + orElse(built.getDepth(), "(full)") + " → " + orElse(now.getDepth(), "(full)"));
        }
        if (!Objects.equals(built.getPrime(), now.getPrime())) {
            what.add("prime " + orElse(built.getPrime(), "(none)") + " → " + orElse(now.getPrime(), "(none)"));
        }
        return what.isEmpty() ? null : now.sourceDescription() + " → " + now.getPath() + " (" + String.join(", ", what) + ")";
    }

    private static String orElse(Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    /** {@code  (still inherited from tpl-dev)} when a parent of {@code now} names the tool, else empty. */
    private static String inheritedFrom(String tool, ImageDef now, Map<String, ImageDef> defs) {
        for (var parent : ImageDef.ancestors(now, defs)) {
            if (list(parent.getTools()).stream().anyMatch(t -> t.getName().equals(tool))) {
                return " (still inherited from " + parent.getName() + ")";
            }
        }
        return "";
    }

    private static <T> Map<String, T> byKey(List<T> items, Function<T, String> key) {
        var map = new LinkedHashMap<String, T>();
        for (var item : items) map.putIfAbsent(key.apply(item), item);
        return map;
    }

    private static <T> List<T> list(List<T> items) {
        return items != null ? items : List.of();
    }
}
