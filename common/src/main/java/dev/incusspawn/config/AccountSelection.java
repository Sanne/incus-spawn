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
     * The proxy's refusal to serve an instance a credential its build cannot honour: see
     * {@link #servingMismatches}. Distinct from an unknown account, which is a pin gone stale;
     * this is an account that exists but is the wrong kind for the instance.
     */
    public static class UnservableAccountException extends RuntimeException {
        private final String namespace;

        public UnservableAccountException(String namespace, String message) {
            super(message);
            this.namespace = namespace;
        }

        public String namespace() { return namespace; }
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
        return differs(baked, wanted) && !canRebake(setup);
    }

    /**
     * Whether an instance that baked {@code baked} has to be brought in line with an account that
     * bakes {@code wanted}: they differ -- another account, the same one with another credential,
     * or {@link Metadata#ACCOUNT_IDENTITY_NONE} -- and the tool can re-derive. The one test every
     * reconcile uses, at branch time and in a child's build, so the two cannot drift. A namespace
     * that bakes nothing now ({@code wanted} blank) has nothing to re-derive from.
     */
    public static boolean needsRederive(ToolSetup setup, String baked, String wanted) {
        return differs(baked, wanted) && canRebake(setup);
    }

    private static boolean differs(String baked, String wanted) {
        return baked != null && !baked.isBlank() && wanted != null && !wanted.isBlank() && !baked.equals(wanted);
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
     * pinned to -- another account, or the same one holding a different credential -- mapped to
     * the account it should be brought in line with. Each one's tool can
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
        return identityReconcile(config, incus, instance, knownSetups).stale();
    }

    /**
     * What reconciling an instance's baked identities involves.
     *
     * @param stale      namespaces whose stamp differs from the account they now resolve to,
     *                   mapped to that account: re-derive them
     * @param unverified namespaces the instance cannot be trusted on, mapped to their account:
     *                   it predates {@link Metadata#ACCOUNT_IDENTITY_VERIFIED}, so ask the guest
     *                   ({@link ToolSetup#lacksBakedIdentity}) and re-derive what it lacks, then
     *                   set the marker so this is done once
     */
    public record IdentityReconcile(Map<String, String> stale, Map<String, String> unverified) {
        public boolean isEmpty() { return stale.isEmpty() && unverified.isEmpty(); }
    }

    /**
     * What {@link IdentityReconcile reconciling} this instance takes, from one read of it: this
     * runs on every branch and start.
     *
     * <p>An identity stamped {@link Metadata#ACCOUNT_IDENTITY_NONE} -- the tool is there but had
     * no account to derive from when the image was built -- is stale as soon as an account with
     * a credential is configured, like any other change of account. Until then the namespace
     * bakes nothing ({@link #bakedIdentities} leaves it out) and there is nothing to do.
     *
     * <p>An instance without {@link Metadata#ACCOUNT_IDENTITY_VERIFIED} comes from a template an
     * older isx built, whose stamps came from config.yaml rather than the guest: every namespace
     * an account would bake into and whose tool can re-derive is {@code unverified}, stamped or
     * not. Only once the instance is checked does it cost a guest exec, and only for such
     * instances, and only while an account is configured.
     *
     * <p>An airgapped instance has nothing to reconcile: it has no proxy to re-derive through.
     */
    public static IdentityReconcile identityReconcile(SpawnConfig config, IncusClient incus,
                                                      String instance, Map<String, ToolSetup> knownSetups) {
        var stale = new LinkedHashMap<String, String>();
        var unverified = new LinkedHashMap<String, String>();
        var metadata = incus.instanceMetadataOrThrow(instance);
        if (metadata == null) {
            throw new dev.incusspawn.incus.IncusException("Failed to read config from " + instance);
        }
        // No proxy to re-derive through: nothing an airgapped instance's reconcile could do
        // but wait for an address it never gets (BranchFlow skips it for the same reason).
        if (NetworkMode.isAirgapped(metadata)) return new IdentityReconcile(stale, unverified);
        var baked = IncusClient.configByPrefix(metadata, Metadata.ACCOUNT_IDENTITY_PREFIX);
        var verified = metadata.path("config").has(Metadata.ACCOUNT_IDENTITY_VERIFIED);
        if (baked.isEmpty() && verified) return new IdentityReconcile(stale, unverified);
        var setups = knownSetups != null ? knownSetups : namespaceSetups(config);
        var selection = fromConfig(metadata.path("config"));
        bakedIdentities(config, effectiveSelection(selection, setups), setups)
                .forEach((namespace, identity) -> {
                    var setup = setups.get(namespace);
                    if (needsRederive(setup, baked.get(namespace), identity)) {
                        stale.put(namespace, AccountResolver.effectiveAccount(config, namespace, selection.get(namespace)));
                    } else if (!verified && canRebake(setup)) {
                        unverified.put(namespace, AccountResolver.effectiveAccount(config, namespace, selection.get(namespace)));
                    }
                });
        return new IdentityReconcile(stale, unverified);
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
     * {@code to}, and a baked identity that names the account (GitHub's) is renamed with it
     * ({@link ToolSetup#renameBakedIdentity}), so the rename does not read as a change of
     * identity and trigger a needless re-derive.
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
            var renamedIdentity = setup == null ? null
                    : setup.renameBakedIdentity(instanceConfig.path(identityKey).asText(""), from, to);
            if (renamedIdentity != null) updates.put(identityKey, renamedIdentity);
            if (updates.isEmpty()) continue;
            incus.configSetAll(name, updates);
            if (updates.containsKey(pinKey)) repointed.add(name);
        }
        return repointed;
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
     * TUI's, which has read the tool YAMLs once already and would otherwise re-read them every
     * time a dialog opens.
     */
    public static Map<String, ToolSetup> namespaceSetups(SpawnConfig config, dev.incusspawn.tool.ToolDefLoader loader) {
        return byNamespace(dev.incusspawn.proxy.ToolProxyResolver.proxyToolSetups(config, loader));
    }

    /** Tool setups keyed by tool name, re-keyed by the config namespace each one owns. */
    public static Map<String, ToolSetup> byNamespace(Map<String, ToolSetup> toolSetups) {
        var byNamespace = new LinkedHashMap<String, ToolSetup>();
        toolSetups.forEach((toolName, setup) -> {
            var proxyDef = setup.proxy();
            if (proxyDef == null) return;
            var namespace = proxyDef.getConfigNamespace();
            if (!namespace.isBlank()) byNamespace.putIfAbsent(namespace, setup);
        });
        return byNamespace;
    }

    /**
     * Which credential namespaces a request to each intercepted domain spends, from the tools'
     * own proxy declarations -- so a refusal for one credential can be scoped to the domains
     * that use it, rather than cutting the instance off from every service.
     */
    public static Map<String, java.util.Set<String>> namespacesByDomain(Map<String, ToolSetup> toolSetups) {
        var byDomain = new LinkedHashMap<String, java.util.Set<String>>();
        toolSetups.values().forEach(setup -> {
            var proxyDef = setup.proxy();
            if (proxyDef == null) return;
            var namespaces = setup.credentialNamespaces();
            if (namespaces.isEmpty()) return;
            for (var auth : proxyDef.getAuth()) {
                for (var domain : auth.getDomains()) {
                    byDomain.computeIfAbsent(domain, d -> new java.util.LinkedHashSet<>()).addAll(namespaces);
                }
            }
        });
        return byDomain;
    }

    /**
     * The credentials a request to {@code domain} spends, from {@link #namespacesByDomain}:
     * an exact entry, or a {@code *.suffix} one it falls under ({@code api.github.com} under
     * GitHub's {@code *.github.com}).
     */
    public static java.util.Set<String> namespacesForDomain(Map<String, java.util.Set<String>> byDomain,
                                                            String domain) {
        if (domain == null) return java.util.Set.of();
        var exact = byDomain.get(domain);
        var result = new java.util.LinkedHashSet<String>(exact == null ? java.util.Set.of() : exact);
        byDomain.forEach((pattern, namespaces) -> {
            if (pattern.startsWith("*.") && domain.endsWith(pattern.substring(1))) result.addAll(namespaces);
        });
        return result;
    }

    /**
     * Credentials an instance cannot be served as things stand: the account it would get --
     * its pin, or the global default it follows -- needs something the instance was not built
     * with (a Claude auth mode), and the tool cannot bring a built instance in line. Mapped to
     * a message saying why and how to fix it.
     *
     * <p>{@code isx account set} refuses such a move while the user is choosing, but changing
     * the global default moves every unpinned instance at once and nothing is there to refuse
     * it. The proxy asks this instead, and fails those requests closed -- the same rule as a pin
     * to an account that is gone -- rather than handing the instance a credential its
     * environment does not match.
     *
     * @param setups          tool setups keyed by namespace ({@link #byNamespace})
     * @param pins            the instance's pins
     * @param bakedIdentities its {@code account-identity} stamps
     */
    public static Map<String, String> servingMismatches(SpawnConfig config, Map<String, ToolSetup> setups,
                                                        String instance, Map<String, String> pins,
                                                        Map<String, String> bakedIdentities) {
        var mismatches = new LinkedHashMap<String, String>();
        bakedIdentities.forEach((namespace, baked) -> {
            var setup = setups.get(namespace);
            if (setup == null || baked == null || baked.isBlank() || canRebake(setup)) return;
            var pin = pins.get(namespace);
            String account;
            String wanted;
            try {
                account = AccountResolver.effectiveAccount(config, namespace, pin);
                if (account.isEmpty()) return;
                wanted = setup.bakedAccountIdentity(config, account);
            } catch (AccountResolver.UnknownAccountException e) {
                return; // a pin to a missing account is refused on its own terms
            }
            if (!cannotHonour(setup, baked, wanted)) return;
            var compatible = new java.util.ArrayList<String>();
            for (var candidate : AccountResolver.usableAccountNames(config, namespace)) {
                try {
                    if (baked.equals(setup.bakedAccountIdentity(config, candidate))) compatible.add(candidate);
                } catch (AccountResolver.UnknownAccountException ignored) {
                }
            }
            var how = pin == null || pin.isBlank()
                    ? "it follows the global default (" + namespace + ".default in config.yaml), which is now '"
                            + account + "', an account of type '" + wanted + "'"
                    : "it is pinned to '" + account + "', an account of type '" + wanted + "'";
            var fix = compatible.isEmpty()
                    ? "Configure a '" + baked + "' account for it, or branch from a template built for '" + wanted + "'."
                    : "Pin it to one it was built for: isx account set " + instance + " " + namespace + "="
                            + compatible.get(0)
                            + (compatible.size() > 1 ? " (or " + String.join(", ", compatible.subList(1, compatible.size())) + ")" : "")
                            + ".";
            mismatches.put(namespace, "Instance '" + instance + "' was built for " + namespace + " '" + baked
                    + "', but " + how + ". " + fix);
        });
        return mismatches;
    }

    /**
     * The credential namespaces a template's tools spend, down its whole chain, in the order the
     * tools are listed. {@code tools} looks a tool up by name; one it does not know contributes
     * nothing.
     */
    public static java.util.Set<String> templateNamespaces(ImageDef template, Map<String, ImageDef> defs,
                                                          java.util.function.Function<String, ToolSetup> tools) {
        var namespaces = new java.util.LinkedHashSet<String>();
        for (var name : chainToolNames(template, defs)) {
            var setup = tools.apply(name);
            if (setup != null) namespaces.addAll(setup.credentialNamespaces());
        }
        return namespaces;
    }

    /** The tools a template's chain lists, root first, each once. */
    private static java.util.Set<String> chainToolNames(ImageDef template, Map<String, ImageDef> defs) {
        var names = new java.util.LinkedHashSet<String>();
        if (template == null) return names;
        for (var layer : ImageDef.chain(template, defs)) {
            for (var ref : layer.getTools()) names.add(ref.getName());
        }
        return names;
    }

    /**
     * The namespaces a tool in the template's chain owns and {@linkplain
     * ToolSetup#canRebakeForAccount can re-derive} what it bakes for -- GitHub where gh itself is
     * installed, not where a tool merely borrows its credential (Copilot), which derives no git
     * identity. Where a build that had no account to derive from stamps
     * {@link Metadata#ACCOUNT_IDENTITY_NONE}, for a later account to be reconciled into
     * ({@link dev.incusspawn.lifecycle.BuildAccounts#identityStamps}).
     *
     * @param allTools every tool by name, to follow {@code requires} and find each one's namespace
     * @param setups   {@link #namespaceSetups}: a namespace it does not know is left out
     */
    public static java.util.Set<String> rederivableNamespaces(ImageDef template, Map<String, ImageDef> defs,
                                                              Map<String, ToolSetup> allTools,
                                                              Map<String, ToolSetup> setups) {
        var namespaces = new java.util.LinkedHashSet<String>();
        // With what each tool requires: a tool that pulls gh in installs it as surely as a list.
        var installed = new java.util.LinkedHashSet<String>();
        for (var name : chainToolNames(template, defs)) ToolSetup.addWithRequires(name, allTools, installed);
        for (var name : installed) {
            var setup = allTools.get(name);
            if (!canRebake(setup) || setup.proxy() == null) continue;
            var namespace = setup.proxy().getConfigNamespace();
            // Only a namespace namespaceSetups() knows: one it gates out is never
            // reconciled, so marking it would only leave a stamp nothing clears.
            if (namespace != null && !namespace.isBlank() && canRebake(setups.get(namespace))) {
                namespaces.add(namespace);
            }
        }
        return namespaces;
    }

    /** Config namespaces a tool declares. */
    public static List<String> knownNamespaces(SpawnConfig config) {
        return List.copyOf(namespaceSetups(config).keySet());
    }
}
