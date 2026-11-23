package dev.incusspawn.proxy;


import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.HostRoutes;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolProxyResolver {

    private static final Pattern REF_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private ToolProxyResolver() {}

    /**
     * Resolve every tool's proxy entries against the configured default accounts.
     *
     * <p>A namespace's selection is passed through {@code accountsByNamespace} on the
     * overloads that take one: a namespace absent from that map falls back to its configured
     * {@code default}, and a name that is not configured raises
     * {@link dev.incusspawn.config.AccountResolver.UnknownAccountException} rather than serving
     * someone else's credential.
     */
    public static List<ResolvedToolProxy> resolve(SpawnConfig config) {
        return resolve(config, proxyToolSetups(config), Map.of());
    }

    /**
     * Load and filter the tool setups that contribute proxy entries.
     *
     * <p>Separated out because it <strong>scans the filesystem</strong> for tool YAMLs. The proxy
     * resolves a per-instance account selection on the event loop, which must never do that, so
     * it loads this once per config reload and hands the result to
     * {@link #resolve(SpawnConfig, Map, Map)} instead of calling
     * {@link #resolve(SpawnConfig, Map, Map)} per request.
     */
    public static Map<String, ToolSetup> proxyToolSetups(SpawnConfig config) {
        return proxyToolSetups(config, new ToolDefLoader(config.getSearchPaths()));
    }

    /** As {@link #proxyToolSetups(SpawnConfig)}, from a loader the caller already holds. */
    public static Map<String, ToolSetup> proxyToolSetups(SpawnConfig config, ToolDefLoader loader) {
        var filtered = filterByFeatureGate(config, loader.allToolSetups());
        rejectProjectLocalProxy(loader.projectLocalToolNames(), filtered);
        return filtered;
    }

    public static List<ResolvedToolProxy> resolve(SpawnConfig config, Map<String, ToolSetup> toolSetups) {
        return resolve(config, toolSetups, Map.of());
    }

    public static List<ResolvedToolProxy> resolve(SpawnConfig config,
                                                  Map<String, ToolSetup> toolSetups,
                                                  Map<String, String> accountsByNamespace) {
        return resolve(config.tree(), toolSetups, accountsByNamespace);
    }

    /**
     * As above, against a config tree the caller already serialized. Serializing a whole
     * {@link SpawnConfig} is not free, and {@link #resolveAcrossAccounts} resolves once per
     * configured account.
     */
    public static List<ResolvedToolProxy> resolve(JqValue configTree,
                                                  Map<String, ToolSetup> toolSetups,
                                                  Map<String, String> accountsByNamespace) {
        var result = new ArrayList<ResolvedToolProxy>();
        var namespaces = new java.util.HashMap<String, String>();
        // Built only when some entry configures its domains
        java.util.function.Predicate<String> taken = null;
        // Each configured domain resolved so far, with the tool that took it first
        var configuredBy = new java.util.HashMap<String, String>();

        for (var toolEntry : toolSetups.entrySet()) {
            var toolName = toolEntry.getKey();
            var tool = toolEntry.getValue();
            var proxyDef = tool.proxy();
            if (proxyDef == null) continue;

            var ns = proxyDef.getConfigNamespace();
            if (!ns.isBlank()) {
                var existing = namespaces.putIfAbsent(ns, toolName);
                if (existing != null) {
                    // Once per channel: every selection resolved re-detects it (#891).
                    dev.incusspawn.Warnings.warn("tool '" + toolName + "' shares config-namespace '"
                            + ns + "' with tool '" + existing + "' — skipping");
                    continue;
                }
            }

            var allConfigValues = resolveConfiguration(proxyDef, configTree, toolSetups, accountsByNamespace);
            // Per list, which item each host was given to: no host carries two items' credentials
            var itemHosts = new java.util.HashMap<String, Map<String, String>>();

            for (var authEntry : proxyDef.getAuth()) {
                if (authEntry.getDomains().isEmpty()) continue;
                if (authEntry.getType() == null) continue;
                if (authEntry.getForEach() != null) {
                    if (taken == null) taken = takenDomains(toolSetups);
                    var servedElsewhere = taken.or(served -> claimedByOther(configuredBy, toolName, served));
                    for (var resolved : resolveEach(toolName, proxyDef, authEntry, allConfigValues,
                            configTree, toolSetups, accountsByNamespace, servedElsewhere,
                            itemHosts.computeIfAbsent(authEntry.getForEach(), k -> new java.util.HashMap<>()))) {
                        configuredBy.putIfAbsent(resolved.domain(), toolName);
                        result.add(resolved);
                    }
                    continue;
                }

                boolean isAnthropic = "anthropic".equals(authEntry.getType());
                var referencedKeys = extractReferencedKeys(authEntry);
                boolean allReferenced = referencedKeys.stream().allMatch(allConfigValues::containsKey);

                if (isAnthropic ? allConfigValues.isEmpty() : !allReferenced) continue;

                var spends = namespacesOf(proxyDef, authEntry);
                if (taken == null && authEntry.getDomains().stream().anyMatch(ToolProxyResolver::isConfiguredDomain)) {
                    taken = takenDomains(toolSetups);
                }
                var servedElsewhere = taken == null ? null : taken.or(served -> claimedByOther(configuredBy, toolName, served));
                for (var domain : resolveDomains(toolName, authEntry.getDomains(), domainValues(proxyDef, allConfigValues), servedElsewhere)) {
                    if (!authEntry.getDomains().contains(domain)) {
                        configuredBy.putIfAbsent(domain, toolName);
                    }
                    result.add(new ResolvedToolProxy(toolName, domain, authEntry, Map.copyOf(allConfigValues), spends));
                }
            }
        }
        return result;
    }

    /** The key a list item's label is referenced by: {@code ${<list>.label}}. */
    public static final String LABEL_FIELD = "label";

    /** What a list item's label may be: a name an MCP server may take after its tool's prefix, and no config path's separator. */
    private static final Pattern ITEM_LABEL = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");

    /** Whether {@code label} may name a list item ({@link #ITEM_LABEL}). */
    public static boolean isItemLabel(String label) {
        return label != null && ITEM_LABEL.matcher(label).matches();
    }

    /**
     * One item of a list entry ({@link ToolDef.ConfigEntry#isList}): its label, and its label and
     * fields keyed as a definition references them ({@code servers.label}, {@code servers.host}).
     */
    public record ListItem(String label, Map<String, String> values) {}

    /**
     * The items {@code listKey}'s list holds for the account selection, in file order: the
     * account's own list when it has one, else the flat one. An item whose label is not a plain
     * lowercase name is dropped with a warning, since an MCP server is named after it and a config
     * path cannot hold a dot in it. A field the item leaves blank is absent from its values.
     */
    public static List<ListItem> listItems(String toolName, ToolDef.ProxyDef proxyDef, String listKey,
                                           JqValue configTree, Map<String, ToolSetup> toolSetups,
                                           Map<String, String> accountsByNamespace) {
        var list = proxyDef.getConfiguration().get(listKey);
        if (list == null || !list.isList()) return List.of();
        var node = listNode(proxyDef, list, configTree, toolSetups, accountsByNamespace);
        if (!(node instanceof io.hyperfoil.tools.jjq.value.JqObject items)) return List.of();
        var result = new ArrayList<ListItem>();
        for (var label : items.keys()) {
            if (!ITEM_LABEL.matcher(label).matches()) {
                // Never the label itself: a key typed by hand may be a pasted credential
                dev.incusspawn.Warnings.warn(dev.incusspawn.util.OutputFormat.oneLine("tool '" + toolName
                        + "': a " + listKey + " item's label is not a lowercase name of letters, digits,"
                        + " '-' and '_' -- skipping it"));
                continue;
            }
            var item = items.getField(label);
            if (!(item instanceof io.hyperfoil.tools.jjq.value.JqObject)) continue;
            var values = new LinkedHashMap<String, String>();
            values.put(listKey + "." + LABEL_FIELD, label);
            list.getForEach().forEach((field, def) -> {
                if (def == null) return;
                var value = AccountResolver.navigate(item, def.fieldPath(field)).strip();
                if (!value.isEmpty()) values.put(listKey + "." + field, value);
            });
            result.add(new ListItem(label, Map.copyOf(values)));
        }
        return result;
    }

    /** The node a list entry resolves to: the selected account's, else the flat one, else null. */
    private static JqValue listNode(ToolDef.ProxyDef proxyDef, ToolDef.ConfigEntry list, JqValue configTree,
                                    Map<String, ToolSetup> toolSetups, Map<String, String> accountsByNamespace) {
        var accountName = accountFor(proxyDef, list, configTree, toolSetups, accountsByNamespace);
        // The parent instance holds the credentials, and this host has no list of its servers
        if (AccountResolver.INHERIT.equals(accountName)) return null;
        if (!accountName.isBlank()) {
            var node = nodeAt(configTree, proxyDef.accountConfigPath(list, accountName));
            if (node != null) return node;
        }
        return nodeAt(configTree, proxyDef.fullConfigPath(list));
    }

    /** The object at dotted {@code path}, or null when there is none or it holds no item. */
    private static JqValue nodeAt(JqValue tree, String path) {
        if (path.isBlank()) return null;
        var node = tree;
        for (var segment : path.split("\\.")) {
            if (!(node instanceof io.hyperfoil.tools.jjq.value.JqObject object) || !object.keys().contains(segment)) return null;
            node = object.getField(segment);
        }
        return node instanceof io.hyperfoil.tools.jjq.value.JqObject object && object.size() > 0 ? node : null;
    }

    /**
     * The field of a list entry {@code ref} names ({@code servers.host}), or null when it names
     * none: a plain key, a list's label, or an unknown name.
     */
    public static ToolDef.ListField listField(ToolDef.ProxyDef proxyDef, String ref) {
        var dot = ref.indexOf('.');
        if (dot <= 0) return null;
        var list = proxyDef.getConfiguration().get(ref.substring(0, dot));
        return list == null || !list.isList() ? null : list.getForEach().get(ref.substring(dot + 1));
    }

    /** Whether {@code ref} names {@code listKey}'s label or one of its fields. */
    public static boolean isListRef(ToolDef.ProxyDef proxyDef, String listKey, String ref) {
        if (!ref.startsWith(listKey + ".")) return false;
        var list = proxyDef.getConfiguration().get(listKey);
        if (list == null || !list.isList()) return false;
        var field = ref.substring(listKey.length() + 1);
        return LABEL_FIELD.equals(field) || list.getForEach().containsKey(field);
    }

    /**
     * {@code auth} repeated for each item of its {@code for-each} list: each entry carries the
     * tool's values and that item's alone, so its credential is the item's and its domain the
     * item's host, checked like every configured domain. A host another item of the list already
     * took is dropped, so a host never carries two items' credentials.
     *
     * @param itemHosts the hosts given out for this list so far, each with the label it went to
     */
    private static List<ResolvedToolProxy> resolveEach(String toolName, ToolDef.ProxyDef proxyDef,
            ToolDef.AuthDef auth, Map<String, String> toolValues, JqValue configTree,
            Map<String, ToolSetup> toolSetups, Map<String, String> accountsByNamespace,
            java.util.function.Predicate<String> servedElsewhere, Map<String, String> itemHosts) {
        var result = new ArrayList<ResolvedToolProxy>();
        var listKey = auth.getForEach();
        var referenced = new ArrayList<>(extractReferencedKeys(auth));
        referenced.addAll(extractDomainKeys(auth));
        var spends = namespacesOf(proxyDef, auth);
        for (var item : listItems(toolName, proxyDef, listKey, configTree, toolSetups, accountsByNamespace)) {
            var values = new LinkedHashMap<>(toolValues);
            values.putAll(item.values());
            var missing = referenced.stream().filter(key -> !values.containsKey(key)).distinct().toList();
            if (!missing.isEmpty()) {
                dev.incusspawn.Warnings.warn(dev.incusspawn.util.OutputFormat.oneLine("tool '" + toolName
                        + "': " + listKey + " item '" + item.label() + "' has no " + String.join(", ", missing)
                        + " -- skipping it"));
                continue;
            }
            var label = item.label();
            // A lone reference would split a comma: an item names one host, never several
            if (extractDomainKeys(auth).stream().anyMatch(key -> values.get(key).contains(","))) {
                dev.incusspawn.Warnings.warn(dev.incusspawn.util.OutputFormat.oneLine("tool '" + toolName
                        + "': " + listKey + " item '" + label + "' names more than one host -- skipping it"));
                continue;
            }
            java.util.function.Predicate<String> taken = servedElsewhere.or(host -> {
                var owner = itemHosts.get(host);
                return owner != null && !owner.equals(label);
            });
            for (var domain : resolveDomains(toolName, auth.getDomains(), domainValues(proxyDef, values), taken)) {
                itemHosts.putIfAbsent(domain, label);
                result.add(new ResolvedToolProxy(toolName, domain, auth, Map.copyOf(values), spends));
            }
        }
        return result;
    }

    /**
     * The items of {@code listKey} the proxy serves for {@code toolName} at the host
     * {@code hostRef} names, from {@code resolved}: what an {@code mcp:} entry repeated over the
     * list is registered for, so an agent is never given a server whose host the proxy dropped.
     * Each item's values are its label and non-secret fields, the host as the proxy serves it.
     */
    public static List<ListItem> servedItems(ToolDef.ProxyDef proxyDef, String toolName, String listKey,
                                             String hostRef, Collection<ResolvedToolProxy> resolved) {
        var byLabel = new LinkedHashMap<String, ListItem>();
        for (var entry : resolved) {
            var auth = entry.auth();
            if (!entry.toolName().equals(toolName) || auth == null || !listKey.equals(auth.getForEach())) continue;
            if (!auth.getDomains().equals(List.of("${" + hostRef + "}"))) continue;
            var label = entry.configValues().get(listKey + "." + LABEL_FIELD);
            if (label == null || byLabel.containsKey(label)) continue;
            var values = new LinkedHashMap<String, String>();
            entry.configValues().forEach((key, value) -> {
                if (!isListRef(proxyDef, listKey, key)) return;
                var field = listField(proxyDef, key);
                if (field == null || !field.isSecret()) values.put(key, value);
            });
            values.put(hostRef, entry.domain());
            byLabel.put(label, new ListItem(label, Map.copyOf(values)));
        }
        return List.copyOf(byLabel.values());
    }

    /**
     * The domains the proxy intercepts beyond its built-in ones: the tool domains any
     * configured account makes resolvable, as {@link #resolveAcrossAccounts} decides for the
     * proxy itself, the names of the {@link #hostRoutes host routes}, and ChatGPT's hosts while a
     * ChatGPT account is configured ({@link ChatGptAccounts}). Anything that rewrites
     * bridge DNS from this set (the TUI's auto-heal, {@code isx doctor}) must not use the
     * default account's narrower set, or it drops a domain only a named account can serve
     * and its requests go upstream without credentials until the proxy reloads; nor leave the
     * routes out, which would stop instances resolving them.
     */
    public static Set<String> resolvedDomains(SpawnConfig config) {
        var acrossAccounts = resolveAcrossAccounts(config, proxyToolSetups(config));
        var domains = acrossAccounts.stream()
                .map(ResolvedToolProxy::domain)
                .collect(Collectors.toCollection(java.util.HashSet::new));
        // The proxy reports what it drops; a CLI writer only needs the names
        domains.addAll(hostRoutes(config, acrossAccounts, warning -> {}).keySet());
        domains.addAll(handlerDomains(config, acrossAccounts));
        return Set.copyOf(domains);
    }

    /**
     * Every domain a built-in handler serves under {@code config}: {@link #handlerDomains(JqValue)}'s,
     * and those of the Maven repositories {@code maven-repositories:} declares for caching (#2312),
     * which may claim none of isx's own. The one place the proxy and {@link #resolvedDomains} take
     * them from, so a configured repository is intercepted, its certificate minted and its name in
     * the bridge DNS block, on a reload too. With them, the {@code confirmed-cache:} domains the
     * proxy serves ({@link #servedConfirmedCacheDomains}, against {@code acrossAccounts}), so a
     * domain it ignores is not intercepted either.
     */
    public static Set<String> handlerDomains(SpawnConfig config, Collection<ResolvedToolProxy> acrossAccounts) {
        var tree = config.tree();
        var builtIn = handlerDomains(tree);
        var repositories = config.mavenRepositories().domains(repositoryDomainsTaken(builtIn));
        var confirmed = servedConfirmedCacheDomains(config, acrossAccounts, warning -> {});
        if (repositories.isEmpty() && confirmed.isEmpty()) return builtIn;
        var all = new java.util.HashSet<>(builtIn);
        all.addAll(repositories);
        confirmed.forEach(domain -> all.add(dev.incusspawn.config.ConfirmedCache.interceptedName(domain)));
        return Set.copyOf(all);
    }

    /**
     * The domains of {@code confirmed-cache:} (#2518) that claim none isx serves otherwise: its
     * built-in and handler domains and the {@code maven-repositories:} ones. Exact names or
     * {@code *.} wildcards; {@link #handlerDomains(SpawnConfig, Collection)} intercepts each served one's name.
     */
    public static List<String> confirmedCacheDomains(SpawnConfig config, Consumer<String> warn) {
        var repositoryTaken = repositoryDomainsTaken(handlerDomains(config.tree()));
        var taken = new java.util.HashSet<>(repositoryTaken);
        taken.addAll(config.mavenRepositories().domains(repositoryTaken));
        return config.confirmedCache().domains(taken, warn);
    }

    /**
     * {@link #confirmedCacheDomains}, less those that overlap a domain a tool injects a credential
     * at under any account ({@code acrossAccounts}): the cache takes a domain's requests before
     * injection would, so they would go out with the guest's placeholder. What the proxy caches.
     */
    public static List<String> servedConfirmedCacheDomains(SpawnConfig config,
            Collection<ResolvedToolProxy> acrossAccounts, Consumer<String> warn) {
        var injected = acrossAccounts.stream().map(ResolvedToolProxy::domain).collect(Collectors.toSet());
        return dev.incusspawn.config.ConfirmedCache.without(confirmedCacheDomains(config, warn), injected,
                "a tool injects a credential at", warn);
    }

    /** The domains a {@code maven-repositories:} entry may not claim, beside {@code handlerDomains}' own. */
    public static Set<String> repositoryDomainsTaken(Set<String> handlerDomains) {
        var taken = new java.util.HashSet<>(dev.incusspawn.config.MavenRepositories.builtInTaken());
        taken.addAll(handlerDomains);
        taken.addAll(ChatGptAccounts.DOMAINS);
        taken.addAll(GoogleAccounts.DOMAINS);
        return Set.copyOf(taken);
    }

    /**
     * The domains a built-in handler serves without a tool proxy, as {@code tree} configures them:
     * ChatGPT's hosts while an {@code openai} account is {@code type: chatgpt} (#1332), and
     * Google's while a {@code gemini} account is {@code type: google} (#2443). The one
     * place the proxy and {@link #resolvedDomains} take them from. A host route cannot clash with
     * them: its name must lie under {@code .internal}.
     */
    public static Set<String> handlerDomains(JqValue tree) {
        var chatGpt = ChatGptAccounts.enabledBy(tree);
        var google = GoogleAccounts.enabledBy(tree);
        if (chatGpt && google) {
            var all = new java.util.HashSet<>(ChatGptAccounts.DOMAINS);
            all.addAll(GoogleAccounts.DOMAINS);
            return Set.copyOf(all);
        }
        return chatGpt ? ChatGptAccounts.DOMAINS : google ? GoogleAccounts.DOMAINS : Set.of();
    }

    /**
     * The routes of {@code host-routes:} the proxy serves (#936): those that pass
     * {@link HostRoutes#routes}' checks against every domain it intercepts otherwise, built-in
     * ones and {@code acrossAccounts}' alike. The one place both the proxy and
     * {@link #resolvedDomains} decide it, so the bridge DNS block and the proxy agree.
     */
    public static Map<String, HostRoutes.Route> hostRoutes(SpawnConfig config,
            Collection<ResolvedToolProxy> acrossAccounts, Consumer<String> warn) {
        var toolDomains = acrossAccounts.stream().map(ResolvedToolProxy::domain).collect(Collectors.toSet());
        return config.hostRoutes().routes(ProxyConfig.interceptedDomains(toolDomains), warn);
    }

    /**
     * Every entry that any configured account could make resolvable, not just the default's.
     *
     * <p>Which domains are intercepted is decided once, globally -- it drives certificate
     * minting and the bridge DNS overrides, neither of which can vary per caller. Resolving
     * that from the default account alone would drop a tool whose credential only exists under
     * a named account (a {@code github.default} with no token beside a {@code github.accounts
     * .acme} that has one): the domain would not be intercepted at all, so a pinned instance's
     * request would be relayed straight through with no credential injected.
     *
     * <p>Credentials are still resolved per caller; this is only for the domain set.
     */
    public static List<ResolvedToolProxy> resolveAcrossAccounts(
            SpawnConfig config, Map<String, ToolSetup> toolSetups) {
        var configTree = config.tree();
        return resolveAcrossAccounts(configTree, toolSetups, resolve(configTree, toolSetups, Map.of()),
                InheritedProof.nested());
    }

    /**
     * As above, against a config tree the caller already serialized and the default
     * selection's entries the caller already resolved from it, so neither is done twice.
     *
     * @param nested whether this machine is an isx instance ({@link InheritedProof#nested}): then
     *               the domains whose credential an instance can inherit are added
     */
    public static List<ResolvedToolProxy> resolveAcrossAccounts(
            JqValue configTree, Map<String, ToolSetup> toolSetups, List<ResolvedToolProxy> defaults,
            boolean nested) {
        var seen = new LinkedHashMap<String, ResolvedToolProxy>();
        for (var resolved : defaults) {
            seen.putIfAbsent(resolved.toolName() + "\t" + resolved.domain(), resolved);
        }
        var selections = new ArrayList<Map<String, String>>();
        for (var tool : toolSetups.values()) {
            var proxyDef = tool.proxy();
            if (proxyDef == null) continue;
            // Per entry, not per tool: a tool that borrows another namespace's credential
            // declares no namespace of its own, and its domains must still be intercepted
            // when only that other namespace's named account carries the token.
            for (var configDef : proxyDef.getConfiguration().values()) {
                var namespace = proxyDef.namespaceOf(configDef);
                if (namespace.isBlank()) continue;
                // Usable accounts only: pinning an incomplete one raises, and one half-configured
                // account must not stop the proxy starting or reloading for all the others.
                var shape = AccountResolver.shapeOf(toolSetups, namespace);
                for (var account : AccountResolver.usableAccountNames(configTree, namespace, shape)) {
                    selections.add(Map.of(namespace, account));
                }
            }
        }
        for (var selection : selections) {
            for (var resolved : resolve(configTree, toolSetups, selection)) {
                seen.putIfAbsent(resolved.toolName() + "\t" + resolved.domain(), resolved);
            }
        }
        if (nested) {
            for (var resolved : inheritable(toolSetups)) {
                seen.putIfAbsent(resolved.toolName() + "\t" + resolved.domain(), resolved);
            }
        }
        return List.copyOf(seen.values());
    }

    /**
     * Inside an isx instance, the domains of every tool whose credential can be inherited from it
     * -- one that declares a placeholder, so the parent holds a proof for it -- with no credential:
     * an instance pinned to {@link AccountResolver#INHERIT} has its proofs swapped only on a
     * domain the proxy intercepts, and a pin is not known when the domains are decided.
     */
    private static List<ResolvedToolProxy> inheritable(Map<String, ToolSetup> toolSetups) {
        var result = new ArrayList<ResolvedToolProxy>();
        toolSetups.forEach((toolName, tool) -> {
            var proxyDef = tool.proxy();
            if (proxyDef == null || tool.placeholders().isEmpty()) return;
            for (var auth : proxyDef.getAuth()) {
                if (auth.getType() == null || auth.getForEach() != null) continue;
                var spends = namespacesOf(proxyDef, auth);
                for (var domain : auth.getDomains()) {
                    if (!domain.isBlank() && !isConfiguredDomain(domain)) {
                        result.add(new ResolvedToolProxy(toolName, domain, auth, Map.of(), spends));
                    }
                }
            }
        });
        return result;
    }

    /**
     * The credential namespaces {@code auth} spends: those of the configuration it references.
     * A request must prove each of them before the entry is injected.
     */
    public static java.util.Set<String> namespacesOf(ToolDef.ProxyDef proxy, ToolDef.AuthDef auth) {
        var namespaces = new java.util.LinkedHashSet<String>();
        var keys = extractReferencedKeys(auth);
        // Anthropic's carrier varies with the guest's mode, independently of this host's.
        if ("anthropic".equals(auth.getType())) keys = List.copyOf(proxy.getConfiguration().keySet());
        for (var key : keys) {
            var config = entryFor(proxy, key);
            if (config != null) namespaces.add(proxy.namespaceOf(config));
        }
        return namespaces;
    }

    /** The configuration entry {@code ref} reads: its own, or for a list's label or field, the list's. */
    private static ToolDef.ConfigEntry entryFor(ToolDef.ProxyDef proxy, String ref) {
        var entry = proxy.getConfiguration().get(ref);
        if (entry != null) return entry;
        var dot = ref.indexOf('.');
        if (dot <= 0) return null;
        var list = proxy.getConfiguration().get(ref.substring(0, dot));
        return list != null && isListRef(proxy, ref.substring(0, dot), ref) ? list : null;
    }

    /** A configuration entry of {@code toolName} that resolves to nothing, in {@code namespace} or {@code ""}. */
    public record UnresolvedToolProxy(String toolName, String configKey, String namespace) {}

    /**
     * Find tool proxy configuration entries that could not be resolved.
     * Excludes {@code type: anthropic} entries (those use relaxed resolution).
     */
    public static List<UnresolvedToolProxy> findUnresolved(JqValue configTree, Map<String, ToolSetup> toolSetups) {
        var result = new ArrayList<UnresolvedToolProxy>();

        for (var toolEntry : toolSetups.entrySet()) {
            var toolName = toolEntry.getKey();
            var tool = toolEntry.getValue();
            var proxyDef = tool.proxy();
            if (proxyDef == null) continue;

            if (!servesNonAnthropicAuth(proxyDef)) continue;

            for (var configEntry : proxyDef.getConfiguration().entrySet()) {
                var configKey = configEntry.getKey();
                var configDef = configEntry.getValue();
                if (configDef.isConfirm()) continue;
                // Map.of() means "no pin", so each entry resolves against its namespace's
                // default -- a namespace on the accounts layout is not reported as missing
                // its credential just because the flat field is empty.
                var value = configDef.isList()
                        ? (listItems(toolName, proxyDef, configKey, configTree, toolSetups, Map.of()).isEmpty() ? "" : "items")
                        : resolveConfigValue(proxyDef, configDef, configTree, toolSetups, Map.of());
                if ((value == null || value.isBlank())
                        && !heldOutsideConfig(proxyDef, configKey, configTree, toolSetups, Map.of())) {
                    result.add(new UnresolvedToolProxy(toolName, configKey, proxyDef.namespaceOf(configDef)));
                }
            }
        }
        return result;
    }

    /**
     * A configuration entry some tool needs that resolves to nothing; {@code path} is its flat
     * config path, {@code namespace} the credential namespace it belongs to, or {@code ""}, and
     * {@code tool} the tool whose proxy declares it.
     */
    public record MissingSecret(String path, String description, String namespace, String tool) {}

    /**
     * Whether the generic resolution applies: {@code type: anthropic} is served by MitmProxy's own
     * Claude logic from a typed account, not from these entries.
     */
    private static boolean servesNonAnthropicAuth(ToolDef.ProxyDef proxyDef) {
        return proxyDef.getAuth().stream()
                .anyMatch(a -> a.getType() != null && !"anthropic".equals(a.getType()));
    }

    /**
     * What {@code toolNames} would be missing for the given account selection, judged as
     * {@link #resolve} judges it: an auth entry is served once every key it references resolves,
     * and a tool is usable while any one of its entries is. For a tool none of whose entries
     * would be served, the unresolved keys they reference are reported -- once each, since tools
     * may share one (Copilot borrows {@code github.token}). An entry whose config path is
     * malformed ({@link dev.incusspawn.config.SecretRegistry#isNavigable}) is not asked for:
     * nobody could supply it. Tools absent from {@code toolSetups}, and those served only by
     * {@code type: anthropic}, are skipped: Claude's credential is a typed account. Neither is a
     * key whose namespace is {@link AccountResolver#INHERIT inherited}: it is the parent
     * instance's, and whether there is one is the caller's question.
     *
     * @param toolSetups the tools the proxy serves ({@link #proxyToolSetups})
     */
    public static List<MissingSecret> missingSecrets(JqValue configTree, Map<String, ToolSetup> toolSetups,
                                                     java.util.Collection<String> toolNames,
                                                     Map<String, String> accountsByNamespace) {
        var result = new LinkedHashMap<String, MissingSecret>();
        for (var toolName : toolNames) {
            var tool = toolSetups.get(toolName);
            var proxyDef = tool == null ? null : tool.proxy();
            if (proxyDef == null || !servesNonAnthropicAuth(proxyDef)) continue;

            var resolved = resolveConfiguration(proxyDef, configTree, toolSetups, accountsByNamespace);
            var unsupplied = new java.util.LinkedHashSet<String>();
            boolean anyServed = false;
            for (var auth : proxyDef.getAuth()) {
                if (auth.getDomains().isEmpty()) continue;
                if (auth.getType() == null || "anthropic".equals(auth.getType())) continue;
                var keys = new ArrayList<>(extractReferencedKeys(auth));
                if (auth.getForEach() != null) {
                    // A list serves once the proxy serves one of its items, its host kept; else the list is missing
                    var listKey = auth.getForEach();
                    var anyItem = resolve(configTree, toolSetups, accountsByNamespace).stream()
                            .anyMatch(r -> r.toolName().equals(toolName) && r.auth() == auth);
                    keys.removeIf(key -> isListRef(proxyDef, listKey, key));
                    if (!anyItem) keys.add(listKey);
                } else if (auth.getDomains().stream().noneMatch(domain -> {
                    var refs = new ArrayList<String>();
                    extractRefs(domain, refs);
                    return refs.stream().allMatch(resolved::containsKey);
                })) {
                    keys.addAll(extractDomainKeys(auth));
                }
                var missing = keys.stream().distinct()
                        .filter(key -> !resolved.containsKey(key) && suppliable(proxyDef, key)
                                && !inherited(proxyDef, key, configTree, accountsByNamespace)
                                && !heldOutsideConfig(proxyDef, key, configTree, toolSetups, accountsByNamespace))
                        .toList();
                if (missing.isEmpty()) {
                    anyServed = true;
                    break;
                }
                unsupplied.addAll(missing);
            }
            if (anyServed) continue;
            for (var key : unsupplied) {
                var entry = proxyDef.getConfiguration().get(key);
                var path = proxyDef.fullConfigPath(entry);
                result.putIfAbsent(path, new MissingSecret(path, entry.getDescription(), proxyDef.namespaceOf(entry), toolName));
            }
        }
        return List.copyOf(result.values());
    }

    /**
     * Whether the account {@code key}'s namespace resolves to holds its credential outside
     * {@code config.yaml} ({@link AccountShape#credentialOutsideConfig}): a Google sign-in (#2443)
     * or a ChatGPT session (#1332) the proxy keeps on the host, which no key here can be missing
     * for; a session gone fails closed where it is served.
     */
    private static boolean heldOutsideConfig(ToolDef.ProxyDef proxyDef, String key, JqValue configTree,
                                             Map<String, ToolSetup> toolSetups,
                                             Map<String, String> accountsByNamespace) {
        var entry = proxyDef.getConfiguration().get(key);
        var namespace = entry == null ? "" : proxyDef.namespaceOf(entry);
        if (namespace.isBlank()) return false;
        var shape = AccountResolver.shapeOf(toolSetups, namespace);
        String account;
        try {
            account = AccountResolver.effectiveAccount(configTree, namespace, shape, accountsByNamespace.get(namespace));
        } catch (AccountResolver.UnknownAccountException e) {
            return false;
        }
        if (account.isBlank() || AccountResolver.INHERIT.equals(account)) return false;
        return shape.credentialOutsideConfig(configTree.path(namespace).path(AccountResolver.ACCOUNTS_KEY).path(account));
    }

    private static boolean inherited(ToolDef.ProxyDef proxyDef, String key, JqValue configTree,
                                     Map<String, String> accountsByNamespace) {
        var namespace = proxyDef.namespaceOf(proxyDef.getConfiguration().get(key));
        return !namespace.isBlank()
                && AccountResolver.inherits(configTree, namespace, accountsByNamespace.get(namespace));
    }

    /** Whether a user could set the entry a key names: it exists, and has somewhere to live. */
    private static boolean suppliable(ToolDef.ProxyDef proxyDef, String key) {
        var entry = proxyDef.getConfiguration().get(key);
        return entry != null && !entry.isConfirm()
                && dev.incusspawn.config.SecretRegistry.isNavigable(proxyDef.fullConfigPath(entry));
    }

    static String fingerprint(List<ResolvedToolProxy> proxies) {
        if (proxies == null || proxies.isEmpty()) return "";
        return sha256(fingerprintContent(proxies));
    }

    private static String fingerprintContent(List<ResolvedToolProxy> proxies) {
        var sorted = proxies.stream()
                .sorted(Comparator.comparing(ResolvedToolProxy::toolName)
                        .thenComparing(ResolvedToolProxy::domain))
                .toList();
        var sb = new StringBuilder();
        for (var tp : sorted) {
            sb.append(tp.toolName()).append('\t')
                    .append(tp.domain()).append('\t');
            if (tp.auth() != null) {
                var a = tp.auth();
                sb.append(nullSafe(a.getType())).append('\t')
                        .append(nullSafe(a.getUsername())).append('\t')
                        .append(nullSafe(a.getPassword())).append('\t')
                        .append(nullSafe(a.getToken())).append('\t')
                        .append(nullSafe(a.getName())).append('\t')
                        .append(nullSafe(a.getValue())).append('\t');
                if (!a.getPaths().isEmpty()) {
                    a.getPaths().stream().sorted().forEach(p -> sb.append(p).append(','));
                    sb.append('\t');
                }
            }
            new TreeMap<>(tp.configValues()).forEach((k, v) ->
                    sb.append(k).append('=').append(v).append(','));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    private static void rejectProjectLocalProxy(Set<String> projectLocal, Map<String, ToolSetup> tools) {
        var it = tools.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (projectLocal.contains(entry.getKey()) && entry.getValue().proxy() != null) {
                // Warnings, not stderr: the TUI's branch dialog reaches this via CredentialCheck.
                dev.incusspawn.Warnings.warn("tool '" + entry.getKey()
                        + "' has proxy configuration but is project-local (.incus-spawn/tools/)."
                        + " The proxy daemon cannot see project-local tools —"
                        + " move it to ~/.config/incus-spawn/tools/ or a configured search path.");
                it.remove();
            }
        }
    }

    private static Map<String, ToolSetup> filterByFeatureGate(
            SpawnConfig config, Map<String, ToolSetup> toolSetups) {
        var filtered = new LinkedHashMap<String, ToolSetup>();
        for (var entry : toolSetups.entrySet()) {
            var feature = entry.getValue().feature();
            if (feature == null || config.isFeatureEnabled(feature)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private static Map<String, String> resolveConfiguration(
            ToolDef.ProxyDef proxyDef,
            JqValue configTree,
            Map<String, ToolSetup> toolSetups,
            Map<String, String> accountsByNamespace) {
        var resolved = new LinkedHashMap<String, String>();
        for (var entry : proxyDef.getConfiguration().entrySet()) {
            var value = resolveConfigValue(proxyDef, entry.getValue(), configTree, toolSetups, accountsByNamespace);
            if (value != null && !value.isBlank()) {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    /**
     * The account applying to one config entry, resolved against the namespace that entry
     * actually belongs to -- which is not always the tool's own.
     *
     * @see ToolDef.ProxyDef#namespaceOf
     */
    private static String accountFor(ToolDef.ProxyDef proxyDef, ToolDef.ConfigEntry configDef,
                                     JqValue configTree, Map<String, ToolSetup> toolSetups,
                                     Map<String, String> accountsByNamespace) {
        var namespace = proxyDef.namespaceOf(configDef);
        if (namespace.isBlank()) return "";
        return AccountResolver.effectiveAccount(configTree, namespace,
                AccountResolver.shapeOf(toolSetups, namespace), accountsByNamespace.get(namespace));
    }

    /**
     * Resolve one configuration entry, preferring the named account when one applies.
     *
     * <p>This is the seam that makes named accounts generic. A tool declaring
     * {@code config-namespace: github} and {@code config-path: token} resolves to
     * {@code github.accounts.<account>.token} when the namespace uses the accounts
     * layout, and to the flat {@code github.token} otherwise -- so a pre-accounts
     * config.yaml keeps working untouched, and a tool defined purely in YAML gains
     * per-account credentials with no code at all.
     */
    private static String resolveConfigValue(
            ToolDef.ProxyDef proxyDef,
            ToolDef.ConfigEntry configDef,
            JqValue configTree,
            Map<String, ToolSetup> toolSetups,
            Map<String, String> accountsByNamespace) {
        if (!configDef.getValue().isBlank()) {
            return configDef.getValue();
        }
        var accountName = accountFor(proxyDef, configDef, configTree, toolSetups, accountsByNamespace);
        // Neither an entry of that name nor the flat credential: the parent instance holds it,
        // and the proxy presents its proof instead (InheritedProof)
        if (AccountResolver.INHERIT.equals(accountName)) return "";
        if (!accountName.isBlank()) {
            var accountPath = proxyDef.accountConfigPath(configDef, accountName);
            if (!accountPath.isBlank()) {
                var value = navigateConfigPath(configTree, accountPath);
                // An account that omits this key falls through to the flat path rather
                // than resolving to blank: a namespace may hold some keys per account
                // (the token) and others globally (an org-wide setting).
                if (!value.isBlank()) return value;
            }
        }
        var fullPath = proxyDef.fullConfigPath(configDef);
        if (!fullPath.isBlank()) {
            return navigateConfigPath(configTree, fullPath);
        }
        return "";
    }

    /** Extract ${...} references from auth fields to determine which configuration keys are needed. */
    public static List<String> extractReferencedKeys(ToolDef.AuthDef auth) {
        var keys = new ArrayList<String>();
        extractRefs(auth.getUsername(), keys);
        extractRefs(auth.getPassword(), keys);
        extractRefs(auth.getToken(), keys);
        extractRefs(auth.getValue(), keys);
        return keys;
    }

    private static void extractRefs(String template, List<String> keys) {
        if (template == null) return;
        Matcher m = REF_PATTERN.matcher(template);
        while (m.find()) {
            keys.add(m.group(1));
        }
    }

    /**
     * The values a {@code domains:} entry may take: never a {@code secret: true} entry's, which
     * would reach bridge DNS, a certificate and the logs. The validator refuses such a reference;
     * this keeps a definition that skipped it from leaking the value anyway.
     */
    private static Map<String, String> domainValues(ToolDef.ProxyDef proxyDef, Map<String, String> values) {
        var configuration = proxyDef.getConfiguration();
        if (configuration.values().stream().noneMatch(entry -> entry != null && (entry.isSecret() || entry.hasSecretField()))) return values;
        var result = new LinkedHashMap<String, String>();
        values.forEach((key, value) -> {
            var entry = configuration.get(key);
            if (entry != null ? !entry.isSecret() && !entry.isList() : isPublicListRef(proxyDef, key)) result.put(key, value);
        });
        return result;
    }

    /** Whether {@code ref} names a list's label or one of its fields that is no secret. */
    private static boolean isPublicListRef(ToolDef.ProxyDef proxyDef, String ref) {
        var dot = ref.indexOf('.');
        if (dot <= 0 || !isListRef(proxyDef, ref.substring(0, dot), ref)) return false;
        var field = listField(proxyDef, ref);
        return field == null || !field.isSecret();
    }

    /** Whether a raw {@code domains:} entry names a configuration key ({@code ${key}}). */
    public static boolean isConfiguredDomain(String rawDomain) {
        return rawDomain != null && REF_PATTERN.matcher(rawDomain).find();
    }

    /** The configuration keys {@code auth}'s {@code domains:} entries reference. */
    public static List<String> extractDomainKeys(ToolDef.AuthDef auth) {
        var keys = new ArrayList<String>();
        for (var domain : auth.getDomains()) extractRefs(domain, keys);
        return keys;
    }

    /**
     * Resolve {@code ${configKey}} references in domain entries. A domain entry that is a pure
     * literal passes through unchanged; one containing an unresolvable reference is dropped. An
     * entry that is a lone reference ({@code ${key}}) takes a comma-separated value as several
     * domains; in an entry with literal text around its reference a comma is no part of a host
     * name, so the text the definition writes applies to the whole value. A configured domain is
     * never a wildcard, whoever writes the {@code *}: the entry is dropped, so no value widens a
     * credential to names nobody listed. It must be a plain host name and none that {@code taken}
     * says isx or another tool already serves: it is dropped with a warning otherwise, so a value
     * of {@code github.com} never puts this entry's credential on GitHub's traffic.
     *
     * @param taken whether a host name is already served, asked only for configured domains
     */
    static List<String> resolveDomains(String toolName, List<String> rawDomains, Map<String, String> configValues,
                                       java.util.function.Predicate<String> taken) {
        if (rawDomains == null) return List.of();
        var result = new ArrayList<String>();
        for (var domain : rawDomains) {
            if (domain == null || domain.isBlank()) continue;
            if (!isConfiguredDomain(domain)) {
                result.add(domain);
                continue;
            }
            if (domain.contains("*")) {
                dev.incusspawn.Warnings.warn(dev.incusspawn.util.OutputFormat.oneLine("tool '" + toolName
                        + "': configured domain entry '" + domain + "' is a wildcard -- skipping it,"
                        + " since a configured domain names its hosts exactly"));
                continue;
            }
            var template = domain;
            // One pass over the entry: a value is never itself searched for references
            var resolved = new StringBuilder();
            boolean unresolved = false;
            Matcher m = REF_PATTERN.matcher(template);
            while (m.find()) {
                var value = configValues.get(m.group(1));
                if (value == null || value.isBlank()) {
                    unresolved = true;
                    break;
                }
                m.appendReplacement(resolved, Matcher.quoteReplacement(value));
            }
            if (unresolved) continue;
            m.appendTail(resolved);
            var parts = LONE_REF_PATTERN.matcher(template).matches()
                    ? resolved.toString().split(",") : new String[] {resolved.toString()};
            for (var part : parts) {
                var name = part.strip().toLowerCase(java.util.Locale.ROOT);
                if (name.isEmpty()) continue;
                if (!CertStore.isHostname(name) || isNumericName(name)) {
                    // Never the value itself: a credential pasted in the wrong key would reach the log
                    dev.incusspawn.Warnings.warn(dev.incusspawn.util.OutputFormat.oneLine("tool '" + toolName
                            + "': a configured domain of entry '" + domain + "' is not a host name -- skipping it"));
                } else if (taken.test(name)) {
                    dev.incusspawn.Warnings.warn(dev.incusspawn.util.OutputFormat.oneLine("tool '" + toolName
                            + "': configured domain '" + name + "' is already served by isx, another tool"
                            + " or another item of its list -- skipping it, so its credential is not sent there"));
                } else {
                    result.add(name);
                }
            }
        }
        return result;
    }

    /** An entry that is one reference and nothing else, its value the whole domain list. */
    private static final Pattern LONE_REF_PATTERN = Pattern.compile("\\$\\{[^}]+}");

    /** Whether {@code name}'s last label is all digits: an IP address, never a host name. */
    private static boolean isNumericName(String name) {
        var last = name.substring(name.lastIndexOf('.') + 1);
        return !last.isEmpty() && last.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** As above, against nothing taken: for tests of the substitution alone. */
    static List<String> resolveDomains(List<String> rawDomains, Map<String, String> configValues) {
        return resolveDomains("", rawDomains, configValues, name -> false);
    }

    /**
     * Whether a configured domain would land on a host name isx already serves: a built-in
     * intercepted domain, ChatGPT's and Google's sign-in hosts, a name under {@code .internal}
     * (host routes, {@code mcp.isx.internal}, {@code host.isx.internal}), or any tool's static
     * domain, exact or under its wildcard. A name under a taken one is taken too.
     */
    static java.util.function.Predicate<String> takenDomains(Map<String, ToolSetup> toolSetups) {
        var taken = new java.util.HashSet<String>();
        for (var name : dev.incusspawn.config.MavenRepositories.builtInTaken()) taken.add(name.toLowerCase(java.util.Locale.ROOT));
        for (var name : ChatGptAccounts.DOMAINS) taken.add(name.toLowerCase(java.util.Locale.ROOT));
        for (var name : GoogleAccounts.DOMAINS) taken.add(name.toLowerCase(java.util.Locale.ROOT));
        for (var tool : toolSetups.values()) {
            var proxyDef = tool.proxy();
            if (proxyDef == null) continue;
            for (var auth : proxyDef.getAuth()) {
                for (var domain : auth.getDomains()) {
                    if (domain == null || domain.isBlank() || isConfiguredDomain(domain)) continue;
                    var bare = domain.startsWith("*.") ? domain.substring(2) : domain;
                    taken.add(bare.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return name -> name.equals("internal") || name.endsWith(".internal") || covers(taken, name);
    }

    /** Whether a tool other than {@code toolName} already configured {@code name} or a name it falls under. */
    private static boolean claimedByOther(Map<String, String> configuredBy, String toolName, String name) {
        var others = new java.util.HashSet<String>();
        configuredBy.forEach((configured, owner) -> {
            if (!owner.equals(toolName)) others.add(configured);
        });
        return !others.isEmpty() && covers(others, name);
    }

    /** Whether {@code name}, or a name it falls under, is one of {@code names}. */
    private static boolean covers(java.util.Collection<String> names, String name) {
        for (var candidate = name; candidate != null; ) {
            if (names.contains(candidate)) return true;
            var dot = candidate.indexOf('.');
            candidate = dot < 0 ? null : candidate.substring(dot + 1);
        }
        return false;
    }

    /** @deprecated use {@link AccountResolver#navigate} -- kept for existing callers. */
    public static String navigateConfigPath(JqValue tree, String path) {
        return AccountResolver.navigate(tree, path);
    }

    static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(hash.length * 2);
            for (var b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
