package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolProxyResolver {

    private static final ObjectMapper JSON = new ObjectMapper();
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
        var loader = new ToolDefLoader();
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
        return resolve(JSON.valueToTree(config), toolSetups, accountsByNamespace);
    }

    /**
     * As above, against a config tree the caller already serialized. Serializing a whole
     * {@link SpawnConfig} is not free, and {@link #resolveAcrossAccounts} resolves once per
     * configured account.
     */
    public static List<ResolvedToolProxy> resolve(JsonNode configTree,
                                                  Map<String, ToolSetup> toolSetups,
                                                  Map<String, String> accountsByNamespace) {
        var result = new ArrayList<ResolvedToolProxy>();
        var namespaces = new java.util.HashMap<String, String>();

        for (var toolEntry : toolSetups.entrySet()) {
            var toolName = toolEntry.getKey();
            var tool = toolEntry.getValue();
            var proxyDef = tool.proxy();
            if (proxyDef == null) continue;

            var ns = proxyDef.getConfigNamespace();
            if (!ns.isBlank()) {
                var existing = namespaces.putIfAbsent(ns, toolName);
                if (existing != null) {
                    System.err.println("Warning: tool '" + toolName + "' shares config-namespace '"
                            + ns + "' with tool '" + existing + "' — skipping");
                    continue;
                }
            }

            var allConfigValues = resolveConfiguration(proxyDef, configTree, toolSetups, accountsByNamespace);

            for (var authEntry : proxyDef.getAuth()) {
                if (authEntry.getDomains() == null || authEntry.getDomains().isEmpty()) continue;
                if (authEntry.getType() == null) continue;

                boolean isAnthropic = "anthropic".equals(authEntry.getType());
                var referencedKeys = extractReferencedKeys(authEntry);
                boolean allReferenced = referencedKeys.stream().allMatch(allConfigValues::containsKey);

                if (isAnthropic ? allConfigValues.isEmpty() : !allReferenced) continue;

                for (var domain : authEntry.getDomains()) {
                    if (domain == null || domain.isBlank()) continue;
                    result.add(new ResolvedToolProxy(toolName, domain, authEntry, Map.copyOf(allConfigValues)));
                }
            }
        }
        return result;
    }

    public static Set<String> resolvedDomains(SpawnConfig config) {
        return resolve(config).stream()
                .map(ResolvedToolProxy::domain)
                .collect(Collectors.toUnmodifiableSet());
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
        var configTree = JSON.valueToTree(config);
        var seen = new LinkedHashMap<String, ResolvedToolProxy>();
        var selections = new ArrayList<Map<String, String>>();
        selections.add(Map.of());
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
        return List.copyOf(seen.values());
    }

    public record UnresolvedToolProxy(String toolName, String configKey) {}

    /**
     * Find tool proxy configuration entries that could not be resolved.
     * Excludes {@code type: anthropic} entries (those use relaxed resolution).
     */
    public static List<UnresolvedToolProxy> findUnresolved(SpawnConfig config) {
        var loader = new ToolDefLoader();
        var filtered = filterByFeatureGate(config, loader.allToolSetups());
        rejectProjectLocalProxy(loader.projectLocalToolNames(), filtered);
        return findUnresolved(config, filtered);
    }

    public static List<UnresolvedToolProxy> findUnresolved(SpawnConfig config, Map<String, ToolSetup> toolSetups) {
        var result = new ArrayList<UnresolvedToolProxy>();
        var configTree = JSON.valueToTree(config);

        for (var toolEntry : toolSetups.entrySet()) {
            var toolName = toolEntry.getKey();
            var tool = toolEntry.getValue();
            var proxyDef = tool.proxy();
            if (proxyDef == null) continue;

            boolean hasNonAnthropicAuth = proxyDef.getAuth().stream()
                    .anyMatch(a -> a.getType() != null && !"anthropic".equals(a.getType()));
            if (!hasNonAnthropicAuth) continue;

            for (var configEntry : proxyDef.getConfiguration().entrySet()) {
                var configKey = configEntry.getKey();
                var configDef = configEntry.getValue();
                if (configDef.isConfirm()) continue;
                // Map.of() means "no pin", so each entry resolves against its namespace's
                // default -- a namespace on the accounts layout is not reported as missing
                // its credential just because the flat field is empty.
                var value = resolveConfigValue(proxyDef, configDef, configTree, toolSetups, Map.of());
                if (value == null || value.isBlank()) {
                    result.add(new UnresolvedToolProxy(toolName, configKey));
                }
            }
        }
        return result;
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
                System.err.println("Warning: tool '" + entry.getKey()
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
            JsonNode configTree,
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
                                     JsonNode configTree, Map<String, ToolSetup> toolSetups,
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
            JsonNode configTree,
            Map<String, ToolSetup> toolSetups,
            Map<String, String> accountsByNamespace) {
        if (!configDef.getValue().isBlank()) {
            return configDef.getValue();
        }
        var accountName = accountFor(proxyDef, configDef, configTree, toolSetups, accountsByNamespace);
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

    /** @deprecated use {@link AccountResolver#navigate} -- kept for existing callers. */
    public static String navigateConfigPath(JsonNode tree, String path) {
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
