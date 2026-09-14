package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BobSetupTest {

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
    void parametersDeclaresAllSettingsAsOptionalReconfigurable() {
        var params = new BobSetup().parameters();

        for (var name : java.util.List.of("max-session-turns", "compression-threshold", "checkpointing")) {
            assertTrue(params.containsKey(name), "Should declare parameter: " + name);
            var p = params.get(name);
            assertTrue(p.isOptional(), name + " should be optional");
            assertTrue(p.isReconfigurable(), name + " should be reconfigurable");
        }
    }
}
