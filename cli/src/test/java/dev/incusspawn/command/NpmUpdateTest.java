package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NpmUpdateTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String NAME = "tpl-test";

    private static IncusClient incus() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        return incus;
    }

    private static void stubCheck(IncusClient incus, String npmPackage, IncusClient.ExecResult result) {
        when(incus.shellExec(eq(NAME), eq("sh"), eq("-c"),
                argThat(arg -> arg.contains("/" + npmPackage + "/package.json")))).thenReturn(result);
    }

    @Test
    void skipsTemplatesWithoutNpm() {
        var incus = incus();
        when(incus.shellExec(NAME, "which", "npm")).thenReturn(new IncusClient.ExecResult(1, "", ""));

        assertTrue(NpmUpdate.run(incus, NAME));

        verify(incus, never()).shellExec(NAME, "npm", "update", "-g");
    }

    @Test
    void reportsAFailedUpdate() {
        var incus = incus();
        when(incus.shellExec(NAME, "npm", "update", "-g")).thenReturn(new IncusClient.ExecResult(1, "", "npm error"));

        assertFalse(NpmUpdate.run(incus, NAME));
    }

    @Test
    void reportsAnUpdateThatLeftACliWithoutItsPlatformBinary() {
        // npm update -g exits 0 when it drops a CLI's optional platform package (#808).
        var incus = incus();
        stubCheck(incus, "@openai/codex", new IncusClient.ExecResult(3, "@openai/codex-linux-x64\n", ""));

        assertFalse(NpmUpdate.run(incus, NAME));

        verify(incus).shellExec(eq(NAME), eq("npm"), eq("install"), eq("-g"),
                eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@openai/codex"));
    }

    @Test
    void succeedsWhenEveryInstalledCliKeptItsPlatformBinary() {
        var incus = incus();
        stubCheck(incus, "@github/copilot", new IncusClient.ExecResult(2, "", ""));

        assertTrue(NpmUpdate.run(incus, NAME));

        verify(incus, never()).shellExec(eq(NAME), eq("npm"), eq("install"), any(), any(), any(), any());
    }
}
