package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.config.YamlErrors;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ResolvedToolProxy;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.util.OutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ToolDefValidator {

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    private static final Set<String> VALID_PARAM_TYPES = Set.of(
            "string", "integer", "boolean", "enum");
    private static final Set<String> VALID_AUTH_TYPES = Set.of(
            "basic", "bearer", "header");

    /** {@code loginctl}, any options, then {@code enable-linger}, within one command. */
    private static final Pattern ENABLE_LINGER = Pattern.compile("\\bloginctl\\b[^\\n;&|]*\\benable-linger\\b");

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
        warnings.addAll(def.unknownKeyWarnings());
        if (def.getName() == null || def.getName().isBlank()) {
            errors.add("'name' field is required and must not be blank");
            return;
        }
        // Stop here: every message below names the tool as it is, which may hold escape sequences
        var invalidName = invalidName(def.getName());
        if (invalidName != null) {
            errors.add(invalidName);
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

        // The refusals tolerate bare keys; the checks below read the entries, so stop before them
        // Reads only the steps, so a bare key elsewhere does not hide it
        warnings.addAll(lingersThroughLoginctl(def));

        var bareKeys = bareKeys(def);
        errors.addAll(refusals(def, bareKeys));
        if (!bareKeys.isEmpty()) return;

        for (var entry : def.getParameters().entrySet()) {
            var name = entry.getKey();
            var param = entry.getValue();
            if (param.getType() != null && !VALID_PARAM_TYPES.contains(param.getType())) {
                warnings.add("parameter '" + name + "' has invalid type '" + param.getType()
                        + "' — must be one of: string, integer, boolean, enum");
            }
            if ("enum".equals(param.getType())
                    && param.getOptions().isEmpty()) {
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

            boolean hasAuth = !proxyDef.getAuth().isEmpty();
            boolean hasUnscopedConfigPaths = configMap.values().stream()
                    .anyMatch(c -> !c.getConfigPath().isBlank() && proxyDef.namespaceOf(c).isBlank());
            if (hasAuth && hasUnscopedConfigPaths) {
                errors.add("proxy for '" + def.getName()
                        + "' has config-path entries but no 'config-namespace'");
            }

            for (var ce : configMap.entrySet()) {
                var config = ce.getValue();
                if (!config.getConfigPath().isBlank() && !config.getValue().isBlank()) {
                    errors.add("configuration '" + ce.getKey() + "' in proxy for '"
                            + def.getName() + "' has both 'config-path' and 'value' — use one or the other");
                }
                if (ns != null && !ns.isBlank() && config.getConfigPath().contains(".")) {
                    warnings.add(OutputFormat.oneLine("configuration '" + ce.getKey() + "' in proxy for '"
                            + def.getName() + "': config-path '" + config.getConfigPath()
                            + "' contains a dot — paths are relative to config-namespace, not absolute"));
                }
                if (!config.isSelfResolving() && config.getDescription().isBlank()) {
                    warnings.add("configuration '" + ce.getKey() + "' in proxy for '"
                            + def.getName() + "' has no description (needed for interactive setup)");
                }
            }

            for (var ae : proxyDef.getAuth()) {
                if (ae.getDomains().isEmpty()) {
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
            if (errors.isEmpty()) {
                warnings.addAll(new dev.incusspawn.proxy.ProofCarriers(List.of(new YamlToolSetup(def)))
                        .warningsForNamespace(proxyDef.getConfigNamespace()));
            }
        }
    }

    /**
     * A warning for a tool that enables lingering with {@code loginctl}, which needs logind, and so
     * a system bus, running during the build: a box being built has neither, and the build fails
     * at that step (#1520). Reported by the loader too, since user tools reach no other check.
     */
    public static List<String> lingersThroughLoginctl(ToolDef def) {
        // A backslash-newline continues the command on the next line; a comment runs nothing
        if (buildSteps(def).flatMap(step -> step.replace("\\\n", " ").lines())
                .noneMatch(line -> !line.strip().startsWith("#") && ENABLE_LINGER.matcher(line).find())) return List.of();
        return List.of(OutputFormat.oneLine("a build step in '" + def.getName() + "' runs 'loginctl enable-linger',"
                + " which fails without logind running during the build: write /var/lib/systemd/linger/<user> instead,"
                + " and list dbus-broker and systemd-pam in 'packages' so logind starts at boot"));
    }

    /** The tool's shell steps, root's and the user's. */
    static Stream<String> buildSteps(ToolDef def) {
        return Stream.of(def.getRun(), def.getRunAsUser()).flatMap(List::stream);
    }

    /** Why a definition is refused where definitions load, or null when it is not. */
    public static String refusal(ToolDef def) {
        var refusals = refusals(def, bareKeys(def));
        return refusals.isEmpty() ? null : refusals.getFirst();
    }

    /**
     * Every reason a definition is refused where definitions load, in the order {@link #refusal}
     * names the first. Each check tolerates bare keys, so {@code validateDef} reports them all in
     * one pass. {@code bareKeys} is what {@link #bareKeys} returns for {@code def}.
     */
    private static List<String> refusals(ToolDef def, List<String> bareKeys) {
        var refusals = new ArrayList<>(bareKeys);
        var invalidName = invalidName(def.getName());
        if (invalidName != null) refusals.addFirst(invalidName);
        var invalidNamespace = invalidNamespace(def);
        if (invalidNamespace != null) refusals.add(invalidNamespace);
        refusals.addAll(invalidPathScopes(def));
        refusals.addAll(invalidMcpServers(def));
        return refusals;
    }

    /**
     * Every key under {@code parameters:} or {@code proxy.configuration:} left without a
     * definition ({@code flavour:} with nothing under it, which arrives as null), as an error.
     * Such a tool is refused where definitions load, like an invalid namespace: a bare key has
     * no meaning of its own, and no default is implied (#1475).
     */
    public static List<String> bareKeys(ToolDef def) {
        var errors = new ArrayList<String>();
        def.getParameters().forEach((name, param) -> {
            if (param == null) {
                errors.add(OutputFormat.oneLine("parameter '" + name + "' in '" + def.getName() + "' has no definition"));
            }
        });
        if (def.getProxy() != null) {
            def.getProxy().getConfiguration().forEach((key, entry) -> {
                if (entry == null) {
                    errors.add(OutputFormat.oneLine("configuration '" + key + "' in proxy for '" + def.getName()
                            + "' has no definition"));
                }
            });
        }
        return errors;
    }

    /**
     * Every {@code mcp:} entry that cannot be registered with an agent ({@link McpServer#problem}),
     * every name declared twice, and every {@code bearer-token-env} that is not one of the tool's
     * own {@code proxy.placeholders}, as an error: a token reaches a server only as a proof the
     * proxy checks before it injects the account's credential. Such a tool is refused where definitions load:
     * an agent would get a server other than the one declared, or none.
     */
    public static List<String> invalidMcpServers(ToolDef def) {
        var errors = new ArrayList<String>();
        var names = new java.util.HashSet<String>();
        // The variables a start fills with this tool's proof, which the proxy checks before it injects
        // (declared and exported, since a start only replaces a variable the build set)
        var exported = YamlToolSetup.placeholderCandidates(def);
        var placeholders = def.getProxy() == null ? java.util.Set.<String>of()
                : def.getProxy().getPlaceholders().stream().filter(java.util.Objects::nonNull)
                        .map(ToolDef.PlaceholderDef::getEnv).filter(exported::contains)
                        .collect(java.util.stream.Collectors.toSet());
        // The hosts the proxy injects this tool's credential for, where its proof is checked
        var domains = def.getProxy() == null ? List.<String>of()
                : def.getProxy().getAuth().stream().filter(java.util.Objects::nonNull)
                        .flatMap(a -> a.getDomains().stream()).filter(java.util.Objects::nonNull).toList();
        for (var entry : def.getMcp()) {
            var problem = McpServer.problem(entry);
            var label = entry.getName() == null ? "" : entry.getName();
            if (problem != null) {
                errors.add(OutputFormat.oneLine("mcp server '" + label + "' in '" + def.getName() + "': " + problem));
            } else if (!names.add(entry.getName())) {
                errors.add("mcp server '" + label + "' is declared twice in '" + def.getName() + "'");
            } else if (entry.getEnv().keySet().stream().anyMatch(SecretRedactor::looksSecret)) {
                errors.add(OutputFormat.oneLine("mcp server '" + label + "' in '" + def.getName()
                        + "': env would write a credential into each agent's config;"
                        + " use 'bearer-token-env' with a proxy placeholder instead"));
            } else if (entry.getBearerTokenEnv() != null && !placeholders.contains(entry.getBearerTokenEnv())) {
                errors.add(OutputFormat.oneLine("mcp server '" + label + "' in '" + def.getName() + "': bearer-token-env "
                        + entry.getBearerTokenEnv() + " is not one of the tool's proxy.placeholders,"
                        + " so no start fills it with a proof the proxy checks"));
            } else if (entry.getBearerTokenEnv() != null && domains.stream().noneMatch(d ->
                    dev.incusspawn.proxy.ProofCarriers.matchesDomain(d, urlHost(entry.getUrl())))) {
                errors.add(OutputFormat.oneLine("mcp server '" + label + "' in '" + def.getName()
                        + "': its url's host is none of the tool's proxy.auth domains,"
                        + " so its proof would reach a server the proxy does not check it for"));
            }
        }
        return errors;
    }

    /** The host of {@code url}, or {@code ""} when it has none. */
    private static String urlHost(String url) {
        try {
            var host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Every {@code paths:} entry of the tool's auth entries that cannot scope one, as an error.
     * Such a tool is refused where definitions load, like an invalid namespace: its scope would
     * not be the one it declares.
     */
    public static List<String> invalidPathScopes(ToolDef def) {
        var proxy = def.getProxy();
        if (proxy == null) return List.of();
        var errors = new ArrayList<String>();
        for (var ae : proxy.getAuth()) {
            for (var path : ae.getPaths()) {
                var problem = pathScopeProblem(path);
                if (problem != null) {
                    errors.add(OutputFormat.oneLine("auth entry for " + ae.getDomains() + " in '" + def.getName()
                            + "': path '" + (path == null ? "" : path) + "' " + problem));
                }
            }
        }
        return errors;
    }

    /**
     * Why {@code path} cannot scope an auth entry, or null when it can: a literal prefix of the
     * request path, matched on whole segments. The proxy ignores one that does not start with
     * '/', so an empty prefix never widens a scope to every path ({@code ResolvedToolProxy}).
     */
    static String pathScopeProblem(String path) {
        if (path == null || path.isBlank()) {
            return "is empty: use '/' to scope every path, or omit 'paths'";
        }
        if (path.chars().anyMatch(Character::isWhitespace)) return "must not contain whitespace";
        if (!path.startsWith("/")) return "must start with '/' (for example '/" + path + "')";
        if (path.contains("?") || path.contains("#")) {
            return "must not contain a query or fragment: scopes match the path alone";
        }
        if (path.contains("*")) return "is a literal prefix: wildcards are not supported";
        if (path.contains(";")) return "must not contain ';': servers that drop path parameters serve another path";
        var segments = path.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            var segment = segments[i];
            // A single trailing slash is allowed: '/mcp/' is '/mcp'
            if (segment.isEmpty() && i == segments.length - 1) continue;
            if (segment.isEmpty()) return "must not contain an empty segment ('//')";
            if (segment.equals(".") || segment.equals("..")) return "must not contain a '.' or '..' segment";
        }
        // Covers the segment checks above too; those stay for their more specific messages
        if (ResolvedToolProxy.mayResolveElsewhere(path)) {
            return "must be a path no upstream may read as another (docs/TOOLS.md, \"Path-scoped injection\"):"
                    + " the proxy matches no request under such a prefix";
        }
        return null;
    }

    /**
     * What a credential namespace may be: an identifier, which {@code isx init} and {@code isx
     * account} can print as is. Allows the camelCase and '.' a previous release's config may hold.
     */
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String NAMESPACE_RULE = "letters, digits, '.', '-' and '_', starting with a letter or digit";

    /** Whether {@code namespace} is a credential namespace isx accepts, from a tool or a proof token. */
    public static boolean isNamespace(String namespace) {
        return namespace != null && NAMESPACE.matcher(namespace).matches();
    }

    /**
     * Why {@code name} cannot name a tool, or null. Such a tool is refused where definitions
     * load, so a cloned repository's tool name never reaches a listing: bash completion feeds
     * {@code isx tools list} to {@code compgen -W}, which runs a command substitution in it
     * (#1778), and a terminal shows any escape sequence in it.
     */
    public static String invalidName(String name) {
        // The same rule as a namespace
        if (name == null || isNamespace(name)) return null;
        return notAnIdentifier("tool name '" + name + "'");
    }

    /**
     * Why {@code def} names a credential namespace that is not an identifier, or null: its own
     * {@code config-namespace}, or one it borrows through a {@code config-path}. Such a tool is
     * refused where definitions load, so a cloned repository's namespace, which could carry
     * escape sequences or line breaks, never reaches a prompt, a config path or an instance
     * stamp (#1266).
     */
    public static String invalidNamespace(ToolDef def) {
        var proxy = def.getProxy();
        if (proxy == null) return null;
        var own = proxy.getConfigNamespace();
        if (!own.isBlank()) {
            return isNamespace(own) ? null : notAnIdentifier(def, "config-namespace", own);
        }
        for (var entry : proxy.getConfiguration().values()) {
            // A bare key ('token:') arrives as null and must be tolerated: this runs on definitions that still hold one
            if (entry == null) continue;
            var borrowed = proxy.namespaceOf(entry);
            if (!borrowed.isEmpty() && !isNamespace(borrowed)) {
                return notAnIdentifier(def, "config-path '" + entry.getConfigPath() + "' names namespace", borrowed);
            }
        }
        return null;
    }

    private static String notAnIdentifier(ToolDef def, String what, String namespace) {
        return notAnIdentifier(what + " '" + namespace + "' in proxy for '" + def.getName() + "'");
    }

    private static String notAnIdentifier(String subject) {
        return OutputFormat.oneLine(subject + " is not an identifier (" + NAMESPACE_RULE + ")");
    }

    /** Shorter values ({@code 1}, {@code true}) are settings, and would match by accident. */
    private static final int MIN_TOKEN_LENGTH = 8;

    /**
     * What a placeholder looks like: one word that is no path or URL. With a variable named as
     * a credential ({@link SecretRedactor#looksSecret}), this keeps {@code FOO_HOME=/opt/foo} or
     * an endpoint from being taken for a token, while base64 ({@code /}) and {@code user:secret}
     * values still count. Looser than {@link SecretRedactor#hasSecretShape}, which knows real
     * issuers' tokens, not placeholders. Only a guess, for a tool that declares no
     * {@code placeholders:}; one that does is checked on exactly those (#1106).
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
        if (proxy == null || proxy.getAuth().isEmpty()) return List.of();
        var warnings = new ArrayList<String>();
        var steps = buildSteps(def).toList();
        // The variables the proxy checks, where the tool declares them (#1106); else a guess
        var declared = proxy.getPlaceholders().stream().map(ToolDef.PlaceholderDef::getEnv).toList();
        for (var env : def.getEnv()) {
            var value = env.getValue();
            if (env.getName() == null || value == null || value.length() < MIN_TOKEN_LENGTH
                    || env.getStrategy() == EnvEntry.Strategy.PREPEND
                    || env.getStrategy() == EnvEntry.Strategy.APPEND) continue;
            if (declared.isEmpty() ? !SecretRedactor.looksSecret(env.getName()) || !TOKEN_SHAPE.matcher(value).matches()
                    : !declared.contains(env.getName())) continue;
            var where = new ArrayList<String>();
            for (var file : def.getFiles()) {
                if (file.getContent() != null && file.getContent().contains(value)) {
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
}
