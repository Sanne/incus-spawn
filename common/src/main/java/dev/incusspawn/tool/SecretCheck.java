package dev.incusspawn.tool;

import dev.incusspawn.proxy.ResolvedToolProxy;
import dev.incusspawn.proxy.ToolProxyResolver;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How {@code isx init} checks a secret a tool declares, before saving it (#2566).
 *
 * <p>A {@code secret: true} configuration entry declares a {@code check:}: a {@code url} isx
 * sends one {@code GET} to, or {@code none} with why nothing can check the secret. The request is
 * authenticated exactly as the tool's own auth entry for the url's host would inject the secret,
 * so a check sends the secret only where the proxy already sends it: the host must be a literal
 * domain of an auth entry that reads this entry, never a configured domain, a wildcard or a
 * repeated entry's.
 */
public final class SecretCheck {

    private SecretCheck() {}

    /** A check that sends {@code GET url}. */
    public static ToolDef.CheckDef url(String url) {
        var check = new ToolDef.CheckDef();
        check.setUrl(url);
        return check;
    }

    /** No check, for {@code why}. */
    public static ToolDef.CheckDef none(String why) {
        var check = new ToolDef.CheckDef();
        check.setNone(why);
        return check;
    }

    /** The one request a check sends: {@code GET uri} with {@code header: value}. */
    public record Request(URI uri, String header, String value) {
        /** The host the check asks, as a person reads it. */
        public String host() {
            return uri.getHost();
        }

        @Override
        public String toString() {
            // Never the credential itself
            return "Request[" + uri + "]";
        }
    }

    /**
     * What is wrong with entry {@code key}'s check, or null when it declares a sound one,
     * {@code none} included. An entry with no {@code check:} at all is a problem too: the caller
     * decides whether that is an error (built-in tools) or allowed (a user's own).
     */
    public static String problem(ToolDef.ProxyDef proxy, String key) {
        var entry = proxy.getConfiguration().get(key);
        if (entry == null) return "is not a configuration entry";
        var check = entry.getCheck();
        if (check == null) return "declares no check";
        if (!entry.isSecret()) return "has a check but is not a secret";
        var hasUrl = !check.getUrl().isEmpty();
        var hasNone = !check.getNone().isEmpty();
        if (hasUrl == hasNone) return "check needs exactly one of 'url' or 'none'";
        if (hasNone) return null;
        URI uri;
        try {
            uri = new URI(check.getUrl());
        } catch (java.net.URISyntaxException e) {
            return "check url is not a URL";
        }
        // The proxy injects only on the default port, so a check never goes to another
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return "check url must be https://<host>/..., with no user, port or fragment";
        }
        if (authFor(proxy, key, uri) == null) {
            return "check url's host " + uri.getHost() + " is not a domain of an auth entry that reads '" + key
                    + "' and whose paths cover the url";
        }
        return null;
    }

    /**
     * The request that checks entry {@code key} given the answers so far, by configuration key:
     * empty when the entry declares no sound url, or when the auth entry reads an answer not given.
     */
    public static Optional<Request> request(String toolName, ToolDef.ProxyDef proxy, String key, Map<String, String> answers) {
        if (problem(proxy, key) != null) return Optional.empty();
        var check = proxy.getConfiguration().get(key).getCheck();
        if (check.getUrl().isEmpty()) return Optional.empty();
        var uri = URI.create(check.getUrl());
        var auth = authFor(proxy, key, uri);
        for (var ref : ToolProxyResolver.extractReferencedKeys(auth)) {
            var answer = answers.get(ref);
            if (answer == null || answer.isBlank()) return Optional.empty();
        }
        var resolved = new ResolvedToolProxy(toolName, uri.getHost(), auth, answers);
        var value = resolved.computeHeaderValue();
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(new Request(uri, resolved.headerName(), value));
    }

    /**
     * The plain auth entry that reads {@code key}, serves {@code uri}'s host as a literal domain and
     * whose {@code paths:} cover its path, or null: the proxy would inject the key nowhere else.
     */
    private static ToolDef.AuthDef authFor(ToolDef.ProxyDef proxy, String key, URI uri) {
        var wanted = uri.getHost().toLowerCase(Locale.ROOT);
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        var target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        for (var auth : proxy.getAuth()) {
            if (auth.getForEach() != null || auth.getType() == null) continue;
            if (!java.util.Set.of("basic", "bearer", "header").contains(auth.getType())) continue;
            if (!ToolProxyResolver.extractReferencedKeys(auth).contains(key)) continue;
            if (!new ResolvedToolProxy("", wanted, auth, Map.of()).matchesPath(target)) continue;
            for (var domain : auth.getDomains()) {
                if (ToolProxyResolver.isConfiguredDomain(domain) || domain.contains("*")) continue;
                if (domain.toLowerCase(Locale.ROOT).equals(wanted)) return auth;
            }
        }
        return null;
    }
}
