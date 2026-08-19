package dev.incusspawn.tool;

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

class CodexSetupTest {

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
    void nameIsCodex() {
        assertEquals("codex", new CodexSetup().name());
    }

    @Test
    void declaresNodejsPackage() {
        assertEquals(java.util.List.of("nodejs"), new CodexSetup().packages());
    }

    @Test
    void installRunsNpmInstallGlobal() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=error"), eq("@openai/codex"));
    }

    @Test
    void installWritesConfigToml() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model = \"o4-mini\"") &&
                        arg.contains("approval_policy = \"full-auto\"") &&
                        arg.contains("forced_login_method = \"api\"") &&
                        arg.contains("check_for_update_on_startup = false") &&
                        arg.contains("trust_level = \"trusted\"")));
    }

    @Test
    void envEntriesSetsOpenaiApiKeyPlaceholder() {
        var entries = new CodexSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "OPENAI_API_KEY".equals(e.getName()) && "sk-placeholder".equals(e.getValue())));
    }

    @Test
    void actionsDeclaresCodexCli() {
        var actions = new CodexSetup().actions();

        assertEquals(1, actions.size());
        assertEquals("Codex CLI", actions.getFirst().getLabel());
        assertEquals("shell", actions.getFirst().getType());
        assertEquals("codex", actions.getFirst().getCommand());
        assertTrue(actions.getFirst().isAutoReturn());
    }
}
