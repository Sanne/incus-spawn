package dev.incusspawn.config;

import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.util.MemorySize;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code confirmed-cache:} (#2518): off without the key, and a domain that is malformed or would
 * take requests isx serves otherwise is dropped with a warning naming it, never failing the file.
 */
class ConfirmedCacheTest {

    private final List<String> warnings = new ArrayList<>();

    private List<String> domains(String yaml, Set<String> taken) throws Exception {
        return Jjq.spawnConfig(yaml).confirmedCache().domains(taken, warnings::add);
    }

    @Test
    void noKeyCachesNothingAndIsNotWritten() throws Exception {
        assertEquals(List.of(), domains("{}", Set.of()));
        assertEquals(ConfirmedCache.DEFAULT_MAX_SIZE, Jjq.spawnConfig("{}").confirmedCache().maxSize(warnings::add));
        assertFalse(Jjq.toYaml(new SpawnConfig()).contains("confirmed-cache"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void exactNamesAndWildcardsAreKeptAndIntercepted() throws Exception {
        var yaml = """
                confirmed-cache:
                  domains: ["*.blob.core.windows.net", downloads.example.com]
                  max-size: 5GiB
                """;
        assertEquals(List.of("*.blob.core.windows.net", "downloads.example.com"), domains(yaml, Set.of()));
        assertEquals(5 * MemorySize.GIB, Jjq.spawnConfig(yaml).confirmedCache().maxSize(warnings::add));
        var intercepted = ToolProxyResolver.handlerDomains(Jjq.spawnConfig(yaml), List.of());
        assertTrue(intercepted.containsAll(Set.of("blob.core.windows.net", "downloads.example.com")), intercepted.toString());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aMalformedDomainIsDroppedAlone() throws Exception {
        var kept = domains("""
                confirmed-cache:
                  domains: ["*.com", "10.0.0.1", Upper.Example.com, "a.*.example.com", 7, ok.example.com, ok.example.com]
                """, Set.of());
        assertEquals(List.of("ok.example.com"), kept);
        assertEquals(6, warnings.size(), warnings.toString());
    }

    @Test
    void aDomainIsxServesOtherwiseIsRefused() throws Exception {
        var kept = domains("""
                confirmed-cache:
                  domains: ["*.maven.org", repo.maven.apache.org, mcp.isx.internal, "*.example.com"]
                """, Set.of("repo1.maven.org", "repo.maven.apache.org"));
        assertEquals(List.of("*.example.com"), kept);
        assertTrue(warnings.get(0).contains("isx serves repo1.maven.org"), warnings.toString());
        assertTrue(warnings.get(2).contains(".internal"), warnings.toString());
    }

    @Test
    void anUnreadableSectionCachesNothing() throws Exception {
        assertEquals(List.of(), domains("confirmed-cache: [x.example.com]", Set.of()));
        assertEquals(List.of(), domains("confirmed-cache: {domain: [x.example.com]}", Set.of()));
        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals(ConfirmedCache.DEFAULT_MAX_SIZE,
                Jjq.spawnConfig("confirmed-cache: {max-size: lots}").confirmedCache().maxSize(warnings::add));
    }

    @Test
    void overlapCoversNamesAndWildcards() {
        assertTrue(ConfirmedCache.overlap("*.example.com", "a.example.com"));
        assertTrue(ConfirmedCache.overlap("a.example.com", "*.example.com"));
        assertTrue(ConfirmedCache.overlap("*.example.com", "*.b.example.com"));
        assertFalse(ConfirmedCache.overlap("*.example.com", "example.com"));
        assertFalse(ConfirmedCache.overlap("*.example.com", "notexample.com"));
        assertFalse(ConfirmedCache.overlap("a.example.com", "b.example.com"));
    }

    @Test
    void aDomainAToolInjectsAtIsDropped() {
        assertEquals(List.of("x.example.com"), ConfirmedCache.without(List.of("*.github.com", "x.example.com"),
                Set.of("api.github.com"), "a tool injects a credential at", warnings::add));
        assertTrue(warnings.getFirst().contains("api.github.com"), warnings.toString());
    }
}
