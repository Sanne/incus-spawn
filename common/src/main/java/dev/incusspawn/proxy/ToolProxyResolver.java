package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolProxyResolver {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REF_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final String FINGERPRINT_SALT = UUID.randomUUID().toString();

    private ToolProxyResolver() {}

    public static List<ResolvedToolProxy> resolve(SpawnConfig config) {
        var loader = new ToolDefLoader();
        var filtered = filterByFeatureGate(config, loader.allToolSetups());
        return resolve(config, filtered);
    }

    public static List<ResolvedToolProxy> resolve(SpawnConfig config, Map<String, ToolSetup> toolSetups) {
        var result = new ArrayList<ResolvedToolProxy>();
        var configTree = JSON.valueToTree(config);
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

            var allConfigValues = resolveConfiguration(proxyDef, config, configTree);

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

    public record UnresolvedToolProxy(String toolName, String configKey) {}

    /**
     * Find tool proxy configuration entries that could not be resolved.
     * Excludes {@code type: anthropic} entries (those use relaxed resolution).
     */
    public static List<UnresolvedToolProxy> findUnresolved(SpawnConfig config) {
        var loader = new ToolDefLoader();
        var filtered = filterByFeatureGate(config, loader.allToolSetups());
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
                var value = resolveConfigValue(proxyDef, configDef, configTree);
                if (value == null || value.isBlank()) {
                    result.add(new UnresolvedToolProxy(toolName, configKey));
                }
            }
        }
        return result;
    }

    /**
     * Compute a SHA-256 fingerprint of resolved tool proxies.
     * Captures domains, auth types, and actual configuration values so that
     * both definition changes and value changes are detected.
     */
    public static String fingerprint(List<ResolvedToolProxy> proxies) {
        if (proxies == null || proxies.isEmpty()) return "";
        return sha256(FINGERPRINT_SALT + "\n" + fingerprintContent(proxies));
    }

    static String unsaltedFingerprint(List<ResolvedToolProxy> proxies) {
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
                    .append(tp.domain()).append('\t')
                    .append(tp.auth() != null ? tp.auth().getType() : "").append('\t');
            new TreeMap<>(tp.configValues()).forEach((k, v) ->
                    sb.append(k).append('=').append(v).append(','));
            sb.append('\n');
        }
        return sb.toString();
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
            SpawnConfig config,
            JsonNode configTree) {
        var resolved = new LinkedHashMap<String, String>();
        for (var entry : proxyDef.getConfiguration().entrySet()) {
            var value = resolveConfigValue(proxyDef, entry.getValue(), configTree);
            if (value != null && !value.isBlank()) {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private static String resolveConfigValue(
            ToolDef.ProxyDef proxyDef,
            ToolDef.ConfigEntry configDef,
            JsonNode configTree) {
        if (!configDef.getValue().isBlank()) {
            return configDef.getValue();
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

    public static String navigateConfigPath(JsonNode tree, String path) {
        var node = tree;
        for (var segment : path.split("\\.")) {
            if (node == null || !node.isObject()) return "";
            node = node.get(segment);
        }
        return node != null && node.isValueNode() ? node.asText() : "";
    }

    private static String sha256(String input) {
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
