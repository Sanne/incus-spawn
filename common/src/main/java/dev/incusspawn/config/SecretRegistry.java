package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for <em>where secrets live</em> in {@code config.yaml}.
 *
 * <p>Nothing is declared here. Every tool that injects credentials already describes its
 * secrets through its proxy definition -- a {@link ToolDef.ConfigEntry} with
 * {@code secret: true} and a {@code config-path}, resolved against the tool's
 * {@code config-namespace} by {@link ToolDef.ProxyDef#fullConfigPath}. This class only
 * collects those declarations into a list of dotted config paths.
 *
 * <p>That indirection is the point: a new tool (including a user's own YAML under
 * {@code ~/.config/incus-spawn/tools/}) becomes known to every secret-aware consumer by
 * declaring {@code secret: true}, with no list to keep in step. Support-bundle redaction
 * ({@link SecretRedactor}) is the first consumer; a pluggable secrets backend -- keychain,
 * 1Password, environment -- would be the second, and would read the same locations.
 */
public final class SecretRegistry {

    private SecretRegistry() {}

    /** A declared secret: its dotted path in config.yaml, and who declared it. */
    public record SecretLocation(String path, String toolName, String description) {}

    /** Declared secret locations across every tool in scope. */
    public static List<SecretLocation> locations() {
        return locations(new ToolDefLoader().allToolSetups());
    }

    /** Testable overload: collect declared secret locations from a given tool set. */
    public static List<SecretLocation> locations(Map<String, ToolSetup> toolSetups) {
        var result = new ArrayList<SecretLocation>();
        for (var entry : toolSetups.entrySet()) {
            var proxy = entry.getValue().proxy();
            if (proxy == null) continue;
            for (var configEntry : proxy.getConfiguration().values()) {
                if (!configEntry.isSecret() || configEntry.isConfirm()) continue;
                var path = proxy.fullConfigPath(configEntry);
                // A malformed config-path (".", "a..b") names nothing navigable. Dropping it
                // here keeps one bad tool definition from failing the whole bundle.
                if (path.isBlank() || java.util.Arrays.stream(path.split("\\.", -1)).anyMatch(String::isBlank)) {
                    continue;
                }
                result.add(new SecretLocation(path, entry.getKey(), configEntry.getDescription()));
            }
        }
        result.sort(Comparator.comparing(SecretLocation::path));
        return List.copyOf(result);
    }

}
