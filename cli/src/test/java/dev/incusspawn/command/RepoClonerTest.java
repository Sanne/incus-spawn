package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.TerminalLink;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        String first = shown(() -> cmd.cloneRepos(new Container(incus, "test"), def, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(first.contains("via " + (reference ? "host reference" : "remote") + " (shallow, depth 1)"), first);
        assertTrue(first.matches("(?s).*releases: attach=[0-9]+ms, guest=[0-9]+ms.*"), first);
        assertTrue(first.matches("(?s).*other: attach=[0-9]+ms, guest=[0-9]+ms.*"), first);
        if (reference) assertTrue(first.matches("(?s).*releases: attach=[0-9]+ms, guest=[0-9]+ms, detach=[0-9]+ms.*"), first);
        def.setRepos(List.of(repo));
        String second = shown(() -> cmd.cloneRepos(new Container(incus, "test"), def, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(second.contains("releases: attach="), second);
        assertFalse(second.contains("other:"), "timings must not leak across builds");
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
        repo.setPrime(List.of("mvn -B dependency:go-offline"));

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
    void cloneReposRunsEachPrimeCommandInTurnAndStopsAtTheFirstFailure() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        when(incus.execInContainer("test", "agentuser", "cd '/home/agentuser/app' && ./gradlew dependencies"))
                .thenReturn(new IncusClient.ExecResult(1, "BUILD FAILED", ""));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/app.git");
        repo.setPath("~/app");
        repo.setPrime(List.of("mise install", "./gradlew dependencies", "echo never"));

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo));

        var ex = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));

        var inOrder = org.mockito.Mockito.inOrder(incus);
        inOrder.verify(incus).execInContainer("test", "agentuser", "cd '/home/agentuser/app' && mise install");
        inOrder.verify(incus).execInContainer("test", "agentuser", "cd '/home/agentuser/app' && ./gradlew dependencies");
        verify(incus, org.mockito.Mockito.never())
                .execInContainer("test", "agentuser", "cd '/home/agentuser/app' && echo never");
        assertTrue(ex.stepOutput().contains("BUILD FAILED"), ex.stepOutput());
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
                script.contains("clone --no-hardlinks") && script.contains("remote set-url origin")
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
                script.contains("clone --no-hardlinks --branch 'feature/x'")
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
        a.setPrime(List.of("build-alpha"));
        var b = new ImageDef.RepoEntry();
        b.setUrl("https://github.com/owner/beta.git");
        b.setPath("~/beta");
        b.setPrime(List.of("build-beta"));

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
        repo.setPrime(List.of("build-beta"));

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
        repo.setPrime(List.of("npm install"));

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

    @Test
    void aFailedPrimeNamesItsStepAndCommand() {
        // #1742: the summary and the failure report said "prepare", not which prime command failed.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        primeAnswers(incus, OK, new IncusClient.ExecResult(1, "ERROR: JAVA_HOME is not set\nlocation of your Java installation.", ""));

        var repo = repo("https://github.com/jbangdev/jbang", "~/jbang", null);
        repo.setPrime(List.of("echo ready", "./gradlew dependencies"));
        var imageDef = new ImageDef();
        imageDef.setName("tpl-jbang");
        imageDef.setRepos(List.of(repo));

        var ex = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(ex.getMessage().contains("jbang: prime `./gradlew dependencies` failed: location of your Java installation."),
                ex.getMessage());
        assertTrue(ex.stepOutput().contains("─── prime output: jbang ───\n$ ./gradlew dependencies\nERROR: JAVA_HOME"),
                ex.stepOutput());
        assertTrue(ex.stepOutput().contains("─── end prime output: jbang ───"), ex.stepOutput());
    }

    @Test
    void aFailedPrimesCommandOutlastsTheReportsTrim() {
        // The report keeps the last 100 lines of a prime's output, and the command above them.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var output = java.util.stream.IntStream.range(0, 150).mapToObj(i -> "line " + i)
                .collect(java.util.stream.Collectors.joining("\n"));
        primeAnswers(incus, new IncusClient.ExecResult(1, output, ""));

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", "npm install")));

        var ex = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(ex.stepOutput().contains("─── prime output: app ───\n$ npm install\nline 50\n"), ex.stepOutput());
        assertFalse(ex.stepOutput().contains("line 49\n"), ex.stepOutput());
    }

    @Test
    void aLongOrMultiLinePrimeIsShortenedOnTheFailureLineAndWholeInItsOutput() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        primeAnswers(incus, new IncusClient.ExecResult(1, "no such task", ""));

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app",
                "cd sub\n./gradlew --no-daemon dependencies --refresh-dependencies")));

        var ex = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(ex.getMessage().contains("app: prime `cd sub …` failed: no such task"), ex.getMessage());
        assertTrue(ex.stepOutput().contains("$ cd sub\n> ./gradlew --no-daemon dependencies --refresh-dependencies\nno such task"),
                ex.stepOutput());
        assertEquals("./gradlew --no-daemon dependencies --re…",
                RepoCloner.shortCommand("./gradlew --no-daemon dependencies --refresh-dependencies"));
    }

    @Test
    void aCarriageReturnInAPrimeCannotHideTheCommandThatRan() {
        // A terminal draws a carriage return's last frame alone: the command must not lose its start to one.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        primeAnswers(incus, new IncusClient.ExecResult(1, "ERR", ""));

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", "curl https://x | sh\r\033[2Knpm install\t-q")));

        var ex = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(ex.getMessage().contains("app: prime `curl https://x | sh npm install -q` failed: ERR"), ex.getMessage());
        assertTrue(ex.stepOutput().contains("$ curl https://x | sh npm install\t-q\nERR"), ex.stepOutput());
    }

    @Test
    void aPrimeWhoseExecThrowsSaysItWasThePrime() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        primeAnswers(incus, OK);
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenAnswer(invocation -> {
            String script = invocation.getArgument(2);
            if (script.startsWith("cd ")) throw new IllegalStateException();
            return OK;
        });

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", "npm install")));

        var ex = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertTrue(ex.getMessage().contains("app: prime failed: java.lang.IllegalStateException"), ex.getMessage());
    }

    @Test
    void aShortenedCommandKeepsWholeCharacters() {
        var command = "x".repeat(38) + "😀😀😀";
        assertEquals("x".repeat(38) + "😀…", RepoCloner.shortCommand(command));
    }

    private static final String PKIX = "Exception in thread \"main\" javax.net.ssl.SSLHandshakeException:"
            + " (certificate_unknown) PKIX path building failed: unable to find valid certification path";

    /** A container whose prime answers {@code primes} in turn; every other command succeeds. */
    private static List<String> primeAnswers(IncusClient incus, IncusClient.ExecResult... primes) {
        var calls = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        var next = new java.util.concurrent.atomic.AtomicInteger();
        when(incus.shellExec(anyString(), any(String[].class))).thenAnswer(inv -> {
            String[] command = java.util.Arrays.copyOfRange(inv.getArguments(), 1, inv.getArguments().length,
                    String[].class);
            calls.add("root: " + String.join(" ", command));
            return OK;
        });
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            calls.add(inv.getArgument(1) + ": " + script);
            return script.startsWith("cd ") ? primes[Math.min(next.getAndIncrement(), primes.length - 1)] : OK;
        });
        return calls;
    }

    @Test
    void aPrimeThatDownloadsItsOwnJdkRunsAgainOnceThatJdkTrustsTheIsxCa() {
        // #1746: `mise x -- ./gradlew dependencies` downloads a JDK and uses it at once, before the
        // isx-trust path unit has bound it, so the Gradle wrapper's download fails PKIX.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var calls = primeAnswers(incus, new IncusClient.ExecResult(1, PKIX, ""), OK);

        var imageDef = new ImageDef();
        imageDef.setName("isx-jbang");
        imageDef.setRepos(List.of(repo("https://github.com/jbangdev/jbang", "~/jbang",
                "mise x -- ./gradlew dependencies")));

        cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        var prime = "agentuser: cd '/home/agentuser/jbang' && mise x -- ./gradlew dependencies";
        assertEquals(2, calls.stream().filter(prime::equals).count(), String.join("\n", calls));
        int first = calls.indexOf(prime);
        int second = calls.lastIndexOf(prime);
        var between = String.join("\n", calls.subList(first, second));
        assertTrue(between.contains("root: /usr/local/bin/isx-trust /usr/lib/jvm /opt /usr/local"), between);
        assertTrue(between.contains("agentuser: /usr/local/bin/isx-trust '/home/agentuser/opt' "), between);
        assertTrue(between.contains("'/home/agentuser/.local/share/mise/installs/java'"), between);
        // The build installed the helper before the primes; another prime may be running it.
        assertFalse(between.contains("cat > '/usr/local/bin/isx-trust'"), between);
    }

    @Test
    void eachPrimeRunIsRecordedInTheBuildsCommandLog() {
        // #1742: the build's log keeps every prime command that ran, a retried one twice, and
        // none of isx's own commands around them (the clone, the trust bind)
        var incus = mock(IncusClient.class);
        var recorded = java.util.Collections.synchronizedList(new ArrayList<String>());
        var container = new Container(incus, "test", RecordingOutput.QUIET,
                (step, command, result, took) -> recorded.add(step + " | " + command + " | " + result.exitCode()));
        primeAnswers(incus, new IncusClient.ExecResult(1, PKIX, ""), OK);

        var repo = repo("https://github.com/jbangdev/jbang", "~/jbang", null);
        repo.setPrime(List.of("mise x -- ./gradlew dependencies", "echo done"));
        var imageDef = new ImageDef();
        imageDef.setName("isx-jbang");
        imageDef.setRepos(List.of(repo));

        cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER);

        assertEquals(List.of(
                "repo 'jbang' prime | cd '/home/agentuser/jbang' && mise x -- ./gradlew dependencies | 1",
                "repo 'jbang' prime | cd '/home/agentuser/jbang' && mise x -- ./gradlew dependencies | 0",
                "repo 'jbang' prime | cd '/home/agentuser/jbang' && echo done | 0"), recorded);
    }

    @Test
    void aBindThatThrowsKeepsWhyThePrimeFailed() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExec(anyString(), any(String[].class))).thenThrow(new IncusException("exec failed"));
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(2)).startsWith("cd ") ? new IncusClient.ExecResult(1, PKIX, "") : OK);

        var run = RepoCloner.runPrime(container, repo("https://github.com/owner/app.git", "~/app", "./fetch-deps"), () -> {});

        assertFalse(run.success());
        assertTrue(run.output().contains("PKIX path building failed"), run.output());
        assertTrue(run.label().contains("exec failed"), run.label());
    }

    @Test
    void aSecondPrimeRunsLabelIsScrubbedAsItsLogIs() {
        // update-all quotes the label with no StepProgress: a key the first run began and the
        // second continued is marked in the log, so it is in the label too
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var armour = "-----";
        primeAnswers(incus, new IncusClient.ExecResult(1, PKIX + "\n" + armour + "BEGIN PRIVATE KEY" + armour + "\nMIIEfirst", ""),
                new IncusClient.ExecResult(1, "MIIElast-body", ""));

        var run = RepoCloner.runPrime(container, repo("https://github.com/owner/app.git", "~/app", "./fetch-deps"), () -> {});

        assertFalse(run.success());
        assertEquals("<isx:redacted:private-key>", run.label());
    }

    @Test
    void aTemplateWithoutTheHelperIsNotPrimedAgainAsIfItHadBound() {
        // isx update-all --prime on a template built before isx-trust existed.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExec(anyString(), any(String[].class)))
                .thenReturn(new IncusClient.ExecResult(127, "", "/usr/local/bin/isx-trust: not found"));
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(2)).startsWith("cd ") ? new IncusClient.ExecResult(1, PKIX, "") : OK);

        var run = RepoCloner.runPrime(container, repo("https://github.com/owner/app.git", "~/app", "./fetch-deps"), () -> {});

        assertFalse(run.success());
        assertTrue(run.label().contains("rebuild"), run.label());
        assertTrue(run.output().contains("not found"), run.output());
        verify(incus, times(1)).execInContainer("test", "agentuser", "cd '/home/agentuser/app' && ./fetch-deps");
    }

    @Test
    void onlyTheListedPrimeCommandThatFailedUntrustedRunsAgain() {
        // A prime that is a list: the commands before the failing one succeeded and do not run
        // again; the bind and the second try are for the failing command, and the list goes on.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var calls = primeAnswers(incus, OK);
        var download = "cd '/home/agentuser/jbang' && mise x -- ./gradlew dependencies";
        var downloads = new java.util.concurrent.atomic.AtomicInteger();
        when(incus.execInContainer("test", "agentuser", download)).thenAnswer(inv -> {
            calls.add("agentuser: " + download);
            return downloads.incrementAndGet() == 1 ? new IncusClient.ExecResult(1, PKIX, "") : OK;
        });
        calls.clear(); // stubbing ran the default answer
        var repo = repo("https://github.com/jbangdev/jbang", "~/jbang", null);
        repo.setPrime(List.of("mise install", "mise x -- ./gradlew dependencies", "echo after"));

        var run = RepoCloner.runPrime(container, repo, () -> {});

        assertTrue(run.success(), run.label());
        assertEquals(List.of(
                "agentuser: cd '/home/agentuser/jbang' && mise install",
                "agentuser: " + download,
                "root: /usr/local/bin/isx-trust /usr/lib/jvm /opt /usr/local"),
                calls.subList(0, 3), String.join("\n", calls));
        assertTrue(calls.get(3).startsWith("agentuser: /usr/local/bin/isx-trust '/home/agentuser/opt' "), calls.get(3));
        assertEquals(List.of("agentuser: " + download, "agentuser: cd '/home/agentuser/jbang' && echo after"),
                calls.subList(4, calls.size()), String.join("\n", calls));
    }

    @Test
    void aPrimeThatFailsForAnotherReasonRunsOnce() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var calls = primeAnswers(incus, new IncusClient.ExecResult(1, "npm ERR! peer dep missing", ""), OK);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", "npm install")));

        assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertEquals(1, calls.stream().filter(c -> c.endsWith("npm install")).count(), String.join("\n", calls));
        assertTrue(calls.stream().noneMatch(c -> c.contains("isx-trust")), String.join("\n", calls));
    }

    @Test
    void aPrimeStillUntrustedAfterTheBindSaysToRunIsxTrustOnItsRuntime() {
        // A runtime unpacked outside the landing directories is not bound by a bare isx-trust.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var calls = primeAnswers(incus, new IncusClient.ExecResult(1, PKIX, ""),
                new IncusClient.ExecResult(1, "", "ssl.SSLCertVerificationError: CERTIFICATE_VERIFY_FAILED"));

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", "./fetch-deps")));

        var ex = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER));
        assertEquals(2, calls.stream().filter(c -> c.endsWith("./fetch-deps")).count(), String.join("\n", calls));
        assertTrue(ex.getMessage().contains(RepoCloner.STILL_UNTRUSTED), ex.getMessage());
        assertFalse(ex.getMessage().contains("gradlew --stop"), "the advice is in the output: " + ex.getMessage());
        assertTrue(ex.stepOutput().contains("isx-trust <dir>"), ex.stepOutput());
        assertTrue(ex.stepOutput().contains("./gradlew --stop"), ex.stepOutput());
        assertTrue(ex.stepOutput().contains("CERTIFICATE_VERIFY_FAILED"), ex.stepOutput());
        assertTrue(ex.stepOutput().contains("PKIX path building failed"), "and the first run's: " + ex.stepOutput());
    }

    @Test
    void failedPrimeOutputCannotDriveTheTerminal() {
        // A prime is code the repository controls: what it prints is shown as text (#1588).
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(OK)  // clone
                .thenReturn(OK)  // refspec restore
                .thenReturn(new IncusClient.ExecResult(1,
                        "\033]0;pwned\007\033[2J\033[31mred\r\n\033]52;c;cHduZWQ=\007\u009b2Jtail\tend", ""));

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", "exit 1")));

        // Off a terminal the failure line, with the prime's last line, is a warning, and so is its log.
        var thrown = new IncusException[1];
        var shown = shown(() -> thrown[0] = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER)));
        var ex = thrown[0];
        assertTrue(shown.contains("red"), shown);
        // The log is a warning's lines now, made safe as a terminal prints them, a tab as a space
        assertTrue(shown.contains("tail end"), shown);
        assertTrue(shown.contains("Preparing app failed: prime `exit 1` failed: tail"), "the last line is on the failure line: " + shown);
        assertEquals(TerminalLink.plainText(shown).replace("\033", "ESC"), shown.replace("\033", "ESC"),
                "the prime's escapes must not reach the terminal");
        // The failure report a person is pointed to and may cat.
        assertEquals(TerminalLink.plainText(ex.stepOutput()), ex.stepOutput());
        assertEquals(OutputFormat.oneLine(ex.getMessage().replace("\n", " ")), ex.getMessage().replace("\n", " "));
    }

    @Test
    void aRepositorysLabelCannotDriveTheTerminal() {
        // A template, project-local included, decides a repository's URL, and so its label (#1610).
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(OK)  // clone
                .thenReturn(OK)  // refspec restore
                .thenReturn(new IncusClient.ExecResult(1, "prime failed", ""));

        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/\033]0;pwned\007\033[2Japp.git", "~/app", "exit 1")));

        var thrown = new IncusException[1];
        var shown = shown(() -> thrown[0] = assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER)));
        var ex = thrown[0];
        assertTrue(shown.contains("prime output:  ]0;pwned  [2Japp"), shown);
        assertEquals(TerminalLink.plainText(shown).replace("\033", "ESC"), shown.replace("\033", "ESC"),
                "the label's escapes must not reach the terminal");
        assertEquals(TerminalLink.plainText(ex.stepOutput()), ex.stepOutput());
        assertEquals(OutputFormat.oneLine(ex.getMessage().replace("\n", " ")), ex.getMessage().replace("\n", " "));
    }

    private static ImageDef.RepoEntry repo(String url, String path, String prime) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url);
        repo.setPath(path);
        repo.setPrime(prime == null ? List.of() : List.of(prime));
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
    void aGitErrorIsScrubbedAcrossBothStreamsAsTheLogIs() {
        // The host-reference fallback note has no log to take its line from: a key's BEGIN header
        // on stdout still marks its body on stderr (#1893 review)
        var armour = "-----";
        var split = new IncusClient.ExecResult(1, armour + "BEGIN PRIVATE KEY" + armour + "\nMIIEfirst",
                "MIIElast-body\n");
        assertEquals("<isx:redacted:private-key>", RepoCloner.gitError(split));
        // Git's fatal: line still wins over stdout's noise
        assertEquals("fatal: repository not found",
                RepoCloner.gitError(new IncusClient.ExecResult(128, "Cloning into 'x'...", "fatal: repository not found\n")));
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
            if (cmdLine.contains("clone --no-hardlinks")) Thread.sleep(50); // slow local clones
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
            repo.setPrime(List.of("echo primed"));
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
        repo.setPrime(List.of("echo primed"));
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

    /**
     * Off a terminal a failed prime is a warning saying so, as it ends, and its log one warning,
     * which the TUI's log and a recorder keep, where it was raw stderr between two rules (#1703).
     */
    @Test
    void aFailedPrimesLogIsOneWarning() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(OK)  // clone
                .thenReturn(OK)  // refspec restore
                .thenReturn(new IncusClient.ExecResult(1, "building\nboom", ""));
        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", "exit 1")));

        var recorded = RecordingOutput.during(() -> assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, java.util.Map.of(), MachineType.CONTAINER)));

        assertTrue(recorded.events(RecordingOutput.Kind.STEP).stream()
                .noneMatch(e -> e.msg().startsWith("Preparing app failed")), "a failure is no progress line");
        assertTrue(recorded.events(RecordingOutput.Kind.WARN).stream()
                .anyMatch(e -> e.msg().equals("Preparing app failed: prime `exit 1` failed: boom")), recorded.events().toString());
        assertEquals(List.of(List.of("$ exit 1", "building", "boom")), recorded.events(RecordingOutput.Kind.WARN)
                .stream().filter(e -> e.msg().equals("prime output: app")).map(RecordingOutput.Event::details).toList());
    }

    /**
     * A progress bar's {@code \r} frames are one line in a failed prime's warning, its last frame,
     * so they cannot push why it failed out of the lines shown; a long command shows its start.
     */
    @Test
    void aFailedPrimesWarningShowsTheLastFrameAndTheCommandsStart() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var bar = new StringBuilder();
        for (int i = 0; i <= 100; i++) bar.append("\rReceiving objects: ").append(i).append('%');
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(OK)  // clone
                .thenReturn(OK)  // refspec restore
                .thenReturn(new IncusClient.ExecResult(1, bar + "\nboom", ""));
        var command = new StringBuilder("true");
        for (int i = 1; i <= 15; i++) command.append("\necho ").append(i);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setRepos(List.of(repo("https://github.com/owner/app.git", "~/app", command.toString())));

        var recorded = RecordingOutput.during(() -> assertThrows(IncusException.class,
                () -> cloner(incus).cloneRepos(container, imageDef, Map.of(), MachineType.CONTAINER)));

        var details = recorded.events(RecordingOutput.Kind.WARN).stream()
                .filter(e -> e.msg().equals("prime output: app")).map(RecordingOutput.Event::details)
                .findFirst().orElseThrow(() -> new AssertionError(recorded.events().toString()));
        var expected = new ArrayList<>(List.of("$ true"));
        for (int i = 1; i <= 9; i++) expected.add("> echo " + i);
        expected.add("(6 more lines of the command not shown)");
        expected.add("Receiving objects: 100%");
        expected.add("boom");
        assertEquals(expected, details);
    }

    /** What {@code work} reported, every kind of message, one per line. */
    static String shown(RecordingOutput.Work work) {
        return String.join("\n", RecordingOutput.during(work).lines());
    }
}
