package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NpmGlobalInstallTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult CODEX_MISSING =
            new IncusClient.ExecResult(3, "@openai/codex-linux-x64\n", "");
    private static final IncusClient.ExecResult NOT_INSTALLED = new IncusClient.ExecResult(2, "", "");
    private static final IncusClient.ExecResult NPM_SKIPPED_IT = new IncusClient.ExecResult(0,
            "", """
            npm verbose cli /usr/bin/node /usr/bin/npm
            npm http fetch GET https://registry.npmjs.org/@openai/codex/-/codex-0.157.1-linux-x64.tgz attempt 1 failed with ECONNRESET
            npm verbose reify failed optional dependency /usr/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-x64
            npm http fetch GET 200 https://registry.npmjs.org/@openai/codex 12ms (referer: install)
            """);
    private static final String CONTAINER = "test-container";
    private static final NpmGlobalInstall.PlatformSplitCli CODEX = CodexSetup.NPM_CLI;

    private static IncusClient incus() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), any(String[].class))).thenReturn(OK);
        return incus;
    }

    private static void stubCheck(IncusClient incus, String npmPackage, IncusClient.ExecResult first,
                                  IncusClient.ExecResult... rest) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"),
                argThat(arg -> arg.contains("npm root -g") && arg.contains("/" + npmPackage + "/package.json"))))
                .thenReturn(first, rest);
    }

    private static void verifyNpmInstalls(IncusClient incus, String npmPackage, int times) {
        verify(incus, times(times)).shellExec(eq(CONTAINER), eq("npm"), eq("install"), eq("-g"),
                eq("--ignore-scripts"), eq("--loglevel=verbose"), eq(npmPackage));
    }

    @Test
    void reinstallsOnceWhenNpmSkippedThePlatformPackage() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", CODEX_MISSING, OK);

        NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX);

        verifyNpmInstalls(incus, "@openai/codex", 2);
    }

    @Test
    void failsWithNpmsReasonWhenTheReinstallSkipsItToo() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", CODEX_MISSING);

        var e = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertTrue(e.getMessage().contains("@openai/codex-linux-x64"), e.getMessage());
        verifyNpmInstalls(incus, "@openai/codex", 2);
        verify(incus, never()).execInContainer(anyString(), anyString(), any(String[].class));
    }

    @Test
    void failsWhenNpmItselfFails() {
        var incus = incus();
        when(incus.shellExec(eq(CONTAINER), eq("npm"), any(), any(), any(), any(), any()))
                .thenReturn(new IncusClient.ExecResult(1, "", "npm error code E404"));

        var e = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertEquals("Failed to install Codex CLI (exit code 1)", e.getMessage());
        verifyNpmInstalls(incus, "@openai/codex", 1);
    }

    @Test
    void failsWhenTheLauncherIsNotInNpmsGlobalRoot() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", NOT_INSTALLED);

        var e = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertTrue(e.getMessage().contains("not in npm's global root"), e.getMessage());
    }

    @Test
    void blamesThePathNotTheDownloadWhenTheBinaryIsNotFound() {
        var incus = incus();
        when(incus.execInContainer(CONTAINER, "agentuser", "command -v codex"))
                .thenReturn(new IncusClient.ExecResult(1, "", ""));

        var e = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertTrue(e.getMessage().contains("not on agentuser's PATH"), e.getMessage());
        assertFalse(e.getMessage().contains("platform package"), e.getMessage());
    }

    @Test
    void failsLoudlyWhenTheCheckItselfBreaks() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", new IncusClient.ExecResult(127, "", "node: not found"));

        var e = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertTrue(e.getMessage().startsWith("Could not check Codex CLI's platform package"), e.getMessage());
    }

    @Test
    void checkRunsAsRootWithoutRunningTheCli() {
        // Running the CLI would write into the template (copilot unpacks ~165 MB into ~/.cache).
        var incus = incus();

        NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX);

        verify(incus, never()).execInContainer(anyString(), anyString(),
                argThat((String arg) -> arg.contains("--version")));
        verify(incus, never()).shellExec(anyString(), argThat((String arg) -> arg.contains("--version")));
    }

    @Test
    void repairAfterUpdateIgnoresClisTheTemplateDoesNotHave() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", NOT_INSTALLED);
        stubCheck(incus, "@github/copilot", NOT_INSTALLED);

        assertEquals(List.of(), NpmGlobalInstall.repairAfterUpdate(new Container(incus, CONTAINER)));

        verify(incus, never()).shellExec(eq(CONTAINER), eq("npm"), any(), any(), any(), any(), any());
    }

    @Test
    void repairAfterUpdateReinstallsACliTheUpdateBroke() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", CODEX_MISSING, OK);
        stubCheck(incus, "@github/copilot", OK);

        assertEquals(List.of(), NpmGlobalInstall.repairAfterUpdate(new Container(incus, CONTAINER)));

        verifyNpmInstalls(incus, "@openai/codex", 1);
        verifyNpmInstalls(incus, "@github/copilot", 0);
    }

    @Test
    void repairAfterUpdateReportsACliItCouldNotRepairWithNpmsReason() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", CODEX_MISSING);
        stubCheck(incus, "@github/copilot", OK);
        when(incus.shellExec(eq(CONTAINER), eq("npm"), eq("install"), eq("-g"),
                eq("--ignore-scripts"), eq("--loglevel=verbose"), eq("@openai/codex"))).thenReturn(NPM_SKIPPED_IT);

        var problems = NpmGlobalInstall.repairAfterUpdate(new Container(incus, CONTAINER));

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("@openai/codex-linux-x64"), problems.getFirst());
        assertTrue(problems.getFirst().contains("attempt 1 failed with ECONNRESET"), problems.getFirst());
    }

    @Test
    void skipReasonsKeepOnlyTheLinesThatSayWhatFailed() {
        assertEquals(List.of(
                "npm http fetch GET https://registry.npmjs.org/@openai/codex/-/codex-0.157.1-linux-x64.tgz attempt 1 failed with ECONNRESET",
                "npm verbose reify failed optional dependency /usr/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-x64"),
                NpmGlobalInstall.skipReasons(NPM_SKIPPED_IT));
    }
}
