package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostCaConfigTest {

    @Test
    void nullRawReturnsDisabled() {
        var config = HostCaConfig.of(null);
        assertFalse(config.propagate());
        assertEquals(1, config.paths().size());
    }

    @Test
    void nonMapRawReturnsDisabled() {
        var config = HostCaConfig.of("bogus");
        assertFalse(config.propagate());
    }

    @Test
    void propagateTrueBoolean() {
        var config = HostCaConfig.of(Map.of("propagate", true));
        assertTrue(config.propagate());
    }

    @Test
    void propagateTrueString() {
        var config = HostCaConfig.of(Map.of("propagate", "true"));
        assertTrue(config.propagate());
    }

    @Test
    void propagateFalse() {
        var config = HostCaConfig.of(Map.of("propagate", false));
        assertFalse(config.propagate());
    }

    @Test
    void customPaths() {
        var config = HostCaConfig.of(Map.of(
                "propagate", true,
                "paths", List.of("/custom/anchors", "/other/certs")));
        assertTrue(config.propagate());
        assertEquals(List.of(Path.of("/custom/anchors"), Path.of("/other/certs")), config.paths());
    }

    @Test
    void emptyPathsKeepsDefault() {
        var config = HostCaConfig.of(Map.of("propagate", true, "paths", List.of()));
        assertEquals(1, config.paths().size());
    }

    @Test
    void nonStringPathEntriesAreSkipped() {
        var config = HostCaConfig.of(Map.of("propagate", true, "paths", List.of("/valid", 42)));
        assertEquals(List.of(Path.of("/valid")), config.paths());
    }
}
