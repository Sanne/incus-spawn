package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyServiceConfigTest {

    @Test
    void nullRawReturnsNull() {
        assertNull(ProxyServiceConfig.of(null));
    }

    @Test
    void nonMapRawReturnsEmptyConfig() {
        var config = ProxyServiceConfig.of("bogus");
        assertNotNull(config);
        assertNull(config.installTimeout());
    }

    @Test
    void parsesInstallTimeout() {
        var config = ProxyServiceConfig.of(Map.of("install-timeout", "15s"));
        assertNotNull(config);
        assertEquals("15s", config.installTimeout());
    }

    @Test
    void installTimeoutSecondsReturnsParsedValue() {
        var config = ProxyServiceConfig.of(Map.of("install-timeout", "15s"));
        assertEquals(15, config.installTimeoutSeconds());
    }

    @Test
    void installTimeoutSecondsParsesDurationUnits() {
        assertEquals(30, ProxyServiceConfig.of(Map.of("install-timeout", "30s")).installTimeoutSeconds());
        assertEquals(120, ProxyServiceConfig.of(Map.of("install-timeout", "2m")).installTimeoutSeconds());
    }

    @Test
    void installTimeoutSecondsUsesDefaultWhenMissing() {
        var config = ProxyServiceConfig.of(Map.of());
        assertEquals(ProxyServiceConfig.DEFAULT_INSTALL_TIMEOUT_SECONDS, config.installTimeoutSeconds());
    }

    @Test
    void installTimeoutSecondsUsesDefaultWhenUnreadable() {
        var config = ProxyServiceConfig.of(Map.of("install-timeout", "not-a-duration"));
        assertEquals(ProxyServiceConfig.DEFAULT_INSTALL_TIMEOUT_SECONDS, config.installTimeoutSeconds());
    }

    @Test
    void installTimeoutSecondsUsesDefaultForZero() {
        var config = ProxyServiceConfig.of(Map.of("install-timeout", "0"));
        assertEquals(ProxyServiceConfig.DEFAULT_INSTALL_TIMEOUT_SECONDS, config.installTimeoutSeconds());
    }

    @Test
    void numericYamlValueWithoutUnitUsesDefault() {
        var config = ProxyServiceConfig.of(Map.of("install-timeout", 15));
        assertEquals(ProxyServiceConfig.DEFAULT_INSTALL_TIMEOUT_SECONDS, config.installTimeoutSeconds());
    }

    @Test
    void numericYamlStringWithUnitParsed() {
        var config = ProxyServiceConfig.of(Map.of("install-timeout", "15s"));
        assertEquals(15, config.installTimeoutSeconds());
    }

    @Test
    void defaultInstallTimeoutIsFiveSeconds() {
        assertEquals(5, ProxyServiceConfig.DEFAULT_INSTALL_TIMEOUT_SECONDS);
    }
}
