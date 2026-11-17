package dev.incusspawn.config;

import dev.incusspawn.proxy.ProxyConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code maven-repositories:} in config.yaml (#2312): Maven repositories, beyond the four isx
 * caches itself, whose artifacts the proxy caches, each with what the proxy cannot find out about
 * it by itself.
 *
 * <pre>
 * maven-repositories:
 *   - url: https://maven.google.com/
 *     access: public
 *     confirm: sidecar              # or checksum-header
 *     replaces-releases: false
 *     fresh: 2h                     # optional, 0 by default
 *     max-stale: 7d                 # optional, 0 by default
 *   - url: https://nexus.example.com/repository/releases/
 *     access: private
 *     credentials: nexus            # the config namespace whose credential the proxy injects there
 *     confirm: checksum-header
 *     replaces-releases: false
 * </pre>
 *
 * <p>Host config only: a template, and above all a project-local definition, cannot add one, since
 * a cached repository decides what every other instance on the host is served. Kept as the raw YAML
 * value, so a mistyped section never fails the whole config.yaml, which would leave the proxy with
 * no credentials: an entry that does not pass every check is dropped with a warning naming it.
 * Every field that could loosen a protection is required, and the defaults only go the cautious
 * way: an entry with no sound confirmation is refused, never cached on hope.
 */
public final class MavenRepositories {

    /**
     * One declared repository.
     *
     * @param domain           the host, lower case
     * @param pathPrefix       the path before the Maven coordinates, starting and ending with {@code /}
     * @param checksumHeader   confirmed by the {@code X-Checksum-SHA1} the server computes, else by the {@code .sha1} sidecar
     * @param replacesReleases the bytes at a release path may be replaced, so a copy is confirmed before every serve
     * @param fresh            served with no check for this long after a confirmation
     * @param maxStale         until then, served at once and confirmed again in the background
     * @param credentials      the config namespace whose credential the proxy injects, or null for a public repository
     */
    public record Entry(String domain, String pathPrefix, boolean checksumHeader, boolean replacesReleases,
                        Duration fresh, Duration maxStale, String credentials) {

        public boolean isPrivate() {
            return credentials != null;
        }

        public String url() {
            return "https://" + domain + pathPrefix;
        }
    }

    /** Until a confirmation is trusted for longer, every hit is confirmed with upstream first. */
    public static final Duration DEFAULT_FRESH = Duration.ZERO;
    public static final Duration DEFAULT_MAX_STALE = Duration.ZERO;

    private static final Set<String> KEYS = Set.of("url", "access", "credentials", "confirm",
            "replaces-releases", "fresh", "max-stale");
    // A host name: labels of letters, digits and hyphens, at least two of them
    private static final Pattern HOST = Pattern.compile("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]");
    // A path of plain segments, so the prefix means one thing to the proxy and to the repository
    private static final Pattern PREFIX = Pattern.compile("/(?:[A-Za-z0-9._~-]+/)*");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9_-]*");

    private static final MavenRepositories NONE = new MavenRepositories(null);

    private final Object raw;

    private MavenRepositories(Object raw) {
        this.raw = raw;
    }

    /** The section as written, null when config.yaml has none. */
    static MavenRepositories of(Object raw) {
        return raw == null ? NONE : new MavenRepositories(raw);
    }

    /**
     * The entries that pass every check, in the order written; each one refused is named through
     * {@code warn} with the reason. {@code taken} are the domains the proxy intercepts for something
     * else, which an entry may not claim.
     */
    public List<Entry> entries(Set<String> taken, Consumer<String> warn) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            warn.accept("Ignoring maven-repositories: in config.yaml (expected a list of repositories,"
                    + " each with url:, access:, confirm: and replaces-releases:)");
            return List.of();
        }
        var entries = new ArrayList<Entry>();
        var seen = new HashSet<String>();
        for (int i = 0; i < list.size(); i++) {
            var item = list.get(i);
            var label = "maven-repositories[" + i + "]";
            if (item instanceof Map<?, ?> map && map.get("url") instanceof String url) label += " (" + url + ")";
            try {
                var entry = entry(item, taken);
                if (!seen.add(entry.url())) throw new Refused("it is listed more than once");
                entries.add(entry);
            } catch (Refused e) {
                warn.accept("Ignoring " + label + " in config.yaml: " + e.getMessage());
            }
        }
        return List.copyOf(entries);
    }

    /** The domains of the entries that pass every check: the proxy intercepts them. */
    public Set<String> domains(Set<String> taken) {
        var domains = new LinkedHashSet<String>();
        entries(taken, warning -> {}).forEach(e -> domains.add(e.domain()));
        return Set.copyOf(domains);
    }

    /** The domains no entry may claim: every one the proxy intercepts by itself. */
    public static Set<String> builtInTaken() {
        var taken = new HashSet<>(ProxyConfig.builtinInterceptedDomains());
        taken.add(ExposedHostPorts.HOST_NAME);
        return Set.copyOf(taken);
    }

    static final class Refused extends Exception {
        Refused(String reason) {
            super(reason, null, false, false);
        }
    }

    private static Entry entry(Object item, Set<String> taken) throws Refused {
        if (!(item instanceof Map<?, ?> map)) throw new Refused("expected a mapping with url:, access:, confirm: and replaces-releases:");
        for (var key : map.keySet()) {
            if (!KEYS.contains(String.valueOf(key))) throw new Refused("unknown key '" + key + "'");
        }
        var url = repositoryUrl(required(map, "url"), "url");
        var host = url.host();
        if (taken.contains(host)) throw new Refused(host + " is already intercepted by isx for something else");
        var prefix = url.pathPrefix();

        var access = required(map, "access");
        String credentials;
        switch (access) {
            case "public" -> {
                if (map.containsKey("credentials")) throw new Refused("credentials: is for access: private");
                credentials = null;
            }
            case "private" -> {
                credentials = required(map, "credentials");
                if (!NAMESPACE.matcher(credentials).matches()) throw new Refused("credentials: must name a config namespace, such as nexus");
            }
            default -> throw new Refused("access: must be public or private");
        }

        var confirm = required(map, "confirm");
        boolean header;
        switch (confirm) {
            case "checksum-header" -> header = true;
            case "sidecar" -> header = false;
            default -> throw new Refused("confirm: must be checksum-header or sidecar");
        }
        var replaces = map.get("replaces-releases");
        if (!(replaces instanceof Boolean replacesReleases)) throw new Refused("replaces-releases: must be true or false");
        if (!header && replacesReleases) {
            throw new Refused("a repository that replaces releases has no sound confirmation by sidecar,"
                    + " since a sidecar is uploaded apart from its artifact; it needs confirm: checksum-header");
        }
        if (!header && credentials != null) {
            throw new Refused("a private repository needs confirm: checksum-header: only a request for the artifact"
                    + " itself shows that its credential may still read it");
        }

        var fresh = duration(map, "fresh", DEFAULT_FRESH);
        var maxStale = duration(map, "max-stale", DEFAULT_MAX_STALE);
        if ((replacesReleases || credentials != null) && (!fresh.isZero() || !maxStale.isZero())) {
            throw new Refused("a copy from a repository that " + (credentials != null ? "is private" : "replaces releases")
                    + " is confirmed before every serve, so fresh: and max-stale: must be 0 or left out");
        }
        return new Entry(host, prefix, header, replacesReleases, fresh, maxStale, credentials);
    }

    /** A repository's URL, cut into the host, lower case, and the path prefix, starting and ending with {@code /}. */
    public record Url(String host, String pathPrefix) {
        @Override
        public String toString() {
            return "https://" + host + pathPrefix;
        }
    }

    /**
     * {@code url}, written under {@code key}, checked as a repository URL the proxy can reach and
     * hold one meaning for: https on port 443, a domain name outside {@code .internal}, and a path
     * of plain segments, so the prefix means one thing to the proxy and to the repository.
     */
    static Url repositoryUrl(String url, String key) throws Refused {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new Refused(key + " is not a URL");
        }
        if (!"https".equals(uri.getScheme())) throw new Refused(key + " must be https://, the only scheme the proxy intercepts");
        if (uri.getRawUserInfo() != null) throw new Refused(key + " must not hold a user or password"
                + (key.equals("url") ? ": name a credentials: namespace instead" : ""));
        if (uri.getPort() != -1 && uri.getPort() != 443) throw new Refused(key + " must use port 443, the only one the proxy intercepts");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) throw new Refused(key + " must not have a query or a fragment");
        var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(host).matches()) throw new Refused(key + " must name a host by its domain name, not an address");
        if (host.equals("internal") || host.endsWith(".internal")) throw new Refused("names under .internal are isx's own");
        var prefix = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (!prefix.endsWith("/")) prefix += "/";
        if (!PREFIX.matcher(prefix).matches()) throw new Refused("the " + key + "'s path must be plain segments (no '.', '..', '%' or empty segment)");
        for (var segment : prefix.split("/")) {
            if (segment.equals(".") || segment.equals("..")) throw new Refused("the " + key + "'s path must not hold '.' or '..'");
        }
        return new Url(host, prefix);
    }

    static String required(Map<?, ?> map, String key) throws Refused {
        var value = map.get(key);
        if (value == null) throw new Refused(key + ": is required");
        if (!(value instanceof String s) || s.isBlank()) throw new Refused(key + ": must be a string");
        return s.strip();
    }

    private static Duration duration(Map<?, ?> map, String key, Duration fallback) throws Refused {
        var value = map.get(key);
        if (value == null) return fallback;
        var parsed = value instanceof String || value instanceof Number
                ? ArtifactCacheConfig.parseDuration(String.valueOf(value)) : null;
        if (parsed == null) throw new Refused(key + ": '" + value + "' is not a duration (expected e.g. 30m, 2h, 7d or 0)");
        return parsed;
    }
}
