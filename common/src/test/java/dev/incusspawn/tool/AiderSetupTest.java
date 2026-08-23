package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiderSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void setup() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void nameIsAider() {
        assertEquals("aider", new AiderSetup().name());
    }

    @Test
    void featureIsNull() {
        assertNull(new AiderSetup().feature());
    }

    @Test
    void declaresPipxPackage() {
        assertEquals(java.util.List.of("pipx", "python3.12"), new AiderSetup().packages());
    }

    @Test
    void installRunsPipxInstall() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new AiderSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("sh"), eq("-c"),
                argThat(arg -> arg.contains("pipx install") &&
                        arg.contains("--python python3.12") &&
                        arg.contains("aider-chat") &&
                        arg.contains("PIPX_BIN_DIR=/usr/local/bin")));
    }

    @Test
    void installWritesConfigYaml() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new AiderSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(AiderSetup.CONFIG_PATH) &&
                        arg.contains("auto-commits: false") &&
                        arg.contains("check-update: false") &&
                        arg.contains("analytics-disable: true") &&
                        arg.contains("yes-always: true") &&
                        arg.contains("model: anthropic/claude-sonnet-4-6")));
    }

    @Test
    void envEntriesSetsAnthropicApiKeyPlaceholder() {
        var entries = new AiderSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "ANTHROPIC_API_KEY".equals(e.getName()) && "sk-ant-placeholder".equals(e.getValue())));
    }

    @Test
    void envEntriesIncludesOpenaiKeyWhenFeatureEnabled() {
        var config = SpawnConfig.load();
        config.getOpenai().setApiKey("sk-real-openai-key");
        config.save();

        var entries = new AiderSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "OPENAI_API_KEY".equals(e.getName()) && "sk-placeholder".equals(e.getValue())));
    }

    @Test
    void envEntriesOmitsOpenaiKeyWhenFeatureDisabled() {
        var entries = new AiderSetup().envEntries(Map.of());

        assertFalse(entries.stream().anyMatch(e ->
                "OPENAI_API_KEY".equals(e.getName())));
    }

    @Test
    void actionsDeclaresAider() {
        var actions = new AiderSetup().actions();

        assertEquals(1, actions.size());
        assertEquals("aider", actions.getFirst().getLabel());
        assertEquals("shell", actions.getFirst().getType());
        assertEquals("aider", actions.getFirst().getCommand());
        assertTrue(actions.getFirst().isAutoReturn());
    }
}
