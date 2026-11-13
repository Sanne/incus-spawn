package dev.incusspawn.tool;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpencodeSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    private static Map<String, String> resolved(Map<String, String> given) {
        return ParameterResolver.resolve(new OpencodeSetup().parameters(), given).resolvedValues();
    }

    /** Each entry as {@code NAME=value}: EnvEntry has no equals. */
    private static List<String> env(Map<String, String> given) {
        return new OpencodeSetup().envEntries(resolved(given)).stream()
                .map(e -> e.getName() + "=" + e.getValue()).toList();
    }

    private static JqObject config(Map<String, String> given) {
        return (JqObject) JqValues.parseStrict(OpencodeSetup.configJson(resolved(given)));
    }

    @Test
    void installRunsThePostinstallNpmWasToldToSkip() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

        new OpencodeSetup().install(new Container(incus, CONTAINER), resolved(Map.of()));

        var order = inOrder(incus);
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("npm"), eq("install"), eq("-g"), eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("opencode-ai"));
        // Without it 'opencode' is a stub that only says the postinstall did not run
        order.verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                arg.contains("cd \"$root/opencode-ai\"") && arg.contains("node postinstall.mjs")
                        // It runs the binary: root's /tmp/opencode would be one agentuser cannot write
                        && arg.contains("HOME=\"$scratch\" TMPDIR=\"$scratch\"")
                        && arg.contains("rm -rf \"$scratch\"")));
    }

    @Test
    void aFailedPostinstallFailsTheInstall() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg -> arg.contains("postinstall.mjs"))))
                .thenReturn(new IncusClient.ExecResult(1, "", "It seems your package manager failed"));

        var e = assertThrows(IncusException.class,
                () -> new OpencodeSetup().install(new Container(incus, CONTAINER), resolved(Map.of())));
        assertTrue(e.getMessage().contains("Failed to install opencode's binary"), e.getMessage());
    }

    @Test
    void theDefaultProviderGetsSonnetAndTheClaudeProof() {
        // Unpinned, opencode 1.19 picks claude-sonnet-4-6
        var config = config(Map.of());
        assertEquals("anthropic/claude-sonnet-5-5", config.getField("model").toJsonString().replace("\"", ""));
        assertEquals("\"{env:OPENCODE_ANTHROPIC_API_KEY}\"", ((JqObject) ((JqObject) ((JqObject) config.getField("provider")).getField("anthropic"))
                        .getField("options")).getField("apiKey").toJsonString());
        assertEquals(List.of("OPENCODE_ANTHROPIC_API_KEY=sk-ant-placeholder"), env(Map.of()));
        assertEquals(Set.of(SpawnConfig.ClaudeConfig.NAMESPACE), new OpencodeSetup().credentialNamespaces(resolved(Map.of())));
    }

    @Test
    void anotherProviderWithoutModelLeavesItToOpencode() {
        var config = config(Map.of("provider", "openai"));
        assertFalse(config.keys().contains("model"));
        assertFalse(config.keys().contains("provider"));
        assertEquals(List.of("OPENAI_API_KEY=sk-placeholder"), env(Map.of("provider", "openai")));
        assertEquals(List.of("GEMINI_API_KEY=" + GeminiSetup.PLACEHOLDER_API_KEY), env(Map.of("provider", "google")));
        assertEquals(Set.of("gemini"), new OpencodeSetup().credentialNamespaces(resolved(Map.of("provider", "google"))));
    }

    @Test
    void aProviderIsxDoesNotManageGetsNoCredential() {
        var params = resolved(Map.of("provider", "openrouter", "model", "some-model"));
        assertEquals(List.of(), env(params));
        assertEquals(Set.of(), new OpencodeSetup().credentialNamespaces(params));
        assertEquals("\"openrouter/some-model\"", config(params).getField("model").toJsonString());
    }

    @Test
    void configAllowsWhatOpencodeAsksAboutAndReadsTheInstructions() {
        var config = config(Map.of());
        assertEquals("{\"read\":\"allow\",\"external_directory\":\"allow\",\"doom_loop\":\"allow\"}",
                config.getField("permission").toJsonString());
        assertEquals("[\"/AGENTS.md\"]", config.getField("instructions").toJsonString());
        assertEquals("false", config.getField("autoupdate").toJsonString());
        assertEquals("\"disabled\"", config.getField("share").toJsonString());
        assertEquals("/AGENTS.md", new OpencodeSetup().environmentContextPath());
    }

    @Test
    void declaresItsOwnClaudePlaceholder() {
        assertEquals(List.of(new dev.incusspawn.proxy.ProofToken.Placeholder(
                        "OPENCODE_ANTHROPIC_API_KEY", "sk-ant-", SpawnConfig.ClaudeConfig.NAMESPACE)),
                new OpencodeSetup().placeholders());
    }

    @Test
    void reconfigureRewritesTheConfig() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        new OpencodeSetup().reconfigure(new Container(incus, CONTAINER), resolved(Map.of("model", "claude-opus-5-5")));

        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat(arg ->
                arg.contains("/home/agentuser/.config/opencode/opencode.json")
                        && arg.contains("\"model\": \"anthropic/claude-opus-5-5\"")));
        verify(incus, never()).shellExec(eq(CONTAINER), eq("npm"), any(), any(), any(), any(), any());
    }
}
