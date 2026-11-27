package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MavenMirrorsTest {

    private final List<String> warnings = new ArrayList<>();

    private List<MavenMirrors.Entry> entries(Object raw) {
        return MavenMirrors.of(raw).entries(warnings::add);
    }

    private static Map<String, Object> entry(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        map.put("repository", "https://repo.maven.apache.org/maven2/");
        map.put("mirror", "https://maven-central.storage-download.googleapis.com/maven2/");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] == null) map.remove((String) keysAndValues[i]);
            else map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private void assertRefused(Object raw, String reason) {
        assertEquals(List.of(), entries(raw));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains(reason), warnings.getFirst());
        warnings.clear();
    }

    @Test
    void noSectionNamesNoMirror() {
        assertEquals(List.of(), entries(null));
        assertEquals(List.of(), new SpawnConfig().mavenMirrors().entries(warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anEntryIsCutIntoHostAndPrefix() {
        var kept = entries(List.of(entry("repository", "https://Repo1.Maven.org/maven2",
                "mirror", "https://mirror.example.com")));
        assertEquals(List.of(new MavenMirrors.Entry(new MavenRepositories.Url("repo1.maven.org", "/maven2/"),
                new MavenRepositories.Url("mirror.example.com", "/"))), kept);
        assertEquals(List.of(), warnings);
    }

    @Test
    void aWrongEntryIsDroppedWithTheReason() {
        assertRefused(Map.of("repository", "x"), "expected a list");
        assertRefused(List.of("https://mirror.example.com/"), "expected a mapping");
        assertRefused(List.of(entry("mirrors", "https://x.example.com/")), "unknown key 'mirrors'");
        assertRefused(List.of(entry("mirror", null)), "mirror: is required");
        assertRefused(List.of(entry("mirror", "http://mirror.example.com/")), "mirror must be https://");
        assertRefused(List.of(entry("mirror", "https://user:pw@mirror.example.com/")), "must not hold a user");
        assertRefused(List.of(entry("mirror", "https://127.0.0.1/")), "by its name, not an address");
        assertRefused(List.of(entry("mirror", "https://127.0.0.1:8080/")), "by its name, not an address");
        assertRefused(List.of(entry("mirror", "https://[::1]:8443/")), "by its name, not an address");
        assertRefused(List.of(entry("mirror", "https://3232235777/")), "by its name, not an address");
        assertRefused(List.of(entry("mirror", "https://nas:0/")), "a port from 1 to 65535");
        assertRefused(List.of(entry("mirror", "https://nas.internal:8080/")), ".internal");
        assertRefused(List.of(entry("mirror", "https://nas:8080/?x=1")), "query or a fragment");
        assertRefused(List.of(entry("mirror", "https://mcp.isx.internal/")), ".internal");
        assertRefused(List.of(entry("mirror", "https://mirror.example.com/a/../b/")), "'.' or '..'");
        assertRefused(List.of(entry("mirror", "https://repo.maven.apache.org/other/")), "another host");
        // Another port of the repository's host is still that host
        assertRefused(List.of(entry("mirror", "https://repo.maven.apache.org:8443/other/")), "another host");
    }

    @Test
    void aMirrorMayUseAnyPortAndAHostNameOfOneLabel() {
        // A stock Reposilite listens on 8080, often on a host known by one label (#2681)
        var kept = entries(List.of(entry("mirror", "https://NAS:8080/releases"),
                entry("repository", "https://repo1.maven.org/maven2/", "mirror", "https://nas.example.net:8443/central/")));
        assertEquals(List.of(), warnings);
        assertEquals(new MavenRepositories.Url("nas", 8080, "/releases/"), kept.get(0).mirror());
        assertEquals("nas:8080", kept.get(0).mirror().authority());
        assertEquals("https://nas.example.net:8443/central/", kept.get(1).mirror().toString());
        // Port 443 written out is the same mirror as one without a port
        assertEquals(new MavenRepositories.Url("nas", "/"),
                entries(List.of(entry("mirror", "https://nas:443/"))).getFirst().mirror());
    }

    @Test
    void aRepositoryKeepsPort443AndADomainName() {
        assertRefused(List.of(entry("repository", "https://repo.maven.apache.org:8443/maven2/")), "port 443");
        assertRefused(List.of(entry("repository", "https://nas/maven2/")), "by its domain name");
    }

    @Test
    void aRepositoryHasOneMirror() {
        var kept = entries(List.of(entry(), entry("mirror", "https://second.example.com/")));
        assertEquals(1, kept.size());
        assertEquals("maven-central.storage-download.googleapis.com", kept.getFirst().mirror().host());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("has a mirror already"), warnings.getFirst());
    }

    @Test
    void theSectionIsReadFromConfigYamlAndWrittenBackAsItWasRead() throws Exception {
        var config = Jjq.spawnConfig("""
                maven-mirrors:
                  - repository: https://repo.maven.apache.org/maven2/
                    mirror: https://mirror.example.com/maven2/
                  - oops
                """);
        assertEquals(1, config.mavenMirrors().entries(warnings::add).size());
        var yaml = Jjq.toYaml(config);
        assertTrue(yaml.contains("maven-mirrors"), yaml);
        assertTrue(yaml.contains("oops"), yaml);
    }
}
