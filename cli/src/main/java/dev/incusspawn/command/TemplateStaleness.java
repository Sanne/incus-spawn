package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.YamlToolSetup;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Why a built template no longer matches what a build would make now (#1115): the TUI's
 * {@code ! △ ↑} marks, {@code isx templates --format=plain|json} and {@code isx build
 * --out-of-sync} ({@link #versionOutdated}, {@link #definitionChanged} and
 * {@link #parentRebuilt}, read from the instance's config) all apply these rules, so a rule
 * cannot change for one of them only. All three make a template out of sync (#1130).
 * The tool fingerprints a definition is compared with are each caller's input: the build
 * computes them per chain ({@code BuildCommand.computeToolFingerprints}), the others with
 * {@link #toolFingerprints}. It reads only what it is handed -- the built templates' stamps from one
 * Incus listing, the definitions, and the tool fingerprints, asked for only when a definition is
 * compared -- so the CLI pays for none of the TUI's reload.
 */
final class TemplateStaleness {

    private TemplateStaleness() {}

    /**
     * A built template's build stamps: {@code created} as recorded ({@code ""} or a placeholder
     * when unknown), the isx version that built it, and its definition's fingerprint then.
     */
    record Built(String name, String created, String buildVersion, String definitionSha) {}

    /**
     * @param versionOutdated   built by another isx version, or by one that did not record it
     * @param definitionChanged its definition (or a tool it uses) differs from the one it was built from
     * @param parentRebuilt     its parent was built after it
     */
    record Staleness(boolean versionOutdated, boolean definitionChanged, boolean parentRebuilt) {
        /** What {@code isx build --out-of-sync} rebuilds, and the TUI counts for it: any of the three. */
        boolean outOfSync() {
            return versionOutdated || definitionChanged || parentRebuilt;
        }
    }

    /**
     * The staleness of every template in {@code built}.
     *
     * @param defs             definitions by name, a stored-source template's included
     * @param storedSource     templates known only from the definitions stored on them, whose
     *                         definition cannot have changed under them
     * @param toolFingerprints the tool fingerprints a definition fingerprint folds in, computed
     *                         at most once, and only if a definition is compared
     */
    static Map<String, Staleness> assess(Collection<Built> built, Map<String, ImageDef> defs,
                                         Set<String> storedSource,
                                         Supplier<Map<String, String>> toolFingerprints,
                                         String currentVersion) {
        var created = new HashMap<String, String>();
        for (var t : built) {
            created.put(t.name(), t.created());
        }
        Map<String, String> fingerprints = null;
        var result = new HashMap<String, Staleness>();
        for (var t : built) {
            var versionOutdated = versionOutdated(t.buildVersion(), currentVersion);
            var def = defs.get(t.name());
            var definitionChanged = false;
            if (!t.definitionSha().isEmpty() && !storedSource.contains(t.name()) && def != null) {
                if (fingerprints == null) fingerprints = toolFingerprints.get();
                definitionChanged = definitionChanged(t.definitionSha(), def, fingerprints);
            }
            var parentRebuilt = def != null && !def.isRoot()
                    && parentRebuilt(created.get(def.getParent()), t.created());
            result.put(t.name(), new Staleness(versionOutdated, definitionChanged, parentRebuilt));
        }
        return result;
    }

    /** Built by another isx version, or by one that recorded none ({@code null} or empty). */
    static boolean versionOutdated(String buildVersion, String currentVersion) {
        return buildVersion == null || buildVersion.isEmpty() || !buildVersion.equals(currentVersion);
    }

    /**
     * The definition differs from the one the template was built from. A template with no
     * recorded fingerprint ({@code null} or empty) has nothing to compare, so it has not changed.
     */
    static boolean definitionChanged(String definitionSha, ImageDef def, Map<String, String> toolFingerprints) {
        return definitionSha != null && !definitionSha.isEmpty()
                && !definitionSha.equals(def.contentFingerprint(toolFingerprints));
    }

    /**
     * The parent was built after the template, so the template was copied from an earlier build
     * of it. A stamp that is missing ({@code null}, a parent not built) or does not parse says
     * nothing, so it has not been.
     */
    static boolean parentRebuilt(String parentCreated, String created) {
        if (parentCreated == null || created == null) return false;
        var parentTs = ListCommand.parseTimestamp(parentCreated);
        var ts = ListCommand.parseTimestamp(created);
        return parentTs != null && ts != null && parentTs.isAfter(ts);
    }

    /** The composite fingerprints of every tool {@code defs} use, their {@code requires} folded in. */
    static Map<String, String> toolFingerprints(Collection<ImageDef> defs, ToolDefLoader loader) {
        var rawFps = new TreeMap<String, String>();
        var depMap = new TreeMap<String, List<String>>();
        var visited = new HashSet<String>();
        for (var def : defs) {
            for (var toolRef : def.getTools()) {
                collectToolFps(toolRef.getName(), loader, rawFps, depMap, visited);
            }
        }
        return ToolDef.compositeFingerprints(rawFps, depMap);
    }

    private static void collectToolFps(String name, ToolDefLoader loader, Map<String, String> rawFps,
                                       Map<String, List<String>> depMap, Set<String> visited) {
        if (!visited.add(name)) return;
        if (loader.find(name) instanceof YamlToolSetup yts) {
            for (var depRef : yts.toolDef().getRequires()) {
                collectToolFps(depRef.getName(), loader, rawFps, depMap, visited);
            }
            rawFps.put(name, yts.toolDef().contentFingerprint());
            depMap.put(name, yts.toolDef().getRequires().stream().map(ToolDef.ToolRef::getName).toList());
        }
    }
}
