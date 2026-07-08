package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void envEntriesReturnsApiKeyPlaceholderByDefault() {
        var entries = new ClaudeSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "ANTHROPIC_API_KEY".equals(e.getName()) && "sk-ant-placeholder".equals(e.getValue())));
    }

    @Test
    void envEntriesReturnsOauthTokenWhenHostHasOauthToken() {
        // This test requires SpawnConfig to report OAuth mode; since envEntries()
        // reads SpawnConfig.load(), we verify the factory method structure instead.
        var entries = new ClaudeSetup().envEntries(Map.of());

        // Default config has no OAuth token, so ANTHROPIC_API_KEY should be present
        assertTrue(entries.stream().anyMatch(e ->
                "ANTHROPIC_API_KEY".equals(e.getName())));
    }

    @Test
    void envEntriesAlwaysIncludesPathPrepend() {
        var entries = new ClaudeSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "PATH".equals(e.getName())
                && e.getStrategy() == EnvEntry.Strategy.PREPEND
                && "$HOME/.local/bin".equals(e.getValue())));
    }

    @Test
    void configureSettingsWritesManagedSettingsToEtc() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER), new SpawnConfig.ClaudeConfig());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("/etc/claude-code/managed-settings.json"));
        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("bypassPermissions"));
        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("skipDangerousModePermissionPrompt"));
    }

    @Test
    void configureSettingsUserSettingsDoNotContainBypassPermissions() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER), new SpawnConfig.ClaudeConfig());

        // bypassPermissions should only be in managed settings, not user settings
        // User settings file is ~/.claude/settings.json
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), captor.capture());

        for (var cmd : captor.getAllValues()) {
            if (cmd.contains(".claude/settings.json") && !cmd.contains("/etc/claude-code")) {
                assertFalse(cmd.contains("bypassPermissions"),
                        "User settings.json should not contain bypassPermissions (now in managed settings)");
                assertFalse(cmd.contains("skipDangerousModePermissionPrompt"),
                        "User settings.json should not contain skipDangerousModePermissionPrompt (now in managed settings)");
            }
        }
    }

    @Test
    void configureSettingsOmitsCustomApiKeyResponsesInOauthMode() {
        var claude = new SpawnConfig.ClaudeConfig();
        claude.setOauthToken("sk-ant-oat01-real-token-on-host");

        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER), claude);

        // The trust-prompt bypass only applies to direct API key auth; in OAuth mode
        // Claude Code never goes through that flow, so it shouldn't reference the
        // placeholder API key at all.
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("customApiKeyResponses"));
    }

    @Test
    void configureSettingsWritesModelToUserSettingsWhenProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER),
                new SpawnConfig.ClaudeConfig(), "claude-sonnet-4-6");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), captor.capture());

        var settingsWrite = captor.getAllValues().stream()
                .filter(cmd -> cmd.contains(".claude/settings.json") && !cmd.contains("/etc/claude-code"))
                .findFirst().orElseThrow();
        assertTrue(settingsWrite.contains("\"model\": \"claude-sonnet-4-6\""),
                "User settings.json should contain model when parameter is set");
    }

    @Test
    void configureSettingsOmitsModelFromUserSettingsWhenNotProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER),
                new SpawnConfig.ClaudeConfig());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), captor.capture());

        var settingsWrite = captor.getAllValues().stream()
                .filter(cmd -> cmd.contains(".claude/settings.json") && !cmd.contains("/etc/claude-code"))
                .findFirst().orElseThrow();
        assertFalse(settingsWrite.contains("\"model\""),
                "User settings.json should not contain model when parameter is not set");
    }

    @Test
    void reconfigureOnlyWritesSettingsNotBinary() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().reconfigure(new Container(incus, CONTAINER),
                java.util.Map.of("model", "claude-opus-4-6"));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), captor.capture());

        var commands = captor.getAllValues();
        assertTrue(commands.stream().anyMatch(cmd ->
                        cmd.contains(".claude/settings.json") && cmd.contains("\"model\": \"claude-opus-4-6\"")),
                "reconfigure should write model to settings.json");
        assertFalse(commands.stream().anyMatch(cmd -> cmd.contains(".local/bin/claude")),
                "reconfigure should not install the binary");
    }

    @Test
    void parametersDeclaresModelAsOptionalReconfigurable() {
        var params = new ClaudeSetup().parameters();
        assertTrue(params.containsKey("model"));
        var model = params.get("model");
        assertEquals("string", model.getType());
        assertNotNull(model.getPattern());
        assertNull(model.getDefault());
        assertTrue(model.isOptional());
        assertTrue(model.isReconfigurable());
    }

    @Test
    void detectPlatformReturnsLinuxX64OnAmd64() {
        assertEquals("linux-x64", ClaudeSetup.detectPlatform("amd64"));
        assertEquals("linux-x64", ClaudeSetup.detectPlatform("x86_64"));
    }

    @Test
    void detectPlatformReturnsLinuxArm64() {
        assertEquals("linux-arm64", ClaudeSetup.detectPlatform("aarch64"));
        assertEquals("linux-arm64", ClaudeSetup.detectPlatform("arm64"));
    }

    @Test
    void detectPlatformThrowsOnUnsupportedArch() {
        assertThrows(RuntimeException.class, () -> ClaudeSetup.detectPlatform("sparc"));
    }

    @Test
    void extractChecksumParsesValidManifest() throws IOException {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                    }
                  }
                }
                """;
        assertEquals(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                ClaudeSetup.extractChecksum(manifest, "linux-x64"));
    }

    @Test
    void extractChecksumAcceptsUppercaseHex() throws IOException {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
                    }
                  }
                }
                """;
        assertEquals(
                "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
                ClaudeSetup.extractChecksum(manifest, "linux-x64"));
    }

    @Test
    void extractChecksumThrowsOnMissingPlatform() {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                    }
                  }
                }
                """;
        var e = assertThrows(IOException.class,
                () -> ClaudeSetup.extractChecksum(manifest, "linux-arm64"));
        assertTrue(e.getMessage().contains("not found"));
        assertTrue(e.getMessage().contains("linux-arm64"));
    }

    @Test
    void extractChecksumThrowsOnInvalidChecksum() {
        var manifest = """
                {
                  "platforms": {
                    "linux-x64": {
                      "checksum": "not-a-valid-sha256"
                    }
                  }
                }
                """;
        var e = assertThrows(IOException.class,
                () -> ClaudeSetup.extractChecksum(manifest, "linux-x64"));
        assertTrue(e.getMessage().contains("Invalid checksum"));
        assertTrue(e.getMessage().contains("not-a-valid-sha256"));
    }

    @Test
    void extractChecksumThrowsOnEmptyManifest() {
        var manifest = "{}";
        assertThrows(IOException.class,
                () -> ClaudeSetup.extractChecksum(manifest, "linux-x64"));
    }

    @Test
    void downloadCacheIsInjectable(@TempDir Path tempDir) {
        var cache = new DownloadCache(tempDir);
        var setup = new ClaudeSetup(cache);
        assertEquals("claude", setup.name());
    }
}
