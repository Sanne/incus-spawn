package dev.incusspawn.config;

import dev.incusspawn.util.MemorySize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code confirmed-cache:} in config.yaml (#2518): HTTPS domains whose downloads the proxy caches
 * on the host, serving a stored copy only after upstream answered the client's own request with
 * {@code 304 Not Modified} for that copy's {@code ETag}.
 *
 * <pre>
 * confirmed-cache:
 *   domains:
 *     - "*.blob.core.windows.net"   # GitHub Actions caches
 *   max-size: 20GiB                 # optional, 20GiB by default
 * </pre>
 *
 * <p>Host config only, and off while there is no key: every instance's traffic to a listed domain
 * is then intercepted, so a tool that pins that domain's certificate stops working, and what the
 * domain serves, private content included, is kept on the host's disk. Kept as the raw YAML value,
 * so a mistyped section never fails the whole config.yaml: a domain that does not pass every check
 * is dropped with a warning naming it, and a section that cannot be read caches nothing.
 */
public final class ConfirmedCache {

    /** The keys {@code confirmed-cache:} takes. */
    static final Set<String> KEYS = Set.of("domains", "max-size");

    /** What the stored copies may take on disk when {@code max-size:} does not say. */
    public static final long DEFAULT_MAX_SIZE = 20 * MemorySize.GIB;

    // A host name, lower case, at least two labels: never an address, never a bare top-level name
    private static final Pattern HOST = Pattern.compile(
            "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]");

    private static final ConfirmedCache NONE = new ConfirmedCache(null);

    private final Object raw;

    private ConfirmedCache(Object raw) {
        this.raw = raw;
    }

    /** The key as written, null when config.yaml has none. */
    static ConfirmedCache of(Object raw) {
        return raw == null ? NONE : new ConfirmedCache(raw);
    }

    /**
     * The domains that pass every check, each once, in the order written: a host name, or
     * {@code *.} and a name of at least two labels for every name below it. None of them may
     * overlap one of {@code taken}, the domains isx serves otherwise, since the cache would take
     * their requests; nor lie under {@code .internal}, isx's own names.
     */
    public List<String> domains(Set<String> taken, Consumer<String> warn) {
        var section = section(warn);
        if (section == null) return List.of();
        var value = section.get("domains");
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            warn.accept("Ignoring confirmed-cache.domains in config.yaml: expected a list of domains");
            return List.of();
        }
        var domains = new LinkedHashSet<String>();
        for (var item : list) {
            var refusal = refusal(item, taken);
            if (refusal != null) {
                warn.accept("Ignoring confirmed-cache domain " + item + " in config.yaml: " + refusal);
            } else if (!domains.add((String) item)) {
                warn.accept("Ignoring confirmed-cache domain " + item + " in config.yaml: it is listed more than once");
            }
        }
        // Two entries may overlap each other: the more specific one is no different a cache
        return List.copyOf(domains);
    }

    /**
     * What the stored copies may take on disk: {@code max-size:}, else {@link #DEFAULT_MAX_SIZE}.
     * Only a wrong value is warned about here; a section that cannot be read is {@link #domains}' to say.
     */
    public long maxSize(Consumer<String> warn) {
        var section = section(unread -> {});
        var value = section == null ? null : section.get("max-size");
        if (value == null) return DEFAULT_MAX_SIZE;
        var bytes = value instanceof String text ? MemorySize.parse(text) : -1;
        if (bytes <= 0) {
            warn.accept("Ignoring confirmed-cache.max-size in config.yaml: expected a size such as 20GiB;"
                    + " using " + MemorySize.shown(DEFAULT_MAX_SIZE));
            return DEFAULT_MAX_SIZE;
        }
        return bytes;
    }

    /** The section as a mapping of known keys, or null when there is none or it cannot be read. */
    private Map<?, ?> section(Consumer<String> warn) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) {
            warn.accept("Ignoring confirmed-cache: in config.yaml: expected a mapping with domains: and max-size:");
            return null;
        }
        for (var key : map.keySet()) {
            if (!KEYS.contains(String.valueOf(key))) {
                warn.accept("Ignoring confirmed-cache: in config.yaml: unknown key '" + key + "'");
                return null;
            }
        }
        return map;
    }

    private static String refusal(Object item, Set<String> taken) {
        if (!(item instanceof String domain)) return "expected a domain name";
        var name = domain.startsWith("*.") ? domain.substring(2) : domain;
        if (!domain.equals(domain.toLowerCase(Locale.ROOT)) || !HOST.matcher(name).matches()) {
            return "expected a lower-case host name such as example.com, or *. and one such as *.example.com";
        }
        if (name.equals("internal") || name.endsWith(".internal")) return "names under .internal are isx's own";
        for (var other : taken) {
            if (overlap(domain, other)) return "isx serves " + other + " otherwise";
        }
        return null;
    }

    /** The name bridge DNS and the certificates intercept for {@code domain}: its own, or its wildcard's base. */
    public static String interceptedName(String domain) {
        return domain.startsWith("*.") ? domain.substring(2) : domain;
    }

    /** Whether the request host {@code host} is one {@code domain} lists. */
    public static boolean covers(String domain, String host) {
        return domain.startsWith("*.") ? host.endsWith(domain.substring(1)) : domain.equals(host);
    }

    /**
     * Whether some host is both {@code a}'s and {@code b}'s, each an exact name or a {@code *.}
     * wildcard: equal names, a name under the other's wildcard, or one wildcard inside the other.
     */
    public static boolean overlap(String a, String b) {
        var aWild = a.startsWith("*.");
        var bWild = b.startsWith("*.");
        if (!aWild && !bWild) return a.equals(b);
        if (aWild && !bWild) return covers(a, b);
        if (!aWild) return covers(b, a);
        var as = a.substring(1);
        var bs = b.substring(1);
        return as.endsWith(bs) || bs.endsWith(as);
    }

    /** The first of {@code domains} that overlaps one of {@code claimed}, as {@code [domain, claimed]}, or null. */
    public static String[] firstOverlap(Collection<String> domains, Collection<String> claimed) {
        for (var domain : domains) {
            for (var other : claimed) {
                if (overlap(domain, other)) return new String[] {domain, other};
            }
        }
        return null;
    }

    /** {@code domains} less those that overlap one of {@code claimed}, each dropped with a warning naming it. */
    public static List<String> without(List<String> domains, Collection<String> claimed, String why,
                                       Consumer<String> warn) {
        var kept = new ArrayList<String>();
        for (var domain : domains) {
            var clash = firstOverlap(List.of(domain), claimed);
            if (clash == null) {
                kept.add(domain);
            } else {
                warn.accept("Ignoring confirmed-cache domain " + domain + " in config.yaml: " + why + " " + clash[1]);
            }
        }
        return List.copyOf(kept);
    }
}
