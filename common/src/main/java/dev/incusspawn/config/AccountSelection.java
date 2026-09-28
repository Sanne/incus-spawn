package dev.incusspawn.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
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
        selection.forEach((namespace, account) ->
                AccountResolver.effectiveAccount(config, namespace, account));
    }

    /**
     * As {@link #validate(SpawnConfig, Map)}, against setups the caller already discovered, so
     * a YAML tool's namespace is not looked up by scanning the tool definitions again.
     */
    public static void validate(SpawnConfig config, Map<String, String> selection,
                                Map<String, ToolSetup> setups) {
        if (selection == null || selection.isEmpty()) return;
        var tree = config.tree();
        selection.forEach((namespace, account) -> {
            var setup = setups.get(namespace);
            var shape = setup != null ? setup.accountShape() : AccountResolver.shapeOf(config, namespace);
            AccountResolver.effectiveAccount(tree, namespace, shape, account);
        });
    }

    /** What a namespace offers: every usable account, and which one applies by default. */
    public record AccountListing(List<String> names, String defaultName) {}

    /**
     * Every usable account under a namespace, and the one that applies when nothing narrower
     * does.
     *
     * <p>Answered by the same {@link AccountResolver} the proxy serves from, for every namespace
     * alike. {@code isx account list} reporting a different default than the proxy actually
     * serves would be the worst kind of wrong -- the user would be reading a reassurance that
     * is not true.
     */
    public static AccountListing listAccounts(SpawnConfig config, String namespace) {
        var tree = config.tree();
        var shape = AccountResolver.shapeOf(config, namespace);
        return new AccountListing(
                AccountResolver.usableAccountNames(tree, namespace, shape),
                AccountResolver.effectiveAccount(tree, namespace, shape, null));
    }

    /**
     * What the build derived from each selected account, for namespaces whose tool derives
     * anything. Namespaces absent from the result bake nothing, so their accounts are freely
     * interchangeable.
     *
     * @see ToolSetup#bakedAccountIdentity
     */
    public static Map<String, String> bakedIdentities(SpawnConfig config, Map<String, String> selection) {
        return bakedIdentities(config, selection, namespaceSetups(config));
    }

    /** As {@link #bakedIdentities(SpawnConfig, Map)}, against setups the caller already discovered. */
    public static Map<String, String> bakedIdentities(SpawnConfig config, Map<String, String> selection,
                                                 Map<String, ToolSetup> setups) {
        var identities = new LinkedHashMap<String, String>();
        if (selection == null || selection.isEmpty()) return identities;
        selection.forEach((namespace, account) -> {
            var setup = setups.get(namespace);
            if (setup == null) return;
            var identity = setup.bakedAccountIdentity(config, account);
            if (identity != null && !identity.isBlank()) identities.put(namespace, identity);
        });
        return identities;
    }

    /**
     * Why this selection cannot be applied to an already-built instance, or {@code ""} when it
     * can.
     *
     * <p>Only namespaces whose tool bakes something can object, and only when it cannot
     * re-derive that thing in place. GitHub can -- the git identity is re-read from the API
     * through the proxy, which answers for the new account by itself -- so a GitHub re-point is
     * accepted here and reconciled on the instance's next use. Claude cannot, because its auth
     * mode lives in the environment a running agent has already read.
     */
    public static String incompatibilityReason(SpawnConfig config, IncusClient incus,
                                               String instance, Map<String, String> selection) {
        return incompatibilityReason(config, incus, instance, selection, namespaceSetups(config));
    }

    /** As {@link #incompatibilityReason(SpawnConfig, IncusClient, String, Map)}, against known setups. */
    public static String incompatibilityReason(SpawnConfig config, IncusClient incus, String instance,
                                               Map<String, String> selection, Map<String, ToolSetup> setups) {
        var wanted = bakedIdentities(config, selection, setups);
        if (wanted.isEmpty()) return "";
        var baked = incus.configByPrefix(instance, Metadata.ACCOUNT_IDENTITY_PREFIX);
        for (var entry : wanted.entrySet()) {
            var namespace = entry.getKey();
            var wasBaked = baked.get(namespace);
            if (!cannotHonour(setups.get(namespace), wasBaked, entry.getValue())) continue;
            // A null account is "follow the default" -- what 'isx account unset' asks for.
            var chosen = selection.get(namespace);
            var what = chosen == null || chosen.isBlank()
                    ? "the default account" : "account '" + chosen + "'";
            return "Instance '" + instance + "' was built for " + namespace + " '"
                    + wasBaked + "', but " + what + " is '"
                    + entry.getValue() + "'. That is baked into the container at build time and"
                    + " cannot be changed on a built instance -- branch from a template"
                    + " configured for '" + entry.getValue() + "' instead.";
        }
        return "";
    }

    private static boolean canRebake(ToolSetup setup) {
        return setup != null && setup.canRebakeForAccount();
    }

    /** Whether an instance that baked {@code baked} cannot be moved to an account that bakes {@code wanted}. */
    private static boolean cannotHonour(ToolSetup setup, String baked, String wanted) {
        if (baked == null || baked.isBlank() || wanted == null || wanted.isBlank() || baked.equals(wanted)) {
            return false;
        }
        return !canRebake(setup);
    }

    /**
     * What an instance built as {@code bakedIdentities} (its {@code account-identity} stamps)
     * would have to be rebuilt as to use {@code account} for {@code namespace} -- e.g. {@code
     * "vertex"} for a Vertex account on an instance built for Pro/Max -- or {@code ""} when the
     * account can be used as it is. For offering only the accounts a choice could honour, before
     * anything is attempted.
     */
    public static String requiredRebuild(SpawnConfig config, ToolSetup setup, String namespace,
                                         String account, Map<String, String> bakedIdentities) {
        if (setup == null) return "";
        String wanted;
        try {
            wanted = setup.bakedAccountIdentity(config, account);
        } catch (AccountResolver.UnknownAccountException e) {
            return "";
        }
        return cannotHonour(setup, bakedIdentities.get(namespace), wanted) ? wanted : "";
    }

    /**
     * Namespaces whose baked identity on this instance no longer matches the account it is
     * pinned to, mapped to the account it should be brought in line with. Each one's tool can
     * re-derive -- the ones that cannot were refused at selection time.
     */
    public static Map<String, String> staleIdentities(SpawnConfig config, IncusClient incus,
                                                      String instance) {
        return staleIdentities(config, incus, instance, null);
    }

    /**
     * As {@link #staleIdentities(SpawnConfig, IncusClient, String)}, against known setups;
     * {@code null} discovers them, and only when something is baked at all.
     */
    public static Map<String, String> staleIdentities(SpawnConfig config, IncusClient incus,
                                                      String instance, Map<String, ToolSetup> knownSetups) {
        var stale = new LinkedHashMap<String, String>();
        var baked = incus.configByPrefix(instance, Metadata.ACCOUNT_IDENTITY_PREFIX);
        if (baked.isEmpty()) return stale;
        var setups = knownSetups != null ? knownSetups : namespaceSetups(config);
        var selection = read(incus, instance);
        bakedIdentities(config, effectiveSelection(selection, setups), setups)
                .forEach((namespace, identity) -> {
                    var wasBaked = baked.get(namespace);
                    if (wasBaked == null || wasBaked.isBlank() || wasBaked.equals(identity)) return;
                    if (!canRebake(setups.get(namespace))) return;
                    stale.put(namespace, identity);
                });
        return stale;
    }

    /**
     * The selection with every known namespace present, a null account meaning "whatever the
     * configured default resolves to" -- which is the account a build with no explicit
     * selection actually used.
     */
    public static Map<String, String> effectiveSelection(Map<String, String> selection,
                                                         Map<String, ToolSetup> setups) {
        var effective = new LinkedHashMap<String, String>();
        setups.keySet().forEach(namespace -> effective.put(namespace, selection.get(namespace)));
        return effective;
    }

    /**
     * Follow an account rename onto every instance: pins to {@code from} become pins to
     * {@code to}, and so does a baked identity that <em>is</em> the account name (GitHub's), so
     * the rename does not read as a change of identity and trigger a needless re-derive.
     *
     * <p>Run after the config is saved under the new name: until then the proxy would answer
     * the re-pointed pin with "not configured". One list request, and one write per instance
     * that has anything to change.
     *
     * @param config the configuration as it reads after the rename
     * @return the instances whose pin was re-pointed
     */
    public static List<String> renameInInstances(IncusClient incus, SpawnConfig config,
                                                 String namespace, String from, String to) {
        var setup = namespaceSetups(config).get(namespace);
        // Only a tool that bakes the account itself: Claude bakes an auth mode, which an
        // account's name could coincide with without being it.
        var identityIsName = setup != null && setup.canRebakeForAccount()
                && identityIsAccountName(setup, config, to);
        var pinKey = Metadata.accountKey(namespace);
        var identityKey = Metadata.accountIdentityKey(namespace);
        var repointed = new java.util.ArrayList<String>();
        JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(incus.listJsonConfig());
        } catch (Exception e) {
            throw new dev.incusspawn.incus.IncusException(
                    "Could not read instance account pinning: " + e.getMessage(), e);
        }
        for (var instance : root) {
            var name = instance.path("name").asText("");
            var instanceConfig = instance.path("config");
            if (name.isEmpty() || !instanceConfig.isObject()) continue;
            var updates = new LinkedHashMap<String, String>();
            if (from.equals(instanceConfig.path(pinKey).asText(""))) updates.put(pinKey, to);
            if (identityIsName && from.equals(instanceConfig.path(identityKey).asText(""))) {
                updates.put(identityKey, to);
            }
            if (updates.isEmpty()) continue;
            incus.configSetAll(name, updates);
            if (updates.containsKey(pinKey)) repointed.add(name);
        }
        return repointed;
    }

    /**
     * Whether the tool's baked identity for {@code account} is its name. Asking resolves the
     * account, which refuses an incomplete one -- and a rename must still re-point the pins of an
     * account that is incomplete, or they would name something that no longer exists. Such an
     * identity is left alone; it is re-derived once the account is usable.
     */
    private static boolean identityIsAccountName(ToolSetup setup, SpawnConfig config, String account) {
        try {
            return account.equals(setup.bakedAccountIdentity(config, account));
        } catch (AccountResolver.UnknownAccountException e) {
            return false;
        }
    }

    /** Render a selection for humans: {@code claude=work, github=acme-bot}. */
    public static String describe(Map<String, String> selection) {
        if (selection == null || selection.isEmpty()) return "(defaults)";
        return selection.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Record an instance's pins and who chose each, replacing what it had: namespaces in
     * {@code current} but not in {@code selection} are unpinned.
     *
     * <p>Cleared keys are sent as {@code null}, which Incus removes; an empty string would set
     * the key to an empty value and leave it visible in {@code incus config show} forever.
     *
     * @param origins who chose each pin in {@code selection}; a namespace missing here is
     *                recorded as unknown rather than guessed
     */
    public static void stamp(IncusClient incus, String instance, Map<String, String> selection,
                             Map<String, AccountOrigin> origins, Map<String, String> current) {
        var updates = new LinkedHashMap<String, Object>(stampUpdates(selection, origins, current));
        if (!updates.isEmpty()) incus.configUpdate(instance, updates);
    }

    /**
     * The config changes {@link #stamp} makes, for a caller folding them into a larger write:
     * a {@code null} value removes the key. A pin and its origin are always written together.
     */
    public static Map<String, String> stampUpdates(Map<String, String> selection,
                                                   Map<String, AccountOrigin> origins,
                                                   Map<String, String> current) {
        var updates = new LinkedHashMap<String, String>();
        current.keySet().forEach(ns -> {
            updates.put(Metadata.accountKey(ns), null);
            updates.put(Metadata.accountOriginKey(ns), null);
        });
        selection.forEach((namespace, account) -> {
            updates.put(Metadata.accountKey(namespace), account);
            var origin = origins.getOrDefault(namespace, AccountOrigin.UNKNOWN).encode();
            updates.put(Metadata.accountOriginKey(namespace), origin.isEmpty() ? null : origin);
        });
        return updates;
    }

    /** Who chose each pin recorded in an instance's {@code config}. */
    public static Map<String, AccountOrigin> originsFromConfig(JsonNode config) {
        var origins = new LinkedHashMap<String, AccountOrigin>();
        config.properties().forEach(entry -> {
            if (!entry.getKey().startsWith(Metadata.ACCOUNT_ORIGIN_PREFIX)) return;
            origins.put(entry.getKey().substring(Metadata.ACCOUNT_ORIGIN_PREFIX.length()),
                    AccountOrigin.decode(entry.getValue().isNull() ? "" : entry.getValue().asText("")));
        });
        return origins;
    }

    /** Who chose each pin on an instance, from its keys without {@link Metadata#PREFIX}. */
    public static Map<String, AccountOrigin> originsFromMetadata(Map<String, String> metadata) {
        var prefix = Metadata.ACCOUNT_ORIGIN_PREFIX.substring(Metadata.PREFIX.length());
        var origins = new LinkedHashMap<String, AccountOrigin>();
        metadata.forEach((key, value) -> {
            if (key.startsWith(prefix)) origins.put(key.substring(prefix.length()), AccountOrigin.decode(value));
        });
        return origins;
    }

    /** Who chose each pin on an instance. */
    public static Map<String, AccountOrigin> readOrigins(IncusClient incus, String instance) {
        var origins = new LinkedHashMap<String, AccountOrigin>();
        incus.configByPrefix(instance, Metadata.ACCOUNT_ORIGIN_PREFIX)
                .forEach((ns, value) -> origins.put(ns, AccountOrigin.decode(value)));
        return origins;
    }

    /** The selection recorded in an instance's {@code config}, as {@link #read} returns it. */
    public static Map<String, String> fromConfig(JsonNode config) {
        var selection = new LinkedHashMap<String, String>();
        config.properties().forEach(entry -> {
            if (!entry.getKey().startsWith(Metadata.ACCOUNT_PREFIX)) return;
            var value = entry.getValue().isNull() ? "" : entry.getValue().asText("");
            if (!value.isBlank()) {
                selection.put(entry.getKey().substring(Metadata.ACCOUNT_PREFIX.length()), value.strip());
            }
        });
        return selection;
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

    /**
     * Config namespaces a tool declares, indexed to the tool that owns each.
     *
     * <p>Built from {@link dev.incusspawn.proxy.ToolProxyResolver#proxyToolSetups}, which is
     * also what the proxy resolves against, so the two cannot disagree about which namespaces
     * exist. Loading the setups separately here would drop that method's feature gate and its
     * project-local rejection, and offer the user a namespace the proxy will never serve.
     *
     * <p>Discovering the setups scans the filesystem, so callers that need both this and
     * {@link #bakedIdentities} should pass the map rather than asking twice.
     */
    public static Map<String, ToolSetup> namespaceSetups(SpawnConfig config) {
        return namespaceSetups(config, new dev.incusspawn.tool.ToolDefLoader());
    }

    /**
     * As {@link #namespaceSetups(SpawnConfig)}, from a loader the caller already holds -- the
     * TUI's, which has read the tool YAMLs once already and would otherwise re-read them, and
     * re-print their warnings over its screen, every time a dialog opens.
     */
    public static Map<String, ToolSetup> namespaceSetups(SpawnConfig config, dev.incusspawn.tool.ToolDefLoader loader) {
        var byNamespace = new LinkedHashMap<String, ToolSetup>();
        dev.incusspawn.proxy.ToolProxyResolver.proxyToolSetups(config, loader)
                .forEach((toolName, setup) -> {
                    var proxyDef = setup.proxy();
                    if (proxyDef == null) return;
                    var namespace = proxyDef.getConfigNamespace();
                    if (!namespace.isBlank()) byNamespace.putIfAbsent(namespace, setup);
                });
        return byNamespace;
    }

    /**
     * The credential namespaces a template's tools spend, down its whole chain, in the order the
     * tools are listed. {@code tools} looks a tool up by name; one it does not know contributes
     * nothing.
     */
    public static java.util.Set<String> templateNamespaces(ImageDef template, Map<String, ImageDef> defs,
                                                          java.util.function.Function<String, ToolSetup> tools) {
        var namespaces = new java.util.LinkedHashSet<String>();
        if (template == null) return namespaces;
        for (var layer : ImageDef.chain(template, defs)) {
            for (var ref : layer.getTools()) {
                var setup = tools.apply(ref.getName());
                if (setup != null) namespaces.addAll(setup.credentialNamespaces());
            }
        }
        return namespaces;
    }

    /** Config namespaces a tool declares. */
    public static List<String> knownNamespaces(SpawnConfig config) {
        return List.copyOf(namespaceSetups(config).keySet());
    }
}
