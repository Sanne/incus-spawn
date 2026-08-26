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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolProxyResolver {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REF_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private ToolProxyResolver() {}

    public static List<ResolvedToolProxy> resolve(SpawnConfig config) {
        var loader = new ToolDefLoader();
        return resolve(config, loader.allToolSetups());
    }

    public static List<ResolvedToolProxy> resolve(SpawnConfig config, Map<String, ToolSetup> toolSetups) {
        var result = new ArrayList<ResolvedToolProxy>();
        var configTree = JSON.valueToTree(config);

        for (var toolEntry : toolSetups.entrySet()) {
            var toolName = toolEntry.getKey();
            var tool = toolEntry.getValue();
            var proxyDef = tool.proxy();
            if (proxyDef == null) continue;

            var allConfigValues = resolveConfiguration(proxyDef.getConfiguration(), config, configTree);

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

    public record UnresolvedToolProxy(String toolName, String configKey) {}

    /**
     * Find tool proxy configuration entries that could not be resolved.
     * Excludes {@code type: anthropic} entries (those use relaxed resolution).
     */
    public static List<UnresolvedToolProxy> findUnresolved(SpawnConfig config) {
        var loader = new ToolDefLoader();
        return findUnresolved(config, loader.allToolSetups());
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
                var value = resolveConfigValue(configDef, config, configTree);
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
        return sha256(sb.toString());
    }

    private static Map<String, String> resolveConfiguration(
            Map<String, ToolDef.ConfigEntry> configuration,
            SpawnConfig config,
            JsonNode configTree) {
        var resolved = new LinkedHashMap<String, String>();
        for (var entry : configuration.entrySet()) {
            var value = resolveConfigValue(entry.getValue(), config, configTree);
            if (value != null && !value.isBlank()) {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private static String resolveConfigValue(
            ToolDef.ConfigEntry configDef,
            SpawnConfig config,
            JsonNode configTree) {
        if (!configDef.getValue().isBlank()) {
            return configDef.getValue();
        }
        if (!configDef.getConfigPath().isBlank()) {
            return navigateConfigPath(configTree, configDef.getConfigPath());
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
        return node != null && node.isTextual() ? node.asText() : "";
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
