package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdateAllCommandTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String NAME = "tpl-test";

    private final List<String> guestScripts = new ArrayList<>();

    /** A template whose every step succeeds; each test fails one. Records what runs as agentuser. */
    private IncusClient incus() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.shellExec(NAME, "which", "npm")).thenReturn(new IncusClient.ExecResult(1, "", ""));
        when(incus.execInContainer(eq(NAME), eq("agentuser"), anyString())).thenAnswer(inv -> {
            guestScripts.add(inv.getArgument(2));
            return OK;
        });
        return incus;
    }

    private static int update(IncusClient incus) {
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, null);
        return new UpdateAllCommand().updateTemplates(incus, resolved);
    }

    @Test
    void succeedsWhenEveryStepSucceeds() {
        assertEquals(0, update(incus()));
    }

    @Test
    void aFailedSystemUpdateFailsTheCommand() {
        var incus = incus();
        when(incus.shellExec(NAME, "dnf", "update", "-y"))
                .thenReturn(new IncusClient.ExecResult(1, "", "Failed to download metadata"));

        assertEquals(1, update(incus));
    }

    @Test
    void aFailedGitFetchFailsTheCommand() {
        var incus = incus();
        when(incus.execInContainer(eq(NAME), eq("agentuser"), anyString()))
                .thenReturn(new IncusClient.ExecResult(1, "", "git fetch failed in /home/agentuser/repo/"));

        assertEquals(1, update(incus));
    }

    @Test
    void theGitFetchScriptFetchesEveryRepoAndReportsAFailure(@TempDir Path home) throws Exception {
        update(incus());
        assertEquals(1, guestScripts.size());
        var script = guestScripts.getFirst();

        // a repository with no remotes fetches nothing, successfully
        git(home, "init", "-q", "good");
        Files.createDirectories(home.resolve("not-a-repo"));
        assertEquals(0, runAsLoginShell(script, home), "the script must parse and succeed");

        git(home, "init", "-q", "host-only");
        var hostOnly = home.resolve("host-only");
        git(hostOnly, "remote", "add", "origin", home.resolve("missing").toString());
        git(hostOnly, "config", "isx.hostOnly", "true");
        assertEquals(0, runAsLoginShell(script, home), "host-only repositories must not be fetched");
        assertFalse(Files.exists(hostOnly.resolve(".git/FETCH_HEAD")));

        git(home, "init", "-q", "broken");
        git(home.resolve("broken"), "remote", "add", "origin", home.resolve("missing").toString());
        assertEquals(1, runAsLoginShell(script, home), "a failed fetch must fail the script");
    }

    /** Runs {@code script} the way {@code su - agentuser -c} does: one bash command line. */
    private static int runAsLoginShell(String script, Path home) throws Exception {
        var pb = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        var process = pb.start();
        process.getInputStream().transferTo(System.out);
        int exit = process.waitFor();
        return exit;
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        assertEquals(0, new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
