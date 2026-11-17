package dev.incusspawn.proxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A repository whose artifacts the proxy caches: the four isx names itself and those declared in
 * config.yaml's {@code maven-repositories:} ({@link CachedRepositories}), one shape for both
 * (#2312). A domain may hold several, under different path prefixes (a company's repository
 * manager serves many); a path under none of them is relayed.
 *
 * @param domain       the host
 * @param pathPrefix   the path before the Maven coordinates, starting and ending with {@code /}
 * @param layout       how a path maps to a cached file and its checksum
 * @param revalidation how a cached copy is confirmed, and how long a confirmation is trusted
 * @param credentials  the config namespace whose injected credential reaches this repository,
 *                     or null for a public one
 */
record CachedRepository(String domain, String pathPrefix, Layout layout, Revalidation revalidation,
                        String credentials) {

    /** How a repository lays out what it serves. */
    enum Layout {
        /** Maven coordinates under the prefix, each artifact beside its {@code .sha1}. */
        MAVEN,
        /** Gradle distribution archives, each beside its {@code .sha256} (services.gradle.org). */
        GRADLE_DISTRIBUTIONS
    }

    /** Whether this repository is private: its copies are kept and confirmed per credential. */
    boolean isPrivate() {
        return credentials != null;
    }

    /** Whether {@code path} lies under this repository's prefix. */
    boolean holds(String path) {
        return path.startsWith(pathPrefix);
    }

    /** Maven Central, its two names. */
    static final CachedRepository CENTRAL = new CachedRepository("repo.maven.apache.org", "/maven2/",
            Layout.MAVEN, Revalidation.HEAD_CHECKSUM, null);
    static final CachedRepository CENTRAL_REPO1 = new CachedRepository("repo1.maven.org", "/maven2/",
            Layout.MAVEN, Revalidation.HEAD_CHECKSUM, null);
    /**
     * The Gradle Plugin Portal. Authors delete versions there (7 days for authors, later via
     * support, a malicious plugin among them), often enough that a withdrawal is never left
     * unnoticed: its tiers are zero, so every hit is confirmed first.
     */
    static final CachedRepository PLUGIN_PORTAL = new CachedRepository("plugins.gradle.org", "/m2/",
            Layout.MAVEN, Revalidation.SIDECAR.trustedFor(ArtifactCacheTiers.CONFIRM_EVERY_HIT), null);
    /** Gradle distributions, which Gradle does not withdraw. */
    static final CachedRepository GRADLE_DISTRIBUTIONS = new CachedRepository("services.gradle.org",
            "/distributions/", Layout.GRADLE_DISTRIBUTIONS, Revalidation.SIDECAR, null);

    /** The repositories isx caches whatever config.yaml says. */
    static final List<CachedRepository> BUILT_IN = List.of(CENTRAL, CENTRAL_REPO1, PLUGIN_PORTAL, GRADLE_DISTRIBUTIONS);

    /** Every repository by domain, the longest prefix first, so the first that holds a path is the one. */
    static Map<String, List<CachedRepository>> byDomain(List<CachedRepository> repositories) {
        var byDomain = new HashMap<String, List<CachedRepository>>();
        for (var repository : repositories) {
            byDomain.computeIfAbsent(repository.domain(), d -> new ArrayList<>()).add(repository);
        }
        var sorted = new HashMap<String, List<CachedRepository>>();
        byDomain.forEach((domain, list) -> {
            list.sort(Comparator.comparingInt((CachedRepository r) -> r.pathPrefix().length()).reversed());
            sorted.put(domain, List.copyOf(list));
        });
        return Map.copyOf(sorted);
    }

    /** The built-in domains, which a configured repository may not take. */
    static Set<String> builtInDomains() {
        var domains = new java.util.HashSet<String>();
        BUILT_IN.forEach(r -> domains.add(r.domain()));
        return Set.copyOf(domains);
    }
}
