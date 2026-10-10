package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.config.YamlErrors;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ToolProxyResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
            var hasExtract = dl.getExtract() != null && !dl.getExtract().isBlank();
            var hasDestinationFile = dl.getDestinationFile() != null
                    && !dl.getDestinationFile().isBlank();
            if (!hasExtract && !hasDestinationFile) {
                errors.add("download entry in '" + def.getName()
                        + "' must set 'extract', 'destination_file', or both");
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
                if ("anthropic".equals(authType)) {
                    errors.add("auth entry for " + ae.getDomains() + " in '" + def.getName()
                            + "': auth type 'anthropic' is reserved for the built-in Claude tool");
                } else if (authType == null || !VALID_AUTH_TYPES.contains(authType)) {
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

                for (var domain : ae.getDomains()) {
                    var bare = domain.startsWith("*.") ? domain.substring(2) : domain;
                    if (ProxyConfig.builtinInterceptedDomains().contains(bare)) {
                        warnings.add("auth entry for '" + domain + "' in '" + def.getName()
                                + "': domain is handled by a built-in proxy handler (caching/relay)"
                                + " — tool proxy credential injection will not take effect for this domain");
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

            var exported = YamlToolSetup.placeholderCandidates(def);
            for (var placeholder : new YamlToolSetup(def).declaredPlaceholders()) {
                var problem = placeholder.problem();
                if (!problem.isEmpty()) {
                    errors.add("placeholder in proxy for '" + def.getName() + "': " + problem);
                } else if (!exported.contains(placeholder.env())) {
                    errors.add("placeholder " + placeholder.env() + " in proxy for '" + def.getName()
                            + "' is not a variable its env: entries set -- a start only fills one the tool sets outright");
                } else if (def.getEnv().stream().anyMatch(e -> placeholder.env().equals(e.getName())
                        && (e.getValue() == null || !e.getValue().startsWith(placeholder.staticValuePrefix())))) {
                    errors.add("placeholder " + placeholder.env() + " in proxy for '" + def.getName()
                            + "' must be set to a value starting with '" + placeholder.staticValuePrefix()
                            + "' -- a start replaces only the build's own placeholder");
                }
            }
        }
    }

    /** Shorter values ({@code 1}, {@code true}) are settings, and would match by accident. */
    private static final int MIN_TOKEN_LENGTH = 8;

    /**
     * What a placeholder looks like: one word that is no path or URL. With a variable named as
     * a credential ({@link SecretRedactor#looksSecret}), this keeps {@code FOO_HOME=/opt/foo} or
     * an endpoint from being taken for a token, while base64 ({@code /}) and {@code user:secret}
     * values still count. Looser than {@link SecretRedactor#hasSecretShape}, which knows real
     * issuers' tokens, not placeholders. A guess until a tool declares which variable the proxy
     * checks (#1106).
     */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("(?![/~.$])(?!.*://)\\S+");

    /**
     * Warnings for a tool that bakes the value of one of its own credential variables into a
     * file or a build step, when it has credentials the proxy injects. The token a tool presents
     * to the proxy changes on every start (#1108): a copy taken at build time is stale after the
     * next one, so the tool must read the variable whenever it runs. Reported by the loader
     * only, so once per load.
     */
    public static List<String> embeddedTokens(ToolDef def) {
        var proxy = def.getProxy();
        if (proxy == null || proxy.getAuth() == null || proxy.getAuth().isEmpty()) return List.of();
        var warnings = new ArrayList<String>();
        // A key left empty in YAML ('run:') arrives as null, list and items alike
        var steps = java.util.stream.Stream.of(def.getRun(), def.getRunAsUser())
                .filter(java.util.Objects::nonNull).flatMap(List::stream)
                .filter(java.util.Objects::nonNull).toList();
        for (var env : orEmpty(def.getEnv())) {
            if (env == null) continue;
            var value = env.getValue();
            if (env.getName() == null || value == null || value.length() < MIN_TOKEN_LENGTH
                    || !SecretRedactor.looksSecret(env.getName()) || !TOKEN_SHAPE.matcher(value).matches()
                    || env.getStrategy() == EnvEntry.Strategy.PREPEND
                    || env.getStrategy() == EnvEntry.Strategy.APPEND) continue;
            var where = new ArrayList<String>();
            for (var file : orEmpty(def.getFiles())) {
                if (file != null && file.getContent() != null && file.getContent().contains(value)) {
                    where.add("file '" + file.getPath() + "'");
                }
            }
            if (steps.stream().anyMatch(c -> c.contains(value))) {
                where.add("a build step");
            }
            if (!where.isEmpty()) {
                warnings.add("tool '" + def.getName() + "': " + String.join(" and ", where)
                        + " holds the value of $" + env.getName() + ", fixed at build time; the token"
                        + " the proxy expects changes on every start, so read $" + env.getName()
                        + " when the tool runs instead");
            }
        }
        return warnings;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
