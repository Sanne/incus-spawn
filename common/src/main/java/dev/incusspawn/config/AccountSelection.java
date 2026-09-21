package dev.incusspawn.config;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsing, resolution and validation of which credential account an instance uses.
 *
 * <h3>Three layers, resolved lowest-first</h3>
 * <ol>
 *   <li>the namespace's {@code default} in config.yaml -- left implicit, applied by
 *       {@link AccountResolver} at the point of use rather than written onto the instance</li>
 *   <li>the template's {@code accounts:} map, merged down the inheritance chain</li>
 *   <li>the per-instance override from {@code isx branch --account} or {@code isx account set}</li>
 * </ol>
 * Only layers 2 and 3 are recorded on the instance, so an instance that says nothing keeps
 * following the global default as it changes.
 *
 * <p>Selection is always written {@code <namespace>=<account>}. A bare account name is
 * deliberately not accepted: it would have to fan out across whichever namespaces happened to
 * have an account of that name, so adding a credential namespace later would silently widen
 * every existing command.
 */
public final class AccountSelection {

    private AccountSelection() {}

    /** Thrown for a malformed {@code --account} argument. */
    public static class InvalidSelectionException extends RuntimeException {
        public InvalidSelectionException(String message) { super(message); }
    }

    /**
     * Parse {@code ns=name} specifications into a namespace → account map.
     * Accepts repeated flags and comma-separated values alike.
     */
    public static Map<String, String> parse(List<String> specs) {
        var parsed = new LinkedHashMap<String, String>();
        if (specs == null) return parsed;
        for (var raw : specs) {
            if (raw == null || raw.isBlank()) continue;
            for (var spec : raw.split(",")) {
                if (spec.isBlank()) continue;
                var eq = spec.indexOf('=');
                // Validate the stripped halves, not the raw offsets: 'claude= ' has a character
                // after the '=' but no account, and would otherwise pass here, strip to empty,
                // and silently resolve to the default instead of saying anything.
                var namespace = eq < 0 ? "" : spec.substring(0, eq).strip();
                var account = eq < 0 ? "" : spec.substring(eq + 1).strip();
                if (eq < 0 || namespace.isEmpty() || account.isEmpty()) {
                    throw new InvalidSelectionException(
                            "Invalid --account '" + spec.strip() + "'. Expected <namespace>=<account>,"
                                    + " for example --account claude=work.");
                }
                var previous = parsed.put(namespace, account);
                if (previous != null && !previous.equals(account)) {
                    throw new InvalidSelectionException(
                            "Conflicting --account values for '" + namespace + "': '"
                                    + previous + "' and '" + account + "'.");
                }
            }
        }
        return parsed;
    }

    /**
     * The selection an instance branched from this template should carry: the template
     * chain's {@code accounts:}, with per-instance overrides applied on top.
     */
    public static Map<String, String> resolve(ImageDef def, Map<String, ImageDef> defs,
                                              Map<String, String> overrides) {
        var resolved = def == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<>(ImageDef.resolveAccounts(def, defs));
        if (overrides != null) resolved.putAll(overrides);
        return resolved;
    }

    /**
     * Check every named account exists and is usable, raising
     * {@link AccountResolver.UnknownAccountException} otherwise.
     *
     * <p>Done at selection time so a typo is reported while the user is choosing, rather than
     * surfacing later as a failed API call inside a container.
     */
    public static void validate(SpawnConfig config, Map<String, String> selection) {
        if (selection == null || selection.isEmpty()) return;
        var tree = new com.fasterxml.jackson.databind.ObjectMapper()
                .<com.fasterxml.jackson.databind.JsonNode>valueToTree(config);
        for (var entry : selection.entrySet()) {
            var namespace = entry.getKey();
            var account = entry.getValue();
            if (SpawnConfig.ClaudeConfig.NAMESPACE.equals(namespace)) {
                // Typed path: also knows about the synthesized pre-accounts account.
                config.getClaude().accountNamed(account);
            } else {
                AccountResolver.effectiveAccount(tree, namespace, account);
            }
        }
    }

    /**
     * The env class each selected account implies, for namespaces whose tool declares one.
     * Namespaces with no class are omitted -- their accounts are freely interchangeable.
     *
     * @see ToolSetup#envClass
     */
    public static Map<String, String> envClasses(SpawnConfig config, Map<String, String> selection) {
        var classes = new LinkedHashMap<String, String>();
        if (selection == null || selection.isEmpty()) return classes;
        var setups = namespaceSetups();
        selection.forEach((namespace, account) -> {
            var setup = setups.get(namespace);
            if (setup == null) return;
            var envClass = setup.envClass(config, account);
            if (envClass != null && !envClass.isBlank()) classes.put(namespace, envClass);
        });
        return classes;
    }

    /**
     * Why this selection cannot be applied to an already-built instance, or {@code ""} when it
     * can. A mismatch means the container's baked environment describes a different auth mode,
     * which no amount of proxy-side substitution can fix.
     */
    public static String incompatibilityReason(SpawnConfig config, IncusClient incus,
                                               String instance, Map<String, String> selection) {
        var wanted = envClasses(config, selection);
        for (var entry : wanted.entrySet()) {
            var namespace = entry.getKey();
            var baked = incus.configGet(instance, Metadata.envClassKey(namespace));
            if (baked == null || baked.isBlank()) continue;
            if (!baked.equals(entry.getValue())) {
                return "Instance '" + instance + "' was built for " + namespace + " auth mode '"
                        + baked + "', but account '" + selection.get(namespace) + "' uses '"
                        + entry.getValue() + "'. The mode is baked into the container's"
                        + " environment at build time, so it cannot be swapped on a built"
                        + " instance -- branch from a template configured for '"
                        + entry.getValue() + "' instead.";
            }
        }
        return "";
    }

    /** Render a selection for humans: {@code claude=work, github=acme-bot}. */
    public static String describe(Map<String, String> selection) {
        if (selection == null || selection.isEmpty()) return "(defaults)";
        return selection.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Write a selection onto an instance, unpinning namespaces it no longer names.
     *
     * <p>Cleared keys are sent as {@code null}, which Incus removes; an empty string would set
     * the key to an empty value and leave it visible in {@code incus config show} forever.
     */
    public static void stamp(IncusClient incus, String instance, Map<String, String> selection) {
        var updates = new LinkedHashMap<String, Object>();
        read(incus, instance).keySet()
                .forEach(ns -> updates.put(Metadata.accountKey(ns), null));
        selection.forEach((namespace, account) ->
                updates.put(Metadata.accountKey(namespace), account));
        if (!updates.isEmpty()) incus.configUpdate(instance, updates);
    }

    /**
     * The selection currently recorded on an instance.
     *
     * <p>Read by key prefix in one request rather than by asking for each known namespace:
     * enumerating namespaces means rescanning every tool YAML, and {@code configGet} is a full
     * instance GET per key -- together a filesystem scan plus a round trip per namespace, which
     * {@code isx doctor} would then pay once per instance. Reading the prefix also surfaces a
     * namespace whose tool is no longer installed, so {@link #stamp} can still clear it.
     */
    public static Map<String, String> read(IncusClient incus, String instance) {
        var selection = new LinkedHashMap<String, String>();
        incus.configByPrefix(instance, Metadata.ACCOUNT_PREFIX).forEach((namespace, value) -> {
            if (value != null && !value.isBlank()) selection.put(namespace, value.strip());
        });
        return selection;
    }

    /** Config namespaces any installed tool declares, plus Claude's built-in one. */
    public static List<String> knownNamespaces() {
        return namespaceSetups().keySet().stream().toList();
    }

    private static Map<String, ToolSetup> namespaceSetups() {
        var byNamespace = new LinkedHashMap<String, ToolSetup>();
        new ToolDefLoader().allToolSetups().forEach((toolName, setup) -> {
            var proxyDef = setup.proxy();
            if (proxyDef == null) return;
            var namespace = proxyDef.getConfigNamespace();
            if (!namespace.isBlank()) byNamespace.putIfAbsent(namespace, setup);
        });
        return byNamespace;
    }
}
