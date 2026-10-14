package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolSetup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Authentication carriers declared by trusted tools, resolved without reading any account. */
public final class ProofCarriers {
    private record Carrier(String domain, String namespace, String header, List<Pattern> tokens, List<Pattern> claims) {}
    private final List<Carrier> carriers;
    private record MissingPlaceholders(String tool, String namespace) {
        String warning(java.util.Set<String> domains) {
            var advice = namespace.isBlank()
                    ? " has no credential namespace: set proxy.config-namespace and use config-path entries relative to it, then declare proxy.placeholders"
                    : " has no declared placeholders: declare proxy.placeholders";
            return dev.incusspawn.util.OutputFormat.oneLine("tool '" + tool + "': proxy auth namespace '" + namespace + "'"
                    + advice + " and export its credential variable in env; send that variable with each request."
                    + " Credential injection is disabled for " + domains + ", including other tools sharing those domains.");
        }
    }
    private final Map<MissingPlaceholders, java.util.Set<String>> missing;

    public ProofCarriers(Collection<? extends ToolSetup> tools) {
        var placeholders = InheritedProof.byNamespace(ProofToken.declaredBy(tools));
        var declared = new ArrayList<Carrier>();
        missing = new LinkedHashMap<>();
        for (var tool : tools) {
            var proxy = tool.proxy();
            if (proxy == null) continue;
            for (var auth : proxy.getAuth()) {
                if (auth.getDomains() == null) continue;
                var namespaces = new java.util.LinkedHashSet<String>();
                var keys = ToolProxyResolver.extractReferencedKeys(auth);
                // Anthropic's carrier varies with the guest's mode, independently of this host's.
                if ("anthropic".equals(auth.getType())) keys = List.copyOf(proxy.getConfiguration().keySet());
                for (var key : keys) {
                    var config = proxy.getConfiguration().get(key);
                    if (config != null) namespaces.add(proxy.namespaceOf(config));
                }
                var headers = switch (String.valueOf(auth.getType())) {
                    case "anthropic" -> List.of("Authorization", "x-api-key");
                    case "header" -> auth.getName() == null ? List.<String>of() : List.of(auth.getName());
                    case "basic", "bearer" -> List.of("Authorization");
                    default -> List.<String>of();
                };
                for (var namespace : namespaces) {
                    if (!placeholders.containsKey(namespace)) {
                        missing.computeIfAbsent(new MissingPlaceholders(tool.name(), namespace),
                                key -> new java.util.LinkedHashSet<>()).addAll(auth.getDomains());
                    }
                    var patterns = placeholders.getOrDefault(namespace, List.of()).stream()
                            .map(p -> Pattern.compile("(?<![A-Za-z0-9._-])" + Pattern.quote(p.prefix() + ProofToken.MARKER)
                                    + "([0-9a-f]{" + ProofToken.DIGEST_HEX_CHARS + "})(?![A-Za-z0-9._-])"))
                            .toList();
                    var claims = placeholders.getOrDefault(namespace, List.of()).stream()
                            .map(p -> Pattern.compile("(?<![A-Za-z0-9._-])" + Pattern.quote(p.prefix()) + "(?:isx_|placeholder)"))
                            .toList();
                    for (var domain : auth.getDomains()) {
                        for (var header : headers) declared.add(new Carrier(domain, namespace, header, patterns, claims));
                    }
                }
            }
        }
        carriers = List.copyOf(declared);
    }

    /** Configuration diagnostics, emitted once per load, never on the request path. */
    public List<String> warnings() {
        return missing.entrySet().stream().map(entry -> entry.getKey().warning(entry.getValue())).toList();
    }

    /** A single-tool validator cannot judge declarations supplied by a borrowed namespace's owner. */
    public List<String> warningsForNamespace(String namespace) {
        return missing.entrySet().stream().filter(entry -> entry.getKey().namespace().equals(namespace))
                .map(entry -> entry.getKey().warning(entry.getValue())).toList();
    }

    /** Every namespace the destination can spend must prove itself; empty lists fail closed. */
    public record Presented(Map<String, List<String>> digests, boolean claimsProof) {}

    public Presented read(String domain, Function<String, List<String>> headers) {
        var result = new LinkedHashMap<String, List<String>>();
        boolean claimsProof = false;
        if (domain == null) return new Presented(result, false);
        for (var carrier : carriers) {
            if (!matchesDomain(carrier.domain(), domain)) continue;
            var found = result.computeIfAbsent(carrier.namespace(), ns -> new ArrayList<>());
            for (var value : headers.apply(carrier.header())) {
                if (value == null || value.length() > 16384) continue;
                if (carrier.header().equalsIgnoreCase("Authorization") && value.regionMatches(true, 0, "Basic ", 0, 6)) {
                    try {
                        value = new String(Base64.getDecoder().decode(value.substring(6).strip()), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                }
                for (var claim : carrier.claims()) if (claim.matcher(value).find()) claimsProof = true;
                for (var pattern : carrier.tokens()) {
                    var matcher = pattern.matcher(value);
                    while (matcher.find()) found.add(matcher.group(1));
                }
            }
        }
        return new Presented(result, claimsProof);
    }

    private static boolean matchesDomain(String declared, String domain) {
        return declared.equalsIgnoreCase(domain) || declared.startsWith("*.")
                && domain.toLowerCase(java.util.Locale.ROOT).endsWith(declared.substring(1).toLowerCase(java.util.Locale.ROOT));
    }
}
