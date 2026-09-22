package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
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
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=error"), eq("@openai/codex"));
    }

    @Test
    void installWritesConfigToml() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model = \"o4-mini\"") &&
                        arg.contains("model_reasoning_effort = \"high\"") &&
                        arg.contains("approval_policy = \"never\"") &&
                        arg.contains("sandbox_mode = \"danger-full-access\"") &&
                        arg.contains("forced_login_method = \"api\"") &&
                        arg.contains("check_for_update_on_startup = false") &&
                        arg.contains("hide_full_access_warning = true") &&
                        arg.contains("show_tooltips = false") &&
                        arg.contains("trust_level = \"trusted\"")));
    }

    @Test
    void installWritesModelToConfigWhenProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER),
                Map.of("model", "gpt-5.3-codex"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model = \"gpt-5.3-codex\"") &&
                        arg.contains("model_reasoning_effort = \"high\"") &&
                        !arg.contains("model = \"o4-mini\"")));
    }

    @Test
    void installWritesEffortToConfigWhenProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER),
                Map.of("effort", "low"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model = \"o4-mini\"") &&
                        arg.contains("model_reasoning_effort = \"low\"") &&
                        !arg.contains("model_reasoning_effort = \"high\"")));
    }

    @Test
    void reconfigureOnlyWritesSettingsNotBinary() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().reconfigure(new Container(incus, CONTAINER),
                Map.of("model", "gpt-5.3-codex", "effort", "xhigh"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model = \"gpt-5.3-codex\"") &&
                        arg.contains("model_reasoning_effort = \"xhigh\"")));
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=error"), eq("@openai/codex"));
    }

    @Test
    void parametersDeclaresAllSettingsAsOptionalReconfigurable() {
        var params = new CodexSetup().parameters();

        for (var name : List.of("model", "effort")) {
            assertTrue(params.containsKey(name), "Should declare parameter: " + name);
            var parameter = params.get(name);
            assertEquals("string", parameter.getType());
            assertTrue(parameter.isOptional(), name + " should be optional");
            assertTrue(parameter.isReconfigurable(), name + " should be reconfigurable");
            assertNotNull(parameter.getPattern());
        }
    }

    @Test
    void parametersResolveDefaultsWhenNotProvided() {
        var result = ParameterResolver.resolve(new CodexSetup().parameters(), Map.of());

        assertFalse(result.hasErrors());
        assertEquals(Map.of("model", "o4-mini", "effort", "high"), result.resolvedValues());
    }

    @Test
    void parametersAcceptModelAndSupportedEfforts() {
        for (var effort : List.of("minimal", "low", "medium", "high", "xhigh")) {
            var provided = Map.of("model", "gpt-5.3-codex", "effort", effort);
            var result = ParameterResolver.resolve(new CodexSetup().parameters(), provided);

            assertFalse(result.hasErrors(), "Should accept effort: " + effort);
            assertEquals(provided, result.resolvedValues());
        }
    }

    @Test
    void parametersRejectInvalidValues() {
        for (var provided : List.of(
                Map.of("model", ""),
                Map.of("model", "invalid model"),
                Map.of("model", "gpt-5.3-codex\"\n[notice]"),
                Map.of("effort", ""),
                Map.of("effort", "invalid"))) {
            var result = ParameterResolver.resolve(new CodexSetup().parameters(), provided);

            assertTrue(result.hasErrors(), "Should reject parameters: " + provided);
        }
    }

    @Test
    void installWritesAuthJson() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.AUTH_PATH) &&
                        arg.contains("\"auth_mode\": \"apikey\"") &&
                        arg.contains("\"OPENAI_API_KEY\": \"sk-placeholder\"")));
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
        var command = actions.getFirst().getCommand();
        assertTrue(command.contains("codex resume --last"), "Should resume last session");
        assertTrue(command.contains(".codex/sessions"), "Should detect sessions in ~/.codex/sessions");
        assertTrue(actions.getFirst().isAutoReturn());
    }
}
