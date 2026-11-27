package dev.incusspawn.config;

import dev.incusspawn.incus.Metadata;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code drivers:} in config.yaml (#1720): what an external tool that drives isx, such as an
 * orchestrator creating an instance per task, may do to the instances it owns
 * ({@code isx branch --owner}).
 *
 * <pre>
 * drivers:
 *   orca:
 *     destroy: true    # isx destroy --owner orca may delete orca's instances
 * </pre>
 *
 * <p>Only here: deleting an instance is the user's to grant, never a definition's, and never the
 * tool's own (it could otherwise grant itself the right). Kept as the raw YAML value, like
 * {@code host-routes:}: a mistyped section must not fail the whole config.yaml. An entry that is
 * wrong is dropped with a warning naming it, and a dropped entry grants nothing, so a typo never
 * widens what a tool may do: an unknown key drops the entry rather than ignoring a misspelt
 * {@code destroy:}.
 */
public final class Drivers {

    /** One tool's grants. */
    public record Driver(String name, boolean destroy) {}

    private static final Set<String> KEYS = Set.of("destroy");

    private final Object raw;

    private Drivers(Object raw) {
        this.raw = raw;
    }

    /** The section as written, null when config.yaml has none. */
    static Drivers of(Object raw) {
        return new Drivers(raw);
    }

    /**
     * The grants of the tool called {@code name}, or null when it is not listed or its entry is
     * wrong (said through {@code warn}): either way it may do nothing beyond what anyone may.
     */
    public Driver find(String name, Consumer<String> warn) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) {
            warn.accept("Ignoring drivers: in config.yaml (expected a tool name per entry, each with destroy:)");
            return null;
        }
        // Every key is checked, so a misspelt one (Orca:) is named rather than silently unmatched
        for (var key : map.keySet()) {
            var problem = Metadata.ownerNameProblem(String.valueOf(key));
            if (problem != null) warn.accept(ignoring(String.valueOf(key)) + ": " + problem);
        }
        var problem = Metadata.ownerNameProblem(name);
        if (problem != null || !map.containsKey(name)) return null;
        var value = map.get(name);
        // A tool listed with nothing under it is granted nothing
        if (value == null) return new Driver(name, false);
        if (!(value instanceof Map<?, ?> fields)) {
            warn.accept(ignoring(name) + ": expected destroy: under it");
            return null;
        }
        for (var field : fields.keySet()) {
            if (!KEYS.contains(String.valueOf(field))) {
                warn.accept(ignoring(name) + ": " + MappedDefinition.unknownKey(field, KEYS, " (expected destroy:)"));
                return null;
            }
        }
        var destroy = fields.get("destroy");
        if (destroy != null && !(destroy instanceof Boolean)) {
            warn.accept(ignoring(name) + ": destroy: is true or false");
            return null;
        }
        return new Driver(name, Boolean.TRUE.equals(destroy));
    }

    private static String ignoring(String name) {
        return "Ignoring drivers: " + dev.incusspawn.util.OutputFormat.oneLine(name) + " in config.yaml";
    }
}
