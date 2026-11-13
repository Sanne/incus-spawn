package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
    // What the registry answered while @openai/codex 0.165.0 was being published (#1757).
    private static final IncusClient.ExecResult NPM_404 = new IncusClient.ExecResult(1, "", """
            npm error code E404
            npm error 404 Not Found - GET https://registry.npmjs.org/@openai/codex/-/codex-0.165.0.tgz - Not found
            """);
    private static final String CONTAINER = "test-container";

    private final NpmWaits waits = new NpmWaits();

    @AfterEach
    void restoreSleep() {
        waits.close();
    }
    private static final NpmGlobalInstall.PlatformSplitCli CODEX = CodexSetup.NPM_CLI;

    private static IncusClient incus() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);
        return incus;
    }

    private static void stubCheck(IncusClient incus, String npmPackage, IncusClient.ExecResult first,
                                  IncusClient.ExecResult... rest) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"),
                argThat(arg -> arg.contains("npm root -g") && arg.contains("/" + npmPackage + "/package.json"))))
                .thenReturn(first, rest);
    }

    private static void stubNpm(IncusClient incus, String npmPackage, IncusClient.ExecResult first,
                                IncusClient.ExecResult... rest) {
        when(incus.shellExec(eq(CONTAINER), eq("npm"), eq("install"), eq("-g"),
                eq("--ignore-scripts"), eq("--loglevel=verbose"), eq(npmPackage))).thenReturn(first, rest);
    }

    private static void verifyNpmInstalls(IncusClient incus, String npmPackage, int times) {
        verify(incus, times(times)).shellExec(eq(CONTAINER), eq("npm"), eq("install"), eq("-g"),
                eq("--ignore-scripts"), eq("--loglevel=verbose"), eq(npmPackage));
    }

    /** The lines the warnings {@code action} reported show under them, each ended by a newline. */
    private static String warnedLinesOf(Runnable action) {
        var recorded = dev.incusspawn.util.RecordingOutput.during(action::run);
        var lines = new StringBuilder();
        for (var warning : recorded.events(dev.incusspawn.util.RecordingOutput.Kind.WARN)) {
            warning.details().forEach(line -> lines.append(line).append('\n'));
        }
        return lines.toString();
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
        assertEquals(NpmGlobalInstall.REGISTRY_RETRY_DELAYS, waits.waited());
        verifyNpmInstalls(incus, "@openai/codex", 2 + NpmGlobalInstall.REGISTRY_RETRY_DELAYS.size());
        verify(incus, never()).execInContainer(anyString(), anyString(), anyString());
    }

    @Test
    void waitsForAPlatformBuildPublishedAfterItsLauncher() {
        // npm publishes @openai/codex minutes before @openai/codex@<v>-linux-x64, and skips the
        // missing optional version silently (#1757).
        var incus = incus();
        stubCheck(incus, "@openai/codex", CODEX_MISSING, CODEX_MISSING, CODEX_MISSING, OK);

        NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX);

        assertEquals(List.of(Duration.ofSeconds(15), Duration.ofSeconds(30)), waits.waited());
        verifyNpmInstalls(incus, "@openai/codex", 4);
    }

    @Test
    void retriesATarballTheRegistryDoesNotServeYet() {
        var incus = incus();
        stubNpm(incus, "@openai/codex", NPM_404, OK);

        NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX);

        assertEquals(List.of(Duration.ofSeconds(15)), waits.waited());
        verifyNpmInstalls(incus, "@openai/codex", 2);
    }

    @Test
    void retriesAVersionTheRegistryDoesNotResolveYet() {
        var incus = incus();
        stubNpm(incus, "@google/gemini-cli", new IncusClient.ExecResult(1, "",
                "npm error code ETARGET\nnpm error notarget No matching version found for @google/gemini-cli@0.99.0.\n"), OK);

        NpmGlobalInstall.install(new Container(incus, CONTAINER), "Gemini CLI", "@google/gemini-cli");

        assertEquals(List.of(Duration.ofSeconds(15)), waits.waited());
        verifyNpmInstalls(incus, "@google/gemini-cli", 2);
    }

    @Test
    void givesUpOnARegistryMissOnceTheRetriesAreSpent() {
        var incus = incus();
        stubNpm(incus, "@openai/codex", NPM_404);

        var e = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertEquals("Failed to install Codex CLI (exit code 1)", e.getMessage());
        assertEquals(NpmGlobalInstall.REGISTRY_RETRY_DELAYS, waits.waited());
        verifyNpmInstalls(incus, "@openai/codex", 1 + NpmGlobalInstall.REGISTRY_RETRY_DELAYS.size());
    }

    @Test
    void failsAtOnceWhenOnlyTheVerboseLogMentionsA404() {
        var incus = incus();
        stubNpm(incus, "@openai/codex", new IncusClient.ExecResult(1, "", """
                npm http fetch GET 404 https://registry.npmjs.org/@types%2fnode 40ms
                npm verbose stack HttpErrorGeneral: 404 Not Found, code E404
                npm error code EACCES
                """));

        assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertEquals(List.of(), waits.waited());
        verifyNpmInstalls(incus, "@openai/codex", 1);
    }

    @Test
    void sharesOneRetryBudgetAcrossAMissedTarballAndASkippedPlatformBuild() {
        var incus = incus();
        stubNpm(incus, "@openai/codex", NPM_404, OK);
        stubCheck(incus, "@openai/codex", CODEX_MISSING);

        assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertEquals(NpmGlobalInstall.REGISTRY_RETRY_DELAYS, waits.waited());
    }

    @Test
    void failsAtOnceWhenNpmFailsForAnotherReason() {
        var incus = incus();
        stubNpm(incus, "@openai/codex", new IncusClient.ExecResult(1, "", "npm error code EACCES"));

        var e = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX));

        assertEquals("Failed to install Codex CLI (exit code 1)", e.getMessage());
        assertEquals(List.of(), waits.waited());
        verifyNpmInstalls(incus, "@openai/codex", 1);
    }

    @Test
    void showsAFailedNpmsOutputAsText() {
        // An npm package's install script decides what npm prints (#1588).
        var incus = incus();
        when(incus.shellExec(eq(CONTAINER), eq("npm"), any(), any(), any(), any(), any()))
                .thenReturn(new IncusClient.ExecResult(1, "\033]0;pwned\007out\n", "\033[31mnpm error\033[0m\n"));
        var thrown = new IncusException[1];
        var err = warnedLinesOf(() -> thrown[0] = assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX)));
        assertEquals("out\nnpm error\n", err);
        // The failure report gets it from the exception, all of it, as text
        assertEquals("npm printed:\nout\nnpm error\n\n", thrown[0].stepOutput());
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
    void repairAfterUpdateWaitsForAPlatformBuildNotPublishedYet() {
        var incus = incus();
        stubCheck(incus, "@openai/codex", CODEX_MISSING, CODEX_MISSING, OK);
        stubCheck(incus, "@github/copilot", OK);

        assertEquals(List.of(), NpmGlobalInstall.repairAfterUpdate(new Container(incus, CONTAINER)));

        assertEquals(List.of(Duration.ofSeconds(15)), waits.waited());
        verifyNpmInstalls(incus, "@openai/codex", 2);
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
        stubNpm(incus, "@openai/codex", NPM_SKIPPED_IT);

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

    @Test
    void skipReasonsKeepNoSecretOnlyTheRawLogShows() {
        // The failure report keeps them: scrubbed as npm printed them and as shown (#1878)
        var npm = new IncusClient.ExecResult(0, "", "npm error fetch https://u:hunter2\u0001secretpw@host/pkg.tgz\nnpm ok\n");
        assertEquals(List.of("npm error fetch https://u:<isx:redacted:url-credentials>@host/pkg.tgz"),
                NpmGlobalInstall.skipReasons(npm));
    }

    @Test
    void skipReasonsQuoteTheRawLineAShownLineCameFromAcrossAControlString() {
        // A title escape run across two lines shows them as one, so the shown line after it is
        // the third raw one, not the second (#1920)
        var npm = new IncusClient.ExecResult(0, "", "a\033]0;t\nu\007b\nnpm error real\nnpm ok\n");
        assertEquals(List.of("npm error real"), NpmGlobalInstall.skipReasons(npm));
    }

    @Test
    void showsNpmsSkipReasonsAsText() {
        // An npm package's install script decides what npm's verbose log holds (#1588).
        var npm = new IncusClient.ExecResult(0, "", "\033]52;c;eA==\007\033[31mnpm error\033[0m \033[2Kskipped\nfetch error 10%\rfetch ok 100%\n");
        var incus = incus();
        stubCheck(incus, "@openai/codex", CODEX_MISSING);
        stubCheck(incus, "@github/copilot", OK);
        stubNpm(incus, "@openai/codex", npm);
        var err = warnedLinesOf(() -> assertThrows(IncusException.class,
                () -> NpmGlobalInstall.install(new Container(incus, CONTAINER), CODEX)));
        assertEquals("npm error skipped\n", err);

        var problems = NpmGlobalInstall.repairAfterUpdate(new Container(incus, CONTAINER));
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().endsWith("\n  npm error skipped"), problems.getFirst());
    }

    @Test
    void updateRunsInstallScriptsInAScratchHomeAndRemovesIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // A stub npm records the HOME it runs with and writes where an install script would
        var bin = java.nio.file.Files.createDirectories(dir.resolve("bin"));
        var npm = bin.resolve("npm");
        java.nio.file.Files.writeString(npm, """
                #!/bin/sh
                [ "$1 $3" = "config cache" ] && { echo /the/cache; exit 0; }
                [ "$1" = config ] && { echo /the/npmrc; exit 0; }
                echo "$HOME $TMPDIR $npm_config_cache $npm_config_userconfig" > "$OUT"
                mkdir -p "$HOME/.local/share/opencode"
                """);
        assertTrue(npm.toFile().setExecutable(true));
        var out = dir.resolve("out");
        var pb = new ProcessBuilder("sh", "-c", NpmGlobalInstall.UPDATE_SCRIPT);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("HOME", dir.resolve("realhome").toString());
        pb.environment().put("OUT", out.toString());
        assertEquals(0, pb.start().waitFor());
        var seen = java.nio.file.Files.readString(out).strip().split(" ");
        assertNotEquals(dir.resolve("realhome").toString(), seen[0]);
        assertEquals(seen[0], seen[1]);
        assertEquals("/the/cache", seen[2]);
        assertEquals("/the/npmrc", seen[3]);
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(seen[0])));
        assertFalse(java.nio.file.Files.exists(dir.resolve("realhome")));
    }
}
