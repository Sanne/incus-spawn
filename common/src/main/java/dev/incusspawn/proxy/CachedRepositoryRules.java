package dev.incusspawn.proxy;

import dev.incusspawn.config.MavenMirrors;
import dev.incusspawn.config.MavenRepositories;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Which of config.yaml's {@code maven-repositories:} and {@code maven-mirrors:} entries the proxy ignores
 * against the tool proxy entries, and why (#2312, #2304). The proxy's {@code CachedRepository} and
 * {@code isx validate} both ask here, so the command warns about exactly what the proxy drops (#2464).
 */
public final class CachedRepositoryRules {

    private CachedRepositoryRules() {}

    /**
     * What a mirror's check needs of a repository the proxy caches.
     *
     * @param servesOnlyConfirmed whether each copy is confirmed before every serve
     * @param maven               whether it has the Maven layout (not the Gradle distributions')
     */
    public record Cached(String domain, String pathPrefix, boolean isPrivate, boolean servesOnlyConfirmed, boolean maven) {}

    /** The repositories isx caches whatever config.yaml says, as a mirror sees them; the proxy's own list agrees (its test). */
    public static final List<Cached> BUILT_IN = List.of(
            new Cached("repo.maven.apache.org", "/maven2/", false, false, true),
            new Cached("repo1.maven.org", "/maven2/", false, false, true),
            new Cached("plugins.gradle.org", "/m2/", false, false, true),
            new Cached("services.gradle.org", "/distributions/", false, false, false));

    /**
     * The warning for a {@code maven-repositories:} entry the proxy ignores, or null when it caches it: a
     * public repository on a domain a tool injects a credential for, since its cache would take the
     * domain's requests from that injection, or a private one with no entry of its own namespace there.
     */
    public static String repositoryRefusal(MavenRepositories.Entry entry, Collection<ResolvedToolProxy> toolProxies) {
        var injecting = toolProxies.stream().filter(tp -> covers(tp.domain(), entry.domain())).toList();
        if (!entry.isPrivate() && !injecting.isEmpty()) {
            return "Ignoring maven-repositories " + entry.url() + " in config.yaml: a tool injects a credential"
                    + " at " + entry.domain() + ", so it is private; declare it access: private with credentials: "
                    + injecting.getFirst().namespaces().stream().sorted().findFirst().orElse("<namespace>");
        }
        if (entry.isPrivate() && injecting.stream().noneMatch(tp -> tp.namespaces().contains(entry.credentials()))) {
            return "Ignoring maven-repositories " + entry.url() + " in config.yaml: no tool injects a "
                    + entry.credentials() + " credential at " + entry.domain()
                    + ", so its requests are relayed uncached";
        }
        return null;
    }

    /** The index in {@code repositories} of the one {@code entry} mirrors, or -1. */
    public static int indexOf(MavenMirrors.Entry entry, List<Cached> repositories) {
        var url = entry.repository();
        var index = -1;
        for (int i = 0; i < repositories.size(); i++) {
            var r = repositories.get(i);
            if (r.domain().equals(url.host()) && r.pathPrefix().equals(url.pathPrefix())) index = i;
        }
        return index;
    }

    /**
     * The warning for a {@code maven-mirrors:} entry the proxy ignores, or null when it attaches it: one
     * whose repository isx does not cache, or one for a repository it cannot stand in for. That is a
     * private one, whose credential must never reach another host; one confirmed before every serve
     * anyway; and the Gradle distributions, laid out apart.
     */
    public static String mirrorRefusal(MavenMirrors.Entry entry, List<Cached> repositories) {
        var url = entry.repository();
        var index = indexOf(entry, repositories);
        var refusal = index < 0 ? "isx caches no repository at " + url
                + " (the built-in ones, or one maven-repositories: declares)"
                : repositories.get(index).isPrivate() ? "a private repository's credential never goes to another host"
                : repositories.get(index).servesOnlyConfirmed()
                ? "a repository that replaces releases is confirmed before every serve, so a mirror cannot stand in for it"
                : !repositories.get(index).maven() ? "only a Maven repository may have a mirror"
                : null;
        return refusal == null ? null : "Ignoring maven-mirrors " + url + " in config.yaml: " + refusal;
    }

    /** The repository the proxy caches for an entry it does not ignore. */
    public static Cached cached(MavenRepositories.Entry entry) {
        return new Cached(entry.domain(), entry.pathPrefix(), entry.isPrivate(),
                entry.replacesReleases() || entry.isPrivate(), true);
    }

    /** Every warning the proxy's second pass gives for {@code repositories} and {@code mirrors}: the repositories', then the mirrors'. */
    public static void check(List<MavenRepositories.Entry> repositories, List<MavenMirrors.Entry> mirrors,
                             Collection<ResolvedToolProxy> toolProxies, Consumer<String> warn) {
        var cached = new ArrayList<>(BUILT_IN);
        for (var entry : repositories) {
            var refusal = repositoryRefusal(entry, toolProxies);
            if (refusal != null) warn.accept(refusal);
            else cached.add(cached(entry));
        }
        for (var mirror : mirrors) {
            var refusal = mirrorRefusal(mirror, cached);
            if (refusal != null) warn.accept(refusal);
        }
    }

    /** Whether a tool proxy entry for {@code entryDomain} (exact, or {@code *.suffix}) covers {@code domain}. */
    static boolean covers(String entryDomain, String domain) {
        if (entryDomain.startsWith("*.")) {
            var base = entryDomain.substring(2);
            return domain.equals(base) || domain.endsWith("." + base);
        }
        return entryDomain.equals(domain);
    }
}
