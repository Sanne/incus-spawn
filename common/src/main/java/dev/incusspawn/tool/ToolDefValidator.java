package dev.incusspawn.tool;

import dev.incusspawn.config.YamlErrors;
import dev.incusspawn.proxy.ToolProxyResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ToolDefValidator {

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    private static final Set<String> VALID_PARAM_TYPES = Set.of(
            "string", "integer", "boolean", "enum");
    private static final Set<String> VALID_AUTH_TYPES = Set.of(
            "basic", "bearer", "header");

    public static ValidationResult validate(Path file) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        ToolDef def;
        try (var is = java.nio.file.Files.newInputStream(file)) {
            def = ToolDef.loadFromStream(is);
        } catch (IOException e) {
            errors.add(YamlErrors.friendly(file.getFileName().toString(), e));
            return new ValidationResult(errors, warnings);
        }

        validateDef(def, errors, warnings);
        return new ValidationResult(errors, warnings);
    }

    public static void validateDef(ToolDef def, List<String> errors, List<String> warnings) {
        if (def.getName() == null || def.getName().isBlank()) {
            errors.add("'name' field is required and must not be blank");
            return;
        }

        for (var dl : def.getDownloads()) {
            if (dl.getUrl() == null || dl.getUrl().isBlank()) {
                warnings.add("download entry in '" + def.getName() + "' is missing a 'url'");
            }
        }

        for (var entry : def.getParameters().entrySet()) {
            var name = entry.getKey();
            var param = entry.getValue();
            if (param.getType() != null && !VALID_PARAM_TYPES.contains(param.getType())) {
                warnings.add("parameter '" + name + "' has invalid type '" + param.getType()
                        + "' — must be one of: string, integer, boolean, enum");
            }
            if ("enum".equals(param.getType())
                    && (param.getOptions() == null || param.getOptions().isEmpty())) {
                warnings.add("parameter '" + name + "' is type 'enum' but has no 'options' defined");
            }
            if ("integer".equals(param.getType())
                    && param.getMin() != null && param.getMax() != null
                    && param.getMin() > param.getMax()) {
                warnings.add("parameter '" + name + "' has min (" + param.getMin()
                        + ") greater than max (" + param.getMax() + ")");
            }
        }

        var proxyDef = def.getProxy();
        if (proxyDef != null) {
            var configMap = proxyDef.getConfiguration();
            var ns = proxyDef.getConfigNamespace();

            boolean hasAuth = proxyDef.getAuth() != null && !proxyDef.getAuth().isEmpty();
            boolean hasConfigPaths = configMap.values().stream()
                    .anyMatch(c -> !c.getConfigPath().isBlank());
            if (hasAuth && hasConfigPaths && (ns == null || ns.isBlank())) {
                errors.add("proxy for '" + def.getName()
                        + "' has config-path entries but no 'config-namespace'");
            }

            for (var ce : configMap.entrySet()) {
                var config = ce.getValue();
                if (!config.getConfigPath().isBlank() && !config.getValue().isBlank()) {
                    errors.add("configuration '" + ce.getKey() + "' in proxy for '"
                            + def.getName() + "' has both 'config-path' and 'value' — use one or the other");
                }
                if (!config.getConfigPath().isBlank() && config.getConfigPath().contains(".")) {
                    warnings.add("configuration '" + ce.getKey() + "' in proxy for '"
                            + def.getName() + "': config-path '" + config.getConfigPath()
                            + "' contains a dot — paths are relative to config-namespace, not absolute");
                }
                if (!config.isSelfResolving() && config.getDescription().isBlank()) {
                    warnings.add("configuration '" + ce.getKey() + "' in proxy for '"
                            + def.getName() + "' has no description (needed for interactive setup)");
                }
            }

            for (var ae : proxyDef.getAuth()) {
                if (ae.getDomains() == null || ae.getDomains().isEmpty()) {
                    errors.add("auth entry in proxy for '" + def.getName() + "' is missing 'domains'");
                    continue;
                }
                var authType = ae.getType();
                if (authType == null || !VALID_AUTH_TYPES.contains(authType)) {
                    errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                            + "' has invalid auth type '" + authType + "' — must be one of: basic, bearer, header");
                }
                if ("header".equals(authType)) {
                    if (ae.getName() == null || ae.getName().isBlank()) {
                        errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                                + "': header auth requires 'name'");
                    }
                    if (ae.getValue() == null || ae.getValue().isBlank()) {
                        errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                                + "': header auth requires 'value'");
                    }
                } else if ("bearer".equals(authType)) {
                    if (ae.getToken() == null || ae.getToken().isBlank()) {
                        errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                                + "': bearer auth requires 'token'");
                    }
                } else if ("basic".equals(authType)) {
                    if (ae.getUsername() == null || ae.getUsername().isBlank()) {
                        errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                                + "': basic auth requires 'username'");
                    }
                    if (ae.getPassword() == null || ae.getPassword().isBlank()) {
                        errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                                + "': basic auth requires 'password'");
                    }
                }

                var refs = ToolProxyResolver.extractReferencedKeys(ae);
                for (var ref : refs) {
                    if (!configMap.containsKey(ref)) {
                        errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                                + "' references '${" + ref + "}' which is not defined in configuration");
                    }
                }
            }
        }
    }
}
