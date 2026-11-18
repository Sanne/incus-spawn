package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MavenRepositoriesTest {

    private final List<String> warnings = new ArrayList<>();

    private List<MavenRepositories.Entry> entries(Object raw) {
        return MavenRepositories.of(raw).entries(MavenRepositories.builtInTaken(), warnings::add);
    }

    private static Map<String, Object> entry(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        map.put("url", "https://maven.google.com/");
        map.put("access", "public");
        map.put("confirm", "sidecar");
        map.put("replaces-releases", false);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] == null) map.remove((String) keysAndValues[i]);
            else map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** The one entry refused, and the warning naming why. */
    private void assertRefused(Map<String, Object> entry, String reason) {
        assertEquals(List.of(), entries(List.of(entry)));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains(reason), warnings.getFirst());
        warnings.clear();
    }

    @Test
    void anEntryWithEveryRequiredFieldIsKeptWithCautiousTiers() {
        var kept = entries(List.of(entry("url", "https://Maven.Google.com")));
        assertEquals(List.of(new MavenRepositories.Entry("maven.google.com", "/", MavenRepositories.Confirm.SIDECAR, false,
                Duration.ZERO, Duration.ZERO, null)), kept);
        assertEquals(List.of(), warnings);
    }

    @Test
    void aPrivateEntryNamesTheNamespaceWhoseCredentialReachesIt() {
        var kept = entries(List.of(entry("url", "https://nexus.example.com/repository/releases",
                "access", "private", "credentials", "nexus", "confirm", "checksum-header")));
        assertEquals("/repository/releases/", kept.getFirst().pathPrefix());
        assertEquals("nexus", kept.getFirst().credentials());
        assertEquals(MavenRepositories.Confirm.CHECKSUM_HEADER, kept.getFirst().confirm());
    }

    @Test
    void nexus3IsConfirmedByTheSha1ItSendsAsItsETagPrivateOrReplacingReleases() {
        var kept = entries(List.of(entry("url", "https://nexus.example.com/repository/releases/",
                        "access", "private", "credentials", "nexus", "confirm", "etag-sha1"),
                entry("url", "https://nexus.example.com/repository/snapshots/", "confirm", "etag-sha1",
                        "replaces-releases", true)));
        assertEquals(List.of(), warnings);
        assertEquals(List.of(MavenRepositories.Confirm.ETAG_SHA1, MavenRepositories.Confirm.ETAG_SHA1),
                kept.stream().map(MavenRepositories.Entry::confirm).toList());
        assertRefused(entry("confirm", "etag"), "confirm: must be checksum-header, etag-sha1 or sidecar");
    }

    @Test
    void everyFieldThatCouldLoosenAProtectionIsRequired() {
        assertRefused(entry("access", null), "access: is required");
        assertRefused(entry("confirm", null), "confirm: is required");
        assertRefused(entry("replaces-releases", null), "replaces-releases: must be true or false");
        assertRefused(entry("access", "private", "confirm", "checksum-header"), "credentials: is required");
    }

    @Test
    void anEntryWithNoSoundConfirmationIsRefused() {
        assertRefused(entry("replaces-releases", true), "no sound confirmation by sidecar");
        assertRefused(entry("access", "private", "credentials", "nexus"), "needs confirm: checksum-header or etag-sha1");
        assertRefused(entry("confirm", "checksum-header", "replaces-releases", true, "fresh", "2h"),
                "fresh: and max-stale: must be 0");
        assertRefused(entry("access", "private", "credentials", "nexus", "confirm", "checksum-header",
                "max-stale", "1d"), "fresh: and max-stale: must be 0");
    }

    @Test
    void aDomainIsxInterceptsForSomethingElseIsRefused() {
        assertRefused(entry("url", "https://repo.maven.apache.org/maven2/"), "already intercepted");
        assertRefused(entry("url", "https://registry.npmjs.org/"), "already intercepted");
        assertRefused(entry("url", "https://host.isx.internal/"), ".internal");
        assertRefused(entry("url", "https://repo.example.internal/"), ".internal");
    }

    @Test
    void aUrlTheProxyCannotInterceptOrThatHidesAPathIsRefused() {
        assertRefused(entry("url", "http://maven.google.com/"), "https://");
        assertRefused(entry("url", "https://maven.google.com:8443/"), "port 443");
        assertRefused(entry("url", "https://user:pw@maven.google.com/"), "user or password");
        assertRefused(entry("url", "https://10.0.0.1/"), "domain name");
        assertRefused(entry("url", "https://maven.google.com/a/../b/"), "'..'");
        assertRefused(entry("url", "https://maven.google.com/a%2Fb/"), "plain segments");
        assertRefused(entry("url", "https://maven.google.com/?x=1"), "query");
    }

    @Test
    void aMistypedValueDropsItsEntryAloneNeverTheSection() {
        var kept = entries(List.of(entry("fresh", "soon"), entry("url", "https://jitpack.io/"),
                entry("confim", "sidecar"), "not a mapping", entry("url", "https://jitpack.io/")));
        assertEquals(List.of("jitpack.io"), kept.stream().map(MavenRepositories.Entry::domain).toList());
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("not a duration"));
        assertTrue(warnings.get(1).contains("unknown key 'confim'"));
        assertTrue(warnings.get(3).contains("more than once"));
    }

    @Test
    void aSectionThatIsNotAListIsIgnoredWithAWarning() {
        assertEquals(List.of(), entries(Map.of("url", "https://jitpack.io/")));
        assertEquals(1, warnings.size());
        assertEquals(List.of(), MavenRepositories.of(null).entries(Set.of(), warnings::add));
    }

    @Test
    void theDomainsAreThoseOfTheEntriesKept() {
        var raw = List.of(entry(), entry("url", "https://maven.google.com/other/"), entry("access", "secret"));
        assertEquals(Set.of("maven.google.com"), MavenRepositories.of(raw).domains(MavenRepositories.builtInTaken()));
    }
}
