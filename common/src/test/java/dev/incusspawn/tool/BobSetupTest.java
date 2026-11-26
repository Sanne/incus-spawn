package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BobSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    // --- buildUserSettings unit tests ---

    @Test
    void buildUserSettingsDefaults() {
        var json = BobSetup.buildUserSettings(Map.of(), new SpawnConfig.BobConfig());

        assertTrue(json.contains("\"autoUpdate\": false"));
        assertTrue(json.contains("\"folderTrust\""));
        assertTrue(json.contains("\"enabled\": false"));
        assertTrue(json.contains("\"selectedType\": \"api-key\""));
        assertFalse(json.contains("\"session\""));
        assertFalse(json.contains("\"checkpointing\""));
        assertFalse(json.contains("licenseConsent"));
    }

    @Test
    void buildUserSettingsWithAllOptions() {
        var json = BobSetup.buildUserSettings(Map.of(
                "max-session-turns", "1000",
                "compression-threshold", "0.6"), new SpawnConfig.BobConfig());

        var settings = JqValues.parseStrict(json);
        assertEquals(JqValues.parseStrict("""
                {"maxTurns":1000,"compactionThresholdPercent":60}
                """), settings.path("session"));
        assertFalse(json.contains("\"model\""));
        assertFalse(json.contains("\"checkpointing\""));
    }

    @Test
    void buildUserSettingsWithOnlyTurns() {
        var json = BobSetup.buildUserSettings(Map.of("max-session-turns", "1000"),
                new SpawnConfig.BobConfig());

        assertEquals(JqValues.parseStrict("{\"maxTurns\":1000}"), JqValues.parseStrict(json).path("session"));
    }

    @Test
    void buildUserSettingsPreservesZeroTurns() {
        var json = BobSetup.buildUserSettings(Map.of("max-session-turns", "0"),
                new SpawnConfig.BobConfig());

        assertEquals(JqValues.parseStrict("{\"maxTurns\":0}"), JqValues.parseStrict(json).path("session"));
    }

    @Test
    void negativeTurnsFailBeforeInstallingBob() {
        var incus = mock(IncusClient.class);
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new BobSetup().install(new Container(incus, CONTAINER), Map.of("max-session-turns", "-1")));

        assertEquals("Bob max-session-turns must be nonnegative. Use 0 for unlimited turns.",
                exception.getMessage());
        verifyNoInteractions(incus);
    }

    @Test
    void unsupportedCheckpointingFailsEvenWhenFalse() {
        var setup = new BobSetup();
        for (var value : List.of("true", "false")) {
            var params = Map.of("checkpointing", value);
            assertEquals(List.of("Unknown parameter: checkpointing"),
                    ParameterResolver.resolve(setup.parameters(), params).errors());

            var incus = mock(IncusClient.class);
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> setup.reconfigure(new Container(incus, CONTAINER), params));
            assertTrue(exception.getMessage().contains("Remove checkpointing"));
            verifyNoInteractions(incus);
        }
    }

    @Test
    void buildUserSettingsWithOnlyCompression() {
        var json = BobSetup.buildUserSettings(Map.of("compression-threshold", "0.605"),
                new SpawnConfig.BobConfig());

        assertEquals(JqValues.parseStrict("{\"compactionThresholdPercent\":60.5}"),
                JqValues.parseStrict(json).path("session"));
    }

    @Test
    void buildUserSettingsWithLicenseConsent() {
        var bobConfig = new SpawnConfig.BobConfig();
        bobConfig.setLicenseConsent(true);
        var json = BobSetup.buildUserSettings(Map.of(), bobConfig);

        assertTrue(json.contains("\"licenseConsent\": true"));
        assertFalse(json.contains("\"ibm\""));
    }

    @Test
    void parametersDeclaresAllSettingsAsOptionalReconfigurable() {
        var params = new BobSetup().parameters();

        for (var name : List.of("max-session-turns", "compression-threshold")) {
            assertTrue(params.containsKey(name), "Should declare parameter: " + name);
            var p = params.get(name);
            assertTrue(p.isOptional(), name + " should be optional");
            assertTrue(p.isReconfigurable(), name + " should be reconfigurable");
        }
    }

    // --- compression-threshold pattern boundary tests ---

    @Test
    void compressionThresholdPatternAcceptsValidValues() {
        var pattern = Pattern.compile(new BobSetup().parameters()
                .get("compression-threshold").getPattern());

        for (var valid : List.of("0.0", "0.5", "0.6", ".5", ".99", "1.0", "0.12")) {
            assertTrue(pattern.matcher(valid).matches(),
                    "Should accept: " + valid);
        }
    }

    @Test
    void compressionThresholdPatternRejectsInvalidValues() {
        var pattern = Pattern.compile(new BobSetup().parameters()
                .get("compression-threshold").getPattern());

        for (var invalid : List.of("1.1", "1.5", "1.6", "2.0", "10.0", "abc")) {
            assertFalse(pattern.matcher(invalid).matches(),
                    "Should reject: " + invalid);
        }
    }

    // --- mocked-container wiring tests ---

    @Test
    void installWritesUserSettingsWithParams() {
        var originalHome = System.setProperty("user.home", "/tmp/bob-test-home");
        try {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

            var setup = new BobSetup();
            setup.configureSettings(new Container(incus, CONTAINER),
                    Map.of("max-session-turns", "1000"));

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), captor.capture());

            var userWrite = captor.getAllValues().stream()
                    .filter(cmd -> cmd.contains(".bob/settings/settings.json"))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("Should write ~/.bob/settings/settings.json"));
            assertTrue(userWrite.contains("maxTurns"),
                    "User settings should contain maxTurns");
            assertFalse(userWrite.contains("checkpointing"));
            assertTrue(userWrite.contains("autoUpdate"),
                    "User settings should contain autoUpdate");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void reconfigureWritesUserSettingsWithParams() {
        var originalHome = System.setProperty("user.home", "/tmp/bob-test-home");
        try {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

            var setup = new BobSetup();
            setup.reconfigure(new Container(incus, CONTAINER),
                    Map.of("compression-threshold", "0.6"));

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), captor.capture());

            var userWrite = captor.getAllValues().stream()
                    .filter(cmd -> cmd.contains(".bob/settings/settings.json"))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("reconfigure should write ~/.bob/settings/settings.json"));
            assertTrue(userWrite.contains("compactionThresholdPercent"),
                    "User settings should contain compression threshold");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void installWithNoParamsWritesDefaultSettings() {
        var originalHome = System.setProperty("user.home", "/tmp/bob-test-home");
        try {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

            var setup = new BobSetup();
            setup.configureSettings(new Container(incus, CONTAINER), Map.of());

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), captor.capture());

            var userWrite = captor.getAllValues().stream()
                    .filter(cmd -> cmd.contains(".bob/settings/settings.json"))
                    .findFirst().orElseThrow();
            assertTrue(userWrite.contains("autoUpdate"));
            assertFalse(userWrite.contains("maxTurns"),
                    "Should not contain maxTurns when param not set");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void noEtcBobshellSettingsWritten() {
        var originalHome = System.setProperty("user.home", "/tmp/bob-test-home");
        try {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

            var setup = new BobSetup();
            setup.configureSettings(new Container(incus, CONTAINER), Map.of());

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), captor.capture());

            assertFalse(captor.getAllValues().stream()
                    .anyMatch(cmd -> cmd.contains("/etc/bobshell")),
                    "Should not write to /etc/bobshell (dead config)");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
