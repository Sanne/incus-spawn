package dev.incusspawn.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulator for YAML definitions resolved across layers (built-in → user →
 * search paths → project-local), shared by {@link ImageDef} and
 * {@code ToolDefLoader}.
 * <p>
 * Overriding a definition from a <em>later</em> layer is intentional and recorded
 * as a {@link LayerOverride}. Two files declaring the same {@code name:} within a
 * <em>single directory</em> is always a mistake (usually a copy that forgot to
 * update {@code name:}) and is recorded as a {@link NameConflict}. Callers bracket
 * each directory scan with {@link #beginDirectory()} / {@link #endDirectory()} and
 * feed definitions via {@link #put}; built-ins (the first, unique layer) go through
 * {@link #putBuiltin}.
 *
 * @param <T> the resolved definition type (e.g. {@code ImageDef}, {@code YamlToolSetup})
 */
public final class LayeredDefinitions<T> {

    /**
     * Two or more files in a single directory declaring the same {@code name:}.
     * {@code files} lists every colliding file in scan order. {@code kind} is a
     * lowercase noun ("image"/"tool") used only for messages.
     */
    public record NameConflict(String kind, String name, List<Path> files) {
        public String message() {
            var sb = new StringBuilder("Multiple ").append(kind)
                    .append(" files declare name '").append(name)
                    .append("' in the same directory:\n");
            for (var f : files) {
                sb.append("  • ").append(f).append('\n');
            }
            sb.append("Rename all but one, or update their 'name:' fields so they don't collide.");
            return sb.toString();
        }

        /** Single-line form for status bars / logs. */
        public String shortMessage() {
            return "Name conflict: '" + name + "' is declared by " + files.size()
                    + " files in " + files.get(0).getParent() + " — rename all but one or fix their 'name:'.";
        }
    }

    /** A later resolution layer replaced an earlier definition of the same name. */
    public record LayerOverride(String kind, String name, String overridingSource,
                                String overriddenSource) {}

    private final String kind;
    private final Map<String, T> defs = new LinkedHashMap<>();
    // Origin ("built-in" or an absolute path) of each currently-kept name.
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final List<NameConflict> conflicts = new ArrayList<>();
    private final List<LayerOverride> overrides = new ArrayList<>();
    // Files seen in the directory currently being scanned, keyed by declared name.
    private Map<String, List<Path>> currentDir;

    public LayeredDefinitions(String kind) {
        this.kind = kind;
    }

    /** Record a built-in definition (first layer, unique names by construction). */
    public void putBuiltin(String name, T def) {
        defs.put(name, def);
        sources.put(name, "built-in");
    }

    /** Start scanning a directory; resets same-directory collision tracking. */
    public void beginDirectory() {
        currentDir = new LinkedHashMap<>();
    }

    /**
     * Record a definition parsed from {@code source} (an absolute, normalized path)
     * in the current directory. A repeat within this directory becomes a conflict
     * (emitted by {@link #endDirectory()}); a name already known from an earlier
     * layer becomes an override.
     */
    public void put(String name, T def, Path source) {
        var seenInDir = currentDir.computeIfAbsent(name, k -> new ArrayList<>());
        if (seenInDir.isEmpty() && defs.containsKey(name)) {
            overrides.add(new LayerOverride(kind, name, source.toString(), sources.get(name)));
        }
        seenInDir.add(source);
        defs.put(name, def);
        sources.put(name, source.toString());
    }

    /** Finish the current directory, emitting a conflict per name seen more than once. */
    public void endDirectory() {
        for (var entry : currentDir.entrySet()) {
            var files = entry.getValue();
            if (files.size() > 1) {
                var name = entry.getKey();
                // A name that collides within this directory is a mistake, not a
                // legitimate cross-layer override. If the first colliding file also
                // shadowed an earlier layer we recorded an override for it in put();
                // drop it so doctor doesn't report the same name as both.
                var firstSource = files.get(0).toString();
                overrides.removeIf(o -> o.name().equals(name)
                        && o.overridingSource().equals(firstSource));
                conflicts.add(new NameConflict(kind, name, List.copyOf(files)));
            }
        }
        currentDir = null;
    }

    public Map<String, T> defs() { return defs; }
    public List<NameConflict> conflicts() { return conflicts; }
    public List<LayerOverride> overrides() { return overrides; }
}
