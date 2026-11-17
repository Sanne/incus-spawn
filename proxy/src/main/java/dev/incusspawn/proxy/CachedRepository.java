package dev.incusspawn.proxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.incusspawn.config.MavenMirrors;
import dev.incusspawn.config.MavenRepositories;

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
 * @param mirror       where a cache miss is asked first (config.yaml's {@code maven-mirrors:},
 *                     #2304), or null to ask only this repository
 */
record CachedRepository(String domain, String pathPrefix, Layout layout, Revalidation revalidation,
                        String credentials, MavenRepositories.Url mirror) {

    CachedRepository(String domain, String pathPrefix, Layout layout, Revalidation revalidation, String credentials) {
        this(domain, pathPrefix, layout, revalidation, credentials, null);
    }

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

    /**
     * Where the mirror holds the artifact this repository holds at {@code path}, or null when
     * there is no mirror: the same path under the mirror's prefix.
     */
    MirrorSource mirrorOf(String path) {
        return mirror == null || !holds(path) ? null
                : new MirrorSource(mirror.host(), mirror.pathPrefix() + path.substring(pathPrefix.length()));
    }

    /** One artifact's place on a mirror. */
    record MirrorSource(String host, String path) {}

    /** Whether {@code path} lies under this repository's prefix. */
    boolean holds(String path) {
        return path.startsWith(pathPrefix);
    }

    /**
     * Maven Central, its two names. Its tiers are longer than the others' unless config.yaml's
     * {@code artifact-cache:} sets them ({@link ArtifactCacheTiers#CENTRAL_DEFAULT}), since a
     * build's every confirmation goes to Central.
     */
    static final CachedRepository CENTRAL = new CachedRepository("repo.maven.apache.org", "/maven2/",
            Layout.MAVEN, Revalidation.HEAD_CHECKSUM.trustedByDefaultFor(ArtifactCacheTiers.CENTRAL_DEFAULT), null);
    static final CachedRepository CENTRAL_REPO1 = new CachedRepository("repo1.maven.org", "/maven2/",
            Layout.MAVEN, Revalidation.HEAD_CHECKSUM.trustedByDefaultFor(ArtifactCacheTiers.CENTRAL_DEFAULT), null);
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

    /**
     * The built-in repositories and those of config.yaml's {@code maven-repositories:} that hold
     * against the tool proxy entries {@code toolProxies} (every account's): a public repository on a
     * domain a tool injects a credential for is dropped, since its cache would take the domain's
     * requests from that injection; a private one needs an entry of its own namespace there.
     */
    static List<CachedRepository> withConfigured(List<dev.incusspawn.config.MavenRepositories.Entry> entries,
                                                 java.util.Collection<ResolvedToolProxy> toolProxies,
                                                 java.util.function.Consumer<String> warn) {
        var all = new ArrayList<>(BUILT_IN);
        for (var entry : entries) {
            var injecting = toolProxies.stream().filter(tp -> covers(tp.domain(), entry.domain())).toList();
            if (!entry.isPrivate() && !injecting.isEmpty()) {
                warn.accept("Ignoring maven-repositories " + entry.url() + " in config.yaml: a tool injects a credential"
                        + " at " + entry.domain() + ", so it is private; declare it access: private with credentials: "
                        + injecting.getFirst().namespaces().stream().sorted().findFirst().orElse("<namespace>"));
                continue;
            }
            if (entry.isPrivate() && injecting.stream().noneMatch(tp -> tp.namespaces().contains(entry.credentials()))) {
                warn.accept("Ignoring maven-repositories " + entry.url() + " in config.yaml: no tool injects a "
                        + entry.credentials() + " credential at " + entry.domain()
                        + ", so its requests are relayed uncached");
                continue;
            }
            var revalidation = (entry.checksumHeader() ? Revalidation.HEAD_CHECKSUM : Revalidation.SIDECAR)
                    .trustedFor(new ArtifactCacheTiers(entry.fresh(), entry.maxStale()));
            if (entry.replacesReleases() || entry.isPrivate()) revalidation = revalidation.onlyConfirmed();
            all.add(new CachedRepository(entry.domain(), entry.pathPrefix(), Layout.MAVEN, revalidation, entry.credentials()));
        }
        return List.copyOf(all);
    }

    /**
     * {@code repositories} with config.yaml's {@code maven-mirrors:} attached (#2304). A mirror
     * whose repository is not one of them is dropped with a warning naming why, as is one for a
     * repository it cannot stand in for: a private one, whose credential must never reach another
     * host and whose copies are confirmed before every serve; one that replaces releases, whose
     * copies are confirmed before every serve anyway; and the Gradle distributions, laid out apart.
     */
    static List<CachedRepository> withMirrors(List<CachedRepository> repositories,
                                              List<MavenMirrors.Entry> mirrors, Consumer<String> warn) {
        var all = new ArrayList<>(repositories);
        for (var entry : mirrors) {
            var url = entry.repository();
            var index = -1;
            for (int i = 0; i < all.size(); i++) {
                var r = all.get(i);
                if (r.domain().equals(url.host()) && r.pathPrefix().equals(url.pathPrefix())) index = i;
            }
            var refusal = index < 0 ? "isx caches no repository at " + url
                    + " (the built-in ones, or one maven-repositories: declares)"
                    : all.get(index).isPrivate() ? "a private repository's credential never goes to another host"
                    : all.get(index).revalidation().servesOnlyConfirmed()
                    ? "a repository that replaces releases is confirmed before every serve, so a mirror cannot stand in for it"
                    : all.get(index).layout() != Layout.MAVEN ? "only a Maven repository may have a mirror"
                    : null;
            if (refusal != null) {
                warn.accept("Ignoring maven-mirrors " + url + " in config.yaml: " + refusal);
                continue;
            }
            var r = all.get(index);
            all.set(index, new CachedRepository(r.domain(), r.pathPrefix(), r.layout(), r.revalidation(),
                    r.credentials(), entry.mirror()));
        }
        return List.copyOf(all);
    }

    /** Whether a tool proxy entry for {@code entryDomain} (exact, or {@code *.suffix}) covers {@code domain}. */
    private static boolean covers(String entryDomain, String domain) {
        if (entryDomain.startsWith("*.")) {
            var base = entryDomain.substring(2);
            return domain.equals(base) || domain.endsWith("." + base);
        }
        return entryDomain.equals(domain);
    }

    /** The built-in domains, which a configured repository may not take. */
    static final Set<String> BUILT_IN_DOMAINS = Set.copyOf(BUILT_IN.stream().map(CachedRepository::domain).toList());

    static Set<String> builtInDomains() {
        return BUILT_IN_DOMAINS;
    }
}
