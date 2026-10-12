package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepoClonerTest {

    private static RepoCloner cloner(IncusClient incus) {
        var cloner = spy(new RepoCloner(incus));
        doReturn(null).when(cloner).planReference(any(), any());
        doReturn("").when(cloner).checkTemplateAccess(any(), any(), any(), anyInt());
        doAnswer(inv -> {
            RepoCloner.RepoReference ref = inv.getArgument(1);
            return reference(ref.deviceName(), ref.containerPath(), ref.source(), null);
        }).when(cloner).prepareReference(any(), any(), any(), anyInt(), any());
        return cloner;
    }

    static RepoCloner.RepoReference reference(String device, String path, String source, String reason) {
        var prepared = mock(dev.incusspawn.git.GitPreparation.Prepared.class);
        when(prepared.checksums()).thenReturn(java.util.Map.of());
        when(prepared.strategy()).thenReturn("prepared Git packs");
        when(prepared.timingSummary()).thenReturn("prepare=0ms");
        return new RepoCloner.RepoReference(device, path, source, reason, null, prepared);
    }

    private static RepoCloner.RepoReference plannedReference(String device, String path, String source, String reason) {
        return new RepoCloner.RepoReference(device, path, source, reason,
                new dev.incusspawn.git.GitPreparation.Request("https://example.invalid/repo", null, null), null);
    }

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shallowClonesReportDepthAndTimingsOnEveryBuild(boolean reference) {
        var incus = mock(IncusClient.class);
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        var cmd = cloner(incus);
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://example.invalid/releases.git");
        repo.setDepth(1);
        if (reference) doReturn(new RepoCloner.RepoReference("ref", "/mnt/ref", "/host/releases", null))
                .when(cmd).planReference(eq(repo.getUrl()), any());
        var other = new ImageDef.RepoEntry();
        other.setUrl("https://example.invalid/other.git");
        var def = new ImageDef();
        def.setName("tpl-test");
        def.setRepos(List.of(repo, other));
        var output = new java.io.ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;
        try {
            System.setOut(new java.io.PrintStream(output, true));
            System.setErr(new java.io.PrintStream(output, true));
            cmd.cloneRepos(new Container(incus, "test"), def, java.util.Map.of(), MachineType.CONTAINER);
            String first = output.toString();
            assertTrue(first.contains("via " + (reference ? "host reference" : "remote") + " (shallow, depth 1)"), first);
            assertTrue(first.matches("(?s).*releases: attach=[0-9]+ms, guest=[0-9]+ms.*"), first);
            assertTrue(first.matches("(?s).*other: attach=[0-9]+ms, guest=[0-9]+ms.*"), first);
            if (reference) assertTrue(first.matches("(?s).*releases: attach=[0-9]+ms, guest=[0-9]+ms, detach=[0-9]+ms.*"), first);
            output.reset();
            def.setRepos(List.of(repo));
            cmd.cloneRepos(new Container(incus, "test"), def, java.util.Map.of(), MachineType.CONTAINER);
            assertTrue(output.toString().contains("releases: attach="), output.toString());
            assertFalse(output.toString().contains("other:"), "timings must not leak across builds");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void cloneReposRunsPrimeCommand() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        // Clone, refspec restore, and prime all run as captured exec as agentuser.
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");
        repo.setPrime("mvn -B dependency:go-offline");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        verify(incus).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/quarkusio/quarkus.git' '/home/agentuser/quarkus'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/quarkus' remote set-branches origin '*'");
        verify(incus).execInContainer("test", "agentuser",
                "cd '/home/agentuser/quarkus' && mvn -B dependency:go-offline");
    }

    @Test
    void cloneReposWithBranch() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setBranch("feature/my branch");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        verify(incus).execInContainer("test", "agentuser",
                "git clone --branch 'feature/my branch' -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/repo' remote set-branches origin '*'");
    }

    @Test
    void cloneReposSkipsPrimeWhenNotSet() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        // no prime set

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        // Clone call + refspec restore, but no prime
        verify(incus, times(2)).execInContainer(eq("test"), anyString(), anyString());
    }

    @Test
    void cloneReposWithReferenceLocalClone() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        var ref = plannedReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        verify(incus).execInContainer(eq("test"), eq("agentuser"), argThat(script ->
                script.contains("git clone --no-hardlinks") && script.contains("remote set-url origin")
                        && !script.contains("pack-objects") && !script.contains("fetch --")));
        // Reference device cleaned up
        verify(incus).deviceRemove("test", "ref-repo");
    }

    @Test
    void cloneReposWithReferenceFailsFallsBack() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        // First call (local clone from reference) fails, rest succeed
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(FAIL)  // local clone fails
                .thenReturn(OK)    // cleanup rm -rf
                .thenReturn(OK)    // normal clone
                .thenReturn(OK);   // refspec restore

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        var ref = plannedReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        // Should fall back to normal clone
        verify(incus).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        // Reference device still cleaned up
        verify(incus).deviceRemove("test", "ref-repo");
    }

    @Test
    void cloneReposWithReferenceAndBranchCheckout() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setBranch("feature/x");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        var ref = plannedReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        verify(incus).execInContainer(eq("test"), eq("agentuser"), argThat(script ->
                script.contains("git clone --no-hardlinks --branch 'feature/x'")
                        && script.contains("remote set-url origin")));
    }

    @Test
    void cloneReposWithReferenceCheckoutFailsFallsBack() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(FAIL)  // copy/checkout/verification fails
                .thenReturn(OK)    // cleanup rm -rf
                .thenReturn(OK)    // normal remote clone
                .thenReturn(OK);   // set-branches

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setBranch("nonexistent");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        var ref = plannedReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        // Should fall back to normal clone after checkout failure
        verify(incus).execInContainer("test", "agentuser",
                "git clone --branch 'nonexistent'"
                        + " -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        verify(incus).deviceRemove("test", "ref-repo");
    }

    @Test
    void cloneReposClonesMultipleReposInParallel() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var a = new ImageDef.RepoEntry();
        a.setUrl("https://github.com/owner/alpha.git");
        a.setPath("~/alpha");
        var b = new ImageDef.RepoEntry();
        b.setUrl("https://github.com/owner/beta.git");
        b.setPath("~/beta");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(a, b));

        var cmd = cloner(incus);
        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        verify(incus).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/owner/alpha.git' '/home/agentuser/alpha'");
        verify(incus).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/owner/beta.git' '/home/agentuser/beta'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/alpha' remote set-branches origin '*'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/beta' remote set-branches origin '*'");
    }

    @Test
    void cloneReposPrimesEachRepoInItsOwnWorker() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var a = new ImageDef.RepoEntry();
        a.setUrl("https://github.com/owner/alpha.git");
        a.setPath("~/alpha");
        a.setPrime("build-alpha");
        var b = new ImageDef.RepoEntry();
        b.setUrl("https://github.com/owner/beta.git");
        b.setPath("~/beta");
        b.setPrime("build-beta");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(a, b));

        var cmd = cloner(incus);
        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        // Each repo's prime runs (pipelined with the other repo's clone), scoped to
        // its own working directory.
        verify(incus).execInContainer("test", "agentuser", "cd '/home/agentuser/alpha' && build-alpha");
        verify(incus).execInContainer("test", "agentuser", "cd '/home/agentuser/beta' && build-beta");
    }

    @Test
    void prepareOneSkipsPrimeWhenAnotherRepoAlreadyFailed() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/beta.git");
        repo.setPath("~/beta");
        repo.setPrime("build-beta");

        var states = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        states.set(0, BuildProgress.StepProgress.running("Cloning"));
        // Another repo has already failed → the build will abort.
        var failureSeen = new java.util.concurrent.atomic.AtomicBoolean(true);

        cloner(incus).prepareOne(container, repo, null, states, 0, failureSeen);

        // The clone still ran, but priming was skipped rather than doing work the
        // aborting build will throw away.
        verify(incus).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/owner/beta.git' '/home/agentuser/beta'");
        verify(incus, never()).execInContainer("test", "agentuser",
                "cd '/home/agentuser/beta' && build-beta");
        assertEquals(BuildProgress.StepState.DONE, states.get(0).state());
        assertTrue(states.get(0).note().contains("priming skipped"),
                "the skipped-prime clone should say so");
    }

    @Test
    void awaitMountRunsUnderShAndTimesOutOnlyOnAPathThatIsNotAMountPoint() throws Exception {
        // The script runs in a Linux guest. macOS has no `mountpoint` (awaitMount then skips the
        // wait, by design) and no `timeout`, so only a host with both can exercise the wait.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                runSh("command -v mountpoint >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1") == 0,
                "needs mountpoint and timeout on the host, as a Linux guest has");
        assertEquals(0, runSh(RepoCloner.awaitMount("/", 5)));
        assertEquals(0, runSh(RepoCloner.awaitMount("/", 5) + " && true"));
        var dir = java.nio.file.Files.createTempDirectory("not-a-mount");
        try {
            assertEquals(124, runSh(RepoCloner.awaitMount(dir.toString(), 1)));
            assertNotEquals(0, runSh(RepoCloner.awaitMount(dir.toString(), 1) + " && true"));
        } finally {
            java.nio.file.Files.delete(dir);
        }
    }

    @Test
    void awaitMountIsSkippedWhereTheGuestHasNoMountpoint() throws Exception {
        var empty = java.nio.file.Files.createTempDirectory("empty-path");
        try {
            // A PATH with nothing on it finds no `mountpoint`, as in a guest without util-linux.
            assertEquals(0, runSh("PATH='" + empty + "'; " + RepoCloner.awaitMount("/nowhere", 1)));
        } finally {
            java.nio.file.Files.delete(empty);
        }
    }

    @Test
    void failedCloneOverAnExistingDestinationLeavesItInPlace() throws Exception {
        // #1394: a checkout already at the destination (inherited from a parent, primed there) was
        // removed by the fallback's cleanup when the reference clone refused to write over it.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(new IncusClient.ExecResult(RepoCloner.DESTINATION_EXISTS_EXIT, "",
                        "fatal: destination path '/home/agentuser/repo' already exists"))
                .thenReturn(new IncusClient.ExecResult(128, "",
                        "fatal: destination path '/home/agentuser/repo' already exists and is not an empty directory."));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        doReturn(plannedReference("ref-repo", "/mnt/ref/repo", "/host/repo", null))
                .when(cmd).planReference(eq(repo.getUrl()), any());

        assertThrows(RuntimeException.class, () -> cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        verify(incus, never()).execInContainer("test", "agentuser", "rm -rf '/home/agentuser/repo'");

        // The guard itself, under sh: an existing destination fails with its own status, untouched.
        // An empty directory is one git clones into, so it does not count.
        var dir = java.nio.file.Files.createTempDirectory("existing-checkout");
        try {
            assertEquals(0, runSh(RepoCloner.freshDestination(dir.toString())));
            assertEquals(0, runSh(RepoCloner.freshDestination(dir.resolve("absent").toString())));
            var head = java.nio.file.Files.createFile(dir.resolve("HEAD"));
            assertEquals(RepoCloner.DESTINATION_EXISTS_EXIT, runSh(RepoCloner.freshDestination(dir.toString())));
            assertTrue(java.nio.file.Files.exists(head));
            java.nio.file.Files.delete(head);
        } finally {
            java.nio.file.Files.delete(dir);
        }
    }

    @Test
    void cloneReposSkipsADestinationAnAncestorDeclared() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(repo("https://github.com/owner/shared.git", "~/shared", "prime-it")));
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        // The inherited one again, with an explicit path that is the same destination, and a new one
        // declared twice in the same template.
        child.setRepos(List.of(repo("https://github.com/owner/shared", "/home/agentuser/shared", "prime-it"),
                repo("https://github.com/owner/own.git", null, null),
                repo("https://github.com/owner/own.git", "~/own", null)));

        cloner(incus).cloneRepos(container, child,
                java.util.Map.of("tpl-parent", parent, "tpl-child", child), MachineType.CONTAINER);

        verify(incus, never()).execInContainer(eq("test"), eq("agentuser"), contains("shared"));
        verify(incus, times(1)).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/owner/own.git' '/home/agentuser/own'");
    }

    @Test
    void failedPrimeCarriesStepOutputOnException() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(OK)  // clone
                .thenReturn(OK)  // refspec restore
                .thenReturn(new IncusClient.ExecResult(1, "npm ERR! could not resolve dependency\nnpm ERR! peer dep missing", ""));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/app.git");
        repo.setPath("~/app");
        repo.setPrime("npm install");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        var ex = assertThrows(IncusException.class,
                () -> cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertNotNull(ex.stepOutput(), "the exception should carry step output from the failed prime");
        assertTrue(ex.stepOutput().contains("npm ERR! could not resolve dependency"),
                "step output should contain the prime's captured stdout");
    }

    private static ImageDef.RepoEntry repo(String url, String path, String prime) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url);
        repo.setPath(path);
        repo.setPrime(prime);
        return repo;
    }

    private static int runSh(String script) throws Exception {
        return new ProcessBuilder("sh", "-c", script).inheritIO().start().waitFor();
    }

    @Test
    void aFailedCloneFromTheReferenceSaysWhyItClonedFromTheNetwork() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(new IncusClient.ExecResult(128, "",
                        "fatal: repository '/mnt/ref/repo' does not exist\n")) // local clone fails
                .thenReturn(OK); // cleanup, network clone, refspec restore

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        var ref = plannedReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);

        var states = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        cloner(incus).prepareOne(container, repo, ref, states, 0,
                new java.util.concurrent.atomic.AtomicBoolean());

        verify(incus).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        assertEquals(BuildProgress.StepState.DONE, states.get(0).state());
        assertEquals("could not use the host reference: fatal: repository '/mnt/ref/repo' does not exist",
                states.get(0).note());
    }

    @Test
    void cloneReposThrowsWhenCloneFails() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(new IncusClient.ExecResult(128, "", "fatal: repository not found"));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/missing.git");
        repo.setPath("~/missing");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        var ex = assertThrows(dev.incusspawn.incus.IncusException.class,
                () -> cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(ex.getMessage().contains("fatal: repository not found"),
                "error message should carry the git failure detail");
    }

    // --- firstGitError ---

    @Test
    void firstGitErrorPrefersFatalLine() {
        var text = "Cloning into 'x'...\nremote: Counting objects\nfatal: could not read Username\nmore noise";
        assertEquals("fatal: could not read Username", RepoCloner.firstGitError(text));
    }

    @Test
    void firstGitErrorFallsBackToLastNonEmptyLine() {
        assertEquals("some trailing message",
                RepoCloner.firstGitError("first line\n\nsome trailing message\n"));
    }

    @Test
    void firstGitErrorEmptyForBlank() {
        assertEquals("", RepoCloner.firstGitError(""));
        assertEquals("", RepoCloner.firstGitError(null));
    }

    private static ImageDef projectLocal(String name, Path root) {
        var def = new ImageDef();
        def.setName(name);
        def.setSource(root.resolve(".incus-spawn/images/x.yaml").toString());
        def.setProjectRoot(root);
        return def;
    }

    private static ImageDef trusted(String name) {
        var def = new ImageDef();
        def.setName(name);
        return def;
    }

    @Test
    void projectLocalTemplatesNeverMountHostCheckouts() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        var imageDef = projectLocal("tpl-proj", Path.of("/work/repo"));
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        verify(cmd, never()).planReference(any(), any());
        verify(incus, never()).deviceAdd(any(), any(), any(), any(String[].class));
        verify(incus).execInContainer("test", "agentuser",
                "git clone -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
    }

    @Test
    void preparedClonesPrimeWhileAnotherHostPreparationIsStillRunning() {
        var incus = mock(IncusClient.class);
        var accessChecks = new java.util.concurrent.atomic.AtomicInteger();
        var primed = new java.util.concurrent.CountDownLatch(1);
        var slowStarted = new java.util.concurrent.CountDownLatch(1);
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            if (script.contains("ls-remote --symref")) accessChecks.incrementAndGet();
            if (script.contains("echo fast-prime")) {
                assertEquals(2, accessChecks.get(), "all guest access checks must precede primes");
                assertTrue(slowStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                primed.countDown();
            }
            return OK;
        });
        var cmd = cloner(incus);
        doCallRealMethod().when(cmd).checkTemplateAccess(any(), any(), any(), anyInt());
        doReturn(2).when(cmd).repoConcurrency(anyInt());
        var fast = repo("https://example.invalid/fast", "~/fast", "echo fast-prime");
        var slow = repo("https://example.invalid/slow", "~/slow", null);
        for (var repo : List.of(fast, slow)) {
            doReturn(new RepoCloner.RepoReference(repo.getUrl(), "/mnt/ref", null, null,
                    new dev.incusspawn.git.GitPreparation.Request(repo.getUrl(), null, null), null))
                    .when(cmd).planReference(eq(repo.getUrl()), any());
        }
        doAnswer(inv -> {
            assertEquals(2, accessChecks.get(), "all guest access checks must precede host preparation");
            ImageDef.RepoEntry repo = inv.getArgument(0);
            RepoCloner.RepoReference ref = inv.getArgument(1);
            if (repo == slow) {
                slowStarted.countDown();
                assertTrue(primed.await(5, java.util.concurrent.TimeUnit.SECONDS), "fast prime waited for slow preparation");
            }
            return reference(ref.deviceName(), ref.containerPath(), "/owned/snapshot", null);
        }).when(cmd).prepareReference(any(), any(), any(), anyInt(), any());
        var def = trusted("tpl-test");
        def.setRepos(List.of(fast, slow));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(15),
                () -> cmd.cloneRepos(new Container(incus, "test"), def, java.util.Map.of(), MachineType.CONTAINER));
        assertEquals(0, primed.getCount());
    }

    @Test
    void localUrlsNeverPlanAHostReference() {
        var cmd = new RepoCloner(mock(IncusClient.class));
        var config = new dev.incusspawn.config.SpawnConfig();
        for (var url : List.of("/home/user/private", "file:///home/user/private", "../private", "ext::cat /private")) {
            var ref = cmd.planReference(url, config);
            assertNotNull(ref);
            assertFalse(ref.hasDevice());
            assertNull(ref.request());
        }
    }

    @Test
    void noPrimeRunsWhileAHostReferenceIsMounted() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var mounted = new java.util.concurrent.atomic.AtomicInteger();
        var primesWhileMounted = new java.util.concurrent.atomic.AtomicInteger();
        var primes = new java.util.concurrent.atomic.AtomicInteger();
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenAnswer(inv -> {
            String cmdLine = inv.getArgument(2);
            if (cmdLine.contains("echo primed")) {
                primes.incrementAndGet();
                if (mounted.get() > 0) primesWhileMounted.incrementAndGet();
            }
            if (cmdLine.contains("git clone --no-hardlinks")) Thread.sleep(50); // slow local clones
            return OK;
        });
        doAnswer(inv -> { mounted.decrementAndGet(); return null; }).when(incus).deviceRemove(eq("test"), anyString());

        // More referenced repos than any worker limit, each with a prime: the waiting primes must
        // not starve the clones that detach the references.
        var repos = new java.util.ArrayList<ImageDef.RepoEntry>();
        for (int i = 0; i < 12; i++) {
            var repo = new ImageDef.RepoEntry();
            repo.setUrl("https://github.com/owner/repo" + i + ".git");
            repo.setPath("~/repo" + i);
            repo.setPrime("echo primed");
            repo.setDepth(1);
            repos.add(repo);
        }
        var imageDef = trusted("tpl-test");
        imageDef.setRepos(repos);

        var cmd = cloner(incus);
        doReturn(1).when(cmd).repoConcurrency(anyInt()); // one slot: 6 references > 1 worker
        for (int i = 0; i < 12; i++) {
            if (i % 2 == 1) continue; // half via host reference, half from the network
            var url = repos.get(i).getUrl();
            doReturn(new RepoCloner.RepoReference("ref-" + url.hashCode(), "/mnt/ref/" + url.hashCode(), "/host/repo", null))
                    .when(cmd).planReference(eq(url), any());
        }
        doAnswer(inv -> { mounted.incrementAndGet(); return null; })
                .when(incus).deviceAdd(eq("test"), anyString(), eq("disk"), any(String[].class));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),
                () -> cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertEquals(12, primes.get());
        assertEquals(0, primesWhileMounted.get());
        assertEquals(0, mounted.get());
    }

    @Test
    void failedDetachFailsTheBuildInsteadOfPriming() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        doThrow(new IncusException("busy")).when(incus).deviceRemove(eq("test"), anyString());
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setPrime("echo primed");
        var imageDef = trusted("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = cloner(incus);
        doReturn(plannedReference("ref-repo", "/mnt/ref/repo", "/host/repo", null))
                .when(cmd).planReference(eq(repo.getUrl()), any());

        assertThrows(RuntimeException.class, () -> cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        verify(incus, never()).execInContainer(eq("test"), eq("agentuser"), contains("echo primed"));
    }

    @Test
    void failedDetachDoesNotStrandAnAttachWaitingForASlot() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        var stillPlugged = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            if (stillPlugged.get()) throw new IncusClient.NoHotplugSlotException("No available PCI hotplug slots");
            return null;
        }).when(incus).deviceAdd(eq("test"), anyString(), eq("disk"), any(String[].class));
        doAnswer(inv -> { stillPlugged.set(true); throw new IncusException("busy"); })
                .when(incus).deviceRemove("test", "ref-0");
        var repos = new java.util.ArrayList<ImageDef.RepoEntry>();
        var cmd = cloner(incus);
        for (int i = 0; i < 2; i++) {
            var repo = new ImageDef.RepoEntry();
            repo.setUrl("https://github.com/owner/repo" + i + ".git");
            repo.setPath("~/repo" + i);
            repos.add(repo);
            doReturn(plannedReference("ref-" + i, "/mnt/ref/" + i, "/host/repo" + i, null))
                    .when(cmd).planReference(eq(repo.getUrl()), any());
        }
        var imageDef = trusted("tpl-test");
        imageDef.setRepos(repos);
        doReturn(1).when(cmd).repoConcurrency(anyInt()); // repo0 detaches (and fails) before repo1 attaches

        // The device that would not detach still holds its slot: repo1 runs out, and must give up, not wait.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),
                () -> assertThrows(RuntimeException.class, () -> cmd.cloneRepos(container, imageDef, java.util.Map.of(), MachineType.VM)));
    }
}
