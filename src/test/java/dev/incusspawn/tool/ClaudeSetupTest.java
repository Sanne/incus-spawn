package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void configureAuthSetsApiKeyPlaceholderByDefault() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureAuth(new Container(incus, CONTAINER), new SpawnConfig.ClaudeConfig());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("ANTHROPIC_API_KEY=sk-ant-placeholder"));
    }

    @Test
    void configureAuthSetsOauthTokenWhenHostHasOauthToken() {
        var claude = new SpawnConfig.ClaudeConfig();
        claude.setOauthToken("sk-ant-oat01-real-token-on-host");

        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureAuth(new Container(incus, CONTAINER), claude);

        // Claude Code itself recognizes CLAUDE_CODE_OAUTH_TOKEN and builds the OAuth
        // request (Bearer auth, identity/beta headers) — it never sees the real host token.
        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-placeholder"));
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void configureAuthSetsVertexEnvVarsWhenVertexConfigured() {
        var claude = new SpawnConfig.ClaudeConfig();
        claude.setUseVertex(true);
        claude.setCloudMlRegion("us-east5");
        claude.setVertexProjectId("my-project");

        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new ClaudeSetup().configureAuth(new Container(incus, CONTAINER), claude);

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("CLAUDE_CODE_USE_VERTEX=1"));
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("CLAUDE_CODE_OAUTH_TOKEN"));
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
