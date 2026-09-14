package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BobSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    // --- buildSystemSettings unit tests ---

    @Test
    void buildSystemSettingsDefaults() {
        var json = BobSetup.buildSystemSettings(Map.of());

        assertTrue(json.contains("\"disableAutoUpdate\" : true"));
        assertTrue(json.contains("\"disableUpdateNag\" : true"));
        assertFalse(json.contains("\"model\""));
        assertFalse(json.contains("\"checkpointing\""));
    }

    @Test
    void buildSystemSettingsWithAllOptions() {
        var json = BobSetup.buildSystemSettings(Map.of(
                "max-session-turns", "-1",
                "compression-threshold", "0.6",
                "checkpointing", "true"));

        assertTrue(json.contains("\"disableAutoUpdate\" : true"));
        assertTrue(json.contains("\"maxSessionTurns\" : -1"));
        assertTrue(json.contains("\"contextPercentageThreshold\" : 0.6"));
        assertTrue(json.contains("\"enabled\" : true"));
    }

    @Test
    void buildSystemSettingsWithOnlyTurns() {
        var json = BobSetup.buildSystemSettings(Map.of("max-session-turns", "50"));

        assertTrue(json.contains("\"maxSessionTurns\" : 50"));
        assertFalse(json.contains("\"chatCompression\""));
        assertFalse(json.contains("\"checkpointing\""));
    }

    @Test
    void buildSystemSettingsWithOnlyCompression() {
        var json = BobSetup.buildSystemSettings(Map.of("compression-threshold", "0.8"));

        assertTrue(json.contains("\"contextPercentageThreshold\" : 0.8"));
        assertFalse(json.contains("\"maxSessionTurns\""));
        assertFalse(json.contains("\"checkpointing\""));
    }

    @Test
    void buildSystemSettingsCheckpointingDisabled() {
        var json = BobSetup.buildSystemSettings(Map.of("checkpointing", "false"));

        assertTrue(json.contains("\"enabled\" : false"));
    }

    @Test
    void parametersDeclaresAllSettingsAsOptionalReconfigurable(@TempDir Path home) {
        var params = new BobSetup(new DownloadCache(home)).parameters();

        for (var name : List.of("max-session-turns", "compression-threshold", "checkpointing")) {
            assertTrue(params.containsKey(name), "Should declare parameter: " + name);
            var p = params.get(name);
            assertTrue(p.isOptional(), name + " should be optional");
            assertTrue(p.isReconfigurable(), name + " should be reconfigurable");
        }
    }

    // --- compression-threshold pattern boundary tests ---

    @Test
    void compressionThresholdPatternAcceptsValidValues(@TempDir Path home) {
        var pattern = Pattern.compile(new BobSetup(new DownloadCache(home)).parameters()
                .get("compression-threshold").getPattern());

        for (var valid : List.of("0.0", "0.5", "0.6", ".5", ".99", "1.0", "0.12")) {
            assertTrue(pattern.matcher(valid).matches(),
                    "Should accept: " + valid);
        }
    }

    @Test
    void compressionThresholdPatternRejectsInvalidValues(@TempDir Path home) {
        var pattern = Pattern.compile(new BobSetup(new DownloadCache(home)).parameters()
                .get("compression-threshold").getPattern());

        for (var invalid : List.of("1.1", "1.5", "1.6", "2.0", "10.0", "abc")) {
            assertFalse(pattern.matcher(invalid).matches(),
                    "Should reject: " + invalid);
        }
    }

    // --- mocked-container wiring tests ---

    @Test
    void installWritesSystemSettingsWithParams(@TempDir Path home) {
        System.setProperty("user.home", home.toString());
        try {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

            var setup = new BobSetup(new DownloadCache(home));
            setup.configureSettings(new Container(incus, CONTAINER),
                    Map.of("max-session-turns", "-1", "checkpointing", "true"));

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), captor.capture());

            var systemWrite = captor.getAllValues().stream()
                    .filter(cmd -> cmd.contains("/etc/bobshell/settings.json"))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("Should write /etc/bobshell/settings.json"));
            assertTrue(systemWrite.contains("maxSessionTurns"),
                    "System settings should contain maxSessionTurns");
            assertTrue(systemWrite.contains("checkpointing"),
                    "System settings should contain checkpointing");
        } finally {
            System.clearProperty("user.home");
        }
    }

    @Test
    void reconfigureWritesSystemSettingsWithParams(@TempDir Path home) {
        System.setProperty("user.home", home.toString());
        try {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

            var setup = new BobSetup(new DownloadCache(home));
            setup.reconfigure(new Container(incus, CONTAINER),
                    Map.of("compression-threshold", "0.6"));

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), captor.capture());

            var systemWrite = captor.getAllValues().stream()
                    .filter(cmd -> cmd.contains("/etc/bobshell/settings.json"))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("reconfigure should write /etc/bobshell/settings.json"));
            assertTrue(systemWrite.contains("contextPercentageThreshold"),
                    "System settings should contain compression threshold");
        } finally {
            System.clearProperty("user.home");
        }
    }

    @Test
    void installWithNoParamsWritesDefaultSystemSettings(@TempDir Path home) {
        System.setProperty("user.home", home.toString());
        try {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

            var setup = new BobSetup(new DownloadCache(home));
            setup.configureSettings(new Container(incus, CONTAINER), Map.of());

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), captor.capture());

            var systemWrite = captor.getAllValues().stream()
                    .filter(cmd -> cmd.contains("/etc/bobshell/settings.json"))
                    .findFirst().orElseThrow();
            assertTrue(systemWrite.contains("disableAutoUpdate"));
            assertFalse(systemWrite.contains("maxSessionTurns"),
                    "Should not contain maxSessionTurns when param not set");
        } finally {
            System.clearProperty("user.home");
        }
    }
}
