package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the build's guest provisioning reports when it goes on without something (#1703). */
class GuestProvisioningOutputTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    /** A .claude.json it cannot update is one warning, which the TUI's log and a recorder keep. */
    @Test
    void aClaudeJsonItCannotUpdateIsAWarning() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("cat"), anyString())).thenReturn(new IncusClient.ExecResult(0, "\"a string\"", ""));
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        var recorded = RecordingOutput.during(() ->
                GuestProvisioning.updateClaudeJsonTrust(new Container(incus, "test"), imageDef, Map.of()));

        var warnings = recorded.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warnings.size(), recorded.events().toString());
        assertTrue(warnings.getFirst().msg().startsWith("Could not update /home/agentuser/.claude.json: "), warnings.getFirst().msg());
    }

    /** Masking services is work the build did, so a live step that ends in its ✓ (#1791). */
    @Test
    void maskingServicesIsALiveStep() {
        var incus = mock(IncusClient.class, call ->
                call.getMethod().getReturnType() == IncusClient.ExecResult.class ? OK : null);
        var imageDef = new ImageDef();
        imageDef.setMaskServices(List.of("dnf-makecache.timer"));

        var recorded = RecordingOutput.during(() -> GuestProvisioning.maskServices(new Container(incus, "test"), imageDef));

        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.STEP_START, "Masking unnecessary services"),
                new RecordingOutput.Event(RecordingOutput.Kind.STEP_DONE, null)), recorded.events());
    }

    /**
     * A login lookup that fails leaves the {github.login} remote's URL unfilled and warns: the remote is
     * optional, and a lookup the build does not need for its identity never fails it (#343, #2396).
     */
    @Test
    void aLoginLookupThatFailsWarnsAndBuildsOn() {
        var incus = mock(IncusClient.class, call ->
                call.getMethod().getReturnType() == IncusClient.ExecResult.class ? OK : null);
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://git.example.com/example-org/example-repo.git");
        repo.setPath("~/quarkus");
        repo.setRemotes(Map.of("origin", "https://git.example.com/{github.login}/example-repo.git"));
        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));
        var gh = new dev.incusspawn.tool.GhSetup() {
            @Override
            public String githubLogin(Container c, dev.incusspawn.config.SpawnConfig config, String accountName) {
                throw new dev.incusspawn.incus.IncusException("Could not ask GitHub for the account's login (HTTP 503)");
            }
        };

        var recorded = RecordingOutput.during(() -> GuestProvisioning.setLoginRemotes(new Container(incus, "test"),
                imageDef, Map.of(), List.of(new BuildTools.ResolvedTool("gh", gh, Map.of()))));

        var warnings = recorded.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warnings.size(), recorded.events().toString());
        assertTrue(warnings.getFirst().msg().endsWith("remote 'origin' in /home/agentuser/quarkus"), warnings.getFirst().msg());
        assertTrue(warnings.getFirst().details().getFirst().contains("HTTP 503"), warnings.getFirst().details().toString());
        org.mockito.Mockito.verify(incus).execInContainer(eq("test"), eq("agentuser"),
                org.mockito.ArgumentMatchers.contains("login=''"));
    }

    /** A fill that fails, as in a recorded clone that is no longer a work tree, is a warning, not a failed build. */
    @Test
    void aFillThatFailsWarnsAndBuildsOn() {
        var incus = mock(IncusClient.class, call ->
                call.getMethod().getReturnType() == IncusClient.ExecResult.class ? OK : null);
        when(incus.execInContainer(eq("test"), eq("agentuser"), org.mockito.ArgumentMatchers.contains("login=")))
                .thenReturn(new IncusClient.ExecResult(1, "", "fatal: not a git repository"));
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://git.example.com/example-org/example-repo.git");
        repo.setPath("~/quarkus");
        repo.setRemotes(Map.of("origin", "https://git.example.com/{github.login}/example-repo.git"));
        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        var recorded = RecordingOutput.during(() -> GuestProvisioning.setLoginRemotes(new Container(incus, "test"),
                imageDef, Map.of(), List.of()));

        var warnings = recorded.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warnings.size(), recorded.events().toString());
        assertEquals("Could not set the remotes whose URL names the GitHub account's login", warnings.getFirst().msg());
    }

    /** No DNF cache to mount is a warning naming why; none when there is no reason. */
    @Test
    void anUnmountableDnfCacheIsAWarning() {
        var recorded = RecordingOutput.during(() -> {
            GuestProvisioning.warnDnfCacheUnavailable("no pool");
            GuestProvisioning.warnDnfCacheUnavailable(null);
        });
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "Could not mount the DNF cache, so builds are slower.", List.of("no pool"))), recorded.events());
    }
}
