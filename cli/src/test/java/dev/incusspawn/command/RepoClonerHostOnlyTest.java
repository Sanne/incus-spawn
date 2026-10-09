package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.git.HostRepoSource;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Host-only URL coverage moved from BuildCommandTest with the repository-cloning extraction. */
@ExtendWith(IsolatedHome.class)
class RepoClonerHostOnlyTest {
    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "failed");
    @TempDir Path tmp;

    private ImageDef definition(String url) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url);
        repo.setPath("~/copy");
        repo.setPrime("echo primed");
        var def = new ImageDef();
        def.setName("tpl-host-only");
        def.setSource(tmp.resolve(".incus-spawn/images/template.yaml").toString());
        def.setRepos(List.of(repo));
        def.setHostRepoRoot(tmp);
        return def;
    }

    private ImageDef repository(String form) throws Exception {
        var init = new ProcessBuilder("git", "init", "-b", "host-branch", tmp.toString()).start();
        assertEquals(0, init.waitFor());
        Files.createDirectories(tmp.resolve(".incus-spawn/images"));
        return definition(form.replace("{absolute}", tmp.resolve(".git").toUri().toString()));
    }

    private void clone(RepoCloner cloner, ImageDef def, MachineType type) {
        cloner.cloneRepos(new Container(incus, "test"), def, Map.of(def.getName(), def), type);
    }

    private final IncusClient incus = mock(IncusClient.class);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hostOnlyCloneUsesCapturedHeadWithoutRemoteFallback(boolean projectLocal) throws Exception {
        var def = repository("{absolute}");
        if (projectLocal) def.setProjectRoot(tmp);
        var repo = def.getRepos().getFirst();
        var snapshot = HostRepoSource.capture(repo);
        var cloner = new RepoCloner(incus);
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenReturn(OK);

        clone(cloner, def, MachineType.CONTAINER);
        var order = inOrder(incus);
        order.verify(incus).execInContainer(eq("test"), eq("agentuser"),
                endsWith(snapshot.cloneScript("/mnt/isx-local-repo-0", "/home/agentuser/copy")));
        order.verify(incus).deviceRemove("test", "ref-local-0");
        order.verify(incus).execInContainer(eq("test"), eq("agentuser"), contains("echo primed"));
        verify(incus, never()).execInContainer(eq("test"), anyString(),
                argThat((String s) -> s.contains("fetch --") || s.contains("remote set-head")));
        assertEquals(snapshot.fingerprint(), cloner.builtHostRepoFingerprints.get(repo.getUrl()));

        clearInvocations(incus);
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenReturn(FAIL);
        assertThrows(RuntimeException.class, () -> clone(cloner, def, MachineType.CONTAINER));
        verify(incus, times(1)).execInContainer(eq("test"), eq("agentuser"), anyString());
        verify(incus).deviceRemove("test", "ref-local-0");
    }

    @Test
    void missingHostSourceFailsBeforeAnyMount() {
        var def = definition(tmp.resolve("missing/.git").toUri().toString());
        var first = new ImageDef.RepoEntry();
        first.setUrl("https://example.com/first.git");
        def.setRepos(List.of(first, def.getRepos().getFirst()));
        assertThrows(RuntimeException.class, () -> clone(new RepoCloner(incus), def, MachineType.CONTAINER));
        verifyNoInteractions(incus);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{absolute}", "../..", "../../.git"})
    void hostOnlyMountIsRequiredAndVmTimeoutRemovesDevice(String form) throws Exception {
        var def = repository(form);
        def.getRepos().getFirst().setPath(null);
        assertEquals("~/" + tmp.getFileName(), def.getRepos().getFirst().getPath());
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenReturn(OK);
        clone(new RepoCloner(incus), def, MachineType.CONTAINER);
        verify(incus).deviceAdd(eq("test"), eq("ref-local-0"), eq("disk"),
                eq("source=" + tmp.resolve(".git").toRealPath()),
                eq("path=/mnt/isx-local-repo-0"), eq("readonly=true"), eq("shift=true"));

        clearInvocations(incus);
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString()))
                .thenReturn(new IncusClient.ExecResult(124, "", "mount timeout"));
        assertThrows(RuntimeException.class, () -> clone(new RepoCloner(incus), def, MachineType.VM));
        verify(incus).execInContainer(eq("test"), eq("agentuser"), contains("mountpoint"));
        verify(incus).deviceRemove("test", "ref-local-0");
        verify(incus, times(1)).execInContainer(eq("test"), eq("agentuser"), anyString());
    }

    @Test
    void changingHostHeadAbortsBeforePrime() throws Exception {
        var def = repository("{absolute}");
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(invocation -> {
            Files.writeString(tmp.resolve(".git/HEAD"), "ref: refs/heads/after\n");
            return OK;
        });
        assertThrows(RuntimeException.class, () -> clone(new RepoCloner(incus), def, MachineType.CONTAINER));
        verify(incus, times(1)).execInContainer(eq("test"), eq("agentuser"), anyString());
        verify(incus).deviceRemove("test", "ref-local-0");
    }

    @Test
    void failedMountWaitStopsTheActualCloneScript() throws Exception {
        var def = repository("{absolute}");
        var destination = tmp.resolve("guest");
        def.getRepos().getFirst().setPath(destination.toString());
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(invocation -> {
            String script = invocation.getArgument(2);
            script = script.replace(RepoCloner.awaitMount("/mnt/isx-local-repo-0"), "(exit 124)");
            var process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(124, process.waitFor(), output);
            assertFalse(Files.exists(destination), "nothing after the mount wait may run");
            return new IncusClient.ExecResult(124, output, "");
        });
        assertThrows(RuntimeException.class, () -> clone(new RepoCloner(incus), def, MachineType.VM));
        verify(incus, times(1)).execInContainer(eq("test"), eq("agentuser"), anyString());
        verify(incus).deviceRemove("test", "ref-local-0");
    }

    @Test
    void hostOnlySourcesRejectBranchOverrides() {
        var def = definition("file:///host/repo/.git");
        def.getRepos().getFirst().setBranch("main");
        assertThrows(IllegalArgumentException.class, () -> clone(new RepoCloner(incus), def, MachineType.CONTAINER));
        verifyNoInteractions(incus);
    }

    @Test
    void projectLocalTemplatesCannotMountHostOnlySourcesOutsideTheirRoot() {
        var def = definition("file:///host/private/.git");
        def.setProjectRoot(tmp);
        var failure = assertThrows(RuntimeException.class,
                () -> clone(new RepoCloner(incus), def, MachineType.CONTAINER));
        assertTrue(failure.getMessage().contains("normalized-path rule failed"));
        assertTrue(failure.getMessage().contains("allowed root: " + tmp));
        verifyNoInteractions(incus);
    }
}
