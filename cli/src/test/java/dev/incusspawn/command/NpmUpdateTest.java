package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tool.NpmGlobalInstall;
import dev.incusspawn.tool.NpmWaits;
import dev.incusspawn.util.RecordingOutput;
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

        verify(incus, never()).shellExec(NAME, "sh", "-c", NpmGlobalInstall.UPDATE_SCRIPT);
    }

    @Test
    void reportsAFailedUpdate() {
        var incus = incus();
        when(incus.shellExec(NAME, "sh", "-c", NpmGlobalInstall.UPDATE_SCRIPT)).thenReturn(new IncusClient.ExecResult(1, "", "npm error"));

        assertFalse(NpmUpdate.run(incus, NAME));
    }

    @Test
    void retriesAnUpdateTheRegistryCannotServeYet() {
        var incus = incus();
        when(incus.shellExec(NAME, "sh", "-c", NpmGlobalInstall.UPDATE_SCRIPT)).thenReturn(
                new IncusClient.ExecResult(1, "", "npm error code ETARGET\n"), OK);

        try (var waits = new NpmWaits()) {
            assertTrue(NpmUpdate.run(incus, NAME));
            assertEquals(1, waits.waited().size());
        }
        verify(incus, times(2)).shellExec(NAME, "sh", "-c", NpmGlobalInstall.UPDATE_SCRIPT);
    }

    @Test
    void reportsAnUpdateThatLeftACliWithoutItsPlatformBinary() {
        // npm update -g exits 0 when it drops a CLI's optional platform package (#808).
        var incus = incus();
        stubCheck(incus, "@openai/codex", new IncusClient.ExecResult(3, "@openai/codex-linux-x64\n", ""));

        int retries;
        RecordingOutput recorded;
        try (var waits = new NpmWaits()) {
            recorded = RecordingOutput.during(() -> assertFalse(NpmUpdate.run(incus, NAME)));
            retries = waits.waited().size();
        }
        // The step fails saying what is broken, which the TUI's log keeps, never a raw stderr line (#1703)
        var failed = recorded.events(RecordingOutput.Kind.STEP_FAIL);
        assertEquals(1, failed.size(), recorded.events().toString());
        assertTrue(failed.getFirst().msg().contains("@openai/codex-linux-x64"), failed.getFirst().msg());

        verify(incus, times(1 + retries)).shellExec(eq(NAME), eq("npm"), eq("install"), eq("-g"),
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
