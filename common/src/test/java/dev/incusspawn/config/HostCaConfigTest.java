package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostCaConfigTest {

    @Test
    void nullRawReturnsDisabled() {
        var config = HostCaConfig.of(null);
        assertFalse(config.propagate());
    }

    @Test
    void nonMapRawReturnsDisabled() {
        var config = HostCaConfig.of("bogus");
        assertFalse(config.propagate());
    }

    @Test
    void bareBooleanTrue() {
        var config = HostCaConfig.of(true);
        assertTrue(config.propagate());
    }

    @Test
    void bareBooleanFalse() {
        var config = HostCaConfig.of(false);
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
    void unknownKeysIgnored() {
        var config = HostCaConfig.of(Map.of("propagate", true, "paths", List.of("/foo")));
        assertTrue(config.propagate());
    }
}
