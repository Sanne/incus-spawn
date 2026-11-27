package dev.incusspawn.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code maven-mirrors:} in config.yaml (#2304): a mirror the proxy asks first for an artifact
 * that one of its cached Maven repositories does not hold yet, so a cold build still works while
 * that repository rate-limits the host. The repository itself is asked when the mirror does not
 * have the artifact, or cannot serve it.
 *
 * <pre>
 * maven-mirrors:
 *   - repository: https://repo.maven.apache.org/maven2/
 *     mirror: https://maven-central.storage-download.googleapis.com/maven2/
 * </pre>
 *
 * <p>Off unless listed, and host config only: a template, and above all a project-local
 * definition, cannot name one, since a mirror decides what every instance on the host is served.
 * Which repositories may take a mirror is the proxy's to say, since only it knows the built-in
 * ones; this class checks the shape of each entry. Kept as the raw YAML value, so a mistyped
 * section never fails the whole config.yaml: an entry that does not pass is dropped with a
 * warning naming it.
 */
public final class MavenMirrors {

    /**
     * One mirror.
     *
     * @param repository the cached repository it stands in for
     * @param mirror     where the same paths are asked first
     */
    public record Entry(MavenRepositories.Url repository, MavenRepositories.Url mirror) {}

    private static final Set<String> KEYS = Set.of("repository", "mirror");

    private static final MavenMirrors NONE = new MavenMirrors(null);

    private final Object raw;

    private MavenMirrors(Object raw) {
        this.raw = raw;
    }

    /** The section as written, null when config.yaml has none. */
    static MavenMirrors of(Object raw) {
        return raw == null ? NONE : new MavenMirrors(raw);
    }

    /** The entries that pass every check, in the order written; each one refused is named through {@code warn}. */
    public List<Entry> entries(Consumer<String> warn) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            warn.accept("Ignoring maven-mirrors: in config.yaml (expected a list of mirrors, each with repository: and mirror:)");
            return List.of();
        }
        var entries = new ArrayList<Entry>();
        var seen = new HashSet<MavenRepositories.Url>();
        for (int i = 0; i < list.size(); i++) {
            var item = list.get(i);
            var label = "maven-mirrors[" + i + "]";
            if (item instanceof Map<?, ?> map && map.get("repository") instanceof String url) label += " (" + url + ")";
            try {
                var entry = entry(item);
                if (!seen.add(entry.repository())) throw new MavenRepositories.Refused("its repository has a mirror already");
                entries.add(entry);
            } catch (MavenRepositories.Refused e) {
                warn.accept("Ignoring " + label + " in config.yaml: " + e.getMessage());
            }
        }
        return List.copyOf(entries);
    }

    private static Entry entry(Object item) throws MavenRepositories.Refused {
        if (!(item instanceof Map<?, ?> map)) throw new MavenRepositories.Refused("expected a mapping with repository: and mirror:");
        for (var key : map.keySet()) {
            if (!KEYS.contains(String.valueOf(key))) throw new MavenRepositories.Refused(MappedDefinition.unknownKey(key, KEYS, ""));
        }
        var repository = MavenRepositories.repositoryUrl(MavenRepositories.required(map, "repository"), "repository");
        var mirror = MavenRepositories.repositoryUrl(MavenRepositories.required(map, "mirror"), "mirror");
        if (mirror.host().equals(repository.host())) {
            throw new MavenRepositories.Refused("the mirror must be on another host than its repository");
        }
        return new Entry(repository, mirror);
    }
}
