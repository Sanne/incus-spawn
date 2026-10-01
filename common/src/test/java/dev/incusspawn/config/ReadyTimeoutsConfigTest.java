package dev.incusspawn.config;

import dev.incusspawn.incus.IncusClient.ReadyTimeouts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadyTimeoutsConfigTest {

    @Test
    void nullRawReturnsNull() {
        assertNull(ReadyTimeoutsConfig.of(null));
    }

    @Test
    void nonMapRawReturnsEmptyConfig() {
        var config = ReadyTimeoutsConfig.of("bogus");
        assertNotNull(config);
        assertNull(config.container());
        assertNull(config.vm());
    }

    @Test
    void parsesContainerAndVm() {
        var config = ReadyTimeoutsConfig.of(Map.of("container", "60s", "vm", "3m"));
        assertNotNull(config);
        assertEquals("60s", config.container());
        assertEquals("3m", config.vm());
    }

    @Test
    void resolveUsesDefaultsForMissingValues() {
        var config = ReadyTimeoutsConfig.of(Map.of("container", "45s"));
        var resolved = config.resolve();
        assertEquals(Duration.ofSeconds(45), resolved.container());
        assertEquals(ReadyTimeouts.DEFAULT.vm(), resolved.vm());
        assertEquals(ReadyTimeouts.DEFAULT.agentFailureGrace(), resolved.agentFailureGrace());
        assertEquals(ReadyTimeouts.DEFAULT.consoleCheckInterval(), resolved.consoleCheckInterval());
    }

    @Test
    void resolveOverridesBothValues() {
        var config = ReadyTimeoutsConfig.of(Map.of("container", "1m", "vm", "5m"));
        var resolved = config.resolve();
        assertEquals(Duration.ofMinutes(1), resolved.container());
        assertEquals(Duration.ofMinutes(5), resolved.vm());
    }

    @Test
    void resolveIgnoresUnreadableValues() {
        var config = ReadyTimeoutsConfig.of(Map.of("container", "not-a-duration"));
        var resolved = config.resolve();
        assertEquals(ReadyTimeouts.DEFAULT.container(), resolved.container());
    }

    @Test
    void numericYamlValueParsed() {
        var config = ReadyTimeoutsConfig.of(Map.of("container", 0));
        var resolved = config.resolve();
        assertEquals(Duration.ZERO, resolved.container());
    }
}
