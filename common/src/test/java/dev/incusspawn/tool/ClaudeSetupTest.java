package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void envEntriesReturnsApiKeyPlaceholderByDefault() {
        var entries = new ClaudeSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "ANTHROPIC_API_KEY".equals(e.getName()) && "sk-ant-placeholder".equals(e.getValue())));
    }

    @Test
    void envEntriesReturnsOauthTokenWhenHostHasOauthToken() {
        var config = SpawnConfig.load();
        config.getClaude().setOauthToken("sk-ant-oat01-real-token-on-host");
        config.save();

        var entries = new ClaudeSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                        "CLAUDE_CODE_OAUTH_TOKEN".equals(e.getName())),
                "Should set CLAUDE_CODE_OAUTH_TOKEN in OAuth mode");
        assertFalse(entries.stream().anyMatch(e ->
                        "ANTHROPIC_API_KEY".equals(e.getName())),
                "Should not set ANTHROPIC_API_KEY in OAuth mode");
    }

    @Test
    void envEntriesAlwaysIncludesPathRawEntry() {
        var entries = new ClaudeSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                e.isRaw() && e.getRaw().contains("$HOME/.local/bin")));
    }

    @Test
    void configureSettingsWritesManagedSettingsToEtc() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER), new SpawnConfig.ClaudeConfig(), Map.of());

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

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER), new SpawnConfig.ClaudeConfig(), Map.of());

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

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER), claude, Map.of());

        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("customApiKeyResponses"));
    }

    @Test
    void configureSettingsWritesModelToUserSettingsWhenProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER),
                new SpawnConfig.ClaudeConfig(), Map.of("model", "claude-sonnet-4-6"));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), captor.capture());

        var settingsWrite = captor.getAllValues().stream()
                .filter(cmd -> cmd.contains(".claude/settings.json") && !cmd.contains("/etc/claude-code"))
                .findFirst().orElseThrow();
        assertTrue(settingsWrite.contains("\"model\" : \"claude-sonnet-4-6\""),
                "User settings.json should contain model when parameter is set");
    }

    @Test
    void configureSettingsOmitsModelFromUserSettingsWhenNotProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureSettings(new Container(incus, CONTAINER),
                new SpawnConfig.ClaudeConfig(), Map.of());

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
                Map.of("model", "claude-opus-4-6"));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), captor.capture());

        var commands = captor.getAllValues();
        assertTrue(commands.stream().anyMatch(cmd ->
                        cmd.contains(".claude/settings.json") && cmd.contains("\"model\" : \"claude-opus-4-6\"")),
                "reconfigure should write model to settings.json");
        assertFalse(commands.stream().anyMatch(cmd -> cmd.contains(".local/bin/claude")),
                "reconfigure should not install the binary");
    }

    @Test
    void parametersDeclaresAllSettingsAsOptionalReconfigurable() {
        var params = new ClaudeSetup().parameters();

        for (var name : List.of("model", "attribution-commit", "attribution-pr",
                "theme", "editor-mode", "output-style")) {
            assertTrue(params.containsKey(name), "Should declare parameter: " + name);
            var p = params.get(name);
            assertEquals("string", p.getType());
            assertTrue(p.isOptional(), name + " should be optional");
            assertTrue(p.isReconfigurable(), name + " should be reconfigurable");
        }

        assertNotNull(params.get("model").getPattern());
        assertNotNull(params.get("theme").getPattern());
        assertNotNull(params.get("editor-mode").getPattern());
    }

    @Test
    void buildUserSettingsWithAllParams() {
        var params = Map.of(
                "model", "claude-opus-4-6",
                "attribution-commit", "Assisted-By: Claude Code <noreply@anthropic.com>",
                "attribution-pr", "",
                "theme", "dark",
                "editor-mode", "vim",
                "output-style", "Proactive"
        );

        var json = ClaudeSetup.buildUserSettings(params);

        assertTrue(json.contains("\"disableDeepLinkRegistration\" : \"disable\""));
        assertTrue(json.contains("\"model\" : \"claude-opus-4-6\""));
        assertTrue(json.contains("\"theme\" : \"dark\""));
        assertTrue(json.contains("\"editorMode\" : \"vim\""));
        assertTrue(json.contains("\"outputStyle\" : \"Proactive\""));
        assertTrue(json.contains("\"attribution\""));
        assertTrue(json.contains("\"commit\" : \"Assisted-By: Claude Code <noreply@anthropic.com>\""));
        assertTrue(json.contains("\"pr\" : \"\""));
    }

    @Test
    void buildUserSettingsMinimal() {
        var json = ClaudeSetup.buildUserSettings(Map.of());

        assertTrue(json.contains("\"disableDeepLinkRegistration\" : \"disable\""));
        assertFalse(json.contains("\"model\""));
        assertFalse(json.contains("\"theme\""));
        assertFalse(json.contains("\"attribution\""));
    }

    @Test
    void buildUserSettingsAttributionPartial() {
        var json = ClaudeSetup.buildUserSettings(
                Map.of("attribution-commit", "Custom trailer"));

        assertTrue(json.contains("\"commit\" : \"Custom trailer\""));
        assertFalse(json.contains("\"pr\""));
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
