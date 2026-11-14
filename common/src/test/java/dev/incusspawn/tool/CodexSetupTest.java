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
    void declaresNoFeatureGate() {
        // Codex graduated from the 'openai' experimental feature flag: it must be
        // offered by init, build and the TUI without opting into anything.
        assertNull(new CodexSetup().feature());
    }

    @Test
    void declaresNodejsPackage() {
        assertEquals(java.util.List.of("nodejs"), new CodexSetup().packages());
    }

    @Test
    void installRunsNpmInstallGlobal() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@openai/codex"));
    }

    @Test
    void installChecksThePlatformPackageAfterNpmInstall() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        var order = inOrder(incus);
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@openai/codex"));
        order.verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"),
                argThat(arg -> arg.contains("npm root -g") && arg.contains("'@openai/codex-'")));
        order.verify(incus).execInContainer(CONTAINER, "agentuser", "command -v codex");
    }

    @Test
    void installFailsBeforeConfiguringWhenThePlatformPackageStaysMissing() {
        // npm exits 0 when an optional platform package fails to download, leaving only the
        // JS launcher: the build must fail rather than stamp an unusable template (#808).
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg -> arg.contains("npm root -g"))))
                .thenReturn(new IncusClient.ExecResult(3, "@openai/codex-linux-x64\n", ""));

        try (var ignored = new NpmWaits()) {
            var e = assertThrows(dev.incusspawn.incus.IncusException.class,
                    () -> new CodexSetup().install(new Container(incus, CONTAINER), Map.of()));
            assertTrue(e.getMessage().contains("@openai/codex-linux-x64"), e.getMessage());
            verify(incus, never()).shellExec(eq(CONTAINER),
                    eq("sh"), eq("-c"), argThat(arg -> arg.contains(CodexSetup.CONFIG_PATH)));
        }
    }

    @Test
    void installWritesConfigToml() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("approval_policy = \"never\"") &&
                        arg.contains("default_permissions = \"Running unconstrained in isx dev [container]\"") &&
                        arg.contains("status_line = [\"model-with-reasoning\", \"current-dir\", \"permissions\"]") &&
                        arg.contains("web_search = \"live\"") &&
                        arg.contains("\":root\" = \"write\"") &&
                        arg.contains("enabled = true") &&
                        arg.contains("forced_login_method = \"api\"") &&
                        arg.contains("check_for_update_on_startup = false") &&
                        arg.contains("hide_full_access_warning = true") &&
                        arg.contains("show_tooltips = false") &&
                        arg.contains("trust_level = \"trusted\"")));
    }

    @Test
    void installOmitsModelAndEffortWhenNotProvided() {
        // Codex picks both from its own server-side catalog when the config is silent;
        // a default baked in here would override that and age the way o4-mini did.
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER), Map.of());

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        !arg.contains("model = ") &&
                        !arg.contains("model_reasoning_effort = ")));
    }

    @Test
    void installWritesModelToConfigWhenProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER),
                Map.of("model", "gpt-6-sol"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model = \"gpt-6-sol\"") &&
                        !arg.contains("model_reasoning_effort = ")));
    }

    @Test
    void installWritesEffortToConfigWhenProvided() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new CodexSetup().install(new Container(incus, CONTAINER),
                Map.of("effort", "low"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model_reasoning_effort = \"low\"") &&
                        !arg.contains("model = \"")));
    }

    @Test
    void reconfigureOnlyWritesSettingsNotBinary() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new CodexSetup().reconfigure(new Container(incus, CONTAINER),
                Map.of("model", "gpt-6-sol", "effort", "xhigh"));

        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), argThat(arg ->
                        arg.contains(CodexSetup.CONFIG_PATH) &&
                        arg.contains("model = \"gpt-6-sol\"") &&
                        arg.contains("model_reasoning_effort = \"xhigh\"")));
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@openai/codex"));
        verify(incus, never()).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg -> arg.contains("npm root -g")));
        verifyNoMoreInteractions(ignoreStubs(incus));
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
    void parametersResolveToNothingWhenNotProvided() {
        var result = ParameterResolver.resolve(new CodexSetup().parameters(), Map.of());

        assertFalse(result.hasErrors());
        assertEquals(Map.of(), result.resolvedValues());
    }

    @Test
    void parametersAcceptModelAndSupportedEfforts() {
        for (var effort : List.of("minimal", "low", "medium", "high", "xhigh")) {
            var provided = Map.of("model", "gpt-6-sol", "effort", effort);
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
                Map.of("model", "gpt-6-sol\"\n[notice]"),
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
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

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
    /** Outside the user's ~/.codex, which stays theirs; rendered, since Codex reads TOML there. */
    @Test
    void readsTheInstructionsFromItsSystemRequirements() {
        var codex = new CodexSetup();
        assertEquals("/etc/codex/requirements.toml", codex.environmentContextPath());
        assertEquals(CodexSetup.requirementsToml("# incus-spawn environment\nhi"),
                codex.renderEnvironmentContext("# incus-spawn environment\nhi"));
    }

    @Test
    void requirementsHoldTheInstructionsAsOneMultiLineString() {
        var toml = CodexSetup.requirementsToml("# incus-spawn environment\n\n- a `tool`\n");

        assertEquals("""
                # incus-spawn environment
                # Written by isx at each build: the environment instructions, as Codex's managed developer instructions
                additional_developer_instructions = \"""
                # incus-spawn environment

                - a `tool`
                \"""
                """, toml);
    }

    /** Agent notes are template-authored: no quote, backslash or control character ends the string early. */
    @Test
    void requirementsEscapeWhatWouldEndOrBreakTheString() {
        var instructions = "# incus-spawn environment\nsay \"\"\"hi\"\"\" C:\\dir\u0001\r\tend \u00e9\uD83D\uDE00";

        var toml = CodexSetup.requirementsToml(instructions);

        var opener = "additional_developer_instructions = \"\"\"\n";
        var body = toml.substring(toml.indexOf(opener) + opener.length());
        assertTrue(body.endsWith("\n\"\"\"\n"), toml);
        body = body.substring(0, body.length() - "\n\"\"\"\n".length());
        assertFalse(body.contains("\"\"\""), "no closing delimiter inside: " + body);
        assertTrue(body.chars().noneMatch(c -> c < 0x20 && c != '\n' && c != '\t'), body);
        assertEquals(instructions, unescape(body));
    }

    @Test
    void aLoneSurrogateBecomesTheReplacementCharacter() {
        var toml = CodexSetup.requirementsToml("# incus-spawn environment\nbad \uD83D here");

        assertTrue(toml.contains("bad \\uFFFD here\n"), toml);
    }

    @Test
    void instructionsCodexWouldRefuseFailTheBuild() {
        var instructions = "# incus-spawn environment\n" + "x".repeat(CodexSetup.MAX_INSTRUCTIONS_BYTES);

        var e = assertThrows(IllegalStateException.class, () -> CodexSetup.requirementsToml(instructions));
        assertTrue(e.getMessage().contains("agent notes"), e.getMessage());
    }

    /** TOML basic-string unescaping, for the escapes {@link CodexSetup#requirementsToml} writes. */
    private static String unescape(String body) {
        var out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            var c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            var next = body.charAt(++i);
            switch (next) {
                case '\\', '"' -> out.append(next);
                case 'u' -> {
                    out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> fail("unexpected escape \\" + next);
            }
        }
        return out.toString();
    }
}
